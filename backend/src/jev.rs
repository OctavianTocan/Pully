//! Environment-only TypeSafe classification, shared by the inbox and review tools.
use crate::{source::s, truncate_utf16, AppState};
use anyhow::{bail, ensure, Context, Result};
use futures_util::StreamExt;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, Instant},
};
use tokio::sync::Semaphore;

const ENDPOINT: &str = "https://api.typesafe.ai/v1/systemone";
const RETRY_FAILED_MS: u64 = 600_000;
const MAX_RATED_FILES: usize = 300;
const KINDS: [&str; 8] = [
    "feature",
    "fix",
    "refactor",
    "performance",
    "docs",
    "tests",
    "dependencies",
    "infra",
];

struct Pace {
    next: Instant,
    speed: f64,
    slowed: Option<Instant>,
}
struct Client {
    key: Option<String>,
    refused: AtomicBool,
    slots: Semaphore,
    pace: Mutex<Pace>,
}
impl Client {
    fn new(key: Option<String>) -> Self {
        Self {
            key,
            refused: AtomicBool::new(false),
            slots: Semaphore::new(8),
            pace: Mutex::new(Pace {
                next: Instant::now(),
                speed: 1.0,
                slowed: None,
            }),
        }
    }
    fn enabled(&self) -> bool {
        self.key.is_some() && !self.refused.load(Ordering::Relaxed)
    }
    async fn wait_turn(&self, body: &str) {
        let start = {
            let mut p = self.pace.lock().unwrap_or_else(|e| e.into_inner());
            let start = p.next.max(Instant::now());
            let tokens = (body.encode_utf16().count() as f64 / 4.0).ceil();
            let cost = (1.0_f64 / 75.0).max(tokens / 90_000.0) / p.speed;
            p.next = start + Duration::from_secs_f64(cost);
            start
        };
        tokio::time::sleep_until(start.into()).await;
    }
    fn slow_down(&self, wait: Duration) {
        let mut p = self.pace.lock().unwrap_or_else(|e| e.into_inner());
        let now = Instant::now();
        p.next = p.next.max(now + wait);
        if p.slowed
            .is_none_or(|at| now.duration_since(at) >= Duration::from_secs(1))
        {
            p.speed = (p.speed / 2.0).max(1.0 / 75.0);
            p.slowed = Some(now);
        }
    }
    /// @cc [owner:OctavianTocan,label:security] rust-jev-secret-never-returned
    /// TypeSafe MUST receive only the environment key at its fixed HTTPS origin;
    /// upstream bodies and transport errors MUST NOT expose credentials to clients
    /// (server-typesafe-key-never-echoed; Rust parity request, 16 Oct 2026).
    async fn ask(&self, app: &AppState, state: Value, questions: Value) -> Result<Option<Value>> {
        if !self.enabled() {
            return Ok(None);
        }
        let started = Instant::now();
        let mut throttled = 0;
        let mut status = json!("error");
        let result = self
            .send(app, state, &questions, &mut throttled, &mut status)
            .await;
        let usage = result
            .as_ref()
            .ok()
            .and_then(|v| v.as_ref())
            .map(|v| &v["usage"]);
        let _ = app.analytics.record("jev.call", json!({"questions":questions.as_object().map_or(0, |v|v.len()),"ms":started.elapsed().as_millis() as u64,"status":status,"throttled":throttled,"tokensIn":usage.map(|v| &v["input_tokens"]),"tokensOut":usage.map(|v| &v["output_tokens"])})).await;
        result
    }
    async fn send(
        &self,
        app: &AppState,
        state: Value,
        questions: &Value,
        throttled: &mut usize,
        status: &mut Value,
    ) -> Result<Option<Value>> {
        let body = serde_json::to_string(
            &json!({"state":state,"model":"jev-latest","questions":questions}),
        )?;
        for attempt in 0..3 {
            let permit = self
                .slots
                .acquire()
                .await
                .context("Jev request slot unavailable")?;
            if !self.enabled() {
                *status = json!("off");
                return Ok(None);
            }
            self.wait_turn(&body).await;
            if !self.enabled() {
                *status = json!("off");
                return Ok(None);
            }
            let response = app
                .http
                .post(ENDPOINT)
                .bearer_auth(self.key.as_ref().unwrap())
                .header("Content-Type", "application/json")
                .body(body.clone())
                .timeout(Duration::from_secs(30))
                .send()
                .await
                .map_err(|_| anyhow::anyhow!("Jev request failed or timed out"))?;
            let code = response.status().as_u16();
            *status = json!(code);
            if code == 401 {
                self.refused.store(true, Ordering::Relaxed);
                *status = json!("off");
                return Ok(None);
            }
            if code == 429 || code == 529 {
                let wait = response
                    .headers()
                    .get("retry-after")
                    .and_then(|v| v.to_str().ok())
                    .and_then(retry_after)
                    .unwrap_or(Duration::from_secs(1 << attempt))
                    .min(Duration::from_secs(20));
                if code == 429 {
                    *throttled += 1;
                    self.slow_down(wait);
                }
                drop(response);
                drop(permit);
                if attempt < 2 {
                    tokio::time::sleep(wait).await;
                    continue;
                }
                bail!("Jev request refused (HTTP {code})");
            }
            ensure!(
                (200..300).contains(&code),
                "Jev request refused (HTTP {code})"
            );
            {
                let mut p = self.pace.lock().unwrap_or_else(|e| e.into_inner());
                p.speed = (p.speed + 1.0 / 75.0).min(1.0);
            }
            let mut bytes = Vec::new();
            let mut stream = response.bytes_stream();
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.map_err(|_| anyhow::anyhow!("Jev response could not be read"))?;
                ensure!(
                    bytes.len().saturating_add(chunk.len()) <= 1024 * 1024,
                    "Jev response exceeds 1 MiB limit"
                );
                bytes.extend_from_slice(&chunk);
            }
            let answer: Value = serde_json::from_slice(&bytes)
                .map_err(|_| anyhow::anyhow!("Jev returned invalid JSON"))?;
            validate_answer(&answer, questions)?;
            return Ok(Some(answer));
        }
        unreachable!()
    }
}

// RFC 7231 dates and delta-seconds are both valid Retry-After values.
fn retry_after(header: &str) -> Option<Duration> {
    if let Ok(seconds) = header.trim().parse::<f64>() {
        return seconds
            .is_finite()
            .then(|| Duration::from_secs_f64(seconds.clamp(0.0, 20.0)));
    }
    let fields: Vec<_> = header.split_whitespace().collect();
    if fields.len() != 6 || fields[5] != "GMT" {
        return None;
    }
    let day = fields[1].parse::<i64>().ok()?;
    let month = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ]
    .iter()
    .position(|m| *m == fields[2])? as i64
        + 1;
    let year = fields[3].parse::<i64>().ok()?;
    let hms = fields[4]
        .split(':')
        .map(str::parse::<i64>)
        .collect::<std::result::Result<Vec<_>, _>>()
        .ok()?;
    if hms.len() != 3
        || !(1..=31).contains(&day)
        || !(1970..=9999).contains(&year)
        || hms[0] > 23
        || hms[1] > 59
        || hms[2] > 60
        || hms.iter().any(|n| *n < 0)
    {
        return None;
    }
    let y = year - i64::from(month <= 2);
    let era = y.div_euclid(400);
    let yo = y - era * 400;
    let mp = month + if month > 2 { -3 } else { 9 };
    let days = era * 146097 + yo * 365 + yo / 4 - yo / 100 + (153 * mp + 2) / 5 + day - 1 - 719468;
    let timestamp = (days * 86400 + hms[0] * 3600 + hms[1] * 60 + hms[2]).max(0) as u64;
    Some(Duration::from_millis(
        timestamp
            .saturating_mul(1000)
            .saturating_sub(crate::now_ms())
            .min(20_000),
    ))
}
fn validate_answer(answer: &Value, questions: &Value) -> Result<()> {
    ensure!(
        answer["answers"].is_object(),
        "Jev answered without answers"
    );
    for (id, question) in questions.as_object().context("Invalid Jev questions")? {
        let a = &answer["answers"][id];
        ensure!(
            a["type"] == question["type"],
            "Jev did not answer every question"
        );
        match s(question, "type") {
            "choice" => ensure!(
                a["choice"].is_string()
                    && a["confidence"]
                        .as_f64()
                        .is_some_and(|n| (0.0..=1.0).contains(&n)),
                "Jev returned invalid choice"
            ),
            "score" => ensure!(
                a["score"].as_f64().is_some_and(f64::is_finite),
                "Jev returned invalid score"
            ),
            _ => bail!("Unsupported Jev question"),
        }
    }
    Ok(())
}
#[derive(Default)]
struct Ratings {
    done_files: HashMap<String, Value>,
    checked_files: HashSet<String>,
    done_pulls: HashMap<String, Value>,
    running_files: HashSet<String>,
    failed_files: HashMap<String, u64>,
    running_pulls: HashSet<String>,
    latest_pulls: HashMap<String, String>,
    failed_pulls: HashMap<String, (String, u64)>,
    by_diff: VecDeque<(String, Value)>,
}
struct Service {
    client: Arc<Client>,
    ratings: Mutex<Ratings>,
    hydration: Mutex<()>,
    #[cfg(test)]
    key_computations: std::sync::atomic::AtomicUsize,
}
impl Service {
    fn new(client: Arc<Client>) -> Self {
        Self {
            client,
            ratings: Mutex::default(),
            hydration: Mutex::default(),
            #[cfg(test)]
            key_computations: std::sync::atomic::AtomicUsize::new(0),
        }
    }
    fn file_keys(&self, diff: &Value) -> Vec<(String, String)> {
        candidates(diff)
            .map(|file| {
                #[cfg(test)]
                self.key_computations.fetch_add(1, Ordering::Relaxed);
                (s(file, "path").to_owned(), file_key(file))
            })
            .collect()
    }
    fn hydrate_files(
        &self,
        keys: &[(String, String)],
        mut read: impl FnMut(&str) -> Option<Value>,
    ) {
        // Serialize first reads without blocking classification completion on disk IO.
        let _hydration = self.hydration.lock().unwrap_or_else(|e| e.into_inner());
        for (_, key) in keys {
            {
                let ratings = self.ratings.lock().unwrap_or_else(|e| e.into_inner());
                if ratings.checked_files.contains(key) || ratings.done_files.contains_key(key) {
                    continue;
                }
            }
            let saved = read(key).filter(is_attention);
            let mut ratings = self.ratings.lock().unwrap_or_else(|e| e.into_inner());
            ratings.checked_files.insert(key.clone());
            if let Some(saved) = saved {
                ratings.done_files.entry(key.clone()).or_insert(saved);
            }
        }
    }
}
static CLIENT: OnceLock<Arc<Client>> = OnceLock::new();
static SERVICES: OnceLock<Mutex<HashMap<PathBuf, Arc<Service>>>> = OnceLock::new();
fn client() -> Arc<Client> {
    CLIENT
        .get_or_init(|| {
            Arc::new(Client::new(
                ["PULLS_REVIEW_TYPESAFE_API_KEY", "TYPESAFE_API_KEY"]
                    .iter()
                    .find_map(|key| {
                        std::env::var(key)
                            .ok()
                            .map(|v| v.trim().to_owned())
                            .filter(|v| !v.is_empty())
                    }),
            ))
        })
        .clone()
}
fn service(app: &AppState) -> Arc<Service> {
    SERVICES
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entry(app.host.storage.root().to_path_buf())
        .or_insert_with(|| Arc::new(Service::new(client())))
        .clone()
}
pub fn enabled() -> bool {
    client().enabled()
}
fn file_questions() -> Value {
    json!({"attention":{"type":"choice","instructions":"How much attention does a reviewer need to give this changed file? Judge the change itself, not how large it is.","criteria":{"p0":"Needs human judgment: logic, security, data handling, API contracts, concurrency or money. A mistake here could break behaviour or leak data.","p1":"Worth a look: ordinary code changes that follow what the code already does.","p2":"Low attention: generated files, lockfiles, formatting, renames, snapshots or trivial docs."}}})
}
fn pull_questions() -> Value {
    json!({"kind":{"type":"choice","instructions":"What kind of change is this pull request? Judge from the title, labels, branch name and changed files together. When it does several things, pick its main purpose.","criteria":{"feature":"Adds a capability users or callers can see: a new screen, command, option, endpoint, API or behaviour. Tests or refactoring done along the way do not change this.","fix":"Corrects a bug: behaviour that was wrong, crashed, regressed or did not do what it was meant to. Titles often say fix, bug, crash, broken or regression.","refactor":"Restructures, renames, moves or cleans up code without changing what it does for users or callers.","performance":"Makes existing behaviour faster or lighter (speed, memory, bundle size, fewer requests) without changing what it does.","docs":"Only documentation: READMEs, guides, code comments, docstrings, changelogs or examples. Nothing about how the code runs changes.","tests":"Only tests: adds or changes test cases, fixtures, snapshots or test helpers, not the code under test.","dependencies":"Bumps, adds or removes third-party dependencies: package versions and lockfiles, often opened by Renovate or Dependabot.","infra":"Build, CI, tooling, configuration or release work: workflows, scripts, linters, bundler or compiler settings, containers, deployment, version tags."}},"risk":{"type":"score","instructions":"How risky is merging this pull request: how likely is it to break something, and how badly?","criteria":["trivial: cannot break anything (typos, comments, docs, formatting)","low: small and contained; a mistake would stay in one place and be easy to spot","medium: touches shared code or behaviour that other parts depend on","high: wide, risky or security-sensitive (auth, permissions, data migrations, money, concurrency, many files)"]}})
}
fn file_state(file: &Value) -> Value {
    let patch = file["hunks"]
        .as_array()
        .into_iter()
        .flatten()
        .enumerate()
        .flat_map(|(i, h)| {
            (if i == 0 { "" } else { "\n" })
                .chars()
                .chain(s(h, "header").chars())
                .chain(std::iter::once('\n'))
                .chain(s(h, "patch").chars())
        });
    json!({"path":file["path"],"status":file["status"],"additions":file["additions"],"deletions":file["deletions"],"patch":truncate_utf16(patch, 6000)})
}
fn file_key(file: &Value) -> String {
    hex::encode(Sha256::digest(
        serde_json::to_vec(&json!([file["sha"], file_state(file)])).unwrap(),
    ))
}
fn attention(answer: &Value) -> Value {
    let confidence = answer["confidence"].as_f64().unwrap_or(0.0);
    let choice = s(answer, "choice");
    json!({"priority":if ["p0","p1","p2"].contains(&choice) && confidence >= 0.5 {choice} else {"p0"},"confidence":confidence})
}
fn is_attention(value: &Value) -> bool {
    ["p0", "p1", "p2"].contains(&s(value, "priority"))
        && value["confidence"]
            .as_f64()
            .is_some_and(|n| (0.0..=1.0).contains(&n))
}
fn candidates(diff: &Value) -> impl Iterator<Item = &Value> {
    diff["files"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|f| f["isBinary"] != true)
        .take(MAX_RATED_FILES)
}
fn identity(context: &Value) -> String {
    format!(
        "{}/{}@{}..{}",
        s(context, "owner"),
        s(context, "repo"),
        s(context, "baseSha"),
        s(context, "headSha")
    )
}
fn diff_identity(diff: &Value) -> Option<String> {
    let r = &diff["ref"];
    if s(r, "owner").is_empty() || s(r, "repo").is_empty() || s(&diff["head"], "sha").is_empty() {
        return None;
    }
    Some(identity(
        &json!({"owner":r["owner"],"repo":r["repo"],"baseSha":diff["base"]["sha"],"headSha":diff["head"]["sha"]}),
    ))
}
fn off() -> Value {
    json!({"enabled":false,"files":{},"pending":0})
}
/// Read ratings only. Opening or polling a diff MUST NOT initiate a paid request
/// (port of server-jev-only-when-needed).
pub fn known_files(app: &AppState, diff: &Value) -> Value {
    let svc = service(app);
    if !svc.client.enabled() {
        return off();
    }
    let keys = svc.file_keys(diff);
    svc.hydrate_files(&keys, |key| {
        app.host
            .storage
            .get(&format!("jev-file:{key}"))
            .ok()
            .flatten()
    });
    let mut result = file_snapshot(&svc, &keys, diff_identity(diff));
    let pending = result["pending"].as_u64().unwrap_or(0);
    if let Some(pull) = pull_of_diff(diff) {
        if let Some(value) = lookup_pull(app, &pull) {
            result["pull"] = value;
        }
        if svc
            .ratings
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .running_pulls
            .contains(&version_key(&pull))
        {
            result["pending"] = json!(pending + 1);
        }
    }
    if !svc.client.enabled() {
        off()
    } else {
        result
    }
}
fn file_snapshot(svc: &Service, keys: &[(String, String)], id: Option<String>) -> Value {
    if !svc.client.enabled() {
        return off();
    }
    let mut files = json!({});
    let mut pending = 0;
    let mut ratings = svc.ratings.lock().unwrap_or_else(|e| e.into_inner());
    for (path, key) in keys {
        if let Some(value) = ratings.done_files.get(key) {
            files[path] = value.clone();
        } else if ratings.running_files.contains(key) {
            pending += 1;
        }
    }
    if let Some(id) = id {
        ratings.by_diff.retain(|(key, _)| key != &id);
        ratings.by_diff.push_back((id, files.clone()));
        while ratings.by_diff.len() > 50 {
            ratings.by_diff.pop_front();
        }
    }
    if svc.client.enabled() {
        json!({"enabled":true,"files":files,"pending":pending})
    } else {
        off()
    }
}

fn start_files(app: &Arc<AppState>, diff: &Value, keys: &[(String, String)]) {
    let svc = service(app);
    if !svc.client.enabled() {
        return;
    }
    for (file, (_, key)) in candidates(diff).zip(keys) {
        {
            let mut ratings = svc.ratings.lock().unwrap_or_else(|e| e.into_inner());
            if ratings.done_files.contains_key(key) {
                continue;
            }
            if ratings
                .failed_files
                .get(key)
                .is_some_and(|at| crate::now_ms().saturating_sub(*at) < RETRY_FAILED_MS)
                || !ratings.running_files.insert(key.clone())
            {
                continue;
            }
        }
        let app = app.clone();
        let svc = svc.clone();
        let input = file_state(file);
        let key = key.clone();
        tokio::spawn(async move {
            let result = svc.client.ask(&app, input, file_questions()).await;
            complete_file(app, svc, key, result).await;
        });
    }
}
/// @cc [owner:OctavianTocan,label:architecture] rust-jev-transactions-off-runtime
/// TypeSafe storage and ratings-lock transactions MUST run on blocking workers,
/// including completion cleanup and polling. Pull writes MUST retain their latest
/// version guard (Tavi's 16 Oct parity request; validated CE review round 6, #4).
async fn complete_file(
    app: Arc<AppState>,
    svc: Arc<Service>,
    key: String,
    result: Result<Option<Value>>,
) {
    // Dropping the waiting task does not cancel this transaction or its cleanup.
    let _ = tokio::task::spawn_blocking(move || {
        let mut ratings = svc.ratings.lock().unwrap_or_else(|e| e.into_inner());
        match result {
            Ok(Some(answer)) => {
                let value = attention(&answer["answers"]["attention"]);
                let _ = app.host.storage.set(&format!("jev-file:{key}"), &value);
                ratings.done_files.insert(key.clone(), value);
                ratings.failed_files.remove(&key);
            }
            Err(_) => {
                ratings.failed_files.insert(key.clone(), crate::now_ms());
            }
            _ => {}
        }
        ratings.running_files.remove(&key);
    })
    .await;
}
fn pull_key(pull: &Value) -> String {
    format!(
        "{}/{}#{}",
        s(pull, "owner"),
        s(pull, "repo"),
        pull["number"]
            .as_str()
            .map(str::to_owned)
            .unwrap_or_else(|| pull["number"].to_string())
    )
}
fn version_key(pull: &Value) -> String {
    format!(
        "{}@{}\n{}",
        pull_key(pull),
        s(pull, "headSha"),
        s(pull, "title")
    )
}
fn current(saved: &Value, pull: &Value) -> bool {
    s(saved, "headSha") == s(pull, "headSha")
        && saved["title"] == pull["title"]
        && KINDS.contains(&s(saved, "kind"))
        && saved["kindConfidence"]
            .as_f64()
            .is_some_and(|n| (0.0..=1.0).contains(&n))
        && saved["risk"].as_f64().is_some_and(f64::is_finite)
}
fn lookup_pull(app: &AppState, pull: &Value) -> Option<Value> {
    let svc = service(app);
    if !svc.client.enabled() {
        return None;
    }
    let key = pull_key(pull);
    let saved = svc
        .ratings
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .done_pulls
        .get(&key)
        .cloned()
        .or_else(|| {
            app.host
                .storage
                .get(&format!("jev-pr:{key}"))
                .ok()
                .flatten()
        })?;
    current(&saved, pull).then(|| json!({"kind":saved["kind"],"kindConfidence":saved["kindConfidence"],"risk":saved["risk"]}))
}
pub async fn apply_pulls(app: &AppState, mut snapshot: Value) -> Result<Value> {
    let app = app.clone();
    tokio::task::spawn_blocking(move || {
        apply_pulls_stored(&app, &mut snapshot);
        snapshot
    })
    .await
    .context("Jev classification snapshot unavailable")
}
fn apply_pulls_stored(app: &AppState, snapshot: &mut Value) {
    if let Some(pulls) = snapshot["pulls"].as_array_mut() {
        for pull in pulls {
            if let Some(obj) = pull.as_object_mut() {
                obj.remove("jev");
            }
            if pull["state"] == "open" {
                if let Some(jev) = lookup_pull(app, pull) {
                    pull["jev"] = jev;
                }
            }
        }
    }
    if service(app).client.enabled() {
        snapshot["classifier"] = json!("jev");
    } else if let Some(obj) = snapshot.as_object_mut() {
        obj.remove("classifier");
    }
}
fn pull_state(app: &AppState, pull: &Value) -> Value {
    let mut state = json!({"repository":format!("{}/{}",s(pull,"owner"),s(pull,"repo")),"title":pull["title"],"labels":pull["labels"],"branch":s(pull,"headRef"),"author":pull["author"]});
    if let Ok(Some(entry)) = app
        .host
        .storage
        .get(&format!("diff:github:{}", pull_key(pull)))
    {
        if entry["diff"]["head"]["sha"].as_str() == pull["headSha"].as_str()
            && pull["headSha"].is_string()
        {
            state["files"] = json!(entry["diff"]["files"]
                .as_array()
                .into_iter()
                .flatten()
                .take(80)
                .map(|f| f["path"].clone())
                .collect::<Vec<_>>());
        }
    }
    state
}
/// Starts missing current classifications without waiting for provider responses.
/// Each version is deduplicated; older responses cannot replace a newer head/title.
pub async fn classify_pulls(app: &Arc<AppState>, pulls: &[Value]) -> Result<()> {
    let app = app.clone();
    let pulls = pulls.to_vec();
    tokio::task::spawn_blocking(move || start_pulls(&app, &pulls))
        .await
        .context("Jev classification preparation unavailable")
}
fn start_pulls(app: &Arc<AppState>, pulls: &[Value]) {
    let svc = service(app);
    if !svc.client.enabled() {
        return;
    }
    for pull in pulls.iter().filter(|p| p["state"] == "open") {
        if lookup_pull(app, pull).is_some() {
            continue;
        }
        let key = pull_key(pull);
        let version = version_key(pull);
        {
            let mut ratings = svc.ratings.lock().unwrap_or_else(|e| e.into_inner());
            if ratings.failed_pulls.get(&key).is_some_and(|(v, at)| {
                v == &version && crate::now_ms().saturating_sub(*at) < RETRY_FAILED_MS
            }) || !ratings.running_pulls.insert(version.clone())
            {
                continue;
            }
            ratings.latest_pulls.insert(key.clone(), version.clone());
        }
        let input = pull_state(app, pull);
        let app = app.clone();
        let svc = svc.clone();
        let pull = pull.clone();
        tokio::spawn(async move {
            let result = svc.client.ask(&app, input, pull_questions()).await;
            complete_pull(app, svc, pull, key, version, result).await;
        });
    }
}
async fn complete_pull(
    app: Arc<AppState>,
    svc: Arc<Service>,
    pull: Value,
    key: String,
    version: String,
    result: Result<Option<Value>>,
) {
    let _ = tokio::task::spawn_blocking(move || {
            let mut ratings = svc.ratings.lock().unwrap_or_else(|e| e.into_inner());
            if ratings.latest_pulls.get(&key) == Some(&version) {
                match result {
                    Ok(Some(answer))
                        if KINDS.contains(&s(&answer["answers"]["kind"], "choice")) =>
                    {
                        let saved = json!({"kind":answer["answers"]["kind"]["choice"],"kindConfidence":answer["answers"]["kind"]["confidence"],"risk":answer["answers"]["risk"]["score"].as_f64().unwrap_or(3.0).clamp(0.0,3.0),"headSha":s(&pull,"headSha"),"title":pull["title"],"model":answer["model"]});
                        let _ = app.host.storage.set(&format!("jev-pr:{key}"), &saved);
                        ratings.done_pulls.insert(key.clone(), saved);
                        ratings.failed_pulls.remove(&key);
                    }
                    Ok(None) => {}
                    _ => {
                        ratings
                            .failed_pulls
                            .insert(key, (version.clone(), crate::now_ms()));
                    }
                }
            }
            ratings.running_pulls.remove(&version);
    })
    .await;
}
fn pull_of_diff(diff: &Value) -> Option<Value> {
    let r = &diff["ref"];
    if r["kind"] != "github-pr"
        || !diff["head"].is_object()
        || matches!(s(&diff["pullRequest"], "state"), "closed" | "merged")
    {
        return None;
    }
    Some(
        json!({"owner":r["owner"],"repo":r["repo"],"number":r["number"].as_u64().or_else(||s(r,"number").parse().ok()),"title":diff["title"],"author":s(&diff["author"],"name"),"labels":[],"state":"open","headSha":diff["head"]["sha"],"headRef":diff["head"]["ref"]}),
    )
}
/// @cc [owner:OctavianTocan,label:architecture] rust-jev-preparation-polls-memory
/// Review preparation MUST NOT reread stored file ratings or rebuild patch identities
/// while waiting for classifications; completed ratings MUST reach cached triage
/// (Tavi's 16 Oct parity request; validated CE review round 4).
pub async fn prepare_review(app: &Arc<AppState>, diff: &Value, wait_ms: u64) {
    let svc = service(app);
    if !svc.client.enabled() {
        return;
    }
    let prep_app = app.clone();
    let prep_diff = diff.clone();
    let prep_svc = svc.clone();
    let Ok((keys, pull_version, id)) = tokio::task::spawn_blocking(move || {
        let keys = prep_svc.file_keys(&prep_diff);
        prep_svc.hydrate_files(&keys, |key| {
            prep_app
                .host
                .storage
                .get(&format!("jev-file:{key}"))
                .ok()
                .flatten()
        });
        // Only explicit review preparation starts paid file requests. Jobs stay
        // server-owned when this caller reaches its bounded wait or is cancelled.
        start_files(&prep_app, &prep_diff, &keys);
        let pull = pull_of_diff(&prep_diff);
        if let Some(pull) = &pull {
            start_pulls(&prep_app, std::slice::from_ref(pull));
        }
        (
            keys,
            pull.as_ref().map(version_key),
            diff_identity(&prep_diff),
        )
    })
    .await
    else {
        return;
    };
    wait_for_preparation(&svc, &keys, pull_version.as_deref(), id, wait_ms).await;
}
async fn wait_for_preparation(
    svc: &Arc<Service>,
    keys: &[(String, String)],
    pull_version: Option<&str>,
    id: Option<String>,
    wait_ms: u64,
) -> Value {
    let until = Instant::now() + Duration::from_millis(wait_ms);
    let keys = Arc::new(keys.to_vec());
    let pull_version = pull_version.map(str::to_owned);
    loop {
        let pending_svc = svc.clone();
        let pending_keys = keys.clone();
        let pending_pull = pull_version.clone();
        let pending = tokio::task::spawn_blocking(move || {
            let ratings = pending_svc
                .ratings
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            pending_keys
                .iter()
                .any(|(_, key)| ratings.running_files.contains(key))
                || pending_pull.is_some_and(|key| ratings.running_pulls.contains(&key))
        })
        .await
        .unwrap_or(false);
        if !svc.client.enabled() || !pending || Instant::now() >= until {
            break;
        }
        tokio::time::sleep(
            Duration::from_millis(25).min(until.saturating_duration_since(Instant::now())),
        )
        .await;
    }
    let svc = svc.clone();
    tokio::task::spawn_blocking(move || file_snapshot(&svc, &keys, id))
        .await
        .unwrap_or_else(|_| off())
}
pub async fn triage_for(app: &AppState, context: &Value) -> Result<Option<Value>> {
    let app = app.clone();
    let context = context.clone();
    tokio::task::spawn_blocking(move || triage_stored(&app, &context))
        .await
        .context("Jev triage snapshot unavailable")
}
fn triage_stored(app: &AppState, context: &Value) -> Option<Value> {
    let svc = service(app);
    if !svc.client.enabled() {
        return None;
    }
    let files = cached_files(&svc, &identity(context));
    let pull = if context["number"].is_null() {
        None
    } else {
        lookup_pull(app, context)
    };
    if files.as_object().is_none_or(|f| f.is_empty()) && pull.is_none() {
        return None;
    }
    let mut result = json!({"files":files});
    if let Some(pull) = pull {
        result["pull"] = pull;
    }
    Some(result)
}

fn cached_files(svc: &Service, id: &str) -> Value {
    svc.ratings
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .by_diff
        .iter()
        .find(|(key, _)| key == id)
        .map(|(_, files)| files.clone())
        .unwrap_or_else(|| json!({}))
}

#[cfg(test)]
mod tests;
