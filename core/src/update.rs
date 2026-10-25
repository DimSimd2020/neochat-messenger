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
    pub(crate) fn update_server(&self) -> Result<String> {
        Ok(kv_get_str(&self.db().conn, "server")?.unwrap_or_else(|| DEFAULT_SERVER.to_string()))
    }

    /// Tests sign their own manifests; no build that ships can swap the key.
    fn update_key(&self) -> String {
        #[cfg(test)]
        if let Ok(Some(k)) = kv_get_str(&self.db().conn, "test_update_key") {
            return k;
        }
        UPDATE_KEY.to_string()
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
                let entry = select_entry(&verified_manifest(&r?, &self.update_key())?, platform, channel);
                (!entry.is_null()).then_some(entry)
            }
        };
        // Alpha is optional: whatever goes wrong there (offline, not a tester, nothing published)
        // must not hide the public update.
        let alpha = if self.logged_in() { self.authed("GET", "/v1/app/alpha/manifest", None).ok().and_then(|m| verified_manifest(&m, &self.update_key()).ok()) } else { None }
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
fn verified_manifest(out: &Value, key: &str) -> Result<Value> {
    let body = unb64(s(out, "manifest")?)?;
    let sig = Signature::from_slice(&unb64(s(out, "signature")?)?).map_err(|_| Error::Crypto("Подпись обновления повреждена".into()))?;
    let key: [u8; 32] = hex::decode(key).ok().and_then(|k| k.try_into().ok()).ok_or_else(|| Error::Crypto("Ключ обновлений повреждён".into()))?;
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

    /// A tester's core finds the alpha build next to the public one, downloads it with the
    /// account's token and reports the tag; the same server answers 404 to anyone else.
    #[test]
    fn tester_is_offered_the_alpha_build_and_others_are_not() {
        use std::io::{BufRead, BufReader, Read, Write};
        use std::net::TcpListener;
        use std::sync::{Arc, Mutex};

        use ed25519_dalek::{Signer, SigningKey};
        use sha2::{Digest, Sha256};

        use crate::engine::Core;
        use crate::keys::b64;
        use crate::store::kv_set;

        const BUILD: &[u8] = b"alpha installer bytes";
        let signing = SigningKey::from_bytes(&[7u8; 32]);
        let manifest = json!({"channels": {"alpha": {"windows": {
            "version": "9.9.9", "file": "NeoChat-9.9.9-alpha-x64.msi", "size": BUILD.len(),
            "sha256": hex::encode(Sha256::digest(BUILD)), "tag": "alpha"}}}})
        .to_string();
        let signed = json!({"manifest": b64(manifest.as_bytes()), "signature": b64(&signing.sign(manifest.as_bytes()).to_bytes())}).to_string();

        // Requests seen by the fake server: (path, Authorization header).
        let seen = Arc::new(Mutex::new(Vec::<(String, String)>::new()));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let log = seen.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { return };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                let path = line.split_whitespace().nth(1).unwrap_or("").to_string();
                let mut auth = String::new();
                loop {
                    let mut h = String::new();
                    reader.read_line(&mut h).unwrap();
                    if h.trim().is_empty() {
                        break;
                    }
                    if let Some(v) = h.to_ascii_lowercase().strip_prefix("authorization:") {
                        auth = v.trim().to_string();
                    }
                }
                log.lock().unwrap().push((path.clone(), auth.clone()));
                let tester = auth == "bearer tester-token";
                let (status, body, kind): (&str, Vec<u8>, &str) = match path.as_str() {
                    "/v1/app/alpha/manifest" if tester => ("200 OK", signed.clone().into_bytes(), "application/json"),
                    "/v1/app/alpha/files/NeoChat-9.9.9-alpha-x64.msi" if tester => ("200 OK", BUILD.to_vec(), "application/octet-stream"),
                    _ => ("404 Not Found", br#"{"error":{"code":"not_found","message":"not found"}}"#.to_vec(), "application/json"),
                };
                let head = format!("HTTP/1.1 {status}
Content-Type: {kind}
Content-Length: {}
Connection: close

", body.len());
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(&body);
                let _ = stream.flush();
                let _ = reader.read(&mut [0u8; 1]);
            }
        });

        let open = |token: &str| {
            let dir = std::env::temp_dir().join(format!("neochat-alpha-{}", uuid::Uuid::new_v4()));
            let core = Core::open(&json!({"data_dir": dir, "db_key": "ab".repeat(32), "foreground": false}), Box::new(|_| {})).unwrap();
            {
                let db = core.inner.db();
                for (k, v) in [
                    ("server", base.clone()),
                    ("user_id", "u1".into()),
                    ("device_id", "d1".into()),
                    ("refresh_token", "refresh".into()),
                    ("access_token", token.into()),
                    ("access_expires_at", (crate::store::now_ms() + 600_000).to_string()),
                    ("test_update_key", hex::encode(signing.verifying_key().to_bytes())),
                ] {
                    kv_set(&db.conn, k, v.as_bytes()).unwrap();
                }
            }
            (core, dir)
        };
        let ask = json!({"platform": "windows", "version": "0.6.9.4"});

        let (tester, dir) = open("tester-token");
        let check = tester.inner.check_update(&ask).unwrap();
        assert_eq!(check["available"], true, "{check}");
        assert_eq!(check["version"], "9.9.9");
        assert_eq!(check["tag"], "alpha");
        assert!(check["url"].as_str().unwrap().ends_with("/v1/app/alpha/files/NeoChat-9.9.9-alpha-x64.msi"));
        let got = tester.inner.download_update(&ask).unwrap();
        assert_eq!(got["tag"], "alpha");
        assert_eq!(std::fs::read(got["path"].as_str().unwrap()).unwrap(), BUILD);
        // Already on that build: nothing more to offer.
        assert_eq!(tester.inner.check_update(&json!({"platform": "windows", "version": "9.9.9"})).unwrap()["available"], false);
        drop(tester);
        std::fs::remove_dir_all(dir).unwrap();

        let (other, dir) = open("someone-else");
        assert_eq!(other.inner.check_update(&ask).unwrap()["available"], false);
        assert!(other.inner.download_update(&ask).is_err());
        drop(other);
        std::fs::remove_dir_all(dir).unwrap();

        // The account's token goes only to the alpha routes, never to the public ones.
        let seen = seen.lock().unwrap();
        assert!(seen.iter().any(|(path, _)| path == "/v1/app/manifest"));
        assert!(seen.iter().filter(|(path, _)| path.starts_with("/v1/app/manifest") || path.starts_with("/v1/app/files/")).all(|(_, auth)| auth.is_empty()), "{seen:?}");
    }
}
