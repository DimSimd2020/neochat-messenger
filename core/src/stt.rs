//! On-device transcription of voice messages and circles. The apps run the recognizer
//! (sherpa-onnx); the core only keeps the model files and the resulting text. Audio and
//! text stay on the device: the text is never sent to the server or to the other side.
//!
//! The model is a Russian offline Zipformer transducer (int8, Apache-2.0, from the Vosk
//! project). The files are hosted by our own server, not a third party, and their SHA-256
//! is pinned here, so a replaced file is rejected.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::{Value, json};

use crate::api::Api;
use crate::engine::{Inner, s};
use crate::error::{Error, Result};
use crate::files::sha256_file;
use crate::store::{kv_del, kv_set};

/// Folder name under the installation's data directory; changes with the model.
const MODEL_ID: &str = "ru-zipformer-2025-04-20";
/// (name in the folder, size, SHA-256). The server serves them as `stt-<MODEL_ID>-<name>`.
const MODEL_FILES: &[(&str, u64, &str)] = &[
    ("encoder.int8.onnx", 70_876_638, "eb6c12fbad810d5bc3e427802e604604c69b5943a91feebc43424dd09d9ec407"),
    ("decoder.onnx", 2_093_080, "dcbe1ffa0211e77ca6d3a80164df13fbda3ec00e47d12b9f449f89572df12136"),
    ("joiner.int8.onnx", 259_417, "93f2e1d12b78d53e7802f1606488c14bb3d764b15fadf5ef6c022f6ba1fa40f7"),
    ("tokens.txt", 6_388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
];
const MAX_TRANSCRIPT: usize = 20_000;

static DOWNLOADING: AtomicBool = AtomicBool::new(false);

/// Other accounts live in `<root>/accounts/<id>`; the model is shared by all of them.
fn root_dir(data_dir: &Path) -> PathBuf {
    match data_dir.parent() {
        Some(p) if p.file_name().is_some_and(|n| n == "accounts") => p.parent().unwrap_or(p).to_path_buf(),
        _ => data_dir.to_path_buf(),
    }
}

fn model_dir(data_dir: &Path) -> PathBuf {
    root_dir(data_dir).join("stt").join(MODEL_ID)
}

fn complete(dir: &Path) -> bool {
    MODEL_FILES.iter().all(|(name, size, _)| std::fs::metadata(dir.join(name)).is_ok_and(|m| m.len() == *size))
}

fn total() -> u64 {
    MODEL_FILES.iter().map(|f| f.1).sum()
}

struct Guard;

impl Drop for Guard {
    fn drop(&mut self) {
        DOWNLOADING.store(false, Ordering::SeqCst);
    }
}

impl Inner {
    /// {ready, dir, size, downloading}: `dir` holds encoder.int8.onnx, decoder.onnx,
    /// joiner.int8.onnx and tokens.txt when `ready`.
    pub fn stt_model(&self) -> Result<Value> {
        let dir = model_dir(&self.data_dir);
        let ready = complete(&dir);
        Ok(json!({"ready": ready, "dir": if ready { dir.to_string_lossy().into_owned() } else { String::new() }, "size": total(), "downloading": DOWNLOADING.load(Ordering::SeqCst)}))
    }

    /// Downloads the model if it is not there yet. Blocks; progress arrives as `stt_progress`
    /// events {done, total}. A file appears in the folder only after its hash matches.
    pub fn stt_model_download(&self) -> Result<Value> {
        if self.stt_model()?["ready"] == true {
            return self.stt_model();
        }
        if DOWNLOADING.swap(true, Ordering::SeqCst) {
            return Err(Error::Invalid("Модель уже скачивается".into()));
        }
        let _guard = Guard;
        let dir = model_dir(&self.data_dir);
        std::fs::create_dir_all(&dir)?;
        let server = self.update_server()?;
        let api = Api::new(&server);
        let part = dir.join("download.part");
        let mut done = 0u64;
        for (name, size, sha) in MODEL_FILES {
            let dest = dir.join(name);
            if std::fs::metadata(&dest).is_ok_and(|m| m.len() == *size) {
                done += size;
                continue;
            }
            if let Err(e) = api.download(&format!("/v1/app/models/stt-{MODEL_ID}-{name}"), "", &part, *size) {
                let _ = std::fs::remove_file(&part);
                return Err(match e {
                    Error::Api { status: 404, .. } => Error::Invalid("Модель распознавания пока недоступна на сервере".into()),
                    e => e,
                });
            }
            if hex::encode(sha256_file(&part)?) != *sha {
                let _ = std::fs::remove_file(&part);
                return Err(Error::Crypto("Файл модели повреждён. Попробуйте позже".into()));
            }
            std::fs::rename(&part, &dest)?;
            done += size;
            self.emit(json!({"type": "stt_progress", "done": done, "total": total()}));
        }
        self.stt_model()
    }

    pub fn stt_model_delete(&self) -> Result<Value> {
        if DOWNLOADING.load(Ordering::SeqCst) {
            return Err(Error::Invalid("Модель сейчас скачивается".into()));
        }
        let _ = std::fs::remove_dir_all(root_dir(&self.data_dir).join("stt"));
        self.stt_model()
    }

    /// Stores the recognized text of a voice message or circle. Empty text means "no speech found".
    pub fn set_transcript(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let text: String = s(p, "text")?.trim().chars().take(MAX_TRANSCRIPT).collect();
        let conv: String = {
            let db = self.db();
            let file: Option<String> = db.conn.query_row("SELECT file FROM messages WHERE id=?1 AND kind='file'", [id], |r| r.get(0)).ok();
            let meta: crate::outbox::FileMeta = serde_json::from_str(&file.ok_or_else(|| Error::Invalid("Сообщение не найдено".into()))?)?;
            if !meta.is_voice() && !meta.is_circle() {
                return Err(Error::Invalid("Расшифровка нужна только для голосовых и кружков".into()));
            }
            kv_set(&db.conn, &format!("tr:{id}"), text.as_bytes())?;
            db.conn.query_row("SELECT conversation_id FROM messages WHERE id=?1", [id], |r| r.get(0))?
        };
        self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        Ok(Value::Null)
    }

    pub fn clear_transcript(&self, p: &Value) -> Result<Value> {
        let id = s(p, "message_id")?;
        let conv: Option<String> = {
            let db = self.db();
            kv_del(&db.conn, &format!("tr:{id}"))?;
            db.conn.query_row("SELECT conversation_id FROM messages WHERE id=?1", [id], |r| r.get(0)).ok()
        };
        if let Some(conv) = conv {
            self.emit(json!({"type": "messages_changed", "conversation_id": conv}));
        }
        Ok(Value::Null)
    }
}
