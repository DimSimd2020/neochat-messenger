//! NeoChat client core shared by the Qt, Android and iOS applications.
//!
//! Native code calls a small C ABI with JSON in and out (see `include/neocore.h`).
//! Cryptography is libsignal (PQXDH + Double/Triple Ratchet); the core never
//! implements primitives itself and never falls back to plaintext.

mod api;
mod conv;
mod engine;
mod error;
mod files;
mod keys;
mod outbox;
mod store;
mod sync;
mod views;

pub mod ffi;
#[cfg(target_os = "android")]
mod jni_bridge;

pub use engine::Core;
pub use error::{Error, Result};
