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
