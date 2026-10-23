//! Service lines of a group: who added, removed, muted or released a member.
//!
//! The owner or admin who makes the change sends an encrypted "service" message to the
//! group. Receivers accept it only from the owner or an admin they know of and work out
//! the rank themselves, so a member cannot dress a forged line up as an admin's action.
//! The stored body is JSON ({ev, by, role, users, until}); names are put in when the
//! line is shown, so a renamed contact is shown by the new name.

use std::sync::atomic::Ordering;

use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::engine::{Inner, display_name};
use crate::error::Result;
use crate::groups::rights_in;
use crate::outbox::Payload;
use crate::store::now_ms;

const EVENTS: [&str; 4] = ["added", "removed", "muted", "unmuted"];
const MAX_USERS: usize = 50;

/// The role of `by` as a receiver sees it, or None when `by` has no right to such a line.
pub fn rank_in(conn: &rusqlite::Connection, conv: &str, by: &str) -> Option<&'static str> {
    let r = rights_in(conn, conv).ok()?;
    if r.kind != "group" {
        return None;
    }
    if r.owner.as_deref() == Some(by) {
        Some("owner")
    } else if r.admins.iter().any(|a| a == by) {
        Some("admin")
    } else {
        None
    }
}

/// The stored body of a line, or None when the payload is malformed.
pub fn body_of(role: &str, by: &str, g: &Value) -> Option<String> {
    let ev = g["ev"].as_str().filter(|e| EVENTS.contains(e))?;
    let users: Vec<&str> = g["users"].as_array()?.iter().map(|u| u.as_str().filter(|u| u.len() <= 40)).collect::<Option<_>>()?;
    if users.is_empty() || users.len() > MAX_USERS {
        return None;
    }
    let mut body = json!({"ev": ev, "by": by, "role": role, "users": users});
    if ev == "muted" {
        body["until"] = json!(g["until"].as_i64().filter(|u| *u == -1 || *u > 0)?);
    }
    Some(body.to_string())
}

impl Inner {
    /// Tells the group what the owner or an admin just did; the line also appears here.
    pub fn send_service(&self, conv: &str, ev: &str, users: &[String], until: Option<i64>) -> Result<()> {
        if users.is_empty() {
            return Ok(());
        }
        let mut g = json!({"ev": ev, "users": users});
        if let Some(u) = until {
            g["until"] = json!(u);
        }
        let mut p = self.new_payload(conv, "service")?;
        let role = rank_in(&self.db().conn, conv, &p.sender).unwrap_or("admin");
        let Some(body) = body_of(role, &p.sender, &g) else { return Ok(()) };
        p.group = Some(g);
        self.enqueue(&p, false, None, "")?;
        self.store_service(&p, &body, true)?;
        self.wake();
        Ok(())
    }

    /// Stores the line and moves the chat up in the list; it never counts as unread.
    pub fn store_service(&self, p: &Payload, body: &str, outgoing: bool) -> Result<()> {
        let preview = self.service_text(body);
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        let inserted = tx.execute(
            "INSERT OR IGNORE INTO messages(id, conversation_id, sender_user_id, sender_device_id, outgoing, kind, body, status, sent_at)
             VALUES (?1, ?2, ?3, ?4, ?5, 'service', ?6, ?7, ?8)",
            params![p.mid, p.cid, p.sender, p.sdev, outgoing as i64, body, if outgoing { "sent" } else { "received" }, p.ts.min(now_ms() + 300_000)],
        )?;
        if inserted > 0 {
            tx.execute("UPDATE conversations SET hidden=0, last_activity=?2, last_preview=?3 WHERE id=?1", params![p.cid, now_ms(), preview])?;
        }
        tx.commit()?;
        drop(db);
        self.emit(json!({"type": "messages_changed", "conversation_id": p.cid}));
        self.emit(json!({"type": "conversations_changed"}));
        Ok(())
    }

    /// The readable form of a stored line.
    pub fn service_text(&self, body: &str) -> String {
        let me = self.me().map(|m| m.0).unwrap_or_default();
        text_in(&self.db().conn, &me, self.tz_offset.load(Ordering::Relaxed) as i64, body)
    }
}

fn name_in(conn: &rusqlite::Connection, user: &str) -> String {
    conn.query_row(
        &format!("SELECT {}, c.last_name, c.username FROM contacts c WHERE c.user_id=?1", crate::engine::NAME_SQL),
        [user],
        |r| Ok(display_name(&r.get::<_, String>(0)?, &r.get::<_, String>(1)?, &r.get::<_, String>(2)?)),
    )
    .optional()
    .ok()
    .flatten()
    .unwrap_or_else(|| "Участник".into())
}

/// The readable form of a stored line; takes an open connection because message lists build
/// their rows while holding the database.
pub fn text_in(conn: &rusqlite::Connection, me: &str, tz_min: i64, body: &str) -> String {
    let v: Value = serde_json::from_str(body).unwrap_or(json!({}));
    let by = v["by"].as_str().unwrap_or_default();
    let to_me = v["users"].as_array().into_iter().flatten().any(|u| u.as_str() == Some(me));
    render(&v, |u| if u == me { "Вы".to_string() } else { name_in(conn, u) }, by == me, &name_in(conn, by), to_me, now_ms(), tz_min)
}

/// "ner1s был добавлен владельцем DimSimd". `name` turns an id into what is shown.
fn render(v: &Value, name: impl Fn(&str) -> String, by_me: bool, by_name: &str, to_me: bool, now: i64, tz_min: i64) -> String {
    let users: Vec<String> = v["users"].as_array().into_iter().flatten().filter_map(Value::as_str).map(&name).collect();
    let who = users.join(", ");
    // "Вы" and several names take the plural verb: "Вы были добавлены".
    let many = users.len() > 1 || to_me;
    let rank = if v["role"] == "owner" { "владельцем" } else { "администратором" };
    let by = if by_me { "вами".to_string() } else { format!("{rank} {by_name}") };
    let verb = |one: &str, plural: &str| if many { plural.to_string() } else { one.to_string() };
    match v["ev"].as_str().unwrap_or("") {
        "added" => format!("{who} {} {by}", verb("был добавлен", "были добавлены")),
        "removed" => format!("{who} {} {by}", verb("был исключён", "были исключены")),
        "muted" => {
            let until = v["until"].as_i64().unwrap_or(-1);
            format!("{who} {} {by} {}", verb("был заглушён", "были заглушены"), if until < 0 { "навсегда".to_string() } else { format!("до {}", clock(until, now, tz_min)) })
        }
        "unmuted" => {
            let actor = if by_me { "Вы".to_string() } else { format!("{} {by_name}", if v["role"] == "owner" { "Владелец" } else { "Администратор" }) };
            let whom = users.iter().map(|u| if u == "Вы" { "вас" } else { u.as_str() }).collect::<Vec<_>>().join(", ");
            format!("{actor} снял{} ограничение с {whom}", if by_me { "и" } else { "" })
        }
        _ => String::new(),
    }
}

/// "14:30" today, "24.10 14:30" otherwise, in the local time zone.
fn clock(at: i64, now: i64, tz_min: i64) -> String {
    let local = |t: i64| t + tz_min * 60_000;
    let (h, m) = (local(at).rem_euclid(86_400_000) / 3_600_000, local(at).rem_euclid(3_600_000) / 60_000);
    if local(at).div_euclid(86_400_000) == local(now).div_euclid(86_400_000) {
        return format!("{h:02}:{m:02}");
    }
    let (_, mo, d) = crate::social::civil(local(at).div_euclid(86_400_000));
    format!("{d:02}.{mo:02} {h:02}:{m:02}")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(u: &str) -> String {
        match u {
            "a" => "DimSimd".into(),
            "b" => "ner1s".into(),
            "me" => "Вы".into(),
            _ => "?".into(),
        }
    }

    #[test]
    fn lines() {
        let added = |role: &str, users: Value| json!({"ev": "added", "by": "a", "role": role, "users": users});
        assert_eq!(render(&added("owner", json!(["b"])), names, false, "DimSimd", false, 0, 0), "ner1s был добавлен владельцем DimSimd");
        assert_eq!(render(&added("admin", json!(["a"])), names, false, "ner1s", false, 0, 0), "DimSimd был добавлен администратором ner1s");
        assert_eq!(render(&added("owner", json!(["me"])), names, false, "DimSimd", true, 0, 0), "Вы были добавлены владельцем DimSimd");
        assert_eq!(render(&added("owner", json!(["a", "b"])), names, false, "X", false, 0, 0), "DimSimd, ner1s были добавлены владельцем X");
        assert_eq!(render(&added("owner", json!(["b"])), names, true, "Вы", false, 0, 0), "ner1s был добавлен вами");
        let removed = json!({"ev": "removed", "by": "a", "role": "admin", "users": ["b"]});
        assert_eq!(render(&removed, names, false, "DimSimd", false, 0, 0), "ner1s был исключён администратором DimSimd");
        let muted = json!({"ev": "muted", "by": "a", "role": "owner", "users": ["b"], "until": -1});
        assert_eq!(render(&muted, names, false, "DimSimd", false, 0, 0), "ner1s был заглушён владельцем DimSimd навсегда");
        let day = 86_400_000;
        let timed = json!({"ev": "muted", "by": "a", "role": "owner", "users": ["b"], "until": 20_000 * day + 14 * 3_600_000 + 30 * 60_000});
        assert_eq!(render(&timed, names, false, "DimSimd", false, 20_000 * day, 0), "ner1s был заглушён владельцем DimSimd до 14:30");
        assert_eq!(render(&timed, names, false, "DimSimd", false, 19_999 * day, 180), "ner1s был заглушён владельцем DimSimd до 04.10 17:30");
        let unmuted = json!({"ev": "unmuted", "by": "a", "role": "admin", "users": ["b"]});
        assert_eq!(render(&unmuted, names, false, "DimSimd", false, 0, 0), "Администратор DimSimd снял ограничение с ner1s");
    }

    #[test]
    fn bodies_are_checked() {
        assert!(body_of("owner", "a", &json!({"ev": "added", "users": ["b"]})).is_some());
        assert!(body_of("owner", "a", &json!({"ev": "added", "users": []})).is_none());
        assert!(body_of("owner", "a", &json!({"ev": "banned", "users": ["b"]})).is_none());
        assert!(body_of("owner", "a", &json!({"ev": "muted", "users": ["b"]})).is_none());
        assert!(body_of("owner", "a", &json!({"ev": "muted", "users": ["b"], "until": 0})).is_none());
        assert!(body_of("owner", "a", &json!({"ev": "muted", "users": ["b"], "until": -1})).is_some());
        assert!(body_of("owner", "a", &json!({"ev": "added", "users": [1]})).is_none());
    }
}
