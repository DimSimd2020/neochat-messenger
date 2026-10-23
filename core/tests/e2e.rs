//! End-to-end tests against a running NeoChat server.
//!
//! Registration needs a confirmed mailbox, so run it through the Go test server, which confirms
//! addresses at example.test: `NEOCHAT_TEST_DATABASE_URL` (a disposable database, it is wiped) and
//! `NEOCHAT_NATIVE_TEST_COMMAND='["<path to this test binary>", "<test name>"]' go test ./internal/app -run TestNativeEmailFlow`
//! in `server`; the server address reaches the tests as NEOCHAT_E2E_SERVER.

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
    // Registration needs a confirmed mailbox; the test server confirms addresses at example.test by itself.
    let mail = c.call("registration_email_start", json!({"server": e.server, "email": format!("{name}@example.test")}));
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while c.call("registration_email_status", json!({"server": e.server, "ticket": mail["ticket"]}))["verified"] != true {
        assert!(std::time::Instant::now() < deadline, "the test mailbox did not confirm {name}");
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    let res = c.call(
        "register",
        json!({"server": e.server, "username": name, "display_name": "Тест", "password": "correct horse battery", "device_name": "test", "platform": "windows", "email_ticket": mail["ticket"]}),
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
fn profiles_mentions_and_username_067() {
    let Some(e) = env() else { return };
    let an = uname("profile_owner");
    let bn = uname("profile_peer");
    let (alice, _) = register(&e, &an);
    let (bob, _) = register(&e, &bn);
    let b = alice.call("resolve_contact", json!({"username":format!("@{bn}")}));
    let a = bob.call("resolve_contact", json!({"username":an}));
    let cid = alice.call("open_direct",json!({"user_id":b["user_id"]}))["id"].as_str().unwrap().to_string();
    let message = alice.call("send_text",json!({"conversation_id":cid,"text":format!("Пинг @{bn}")}));
    alice.sync(); bob.sync();
    assert_eq!(bob.call("get_conversation",json!({"conversation_id":cid}))["mention_count"],1);
    assert_eq!(bob.messages(&cid).last().unwrap()["unread_mention"],true);
    bob.call("send_text",json!({"conversation_id":cid,"text":"Ответ","reply_to":message["id"]}));
    assert_eq!(bob.call("get_conversation",json!({"conversation_id":cid}))["mention_count"],0);
    assert_eq!(bob.messages(&cid).iter().find(|m|m["id"]==message["id"]).unwrap()["unread_mention"],false);
    alice.call("set_profile_note",json!({"user_id":b["user_id"],"note":"Личная заметка"}));
    assert_eq!(alice.call("user_profile",json!({"user_id":b["user_id"]}))["note"],"Личная заметка");
    assert_eq!(bob.call("user_profile",json!({"user_id":a["user_id"]}))["note"],"");
    assert!(alice.try_call("set_profile_note",json!({"user_id":b["user_id"],"note":"я".repeat(101)})).is_err());
    let group=alice.call("create_group",json!({"title":"Общая группа","member_ids":[b["user_id"]]}));
    let gid=group["id"].as_str().unwrap(); alice.sync();bob.sync();
    assert_eq!(alice.call("user_profile",json!({"user_id":b["user_id"]}))["groups"].as_array().unwrap().len(),1);
    alice.call("send_text",json!({"conversation_id":gid,"text":"@everyone"}));alice.sync();bob.sync();
    assert_eq!(bob.call("get_conversation",json!({"conversation_id":gid}))["mention_count"],1);
    alice.call("mark_read",json!({"conversation_id":gid}));
    bob.call("send_text",json!({"conversation_id":gid,"text":"@everyone @here"}));bob.sync();alice.sync();
    assert_eq!(alice.call("get_conversation",json!({"conversation_id":gid}))["mention_count"],0);
    let renamed=uname("new_handle");
    alice.call("set_profile",json!({"username":renamed}));alice.sync();
    let second=Client::new();
    second.call("login",json!({"server":e.server,"username":renamed,"password":"correct horse battery","device_name":"new phone","platform":"ios"}));
    alice.sync();second.sync();
    assert_eq!(uid(&alice),uid(&second));
    assert_eq!(second.call("user_profile",json!({"user_id":b["user_id"]}))["note"],"Личная заметка");
    let avatar=PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/avatar.jpg");
    alice.call("set_profile_photo",json!({"path":avatar}));
    alice.call("set_profile_photo",json!({"path":avatar}));
    let photos=alice.call("profile_photos",json!({}));
    assert_eq!(photos.as_array().unwrap().len(),2);
    let photo=alice.call("profile_photo",json!({"user_id":uid(&alice),"photo_id":photos[0]["id"]}));
    assert_eq!(std::fs::read(photo["path"].as_str().unwrap()).unwrap(),include_bytes!("fixtures/avatar.jpg"));
    alice.call("delete_profile_photo",json!({"photo_id":photos[0]["id"]}));
    assert_eq!(alice.call("profile_photos",json!({})).as_array().unwrap().len(),1);
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

/// A message from a device the receiver cannot vouch for becomes a placeholder, but its ciphertext is
/// kept: once the device is trusted, decryption by hand, on schedule or from the server's feed brings it back.
#[test]
fn undecryptable_messages_are_retried() {
    let Some(e) = env() else { return };
    let aname = uname("alice");
    let bname = uname("bob");
    let (alice, _) = register(&e, &aname);
    let (bob, _) = register(&e, &bname);
    let aid = uid(&alice);
    // Bob learns Alice's device from her profile; Alice opens the chat.
    bob.call("resolve_contact", json!({"username": aname}));
    let contact = alice.call("resolve_contact", json!({"username": bname}));
    let cid = alice.call("open_direct", json!({"user_id": contact["user_id"]}))["id"].as_str().unwrap().to_string();
    let identity = |c: &Client| -> Vec<u8> {
        c.core.as_ref().unwrap().inner.db().conn.query_row("SELECT identity_key FROM known_devices WHERE user_id=?1", [&aid], |r| r.get(0)).unwrap()
    };
    let set_identity = |c: &Client, key: &[u8]| {
        c.core.as_ref().unwrap().inner.db().conn.execute("UPDATE known_devices SET identity_key=?2 WHERE user_id=?1", rusqlite::params![aid, key]).unwrap();
    };
    let count = |c: &Client, table: &str| -> i64 {
        c.core.as_ref().unwrap().inner.db().conn.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0)).unwrap()
    };
    let placeholders = |c: &Client| -> Vec<Value> { c.messages(&cid).into_iter().filter(|m| m["kind"] == "undecryptable").collect() };

    let real = identity(&bob);
    let wrong = vec![5u8; real.len()];

    // By hand: two messages arrive while Alice's device is not trusted.
    set_identity(&bob, &wrong);
    alice.call("send_text", json!({"conversation_id": cid, "text": "one"}));
    alice.call("send_text", json!({"conversation_id": cid, "text": "two"}));
    alice.sync();
    bob.sync();
    assert_eq!(placeholders(&bob).len(), 2, "{:?}", bob.messages(&cid));
    assert_eq!(count(&bob, "undecrypted"), 2);
    let first = placeholders(&bob)[0]["id"].as_str().unwrap().to_string();
    let again = bob.call("retry_decrypt", json!({"message_id": first}));
    assert_eq!(again["decrypted"], false, "still untrusted");
    assert!(again["message"].as_str().unwrap().starts_with("Пока не получилось: "), "the reason is reported: {again}");
    assert_eq!(placeholders(&bob).len(), 2);
    set_identity(&bob, &real);
    assert_eq!(bob.call("retry_decrypt", json!({"message_id": first}))["decrypted"], true);
    assert_eq!(bob.texts(&cid), vec!["one", "two"], "{:?}", bob.messages(&cid));
    assert!(placeholders(&bob).is_empty());
    assert_eq!(count(&bob, "undecrypted"), 0);

    // On schedule: the retry is not due yet, then it is.
    set_identity(&bob, &wrong);
    alice.call("send_text", json!({"conversation_id": cid, "text": "three"}));
    alice.sync();
    bob.sync();
    assert_eq!(placeholders(&bob).len(), 1);
    set_identity(&bob, &real);
    bob.sync();
    assert_eq!(placeholders(&bob).len(), 1, "the 20 minutes have not passed");
    bob.core.as_ref().unwrap().inner.db().conn.execute("UPDATE undecrypted SET next_at=0", []).unwrap();
    bob.sync();
    assert!(placeholders(&bob).is_empty(), "{:?}", bob.messages(&cid));
    assert_eq!(bob.texts(&cid).last().unwrap(), "three");

    // A placeholder from before the queue existed has no ciphertext: the feed supplies it again.
    set_identity(&bob, &wrong);
    alice.call("send_text", json!({"conversation_id": cid, "text": "four"}));
    alice.sync();
    bob.sync();
    assert_eq!(placeholders(&bob).len(), 1);
    {
        let db = bob.core.as_ref().unwrap().inner.db();
        db.conn.execute("DELETE FROM undecrypted", []).unwrap();
        db.conn.execute("DELETE FROM kv WHERE k='undecrypted_backfill'", []).unwrap();
    }
    set_identity(&bob, &real);
    let old = placeholders(&bob)[0]["id"].as_str().unwrap().to_string();
    assert_eq!(bob.call("retry_decrypt", json!({"message_id": old}))["decrypted"], true);
    assert_eq!(bob.texts(&cid).last().unwrap(), "four");

    // Deleting a placeholder drops its queue row, and nothing comes back later.
    set_identity(&bob, &wrong);
    alice.call("send_text", json!({"conversation_id": cid, "text": "five"}));
    alice.sync();
    bob.sync();
    let gone = placeholders(&bob)[0]["id"].as_str().unwrap().to_string();
    bob.call("delete_local_message", json!({"message_id": gone}));
    set_identity(&bob, &real);
    bob.call("retry_decrypt", json!({"message_id": "none"}));
    assert_eq!(count(&bob, "undecrypted"), 0);
    assert!(!bob.texts(&cid).contains(&"five".to_string()));
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
    assert_eq!(bob.texts(&gid), vec!["всем привет", "Тест был исключён владельцем Тест", "без Кэрол"]);
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
fn edit_message_roundtrip() {
    let Some(e) = env() else { return };
    let (alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);
    let contact = alice.call("resolve_contact", json!({"username": bname, "first_name": "Боб"}));
    let conv = alice.call("open_direct", json!({"user_id": contact["user_id"]}));
    let cid = conv["id"].as_str().unwrap().to_string();

    let sent = alice.call("send_text", json!({"conversation_id": cid, "text": "опечтка"}));
    let id = sent["id"].as_str().unwrap().to_string();
    alice.sync();
    bob.sync();
    // Only the sender edits; an empty text is refused.
    assert!(bob.try_call("edit_message", json!({"message_id": id, "text": "чужое"})).is_err());
    assert!(alice.try_call("edit_message", json!({"message_id": id, "text": "  "})).is_err());
    let edited = alice.call("edit_message", json!({"message_id": id, "text": "опечатка исправлена"}));
    assert_eq!(edited["text"], "опечатка исправлена");
    assert_eq!(edited["edited"], true);
    alice.sync();
    bob.sync();
    let seen = bob.messages(&cid).into_iter().find(|m| m["id"] == id).unwrap();
    assert_eq!(seen["text"], "опечатка исправлена", "{seen}");
    assert_eq!(seen["edited"], true);
    // A forged edit control from the other side changes nothing.
    bob.core.as_ref().unwrap().inner.enqueue_control(&cid, "edit", json!({"id": id, "text": "подделка"})).unwrap();
    bob.sync();
    alice.sync();
    let mine = alice.messages(&cid).into_iter().find(|m| m["id"] == id).unwrap();
    assert_eq!(mine["text"], "опечатка исправлена");
    let list = bob.call("list_conversations", json!({}));
    assert!(list.to_string().contains("опечатка исправлена"), "the chat preview follows the edit: {list}");
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
    // Clients send everyone except the caller: the admin must stay a member and an admin.
    let got = admin.call("group_set_members", json!({"conversation_id": gid, "member_ids": [uid(&owner), b, c]}));
    assert_eq!(got["role"], "admin");
    assert_eq!(got["member_count"], 4);
    bob.sync();
    carol.sync();
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["member_count"], 4);
    assert_eq!(carol.call("get_conversation", json!({"conversation_id": gid}))["role"], "member");
    // A member cannot change the list, an admin cannot remove another admin or name one.
    assert!(bob.try_call("group_set_members", json!({"conversation_id": gid, "member_ids": [a, b]})).is_err());

    // A group photo from the owner reaches members, including one added later, and can be removed.
    let photo = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/avatar.jpg");
    owner.sync();
    let set = owner.call("group_set_avatar", json!({"conversation_id": gid, "path": photo}));
    assert_eq!(std::fs::read(set["avatar_path"].as_str().unwrap()).unwrap(), include_bytes!("fixtures/avatar.jpg"));
    owner.sync();
    bob.sync();
    carol.sync();
    for m in [&bob, &carol] {
        let got = m.call("get_conversation", json!({"conversation_id": gid}));
        assert_eq!(std::fs::read(got["avatar_path"].as_str().unwrap()).unwrap(), include_bytes!("fixtures/avatar.jpg"));
    }
    assert!(bob.try_call("group_set_avatar", json!({"conversation_id": gid, "path": photo})).is_err());
    assert!(owner.try_call("group_set_avatar", json!({"conversation_id": gid, "path": PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml")})).is_err());
    owner.call("group_set_avatar", json!({"conversation_id": gid, "path": ""}));
    owner.sync();
    bob.sync();
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["avatar_path"], "");

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
fn group_service_lines_and_mute() {
    let Some(e) = env() else { return };
    let users: Vec<(Client, String)> = (0..4).map(|i| {
        let name = uname(&format!("s{i}"));
        (register(&e, &name).0, name)
    }).collect();
    let [(owner, _), (admin, an), (bob, bn), (carol, cn)] = <[_; 4]>::try_from(users).ok().unwrap();
    let id = |c: &Client, n: &str| c.call("resolve_contact", json!({"username": n}))["user_id"].as_str().unwrap().to_string();
    let (a, b) = (id(&owner, &an), id(&owner, &bn));
    let c = id(&admin, &cn);
    let gid = owner.call("create_group", json!({"title": "Служебные", "member_ids": [a, b], "admin_ids": [a]}))["id"].as_str().unwrap().to_string();
    let lines = |x: &Client| -> Vec<String> { x.messages(&gid).iter().filter(|m| m["kind"] == "service").filter_map(|m| m["text"].as_str().map(str::to_string)).collect() };

    // An admin adds Carol: everyone, Carol included, sees who did it.
    admin.sync();
    admin.call("group_set_members", json!({"conversation_id": gid, "member_ids": [uid(&owner), b, c]}));
    admin.sync();
    owner.sync();
    bob.sync();
    carol.sync();
    assert!(lines(&admin).last().unwrap().ends_with("был добавлен вами"), "{:?}", lines(&admin));
    assert!(lines(&bob).last().unwrap().ends_with("был добавлен администратором Тест"), "{:?}", lines(&bob));
    assert_eq!(lines(&carol).last().unwrap(), "Вы были добавлены администратором Тест");
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["unread"], 0);

    // The owner removes her; the rest read it.
    owner.sync();
    owner.call("group_set_members", json!({"conversation_id": gid, "member_ids": [a, b]}));
    owner.sync();
    admin.sync();
    bob.sync();
    assert_eq!(lines(&bob).last().unwrap(), "Тест был исключён владельцем Тест");
    assert!(lines(&owner).last().unwrap().ends_with("был исключён вами"));

    // Muting: ordinary members cannot, the owner can; the muted member cannot write.
    assert!(bob.try_call("group_mute", json!({"conversation_id": gid, "user_id": a, "minutes": 60})).is_err());
    owner.call("group_mute", json!({"conversation_id": gid, "user_id": b, "minutes": 60}));
    owner.sync();
    bob.sync();
    admin.sync();
    let bc = bob.call("get_conversation", json!({"conversation_id": gid}));
    assert_eq!(bc["can_send"], false);
    assert_ne!(bc["muted"], 0);
    assert_eq!(bc["notice"], "Вам запретили писать в этой группе");
    assert!(bob.try_call("send_text", json!({"conversation_id": gid, "text": "можно?"})).is_err());
    assert!(lines(&bob).last().unwrap().starts_with("Вы были заглушены владельцем Тест до "), "{:?}", lines(&bob));
    let seen = admin.call("get_conversation", json!({"conversation_id": gid}));
    assert!(seen["members"].as_array().unwrap().iter().any(|m| m["user_id"] == b.as_str() && m["muted_until"].as_i64().unwrap() > 0));
    assert!(admin.try_call("group_mute", json!({"conversation_id": gid, "user_id": uid(&owner), "minutes": 60})).is_err());

    // Released: writing works again and the group is told.
    owner.call("group_mute", json!({"conversation_id": gid, "user_id": b, "minutes": 0}));
    owner.sync();
    bob.sync();
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": gid}))["can_send"], true);
    bob.call("send_text", json!({"conversation_id": gid, "text": "снова пишу"}));
    assert_eq!(lines(&bob).last().unwrap(), "Владелец Тест снял ограничение с вас");
}

#[test]
fn profile_style_reaches_other_people() {
    let Some(e) = env() else { return };
    let (an, bn) = (uname("sa"), uname("sb"));
    let (alice, _) = register(&e, &an);
    let (bob, _) = register(&e, &bn);
    let b = alice.call("resolve_contact", json!({"username": bn}))["user_id"].as_str().unwrap().to_string();
    let a = bob.call("resolve_contact", json!({"username": an}))["user_id"].as_str().unwrap().to_string();
    let cid = alice.call("open_direct", json!({"user_id": b}))["id"].as_str().unwrap().to_string();
    bob.call("open_direct", json!({"user_id": a}));
    let gid = alice.call("create_group", json!({"title": "Цвета", "member_ids": [b]}))["id"].as_str().unwrap().to_string();

    // Nothing chosen yet: empty strings, not missing fields.
    assert_eq!(bob.call("user_profile", json!({"user_id": a}))["name_color"], "");
    assert!(alice.try_call("set_profile", json!({"name_color": "red"})).is_err());
    assert!(alice.try_call("set_profile", json!({"header": "#111111,#222222,#333333,#444444"})).is_err());

    alice.call("set_profile", json!({"name_color": "#FF8800", "header": "#112233,#445566"}));
    alice.sync();
    bob.sync();
    let status = alice.call("status", json!({}));
    assert_eq!((status["name_color"].as_str(), status["header"].as_str()), (Some("#ff8800"), Some("#112233,#445566")));
    let profile = bob.call("user_profile", json!({"user_id": a}));
    assert_eq!((profile["name_color"].as_str(), profile["header"].as_str()), (Some("#ff8800"), Some("#112233,#445566")));

    // Where names are shown: messages, quotes, members and the title of a direct chat.
    let first = alice.call("send_text", json!({"conversation_id": cid, "text": "привет"}));
    alice.call("send_text", json!({"conversation_id": gid, "text": "в группе"}));
    alice.sync();
    bob.sync();
    let reply = bob.call("send_text", json!({"conversation_id": cid, "text": "ответ", "reply_to": first["id"]}));
    bob.sync();
    alice.sync();
    let seen = bob.messages(&cid);
    assert_eq!(seen[0]["sender_color"], "#ff8800");
    assert_eq!(seen.iter().find(|m| m["id"] == reply["id"]).unwrap()["reply_to"]["sender_color"], "#ff8800");
    assert_eq!(bob.messages(&gid).last().unwrap()["sender_color"], "#ff8800");
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": cid}))["title_color"], "#ff8800");
    let members = bob.call("get_conversation", json!({"conversation_id": gid}))["members"].clone();
    assert_eq!(members.as_array().unwrap().iter().find(|m| m["user_id"] == a.as_str()).unwrap()["name_color"], "#ff8800");
    // My own messages carry my own colour too.
    assert_eq!(alice.messages(&cid)[0]["sender_color"], "#ff8800");
    assert_eq!(alice.messages(&cid).iter().find(|m| m["id"] == reply["id"]).unwrap()["sender_color"], "");

    // Clearing goes back to the default everywhere.
    alice.call("set_profile", json!({"name_color": "", "header": ""}));
    alice.sync();
    bob.sync();
    assert_eq!(bob.messages(&cid)[0]["sender_color"], "");
    assert_eq!(bob.call("user_profile", json!({"user_id": a}))["header"], "");
}

#[test]
fn pinned_messages_and_saved_chat() {
    let Some(e) = env() else { return };
    let (an, bn) = (uname("pa"), uname("pb"));
    let (alice, _) = register(&e, &an);
    let (bob, _) = register(&e, &bn);
    let alice2 = Client::new();
    alice2.call("login", json!({"server": e.server, "username": an, "password": "correct horse battery", "device_name": "phone", "platform": "android"}));
    alice.sync();
    alice2.sync();
    let b = alice.call("resolve_contact", json!({"username": bn}))["user_id"].as_str().unwrap().to_string();
    let cid = alice.call("open_direct", json!({"user_id": b}))["id"].as_str().unwrap().to_string();
    bob.call("resolve_contact", json!({"username": an}));
    bob.call("open_direct", json!({"user_id": uid(&alice)}));

    // Direct chat: either side pins; several pins, newest first, and unpinning one keeps the other.
    let m1 = alice.call("send_text", json!({"conversation_id": cid, "text": "первое"}))["id"].as_str().unwrap().to_string();
    let m2 = alice.call("send_text", json!({"conversation_id": cid, "text": "второе"}))["id"].as_str().unwrap().to_string();
    alice.sync();
    bob.sync();
    alice2.sync();
    bob.call("pin_message", json!({"message_id": m1, "on": true}));
    std::thread::sleep(std::time::Duration::from_millis(5));
    alice.call("pin_message", json!({"message_id": m2, "on": true}));
    bob.sync();
    alice.sync();
    bob.sync();
    alice.sync();
    alice2.sync();
    for c in [&alice, &bob, &alice2] {
        let pins = c.call("list_pins", json!({"conversation_id": cid}));
        let ids: Vec<&str> = pins.as_array().unwrap().iter().map(|m| m["id"].as_str().unwrap()).collect();
        assert_eq!(ids, vec![m2.as_str(), m1.as_str()], "{pins}");
        assert_eq!(c.call("get_conversation", json!({"conversation_id": cid}))["pin_count"], 2);
    }
    alice.call("pin_message", json!({"message_id": m2, "on": false}));
    alice.sync();
    bob.sync();
    let left = bob.call("list_pins", json!({"conversation_id": cid}));
    assert_eq!(left.as_array().unwrap().len(), 1);
    assert_eq!(left[0]["id"], m1.as_str());
    assert_eq!(left[0]["pinned"], true);

    // Group: members cannot pin; an admin's pin reaches everyone.
    let gid = alice.call("create_group", json!({"title": "Закрепы", "member_ids": [b]}))["id"].as_str().unwrap().to_string();
    let gm = alice.call("send_text", json!({"conversation_id": gid, "text": "правила"}))["id"].as_str().unwrap().to_string();
    alice.sync();
    bob.sync();
    assert!(bob.try_call("pin_message", json!({"message_id": gm, "on": true})).is_err());
    alice.call("pin_message", json!({"message_id": gm, "on": true}));
    alice.sync();
    bob.sync();
    assert_eq!(bob.call("list_pins", json!({"conversation_id": gid}))[0]["id"], gm.as_str());

    // Saved messages: created once, shared with the other device, takes copies of messages.
    let saved = alice.call("open_saved", json!({}));
    let sid = saved["id"].as_str().unwrap().to_string();
    assert_eq!(saved["title"], "Избранное");
    assert_eq!(saved["saved"], true);
    assert_eq!(alice.call("open_saved", json!({}))["id"], sid.as_str());
    alice.call("save_message", json!({"message_id": gm}));
    alice.call("send_text", json!({"conversation_id": sid, "text": "заметка"}));
    alice.sync();
    alice2.sync();
    alice2.sync();
    let on2 = alice2.call("get_conversation", json!({"conversation_id": sid}));
    assert_eq!(on2["saved"], true, "{on2}");
    assert_eq!(on2["title"], "Избранное");
    assert_eq!(alice2.call("open_saved", json!({}))["id"], sid.as_str());
    assert_eq!(alice2.texts(&sid), vec!["правила", "заметка"]);
    assert_eq!(alice.messages(&sid)[0]["fwd"]["name"].as_str().is_some(), true);
    // Nobody else sees it.
    bob.sync();
    assert!(bob.try_call("get_conversation", json!({"conversation_id": sid})).is_err());
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

    let avatar = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/avatar.jpg");
    bob.call("set_profile", json!({"bio": "Люблю чай"}));
    bob.call("set_avatar", json!({"path": avatar}));
    let photo = bob.call("status", json!({}))["avatar_path"].as_str().unwrap().to_string();
    assert_eq!(std::fs::read(photo).unwrap(), include_bytes!("fixtures/avatar.jpg"));
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
    assert_eq!(std::fs::read(info["avatar_path"].as_str().unwrap()).unwrap(), include_bytes!("fixtures/avatar.jpg"));
    assert_eq!(alice.call("get_conversation", json!({"conversation_id": cid}))["avatar_path"], info["avatar_path"]);
    bob.call("set_avatar", json!({"path": ""}));
    alice.sync();
    assert_eq!(alice.call("contact_info", json!({"user_id": b["user_id"]}))["avatar_path"], "");
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

/// Syncs until a call event matching `f` appears after `from`; returns it.
fn call_event(c: &Client, from: usize, f: &dyn Fn(&Value) -> bool) -> Value {
    let t = std::time::Instant::now();
    loop {
        if let Some(v) = c.events.lock().unwrap()[from..].iter().find(|v| (v["type"] == "call" || v["type"] == "call_media") && f(v)) {
            return v.clone();
        }
        assert!(t.elapsed().as_secs() < 20, "нет события звонка: {:?}", &c.events.lock().unwrap()[from..]);
        c.sync();
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

fn mark(c: &Client) -> usize {
    c.events.lock().unwrap().len()
}

fn call_logs(c: &Client, conv: &str) -> Vec<String> {
    c.messages(conv).iter().filter(|m| m["kind"] == "call").map(|m| m["text"].as_str().unwrap().to_string()).collect()
}

#[test]
fn calls_ring_answer_hangup_busy_and_log() {
    let Some(e) = env() else { return };
    let (alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);
    let cname = uname("carol");
    let (carol, _) = register(&e, &cname);
    let b = alice.call("resolve_contact", json!({"username": bname}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": cid, "text": "звоню"}));
    alice.sync();
    bob.sync();

    // Calls are one-to-one only.
    let g = alice.call("create_group", json!({"title": "Группа", "member_ids": [b["user_id"]]}));
    assert!(alice.try_call("call_start", json!({"conversation_id": g["id"]})).is_err());

    // Ring, answer, connect, camera off, hang up.
    let start = alice.call("call_start", json!({"conversation_id": cid, "video": true}));
    let id = start["call"]["call_id"].as_str().unwrap().to_string();
    assert!(start["ice_servers"].is_array());
    assert!(alice.try_call("call_start", json!({"conversation_id": cid})).is_err(), "второй звонок при активном");
    let (a0, b0) = (mark(&alice), mark(&bob));
    alice.call("call_offer", json!({"call_id": id, "sdp": "v=0 offer"}));
    alice.sync();
    let inc = call_event(&bob, b0, &|v| v["state"] == "incoming");
    assert_eq!((inc["call_id"].as_str().unwrap(), inc["video"].as_bool().unwrap(), inc["outgoing"].as_bool().unwrap()), (id.as_str(), true, false));
    assert_eq!(bob.call("call_current", json!({}))["state"], "incoming");
    let acc = bob.call("call_accept", json!({"call_id": id}));
    assert_eq!(acc["sdp"], "v=0 offer");
    bob.call("call_answer", json!({"call_id": id, "sdp": "v=0 answer"}));
    bob.sync();
    let ans = call_event(&alice, a0, &|v| v["state"] == "answered");
    assert_eq!(ans["sdp"], "v=0 answer");
    alice.call("call_connected", json!({"call_id": id}));
    bob.call("call_connected", json!({"call_id": id}));
    let b1 = mark(&bob);
    alice.call("call_media", json!({"call_id": id, "video": false}));
    alice.sync();
    assert_eq!(call_event(&bob, b1, &|v| v["type"] == "call_media")["video"], false);
    std::thread::sleep(std::time::Duration::from_millis(1100));
    let a1 = mark(&alice);
    bob.call("call_hangup", json!({"call_id": id}));
    bob.sync();
    assert_eq!(call_event(&alice, a1, &|v| v["state"] == "ended")["reason"], "hangup");
    assert_eq!(alice.call("call_current", json!({})), Value::Null);
    assert!(call_logs(&alice, &cid)[0].starts_with("Исходящий видеозвонок · 0:0"), "{:?}", call_logs(&alice, &cid));
    assert!(call_logs(&bob, &cid)[0].starts_with("Входящий видеозвонок · 0:0"), "{:?}", call_logs(&bob, &cid));

    // Decline.
    let id2 = alice.call("call_start", json!({"conversation_id": cid}))["call"]["call_id"].as_str().unwrap().to_string();
    let (a2, b2) = (mark(&alice), mark(&bob));
    alice.call("call_offer", json!({"call_id": id2, "sdp": "v=0"}));
    alice.sync();
    call_event(&bob, b2, &|v| v["state"] == "incoming");
    bob.call("call_hangup", json!({"call_id": id2}));
    bob.sync();
    assert_eq!(call_event(&alice, a2, &|v| v["state"] == "ended")["reason"], "declined");
    assert_eq!(call_logs(&alice, &cid)[1], "Отклонённый звонок");

    // Busy: Carol calls Bob while Alice's call rings there.
    let c = carol.call("resolve_contact", json!({"username": bname}));
    let ccid = carol.call("open_direct", json!({"user_id": c["user_id"]}))["id"].as_str().unwrap().to_string();
    carol.call("send_text", json!({"conversation_id": ccid, "text": "привет"}));
    carol.sync();
    bob.sync();
    let id3 = alice.call("call_start", json!({"conversation_id": cid}))["call"]["call_id"].as_str().unwrap().to_string();
    let b3 = mark(&bob);
    alice.call("call_offer", json!({"call_id": id3, "sdp": "v=0"}));
    alice.sync();
    call_event(&bob, b3, &|v| v["state"] == "incoming");
    let idc = carol.call("call_start", json!({"conversation_id": ccid}))["call"]["call_id"].as_str().unwrap().to_string();
    let c0 = mark(&carol);
    carol.call("call_offer", json!({"call_id": idc, "sdp": "v=0"}));
    carol.sync();
    bob.sync();
    bob.sync();
    assert_eq!(call_event(&carol, c0, &|v| v["state"] == "ended")["reason"], "busy");
    assert_eq!(call_logs(&carol, &ccid), vec!["Абонент занят"]);
    assert_eq!(call_logs(&bob, &ccid), vec!["Пропущенный звонок"]);
    assert_eq!(bob.call("call_current", json!({}))["call_id"], id3.as_str(), "звонок Алисы продолжает звонить");

    // Cancelled before Bob synced: no ring, only a missed call with a notification.
    let a4 = mark(&alice);
    alice.call("call_hangup", json!({"call_id": id3}));
    alice.sync();
    call_event(&alice, a4, &|v| v["state"] == "ended" && v["reason"] == "cancelled");
    bob.sync();
    assert_eq!(bob.call("call_current", json!({})), Value::Null);
    let id5 = alice.call("call_start", json!({"conversation_id": cid}))["call"]["call_id"].as_str().unwrap().to_string();
    alice.call("call_offer", json!({"call_id": id5, "sdp": "v=0"}));
    alice.call("call_hangup", json!({"call_id": id5}));
    alice.sync();
    let b5 = mark(&bob);
    let unread = bob.call("get_conversation", json!({"conversation_id": cid}))["unread"].as_i64().unwrap();
    bob.sync();
    assert!(!bob.events.lock().unwrap()[b5..].iter().any(|v| v["type"] == "call" && v["state"] == "incoming"), "отменённый звонок не должен звонить");
    assert!(bob.events.lock().unwrap()[b5..].iter().any(|v| v["type"] == "notify" && v["text"] == "Пропущенный звонок"));
    assert_eq!(bob.call("get_conversation", json!({"conversation_id": cid}))["unread"].as_i64().unwrap(), unread + 1);
    assert_eq!(call_logs(&bob, &cid).last().unwrap(), "Пропущенный звонок");
    assert!(bob.call("list_conversations", json!({}))[0]["last_preview"].as_str().unwrap().starts_with("📞"));
    let recent = bob.call("list_calls", json!({}));
    assert_eq!(recent[0]["text"], "Пропущенный звонок");
    assert_eq!(recent[0]["missed"], true);
    assert_eq!(recent[0]["conversation_id"], cid.as_str());
    assert_eq!(recent.as_array().unwrap().len(), call_logs(&bob, &cid).len() + call_logs(&bob, &ccid).len());
}

#[test]
fn call_answered_on_one_device_stops_the_other() {
    let Some(e) = env() else { return };
    let (alice, _) = register(&e, &uname("alice"));
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);
    let bob2 = Client::new();
    bob2.call("login", json!({"server": e.server, "username": bname, "password": "correct horse battery", "device_name": "phone", "platform": "android"}));
    bob.sync();
    let b = alice.call("resolve_contact", json!({"username": bname}));
    let cid = alice.call("open_direct", json!({"user_id": b["user_id"]}))["id"].as_str().unwrap().to_string();
    alice.call("send_text", json!({"conversation_id": cid, "text": "привет"}));
    alice.sync();
    bob.sync();
    bob2.sync();

    let id = alice.call("call_start", json!({"conversation_id": cid}))["call"]["call_id"].as_str().unwrap().to_string();
    let (a0, b0, c0) = (mark(&alice), mark(&bob), mark(&bob2));
    alice.call("call_offer", json!({"call_id": id, "sdp": "v=0"}));
    alice.sync();
    call_event(&bob, b0, &|v| v["state"] == "incoming");
    call_event(&bob2, c0, &|v| v["state"] == "incoming");
    bob2.call("call_accept", json!({"call_id": id}));
    bob2.call("call_answer", json!({"call_id": id, "sdp": "v=0 phone"}));
    bob2.sync();
    assert_eq!(call_event(&bob, b0, &|v| v["state"] == "ended")["reason"], "answered_elsewhere");
    assert_eq!(call_event(&alice, a0, &|v| v["state"] == "answered")["sdp"], "v=0 phone");
    // The desktop that did not answer writes no missed call.
    assert!(call_logs(&bob, &cid).is_empty());

    // The hangup reaches the device that answered.
    let a1 = mark(&alice);
    alice.call("call_connected", json!({"call_id": id}));
    alice.call("call_hangup", json!({"call_id": id}));
    alice.sync();
    let c1 = mark(&bob2);
    call_event(&bob2, c1, &|v| v["state"] == "ended" && v["reason"] == "hangup");
    assert!(call_event(&alice, a1, &|v| v["state"] == "ended")["reason"] == "hangup");
    assert_eq!(call_logs(&bob2, &cid), vec!["Входящий звонок"]);
}

#[test]
fn own_devices_share_contacts_pins_settings_and_avatar() {
    let Some(e) = env() else { return };
    let aname = uname("alice");
    let (alice, _) = register(&e, &aname);
    let bname = uname("bob");
    let (bob, _) = register(&e, &bname);
    let (carol, _) = register(&e, &uname("carol"));
    let alice2 = Client::new();
    alice2.call(
        "login",
        json!({"server": e.server, "username": aname, "password": "correct horse battery", "device_name": "phone", "platform": "android"}),
    );
    alice.sync();
    alice2.sync();
    let contact = |c: &Client, id: &str| c.call("list_contacts", json!({})).as_array().unwrap().iter().find(|x| x["user_id"] == id).cloned();

    // A contact added and named on one device appears with that name on the other.
    let b = alice.call("resolve_contact", json!({"username": bname, "first_name": "Борис", "last_name": "Тестов"}));
    let bid = b["user_id"].as_str().unwrap().to_string();
    let cid = alice.call("open_direct", json!({"user_id": bid}))["id"].as_str().unwrap().to_string();
    alice.call("verify_contact", json!({"user_id": bid}));
    alice.call("set_pinned", json!({"conversation_id": cid, "pinned": true}));
    alice.call("set_settings", json!({"quick_reaction": "🔥", "read_receipts": false}));
    alice.sync();
    alice2.sync();
    let on2 = contact(&alice2, &bid).expect("contact synced");
    assert_eq!(on2["display_name"], "Борис Тестов");
    assert_eq!(on2["verified"], true);
    let settings = alice2.call("get_settings", json!({}));
    assert_eq!(settings["quick_reaction"], "🔥");
    assert_eq!(settings["read_receipts"], false);

    // The chat reaches the second device later; the pin made earlier applies to it.
    bob.call("resolve_contact", json!({"username": aname}));
    bob.call("open_direct", json!({"user_id": uid(&alice)}));
    bob.call("send_text", json!({"conversation_id": cid, "text": "привет"}));
    bob.sync();
    alice2.sync();
    alice2.sync();
    assert_eq!(alice2.call("get_conversation", json!({"conversation_id": cid}))["pinned"], true);

    // The contact's own key reached the second device: messages still flow.
    alice2.call("send_text", json!({"conversation_id": cid, "text": "со второго"}));
    alice2.sync();
    bob.sync();
    assert!(bob.texts(&cid).contains(&"со второго".to_string()));

    // Renames, unpinning, archiving and removal travel back.
    alice2.call("update_contact", json!({"user_id": bid, "first_name": "Боб", "last_name": ""}));
    alice2.call("set_pinned", json!({"conversation_id": cid, "pinned": false}));
    alice2.call("set_archived", json!({"conversation_id": cid, "archived": true}));
    alice2.sync();
    alice.sync();
    assert_eq!(contact(&alice, &bid).unwrap()["display_name"], "Боб");
    let conv = alice.call("get_conversation", json!({"conversation_id": cid}));
    assert_eq!(conv["pinned"], false);
    assert_eq!(conv["in_archive"], true);

    // Changes made on both devices before either syncs are merged, not lost.
    let c = carol.call("status", json!({}))["username"].as_str().unwrap().to_string();
    alice.call("resolve_contact", json!({"username": c}));
    alice2.call("delete_contact", json!({"user_id": bid}));
    alice.sync();
    alice2.sync();
    alice.sync();
    let cid_carol = uid(&carol);
    assert!(contact(&alice2, &cid_carol).is_some());
    assert!(contact(&alice, &bid).is_none());

    // A new profile photo on one device is cached on the other.
    let avatar = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/avatar.jpg");
    alice.call("set_avatar", json!({"path": avatar}));
    alice2.sync();
    let photo = alice2.call("status", json!({}))["avatar_path"].as_str().unwrap().to_string();
    assert_eq!(std::fs::read(photo).unwrap(), include_bytes!("fixtures/avatar.jpg"));

    // Release notes: skipped on a fresh sign-in, shown once after an update.
    let notes = |c: &Client| c.call("whats_new", json!({"version": "0.6.3", "platform": "ios"}));
    assert_eq!(notes(&alice)["show"], false);
    alice.call("whats_new_seen", json!({"version": "0.6.2"}));
    let w = notes(&alice);
    assert_eq!(w["show"], true);
    let items: Vec<&str> = w["items"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
    assert!(items.iter().any(|t| t.contains("Смахните сообщение влево")));
    assert!(!items.iter().any(|t| t.contains("NeoChat на связи")));
    alice.call("whats_new_seen", json!({"version": "0.6.3"}));
    assert_eq!(notes(&alice)["show"], false);
}
