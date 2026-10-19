//! Group roles, settings, member tags, polls and channels.
//!
//! Membership is an owner-rooted chain of signed states: the owner signs any state; an
//! admin of the previous state may sign the next one but cannot change the admin list,
//! remove the owner or another admin. Settings (who may post) are enforced by the server;
//! titles, descriptions, tags and polls travel end-to-end encrypted, and every client
//! checks the sender's right to change them.

use std::collections::BTreeSet;
use std::sync::Arc;

use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::keys::{self, GroupState, unb64};
use crate::outbox::Payload;
use crate::store::now_ms;

const MAX_POLL_OPTIONS: usize = 10;

/// Rights of one user in one conversation, from the verified state and server settings.
pub struct Rights {
    pub kind: String,
    pub owner: Option<String>,
    pub admins: Vec<String>,
    pub settings: Value,
}

impl Rights {
    pub fn is_admin(&self, user: &str) -> bool {
        self.owner.as_deref() == Some(user) || self.admins.iter().any(|a| a == user)
    }
    fn allows(&self, key: &str) -> bool {
        self.settings[key].as_bool().unwrap_or(true)
    }
    /// Text messages; media and polls additionally need their own permission.
    pub fn can_post(&self, user: &str) -> bool {
        match self.kind.as_str() {
            "direct" => true,
            "system" => false,
            "channel" => self.is_admin(user),
            _ => self.is_admin(user) || self.allows("members_can_send"),
        }
    }
    pub fn can_media(&self, user: &str) -> bool {
        self.can_post(user) && (self.kind == "direct" || self.is_admin(user) || self.allows("members_can_media"))
    }
    pub fn can_polls(&self, user: &str) -> bool {
        (self.kind == "group" || self.kind == "channel") && self.can_post(user) && (self.is_admin(user) || self.allows("members_can_polls"))
    }
    pub fn can_info(&self, user: &str) -> bool {
        self.is_admin(user) || (self.kind == "group" && self.allows("members_can_info"))
    }
}

fn sorted(v: &[String]) -> Vec<String> {
    let mut v = v.to_vec();
    v.sort();
    v.dedup();
    v
}

/// Checks that `next` may follow `prev` given who signed it.
fn check_transition(prev: &GroupState, next: &GroupState) -> Result<()> {
    let bad = |m: &str| Err(Error::Crypto(m.into()));
    if next.group_id != prev.group_id || next.owner_id != prev.owner_id || next.channel != prev.channel {
        return bad("Состояние группы не продолжает предыдущее");
    }
    let signer = &next.signer_id;
    if *signer == next.owner_id {
        return Ok(());
    }
    if !prev.admins.contains(signer) || !prev.members.contains(signer) {
        return bad("Состав группы подписал не админ");
    }
    if sorted(&next.admins) != sorted(&prev.admins) {
        return bad("Админов назначает только владелец");
    }
    for m in &prev.members {
        if !next.members.contains(m) && (*m == prev.owner_id || prev.admins.contains(m)) {
            return bad("Админ не может удалить владельца или другого админа");
        }
    }
    Ok(())
}

/// Rights from an open connection (callers inside a transaction cannot lock the db again).
pub fn rights_in(conn: &rusqlite::Connection, conv: &str) -> Result<Rights> {
    let (kind, owner, admins, settings): (String, Option<String>, String, String) = conn
            .query_row("SELECT kind, owner_id, admins, settings FROM conversations WHERE id=?1", [conv], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })
            .optional()?
            .ok_or_else(|| Error::Invalid("Беседа не найдена".into()))?;
    Ok(Rights {
        kind,
        owner,
        admins: serde_json::from_str(&admins).unwrap_or_default(),
        settings: serde_json::from_str(&settings).unwrap_or(json!({})),
    })
}

/// Content a sender had no right to post is dropped (the server sees only ciphertext).
pub fn may_post(conn: &rusqlite::Connection, conv: &str, sender: &str, kind: &str) -> bool {
    let Ok(r) = rights_in(conn, conv) else { return true };
    match kind {
        "text" => r.can_post(sender),
        "file" => r.can_media(sender),
        "poll" => r.can_polls(sender),
        _ => true,
    }
}

/// A received poll definition, or None when it is malformed.
pub fn clean_poll(p: &Payload) -> Option<Value> {
    let poll = p.poll.as_ref()?;
    let options: Vec<String> = poll["options"].as_array()?.iter().map(|o| o.as_str().map(|s| s.chars().take(100).collect())).collect::<Option<_>>()?;
    if options.len() < 2 || options.len() > MAX_POLL_OPTIONS || p.text.as_deref().unwrap_or("").is_empty() {
        return None;
    }
    Some(json!({"options": options, "multiple": poll["multiple"].as_bool().unwrap_or(false), "anonymous": poll["anonymous"].as_bool().unwrap_or(true)}))
}

impl Inner {
    pub fn rights(&self, conv: &str) -> Result<Rights> {
        rights_in(&self.db().conn, conv)
    }

    fn verify_signed(&self, st: &GroupState, blob: &[u8], sig: &[u8]) -> Result<()> {
        self.ensure_user(&st.signer_id)?;
        keys::verify_blob(&self.account_key_of(&st.signer_id)?, blob, sig)
    }

    /// Verifies the latest signed state of a group, walking the chain from the last
    /// state this device trusted (or any owner-signed state) when an admin signed it.
    pub fn verified_state(&self, id: &str, owner: &str, blob: &[u8], sig: &[u8]) -> Result<GroupState> {
        let st = GroupState::decode(blob)?;
        if st.group_id != id || st.owner_id != owner {
            return Err(Error::Crypto("Состояние группы относится к другой группе".into()));
        }
        if st.signer_id == owner {
            self.verify_signed(&st, blob, sig)?;
            return Ok(st);
        }
        let local: Option<Vec<u8>> = self.db().conn.query_row("SELECT state_blob FROM conversations WHERE id=?1", [id], |r| r.get(0)).optional()?.flatten();
        if let Some(lb) = &local {
            if lb.as_slice() == blob {
                return Ok(st);
            }
            if keys::sha256(lb) == st.prev_hash {
                check_transition(&GroupState::decode(lb)?, &st)?;
                self.verify_signed(&st, blob, sig)?;
                return Ok(st);
            }
        }
        let res = self.authed("GET", &format!("/v1/conversations/{id}/states"), None)?;
        let mut trusted: Option<(GroupState, Vec<u8>)> = None;
        for item in res["states"].as_array().cloned().unwrap_or_default() {
            let b = unb64(item["blob"].as_str().unwrap_or_default())?;
            let g = unb64(item["signature"].as_str().unwrap_or_default())?;
            let cur = GroupState::decode(&b)?;
            if cur.group_id != id || cur.owner_id != owner {
                trusted = None;
                continue;
            }
            let ok = if cur.signer_id == owner || local.as_deref() == Some(b.as_slice()) {
                cur.signer_id != owner || self.verify_signed(&cur, &b, &g).is_ok()
            } else if let Some((p, pb)) = &trusted {
                keys::sha256(pb) == cur.prev_hash && check_transition(p, &cur).is_ok() && self.verify_signed(&cur, &b, &g).is_ok()
            } else {
                false
            };
            trusted = if ok { Some((cur, b)) } else { None };
        }
        match trusted {
            Some((t, tb)) if tb.as_slice() == blob => Ok(t),
            _ => Err(Error::Crypto("Цепочка состояний группы не прошла проверку".into())),
        }
    }

    pub fn group_set_settings(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let mut body = json!({});
        for k in ["members_can_send", "members_can_media", "members_can_polls", "members_can_info"] {
            if let Some(v) = p[k].as_bool() {
                body[k] = json!(v);
            }
        }
        let c = self.authed("PUT", &format!("/v1/conversations/{gid}/settings"), Some(&body))?;
        self.apply_conversation(&c)?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    /// Title and description; sent end-to-end encrypted to the members.
    pub fn group_set_info(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let (me, _) = self.me()?;
        if !self.rights(gid)?.can_info(&me) {
            return Err(Error::Invalid("Менять описание могут владелец и админы".into()));
        }
        let mut update = json!({});
        if let Some(t) = p["title"].as_str() {
            let t = t.trim();
            if t.is_empty() || t.chars().count() > 64 {
                return Err(Error::Invalid("Название: от 1 до 64 символов".into()));
            }
            self.db().conn.execute("UPDATE conversations SET title=?2 WHERE id=?1", params![gid, t])?;
            update["title"] = json!(t);
        }
        if let Some(d) = p["description"].as_str() {
            let d = d.trim();
            if d.chars().count() > 255 {
                return Err(Error::Invalid("Описание: не больше 255 символов".into()));
            }
            self.db().conn.execute("UPDATE conversations SET description=?2 WHERE id=?1", params![gid, d])?;
            update["description"] = json!(d);
        }
        self.enqueue_control(gid, "group_update", update)?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(gid)
    }

    /// A short label next to a member's name, set by the owner or an admin.
    pub fn group_set_tag(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let gid = s(p, "conversation_id")?;
        let user = s(p, "user_id")?;
        let tag = p["tag"].as_str().unwrap_or("").trim().to_string();
        let (me, _) = self.me()?;
        if !self.rights(gid)?.is_admin(&me) {
            return Err(Error::Invalid("Теги назначают владелец и админы".into()));
        }
        if tag.chars().count() > 16 {
            return Err(Error::Invalid("Тег: не больше 16 символов".into()));
        }
        self.store_tag(gid, user, &tag)?;
        self.enqueue_control(gid, "group_update", json!({"tags": {user: tag}}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.emit(json!({"type": "messages_changed", "conversation_id": gid}));
        self.conversation_json(gid)
    }

    fn store_tag(&self, gid: &str, user: &str, tag: &str) -> Result<()> {
        let db = self.db();
        let raw: String = db.conn.query_row("SELECT tags FROM conversations WHERE id=?1", [gid], |r| r.get(0))?;
        let mut tags: serde_json::Map<String, Value> = serde_json::from_str(&raw).unwrap_or_default();
        if tag.is_empty() {
            tags.remove(user);
        } else {
            tags.insert(user.to_string(), json!(tag));
        }
        db.conn.execute("UPDATE conversations SET tags=?2 WHERE id=?1", params![gid, serde_json::to_string(&tags)?])?;
        Ok(())
    }

    /// Applies a received group_update after checking the sender's rights.
    pub fn apply_group_update(&self, conv: &str, sender: &str, g: &Value) -> Result<bool> {
        let r = self.rights(conv)?;
        let mut changed = false;
        if r.can_info(sender) {
            if let Some(t) = g["title"].as_str() {
                let t: String = t.chars().take(64).collect();
                self.db().conn.execute("UPDATE conversations SET title=?2 WHERE id=?1", params![conv, t])?;
                changed = true;
            }
            if let Some(d) = g["description"].as_str() {
                let d: String = d.chars().take(255).collect();
                self.db().conn.execute("UPDATE conversations SET description=?2 WHERE id=?1", params![conv, d])?;
                changed = true;
            }
        }
        if r.is_admin(sender) {
            if let Some(tags) = g["tags"].as_object() {
                for (u, t) in tags.iter().take(256) {
                    let t: String = t.as_str().unwrap_or("").chars().take(16).collect();
                    self.store_tag(conv, u, &t)?;
                }
                changed = true;
            }
        }
        Ok(changed)
    }

    // ---- polls ----

    pub fn send_poll(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let question = s(p, "question")?.trim().to_string();
        let options: Vec<String> = p["options"].as_array().into_iter().flatten().filter_map(|o| o.as_str()).map(|o| o.trim().to_string()).filter(|o| !o.is_empty()).collect();
        if question.is_empty() || question.chars().count() > 255 {
            return Err(Error::Invalid("Вопрос: от 1 до 255 символов".into()));
        }
        if options.len() < 2 || options.len() > MAX_POLL_OPTIONS || options.iter().any(|o| o.chars().count() > 100) {
            return Err(Error::Invalid("Вариантов от 2 до 10, каждый до 100 символов".into()));
        }
        self.check_can_send(conv)?;
        let (me, _) = self.me()?;
        if !self.rights(conv)?.can_polls(&me) {
            return Err(Error::Invalid("Опросы здесь создавать нельзя".into()));
        }
        let mut pl = self.new_payload(conv, "poll")?;
        pl.text = Some(question.clone());
        pl.poll = Some(json!({"options": options, "multiple": p["multiple"].as_bool().unwrap_or(false), "anonymous": p["anonymous"].as_bool().unwrap_or(true)}));
        self.enqueue(&pl, true, None, &format!("📊 {question}"))?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.wake();
        self.message_json(&pl.mid)
    }

    fn poll_of(&self, id: &str) -> Result<(String, String, Value)> {
        let (conv, sender, poll): (String, String, Option<String>) = self
            .db()
            .conn
            .query_row("SELECT conversation_id, sender_user_id, poll FROM messages WHERE id=?1 AND kind='poll'", [id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .optional()?
            .ok_or_else(|| Error::Invalid("Опрос не найден".into()))?;
        Ok((conv, sender, serde_json::from_str(&poll.unwrap_or_default()).unwrap_or(json!({}))))
    }

    /// Valid choices for a poll: indexes in range, one unless the poll is multiple choice.
    fn clean_choices(poll: &Value, choices: &Value) -> Vec<u64> {
        let n = poll["options"].as_array().map(|a| a.len()).unwrap_or(0) as u64;
        let mut out: Vec<u64> = choices.as_array().into_iter().flatten().filter_map(|c| c.as_u64()).filter(|c| *c < n).collect::<BTreeSet<_>>().into_iter().collect();
        if !poll["multiple"].as_bool().unwrap_or(false) {
            out.truncate(1);
        }
        out
    }

    pub fn vote_poll(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let (conv, _, poll) = self.poll_of(id)?;
        if poll["closed"] == true {
            return Err(Error::Invalid("Опрос завершён".into()));
        }
        let choices = Self::clean_choices(&poll, &p["choices"]);
        let (me, _) = self.me()?;
        self.store_vote(id, &me, &choices)?;
        self.enqueue_control(&conv, "poll_vote", json!({"id": id, "choices": choices}))?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.message_json(id)
    }

    pub fn close_poll(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let (conv, sender, _) = self.poll_of(id)?;
        let (me, _) = self.me()?;
        if sender != me {
            return Err(Error::Invalid("Завершить опрос может только его автор".into()));
        }
        self.mark_poll_closed(id)?;
        self.enqueue_control(&conv, "poll_close", json!({"id": id}))?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.message_json(id)
    }

    fn store_vote(&self, id: &str, user: &str, choices: &[u64]) -> Result<()> {
        let db = self.db();
        if choices.is_empty() {
            db.conn.execute("DELETE FROM poll_votes WHERE poll_id=?1 AND user_id=?2", params![id, user])?;
        } else {
            db.conn.execute(
                "INSERT INTO poll_votes(poll_id, user_id, choices, at) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(poll_id, user_id) DO UPDATE SET choices=excluded.choices, at=excluded.at",
                params![id, user, serde_json::to_string(choices)?, now_ms()],
            )?;
        }
        Ok(())
    }

    fn mark_poll_closed(&self, id: &str) -> Result<()> {
        let (_, _, mut poll) = self.poll_of(id)?;
        poll["closed"] = json!(true);
        self.db().conn.execute("UPDATE messages SET poll=?2 WHERE id=?1", params![id, poll.to_string()])?;
        Ok(())
    }

    /// Received poll_vote / poll_close; returns whether anything changed.
    pub fn apply_poll_control(&self, conv: &str, sender: &str, kind: &str, g: &Value) -> Result<bool> {
        let id = g["id"].as_str().unwrap_or_default();
        let Ok((pconv, author, poll)) = self.poll_of(id) else { return Ok(false) };
        if pconv != conv {
            return Ok(false);
        }
        match kind {
            "poll_vote" if poll["closed"] != true => {
                let choices = Self::clean_choices(&poll, &g["choices"]);
                self.store_vote(id, sender, &choices)?;
                Ok(true)
            }
            "poll_close" if author == sender => {
                self.mark_poll_closed(id)?;
                Ok(true)
            }
            _ => Ok(false),
        }
    }

    /// The poll as the UI shows it: counts, my choices and, for open polls, voter names.
    pub fn poll_json(&self, id: &str, poll: &Value, me: &str) -> Result<Value> {
        let db = self.db();
        let mut st = db.conn.prepare(&format!(
            "SELECT v.user_id, v.choices, COALESCE({}, ''), COALESCE(c.last_name, ''), COALESCE(c.username, '') FROM poll_votes v LEFT JOIN contacts c ON c.user_id=v.user_id WHERE v.poll_id=?1",
            crate::engine::NAME_SQL
        ))?;
        let votes: Vec<(String, Vec<u64>, String)> = st
            .query_map([id], |r| {
                let u: String = r.get(0)?;
                let ch: String = r.get(1)?;
                let name = crate::engine::display_name(&r.get::<_, String>(2)?, &r.get::<_, String>(3)?, &r.get::<_, String>(4)?);
                Ok((u, serde_json::from_str(&ch).unwrap_or_default(), name))
            })?
            .collect::<std::result::Result<_, _>>()?;
        let anonymous = poll["anonymous"].as_bool().unwrap_or(true);
        let options: Vec<Value> = poll["options"]
            .as_array()
            .into_iter()
            .flatten()
            .enumerate()
            .map(|(i, o)| {
                let who: Vec<&(String, Vec<u64>, String)> = votes.iter().filter(|v| v.1.contains(&(i as u64))).collect();
                let mut o = json!({"text": o, "votes": who.len()});
                if !anonymous {
                    o["voters"] = json!(who.iter().map(|v| if v.0 == me { "Вы".to_string() } else { v.2.clone() }).collect::<Vec<_>>());
                }
                o
            })
            .collect();
        let mine = votes.iter().find(|v| v.0 == me).map(|v| v.1.clone()).unwrap_or_default();
        Ok(json!({
            "options": options,
            "multiple": poll["multiple"].as_bool().unwrap_or(false),
            "anonymous": anonymous,
            "closed": poll["closed"].as_bool().unwrap_or(false),
            "voters": votes.len(),
            "my_choices": mine,
        }))
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(signer: &str, owner: &str, members: &[&str], admins: &[&str]) -> GroupState {
        GroupState {
            group_id: "00000000-0000-0000-0000-000000000001".into(),
            revision: 1,
            prev_hash: [0; 32],
            owner_id: owner.into(),
            members: members.iter().map(|m| m.to_string()).collect(),
            signer_id: signer.into(),
            channel: false,
            admins: admins.iter().map(|m| m.to_string()).collect(),
        }
    }

    #[test]
    fn admin_rights_in_state_chain() {
        let prev = st("o", "o", &["o", "a", "b", "c"], &["a"]);
        // An admin may remove an ordinary member.
        assert!(check_transition(&prev, &st("a", "o", &["o", "a", "b"], &["a"])).is_ok());
        // ...but not the owner, not change admins, and a member cannot sign.
        assert!(check_transition(&prev, &st("a", "o", &["a", "b", "c"], &["a"])).is_err());
        assert!(check_transition(&prev, &st("a", "o", &["o", "a", "b", "c"], &["a", "b"])).is_err());
        assert!(check_transition(&prev, &st("b", "o", &["o", "a", "b"], &["a"])).is_err());
        // The owner may do anything.
        assert!(check_transition(&prev, &st("o", "o", &["o", "b"], &["b"])).is_ok());
    }
}
