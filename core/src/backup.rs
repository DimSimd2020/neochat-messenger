//! Password-protected account backup. The account identity and an encrypted copy of
//! the chat history live on the server, so a new device signs in with the password
//! alone and gets the old conversations.
//!
//! Two keys come from the password (Argon2id, then HKDF): `auth` goes to the server
//! in place of the password, `backup` never leaves devices. The server cannot read
//! the backup directly, but whoever holds its database can guess passwords offline;
//! this is the accepted cost of signing in without another device.

use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::path::Path;

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use rusqlite::params;
use serde_json::{Value, json};
use sha2::Sha256;
use zeroize::Zeroizing;

use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::files;
use crate::keys::{b64, random_bytes, sha256};
use crate::store::{kv_get, kv_get_str, kv_set, now_ms};

/// History is re-uploaded at most this often, and only after something changed.
const UPLOAD_EVERY_MS: i64 = 10 * 60 * 1000;
/// Server-side cap for one history backup.
pub const MAX_HISTORY: u64 = 200 << 20;

pub struct PasswordKeys {
    pub auth: String,
    pub backup: Zeroizing<[u8; 32]>,
}

/// Same username and password give the same keys on every device.
pub fn derive(username: &str, password: &str) -> Result<PasswordKeys> {
    let salt = sha256(format!("neochat-password-v1\n{username}").as_bytes());
    let params = Params::new(19 * 1024, 2, 1, Some(32)).map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    let mut master = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), &salt[..16], &mut master[..])
        .map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    let hk = Hkdf::<Sha256>::new(None, &master[..]);
    let mut auth = Zeroizing::new([0u8; 32]);
    let mut backup = Zeroizing::new([0u8; 32]);
    hk.expand(b"neochat-auth-v1", &mut auth[..]).map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    hk.expand(b"neochat-backup-v1", &mut backup[..]).map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    Ok(PasswordKeys { auth: b64(&auth[..]), backup })
}

/// Separate keys for the identity blob and the history stream.
pub fn subkey(backup: &[u8], label: &str) -> Result<[u8; 32]> {
    let mut out = [0u8; 32];
    Hkdf::<Sha256>::new(None, backup)
        .expand(format!("neochat-{label}-v1").as_bytes(), &mut out)
        .map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    Ok(out)
}

/// XChaCha20-Poly1305 with a random nonce in front of the ciphertext.
pub fn seal(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>> {
    let nonce = random_bytes::<24>();
    let mut out = nonce.to_vec();
    let ct = XChaCha20Poly1305::new(key.into())
        .encrypt(XNonce::from_slice(&nonce), plaintext)
        .map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    out.extend_from_slice(&ct);
    Ok(out)
}

pub fn open(key: &[u8; 32], data: &[u8]) -> Result<Vec<u8>> {
    if data.len() < 24 {
        return Err(Error::Crypto("Неверный пароль или повреждённая копия".into()));
    }
    XChaCha20Poly1305::new(key.into())
        .decrypt(XNonce::from_slice(&data[..24]), &data[24..])
        .map_err(|_| Error::Crypto("Неверный пароль или повреждённая копия".into()))
}

/// The account identity sealed for the server.
pub fn seal_keys(backup: &[u8; 32], account_identity: &[u8]) -> Result<Vec<u8>> {
    seal(&subkey(backup, "keys")?, json!({"account_identity": b64(account_identity)}).to_string().as_bytes())
}

pub fn open_keys(backup: &[u8; 32], blob: &[u8]) -> Result<Vec<u8>> {
    let v: Value = serde_json::from_slice(&open(&subkey(backup, "keys")?, blob)?)?;
    crate::keys::unb64(v["account_identity"].as_str().unwrap_or_default())
}

impl Inner {
    pub(crate) fn backup_key(&self) -> Result<Option<[u8; 32]>> {
        Ok(kv_get(&self.db().conn, "backup_key")?.and_then(|k| k.try_into().ok()))
    }

    /// Runs maybe_upload_history on its own thread so a large upload never delays messages.
    pub fn spawn_history_upload(self: &std::sync::Arc<Self>) {
        static RUNNING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        if RUNNING.swap(true, std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        let me = self.clone();
        let spawned = std::thread::Builder::new().name("neocore-history".into()).spawn(move || {
            let _ = me.maybe_upload_history();
            RUNNING.store(false, std::sync::atomic::Ordering::SeqCst);
        });
        if spawned.is_err() {
            RUNNING.store(false, std::sync::atomic::Ordering::SeqCst);
        }
    }

    /// Uploads the history when it changed and the last upload is old enough.
    pub fn maybe_upload_history(&self) -> Result<()> {
        let Some(key) = self.backup_key()? else { return Ok(()) };
        let (last_at, last_mark, mark) = {
            let db = self.db();
            let last_at: i64 = kv_get_str(&db.conn, "history_uploaded_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            let last_mark = kv_get_str(&db.conn, "history_mark")?.unwrap_or_default();
            let mark: String = db.conn.query_row(
                "SELECT COALESCE(MAX(local_id), 0) || ':' || count(*) || ':' || (SELECT count(*) FROM deleted_messages) || ':' || (SELECT count(*) FROM contacts) || ':' || (SELECT count(*) FROM conversations) FROM messages",
                [],
                |r| r.get(0),
            )?;
            (last_at, last_mark, mark)
        };
        // A new own device asks for an immediate upload (kv "history_force").
        let force = kv_get_str(&self.db().conn, "history_force")?.is_some();
        if !force && (mark == last_mark || now_ms() - last_at < UPLOAD_EVERY_MS) {
            return Ok(());
        }
        self.upload_history(&key)?;
        let db = self.db();
        crate::store::kv_del(&db.conn, "history_force")?;
        kv_set(&db.conn, "history_uploaded_at", now_ms().to_string().as_bytes())?;
        kv_set(&db.conn, "history_mark", mark.as_bytes())?;
        Ok(())
    }

    pub fn upload_history(&self, key: &[u8; 32]) -> Result<()> {
        let tmp = self.data_dir.join("tmp");
        let plain = tmp.join("history.jsonl");
        let enc = tmp.join("history.enc");
        let out = tmp.join("history.up");
        let res = (|| {
            self.export_history(&plain)?;
            let nonce = random_bytes::<19>();
            files::encrypt_with(&plain, &enc, subkey(key, "history")?, nonce)?;
            let _ = std::fs::remove_file(&plain);
            // The stream nonce goes in front of the ciphertext.
            let mut w = BufWriter::new(File::create(&out)?);
            w.write_all(&nonce)?;
            std::io::copy(&mut BufReader::new(File::open(&enc)?), &mut w)?;
            w.flush()?;
            drop(w);
            let token = self.access_token()?;
            self.api()?.upload("/v1/accounts/current/history", &token, &out)
        })();
        for p in [&plain, &enc, &out] {
            let _ = std::fs::remove_file(p);
        }
        res
    }

    /// Downloads and imports the history backup, if the account has one.
    pub fn restore_history(&self, key: &[u8; 32]) -> Result<()> {
        let tmp = self.data_dir.join("tmp");
        let down = tmp.join("history.down");
        let enc = tmp.join("history.enc");
        let plain = tmp.join("history.jsonl");
        let res = (|| {
            let token = self.access_token()?;
            match self.api()?.download("/v1/accounts/current/history", &token, &down, MAX_HISTORY) {
                Err(Error::Api { status: 404, .. }) => return Ok(()),
                r => r?,
            };
            let mut r = BufReader::new(File::open(&down)?);
            let mut nonce = [0u8; 19];
            r.read_exact(&mut nonce).map_err(|_| Error::Crypto("Копия переписки повреждена".into()))?;
            std::io::copy(&mut r, &mut BufWriter::new(File::create(&enc)?))?;
            files::decrypt_file(&enc, &plain, &subkey(key, "history")?, &nonce)?;
            self.import_history(&plain)
        })();
        for p in [&down, &enc, &plain] {
            let _ = std::fs::remove_file(p);
        }
        res?;
        // Imported contacts need their current device lists before anyone can write to them.
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare("SELECT user_id FROM contacts")?;
            st.query_map([], |r| r.get(0))?.collect::<std::result::Result<_, _>>()?
        };
        for id in ids {
            let _ = self.refresh_user(&id);
        }
        Ok(())
    }

    /// One JSON object per line, streamed so large histories never sit in memory.
    fn export_history(&self, path: &Path) -> Result<()> {
        let mut w = BufWriter::new(File::create(path)?);
        let db = self.db();
        let mut st = db.conn.prepare(
            "SELECT user_id, username, first_name, last_name, account_key, identity_epoch, roster_revision, verified, is_contact, added_at FROM contacts",
        )?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let line = json!({"t": "contact", "user_id": r.get::<_, String>(0)?, "username": r.get::<_, String>(1)?, "first_name": r.get::<_, String>(2)?,
                "last_name": r.get::<_, String>(3)?, "account_key": b64(&r.get::<_, Vec<u8>>(4)?), "identity_epoch": r.get::<_, String>(5)?,
                "roster_revision": r.get::<_, i64>(6)?, "verified": r.get::<_, i64>(7)?, "is_contact": r.get::<_, i64>(8)?, "added_at": r.get::<_, i64>(9)?});
            writeln!(w, "{line}")?;
        }
        drop(rows);
        let mut st = db.conn.prepare(
            "SELECT id, kind, title, peer_user_id, owner_id, revision, members, archived, left_group, last_activity, last_preview, description, tags FROM conversations",
        )?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let line = json!({"t": "conversation", "id": r.get::<_, String>(0)?, "kind": r.get::<_, String>(1)?, "title": r.get::<_, String>(2)?,
                "peer_user_id": r.get::<_, Option<String>>(3)?, "owner_id": r.get::<_, Option<String>>(4)?, "revision": r.get::<_, String>(5)?,
                "members": r.get::<_, String>(6)?, "archived": r.get::<_, i64>(7)?, "left_group": r.get::<_, i64>(8)?,
                "last_activity": r.get::<_, i64>(9)?, "last_preview": r.get::<_, String>(10)?, "description": r.get::<_, String>(11)?, "tags": r.get::<_, String>(12)?});
            writeln!(w, "{line}")?;
        }
        drop(rows);
        let mut st = db.conn.prepare(
            "SELECT id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, reply_to, file, status, server_id, sent_at, poll, fwd, edited_at FROM messages
             WHERE kind IN ('text','file','poll') AND status NOT IN ('queued','sending','failed') ORDER BY local_id",
        )?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let line = json!({"t": "message", "id": r.get::<_, String>(0)?, "conversation_id": r.get::<_, String>(1)?, "sender_user_id": r.get::<_, String>(2)?,
                "sender_device_id": r.get::<_, String>(3)?, "outgoing": r.get::<_, i64>(4)?, "kind": r.get::<_, String>(5)?, "body": r.get::<_, String>(6)?,
                "reply_to": r.get::<_, Option<String>>(7)?, "file": r.get::<_, Option<String>>(8)?, "status": r.get::<_, String>(9)?,
                "server_id": r.get::<_, Option<String>>(10)?, "sent_at": r.get::<_, i64>(11)?, "poll": r.get::<_, Option<String>>(12)?, "fwd": r.get::<_, Option<String>>(13)?, "edited_at": r.get::<_, i64>(14)?});
            writeln!(w, "{line}")?;
        }
        drop(rows);
        let mut st = db.conn.prepare("SELECT poll_id, user_id, choices, at FROM poll_votes")?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let line = json!({"t": "vote", "poll_id": r.get::<_, String>(0)?, "user_id": r.get::<_, String>(1)?, "choices": r.get::<_, String>(2)?, "at": r.get::<_, i64>(3)?});
            writeln!(w, "{line}")?;
        }
        drop(rows);
        let mut st = db.conn.prepare("SELECT message_id, conversation_id, sender_user_id FROM deleted_messages")?;
        let mut rows = st.query([])?;
        while let Some(r) = rows.next()? {
            let line = json!({"t": "delete", "id": r.get::<_, String>(0)?, "conversation_id": r.get::<_, String>(1)?, "sender_user_id": r.get::<_, String>(2)?});
            writeln!(w, "{line}")?;
        }
        w.flush()?;
        Ok(())
    }

    fn import_history(&self, path: &Path) -> Result<()> {
        let me = kv_get_str(&self.db().conn, "user_id")?.unwrap_or_default();
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        for line in BufReader::new(File::open(path)?).lines() {
            let v: Value = serde_json::from_str(&line?)?;
            let st = |k: &str| v[k].as_str().unwrap_or_default().to_string();
            let int = |k: &str| v[k].as_i64().unwrap_or(0);
            match v["t"].as_str().unwrap_or("") {
                "contact" if st("user_id") != me => {
                    tx.execute(
                        "INSERT OR IGNORE INTO contacts(user_id, username, first_name, last_name, account_key, identity_epoch, roster_revision, verified, is_contact, added_at)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
                        params![st("user_id"), st("username"), st("first_name"), st("last_name"), crate::keys::unb64(&st("account_key"))?,
                            st("identity_epoch"), int("roster_revision"), int("verified"), int("is_contact"), int("added_at")],
                    )?;
                }
                "conversation" => {
                    let tags = if st("tags").is_empty() { "{}".to_string() } else { st("tags") };
                    tx.execute(
                        "INSERT OR IGNORE INTO conversations(id, kind, title, peer_user_id, owner_id, revision, members, archived, left_group, last_activity, last_preview, description, tags)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
                        params![st("id"), st("kind"), st("title"), v["peer_user_id"].as_str(), v["owner_id"].as_str(), st("revision"), st("members"),
                            int("archived"), int("left_group"), int("last_activity"), st("last_preview"), st("description"), tags],
                    )?;
                }
                "message" => {
                    tx.execute(
                        "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, reply_to, file, status, server_id, sent_at, poll, fwd, edited_at)
                         SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15
                         WHERE NOT EXISTS(SELECT 1 FROM deleted_messages WHERE message_id=?1 AND conversation_id=?2 AND sender_user_id=?3)",
                        params![st("id"), st("conversation_id"), st("sender_user_id"), st("sender_device_id"), int("outgoing"), st("kind"), st("body"),
                            v["reply_to"].as_str(), v["file"].as_str(), st("status"), v["server_id"].as_str(), int("sent_at"), v["poll"].as_str(), v["fwd"].as_str(), int("edited_at")],
                    )?;
                }
                "delete" => {
                    Self::apply_delete(&tx, &st("conversation_id"), &st("sender_user_id"), &json!({"id": st("id")}))?;
                }
                "vote" => {
                    tx.execute(
                        "INSERT OR IGNORE INTO poll_votes(poll_id, user_id, choices, at) VALUES (?1, ?2, ?3, ?4)",
                        params![st("poll_id"), st("user_id"), st("choices"), int("at")],
                    )?;
                }
                _ => {}
            }
        }
        tx.commit()?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_are_stable_and_separate() {
        let a = derive("anna", "correct horse battery").unwrap();
        let b = derive("anna", "correct horse battery").unwrap();
        let c = derive("boris", "correct horse battery").unwrap();
        assert_eq!(a.auth, b.auth);
        assert_eq!(a.backup[..], b.backup[..]);
        assert_ne!(a.auth, c.auth);
        assert_ne!(crate::keys::unb64(&a.auth).unwrap()[..], a.backup[..]);
        let blob = seal_keys(&a.backup, b"identity").unwrap();
        assert_eq!(open_keys(&b.backup, &blob).unwrap(), b"identity");
        assert!(open_keys(&c.backup, &blob).is_err());
    }
}
