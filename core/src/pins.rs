//! Pinned messages and the "Saved messages" chat.
//!
//! A pin is an encrypted control message ({id, on}) so every member sees the same pins.
//! In a direct chat either side may pin; in groups and channels only the owner and admins.
//! Several messages can be pinned at once; the newest pin comes first.
//!
//! "Saved messages" is a channel with its owner as the only member. Its other devices
//! receive it like any channel, so an older app on the same account still syncs it as a
//! plain channel; `saved` only tells apps that know the feature to show it as notes.

use std::sync::Arc;

use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::groups::rights_in;
use crate::store::now_ms;

const MAX_PINS: i64 = 20;
const SAVED_TITLE: &str = "Избранное";

impl Inner {
    /// Whether `user` may pin or unpin in `conv`.
    fn may_pin(conn: &rusqlite::Connection, conv: &str, user: &str) -> Result<bool> {
        let r = rights_in(conn, conv)?;
        Ok(match r.kind.as_str() {
            "direct" => true,
            "group" | "channel" => r.is_admin(user),
            _ => false,
        })
    }

    /// Applies a pin control of `sender`; true when something changed.
    pub fn apply_pin(conn: &rusqlite::Connection, conv: &str, sender: &str, g: &Value, now: i64) -> Result<bool> {
        let id = g["id"].as_str().unwrap_or_default();
        let on = g["on"].as_bool().unwrap_or(false);
        // The pin keeps the time its author gave it, so every device orders the pins alike.
        let at = g["at"].as_i64().filter(|t| *t > 0).map_or(now, |t| t.min(now + 300_000));
        if !Self::may_pin(conn, conv, sender)? {
            return Ok(false);
        }
        let pinned: Option<i64> = conn
            .query_row("SELECT pinned_at FROM messages WHERE id=?1 AND conversation_id=?2 AND kind IN ('text','file','poll')", params![id, conv], |r| r.get(0))
            .optional()?;
        let Some(pinned) = pinned else { return Ok(false) };
        if !on && pinned == 0 {
            return Ok(false);
        }
        if on {
            let count: i64 = conn.query_row("SELECT count(*) FROM messages WHERE conversation_id=?1 AND pinned_at>0 AND id<>?2", params![conv, id], |r| r.get(0))?;
            if count >= MAX_PINS {
                return Ok(false);
            }
        }
        conn.execute("UPDATE messages SET pinned_at=?3 WHERE id=?1 AND conversation_id=?2", params![id, conv, if on { at } else { 0 }])?;
        Ok(true)
    }

    pub fn pin_message(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let on = p["on"].as_bool().unwrap_or(true);
        let conv: String = self
            .db()
            .conn
            .query_row("SELECT conversation_id FROM messages WHERE id=?1 AND kind IN ('text','file','poll')", [id], |r| r.get(0))
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        let (me, _) = self.me()?;
        if !Self::may_pin(&self.db().conn, &conv, &me)? {
            return Err(Error::Invalid("Закреплять могут владелец и админы".into()));
        }
        let count: i64 = self.db().conn.query_row("SELECT count(*) FROM messages WHERE conversation_id=?1 AND pinned_at>0 AND id<>?2", params![conv, id], |r| r.get(0))?;
        if on && count >= MAX_PINS {
            return Err(Error::Invalid(format!("Можно закрепить не больше {MAX_PINS} сообщений")));
        }
        self.check_can_send(&conv)?;
        let control = json!({"id": id, "on": on, "at": now_ms()});
        Self::apply_pin(&self.db().conn, &conv, &me, &control, now_ms())?;
        self.enqueue_control(&conv, "pin", control)?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.emit(json!({"type": "conversations_changed"}));
        self.message_json(id)
    }

    /// Pinned messages of a conversation, the newest pin first.
    pub fn list_pins(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        Ok(Value::Array(self.query_messages("m.conversation_id = ?1 AND m.pinned_at > 0 ORDER BY m.pinned_at DESC", &[&conv])?))
    }

    // ---- saved messages ----

    /// The Saved chat: found, or created on first use.
    pub fn open_saved(self: &Arc<Self>) -> Result<Value> {
        let existing: Option<String> = self
            .db()
            .conn
            .query_row("SELECT id FROM conversations WHERE saved=1 AND left_group=0 AND archived=0 ORDER BY last_activity DESC LIMIT 1", [], |r| r.get(0))
            .optional()?;
        if let Some(id) = existing {
            self.db().conn.execute("UPDATE conversations SET hidden=0 WHERE id=?1", [&id])?;
            self.emit(json!({"type": "conversations_changed"}));
            return self.conversation_json(&id);
        }
        let c = self.create_group(&json!({"title": SAVED_TITLE, "kind": "channel", "member_ids": []}))?;
        let gid = s(&c, "id")?.to_string();
        self.db().conn.execute("UPDATE conversations SET saved=1 WHERE id=?1", [&gid])?;
        // Own other devices learn which channel is the notes chat.
        self.enqueue_control(&gid, "group_update", json!({"title": SAVED_TITLE, "saved": true}))?;
        self.emit(json!({"type": "conversations_changed"}));
        self.conversation_json(&gid)
    }

    /// Copies a message into Saved; a file that is not downloaded yet is fetched first.
    pub fn save_message(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let (kind, conv): (String, String) = self
            .db()
            .conn
            .query_row("SELECT kind, conversation_id FROM messages WHERE id=?1 AND kind IN ('text','file','poll')", [id], |r| Ok((r.get(0)?, r.get(1)?)))
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        if kind == "file" && !self.data_dir.join("files").join(format!("{id}.enc")).exists() {
            self.download_file(&json!({"message_id": id}))?;
        }
        let saved = self.open_saved()?;
        let sid = s(&saved, "id")?;
        if sid == conv {
            return Err(Error::Invalid("Сообщение уже в избранном".into()));
        }
        self.forward_message(&json!({"message_id": id, "conversation_id": sid}))
    }
}
