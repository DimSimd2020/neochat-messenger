//! Outgoing messages. A message is first stored with its plaintext payload; the
//! worker then encrypts it for every recipient device and stores the complete
//! request in the same transaction as the ratchet steps. Network retries resend
//! exactly that request. Only an explicit 409 recipient/revision change (which the
//! server guarantees was not committed) allows rebuilding it.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::SystemTime;

use futures::executor::block_on;
use libsignal_protocol::{
    DeviceId, IdentityKey, PreKeyBundle, ProtocolAddress, PublicKey, kem, message_encrypt, process_prekey_bundle,
};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use uuid::Uuid;

use crate::engine::{Inner, s};
use crate::error::{Error, Result, crypto};
use crate::files;
use crate::keys::{b64, unb64};
use crate::store::{SignalStore, kv_get_str, now_ms};

pub const MAX_TEXT: usize = 4096;
const PAUSED: i64 = i64::MAX;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct FileMeta {
    pub id: String,
    pub key: String,
    pub nonce: String,
    pub sha256: String,
    pub size: u64,
    pub ciphertext_size: u64,
    pub name: String,
    pub mime: String,
    /// Photos: a small JPEG (base64) and the full size, shown before the download.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub thumb: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub w: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub h: Option<u32>,
}

/// Thumbnails travel inside the E2EE payload, so they must stay small.
const MAX_THUMB_B64: usize = 48 << 10;

/// Plaintext inside every E2EE envelope. Routing fields are bound here and
/// checked against the outer event after decryption.
#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct Payload {
    pub v: u32,
    #[serde(rename = "type")]
    pub kind: String,
    pub cid: String,
    pub mid: String,
    pub sender: String,
    pub sdev: String,
    pub rev: String,
    pub ts: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reply_to: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub file: Option<FileMeta>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub receipt: Option<Receipt>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<Value>,
    /// A poll: {options, multiple, anonymous}; the question is `text`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub poll: Option<Value>,
    /// Forward header: {"name": <original sender>, "ts": <original sent_at>}.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fwd: Option<Value>,
    /// Call signaling: {op, id, ...} (see calls.rs).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub call: Option<Value>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Receipt {
    pub kind: String,
    pub ids: Vec<String>,
}

pub fn address(device_id: &str) -> ProtocolAddress {
    ProtocolAddress::new(device_id.to_string(), DeviceId::new(1).expect("valid device id"))
}

/// Pads the serialized payload to a multiple of 128 bytes to hide exact lengths.
fn pad(mut b: Vec<u8>) -> Vec<u8> {
    let target = b.len().div_ceil(128) * 128;
    b.resize(target, b' ');
    b
}

impl Inner {
    pub(crate) fn new_payload(&self, conv: &str, kind: &str) -> Result<Payload> {
        let (me, dev) = self.me()?;
        let rev: String = self.db().conn.query_row("SELECT revision FROM conversations WHERE id=?1", [conv], |r| r.get(0)).optional()?.ok_or_else(|| Error::Invalid("Беседа не найдена".into()))?;
        Ok(Payload { v: 1, kind: kind.into(), cid: conv.into(), mid: Uuid::new_v4().to_string(), sender: me, sdev: dev, rev, ts: now_ms(), ..Default::default() })
    }

    pub(crate) fn check_can_send(&self, conv: &str) -> Result<()> {
        let c = self.conversation_json(conv)?;
        if !c["key_changed_users"].as_array().map(|a| a.is_empty()).unwrap_or(true) {
            return Err(Error::KeyChanged("Ключ собеседника изменился. Сверьте код перед продолжением".into()));
        }
        if c["left"] == true {
            return Err(Error::Invalid("Вы больше не участник этой группы".into()));
        }
        if c["archived"] == true {
            return Err(Error::Invalid("Группа архивирована".into()));
        }
        if c["kind"] == "system" {
            return Err(Error::Invalid("В этот чат нельзя писать".into()));
        }
        if c["can_post"] == false {
            return Err(Error::Invalid(if c["kind"] == "channel" { "Публикуют в канале только владелец и админы" } else { "Писать в группу могут только админы" }.into()));
        }
        Ok(())
    }

    /// Stores a visible message and its outbox entry in one transaction.
    pub(crate) fn enqueue(&self, p: &Payload, visible: bool, attachment: Option<&Path>, preview: &str) -> Result<()> {
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        if visible {
            tx.execute(
                "INSERT INTO messages(id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, reply_to, file, status, sent_at, poll, fwd)
                 VALUES (?1, ?2, ?3, ?4, 1, ?5, ?6, ?7, ?8, 'queued', ?9, ?10, ?11)",
                params![
                    p.mid,
                    p.cid,
                    p.sender,
                    p.sdev,
                    p.kind,
                    p.text.clone().unwrap_or_default(),
                    p.reply_to,
                    p.file.as_ref().map(|f| serde_json::to_string(f)).transpose()?,
                    p.ts,
                    p.poll.as_ref().map(|v| v.to_string()),
                    p.fwd.as_ref().map(|v| v.to_string()),
                ],
            )?;
            tx.execute(
                "UPDATE conversations SET hidden=0, last_activity=?2, last_preview=?3, draft='' WHERE id=?1",
                params![p.cid, p.ts, preview],
            )?;
        }
        tx.execute(
            "INSERT INTO outbox(id, conversation_id, payload, attachment_path, visible, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![p.mid, p.cid, serde_json::to_vec(p)?, attachment.map(|a| a.to_string_lossy().into_owned()), visible as i64, now_ms()],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn enqueue_control(&self, conv: &str, kind: &str, group: Value) -> Result<()> {
        let mut p = self.new_payload(conv, kind)?;
        p.group = Some(group);
        self.enqueue(&p, false, None, "")?;
        self.wake();
        Ok(())
    }

    pub fn enqueue_receipt(&self, conv: &str, kind: &str, ids: Vec<String>) -> Result<()> {
        if ids.is_empty() {
            return Ok(());
        }
        let mut p = self.new_payload(conv, "receipt")?;
        p.receipt = Some(Receipt { kind: kind.into(), ids });
        self.enqueue(&p, false, None, "")
    }

    pub fn send_text(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let text = s(p, "text")?.trim_end().to_string();
        if text.trim().is_empty() {
            return Err(Error::Invalid("Пустое сообщение".into()));
        }
        if text.len() > MAX_TEXT {
            return Err(Error::Invalid("Сообщение длиннее 4096 байт".into()));
        }
        self.check_can_send(conv)?;
        let mut pl = self.new_payload(conv, "text")?;
        pl.text = Some(text.clone());
        pl.reply_to = p["reply_to"].as_str().map(str::to_string);
        pl.mentions = self.resolve_mentions(conv, &text)?;
        self.enqueue(&pl, true, None, &preview(&text))?;
        if let Some(id) = &pl.reply_to { let _ = self.mark_read(&json!({"conversation_id":conv,"message_id":id})); }
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.wake();
        self.message_json(&pl.mid)
    }

    pub fn send_file(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let src = PathBuf::from(s(p, "path")?);
        self.check_can_send(conv)?;
        if self.conversation_json(conv)?["can_media"] == false {
            return Err(Error::Invalid("Отправлять файлы здесь могут только админы".into()));
        }
        let name = files::safe_name(p["name"].as_str().map(str::to_string).unwrap_or_else(|| src.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()).as_str());
        let mut pl = self.new_payload(conv, "file")?;
        let enc_path = self.data_dir.join("outgoing").join(format!("{}.enc", pl.mid));
        let enc = files::encrypt_file(&src, &enc_path)?;
        pl.file = Some(FileMeta {
            id: String::new(),
            key: b64(&enc.key),
            nonce: b64(&enc.nonce),
            sha256: b64(&enc.ciphertext_sha256),
            size: enc.plaintext_size,
            ciphertext_size: enc.ciphertext_size,
            name: name.clone(),
            mime: p["mime"].as_str().unwrap_or("application/octet-stream").to_string(),
            thumb: p["thumb"].as_str().filter(|t| !t.is_empty() && t.len() <= MAX_THUMB_B64).map(str::to_string),
            w: p["width"].as_u64().map(|v| v.min(100_000) as u32),
            h: p["height"].as_u64().map(|v| v.min(100_000) as u32),
        });
        let photo = pl.file.as_ref().is_some_and(|f| f.thumb.is_some() && f.mime.starts_with("image/"));
        pl.text = p["caption"].as_str().map(str::to_string);
        // The sender keeps its own encrypted copy for local viewing.
        std::fs::copy(&enc_path, self.data_dir.join("files").join(format!("{}.enc", pl.mid)))?;
        let preview = if photo { "🖼 Фото".to_string() } else { format!("📎 {name}") };
        self.enqueue(&pl, true, Some(&enc_path), &preview)?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.wake();
        self.message_json(&pl.mid)
    }

    pub fn retry_message(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let n = self.db().conn.execute("UPDATE outbox SET next_at=0, attempts=0, request=NULL WHERE id=?1", [id])?;
        if n == 0 {
            return Err(Error::Invalid("Сообщение уже отправлено".into()));
        }
        self.db().conn.execute("UPDATE messages SET status='queued', error=NULL WHERE id=?1", [id])?;
        self.emit(json!({"type": "messages_changed", "conversation_id": self.message_conv(id)?}));
        self.wake();
        Ok(Value::Null)
    }

    fn message_conv(&self, id: &str) -> Result<String> {
        Ok(self.db().conn.query_row("SELECT conversation_id FROM messages WHERE id=?1", [id], |r| r.get(0))?)
    }

    fn fail_message(&self, id: &str, conv: &str, err: &Error, keep_for_retry: bool) -> Result<()> {
        let db = self.db();
        db.conn.execute("UPDATE messages SET status='failed', error=?2 WHERE id=?1", params![id, err.message()])?;
        if keep_for_retry {
            db.conn.execute("UPDATE outbox SET next_at=?2, request=NULL WHERE id=?1", params![id, PAUSED])?;
        } else {
            db.conn.execute("DELETE FROM outbox WHERE id=?1", [id])?;
        }
        drop(db);
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        Ok(())
    }

    /// Sends due outbox entries in order. Stops at the first retryable failure.
    pub fn flush_outbox(self: &Arc<Self>) -> Result<()> {
        loop {
            let row: Option<(String, String, Vec<u8>, Option<Vec<u8>>, Option<String>, Option<String>, bool, i64)> = self
                .db()
                .conn
                .query_row(
                    "SELECT id, conversation_id, payload, request, attachment_path, attachment_id, visible, attempts FROM outbox WHERE next_at<=?1 ORDER BY created_at LIMIT 1",
                    [now_ms()],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get::<_, i64>(6)? == 1, r.get(7)?)),
                )
                .optional()?;
            let Some((id, conv, payload, request, att_path, att_id, visible, attempts)) = row else {
                return Ok(());
            };
            match self.send_one(&id, &conv, &payload, request, att_path, att_id) {
                Ok(()) => {}
                Err(e) if e.retryable() => {
                    let backoff = (1000i64 << attempts.min(6)) + (rand::random::<u16>() % 1000) as i64;
                    let wait = match &e {
                        Error::Api { retry_after_ms: Some(ms), .. } => (*ms as i64).max(backoff),
                        _ => backoff,
                    };
                    self.db().conn.execute("UPDATE outbox SET attempts=attempts+1, next_at=?2 WHERE id=?1", params![id, now_ms() + wait])?;
                    return Err(e);
                }
                Err(e) => {
                    if visible {
                        let keep = matches!(e, Error::KeyChanged(_) | Error::Crypto(_)) || e.is_api("attachment_not_ready");
                        self.fail_message(&id, &conv, &e, keep)?;
                    } else {
                        self.db().conn.execute("DELETE FROM outbox WHERE id=?1", [&id])?;
                    }
                }
            }
        }
    }

    fn send_one(self: &Arc<Self>, id: &str, conv: &str, payload: &[u8], mut request: Option<Vec<u8>>, att_path: Option<String>, mut att_id: Option<String>) -> Result<()> {
        let mut payload: Payload = serde_json::from_slice(payload)?;
        if let (Some(path), None) = (&att_path, &att_id) {
            att_id = Some(self.upload_attachment(id, Path::new(path))?);
        }
        if let (Some(f), Some(aid)) = (payload.file.as_mut(), &att_id) {
            f.id = aid.clone();
        }
        for _ in 0..3 {
            let req = match request.take() {
                Some(r) => r,
                None => self.build_request(conv, &payload, att_id.as_deref())?,
            };
            let body: Value = serde_json::from_slice(&req)?;
            match self.authed("POST", &format!("/v1/conversations/{conv}/messages"), Some(&body)) {
                Ok(ack) => {
                    let mut db = self.db();
                    let tx = db.conn.transaction()?;
                    tx.execute(
                        "UPDATE messages SET status=CASE WHEN status IN ('delivered','read') THEN status ELSE 'sent' END, server_id=?2, error=NULL WHERE id=?1",
                        params![id, ack["message_id"].as_str()],
                    )?;
                    if let Some(p) = &att_path {
                        let _ = std::fs::remove_file(p);
                    }
                    tx.execute("DELETE FROM outbox WHERE id=?1", [id])?;
                    tx.commit()?;
                    drop(db);
                    self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
                    return Ok(());
                }
                Err(e) if e.is_api("recipient_set_changed") || e.is_api("conversation_revision_changed") => {
                    // Not committed by the server: refresh membership, verify, rebuild.
                    self.db().conn.execute("UPDATE outbox SET request=NULL WHERE id=?1", [id])?;
                    self.fetch_conversation(conv)?;
                    self.check_can_send(conv)?;
                    payload.rev = self.db().conn.query_row("SELECT revision FROM conversations WHERE id=?1", [conv], |r| r.get(0))?;
                }
                Err(e) => return Err(e),
            }
        }
        Err(Error::Api { status: 503, code: "membership_unstable".into(), message: "Обновляем состав группы".into(), retry_after_ms: Some(2000), details: None })
    }

    fn upload_attachment(&self, id: &str, path: &Path) -> Result<String> {
        let size = std::fs::metadata(path)?.len();
        let sha = files::sha256_file(path)?;
        let res = self.authed("POST", "/v1/attachments", Some(&json!({"size": size, "sha256": b64(&sha)})))?;
        let aid = s(&res, "attachment_id")?.to_string();
        let token = self.access_token()?;
        self.api()?.upload(&format!("/v1/attachments/{aid}/content"), &token, path)?;
        self.db().conn.execute("UPDATE outbox SET attachment_id=?2 WHERE id=?1", params![id, aid])?;
        Ok(aid)
    }

    /// Ensures sessions, encrypts for every recipient and stores the request
    /// atomically with the new ratchet state.
    fn build_request(&self, conv: &str, payload: &Payload, att_id: Option<&str>) -> Result<Vec<u8>> {
        // Membership changes arrive as events and the server rejects a stale recipient
        // set (send_one then refetches), so the cached list is used when there is one.
        let known: String = self.db().conn.query_row("SELECT recipients FROM conversations WHERE id=?1", [conv], |r| r.get(0))?;
        let cached: Vec<Value> = serde_json::from_str(&known).unwrap_or_default();
        // A cached device that left its owner's verified roster (revoked, account recovered) means the list is stale.
        let stale = cached.is_empty()
            || cached.iter().any(|r| {
                let d = r["device_id"].as_str().unwrap_or_default();
                !self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM known_devices WHERE device_id=?1)", [d], |x| x.get::<_, bool>(0)).unwrap_or(false)
            });
        if stale {
            self.fetch_conversation(conv)?;
        }
        self.check_can_send(conv)?;
        let (recipients, rev, rset): (Vec<Value>, String, String) = {
            let db = self.db();
            let (r, rev, rs): (String, String, String) =
                db.conn.query_row("SELECT recipients, revision, recipient_rev FROM conversations WHERE id=?1", [conv], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
            (serde_json::from_str(&r)?, rev, rs)
        };
        if recipients.is_empty() {
            return Err(Error::Invalid("В беседе нет получателей".into()));
        }
        // Claim bundles for devices without a session, grouped by user.
        let mut missing: std::collections::BTreeMap<String, Vec<String>> = Default::default();
        {
            let db = self.db();
            for r in &recipients {
                let d = s(r, "device_id")?;
                let has: bool = db.conn.query_row("SELECT EXISTS(SELECT 1 FROM sessions WHERE address=?1)", [d], |x| x.get(0))?;
                if !has {
                    missing.entry(s(r, "user_id")?.to_string()).or_default().push(d.to_string());
                }
            }
        }
        let mut bundles = Vec::new();
        for (user, devs) in &missing {
            let res = self.authed("POST", &format!("/v1/users/{user}/bundles"), Some(&json!({"device_ids": devs})))?;
            for b in res["bundles"].as_array().cloned().unwrap_or_default() {
                bundles.push((user.clone(), b));
            }
        }
        let (me, my_dev) = self.me()?;
        let local = address(&my_dev);
        let mut body = serde_json::to_vec(payload)?;
        if let Some(a) = att_id {
            if let Some(f) = &payload.file {
                let mut p2 = payload.clone();
                p2.file = Some(FileMeta { id: a.to_string(), ..f.clone() });
                body = serde_json::to_vec(&p2)?;
            }
        }
        let plaintext = pad(body);
        let _ = me;
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        // Two views of the same transaction: libsignal wants separate session and identity stores.
        let mut sessions = SignalStore::new(&tx)?;
        let mut idstore = SignalStore::new(&tx)?;
        let mut rng = rand::rng();
        for (user, b) in &bundles {
            let dev = s(b, "device_id")?;
            let bundle = parse_bundle(b)?;
            // The bundle identity must match the device key in the verified roster.
            let known: Option<Vec<u8>> = tx
                .query_row("SELECT identity_key FROM known_devices WHERE user_id=?1 AND device_id=?2", [user.as_str(), dev], |r| r.get(0))
                .optional()?;
            if known.as_deref() != Some(&bundle.identity_key()?.serialize()[..]) {
                return Err(Error::Crypto("Ключ устройства собеседника не совпадает с подписанным списком".into()));
            }
            block_on(process_prekey_bundle(&address(dev), &local, &mut sessions, &mut idstore, &bundle, SystemTime::now(), &mut rng))?;
        }
        let mut out = Vec::with_capacity(recipients.len());
        for r in &recipients {
            let dev = s(r, "device_id")?;
            let ct = block_on(message_encrypt(&plaintext, &address(dev), &local, &mut sessions, &mut idstore, SystemTime::now(), &mut rng))?;
            let mut wire = vec![ct.message_type() as u8];
            wire.extend_from_slice(ct.serialize());
            out.push(json!({"target_device_id": dev, "ciphertext": b64(&wire)}));
        }
        let req = serde_json::to_vec(&json!({
            "version": 1,
            "client_message_id": payload.mid,
            "conversation_revision": rev,
            "recipient_set_revision": rset,
            "recipients": out,
            "attachment_ids": att_id.map(|a| vec![a.to_string()]).unwrap_or_default(),
            "control": !matches!(payload.kind.as_str(), "text" | "file" | "poll"),
        }))?;
        tx.execute("UPDATE outbox SET request=?2 WHERE id=?1", params![payload.mid, req])?;
        drop(sessions);
        drop(idstore);
        tx.commit()?;
        Ok(req)
    }

    /// Removes a message from the local tables inside `tx`: the row, its unsent
    /// outbox entry, reactions and poll votes; an unread incoming message also
    /// lowers the conversation badge.
    pub(crate) fn erase_message(conn: &rusqlite::Connection, id: &str, conv: &str) -> Result<()> {
        conn.execute(
            "UPDATE conversations SET unread=max(0, unread-1) WHERE id=?1 AND EXISTS(SELECT 1 FROM messages WHERE id=?2 AND conversation_id=?1 AND outgoing=0 AND NOT EXISTS(SELECT 1 FROM receipts WHERE message_id=?2 AND kind='read'))",
            params![conv, id],
        )?;
        conn.execute("DELETE FROM messages WHERE id=?1", [id])?;
        conn.execute("DELETE FROM outbox WHERE id=?1 AND request IS NULL", [id])?;
        conn.execute("DELETE FROM reactions WHERE message_id=?1", [id])?;
        conn.execute("DELETE FROM poll_votes WHERE poll_id=?1", [id])?;
        conn.execute(
            "UPDATE conversations SET last_preview=COALESCE((SELECT CASE kind WHEN 'file' THEN '📎 Файл' WHEN 'poll' THEN '📊 ' || body WHEN 'call' THEN '📞 Звонок' ELSE body END FROM messages WHERE conversation_id=?1 ORDER BY local_id DESC LIMIT 1),'') WHERE id=?1",
            [conv],
        )?;
        Ok(())
    }

    pub fn delete_local_message(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let conv = self.message_conv(id)?;
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            Self::erase_message(&tx, &id, &conv)?;
            tx.commit()?;
        }
        let _ = std::fs::remove_file(self.data_dir.join("files").join(format!("{id}.enc")));
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }

    /// Deletes the message here and sends an E2EE control message that removes it
    /// on every device — ours and the recipients' ("delete for all"). Own messages only.
    pub fn delete_everywhere(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let (conv, outgoing): (String, i64) = self
            .db()
            .conn
            .query_row("SELECT conversation_id, outgoing FROM messages WHERE id=?1 AND kind IN ('text','file','poll')", [id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        if outgoing != 1 {
            return Err(Error::Invalid("Удалить у всех можно только свои сообщения".into()));
        }
        self.check_can_send(&conv)?;
        let mut control = self.new_payload(&conv, "delete")?;
        control.group = Some(json!({"id": id}));
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            Self::erase_message(&tx, &id, &conv)?;
            tx.execute(
                "INSERT OR IGNORE INTO deleted_messages(message_id, conversation_id, sender_user_id) VALUES (?1, ?2, ?3)",
                params![id, conv, control.sender],
            )?;
            tx.execute(
                "INSERT INTO outbox(id, conversation_id, payload, visible, created_at) VALUES (?1, ?2, ?3, 0, ?4)",
                params![control.mid, conv, serde_json::to_vec(&control)?, now_ms()],
            )?;
            tx.commit()?;
        }
        let _ = std::fs::remove_file(self.data_dir.join("files").join(format!("{id}.enc")));
        self.wake();
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }

    /// Replaces the text of an own message here and, in an E2EE control message, on every
    /// other device. Text messages and photo or file captions only; a caption may be emptied.
    pub fn edit_message(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let text = s(p, "text")?.trim_end().to_string();
        let (conv, kind, outgoing, status, old): (String, String, i64, String, String) = self
            .db()
            .conn
            .query_row("SELECT conversation_id, kind, outgoing, status, body FROM messages WHERE id=?1 AND kind IN ('text','file')", [id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        if outgoing != 1 {
            return Err(Error::Invalid("Изменить можно только свои сообщения".into()));
        }
        if matches!(status.as_str(), "queued" | "sending" | "failed") {
            return Err(Error::Invalid("Дождитесь отправки сообщения".into()));
        }
        if kind == "text" && text.trim().is_empty() {
            return Err(Error::Invalid("Пустое сообщение".into()));
        }
        if text.len() > MAX_TEXT {
            return Err(Error::Invalid("Сообщение длиннее 4096 байт".into()));
        }
        if text == old {
            return self.message_json(id);
        }
        self.check_can_send(&conv)?;
        let (me, _) = self.me()?;
        self.enqueue_control(&conv, "edit", json!({"id": id, "text": text}))?;
        Self::apply_edit(&self.db().conn, &conv, &me, &json!({"id": id, "text": text}), now_ms())?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.message_json(id)
    }

    /// An "edit" control: only the original sender changes a message. Returns whether anything changed.
    pub fn apply_edit(conn: &rusqlite::Connection, conv: &str, sender: &str, g: &Value, at: i64) -> Result<bool> {
        let id = g["id"].as_str().unwrap_or_default();
        let text = g["text"].as_str().unwrap_or_default().trim_end();
        if text.len() > MAX_TEXT {
            return Ok(false);
        }
        let n = conn.execute(
            "UPDATE messages SET body=?4, edited_at=?5 WHERE id=?1 AND conversation_id=?2 AND sender_user_id=?3 AND body<>?4
             AND (kind='file' OR (kind='text' AND ?4<>''))",
            params![id, conv, sender, text, at.max(1)],
        )?;
        if n > 0 {
            // Photos and files keep their own preview; only a text message shows its text there.
            conn.execute(
                "UPDATE conversations SET last_preview=?3
                 WHERE id=?1 AND (SELECT id FROM messages WHERE conversation_id=?1 ORDER BY local_id DESC LIMIT 1)=?2
                 AND (SELECT kind FROM messages WHERE id=?2)='text'",
                params![conv, id, preview(text)],
            )?;
        }
        Ok(n > 0)
    }

    /// The sender name shown on a forwarded message: the original author's.
    fn forward_name(&self, sconv: &str, sender: &str) -> Result<String> {
        let (me, _) = self.me()?;
        let db = self.db();
        let (kind, title): (String, String) = db.conn.query_row("SELECT kind, title FROM conversations WHERE id=?1", [sconv], |r| Ok((r.get(0)?, r.get(1)?)))?;
        if kind == "channel" {
            return Ok(if title.is_empty() { "Канал".into() } else { title });
        }
        if sender == me {
            let name = kv_get_str(&db.conn, "display_name")?.unwrap_or_default();
            let user = kv_get_str(&db.conn, "username")?.unwrap_or_default();
            return Ok(if name.trim().is_empty() { format!("@{user}") } else { name });
        }
        let contact: Option<(String, String, String)> = db
            .conn
            .query_row(
                "SELECT COALESCE(CASE WHEN first_name='' AND last_name='' THEN profile_name ELSE first_name END,''), COALESCE(last_name,''), COALESCE(username,'') FROM contacts WHERE user_id=?1",
                [sender],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        if let Some((f, l, u)) = contact {
            let n = crate::engine::display_name(&f, &l, &u);
            if !n.is_empty() && n != "@" {
                return Ok(n);
            }
        }
        Ok(if kind == "direct" && !title.is_empty() { title } else { "Участник".into() })
    }

    /// Re-sends a local message into another conversation with a forward header.
    /// A file keeps its key: the same ciphertext is uploaded as a new attachment,
    /// because downloads are limited to members of the source conversation.
    pub fn forward_message(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let conv = s(p, "conversation_id")?;
        let (kind, body, file, poll, sconv, sender, ts): (String, String, Option<String>, Option<String>, String, String, i64) = self
            .db()
            .conn
            .query_row("SELECT kind, body, file, poll, conversation_id, sender_user_id, sent_at FROM messages WHERE id=?1 AND kind IN ('text','file','poll')", [id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?))
            })
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        self.check_can_send(&conv)?;
        let rights = self.conversation_json(&conv)?;
        if (kind == "file" && rights["can_media"] == false) || (kind == "poll" && rights["can_polls"] == false) {
            return Err(Error::Invalid("Недостаточно прав для пересылки сюда".into()));
        }
        let mut pl = self.new_payload(&conv, &kind)?;
        pl.text = if body.is_empty() { None } else { Some(body) };
        pl.poll = poll.map(|s| serde_json::from_str(&s)).transpose()?;
        pl.fwd = Some(json!({"name": self.forward_name(&sconv, &sender)?, "ts": ts}));
        let mut att: Option<PathBuf> = None;
        if kind == "file" {
            let mut f: FileMeta = serde_json::from_str(&file.ok_or_else(|| Error::Invalid("В сообщении нет файла".into()))?)?;
            let src = self.data_dir.join("files").join(format!("{id}.enc"));
            if !src.exists() {
                return Err(Error::Invalid("Сначала скачайте файл".into()));
            }
            let dst = self.data_dir.join("outgoing").join(format!("{}.enc", pl.mid));
            if let Some(dir) = dst.parent() {
                std::fs::create_dir_all(dir)?;
            }
            std::fs::copy(&src, &dst)?;
            std::fs::copy(&src, self.data_dir.join("files").join(format!("{}.enc", pl.mid)))?;
            f.id = String::new();
            pl.file = Some(f);
            att = Some(dst);
        }
        let pv = match &pl.file {
            Some(f) if f.thumb.is_some() && f.mime.starts_with("image/") => "🖼 Фото".to_string(),
            Some(f) => format!("📎 {}", f.name),
            None if kind == "poll" => format!("📊 {}", pl.text.as_deref().unwrap_or("")),
            None => preview(pl.text.as_deref().unwrap_or("")),
        };
        self.enqueue(&pl, true, att.as_deref(), &pv)?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.wake();
        self.message_json(&pl.mid)
    }

    fn file_meta(&self, id: &str) -> Result<FileMeta> {
        let f: Option<String> = self.db().conn.query_row("SELECT file FROM messages WHERE id=?1", [id], |r| r.get(0)).optional()?.flatten();
        Ok(serde_json::from_str(&f.ok_or_else(|| Error::Invalid("В сообщении нет файла".into()))?)?)
    }

    /// Downloads the encrypted object (kept encrypted locally) and decrypts a
    /// temporary copy for the user's explicit "open" action.
    pub fn download_file(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let meta = self.file_meta(id)?;
        let enc = self.data_dir.join("files").join(format!("{id}.enc"));
        if !enc.exists() {
            let tmp = self.data_dir.join("tmp").join(format!("{id}.part"));
            let token = self.access_token()?;
            let res = self.api()?.download(&format!("/v1/attachments/{}/content", meta.id), &token, &tmp, meta.ciphertext_size.max(1) + 64);
            if let Err(e) = res {
                let _ = std::fs::remove_file(&tmp);
                if let Error::Api { status: 404, .. } = e {
                    return Err(Error::Invalid("Срок хранения файла на сервере закончился".into()));
                }
                return Err(e);
            }
            if files::sha256_file(&tmp)?[..] != unb64(&meta.sha256)?[..] {
                let _ = std::fs::remove_file(&tmp);
                return Err(Error::Crypto("Файл повреждён или изменён".into()));
            }
            std::fs::rename(&tmp, &enc)?;
            self.emit(json!({"type": "messages_changed", "conversation_id": self.message_conv(id)?}));
        }
        let dir = self.data_dir.join("tmp").join(id);
        std::fs::create_dir_all(&dir)?;
        let out = dir.join(files::safe_name(&meta.name));
        // Photos are shown again and again: reuse the decrypted copy while it is complete.
        if std::fs::metadata(&out).map(|m| m.len() == meta.size).unwrap_or(false) {
            return Ok(json!({"path": out.to_string_lossy()}));
        }
        files::decrypt_file(&enc, &out, &unb64(&meta.key)?, &unb64(&meta.nonce)?)?;
        Ok(json!({"path": out.to_string_lossy()}))
    }

    /// Decrypts a downloaded file to a location the user picked.
    pub fn export_file(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let meta = self.file_meta(id)?;
        let enc = self.data_dir.join("files").join(format!("{id}.enc"));
        if !enc.exists() {
            self.download_file(p)?;
        }
        files::decrypt_file(&enc, Path::new(s(p, "dest")?), &unb64(&meta.key)?, &unb64(&meta.nonce)?)?;
        Ok(Value::Null)
    }

    pub fn mark_read(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let (me, _) = self.me()?;
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare(
                "SELECT id FROM messages WHERE conversation_id=?1 AND outgoing=0 AND kind IN ('text','file','poll')
                 AND id NOT IN (SELECT message_id FROM receipts WHERE user_id=?2 AND kind='read') AND (?3 IS NULL OR id=?3)",
            )?;
            let target = p["message_id"].as_str();
            let ids: Vec<String> = st.query_map(params![conv, me, target], |r| r.get(0))?.collect::<std::result::Result<_, _>>()?;
            for i in &ids {
                db.conn.execute("INSERT OR IGNORE INTO receipts(message_id, user_id, kind, at) VALUES (?1, ?2, 'read', ?3)", params![i, me, now_ms()])?;
            }
            if target.is_none() { db.conn.execute("UPDATE conversations SET unread=0 WHERE id=?1", [conv])?; }
            else { db.conn.execute("UPDATE conversations SET unread=max(0,unread-?2) WHERE id=?1", params![conv,ids.len() as i64])?; }
            ids
        };
        // Read receipts are always on; channels have none (they would reach every subscriber).
        let channel = self.rights(conv).map(|r| r.kind == "channel").unwrap_or(false);
        if !ids.is_empty() {
            if conv != crate::sync::SYSTEM_CHAT && !channel {
                for batch in ids.chunks(500) { self.enqueue_receipt(conv, "read", batch.to_vec())?; }
                self.wake();
            }
            self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        }
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }
}

pub fn preview(text: &str) -> String {
    let one: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    one.chars().take(80).collect()
}

fn parse_bundle(b: &Value) -> Result<PreKeyBundle> {
    let otp = match b.get("one_time_prekey") {
        Some(o) if !o.is_null() => Some((
            (o["id"].as_u64().unwrap_or(0) as u32).into(),
            PublicKey::deserialize(&unb64(o["public_key"].as_str().unwrap_or_default())?).map_err(crypto)?,
        )),
        _ => None,
    };
    let sp = &b["signed_prekey"];
    let kp = &b["kyber_prekey"];
    Ok(PreKeyBundle::new(
        b["registration_id"].as_u64().unwrap_or(0) as u32,
        DeviceId::new(1).expect("valid device id"),
        otp,
        (sp["id"].as_u64().unwrap_or(0) as u32).into(),
        PublicKey::deserialize(&unb64(sp["public_key"].as_str().unwrap_or_default())?).map_err(crypto)?,
        unb64(sp["signature"].as_str().unwrap_or_default())?,
        (kp["id"].as_u64().unwrap_or(0) as u32).into(),
        kem::PublicKey::deserialize(&unb64(kp["public_key"].as_str().unwrap_or_default())?)?,
        unb64(kp["signature"].as_str().unwrap_or_default())?,
        IdentityKey::decode(&unb64(b["identity_key"].as_str().unwrap_or_default())?)?,
    )?)
}
