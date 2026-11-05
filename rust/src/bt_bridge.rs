//! Puente entre un `BluetoothSocket` clásico de Android (expuesto a Kotlin
//! como `InputStream`/`OutputStream` bloqueantes, ver [`ByteStreamIo`]) y
//! el `AsyncRead + AsyncWrite` que espera `kushare_transfer` (el mismo
//! handshake UKEY2 y framing que usa el transporte TCP del cliente Linux,
//! y el transporte RFCOMM vía `bluer` del propio cliente Linux — ver
//! `kushare_transfer::bt` en el repo `kushare`).
//!
//! Android no tiene equivalente a `bluer` (que es específico de
//! BlueZ/D-Bus en Linux de escritorio), así que el descubrimiento y la
//! conexión RFCOMM los hace Kotlin con las APIs nativas de
//! `BluetoothSocket`/`BluetoothServerSocket`; aquí solo se adapta el flujo
//! de bytes ya conectado a lo que necesita el core async en Rust.
//!
//! [`ByteStreamIo::read`]/[`ByteStreamIo::write`] son llamadas síncronas
//! (bloqueantes) hacia Kotlin vía UniFFI, así que no pueden invocarse
//! directamente desde una tarea de tokio sin bloquear ese hilo del
//! runtime. En vez de eso, [`CallbackStream`] arranca dos hilos de SO
//! dedicados (uno de lectura, uno de escritura) que llaman al callback de
//! forma bloqueante y se comunican con el lado async a través de canales
//! de tokio.

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::{mpsc, oneshot};

/// Lado Kotlin de un `BluetoothSocket` ya conectado (como cliente que
/// marcó `connect()`, o como servidor que aceptó una conexión entrante en
/// `BluetoothServerSocket.accept()`). Cada método bloquea el hilo que lo
/// llama hasta completar la operación de E/S real, igual que
/// `InputStream.read`/`OutputStream.write` en Java.
#[uniffi::export(callback_interface)]
pub trait ByteStreamIo: Send + Sync {
    /// Lee hasta `max_len` bytes. Un resultado vacío indica fin de stream
    /// (EOF) o un error de E/S; `kushare_transfer` ya trata cualquier EOF
    /// inesperado a mitad de handshake/transferencia como fallo, así que
    /// no hace falta distinguirlos aquí.
    fn read(&self, max_len: u32) -> Vec<u8>;

    /// Escribe todos los bytes de `data`. `false` indica que falló (socket
    /// cerrado o error de E/S).
    fn write(&self, data: Vec<u8>) -> bool;

    /// Cierra el socket. Lo llama [`CallbackStream`] al soltarse, para
    /// desbloquear el hilo que espera en `read` (sin esto, un enlace
    /// cerrado desde Rust dejaría el `BluetoothSocket` abierto).
    fn close(&self);
}

struct WriteJob {
    data: Vec<u8>,
    ack: oneshot::Sender<io::Result<()>>,
}

/// Adaptador `AsyncRead + AsyncWrite` sobre un [`ByteStreamIo`]. Ver la
/// documentación del módulo para el porqué de los hilos dedicados.
pub struct CallbackStream {
    io: Arc<dyn ByteStreamIo>,
    read_rx: mpsc::Receiver<Vec<u8>>,
    read_buf: Vec<u8>,
    read_pos: usize,
    write_tx: mpsc::UnboundedSender<WriteJob>,
    pending_write: Option<oneshot::Receiver<io::Result<()>>>,
}

impl CallbackStream {
    pub fn new(io: Box<dyn ByteStreamIo>) -> Self {
        let io: Arc<dyn ByteStreamIo> = Arc::from(io);

        let (read_tx, read_rx) = mpsc::channel::<Vec<u8>>(4);
        let reader_io = io.clone();
        std::thread::spawn(move || {
            loop {
                let chunk = reader_io.read(64 * 1024);
                let eof = chunk.is_empty();
                if read_tx.blocking_send(chunk).is_err() || eof {
                    break;
                }
            }
        });

        let io_for_close = io.clone();
        let (write_tx, mut write_rx) = mpsc::unbounded_channel::<WriteJob>();
        std::thread::spawn(move || {
            while let Some(job) = write_rx.blocking_recv() {
                let ok = io.write(job.data);
                let result = if ok {
                    Ok(())
                } else {
                    Err(io::Error::new(
                        io::ErrorKind::BrokenPipe,
                        "escritura Bluetooth falló",
                    ))
                };
                let failed = result.is_err();
                let _ = job.ack.send(result);
                if failed {
                    break;
                }
            }
        });

        Self {
            io: io_for_close,
            read_rx,
            read_buf: Vec::new(),
            read_pos: 0,
            write_tx,
            pending_write: None,
        }
    }
}

impl Drop for CallbackStream {
    fn drop(&mut self) {
        self.io.close();
    }
}

impl AsyncRead for CallbackStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        loop {
            if this.read_pos < this.read_buf.len() {
                let n = std::cmp::min(buf.remaining(), this.read_buf.len() - this.read_pos);
                buf.put_slice(&this.read_buf[this.read_pos..this.read_pos + n]);
                this.read_pos += n;
                return Poll::Ready(Ok(()));
            }
            match this.read_rx.poll_recv(cx) {
                Poll::Ready(Some(chunk)) => {
                    if chunk.is_empty() {
                        // EOF: no se llena `buf`, que es como AsyncRead señala fin de stream.
                        return Poll::Ready(Ok(()));
                    }
                    this.read_buf = chunk;
                    this.read_pos = 0;
                }
                Poll::Ready(None) => return Poll::Ready(Ok(())), // canal cerrado: también EOF
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl AsyncWrite for CallbackStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.pending_write.is_none() {
            let (ack_tx, ack_rx) = oneshot::channel();
            if this
                .write_tx
                .send(WriteJob {
                    data: data.to_vec(),
                    ack: ack_tx,
                })
                .is_err()
            {
                return Poll::Ready(Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "el hilo de escritura Bluetooth ya había terminado",
                )));
            }
            this.pending_write = Some(ack_rx);
        }

        let rx = this.pending_write.as_mut().expect("se acaba de asignar");
        match Pin::new(rx).poll(cx) {
            Poll::Ready(Ok(Ok(()))) => {
                this.pending_write = None;
                Poll::Ready(Ok(data.len()))
            }
            Poll::Ready(Ok(Err(err))) => {
                this.pending_write = None;
                Poll::Ready(Err(err))
            }
            Poll::Ready(Err(_)) => {
                this.pending_write = None;
                Poll::Ready(Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "el hilo de escritura Bluetooth se cerró sin confirmar",
                )))
            }
            Poll::Pending => Poll::Pending,
        }
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        // Cada escritura ya se confirma de forma síncrona antes de que
        // `poll_write` complete, así que no hay nada pendiente que vaciar.
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}
