//! Claude Code and Codex on each person's own subscription sign-in, and Gemini embeddings, for
//! any server that wants them: Pully's (`backend/`) and ThyMind's. A host names its volumes,
//! image and messages once with [`configure`]; everything else is the same code for both.
//!
//! - [`login`]: the CLIs' own sign-in (`claude auth login --claudeai`, `codex login --device-auth`),
//!   status and sign-out, per login.
//! - [`process`]: runs, model lists and efforts, in Docker on a native server or as plain
//!   processes inside the hosted agent container.
//! - [`remote`] and [`runner`]: the hosted server's way to a login's own agent container, and the
//!   server that answers in it.
//! - [`embed`]: Gemini embedding requests and answers, stored vectors and cosine similarity.
//! - [`rt`], [`redact`]: the runtime shim that lets all of it build for Workers, and the secret
//!   redaction every model output goes through.

// Code that only the native-only modules use is dead in the Worker build, and the Worker's
// `rt::process` makes a spawned child impossible, so these lints only mean something natively.
#![cfg_attr(
    target_arch = "wasm32",
    allow(dead_code, unused_imports, irrefutable_let_patterns, unreachable_code)
)]

#[cfg(feature = "agents")]
pub mod config;
#[cfg(feature = "embed")]
pub mod embed;
#[cfg(feature = "agents")]
pub mod login;
#[cfg(feature = "agents")]
mod preview;
#[cfg(feature = "agents")]
pub mod process;
#[cfg(feature = "agents")]
pub mod redact;
#[cfg(feature = "agents")]
pub mod remote;
#[cfg(feature = "agents")]
pub mod rt;
#[cfg(all(feature = "runner", not(target_arch = "wasm32")))]
pub mod runner;
#[cfg(feature = "agents")]
pub mod schema;

#[cfg(feature = "agents")]
pub use config::{config, configure, Config, Preparation, Tools};

/// Milliseconds since the Unix epoch, on both hosts.
#[cfg(feature = "agents")]
pub fn now_ms() -> u64 {
    web_time::SystemTime::now()
        .duration_since(web_time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
