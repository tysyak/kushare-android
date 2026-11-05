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
}

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
            LinkConfig::default(),
        )?;

        let names = manager.clone();
        RUNTIME.spawn(async move {
            while let Some(event) = events.recv().await {
                dispatch(&*callback, &names, event);
            }
        });
        let reconnect = manager.clone();
        RUNTIME.spawn(async move { reconnect.run_reconnect(TcpDialer).await });
        Ok(Arc::new(Self { manager }))
    }

    pub fn device_id(&self) -> String {
        self.manager.device_id().to_string()
    }

    /// Escucha conexiones del enlace en `port` (0 = libre). Devuelve el
    /// puerto real, para anunciarlo por mDNS.
    pub async fn start_tcp_listener(&self, port: u16) -> Result<u16, MobileError> {
        let manager = self.manager.clone();
        let port = RUNTIME
            .spawn(async move {
                let listener = TcpListener::bind(("0.0.0.0", port)).await?;
                let port = listener.local_addr()?.port();
                tokio::spawn(async move {
                    loop {
                        let Ok((stream, addr)) = listener.accept().await else { continue };
                        let manager = manager.clone();
                        tokio::spawn(async move {
                            let _ = manager.accept(stream, Transport::Tcp(addr)).await;
                        });
                    }
                });
                anyhow::Ok(port)
            })
            .await
            .map_err(|e| anyhow::anyhow!("tarea interrumpida: {e}"))??;
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
        self.manager.post_local_notification(NotificationData {
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
        });
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
                bt_address: p.bt_address,
                device_id: p.device_id,
                device_name: p.device_name,
                device_kind: p.device_kind,
                connected: p.connected,
                transport: p.transport,
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

    pub fn local_apps(&self) -> Vec<MobileApp> {
        self.manager
            .local_apps()
            .into_iter()
            .map(|(app_id, name)| MobileApp { app_id, name })
            .collect()
    }
}

fn dispatch(callback: &dyn LinkCallback, manager: &LinkManager, event: LinkEvent) {
    match event {
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
        LinkEvent::PeerConnected { .. }
        | LinkEvent::PeerDisconnected { .. }
        | LinkEvent::PeerUnpaired { .. }
        | LinkEvent::ConfigChanged { .. } => callback.on_peers_changed(),
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
