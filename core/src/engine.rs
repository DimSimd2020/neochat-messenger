//! The client engine: one serialized database, a background worker that syncs
//! and flushes the outbox, and an optional WebSocket that only wakes the worker.
//!
//! Locking rule: the database mutex is held only for local work. Network calls
//! happen outside it; results are applied in short transactions afterwards.

use std::path::PathBuf;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU8, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::Duration;

use libsignal_protocol::{IdentityKey, IdentityKeyPair};
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::api::Api;
use crate::backup;
use crate::error::{Error, Result};
use crate::keys::{self, Roster, b64, unb64};
use crate::store::{Db, kv_del, kv_get, kv_get_str, kv_set, now_ms};

pub type EventCallback = Box<dyn Fn(&str) + Send + Sync>;

pub const STATE_OFFLINE: u8 = 0;
pub const STATE_CONNECTING: u8 = 1;
pub const STATE_ONLINE: u8 = 2;
/// Built-in server; never shown to people. Callers may override it with "server" (tests).
pub const DEFAULT_SERVER: &str = "https://r1270286.s2566.vm.mydomain.zone:8443";

pub struct Inner {
    pub db: Mutex<Db>,
    pub data_dir: PathBuf,
    cb: EventCallback,
    wake: (Mutex<bool>, Condvar),
    pub foreground: AtomicBool,
    /// Keep the event socket open while in the background (Android service).
    pub background: AtomicBool,
    pub shutdown: AtomicBool,
    pub conn_state: AtomicU8,
    refresh_lock: Mutex<()>,
    pub ws_active: AtomicBool,
    /// Serializes sync cycles between the worker and `sync_now`.
    pub cycle_lock: Mutex<()>,
    /// Last known presence per user (see social.rs) and when we last said "typing".
    pub presence: Mutex<HashMap<String, Value>>,
    pub typing_sent: Mutex<HashMap<String, i64>>,
    pub tz_offset: AtomicI32,
    pub cur_call: Mutex<Option<crate::calls::Call>>,
    pub ice_cache: Mutex<Option<(Value, i64)>>,
}

pub struct Core {
    pub inner: Arc<Inner>,
    worker: Option<JoinHandle<()>>,
}

impl Core {
    pub fn open(config: &Value, cb: EventCallback) -> Result<Core> {
        let data_dir = PathBuf::from(config["data_dir"].as_str().ok_or_else(|| Error::Invalid("Не указан каталог данных".into()))?);
        let key = hex::decode(config["db_key"].as_str().unwrap_or("")).map_err(|_| Error::Invalid("Некорректный ключ хранилища".into()))?;
        for d in ["files", "outgoing", "tmp", "avatars"] {
            std::fs::create_dir_all(data_dir.join(d))?;
        }
        let db = Db::open(data_dir.join("neochat.db").to_str().unwrap_or("neochat.db"), &key)?;
        // Leftover decrypted temp files from a previous run are removed on start.
        if let Ok(rd) = std::fs::read_dir(data_dir.join("tmp")) {
            for e in rd.flatten() {
                let _ = std::fs::remove_file(e.path());
            }
        }
        let inner = Arc::new(Inner {
            db: Mutex::new(db),
            data_dir,
            cb,
            wake: (Mutex::new(false), Condvar::new()),
            foreground: AtomicBool::new(config["foreground"].as_bool().unwrap_or(true)),
            background: AtomicBool::new(false),
            shutdown: AtomicBool::new(false),
            conn_state: AtomicU8::new(STATE_OFFLINE),
            refresh_lock: Mutex::new(()),
            ws_active: AtomicBool::new(false),
            cycle_lock: Mutex::new(()),
            presence: Mutex::new(HashMap::new()),
            typing_sent: Mutex::new(HashMap::new()),
            tz_offset: AtomicI32::new(config["tz_offset"].as_i64().unwrap_or(0) as i32),
            cur_call: Mutex::new(None),
            ice_cache: Mutex::new(None),
        });
        let w = inner.clone();
        let worker = std::thread::Builder::new().name("neocore-worker".into()).spawn(move || w.worker_loop()).ok();
        Ok(Core { inner, worker })
    }

    pub fn call(&self, method: &str, params: &Value) -> Result<Value> {
        self.inner.call(method, params)
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        self.inner.shutdown.store(true, Ordering::SeqCst);
        self.inner.wake();
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}

pub fn s<'a>(p: &'a Value, k: &str) -> Result<&'a str> {
    p[k].as_str().ok_or_else(|| Error::Invalid(format!("Не указано поле {k}")))
}

impl Inner {
    pub fn db(&self) -> MutexGuard<'_, Db> {
        self.db.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn emit(&self, v: Value) {
        // Notification producers call this after releasing their database transaction.
        // Keep delivery and unread counts active while the conversation is in the archive.
        if v["type"] == "notify" {
            let archived = self.db().conn.query_row(
                "SELECT in_archive FROM conversations WHERE id=?1",
                [v["conversation_id"].as_str().unwrap_or("")],
                |r| r.get::<_, bool>(0),
            ).unwrap_or(false);
            if archived { return; }
        }
        (self.cb)(&v.to_string());
    }

    pub fn wake(&self) {
        let (m, c) = &self.wake;
        *m.lock().unwrap_or_else(|e| e.into_inner()) = true;
        c.notify_all();
    }

    pub fn wait(&self, timeout: Duration) {
        let (m, c) = &self.wake;
        let mut woke = m.lock().unwrap_or_else(|e| e.into_inner());
        if !*woke {
            woke = c.wait_timeout(woke, timeout).map(|r| r.0).unwrap_or_else(|e| e.into_inner().0);
        }
        *woke = false;
    }

    pub fn set_conn(&self, st: u8) {
        if self.conn_state.swap(st, Ordering::SeqCst) != st {
            let name = match st {
                STATE_ONLINE => "online",
                STATE_CONNECTING => "connecting",
                _ => "offline",
            };
            self.emit(json!({"type": "connection", "state": name}));
        }
    }

    pub fn api(&self) -> Result<Api> {
        let server = kv_get_str(&self.db().conn, "server")?.ok_or(Error::NotLoggedIn)?;
        Ok(Api::new(&server))
    }

    pub fn logged_in(&self) -> bool {
        let db = self.db();
        kv_get(&db.conn, "refresh_token").ok().flatten().is_some() && kv_get(&db.conn, "user_id").ok().flatten().is_some()
    }

    pub fn me(&self) -> Result<(String, String)> {
        let db = self.db();
        Ok((
            kv_get_str(&db.conn, "user_id")?.ok_or(Error::NotLoggedIn)?,
            kv_get_str(&db.conn, "device_id")?.ok_or(Error::NotLoggedIn)?,
        ))
    }

    // ---- tokens ----

    fn token(&self, force_refresh: bool) -> Result<String> {
        {
            let db = self.db();
            let tok = kv_get_str(&db.conn, "access_token")?;
            let exp: i64 = kv_get_str(&db.conn, "access_expires_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            if let (Some(t), false) = (tok, force_refresh) {
                if exp > now_ms() + 30_000 {
                    return Ok(t);
                }
            }
        }
        let _g = self.refresh_lock.lock().unwrap_or_else(|e| e.into_inner());
        // Another thread may have refreshed while we waited.
        let (refresh, old_access) = {
            let db = self.db();
            let exp: i64 = kv_get_str(&db.conn, "access_expires_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            let tok = kv_get_str(&db.conn, "access_token")?;
            if let Some(t) = &tok {
                if exp > now_ms() + 30_000 && !force_refresh {
                    return Ok(t.clone());
                }
            }
            (kv_get_str(&db.conn, "refresh_token")?.ok_or(Error::NotLoggedIn)?, tok)
        };
        let _ = old_access;
        let res = self.api()?.call("POST", "/v1/sessions/refresh", None, Some(&json!({"refresh_token": refresh})));
        match res {
            Ok(v) => {
                let db = self.db();
                self.store_tokens(&db.conn, &v)?;
                Ok(v["access_token"].as_str().unwrap_or_default().to_string())
            }
            Err(e @ Error::Api { status: 401, .. }) => {
                let db = self.db();
                kv_del(&db.conn, "access_token")?;
                kv_del(&db.conn, "refresh_token")?;
                drop(db);
                self.emit(json!({"type": "auth_required"}));
                Err(e)
            }
            Err(e) => Err(e),
        }
    }

    pub fn store_tokens(&self, conn: &rusqlite::Connection, v: &Value) -> Result<()> {
        let access = v["access_token"].as_str().ok_or_else(|| Error::Invalid("Сервер не выдал токен входа".into()))?;
        let refresh = v["refresh_token"].as_str().ok_or_else(|| Error::Invalid("Сервер не выдал токен входа".into()))?;
        let ttl = v["access_expires_in"].as_i64().unwrap_or(900);
        kv_set(conn, "access_token", access.as_bytes())?;
        kv_set(conn, "refresh_token", refresh.as_bytes())?;
        kv_set(conn, "access_expires_at", (now_ms() + ttl * 1000).to_string().as_bytes())?;
        Ok(())
    }

    /// Authenticated request with one transparent refresh on 401.
    pub fn authed(&self, method: &str, path: &str, body: Option<&Value>) -> Result<Value> {
        let api = self.api()?;
        let t = self.token(false)?;
        let res = api.call(method, path, Some(&t), body);
        let res = match res {
            Err(Error::Api { status: 401, .. }) => {
                let t = self.token(true)?;
                api.call(method, path, Some(&t), body)
            }
            r => r,
        };
        match &res {
            Ok(_) => self.set_conn(STATE_ONLINE),
            Err(Error::Network(_)) => self.set_conn(STATE_OFFLINE),
            _ => {}
        }
        res
    }

    pub fn access_token(&self) -> Result<String> {
        self.token(false)
    }

    // ---- dispatch ----

    pub fn call(self: &Arc<Self>, method: &str, p: &Value) -> Result<Value> {
        match method {
            "status" => self.status(),
            "register" => self.register(p),
            "login" => self.login(p),
            "recover" => self.recover(p),
            "logout" => self.logout(),
            "qr_login_start" => self.qr_login_start(p),
            "qr_login_poll" => self.qr_login_poll(),
            "qr_login_approve" => self.qr_login_approve(p),
            "account_settings" => self.authed("GET", "/v1/accounts/current/settings", None),
            "set_profile" => self.set_profile(p),
            "set_avatar" => self.set_avatar(p),
            "check_update" => self.check_update(p),
            "download_update" => self.download_update(p),
            "set_two_factor" => self
                .authed("PUT", "/v1/accounts/current/settings", Some(&json!({"two_factor": p["enabled"].as_bool().unwrap_or(false)})))
                .map(|_| Value::Null),
            "devices" => self.authed("GET", "/v1/devices", None),
            "revoke_device" => self.revoke_device(p),
            "set_foreground" => {
                let fg = p["active"].as_bool().unwrap_or(true);
                self.foreground.store(fg, Ordering::SeqCst);
                self.wake();
                Ok(Value::Null)
            }
            "set_background_connection" => {
                self.background.store(p["enabled"].as_bool().unwrap_or(false), Ordering::SeqCst);
                self.wake();
                Ok(Value::Null)
            }
            "sync_now" => self.sync_now(),
            "set_push_token" => self.authed("PUT", "/v1/devices/current/push", Some(p)).map(|_| Value::Null),
            "resolve_contact" => self.resolve_contact(p),
            "list_contacts" => self.list_contacts(p),
            "update_contact" => self.update_contact(p),
            "delete_contact" => self.delete_contact(p),
            "contact_info" => self.contact_info(p),
            "user_profile" => self.user_profile(p),
            "profile_items" => self.profile_items(p),
            "set_profile_note" => self.set_profile_note(p),
            "profile_qr" => self.profile_qr(p),
            "profile_photos" => self.profile_photos(p),
            "profile_photo" => self.profile_photo(p),
            "set_profile_photo" => self.set_profile_photo(p),
            "delete_profile_photo" => self.delete_profile_photo(p),
            "mention_candidates" => self.mention_candidates(p),
            "lookup_user" => self.lookup_user(p),
            "verify_contact" => self.verify_contact(p),
            "accept_key_change" => self.accept_key_change(p),
            "open_direct" => self.open_direct(p),
            "create_group" => self.create_group(p),
            "group_set_members" => self.group_set_members(p),
            "group_leave" => self.group_leave(p),
            "group_rename" => self.group_rename(p),
            "group_set_info" => self.group_set_info(p),
            "group_set_avatar" => self.group_set_avatar(p),
            "group_set_settings" => self.group_set_settings(p),
            "group_set_tag" => self.group_set_tag(p),
            "send_poll" => self.send_poll(p),
            "vote_poll" => self.vote_poll(p),
            "close_poll" => self.close_poll(p),
            "list_conversations" => self.list_conversations(p),
            "get_conversation" => self.get_conversation(p),
            "list_messages" => self.list_messages(p),
            "search_messages" => self.search_messages(p),
            "send_text" => self.send_text(p),
            "send_file" => self.send_file(p),
            "retry_message" => self.retry_message(p),
            "delete_local_message" => self.delete_local_message(p),
            "delete_everywhere" => self.delete_everywhere(p),
            "edit_message" => self.edit_message(p),
            "forward_message" => self.forward_message(p),
            "download_file" => self.download_file(p),
            "export_file" => self.export_file(p),
            "set_draft" => self.set_draft(p),
            "mark_read" => self.mark_read(p),
            "get_settings" => self.get_settings(),
            "whats_new" => self.whats_new(p),
            "whats_new_seen" => self.whats_new_seen(p),
            "set_settings" => self.set_settings(p),
            "safety_number" => self.contact_info(p),
            "react" => self.react(p),
            "set_archived" => self.set_archived(p),
            "set_pinned" => self.set_pinned(p),
            "clear_conversation" => self.clear_conversation(p),
            "presence" => self.presence(p),
            "send_typing" => self.send_typing(p),
            "set_last_seen_visible" => self.set_last_seen_visible(p),
            "lock_set" => self.lock_set(p),
            "lock_check" => self.lock_check(p),
            "lock_clear" => self.lock_clear(),
            "lock_status" => self.lock_status(),
            "my_qr" => self.my_qr(),
            "add_contact_qr" => self.add_contact_qr(p),
            "call_start" => self.call_start(p),
            "call_offer" => self.call_offer(p),
            "call_accept" => self.call_accept(p),
            "call_answer" => self.call_answer(p),
            "call_connected" => self.call_connected(p),
            "call_hangup" => self.call_hangup(p),
            "call_media" => self.call_media(p),
            "call_current" => self.call_current(),
            "list_calls" => self.list_calls(p),
            _ => Err(Error::Invalid(format!("Неизвестный метод {method}"))),
        }
    }

    pub(crate) fn status(&self) -> Result<Value> {
        let db = self.db();
        let c = &db.conn;
        let st = match self.conn_state.load(Ordering::SeqCst) {
            STATE_ONLINE => "online",
            STATE_CONNECTING => "connecting",
            _ => "offline",
        };
        Ok(json!({
            "logged_in": kv_get(c, "refresh_token")?.is_some() && kv_get(c, "user_id")?.is_some(),
            "display_name": kv_get_str(c, "display_name")?,
            "bio": kv_get_str(c, "bio")?.unwrap_or_default(),
            "avatar_path": self.avatar_path(&kv_get_str(c, "avatar")?.unwrap_or_default()),
            "number": kv_get_str(c, "number")?,
            "has_device_keys": kv_get(c, "device_identity")?.is_some() && kv_get(c, "user_id")?.is_some(),
            "user_id": kv_get_str(c, "user_id")?,
            "username": kv_get_str(c, "username")?,
            "device_id": kv_get_str(c, "device_id")?,
            "primary": kv_get_str(c, "primary")?.as_deref() == Some("1"),
            "connection": st,
        }))
    }

    fn wipe_account(&self, conn: &rusqlite::Connection) -> Result<()> {
        // Used only before creating a fresh identity on this installation.
        conn.execute_batch(
            "DELETE FROM kv WHERE k<>'history_owner'; DELETE FROM sessions; DELETE FROM identities; DELETE FROM prekeys; DELETE FROM signed_prekeys;
             DELETE FROM kyber_prekeys; DELETE FROM kyber_used; DELETE FROM known_devices; DELETE FROM outbox; DELETE FROM processed;",
        )?;
        Ok(())
    }

    fn register(&self, p: &Value) -> Result<Value> {
        let server = p["server"].as_str().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(DEFAULT_SERVER).to_string();
        let nickname = s(p, "username")?.trim().trim_start_matches('@').to_string();
        let username = nickname.to_lowercase();
        let display = s(p, "display_name")?.trim().to_string();
        if display.is_empty() {
            return Err(Error::Invalid("Введите имя".into()));
        }
        let pw = backup::derive(&username, s(p, "password")?)?;
        let device = keys::new_device()?;
        let account = IdentityKeyPair::generate(&mut rand::rng());
        let secret = keys::random_bytes::<32>();
        let roster = Roster {
            username: username.clone(),
            epoch: 1,
            revision: 1,
            prev_hash: [0; 32],
            account_key: account.identity_key().serialize().to_vec(),
            devices: vec![device.roster_entry()],
        };
        let (blob, sig) = roster.sign(&account)?;
        let body = json!({
            "username": nickname,
            "display_name": display,
            "password": pw.auth,
            "key_backup": b64(&backup::seal_keys(&pw.backup, &account.serialize())?),
            "account_identity_key": b64(&account.identity_key().serialize()),
            "recovery_hash": b64(&keys::sha256(&secret)),
            "device": device.upload_json(p["device_name"].as_str().unwrap_or("NeoChat"), s(p, "platform")?)?,
            "roster": {"revision": "1", "blob": b64(&blob), "signature": b64(&sig)},
        });
        let out = Api::new(&server).call("POST", "/v1/accounts", None, Some(&body))?;
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        self.wipe_account(&tx)?;
        device.persist(&tx)?;
        self.store_account(&tx, &server, &out, &username, true)?;
        kv_set(&tx, "account_identity", &account.serialize())?;
        kv_set(&tx, "backup_key", &pw.backup[..])?;
        self.store_own_roster(&tx, "1", &blob, &sig, &account.identity_key().serialize())?;
        tx.commit()?;
        drop(db);
        self.after_login();
        Ok(json!({"recovery_code": keys::recovery_encode(&secret), "user_id": out["user_id"]}))
    }

    fn store_account(&self, tx: &rusqlite::Connection, server: &str, out: &Value, username: &str, primary: bool) -> Result<()> {
        kv_set(tx, "server", server.trim_end_matches('/').as_bytes())?;
        set_history_owner(tx, out["user_id"].as_str().unwrap_or_default())?;
        kv_set(tx, "user_id", out["user_id"].as_str().unwrap_or_default().as_bytes())?;
        kv_set(tx, "username", username.as_bytes())?;
        kv_set(tx, "primary", if primary { b"1" } else { b"0" })?;
        self.store_tokens(tx, out)
    }

    pub fn store_own_roster(&self, tx: &rusqlite::Connection, rev: &str, blob: &[u8], sig: &[u8], account_key: &[u8]) -> Result<()> {
        let r = keys::verify_roster(account_key, blob, sig)?;
        let me = kv_get_str(tx, "user_id")?.ok_or(Error::NotLoggedIn)?;
        kv_set(tx, "roster_revision", rev.as_bytes())?;
        kv_set(tx, "roster_blob", blob)?;
        kv_set(tx, "roster_sig", sig)?;
        kv_set(tx, "account_key", account_key)?;
        tx.execute("DELETE FROM known_devices WHERE user_id=?1", [&me])?;
        for d in &r.devices {
            tx.execute(
                "INSERT OR REPLACE INTO known_devices(user_id, device_id, identity_key) VALUES (?1, ?2, ?3)",
                params![me, d.device_id, d.identity_key],
            )?;
        }
        Ok(())
    }

    fn after_login(&self) {
        // For a while after sign-in, fresher history copies from other devices are merged in.
        let _ = kv_set(&self.db().conn, "history_fresh_until", (now_ms() + 15 * 60 * 1000).to_string().as_bytes());
        // A fresh sign-in does not show the notes of the version it started on.
        let _ = kv_set(&self.db().conn, "fresh_install", b"1");
        let _ = self.refresh_profile();
        self.set_conn(STATE_ONLINE);
        self.emit(json!({"type": "account_changed"}));
        self.wake();
    }

    /// Signs in with the password. A device that already has keys for this account
    /// proves itself with its auth key; a new device restores the account identity
    /// from the password-protected backup, adds itself to the roster and imports the
    /// history. With two-factor sign-in the server first asks for a code that it sends
    /// to the account's other devices ("code_required").
    fn login(&self, p: &Value) -> Result<Value> {
        let server = p["server"].as_str().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(DEFAULT_SERVER).to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let salt = match Api::new(&server).call("POST", "/v1/accounts/password-salt", None, Some(&json!({"username": username}))) {
            Ok(v) => s(&v, "salt")?.to_string(),
            Err(Error::Api { status: 404, .. }) => username.clone(),
            Err(e) => return Err(e),
        };
        let pw = backup::derive(&salt, s(p, "password")?)?;
        let (device_id, stored_user, has_identity) = {
            let db = self.db();
            (kv_get_str(&db.conn, "device_id")?, kv_get_str(&db.conn, "username")?, kv_get(&db.conn, "user_id")?.is_some())
        };
        let device_id = match (device_id, stored_user, has_identity) {
            (Some(d), Some(u), true) if u.to_lowercase() == username => d,
            _ => return self.login_new_device(&server, &username, &pw, p),
        };
        let ts = (now_ms() / 1000).to_string();
        let sig = keys::login_signature(&self.db().conn, &username, &device_id, &ts)?;
        let out = match Api::new(&server).call(
            "POST",
            "/v1/sessions",
            None,
            Some(&json!({"username": username, "password": pw.auth, "device_id": device_id, "timestamp": ts, "signature": b64(&sig)})),
        ) {
            // The server no longer knows this device (revoked, or the account was recreated):
            // sign in as a new device; a wrong password fails there with the same error.
            Err(e) if e.is_api("invalid_credentials") => return self.login_new_device(&server, &username, &pw, p),
            r => r?,
        };
        {
            let db = self.db();
            kv_set(&db.conn, "server", server.trim_end_matches('/').as_bytes())?;
            kv_set(&db.conn, "backup_key", &pw.backup[..])?;
            self.store_tokens(&db.conn, &out)?;
        }
        self.after_login();
        Ok(Value::Null)
    }

    fn login_new_device(&self, server: &str, username: &str, pw: &backup::PasswordKeys, p: &Value) -> Result<Value> {
        let api = Api::new(server);
        let mut start = json!({"username": username, "password": pw.auth});
        if let Some(code) = p["code"].as_str().map(str::trim).filter(|c| !c.is_empty()) {
            start["code"] = code.into();
        }
        let st = api.call("POST", "/v1/devices/new/start", None, Some(&start))?;
        let account_key = unb64(s(&st, "account_identity_key")?)?;
        let account = IdentityKeyPair::try_from(backup::open_keys(&pw.backup, &unb64(s(&st, "key_backup")?)?)?.as_slice())?;
        if account.identity_key().serialize()[..] != account_key[..] {
            return Err(Error::Crypto("Копия ключей не подходит к аккаунту".into()));
        }
        let blob = unb64(st["roster"]["blob"].as_str().unwrap_or_default())?;
        let sig = unb64(st["roster"]["signature"].as_str().unwrap_or_default())?;
        let mut r = keys::verify_roster(&account_key, &blob, &sig)?;
        if r.devices.len() >= keys::MAX_DEVICES {
            return Err(Error::Invalid(format!("У аккаунта уже {} устройств. Отзовите одно из них", keys::MAX_DEVICES)));
        }
        let device = keys::new_device()?;
        r.devices.push(device.roster_entry());
        r.prev_hash = keys::sha256(&blob);
        r.revision += 1;
        let (nb, ns) = r.sign(&account)?;
        let out = api.call(
            "POST",
            "/v1/devices/new/finish",
            None,
            Some(&json!({
                "start_token": s(&st, "start_token")?,
                "device": device.upload_json(p["device_name"].as_str().unwrap_or("NeoChat"), s(p, "platform")?)?,
                "roster": {"revision": r.revision.to_string(), "blob": b64(&nb), "signature": b64(&ns)},
            })),
        )?;
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            self.wipe_account(&tx)?;
            device.persist(&tx)?;
            self.store_account(&tx, server, &out, username, true)?;
            kv_set(&tx, "account_identity", &account.serialize())?;
            kv_set(&tx, "backup_key", &pw.backup[..])?;
            self.store_own_roster(&tx, &r.revision.to_string(), &nb, &ns, &account_key)?;
            tx.commit()?;
        }
        if let Err(e) = self.restore_history(&pw.backup) {
            self.emit(json!({"type": "warning", "message": format!("Переписку не удалось перенести: {}", e.message())}));
        }
        self.after_login();
        Ok(Value::Null)
    }

    fn logout(&self) -> Result<Value> {
        let _ = self.authed("DELETE", "/v1/sessions/current", None);
        let db = self.db();
        kv_del(&db.conn, "access_token")?;
        kv_del(&db.conn, "refresh_token")?;
        drop(db);
        self.set_conn(STATE_OFFLINE);
        self.emit(json!({"type": "account_changed"}));
        Ok(Value::Null)
    }

    /// New account identity from the offline recovery code. Old devices are revoked
    /// and contacts see an identity change; past history is not restored.
    fn recover(&self, p: &Value) -> Result<Value> {
        let server = p["server"].as_str().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(DEFAULT_SERVER).to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let old_secret = keys::recovery_decode(s(p, "recovery_code")?)?;
        let pw = backup::derive(&username, s(p, "new_password")?)?;
        let device = keys::new_device()?;
        let account = IdentityKeyPair::generate(&mut rand::rng());
        let secret = keys::random_bytes::<32>();
        // The previous roster revision is unknown on a fresh install; a timestamp is
        // always above any counter-based revision and stays monotonic afterwards.
        let rev = now_ms() as u64;
        let roster = Roster {
            username: username.clone(),
            epoch: rev,
            revision: rev,
            prev_hash: [0; 32],
            account_key: account.identity_key().serialize().to_vec(),
            devices: vec![device.roster_entry()],
        };
        let (blob, sig) = roster.sign(&account)?;
        let body = json!({
            "username": username,
            "recovery_secret": b64(&old_secret),
            "new_password": pw.auth,
            "key_backup": b64(&backup::seal_keys(&pw.backup, &account.serialize())?),
            "recovery_hash": b64(&keys::sha256(&secret)),
            "account_identity_key": b64(&account.identity_key().serialize()),
            "device": device.upload_json(p["device_name"].as_str().unwrap_or("NeoChat"), s(p, "platform")?)?,
            "roster": {"revision": rev.to_string(), "blob": b64(&blob), "signature": b64(&sig)},
        });
        let out = Api::new(&server).call("POST", "/v1/accounts/recover", None, Some(&body))?;
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        self.wipe_account(&tx)?;
        device.persist(&tx)?;
        self.store_account(&tx, &server, &out, &username, true)?;
        kv_set(&tx, "account_identity", &account.serialize())?;
        kv_set(&tx, "backup_key", &pw.backup[..])?;
        self.store_own_roster(&tx, &rev.to_string(), &blob, &sig, &account.identity_key().serialize())?;
        tx.commit()?;
        drop(db);
        self.after_login();
        Ok(json!({"recovery_code": keys::recovery_encode(&secret)}))
    }

    // ---- QR sign-in on a computer ----

    /// Prepares this installation as a new device and returns the QR code to show.
    /// The QR carries the device keys and a one-time transfer secret that the server
    /// never sees: the phone seals the account keys with it.
    fn qr_login_start(&self, p: &Value) -> Result<Value> {
        let server = p["server"].as_str().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(DEFAULT_SERVER).to_string();
        let device = keys::new_device()?;
        let transfer = keys::random_bytes::<32>();
        let out = Api::new(&server).call(
            "POST",
            "/v1/devices/qr",
            None,
            Some(&json!({"device": device.upload_json(p["device_name"].as_str().unwrap_or("NeoChat"), s(p, "platform")?)?})),
        )?;
        let link_id = s(&out, "link_id")?.to_string();
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        self.wipe_account(&tx)?;
        device.persist(&tx)?;
        kv_set(&tx, "server", server.trim_end_matches('/').as_bytes())?;
        kv_set(&tx, "link_id", link_id.as_bytes())?;
        kv_set(&tx, "link_secret", s(&out, "link_secret")?.as_bytes())?;
        kv_set(&tx, "link_transfer", &transfer)?;
        tx.commit()?;
        let e = device.roster_entry();
        let qr = format!("neochat-login:1:{}:{}:{}:{}", link_id, b64(&e.identity_key), b64(&e.auth_key), b64(&transfer));
        Ok(json!({"qr": qr, "qr_rows": qr_rows(&qr)?, "expires_in": out["expires_in"]}))
    }

    fn qr_login_poll(&self) -> Result<Value> {
        let (server, link_id, secret, transfer) = {
            let db = self.db();
            (
                kv_get_str(&db.conn, "server")?.ok_or(Error::NotLoggedIn)?,
                kv_get_str(&db.conn, "link_id")?.ok_or_else(|| Error::Invalid("Вход по QR-коду не начат".into()))?,
                kv_get_str(&db.conn, "link_secret")?.unwrap_or_default(),
                kv_get(&db.conn, "link_transfer")?.unwrap_or_default(),
            )
        };
        let out = Api::new(&server).get_with_header(&format!("/v1/devices/qr/{link_id}"), ("X-Link-Secret", &secret))?;
        let status = out["status"].as_str().unwrap_or("pending").to_string();
        if status != "approved" {
            return Ok(json!({"status": status}));
        }
        let transfer: [u8; 32] = transfer.try_into().map_err(|_| Error::Crypto("Повреждён секрет входа".into()))?;
        let sealed: Value = serde_json::from_slice(&backup::open(&transfer, &unb64(s(&out, "sealed")?)?)?)?;
        let account = IdentityKeyPair::try_from(unb64(s(&sealed, "account_identity")?)?.as_slice())?;
        let backup_key: [u8; 32] = unb64(s(&sealed, "backup_key")?)?.try_into().map_err(|_| Error::Crypto("Повреждён ключ копии".into()))?;
        let user_id = s(&out, "user_id")?.to_string();
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            set_history_owner(&tx, &user_id)?;
            kv_set(&tx, "user_id", user_id.as_bytes())?;
            kv_set(&tx, "username", s(&out, "username")?.as_bytes())?;
            kv_set(&tx, "primary", b"1")?;
            kv_set(&tx, "account_identity", &account.serialize())?;
            kv_set(&tx, "backup_key", &backup_key)?;
            self.store_tokens(&tx, &out["session"])?;
            for k in ["link_id", "link_secret", "link_transfer"] {
                kv_del(&tx, k)?;
            }
            tx.commit()?;
        }
        // The roster on the server must be signed by the account we just received and list this device.
        let me = self.authed("GET", &format!("/v1/users/{user_id}"), None)?;
        let account_key = account.identity_key().serialize().to_vec();
        if unb64(s(&me, "account_identity_key")?)? != account_key {
            return Err(Error::Crypto("Ключ аккаунта не совпадает. Вход отменён".into()));
        }
        let blob = unb64(me["roster"]["blob"].as_str().unwrap_or_default())?;
        let sig = unb64(me["roster"]["signature"].as_str().unwrap_or_default())?;
        let roster = keys::verify_roster(&account_key, &blob, &sig)?;
        {
            let db = self.db();
            let my_device = kv_get_str(&db.conn, "device_id")?.unwrap_or_default();
            if !roster.devices.iter().any(|d| d.device_id == my_device) {
                return Err(Error::Crypto("Список устройств не содержит это устройство".into()));
            }
            self.store_own_roster(&db.conn, me["roster"]["revision"].as_str().unwrap_or("1"), &blob, &sig, &account_key)?;
        }
        if let Err(e) = self.restore_history(&backup_key) {
            self.emit(json!({"type": "warning", "message": format!("Переписку не удалось перенести: {}", e.message())}));
        }
        self.after_login();
        Ok(json!({"status": "approved"}))
    }

    /// Phone side: lets the computer from the scanned QR code into this account.
    fn qr_login_approve(&self, p: &Value) -> Result<Value> {
        let qr = s(p, "qr")?.trim();
        let parts: Vec<&str> = qr.split(':').collect();
        if parts.len() != 6 || parts[0] != "neochat-login" || parts[1] != "1" {
            return Err(Error::Invalid("Это не QR-код входа NeoChat".into()));
        }
        let (link_id, qr_identity, qr_auth) = (parts[2], unb64(parts[3])?, unb64(parts[4])?);
        let transfer: [u8; 32] = unb64(parts[5])?.try_into().map_err(|_| Error::Invalid("Это не QR-код входа NeoChat".into()))?;
        let (account, backup_key) = {
            let db = self.db();
            (keys::account_keypair(&db.conn)?, kv_get(&db.conn, "backup_key")?)
        };
        let account = account.ok_or_else(|| Error::Invalid("На этом устройстве нет ключей аккаунта".into()))?;
        let backup_key: [u8; 32] = backup_key
            .and_then(|k| k.try_into().ok())
            .ok_or_else(|| Error::Invalid("Выйдите и войдите заново по паролю, затем повторите".into()))?;
        let pending = self.authed("GET", &format!("/v1/devices/qr/{link_id}/device"), None)?;
        let identity = unb64(pending["identity_key"].as_str().unwrap_or_default())?;
        let auth = unb64(pending["auth_key"].as_str().unwrap_or_default())?;
        if identity != qr_identity || auth != qr_auth {
            return Err(Error::Crypto("Ключи устройства не совпадают с QR-кодом. Вход отменён".into()));
        }
        let new_dev = keys::RosterDevice { device_id: pending["device_id"].as_str().unwrap_or_default().to_string(), identity_key: identity, auth_key: auth };
        let (blob, sig, rev) = self.next_own_roster(&account, |devs| {
            if devs.len() >= keys::MAX_DEVICES {
                return Err(Error::Invalid(format!("У аккаунта уже {} устройств. Отзовите одно из них", keys::MAX_DEVICES)));
            }
            devs.push(new_dev.clone());
            Ok(())
        })?;
        let sealed = backup::seal(&transfer, json!({"account_identity": b64(&account.serialize()), "backup_key": b64(&backup_key)}).to_string().as_bytes())?;
        // The new device should find the latest history right away.
        let _ = self.upload_history(&backup_key);
        self.authed(
            "POST",
            &format!("/v1/devices/qr/{link_id}/approve"),
            Some(&json!({"roster": {"revision": rev.to_string(), "blob": b64(&blob), "signature": b64(&sig)}, "sealed": b64(&sealed)})),
        )?;
        let db = self.db();
        self.store_own_roster(&db.conn, &rev.to_string(), &blob, &sig, &account.identity_key().serialize())?;
        drop(db);
        self.emit(json!({"type": "devices_changed"}));
        Ok(json!({"device_id": new_dev.device_id, "name": pending["name"]}))
    }

    /// Builds the next revision of our own roster with `edit` applied.
    fn next_own_roster(&self, account: &IdentityKeyPair, edit: impl FnOnce(&mut Vec<keys::RosterDevice>) -> Result<()>) -> Result<(Vec<u8>, Vec<u8>, u64)> {
        let (blob, sig) = {
            let db = self.db();
            (kv_get(&db.conn, "roster_blob")?.ok_or(Error::NotLoggedIn)?, kv_get(&db.conn, "roster_sig")?.ok_or(Error::NotLoggedIn)?)
        };
        let mut r = keys::verify_roster(&account.identity_key().serialize(), &blob, &sig)?;
        edit(&mut r.devices)?;
        r.prev_hash = keys::sha256(&blob);
        r.revision += 1;
        let (nb, ns) = r.sign(account)?;
        Ok((nb, ns, r.revision))
    }

    fn revoke_device(&self, p: &Value) -> Result<Value> {
        let target = s(p, "device_id")?.to_string();
        let (_, my_device) = self.me()?;
        if target == my_device {
            self.authed("DELETE", &format!("/v1/devices/{target}"), None)?;
            return self.logout();
        }
        let account = keys::account_keypair(&self.db().conn)?.ok_or_else(|| Error::Invalid("Отзывает только основное устройство".into()))?;
        let (blob, sig, rev) = self.next_own_roster(&account, |devs| {
            devs.retain(|d| d.device_id != target);
            Ok(())
        })?;
        self.authed(
            "DELETE",
            &format!("/v1/devices/{target}"),
            Some(&json!({"roster": {"revision": rev.to_string(), "blob": b64(&blob), "signature": b64(&sig)}})),
        )?;
        let db = self.db();
        self.store_own_roster(&db.conn, &rev.to_string(), &blob, &sig, &account.identity_key().serialize())?;
        db.conn.execute("DELETE FROM sessions WHERE address=?1", [&target])?;
        drop(db);
        self.emit(json!({"type": "devices_changed"}));
        Ok(Value::Null)
    }

    fn get_settings(&self) -> Result<Value> {
        let db = self.db();
        Ok(json!({
            "read_receipts": kv_get_str(&db.conn, "read_receipts")?.as_deref() != Some("0"),
            "quick_reaction": kv_get_str(&db.conn, "quick_reaction")?.unwrap_or_else(|| crate::state::DEFAULT_QUICK_REACTION.into()),
        }))
    }

    /// Account preferences; they follow the user to every own device.
    fn set_settings(&self, p: &Value) -> Result<Value> {
        if let Some(r) = p["quick_reaction"].as_str() {
            if !crate::social::valid_reaction(r.trim()) {
                return Err(Error::Invalid("Быстрая реакция должна быть эмодзи".into()));
            }
        }
        let mut touched = Vec::new();
        {
            let db = self.db();
            if let Some(v) = p["read_receipts"].as_bool() {
                kv_set(&db.conn, "read_receipts", if v { b"1" } else { b"0" })?;
                touched.push("pref:read_receipts");
            }
            if let Some(r) = p["quick_reaction"].as_str() {
                kv_set(&db.conn, "quick_reaction", r.trim().as_bytes())?;
                touched.push("pref:quick_reaction");
            }
        }
        for k in touched {
            self.touch(k);
        }
        self.emit(json!({"type": "settings_changed"}));
        Ok(Value::Null)
    }

    // ---- contacts and verified identities ----

    /// Applies a user profile from the server: pins the account key on first sight
    /// (TOFU), flags a key change otherwise, and replaces known devices only from a
    /// roster that verifies and does not roll back.
    pub fn apply_user(&self, u: &Value, as_contact: Option<(&str, &str)>) -> Result<Value> {
        let user_id = s(u, "user_id")?.to_string();
        let account_key = unb64(s(u, "account_identity_key")?)?;
        IdentityKey::decode(&account_key)?;
        let blob = unb64(u["roster"]["blob"].as_str().unwrap_or_default())?;
        let sig = unb64(u["roster"]["signature"].as_str().unwrap_or_default())?;
        let roster = keys::verify_roster(&account_key, &blob, &sig)?;
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        let existing: Option<(Vec<u8>, i64)> = tx
            .query_row("SELECT account_key, roster_revision FROM contacts WHERE user_id=?1", [&user_id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?;
        let mut key_changed = false;
        match &existing {
            None => {
                tx.execute(
                    "INSERT INTO contacts(user_id, username, account_key, identity_epoch, roster_revision, added_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    params![user_id, s(u, "username")?, account_key, u["identity_epoch"].as_str().unwrap_or("1"), roster.revision as i64, now_ms()],
                )?;
            }
            Some((old_key, old_rev)) => {
                if *old_key != account_key {
                    key_changed = true;
                    tx.execute(
                        "UPDATE contacts SET key_changed=1, verified=0, pending_account_key=?2, identity_epoch=?3, roster_revision=?4 WHERE user_id=?1",
                        params![user_id, account_key, u["identity_epoch"].as_str().unwrap_or("1"), roster.revision as i64],
                    )?;
                } else if (roster.revision as i64) < *old_rev {
                    return Err(Error::Crypto("Сервер вернул устаревший список устройств".into()));
                } else {
                    tx.execute("UPDATE contacts SET roster_revision=?2 WHERE user_id=?1", params![user_id, roster.revision as i64])?;
                }
            }
        }
        tx.execute(
            "UPDATE contacts SET username=?2, profile_name=?3, number=?4, bio=?5, avatar=?6 WHERE user_id=?1",
            params![user_id, s(u, "username")?, u["display_name"].as_str().unwrap_or("").trim(), u["number"].as_str().unwrap_or(""), u["bio"].as_str().unwrap_or("").chars().take(100).collect::<String>(), u["avatar_id"].as_str().unwrap_or("")],
        )?;
        if let Some((first, last)) = as_contact {
            tx.execute(
                "UPDATE contacts SET is_contact=1, first_name=?2, last_name=?3 WHERE user_id=?1",
                params![user_id, first.trim(), last.trim()],
            )?;
        }
        // Devices from the newest verified roster. After a key change, decrypting from
        // the new devices works, but sending stays blocked until the user accepts.
        tx.execute("DELETE FROM known_devices WHERE user_id=?1", [&user_id])?;
        for d in &roster.devices {
            tx.execute(
                "INSERT OR REPLACE INTO known_devices(user_id, device_id, identity_key) VALUES (?1, ?2, ?3)",
                params![user_id, d.device_id, d.identity_key],
            )?;
        }
        tx.commit()?;
        drop(db);
        let _ = self.cache_user_avatar(u);
        if key_changed {
            self.emit(json!({"type": "identity_changed", "user_id": user_id}));
        }
        self.contact_json(&user_id)
    }

    /// Our own public profile: the username as typed, display name and numeric ID.
    pub fn refresh_profile(&self) -> Result<()> {
        let (me, _) = self.me()?;
        let u = self.authed("GET", &format!("/v1/users/{me}"), None)?;
        let db = self.db();
        kv_set(&db.conn, "username", s(&u, "username")?.as_bytes())?;
        kv_set(&db.conn, "display_name", u["display_name"].as_str().unwrap_or("").as_bytes())?;
        kv_set(&db.conn, "number", u["number"].as_str().unwrap_or("").as_bytes())?;
        kv_set(&db.conn, "bio", u["bio"].as_str().unwrap_or("").as_bytes())?;
        kv_set(&db.conn, "avatar", u["avatar_id"].as_str().unwrap_or("").as_bytes())?;
        drop(db);
        let _ = self.cache_user_avatar(&u);
        self.emit(json!({"type": "account_changed"}));
        Ok(())
    }

    /// Display name and/or the about text (up to 100 characters); absent fields stay.
    fn set_profile(&self, p: &Value) -> Result<Value> {
        let mut body = json!({});
        if let Some(name) = p["display_name"].as_str() {
            let name = name.trim();
            if name.is_empty() {
                return Err(Error::Invalid("Введите имя".into()));
            }
            body["display_name"] = json!(name);
        }
        if let Some(bio) = p["bio"].as_str() {
            let bio = bio.trim();
            if bio.chars().count() > 100 {
                return Err(Error::Invalid("О себе: до 100 символов".into()));
            }
            body["bio"] = json!(bio);
        }
        if let Some(username) = p["username"].as_str() {
            let username = username.trim().trim_start_matches('@');
            if !(3..=32).contains(&username.len()) || !username.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
                return Err(Error::Invalid("Имя пользователя: 3–32 латинские буквы, цифры или подчёркивание".into()));
            }
            body["username"] = json!(username);
        }
        self.authed("PUT", "/v1/accounts/current/profile", Some(&body))?;
        self.refresh_profile()?;
        Ok(Value::Null)
    }

    pub fn refresh_user(&self, user_id: &str) -> Result<()> {
        let u = self.authed("GET", &format!("/v1/users/{user_id}"), None)?;
        self.apply_user(&u, None)?;
        Ok(())
    }

    pub fn ensure_user(&self, user_id: &str) -> Result<()> {
        let known: bool = self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM contacts WHERE user_id=?1)", [user_id], |r| r.get(0))?;
        let (me, _) = self.me()?;
        if !known && user_id != me {
            self.refresh_user(user_id)?;
        }
        Ok(())
    }

    fn resolve_contact(&self, p: &Value) -> Result<Value> {
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        // Without a name of our own the contact shows the name from their profile.
        let first = p["first_name"].as_str().unwrap_or("").trim().to_string();
        let u = self.authed("POST", "/v1/contacts/resolve", Some(&json!({"username": username})))?;
        let (me, _) = self.me()?;
        if u["user_id"].as_str() == Some(me.as_str()) {
            return Err(Error::Invalid("Это ваше собственное имя пользователя".into()));
        }
        let c = self.apply_user(&u, Some((&first, p["last_name"].as_str().unwrap_or(""))))?;
        self.contact_touch(s(&c, "user_id")?);
        self.emit(json!({"type": "contacts_changed"}));
        Ok(c)
    }

    pub fn contact_json(&self, user_id: &str) -> Result<Value> {
        let db = self.db();
        db.conn
            .query_row(
                "SELECT user_id, username, first_name, last_name, verified, key_changed, is_contact, added_at, profile_name, number, bio, avatar FROM contacts WHERE user_id=?1",
                [user_id],
                |r| {
                    Ok(json!({
                        "user_id": r.get::<_, String>(0)?,
                        "username": r.get::<_, String>(1)?,
                        "first_name": r.get::<_, String>(2)?,
                        "last_name": r.get::<_, String>(3)?,
                        "display_name": display_name(&alias_or_profile(&r.get::<_, String>(2)?, &r.get::<_, String>(3)?, &r.get::<_, String>(8)?), &r.get::<_, String>(3)?, &r.get::<_, String>(1)?),
                        "profile_name": r.get::<_, String>(8)?,
                        "number": r.get::<_, String>(9)?,
                        "bio": r.get::<_, String>(10)?,
                        "avatar_path": self.avatar_path(&r.get::<_, String>(11)?),
                        "verified": r.get::<_, i64>(4)? == 1,
                        "key_changed": r.get::<_, i64>(5)? == 1,
                        "is_contact": r.get::<_, i64>(6)? == 1,
                        "added_at": r.get::<_, i64>(7)?,
                    }))
                },
            )
            .optional()?
            .ok_or_else(|| Error::Invalid("Контакт не найден".into()))
    }

    fn list_contacts(&self, p: &Value) -> Result<Value> {
        let q = p["query"].as_str().unwrap_or("").trim().to_lowercase();
        let order = match p["sort"].as_str().unwrap_or("name-asc") {
            "name-desc" => "lower(first_name || ' ' || last_name || profile_name || username) DESC",
            "recent" => "added_at DESC",
            _ => "lower(first_name || ' ' || last_name || profile_name || username) ASC",
        };
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare(&format!("SELECT user_id, first_name, last_name || ' ' || profile_name, username FROM contacts WHERE is_contact=1 ORDER BY {order}"))?;
            let rows = st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?)))?;
            let mut v = Vec::new();
            for row in rows {
                let (id, f, l, u) = row?;
                let hay = format!("{f} {l} {u}").to_lowercase();
                if q.is_empty() || hay.contains(&q) {
                    v.push(id);
                }
            }
            v
        };
        let list: Vec<Value> = ids.iter().map(|id| self.contact_json(id)).collect::<Result<_>>()?;
        Ok(Value::Array(list))
    }

    fn update_contact(&self, p: &Value) -> Result<Value> {
        let id = s(p, "user_id")?;
        // An empty name returns to the name from the contact's profile.
        let first = p["first_name"].as_str().unwrap_or("").trim();
        self.db().conn.execute(
            "UPDATE contacts SET first_name=?2, last_name=?3, is_contact=1 WHERE user_id=?1",
            params![id, first, p["last_name"].as_str().unwrap_or("").trim()],
        )?;
        self.contact_touch(id);
        self.emit(json!({"type": "contacts_changed"}));
        self.emit(json!({"type": "conversations_changed"}));
        self.contact_json(id)
    }

    fn delete_contact(&self, p: &Value) -> Result<Value> {
        self.db().conn.execute("UPDATE contacts SET is_contact=0 WHERE user_id=?1", [s(p, "user_id")?])?;
        self.contact_touch(s(p, "user_id")?);
        self.emit(json!({"type": "contacts_changed"}));
        Ok(Value::Null)
    }

    pub(crate) fn contact_info(&self, p: &Value) -> Result<Value> {
        let id = s(p, "user_id")?;
        let mut c = self.contact_json(id)?;
        let db = self.db();
        let their_key: Vec<u8> = db.conn.query_row("SELECT account_key FROM contacts WHERE user_id=?1", [id], |r| r.get(0))?;
        let my_key = kv_get(&db.conn, "account_key")?.ok_or(Error::NotLoggedIn)?;
        let me = keys::my_user_id(&db.conn)?;
        c["safety_number"] = keys::safety_number(&me, &my_key, id, &their_key)?.into();
        Ok(c)
    }

    fn verify_contact(&self, p: &Value) -> Result<Value> {
        self.db().conn.execute("UPDATE contacts SET verified=1 WHERE user_id=?1 AND key_changed=0", [s(p, "user_id")?])?;
        self.contact_touch(s(p, "user_id")?);
        self.emit(json!({"type": "contacts_changed"}));
        self.contact_json(s(p, "user_id")?)
    }

    /// The user accepted a changed identity: pin the new key, drop old sessions.
    fn accept_key_change(&self, p: &Value) -> Result<Value> {
        let id = s(p, "user_id")?;
        let c = self.accept_key_change_synced(id)?;
        self.contact_touch(id);
        Ok(c)
    }

    /// Accepting without publishing it: also used when another own device accepted.
    pub fn accept_key_change_synced(&self, id: &str) -> Result<Value> {
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            tx.execute(
                "UPDATE contacts SET account_key=COALESCE(pending_account_key, account_key), pending_account_key=NULL, key_changed=0, verified=0 WHERE user_id=?1",
                [id],
            )?;
            tx.execute("UPDATE outbox SET next_at=0, request=NULL WHERE next_at=?1", [i64::MAX])?;
            tx.commit()?;
        }
        self.refresh_user(id)?;
        // Sessions with devices that no verified roster lists any more are dropped.
        self.db().conn.execute("DELETE FROM sessions WHERE address NOT IN (SELECT device_id FROM known_devices)", [])?;
        self.emit(json!({"type": "contacts_changed"}));
        self.emit(json!({"type": "conversations_changed"}));
        self.wake();
        self.contact_json(id)
    }
}

/// The name we gave a contact wins; otherwise the name from their profile.
pub fn alias_or_profile(first: &str, last: &str, profile: &str) -> String {
    if first.trim().is_empty() && last.trim().is_empty() { profile.to_string() } else { first.to_string() }
}

/// SQL form of `alias_or_profile` for a contacts row aliased as `c`.
pub const NAME_SQL: &str = "CASE WHEN c.first_name='' AND c.last_name='' THEN c.profile_name ELSE c.first_name END";

pub fn display_name(first: &str, last: &str, username: &str) -> String {
    let full = format!("{} {}", first.trim(), last.trim()).trim().to_string();
    if full.is_empty() { format!("@{username}") } else { full }
}

/// QR modules as rows of '1' (dark) and '0' so every client draws the same code.
/// Local history belongs to one account: signing in to another account on this
/// installation must not show the previous account's chats.
fn set_history_owner(tx: &rusqlite::Connection, user_id: &str) -> Result<()> {
    if kv_get_str(tx, "history_owner")?.as_deref() != Some(user_id) {
        tx.execute_batch("DELETE FROM receipts; DELETE FROM messages; DELETE FROM conversations; DELETE FROM contacts;")?;
        kv_set(tx, "history_owner", user_id.as_bytes())?;
    }
    Ok(())
}

fn qr_rows(text: &str) -> Result<Vec<String>> {
    qr_rows_with(text, qrcode::EcLevel::M)
}

pub fn qr_rows_with(text: &str, level: qrcode::EcLevel) -> Result<Vec<String>> {
    let code = qrcode::QrCode::with_error_correction_level(text, level).map_err(|_| Error::Invalid("Не удалось построить QR-код".into()))?;
    let w = code.width();
    let colors = code.to_colors();
    Ok(colors.chunks(w).map(|row| row.iter().map(|c| if *c == qrcode::Color::Dark { '1' } else { '0' }).collect()).collect())
}

#[cfg(test)]
mod notification_tests {
    use super::*;

    #[test]
    fn archive_silences_notifications_until_restored() {
        let dir = std::env::temp_dir().join(format!("neochat-notify-{}", uuid::Uuid::new_v4()));
        let events = Arc::new(Mutex::new(Vec::<Value>::new()));
        let received = events.clone();
        let core = Core::open(
            &json!({"data_dir": dir, "db_key": "ab".repeat(32), "foreground": false}),
            Box::new(move |event| received.lock().unwrap().push(serde_json::from_str(event).unwrap())),
        ).unwrap();
        for kind in ["direct", "group", "channel"] {
            core.inner.db().conn.execute("INSERT INTO conversations(id, kind) VALUES (?1, ?1)", [kind]).unwrap();
            for archived in [false, true, false] {
                core.inner.db().conn.execute("UPDATE conversations SET in_archive=?2 WHERE id=?1", params![kind, archived]).unwrap();
                events.lock().unwrap().clear();
                for text in ["Новое сообщение", "Вас упомянули", "Пропущенный звонок"] {
                    core.inner.emit(json!({"type": "notify", "conversation_id": kind, "title": "Тест", "text": text}));
                }
                core.inner.emit(json!({"type": "messages_changed", "conversation_id": kind}));
                let captured = events.lock().unwrap();
                assert_eq!(captured.iter().filter(|v| v["type"] == "notify").count(), if archived { 0 } else { 3 }, "{kind}, archived={archived}");
                assert!(captured.iter().any(|v| v["type"] == "messages_changed"));
            }
        }
        drop(core);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn lookup_user_opens_people_not_broadcasts() {
        let dir = std::env::temp_dir().join(format!("neochat-lookup-{}", uuid::Uuid::new_v4()));
        let core = Core::open(
            &json!({"data_dir": dir, "db_key": "ab".repeat(32), "foreground": false}),
            Box::new(|_| {}),
        ).unwrap();
        core.inner.db().conn.execute(
            "INSERT INTO contacts(user_id, username, account_key, added_at) VALUES ('user-alice', 'Alice', x'00', 1)",
            [],
        ).unwrap();
        let found = core.inner.lookup_user(&json!({"username": "@Alice"})).unwrap();
        assert_eq!(found["user_id"], "user-alice");
        assert!(core.inner.lookup_user(&json!({"username": "everyone"})).is_err());
        assert!(core.inner.lookup_user(&json!({"username": "here"})).is_err());
        assert!(core.inner.lookup_user(&json!({"username": "nobody"})).is_err());
        drop(core);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
