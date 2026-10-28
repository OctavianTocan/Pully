//! Secrets never leave in model output, activity or errors. Any environment variable whose name says
//! TOKEN, KEY, SECRET or PASSWORD is redacted wherever its value appears; the provider keys below,
//! and the host's own list ([`crate::Config::secrets`]), are redacted whatever their length.
use serde_json::{json, Value};

pub use crate::preview::safe_preview;

/// Provider and service keys a CLI or tool could echo back.
pub(crate) const KNOWN_SECRETS: &[&str] = &[
    "ANTHROPIC_API_KEY",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "OPENAI_API_KEY",
    "GITHUB_TOKEN",
    "GH_TOKEN",
    "TYPESAFE_API_KEY",
];

/// Whether `name` is redacted whatever its value's length.
pub(crate) fn known_secret(name: &str) -> bool {
    KNOWN_SECRETS.contains(&name)
        || crate::config::try_config().is_some_and(|config| config.secrets.contains(&name))
}

fn secret_name(name: &str) -> bool {
    let name = name.to_ascii_uppercase();
    ["TOKEN", "KEY", "SECRET", "PASSWORD"]
        .iter()
        .any(|part| name.contains(part))
}

/// `s` with every configured secret replaced by `[redacted]`.
pub fn sanitize(s: &str) -> String {
    let mut out = s.to_owned();
    for (name, value) in crate::rt::env::vars() {
        if !value.is_empty() && known_secret(&name) {
            out = out.replace(&value, "[redacted]")
        }
    }
    for (name, value) in crate::rt::env::vars() {
        if value.len() >= 6 && secret_name(&name) {
            out = out.replace(&value, "[redacted]");
        }
    }
    out
}

/// Redacts string values and object keys without serializing first, so escaped secrets stay
/// protected.
pub fn sanitize_value(value: &Value) -> Value {
    match value {
        Value::String(value) => json!(sanitize(value)),
        Value::Array(values) => Value::Array(values.iter().map(sanitize_value).collect()),
        Value::Object(values) => Value::Object(
            values
                .iter()
                .map(|(key, value)| (sanitize(key), sanitize_value(value)))
                .collect(),
        ),
        value => value.clone(),
    }
}

/// [`sanitize`] for text that is still arriving: an unfinished secret prefix at the end is held
/// back, so streaming cannot leak a key across chunks.
pub fn safe_stream_text(s: &str) -> String {
    let mut cut = s.len();
    for (name, value) in crate::rt::env::vars() {
        if value.is_empty() || !secret_name(&name) {
            continue;
        }
        for (index, _) in value.char_indices().skip(1) {
            if s.ends_with(&value[..index]) {
                cut = cut.min(s.len() - index);
            }
        }
    }
    sanitize(&s[..cut])
}

/// An error's message with secrets redacted and at most 1,200 characters, for any answer that
/// leaves the server.
pub fn safe_error(error: &anyhow::Error) -> String {
    let mut message = error.to_string();
    for (name, value) in crate::rt::env::vars() {
        if value.len() >= 4 && secret_name(&name) {
            message = message.replace(&value, "[redacted]");
        }
    }
    message.chars().take(1200).collect()
}

/// Where the last `limit` characters of `text` start, found without decoding or copying the text
/// before them.
pub fn tail_start(text: &str, limit: usize) -> usize {
    text.char_indices()
        .rev()
        .nth(limit - 1)
        .map_or(0, |(at, _)| at)
}
