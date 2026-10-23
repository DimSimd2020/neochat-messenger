//! Installation-level account routing. Each slot owns its database, keys, outbox
//! and worker; the original database stays in place when upgrading.
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use serde_json::{Value, json};

use crate::engine::{Core as Account, EventCallback, s};
use crate::store::kv_get_str;
use crate::{Error, Result};

pub struct Core {
    accounts: Mutex<BTreeMap<String, Arc<Account>>>,
    active: Arc<Mutex<String>>,
    config: Value,
    callback: Arc<EventCallback>,
    selection: Mutex<()>,
}

impl Core {
    pub fn open(config: &Value, callback: EventCallback) -> Result<Self> {
        let active = Arc::new(Mutex::new("default".to_string()));
        let callback = Arc::new(callback);
        let first = Arc::new(Self::open_account(config, "default", &active, &callback)?);
        let (ids, selected) = {
            let db = first.inner.db();
            db.conn.execute_batch("CREATE TABLE IF NOT EXISTS local_accounts (id TEXT PRIMARY KEY, selected INTEGER NOT NULL DEFAULT 0)")?;
            let mut query = db.conn.prepare("SELECT id, selected FROM local_accounts")?;
            let rows = query.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, bool>(1)?)))?;
            let mut ids = Vec::new();
            let mut selected = "default".to_string();
            for row in rows {
                let (id, current) = row?;
                if current { selected = id.clone(); }
                ids.push(id);
            }
            (ids, selected)
        };
        let mut accounts = BTreeMap::from([("default".into(), first)]);
        for id in ids {
            // Slots are local UUIDs, never paths or remote user IDs.
            if uuid::Uuid::parse_str(&id).is_err() {
                return Err(Error::Invalid("Некорректный список аккаунтов".into()));
            }
            let account = Self::open_account(config, &id, &active, &callback)?;
            accounts.insert(id, Arc::new(account));
        }
        if !accounts.contains_key(&selected) {
            return Err(Error::Invalid("Не найден сохранённый аккаунт".into()));
        }
        *active.lock().unwrap() = selected;
        let core = Self { accounts: Mutex::new(accounts), active, config: config.clone(), callback, selection: Mutex::new(()) };
        core.selected_event();
        Ok(core)
    }

    fn open_account(config: &Value, id: &str, active: &Arc<Mutex<String>>, callback: &Arc<EventCallback>) -> Result<Account> {
        let mut cfg = config.clone();
        if id != "default" {
            let dir = std::path::Path::new(s(config, "data_dir")?).join("accounts").join(id);
            cfg["data_dir"] = json!(dir);
        }
        let id = id.to_string();
        let active = active.clone();
        let cb = callback.clone();
        Account::open(&cfg, Box::new(move |event| {
            let Ok(mut ev) = serde_json::from_str::<Value>(event) else { return };
            ev["account_id"] = json!(id);
            if *active.lock().unwrap() != id {
                if ev["type"] == "call" && ev["state"] == "incoming" {
                    ev["title"] = ev["peer_name"].clone();
                    ev["text"] = json!("Входящий звонок · переключитесь на аккаунт");
                } else if ev["type"] != "notify" { return; }
                ev["type"] = json!("account_notify");
            }
            cb(&ev.to_string());
        }))
    }

    fn account(&self, id: &str) -> Result<Arc<Account>> {
        self.accounts.lock().unwrap().get(id).cloned()
            .ok_or_else(|| Error::Invalid("Аккаунт не найден на этом устройстве".into()))
    }

    fn selected_event(&self) {
        let id = self.active.lock().unwrap().clone();
        (self.callback)(&json!({"type": "account_selected", "account_id": id}).to_string());
    }

    fn select(&self, id: &str) -> Result<Value> {
        let account = self.account(id)?;
        if self.accounts.lock().unwrap().iter().any(|(other, a)| other != id && a.inner.cur_call.lock().unwrap().is_some()) {
            return Err(Error::Invalid("Завершите звонок перед сменой аккаунта".into()));
        }
        {
            let root = self.account("default")?;
            let mut db = root.inner.db();
            let tx = db.conn.transaction()?;
            tx.execute("UPDATE local_accounts SET selected=0", [])?;
            if id != "default" { tx.execute("UPDATE local_accounts SET selected=1 WHERE id=?1", [id])?; }
            tx.commit()?;
        }
        *self.active.lock().unwrap() = id.into();
        account.inner.wake();
        self.selected_event();
        let mut call = account.call("call_current", &Value::Null)?;
        if call["state"] == "incoming" {
            call["type"] = json!("call");
            call["account_id"] = json!(id);
            (self.callback)(&call.to_string());
        }
        account.call("status", &Value::Null)
    }

    pub fn call(&self, method: &str, params: &Value) -> Result<Value> {
        let routed = params["_account_id"].as_str().map(str::to_string);
        let mut input = params.clone();
        if let Some(object) = input.as_object_mut() { object.remove("_account_id"); }
        let params = &input;
        match method {
            "list_accounts" => {
                let active = self.active.lock().unwrap().clone();
                let accounts = self.accounts.lock().unwrap().clone();
                let mut rows = Vec::new();
                for (id, account) in accounts {
                    let mut status = account.call("status", &Value::Null)?;
                    if status["user_id"].is_null() && id != active { continue; }
                    status["account_id"] = json!(id);
                    status["current"] = json!(id == active);
                    rows.push(status);
                }
                Ok(json!(rows))
            }
            "switch_account" => {
                let _selection = self.selection.lock().unwrap();
                self.select(s(params, "account_id")?)
            }
            "add_account" => {
                let _selection = self.selection.lock().unwrap();
                let accounts = self.accounts.lock().unwrap().clone();
                if let Some((id, _)) = accounts.iter().find(|(_, a)| {
                    let db = a.inner.db();
                    kv_get_str(&db.conn, "user_id").ok().flatten().is_none()
                }) {
                    return self.select(id);
                }
                let id = uuid::Uuid::new_v4().to_string();
                let mut cfg = self.config.clone();
                let current = self.account(&self.active.lock().unwrap().clone())?;
                cfg["foreground"] = json!(current.inner.foreground.load(std::sync::atomic::Ordering::SeqCst));
                let account = Arc::new(Self::open_account(&cfg, &id, &self.active, &self.callback)?);
                // Persist the registry before making the new slot visible.
                self.account("default")?.inner.db().conn.execute("INSERT INTO local_accounts(id) VALUES (?1)", [&id])?;
                self.accounts.lock().unwrap().insert(id.clone(), account);
                self.select(&id)
            }
            "set_foreground" | "set_background_connection" | "sync_now" | "set_push_token" => {
                let accounts = self.accounts.lock().unwrap().clone();
                let mut result = Ok(Value::Null);
                for account in accounts.values() {
                    if (method == "sync_now" || method == "set_push_token") && !account.inner.logged_in() { continue; }
                    if let Err(e) = account.call(method, params) { result = Err(e); }
                }
                result
            }
            _ => {
                // Native wrappers capture this ID when scheduling work, so an old
                // queued send cannot use the newly selected account's identity.
                let id = routed.unwrap_or_else(|| self.active.lock().unwrap().clone());
                self.account(&id)?.call(method, params)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::kv_set;

    #[test]
    fn switching_isolates_history_and_queued_calls_and_survives_restart() {
        let dir = std::env::temp_dir().join(format!("neochat-accounts-{}", uuid::Uuid::new_v4()));
        let config = json!({"data_dir": dir, "db_key": "ab".repeat(32), "foreground": false});
        let events = Arc::new(Mutex::new(Vec::<Value>::new()));
        let received = events.clone();
        let core = Core::open(&config, Box::new(move |s| received.lock().unwrap().push(serde_json::from_str(s).unwrap()))).unwrap();
        let first = core.account("default").unwrap();
        kv_set(&first.inner.db().conn, "user_id", b"alice").unwrap();
        kv_set(&first.inner.db().conn, "device_id", b"alice-device").unwrap();
        kv_set(&first.inner.db().conn, "refresh_token", b"fixture-alice-refresh").unwrap();
        first.inner.db().conn.execute("INSERT INTO conversations(id,kind,draft) VALUES ('same','direct','Alice draft')", []).unwrap();
        core.call("add_account", &Value::Null).unwrap();
        let second_id = core.active.lock().unwrap().clone();
        assert_ne!(second_id, "default");
        let second = core.account(&second_id).unwrap();
        kv_set(&second.inner.db().conn, "user_id", b"bob").unwrap();
        kv_set(&second.inner.db().conn, "device_id", b"bob-device").unwrap();
        kv_set(&second.inner.db().conn, "refresh_token", b"fixture-bob-refresh").unwrap();
        second.inner.db().conn.execute("INSERT INTO conversations(id,kind,draft) VALUES ('same','direct','Bob draft')", []).unwrap();
        core.call("set_draft", &json!({"_account_id": "default", "conversation_id": "same", "text": "Queued for Alice"})).unwrap();
        assert_eq!(core.call("get_conversation", &json!({"conversation_id": "same"})).unwrap()["draft"], "Bob draft");
        assert!(core.call("switch_account", &json!({"account_id": "../outside"})).is_err());
        assert_eq!(*core.active.lock().unwrap(), second_id);
        events.lock().unwrap().clear();
        first.inner.emit(json!({"type": "auth_required"}));
        first.inner.emit(json!({"type": "notify", "conversation_id": "same"}));
        let captured = events.lock().unwrap().clone();
        assert_eq!(captured.len(), 1);
        assert_eq!(captured[0]["type"], "account_notify");
        assert_eq!(captured[0]["account_id"], "default");
        core.call("logout", &Value::Null).unwrap();
        assert!(!core.call("status", &Value::Null).unwrap()["logged_in"].as_bool().unwrap());
        assert!(first.inner.logged_in());
        drop(first);
        drop(second);
        drop(core);
        let core = Core::open(&config, Box::new(|_| {})).unwrap();
        assert_eq!(core.call("status", &Value::Null).unwrap()["user_id"], "bob");
        core.call("switch_account", &json!({"account_id": "default"})).unwrap();
        assert_eq!(core.call("get_conversation", &json!({"conversation_id": "same"})).unwrap()["draft"], "Queued for Alice");
        assert_eq!(core.call("list_accounts", &Value::Null).unwrap().as_array().unwrap().len(), 2);
        drop(core);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
