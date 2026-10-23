//! App updates. The server serves a manifest signed offline with the update key below;
//! a manifest without a valid signature is ignored, so a compromised server cannot push
//! its own build. Downloads are checked against the SHA-256 from the signed manifest.
//!
//! Alpha builds (diagnostics for a bug hunt) are a third, hidden track: the server hands the
//! alpha manifest and files only to accounts listed in `alpha_testers` and answers 404 to
//! everyone else, so a client that is not a tester simply never sees one. A tester is
//! offered whichever is newer, the alpha or the public build.

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

    /// The verified manifest entry for `platform`, its download URL and whether it is an
    /// alpha build, or None when nothing is published.
    /// `platforms` is the stable channel: clients that omit `channel` keep receiving it.
    /// `channels.beta` is served only when the client asks for beta.
    fn update_entry(&self, platform: &str, channel: &str) -> Result<Option<(Value, String, bool)>> {
        let server = self.update_server()?;
        let public = match Api::new(&server).call("GET", "/v1/app/manifest", None, None) {
            Err(Error::Api { status: 404, .. }) => None,
            r => {
                let entry = select_entry(&verified_manifest(&r?)?, platform, channel);
                (!entry.is_null()).then_some(entry)
            }
        };
        // Alpha is optional: whatever goes wrong there (offline, not a tester, nothing published)
        // must not hide the public update.
        let alpha = if self.logged_in() { self.authed("GET", "/v1/app/alpha/manifest", None).ok().and_then(|m| verified_manifest(&m).ok()) } else { None }
            .map(|m| m["channels"]["alpha"][platform].clone())
            .filter(|e| !e.is_null());
        let Some((entry, alpha)) = pick(public, alpha) else { return Ok(None) };
        let path = if alpha { "alpha/files" } else { "files" };
        let url = format!("{}/v1/app/{path}/{}", server.trim_end_matches('/'), entry["file"].as_str().unwrap_or(""));
        Ok(Some((entry, url, alpha)))
    }

    pub fn check_update(&self, p: &Value) -> Result<Value> {
        let current = s(p, "version")?;
        match self.update_entry(s(p, "platform")?, channel_of(p))? {
            Some((e, url, _)) if newer(e["version"].as_str().unwrap_or(""), current) => Ok(json!({
                "available": true,
                "version": e["version"],
                "size": e["size"],
                "url": url,
                "tag": e["tag"],
            })),
            _ => Ok(json!({"available": false})),
        }
    }

    /// Downloads the newer build into data_dir/updates and returns its path after the
    /// SHA-256 check; an already downloaded file with the right hash is reused.
    pub fn download_update(&self, p: &Value) -> Result<Value> {
        let current = s(p, "version")?;
        let Some((e, _, alpha)) = self.update_entry(s(p, "platform")?, channel_of(p))? else {
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
            return Ok(json!({"path": dest.to_string_lossy(), "version": version, "tag": e["tag"]}));
        }
        // Old builds are no longer needed.
        for old in std::fs::read_dir(&dir)?.flatten() {
            let _ = std::fs::remove_file(old.path());
        }
        let part = dir.join(format!("{file}.part"));
        let server = self.update_server()?;
        let (path, token) = if alpha { (format!("/v1/app/alpha/files/{file}"), self.access_token()?) } else { (format!("/v1/app/files/{file}"), String::new()) };
        Api::new(&server).download(&path, &token, &part, MAX_UPDATE)?;
        if sha256_file(&part)? != want {
            let _ = std::fs::remove_file(&part);
            return Err(Error::Crypto("Файл обновления повреждён. Попробуйте позже".into()));
        }
        std::fs::rename(&part, &dest)?;
        Ok(json!({"path": dest.to_string_lossy(), "version": version, "tag": e["tag"]}))
    }
}

/// A tester gets the alpha build only while it is newer than the public one, so the next
/// public release takes them off the alpha track by itself.
fn pick(public: Option<Value>, alpha: Option<Value>) -> Option<(Value, bool)> {
    match (public, alpha) {
        (Some(p), Some(a)) if newer(a["version"].as_str().unwrap_or(""), p["version"].as_str().unwrap_or("")) => Some((a, true)),
        (Some(p), _) => Some((p, false)),
        (None, Some(a)) => Some((a, true)),
        (None, None) => None,
    }
}

/// The manifest body after the offline signature check.
fn verified_manifest(out: &Value) -> Result<Value> {
    let body = unb64(s(out, "manifest")?)?;
    let sig = Signature::from_slice(&unb64(s(out, "signature")?)?).map_err(|_| Error::Crypto("Подпись обновления повреждена".into()))?;
    let key: [u8; 32] = hex::decode(UPDATE_KEY).ok().and_then(|k| k.try_into().ok()).ok_or_else(|| Error::Crypto("Ключ обновлений повреждён".into()))?;
    VerifyingKey::from_bytes(&key)
        .and_then(|k| k.verify(&body, &sig))
        .map_err(|_| Error::Crypto("Подпись обновления не прошла проверку".into()))?;
    Ok(serde_json::from_slice(&body)?)
}

/// Missing or unknown channel is stable. Beta falls back to stable when that track has no build.
fn channel_of(p: &Value) -> &str {
    match p.get("channel").and_then(|v| v.as_str()) {
        Some("beta") => "beta",
        _ => "stable",
    }
}

fn select_entry(manifest: &Value, platform: &str, channel: &str) -> Value {
    if channel == "beta" {
        let beta = &manifest["channels"]["beta"][platform];
        if !beta.is_null() {
            return beta.clone();
        }
    } else {
        let stable = &manifest["channels"]["stable"][platform];
        if !stable.is_null() {
            return stable.clone();
        }
    }
    manifest["platforms"][platform].clone()
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
    use super::{channel_of, newer, pick, select_entry};
    use serde_json::json;

    #[test]
    fn versions_compare_numerically() {
        assert!(newer("0.10.0", "0.9.9"));
        assert!(newer("0.4.0", "0.3.0"));
        assert!(newer("1.0", "0.99.99"));
        assert!(!newer("0.3.0", "0.3.0"));
        assert!(!newer("0.3", "0.3.0"));
        assert!(!newer("0.2.9", "0.3.0"));
    }

    #[test]
    fn stable_channel_ignores_beta_builds() {
        let manifest = json!({
            "platforms": {"android": {"version": "0.6.7.1", "file": "stable.apk"}},
            "channels": {
                "stable": {"android": {"version": "0.6.7.1", "file": "stable.apk"}},
                "beta": {"android": {"version": "0.6.7.2", "file": "beta.apk"}}
            }
        });
        assert_eq!(select_entry(&manifest, "android", "stable")["file"], "stable.apk");
        assert_eq!(select_entry(&manifest, "android", "beta")["file"], "beta.apk");
        assert_eq!(channel_of(&json!({})), "stable");
        assert_eq!(channel_of(&json!({"channel": "beta"})), "beta");
        assert_eq!(channel_of(&json!({"channel": "nightly"})), "stable");
    }

    #[test]
    fn missing_beta_uses_the_stable_entry() {
        let manifest = json!({"platforms": {"ios": {"version": "0.6.7.1", "file": "stable.ipa"}}});
        assert_eq!(select_entry(&manifest, "ios", "beta")["file"], "stable.ipa");
        assert!(select_entry(&manifest, "windows", "stable").is_null());
    }

    #[test]
    fn alpha_is_offered_only_while_newer_than_the_public_build() {
        let public = || Some(json!({"version": "0.6.9.4", "file": "public.msi"}));
        let alpha = |v: &str| Some(json!({"version": v, "file": "alpha.msi", "tag": "alpha"}));
        assert_eq!(pick(public(), alpha("0.6.9.5")).map(|(e, a)| (e["file"].clone(), a)), Some((json!("alpha.msi"), true)));
        assert_eq!(pick(public(), alpha("0.6.9.4")).map(|(e, a)| (e["file"].clone(), a)), Some((json!("public.msi"), false)));
        assert_eq!(pick(public(), None).map(|(_, a)| a), Some(false));
        assert_eq!(pick(None, alpha("0.6.9.5")).map(|(_, a)| a), Some(true));
        assert!(pick(None, None).is_none());
    }
}
