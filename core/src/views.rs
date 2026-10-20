//! Read-only views for the UI: pages of messages and local search.

use rusqlite::{OptionalExtension, ToSql};
use serde_json::{Value, json};

use crate::engine::{Inner, display_name, s};
use crate::error::{Error, Result};
use crate::keys;
use crate::outbox::FileMeta;

const COLUMNS: &str = "m.local_id, m.id, m.conversation_id, m.sender_user_id, m.outgoing, m.kind, m.body, m.reply_to, m.file, m.status, m.error, m.sent_at,
    COALESCE(CASE WHEN c.first_name='' AND c.last_name='' THEN c.profile_name ELSE c.first_name END, CASE WHEN m.sender_user_id='system' THEN 'NeoChat' ELSE '' END),
    COALESCE(c.last_name, ''), COALESCE(c.username, ''), m.poll, m.fwd";

impl Inner {
    /// Runs a message query; `me` is appended as the last positional parameter.
    fn query_messages(&self, cond: &str, args: &[&dyn ToSql]) -> Result<Vec<Value>> {
        let db = self.db();
        let me = keys::my_user_id(&db.conn)?;
        let n = args.len() + 1;
        let sql = format!(
            "SELECT {COLUMNS},
                (SELECT count(DISTINCT user_id) FROM receipts r WHERE r.message_id=m.id AND r.user_id<>?{n}),
                (SELECT count(DISTINCT user_id) FROM receipts r WHERE r.message_id=m.id AND r.kind='read' AND r.user_id<>?{n})
             FROM messages m LEFT JOIN contacts c ON c.user_id=m.sender_user_id WHERE {cond}"
        );
        let mut params: Vec<&dyn ToSql> = args.to_vec();
        params.push(&me);
        let mut st = db.conn.prepare(&sql)?;
        let rows = st.query_map(params.as_slice(), |r| {
            Ok(Row {
                local_id: r.get(0)?,
                id: r.get(1)?,
                conversation_id: r.get(2)?,
                sender: r.get(3)?,
                outgoing: r.get::<_, i64>(4)? == 1,
                kind: r.get(5)?,
                body: r.get(6)?,
                reply_to: r.get(7)?,
                file: r.get(8)?,
                status: r.get(9)?,
                error: r.get(10)?,
                sent_at: r.get(11)?,
                sender_name: display_name(&r.get::<_, String>(12)?, &r.get::<_, String>(13)?, &r.get::<_, String>(14)?),
                poll: r.get(15)?,
                fwd: r.get(16)?,
                delivered: r.get(17)?,
                read: r.get(18)?,
            })
        })?;
        let rows: Vec<Row> = rows.collect::<std::result::Result<_, _>>()?;
        let mut out = Vec::with_capacity(rows.len());
        // Per conversation: (channel title when it is a channel, member tags).
        let mut convs: std::collections::HashMap<String, (Option<String>, Value)> = Default::default();
        let mut polls: Vec<(usize, String, Value)> = Vec::new();
        let mut reactions: Vec<Value> = Vec::with_capacity(rows.len());
        for r in &rows {
            reactions.push(self.reactions_json(&db.conn, &r.id, &me)?);
        }
        let mut reactions = reactions.into_iter();
        for r in rows {
            let reply = match &r.reply_to {
                Some(rid) => db
                    .conn
                    .query_row(
                        "SELECT m.body, m.outgoing, COALESCE(CASE WHEN c.first_name='' AND c.last_name='' THEN c.profile_name ELSE c.first_name END,''), COALESCE(c.last_name,''), COALESCE(c.username,''), m.kind FROM messages m
                         LEFT JOIN contacts c ON c.user_id=m.sender_user_id WHERE m.id=?1",
                        [rid],
                        |x| {
                            let name = if x.get::<_, i64>(1)? == 1 { "Вы".to_string() } else { display_name(&x.get::<_, String>(2)?, &x.get::<_, String>(3)?, &x.get::<_, String>(4)?) };
                            Ok(json!({"id": rid, "text": x.get::<_, String>(0)?.chars().take(120).collect::<String>(), "sender_name": name, "kind": x.get::<_, String>(5)?}))
                        },
                    )
                    .optional()?,
                None => None,
            };
            let file = match &r.file {
                Some(f) => {
                    let meta: FileMeta = serde_json::from_str(f)?;
                    let downloaded = self.data_dir.join("files").join(format!("{}.enc", r.id)).exists();
                    Some(json!({"name": meta.name, "size": meta.size, "mime": meta.mime, "downloaded": downloaded,
                        "image": meta.mime.starts_with("image/") && meta.thumb.is_some(),
                        "thumb": meta.thumb, "width": meta.w, "height": meta.h}))
                }
                None => None,
            };
            if !convs.contains_key(&r.conversation_id) {
                let info = db
                    .conn
                    .query_row("SELECT kind, title, tags FROM conversations WHERE id=?1", [&r.conversation_id], |x| {
                        Ok((x.get::<_, String>(0)?, x.get::<_, String>(1)?, x.get::<_, String>(2)?))
                    })
                    .optional()?
                    .unwrap_or_default();
                let channel = (info.0 == "channel").then(|| if info.1.is_empty() { "Канал".to_string() } else { info.1 });
                convs.insert(r.conversation_id.clone(), (channel, serde_json::from_str(&info.2).unwrap_or(json!({}))));
            }
            let (channel, tags) = &convs[&r.conversation_id];
            if let Some(p) = &r.poll {
                polls.push((out.len(), r.id.clone(), serde_json::from_str(p).unwrap_or(json!({}))));
            }
            let sender_name = match channel {
                Some(t) => t.clone(),
                None if r.outgoing => "Вы".to_string(),
                None => r.sender_name,
            };
            out.push(json!({
                "local_id": r.local_id,
                "id": r.id,
                "conversation_id": r.conversation_id,
                "sender_user_id": r.sender,
                "sender_name": sender_name,
                "sender_tag": if channel.is_some() { json!("") } else { tags.get(&r.sender).cloned().unwrap_or(json!("")) },
                "channel": channel.is_some(),
                "spans": if r.kind == "text" || r.kind == "file" { json!(crate::markdown::spans(&r.body)) } else { json!([]) },
                "emoji_only": if r.kind == "text" { crate::social::emoji_count(&r.body).filter(|n| *n <= 3).unwrap_or(0) } else { 0 },
                "reactions": reactions.next().unwrap_or(json!([])),
                "outgoing": r.outgoing,
                "kind": r.kind,
                "text": if r.kind == "undecryptable" { "Не удалось расшифровать сообщение".to_string() } else { r.body },
                "reply_to": reply,
                "file": file,
                "status": r.status,
                "fwd": r.fwd.map(|f| serde_json::from_str::<Value>(&f).unwrap_or(Value::Null)),
                "error": r.error.filter(|_| r.status == "failed"),
                "sent_at": r.sent_at,
                "delivered_count": r.delivered,
                "read_count": r.read,
            }));
        }
        drop(st);
        drop(db);
        for (i, id, poll) in polls {
            out[i]["poll"] = self.poll_json(&id, &poll, &me)?;
        }
        Ok(out)
    }

    pub fn message_json(&self, id: &str) -> Result<Value> {
        self.query_messages("m.id = ?1", &[&id])?.into_iter().next().ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))
    }

    /// Newest-first page from the database, returned in chronological order.
    pub fn list_messages(&self, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?;
        let before = p["before"].as_i64().unwrap_or(i64::MAX);
        let limit = p["limit"].as_i64().unwrap_or(50).clamp(1, 200);
        let mut v = self.query_messages("m.conversation_id = ?1 AND m.local_id < ?2 ORDER BY m.local_id DESC LIMIT ?3", &[&conv, &before, &limit])?;
        v.reverse();
        Ok(Value::Array(v))
    }

    /// Case-insensitive search over locally stored text (server search is impossible with E2EE).
    pub fn search_messages(&self, p: &Value) -> Result<Value> {
        let q = s(p, "query")?.trim().to_lowercase();
        if q.is_empty() {
            return Ok(Value::Array(vec![]));
        }
        let ids: Vec<String> = {
            let db = self.db();
            let conv = p["conversation_id"].as_str();
            let mut st = db.conn.prepare(
                "SELECT id, body FROM messages WHERE kind IN ('text','file','poll') AND (?1 IS NULL OR conversation_id=?1) ORDER BY local_id DESC LIMIT 5000",
            )?;
            let rows = st.query_map([conv], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
            let mut ids = Vec::new();
            for row in rows {
                let (id, body) = row?;
                if body.to_lowercase().contains(&q) {
                    ids.push(id);
                    if ids.len() >= 200 {
                        break;
                    }
                }
            }
            ids
        };
        let mut out = Vec::new();
        for id in ids {
            out.push(self.message_json(&id)?);
        }
        Ok(Value::Array(out))
    }
}

struct Row {
    local_id: i64,
    id: String,
    conversation_id: String,
    sender: String,
    outgoing: bool,
    kind: String,
    body: String,
    reply_to: Option<String>,
    file: Option<String>,
    status: String,
    error: Option<String>,
    sent_at: i64,
    sender_name: String,
    poll: Option<String>,
    fwd: Option<String>,
    delivered: i64,
    read: i64,
}
