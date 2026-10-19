//! The client engine: one serialized database, a background worker that syncs
//! and flushes the outbox, and an optional WebSocket that only wakes the worker.
//!
//! Locking rule: the database mutex is held only for local work. Network calls
//! happen outside it; results are applied in short transactions afterwards.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::Duration;

use libsignal_protocol::{IdentityKey, IdentityKeyPair};
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::api::Api;
use crate::error::{Error, Result};
use crate::keys::{self, Roster, b64, unb64};
use crate::store::{Db, kv_del, kv_get, kv_get_str, kv_set, now_ms};

pub type EventCallback = Box<dyn Fn(&str) + Send + Sync>;

pub const STATE_OFFLINE: u8 = 0;
pub const STATE_CONNECTING: u8 = 1;
pub const STATE_ONLINE: u8 = 2;

pub struct Inner {
    pub db: Mutex<Db>,
    pub data_dir: PathBuf,
    cb: EventCallback,
    wake: (Mutex<bool>, Condvar),
    pub foreground: AtomicBool,
    pub shutdown: AtomicBool,
    pub conn_state: AtomicU8,
    refresh_lock: Mutex<()>,
    pub ws_active: AtomicBool,
    /// Serializes sync cycles between the worker and `sync_now`.
    pub cycle_lock: Mutex<()>,
}

pub struct Core {
    pub inner: Arc<Inner>,
    worker: Option<JoinHandle<()>>,
}

impl Core {
    pub fn open(config: &Value, cb: EventCallback) -> Result<Core> {
        let data_dir = PathBuf::from(config["data_dir"].as_str().ok_or_else(|| Error::Invalid("data_dir required".into()))?);
        let key = hex::decode(config["db_key"].as_str().unwrap_or("")).map_err(|_| Error::Invalid("db_key must be hex".into()))?;
        for d in ["files", "outgoing", "tmp"] {
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
            shutdown: AtomicBool::new(false),
            conn_state: AtomicU8::new(STATE_OFFLINE),
            refresh_lock: Mutex::new(()),
            ws_active: AtomicBool::new(false),
            cycle_lock: Mutex::new(()),
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
        let access = v["access_token"].as_str().ok_or_else(|| Error::Invalid("no token".into()))?;
        let refresh = v["refresh_token"].as_str().ok_or_else(|| Error::Invalid("no token".into()))?;
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
            "link_start" => self.link_start(p),
            "link_poll" => self.link_poll(),
            "link_approve" => self.link_approve(p),
            "devices" => self.authed("GET", "/v1/devices", None),
            "revoke_device" => self.revoke_device(p),
            "set_foreground" => {
                let fg = p["active"].as_bool().unwrap_or(true);
                self.foreground.store(fg, Ordering::SeqCst);
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
            "verify_contact" => self.verify_contact(p),
            "accept_key_change" => self.accept_key_change(p),
            "open_direct" => self.open_direct(p),
            "create_group" => self.create_group(p),
            "group_set_members" => self.group_set_members(p),
            "group_leave" => self.group_leave(p),
            "group_rename" => self.group_rename(p),
            "list_conversations" => self.list_conversations(p),
            "get_conversation" => self.get_conversation(p),
            "list_messages" => self.list_messages(p),
            "search_messages" => self.search_messages(p),
            "send_text" => self.send_text(p),
            "send_file" => self.send_file(p),
            "retry_message" => self.retry_message(p),
            "delete_local_message" => self.delete_local_message(p),
            "download_file" => self.download_file(p),
            "export_file" => self.export_file(p),
            "set_draft" => self.set_draft(p),
            "mark_read" => self.mark_read(p),
            "get_settings" => self.get_settings(),
            "set_settings" => self.set_settings(p),
            "safety_number" => self.contact_info(p),
            _ => Err(Error::Invalid(format!("Неизвестный метод {method}"))),
        }
    }

    fn status(&self) -> Result<Value> {
        let db = self.db();
        let c = &db.conn;
        let st = match self.conn_state.load(Ordering::SeqCst) {
            STATE_ONLINE => "online",
            STATE_CONNECTING => "connecting",
            _ => "offline",
        };
        Ok(json!({
            "logged_in": kv_get(c, "refresh_token")?.is_some() && kv_get(c, "user_id")?.is_some(),
            "has_device_keys": kv_get(c, "device_identity")?.is_some() && kv_get(c, "user_id")?.is_some(),
            "pending_link": kv_get(c, "link_id")?.is_some(),
            "user_id": kv_get_str(c, "user_id")?,
            "username": kv_get_str(c, "username")?,
            "device_id": kv_get_str(c, "device_id")?,
            "primary": kv_get_str(c, "primary")?.as_deref() == Some("1"),
            "server": kv_get_str(c, "server")?,
            "connection": st,
        }))
    }

    fn wipe_account(&self, conn: &rusqlite::Connection) -> Result<()> {
        // Used only before creating a fresh identity on this installation.
        conn.execute_batch(
            "DELETE FROM kv; DELETE FROM sessions; DELETE FROM identities; DELETE FROM prekeys; DELETE FROM signed_prekeys;
             DELETE FROM kyber_prekeys; DELETE FROM kyber_used; DELETE FROM known_devices; DELETE FROM outbox; DELETE FROM processed;",
        )?;
        Ok(())
    }

    fn register(&self, p: &Value) -> Result<Value> {
        let server = s(p, "server")?.trim().to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let password = s(p, "password")?;
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
            "invite": s(p, "invite")?.trim(),
            "username": username,
            "password": password,
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
        self.store_own_roster(&tx, "1", &blob, &sig, &account.identity_key().serialize())?;
        tx.commit()?;
        drop(db);
        self.after_login();
        Ok(json!({"recovery_code": keys::recovery_encode(&secret), "user_id": out["user_id"]}))
    }

    fn store_account(&self, tx: &rusqlite::Connection, server: &str, out: &Value, username: &str, primary: bool) -> Result<()> {
        kv_set(tx, "server", server.trim_end_matches('/').as_bytes())?;
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
        self.set_conn(STATE_ONLINE);
        self.emit(json!({"type": "account_changed"}));
        self.wake();
    }

    fn login(&self, p: &Value) -> Result<Value> {
        let server = s(p, "server")?.trim().to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let (device_id, stored_user) = {
            let db = self.db();
            (kv_get_str(&db.conn, "device_id")?, kv_get_str(&db.conn, "username")?)
        };
        let device_id = match (device_id, stored_user) {
            (Some(d), Some(u)) if u == username => d,
            _ => {
                return Err(Error::Invalid(
                    "Это устройство ещё не привязано к аккаунту. Привяжите его с основного устройства или восстановите аккаунт".into(),
                ));
            }
        };
        let ts = (now_ms() / 1000).to_string();
        let sig = keys::login_signature(&self.db().conn, &username, &device_id, &ts)?;
        let out = Api::new(&server).call(
            "POST",
            "/v1/sessions",
            None,
            Some(&json!({"username": username, "password": s(p, "password")?, "device_id": device_id, "timestamp": ts, "signature": b64(&sig)})),
        )?;
        {
            let db = self.db();
            kv_set(&db.conn, "server", server.trim_end_matches('/').as_bytes())?;
            self.store_tokens(&db.conn, &out)?;
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
        let server = s(p, "server")?.trim().to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let old_secret = keys::recovery_decode(s(p, "recovery_code")?)?;
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
            "new_password": s(p, "new_password")?,
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
        self.store_own_roster(&tx, &rev.to_string(), &blob, &sig, &account.identity_key().serialize())?;
        tx.commit()?;
        drop(db);
        self.after_login();
        Ok(json!({"recovery_code": keys::recovery_encode(&secret)}))
    }

    // ---- device linking ----

    /// Starts linking this installation as a secondary device. Returns QR text that
    /// the primary device scans; it carries the new device's public keys.
    fn link_start(&self, p: &Value) -> Result<Value> {
        let server = s(p, "server")?.trim().to_string();
        let username = s(p, "username")?.trim().trim_start_matches('@').to_lowercase();
        let device = keys::new_device()?;
        let body = json!({
            "username": username,
            "password": s(p, "password")?,
            "device": device.upload_json(p["device_name"].as_str().unwrap_or("NeoChat"), s(p, "platform")?)?,
        });
        let out = Api::new(&server).call("POST", "/v1/devices/link", None, Some(&body))?;
        let link_id = out["link_id"].as_str().unwrap_or_default().to_string();
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        self.wipe_account(&tx)?;
        device.persist(&tx)?;
        kv_set(&tx, "server", server.trim_end_matches('/').as_bytes())?;
        kv_set(&tx, "username", username.as_bytes())?;
        kv_set(&tx, "pending_user_id", out["user_id"].as_str().unwrap_or_default().as_bytes())?;
        kv_set(&tx, "link_id", link_id.as_bytes())?;
        kv_set(&tx, "link_secret", out["link_secret"].as_str().unwrap_or_default().as_bytes())?;
        tx.commit()?;
        let e = device.roster_entry();
        Ok(json!({
            "qr": format!("neochat-link:1:{}:{}:{}", link_id, b64(&e.identity_key), b64(&e.auth_key)),
            "expires_in": out["expires_in"],
        }))
    }

    fn link_poll(&self) -> Result<Value> {
        let (server, link_id, secret, user_id) = {
            let db = self.db();
            (
                kv_get_str(&db.conn, "server")?.ok_or(Error::NotLoggedIn)?,
                kv_get_str(&db.conn, "link_id")?.ok_or_else(|| Error::Invalid("Привязка не начата".into()))?,
                kv_get_str(&db.conn, "link_secret")?.unwrap_or_default(),
                kv_get_str(&db.conn, "pending_user_id")?.unwrap_or_default(),
            )
        };
        let api = Api::new(&server);
        let out = api.get_with_header(&format!("/v1/devices/link/{link_id}"), ("X-Link-Secret", &secret))?;
        let status = out["status"].as_str().unwrap_or("pending").to_string();
        if status != "approved" {
            return Ok(json!({"status": status}));
        }
        let session = &out["session"];
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            kv_set(&tx, "user_id", user_id.as_bytes())?;
            kv_set(&tx, "primary", b"0")?;
            self.store_tokens(&tx, session)?;
            kv_del(&tx, "link_id")?;
            kv_del(&tx, "link_secret")?;
            kv_del(&tx, "pending_user_id")?;
            tx.commit()?;
        }
        // Pin the account identity from the roster that lists this device's keys.
        let me = self.authed("GET", &format!("/v1/users/{user_id}"), None)?;
        let account_key = unb64(me["account_identity_key"].as_str().unwrap_or_default())?;
        let blob = unb64(me["roster"]["blob"].as_str().unwrap_or_default())?;
        let sig = unb64(me["roster"]["signature"].as_str().unwrap_or_default())?;
        let roster = keys::verify_roster(&account_key, &blob, &sig)?;
        let db = self.db();
        let my_device = kv_get_str(&db.conn, "device_id")?.unwrap_or_default();
        let my_identity = keys::local_identity(&db.conn)?;
        if !roster.devices.iter().any(|d| d.device_id == my_device && d.identity_key[..] == my_identity.identity_key().serialize()[..]) {
            return Err(Error::Crypto("Список устройств не содержит это устройство".into()));
        }
        self.store_own_roster(&db.conn, me["roster"]["revision"].as_str().unwrap_or("1"), &blob, &sig, &account_key)?;
        drop(db);
        self.after_login();
        Ok(json!({"status": "approved"}))
    }

    /// Primary device: approve the device shown in the scanned QR code. The keys
    /// from the QR must match what the server holds for the pending link.
    fn link_approve(&self, p: &Value) -> Result<Value> {
        let qr = s(p, "qr")?.trim();
        let parts: Vec<&str> = qr.split(':').collect();
        if parts.len() != 5 || parts[0] != "neochat-link" || parts[1] != "1" {
            return Err(Error::Invalid("Это не QR-код привязки NeoChat".into()));
        }
        let (link_id, qr_identity, qr_auth) = (parts[2], unb64(parts[3])?, unb64(parts[4])?);
        let account = keys::account_keypair(&self.db().conn)?.ok_or_else(|| Error::Invalid("Подтверждает только основное устройство".into()))?;
        let pending = self.authed("GET", &format!("/v1/devices/links/{link_id}"), None)?;
        let identity = unb64(pending["identity_key"].as_str().unwrap_or_default())?;
        let auth = unb64(pending["auth_key"].as_str().unwrap_or_default())?;
        if identity != qr_identity || auth != qr_auth {
            return Err(Error::Crypto("Ключи устройства не совпадают с QR-кодом. Привязка отменена".into()));
        }
        let new_dev = keys::RosterDevice { device_id: pending["device_id"].as_str().unwrap_or_default().to_string(), identity_key: identity, auth_key: auth };
        let (blob, sig, rev) = self.next_own_roster(&account, |devs| {
            if devs.len() >= 3 {
                return Err(Error::Invalid("У аккаунта уже 3 устройства".into()));
            }
            devs.push(new_dev.clone());
            Ok(())
        })?;
        self.authed(
            "POST",
            &format!("/v1/devices/links/{link_id}/approve"),
            Some(&json!({"roster": {"revision": rev.to_string(), "blob": b64(&blob), "signature": b64(&sig)}})),
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
        }))
    }

    fn set_settings(&self, p: &Value) -> Result<Value> {
        let db = self.db();
        if let Some(v) = p["read_receipts"].as_bool() {
            kv_set(&db.conn, "read_receipts", if v { b"1" } else { b"0" })?;
        }
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
        if key_changed {
            self.emit(json!({"type": "identity_changed", "user_id": user_id}));
        }
        self.contact_json(&user_id)
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
        let first = p["first_name"].as_str().unwrap_or("").trim().to_string();
        let first = if first.is_empty() { username.clone() } else { first };
        let u = self.authed("POST", "/v1/contacts/resolve", Some(&json!({"username": username})))?;
        let (me, _) = self.me()?;
        if u["user_id"].as_str() == Some(me.as_str()) {
            return Err(Error::Invalid("Это ваш собственный username".into()));
        }
        let c = self.apply_user(&u, Some((&first, p["last_name"].as_str().unwrap_or(""))))?;
        self.emit(json!({"type": "contacts_changed"}));
        Ok(c)
    }

    pub fn contact_json(&self, user_id: &str) -> Result<Value> {
        let db = self.db();
        db.conn
            .query_row(
                "SELECT user_id, username, first_name, last_name, verified, key_changed, is_contact, added_at FROM contacts WHERE user_id=?1",
                [user_id],
                |r| {
                    Ok(json!({
                        "user_id": r.get::<_, String>(0)?,
                        "username": r.get::<_, String>(1)?,
                        "first_name": r.get::<_, String>(2)?,
                        "last_name": r.get::<_, String>(3)?,
                        "display_name": display_name(&r.get::<_, String>(2)?, &r.get::<_, String>(3)?, &r.get::<_, String>(1)?),
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
            "name-desc" => "lower(first_name || ' ' || last_name || username) DESC",
            "recent" => "added_at DESC",
            _ => "lower(first_name || ' ' || last_name || username) ASC",
        };
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare(&format!("SELECT user_id, first_name, last_name, username FROM contacts WHERE is_contact=1 ORDER BY {order}"))?;
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
        let first = p["first_name"].as_str().unwrap_or("").trim();
        if first.is_empty() {
            return Err(Error::Invalid("Имя обязательно".into()));
        }
        self.db().conn.execute(
            "UPDATE contacts SET first_name=?2, last_name=?3, is_contact=1 WHERE user_id=?1",
            params![id, first, p["last_name"].as_str().unwrap_or("").trim()],
        )?;
        self.emit(json!({"type": "contacts_changed"}));
        self.emit(json!({"type": "conversations_changed"}));
        self.contact_json(id)
    }

    fn delete_contact(&self, p: &Value) -> Result<Value> {
        self.db().conn.execute("UPDATE contacts SET is_contact=0 WHERE user_id=?1", [s(p, "user_id")?])?;
        self.emit(json!({"type": "contacts_changed"}));
        Ok(Value::Null)
    }

    fn contact_info(&self, p: &Value) -> Result<Value> {
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
        self.emit(json!({"type": "contacts_changed"}));
        self.contact_json(s(p, "user_id")?)
    }

    /// The user accepted a changed identity: pin the new key, drop old sessions.
    fn accept_key_change(&self, p: &Value) -> Result<Value> {
        let id = s(p, "user_id")?;
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

pub fn display_name(first: &str, last: &str, username: &str) -> String {
    let full = format!("{} {}", first.trim(), last.trim()).trim().to_string();
    if full.is_empty() { format!("@{username}") } else { full }
}
