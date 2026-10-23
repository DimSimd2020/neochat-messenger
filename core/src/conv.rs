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
use crate::store::{kv_get, kv_get_str, now_ms};

pub const MAX_GROUP: usize = 50;
pub const MAX_CHANNEL: usize = 200;

impl Inner {
    pub(crate) fn account_key_of(&self, user_id: &str) -> Result<Vec<u8>> {
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
        let mut verified: Option<(GroupState, Vec<u8>)> = None;
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
            let st = self.verified_state(&id, &owner, &blob, &sig)?;
            if st.channel != (kind == "channel") {
                return Err(Error::Crypto("Тип группы не совпадает с подписанным".into()));
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
            verified = Some((st, blob));
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
        // Mutes are the server's to enforce; the client only shows them.
        let muted: serde_json::Map<String, Value> = c["members"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|m| Some((m["user_id"].as_str()?.to_string(), json!(m["muted_until"].as_i64()?))))
            .collect();
        db.conn.execute("UPDATE conversations SET muted=?2 WHERE id=?1", params![id, Value::Object(muted).to_string()])?;
        if let Some((st, blob)) = verified {
            // Admins come from the verified state, never from the server's role list.
            db.conn.execute(
                "UPDATE conversations SET admins=?2, settings=?3, state_rev=?4, state_blob=?5 WHERE id=?1",
                params![id, serde_json::to_string(&st.admins)?, c["settings"].to_string(), st.revision as i64, blob],
            )?;
        }
        Ok(())
    }

    /// Refreshes one conversation; a 404 means we are no longer a member.
    pub fn fetch_conversation(&self, id: &str) -> Result<()> {
        match self.authed("GET", &format!("/v1/conversations/{id}"), None) {
            Ok(c) => {
                let new = !self.conversation_known(id)?;
                self.apply_conversation(&c)?;
                if new {
                    // Another own device may have pinned or archived it already.
                    crate::store::kv_set(&self.db().conn, "state_pull", b"1")?;
                }
                Ok(())
            }
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
        self.db().conn.execute("UPDATE conversations SET hidden=0 WHERE id=?1", [s(&c, "id")?])?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(s(&c, "id")?)
    }

    fn require_account(&self) -> Result<libsignal_protocol::IdentityKeyPair> {
        keys::account_keypair(&self.db().conn)?.ok_or_else(|| Error::Invalid("Группами управляет основное устройство".into()))
    }

    pub fn create_group(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let account = self.require_account()?;
        let title = s(p, "title")?.trim().to_string();
        let channel = p["kind"].as_str() == Some("channel");
        if title.is_empty() || title.chars().count() > 64 {
            return Err(Error::Invalid("Название: от 1 до 64 символов".into()));
        }
        let description = p["description"].as_str().unwrap_or("").trim().chars().take(255).collect::<String>();
        let (me, _) = self.me()?;
        let mut members: BTreeSet<String> = ids(&p["member_ids"]);
        members.insert(me.clone());
        let (min, max) = if channel { (1, MAX_CHANNEL) } else { (2, MAX_GROUP) };
        if members.len() < min || members.len() > max {
            return Err(Error::Invalid(if channel { format!("В канале до {MAX_CHANNEL} подписчиков") } else { format!("В группе от 2 до {MAX_GROUP} участников") }));
        }
        let admins: Vec<String> = ids(&p["admin_ids"]).into_iter().filter(|a| members.contains(a) && *a != me).collect();
        let gid = Uuid::new_v4().to_string();
        let st = GroupState { group_id: gid.clone(), revision: 1, prev_hash: [0; 32], owner_id: me.clone(), members: members.iter().cloned().collect(), signer_id: me.clone(), channel, admins: admins.clone() };
        let blob = st.encode()?;
        let sig = keys::sign_blob(&account, &blob)?;
        let c = self.authed(
            "POST",
            "/v1/conversations/groups",
            Some(&json!({"group_id": gid, "kind": if channel { "channel" } else { "group" }, "members": members, "admins": admins,
                "state": {"revision": "1", "blob": b64(&blob), "signature": b64(&sig)}})),
        )?;
        self.apply_conversation(&c)?;
        self.db().conn.execute("UPDATE conversations SET title=?2, description=?3 WHERE id=?1", params![gid, title, description])?;
        self.enqueue_control(&gid, "group_update", json!({"title": title, "description": description}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(&gid)
    }

    /// New member list and, for the owner, a new admin list. Admins sign changes of
    /// ordinary members; the owner signs everything else.
    pub fn group_set_members(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let account = self.require_account()?;
        let gid = s(p, "conversation_id")?;
        let (me, _) = self.me()?;
        // Verify the current state first: it is the base of the next one.
        self.fetch_conversation(gid)?;
        let cur = self.authed("GET", &format!("/v1/conversations/{gid}"), None)?;
        let owner = cur["owner_id"].as_str().unwrap_or_default().to_string();
        let prev_blob = unb64(cur["state"]["blob"].as_str().unwrap_or_default())?;
        let prev_state = GroupState::decode(&prev_blob)?;
        let is_owner = owner == me;
        if !is_owner && !prev_state.admins.contains(&me) {
            return Err(Error::Invalid("Состав меняют владелец и админы".into()));
        }
        let prev: u64 = cur["revision"].as_str().unwrap_or("0").parse().unwrap_or(0);
        let mut members: BTreeSet<String> = ids(&p["member_ids"]);
        members.insert(owner.clone());
        // Clients send "everyone except me"; the caller stays a member (an admin
        // missing from the list read as an admin removed by a non-owner).
        members.insert(me.clone());
        let (min, max) = if prev_state.channel { (1, MAX_CHANNEL) } else { (2, MAX_GROUP) };
        if members.len() < min || members.len() > max {
            return Err(Error::Invalid(format!("Участников от {min} до {max}")));
        }
        let admins: Vec<String> = if is_owner && !p["admin_ids"].is_null() {
            ids(&p["admin_ids"]).into_iter().filter(|a| members.contains(a) && *a != owner).collect()
        } else {
            prev_state.admins.iter().filter(|a| members.contains(*a)).cloned().collect()
        };
        if !is_owner {
            for m in &prev_state.members {
                if !members.contains(m) && prev_state.admins.contains(m) {
                    return Err(Error::Invalid("Админа может удалить только владелец".into()));
                }
            }
        }
        let st = GroupState {
            group_id: gid.to_string(),
            revision: prev + 1,
            prev_hash: keys::sha256(&prev_blob),
            owner_id: owner,
            members: members.iter().cloned().collect(),
            signer_id: me.clone(),
            channel: prev_state.channel,
            admins: admins.clone(),
        };
        let blob = st.encode()?;
        let sig = keys::sign_blob(&account, &blob)?;
        let c = self.authed(
            "POST",
            &format!("/v1/conversations/{gid}/membership"),
            Some(&json!({"prev_revision": prev.to_string(), "members": members, "admins": admins,
                "state": {"revision": (prev + 1).to_string(), "blob": b64(&blob), "signature": b64(&sig)}})),
        )?;
        self.apply_conversation(&c)?;
        // New members learn the title, description and tags from whoever added them.
        let (title, description, tags): (String, String, String) =
            self.db().conn.query_row("SELECT title, description, tags FROM conversations WHERE id=?1", [gid], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?;
        let mut info = json!({"title": title, "description": description});
        if let Some(a) = self.group_avatar_b64(gid) {
            info["avatar"] = json!(a);
        }
        if is_owner || admins.contains(&me) {
            info["tags"] = serde_json::from_str(&tags).unwrap_or(json!({}));
        }
        self.enqueue_control(gid, "group_update", info)?;
        // "X был добавлен …" goes after the update, so a new member already knows the group.
        let added: Vec<String> = members.iter().filter(|m| !prev_state.members.contains(m)).cloned().collect();
        let removed: Vec<String> = prev_state.members.iter().filter(|m| !members.contains(*m)).cloned().collect();
        self.send_service(gid, "added", &added, None)?;
        self.send_service(gid, "removed", &removed, None)?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    /// Stops a member from writing (`minutes` > 0 for that long, -1 for good) or, with 0,
    /// lets them write again. The server enforces it; the group sees a service line.
    pub fn group_mute(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let user = s(p, "user_id")?;
        let minutes = p["minutes"].as_i64().filter(|m| (-1..=525_600).contains(m)).ok_or_else(|| Error::Invalid("Некорректный срок".into()))?;
        let (me, _) = self.me()?;
        if !self.rights(gid)?.is_admin(&me) {
            return Err(Error::Invalid("Ограничивают владелец и админы".into()));
        }
        let until = if minutes > 0 { now_ms() + minutes * 60_000 } else { minutes };
        let c = self.authed("PUT", &format!("/v1/conversations/{gid}/members/{user}/mute"), Some(&json!({"until": until})))?;
        self.apply_conversation(&c)?;
        self.send_service(gid, if minutes == 0 { "unmuted" } else { "muted" }, &[user.to_string()], (minutes != 0).then_some(until))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    pub fn group_rename(self: &Arc<Self>, p: &Value) -> Result<Value> {
        self.group_set_info(&json!({"conversation_id": s(p, "conversation_id")?, "title": s(p, "title")?}))
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
                "SELECT id, kind, title, peer_user_id, owner_id, members, archived, left_group, unread, draft, last_preview, last_activity, description, tags, in_archive, pinned, avatar, muted, saved FROM conversations WHERE id=?1",
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
                        r.get::<_, String>(12)?,
                        r.get::<_, String>(13)?,
                        r.get::<_, i64>(14)?,
                        r.get::<_, i64>(15)?,
                        r.get::<_, String>(16)?,
                        r.get::<_, String>(17)?,
                        r.get::<_, i64>(18)?,
                    ))
                },
            )
            .optional()?
            .ok_or_else(|| Error::Invalid("Беседа не найдена".into()))?;
        let (id, kind, title, peer, owner, members, archived, left, unread, draft, preview, activity, description, tags, in_archive, pinned, group_avatar, muted, saved) = row;
        drop(db);
        let rights = self.rights(&id)?;
        let db = self.db();
        let tags: serde_json::Map<String, Value> = serde_json::from_str(&tags).unwrap_or_default();
        let role_of = |u: &str| if owner.as_deref() == Some(u) { "owner" } else if rights.admins.iter().any(|a| a == u) { "admin" } else { "member" };
        let member_ids: Vec<String> = serde_json::from_str(&members)?;
        // Mutes still in force: {user_id: until}, -1 without an end.
        let now = now_ms();
        let muted: serde_json::Map<String, Value> = serde_json::from_str::<serde_json::Map<String, Value>>(&muted)
            .unwrap_or_default()
            .into_iter()
            .filter(|(_, u)| u.as_i64().is_some_and(|u| u == -1 || u > now))
            .collect();
        let muted_me = muted.get(&me).and_then(Value::as_i64).unwrap_or(0);
        let mut members_out = Vec::new();
        let mut peer_verified = false;
        for m in &member_ids {
            if *m == me {
                members_out.push(json!({"user_id": m, "display_name": "Вы", "name_color": kv_get_str(&db.conn, "name_color")?.unwrap_or_default(), "username": kv_get(&db.conn, "username")?.map(|b| String::from_utf8_lossy(&b).into_owned()), "me": true, "avatar_path": self.avatar_path(&kv_get_str(&db.conn, "avatar")?.unwrap_or_default()),
                    "role": role_of(m), "tag": tags.get(m).cloned().unwrap_or(json!("")), "muted_until": muted.get(m).cloned().unwrap_or(json!(0))}));
                continue;
            }
            let c = db
                .conn
                .query_row(&format!("SELECT c.username, {}, c.last_name, c.verified, c.key_changed, c.bio, c.avatar, c.name_color FROM contacts c WHERE c.user_id=?1", crate::engine::NAME_SQL), [m], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?, r.get::<_, i64>(4)?, r.get::<_, String>(5)?, r.get::<_, String>(6)?, r.get::<_, String>(7)?))
                })
                .optional()?;
            if let Some((u, f, l, v, k, bio, avatar, color)) = c {
                if Some(m) == peer.as_ref() {
                    peer_verified = v == 1;
                }
                members_out.push(json!({"user_id": m, "username": u, "display_name": display_name(&f, &l, &u), "verified": v == 1, "key_changed": k == 1, "me": false, "bio": bio, "name_color": color, "avatar_path": self.avatar_path(&avatar),
                    "role": role_of(m), "tag": tags.get(m).cloned().unwrap_or(json!("")), "muted_until": muted.get(m).cloned().unwrap_or(json!(0))}));
            }
        }
        let display = if kind == "direct" {
            members_out.iter().find(|m| m["me"] == false).and_then(|m| m["display_name"].as_str()).unwrap_or("Диалог").to_string()
        } else if saved == 1 {
            "Избранное".to_string()
        } else if title.is_empty() && kind == "channel" {
            "Канал".to_string()
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
        } else if muted_me != 0 && kind == "group" {
            Some("Вам запретили писать в этой группе")
        } else if archived == 1 {
            Some("Группа архивирована: владелец вышел")
        } else {
            None
        };
        let active = kind != "system" && changed.is_empty() && left == 0 && archived == 0;
        // Members seen online (I count as online); only for groups, whose members see each other.
        let online_count = if kind == "group" {
            1 + member_ids.iter().filter(|m| **m != me && self.cached_presence(m).is_some_and(|p| p["online"] == true)).count()
        } else {
            0
        };
        Ok(json!({
            "id": id,
            "kind": kind,
            "title": display,
            // A direct chat is titled with the other person's name, in the colour they chose.
            "title_color": if kind == "direct" { members_out.iter().find(|m| m["me"] == false).and_then(|m| m["name_color"].as_str()).unwrap_or("").to_string() } else { String::new() },
            "peer_user_id": peer,
            "avatar_path": if kind == "direct" {
                members_out.iter().find(|m| Some(m["user_id"].as_str().unwrap_or("")) == peer.as_deref()).map(|m| m["avatar_path"].clone()).unwrap_or(json!(""))
            } else {
                json!(self.group_avatar_path(&group_avatar))
            },
            "online_count": online_count,
            "owner_id": owner,
            "is_owner": owner.as_deref() == Some(me.as_str()),
            "members": members_out,
            "member_count": member_ids.len(),
            "archived": archived == 1,
            "left": left == 1,
            "unread": unread,
            "mention_count": db.conn.query_row("SELECT count(*) FROM messages m WHERE m.conversation_id=?1 AND m.outgoing=0 AND m.mentioned=1 AND NOT EXISTS(SELECT 1 FROM receipts r WHERE r.message_id=m.id AND r.user_id=?2 AND r.kind='read')", [&id,&me], |r| r.get::<_,i64>(0))?,
            "draft": draft,
            "last_preview": preview,
            "last_activity": activity,
            "key_changed_users": changed,
            "peer_verified": peer_verified,
            "can_send": active && muted_me == 0 && rights.can_post(&me),
            "can_post": rights.can_post(&me),
            "muted": muted_me,
            "saved": saved == 1,
            "can_pin": active && (kind == "direct" || rights.is_admin(&me)),
            "pin_count": db.conn.query_row("SELECT count(*) FROM messages WHERE conversation_id=?1 AND pinned_at>0", [&id], |r| r.get::<_, i64>(0))?,
            "can_media": active && muted_me == 0 && rights.can_media(&me),
            "can_polls": active && muted_me == 0 && rights.can_polls(&me),
            "can_edit_info": rights.can_info(&me),
            "role": role_of(&me),
            "is_admin": rights.is_admin(&me),
            "admins": rights.admins,
            "settings": {
                "members_can_send": rights.settings["members_can_send"].as_bool().unwrap_or(true),
                "members_can_media": rights.settings["members_can_media"].as_bool().unwrap_or(true),
                "members_can_polls": rights.settings["members_can_polls"].as_bool().unwrap_or(true),
                "members_can_info": rights.settings["members_can_info"].as_bool().unwrap_or(false),
            },
            "description": description,
            "tags": tags,
            "notice": notice,
            "in_archive": in_archive == 1,
            "pinned": pinned > 0,
            "pinned_at": pinned,
            "presence": peer.as_deref().and_then(|u| self.cached_presence(u)),
        }))
    }

    /// The main list (pinned first) or, with `archived`, the archive. `sort`:
    /// "activity" (default), "unread" (unread first) or "name". A search query
    /// looks through both lists.
    pub fn list_conversations(&self, p: &Value) -> Result<Value> {
        let q = p["query"].as_str().unwrap_or("").trim().to_lowercase();
        let archive = p["archived"].as_bool().unwrap_or(false);
        let filter = if !q.is_empty() { "1" } else if archive { "in_archive=1" } else { "in_archive=0" };
        let order = if p["sort"] == "unread" { "(unread>0) DESC, last_activity DESC" } else { "last_activity DESC" };
        let pins = if archive || !q.is_empty() { "" } else { "pinned DESC, " };
        let ids: Vec<String> = {
            let db = self.db();
            let mut st = db.conn.prepare(&format!("SELECT id FROM conversations WHERE hidden=0 AND ({filter}) ORDER BY {pins}{order}"))?;
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
        if p["sort"] == "name" {
            // Titles of direct chats come from contacts, so the name order is applied here.
            let key = |c: &Value| (!archive && c["pinned"] == true, c["pinned_at"].as_i64().unwrap_or(0));
            out.sort_by(|a, b| key(b).cmp(&key(a)).then_with(|| a["title"].as_str().unwrap_or("").to_lowercase().cmp(&b["title"].as_str().unwrap_or("").to_lowercase())));
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

/// A JSON array of ids as a set.
fn ids(v: &Value) -> BTreeSet<String> {
    v.as_array().into_iter().flatten().filter_map(|x| x.as_str().map(str::to_string)).collect()
}
