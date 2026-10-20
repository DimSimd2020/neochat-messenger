//! Reactions, archive and pinned chats, presence and typing hints, the local
//! app passcode and contact QR codes.

use std::sync::Arc;
use std::sync::atomic::Ordering;

use argon2::{Algorithm, Argon2, Params, Version};
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::keys::{self, b64, unb64};
use crate::store::{kv_del, kv_get, kv_get_str, kv_set, now_ms};

pub const MAX_PINNED: i64 = 3;
const TYPING_EVERY_MS: i64 = 4000;
const CONTACT_LINK: &str = "neochat://u/";

impl Inner {
    // ---- reactions ----

    /// One reaction per person and message, like Telegram; an empty emoji removes it.
    pub fn react(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let emoji = p["emoji"].as_str().unwrap_or("").trim().to_string();
        if !valid_reaction(&emoji) && !emoji.is_empty() {
            return Err(Error::Invalid("Реакция должна быть эмодзи".into()));
        }
        let conv: String = self
            .db()
            .conn
            .query_row("SELECT conversation_id FROM messages WHERE id=?1 AND kind IN ('text','file','poll')", [id], |r| r.get(0))
            .optional()?
            .ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?;
        let c = self.conversation_json(&conv)?;
        if c["left"] == true || c["archived"] == true || c["kind"] == "system" || !c["key_changed_users"].as_array().is_none_or(|a| a.is_empty()) {
            return Err(Error::Invalid("Здесь нельзя ставить реакции".into()));
        }
        let (me, _) = self.me()?;
        self.store_reaction(id, &me, &emoji)?;
        self.enqueue_control(&conv, "reaction", json!({"id": id, "emoji": emoji}))?;
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        self.message_json(id)
    }

    fn store_reaction(&self, id: &str, user: &str, emoji: &str) -> Result<()> {
        let db = self.db();
        if emoji.is_empty() {
            db.conn.execute("DELETE FROM reactions WHERE message_id=?1 AND user_id=?2", params![id, user])?;
        } else {
            db.conn.execute(
                "INSERT INTO reactions(message_id, user_id, emoji, at) VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(message_id, user_id) DO UPDATE SET emoji=excluded.emoji, at=excluded.at",
                params![id, user, emoji, now_ms()],
            )?;
        }
        Ok(())
    }

    pub fn apply_reaction(&self, conv: &str, sender: &str, g: &Value) -> Result<bool> {
        let id = g["id"].as_str().unwrap_or_default();
        let emoji = g["emoji"].as_str().unwrap_or("");
        if !emoji.is_empty() && !valid_reaction(emoji) {
            return Ok(false);
        }
        let same: bool = self.db().conn.query_row("SELECT EXISTS(SELECT 1 FROM messages WHERE id=?1 AND conversation_id=?2)", [id, conv], |r| r.get(0))?;
        if !same {
            return Ok(false);
        }
        self.store_reaction(id, sender, emoji)?;
        Ok(true)
    }

    /// Reactions of one message, in the order they first appeared.
    pub fn reactions_json(&self, conn: &rusqlite::Connection, id: &str, me: &str) -> Result<Value> {
        let mut st = conn.prepare_cached(&format!(
            "SELECT r.emoji, count(*), max(r.user_id=?2), group_concat(CASE WHEN r.user_id=?2 THEN 'Вы' ELSE COALESCE({}, '') END, ', ')
             FROM reactions r LEFT JOIN contacts c ON c.user_id=r.user_id WHERE r.message_id=?1 GROUP BY r.emoji ORDER BY min(r.at)",
            crate::engine::NAME_SQL
        ))?;
        let rows = st.query_map(params![id, me], |r| {
            Ok(json!({"emoji": r.get::<_, String>(0)?, "count": r.get::<_, i64>(1)?, "mine": r.get::<_, i64>(2)? == 1, "names": r.get::<_, Option<String>>(3)?.unwrap_or_default()}))
        })?;
        Ok(Value::Array(rows.collect::<std::result::Result<_, _>>()?))
    }

    // ---- archive and pins (this device only) ----

    pub fn set_archived(&self, p: &Value) -> Result<Value> {
        let id = s(p, "conversation_id")?;
        let on = p["archived"].as_bool().unwrap_or(true);
        // Archiving unpins: pins live only in the main list.
        self.db().conn.execute("UPDATE conversations SET in_archive=?2, pinned=CASE WHEN ?2=1 THEN 0 ELSE pinned END WHERE id=?1", params![id, on as i64])?;
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }

    pub fn set_pinned(&self, p: &Value) -> Result<Value> {
        let id = s(p, "conversation_id")?;
        let on = p["pinned"].as_bool().unwrap_or(true);
        let db = self.db();
        if on {
            let n: i64 = db.conn.query_row("SELECT count(*) FROM conversations WHERE pinned>0 AND id<>?1", [id], |r| r.get(0))?;
            if n >= MAX_PINNED {
                return Err(Error::Invalid(format!("Закрепить можно до {MAX_PINNED} чатов")));
            }
        }
        db.conn.execute("UPDATE conversations SET pinned=?2, in_archive=CASE WHEN ?2>0 THEN 0 ELSE in_archive END WHERE id=?1", params![id, if on { now_ms() } else { 0 }])?;
        drop(db);
        self.emit(json!({"type": "conversations_changed"}));
        Ok(Value::Null)
    }

    // ---- presence and typing ----

    /// Presence of up to 200 users, also kept for conversation_json. `tz_offset`
    /// (minutes east of UTC) formats "был(а) в 14:05" in local time.
    pub fn presence(&self, p: &Value) -> Result<Value> {
        if let Some(tz) = p["tz_offset"].as_i64() {
            self.tz_offset.store(tz.clamp(-14 * 60, 14 * 60) as i32, Ordering::Relaxed);
        }
        let ids: Vec<&str> = p["user_ids"].as_array().into_iter().flatten().filter_map(|v| v.as_str()).take(200).collect();
        if ids.is_empty() {
            return Ok(json!({}));
        }
        let res = self.authed("POST", "/v1/presence", Some(&json!({"user_ids": ids})))?;
        let mut out = serde_json::Map::new();
        let mut cache = self.presence.lock().unwrap_or_else(|e| e.into_inner());
        for v in res["presence"].as_array().into_iter().flatten() {
            let Some(uid) = v["user_id"].as_str() else { continue };
            let item = self.presence_item(v);
            cache.insert(uid.to_string(), item.clone());
            out.insert(uid.to_string(), item);
        }
        Ok(Value::Object(out))
    }

    fn presence_item(&self, v: &Value) -> Value {
        let status = v["status"].as_str().unwrap_or("long");
        let last = v["last_seen"].as_i64().unwrap_or(0);
        json!({"status": status, "last_seen": last, "online": status == "online",
            "text": presence_text(status, last, now_ms(), self.tz_offset.load(Ordering::Relaxed) as i64)})
    }

    /// Cached presence, re-rendered so "был(а) в 14:05" turns into "вчера" over time.
    pub fn cached_presence(&self, user_id: &str) -> Option<Value> {
        let cache = self.presence.lock().unwrap_or_else(|e| e.into_inner());
        let v = cache.get(user_id)?;
        let mut v = v.clone();
        v["text"] = json!(presence_text(v["status"].as_str().unwrap_or("long"), v["last_seen"].as_i64().unwrap_or(0), now_ms(), self.tz_offset.load(Ordering::Relaxed) as i64));
        Some(v)
    }

    /// A live hint from the WebSocket: typing is passed on, presence is refetched.
    pub fn on_live_hint(&self, msg: &Value) {
        match msg["type"].as_str() {
            Some("typing") => {
                let (Some(conv), Some(user)) = (msg["conversation_id"].as_str(), msg["user_id"].as_str()) else { return };
                let name: String = self
                    .db()
                    .conn
                    .query_row(&format!("SELECT {}, c.last_name, c.username FROM contacts c WHERE c.user_id=?1", crate::engine::NAME_SQL), [user], |r| {
                        Ok(crate::engine::display_name(&r.get::<_, String>(0)?, &r.get::<_, String>(1)?, &r.get::<_, String>(2)?))
                    })
                    .unwrap_or_default();
                self.emit(json!({"type": "typing", "conversation_id": conv, "user_id": user, "name": name}));
            }
            Some("presence") => {
                let Some(user) = msg["user_id"].as_str() else { return };
                if let Ok(v) = self.presence(&json!({"user_ids": [user]})) {
                    if let Some(item) = v.get(user) {
                        self.emit(json!({"type": "presence", "user_id": user, "presence": item}));
                    }
                }
            }
            _ => {}
        }
    }

    /// Tells the other members we are typing; at most every 4 s per chat, in the background.
    pub fn send_typing(self: &Arc<Self>, p: &Value) -> Result<Value> {
        let conv = s(p, "conversation_id")?.to_string();
        {
            let mut last = self.typing_sent.lock().unwrap_or_else(|e| e.into_inner());
            let now = now_ms();
            if now - last.get(&conv).copied().unwrap_or(0) < TYPING_EVERY_MS {
                return Ok(Value::Null);
            }
            last.insert(conv.clone(), now);
        }
        let me = self.clone();
        std::thread::spawn(move || {
            let _ = me.authed("POST", &format!("/v1/conversations/{conv}/typing"), None);
        });
        Ok(Value::Null)
    }

    pub fn set_last_seen_visible(&self, p: &Value) -> Result<Value> {
        self.authed("PUT", "/v1/accounts/current/settings", Some(&json!({"show_last_seen": p["visible"].as_bool().unwrap_or(true)})))
    }

    // ---- local passcode for the app and the archive ----

    pub fn lock_set(&self, p: &Value) -> Result<Value> {
        let code = s(p, "passcode")?;
        if code.chars().count() < 4 || code.len() > 64 {
            return Err(Error::Invalid("Код-пароль: от 4 символов".into()));
        }
        let mut salt = [0u8; 16];
        rand::fill(&mut salt);
        let hash = lock_hash(code, &salt)?;
        let db = self.db();
        kv_set(&db.conn, "lock_salt", &salt)?;
        kv_set(&db.conn, "lock_hash", &hash)?;
        kv_del(&db.conn, "lock_fails")?;
        Ok(Value::Null)
    }

    /// After 5 wrong tries every next try waits 30 s.
    pub fn lock_check(&self, p: &Value) -> Result<Value> {
        let code = s(p, "passcode")?;
        let (salt, want, fails, last) = {
            let db = self.db();
            let (Some(salt), Some(want)) = (kv_get(&db.conn, "lock_salt")?, kv_get(&db.conn, "lock_hash")?) else {
                return Ok(json!({"ok": true}));
            };
            let fails: i64 = kv_get_str(&db.conn, "lock_fails")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            let last: i64 = kv_get_str(&db.conn, "lock_failed_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            (salt, want, fails, last)
        };
        let wait = if fails >= 5 { 30_000 - (now_ms() - last) } else { 0 };
        if wait > 0 {
            return Err(Error::Invalid(format!("Слишком много попыток. Подождите {} с", (wait + 999) / 1000)));
        }
        let ok = ct_eq(&lock_hash(code, &salt)?, &want);
        let db = self.db();
        if ok {
            kv_del(&db.conn, "lock_fails")?;
        } else {
            kv_set(&db.conn, "lock_fails", (fails + 1).to_string().as_bytes())?;
            kv_set(&db.conn, "lock_failed_at", now_ms().to_string().as_bytes())?;
        }
        Ok(json!({"ok": ok}))
    }

    pub fn lock_clear(&self) -> Result<Value> {
        let db = self.db();
        kv_del(&db.conn, "lock_salt")?;
        kv_del(&db.conn, "lock_hash")?;
        kv_del(&db.conn, "lock_fails")?;
        Ok(Value::Null)
    }

    pub fn lock_status(&self) -> Result<Value> {
        Ok(json!({"enabled": kv_get(&self.db().conn, "lock_hash")?.is_some()}))
    }

    // ---- contact QR codes ----

    /// Our contact link and its QR. The fingerprint of the account key makes a
    /// scan also verify the key the server returns.
    pub fn my_qr(&self) -> Result<Value> {
        let db = self.db();
        let username = kv_get_str(&db.conn, "username")?.ok_or(Error::NotLoggedIn)?;
        let key = kv_get(&db.conn, "account_key")?.ok_or(Error::NotLoggedIn)?;
        let link = format!("{CONTACT_LINK}{username}?k={}", fingerprint(&key));
        Ok(json!({"link": link, "username": username, "qr_rows": crate::engine::qr_rows_with(&link, qrcode::EcLevel::Q)?}))
    }

    pub fn add_contact_qr(&self, p: &Value) -> Result<Value> {
        let link = s(p, "link")?.trim();
        let rest = link.strip_prefix(CONTACT_LINK).ok_or_else(|| Error::Invalid("Это не QR-код контакта NeoChat".into()))?;
        let (username, fp) = rest.split_once("?k=").ok_or_else(|| Error::Invalid("Это не QR-код контакта NeoChat".into()))?;
        let u = self.authed("POST", "/v1/contacts/resolve", Some(&json!({"username": username.to_lowercase()})))?;
        let (me, _) = self.me()?;
        if u["user_id"].as_str() == Some(me.as_str()) {
            return Err(Error::Invalid("Это ваш собственный QR-код".into()));
        }
        let key = unb64(s(&u, "account_identity_key")?)?;
        if fingerprint(&key) != fp {
            return Err(Error::Crypto("Ключ пользователя не совпадает с QR-кодом. Контакт не добавлен".into()));
        }
        let c = self.apply_user(&u, Some(("", "")))?;
        let id = s(&c, "user_id")?.to_string();
        // The scanned code matched the key: the same as comparing the safety number.
        self.db().conn.execute("UPDATE contacts SET verified=1 WHERE user_id=?1 AND key_changed=0", [&id])?;
        self.emit(json!({"type": "contacts_changed"}));
        self.contact_json(&id)
    }
}

fn fingerprint(account_key: &[u8]) -> String {
    let h = keys::sha256(account_key);
    b64(&h[..16]).replace('+', "-").replace('/', "_").trim_end_matches('=').to_string()
}

fn lock_hash(code: &str, salt: &[u8]) -> Result<Vec<u8>> {
    let params = Params::new(19 * 1024, 2, 1, Some(32)).map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    let mut out = vec![0u8; 32];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(code.as_bytes(), salt, &mut out)
        .map_err(|_| Error::Crypto("Ошибка шифрования".into()))?;
    Ok(out)
}

fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn valid_reaction(e: &str) -> bool {
    !e.is_empty() && e.len() <= 32 && emoji_count(e).is_some_and(|n| n == 1)
}

/// Number of emoji when the text is nothing but emoji (and spaces), else None.
pub fn emoji_count(text: &str) -> Option<usize> {
    let mut n = 0;
    let mut joined = false;
    for c in text.chars() {
        let u = c as u32;
        match u {
            0x20 | 0x0A => {}
            // Zero width joiner glues the next emoji to the previous one.
            0x200D => joined = true,
            // Variation selectors, skin tones, keycap, tag characters.
            0xFE0E | 0xFE0F | 0x1F3FB..=0x1F3FF | 0x20E3 | 0xE0020..=0xE007F => {}
            0x1F1E6..=0x1F1FF => {
                // Two regional indicators make one flag.
                if !joined {
                    n += 1;
                }
                joined = !joined;
                continue;
            }
            0x1F000..=0x1FAFF | 0x2600..=0x27BF | 0x2300..=0x23FF | 0x2B00..=0x2BFF | 0x2190..=0x21FF | 0x3030 | 0x303D | 0x3297 | 0x3299 | 0x00A9 | 0x00AE | 0x203C | 0x2049 | 0x2122 | 0x2139 => {
                if !joined {
                    n += 1;
                }
                joined = false;
            }
            // Digits, # and * only as keycaps.
            0x23 | 0x2A | 0x30..=0x39 => {
                if !text.contains('\u{20E3}') {
                    return None;
                }
                n += 1;
            }
            _ => return None,
        }
    }
    (n > 0).then_some(n)
}

/// "в сети", "был(а) недавно", "был(а) давно" or the local time of the last visit.
pub fn presence_text(status: &str, last_seen: i64, now: i64, tz_min: i64) -> String {
    match status {
        "online" => "в сети".into(),
        "recently" => "был(а) недавно".into(),
        "exact" if last_seen > 0 => {
            let ago = now - last_seen;
            if ago < 60_000 {
                return "был(а) только что".into();
            }
            if ago < 3_600_000 {
                return format!("был(а) {} мин назад", ago / 60_000);
            }
            let local = |t: i64| t + tz_min * 60_000;
            let day = |t: i64| local(t).div_euclid(86_400_000);
            let (h, m) = (local(last_seen).rem_euclid(86_400_000) / 3_600_000, local(last_seen).rem_euclid(3_600_000) / 60_000);
            match day(now) - day(last_seen) {
                0 => format!("был(а) в {h:02}:{m:02}"),
                1 => format!("был(а) вчера в {h:02}:{m:02}"),
                d if d < 7 => "был(а) на этой неделе".into(),
                _ => {
                    let (y, mo, d) = civil(day(last_seen));
                    format!("был(а) {d:02}.{mo:02}.{y}")
                }
            }
        }
        _ => "был(а) давно".into(),
    }
}

/// Days since 1970-01-01 to (year, month, day), proleptic Gregorian.
fn civil(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + (m <= 2) as i64, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn emoji_only() {
        assert_eq!(emoji_count("😀"), Some(1));
        assert_eq!(emoji_count("😀 👍🏽"), Some(2));
        assert_eq!(emoji_count("👨‍👩‍👧"), Some(1));
        assert_eq!(emoji_count("🇷🇺"), Some(1));
        assert_eq!(emoji_count("❤️"), Some(1));
        assert_eq!(emoji_count("hi 😀"), None);
        assert_eq!(emoji_count("1"), None);
        assert_eq!(emoji_count(""), None);
    }

    #[test]
    fn presence_texts() {
        let day = 86_400_000;
        let now = 20_000 * day + 12 * 3_600_000;
        assert_eq!(presence_text("online", 0, now, 0), "в сети");
        assert_eq!(presence_text("long", 0, now, 0), "был(а) давно");
        assert_eq!(presence_text("exact", now - 5 * 60_000, now, 0), "был(а) 5 мин назад");
        assert_eq!(presence_text("exact", now - 3 * 3_600_000, now, 180), "был(а) в 12:00");
        assert_eq!(presence_text("exact", now - day, now, 0), "был(а) вчера в 12:00");
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_000), (2024, 10, 4));
    }
}
