//! Profile photos use binary HTTP bodies and a local file cache, never JSON image data.

use std::io::Read;
use std::path::Path;

use serde_json::Value;
use uuid::Uuid;

use crate::engine::{Inner, s};
use crate::error::{Error, Result};

const MAX_AVATAR_SIZE: u64 = 48 << 10;

impl Inner {
    pub fn avatar_path(&self, id: &str) -> String {
        match Uuid::parse_str(id) {
            Ok(id) => self.data_dir.join("avatars").join(format!("{id}.jpg")).to_string_lossy().into_owned(),
            Err(_) => String::new(),
        }
    }

    pub fn cache_user_avatar(&self, user: &Value) -> Result<()> {
        let path = self.avatar_path(user["avatar_id"].as_str().unwrap_or(""));
        if path.is_empty() || Path::new(&path).is_file() { return Ok(()); }
        let uid = Uuid::parse_str(s(user, "user_id")?).map_err(|_| Error::Invalid("Некорректный ID профиля".into()))?;
        let id = Uuid::parse_str(s(user, "avatar_id")?).map_err(|_| Error::Invalid("Некорректный ID фото".into()))?;
        let tmp = self.data_dir.join("tmp").join(format!("avatar-{}.jpg", Uuid::new_v4()));
        let result = (|| {
            self.api()?.download(&format!("/v1/users/{uid}/avatar/{id}"), &self.access_token()?, &tmp, MAX_AVATAR_SIZE)?;
            let mut magic = [0; 3];
            std::fs::File::open(&tmp)?.read_exact(&mut magic)?;
            if magic != [0xff, 0xd8, 0xff] { return Err(Error::Invalid("Не удалось прочитать фото".into())); }
            if !Path::new(&path).is_file() { std::fs::rename(&tmp, &path)?; }
            Ok(())
        })();
        let _ = std::fs::remove_file(tmp);
        result
    }

    /// Photos whose download failed earlier (offline, server busy) are fetched again;
    /// otherwise they would stay missing until the next profile change.
    pub fn retry_missing_avatars(&self) {
        let users: Vec<(String, String)> = (|| {
            let db = self.db();
            let now = crate::store::now_ms();
            let last: i64 = crate::store::kv_get_str(&db.conn, "avatars_checked_at")?.and_then(|v| v.parse().ok()).unwrap_or(0);
            if now - last < 5 * 60 * 1000 {
                return Ok(Vec::new());
            }
            crate::store::kv_set(&db.conn, "avatars_checked_at", now.to_string().as_bytes())?;
            let mut v: Vec<(String, String)> = Vec::new();
            if let (Some(me), Some(a)) = (crate::store::kv_get_str(&db.conn, "user_id")?, crate::store::kv_get_str(&db.conn, "avatar")?) {
                v.push((me, a));
            }
            let mut st = db.conn.prepare("SELECT user_id, avatar FROM contacts WHERE avatar<>''")?;
            for row in st.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))? {
                v.push(row?);
            }
            Ok::<_, Error>(v)
        })()
        .unwrap_or_default();
        let mut fetched = false;
        for (uid, avatar) in users.into_iter().filter(|(_, a)| { let p = self.avatar_path(a); !p.is_empty() && !Path::new(&p).is_file() }).take(20) {
            match self.cache_user_avatar(&serde_json::json!({"user_id": uid, "avatar_id": avatar})) {
                Ok(()) => fetched = true,
                Err(Error::Network(_)) => break,
                Err(_) => {}
            }
        }
        if fetched {
            self.emit(serde_json::json!({"type": "contacts_changed"}));
            self.emit(serde_json::json!({"type": "conversations_changed"}));
            self.emit(serde_json::json!({"type": "account_changed"}));
        }
    }

    pub fn set_avatar(&self, p: &Value) -> Result<Value> {
        let path = s(p, "path")?;
        if path.is_empty() {
            self.authed("DELETE", "/v1/accounts/current/avatar", None)?;
        } else {
            let file = Path::new(path);
            if std::fs::metadata(file)?.len() > MAX_AVATAR_SIZE {
                return Err(Error::Invalid("Аватар: JPEG до 48 КБ".into()));
            }
            self.api()?.upload("/v1/accounts/current/avatar", &self.access_token()?, file)?;
        }
        self.refresh_profile()?;
        Ok(Value::Null)
    }
}
