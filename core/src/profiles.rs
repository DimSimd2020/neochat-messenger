//! Public profile views and the author's private, account-encrypted notes.
use std::path::Path;

use rusqlite::OptionalExtension;
use serde_json::{Value, json};
use uuid::Uuid;

use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::store::{kv_get, kv_get_str, kv_set};

/// '#rrggbb' in lower case, or an empty string for anything else.
pub fn clean_color(c: &str) -> String {
    let c = c.trim();
    let ok = c.len() == 7 && c.starts_with('#') && c[1..].bytes().all(|b| b.is_ascii_hexdigit());
    if ok { c.to_ascii_lowercase() } else { String::new() }
}

/// Up to three '#rrggbb' joined by commas; one bad colour drops the whole header.
pub fn clean_header(h: &str) -> String {
    let parts: Vec<String> = h.split(',').map(clean_color).collect();
    if h.trim().is_empty() || parts.len() > 3 || parts.iter().any(String::is_empty) { String::new() } else { parts.join(",") }
}

/// The colour of a person's name as they chose it; mine comes from my own profile.
pub fn name_color_in(conn: &rusqlite::Connection, user_id: &str) -> String {
    let theirs: Option<String> = conn.query_row("SELECT name_color FROM contacts WHERE user_id=?1", [user_id], |r| r.get(0)).optional().ok().flatten();
    theirs.unwrap_or_else(|| {
        if kv_get_str(conn, "user_id").ok().flatten().as_deref() == Some(user_id) { kv_get_str(conn, "name_color").ok().flatten().unwrap_or_default() } else { String::new() }
    })
}

impl Inner {
    pub fn user_profile(&self, p: &Value) -> Result<Value> {
        let (me, _) = self.me()?;
        let uid = p["user_id"].as_str().unwrap_or(&me);
        let own = uid == me;
        let mut profile = if own { self.status()? } else { self.ensure_user(uid)?; self.contact_json(uid)? };
        profile["user_id"] = json!(uid);
        profile["me"] = json!(own);
        profile["note"] = json!(kv_get_str(&self.db().conn, &format!("note:{uid}"))?.unwrap_or_default());
        if !own {
            let info = self.contact_info(&json!({"user_id": uid}))?;
            profile["safety_number"] = info["safety_number"].clone();
        }
        let (direct, groups) = {
            let db = self.db();
            let direct: Option<String> = db.conn.query_row("SELECT id FROM conversations WHERE kind='direct' AND peer_user_id=?1 LIMIT 1", [uid], |r| r.get(0)).optional()?;
            let mut st = db.conn.prepare("SELECT id FROM conversations WHERE kind='group' AND left_group=0 AND archived=0 AND EXISTS(SELECT 1 FROM json_each(members) WHERE value=?1) AND EXISTS(SELECT 1 FROM json_each(members) WHERE value=?2) ORDER BY last_activity DESC LIMIT 200")?;
            let groups: Vec<String> = st.query_map([uid, &me], |r| r.get(0))?.collect::<std::result::Result<_,_>>()?;
            (direct, groups)
        };
        profile["conversation_id"] = json!(direct);
        profile["groups"] = json!(groups.iter().filter_map(|id| self.conversation_json(id).ok()).collect::<Vec<_>>());
        Ok(profile)
    }

    pub fn set_profile_note(&self, p: &Value) -> Result<Value> {
        let uid = s(p, "user_id")?;
        if uid == self.me()?.0 || Uuid::parse_str(uid).is_err() { return Err(Error::Invalid("Выберите собеседника".into())); }
        self.ensure_user(uid)?;
        let note = s(p, "note")?.trim();
        if note.chars().count() > 100 { return Err(Error::Invalid("Заметка: до 100 символов".into())); }
        let key = format!("note:{uid}");
        kv_set(&self.db().conn, &key, note.as_bytes())?;
        self.touch(&key);
        self.emit(json!({"type": "contacts_changed"}));
        Ok(Value::Null)
    }

    /// Pages only the local direct conversation; media never goes to a server index.
    pub fn profile_items(&self, p: &Value) -> Result<Value> {
        let uid = s(p, "user_id")?;
        let direct: Option<String> = self.db().conn.query_row("SELECT id FROM conversations WHERE kind='direct' AND peer_user_id=?1 LIMIT 1", [uid], |r| r.get(0)).optional()?;
        let Some(conv) = direct else { return Ok(json!([])); };
        let filter = match p["kind"].as_str().unwrap_or("media") {
            "media" => "m.file IS NOT NULL AND json_extract(m.file,'$.mime') LIKE 'image/%'",
            "files" => "m.file IS NOT NULL AND json_extract(m.file,'$.mime') NOT LIKE 'image/%'",
            "links" => "(m.body LIKE '%https://%' OR m.body LIKE '%http://%')",
            _ => return Err(Error::Invalid("Неизвестный раздел профиля".into())),
        };
        let before = p["before"].as_i64().unwrap_or(i64::MAX);
        let limit = p["limit"].as_i64().unwrap_or(48).clamp(1, 100);
        Ok(json!(self.query_messages(&format!("m.conversation_id=?1 AND m.local_id<?2 AND ({filter}) ORDER BY m.local_id DESC LIMIT ?3"), &[&conv, &before, &limit])?))
    }

    pub fn profile_qr(&self, p: &Value) -> Result<Value> {
        let (me, _) = self.me()?;
        let uid = p["user_id"].as_str().unwrap_or(&me);
        let (name, key) = if uid == me {
            let db = self.db();
            (kv_get_str(&db.conn, "username")?.ok_or(Error::NotLoggedIn)?, kv_get(&db.conn, "account_key")?.ok_or(Error::NotLoggedIn)?)
        } else {
            self.ensure_user(uid)?;
            self.db().conn.query_row("SELECT username, account_key FROM contacts WHERE user_id=?1", [uid], |r| Ok((r.get::<_,String>(0)?,r.get::<_,Vec<u8>>(1)?)))?
        };
        let link = format!("neochat://u/{name}?k={}", crate::social::fingerprint(&key));
        Ok(json!({"link":link,"username":name,"qr_rows":crate::engine::qr_rows_with(&link,qrcode::EcLevel::Q)?}))
    }

    pub fn profile_photos(&self, p: &Value) -> Result<Value> {
        let (me, _) = self.me()?;
        let uid = p["user_id"].as_str().unwrap_or(&me);
        let uid = Uuid::parse_str(uid).map_err(|_| Error::Invalid("Некорректный ID профиля".into()))?;
        let result = self.authed("GET", &format!("/v1/users/{uid}/photos"), None)?;
        Ok(json!(result["photos"].as_array().into_iter().flatten().filter_map(|id| id.as_str()).filter(|id| Uuid::parse_str(id).is_ok()).take(20).map(|id| json!({"id":id,"path":self.data_dir.join("avatars").join(format!("{id}-full.jpg")).to_string_lossy()})).collect::<Vec<_>>()))
    }

    pub fn profile_photo(&self, p: &Value) -> Result<Value> {
        let uid = Uuid::parse_str(s(p,"user_id")?).map_err(|_| Error::Invalid("Некорректный ID профиля".into()))?;
        let id = Uuid::parse_str(s(p,"photo_id")?).map_err(|_| Error::Invalid("Некорректный ID фото".into()))?;
        let path = self.data_dir.join("avatars").join(format!("{id}-full.jpg"));
        if !path.is_file() {
            let tmp = self.data_dir.join("tmp").join(format!("profile-{}.jpg",Uuid::new_v4()));
            let result = self.api()?.download(&format!("/v1/users/{uid}/photos/{id}"), &self.access_token()?, &tmp, 8<<20)
                .and_then(|_| { std::fs::rename(&tmp,&path)?; Ok(()) });
            let _ = std::fs::remove_file(tmp);
            result?;
        }
        Ok(json!({"path":path.to_string_lossy()}))
    }

    pub fn set_profile_photo(&self, p: &Value) -> Result<Value> {
        let path = Path::new(s(p,"path")?);
        if std::fs::metadata(path)?.len() > 8<<20 { return Err(Error::Invalid("Фото: до 8 МБ".into())); }
        self.api()?.upload("/v1/accounts/current/photos", &self.access_token()?, path)?;
        self.refresh_profile()?;
        Ok(Value::Null)
    }

    pub fn delete_profile_photo(&self, p: &Value) -> Result<Value> {
        let id = Uuid::parse_str(s(p,"photo_id")?).map_err(|_| Error::Invalid("Некорректный ID фото".into()))?;
        self.authed("DELETE", &format!("/v1/accounts/current/photos/{id}"), None)?;
        let _ = std::fs::remove_file(self.avatar_path(&id.to_string()));
        let _ = std::fs::remove_file(self.data_dir.join("avatars").join(format!("{id}-full.jpg")));
        self.refresh_profile()?;
        Ok(Value::Null)
    }
}
