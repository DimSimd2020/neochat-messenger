//! Background worker: sync of the device event feed, outbox flush, prekey
//! maintenance and the WebSocket that only hints "sync now".

use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use futures::executor::block_on;
use libsignal_protocol::{
    CiphertextMessage, CiphertextMessageType, GenericSignedPreKey, PreKeySignalMessage, SignalMessage, message_decrypt,
};
use rusqlite::params;
use serde_json::{Value, json};

use crate::engine::{Inner, STATE_CONNECTING, STATE_OFFLINE, STATE_ONLINE, s};
use crate::error::{Error, Result};
use crate::keys::{self, PREKEY_BATCH, unb64};
use crate::outbox::{FileMeta, Payload, address, preview};
use crate::store::{SignalStore, kv_get_str, kv_set, now_ms};

pub const SYSTEM_CHAT: &str = "neochat-system";
const SIGNED_PREKEY_ROTATE_MS: i64 = 7 * 24 * 3600 * 1000;
const SIGNED_PREKEY_KEEP_MS: i64 = 30 * 24 * 3600 * 1000;

#[derive(Default)]
struct CycleStats {
    new_messages: usize,
    receipts: BTreeMap<String, Vec<String>>,
    notify: std::collections::BTreeSet<String>,
    calls: Vec<crate::calls::Signal>,
}

impl Inner {
    pub fn worker_loop(self: Arc<Self>) {
        let mut ws: Option<std::thread::JoinHandle<()>> = None;
        while !self.shutdown.load(Ordering::SeqCst) {
            if self.logged_in() {
                if self.ws_mode().is_some() && !self.ws_active.load(Ordering::SeqCst) {
                    if let Some(h) = ws.take() {
                        let _ = h.join();
                    }
                    self.ws_active.store(true, Ordering::SeqCst);
                    let me = self.clone();
                    ws = std::thread::Builder::new().name("neocore-ws".into()).spawn(move || me.ws_loop()).ok();
                }
                let _ = self.run_cycle();
                self.spawn_history_upload();
                self.retry_missing_avatars();
                // Installations from before profiles learn the display name and numeric ID once.
                if kv_get_str(&self.db().conn, "number").ok().flatten().unwrap_or_default().is_empty() {
                    let _ = self.refresh_profile();
                }
            }
            let fg = self.foreground.load(Ordering::SeqCst);
            self.wait(if fg { Duration::from_secs(30) } else { Duration::from_secs(300) });
        }
        if let Some(h) = ws {
            let _ = h.join();
        }
    }

    /// One full cycle, also used by platform background tasks.
    pub fn sync_now(self: &Arc<Self>) -> Result<Value> {
        if !self.logged_in() {
            return Err(Error::NotLoggedIn);
        }
        let n = self.run_cycle()?;
        let _ = self.maybe_upload_history();
        Ok(json!({"new_messages": n}))
    }

    fn run_cycle(self: &Arc<Self>) -> Result<usize> {
        let _g = self.cycle_lock.lock().unwrap_or_else(|e| e.into_inner());
        let res = (|| {
            // Outgoing first: a just-typed message should not wait for the incoming feed.
            self.flush_outbox()?;
            let n = self.sync_events()?;
            self.flush_outbox()?;
            // Contacts, pins and preferences from the other own devices.
            if let Err(e @ Error::Network(_)) = self.sync_account_state() {
                return Err(e);
            }
            self.maintain_prekeys()?;
            Ok(n)
        })();
        match &res {
            Ok(_) => self.set_conn(STATE_ONLINE),
            Err(Error::Network(_)) => self.set_conn(STATE_OFFLINE),
            Err(_) => {}
        }
        res
    }

    fn sync_events(self: &Arc<Self>) -> Result<usize> {
        let mut stats = CycleStats::default();
        loop {
            let cursor = kv_get_str(&self.db().conn, "cursor")?.unwrap_or_default();
            let res = match self.authed("GET", &format!("/v1/sync?cursor={cursor}&limit=100"), None) {
                Err(e) if e.is_api("sync_reset_required") || e.is_api("cursor_expired") => {
                    // The server restored a backup or expired our feed: start a new cursor.
                    // Messages that were never delivered cannot be recovered.
                    crate::store::kv_del(&self.db().conn, "cursor")?;
                    self.emit(json!({"type": "sync_reset"}));
                    continue;
                }
                r => r?,
            };
            for ev in res["events"].as_array().cloned().unwrap_or_default() {
                self.handle_event(&ev, &mut stats)?;
            }
            // The cursor advances only after every event in the batch is committed.
            if let Some(next) = res["next_cursor"].as_str() {
                kv_set(&self.db().conn, "cursor", next.as_bytes())?;
            }
            if !res["has_more"].as_bool().unwrap_or(false) {
                break;
            }
        }
        for (conv, ids) in std::mem::take(&mut stats.receipts) {
            self.enqueue_receipt(&conv, "delivered", ids)?;
        }
        self.apply_call_signals(std::mem::take(&mut stats.calls))?;
        Ok(stats.new_messages)
    }

    fn processed(&self, event_id: &str) -> Result<bool> {
        Ok(self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM processed WHERE event_id=?1)", [event_id], |r| r.get(0))?)
    }

    fn mark_processed(&self, event_id: &str) -> Result<()> {
        self.db().conn.execute("INSERT OR IGNORE INTO processed(event_id, at) VALUES (?1, ?2)", params![event_id, now_ms()])?;
        Ok(())
    }

    fn handle_event(self: &Arc<Self>, ev: &Value, stats: &mut CycleStats) -> Result<()> {
        let event_id = s(ev, "event_id")?;
        if self.processed(event_id)? {
            return Ok(());
        }
        match ev["kind"].as_str().unwrap_or("") {
            "message" => self.handle_message(ev, stats),
            "membership" => {
                let cid = ev["payload"]["conversation_id"].as_str().unwrap_or_default().to_string();
                match self.fetch_conversation(&cid) {
                    Ok(()) => {}
                    Err(e @ Error::Network(_)) => return Err(e),
                    Err(e) => self.emit(json!({"type": "warning", "conversation_id": cid, "message": e.message()})),
                }
                self.mark_processed(event_id)?;
                self.emit(json!({"type": "conversations_changed"}));
                Ok(())
            }
            "profile" => {
                let uid = ev["payload"]["user_id"].as_str().unwrap_or_default().to_string();
                let (me, _) = self.me()?;
                match if uid == me { self.refresh_profile() } else { self.refresh_user(&uid) } {
                    Err(e @ Error::Network(_)) => return Err(e),
                    _ => {}
                }
                self.mark_processed(event_id)?;
                self.emit(json!({"type": "contacts_changed"}));
                self.emit(json!({"type": "conversations_changed"}));
                Ok(())
            }
            "roster" | "identity" => {
                let uid = ev["payload"]["user_id"].as_str().unwrap_or_default().to_string();
                let (me, _) = self.me()?;
                let res = if uid == me { self.refresh_own_roster() } else { self.refresh_user(&uid) };
                if uid == me && res.is_ok() {
                    // A device may have just joined: give it the latest history at once.
                    kv_set(&self.db().conn, "history_force", b"1")?;
                }
                match res {
                    Ok(()) => {}
                    Err(e @ Error::Network(_)) => return Err(e),
                    Err(Error::Api { status: 404, .. }) => {}
                    Err(e) => self.emit(json!({"type": "warning", "message": e.message()})),
                }
                self.mark_processed(event_id)?;
                self.emit(json!({"type": "contacts_changed"}));
                self.emit(json!({"type": "conversations_changed"}));
                Ok(())
            }
            "history" => {
                let fresh: i64 = kv_get_str(&self.db().conn, "history_fresh_until")?.and_then(|v| v.parse().ok()).unwrap_or(0);
                if fresh > crate::store::now_ms() {
                    if let Some(key) = self.backup_key()? {
                        match self.restore_history(&key) {
                            Ok(()) => self.emit(json!({"type": "conversations_changed"})),
                            Err(e @ Error::Network(_)) => return Err(e),
                            Err(_) => {}
                        }
                    }
                }
                self.mark_processed(event_id)
            }
            "state" => {
                // Another own device changed the shared account state.
                kv_set(&self.db().conn, "state_pull", b"1")?;
                self.mark_processed(event_id)
            }
            "notice" => {
                self.add_notice(event_id, ev["payload"]["text"].as_str().unwrap_or_default())?;
                self.mark_processed(event_id)?;
                Ok(())
            }
            _ => self.mark_processed(event_id),
        }
    }

    /// Server notices (sign-in codes, new devices) land in the read-only system chat.
    fn add_notice(&self, id: &str, text: &str) -> Result<()> {
        if text.trim().is_empty() {
            return Ok(());
        }
        let now = now_ms();
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        tx.execute(
            "INSERT OR IGNORE INTO conversations(id, kind, title, members) VALUES (?1, 'system', 'NeoChat уведомления', '[]')",
            [SYSTEM_CHAT],
        )?;
        let n = tx.execute(
            "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, outgoing, kind, body, status, sent_at) VALUES (?1, ?2, 'system', 0, 'text', ?3, 'received', ?4)",
            params![id, SYSTEM_CHAT, text.chars().take(4096).collect::<String>(), now],
        )?;
        if n > 0 {
            tx.execute(
                "UPDATE conversations SET hidden=0, last_activity=?2, last_preview=?3, unread=unread+1 WHERE id=?1",
                params![SYSTEM_CHAT, now, preview(text)],
            )?;
        }
        tx.commit()?;
        drop(db);
        self.emit(json!({"type": "messages_changed", "conversation_id": SYSTEM_CHAT}));
        self.emit(json!({"type": "conversations_changed"}));
        Ok(())
    }

    fn refresh_own_roster(&self) -> Result<()> {
        let (me, my_dev) = self.me()?;
        let u = self.authed("GET", &format!("/v1/users/{me}"), None)?;
        let pinned = crate::store::kv_get(&self.db().conn, "account_key")?.ok_or(Error::NotLoggedIn)?;
        let key = unb64(s(&u, "account_identity_key")?)?;
        if key != pinned {
            return Err(Error::Crypto("Ключ аккаунта изменился: аккаунт восстановлен на другом устройстве".into()));
        }
        let blob = unb64(u["roster"]["blob"].as_str().unwrap_or_default())?;
        let sig = unb64(u["roster"]["signature"].as_str().unwrap_or_default())?;
        let r = keys::verify_roster(&key, &blob, &sig)?;
        let cur: u64 = kv_get_str(&self.db().conn, "roster_revision")?.and_then(|v| v.parse().ok()).unwrap_or(0);
        if r.revision < cur {
            return Err(Error::Crypto("Сервер вернул устаревший список устройств".into()));
        }
        if !r.devices.iter().any(|d| d.device_id == my_dev) {
            // This device was revoked by the primary.
            self.emit(json!({"type": "device_revoked"}));
        }
        let db = self.db();
        self.store_own_roster(&db.conn, &r.revision.to_string(), &blob, &sig, &key)?;
        drop(db);
        self.emit(json!({"type": "devices_changed"}));
        Ok(())
    }

    fn handle_message(self: &Arc<Self>, ev: &Value, stats: &mut CycleStats) -> Result<()> {
        let event_id = s(ev, "event_id")?.to_string();
        let conv = s(ev, "conversation_id")?.to_string();
        let sender = s(ev, "sender_user_id")?.to_string();
        let sdev = s(ev, "sender_device_id")?.to_string();
        let (me, my_dev) = self.me()?;
        // Network preparation outside the transaction.
        let known_dev: bool =
            self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM known_devices WHERE user_id=?1 AND device_id=?2)", [&sender, &sdev], |r| r.get(0))?;
        if !known_dev {
            let r = if sender == me { self.refresh_own_roster() } else { self.refresh_user(&sender) };
            if let Err(e @ Error::Network(_)) = r {
                return Err(e);
            }
        }
        if !self.conversation_known(&conv)? {
            match self.fetch_conversation(&conv) {
                Err(e @ Error::Network(_)) => return Err(e),
                Err(e) => self.emit(json!({"type": "warning", "conversation_id": conv, "message": e.message()})),
                Ok(()) => {}
            }
        }
        let wire = unb64(ev["ciphertext"].as_str().unwrap_or_default())?;

        let mut db = self.db();
        let tx = db.conn.transaction()?;
        let done: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM processed WHERE event_id=?1)", [&event_id], |r| r.get(0))?;
        if done {
            return Ok(());
        }
        let decrypted: Result<Vec<u8>> = (|| {
            let (ty, body) = wire.split_first().ok_or_else(|| Error::Crypto("Пустое зашифрованное сообщение".into()))?;
            let msg = match *ty {
                t if t == CiphertextMessageType::PreKey as u8 => CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(body)?),
                t if t == CiphertextMessageType::Whisper as u8 => CiphertextMessage::SignalMessage(SignalMessage::try_from(body)?),
                _ => return Err(Error::Crypto("Неизвестный тип зашифрованного сообщения".into())),
            };
            let mut sessions = SignalStore::new(&tx)?;
            let mut ids = SignalStore::new(&tx)?;
            let mut pre = SignalStore::new(&tx)?;
            let signed = SignalStore::new(&tx)?;
            let mut kyber = SignalStore::new(&tx)?;
            Ok(block_on(message_decrypt(&msg, &address(&sdev), &address(&my_dev), &mut sessions, &mut ids, &mut pre, &signed, &mut kyber, &mut rand::rng()))?)
        })();
        let payload: Result<Payload> = decrypted.and_then(|pt| {
            let p: Payload = serde_json::from_slice(&pt)?;
            // Routing fields inside the ciphertext must match the server's envelope.
            if p.cid != conv || p.sender != sender || p.sdev != sdev || p.v != 1 {
                return Err(Error::Crypto("Сообщение не соответствует маршруту".into()));
            }
            Ok(p)
        });
        let now = now_ms();
        let mut changed_conv = false;
        // Group and poll controls change state through helpers that lock the db: applied after commit.
        let mut control: Option<(String, Value)> = None;
        let mut deleted = None;
        let payload = payload.map(|p| {
            if crate::groups::may_post(&tx, &conv, &sender, &p.kind) { Some(p) } else { None }
        });
        match payload {
            Ok(None) => {}
            Err(e) => {
                // Kept as a diagnosable placeholder; the cursor still advances.
                tx.execute(
                    "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, status, error, sent_at)
                     VALUES (?1, ?2, ?3, ?4, 0, 'undecryptable', '', 'received', ?5, ?6)",
                    params![s(ev, "message_id")?, conv, sender, sdev, e.to_string(), now],
                )?;
                changed_conv = true;
            }
            Ok(Some(p)) => match p.kind.as_str() {
                "text" | "file" | "poll" if tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM deleted_messages WHERE message_id=?1 AND conversation_id=?2 AND sender_user_id=?3)",
                    params![p.mid, conv, sender], |r| r.get::<_, bool>(0),
                )? => {}
                "text" | "file" | "poll" => {
                    let poll = if p.kind == "poll" { crate::groups::clean_poll(&p) } else { None };
                    if p.kind == "poll" && poll.is_none() {
                        tx.execute("INSERT OR IGNORE INTO processed(event_id, at) VALUES (?1, ?2)", params![event_id, now])?;
                        tx.commit()?;
                        return Ok(());
                    }
                    let outgoing = sender == me;
                    let mentioned = crate::mentions::incoming(&tx, &p, &me)?;
                    let fwd = p.fwd.as_ref().and_then(|f| {
                        let name = f["name"].as_str().unwrap_or("").chars().take(100).collect::<String>();
                        (!name.is_empty()).then(|| json!({"name": name, "ts": f["ts"].as_i64().unwrap_or(0)}).to_string())
                    });
                    let inserted = tx.execute(
                        "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, reply_to, file, status, server_id, sent_at, poll, fwd, mentioned)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
                        params![
                            p.mid,
                            conv,
                            sender,
                            sdev,
                            outgoing as i64,
                            p.kind,
                            p.text.clone().unwrap_or_default().chars().take(4096).collect::<String>(),
                            p.reply_to,
                            p.file.as_ref().map(|f| serde_json::to_string(&FileMeta { name: crate::files::safe_name(&f.name), ..f.clone() })).transpose()?,
                            if outgoing { "sent" } else { "received" },
                            s(ev, "message_id")?,
                            p.ts.min(now + 300_000),
                            poll.map(|v| v.to_string()),
                            fwd,
                            mentioned as i64,
                        ],
                    )?;
                    if inserted > 0 {
                        let pv = match &p.file {
                            Some(f) if f.thumb.is_some() && f.mime.starts_with("image/") => "🖼 Фото".to_string(),
                            Some(f) => format!("📎 {}", f.name),
                            None if p.kind == "poll" => format!("📊 {}", p.text.as_deref().unwrap_or("")),
                            None => preview(p.text.as_deref().unwrap_or("")),
                        };
                        tx.execute(
                            "UPDATE conversations SET hidden=0, last_activity=?2, last_preview=?3, unread=unread+?4 WHERE id=?1",
                            params![conv, now, pv, (!outgoing) as i64],
                        )?;
                        if !outgoing {
                            stats.new_messages += 1;
                            // Channels have no receipts: they would go to every subscriber.
                            let channel: bool = tx.query_row("SELECT kind='channel' FROM conversations WHERE id=?1", [&conv], |r| r.get(0)).unwrap_or(false);
                            if !channel {
                                stats.receipts.entry(conv.clone()).or_default().push(p.mid.clone());
                            } else {
                                stats.receipts.entry(conv.clone()).or_default();
                                stats.notify.insert(conv.clone());
                            }
                        }
                        changed_conv = true;
                    }
                }
                "receipt" => {
                    if let Some(r) = &p.receipt {
                        if r.kind == "delivered" || r.kind == "read" {
                            for id in r.ids.iter().take(500) {
                                tx.execute(
                                    "INSERT OR IGNORE INTO receipts(message_id, user_id, kind, at) SELECT ?1, ?2, ?3, ?4 WHERE EXISTS(SELECT 1 FROM messages WHERE id=?1 AND conversation_id=?5)",
                                    params![id, sender, r.kind, now, conv],
                                )?;
                                if sender != me {
                                    let new_status = if r.kind == "read" { "read" } else { "delivered" };
                                    tx.execute(
                                        "UPDATE messages SET status=?2 WHERE id=?1 AND outgoing=1 AND conversation_id=?3 AND (status IN ('sent','queued') OR (status='delivered' AND ?2='read'))",
                                        params![id, new_status, conv],
                                    )?;
                                }
                            }
                            if sender == me && r.kind == "read" {
                                // Read on another of our devices.
                                tx.execute("UPDATE conversations SET unread=0 WHERE id=?1", [&conv])?;
                                changed_conv = true;
                            }
                        }
                    }
                }
                "delete" => {
                    if let Some(g) = &p.group {
                        if Self::apply_delete(&tx, &conv, &sender, g)? {
                            changed_conv = true;
                            deleted = g["id"].as_str().map(str::to_string);
                        }
                    }
                }
                "call" => {
                    if let Some(body) = p.call.clone() {
                        stats.calls.push(crate::calls::Signal { sender: sender.clone(), sdev: sdev.clone(), conv: conv.clone(), ts: p.ts, body });
                    }
                }
                "group_update" | "poll_vote" | "poll_close" | "reaction" | "edit" => {
                    if let Some(g) = p.group.clone() {
                        control = Some((p.kind.clone(), g));
                    }
                }
                _ => {}
            },
        }
        tx.execute("INSERT OR IGNORE INTO processed(event_id, at) VALUES (?1, ?2)", params![event_id, now])?;
        tx.commit()?;
        drop(db);
        if let Some(id) = deleted {
            let _ = std::fs::remove_file(self.data_dir.join("files").join(format!("{id}.enc")));
        }
        if let Some((kind, g)) = control {
            let applied = match kind.as_str() {
                "group_update" => self.apply_group_update(&conv, &sender, &g),
                "reaction" => self.apply_reaction(&conv, &sender, &g),
                "edit" => Self::apply_edit(&self.db().conn, &conv, &sender, &g, now_ms()),
                _ => self.apply_poll_control(&conv, &sender, &kind, &g),
            };
            changed_conv |= applied.unwrap_or(false) && matches!(kind.as_str(), "group_update" | "edit");
        }
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        if changed_conv {
            self.emit(json!({"type": "conversations_changed"}));
        }
        if (stats.receipts.get(&conv).is_some_and(|v| !v.is_empty()) || stats.notify.remove(&conv)) && sender != me {
            let title = self.conversation_json(&conv).ok().and_then(|c| c["title"].as_str().map(str::to_string)).unwrap_or_default();
            let mention = self.conversation_json(&conv).ok().is_some_and(|c| c["mention_count"].as_i64().unwrap_or(0) > 0);
            self.emit(json!({"type": "notify", "conversation_id": conv, "title": title, "text":if mention { "Вас упомянули" } else { "Новое сообщение" }}));
        }
        Ok(())
    }

    /// Keeps enough one-time prekeys on the server and rotates the signed prekey.
    /// New private keys are committed locally before they are published.
    fn maintain_prekeys(&self) -> Result<()> {
        // Prekeys drain slowly; checking every 10 minutes saves a request per cycle.
        let checked: i64 = kv_get_str(&self.db().conn, "prekeys_checked_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
        if now_ms() - checked < 10 * 60 * 1000 {
            return Ok(());
        }
        let counts = self.authed("GET", "/v1/devices/current/prekeys", None)?;
        kv_set(&self.db().conn, "prekeys_checked_at", now_ms().to_string().as_bytes())?;
        let low = counts["one_time_prekeys"].as_u64().unwrap_or(0) < 25 || counts["kyber_one_time_prekeys"].as_u64().unwrap_or(0) < 25;
        let rotate = {
            let db = self.db();
            let newest: i64 = db.conn.query_row("SELECT COALESCE(max(created_at), 0) FROM signed_prekeys", [], |r| r.get(0))?;
            now_ms() - newest > SIGNED_PREKEY_ROTATE_MS
        };
        if !low && !rotate {
            return Ok(());
        }
        let mut body = json!({"one_time_prekeys": [], "kyber_one_time_prekeys": []});
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            let identity = keys::local_identity(&tx)?;
            if low {
                let start: u32 = kv_get_str(&tx, "next_prekey_id")?.and_then(|v| v.parse().ok()).unwrap_or(2);
                let (ec, ky) = keys::new_one_time_prekeys(&identity, start, PREKEY_BATCH)?;
                keys::save_one_time(&tx, &ec, &ky)?;
                kv_set(&tx, "next_prekey_id", (start + PREKEY_BATCH).to_string().as_bytes())?;
                let (e, k) = keys::prekeys_json(&ec, &ky)?;
                body["one_time_prekeys"] = e;
                body["kyber_one_time_prekeys"] = k;
            }
            if rotate {
                let id: u32 = kv_get_str(&tx, "next_signed_prekey_id")?.and_then(|v| v.parse().ok()).unwrap_or(2);
                let sp = keys::new_signed_prekey(&identity, id)?;
                tx.execute(
                    "INSERT OR REPLACE INTO signed_prekeys(id, record, created_at) VALUES (?1, ?2, ?3)",
                    params![id, sp.serialize()?, now_ms()],
                )?;
                kv_set(&tx, "next_signed_prekey_id", (id + 1).to_string().as_bytes())?;
                // Old signed prekeys stay for in-flight session setups, then expire.
                tx.execute("DELETE FROM signed_prekeys WHERE created_at < ?1 AND id <> ?2", params![now_ms() - SIGNED_PREKEY_KEEP_MS, id])?;
                body["signed_prekey"] = keys::signed_prekey_json(&sp)?;
            }
            tx.commit()?;
        }
        self.authed("POST", "/v1/devices/current/prekeys", Some(&body))?;
        Ok(())
    }

    // ---- WebSocket hints ----

    /// Whether the event socket should be open: Some(true) for a background
    /// connection that does not show the user online.
    fn ws_mode(&self) -> Option<bool> {
        if self.foreground.load(Ordering::SeqCst) {
            Some(false)
        } else if self.background.load(Ordering::SeqCst) {
            Some(true)
        } else {
            None
        }
    }

    fn ws_loop(self: Arc<Self>) {
        let mut backoff = 1u64;
        while !self.shutdown.load(Ordering::SeqCst) && self.ws_mode().is_some() && self.logged_in() {
            match self.ws_session() {
                Ok(()) => backoff = 1,
                Err(_) => {
                    let wait = backoff.min(60);
                    backoff = (backoff * 2).min(60);
                    for _ in 0..wait {
                        if self.shutdown.load(Ordering::SeqCst) || self.ws_mode().is_none() {
                            break;
                        }
                        std::thread::sleep(Duration::from_secs(1));
                    }
                }
            }
        }
        self.ws_active.store(false, Ordering::SeqCst);
    }

    fn ws_session(&self) -> Result<()> {
        use tungstenite::client::IntoClientRequest;
        let server = kv_get_str(&self.db().conn, "server")?.ok_or(Error::NotLoggedIn)?;
        let background = self.ws_mode() == Some(true);
        let url = server.replacen("https://", "wss://", 1).replacen("http://", "ws://", 1) + if background { "/v1/events?mode=background" } else { "/v1/events" };
        let token = self.access_token()?;
        let mut req = url.into_client_request().map_err(|e| Error::Network(e.to_string()))?;
        req.headers_mut().insert("Authorization", format!("Bearer {token}").parse().map_err(|_| Error::Invalid("Некорректный токен входа".into()))?);
        if self.conn_state.load(Ordering::SeqCst) != STATE_ONLINE {
            self.set_conn(STATE_CONNECTING);
        }
        let (mut sock, _) = tungstenite::connect(req).map_err(|e| Error::Network(e.to_string()))?;
        match sock.get_mut() {
            tungstenite::stream::MaybeTlsStream::Plain(s) => s.set_read_timeout(Some(Duration::from_secs(1)))?,
            tungstenite::stream::MaybeTlsStream::Rustls(s) => s.get_mut().set_read_timeout(Some(Duration::from_secs(1)))?,
            _ => {}
        }
        self.set_conn(STATE_ONLINE);
        let mut last = std::time::Instant::now();
        loop {
            // Coming to the foreground reconnects so the user shows online, and vice versa.
            if self.shutdown.load(Ordering::SeqCst) || self.ws_mode() != Some(background) {
                let _ = sock.close(None);
                return Ok(());
            }
            match sock.read() {
                Ok(tungstenite::Message::Text(t)) => {
                    last = std::time::Instant::now();
                    match serde_json::from_str::<Value>(&t) {
                        Ok(v) if v["type"] == "typing" || v["type"] == "presence" => self.on_live_hint(&v),
                        _ => self.wake(),
                    }
                }
                Ok(tungstenite::Message::Close(_)) => return Err(Error::Network("closed".into())),
                Ok(_) => last = std::time::Instant::now(),
                Err(tungstenite::Error::Io(e)) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                    // Server pings every 30 s; silence for 75 s means a dead connection.
                    if last.elapsed() > Duration::from_secs(75) {
                        return Err(Error::Network("ws timeout".into()));
                    }
                }
                Err(e) => return Err(Error::Network(e.to_string())),
            }
        }
    }
}
