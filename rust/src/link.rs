//! Puente UniFFI del enlace persistente (`kushare_link`) para Android: el
//! mismo protocolo, emparejado, persistencia y sincronización que usa el
//! escritorio. Kotlin aporta lo que es específico de Android:
//! descubrimiento mDNS (`NsdManager`, se informa con
//! [`LinkService::note_sighting`]), los sockets Bluetooth
//! ([`LinkService::accept_bt`] / [`LinkService::connect_bt`]), la captura
//! de notificaciones (`NotificationListenerService` →
//! [`LinkService::post_local_notification`]) y mostrarlas
//! ([`LinkCallback::on_notification_posted`]). Las reconexiones por TCP las
//! hace el propio Rust.

use kushare_link::{
    ConnectIntent, DialFuture, Dialer, Direction, LinkConfig, LinkEvent, LinkManager, LinkStream, LocalDevice,
    NotificationAction, NotificationData, PeerRecord, Transport,
};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};

use crate::bt_bridge::{ByteStreamIo, CallbackStream};
use crate::features::{
    self, MobileAudioOutput, MobileBattery, MobileCallLogEntry, MobileContact, MobilePhoto, MobilePlayer, MobileSms,
    MobileSmsThread, SharedCache,
};
use kushare_link::pb::{self, feature::Kind as F};
use crate::{MobileError, RUNTIME};

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileAction {
    pub id: String,
    pub label: String,
    pub is_reply: bool,
}

/// Notificación de ESTE teléfono, para reenviar.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileNotification {
    pub key: String,
    pub app_id: String,
    pub app_name: String,
    pub title: String,
    pub text: String,
    pub timestamp_ms: i64,
    pub icon_png: Option<Vec<u8>>,
    pub actions: Vec<MobileAction>,
}

/// Notificación de un peer, lista para mostrarse.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileRemoteNotification {
    pub device_id: String,
    pub device_name: String,
    pub key: String,
    pub app_id: String,
    pub app_name: String,
    pub title: String,
    pub text: String,
    pub timestamp_ms: i64,
    pub icon_path: Option<String>,
    pub actions: Vec<MobileAction>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileApp {
    pub app_id: String,
    pub name: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobilePeer {
    pub device_id: String,
    pub device_name: String,
    pub device_kind: String,
    pub connected: bool,
    pub transport: Option<String>,
    /// Enlace de datos por Wi-Fi abierto ahora (modo híbrido), si hay.
    pub bulk_transport: Option<String>,
    /// El PC comprobó que nos alcanza por Wi-Fi (modo híbrido listo).
    pub wifi_ready: bool,
    pub bt_address: Option<String>,
    /// ¿Se reenvían las notificaciones del teléfono a este peer?
    pub outgoing_enabled: bool,
    /// ¿Se muestran en el teléfono las notificaciones del peer?
    pub incoming_enabled: bool,
    pub outgoing_blocked: Vec<String>,
    pub incoming_blocked: Vec<String>,
    pub peer_apps: Vec<MobileApp>,
}

/// Eventos del enlace hacia Kotlin. Se llaman desde hilos del runtime de
/// Rust (no el hilo principal de Android).
#[uniffi::export(callback_interface)]
pub trait LinkCallback: Send + Sync {
    fn on_pairing_requested(&self, device_id: String, device_name: String, pin: String, initiated_locally: bool);
    fn on_pairing_finished(&self, device_id: String, device_name: String, accepted: bool);
    /// Cambió algo de la lista de peers (conexión, configuración, …).
    fn on_peers_changed(&self);
    fn on_notification_posted(&self, notification: MobileRemoteNotification);
    fn on_notification_removed(&self, device_id: String, key: String);
    /// El peer descartó el espejo de una notificación del teléfono:
    /// cancelar la original.
    fn on_dismiss_local(&self, device_id: String, key: String);
    fn on_action_requested(&self, device_id: String, key: String, action_id: String);
    fn on_reply_requested(&self, device_id: String, key: String, action_id: String, text: String);

    // --- Funciones extra (ver `features.rs`) ---
    fn on_peer_battery(&self, device_id: String, battery: MobileBattery);
    fn on_peer_players(&self, device_id: String, players: Vec<MobilePlayer>);
    fn on_peer_audio_outputs(&self, device_id: String, selectable: bool, outputs: Vec<MobileAudioOutput>);
    /// El peer pide controlar un reproductor del teléfono (`player_id`
    /// vacío = todos). `action`: play-pause|play|pause|next|previous|stop.
    fn on_media_command(&self, device_id: String, player_id: String, action: String);
    fn on_set_audio_output(&self, device_id: String, output_id: String);
    /// `mode`: normal|vibrate|silent.
    fn on_set_ringer(&self, device_id: String, mode: String);
    /// `action`: answer|reject|hangup|mute|unmute.
    fn on_call_command(&self, device_id: String, action: String);
    /// Responder con [`LinkService::send_photo_list`]. `have_thumbnails`:
    /// ids cuya miniatura ya tiene el PC (mandarlas sin miniatura).
    fn on_photo_list_request(
        &self,
        device_id: String,
        request_id: u64,
        offset: u32,
        limit: u32,
        have_thumbnails: Vec<String>,
    );
    /// Responder con [`LinkService::send_photo_data`] (uno o varios trozos
    /// de como mucho `chunk_size` bytes). `background`: precarga, mandarla
    /// con `background = true` para no frenar el resto del enlace.
    fn on_photo_request(&self, device_id: String, request_id: u64, photo_id: String, chunk_size: u32, background: bool);
    /// El PC copió texto: ponerlo en el portapapeles del teléfono.
    fn on_clipboard(&self, device_id: String, text: String);
    /// Responder con [`LinkService::send_sms_threads`].
    fn on_sms_threads_request(&self, device_id: String, request_id: u64, offset: u32, limit: u32);
    /// Responder con [`LinkService::send_sms_messages`].
    fn on_sms_messages_request(&self, device_id: String, request_id: u64, thread_id: String, limit: u32, before_ms: i64);
    /// Mandar un SMS; responder con [`LinkService::send_command_result`].
    fn on_send_sms(&self, device_id: String, request_id: u64, address: String, body: String);
    /// Responder con [`LinkService::send_call_log`].
    fn on_call_log_request(&self, device_id: String, request_id: u64, limit: u32);
    /// Responder con [`LinkService::send_contacts`].
    fn on_contacts_request(&self, device_id: String, request_id: u64);
    /// Llamar; responder con [`LinkService::send_command_result`].
    fn on_dial(&self, device_id: String, request_id: u64, number: String);
    /// Responder con [`LinkService::send_active_notifications`].
    fn on_active_notifications_request(&self, device_id: String, request_id: u64, have_icons: Vec<String>);
    /// Responder con [`LinkService::send_wallpaper`].
    fn on_wallpaper_request(&self, device_id: String, request_id: u64, max_size: u32);
    /// Borrar la foto; responder con [`LinkService::send_command_result`].
    fn on_photo_delete(&self, device_id: String, request_id: u64, photo_id: String);
}

const TCP_DIAL_TIMEOUT: Duration = Duration::from_secs(4);

/// Reconexión por TCP desde Rust (Bluetooth lo maneja Kotlin, que es quien
/// puede abrir un `BluetoothSocket`).
struct TcpDialer;

impl Dialer for TcpDialer {
    fn dial(&self, peer: PeerRecord) -> DialFuture {
        Box::pin(async move {
            let addr: SocketAddr = peer
                .last_tcp_addr
                .as_deref()
                .and_then(|a| a.parse().ok())
                .ok_or_else(|| anyhow::anyhow!("{} no tiene dirección Wi-Fi conocida", peer.device_name))?;
            let stream = tokio::time::timeout(TCP_DIAL_TIMEOUT, TcpStream::connect(addr))
                .await
                .map_err(|_| anyhow::anyhow!("tiempo agotado conectando a {addr}"))??;
            Ok((Box::new(stream) as Box<dyn LinkStream>, Transport::Tcp(addr)))
        })
    }
}

fn dir(outgoing: bool) -> Direction {
    if outgoing { Direction::Outgoing } else { Direction::Incoming }
}

fn actions_from(actions: Vec<NotificationAction>) -> Vec<MobileAction> {
    actions
        .into_iter()
        .map(|a| MobileAction {
            id: a.id,
            label: a.label,
            is_reply: a.is_reply,
        })
        .collect()
}

#[derive(uniffi::Object)]
pub struct LinkService {
    manager: LinkManager,
    /// Último estado local publicado (se reenvía a cada peer que conecta).
    cache: SharedCache,
    /// Tareas de fondo (eventos, reconexión, listener TCP), para poder
    /// detenerlas en [`LinkService::shutdown`].
    tasks: std::sync::Mutex<Vec<tokio::task::AbortHandle>>,
    /// PCs que comprobaron que nos alcanzan por Wi-Fi (`LanStatus`).
    wifi_ready: WifiReady,
}

type WifiReady = Arc<std::sync::Mutex<std::collections::HashSet<String>>>;

#[uniffi::export]
impl LinkService {
    /// Abre (o crea) la identidad y los peers en `config_dir`, guarda los
    /// iconos recibidos en `cache_dir`, y empieza a entregar eventos por
    /// `callback` y a reconectar por TCP con los peers emparejados.
    #[uniffi::constructor]
    pub fn new(
        config_dir: String,
        cache_dir: String,
        device_name: String,
        callback: Box<dyn LinkCallback>,
    ) -> Result<Arc<Self>, MobileError> {
        let _guard = RUNTIME.enter();
        let (manager, mut events) = LinkManager::open(
            &PathBuf::from(config_dir),
            &PathBuf::from(cache_dir),
            LocalDevice {
                name: device_name,
                kind: "android".into(),
                bt_address: None, // Android no deja leer la MAC propia
                capabilities: vec![
                    "notifications".into(),
                    "notification-dismiss".into(),
                    "notification-actions".into(),
                    "notification-reply".into(),
                ],
            },
            LinkConfig::mobile(),
        )?;

        let names = manager.clone();
        let cache = SharedCache::default();
        let events_cache = cache.clone();
        let wifi_ready = WifiReady::default();
        let events_ready = wifi_ready.clone();
        let events_task = RUNTIME.spawn(async move {
            while let Some(event) = events.recv().await {
                dispatch(&*callback, &names, &events_cache, &events_ready, event);
            }
        });
        let reconnect = manager.clone();
        let reconnect_task = RUNTIME.spawn(async move { reconnect.run_reconnect(TcpDialer).await });
        Ok(Arc::new(Self {
            manager,
            cache,
            wifi_ready,
            tasks: std::sync::Mutex::new(vec![events_task.abort_handle(), reconnect_task.abort_handle()]),
        }))
    }

    pub fn device_id(&self) -> String {
        self.manager.device_id().to_string()
    }

    /// Escucha conexiones del enlace en `port` (si está ocupado, en uno
    /// libre). Devuelve el puerto real, para anunciarlo por mDNS; también
    /// se manda en el `Hello` para que el PC pueda reconectar sin escanear.
    pub async fn start_tcp_listener(&self, port: u16) -> Result<u16, MobileError> {
        let manager = self.manager.clone();
        let port = RUNTIME
            .spawn(async move {
                let listener = match TcpListener::bind(("0.0.0.0", port)).await {
                    Ok(listener) => listener,
                    Err(_) if port != 0 => TcpListener::bind(("0.0.0.0", 0)).await?,
                    Err(err) => return Err(err.into()),
                };
                let port = listener.local_addr()?.port();
                manager.set_tcp_port(port);
                let task = tokio::spawn(async move {
                    loop {
                        let Ok((stream, addr)) = listener.accept().await else { continue };
                        let manager = manager.clone();
                        tokio::spawn(async move {
                            let _ = manager.accept(stream, Transport::Tcp(addr)).await;
                        });
                    }
                });
                anyhow::Ok((port, task.abort_handle()))
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
        let (port, task) = port;
        self.tasks.lock().expect("mutex envenenado").push(task);
        Ok(port)
    }

    /// El peer `device_id` se anuncia por mDNS en `host:port`.
    pub fn note_sighting(&self, device_id: String, host: String, port: u16) {
        if let Ok(ip) = host.parse() {
            self.manager.note_sighting(&device_id, SocketAddr::new(ip, port));
        }
    }

    /// Empareja con un peer visible en `host:port`. Vuelve cuando termina
    /// (tras confirmar el PIN en ambos lados) o falla.
    pub async fn pair_tcp(&self, host: String, port: u16) -> Result<String, MobileError> {
        let manager = self.manager.clone();
        let id = RUNTIME
            .spawn(async move {
                let ip: std::net::IpAddr = host.parse()?;
                let addr = SocketAddr::new(ip, port);
                let stream = tokio::time::timeout(TCP_DIAL_TIMEOUT, TcpStream::connect(addr))
                    .await
                    .map_err(|_| anyhow::anyhow!("tiempo agotado conectando a {addr}"))??;
                manager.connect(stream, Transport::Tcp(addr), ConnectIntent::Pair).await
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
        Ok(id)
    }

    /// Atiende una conexión RFCOMM entrante ya aceptada por Kotlin.
    pub async fn accept_bt(&self, io: Box<dyn ByteStreamIo>, address: String) -> Result<String, MobileError> {
        let manager = self.manager.clone();
        let id = RUNTIME
            .spawn(async move {
                let stream = CallbackStream::new(io);
                manager.accept(stream, Transport::Bluetooth(address)).await
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
        Ok(id)
    }

    /// Reconecta con un peer emparejado por un `BluetoothSocket` que Kotlin
    /// ya conectó.
    pub async fn connect_bt(&self, io: Box<dyn ByteStreamIo>, address: String) -> Result<String, MobileError> {
        let manager = self.manager.clone();
        let id = RUNTIME
            .spawn(async move {
                let stream = CallbackStream::new(io);
                manager
                    .connect(stream, Transport::Bluetooth(address), ConnectIntent::Reconnect)
                    .await
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
        Ok(id)
    }

    /// Empareja (con PIN) por un `BluetoothSocket` que Kotlin ya conectó al
    /// servicio RFCOMM del enlace. Para redes que aíslan a los clientes
    /// entre sí, donde el Wi-Fi no sirve.
    pub async fn pair_bt(&self, io: Box<dyn ByteStreamIo>, address: String) -> Result<String, MobileError> {
        let manager = self.manager.clone();
        let id = RUNTIME
            .spawn(async move {
                let stream = CallbackStream::new(io);
                manager
                    .connect(stream, Transport::Bluetooth(address), ConnectIntent::Pair)
                    .await
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
        Ok(id)
    }

    /// Apaga el enlace: cierra todas las conexiones y detiene la escucha, la
    /// reconexión y los eventos. Los peers emparejados se conservan. Tras
    /// esto el objeto no sirve; crear otro `LinkService` para volver a
    /// encender.
    pub fn shutdown(&self) {
        for task in self.tasks.lock().expect("mutex envenenado").drain(..) {
            task.abort();
        }
        self.manager.disconnect_all();
    }

    /// Reintentar ya la reconexión por Wi-Fi (p. ej. al volver la red), sin
    /// esperar al backoff.
    pub fn wake_reconnect(&self) {
        self.manager.wake_reconnect();
    }

    pub fn respond_pairing(&self, device_id: String, accept: bool) -> bool {
        self.manager.respond_pairing(&device_id, accept)
    }

    pub fn unpair(&self, device_id: String) -> Result<(), MobileError> {
        let _guard = RUNTIME.enter();
        Ok(self.manager.unpair(&device_id)?)
    }

    /// `outgoing`: true = teléfono → peer; false = peer → teléfono.
    pub fn set_direction(&self, device_id: String, outgoing: bool, enabled: bool) -> Result<(), MobileError> {
        Ok(self.manager.set_direction(&device_id, dir(outgoing), enabled)?)
    }

    pub fn set_app_blocked(
        &self,
        device_id: String,
        outgoing: bool,
        app_id: String,
        blocked: bool,
    ) -> Result<(), MobileError> {
        Ok(self.manager.set_app_blocked(&device_id, dir(outgoing), &app_id, blocked)?)
    }

    pub fn post_local_notification(&self, notification: MobileNotification) {
        self.manager.post_local_notification(notification_data(notification));
    }

    pub fn remove_local_notification(&self, key: String) {
        self.manager.remove_local_notification(&key);
    }

    pub fn dismiss_remote_notification(&self, device_id: String, key: String) {
        self.manager.dismiss_remote_notification(&device_id, &key);
    }

    pub fn invoke_remote_action(&self, device_id: String, key: String, action_id: String) {
        self.manager.invoke_remote_action(&device_id, &key, &action_id);
    }

    pub fn reply_remote(&self, device_id: String, key: String, action_id: String, text: String) {
        self.manager.reply_remote(&device_id, &key, &action_id, &text);
    }

    pub fn is_connected(&self, device_id: String) -> bool {
        self.manager.is_connected(&device_id)
    }

    pub fn peers(&self) -> Vec<MobilePeer> {
        self.manager
            .peers()
            .into_iter()
            .map(|p| MobilePeer {
                wifi_ready: p.bulk_transport.is_some()
                    || self.wifi_ready.lock().expect("mutex envenenado").contains(&p.device_id),
                bt_address: p.bt_address,
                device_id: p.device_id,
                device_name: p.device_name,
                device_kind: p.device_kind,
                connected: p.connected,
                transport: p.transport,
                bulk_transport: p.bulk_transport,
                outgoing_enabled: p.outgoing_enabled,
                incoming_enabled: p.incoming_enabled,
                outgoing_blocked: p.outgoing_blocked,
                incoming_blocked: p.incoming_blocked,
                peer_apps: p
                    .peer_apps
                    .into_iter()
                    .map(|(app_id, name)| MobileApp { app_id, name })
                    .collect(),
            })
            .collect()
    }

    // --- Funciones extra: estado local del teléfono --------------------

    pub fn publish_battery(&self, battery: MobileBattery) {
        let b = pb::BatteryStatus {
            present: battery.present,
            percent: battery.percent.min(100),
            charging: battery.charging,
        };
        features::publish(&self.manager, &self.cache, F::Battery(b));
    }

    pub fn publish_players(&self, players: Vec<MobilePlayer>) {
        let players = pb::MediaPlayers {
            players: players.into_iter().map(features::player_to_pb).collect(),
        };
        features::publish(&self.manager, &self.cache, F::MediaPlayers(players));
    }

    pub fn publish_audio_outputs(&self, selectable: bool, outputs: Vec<MobileAudioOutput>) {
        let outputs = pb::AudioOutputs {
            selectable,
            outputs: outputs
                .into_iter()
                .map(|o| pb::AudioOutput {
                    id: o.id,
                    name: o.name,
                    active: o.active,
                })
                .collect(),
        };
        features::publish(&self.manager, &self.cache, F::AudioOutputs(outputs));
    }

    /// `mode`: normal|vibrate|silent.
    pub fn publish_ringer(&self, mode: String) {
        let ringer = pb::RingerMode {
            mode: features::ringer_from(&mode).into(),
        };
        features::publish(&self.manager, &self.cache, F::RingerMode(ringer));
    }

    /// `phase`: idle|ringing|active|dialing.
    pub fn publish_call_state(&self, phase: String, number: String, name: String, muted: bool) {
        let call = pb::CallState {
            phase: features::call_phase_from(&phase).into(),
            number,
            name,
            muted,
        };
        features::publish(&self.manager, &self.cache, F::CallState(call));
    }

    /// Modo híbrido: IPs de la Wi-Fi y puerto TCP del enlace, para que el PC
    /// abra ahí un enlace de datos cuando necesite algo pesado. Lista vacía
    /// = no usar Wi-Fi (modo "Solo Bluetooth" o sin Wi-Fi).
    pub fn publish_lan_address(&self, addrs: Vec<String>, port: u16) {
        let lan = pb::LanAddress {
            addrs,
            port: u32::from(port),
        };
        features::publish(&self.manager, &self.cache, F::LanAddress(lan));
    }

    /// La galería cambió (foto o captura nueva): avisar a los peers para que
    /// la GUI que la esté mostrando pida las más recientes.
    pub fn notify_photos_changed(&self) {
        self.manager.broadcast_feature(pb::Feature {
            kind: Some(F::PhotosChanged(pb::PhotosChanged {})),
        });
    }

    /// Respuesta a [`LinkCallback::on_photo_list_request`].
    pub async fn send_photo_list(
        &self,
        device_id: String,
        request_id: u64,
        total: u32,
        photos: Vec<MobilePhoto>,
    ) -> Result<(), MobileError> {
        let list = pb::PhotoList {
            request_id,
            total,
            photos: photos
                .into_iter()
                .map(|p| pb::PhotoInfo {
                    id: p.id,
                    name: p.name,
                    date_ms: p.date_ms,
                    width: p.width,
                    height: p.height,
                    size: p.size,
                    thumbnail: p.thumbnail,
                })
                .collect(),
        };
        let manager = self.manager.clone();
        RUNTIME
            .spawn(async move {
                manager
                    .send_feature_wait(&device_id, pb::Feature { kind: Some(F::PhotoList(list)) })
                    .await
            })
            .await
            .map_err(|e| anyhow::anyhow!(e))??;
        Ok(())
    }

    /// Un trozo de la foto pedida en [`LinkCallback::on_photo_request`].
    /// Espera a que haya sitio en la cola del enlace (no se pierde ningún
    /// trozo). `error` no vacío = no se pudo leer (y cierra la respuesta).
    #[allow(clippy::too_many_arguments)]
    pub async fn send_photo_data(
        &self,
        device_id: String,
        request_id: u64,
        photo_id: String,
        name: String,
        offset: u64,
        data: Vec<u8>,
        last: bool,
        error: String,
        background: bool,
    ) -> Result<(), MobileError> {
        let chunk = pb::PhotoData {
            request_id,
            id: photo_id,
            name,
            offset,
            data,
            last: last || !error.is_empty(),
            error,
        };
        let manager = self.manager.clone();
        RUNTIME
            .spawn(async move {
                let feature = pb::Feature { kind: Some(F::PhotoData(chunk)) };
                if background {
                    manager.send_feature_idle(&device_id, feature).await
                } else {
                    manager.send_feature_wait(&device_id, feature).await
                }
            })
            .await
            .map_err(|e| anyhow::anyhow!(e))??;
        Ok(())
    }

    // --- Portapapeles, SMS, llamadas, contactos, notificaciones, fondo ---

    /// Texto copiado en el teléfono: mandarlo a los peers.
    pub fn publish_clipboard(&self, text: String) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or_default();
        self.manager.broadcast_feature(pb::Feature {
            kind: Some(F::Clipboard(pb::ClipboardContent { text, timestamp_ms: now })),
        });
    }

    pub async fn send_sms_threads(
        &self,
        device_id: String,
        request_id: u64,
        total: u32,
        threads: Vec<MobileSmsThread>,
        error: String,
    ) -> Result<(), MobileError> {
        let threads = threads
            .into_iter()
            .map(|t| pb::SmsThread {
                thread_id: t.thread_id,
                address: t.address,
                name: t.name,
                snippet: t.snippet,
                date_ms: t.date_ms,
                unread: t.unread,
                count: t.count,
                outgoing: t.outgoing,
            })
            .collect();
        self.reply(&device_id, F::SmsThreads(pb::SmsThreads { request_id, threads, error, total }))
            .await
    }

    pub async fn send_sms_messages(
        &self,
        device_id: String,
        request_id: u64,
        thread_id: String,
        messages: Vec<MobileSms>,
        error: String,
    ) -> Result<(), MobileError> {
        let messages = messages
            .into_iter()
            .map(|m| pb::SmsMessage {
                id: m.id,
                address: m.address,
                body: m.body,
                date_ms: m.date_ms,
                outgoing: m.outgoing,
                read: m.read,
                failed: m.failed,
            })
            .collect();
        self.reply(&device_id, F::SmsMessages(pb::SmsMessages { request_id, thread_id, messages, error }))
            .await
    }

    /// Llegó o se mandó un SMS.
    pub fn notify_sms_changed(&self, thread_id: String) {
        self.manager.broadcast_feature(pb::Feature {
            kind: Some(F::SmsChanged(pb::SmsChanged { thread_id })),
        });
    }

    pub async fn send_call_log(
        &self,
        device_id: String,
        request_id: u64,
        entries: Vec<MobileCallLogEntry>,
        error: String,
    ) -> Result<(), MobileError> {
        let entries = entries
            .into_iter()
            .map(|e| pb::CallLogEntry {
                r#type: features::call_type_from(&e.kind).into(),
                number: e.number,
                name: e.name,
                date_ms: e.date_ms,
                duration_s: e.duration_s,
            })
            .collect();
        self.reply(&device_id, F::CallLog(pb::CallLog { request_id, entries, error })).await
    }

    pub async fn send_contacts(
        &self,
        device_id: String,
        request_id: u64,
        contacts: Vec<MobileContact>,
        error: String,
    ) -> Result<(), MobileError> {
        let contacts = contacts
            .into_iter()
            .map(|c| pb::Contact { name: c.name, numbers: c.numbers, starred: c.starred })
            .collect();
        self.reply(&device_id, F::Contacts(pb::Contacts { request_id, contacts, error })).await
    }

    /// Las notificaciones activas del teléfono (sin el PNG de los iconos
    /// cuyo hash está en `have_icons`).
    pub async fn send_active_notifications(
        &self,
        device_id: String,
        request_id: u64,
        notifications: Vec<MobileNotification>,
        have_icons: Vec<String>,
        error: String,
    ) -> Result<(), MobileError> {
        let have: std::collections::HashSet<String> = have_icons.into_iter().collect();
        let notifications = notifications
            .into_iter()
            .map(|n| kushare_link::notification_to_pb(&notification_data(n), &have))
            .collect();
        self.reply(
            &device_id,
            F::ActiveNotifications(pb::ActiveNotifications { request_id, notifications, error }),
        )
        .await
    }

    pub async fn send_wallpaper(
        &self,
        device_id: String,
        request_id: u64,
        jpeg: Vec<u8>,
        colors: Vec<u32>,
        error: String,
    ) -> Result<(), MobileError> {
        self.reply(&device_id, F::Wallpaper(pb::Wallpaper { request_id, jpeg, colors, error })).await
    }

    /// Resultado de `on_send_sms`, `on_dial` u `on_photo_delete`.
    /// `status`: "done" | "confirm".
    pub fn send_command_result(&self, device_id: String, request_id: u64, ok: bool, error: String, status: String) {
        self.manager.send_feature(
            &device_id,
            pb::Feature { kind: Some(F::CommandResult(pb::CommandResult { request_id, ok, error, status })) },
        );
    }


    // --- Funciones extra: control del peer (el PC) ------------------------

    /// `player_id` vacío = todos. `action`: play-pause|play|pause|next|previous|stop.
    pub fn send_media_command(&self, device_id: String, player_id: String, action: String) {
        let cmd = pb::MediaCommand {
            player_id,
            action: features::media_action_from(&action).into(),
        };
        self.manager
            .send_feature(&device_id, pb::Feature { kind: Some(F::MediaCommand(cmd)) });
    }

    pub fn send_set_audio_output(&self, device_id: String, output_id: String) {
        let set = pb::SetAudioOutput { id: output_id };
        self.manager
            .send_feature(&device_id, pb::Feature { kind: Some(F::SetAudioOutput(set)) });
    }

    pub fn local_apps(&self) -> Vec<MobileApp> {
        self.manager
            .local_apps()
            .into_iter()
            .map(|(app_id, name)| MobileApp { app_id, name })
            .collect()
    }
}

fn notification_data(notification: MobileNotification) -> NotificationData {
    NotificationData {
        key: notification.key,
        app_id: notification.app_id,
        app_name: notification.app_name,
        title: notification.title,
        text: notification.text,
        timestamp_ms: notification.timestamp_ms,
        icon_png: notification.icon_png,
        actions: notification
            .actions
            .into_iter()
            .map(|a| NotificationAction {
                id: a.id,
                label: a.label,
                is_reply: a.is_reply,
            })
            .collect(),
    }
}

fn dispatch(
    callback: &dyn LinkCallback,
    manager: &LinkManager,
    cache: &SharedCache,
    wifi_ready: &WifiReady,
    event: LinkEvent,
) {
    match event {
        LinkEvent::Feature {
            device_id,
            feature: pb::Feature {
                kind: Some(F::LanStatus(status)),
            },
        } => {
            let mut ready = wifi_ready.lock().expect("mutex envenenado");
            let changed = if status.ready {
                ready.insert(device_id)
            } else {
                ready.remove(&device_id)
            };
            drop(ready);
            if changed {
                callback.on_peers_changed();
            }
        }
        LinkEvent::PeerConnected { device_id, .. } => {
            features::send_cached(manager, cache, &device_id);
            callback.on_peers_changed();
        }
        LinkEvent::Feature { device_id, feature } => features::dispatch(callback, device_id, feature),
        LinkEvent::PairingRequested {
            device_id,
            device_name,
            pin,
            initiated_locally,
        } => callback.on_pairing_requested(device_id, device_name, pin, initiated_locally),
        LinkEvent::PairingFinished {
            device_id,
            device_name,
            accepted,
        } => {
            callback.on_pairing_finished(device_id, device_name, accepted);
            callback.on_peers_changed();
        }
        LinkEvent::PeerDisconnected { device_id } | LinkEvent::PeerUnpaired { device_id } => {
            wifi_ready.lock().expect("mutex envenenado").remove(&device_id);
            callback.on_peers_changed();
        }
        LinkEvent::ConfigChanged { .. } => callback.on_peers_changed(),
        LinkEvent::NotificationPosted {
            device_id,
            notification: n,
        } => {
            let device_name = manager
                .peers()
                .into_iter()
                .find(|p| p.device_id == device_id)
                .map(|p| p.device_name)
                .unwrap_or_else(|| device_id.clone());
            callback.on_notification_posted(MobileRemoteNotification {
                device_id,
                device_name,
                key: n.key,
                app_id: n.app_id,
                app_name: n.app_name,
                title: n.title,
                text: n.text,
                timestamp_ms: n.timestamp_ms,
                icon_path: n.icon_path.map(|p| p.to_string_lossy().to_string()),
                actions: actions_from(n.actions),
            });
        }
        LinkEvent::NotificationRemoved { device_id, key } => callback.on_notification_removed(device_id, key),
        LinkEvent::DismissLocal { device_id, key } => callback.on_dismiss_local(device_id, key),
        LinkEvent::ActionRequested {
            device_id,
            key,
            action_id,
        } => callback.on_action_requested(device_id, key, action_id),
        LinkEvent::ReplyRequested {
            device_id,
            key,
            action_id,
            text,
        } => callback.on_reply_requested(device_id, key, action_id, text),
    }
}


impl LinkService {
    /// Manda una respuesta (grande) esperando sitio en la cola del enlace.
    async fn reply(&self, device_id: &str, kind: F) -> Result<(), MobileError> {
        let manager = self.manager.clone();
        let device_id = device_id.to_string();
        RUNTIME
            .spawn(async move { manager.send_feature_wait(&device_id, pb::Feature { kind: Some(kind) }).await })
            .await
            .map_err(|e| anyhow::anyhow!(e))??;
        Ok(())
    }
}
