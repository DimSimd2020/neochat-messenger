//! App updates. The server serves a manifest signed offline with the update key below;
//! a manifest without a valid signature is ignored, so a compromised server cannot push
//! its own build. Downloads are checked against the SHA-256 from the signed manifest.

use std::io::Read;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::api::Api;
use crate::engine::{DEFAULT_SERVER, Inner, s};
use crate::error::{Error, Result};
use crate::keys::unb64;
use crate::store::kv_get_str;

/// Public half of the update signing key (infra/publish-update.py holds the private one offline).
const UPDATE_KEY: &str = "ad278711420eb8f0f216cda3dcc88d08d981f245cf90f65ef0856a872d620392";
const MAX_UPDATE: u64 = 300 << 20;

/// "0.10.1" > "0.9.3"; missing parts count as zero.
fn newer(candidate: &str, current: &str) -> bool {
    let parse = |v: &str| -> Vec<u64> { v.trim().split('.').map(|x| x.parse().unwrap_or(0)).collect() };
    let (a, b) = (parse(candidate), parse(current));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

impl Inner {
    fn update_server(&self) -> Result<String> {
        Ok(kv_get_str(&self.db().conn, "server")?.unwrap_or_else(|| DEFAULT_SERVER.to_string()))
    }

    /// The verified manifest entry for `platform`, or None when nothing is published.
    fn update_entry(&self, platform: &str) -> Result<Option<(Value, String)>> {
        let server = self.update_server()?;
        let out = match Api::new(&server).call("GET", "/v1/app/manifest", None, None) {
            Err(Error::Api { status: 404, .. }) => return Ok(None),
            r => r?,
        };
        let body = unb64(s(&out, "manifest")?)?;
        let sig = Signature::from_slice(&unb64(s(&out, "signature")?)?).map_err(|_| Error::Crypto("Подпись обновления повреждена".into()))?;
        let key: [u8; 32] = hex::decode(UPDATE_KEY).ok().and_then(|k| k.try_into().ok()).ok_or_else(|| Error::Crypto("Ключ обновлений повреждён".into()))?;
        VerifyingKey::from_bytes(&key)
            .and_then(|k| k.verify(&body, &sig))
            .map_err(|_| Error::Crypto("Подпись обновления не прошла проверку".into()))?;
        let manifest: Value = serde_json::from_slice(&body)?;
        let entry = manifest["platforms"][platform].clone();
        if entry.is_null() {
            return Ok(None);
        }
        let url = format!("{}/v1/app/files/{}", server.trim_end_matches('/'), entry["file"].as_str().unwrap_or(""));
        Ok(Some((entry, url)))
    }

    pub fn check_update(&self, p: &Value) -> Result<Value> {
        let current = s(p, "version")?;
        match self.update_entry(s(p, "platform")?)? {
            Some((e, url)) if newer(e["version"].as_str().unwrap_or(""), current) => Ok(json!({
                "available": true,
                "version": e["version"],
                "size": e["size"],
                "url": url,
            })),
            _ => Ok(json!({"available": false})),
        }
    }

    /// Downloads the newer build into data_dir/updates and returns its path after the
    /// SHA-256 check; an already downloaded file with the right hash is reused.
    pub fn download_update(&self, p: &Value) -> Result<Value> {
        let current = s(p, "version")?;
        let Some((e, _)) = self.update_entry(s(p, "platform")?)? else {
            return Err(Error::Invalid("Обновлений нет".into()));
        };
        let version = e["version"].as_str().unwrap_or("");
        if !newer(version, current) {
            return Err(Error::Invalid("Обновлений нет".into()));
        }
        let file = e["file"].as_str().unwrap_or("");
        if file.is_empty() || file.contains(['/', '\\']) || file.starts_with('.') {
            return Err(Error::Crypto("Некорректное имя файла обновления".into()));
        }
        let want = e["sha256"].as_str().unwrap_or("").to_lowercase();
        let dir = self.data_dir.join("updates");
        std::fs::create_dir_all(&dir)?;
        let dest = dir.join(file);
        if dest.exists() && sha256_file(&dest)? == want {
            return Ok(json!({"path": dest.to_string_lossy(), "version": version}));
        }
        // Old builds are no longer needed.
        for old in std::fs::read_dir(&dir)?.flatten() {
            let _ = std::fs::remove_file(old.path());
        }
        let part = dir.join(format!("{file}.part"));
        let server = self.update_server()?;
        Api::new(&server).download(&format!("/v1/app/files/{file}"), "", &part, MAX_UPDATE)?;
        if sha256_file(&part)? != want {
            let _ = std::fs::remove_file(&part);
            return Err(Error::Crypto("Файл обновления повреждён. Попробуйте позже".into()));
        }
        std::fs::rename(&part, &dest)?;
        Ok(json!({"path": dest.to_string_lossy(), "version": version}))
    }
}

fn sha256_file(path: &std::path::Path) -> Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(hex::encode(h.finalize()))
}

#[cfg(test)]
mod tests {
    use super::newer;

    #[test]
    fn versions_compare_numerically() {
        assert!(newer("0.10.0", "0.9.9"));
        assert!(newer("0.4.0", "0.3.0"));
        assert!(newer("1.0", "0.99.99"));
        assert!(!newer("0.3.0", "0.3.0"));
        assert!(!newer("0.3", "0.3.0"));
        assert!(!newer("0.2.9", "0.3.0"));
    }
}
