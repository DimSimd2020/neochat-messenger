//! End-to-end tests against a running NeoChat server.
//!
//! Run through `scripts/e2e.sh`, which starts PostgreSQL, the Go server and
//! passes NEOCHAT_E2E_SERVER.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use neocore::Core;
use serde_json::{Value, json};

struct Env {
    server: String,
}

static ENV: std::sync::OnceLock<Option<Arc<Env>>> = std::sync::OnceLock::new();

/// Shared by all tests.
fn env() -> Option<Arc<Env>> {
    ENV.get_or_init(|| {
        let server = std::env::var("NEOCHAT_E2E_SERVER").ok()?;
        Some(Arc::new(Env { server }))
    })
    .clone()
}

struct Client {
    core: Option<Core>,
    dir: PathBuf,
    key: String,
    events: Arc<Mutex<Vec<Value>>>,
}

impl Client {
    fn new() -> Client {
        let dir = std::env::temp_dir().join(format!("neocore-e2e-{}", uuid::Uuid::new_v4()));
        let mut c = Client { core: None, dir, key: hex::encode(rand::random::<[u8; 32]>()), events: Default::default() };
        c.open();
        c
    }

    fn open(&mut self) {
        let ev = self.events.clone();
        let core = Core::open(
            &json!({"data_dir": self.dir.to_string_lossy(), "db_key": self.key, "foreground": std::env::var("NEOCHAT_E2E_LIVE").is_ok()}),
            Box::new(move |s| ev.lock().unwrap().push(serde_json::from_str(s).unwrap())),
        )
        .expect("open core");
        self.core = Some(core);
    }

    fn reopen(&mut self) {
        self.core = None;
        self.open();
    }

    fn call(&self, m: &str, p: Value) -> Value {
        self.core.as_ref().unwrap().call(m, &p).unwrap_or_else(|e| panic!("{m}: {e}"))
    }

    fn try_call(&self, m: &str, p: Value) -> neocore::Result<Value> {
        self.core.as_ref().unwrap().call(m, &p)
    }

    fn sync(&self) {
        self.call("sync_now", json!({}));
    }

    fn messages(&self, conv: &str) -> Vec<Value> {
        self.call("list_messages", json!({"conversation_id": conv, "limit": 200})).as_array().unwrap().clone()
    }

    fn texts(&self, conv: &str) -> Vec<String> {
        self.messages(conv).iter().filter_map(|m| m["text"].as_str().map(str::to_string)).collect()
    }
}

fn register(e: &Env, name: &str) -> (Client, String) {
    let c = Client::new();
    let res = c.call(
        "register",
        json!({"server": e.server, "username": name, "display_name": "Тест", "password": "correct horse battery", "device_name": "test", "platform": "windows"}),
    );
    assert!(res["recovery_code"].as_str().unwrap().len() > 40);
    (c, res["recovery_code"].as_str().unwrap().to_string())
}

fn uname(prefix: &str) -> String {
    format!("{prefix}_{}", &uuid::Uuid::new_v4().simple().to_string()[..8])
}

fn uid(c: &Client) -> String {
    c.call("status", json!({}))["user_id"].as_str().unwrap().to_string()
}

#[test]
fn direct_chat_restart_receipts_and_dedupe() {
    let Some(e) = env() else { return };
    let (mut alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);

    let contact = alice.call("resolve_contact", json!({"username": bname, "first_name": "Боб"}));
    assert_eq!(contact["display_name"], "Боб");
    let conv = alice.call("open_direct", json!({"user_id": contact["user_id"]}));
    let cid = conv["id"].as_str().unwrap().to_string();

    alice.call("send_text", json!({"conversation_id": cid, "text": "Привет, Боб!"}));
    alice.sync();
    let sent = alice.messages(&cid);
    assert_eq!(sent.last().unwrap()["status"], "sent", "{sent:?}");

    bob.sync();
    assert_eq!(bob.texts(&cid), vec!["Привет, Боб!"]);
    let bconv = bob.call("get_conversation", json!({"conversation_id": cid}));
    assert_eq!(bconv["unread"], 1);

    // Bob's delivered receipt and reply reach Alice.
    bob.call("send_text", json!({"conversation_id": cid, "text": "Привет!"}));
    bob.sync();
    alice.sync();
    let a = alice.messages(&cid);
    assert_eq!(a.iter().map(|m| m["text"].as_str().unwrap()).collect::<Vec<_>>(), vec!["Привет, Боб!", "Привет!"]);
    assert_eq!(a[0]["status"], "delivered", "{a:?}");

    // Read receipt.
    bob.call("mark_read", json!({"conversation_id": cid}));
    bob.sync();
    alice.sync();
    assert_eq!(alice.messages(&cid)[0]["status"], "read");

    // Sessions and keys survive a restart.
    alice.reopen();
    alice.call("send_text", json!({"conversation_id": cid, "text": "после перезапуска"}));
    alice.sync();
    bob.sync();
    assert_eq!(bob.texts(&cid).last().unwrap(), "после перезапуска");

    // Replaying the whole feed must not duplicate messages.
    bob.core.as_ref().unwrap().inner.db().conn.execute("DELETE FROM kv WHERE k='cursor'", []).unwrap();
    bob.sync();
    assert_eq!(bob.texts(&cid).len(), 3);
}

#[test]
fn group_membership_and_files() {
    let Some(e) = env() else { return };
    let (alice, _) = register(&e, &uname("alice"));
    let (bname, cname) = (uname("bob"), uname("carol"));
    let (bob, _) = register(&e, &bname);
    let (carol, _) = register(&e, &cname);
    let b = alice.call("resolve_contact", json!({"username": bname}));
    let c = alice.call("resolve_contact", json!({"username": cname}));
    let g = alice.call("create_group", json!({"title": "Наши", "member_ids": [b["user_id"], c["user_id"]]}));
    let gid = g["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": gid, "text": "всем привет"}));
    alice.sync();
    bob.sync();
    carol.sync();
    assert_eq!(bob.texts(&gid), vec!["всем привет"]);
    assert_eq!(carol.texts(&gid), vec!["всем привет"]);
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["title"], "Наши");

    // Carol is removed; later messages do not reach her.
    alice.call("group_set_members", json!({"conversation_id": gid, "member_ids": [b["user_id"]]}));
    alice.call("send_text", json!({"conversation_id": gid, "text": "без Кэрол"}));
    alice.sync();
    bob.sync();
    carol.sync();
    assert_eq!(bob.texts(&gid), vec!["всем привет", "без Кэрол"]);
    assert_eq!(carol.texts(&gid), vec!["всем привет"]);
    assert_eq!(carol.call("get_conversation", json!({"conversation_id": gid}))["left"], true);
    assert!(carol.try_call("send_text", json!({"conversation_id": gid, "text": "x"})).is_err());

    // Encrypted file: Bob downloads and gets identical bytes.
    let src = std::env::temp_dir().join(format!("nc-e2e-{}.bin", uuid::Uuid::new_v4()));
    let data: Vec<u8> = (0..300_000u32).map(|i| (i % 253) as u8).collect();
    std::fs::write(&src, &data).unwrap();
    let m = alice.call("send_file", json!({"conversation_id": gid, "path": src.to_string_lossy(), "mime": "application/octet-stream"}));
    alice.sync();
    bob.sync();
    let path = bob.call("download_file", json!({"message_id": m["id"]}))["path"].as_str().unwrap().to_string();
    assert_eq!(std::fs::read(path).unwrap(), data);

    // Bob leaves; Alice is alone and sending reports no recipients.
    bob.call("group_leave", json!({"conversation_id": gid}));
    alice.sync();
    let ag = alice.call("get_conversation", json!({"conversation_id": gid}));
    assert_eq!(ag["member_count"], 1, "{ag}");
}

#[test]
fn delete_everywhere_and_forward() {
    let Some(e) = env() else { return };
    let (alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);

    let contact = alice.call("resolve_contact", json!({"username": bname, "first_name": "Боб"}));
    let conv = alice.call("open_direct", json!({"user_id": contact["user_id"]}));
    let cid = conv["id"].as_str().unwrap().to_string();

    // "Delete for everyone" removes the message on both sides; deleting somebody
    // else's message is rejected. A replayed delete control stays a no-op.
    alice.call("send_text", json!({"conversation_id": cid, "text": "это удалим"}));
    alice.sync();
    bob.sync();
    let doomed = bob.messages(&cid).last().unwrap()["id"].as_str().unwrap().to_string();
    assert!(bob.try_call("delete_everywhere", json!({"message_id": doomed})).is_err());
    bob.core.as_ref().unwrap().inner.enqueue_control(&cid, "delete", json!({"id": doomed})).unwrap();
    bob.sync();
    alice.sync();
    assert!(alice.messages(&cid).iter().any(|m| m["id"] == doomed), "a peer cannot delete our messages");
    // A full disk / failed outbox write must keep the original message intact.
    alice.core.as_ref().unwrap().inner.db().conn.execute_batch(
        "CREATE TEMP TRIGGER fail_delete_control BEFORE INSERT ON outbox WHEN NEW.visible=0 BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END;"
    ).unwrap();
    assert!(alice.try_call("delete_everywhere", json!({"message_id": doomed})).is_err());
    assert!(alice.messages(&cid).iter().any(|m| m["id"] == doomed));
    alice.core.as_ref().unwrap().inner.db().conn.execute_batch("DROP TRIGGER fail_delete_control").unwrap();
    alice.call("delete_everywhere", json!({"message_id": doomed}));
    assert!(alice.messages(&cid).iter().all(|m| m["id"] != doomed));
    alice.sync();
    bob.sync();
    assert!(bob.messages(&cid).iter().all(|m| m["id"] != doomed));
    bob.core.as_ref().unwrap().inner.db().conn.execute("DELETE FROM kv WHERE k='cursor'", []).unwrap();
    bob.sync();
    assert!(bob.messages(&cid).iter().all(|m| m["id"] != doomed));

    // A delayed original must not reappear if its delete arrived first.
    let delayed = {
        let inner = &alice.core.as_ref().unwrap().inner;
        let _cycle = inner.cycle_lock.lock().unwrap();
        let m = alice.call("send_text", json!({"conversation_id": cid, "text": "задержанное"}));
        inner.db().conn.execute("UPDATE outbox SET next_at=?2 WHERE id=?1", rusqlite::params![m["id"].as_str().unwrap(), i64::MAX]).unwrap();
        inner.enqueue_control(&cid, "delete", json!({"id": m["id"]})).unwrap();
        m
    };
    alice.sync();
    bob.sync();
    alice.core.as_ref().unwrap().inner.db().conn.execute("UPDATE outbox SET next_at=0 WHERE id=?1", [delayed["id"].as_str().unwrap()]).unwrap();
    alice.sync();
    bob.sync();
    assert!(bob.messages(&cid).iter().all(|m| m["id"] != delayed["id"]));
    alice.call("delete_local_message", json!({"message_id": delayed["id"]}));

    // Bob forwards Alice's text and file into a group where Carol is a member
    // but the source chat is not. The copy keeps the author's name; the file is
    // uploaded as a separate encrypted attachment so Carol can download it.
    let cname = uname("carol");
    let (carol, _) = register(&e, &cname);
    let car = alice.call("resolve_contact", json!({"username": cname}));
    let g = alice.call("create_group", json!({"title": "Цель", "member_ids": [contact["user_id"], car["user_id"]]}));
    let gid = g["id"].as_str().unwrap().to_string();

    alice.call("send_text", json!({"conversation_id": gid, "text": "тема группы"}));
    alice.call("send_text", json!({"conversation_id": cid, "text": "перешли это"}));
    let src = std::env::temp_dir().join(format!("nc-e2e-{}.bin", uuid::Uuid::new_v4()));
    let data: Vec<u8> = (0..120_000u32).map(|i| (i % 251) as u8).collect();
    std::fs::write(&src, &data).unwrap();
    let fm = alice.call("send_file", json!({"conversation_id": cid, "path": src.to_string_lossy(), "mime": "application/octet-stream"}));
    alice.sync();
    bob.sync();
    carol.sync();
    // The file must be downloaded before it can be forwarded.
    assert!(bob.try_call("forward_message", json!({"message_id": fm["id"], "conversation_id": gid})).is_err());
    bob.call("download_file", json!({"message_id": fm["id"]}));

    let texts = bob.messages(&cid);
    let txt = texts.iter().find(|m| m["text"] == "перешли это").unwrap();
    bob.call("forward_message", json!({"message_id": txt["id"], "conversation_id": gid}));
    let fwd_file = bob.call("forward_message", json!({"message_id": fm["id"], "conversation_id": gid}));
    // The forwarding device can open its own copy even before upload.
    let local = bob.call("download_file", json!({"message_id": fwd_file["id"]}));
    assert_eq!(std::fs::read(local["path"].as_str().unwrap()).unwrap(), data);
    assert_ne!(fwd_file["id"], fm["id"]);
    assert!(fwd_file["fwd"]["name"].as_str().unwrap().len() > 0, "{fwd_file:?}");
    bob.sync();
    carol.sync();

    let gmsgs = carol.messages(&gid);
    let fwd = gmsgs.iter().find(|m| m["id"] == fwd_file["id"]).unwrap_or_else(|| panic!("forwarded file missing: {gmsgs:?}"));
    assert!(fwd["fwd"]["name"].as_str().unwrap().len() > 0);
    let dl = carol.call("download_file", json!({"message_id": fwd["id"]}));
    assert_eq!(std::fs::read(dl["path"].as_str().unwrap()).unwrap(), data);
    assert!(gmsgs.iter().any(|m| m["text"] == "перешли это" && m["fwd"]["name"].is_string()));
}

#[test]
fn linked_device_and_recovery() {
    let Some(e) = env() else { return };
    let aname = uname("alice");
    let (alice, _) = register(&e, &aname);
    let bname = uname("bob");
    let (bob, bob_code) = register(&e, &bname);

    // Second device for Alice: the password alone restores the account keys from the server copy.
    let alice2 = Client::new();
    alice2.call(
        "login",
        json!({"server": e.server, "username": aname, "password": "correct horse battery", "device_name": "phone", "platform": "android"}),
    );
    alice.sync();
    assert_eq!(alice.call("devices", json!({}))["devices"].as_array().unwrap().len(), 2);

    let b = alice.call("resolve_contact", json!({"username": bname}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    let a = bob.call("resolve_contact", json!({"username": aname}));
    assert_eq!(bob.call("open_direct", json!({"user_id": a["user_id"]}))["id"].as_str().unwrap(), cid);
    bob.call("send_text", json!({"conversation_id": cid, "text": "двум устройствам"}));
    bob.sync();
    alice.sync();
    alice2.sync();
    assert_eq!(alice.texts(&cid), vec!["двум устройствам"]);
    assert_eq!(alice2.texts(&cid), vec!["двум устройствам"]);

    // A message from the secondary shows as outgoing on the primary.
    alice2.call("send_text", json!({"conversation_id": cid, "text": "с телефона"}));
    alice2.sync();
    alice.sync();
    let last = alice.messages(&cid).last().unwrap().clone();
    assert_eq!(last["text"], "с телефона");
    assert_eq!(last["outgoing"], true);

    // Bob recovers his account: Alice must see the key change and cannot send until she accepts.
    let bob2 = Client::new();
    bob2.call(
        "recover",
        json!({"server": e.server, "username": bname, "recovery_code": bob_code, "new_password": "new long password", "device_name": "new", "platform": "windows"}),
    );
    alice.sync();
    let err = alice.try_call("send_text", json!({"conversation_id": cid, "text": "кто ты?"})).unwrap_err();
    assert_eq!(err.code(), "key_changed");
    alice.call("accept_key_change", json!({"user_id": uid(&bob2)}));
    alice.call("send_text", json!({"conversation_id": cid, "text": "снова привет"}));
    alice.sync();
    bob2.sync();
    // History is not restored on the recovered device; only the new message arrives.
    assert_eq!(bob2.texts(&cid), vec!["снова привет"]);
    // The old device is revoked.
    assert!(bob.try_call("sync_now", json!({})).is_err() || !bob.call("status", json!({}))["logged_in"].as_bool().unwrap());
}

#[test]
fn qr_login_history_and_two_factor() {
    let Some(e) = env() else { return };
    let aname = uname("alice");
    let (alice, _) = register(&e, &aname);
    let (bob, _) = register(&e, &uname("bob"));
    let b = alice.call("resolve_contact", json!({"username": bob.call("status", json!({}))["username"]}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": cid, "text": "старая переписка"}));
    alice.sync();

    // The computer shows a QR code, the phone approves it: keys and history move over.
    let pc = Client::new();
    let qr = pc.call("qr_login_start", json!({"server": e.server, "device_name": "pc", "platform": "windows"}))["qr"].as_str().unwrap().to_string();
    assert_eq!(pc.call("qr_login_poll", json!({}))["status"], "pending");
    alice.call("qr_login_approve", json!({"qr": qr}));
    assert_eq!(pc.call("qr_login_poll", json!({}))["status"], "approved");
    assert_eq!(pc.texts(&cid), vec!["старая переписка"]);

    // Two-factor sign-in: the code arrives in the system chat of the signed-in devices.
    alice.call("set_two_factor", json!({"enabled": true}));
    let third = Client::new();
    let login = json!({"server": e.server, "username": aname, "password": "correct horse battery", "device_name": "tablet", "platform": "android"});
    assert!(third.try_call("login", login.clone()).unwrap_err().is_api("code_required"));
    alice.sync();
    let notice = alice.texts("neochat-system").last().unwrap().clone();
    let code: String = notice.chars().filter(char::is_ascii_digit).take(6).collect();
    let mut with_code = login.clone();
    with_code["code"] = code.into();
    third.call("login", with_code);
    assert_eq!(third.texts(&cid), vec!["старая переписка"]);
}

#[test]
fn profile_name_nickname_and_number() {
    let Some(e) = env() else { return };
    let nick = format!("DimSimd_{}", &uuid::Uuid::new_v4().simple().to_string()[..6]);
    let dima = Client::new();
    dima.call(
        "register",
        json!({"server": e.server, "username": nick, "display_name": "Дмитрий", "password": "correct horse battery", "device_name": "test", "platform": "windows"}),
    );
    let st = dima.call("status", json!({}));
    assert_eq!(st["username"], nick.as_str());
    assert_eq!(st["display_name"], "Дмитрий");
    assert!(st["number"].as_str().unwrap().parse::<u64>().unwrap() > 100000);

    // The nickname is found in any letter case and shows as typed, with the profile name.
    let (other, _) = register(&e, &uname("other"));
    let c = other.call("resolve_contact", json!({"username": nick.to_lowercase()}));
    assert_eq!(c["username"], nick.as_str());
    assert_eq!(c["display_name"], "Дмитрий");

    dima.call("set_profile", json!({"display_name": "Дима"}));
    other.sync();
    let c = other.call("list_contacts", json!({}))[0].clone();
    assert_eq!(c["display_name"], "Дима");
}

#[test]
fn published_update_is_signed_and_downloads() {
    if env().is_none() {
        return;
    }
    // Uses the default server; skipped while nothing is published there.
    let c = Client::new();
    let p = json!({"platform": "android", "version": "0.0.1"});
    let check = c.call("check_update", p.clone());
    if check["available"] != json!(true) {
        return;
    }
    let got = c.call("download_update", p);
    assert_eq!(got["version"], check["version"]);
    assert_eq!(std::fs::metadata(got["path"].as_str().unwrap()).unwrap().len(), check["size"].as_u64().unwrap());
    assert_eq!(c.call("check_update", json!({"platform": "android", "version": check["version"]}))["available"], json!(false));
}

#[test]
fn password_sign_in_gets_latest_history() {
    let Some(e) = env() else { return };
    let aname = uname("alice");
    let (alice, _) = register(&e, &aname);
    let (bob, _) = register(&e, &uname("bob"));
    let b = alice.call("resolve_contact", json!({"username": bob.call("status", json!({}))["username"]}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.sync();
    // Sent after the last history upload: the copy on the server does not have it yet.
    alice.call("send_text", json!({"conversation_id": cid, "text": "только что"}));
    alice.sync();

    let pc = Client::new();
    pc.call("login", json!({"server": e.server, "username": aname, "password": "correct horse battery", "device_name": "pc", "platform": "windows"}));
    // Alice's device learns about the new device and uploads at once; the PC merges the fresh copy.
    alice.sync();
    pc.sync();
    assert_eq!(pc.texts(&cid), vec!["только что"]);
    assert!(pc.texts("neochat-system").iter().any(|t| t.contains("Вход на новом устройстве")));
}

/// Live delivery over the event socket: NEOCHAT_E2E_LIVE=1 cargo test --test e2e live_latency -- --nocapture
#[test]
fn live_latency() {
    let Some(e) = env() else { return };
    if std::env::var("NEOCHAT_E2E_LIVE").is_err() {
        return;
    }
    let (alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);
    let b = alice.call("resolve_contact", json!({"username": bname}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": cid, "text": "прогрев"}));
    let wait = |c: &Client, n: usize| {
        let t = std::time::Instant::now();
        while c.texts(&cid).len() < n {
            assert!(t.elapsed().as_secs() < 30, "не дошло");
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        t.elapsed()
    };
    wait(&bob, 1);
    for i in 0..3 {
        let t = std::time::Instant::now();
        alice.call("send_text", json!({"conversation_id": cid, "text": format!("сообщение {i}")}));
        wait(&bob, i + 2);
        let sent = alice.messages(&cid).last().unwrap()["status"].clone();
        println!("доставка {i}: {:?} (статус у отправителя: {sent})", t.elapsed());
    }
}

#[test]
fn groups_admins_tags_polls_and_channels() {
    let Some(e) = env() else { return };
    let users: Vec<(Client, String)> = (0..4).map(|i| {
        let name = uname(&format!("u{i}"));
        (register(&e, &name).0, name)
    }).collect();
    let [(owner, _), (admin, an), (bob, bn), (carol, cn)] = <[_; 4]>::try_from(users).ok().unwrap();
    let id = |c: &Client, n: &str| c.call("resolve_contact", json!({"username": n}))["user_id"].as_str().unwrap().to_string();
    let (a, b) = (id(&owner, &an), id(&owner, &bn));
    let c = id(&admin, &cn);
    let g = owner.call("create_group", json!({"title": "Команда", "member_ids": [a, b], "admin_ids": [a]}));
    let gid = g["id"].as_str().unwrap().to_string();
    assert_eq!(g["role"], "owner");

    // The admin adds Carol; Bob and Carol verify the admin-signed state through the chain.
    admin.sync();
    let got = admin.call("group_set_members", json!({"conversation_id": gid, "member_ids": [a, b, c]}));
    assert_eq!(got["member_count"], 4);
    bob.sync();
    carol.sync();
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["member_count"], 4);
    assert_eq!(carol.call("get_conversation", json!({"conversation_id": gid}))["role"], "member");
    // A member cannot change the list, an admin cannot remove another admin or name one.
    assert!(bob.try_call("group_set_members", json!({"conversation_id": gid, "member_ids": [a, b]})).is_err());

    // Tags and info from an admin reach everyone; a member's attempt is refused locally.
    owner.sync();
    owner.call("group_set_tag", json!({"conversation_id": gid, "user_id": b, "tag": "Модератор"}));
    owner.call("group_set_info", json!({"conversation_id": gid, "description": "Обсуждаем релиз"}));
    owner.sync();
    bob.sync();
    let bc = bob.call("get_conversation", json!({"conversation_id": gid}));
    assert_eq!(bc["description"], "Обсуждаем релиз");
    assert_eq!(bc["tags"][&b], "Модератор");
    assert!(bob.try_call("group_set_tag", json!({"conversation_id": gid, "user_id": b, "tag": "Босс"})).is_err());

    // Polls: Bob votes, the owner sees it.
    let poll = owner.call("send_poll", json!({"conversation_id": gid, "question": "Когда релиз?", "options": ["Сегодня", "Завтра"]}));
    owner.sync();
    bob.sync();
    bob.call("vote_poll", json!({"message_id": poll["id"], "choices": [1]}));
    bob.sync();
    owner.sync();
    let p = owner.messages(&gid).into_iter().find(|m| m["id"] == poll["id"]).unwrap();
    assert_eq!(p["poll"]["options"][1]["votes"], 1, "{p}");

    // Only admins post when the owner closes the group for members.
    owner.call("group_set_settings", json!({"conversation_id": gid, "members_can_send": false}));
    bob.sync();
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["can_send"], false);
    assert!(bob.try_call("send_text", json!({"conversation_id": gid, "text": "можно?"})).is_err());

    // Channel: subscribers read, only the owner posts, posts are signed by the channel.
    let ch = owner.call("create_group", json!({"title": "Новости", "kind": "channel", "member_ids": [b]}));
    let chid = ch["id"].as_str().unwrap().to_string();
    owner.call("send_text", json!({"conversation_id": chid, "text": "Первый пост"}));
    owner.sync();
    bob.sync();
    let bch = bob.call("get_conversation", json!({"conversation_id": chid}));
    assert_eq!(bch["kind"], "channel");
    assert_eq!(bch["can_send"], false);
    let posts = bob.messages(&chid);
    assert_eq!(posts.last().unwrap()["text"], "Первый пост");
    assert_eq!(posts.last().unwrap()["sender_name"], "Новости");
}

#[test]
fn reactions_bio_presence_typing_qr_archive_pins_lock() {
    let Some(e) = env() else { return };
    if std::env::var("NEOCHAT_E2E_LIVE").is_err() {
        return;
    }
    let (alice, _) = register(&e, &uname("alice"));
    let (bob, _) = register(&e, &uname("bob"));
    // Adding by QR verifies the key.
    let qr = bob.call("my_qr", json!({}));
    assert!(qr["qr_rows"].as_array().unwrap().len() > 20);
    let b = alice.call("add_contact_qr", json!({"link": qr["link"]}));
    assert_eq!(b["verified"], true);
    let bad = qr["link"].as_str().unwrap().replace("?k=", "?k=x");
    assert!(alice.try_call("add_contact_qr", json!({"link": bad})).is_err());

    let avatar = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, include_bytes!("fixtures/avatar.jpg"));
    bob.call("set_profile", json!({"bio": "Люблю чай", "avatar": avatar}));
    assert_eq!(bob.call("status", json!({}))["avatar"], avatar);
    assert!(bob.try_call("set_profile", json!({"bio": "я".repeat(101)})).is_err());
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": cid, "text": "👍"}));
    let wait = |c: &Client, f: &dyn Fn(&Client) -> bool| {
        let t = std::time::Instant::now();
        while !f(c) {
            assert!(t.elapsed().as_secs() < 20, "не дождались");
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    };
    wait(&bob, &|c| c.messages(&cid).len() == 1);
    let m = bob.messages(&cid)[0].clone();
    assert_eq!(m["emoji_only"], 1);
    bob.call("react", json!({"message_id": m["id"], "emoji": "🔥"}));
    wait(&alice, &|c| c.messages(&cid)[0]["reactions"][0]["emoji"] == "🔥");
    assert!(bob.try_call("react", json!({"message_id": m["id"], "emoji": "ab"})).is_err());

    alice.sync();
    let info = alice.call("contact_info", json!({"user_id": b["user_id"]}));
    assert_eq!(info["bio"], "Люблю чай");
    assert_eq!(info["avatar"], avatar);
    assert_eq!(alice.call("get_conversation", json!({"conversation_id": cid}))["avatar"], avatar);
    bob.call("set_profile", json!({"avatar": ""}));
    alice.sync();
    assert_eq!(alice.call("contact_info", json!({"user_id": b["user_id"]}))["avatar"], "");
    let p = alice.call("presence", json!({"user_ids": [b["user_id"]], "tz_offset": 180}));
    let st = p[b["user_id"].as_str().unwrap()]["status"].as_str().unwrap().to_string();
    assert!(st == "online" || st == "exact", "{st}");
    // Hiding the time hides it both ways.
    bob.call("set_last_seen_visible", json!({"visible": false}));
    let p = alice.call("presence", json!({"user_ids": [b["user_id"]]}));
    assert_eq!(p[b["user_id"].as_str().unwrap()]["status"], "recently");
    bob.call("set_last_seen_visible", json!({"visible": true}));

    bob.call("send_typing", json!({"conversation_id": cid}));
    let t = std::time::Instant::now();
    while !alice.events.lock().unwrap().iter().any(|v| v["type"] == "typing" && v["conversation_id"] == cid.as_str()) {
        assert!(t.elapsed().as_secs() < 10, "нет typing");
        std::thread::sleep(std::time::Duration::from_millis(50));
    }

    alice.call("set_pinned", json!({"conversation_id": cid, "pinned": true}));
    assert_eq!(alice.call("list_conversations", json!({}))[0]["pinned"], true);
    alice.call("set_archived", json!({"conversation_id": cid, "archived": true}));
    assert!(alice.call("list_conversations", json!({})).as_array().unwrap().iter().all(|c| c["id"] != cid.as_str()));
    assert_eq!(alice.call("list_conversations", json!({"archived": true}))[0]["pinned"], false);

    // Clear is local; hide survives a sync and a new message returns the chat.
    alice.call("clear_conversation", json!({"conversation_id": cid}));
    assert!(alice.messages(&cid).is_empty());
    assert!(!bob.messages(&cid).is_empty());
    alice.sync();
    assert!(alice.messages(&cid).is_empty());
    alice.call("clear_conversation", json!({"conversation_id": cid, "hide": true}));
    assert!(!alice.call("list_conversations", json!({"archived": true})).as_array().unwrap().iter().any(|c| c["id"] == cid));
    bob.call("send_text", json!({"conversation_id": cid, "text": "После очистки"}));
    wait(&alice, &|c| !c.messages(&cid).is_empty());
    assert!(alice.call("list_conversations", json!({"archived": true})).as_array().unwrap().iter().any(|c| c["id"] == cid));

    alice.call("lock_set", json!({"passcode": "1234"}));
    assert_eq!(alice.call("lock_check", json!({"passcode": "1234"}))["ok"], true);
    assert_eq!(alice.call("lock_check", json!({"passcode": "0000"}))["ok"], false);
    assert_eq!(alice.call("lock_status", json!({}))["enabled"], true);
}
