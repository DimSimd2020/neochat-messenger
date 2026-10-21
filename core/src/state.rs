//! Account state shared by the user's own devices: contacts (added, removed, own
//! names, verification of the safety number), pinned and archived chats, and account
//! preferences (quick reaction, read receipts).
//!
//! The document is a JSON map `{key: {"v": value, "at": ms}}`, sealed with a key
//! derived from the password backup key, so the server stores an opaque blob with a
//! revision. Every entry merges on its own: the later change wins; on equal times the
//! larger value wins, so devices converge on the same result.
//!
//! Local tables stay the source of truth: a change rewrites its entry from the table
//! (`touch`), and the worker merges with the server document (`sync_account_state`).

use rusqlite::{OptionalExtension, params};
use serde_json::{Map, Value, json};

use crate::backup::{open, seal, subkey};
use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::keys::{b64, unb64};
use crate::store::{kv_del, kv_get_str, kv_set, now_ms};

/// Without a nudge from the server the document is still re-read this often.
const PULL_EVERY_MS: i64 = 30 * 60 * 1000;
pub const DEFAULT_QUICK_REACTION: &str = "❤️";

pub const SCHEMA: &str = "CREATE TABLE IF NOT EXISTS account_state (
    key TEXT PRIMARY KEY, v TEXT NOT NULL, at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0
);";

/// Entries that win against `b` (time first, then the serialized value).
fn newer(a_at: i64, a_v: &str, b_at: i64, b_v: &str) -> bool {
    (a_at, a_v) > (b_at, b_v)
}

impl Inner {
    /// Records a local change of `key` for the other devices and wakes the worker.
    pub fn touch(&self, key: &str) {
        let res = (|| {
            let db = self.db();
            let Some(v) = local_value(&db.conn, key)? else { return Ok(()) };
            let prev: i64 = db.conn.query_row("SELECT at FROM account_state WHERE key=?1", [key], |r| r.get(0)).optional()?.unwrap_or(0);
            db.conn.execute(
                "INSERT INTO account_state(key, v, at, dirty) VALUES (?1, ?2, ?3, 1)
                 ON CONFLICT(key) DO UPDATE SET v=excluded.v, at=excluded.at, dirty=1",
                params![key, v.to_string(), now_ms().max(prev + 1)],
            )?;
            Ok::<_, Error>(())
        })();
        if res.is_ok() {
            self.wake();
        }
    }

    pub fn contact_touch(&self, user_id: &str) {
        self.touch(&format!("contact:{user_id}"));
    }

    /// Something to send, a nudge from another device, or a periodic re-read.
    fn state_due(&self) -> Result<bool> {
        let db = self.db();
        let dirty: bool = db.conn.query_row("SELECT EXISTS(SELECT 1 FROM account_state WHERE dirty=1)", [], |r| r.get(0))?;
        let pulled: i64 = kv_get_str(&db.conn, "state_pulled_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
        Ok(dirty || kv_get_str(&db.conn, "state_pull")?.is_some() || now_ms() - pulled > PULL_EVERY_MS)
    }

    /// Merges the local entries with the server document. Conflicting writes from
    /// two devices are retried on top of the newer revision.
    pub fn sync_account_state(&self) -> Result<()> {
        if !self.state_due()? {
            return Ok(());
        }
        let Some(backup) = self.backup_key()? else { return Ok(()) };
        let key = subkey(&backup, "state")?;
        self.seed_state()?;
        for _ in 0..4 {
            let (rev, doc, readable) = match self.authed("GET", "/v1/accounts/current/state", None) {
                Err(Error::Api { status: 404, .. }) => ("0".to_string(), Map::new(), true),
                Err(e) => return Err(e),
                Ok(r) => {
                    let rev = r["revision"].as_str().unwrap_or("0").to_string();
                    // A document sealed before an account recovery cannot be read: it is replaced.
                    let doc = unb64(r["blob"].as_str().unwrap_or_default())
                        .and_then(|b| open(&key, &b))
                        .ok()
                        .and_then(|pt| serde_json::from_slice::<Value>(&pt).ok())
                        .and_then(|v| v.as_object().cloned());
                    let readable = doc.is_some();
                    (rev, doc.unwrap_or_default(), readable)
                }
            };
            let mut doc = doc;
            let refresh = self.apply_remote_state(&doc)?;
            for uid in refresh {
                let _ = self.refresh_user(&uid);
            }
            // Local changes that are still newer than the document.
            let dirty: Vec<(String, String, i64)> = {
                let db = self.db();
                let mut st = db.conn.prepare("SELECT key, v, at FROM account_state WHERE dirty=1")?;
                st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect::<std::result::Result<_, _>>()?
            };
            for (k, v, at) in &dirty {
                doc.insert(k.clone(), json!({"v": serde_json::from_str::<Value>(v)?, "at": at}));
            }
            if !dirty.is_empty() || !readable {
                let blob = seal(&key, Value::Object(doc).to_string().as_bytes())?;
                match self.authed("PUT", "/v1/accounts/current/state", Some(&json!({"revision": rev, "blob": b64(&blob)}))) {
                    Err(e) if e.is_api("state_conflict") => continue,
                    Err(e) => return Err(e),
                    Ok(_) => {}
                }
                let db = self.db();
                for (k, _, at) in &dirty {
                    db.conn.execute("UPDATE account_state SET dirty=0 WHERE key=?1 AND at=?2", params![k, at])?;
                }
            }
            let db = self.db();
            kv_del(&db.conn, "state_pull")?;
            kv_set(&db.conn, "state_pulled_at", now_ms().to_string().as_bytes())?;
            return Ok(());
        }
        Ok(())
    }

    /// Installations from before the shared state publish what they already have.
    /// The early time lets any real change from another device win.
    fn seed_state(&self) -> Result<()> {
        let db = self.db();
        if kv_get_str(&db.conn, "state_seeded")?.is_some() {
            return Ok(());
        }
        let mut keys: Vec<String> = Vec::new();
        {
            let mut st = db.conn.prepare("SELECT user_id FROM contacts WHERE is_contact=1 OR verified=1")?;
            for id in st.query_map([], |r| r.get::<_, String>(0))? {
                keys.push(format!("contact:{}", id?));
            }
            let mut st = db.conn.prepare("SELECT id FROM conversations WHERE pinned>0 OR in_archive=1")?;
            for id in st.query_map([], |r| r.get::<_, String>(0))? {
                keys.push(format!("chat:{}", id?));
            }
        }
        for k in ["pref:read_receipts", "pref:quick_reaction"] {
            let set = kv_get_str(&db.conn, k.trim_start_matches("pref:"))?.is_some();
            if set {
                keys.push(k.to_string());
            }
        }
        for k in keys {
            if let Some(v) = local_value(&db.conn, &k)? {
                db.conn.execute("INSERT OR IGNORE INTO account_state(key, v, at, dirty) VALUES (?1, ?2, 1, 1)", params![k, v.to_string()])?;
            }
        }
        kv_set(&db.conn, "state_seeded", b"1")?;
        Ok(())
    }

    /// Applies entries that are newer than the local ones. Returns contacts that
    /// were added here and still need their profile and devices from the server.
    fn apply_remote_state(&self, doc: &Map<String, Value>) -> Result<Vec<String>> {
        let me = kv_get_str(&self.db().conn, "user_id")?.unwrap_or_default();
        let mut refresh = Vec::new();
        let mut accept = Vec::new();
        let mut changed = (false, false, false);
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            for (k, e) in doc {
                let (Some(at), v) = (e["at"].as_i64(), &e["v"]) else { continue };
                let vs = v.to_string();
                let local: Option<(String, i64)> = tx.query_row("SELECT v, at FROM account_state WHERE key=?1", [k], |r| Ok((r.get(0)?, r.get(1)?))).optional()?;
                if let Some((lv, lat)) = &local {
                    if !newer(at, &vs, *lat, lv) {
                        continue;
                    }
                }
                if let Some(uid) = k.strip_prefix("contact:") {
                    if uid == me || uuid::Uuid::parse_str(uid).is_err() {
                        continue;
                    }
                    match apply_contact(&tx, uid, v)? {
                        ContactApplied::Inserted => refresh.push(uid.to_string()),
                        ContactApplied::AcceptKey => accept.push(uid.to_string()),
                        ContactApplied::Updated => {}
                    }
                    changed.0 = true;
                } else if let Some(cid) = k.strip_prefix("chat:") {
                    let pinned = v["pinned"].as_i64().unwrap_or(0).max(0);
                    let archived = v["archived"].as_bool().unwrap_or(false) && pinned == 0;
                    // A chat this device does not know yet takes the entry on a later pull.
                    if tx.execute("UPDATE conversations SET pinned=?2, in_archive=?3 WHERE id=?1", params![cid, pinned, archived as i64])? == 0 {
                        continue;
                    }
                    changed.1 = true;
                } else if let Some(uid) = k.strip_prefix("note:") {
                    if uid == me || uuid::Uuid::parse_str(uid).is_err() { continue; }
                    let Some(note) = v.as_str().filter(|s| s.chars().count() <= 100) else { continue; };
                    kv_set(&tx, k, note.as_bytes())?;
                    changed.0 = true;
                } else if k == "pref:quick_reaction" {
                    match v.as_str() {
                        Some(r) if crate::social::valid_reaction(r) => kv_set(&tx, "quick_reaction", r.as_bytes())?,
                        _ => continue,
                    }
                    changed.2 = true;
                } else if k == "pref:read_receipts" {
                    kv_set(&tx, "read_receipts", if v.as_bool().unwrap_or(true) { b"1" } else { b"0" })?;
                    changed.2 = true;
                } else {
                    continue;
                }
                tx.execute(
                    "INSERT INTO account_state(key, v, at, dirty) VALUES (?1, ?2, ?3, 0)
                     ON CONFLICT(key) DO UPDATE SET v=excluded.v, at=excluded.at, dirty=0",
                    params![k, vs, at],
                )?;
            }
            tx.commit()?;
        }
        for uid in accept {
            let _ = self.accept_key_change_synced(&uid);
        }
        if changed.0 {
            self.emit(json!({"type": "contacts_changed"}));
        }
        if changed.0 || changed.1 {
            self.emit(json!({"type": "conversations_changed"}));
        }
        if changed.2 {
            self.emit(json!({"type": "settings_changed"}));
        }
        Ok(refresh)
    }
}

enum ContactApplied {
    Inserted,
    Updated,
    AcceptKey,
}

fn apply_contact(tx: &rusqlite::Transaction, uid: &str, v: &Value) -> Result<ContactApplied> {
    let first = v["first"].as_str().unwrap_or("").trim().chars().take(64).collect::<String>();
    let last = v["last"].as_str().unwrap_or("").trim().chars().take(64).collect::<String>();
    let is_contact = v["is_contact"].as_bool().unwrap_or(false);
    let key = unb64(v["key"].as_str().unwrap_or_default())?;
    let row: Option<(Vec<u8>, Option<Vec<u8>>, i64)> = tx
        .query_row("SELECT account_key, pending_account_key, key_changed FROM contacts WHERE user_id=?1", [uid], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .optional()?;
    let Some((local_key, pending, key_changed)) = row else {
        if !is_contact {
            return Ok(ContactApplied::Updated);
        }
        libsignal_protocol::IdentityKey::decode(&key)?;
        // The key another own device pinned is pinned here too; the server then only
        // supplies devices and the profile, and a different key shows as a change.
        tx.execute(
            "INSERT INTO contacts(user_id, username, first_name, last_name, account_key, identity_epoch, roster_revision, verified, is_contact, added_at)
             VALUES (?1, ?2, ?3, ?4, ?5, '1', 0, ?6, 1, ?7)",
            params![uid, v["username"].as_str().unwrap_or(""), first, last, key, v["verified"].as_bool().unwrap_or(false) as i64, now_ms()],
        )?;
        return Ok(ContactApplied::Inserted);
    };
    tx.execute("UPDATE contacts SET first_name=?2, last_name=?3, is_contact=?4 WHERE user_id=?1", params![uid, first, last, is_contact as i64])?;
    if local_key == key && key_changed == 0 {
        tx.execute("UPDATE contacts SET verified=?2 WHERE user_id=?1", params![uid, v["verified"].as_bool().unwrap_or(false) as i64])?;
    } else if key_changed == 1 && pending.as_deref() == Some(&key[..]) {
        // The new key was accepted on another own device.
        return Ok(ContactApplied::AcceptKey);
    }
    Ok(ContactApplied::Updated)
}

/// The shareable value of an entry, read from the local tables.
fn local_value(c: &rusqlite::Connection, key: &str) -> Result<Option<Value>> {
    if key.starts_with("note:") { return Ok(kv_get_str(c, key)?.map(|s| json!(s))); }
    if let Some(uid) = key.strip_prefix("contact:") {
        return Ok(c
            .query_row(
                "SELECT username, first_name, last_name, is_contact, account_key, verified FROM contacts WHERE user_id=?1",
                [uid],
                |r| {
                    Ok(json!({"username": r.get::<_, String>(0)?, "first": r.get::<_, String>(1)?, "last": r.get::<_, String>(2)?,
                        "is_contact": r.get::<_, i64>(3)? == 1, "key": b64(&r.get::<_, Vec<u8>>(4)?), "verified": r.get::<_, i64>(5)? == 1}))
                },
            )
            .optional()?);
    }
    if let Some(cid) = key.strip_prefix("chat:") {
        return Ok(c
            .query_row("SELECT pinned, in_archive FROM conversations WHERE id=?1", [cid], |r| {
                Ok(json!({"pinned": r.get::<_, i64>(0)?, "archived": r.get::<_, i64>(1)? == 1}))
            })
            .optional()?);
    }
    Ok(match key {
        "pref:quick_reaction" => Some(json!(kv_get_str(c, "quick_reaction")?.unwrap_or_else(|| DEFAULT_QUICK_REACTION.into()))),
        "pref:read_receipts" => Some(json!(kv_get_str(c, "read_receipts")?.as_deref() != Some("0"))),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn later_or_larger_entry_wins() {
        assert!(newer(2, "\"a\"", 1, "\"b\""));
        assert!(!newer(1, "\"b\"", 2, "\"a\""));
        // Equal times: both devices pick the same value.
        assert!(newer(5, "\"b\"", 5, "\"a\""));
        assert!(!newer(5, "\"a\"", 5, "\"b\""));
        assert!(!newer(5, "\"a\"", 5, "\"a\""));
    }
}
