//! C ABI. Every call takes and returns UTF-8 JSON; returned strings are freed
//! with `nc_free`. Events arrive on a core thread through the callback.

use std::ffi::{CStr, CString, c_char, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};

use serde_json::{Value, json};

use crate::engine::Core;

pub type EventFn = extern "C" fn(ctx: *mut c_void, json: *const c_char);

struct Ctx(*mut c_void);
// The native side guarantees the context outlives the core and is thread-safe.
unsafe impl Send for Ctx {}
unsafe impl Sync for Ctx {}

impl Ctx {
    fn ptr(&self) -> *mut c_void {
        self.0
    }
}

fn to_c(s: String) -> *mut c_char {
    CString::new(s.replace('\0', "")).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

unsafe fn from_c<'a>(p: *const c_char) -> &'a str {
    if p.is_null() { "" } else { unsafe { CStr::from_ptr(p) }.to_str().unwrap_or("") }
}

pub fn wrap(result: crate::Result<Value>) -> String {
    match result {
        Ok(v) => json!({"ok": v}).to_string(),
        Err(e) => json!({"error": e.to_json()}).to_string(),
    }
}

/// Opens the core. `config_json`: {"data_dir": "...", "db_key": "<64 hex>", "foreground": true}.
/// Returns NULL and sets `*error_out` (free with nc_free) on failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nc_open(config_json: *const c_char, cb: EventFn, ctx: *mut c_void, error_out: *mut *mut c_char) -> *mut c_void {
    let ctx = Ctx(ctx);
    let res = catch_unwind(AssertUnwindSafe(|| {
        let cfg: Value = serde_json::from_str(unsafe { from_c(config_json) }).map_err(crate::Error::from)?;
        Core::open(
            &cfg,
            Box::new(move |s: &str| {
                let c = CString::new(s.replace('\0', "")).unwrap_or_default();
                cb(ctx.ptr(), c.as_ptr());
            }),
        )
    }));
    match res {
        Ok(Ok(core)) => Box::into_raw(Box::new(core)) as *mut c_void,
        Ok(Err(e)) => {
            if !error_out.is_null() {
                unsafe { *error_out = to_c(json!({"error": e.to_json()}).to_string()) };
            }
            std::ptr::null_mut()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Runs one method. May block on network; call it off the UI thread.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nc_call(core: *mut c_void, method: *const c_char, params_json: *const c_char) -> *mut c_char {
    if core.is_null() {
        return to_c(json!({"error": {"code": "closed", "message": "core closed"}}).to_string());
    }
    let core = unsafe { &*(core as *const Core) };
    let method = unsafe { from_c(method) }.to_string();
    let params = unsafe { from_c(params_json) }.to_string();
    let out = catch_unwind(AssertUnwindSafe(|| {
        let p: Value = if params.is_empty() { Value::Null } else { serde_json::from_str(&params).unwrap_or(Value::Null) };
        wrap(core.call(&method, &p))
    }))
    .unwrap_or_else(|_| json!({"error": {"code": "internal", "message": "Внутренняя ошибка"}}).to_string());
    to_c(out)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn nc_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}

/// Stops the worker and closes the database. The callback is not called afterwards.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nc_close(core: *mut c_void) {
    if !core.is_null() {
        drop(unsafe { Box::from_raw(core as *mut Core) });
    }
}
