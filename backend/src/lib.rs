pub mod account;
pub mod agent_api;
pub mod agent_process;
pub mod ai;
pub mod analytics;
pub mod brief;
pub mod github_stats;
pub mod host;
pub mod jev;
mod lz;
pub mod posted;
pub mod presentation;
pub mod review;
mod rpc_validation;
pub mod scheduler;
pub mod source;
pub mod store;
pub mod transport;
use anyhow::{bail, Result};
use serde_json::Value;
use std::{path::PathBuf, sync::Arc};

#[derive(Clone)]
pub struct AppState {
    pub accounts: account::Accounts,
    pub host: host::ServerHost,
    pub posted: posted::PostedStore,
    pub github_stats: github_stats::Cache,
    account_seed: Arc<tokio::sync::OnceCell<bool>>,
    pub http: reqwest::Client,
    pub jobs: ai::Jobs,
    pub analytics: analytics::Analytics,
    pub(crate) diff_cache: Arc<std::sync::Mutex<review::DiffCache>>,
}
impl AppState {
    pub async fn ensure_account(&self) -> Result<bool> {
        self.account_seed
            .get_or_try_init(|| async { Ok(self.accounts.seed_env().await?.is_some()) })
            .await
            .copied()
    }
    pub fn new(cwd: PathBuf, data: PathBuf) -> Result<Arc<Self>> {
        let store = store::Store::new(data)?;
        Self::from_host(host::ServerHost::new(store, Some(cwd), None))
    }
    pub fn from_host(host: host::ServerHost) -> Result<Arc<Self>> {
        let store = host.storage.clone();
        Ok(Arc::new(Self {
            host,
            accounts: account::Accounts::new(store.clone()),
            posted: posted::PostedStore::new(store.clone()),
            github_stats: github_stats::Cache::default(),
            account_seed: Arc::new(tokio::sync::OnceCell::new()),
            http: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(std::time::Duration::from_secs(60))
                .build()?,
            jobs: ai::Jobs::default(),
            analytics: analytics::Analytics::new(),
            diff_cache: Arc::new(std::sync::Mutex::new(review::DiffCache::default())),
        }))
    }
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
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub async fn dispatch(state: &Arc<AppState>, name: &str, args: Value) -> Result<Value> {
    let start = std::time::Instant::now();
    let result = match rpc_validation::arguments(name, args) {
        Ok(args) => dispatch_inner(state, name, args)
            .await
            .and_then(|value| rpc_validation::result(name, value)),
        Err(error) => Err(error),
    };
    let _=state.analytics.record("rpc.call",serde_json::json!({"method":name,"ms":start.elapsed().as_millis() as u64,"status":if result.is_ok(){"ok"}else{"error"}})).await;
    result
}
async fn dispatch_inner(state: &Arc<AppState>, name: &str, args: Value) -> Result<Value> {
    if name == "github-stats" {
        return github_stats::stats(state, args["fresh"] == true).await;
    }
    if name == "analytics-summary" {
        return state.analytics.summary(args).await;
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
    if let Some(r) = ai::handle(state, name, args).await {
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
