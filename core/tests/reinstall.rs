//! Focused native-core check, run against TestNativeEmailFlow's disposable API.
use neocore::Core;
use serde_json::{Value, json};

#[test]
fn reinstall_keeps_one_active_device() {
    let Ok(server) = std::env::var("NEOCHAT_E2E_SERVER") else { return };
    let root = std::env::temp_dir().join(format!("neochat-reinstall-{}", uuid::Uuid::new_v4()));
    let open = |slot: &str, installation: &str| {
        Core::open(&json!({"data_dir":root.join(slot),"db_key":hex::encode(rand::random::<[u8;32]>()),"foreground":false,"installation_id":installation}),Box::new(|_|{})).unwrap()
    };
    let call = |core: &Core, method: &str, params: Value| core.call(method,&params).unwrap_or_else(|error|panic!("{method}: {error}"));
    let username = format!("reinstall_{}", &uuid::Uuid::new_v4().simple().to_string()[..12]);
    let password = "local fixture password";
    let first = open("first","same-physical-device");
    let mail = call(&first,"registration_email_start",json!({"server":server,"email":format!("{username}@example.test")}));
    let deadline = std::time::Instant::now()+std::time::Duration::from_secs(5);
    loop {
        let status = call(&first,"registration_email_status",json!({"server":server,"ticket":mail["ticket"]}));
        if status["verified"] == true { break }
        assert!(std::time::Instant::now()<deadline,"fixture email not confirmed");
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    call(&first,"register",json!({"server":server,"username":username,"display_name":"Reinstall fixture","password":password,"email_ticket":mail["ticket"],"device_name":"Same name","platform":"linux"}));
    drop(first);
    for slot in ["second","third"] {
        let current = open(slot,"same-physical-device");
        call(&current,"login",json!({"server":server,"username":username,"password":password,"device_name":"Same name","platform":"linux"}));
        let devices = call(&current,"devices",json!({}));
        assert_eq!(devices["devices"].as_array().unwrap().len(),1,"reinstall duplicated the device");
    }
    let other = open("other","another-physical-device");
    call(&other,"login",json!({"server":server,"username":username,"password":password,"device_name":"Same name","platform":"linux"}));
    assert_eq!(call(&other,"devices",json!({}))["devices"].as_array().unwrap().len(),2,"device names incorrectly merged different installations");
    drop(other);
    assert!(root.starts_with(std::env::temp_dir()));
    // Windows can keep SQLite files busy briefly after the worker has stopped.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while let Err(error) = std::fs::remove_dir_all(&root) {
        assert!(std::time::Instant::now() < deadline, "cannot remove fixture: {error}");
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}
