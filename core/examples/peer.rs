//! Scripted second participant for manual client checks.
//!
//!   cargo run --release --example peer -- <dir> register <server> <username>
//!   cargo run --release --example peer -- <dir> send <username> <text>
//!   cargo run --release --example peer -- <dir> expect <username> <text>
//!   cargo run --release --example peer -- <dir> file <username> <path>
//!   cargo run --release --example peer -- <dir> login <server> <username> <password>
//!
//! The state directory keeps a random test database key; use it only with test accounts.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use neocore::Core;
use serde_json::{Value, json};

fn open(dir: &PathBuf) -> Core {
    std::fs::create_dir_all(dir).unwrap();
    let kf = dir.join("test-db-key");
    let key = std::fs::read_to_string(&kf).unwrap_or_else(|_| {
        let k = hex::encode(rand::random::<[u8; 32]>());
        std::fs::write(&kf, &k).unwrap();
        k
    });
    Core::open(&json!({"data_dir": dir.join("core").to_string_lossy(), "db_key": key, "foreground": true}), Box::new(|_| {})).expect("open core")
}

fn call(c: &Core, m: &str, p: Value) -> Value {
    c.call(m, &p).unwrap_or_else(|e| {
        eprintln!("{m}: {e}");
        std::process::exit(1)
    })
}

fn direct(c: &Core, username: &str) -> String {
    let contact = call(c, "resolve_contact", json!({"username": username, "first_name": username}));
    let conv = call(c, "open_direct", json!({"user_id": contact["user_id"]}));
    conv["id"].as_str().unwrap().to_string()
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 3 {
        eprintln!("usage: peer <dir> register|send|expect|file ...");
        std::process::exit(2);
    }
    let dir = PathBuf::from(&a[1]);
    let c = open(&dir);
    match a[2].as_str() {
        "register" => {
            call(&c, "register", json!({"server": a[3], "username": a[4], "password": "peer-test-passphrase", "device_name": "peer", "platform": "windows"}));
            println!("registered");
        }
        "login" => {
            call(&c, "login", json!({"server": a[3], "username": a[4], "password": a[5], "device_name": "peer", "platform": "windows"}));
            println!("logged in");
        }
        "send" => {
            let conv = direct(&c, &a[3]);
            call(&c, "send_text", json!({"conversation_id": conv, "text": a[4]}));
            let t = Instant::now();
            while t.elapsed() < Duration::from_secs(20) {
                call(&c, "sync_now", json!({}));
                let ms = call(&c, "list_messages", json!({"conversation_id": conv, "limit": 5}));
                if ms.as_array().unwrap().iter().all(|m| m["status"] != "queued") {
                    println!("sent");
                    return;
                }
                std::thread::sleep(Duration::from_millis(500));
            }
            println!("still queued");
        }
        "file" => {
            let conv = direct(&c, &a[3]);
            call(&c, "send_file", json!({"conversation_id": conv, "path": a[4], "mime": "application/pdf"}));
            std::thread::sleep(Duration::from_secs(3));
            call(&c, "sync_now", json!({}));
            println!("file queued");
        }
        "expect" => {
            let conv = direct(&c, &a[3]);
            let t = Instant::now();
            while t.elapsed() < Duration::from_secs(60) {
                call(&c, "sync_now", json!({}));
                let ms = call(&c, "list_messages", json!({"conversation_id": conv, "limit": 50}));
                if ms.as_array().unwrap().iter().any(|m| m["text"] == a[4].as_str() && m["outgoing"] == false) {
                    call(&c, "mark_read", json!({"conversation_id": conv}));
                    println!("received");
                    return;
                }
                std::thread::sleep(Duration::from_secs(1));
            }
            println!("not received");
            std::process::exit(1);
        }
        other => {
            eprintln!("unknown command {other}");
            std::process::exit(2);
        }
    }
}
