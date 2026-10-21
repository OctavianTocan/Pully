//! First-party usage analytics. Only sanitized events are persisted, in global UTC-day files.
//! No repository content or credentials are collected, and no telemetry is sent elsewhere.
use anyhow::Result;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    path::{Path, PathBuf},
    sync::{Arc, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    fs::{self, OpenOptions},
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},
    sync::Mutex,
};

pub const ANALYTICS_KEEP_DAYS: i64 = 365;
const DAY_MS: i64 = 86_400_000;
const MAX_PENDING: usize = 5_000;
const MAX_BATCH: usize = 200;
const MAX_EVENT_BYTES: usize = 8 * 1024;
const MAX_PROPS: usize = 40;

#[derive(Clone)]
pub struct Analytics {
    inner: Arc<Inner>,
}
struct Inner {
    dir: PathBuf,
    enabled: bool,
    pending: Mutex<VecDeque<Event>>,
    last_pruned: Mutex<Option<i64>>,
}

// The lock also covers separate Analytics instances sharing the global directory.
static FILE_LOCK: Mutex<()> = Mutex::const_new(());

impl Default for Analytics {
    fn default() -> Self {
        Self::new()
    }
}

impl Analytics {
    /// Directory excluded from repository inspection and AI context.
    pub fn root(&self) -> &Path {
        &self.inner.dir
    }

    /// Reads configuration without creating a directory or touching credentials.
    /// On by default, matching the web and Android clients' Usage stats switch.
    pub fn new() -> Self {
        let enabled = !matches!(
            std::env::var("PULLS_REVIEW_ANALYTICS")
                .unwrap_or_default()
                .to_ascii_lowercase()
                .as_str(),
            "off" | "0" | "false" | "no"
        );
        let dir = std::env::var_os("PULLS_REVIEW_ANALYTICS_DIR")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                let home = std::env::var_os("HOME")
                    .filter(|v| !v.is_empty())
                    .or_else(|| std::env::var_os("USERPROFILE").filter(|v| !v.is_empty()))
                    .map(PathBuf::from)
                    .unwrap_or_else(std::env::temp_dir);
                home.join(".local/share/pulls-review/analytics")
            });
        Self::configured(dir, enabled)
    }

    pub(crate) fn for_tenant(dir: PathBuf) -> Self {
        let enabled = !matches!(
            std::env::var("PULLS_REVIEW_ANALYTICS")
                .unwrap_or_default()
                .to_ascii_lowercase()
                .as_str(),
            "off" | "0" | "false" | "no"
        );
        Self::configured(dir, enabled)
    }

    fn configured(dir: PathBuf, enabled: bool) -> Self {
        let dir = normalized_directory(dir);
        let result = Self {
            inner: Arc::new(Inner {
                dir,
                enabled,
                pending: Mutex::new(VecDeque::new()),
                last_pruned: Mutex::new(None),
            }),
        };
        {
            if let Ok(runtime) = tokio::runtime::Handle::try_current() {
                let weak = Arc::downgrade(&result.inner);
                runtime.spawn(async move {
                    let mut interval = tokio::time::interval(Duration::from_secs(2));
                    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
                    loop {
                        interval.tick().await;
                        let Some(inner) = weak.upgrade() else { break };
                        let analytics = Self { inner };
                        analytics.flush().await;
                        analytics.prune().await;
                    }
                });
            }
        }
        result
    }

    /// Accepts `{ events: [...] }`; invalid events are discarded. Disk trouble never
    /// breaks the operation being measured: a bounded queue is retried every two seconds.
    pub async fn ingest(&self, body: Value) -> Result<usize> {
        if !self.inner.enabled {
            return Ok(0);
        }
        let events = clean_batch(&body, &iso_time(now_ms()));
        let count = events.len();
        self.enqueue(events).await;
        Ok(count)
    }

    /// Records a server event through precisely the same privacy boundary as clients.
    pub async fn record(&self, name: &str, props: Value) -> Result<()> {
        if !self.inner.enabled {
            return Ok(());
        }
        let rx = iso_time(now_ms());
        let raw = json!({ "name": name, "at": rx, "app": "server", "props": props });
        if let Some(event) = clean_event(&raw, &rx) {
            self.enqueue(vec![event]).await;
        }
        Ok(())
    }

    async fn enqueue(&self, events: Vec<Event>) {
        if events.is_empty() {
            return;
        }
        let should_flush = {
            let mut pending = self.inner.pending.lock().await;
            let batches_before = pending.len() / MAX_BATCH;
            pending.extend(events);
            while pending.len() > MAX_PENDING {
                pending.pop_front();
            }
            pending.len() / MAX_BATCH > batches_before
        };
        // The measured operation waits only for the short memory-queue lock, not
        // for disk I/O. The two-second worker retries failed writes. Large batches
        // schedule an earlier flush; summary explicitly flushes before reading.
        if should_flush {
            let analytics = self.clone();
            tokio::spawn(async move {
                analytics.flush().await;
            });
        }
    }

    pub async fn flush(&self) {
        if !self.inner.enabled || self.inner.pending.lock().await.is_empty() {
            return;
        }
        let _files = FILE_LOCK.lock().await;
        let mut batch = {
            let mut pending = self.inner.pending.lock().await;
            std::mem::take(&mut *pending)
        };
        if batch.is_empty() {
            return;
        }
        if fs::create_dir_all(&self.inner.dir).await.is_err() {
            self.restore_pending(batch).await;
            return;
        }
        // The queue mutex is never held during disk I/O. Never include a
        // filesystem error, path, or failed event in application logs.
        while let Some(first) = batch.front() {
            let day = &first.rx[..10];
            let count = batch.iter().take_while(|e| &e.rx[..10] == day).count();
            let mut bytes = Vec::new();
            for event in batch.iter().take(count) {
                if serde_json::to_writer(&mut bytes, event).is_err() {
                    self.restore_pending(batch).await;
                    return;
                }
                bytes.push(b'\n');
            }
            let path = self.inner.dir.join(format!("{day}.ndjson"));
            let Ok(mut file) = OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .await
            else {
                self.restore_pending(batch).await;
                return;
            };
            let Ok(length) = file.metadata().await.map(|m| m.len()) else {
                self.restore_pending(batch).await;
                return;
            };
            if file.write_all(&bytes).await.is_err() || file.flush().await.is_err() {
                // Undo a partial batch before retrying, to avoid duplicate complete
                // events or concatenating the next append onto a truncated JSON line.
                let _ = file.set_len(length).await;
                self.restore_pending(batch).await;
                return;
            }
            batch.drain(..count);
        }
    }

    async fn restore_pending(&self, mut failed: VecDeque<Event>) {
        let mut pending = self.inner.pending.lock().await;
        failed.append(&mut *pending);
        while failed.len() > MAX_PENDING {
            failed.pop_front();
        }
        *pending = failed;
    }

    /// Best-effort retention, including after opt-out. No new events or folders
    /// are written when disabled; expired existing files are still removed.
    pub async fn prune(&self) {
        let today = now_ms().div_euclid(DAY_MS);
        let mut last = self.inner.last_pruned.lock().await;
        if *last == Some(today) {
            return;
        }
        let _files = FILE_LOCK.lock().await;
        let Ok(mut entries) = fs::read_dir(&self.inner.dir).await else {
            return;
        };
        while let Ok(Some(entry)) = entries.next_entry().await {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            let Some(day) = name.strip_suffix(".ndjson").and_then(parse_day) else {
                continue;
            };
            if day < today - ANALYTICS_KEEP_DAYS {
                let _ = fs::remove_file(entry.path()).await;
            }
        }
        *last = Some(today);
    }

    /// Returns the existing Stats wire shape, with inclusive UTC days and real
    /// counts, costs, errors, latency quantiles, cache outcomes and distinct devices.
    /// Stored history remains readable when ingestion has been turned off.
    pub async fn summary(&self, args: Value) -> Result<Value> {
        self.flush().await;
        self.prune().await;
        let (first, last) = analytics_range(&args, now_ms());
        let days: Vec<String> = (first..=last).map(format_day).collect();
        let mut tally = Tally::new(&days);
        let _files = FILE_LOCK.lock().await;
        for day in &days {
            let Ok(file) = fs::File::open(self.inner.dir.join(format!("{day}.ndjson"))).await
            else {
                continue;
            };
            let mut lines = BufReader::new(file).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if line.len() > MAX_EVENT_BYTES {
                    continue;
                }
                let Ok(raw) = serde_json::from_str::<Value>(&line) else {
                    continue;
                };
                let Some(rx) = raw
                    .get("rx")
                    .and_then(Value::as_str)
                    .and_then(parse_timestamp)
                    .map(iso_time)
                else {
                    continue;
                };
                // Reapply sanitization to existing files as well, so reading older
                // data cannot expose strings disallowed by the current boundary.
                if let Some(event) = clean_event(&raw, &rx) {
                    tally.add(&event);
                }
            }
        }
        Ok(tally.summary(first, last))
    }
}

// Resolve existing ancestor symlinks too: the analytics folder commonly does not
// exist yet, but its actual destination still must be excluded from AI file reads.
fn normalized_directory(dir: PathBuf) -> PathBuf {
    let absolute = if dir.is_absolute() {
        dir
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("/"))
            .join(dir)
    };
    let path = absolute
        .ancestors()
        .find_map(|ancestor| {
            std::fs::canonicalize(ancestor).ok().and_then(|resolved| {
                absolute
                    .strip_prefix(ancestor)
                    .ok()
                    .map(|suffix| resolved.join(suffix))
            })
        })
        .unwrap_or(absolute);
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                normalized.pop();
            }
            component => normalized.push(component.as_os_str()),
        }
    }
    normalized
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Event {
    name: String,
    at: String,
    rx: String,
    app: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    session: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    device: Option<String>,
    props: Map<String, Value>,
}

struct Patterns {
    name: Regex,
    id: Regex,
    prop: Regex,
    banned: Regex,
    key: Regex,
    credential: Regex,
    file_url: Regex,
    local_path: Regex,
    relative_path: Regex,
    email: Regex,
    quoted: Regex,
    timestamp: Regex,
}
fn patterns() -> &'static Patterns {
    static PATTERNS: OnceLock<Patterns> = OnceLock::new();
    PATTERNS.get_or_init(|| Patterns {
        name: Regex::new(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$").unwrap(),
        id: Regex::new(r"^[A-Za-z0-9_-]{1,64}$").unwrap(),
        prop: Regex::new(r"^[A-Za-z][A-Za-z0-9_]{0,39}$").unwrap(),
        banned: Regex::new(r"(?i)token|secret|password|passwd|api_?key|authorization|cookie|credential|prompt|body|content|diff|patch|query|path|filename|text|answer|comment").unwrap(),
        key: Regex::new(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}|xox[abprs]-[A-Za-z0-9_-]{10,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(?:\.[A-Za-z0-9_-]+)?)").unwrap(),
        credential: Regex::new(r"(?i)\b(?:password|passwd|secret|api[_-]?key|authorization|cookie|(?:access[_-]?)?token)\s*[:=]\s*(?:(?:bearer|basic)\s+)?\S+").unwrap(),
        file_url: Regex::new(r"(?i)file://\S*").unwrap(),
        local_path: Regex::new(r#"(^|[\s'"(=])(?:~/|/|[A-Za-z]:\\)\S*"#).unwrap(),
        relative_path: Regex::new(r#"(?i)(^|[\s'"(=:`])(?:[\w.-]+/)+[\w.-]+\.[a-z\d]{1,8}\b"#).unwrap(),
        email: Regex::new(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+").unwrap(),
        quoted: Regex::new(r#"'[^'"`\n]*'|"[^'"`\n]*"|`[^'"`\n]*`"#).unwrap(),
        timestamp: Regex::new(r"^(\d{4}-\d{2}-\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:([Zz])|([+-])(\d{2}):?(\d{2}))?)?$").unwrap(),
    })
}

pub fn scrub_text(value: &str, limit: usize) -> String {
    let p = patterns();
    let clean = p.key.replace_all(value, "[redacted]");
    let clean = p.credential.replace_all(&clean, "[redacted]");
    let clean = p.file_url.replace_all(&clean, "[path]");
    let clean = p.local_path.replace_all(&clean, "${1}[path]");
    let clean = p.relative_path.replace_all(&clean, "${1}[path]");
    let clean = p.email.replace_all(&clean, "[email]");
    let clean = p.quoted.replace_all(&clean, |caps: &regex::Captures<'_>| {
        let text = &caps[0];
        if text[1..text.len() - 1].chars().any(char::is_whitespace) {
            let mark = &text[..1];
            format!("{mark}…{mark}")
        } else {
            text.to_string()
        }
    });
    crate::truncate_utf16(clean.chars(), limit)
}

fn clean_props(raw: Option<&Value>) -> Map<String, Value> {
    let mut props = Map::new();
    let Some(raw) = raw.and_then(Value::as_object) else {
        return props;
    };
    for (key, value) in raw {
        if props.len() >= MAX_PROPS {
            break;
        }
        // These are usage counts, not credentials. All other token-named fields
        // are excluded, and even these two fields never accept string values.
        let token_count = matches!(key.as_str(), "tokensIn" | "tokensOut") && value.is_number();
        if !patterns().prop.is_match(key) || (patterns().banned.is_match(key) && !token_count) {
            continue;
        }
        let clean = match value {
            Value::Bool(_) => Some(value.clone()),
            Value::Number(n) if n.as_f64().is_some_and(f64::is_finite) => Some(value.clone()),
            Value::String(s) => Some(Value::String(scrub_text(
                s,
                if key == "stack" { 2000 } else { 200 },
            ))),
            _ => None,
        };
        if let Some(clean) = clean {
            props.insert(key.clone(), clean);
        }
    }
    props
}

fn clean_event(raw: &Value, rx: &str) -> Option<Event> {
    let name = raw.get("name")?.as_str()?;
    let app = raw.get("app")?.as_str()?;
    if name.len() > 64
        || (name != "crash" && !patterns().name.is_match(name))
        || !matches!(app, "android" | "web" | "server")
        || patterns().key.is_match(name)
    {
        return None;
    }
    let id = |key: &str| {
        raw.get(key)
            .and_then(Value::as_str)
            .filter(|s| patterns().id.is_match(s) && !patterns().key.is_match(s))
            .map(str::to_string)
    };
    let event = Event {
        name: name.into(),
        at: raw
            .get("at")
            .and_then(Value::as_str)
            .and_then(parse_timestamp)
            .map(iso_time)
            .unwrap_or_else(|| rx.into()),
        rx: rx.into(),
        app: app.into(),
        version: raw
            .get("version")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(|s| scrub_text(s, 32)),
        session: id("session"),
        device: id("device"),
        props: clean_props(raw.get("props")),
    };
    (serde_json::to_vec(&event).ok()?.len() <= MAX_EVENT_BYTES).then_some(event)
}

fn clean_batch(raw: &Value, rx: &str) -> Vec<Event> {
    raw.get("events")
        .and_then(Value::as_array)
        .map(|events| {
            events
                .iter()
                .take(MAX_BATCH)
                .filter_map(|event| clean_event(event, rx))
                .collect()
        })
        .unwrap_or_default()
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

// Gregorian civil-day conversion, using 400-year eras. All analytics timestamps
// are UTC; this does not use the machine's locale, timezone, or daylight savings.
fn civil_day(year: i64, month: i64, day: i64) -> i64 {
    let year = year - i64::from(month <= 2);
    let era = year.div_euclid(400);
    let yoe = year - era * 400;
    let doy = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn format_day(day: i64) -> String {
    let z = day + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    let year = year + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02}")
}

fn parse_day(day: &str) -> Option<i64> {
    let bytes = day.as_bytes();
    if bytes.len() != 10
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes
            .iter()
            .enumerate()
            .any(|(i, b)| i != 4 && i != 7 && !b.is_ascii_digit())
    {
        return None;
    }
    let year: i64 = day[..4].parse().ok()?;
    let month: i64 = day[5..7].parse().ok()?;
    let date: i64 = day[8..].parse().ok()?;
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let max = match month {
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        4 | 6 | 9 | 11 => 30,
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        _ => return None,
    };
    if !(1..=max).contains(&date) {
        return None;
    }
    Some(civil_day(year, month, date))
}

fn iso_time(ms: i64) -> String {
    let day = ms.div_euclid(DAY_MS);
    let time = ms.rem_euclid(DAY_MS);
    format!(
        "{}T{:02}:{:02}:{:02}.{:03}Z",
        format_day(day),
        time / 3_600_000,
        time / 60_000 % 60,
        time / 1000 % 60,
        time % 1000
    )
}

fn parse_timestamp(value: &str) -> Option<i64> {
    let caps = patterns().timestamp.captures(value)?;
    let day = parse_day(&caps[1])?;
    let part = |i| {
        caps.get(i)
            .map(|m| m.as_str().parse::<i64>())
            .transpose()
            .ok()
            .map(|v| v.unwrap_or(0))
    };
    let (hour, minute, second) = (part(2)?, part(3)?, part(4)?);
    if hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    let fraction = caps.get(5).map(|m| m.as_str()).unwrap_or("");
    let millis = format!("{fraction:0<3}")[..3].parse::<i64>().ok()?;
    let (oh, om) = (part(8)?, part(9)?);
    if oh > 23 || om > 59 {
        return None;
    }
    let offset = (oh * 60 + om)
        * 60_000
        * if caps.get(7).is_some_and(|m| m.as_str() == "-") {
            -1
        } else {
            1
        };
    let ms = day * DAY_MS + hour * 3_600_000 + minute * 60_000 + second * 1000 + millis - offset;
    (ms >= civil_day(0, 1, 1) * DAY_MS && ms < civil_day(10_000, 1, 1) * DAY_MS).then_some(ms)
}

fn analytics_range(input: &Value, now: i64) -> (i64, i64) {
    let to = input
        .get("to")
        .and_then(Value::as_str)
        .and_then(parse_day)
        .unwrap_or_else(|| now.div_euclid(DAY_MS));
    let span = input
        .get("days")
        .and_then(Value::as_f64)
        .filter(|n| n.is_finite() && *n >= 1.0)
        .map(|n| n.floor().min(366.0) as i64)
        .unwrap_or(30);
    let from = input
        .get("from")
        .and_then(Value::as_str)
        .and_then(parse_day)
        .unwrap_or(to - span + 1);
    let (first, last) = (from.min(to), from.max(to));
    (first.max(last - 365).max(civil_day(0, 1, 1)), last)
}

fn num(props: &Map<String, Value>, key: &str) -> f64 {
    props
        .get(key)
        .and_then(Value::as_f64)
        .filter(|n| n.is_finite())
        .unwrap_or(0.0)
}
fn string<'a>(props: &'a Map<String, Value>, key: &str, fallback: &'a str) -> &'a str {
    props.get(key).and_then(Value::as_str).unwrap_or(fallback)
}
fn failed(props: &Map<String, Value>) -> bool {
    matches!(string(props, "status", ""), "error" | "failed" | "timeout")
        || num(props, "status") >= 400.0
        || props.get("ok") == Some(&Value::Bool(false))
}
fn cents(value: f64) -> f64 {
    (value * 10_000.0).round() / 10_000.0
}

// Weighted samples keep GitHub's minute buckets exact without allocating one
// element per call (client-supplied counts must never drive an enormous loop).
#[derive(Default)]
struct Samples {
    values: Vec<(f64, f64)>,
    count: f64,
}
impl Samples {
    fn add(&mut self, ms: f64) {
        self.add_weight(ms, 1.0);
    }
    fn add_weight(&mut self, ms: f64, weight: f64) {
        if weight > 0.0 {
            self.values.push((ms, weight));
            self.count += weight;
        }
    }
    fn quantile(&self, q: f64) -> f64 {
        self.quantiles([q])[0]
    }
    fn quantiles<const N: usize>(&self, qs: [f64; N]) -> [f64; N] {
        if self.values.is_empty() {
            return [0.0; N];
        }
        let mut values = self.values.clone();
        values.sort_by(|a, b| a.0.total_cmp(&b.0));
        qs.map(|q| {
            let target = (q * self.count).floor().min(self.count - 1.0);
            let mut at = 0.0;
            for (ms, weight) in &values {
                at += weight;
                if at > target {
                    return ms.round();
                }
            }
            values.last().map(|v| v.0.round()).unwrap_or(0.0)
        })
    }
}
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct Totals {
    events: usize,
    sessions: usize,
    devices: usize,
    prs_opened: usize,
    files_reviewed: f64,
    ai_runs: usize,
    ai_failures: usize,
    cost_usd: f64,
    tokens_in: f64,
    tokens_out: f64,
    crashes: usize,
    errors: usize,
}
#[derive(Default)]
struct Day {
    sessions: BTreeSet<String>,
    prs: usize,
    files: f64,
    ai: usize,
    cost: f64,
}
#[derive(Default)]
struct Screen {
    views: usize,
    seconds: f64,
}
#[derive(Default)]
struct Engine {
    runs: usize,
    failures: usize,
    cost: f64,
    input: f64,
    output: f64,
    cache_read: f64,
    priced: usize,
    ms: Samples,
}
#[derive(Default)]
struct Task {
    runs: usize,
    failures: usize,
    cost: f64,
    input: f64,
    output: f64,
    cache_read: f64,
    priced: usize,
}
#[derive(Default)]
struct Bucket {
    calls: f64,
    failures: f64,
    ms: Samples,
}
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorRow {
    name: String,
    app: String,
    kind: String,
    message: String,
    count: usize,
    last_at: String,
}
#[derive(Default)]
struct Tally {
    totals: Totals,
    days: BTreeMap<String, Day>,
    sessions: BTreeSet<String>,
    devices: BTreeSet<String>,
    names: BTreeMap<String, usize>,
    screens: BTreeMap<String, Screen>,
    engines: BTreeMap<(String, String), Engine>,
    tasks: BTreeMap<String, Task>,
    calls: BTreeMap<String, Bucket>,
    speed: BTreeMap<(String, String), Samples>,
    errors: BTreeMap<(String, String, String, String), ErrorRow>,
    github_calls: f64,
    github_failures: f64,
    github_rate: Option<f64>,
    hit: usize,
    refetch: usize,
    stale: usize,
}
impl Tally {
    fn new(days: &[String]) -> Self {
        Self {
            days: days
                .iter()
                .map(|day| (day.clone(), Day::default()))
                .collect(),
            ..Self::default()
        }
    }
    fn timed(&mut self, name: String, ms: f64, bad: bool) {
        let row = self.calls.entry(name).or_default();
        row.calls += 1.0;
        row.failures += if bad { 1.0 } else { 0.0 };
        if ms > 0.0 {
            row.ms.add(ms);
        }
    }
    fn sped(&mut self, metric: String, event: &Event) {
        let ms = num(&event.props, "ms");
        if ms > 0.0 {
            self.speed
                .entry((metric, event.app.clone()))
                .or_default()
                .add(ms);
        }
    }
    fn add(&mut self, event: &Event) {
        self.totals.events += 1;
        *self.names.entry(event.name.clone()).or_default() += 1;
        if let Some(session) = &event.session {
            self.sessions.insert(session.clone());
            if let Some(day) = self.days.get_mut(&event.rx[..10]) {
                day.sessions.insert(session.clone());
            }
        }
        if let Some(device) = &event.device {
            self.devices.insert(device.clone());
        }
        let p = &event.props;
        match event.name.as_str() {
            "screen.view" => {
                self.screens
                    .entry(string(p, "screen", "unknown").into())
                    .or_default()
                    .views += 1
            }
            "screen.time" => {
                self.screens
                    .entry(string(p, "screen", "unknown").into())
                    .or_default()
                    .seconds += num(p, "seconds")
            }
            "pr.open" if string(p, "kind", "github-pr") == "github-pr" => {
                self.totals.prs_opened += 1;
                if let Some(day) = self.days.get_mut(&event.rx[..10]) {
                    day.prs += 1;
                }
            }
            "file.reviewed" => {
                let files = num(p, "files").max(1.0);
                self.totals.files_reviewed += files;
                if let Some(day) = self.days.get_mut(&event.rx[..10]) {
                    day.files += files;
                }
            }
            "pr.load" => self.sped("pr.load".into(), event),
            "app.open" => self.sped(format!("app.open {}", string(p, "start", "cold")), event),
            "ai.run" => self.ai_run(event),
            "rpc.call" => self.timed(
                format!("rpc {}", string(p, "method", "unknown")),
                num(p, "ms"),
                failed(p),
            ),
            "jev.call" => {
                self.timed("jev".into(), num(p, "ms"), failed(p));
                self.totals.tokens_in += num(p, "tokensIn");
                self.totals.tokens_out += num(p, "tokensOut");
            }
            "github.calls" => {
                let count = num(p, "calls").max(1.0);
                let failures = num(p, "failures");
                self.github_calls += count;
                self.github_failures += failures;
                if p.get("rateLeft").is_some_and(Value::is_number) {
                    let rate = num(p, "rateLeft");
                    self.github_rate = Some(self.github_rate.map_or(rate, |v| v.min(rate)));
                }
                let row = self
                    .calls
                    .entry(format!("github {}", string(p, "route", "other")))
                    .or_default();
                row.calls += count;
                row.failures += failures;
                row.ms
                    .add_weight(num(p, "ms"), (count - 1.0).ceil().max(0.0));
                row.ms.add(num(p, "ms").max(num(p, "msMax")));
            }
            "cache.diff" => match string(p, "outcome", "") {
                "hit" => self.hit += 1,
                "refetch" => self.refetch += 1,
                "stale" => self.stale += 1,
                _ => {}
            },
            "store.refresh" | "preanalyze.run" => {
                self.timed(event.name.clone(), num(p, "ms"), failed(p))
            }
            "crash" | "error.client" | "error.server" => self.error(event),
            _ => {}
        }
    }
    /// @cc [owner:OctavianTocan,label:product] rust-analytics-cost-reporting
    /// AI task and engine summaries MUST count reported zero costs as priced and
    /// missing costs as unpriced, and retain input/output/cache-read totals (O9).
    fn ai_run(&mut self, event: &Event) {
        let p = &event.props;
        let row = self
            .engines
            .entry((
                string(p, "engine", "unknown").into(),
                string(p, "model", "default").into(),
            ))
            .or_default();
        let bad = usize::from(failed(p));
        let cost = num(p, "costUsd");
        let priced = usize::from(p.get("costUsd").is_some_and(Value::is_number));
        row.runs += 1;
        row.failures += bad;
        row.cost += cost;
        row.input += num(p, "tokensIn");
        row.output += num(p, "tokensOut");
        row.cache_read += num(p, "cacheRead");
        row.priced += priced;
        if num(p, "ms") > 0.0 {
            row.ms.add(num(p, "ms"));
        }
        let task = self
            .tasks
            .entry(string(p, "task", "other").into())
            .or_default();
        task.runs += 1;
        task.failures += bad;
        task.cost += cost;
        task.input += num(p, "tokensIn");
        task.output += num(p, "tokensOut");
        task.cache_read += num(p, "cacheRead");
        task.priced += priced;
        self.totals.ai_runs += 1;
        self.totals.ai_failures += bad;
        self.totals.cost_usd += cost;
        self.totals.tokens_in += num(p, "tokensIn");
        self.totals.tokens_out += num(p, "tokensOut");
        if let Some(day) = self.days.get_mut(&event.rx[..10]) {
            day.ai += 1;
            day.cost += cost;
        }
    }
    fn error(&mut self, event: &Event) {
        if event.name == "crash" {
            self.totals.crashes += 1;
        } else {
            self.totals.errors += 1;
        }
        let kind = string(&event.props, "kind", "error").to_string();
        let message = string(&event.props, "message", "").to_string();
        let row = self
            .errors
            .entry((
                event.name.clone(),
                event.app.clone(),
                kind.clone(),
                message.clone(),
            ))
            .or_insert_with(|| ErrorRow {
                name: event.name.clone(),
                app: event.app.clone(),
                kind,
                message,
                count: 0,
                last_at: event.rx.clone(),
            });
        row.count += 1;
        if event.rx > row.last_at {
            row.last_at = event.rx.clone();
        }
    }
    fn summary(mut self, first: i64, last: i64) -> Value {
        self.totals.sessions = self.sessions.len();
        self.totals.devices = self.devices.len();
        self.totals.cost_usd = cents(self.totals.cost_usd);
        let days: Vec<Value> = self
            .days
            .iter()
            .map(|(day, row)| {
                json!({
                    "day": day, "sessions": row.sessions.len(), "prsOpened": row.prs,
                    "filesReviewed": row.files, "aiRuns": row.ai, "costUsd": cents(row.cost),
                })
            })
            .collect();
        let mut screens: Vec<Value> = self.screens.iter().map(|(name, row)| json!({ "screen": name, "views": row.views, "seconds": row.seconds.round() })).collect();
        screens.sort_by(|a, b| {
            value_num(b, "seconds")
                .total_cmp(&value_num(a, "seconds"))
                .then_with(|| value_num(b, "views").total_cmp(&value_num(a, "views")))
        });
        let mut engines: Vec<Value> = self.engines.iter().map(|((engine, model), row)| json!({
            "engine": engine, "model": model, "runs": row.runs, "failures": row.failures,
            "costUsd": cents(row.cost), "tokensIn": row.input, "tokensOut": row.output, "cacheRead": row.cache_read, "priced": row.priced, "medianMs": row.ms.quantile(0.5),
        })).collect();
        sort_desc(&mut engines, "runs");
        let mut tasks: Vec<Value> = self.tasks.iter().map(|(task, row)| json!({ "task": task, "runs": row.runs, "failures": row.failures, "costUsd": cents(row.cost), "tokensIn": row.input, "tokensOut": row.output, "cacheRead": row.cache_read, "priced": row.priced })).collect();
        sort_desc(&mut tasks, "runs");
        let mut calls: Vec<Value> = self.calls.iter().map(|(name, row)| { let [median, p95] = row.ms.quantiles([0.5, 0.95]); json!({ "name": name, "calls": row.calls, "failures": row.failures, "medianMs": median, "p95Ms": p95 }) }).collect();
        sort_desc(&mut calls, "medianMs");
        let speed: Vec<Value> = self.speed.iter().map(|((metric, app), ms)| { let [median, p95] = ms.quantiles([0.5, 0.95]); json!({ "metric": metric, "app": app, "count": ms.count, "medianMs": median, "p95Ms": p95 }) }).collect();
        let mut errors: Vec<ErrorRow> = self.errors.into_values().collect();
        errors.sort_by(|a, b| {
            b.count
                .cmp(&a.count)
                .then_with(|| b.last_at.cmp(&a.last_at))
        });
        errors.truncate(50);
        let mut names: Vec<Value> = self
            .names
            .iter()
            .map(|(name, count)| json!({ "name": name, "count": count }))
            .collect();
        sort_desc(&mut names, "count");
        let mut github = json!({ "calls": self.github_calls, "failures": self.github_failures });
        if let Some(rate) = self.github_rate {
            github["lowestRateLeft"] = json!(rate);
        }
        json!({
            "from": format_day(first), "to": format_day(last), "totals": self.totals,
            "days": days, "screens": screens, "engines": engines, "tasks": tasks,
            "calls": calls, "speed": speed, "github": github,
            "cache": { "hit": self.hit, "refetch": self.refetch, "stale": self.stale },
            "errors": errors, "names": names,
        })
    }
}
fn value_num(value: &Value, key: &str) -> f64 {
    value.get(key).and_then(Value::as_f64).unwrap_or(0.0)
}
fn sort_desc(values: &mut [Value], key: &str) {
    values.sort_by(|a, b| value_num(b, key).total_cmp(&value_num(a, key)));
}

#[cfg(test)]
mod tests {
    use super::*;
    const RX: &str = "2026-10-15T21:00:00.000Z";

    #[test]
    fn combined_quantiles_preserve_weighted_targets_and_rounding() {
        let mut samples = Samples::default();
        assert_eq!(samples.quantiles([0.5, 0.95]), [0.0, 0.0]);
        for (value, weight) in [(19.6, 1.0), (0.4, 1.0), (10.5, 8.0), (99.6, 0.0)] {
            samples.add_weight(value, weight);
        }
        assert_eq!(
            samples.quantiles([0.0, 0.5, 0.95, 1.0]),
            [0.0, 11.0, 20.0, 20.0]
        );
        assert_eq!(samples.values, vec![(19.6, 1.0), (0.4, 1.0), (10.5, 8.0)]);
        assert_eq!(
            samples.quantiles([0.5, 0.95]),
            [samples.quantile(0.5), samples.quantile(0.95)]
        );
    }

    #[test]
    fn utc_dates_and_range_are_calendar_correct() {
        for date in [
            "0000-01-01",
            "1900-02-28",
            "1969-12-31",
            "1970-01-01",
            "2000-02-29",
            "2024-02-29",
            "2026-10-15",
            "9999-12-31",
        ] {
            assert_eq!(format_day(parse_day(date).unwrap()), date);
        }
        assert_eq!(parse_day("1970-01-01"), Some(0));
        assert_eq!(parse_day("1900-02-29"), None);
        assert_eq!(parse_day("2026-02-30"), None);
        assert_eq!(iso_time(-1), "1969-12-31T23:59:59.999Z");
        assert_eq!(
            iso_time(parse_timestamp("2026-10-16T00:30:42.12345+03:30").unwrap()),
            "2026-10-15T21:00:42.123Z"
        );
        assert_eq!(
            iso_time(parse_timestamp("2026-10-15").unwrap()),
            "2026-10-15T00:00:00.000Z"
        );
        let now = parse_timestamp(RX).unwrap();
        let (first, last) = analytics_range(&Value::Null, now);
        assert_eq!(
            (format_day(first), format_day(last)),
            ("2026-09-16".into(), "2026-10-15".into())
        );
        let (first, last) =
            analytics_range(&json!({ "from": "1990-01-01", "to": "2026-10-15" }), now);
        assert_eq!(last - first, 365);
        assert_eq!(format_day(first), "2025-10-15");
        let (first, last) =
            analytics_range(&json!({ "from": "2026-10-20", "to": "2026-10-01" }), now);
        assert_eq!(
            (format_day(first), format_day(last)),
            ("2026-10-01".into(), "2026-10-20".into())
        );
    }

    #[test]
    fn sanitization_keeps_counts_and_redacts_content() {
        let event = clean_event(
            &json!({
                "name": "ai.run", "app": "server", "at": "2026-10-15T20:59:59Z",
                "device": "ghp_abcdefghijklmnopqrstuvwxyz0123",
                "version": "ghp_abcdefghijklmnopqrstuvwxyz0123",
                "props": { "query": "private", "path": "src/private.ts", "prompt": "secret",
                    "githubToken": "never", "tokensIn": 100, "tokensOut": 10,
                    "message": "failed at /home/tavi/private.ts", "array": [1], "ok": true }
            }),
            RX,
        )
        .unwrap();
        assert_eq!(event.at, "2026-10-15T20:59:59.000Z");
        assert!(event.device.is_none());
        assert_eq!(event.version.as_deref(), Some("[redacted]"));
        assert_eq!(
            event.props,
            json!({ "tokensIn": 100, "tokensOut": 10, "message": "failed at [path]", "ok": true })
                .as_object()
                .unwrap()
                .clone()
        );
        assert_eq!(scrub_text("at file:///srv/app/x.js:3:4", 200), "at [path]");
        assert_eq!(scrub_text("open /custom/private.rs", 200), "open [path]");
        assert_eq!(
            scrub_text("authorization: Bearer opaque-value", 200),
            "[redacted]"
        );
        assert_eq!(scrub_text("token=opaque-value", 200), "[redacted]");
        assert_eq!(
            scrub_text("load src/private/plan.ts for tavi@example.com", 200),
            "load [path] for [email]"
        );
        assert_eq!(
            scrub_text("\"ship the secret plan\" is not valid JSON", 200),
            "\"…\" is not valid JSON"
        );
        assert_eq!(
            scrub_text("Cannot read (reading 'files')", 200),
            "Cannot read (reading 'files')"
        );
        assert_eq!(
            scrub_text("at https://prs.example.com/assets/index.js:1:2", 200),
            "at https://prs.example.com/assets/index.js:1:2"
        );
        assert!(clean_event(&json!({ "name": "open", "app": "web" }), RX).is_none());
        assert!(clean_event(&json!({ "name": "pr.open", "app": "ios" }), RX).is_none());
        assert!(clean_event(&json!({ "name": "crash", "app": "android" }), RX).is_some());
        let events = vec![json!({ "name": "ui.tap", "app": "web" }); 250];
        assert_eq!(clean_batch(&json!({ "events": events }), RX).len(), 200);
    }

    #[test]
    fn caps_strings_props_and_event_bytes() {
        let event = clean_event(
            &json!({ "name": "error.client", "app": "web", "props": {
                "message": "m".repeat(500), "stack": "s".repeat(5000), "tokensIn": "secret"
            }}),
            RX,
        )
        .unwrap();
        assert_eq!(event.props["message"].as_str().unwrap().len(), 200);
        assert_eq!(event.props["stack"].as_str().unwrap().len(), 2000);
        assert!(!event.props.contains_key("tokensIn"));
        let mut props = Map::new();
        for i in 0..45 {
            props.insert(format!("k{i}"), json!(i));
        }
        assert_eq!(clean_props(Some(&Value::Object(props))).len(), 40);
        let props: Map<String, Value> = (0..40)
            .map(|i| (format!("k{i}"), json!("v".repeat(200))))
            .collect();
        assert!(clean_event(
            &json!({ "name": "ui.tap", "app": "web", "props": props }),
            RX
        )
        .is_none());
        assert!(clean_batch(&json!("nope"), RX).is_empty());
    }

    #[test]
    fn summary_matches_stats_fields_with_real_data() {
        let day = parse_day("2026-10-15").unwrap();
        let mut tally = Tally::new(&["2026-10-14".into(), "2026-10-15".into()]);
        for (name, props) in [
            ("screen.view", json!({ "screen": "home" })),
            ("screen.time", json!({ "screen": "home", "seconds": 42 })),
            ("pr.open", json!({ "number": 1 })),
            ("pr.open", json!({ "kind": "branch" })),
            ("file.reviewed", json!({ "files": 3 })),
            (
                "ai.run",
                json!({ "task": "review", "engine": "codex", "model": "gpt", "status": "done", "costUsd": 0.5, "tokensIn": 100, "tokensOut": 10, "ms": 2000 }),
            ),
            (
                "ai.run",
                json!({ "task": "review", "engine": "codex", "model": "gpt", "status": "failed", "ms": 1000 }),
            ),
            (
                "github.calls",
                json!({ "route": "pulls", "calls": 2, "failures": 1, "ms": 100, "msMax": 120, "rateLeft": 3999 }),
            ),
            ("cache.diff", json!({ "outcome": "hit" })),
            (
                "crash",
                json!({ "kind": "IllegalStateException", "message": "boom" }),
            ),
        ] {
            tally.add(&clean_event(&json!({ "name": name, "app": "android", "session": "s1", "device": "d1", "props": props }), RX).unwrap());
        }
        let summary = tally.summary(day - 1, day);
        let totals = &summary["totals"];
        assert_eq!(totals["events"], 10);
        assert_eq!(totals["sessions"], 1);
        assert_eq!(totals["devices"], 1);
        assert_eq!(totals["prsOpened"], 1);
        assert_eq!(totals["aiRuns"], 2);
        assert_eq!(totals["aiFailures"], 1);
        assert_eq!(totals["crashes"], 1);
        assert_eq!(totals["tokensIn"].as_f64(), Some(100.0));
        assert_eq!(totals["costUsd"].as_f64(), Some(0.5));
        assert_eq!(summary["days"][0]["sessions"], 0);
        assert_eq!(summary["engines"][0]["medianMs"].as_f64(), Some(2000.0));
        assert_eq!(summary["github"]["lowestRateLeft"].as_f64(), Some(3999.0));
        assert_eq!(summary["cache"]["hit"], 1);
        assert_eq!(summary["errors"][0]["message"], "boom");
    }

    #[test]
    fn ai_cost_and_task_tokens_distinguish_missing_from_zero() {
        let mut tally = Tally::new(&["2026-10-15".into()]);
        for cost in [Some(0.0), Some(0.25), None] {
            let mut props = json!({"task":"tour","engine":"codex","tokensIn":100,"tokensOut":10,"cacheRead":40});
            if let Some(cost) = cost {
                props["costUsd"] = json!(cost);
            }
            tally.add(
                &clean_event(&json!({"name":"ai.run","app":"server","props":props}), RX).unwrap(),
            );
        }
        let day = parse_day("2026-10-15").unwrap();
        let summary = tally.summary(day, day);
        for field in ["engines", "tasks"] {
            let row = &summary[field][0];
            assert_eq!(row["runs"], 3);
            assert_eq!(row["priced"], 2);
            assert_eq!(row["tokensIn"].as_f64(), Some(300.0));
            assert_eq!(row["tokensOut"].as_f64(), Some(30.0));
            assert_eq!(row["cacheRead"].as_f64(), Some(120.0));
            assert_eq!(row["costUsd"].as_f64(), Some(0.25));
        }
    }

    #[test]
    fn weighted_quantiles_are_bounded_for_large_call_counts() {
        let mut samples = Samples::default();
        samples.add_weight(100.0, 999_999_999.0);
        samples.add(200.0);
        assert_eq!(samples.values.len(), 2);
        assert_eq!(samples.quantile(0.5), 100.0);
        assert_eq!(samples.quantile(0.95), 100.0);
        let mut two = Samples::default();
        two.add(100.0);
        two.add(120.0);
        assert_eq!(two.quantile(0.5), 120.0);
    }
}
