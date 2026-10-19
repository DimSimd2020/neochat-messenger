//! HTTPS JSON client for the NeoChat server. TLS is verified against bundled
//! Mozilla roots (webpki-roots); certificate checks are never disabled.

use std::io::{Read, Write};
use std::path::Path;
use std::time::Duration;

use serde_json::Value;

use crate::error::{Error, Result};

pub struct Api {
    agent: ureq::Agent,
    pub base: String,
}

impl Api {
    pub fn new(base: &str) -> Api {
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(10)))
            .timeout_recv_response(Some(Duration::from_secs(30)))
            .user_agent("NeoChat/0.1")
            .build()
            .into();
        Api { agent, base: base.trim_end_matches('/').to_string() }
    }

    pub fn call(&self, method: &str, path: &str, token: Option<&str>, body: Option<&Value>) -> Result<Value> {
        let url = format!("{}{}", self.base, path);
        let auth = token.map(|t| format!("Bearer {t}"));
        let resp = match method {
            "GET" | "DELETE" if body.is_none() => {
                let mut req = if method == "GET" { self.agent.get(&url) } else { self.agent.delete(&url) };
                if let Some(a) = &auth {
                    req = req.header("Authorization", a);
                }
                req.call()
            }
            _ => {
                let mut req = match method {
                    "POST" => self.agent.post(&url),
                    "PUT" => self.agent.put(&url),
                    "DELETE" => self.agent.delete(&url).force_send_body(),
                    _ => return Err(Error::Invalid("Неподдерживаемый запрос".into())),
                };
                if let Some(a) = &auth {
                    req = req.header("Authorization", a);
                }
                req.send_json(body.unwrap_or(&Value::Null))
            }
        };
        let mut resp = resp.map_err(|e| Error::Network(e.to_string()))?;
        let status = resp.status().as_u16();
        let text = resp
            .body_mut()
            .with_config()
            .limit(4 << 20)
            .read_to_string()
            .map_err(|e| Error::Network(e.to_string()))?;
        if status >= 400 {
            return Err(api_error(status, &text));
        }
        if text.is_empty() { Ok(Value::Null) } else { Ok(serde_json::from_str(&text)?) }
    }

    pub fn get_with_header(&self, path: &str, header: (&str, &str)) -> Result<Value> {
        let resp = self.agent.get(&format!("{}{}", self.base, path)).header(header.0, header.1).call();
        let mut resp = resp.map_err(|e| Error::Network(e.to_string()))?;
        let status = resp.status().as_u16();
        let text = resp.body_mut().read_to_string().map_err(|e| Error::Network(e.to_string()))?;
        if status >= 400 {
            return Err(api_error(status, &text));
        }
        Ok(serde_json::from_str(&text)?)
    }

    /// Streams a ciphertext file with an exact Content-Length.
    pub fn upload(&self, path: &str, token: &str, file: &Path) -> Result<()> {
        let size = std::fs::metadata(file)?.len();
        let mut f = std::fs::File::open(file)?;
        let resp = self
            .agent
            .put(&format!("{}{}", self.base, path))
            .header("Authorization", &format!("Bearer {token}"))
            .header("Content-Length", &size.to_string())
            .header("Content-Type", "application/octet-stream")
            .send(ureq::SendBody::from_reader(&mut f));
        let mut resp = resp.map_err(|e| Error::Network(e.to_string()))?;
        let status = resp.status().as_u16();
        if status >= 400 {
            let text = resp.body_mut().read_to_string().unwrap_or_default();
            return Err(api_error(status, &text));
        }
        Ok(())
    }

    /// Streams a response body into `dest`, refusing more than `max` bytes.
    pub fn download(&self, path: &str, token: &str, dest: &Path, max: u64) -> Result<u64> {
        let resp = self
            .agent
            .get(&format!("{}{}", self.base, path))
            .header("Authorization", &format!("Bearer {token}"))
            .call();
        let mut resp = resp.map_err(|e| Error::Network(e.to_string()))?;
        let status = resp.status().as_u16();
        if status >= 400 {
            let text = resp.body_mut().read_to_string().unwrap_or_default();
            return Err(api_error(status, &text));
        }
        let mut reader = resp.body_mut().with_config().limit(max + 1).reader();
        let mut out = std::fs::File::create(dest)?;
        let mut buf = vec![0u8; 64 * 1024];
        let mut total = 0u64;
        loop {
            let n = reader.read(&mut buf).map_err(|e| Error::Network(e.to_string()))?;
            if n == 0 {
                break;
            }
            total += n as u64;
            if total > max {
                return Err(Error::Crypto("Файл больше допустимого размера".into()));
            }
            out.write_all(&buf[..n])?;
        }
        out.sync_all()?;
        Ok(total)
    }
}

fn api_error(status: u16, text: &str) -> Error {
    let v: Value = serde_json::from_str(text).unwrap_or(Value::Null);
    Error::Api {
        status,
        code: v["code"].as_str().unwrap_or("http_error").to_string(),
        message: v["message"].as_str().map(str::to_string).unwrap_or_else(|| format!("Ошибка сервера ({status})")),
        retry_after_ms: v["retry_after_ms"].as_u64(),
        details: v.get("details").cloned(),
    }
}
