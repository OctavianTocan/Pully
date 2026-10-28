// Code that only the native-only modules use is dead in the Worker build, and the Worker's
// `rt::process` makes a spawned child impossible, so these lints only mean something natively.
#![cfg_attr(
    target_arch = "wasm32",
    allow(dead_code, unused_imports, irrefutable_let_patterns, unreachable_code)
)]
pub mod account;
pub mod agent_api;
pub mod agent_login;
pub mod agent_process;
pub mod agent_remote;
#[cfg(not(target_arch = "wasm32"))]
pub mod agent_runner;
pub mod ai;
pub mod analytics;
pub mod brief;
pub mod feedback;
pub mod github_stats;
pub mod google_sign_in;
pub mod host;
pub(crate) mod identity;
pub mod jev;
mod lz;
mod merge_risk;
pub mod net;
pub(crate) mod personal_auth;
#[cfg(not(target_arch = "wasm32"))]
pub(crate) mod personal_provider;
pub mod posted;
pub mod presentation;
pub mod public_api;
pub mod public_server;
pub mod review;
mod review_experiments;
mod rpc_validation;
pub mod rt;
pub mod scheduler;
pub mod source;
pub mod store;
pub mod sync;
pub mod transport;
use anyhow::{bail, Result};
use serde_json::Value;
use std::{path::PathBuf, sync::Arc};

#[derive(Clone)]
pub struct AppState {
    pub(crate) identity: identity::RequestIdentity,
    /// Granted only by the canonical private personal-session workspace router.
    pub(crate) personal_workspace: bool,
    pub(crate) github_origin: url::Url,
    pub accounts: account::Accounts,
    pub google_sign_in: Arc<google_sign_in::GoogleSignIn>,
    pub host: host::ServerHost,
    pub posted: posted::PostedStore,
    pub feedback: feedback::FeedbackStore,
    pub github_stats: github_stats::Cache,
    account_seed: Arc<tokio::sync::OnceCell<bool>>,
    pub http: reqwest::Client,
    pub jobs: ai::Jobs,
    pub agent_logins: Arc<agent_login::Logins>,
    pub analytics: analytics::Analytics,
    pub(crate) diff_cache: Arc<std::sync::Mutex<review::DiffCache>>,
}
impl AppState {
    pub async fn ensure_account(&self) -> Result<bool> {
        if !self.identity.is_legacy() {
            bail!("Legacy account sign-in is not available in this context");
        }
        self.account_seed
            .get_or_try_init(|| async { Ok(self.accounts.seed_env().await?.is_some()) })
            .await
            .copied()
    }
    #[cfg(not(target_arch = "wasm32"))]
    pub fn new(cwd: PathBuf, data: PathBuf) -> Result<Arc<Self>> {
        let store = store::Store::new(data)?;
        Self::from_host(host::ServerHost::new(store, Some(cwd), None))
    }
    pub fn from_host(host: host::ServerHost) -> Result<Arc<Self>> {
        Self::from_identity_host(host, identity::RequestIdentity::LegacyPrivate)
    }
    /// Like [`AppState::from_host`], recording usage analytics in `analytics`.
    pub fn from_host_with_analytics(
        host: host::ServerHost,
        analytics: analytics::Analytics,
    ) -> Result<Arc<Self>> {
        Self::build(host, identity::RequestIdentity::LegacyPrivate, analytics)
    }
    pub(crate) fn from_identity_host(
        host: host::ServerHost,
        identity: identity::RequestIdentity,
    ) -> Result<Arc<Self>> {
        let analytics = if identity.is_legacy() {
            analytics::Analytics::new()
        } else {
            analytics::Analytics::in_records(host.storage.clone())
        };
        Self::build(host, identity, analytics)
    }
    pub(crate) fn build(
        host: host::ServerHost,
        identity: identity::RequestIdentity,
        analytics: analytics::Analytics,
    ) -> Result<Arc<Self>> {
        let store = host.storage.clone();
        Ok(Arc::new(Self {
            identity,
            personal_workspace: false,
            github_origin: url::Url::parse("https://api.github.com/")?,
            host,
            accounts: account::Accounts::new(store.clone()),
            google_sign_in: Arc::new(google_sign_in::GoogleSignIn::new()?),
            posted: posted::PostedStore::new(store.clone()),
            feedback: feedback::FeedbackStore::new(store.clone()),
            github_stats: github_stats::Cache::default(),
            account_seed: Arc::new(tokio::sync::OnceCell::new()),
            http: crate::net::client()
                .timeout(std::time::Duration::from_secs(60))
                .build()?,
            jobs: ai::Jobs::default(),
            agent_logins: Default::default(),
            analytics,
            diff_cache: Arc::new(std::sync::Mutex::new(review::DiffCache::default())),
        }))
    }

    pub(crate) fn authorize_subscription(
        &self,
        login: &agent_login::AgentLogin,
        engine: &str,
    ) -> Result<()> {
        self.identity.ensure_current()?;
        if self.identity.is_legacy() {
            return Ok(());
        }
        anyhow::ensure!(self.personal_workspace, "Connect a personal AI provider before starting work");
        if engine == "pi" && self.pi_owner() {
            return Ok(());
        }
        anyhow::ensure!(
            matches!(login, agent_login::AgentLogin::Account(id) if self.identity.account_id().ok() == Some(id.as_str()))
                && matches!(engine, "claude" | "claude-code" | "codex"),
            "This workspace uses only your own Claude Code or Codex sign-in"
        );
        Ok(())
    }
}
impl AppState {
    /// Whether this personal workspace belongs to the one GitHub account `PULLY_PI_GITHUB_ID` names, the only account
    /// that may run Pi on the server's own proxy (Tavi's recording of 26 Oct 2026, 02:21 to 02:36).
    fn pi_owner(&self) -> bool {
        self.personal_workspace && is_pi_owner(crate::rt::env::var("PULLY_PI_GITHUB_ID").ok().as_deref(), self.identity.github_id())
    }
}

/// True when `setting` is a GitHub user id and `github_id` is that id; an unset or malformed setting admits nobody.
fn is_pi_owner(setting: Option<&str>, github_id: Option<u64>) -> bool {
    let owner = setting.and_then(|id| id.trim().parse::<u64>().ok());
    owner.is_some() && github_id == owner
}

#[cfg(test)]
#[test]
fn only_the_named_github_account_runs_pi() {
    assert!(is_pi_owner(Some("42"), Some(42)));
    assert!(is_pi_owner(Some(" 42 "), Some(42)));
    assert!(!is_pi_owner(Some("42"), Some(43)));
    assert!(!is_pi_owner(Some("42"), None));
    assert!(!is_pi_owner(None, Some(42)));
    assert!(!is_pi_owner(Some(""), Some(42)));
    assert!(!is_pi_owner(Some("octaviantocan"), Some(42)));
}

pub fn encode_component(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

pub(crate) fn decode_component(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let high = (bytes[i + 1] as char).to_digit(16)? as u8;
            let low = (bytes[i + 2] as char).to_digit(16)? as u8;
            out.push(high * 16 + low);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

// ECMAScript String.trim includes BOM and excludes Unicode NEXT LINE.
pub(crate) fn js_whitespace(c: char) -> bool {
    matches!(c, '\u{0009}'..='\u{000d}' | '\u{0020}' | '\u{00a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}' | '\u{feff}')
}
// Bound UTF-16 units without emitting an unpaired surrogate.
pub(crate) fn truncate_utf16(chars: impl Iterator<Item = char>, max: usize) -> String {
    let mut n = 0;
    chars
        .take_while(|c| {
            n += c.len_utf16();
            n <= max
        })
        .collect()
}
pub fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}
pub fn required<'a>(v: &'a Value, key: &str) -> Result<&'a str> {
    let s = text(v, key);
    if s.is_empty() {
        bail!("{key} is required")
    }
    Ok(s)
}
pub(crate) fn omit_nulls(value: &mut Value) {
    match value {
        Value::Object(map) => {
            map.retain(|_, v| !v.is_null());
            for v in map.values_mut() {
                omit_nulls(v);
            }
        }
        Value::Array(array) => {
            for v in array {
                omit_nulls(v);
            }
        }
        _ => {}
    }
}
// Gregorian civil date conversion, independent of the runtime timezone.
pub(crate) fn iso_ms(ms: u64) -> String {
    let days = (ms / 86400000) as i64;
    let z = days + 719468;
    let era = z / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let mut year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    year += if month <= 2 { 1 } else { 0 };
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        ms / 3600000 % 24,
        ms / 60000 % 60,
        ms / 1000 % 60,
        ms % 1000
    )
}
pub fn now_ms() -> u64 {
    web_time::SystemTime::now()
        .duration_since(web_time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub async fn dispatch(state: &Arc<AppState>, name: &str, args: Value) -> Result<Value> {
    dispatch_as(state, name, args, &agent_login::AgentLogin::Server).await
}
/// `login` is the verified Pully account of the request, whose own CLI sign-ins AI work uses.
pub async fn dispatch_as(
    state: &Arc<AppState>,
    name: &str,
    args: Value,
    login: &agent_login::AgentLogin,
) -> Result<Value> {
    let start = web_time::Instant::now();
    let result = match rpc_validation::arguments(name, args) {
        Ok(args) => dispatch_inner(state, name, args, login)
            .await
            .and_then(|value| rpc_validation::result(name, value)),
        Err(error) => Err(error),
    };
    // A change-feed wait is held for up to 25 s by design; timing it would only swamp the call timings.
    if name != "sync-wait" {
        let _=state.analytics.record("rpc.call",serde_json::json!({"method":name,"ms":start.elapsed().as_millis() as u64,"status":if result.is_ok(){"ok"}else{"error"}})).await;
    }
    result
}
async fn dispatch_inner(
    state: &Arc<AppState>,
    name: &str,
    args: Value,
    login: &agent_login::AgentLogin,
) -> Result<Value> {
    if name == "github-stats" {
        return github_stats::stats(state, args["fresh"] == true).await;
    }
    if name == "analytics-summary" {
        return state.analytics.summary(args).await;
    }
    if name == "sync-wait" {
        return sync::wait(state, &args).await;
    }
    if let Some(r) = presentation::handle(name, &args) {
        return r;
    }
    if let Some(r) = source::handle(state, name, args.clone()).await {
        return r;
    }
    if let Some(r) = review::handle(state, name, args.clone()).await {
        return r;
    }
    if let Some(r) = ai::handle_as(state, name, args, login).await {
        return r;
    }
    bail!("That method is not available to the app.")
}

#[cfg(test)]
mod timestamp_tests {
    use super::iso_ms;
    #[test]
    fn utc_formatter_preserves_boundaries_and_extended_years() {
        for (ms, expected) in [
            (0, "1970-01-01T00:00:00.000Z"),
            (1, "1970-01-01T00:00:00.001Z"),
            (86_399_999, "1970-01-01T23:59:59.999Z"),
            (86_400_000, "1970-01-02T00:00:00.000Z"),
            (951_782_400_000, "2000-02-29T00:00:00.000Z"),
            (4_107_542_399_999, "2100-02-28T23:59:59.999Z"),
            (4_107_542_400_000, "2100-03-01T00:00:00.000Z"),
            (253_402_300_799_999, "9999-12-31T23:59:59.999Z"),
            (u64::MAX, "584556019-04-03T14:25:51.615Z"),
        ] {
            assert_eq!(iso_ms(ms), expected);
        }
    }
}

#[cfg(test)]
mod shared_value_tests {
    #[test]
    fn utf16_truncation_keeps_scalar_boundaries_and_iterator_consumption() {
        for (input, limit, expected) in [
            ("", 0, ""),
            ("abc", 0, ""),
            ("abc", 2, "ab"),
            ("abc", 3, "abc"),
            ("😀x", 1, ""),
            ("😀x", 2, "😀"),
            ("a😀x", 2, "a"),
            ("a😀x", 3, "a😀"),
            ("a😀x", 4, "a😀x"),
        ] {
            assert_eq!(super::truncate_utf16(input.chars(), limit), expected);
        }
        let mut chars = "a😀x".chars();
        assert_eq!(super::truncate_utf16(&mut chars, 2), "a");
        assert_eq!(chars.collect::<String>(), "x");
    }

    #[test]
    fn component_decoding_preserves_strict_single_pass_semantics() {
        for (input, expected) in [
            ("", ""),
            ("a+b", "a+b"),
            ("%252F", "%2F"),
            ("é😀", "é😀"),
            ("%c3%a9%F0%9F%98%80", "é😀"),
            ("%00", "\0"),
        ] {
            assert_eq!(super::decode_component(input).as_deref(), Some(expected));
        }
        for input in ["%", "%1", "%zz", "%FF", "%C3", "%C3%28"] {
            assert_eq!(super::decode_component(input), None);
        }
        for byte in 0..=127u8 {
            let value = (byte as char).to_string();
            assert_eq!(
                super::decode_component(&super::encode_component(&value)),
                Some(value)
            );
        }
    }
    #[test]
    fn component_encoding_retains_wire_and_storage_spelling() {
        assert_eq!(super::encode_component(""), "");
        assert_eq!(
            super::encode_component("AZaz09-_.!~*'()"),
            "AZaz09-_.!~*'()"
        );
        assert_eq!(
            super::encode_component("a/b %é😀"),
            "a%2Fb%20%25%C3%A9%F0%9F%98%80"
        );
        for byte in 0..=127u8 {
            let expected = if byte.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&byte) {
                (byte as char).to_string()
            } else {
                format!("%{byte:02X}")
            };
            assert_eq!(
                super::encode_component(&(byte as char).to_string()),
                expected
            );
        }
    }
    #[test]
    fn shared_string_lookup_preserves_missing_and_non_string_fallbacks() {
        for value in [
            serde_json::json!(null),
            serde_json::json!(false),
            serde_json::json!(4),
            serde_json::json!("x"),
            serde_json::json!([]),
            serde_json::json!({}),
            serde_json::json!({"key":null}),
            serde_json::json!({"key":3}),
            serde_json::json!({"key":"value"}),
        ] {
            assert_eq!(
                super::text(&value, "key"),
                value["key"].as_str().unwrap_or("")
            );
        }
    }
}
