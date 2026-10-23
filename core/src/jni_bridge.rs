//! JNI entry points for the Android app (`app.neochat.core.NativeCore`). Same
//! contract as the C ABI: JSON in, `{"ok": ...}` or `{"error": ...}` out.

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jlong, jstring};
use serde_json::{Value, json};

use crate::accounts::Core;
use crate::ffi::wrap;

fn read(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

/// Opens the core; events go to `listener.onEvent(String)` on a core thread.
/// Throws IllegalStateException with the error JSON on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_neochat_core_NativeCore_open<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, config: JString<'l>, listener: JObject<'l>) -> jlong {
    let cfg = read(&mut env, &config);
    let res = (|| -> crate::Result<Core> {
        let vm = env.get_java_vm().map_err(|_| crate::Error::Invalid("Не удалось подключить ядро".into()))?;
        let listener = env.new_global_ref(listener).map_err(|_| crate::Error::Invalid("Не удалось подключить ядро".into()))?;
        let cfg: Value = serde_json::from_str(&cfg)?;
        Core::open(
            &cfg,
            Box::new(move |s: &str| {
                let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
                if let Ok(js) = env.new_string(s) {
                    let _ = env.call_method(listener.as_obj(), "onEvent", "(Ljava/lang/String;)V", &[JValue::Object(&js)]);
                    let _ = env.delete_local_ref(js);
                }
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
            }),
        )
    })();
    match res {
        Ok(core) => Box::into_raw(Box::new(core)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", json!({"error": e.to_json()}).to_string());
            0
        }
    }
}

/// Runs one method. Blocks on network: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_neochat_core_NativeCore_call<'l>(mut env: JNIEnv<'l>, _c: JClass<'l>, handle: jlong, method: JString<'l>, params: JString<'l>) -> jstring {
    let method = read(&mut env, &method);
    let params = read(&mut env, &params);
    let out = if handle == 0 {
        json!({"error": {"code": "closed", "message": "core closed"}}).to_string()
    } else {
        let core = unsafe { &*(handle as *const Core) };
        catch_unwind(AssertUnwindSafe(|| {
            let p: Value = if params.is_empty() { Value::Null } else { serde_json::from_str(&params).unwrap_or(Value::Null) };
            wrap(core.call(&method, &p))
        }))
        .unwrap_or_else(|_| json!({"error": {"code": "internal", "message": "Внутренняя ошибка"}}).to_string())
    };
    env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_neochat_core_NativeCore_close<'l>(_env: JNIEnv<'l>, _c: JClass<'l>, handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Core) });
    }
}
