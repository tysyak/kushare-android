//! Funciones extra del enlace (batería, multimedia, salidas de audio,
//! timbre, llamadas y fotos) expuestas a Kotlin con tipos UniFFI simples.
//! El protocolo (`kushare_link::pb::Feature`) queda oculto: Kotlin solo
//! ve registros y cadenas ("playing", "vibrate", "answer"…).
//!
//! El último estado local publicado se guarda aquí y se reenvía solo a cada
//! peer que conecta, para que Kotlin no tenga que hacerlo.

use kushare_link::LinkManager;
use kushare_link::pb::{self, feature::Kind as F};
use std::sync::{Arc, Mutex};

use crate::link::LinkCallback;

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileBattery {
    pub present: bool,
    pub percent: u32,
    pub charging: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobilePlayer {
    pub id: String,
    pub name: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    /// "playing" | "paused" | "stopped".
    pub state: String,
    pub can_play: bool,
    pub can_pause: bool,
    pub can_next: bool,
    pub can_previous: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileAudioOutput {
    pub id: String,
    pub name: String,
    pub active: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobilePhoto {
    pub id: String,
    pub name: String,
    pub date_ms: i64,
    pub width: u32,
    pub height: u32,
    pub size: u64,
    /// Miniatura JPEG.
    pub thumbnail: Vec<u8>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileSmsThread {
    pub thread_id: String,
    pub address: String,
    pub name: String,
    pub snippet: String,
    pub date_ms: i64,
    pub unread: bool,
    pub count: u32,
    pub outgoing: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileSms {
    pub id: String,
    pub address: String,
    pub body: String,
    pub date_ms: i64,
    pub outgoing: bool,
    pub read: bool,
    pub failed: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileCallLogEntry {
    pub number: String,
    pub name: String,
    pub date_ms: i64,
    pub duration_s: u32,
    /// incoming|outgoing|missed|rejected|blocked|voicemail
    pub kind: String,
    /// "<contactId>-<photoId>" o "" (ver `ContactPhotosRequest`).
    pub photo_key: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileContact {
    pub name: String,
    pub numbers: Vec<String>,
    pub starred: bool,
    pub photo_key: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MobileContactPhoto {
    pub key: String,
    /// JPEG/PNG pequeño, como mucho [`CONTACT_PHOTO_MAX`] bytes.
    pub image: Vec<u8>,
}

/// Tamaño máximo de una foto de contacto (las mayores se omiten).
pub const CONTACT_PHOTO_MAX: usize = 64 * 1024;

#[derive(Default)]
pub struct LocalCache {
    battery: Option<pb::BatteryStatus>,
    players: Option<pb::MediaPlayers>,
    outputs: Option<pb::AudioOutputs>,
    ringer: Option<pb::RingerMode>,
    call: Option<pb::CallState>,
    lan: Option<pb::LanAddress>,
}

impl LocalCache {
    fn messages(&self) -> Vec<F> {
        [
            self.battery.map(F::Battery),
            self.players.clone().map(F::MediaPlayers),
            self.outputs.clone().map(F::AudioOutputs),
            self.ringer.map(F::RingerMode),
            self.call.clone().map(F::CallState),
            self.lan.clone().map(F::LanAddress),
        ]
        .into_iter()
        .flatten()
        .collect()
    }
}

pub type SharedCache = Arc<Mutex<LocalCache>>;

/// Un peer acaba de conectar: mandarle todo el estado local.
pub fn send_cached(manager: &LinkManager, cache: &SharedCache, peer_id: &str) {
    for kind in cache.lock().expect("mutex envenenado").messages() {
        manager.send_feature(peer_id, pb::Feature { kind: Some(kind) });
    }
}

/// Guarda `kind` en la caché y, si cambió, lo manda a todos los peers.
pub fn publish(manager: &LinkManager, cache: &SharedCache, kind: F) {
    {
        let mut c = cache.lock().expect("mutex envenenado");
        let changed = match &kind {
            F::Battery(v) => replace(&mut c.battery, v),
            F::MediaPlayers(v) => replace(&mut c.players, v),
            F::AudioOutputs(v) => replace(&mut c.outputs, v),
            F::RingerMode(v) => replace(&mut c.ringer, v),
            F::CallState(v) => replace(&mut c.call, v),
            F::LanAddress(v) => replace(&mut c.lan, v),
            _ => true,
        };
        if !changed {
            return;
        }
    }
    manager.broadcast_feature(pb::Feature { kind: Some(kind) });
}

fn replace<T: PartialEq + Clone>(slot: &mut Option<T>, value: &T) -> bool {
    if slot.as_ref() == Some(value) {
        return false;
    }
    *slot = Some(value.clone());
    true
}

pub fn ringer_from(mode: &str) -> pb::Ringer {
    match mode {
        "silent" => pb::Ringer::Silent,
        "vibrate" => pb::Ringer::Vibrate,
        _ => pb::Ringer::Normal,
    }
}

fn ringer_name(mode: pb::Ringer) -> &'static str {
    match mode {
        pb::Ringer::Normal => "normal",
        pb::Ringer::Vibrate => "vibrate",
        pb::Ringer::Silent => "silent",
    }
}

pub fn call_phase_from(phase: &str) -> pb::CallPhase {
    match phase {
        "ringing" => pb::CallPhase::CallRinging,
        "active" => pb::CallPhase::CallActive,
        "dialing" => pb::CallPhase::CallDialing,
        _ => pb::CallPhase::CallIdle,
    }
}

pub fn media_action_from(action: &str) -> pb::MediaAction {
    match action {
        "play" => pb::MediaAction::MediaPlay,
        "pause" => pb::MediaAction::MediaPause,
        "next" => pb::MediaAction::MediaNext,
        "previous" => pb::MediaAction::MediaPrevious,
        "stop" => pb::MediaAction::MediaStop,
        _ => pb::MediaAction::MediaPlayPause,
    }
}

fn media_action_name(action: pb::MediaAction) -> &'static str {
    match action {
        pb::MediaAction::MediaPlayPause => "play-pause",
        pb::MediaAction::MediaPlay => "play",
        pb::MediaAction::MediaPause => "pause",
        pb::MediaAction::MediaNext => "next",
        pb::MediaAction::MediaPrevious => "previous",
        pb::MediaAction::MediaStop => "stop",
    }
}

pub fn player_to_pb(p: MobilePlayer) -> pb::MediaPlayer {
    let state = match p.state.as_str() {
        "playing" => pb::PlaybackState::PlaybackPlaying,
        "paused" => pb::PlaybackState::PlaybackPaused,
        _ => pb::PlaybackState::PlaybackStopped,
    };
    pb::MediaPlayer {
        id: p.id,
        name: p.name,
        title: p.title,
        artist: p.artist,
        album: p.album,
        state: state.into(),
        can_play: p.can_play,
        can_pause: p.can_pause,
        can_next: p.can_next,
        can_previous: p.can_previous,
    }
}

fn player_from_pb(p: pb::MediaPlayer) -> MobilePlayer {
    let state = match p.state() {
        pb::PlaybackState::PlaybackPlaying => "playing",
        pb::PlaybackState::PlaybackPaused => "paused",
        pb::PlaybackState::PlaybackStopped => "stopped",
    };
    MobilePlayer {
        state: state.into(),
        id: p.id,
        name: p.name,
        title: p.title,
        artist: p.artist,
        album: p.album,
        can_play: p.can_play,
        can_pause: p.can_pause,
        can_next: p.can_next,
        can_previous: p.can_previous,
    }
}

/// Entrega a Kotlin un mensaje de función extra del peer.
pub fn dispatch(callback: &dyn LinkCallback, device_id: String, feature: pb::Feature) {
    let Some(kind) = feature.kind else {
        return;
    };
    match kind {
        F::Battery(b) => callback.on_peer_battery(
            device_id,
            MobileBattery {
                present: b.present,
                percent: b.percent,
                charging: b.charging,
            },
        ),
        F::MediaPlayers(p) => callback.on_peer_players(
            device_id,
            p.players.into_iter().map(player_from_pb).collect(),
        ),
        F::AudioOutputs(o) => callback.on_peer_audio_outputs(
            device_id,
            o.selectable,
            o.outputs
                .into_iter()
                .map(|o| MobileAudioOutput {
                    id: o.id,
                    name: o.name,
                    active: o.active,
                })
                .collect(),
        ),
        F::MediaCommand(cmd) => {
            let action = media_action_name(cmd.action()).to_string();
            callback.on_media_command(device_id, cmd.player_id, action)
        }
        F::SetAudioOutput(set) => callback.on_set_audio_output(device_id, set.id),
        F::SetRingerMode(set) => callback.on_set_ringer(device_id, ringer_name(set.mode()).into()),
        F::CallCommand(cmd) => {
            let action = match cmd.action() {
                pb::CallAction::CallAnswer => "answer",
                pb::CallAction::CallReject => "reject",
                pb::CallAction::CallHangup => "hangup",
                pb::CallAction::CallMute => "mute",
                pb::CallAction::CallUnmute => "unmute",
            };
            callback.on_call_command(device_id, action.into())
        }
        F::PhotoListRequest(req) => callback.on_photo_list_request(
            device_id,
            req.request_id,
            req.offset,
            req.limit.min(60),
            req.have_thumbnails,
        ),
        F::PhotoRequest(req) => {
            let chunk = if req.chunk_size == 0 {
                256 * 1024
            } else {
                req.chunk_size.clamp(16 * 1024, 256 * 1024)
            };
            callback.on_photo_request(device_id, req.request_id, req.id, chunk, req.background)
        }
        F::Clipboard(c) => callback.on_clipboard(device_id, c.text),
        F::SmsThreadsRequest(r) => {
            callback.on_sms_threads_request(device_id, r.request_id, r.offset, r.limit.min(100))
        }
        F::SmsMessagesRequest(r) => callback.on_sms_messages_request(
            device_id,
            r.request_id,
            r.thread_id,
            r.limit.min(200),
            r.before_ms,
        ),
        F::SendSms(r) => callback.on_send_sms(device_id, r.request_id, r.address, r.body),
        F::CallLogRequest(r) => {
            callback.on_call_log_request(device_id, r.request_id, r.limit.min(500))
        }
        F::ContactsRequest(r) => callback.on_contacts_request(device_id, r.request_id),
        F::ContactPhotosRequest(r) => {
            let mut keys = r.keys;
            keys.truncate(60);
            callback.on_contact_photos_request(device_id, r.request_id, keys)
        }
        F::Dial(r) => callback.on_dial(device_id, r.request_id, r.number),
        F::ActiveNotificationsRequest(r) => {
            callback.on_active_notifications_request(device_id, r.request_id, r.have_icons)
        }
        F::WallpaperRequest(r) => {
            callback.on_wallpaper_request(device_id, r.request_id, r.max_size.clamp(256, 2048))
        }
        F::PhotoDelete(r) => callback.on_photo_delete(device_id, r.request_id, r.id),
        // Solo los manda un teléfono (o son respuestas que el PC no pide al teléfono).
        F::RingerMode(_)
        | F::CallState(_)
        | F::PhotoList(_)
        | F::PhotoData(_)
        | F::PhotosChanged(_)
        | F::SmsThreads(_)
        | F::SmsMessages(_)
        | F::SmsChanged(_)
        | F::CallLog(_)
        | F::Contacts(_)
        | F::ContactPhotos(_)
        | F::ActiveNotifications(_)
        | F::Wallpaper(_)
        | F::CommandResult(_)
        | F::LanAddress(_)
        | F::LanStatus(_) => {}
    }
}

pub fn call_type_from(kind: &str) -> pb::CallType {
    match kind {
        "outgoing" => pb::CallType::Outgoing,
        "missed" => pb::CallType::Missed,
        "rejected" => pb::CallType::Rejected,
        "blocked" => pb::CallType::Blocked,
        "voicemail" => pb::CallType::Voicemail,
        _ => pb::CallType::Incoming,
    }
}
