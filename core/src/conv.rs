//! Conversations: verification of server-provided membership against signed
//! group state, direct chats, group management.

use std::collections::BTreeSet;
use std::sync::Arc;

use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};
use uuid::Uuid;

use crate::engine::{Inner, display_name, s};
use crate::error::{Error, Result};
use crate::keys::{self, GroupState, b64, unb64};
use crate::store::{kv_get, now_ms};

pub const MAX_GROUP: usize = 16;

impl Inner {
    fn account_key_of(&self, user_id: &str) -> Result<Vec<u8>> {
        let db = self.db();
        let me = keys::my_user_id(&db.conn)?;
        if user_id == me {
            return kv_get(&db.conn, "account_key")?.ok_or(Error::NotLoggedIn);
        }
        db.conn
            .query_row("SELECT account_key FROM contacts WHERE user_id=?1", [user_id], |r| r.get(0))
            .optional()?
            .ok_or_else(|| Error::Crypto("Неизвестный участник".into()))
    }

    fn device_identity(&self, user_id: &str, device_id: &str) -> Result<Option<Vec<u8>>> {
        Ok(self
            .db()
            .conn
            .query_row("SELECT identity_key FROM known_devices WHERE user_id=?1 AND device_id=?2", [user_id, device_id], |r| r.get(0))
            .optional()?)
    }

    /// Checks the server's view of a conversation and stores it. For groups the
    /// member list must equal the owner-signed state minus verified leave statements.
    pub fn apply_conversation(&self, c: &Value) -> Result<()> {
        let id = s(c, "id")?.to_string();
        let kind = s(c, "kind")?.to_string();
        let (me, my_device) = self.me()?;
        let server_members: BTreeSet<String> = c["members"].as_array().into_iter().flatten().filter_map(|m| m["user_id"].as_str().map(str::to_string)).collect();
        for m in &server_members {
            self.ensure_user(m)?;
        }
        let mut archived = c["archived"].as_bool().unwrap_or(false);
        let mut peer: Option<String> = None;
        let owner = c["owner_id"].as_str().map(str::to_string);
        if kind == "direct" {
            if server_members.len() != 2 || !server_members.contains(&me) {
                return Err(Error::Crypto("Некорректный состав личного диалога".into()));
            }
            peer = server_members.iter().find(|m| **m != me).cloned();
        } else {
            let owner = owner.clone().ok_or_else(|| Error::Crypto("У группы нет владельца".into()))?;
            self.ensure_user(&owner)?;
            let blob = unb64(c["state"]["blob"].as_str().unwrap_or_default())?;
            let sig = unb64(c["state"]["signature"].as_str().unwrap_or_default())?;
            keys::verify_blob(&self.account_key_of(&owner)?, &blob, &sig)?;
            let st = GroupState::decode(&blob)?;
            if st.group_id != id || st.owner_id != owner {
                return Err(Error::Crypto("Состояние группы относится к другой группе".into()));
            }
            let mut members: BTreeSet<String> = st.members.iter().cloned().collect();
            let leaves = c["leaves"].as_array().cloned().unwrap_or_default();
            for (i, l) in leaves.iter().enumerate() {
                let lb = unb64(l["blob"].as_str().unwrap_or_default())?;
                let ls = unb64(l["signature"].as_str().unwrap_or_default())?;
                let (g, u, d, prev) = keys::parse_leave(&lb)?;
                if g != id || Some(u.as_str()) != l["user_id"].as_str() || prev != st.revision + i as u64 {
                    return Err(Error::Crypto("Некорректное заявление о выходе".into()));
                }
                self.ensure_user(&u)?;
                let mut ik = self.device_identity(&u, &d)?;
                if ik.is_none() && u != me {
                    self.refresh_user(&u)?;
                    ik = self.device_identity(&u, &d)?;
                }
                let ik = ik.ok_or_else(|| Error::Crypto("Выход подписан неизвестным устройством".into()))?;
                keys::verify_blob(&ik, &lb, &ls)?;
                if u == owner {
                    archived = true;
                } else {
                    members.remove(&u);
                }
            }
            let rev: u64 = c["revision"].as_str().unwrap_or("0").parse().unwrap_or(0);
            if rev != st.revision + leaves.len() as u64 || members != server_members {
                return Err(Error::Crypto("Состав группы не совпадает с подписанным владельцем".into()));
            }
        }
        // Every recipient device must be listed in a verified roster of a verified member.
        let recipients = c["recipients"].as_array().cloned().unwrap_or_default();
        for r in &recipients {
            let u = s(r, "user_id")?;
            let d = s(r, "device_id")?;
            if !server_members.contains(u) || d == my_device {
                return Err(Error::Crypto("Сервер добавил постороннего получателя".into()));
            }
            if self.device_identity(u, d)?.is_none() {
                if u != me {
                    self.refresh_user(u)?;
                }
                if self.device_identity(u, d)?.is_none() {
                    return Err(Error::Crypto("Устройство получателя отсутствует в подписанном списке".into()));
                }
            }
        }
        let members_json = serde_json::to_string(&server_members)?;
        let db = self.db();
        db.conn.execute(
            "INSERT INTO conversations(id, kind, peer_user_id, owner_id, revision, members, recipients, recipient_rev, archived, left_group, last_activity)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, 0, ?10)
             ON CONFLICT(id) DO UPDATE SET revision=excluded.revision, members=excluded.members, recipients=excluded.recipients,
               recipient_rev=excluded.recipient_rev, archived=excluded.archived, owner_id=excluded.owner_id, left_group=0",
            params![
                id,
                kind,
                peer,
                owner,
                c["revision"].as_str().unwrap_or("1"),
                members_json,
                serde_json::to_string(&recipients)?,
                c["recipient_set_revision"].as_str().unwrap_or(""),
                archived as i64,
                now_ms()
            ],
        )?;
        Ok(())
    }

    /// Refreshes one conversation; a 404 means we are no longer a member.
    pub fn fetch_conversation(&self, id: &str) -> Result<()> {
        match self.authed("GET", &format!("/v1/conversations/{id}"), None) {
            Ok(c) => self.apply_conversation(&c),
            Err(Error::Api { status: 404, .. }) => {
                self.db().conn.execute("UPDATE conversations SET left_group=1, recipients='[]' WHERE id=?1", [id])?;
                Ok(())
            }
            Err(e) => Err(e),
        }
    }

    pub fn conversation_known(&self, id: &str) -> Result<bool> {
        Ok(self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM conversations WHERE id=?1)", [id], |r| r.get(0))?)
    }

    pub fn open_direct(&self, p: &Value) -> Result<Value> {
        let user = s(p, "user_id")?;
        self.ensure_user(user)?;
        let c = self.authed("POST", "/v1/conversations/direct", Some(&json!({"user_id": user})))?;
        self.apply_conversation(&c)?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(s(&c, "id")?)
    }

    fn require_account(&self) -> Result<libsignal_protocol::IdentityKeyPair> {
        keys::account_keypair(&self.db().conn)?.ok_or_else(|| Error::Invalid("Группами управляет основное устройство".into()))
    }

    pub fn create_group(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let account = self.require_account()?;
        let title = s(p, "title")?.trim().to_string();
        if title.is_empty() || title.chars().count() > 64 {
            return Err(Error::Invalid("Название группы: от 1 до 64 символов".into()));
        }
        let (me, _) = self.me()?;
        let mut members: BTreeSet<String> = p["member_ids"].as_array().into_iter().flatten().filter_map(|v| v.as_str().map(str::to_string)).collect();
        members.insert(me.clone());
        if members.len() < 2 || members.len() > MAX_GROUP {
            return Err(Error::Invalid("В группе от 2 до 16 участников".into()));
        }
        let gid = Uuid::new_v4().to_string();
        let st = GroupState { group_id: gid.clone(), revision: 1, prev_hash: [0; 32], owner_id: me, members: members.iter().cloned().collect() };
        let blob = st.encode()?;
        let sig = keys::sign_blob(&account, &blob)?;
        let c = self.authed(
            "POST",
            "/v1/conversations/groups",
            Some(&json!({"group_id": gid, "members": members, "state": {"revision": "1", "blob": b64(&blob), "signature": b64(&sig)}})),
        )?;
        self.apply_conversation(&c)?;
        self.db().conn.execute("UPDATE conversations SET title=?2 WHERE id=?1", params![gid, title])?;
        self.enqueue_control(&gid, "group_update", json!({"title": title}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(&gid)
    }

    pub fn group_set_members(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let account = self.require_account()?;
        let gid = s(p, "conversation_id")?;
        let (me, _) = self.me()?;
        let cur = self.authed("GET", &format!("/v1/conversations/{gid}"), None)?;
        if cur["owner_id"].as_str() != Some(me.as_str()) {
            return Err(Error::Invalid("Состав меняет только владелец группы".into()));
        }
        let prev_blob = unb64(cur["state"]["blob"].as_str().unwrap_or_default())?;
        let prev: u64 = cur["revision"].as_str().unwrap_or("0").parse().unwrap_or(0);
        let mut members: BTreeSet<String> = p["member_ids"].as_array().into_iter().flatten().filter_map(|v| v.as_str().map(str::to_string)).collect();
        members.insert(me.clone());
        if members.len() < 2 || members.len() > MAX_GROUP {
            return Err(Error::Invalid("В группе от 2 до 16 участников".into()));
        }
        let st = GroupState { group_id: gid.to_string(), revision: prev + 1, prev_hash: keys::sha256(&prev_blob), owner_id: me, members: members.iter().cloned().collect() };
        let blob = st.encode()?;
        let sig = keys::sign_blob(&account, &blob)?;
        let c = self.authed(
            "POST",
            &format!("/v1/conversations/{gid}/membership"),
            Some(&json!({"prev_revision": prev.to_string(), "members": members, "state": {"revision": (prev + 1).to_string(), "blob": b64(&blob), "signature": b64(&sig)}})),
        )?;
        self.apply_conversation(&c)?;
        let title: String = self.db().conn.query_row("SELECT title FROM conversations WHERE id=?1", [gid], |r| r.get(0))?;
        self.enqueue_control(gid, "group_update", json!({"title": title}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    pub fn group_rename(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let title = s(p, "title")?.trim().to_string();
        let (me, _) = self.me()?;
        let owner: Option<String> = self.db().conn.query_row("SELECT owner_id FROM conversations WHERE id=?1", [gid], |r| r.get(0))?;
        if owner.as_deref() != Some(me.as_str()) {
            return Err(Error::Invalid("Название меняет владелец группы".into()));
        }
        if title.is_empty() || title.chars().count() > 64 {
            return Err(Error::Invalid("Название группы: от 1 до 64 символов".into()));
        }
        self.db().conn.execute("UPDATE conversations SET title=?2 WHERE id=?1", params![gid, title])?;
        self.enqueue_control(gid, "group_update", json!({"title": title}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    pub fn group_leave(&self, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let (me, my_device) = self.me()?;
        let cur = self.authed("GET", &format!("/v1/conversations/{gid}"), None)?;
        let prev: u64 = cur["revision"].as_str().unwrap_or("0").parse().unwrap_or(0);
        let blob = keys::leave_blob(gid, &me, &my_device, prev)?;
        let identity = keys::local_identity(&self.db().conn)?;
        let sig = keys::sign_blob(&identity, &blob)?;
        self.authed(
            "POST",
            &format!("/v1/conversations/{gid}/leave"),
            Some(&json!({"prev_revision": prev.to_string(), "blob": b64(&blob), "signature": b64(&sig)})),
        )?;
        self.fetch_conversation(gid)?;
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }

    pub fn members_with_changed_keys(&self, conv_id: &str) -> Result<Vec<String>> {
        let db = self.db();
        let members: String = db.conn.query_row("SELECT members FROM conversations WHERE id=?1", [conv_id], |r| r.get(0))?;
        let members: Vec<String> = serde_json::from_str(&members)?;
        let mut out = Vec::new();
        for m in members {
            let changed: Option<i64> = db.conn.query_row("SELECT key_changed FROM contacts WHERE user_id=?1", [&m], |r| r.get(0)).optional()?;
            if changed == Some(1) {
                out.push(m);
            }
        }
        Ok(out)
    }

    pub fn conversation_json(&self, id: &str) -> Result<Value> {
        let (me, _) = self.me()?;
        let changed = self.members_with_changed_keys(id)?;
        let db = self.db();
        let row = db
            .conn
            .query_row(
                "SELECT id, kind, title, peer_user_id, owner_id, members, archived, left_group, unread, draft, last_preview, last_activity FROM conversations WHERE id=?1",
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, Option<String>>(4)?,
                        r.get::<_, String>(5)?,
                        r.get::<_, i64>(6)?,
                        r.get::<_, i64>(7)?,
                        r.get::<_, i64>(8)?,
                        r.get::<_, String>(9)?,
                        r.get::<_, String>(10)?,
                        r.get::<_, i64>(11)?,
                    ))
                },
            )
            .optional()?
            .ok_or_else(|| Error::Invalid("Беседа не найдена".into()))?;
        let (id, kind, title, peer, owner, members, archived, left, unread, draft, preview, activity) = row;
        let member_ids: Vec<String> = serde_json::from_str(&members)?;
        let mut members_out = Vec::new();
        let mut peer_verified = false;
        for m in &member_ids {
            if *m == me {
                members_out.push(json!({"user_id": m, "display_name": "Вы", "username": kv_get(&db.conn, "username")?.map(|b| String::from_utf8_lossy(&b).into_owned()), "me": true}));
                continue;
            }
            let c = db
                .conn
                .query_row("SELECT username, first_name, last_name, verified, key_changed FROM contacts WHERE user_id=?1", [m], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?, r.get::<_, i64>(4)?))
                })
                .optional()?;
            if let Some((u, f, l, v, k)) = c {
                if Some(m) == peer.as_ref() {
                    peer_verified = v == 1;
                }
                members_out.push(json!({"user_id": m, "username": u, "display_name": display_name(&f, &l, &u), "verified": v == 1, "key_changed": k == 1, "me": false}));
            }
        }
        let display = if kind == "direct" {
            members_out.iter().find(|m| m["me"] == false).and_then(|m| m["display_name"].as_str()).unwrap_or("Диалог").to_string()
        } else if title.is_empty() {
            "Группа".to_string()
        } else if kind == "system" {
            "NeoChat уведомления".to_string()
        } else {
            title.clone()
        };
        let notice = if !changed.is_empty() {
            Some("Ключ собеседника изменился. Сверьте код перед продолжением")
        } else if left == 1 {
            Some("Вы больше не участник этой группы")
        } else if archived == 1 {
            Some("Группа архивирована: владелец вышел")
        } else {
            None
        };
        Ok(json!({
            "id": id,
            "kind": kind,
            "title": display,
            "peer_user_id": peer,
            "owner_id": owner,
            "is_owner": owner.as_deref() == Some(me.as_str()),
            "members": members_out,
            "member_count": member_ids.len(),
            "archived": archived == 1,
            "left": left == 1,
            "unread": unread,
            "draft": draft,
            "last_preview": preview,
            "last_activity": activity,
            "key_changed_users": changed,
            "peer_verified": peer_verified,
            "can_send": kind != "system" && changed.is_empty() && left == 0 && archived == 0,
            "notice": notice,
        }))
    }

    pub fn list_conversations(&self, p: &Value) -> Result<Value> {
        let q = p["query"].as_str().unwrap_or("").trim().to_lowercase();
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare("SELECT id FROM conversations ORDER BY last_activity DESC")?;
            st.query_map([], |r| r.get(0))?.collect::<std::result::Result<_, _>>()?
        };
        let mut out = Vec::new();
        for id in ids {
            let c = self.conversation_json(&id)?;
            if q.is_empty()
                || c["title"].as_str().unwrap_or("").to_lowercase().contains(&q)
                || c["last_preview"].as_str().unwrap_or("").to_lowercase().contains(&q)
            {
                out.push(c);
            }
        }
        Ok(Value::Array(out))
    }

    pub fn get_conversation(&self, p: &Value) -> Result<Value> {
        self.conversation_json(s(p, "conversation_id")?)
    }

    pub fn set_draft(&self, p: &Value) -> Result<Value> {
        let text = p["text"].as_str().unwrap_or("");
        self.db().conn.execute("UPDATE conversations SET draft=?2 WHERE id=?1", params![s(p, "conversation_id")?, text])?;
        Ok(Value::Null)
    }
}
