//! Mention recipients travel inside the authenticated E2EE message only.
use rusqlite::Connection;
use serde_json::{Value, json};

use crate::engine::{Inner, s};
use crate::error::Result;
use crate::outbox::Payload;

pub fn names(text: &str) -> Vec<String> {
    crate::markdown::spans(text).iter().filter_map(|s| s["mention"].as_str().map(str::to_lowercase)).collect()
}

impl Inner {
    pub fn resolve_mentions(&self, conv: &str, text: &str) -> Result<Vec<String>> {
        let names = names(text);
        if names.is_empty() { return Ok(Vec::new()); }
        let c = self.conversation_json(conv)?;
        let (me, _) = self.me()?;
        let members = c["members"].as_array().cloned().unwrap_or_default();
        let broadcast = c["kind"] == "group" && c["is_admin"] == true;
        let everyone = broadcast && names.iter().any(|s| s == "everyone");
        let here = broadcast && names.iter().any(|s| s == "here");
        if here {
            let ids: Vec<Value> = members.iter().map(|m| m["user_id"].clone()).collect();
            self.presence(&json!({"user_ids":ids}))?;
        }
        Ok(members.iter().filter(|m| m["user_id"] != me).filter(|m| everyone ||
            names.iter().any(|n| m["username"].as_str().is_some_and(|u| u.eq_ignore_ascii_case(n))) ||
            (here && self.cached_presence(m["user_id"].as_str().unwrap_or("")).is_some_and(|p| p["online"] == true)))
            .filter_map(|m| m["user_id"].as_str().map(str::to_string)).take(200).collect())
    }

    pub fn mention_candidates(&self, p: &Value) -> Result<Value> {
        let c = self.conversation_json(s(p,"conversation_id")?)?;
        let query = p["query"].as_str().unwrap_or("").trim_start_matches('@').to_lowercase();
        let mut out = Vec::new();
        if c["kind"] == "group" && c["is_admin"] == true {
            for (u, title) in [("everyone","Все участники"),("here","Сейчас в сети")] {
                if u.starts_with(&query) { out.push(json!({"username":u,"display_name":title,"broadcast":true})); }
            }
        }
        for member in c["members"].as_array().into_iter().flatten() {
            if member["me"] == true { continue; }
            if member["username"].as_str().is_some_and(|u| !u.is_empty() && u.to_lowercase().starts_with(&query)) { out.push(member.clone()); }
        }
        Ok(json!(out))
    }
}

/// The sender's role comes from the locally verified membership, never from the
/// envelope's mention list. A normal user cannot turn @everyone into a ping.
pub fn incoming(conn: &Connection, p: &Payload, me: &str) -> Result<bool> {
    if p.sender == me { return Ok(false); }
    let reply = p.reply_to.as_ref().is_some_and(|id| conn.query_row("SELECT EXISTS(SELECT 1 FROM messages WHERE id=?1 AND conversation_id=?2 AND outgoing=1)", [id,&p.cid], |r| r.get::<_,bool>(0)).unwrap_or(false));
    if reply { return Ok(true); }
    if !p.mentions.iter().take(200).any(|id| id == me) { return Ok(false); }
    let tokens = names(p.text.as_deref().unwrap_or(""));
    let (kind, owner, admins): (String,Option<String>,String) = conn.query_row("SELECT kind,owner_id,admins FROM conversations WHERE id=?1", [&p.cid], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?;
    let admins: Vec<String> = serde_json::from_str(&admins).unwrap_or_default();
    if kind == "group" && (owner.as_deref() == Some(&p.sender) || admins.contains(&p.sender)) && tokens.iter().any(|n| n == "everyone" || n == "here") { return Ok(true); }
    let own = crate::store::kv_get_str(conn,"username")?.unwrap_or_default();
    // Named pings remain readable when the recipient has just changed their handle.
    Ok(tokens.iter().any(|n| n.eq_ignore_ascii_case(&own)) || tokens.iter().any(|n| n != "everyone" && n != "here") && p.mentions.len() == 1)
}
