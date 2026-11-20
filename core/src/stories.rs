use std::collections::BTreeSet;
use std::path::Path;

use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use uuid::Uuid;

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::files;
use crate::keys::b64;
use crate::outbox::{FileMeta, Payload};
use crate::store::now_ms;

const HOUR: i64 = 3_600_000;
const PROFILE_TTL: i64 = 30 * 24 * HOUR;

#[derive(Serialize, Deserialize, Default)]
struct Style {
    #[serde(default)]
    text: String,
    #[serde(default = "middle")]
    text_y: u16,
    #[serde(default = "middle")]
    text_x: u16,
    #[serde(default = "normal_scale")]
    text_scale: u16,
    #[serde(default)]
    text_angle: i16,
    #[serde(default)]
    color: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    text_color: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    text_align: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    text_font: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    text_background: Option<u8>,
    #[serde(default)]
    lines: Vec<Vec<[u16; 2]>>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    brushes: Vec<Brush>,
    #[serde(default)]
    brightness: i16,
    #[serde(default)]
    contrast: i16,
    #[serde(default)]
    saturation: i16,
    #[serde(default)]
    muted: bool,
    #[serde(default)]
    stickers: Vec<Sticker>,
}

fn middle() -> u16 { 500 }
fn normal_scale() -> u16 { 1000 }
fn yes() -> bool { true }

#[derive(Serialize, Deserialize)]
struct Brush {
    color: u32,
    kind: u8,
}

#[derive(Serialize, Deserialize)]
struct Sticker {
    emoji: String,
    x: u16,
    y: u16,
    #[serde(default = "normal_scale")]
    scale: u16,
    #[serde(default)]
    angle: i16,
}

fn valid_transform(scale: u16, angle: i16) -> bool {
    (400..=2500).contains(&scale) && (-180..=180).contains(&angle)
}

impl Style {
    fn valid(&self) -> bool {
        self.text.chars().count() <= 100 && self.text_y <= 1_000 && self.text_x <= 1_000 && self.color <= 0xffffff
            && valid_transform(self.text_scale, self.text_angle)
            && self.text_color.is_none_or(|color| color <= 0xffffff)
            && [self.text_align, self.text_font, self.text_background].into_iter().flatten().all(|value| value <= 2)
            && self.lines.len() <= 24 && self.lines.iter().map(Vec::len).sum::<usize>() <= 400
            && self.brushes.len() <= self.lines.len()
            && self.brushes.iter().all(|brush| brush.color <= 0xffffff && brush.kind <= 2)
            && self.lines.iter().flatten().all(|[x, y]| *x <= 1_000 && *y <= 1_000)
            && self.stickers.len() <= 8
            && self.stickers.iter().all(|s| s.x <= 1_000 && s.y <= 1_000 && valid_transform(s.scale, s.angle)
                && crate::social::valid_reaction(&s.emoji))
            && [self.brightness, self.contrast, self.saturation].iter().all(|v| (-100..=100).contains(v))
    }
}

#[derive(Serialize, Deserialize)]
struct Options {
    #[serde(default = "yes")]
    screenshots: bool,
    #[serde(default)]
    profile: bool,
    #[serde(default)]
    audience: String,
    #[serde(default)]
    album: String,
}

impl Default for Options {
    fn default() -> Self { Self { screenshots: true, profile: false, audience: String::new(), album: String::new() } }
}

impl Options {
    fn valid(&self) -> bool {
        matches!(self.audience.as_str(), "" | "all" | "contacts" | "friends" | "selected")
            && self.album.chars().count() <= 48
    }
}

#[derive(Serialize, Deserialize)]
struct Revision {
    caption: String,
    style: Style,
    options: Options,
    #[serde(default)]
    deleted: bool,
}

impl Revision {
    fn valid(&self) -> bool { self.caption.chars().count() <= 240 && self.style.valid() && self.options.valid() }
}

fn hours(v: &Value) -> Option<i64> {
    v.as_i64().filter(|h| matches!(h, 6 | 12 | 24 | 48))
}

fn expires_at(p: &Payload) -> i64 {
    p.group.as_ref().and_then(|g| hours(&g["hours"]))
        .map(|h| p.ts.saturating_add(h * HOUR)).unwrap_or(0)
}

pub(crate) fn publishable(p: &Payload, now: i64) -> bool {
    expires_at(p) > now || (p.group.as_ref().is_some_and(|g| g["options"]["profile"] == true)
        && p.ts.saturating_add(PROFILE_TTL) > now)
}

fn insert(conn: &Connection, id: &str, p: &Payload, revision: &Revision, file: &FileMeta, sent_at: i64, expires: i64) -> Result<bool> {
    Ok(conn.execute(
        "INSERT OR IGNORE INTO stories(id, conversation_id, sender_user_id, sent_at, expires_at, caption, file, style, options)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
        params![id, p.cid, p.sender, sent_at, expires, revision.caption,
            serde_json::to_string(file)?, serde_json::to_string(&revision.style)?, serde_json::to_string(&revision.options)?],
    )? > 0)
}

fn direct_route(conn: &Connection, p: &Payload) -> Result<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM conversations, json_each(conversations.members) AS member
         WHERE conversations.id=?1 AND kind='direct' AND member.value=?2)", params![p.cid, p.sender], |r| r.get(0),
    )?)
}

fn remember_route(conn: &Connection, id: &str, route: &str) -> Result<()> {
    conn.execute("INSERT OR IGNORE INTO story_routes(story_id, conversation_id) VALUES (?1,?2)", params![id, route])?;
    Ok(())
}

fn stamp(p: &Payload, now: i64) -> String {
    format!("{:019}-{}", p.ts.clamp(0, now + 300_000), p.mid)
}

pub(crate) fn receive(conn: &Connection, p: &Payload, now: i64) -> Result<bool> {
    let Some(group) = &p.group else { return Ok(false) };
    let Some(id) = group["id"].as_str().filter(|id| Uuid::parse_str(id).is_ok()) else { return Ok(false) };
    let Some(file) = p.file.as_ref().filter(|f| Uuid::parse_str(&f.id).is_ok()
        && (1..=files::MAX_PLAINTEXT).contains(&f.size) && (17..=(51 << 20)).contains(&f.ciphertext_size)
        && (f.mime.starts_with("image/") || (f.mime.starts_with("video/") && valid_duration(f.duration)))) else {
        return Ok(false);
    };
    if hours(&group["hours"]).is_none() { return Ok(false) }
    let Ok(style) = serde_json::from_value::<Style>(group["style"].clone()) else { return Ok(false) };
    let options = if group["options"].is_null() { Options::default() }
        else if let Ok(options) = serde_json::from_value::<Options>(group["options"].clone()) { options }
        else { return Ok(false) };
    let sent_at = p.ts.min(now + 300_000);
    let expires = expires_at(p).min(sent_at.saturating_add(hours(&group["hours"]).unwrap_or(0) * HOUR));
    if sent_at.saturating_add(PROFILE_TTL) <= now || (!options.profile && expires <= now)
        || !style.valid() || !options.valid() || !direct_route(conn, p)? {
        return Ok(false);
    }
    let id = format!("story-{}-{id}", p.sender);
    let pending: Option<String> = conn.query_row("SELECT revision FROM story_changes WHERE story_id=?1 AND conversation_id=?2",
        params![id, p.cid], |r| r.get(0)).optional()?;
    let revision = match pending {
        Some(revision) => serde_json::from_str::<Revision>(&revision)?,
        None => Revision {
            caption: p.text.as_deref().unwrap_or("").chars().take(240).collect(),
            style, options, deleted: false,
        },
    };
    if revision.deleted || (!revision.options.profile && expires <= now) { return Ok(false) }
    let mut meta = file.sanitized();
    meta.thumb = None;
    meta.once = None;
    meta.spoiler = None;
    let inserted = insert(conn, &id, p, &revision, &meta, sent_at, expires)?;
    remember_route(conn, &id, &p.cid)?;
    Ok(inserted)
}

fn store_change(conn: &Connection, p: &Payload, now: i64, id: &str, revision: &Revision) -> Result<bool> {
    let version = stamp(p, now);
    let modified = conn.execute(
        "INSERT INTO story_changes(story_id,conversation_id,stamp,at,revision,deleted) VALUES (?1,?2,?3,?4,?5,?6)
         ON CONFLICT(story_id,conversation_id) DO UPDATE SET stamp=excluded.stamp,at=excluded.at,revision=excluded.revision,deleted=excluded.deleted
         WHERE story_changes.deleted=0 AND (excluded.deleted=1 OR excluded.stamp>story_changes.stamp)",
        params![id, p.cid, version, now, serde_json::to_string(revision)?, revision.deleted],
    )? > 0;
    if !modified { return Ok(false); }
    Ok(conn.execute(
        "UPDATE stories SET caption=?3,style=?4,options=?5 WHERE id=?1 AND
         (conversation_id=?2 OR EXISTS(SELECT 1 FROM story_routes WHERE story_id=?1 AND conversation_id=?2)) AND
         (?7 OR NOT EXISTS(SELECT 1 FROM story_changes WHERE story_id=?1 AND (deleted=1 OR stamp>?6)))",
        params![id, p.cid, revision.caption, serde_json::to_string(&revision.style)?, serde_json::to_string(&revision.options)?, version, revision.deleted],
    )? > 0)
}

pub(crate) fn receive_change(conn: &Connection, p: &Payload, now: i64) -> Result<bool> {
    let Some(group) = &p.group else { return Ok(false) };
    let Some(uuid) = group["id"].as_str().and_then(|s| Uuid::parse_str(s).ok()) else { return Ok(false) };
    let Ok(revision) = serde_json::from_value::<Revision>(group["revision"].clone()) else { return Ok(false) };
    if p.ts.saturating_add(PROFILE_TTL) <= now || !revision.valid() || !direct_route(conn, p)? {
        return Ok(false);
    }
    store_change(conn, p, now, &format!("story-{}-{uuid}", p.sender), &revision)
}

fn save_feedback(conn: &Connection, id: &str, p: &Payload, now: i64, viewed: bool, reaction: Option<&str>) -> Result<bool> {
    let version = stamp(p, now);
    Ok(conn.execute(
        "INSERT INTO story_feedback(story_id,user_id,viewed,reaction,stamp) VALUES (?1,?2,?3,NULLIF(?4,''),?5)
         ON CONFLICT(story_id,user_id) DO UPDATE SET viewed=max(viewed,excluded.viewed),
         reaction=CASE WHEN ?4 IS NOT NULL AND excluded.stamp>stamp THEN excluded.reaction ELSE reaction END,
         stamp=CASE WHEN ?4 IS NOT NULL AND excluded.stamp>stamp THEN excluded.stamp ELSE stamp END
         WHERE (excluded.viewed=1 AND viewed=0) OR (?4 IS NOT NULL AND excluded.stamp>stamp)",
        params![id, p.sender, viewed, reaction, if reaction.is_some() { version.as_str() } else { "" }],
    )? > 0)
}

pub(crate) fn receive_feedback(conn: &Connection, p: &Payload, me: &str, now: i64) -> Result<bool> {
    let Some(group) = &p.group else { return Ok(false) };
    let Some(id) = group["story_id"].as_str() else { return Ok(false) };
    let reaction = group["reaction"].as_str();
    let viewed = group["viewed"] == true;
    if (!viewed && reaction.is_none()) || reaction.is_some_and(|e| !e.is_empty() && !crate::social::valid_reaction(e))
        || !direct_route(conn, p)? { return Ok(false) }
    let allowed: bool = conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM stories s WHERE s.id=?1 AND s.sender_user_id<>?2
         AND (s.sender_user_id=?4 OR ?2=?4)
         AND (s.conversation_id=?3 OR EXISTS(SELECT 1 FROM story_routes WHERE story_id=s.id AND conversation_id=?3))
         AND NOT EXISTS(SELECT 1 FROM story_changes WHERE story_id=s.id AND deleted=1)
         AND (expires_at>?5 OR (json_extract(options,'$.profile')=1 AND sent_at>?6)))",
        params![id, p.sender, p.cid, me, now, now - PROFILE_TTL], |r| r.get(0),
    )?;
    if !allowed { return Ok(false) }
    save_feedback(conn, id, p, now, viewed, reaction)
}

const AVAILABLE: &str = "(s.expires_at>?2 OR (coalesce(json_extract(s.options,'$.profile'),0)=1 AND s.sent_at>?2-30*86400000))
    AND NOT EXISTS(SELECT 1 FROM story_changes WHERE story_id=s.id AND deleted=1)";

fn unavailable() -> Error { Error::Invalid("История больше недоступна".into()) }

impl Inner {
    fn story_owner(&self, id: &str) -> Result<(String, String, String)> {
        self.db().conn.query_row(
            &format!("SELECT sender_user_id, conversation_id, caption FROM stories s WHERE id=?1 AND {AVAILABLE}"),
            params![id, now_ms()], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        ).optional()?.ok_or_else(unavailable)
    }

    fn story_routes(&self, id: &str) -> Result<Vec<String>> {
        let db = self.db();
        let mut q = db.conn.prepare(
            "SELECT c.id FROM conversations c WHERE c.kind='direct' AND (c.id IN
             (SELECT conversation_id FROM story_routes WHERE story_id=?1 UNION SELECT conversation_id FROM stories WHERE id=?1))
             ORDER BY c.id",
        )?;
        Ok(q.query_map([id], |r| r.get(0))?.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub(crate) fn story_audience(&self, p: &Value) -> Result<Value> {
        let id = s(p, "story_id")?;
        let (owner, _, _) = self.story_owner(id)?;
        if owner != self.me()?.0 { return Err(unavailable()); }
        let names: Vec<Value> = self.story_routes(id)?.iter().filter_map(|route| self.conversation_json(route).ok())
            .map(|c| c["title"].clone()).collect();
        Ok(json!(names))
    }

    pub(crate) fn manage_story(&self, p: &Value) -> Result<Value> {
        let id = s(p, "story_id")?;
        let (owner, _, caption) = self.story_owner(id)?;
        if owner != self.me()?.0 { return Err(unavailable()); }
        let uuid = id.strip_prefix(&format!("story-{owner}-")).and_then(|id| Uuid::parse_str(id).ok()).ok_or_else(unavailable)?;
        let (style, options, previous): (String, String, i64) = self.db().conn.query_row(
            "SELECT style,options,coalesce((SELECT max(at) FROM story_changes WHERE story_id=?1),0) FROM stories WHERE id=?1",
            [id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        let mut options: Options = serde_json::from_str(&options)?;
        if let Some(profile) = p["profile"].as_bool() { options.profile = profile; }
        if let Some(album) = p["album"].as_str() {
            options.album = album.trim().to_owned();
            if !options.album.is_empty() { options.profile = true; }
        }
        let style = if p["style"].is_null() { serde_json::from_str(&style)? }
            else { serde_json::from_value(p["style"].clone()).map_err(|_| unavailable())? };
        let revision = Revision {
            caption: p["caption"].as_str().map(str::trim).map(str::to_owned).unwrap_or(caption),
            style, options, deleted: p["delete"] == true,
        };
        if !revision.valid() { return Err(Error::Invalid("Некорректные настройки истории".into())); }
        let now = now_ms();
        let mut outgoing = Vec::new();
        for route in self.story_routes(id)? {
            let mut packet = self.new_payload(&route, "story_change")?;
            packet.ts = now.max(previous.saturating_add(1).min(now + 300_000));
            packet.group = Some(json!({"id": uuid.to_string(), "revision": revision}));
            outgoing.push(packet);
        }
        if outgoing.is_empty() { return Err(unavailable()); }
        {
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            for p in &outgoing {
                store_change(&tx, p, p.ts, id, &revision)?;
                Self::queue_envelope(&tx, p, false, None)?;
            }
            tx.commit()?;
        }
        self.emit(json!({"type": "stories_changed"}));
        self.wake();
        Ok(json!({"options": revision.options}))
    }

    pub(crate) fn story_feedback(&self, p: &Value) -> Result<Value> {
        let id = s(p, "story_id")?;
        let (owner, route, _) = self.story_owner(id)?;
        if owner == self.me()?.0 { return Err(unavailable()); }
        let reaction = p["reaction"].as_str();
        let viewed = p["viewed"] == true;
        if reaction.is_some_and(|e| !e.is_empty() && !crate::social::valid_reaction(e)) || (!viewed && reaction.is_none()) {
            return Err(Error::Invalid("Некорректная реакция".into()));
        }
        let mut packet = self.new_payload(&route, "story_feedback")?;
        packet.group = Some(json!({"story_id": id, "viewed": viewed, "reaction": reaction}));
        let mut db = self.db();
        let tx = db.conn.transaction()?;
        if receive_feedback(&tx, &packet, &packet.sender, now_ms())? {
            Self::queue_envelope(&tx, &packet, false, None)?;
            tx.commit()?;
            drop(db);
            self.emit(json!({"type": "stories_changed"}));
            self.wake();
        }
        Ok(json!({"reaction": reaction}))
    }

    pub(crate) fn comment_story(&self, p: &Value) -> Result<Value> {
        let (owner, route, caption) = self.story_owner(s(p, "story_id")?)?;
        if owner == self.me()?.0 { return Err(unavailable()); }
        let text = s(p, "text")?.trim();
        if text.is_empty() || text.chars().count() > 500 {
            return Err(Error::Invalid("Комментарий должен содержать от 1 до 500 символов".into()));
        }
        let title = if caption.is_empty() { "Комментарий к истории".to_owned() }
            else { format!("Комментарий к истории «{}»", caption.chars().take(80).collect::<String>()) };
        self.send_text(&json!({"conversation_id": route, "text": format!("{title}\n{text}")}))
    }

    pub(crate) fn publish_story(&self, p: &Value) -> Result<Value> {
        let invalid = |message: &str| Error::Invalid(message.into());
        let hours = hours(&p["hours"]).ok_or_else(|| invalid("Выберите срок показа истории"))?;
        let caption = p["caption"].as_str().unwrap_or("").trim();
        if caption.chars().count() > 240 {
            return Err(invalid("Подпись истории должна содержать не более 240 символов"));
        }
        let style: Style = serde_json::from_value(p["style"].clone()).map_err(|_| invalid("Некорректное оформление истории"))?;
        let options = if p["options"].is_null() { Options::default() }
            else { serde_json::from_value(p["options"].clone()).map_err(|_| invalid("Некорректные настройки истории"))? };
        let revision = Revision { caption: caption.to_owned(), style, options, deleted: false };
        if !revision.valid() { return Err(invalid("Некорректное оформление истории")); }
        let targets = p["conversation_ids"].as_array().filter(|a| !a.is_empty())
            .ok_or_else(|| invalid("В выбранной аудитории нет доступных собеседников"))?;
        let mime = s(p, "mime")?;
        if !mime.starts_with("image/") && !mime.starts_with("video/") {
            return Err(invalid("История должна быть фото или видео"));
        }
        let duration = p["duration"].as_u64().filter(|_| mime.starts_with("video/"));
        if mime.starts_with("video/") && !valid_duration(duration) {
            return Err(invalid("Видео для истории должно длиться не больше минуты"));
        }
        let mut payloads = Vec::with_capacity(targets.len());
        let mut unique = BTreeSet::new();
        let uuid = Uuid::new_v4().to_string();
        let (me, _) = self.me()?;
        let id = format!("story-{me}-{uuid}");
        let now = now_ms();
        let spec = json!({"id": uuid, "hours": hours, "style": revision.style, "options": revision.options});
        for target in targets {
            let conv = target.as_str().ok_or_else(|| invalid("Некорректный список чатов"))?;
            if !unique.insert(conv) { continue; }
            let c = self.conversation_json(conv)?;
            if c["kind"] != "direct" || c["can_media"] == false {
                return Err(invalid("Истории можно отправить только в личные чаты"));
            }
            self.check_can_send(conv)?;
            let mut packet = self.new_payload(conv, "story")?;
            packet.ts = now;
            packet.group = Some(spec.clone());
            packet.text = Some(caption.to_string());
            payloads.push(packet);
        }
        let path = Path::new(s(p, "path")?);
        let cached = self.data_dir.join("files").join(format!("{id}.enc"));
        let result = (|| -> Result<()> {
            let encrypted = files::encrypt_file(path, &cached)?;
            if encrypted.plaintext_size == 0 { return Err(invalid("Медиафайл пуст")); }
            let file = FileMeta {
                key: b64(&encrypted.key),
                nonce: b64(&encrypted.nonce),
                sha256: b64(&encrypted.ciphertext_sha256),
                size: encrypted.plaintext_size,
                ciphertext_size: encrypted.ciphertext_size,
                name: files::safe_name(s(p, "name")?),
                mime: mime.into(),
                duration,
                ..FileMeta::default()
            };
            let mut db = self.db();
            let tx = db.conn.transaction()?;
            insert(&tx, &id, &payloads[0], &revision, &file, now, now + hours * HOUR)?;
            for packet in &mut payloads {
                remember_route(&tx, &id, &packet.cid)?;
                packet.file = Some(file.clone());
                Self::queue_envelope(&tx, packet, false, Some(&cached))?;
            }
            tx.commit()?;
            Ok(())
        })();
        if let Err(error) = result {
            let _ = std::fs::remove_file(cached);
            return Err(error);
        }
        self.emit(json!({"type": "stories_changed"}));
        self.wake();
        Ok(json!({"id": id}))
    }

    pub(crate) fn list_stories(&self, p: &Value) -> Result<Value> {
        let (me, _) = self.me()?;
        self.sweep_stories()?;
        let profile = p["profile_user_id"].as_str();
        let rows = {
            let db = self.db();
            let mut q = db.conn.prepare(
                &format!("SELECT s.id, s.conversation_id, s.sender_user_id, s.sent_at, s.expires_at, s.caption, s.file, s.style, s.options,
                            (SELECT count(*) FROM story_feedback WHERE story_id=s.id AND viewed=1),
                            (SELECT count(*) FROM story_feedback WHERE story_id=s.id AND reaction IS NOT NULL),
                            (SELECT reaction FROM story_feedback WHERE story_id=s.id AND user_id=?3)
                 FROM stories s WHERE {AVAILABLE} AND ((?1 IS NULL AND s.expires_at>?2) OR
                       (s.sender_user_id=?1 AND json_extract(s.options,'$.profile')=1))
                 ORDER BY s.sent_at DESC, s.id LIMIT 60"),
            )?;
            q.query_map(params![profile, now_ms(), me], |r| Ok((
                r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?,
                r.get::<_, i64>(3)?, r.get::<_, i64>(4)?, r.get::<_, String>(5)?,
                r.get::<_, String>(6)?, r.get::<_, String>(7)?, r.get::<_, String>(8)?,
                r.get::<_, i64>(9)?, r.get::<_, i64>(10)?, r.get::<_, Option<String>>(11)?,
            )))?.collect::<std::result::Result<Vec<_>, _>>()?
        };
        let mut stories = Vec::with_capacity(rows.len());
        for (id, conv, author, sent_at, expires_at, caption, file, style, options, views, reactions, my_reaction) in rows {
            let mine = author == me;
            let c = if mine { Value::Null } else { self.conversation_json(&conv).unwrap_or_default() };
            let file: FileMeta = serde_json::from_str(&file)?;
            let style: Value = serde_json::from_str(&style)?;
            let options: Options = serde_json::from_str(&options)?;
            let display_until = if profile.is_some() && options.profile { sent_at + PROFILE_TTL } else { expires_at };
            stories.push(json!({
                "id": id, "author_id": author, "mine": mine, "sent_at": sent_at, "expires_at": expires_at,
                "display_until": display_until, "options": options, "views": views, "reactions": reactions, "my_reaction": my_reaction,
                "author": if mine { "Моя история" } else { c["title"].as_str().unwrap_or("История") },
                "avatar_path": c["avatar_path"], "caption": caption, "mime": file.mime, "duration": file.duration, "style": style,
            }));
        }
        Ok(json!(stories))
    }

    pub(crate) fn open_story(&self, p: &Value) -> Result<Value> {
        let id = s(p, "story_id")?;
        let _guard = self.story_download_lock.lock().unwrap_or_else(|e| e.into_inner());
        let row: Option<String> = self.db().conn.query_row(
            &format!("SELECT file FROM stories s WHERE s.id=?1 AND {AVAILABLE}"), params![id, now_ms()],
            |r| r.get(0),
        ).optional()?;
        let file = row.ok_or_else(unavailable)?;
        let meta: FileMeta = serde_json::from_str(&file)?;
        let result = self.open_media(id, &meta, false)?;
        let still_available: bool = self.db().conn.query_row(
            &format!("SELECT EXISTS(SELECT 1 FROM stories s WHERE s.id=?1 AND {AVAILABLE})"), params![id, now_ms()],
            |r| r.get(0),
        )?;
        if !still_available {
            self.erase_media(id);
            return Err(unavailable());
        }
        Ok(result)
    }

    pub(crate) fn sweep_stories(&self) -> Result<()> {
        let Ok(_guard) = self.story_download_lock.try_lock() else { return Ok(()) };
        let ids = {
            let db = self.db();
            let ids = {
                let mut q = db.conn.prepare(&format!("DELETE FROM stories AS s WHERE NOT ({AVAILABLE}) RETURNING id"))?;
                q.query_map(params![rusqlite::types::Null, now_ms()], |r| r.get::<_, String>(0))?
                    .collect::<std::result::Result<Vec<_>, _>>()?
            };
            for id in &ids {
                db.conn.execute("DELETE FROM story_feedback WHERE story_id=?1", [id])?;
                db.conn.execute("DELETE FROM story_routes WHERE story_id=?1", [id])?;
            }
            db.conn.execute("DELETE FROM story_changes WHERE at<=?1 AND NOT EXISTS(SELECT 1 FROM stories WHERE id=story_id)",
                [now_ms() - PROFILE_TTL])?;
            ids
        };
        for id in ids {
            self.erase_media(&id);
        }
        Ok(())
    }
}

fn valid_duration(duration: Option<u64>) -> bool {
    duration.is_some_and(|millis| (1..=60_000).contains(&millis))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn text_and_emoji_transform_values_are_validated() {
        let mut style: Style = serde_json::from_value(json!({
            "text": "Проверка", "text_x": 470, "text_y": 530,
            "text_scale": 1830, "text_angle": 28,
            "stickers": [{"emoji": "🔥", "x": 322, "y": 299, "scale": 1420, "angle": -67}]
        })).unwrap();
        assert!(style.valid(), "valid editor transforms were rejected");
        style.text_scale = 399;
        assert!(!style.valid(), "text smaller than the minimum was accepted");
        style.text_scale = 1000;
        style.text_angle = 181;
        assert!(!style.valid(), "text rotation outside 180 degrees was accepted");
        style.text_angle = 0;
        style.stickers[0].scale = 2501;
        assert!(!style.valid(), "oversized emoji was accepted");
        style.stickers[0].scale = 1000;
        style.stickers[0].x = 1001;
        assert!(!style.valid(), "off-canvas emoji was accepted");
    }

    #[test]
    fn story_options_keep_screenshot_and_profile_preferences() {
        let default = Options::default();
        assert!(default.screenshots);
        assert!(!default.profile);
        let opts: Options = serde_json::from_value(json!({
            "screenshots": false, "profile": true, "audience": "contacts"
        })).unwrap();
        assert!(opts.valid());
        assert!(!opts.screenshots);
        assert!(opts.profile);
        let encoded = serde_json::to_value(&opts).unwrap();
        assert_eq!(encoded["screenshots"], false);
        assert_eq!(encoded["profile"], true);
    }

    #[test]
    fn expired_story_is_ignored_before_it_can_enter_the_inbox() {
        let db = Connection::open_in_memory().unwrap();
        let story = Payload {
            ts: 100,
            group: Some(json!({"id": Uuid::new_v4().to_string(), "hours": 6, "style": {}})),
            file: Some(FileMeta {
                id: Uuid::new_v4().to_string(), mime: "image/jpeg".into(), size: 1, ciphertext_size: 17,
                ..FileMeta::default()
            }),
            ..Payload::default()
        };
        assert!(!receive(&db, &story, 100 + 6 * HOUR).unwrap());
    }
}
