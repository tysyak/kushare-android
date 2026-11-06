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

#[derive(Default)]
pub struct LocalCache {
    battery: Option<pb::BatteryStatus>,
    players: Option<pb::MediaPlayers>,
    outputs: Option<pb::AudioOutputs>,
    ringer: Option<pb::RingerMode>,
    call: Option<pb::CallState>,
}

impl LocalCache {
    fn messages(&self) -> Vec<F> {
        [
            self.battery.map(F::Battery),
            self.players.clone().map(F::MediaPlayers),
            self.outputs.clone().map(F::AudioOutputs),
            self.ringer.map(F::RingerMode),
            self.call.clone().map(F::CallState),
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
        F::MediaPlayers(p) => {
            callback.on_peer_players(device_id, p.players.into_iter().map(player_from_pb).collect())
        }
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
        F::PhotoListRequest(req) => {
            callback.on_photo_list_request(device_id, req.request_id, req.offset, req.limit.min(60))
        }
        F::PhotoRequest(req) => callback.on_photo_request(device_id, req.request_id, req.id),
        // Solo los manda un teléfono; el PC no tiene timbre, llamadas ni galería.
        F::RingerMode(_) | F::CallState(_) | F::PhotoList(_) | F::PhotoData(_) | F::PhotosChanged(_) => {}
    }
}
