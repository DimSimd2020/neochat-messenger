//! One-to-one calls. Media is WebRTC in the platform layer (DTLS-SRTP between
//! the devices); the core carries offer/answer/hangup as E2EE control messages
//! of the direct conversation, keeps the single current call and writes the
//! call log into the chat. The SDP, including the DTLS fingerprints, is only
//! ever inside libsignal ciphertext, so the server cannot substitute keys.

use std::sync::Arc;
use std::time::Duration;

use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};
use uuid::Uuid;

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::store::now_ms;

/// How long an unanswered call rings on both sides.
pub const RING_MS: u64 = 45_000;
/// An offer older than this (delivered late, device was offline) is only logged as missed.
const STALE_MS: i64 = 60_000;
const MAX_SDP: usize = 24 << 10;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Phase {
    /// Our offer is sent and ringing at the peer.
    Outgoing,
    /// A peer's offer is ringing here.
    Incoming,
    /// Answered; media is connecting.
    Connecting,
    Active,
}

#[derive(Clone, Debug)]
pub struct Call {
    pub id: String,
    pub conv: String,
    pub peer: String,
    /// The peer device that offered or answered; signals from other devices are ignored.
    pub peer_dev: Option<String>,
    pub outgoing: bool,
    pub video: bool,
    pub phase: Phase,
    /// The peer's offer while an incoming call rings.
    pub offer: Option<String>,
    /// When media connected, for the duration in the log.
    pub since: i64,
}

/// A decrypted call signal waiting to be applied after its sync batch.
pub struct Signal {
    pub sender: String,
    pub sdev: String,
    pub conv: String,
    pub ts: i64,
    pub body: Value,
}

fn phase_name(p: Phase) -> &'static str {
    match p {
        Phase::Outgoing => "outgoing",
        Phase::Incoming => "incoming",
        Phase::Connecting => "connecting",
        Phase::Active => "active",
    }
}

/// Text of a call log entry; also the chat list preview.
pub fn call_text(outgoing: bool, video: bool, status: &str, duration: i64) -> String {
    let what = if video { "видеозвонок" } else { "звонок" };
    let base = match (outgoing, status) {
        (_, "ended") if outgoing => format!("Исходящий {what}"),
        (_, "ended") => format!("Входящий {what}"),
        (true, "busy") => "Абонент занят".to_string(),
        (true, "declined") => format!("Отклонённый {what}"),
        (true, "no_answer") => format!("Исходящий {what} · нет ответа"),
        (true, _) => format!("Отменённый {what}"),
        (false, "declined") => format!("Отклонённый {what}"),
        (false, _) => format!("Пропущенный {what}"),
    };
    if status == "ended" && duration > 0 { format!("{base} · {}:{:02}", duration / 60, duration % 60) } else { base }
}

impl Inner {
    fn slot(&self) -> std::sync::MutexGuard<'_, Option<Call>> {
        self.cur_call.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn call_json(&self, c: &Call) -> Value {
        let conv = self.conversation_json(&c.conv).unwrap_or(Value::Null);
        json!({
            "call_id": c.id,
            "conversation_id": c.conv,
            "peer_user_id": c.peer,
            "peer_name": conv["title"],
            "avatar_path": conv["avatar_path"],
            "video": c.video,
            "outgoing": c.outgoing,
            "state": phase_name(c.phase),
        })
    }

    fn emit_call(&self, c: &Call, state: &str, extra: Value) {
        let mut v = self.call_json(c);
        v["type"] = json!("call");
        v["state"] = json!(state);
        if let Value::Object(m) = extra {
            for (k, x) in m {
                v[k] = x;
            }
        }
        self.emit(v);
    }

    /// TURN credentials from the server, cached until shortly before they expire.
    fn ice_servers(&self) -> Result<Value> {
        if let Some((v, until)) = self.ice_cache.lock().unwrap_or_else(|e| e.into_inner()).clone() {
            if until > now_ms() {
                return Ok(v);
            }
        }
        let res = self.authed("GET", "/v1/calls/ice", None)?;
        let servers = res["ice_servers"].clone();
        let ttl = res["ttl"].as_i64().unwrap_or(0) * 1000;
        *self.ice_cache.lock().unwrap_or_else(|e| e.into_inner()) = Some((servers.clone(), now_ms() + ttl / 2));
        Ok(servers)
    }

    fn send_signal(&self, conv: &str, body: Value) -> Result<()> {
        let mut p = self.new_payload(conv, "call")?;
        p.call = Some(body);
        self.enqueue(&p, false, None, "")?;
        self.wake();
        Ok(())
    }

    fn current(&self, id: &str) -> Result<Call> {
        self.slot().clone().filter(|c| c.id == id).ok_or_else(|| Error::Invalid("Звонок уже завершён".into()))
    }

    /// Clears the slot if it still holds this call in one of `phases`.
    fn take_call(&self, id: &str, phases: &[Phase]) -> Option<Call> {
        let mut slot = self.slot();
        if slot.as_ref().is_some_and(|c| c.id == id && phases.contains(&c.phase)) { slot.take() } else { None }
    }

    /// Rings for RING_MS, then gives up if the call is still in `phase`.
    fn ring_timer(self: &Arc<Self>, id: String, phase: Phase) {
        let me = Arc::downgrade(self);
        let _ = std::thread::Builder::new().name("neocore-ring".into()).spawn(move || {
            std::thread::sleep(Duration::from_millis(RING_MS));
            let Some(me) = me.upgrade() else { return };
            if let Some(c) = me.take_call(&id, &[phase]) {
                if phase == Phase::Outgoing {
                    let _ = me.send_signal(&c.conv, json!({"op": "hangup", "id": c.id}));
                    let _ = me.log_call(&c, "no_answer", 0);
                    me.emit_call(&c, "ended", json!({"reason": "no_answer"}));
                } else {
                    let _ = me.log_call(&c, "missed", 0);
                    me.emit_call(&c, "ended", json!({"reason": "missed"}));
                }
            }
        });
    }

    // ---- methods for the platform layer ----

    /// Reserves the call and returns the ICE servers; the platform then builds the offer.
    pub fn call_start(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let c = self.conversation_json(conv)?;
        if c["kind"] != "direct" {
            return Err(Error::Invalid("Звонить можно только в личном чате".into()));
        }
        self.check_can_send(conv)?;
        let peer = c["peer_user_id"].as_str().ok_or_else(|| Error::Invalid("Собеседник не найден".into()))?.to_string();
        if self.slot().is_some() {
            return Err(Error::Invalid("Уже идёт звонок".into()));
        }
        let ice = self.ice_servers()?;
        let call = Call {
            id: Uuid::new_v4().to_string(),
            conv: conv.to_string(),
            peer,
            peer_dev: None,
            outgoing: true,
            video: p["video"].as_bool().unwrap_or(false),
            phase: Phase::Outgoing,
            offer: None,
            since: 0,
        };
        {
            let mut slot = self.slot();
            if slot.is_some() {
                return Err(Error::Invalid("Уже идёт звонок".into()));
            }
            *slot = Some(call.clone());
        }
        Ok(json!({"call": self.call_json(&call), "ice_servers": ice}))
    }

    /// Sends the offer with all ICE candidates gathered (no trickle: every
    /// signal is an E2EE message, so one round trip is cheaper than many).
    pub fn call_offer(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let sdp = s(p, "sdp")?;
        if sdp.len() > MAX_SDP {
            return Err(Error::Invalid("Слишком длинное описание звонка".into()));
        }
        let c = self.current(id)?;
        if c.phase != Phase::Outgoing {
            return Err(Error::Invalid("Звонок уже начат".into()));
        }
        if let Err(e) = self.send_signal(&c.conv, json!({"op": "offer", "id": id, "video": c.video, "sdp": sdp})) {
            self.take_call(id, &[Phase::Outgoing]);
            return Err(e);
        }
        self.ring_timer(id.to_string(), Phase::Outgoing);
        self.emit_call(&c, "outgoing", json!({}));
        Ok(Value::Null)
    }

    /// Accepts a ringing call; returns the peer's offer and the ICE servers.
    pub fn call_accept(&self, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let ice = self.ice_servers()?;
        let mut slot = self.slot();
        let c = slot.as_mut().filter(|c| c.id == id && c.phase == Phase::Incoming).ok_or_else(|| Error::Invalid("Звонок уже завершён".into()))?;
        c.phase = Phase::Connecting;
        Ok(json!({"sdp": c.offer.take().unwrap_or_default(), "video": c.video, "ice_servers": ice}))
    }

    pub fn call_answer(&self, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let sdp = s(p, "sdp")?;
        if sdp.len() > MAX_SDP {
            return Err(Error::Invalid("Слишком длинное описание звонка".into()));
        }
        let c = self.current(id)?;
        if c.phase != Phase::Connecting || c.outgoing {
            return Err(Error::Invalid("Звонок уже завершён".into()));
        }
        self.send_signal(&c.conv, json!({"op": "answer", "id": id, "sdp": sdp, "to": c.peer_dev}))?;
        self.emit_call(&c, "connecting", json!({}));
        Ok(Value::Null)
    }

    /// Media connected: the duration counts from here.
    pub fn call_connected(&self, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let c = {
            let mut slot = self.slot();
            let c = slot.as_mut().filter(|c| c.id == id && c.phase == Phase::Connecting).ok_or_else(|| Error::Invalid("Звонок уже завершён".into()))?;
            c.phase = Phase::Active;
            c.since = now_ms();
            c.clone()
        };
        self.emit_call(&c, "active", json!({"since": c.since}));
        Ok(Value::Null)
    }

    /// Ends the call from this side: cancel, decline or hang up. `reason`
    /// "failed" marks a media failure.
    pub fn call_hangup(&self, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let Some(c) = self.take_call(id, &[Phase::Outgoing, Phase::Incoming, Phase::Connecting, Phase::Active]) else {
            return Ok(Value::Null);
        };
        let (op, status, reason) = match c.phase {
            Phase::Incoming => ("reject", "declined", "declined"),
            Phase::Outgoing => ("hangup", "cancelled", "cancelled"),
            _ if p["reason"] == "failed" => ("hangup", "failed", "failed"),
            _ => ("hangup", "ended", "hangup"),
        };
        let duration = if c.phase == Phase::Active { (now_ms() - c.since) / 1000 } else { 0 };
        let sent = self.send_signal(&c.conv, json!({"op": op, "id": c.id, "to": c.peer_dev}));
        self.log_call(&c, if status == "failed" { "ended" } else { status }, duration)?;
        self.emit_call(&c, "ended", json!({"reason": reason}));
        sent.map(|_| Value::Null)
    }

    /// Camera on/off, so the peer shows the avatar instead of a frozen frame.
    pub fn call_media(&self, p: &Value) -> Result<Value> {
        let id = s(p, "call_id")?;
        let c = self.current(id)?;
        self.send_signal(&c.conv, json!({"op": "media", "id": id, "video": p["video"].as_bool().unwrap_or(false), "to": c.peer_dev}))?;
        Ok(Value::Null)
    }

    pub fn call_current(&self) -> Result<Value> {
        Ok(self.slot().clone().map(|c| self.call_json(&c)).unwrap_or(Value::Null))
    }

    /// Recent calls from the local logs, newest first.
    pub fn list_calls(&self, p: &Value) -> Result<Value> {
        let limit = p["limit"].as_i64().unwrap_or(50).clamp(1, 200);
        let rows: Vec<(String, String, bool, String, i64)> = {
            let db = self.db();
            let mut st = db.conn.prepare("SELECT id, conversation_id, outgoing, body, sent_at FROM messages WHERE kind='call' ORDER BY sent_at DESC LIMIT ?1")?;
            st.query_map([limit], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? == 1, r.get(3)?, r.get(4)?)))?.collect::<std::result::Result<_, _>>()?
        };
        let mut out = Vec::with_capacity(rows.len());
        for (id, conv, outgoing, body, at) in rows {
            let c: Value = serde_json::from_str(&body).unwrap_or(json!({}));
            let info = self.conversation_json(&conv).unwrap_or(Value::Null);
            let (video, status, duration) = (c["video"] == true, c["status"].as_str().unwrap_or("").to_string(), c["duration"].as_i64().unwrap_or(0));
            out.push(json!({
                "call_id": id, "conversation_id": conv, "peer_name": info["title"], "avatar_path": info["avatar_path"],
                "peer_user_id": info["peer_user_id"], "outgoing": outgoing, "video": video, "status": status, "duration": duration,
                "missed": !outgoing && status == "missed", "text": call_text(outgoing, video, &status, duration), "at": at,
            }));
        }
        Ok(Value::Array(out))
    }

    // ---- incoming signals ----

    /// Applies the call signals of one sync pass in order. Knowing the whole
    /// batch lets an offer that was already cancelled or answered elsewhere
    /// become a log entry instead of a brief ring.
    pub fn apply_call_signals(self: &Arc<Self>, sigs: Vec<Signal>) -> Result<()> {
        if sigs.is_empty() {
            return Ok(());
        }
        let (me, my_dev) = self.me()?;
        for (i, sg) in sigs.iter().enumerate() {
            let id = sg.body["id"].as_str().unwrap_or_default();
            if id.is_empty() || id.len() > 64 {
                continue;
            }
            let to_me = sg.body["to"].as_str().is_none_or(|t| t == my_dev);
            let later: Vec<&Signal> = sigs[i + 1..].iter().filter(|x| x.body["id"] == id).collect();
            match sg.body["op"].as_str().unwrap_or_default() {
                "offer" if sg.sender != me => self.on_offer(sg, id, &later, &me)?,
                "answer" if sg.sender != me && to_me => {
                    let c = {
                        let mut slot = self.slot();
                        match slot.as_mut().filter(|c| c.id == id && c.phase == Phase::Outgoing && c.peer == sg.sender) {
                            Some(c) => {
                                c.phase = Phase::Connecting;
                                c.peer_dev = Some(sg.sdev.clone());
                                Some(c.clone())
                            }
                            None => None,
                        }
                    };
                    if let Some(c) = c {
                        let sdp = sg.body["sdp"].as_str().unwrap_or_default();
                        self.emit_call(&c, "answered", json!({"sdp": sdp}));
                    }
                }
                // Another of our devices took or declined the call: stop ringing here.
                "answer" | "reject" if sg.sender == me => {
                    if let Some(c) = self.take_call(id, &[Phase::Incoming]) {
                        let reason = if sg.body["op"] == "answer" { "answered_elsewhere" } else { "declined_elsewhere" };
                        self.emit_call(&c, "ended", json!({"reason": reason}));
                    }
                }
                op @ ("reject" | "busy") if to_me => {
                    if let Some(c) = self.take_peer_call(id, &sg.sender, &sg.sdev, &[Phase::Outgoing]) {
                        let status = if op == "busy" { "busy" } else { "declined" };
                        self.log_call(&c, status, 0)?;
                        self.emit_call(&c, "ended", json!({"reason": status}));
                    }
                }
                "hangup" if sg.sender != me => {
                    if let Some(c) = self.take_peer_call(id, &sg.sender, &sg.sdev, &[Phase::Incoming, Phase::Connecting, Phase::Active]) {
                        if c.phase == Phase::Incoming {
                            self.log_call(&c, "missed", 0)?;
                            self.emit_call(&c, "ended", json!({"reason": "missed"}));
                        } else {
                            let duration = if c.phase == Phase::Active { (now_ms() - c.since) / 1000 } else { 0 };
                            self.log_call(&c, "ended", duration)?;
                            self.emit_call(&c, "ended", json!({"reason": "hangup"}));
                        }
                    }
                }
                "media" if sg.sender != me => {
                    if self.slot().as_ref().is_some_and(|c| c.id == id && c.peer == sg.sender) {
                        self.emit(json!({"type": "call_media", "call_id": id, "video": sg.body["video"].as_bool().unwrap_or(false)}));
                    }
                }
                _ => {}
            }
        }
        Ok(())
    }

    /// Takes the call if the signal comes from its peer (and, once known, the peer's device).
    fn take_peer_call(&self, id: &str, sender: &str, sdev: &str, phases: &[Phase]) -> Option<Call> {
        let mut slot = self.slot();
        let ok = slot.as_ref().is_some_and(|c| {
            c.id == id && c.peer == sender && phases.contains(&c.phase) && c.peer_dev.as_deref().is_none_or(|d| d == sdev)
        });
        if ok { slot.take() } else { None }
    }

    fn on_offer(self: &Arc<Self>, sg: &Signal, id: &str, later: &[&Signal], me: &str) -> Result<()> {
        let sdp = sg.body["sdp"].as_str().unwrap_or_default();
        let video = sg.body["video"].as_bool().unwrap_or(false);
        // Only the peer of a direct chat may call through it.
        let direct: Option<String> = self
            .db()
            .conn
            .query_row("SELECT peer_user_id FROM conversations WHERE id=?1 AND kind='direct'", [&sg.conv], |r| r.get(0))
            .optional()?
            .flatten();
        if direct.as_deref() != Some(sg.sender.as_str()) || sdp.is_empty() || sdp.len() > MAX_SDP {
            return Ok(());
        }
        let call = Call {
            id: id.to_string(),
            conv: sg.conv.clone(),
            peer: sg.sender.clone(),
            peer_dev: Some(sg.sdev.clone()),
            outgoing: false,
            video,
            phase: Phase::Incoming,
            offer: Some(sdp.to_string()),
            since: 0,
        };
        if later.iter().any(|x| x.sender == me && x.body["op"] == "answer") {
            return Ok(()); // answered on another of our devices, which keeps the log
        }
        let ended = later.iter().any(|x| x.sender == sg.sender && x.body["op"] == "hangup");
        if ended || now_ms() - sg.ts > STALE_MS {
            return self.log_call(&call, "missed", 0);
        }
        {
            let mut slot = self.slot();
            match slot.as_ref() {
                Some(c) if c.id == id => return Ok(()),
                Some(_) => {}
                None => {
                    *slot = Some(call.clone());
                    drop(slot);
                    self.ring_timer(id.to_string(), Phase::Incoming);
                    self.emit_call(&call, "incoming", json!({}));
                    // The relay credentials are fetched while the phone rings, so answering does not wait for them.
                    let me = self.clone();
                    let _ = std::thread::Builder::new().name("neocore-ice".into()).spawn(move || {
                        let _ = me.ice_servers();
                    });
                    return Ok(());
                }
            }
        }
        // Already in another call.
        self.send_signal(&call.conv, json!({"op": "busy", "id": id, "to": sg.sdev}))?;
        self.log_call(&call, "missed", 0)
    }

    /// Writes the local call log entry (once per call) into the chat.
    fn log_call(&self, c: &Call, status: &str, duration: i64) -> Result<()> {
        let (me, _) = self.me()?;
        let missed = !c.outgoing && status == "missed";
        let text = call_text(c.outgoing, c.video, status, duration);
        let now = now_ms();
        let inserted = {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            let n = tx.execute(
                "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, outgoing, kind, body, status, sent_at) VALUES (?1, ?2, ?3, ?4, 'call', ?5, ?6, ?7)",
                params![
                    c.id,
                    c.conv,
                    if c.outgoing { me.as_str() } else { c.peer.as_str() },
                    c.outgoing as i64,
                    json!({"video": c.video, "status": status, "duration": duration}).to_string(),
                    if c.outgoing { "sent" } else { "received" },
                    now
                ],
            )?;
            if n > 0 {
                tx.execute(
                    "UPDATE conversations SET hidden=0, last_activity=?2, last_preview=?3, unread=unread+?4 WHERE id=?1",
                    params![c.conv, now, format!("📞 {text}"), missed as i64],
                )?;
            }
            tx.commit()?;
            n > 0
        };
        if inserted {
            self.emit(json!({"type": "messages_changed", "conversation_id": c.conv}));
            self.emit(json!({"type": "conversations_changed"}));
            if missed {
                let title = self.conversation_json(&c.conv).ok().and_then(|v| v["title"].as_str().map(str::to_string)).unwrap_or_default();
                self.emit(json!({"type": "notify", "conversation_id": c.conv, "title": title, "text": text}));
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::call_text;

    #[test]
    fn log_texts() {
        assert_eq!(call_text(true, false, "ended", 151), "Исходящий звонок · 2:31");
        assert_eq!(call_text(false, true, "ended", 0), "Входящий видеозвонок");
        assert_eq!(call_text(false, false, "missed", 0), "Пропущенный звонок");
        assert_eq!(call_text(true, false, "busy", 0), "Абонент занят");
        assert_eq!(call_text(true, true, "no_answer", 0), "Исходящий видеозвонок · нет ответа");
        assert_eq!(call_text(true, false, "cancelled", 0), "Отменённый звонок");
        assert_eq!(call_text(false, false, "declined", 0), "Отклонённый звонок");
    }
}
