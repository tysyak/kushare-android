//! Puente UniFFI entre el core Rust de kushare (protocolo UKEY2, framing,
//! transferencia — el mismo código probado en el cliente Linux) y la app
//! Android en Kotlin. La UI, permisos y descubrimiento mDNS (vía
//! `NsdManager`) viven del lado Kotlin; aquí solo se expone lo necesario
//! para conducir el handshake y mover los bytes.

use kushare_core::TransferEvent;
use kushare_transfer::{Receiver, Sender, TransferSession, handle_connection};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Mutex;
use tokio::sync::mpsc;

mod bt_bridge;
mod link;
pub mod features;
pub use bt_bridge::ByteStreamIo;
pub use link::{LinkCallback, LinkService};
use bt_bridge::CallbackStream;

uniffi::setup_scaffolding!();

pub(crate) static RUNTIME: std::sync::LazyLock<tokio::runtime::Runtime> = std::sync::LazyLock::new(|| {
    tokio::runtime::Runtime::new().expect("no se pudo crear el runtime de tokio")
});

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MobileError {
    // El campo no puede llamarse `message`: UniFFI genera una excepción
    // Kotlin donde eso chocaría con `Throwable.message` (propiedad
    // heredada), y ni siquiera compilaría el lado Kotlin.
    #[error("{reason}")]
    Failed { reason: String },
}

impl From<anyhow::Error> for MobileError {
    fn from(err: anyhow::Error) -> Self {
        Self::Failed {
            reason: err.to_string(),
        }
    }
}

/// Genera un `endpoint_id` de 4 caracteres, igual que en el cliente Linux.
#[uniffi::export]
pub fn random_endpoint_id() -> String {
    kushare_core::random_endpoint_id()
}

// ---------------------------------------------------------------------
// Envío
// ---------------------------------------------------------------------

#[uniffi::export(callback_interface)]
pub trait SendProgressCallback: Send + Sync {
    fn on_progress(&self, bytes_transferred: u64, total_bytes: u64);
}

/// Conecta a `addr` (IP:puerto de otro kushare descubierto por mDNS del
/// lado Kotlin) y envía `file_path`, reportando progreso por `callback`.
#[uniffi::export]
pub async fn send_file(
    addr: String,
    my_endpoint_id: String,
    my_device_name: String,
    file_path: String,
    callback: Box<dyn SendProgressCallback>,
) -> Result<(), MobileError> {
    RUNTIME
        .spawn(async move {
            let addr: SocketAddr = addr
                .parse()
                .map_err(|e| anyhow::anyhow!("dirección inválida {addr:?}: {e}"))?;
            let path = PathBuf::from(file_path);

            let (events_tx, mut events_rx) = mpsc::channel::<TransferEvent>(32);
            let progress_task = tokio::spawn(async move {
                while let Some(event) = events_rx.recv().await {
                    if let TransferEvent::Progress {
                        bytes_transferred,
                        total_bytes,
                        ..
                    } = event
                    {
                        callback.on_progress(bytes_transferred, total_bytes);
                    }
                }
            });

            let mut session = Sender::connect(addr).await?;
            let result = Sender::send_file(
                &mut session,
                &my_endpoint_id,
                &my_device_name,
                &path,
                events_tx,
            )
            .await;
            let _ = progress_task.await;
            result
        })
        .await
        .map_err(|join_err| anyhow::anyhow!("tarea de envío interrumpida: {join_err}"))??;
    Ok(())
}

/// Igual que [`send_file`] pero sobre un `BluetoothSocket` clásico ya
/// conectado del lado Kotlin (`io`), en vez de una dirección IP:puerto.
/// Pensado para redes donde el PC y el Android no tienen conectividad IP
/// entre sí (ver `kushare_transfer::bt` en el repo `kushare` para el lado
/// Linux de este mismo transporte).
#[uniffi::export]
pub async fn send_file_bt(
    io: Box<dyn ByteStreamIo>,
    my_endpoint_id: String,
    my_device_name: String,
    file_path: String,
    callback: Box<dyn SendProgressCallback>,
) -> Result<(), MobileError> {
    RUNTIME
        .spawn(async move {
            let stream = CallbackStream::new(io);
            let path = PathBuf::from(file_path);

            let (events_tx, mut events_rx) = mpsc::channel::<TransferEvent>(32);
            let progress_task = tokio::spawn(async move {
                while let Some(event) = events_rx.recv().await {
                    if let TransferEvent::Progress {
                        bytes_transferred,
                        total_bytes,
                        ..
                    } = event
                    {
                        callback.on_progress(bytes_transferred, total_bytes);
                    }
                }
            });

            let mut session = TransferSession::handshake_as_initiator(stream).await?;
            let result = Sender::send_file(
                &mut session,
                &my_endpoint_id,
                &my_device_name,
                &path,
                events_tx,
            )
            .await;
            let _ = progress_task.await;
            result
        })
        .await
        .map_err(|join_err| anyhow::anyhow!("tarea de envío Bluetooth interrumpida: {join_err}"))??;
    Ok(())
}

/// Atiende una única conexión Bluetooth ya aceptada del lado Kotlin (`io`,
/// resultado de `BluetoothServerSocket.accept()`): conduce el handshake
/// UKEY2 como respondedor y recibe el archivo que transfiera el peer.
///
/// A diferencia de [`start_receiver`] (TCP), aquí el bucle de aceptación
/// vive en Kotlin (`BluetoothServerSocket.accept()` es bloqueante, como el
/// resto de la API clásica de Bluetooth en Android): por cada conexión
/// entrante aceptada, Kotlin llama a esta función una vez, normalmente
/// desde una corrutina/hilo distinto para no bloquear la aceptación de la
/// siguiente conexión.
#[uniffi::export]
pub async fn receive_over_bt(
    io: Box<dyn ByteStreamIo>,
    download_dir: String,
    callback: Box<dyn ReceiveCallback>,
) -> Result<(), MobileError> {
    RUNTIME
        .spawn(async move {
            let stream = CallbackStream::new(io);
            let (events_tx, mut events_rx) = mpsc::channel::<TransferEvent>(64);

            let forward_task = tokio::spawn(async move {
                while let Some(event) = events_rx.recv().await {
                    match event {
                        TransferEvent::IncomingRequest {
                            device_name,
                            file_name,
                            file_size,
                        } => callback.on_incoming_request(device_name, file_name, file_size),
                        TransferEvent::Completed {
                            device_name,
                            file_name,
                            saved_to,
                        } => callback.on_completed(
                            device_name,
                            file_name,
                            saved_to.to_string_lossy().to_string(),
                        ),
                        TransferEvent::Failed { file_name, reason } => {
                            callback.on_failed(file_name, reason)
                        }
                        TransferEvent::Progress { .. } => {}
                    }
                }
            });

            let result = handle_connection(stream, PathBuf::from(download_dir), events_tx).await;
            let _ = forward_task.await;
            result
        })
        .await
        .map_err(|join_err| {
            anyhow::anyhow!("tarea de recepción Bluetooth interrumpida: {join_err}")
        })??;
    Ok(())
}

// ---------------------------------------------------------------------
// Recepción
// ---------------------------------------------------------------------

#[uniffi::export(callback_interface)]
pub trait ReceiveCallback: Send + Sync {
    fn on_incoming_request(&self, device_name: String, file_name: String, file_size: u64);
    fn on_completed(&self, device_name: String, file_name: String, saved_to: String);
    fn on_failed(&self, file_name: String, reason: String);
}

/// Handle de un receptor corriendo en segundo plano. Mientras exista (no
/// se haya llamado `stop()` ni se haya destruido), sigue aceptando
/// conexiones entrantes en `local_port()`.
#[derive(uniffi::Object)]
pub struct ReceiverHandle {
    port: u16,
    abort: Mutex<Option<tokio::task::AbortHandle>>,
}

#[uniffi::export]
impl ReceiverHandle {
    pub fn local_port(&self) -> u16 {
        self.port
    }

    /// Deja de aceptar nuevas conexiones. Las transferencias ya en curso
    /// no se interrumpen abruptamente (cada una corre en su propia tarea).
    pub fn stop(&self) {
        if let Some(handle) = self.abort.lock().expect("mutex envenenado").take() {
            handle.abort();
        }
    }
}

/// Empieza a escuchar transferencias entrantes en `port` (0 = que el
/// sistema operativo elija uno libre) y a guardar los archivos recibidos
/// en `download_dir`. Los eventos de la transferencia se reportan por
/// `callback`.
#[uniffi::export]
pub async fn start_receiver(
    port: u16,
    download_dir: String,
    callback: Box<dyn ReceiveCallback>,
) -> Result<std::sync::Arc<ReceiverHandle>, MobileError> {
    let handle = RUNTIME
        .spawn(async move {
            let (events_tx, mut events_rx) = mpsc::channel::<TransferEvent>(64);
            let receiver = Receiver::bind(port, PathBuf::from(download_dir), events_tx).await?;
            let local_port = receiver
                .local_addr()
                .map(|addr| addr.port())
                .unwrap_or(port);

            tokio::spawn(async move {
                while let Some(event) = events_rx.recv().await {
                    match event {
                        TransferEvent::IncomingRequest {
                            device_name,
                            file_name,
                            file_size,
                        } => callback.on_incoming_request(device_name, file_name, file_size),
                        TransferEvent::Completed {
                            device_name,
                            file_name,
                            saved_to,
                        } => callback.on_completed(
                            device_name,
                            file_name,
                            saved_to.to_string_lossy().to_string(),
                        ),
                        TransferEvent::Failed { file_name, reason } => {
                            callback.on_failed(file_name, reason)
                        }
                        TransferEvent::Progress { .. } => {}
                    }
                }
            });

            let run_task = tokio::spawn(receiver.run());
            let abort = run_task.abort_handle();

            anyhow::Ok(ReceiverHandle {
                port: local_port,
                abort: Mutex::new(Some(abort)),
            })
        })
        .await
        .map_err(|join_err| anyhow::anyhow!("tarea de recepción interrumpida: {join_err}"))??;

    Ok(std::sync::Arc::new(handle))
}
