//! NeoChat client core shared by the Qt, Android and iOS applications.
//!
//! Native code calls a small C ABI with JSON in and out (see `include/neocore.h`).
//! Cryptography is libsignal (PQXDH + Double/Triple Ratchet); the core never
//! implements primitives itself and never falls back to plaintext.

mod api;
mod accounts;
mod avatar;
mod backup;
mod calls;
mod conv;
mod engine;
mod error;
mod files;
mod groups;
mod markdown;
mod keys;
mod outbox;
mod profiles;
mod mentions;
mod social;
mod state;
mod store;
mod sync;
mod update;
mod views;
mod whatsnew;

pub mod ffi;
#[cfg(target_os = "android")]
mod jni_bridge;

pub use engine::Core;
pub use error::{Error, Result};
