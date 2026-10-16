//! Native server-side jobs: clients observe revisions; only explicit cancellation stops work.
mod lenses;
mod preview;
mod provider;
pub(super) use preview::safe_preview;
mod tasks;
mod tools;
pub(crate) use tasks::{prepare_structured_answer, StructuredAnswerPreparation};
pub async fn mcp(
    state: &crate::AppState,
    context: &serde_json::Value,
    request: serde_json::Value,
) -> serde_json::Value {
    tools::mcp(Some(state), context, request).await
}
pub async fn mcp_context(
    context: &serde_json::Value,
    request: serde_json::Value,
) -> serde_json::Value {
    if let Some(bridge) = context.get("_rustMcpBridge") {
        let failure = || json!({"jsonrpc":"2.0","id":request["id"],"error":{"code":-32603,"message":"The scoped tool bridge is unavailable"}});
        let Some(url) = bridge["url"].as_str() else {
            return failure();
        };
        let Some(token) = bridge["token"].as_str() else {
            return failure();
        };
        // Only the server creates these payloads. Reject redirects and arbitrary schemes even
        // if a job input file is accidentally replaced. Never include connection errors/tokens.
        let Ok(parsed) = url::Url::parse(url) else {
            return failure();
        };
        if parsed.scheme() != "http"
            || parsed.host_str() != Some("host.docker.internal")
            || parsed.path() != "/mcp"
            || parsed.query().is_some()
            || parsed.fragment().is_some()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return failure();
        }
        let Ok(client) = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(120))
            .build()
        else {
            return failure();
        };
        let Ok(mut response) = client
            .post(url)
            .bearer_auth(token)
            .json(&request)
            .send()
            .await
        else {
            return failure();
        };
        if !response.status().is_success() {
            return failure();
        }
        let mut bytes = Vec::new();
        loop {
            match response.chunk().await {
                Ok(Some(chunk)) if bytes.len() + chunk.len() <= 8 * 1024 * 1024 => {
                    bytes.extend_from_slice(&chunk)
                }
                Ok(None) => break,
                _ => return failure(),
            }
        }
        return serde_json::from_slice(&bytes).unwrap_or_else(|_| failure());
    }
    tools::mcp(None, context, request).await
}
use crate::{now_ms, required, text, AppState};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::sync::{watch, Notify, Semaphore};
#[derive(Clone)]
pub struct Jobs {
    provider_slots: Arc<Semaphore>,
    runs: Arc<Mutex<HashMap<String, Arc<Run>>>>,
    sessions: Arc<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    handles: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
    pub(crate) diff_pin_tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
    pub(crate) stopping: Arc<std::sync::atomic::AtomicBool>,
    catalogs: Arc<tokio::sync::Mutex<HashMap<String, (u64, Value)>>>,
    catalog_refreshes: Arc<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    bridge_url: Arc<Mutex<Option<String>>>,
    bridges: Arc<Mutex<HashMap<String, Bridge>>>,
}
impl Default for Jobs {
    fn default() -> Self {
        Self {
            provider_slots: Arc::new(Semaphore::new(3)),
            runs: Default::default(),
            sessions: Default::default(),
            handles: Default::default(),
            diff_pin_tasks: Default::default(),
            stopping: Default::default(),
            catalogs: Default::default(),
            catalog_refreshes: Default::default(),
            bridge_url: Default::default(),
            bridges: Default::default(),
        }
    }
}
struct Bridge {
    run: std::sync::Weak<Run>,
    context: Arc<Value>,
}
pub(super) struct BridgeGuard {
    token: String,
    jobs: Jobs,
}
impl Drop for BridgeGuard {
    fn drop(&mut self) {
        self.jobs.bridges.lock().unwrap().remove(&self.token);
    }
}
/// Configure the dedicated, capability-authenticated Docker tool relay listener.
pub fn configure_bridge(state: &AppState, url: String) {
    *state.jobs.bridge_url.lock().unwrap() = Some(url);
}
pub(super) fn scoped_bridge(
    state: &AppState,
    run: &Arc<Run>,
    context: &Value,
) -> Result<(Value, BridgeGuard)> {
    let url = state
        .jobs
        .bridge_url
        .lock()
        .unwrap()
        .clone()
        .context("The sandbox tool bridge is not running")?;
    let token = format!(
        "{}{}{}",
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple(),
        uuid::Uuid::new_v4().simple()
    );
    let context = Arc::new(context.clone());
    state.jobs.bridges.lock().unwrap().insert(
        token.clone(),
        Bridge {
            run: Arc::downgrade(run),
            context,
        },
    );
    Ok((
        json!({"_rustMcpBridge":{"url":url,"token":token}}),
        BridgeGuard {
            token,
            jobs: state.jobs.clone(),
        },
    ))
}
/// This endpoint has no ambient authority: each unpredictable token names one live job.
/// The source context is retained server-side and cannot be replaced by the MCP caller.
pub async fn bridge_mcp(state: &AppState, token: &str, request: Value) -> Option<Value> {
    if token.len() != 96 {
        return None;
    }
    let (run, context) = {
        let bridges = state.jobs.bridges.lock().unwrap();
        let bridge = bridges.get(token)?;
        (bridge.run.upgrade()?, Arc::clone(&bridge.context))
    };
    if check(&run).is_err() || run.snapshot.lock().unwrap()["status"] != "running" {
        return None;
    }
    let mut cancelled = run.cancel.subscribe();
    tokio::select! {
        biased;
        _ = cancelled.changed() => None,
        answer = tools::mcp_for_run(state, &run, context.as_ref(), request) => Some(answer),
    }
}
pub(super) struct Run {
    pub snapshot: Arc<Mutex<Value>>,
    publication: Arc<Mutex<ActivityPublication>>,
    pub request: Value,
    pub task: bool,
    pub notify: Arc<Notify>,
    pub cancel: watch::Sender<bool>,
}
impl Jobs {
    #[cfg(test)]
    pub(crate) fn hold_session_for_test(&self, key: &str) -> Arc<tokio::sync::Mutex<()>> {
        self.session(key)
    }
    fn session(&self, key: &str) -> Arc<tokio::sync::Mutex<()>> {
        self.sessions
            .lock()
            .unwrap()
            .entry(key.to_owned())
            .or_default()
            .clone()
    }
}
pub(super) fn check(run: &Run) -> Result<()> {
    if *run.cancel.borrow() {
        bail!("Stopped.")
    }
    Ok(())
}
pub(super) fn record(
    run: &Run,
    id: &str,
    kind: &str,
    title: &str,
    body: Option<&str>,
    status: &str,
) {
    let mut entry = json!({"id":id,"kind":kind,"title":title,"status":status});
    if let Some(body) = body {
        entry["text"] = json!(body);
    }
    record_patch(run, entry)
}
const TEXT_FLUSH_DELAY: Duration = Duration::from_millis(250);
#[derive(Default)]
struct ActivityPublication {
    pending: std::collections::BTreeMap<usize, Value>,
    timer: Option<tokio::task::JoinHandle<()>>,
    timer_generation: u64,
}
impl ActivityPublication {
    fn stop_timer(&mut self) {
        if let Some(timer) = self.timer.take() {
            self.timer_generation = self.timer_generation.wrapping_add(1);
            timer.abort();
        }
    }
    fn flush_timer(&mut self, snapshot: &mut Value, generation: u64) -> bool {
        // An aborted timer may already be executing and waiting for this mutex.
        // It must not take or publish the next text window's timer and patches.
        if self.timer_generation != generation || self.timer.is_none() {
            return false;
        }
        self.timer.take();
        snapshot["status"] == "running" && self.flush(snapshot)
    }
    fn flush(&mut self, snapshot: &mut Value) -> bool {
        self.stop_timer();
        if self.pending.is_empty() {
            return false;
        }
        let rev = snapshot["rev"].as_u64().unwrap_or(0) + 1;
        self.publish(snapshot, rev);
        snapshot["rev"] = json!(rev);
        true
    }
    fn publish(&mut self, snapshot: &mut Value, rev: u64) {
        for (index, mut entry) in std::mem::take(&mut self.pending) {
            entry["rev"] = json!(rev);
            snapshot["activities"][index] = entry;
        }
    }
}
impl Drop for ActivityPublication {
    fn drop(&mut self) {
        self.stop_timer();
    }
}
/// @cc [owner:OctavianTocan,label:architecture] rust-activity-publications-coalesce-text
/// Unchanged activity patches MUST NOT publish. Text-only deltas MUST coalesce for
/// approximately 250 ms without advancing public revisions. Structural and terminal
/// updates MUST publish immediately, including pending text; settling MUST stop the
/// timer and preserve the final text (R15 finding 4, 16 Oct 2026; former jobs.ts flush).
pub(super) fn record_patch(run: &Run, patch: Value) {
    let Some(id) = patch["id"].as_str().filter(|id| !id.is_empty()) else {
        return;
    };
    let id = sanitize(id);
    // Both timer and terminal publication use this lock order. Pending entries are
    // separate from the snapshot: even an uncursored reader sees only published data.
    let mut publication = run.publication.lock().unwrap();
    let mut s = run.snapshot.lock().unwrap();
    if s["status"] != "running" {
        return;
    }
    let entries = s["activities"].as_array_mut().unwrap();
    let existing = entries.iter().position(|entry| entry["id"] == id);
    let index = existing.unwrap_or(entries.len());
    if existing.is_none() && entries.len() >= 4096 {
        return;
    }
    let previous = publication
        .pending
        .get(&index)
        .or_else(|| entries.get(index));
    let mut entry = previous.cloned().unwrap_or_else(|| {
        json!({"id":id,"kind":"status","title":"Working","status":"running","startedAt":now_ms()})
    });
    let mut structural = existing.is_none();
    let mut changed = structural;
    for field in ["kind", "title", "text", "tool", "status"] {
        if let Some(value) = patch[field].as_str() {
            let value = json!(sanitize(value));
            if entry.get(field) != Some(&value) {
                entry[field] = value;
                changed = true;
                structural |= field != "text";
            }
        }
    }
    if !changed {
        return;
    }
    if entry["status"] != "running" && entry.get("endedAt").is_none() {
        entry["endedAt"] = json!(now_ms());
    }
    let immediate = structural || entry["status"] != "running";
    if existing.is_none() {
        entries.push(Value::Null);
    }
    if entries[index] == entry {
        publication.pending.remove(&index);
        if publication.pending.is_empty() {
            publication.stop_timer();
        }
        return;
    }
    publication.pending.insert(index, entry);
    if immediate {
        publication.flush(&mut s);
        drop(s);
        drop(publication);
        run.notify.notify_waiters();
    } else if publication.timer.is_none() {
        let pending = Arc::downgrade(&run.publication);
        let snapshot = Arc::downgrade(&run.snapshot);
        let notify = Arc::downgrade(&run.notify);
        let deadline = tokio::time::Instant::now() + TEXT_FLUSH_DELAY;
        let generation = publication.timer_generation;
        publication.timer = Some(tokio::spawn(async move {
            tokio::time::sleep_until(deadline).await;
            let (Some(pending), Some(snapshot), Some(notify)) =
                (pending.upgrade(), snapshot.upgrade(), notify.upgrade())
            else {
                return;
            };
            let mut pending = pending.lock().unwrap();
            let mut snapshot = snapshot.lock().unwrap();
            if !pending.flush_timer(&mut snapshot, generation) {
                return;
            }
            drop(snapshot);
            drop(pending);
            notify.notify_waiters();
        }));
    }
}

// Persisted turn activities must see the same final text as terminal task observers.
fn flush_activity_updates(run: &Run) {
    let mut publication = run.publication.lock().unwrap();
    let mut snapshot = run.snapshot.lock().unwrap();
    let published = snapshot["status"] == "running" && publication.flush(&mut snapshot);
    drop(snapshot);
    drop(publication);
    if published {
        run.notify.notify_waiters();
    }
}

fn settle(
    run: &Run,
    status: &str,
    result: Option<String>,
    usage: Option<Value>,
    error: Option<String>,
) {
    let mut publication = run.publication.lock().unwrap();
    let mut s = run.snapshot.lock().unwrap();
    if s["status"] != "running" {
        return;
    }
    publication.stop_timer();
    let next_rev = s["rev"].as_u64().unwrap_or(0) + 1;
    publication.publish(&mut s, next_rev);
    s["rev"] = json!(next_rev);
    s["status"] = json!(status);
    s["endedAt"] = json!(now_ms());
    if let Some(r) = result {
        s["result"] = json!(sanitize(&r))
    }
    if let Some(u) = usage {
        s["usage"] = sanitize_value(&u)
    }
    if let Some(e) = error {
        s["error"] = json!(e)
    }
    let rev = s["rev"].clone();
    for e in s["activities"].as_array_mut().unwrap() {
        if e["status"] == "running" {
            e["status"] = json!(if status == "failed" { "failed" } else { "done" });
            e["rev"] = rev.clone();
            e["endedAt"] = json!(now_ms())
        }
    }
    drop(s);
    drop(publication);
    run.notify.notify_waiters()
}
// JavaScript RPC cursors are numbers, not integer-only indices. Preserve their
// ordering for negative/fractional values instead of silently treating them as 0.
fn wait_for_revision(snapshot: &Value, after: f64) -> bool {
    snapshot["status"] == "running" && snapshot["rev"].as_f64().unwrap_or(0.0) <= after
}
fn snapshot(run: &Run, after: f64) -> Value {
    let stored = run.snapshot.lock().unwrap();
    Value::Object(
        stored
            .as_object()
            .unwrap()
            .iter()
            .map(|(key, value)| {
                let value = if key == "activities" {
                    Value::Array(
                        value
                            .as_array()
                            .unwrap()
                            .iter()
                            .filter(|v| v["rev"].as_f64().unwrap_or(0.0) > after)
                            .cloned()
                            .collect(),
                    )
                } else {
                    value.clone()
                };
                (key.clone(), value)
            })
            .collect(),
    )
}
fn terminal_result(run: &Run) -> Option<Result<Value>> {
    let state = run.snapshot.lock().unwrap();
    if state["status"] == "done" {
        Some(Ok(state["result"].clone()))
    } else if state["status"] != "running" {
        Some(Err(anyhow::anyhow!("{}", text(&state, "error"))))
    } else {
        None
    }
}
fn cancel(run: &Run) -> bool {
    if run.snapshot.lock().unwrap()["status"] != "running" {
        return false;
    }
    run.cancel.send_replace(true);
    settle(run, "cancelled", None, None, None);
    true
}
pub(crate) fn task_key(state: &AppState, id: &str) -> Result<String> {
    Ok(text(&lookup(state, id)?.request, "key").to_owned())
}
fn lookup(state: &AppState, id: &str) -> Result<Arc<Run>> {
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .get(id)
        .cloned()
        .context("That AI task is no longer available")
}
fn same(a: &Value, b: &Value, task: bool) -> bool {
    if text(a, "key").is_empty() || a["key"] != b["key"] {
        return false;
    }
    if !task {
        return true;
    }
    a["kind"] == b["kind"]
        && match text(a, "kind") {
            "analyze" => a["expectedHeadSha"] == b["expectedHeadSha"],
            "critique" => a["variant"] == b["variant"],
            "ask" => a["threadId"] == b["threadId"],
            _ => true,
        }
}
pub(super) fn check_expected_head(request: &Value, entry: &Value) -> Result<()> {
    if request["kind"] == "analyze"
        && request
            .get("expectedHeadSha")
            .is_some_and(|head| head != &entry["headSha"])
    {
        bail!("The pull request changed before analysis started. Run again on the current head.");
    }
    Ok(())
}
/// Complete synchronous AI transactions run here, including their lock acquisition.
async fn storage<T: Send + 'static>(
    state: &AppState,
    operation: impl FnOnce(&AppState) -> Result<T> + Send + 'static,
) -> Result<T> {
    let state = state.clone();
    crate::store::blocking(move || operation(&state)).await
}
/// Keep writes drainable even when Stop drops the task's async waiter.
async fn run_storage<T: Send + 'static>(
    state: &AppState,
    run: &Arc<Run>,
    operation: impl FnOnce(&AppState, &Run) -> Result<T> + Send + 'static,
) -> Result<T> {
    let (send, result) = tokio::sync::oneshot::channel();
    let stored_state = state.clone();
    let run = run.clone();
    {
        let mut handles = state.jobs.handles.lock().unwrap();
        handles.retain(|h| !h.is_finished());
        handles.push(tokio::task::spawn_blocking(move || {
            let _ = send.send(operation(&stored_state, &run));
        }));
    }
    result.await.context("AI storage task stopped")?
}
/// Admission stays independent of the HTTP request once queued. Its complete
/// storage/pin transaction runs on the blocking pool, including mutex acquisition.
async fn start_async(state: &Arc<AppState>, req: Value, task: bool) -> Result<String> {
    let (send, result) = tokio::sync::oneshot::channel();
    {
        let mut handles = state.jobs.handles.lock().unwrap();
        if state
            .jobs
            .stopping
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            bail!("Server is shutting down");
        }
        let stored_state = state.clone();
        handles.retain(|h| !h.is_finished());
        handles.push(tokio::task::spawn_blocking(move || {
            let _ = send.send(start(&stored_state, req, task, None));
        }));
    }
    result.await.context("AI admission stopped")?
}
/// @cc [owner:OctavianTocan,label:architecture] rust-ai-admission-keeps-registry-responsive
/// Admission MUST reserve duplicate work in memory before preparing its saved state,
/// and MUST NOT hold the run registry across storage or cache locks. Reserved runs
/// MUST remain pollable and cancellable, including during shutdown (R13 finding 2,
/// 16 Oct 2026; server-ai-work-outlives-the-page).
fn start(
    state: &Arc<AppState>,
    mut req: Value,
    task: bool,
    parent: Option<&Run>,
) -> Result<String> {
    if task {
        required(&req, "key")?;
        if ![
            "analyze", "critique", "ask", "chat", "tour", "brief", "inbox",
        ]
        .contains(&text(&req, "kind"))
        {
            bail!("Unknown AI task kind")
        };
        if req["kind"] == "ask" {
            required(&req, "threadId")?;
            required(&req, "question")?;
        }
        if req["kind"] == "critique" {
            required(&req, "variant")?;
        }
    } else {
        if !req["system"].is_string() || !req["prompt"].is_string() {
            bail!("System and prompt must be strings")
        };
    }
    // Stamp user-started analyses too, so an old in-flight task cannot consume a
    // newly reserved background attempt for a different head.
    let pin = if task && req["kind"] == "analyze" {
        let key = text(&req, "key");
        let pin = crate::review::pin_diff_blocking(state, key);
        if req.get("expectedHeadSha").is_none() {
            let entry = state
                .host
                .storage
                .get(&format!("diff:{key}"))?
                .context("This diff is no longer cached; open it again.")?;
            req["expectedHeadSha"] = entry["headSha"].clone();
        }
        Some(pin)
    } else {
        None
    };
    let model = if task { &req["model"] } else { &req };
    if !["pi", "claude-code", "codex", "claude"].contains(&text(model, "engine")) {
        bail!("Unknown AI engine")
    }
    for key in ["apiKey", "baseUrl", "url", "endpoint"] {
        if req.get(key).is_some() || model.get(key).is_some() {
            bail!("Provider credentials and endpoints are configured on the server")
        }
    }
    // Populate the title before exposing a reservation, without the registry lock.
    let title = task.then(|| tasks::cached_title(state, &req)).flatten();
    let mut runs = state.jobs.runs.lock().unwrap();
    if state
        .jobs
        .stopping
        .load(std::sync::atomic::Ordering::SeqCst)
    {
        bail!("Server is shutting down");
    }
    runs.retain(|_, r| {
        let s = r.snapshot.lock().unwrap();
        s["status"] == "running"
            || now_ms().saturating_sub(s["endedAt"].as_u64().unwrap_or(now_ms())) < 30 * 60 * 1000
    });
    if let Some(r) = runs.values().find(|r| {
        r.task == task
            && r.snapshot.lock().unwrap()["status"] == "running"
            && same(&req, &r.request, task)
    }) {
        return Ok(text(&r.snapshot.lock().unwrap(), "id").to_owned());
    }
    let id = uuid::Uuid::new_v4().to_string();
    let mut snap = json!({"id":id,"engine":model["engine"],"status":"running","rev":0,"activities":[],"startedAt":now_ms()});
    if let Some(title) = title {
        snap["title"] = json!(title);
    }
    for f in ["model", "effort"] {
        if let Some(v) = model.get(f) {
            snap[f] = v.clone()
        }
    }
    for f in [
        "key", "kind", "variant", "lens", "threadId", "then", "label",
    ] {
        if let Some(v) = req.get(f) {
            snap[f] = v.clone()
        }
    }
    if task {
        snap["label"] = json!(format!("{} {}", text(&req, "kind"), text(&req, "key")));
        if let Some(v) = req.get("question").or(req.get("text")) {
            snap["text"] = v.clone()
        }
    }
    let (tx, _) = watch::channel(false);
    let run = Arc::new(Run {
        snapshot: Mutex::new(snap).into(),
        publication: Default::default(),
        request: req.clone(),
        task,
        notify: Notify::new().into(),
        cancel: tx,
    });
    // Serialize follow-up registration with Stop, but never hold this guard over I/O.
    let parent_snapshot = parent.map(|parent| parent.snapshot.lock().unwrap());
    if parent_snapshot
        .as_ref()
        .is_some_and(|snapshot| snapshot["status"] != "running")
    {
        bail!("Stopped.");
    }
    if let Some(parent) = parent {
        check(parent)?;
    }
    // Reservation makes overlapping starts join this run while preparation yields.
    runs.insert(id.clone(), run.clone());
    drop(parent_snapshot);
    drop(runs);
    if let Err(error) = (|| {
        check(&run)?;
        if task {
            tasks::prepare(state, &run)?;
        }
        check(&run)
    })() {
        let stopped = *run.cancel.borrow();
        let message = if stopped {
            "Stopped.".to_owned()
        } else {
            sanitize(&error.to_string())
        };
        if task {
            let _ = tasks::failed(state, &run, &message, stopped);
        }
        settle(
            &run,
            if stopped { "cancelled" } else { "failed" },
            None,
            None,
            Some(message),
        );
        return Err(error);
    }
    record(&run, "start", "status", "Started", None, "done");
    let handles = state.jobs.handles.clone();
    let state = state.clone();
    let handle = tokio::spawn(async move {
        let mut receiver = run.cancel.subscribe();
        // Keep the large provider/task state machine out of this poll frame.
        // Tokio's outer task boxing does not shrink nested inline polling frames.
        let work: futures_util::future::BoxFuture<'_, _> = if task {
            tasks::perform(&state, &run)
        } else {
            Box::pin(provider::run(&state, &run, &run.request))
        };
        let outcome = if check(&run).is_err() {
            Err(anyhow::anyhow!("Stopped."))
        } else {
            tokio::select! {biased;_=receiver.changed()=>Err(anyhow::anyhow!("Stopped.")),r=work=>r}
        };
        match outcome {
            Ok((answer, usage)) => {
                finish_success(&state, &run, answer, usage).await;
            }
            Err(error) => {
                let stopped = *run.cancel.borrow();
                let message = if stopped {
                    "Stopped.".to_owned()
                } else {
                    sanitize(&error.to_string())
                };
                if task {
                    let _ = tasks::failed_async(&state, &run, &message, stopped).await;
                }
                settle(
                    &run,
                    if stopped { "cancelled" } else { "failed" },
                    None,
                    None,
                    Some(message),
                );
            }
        }
        let summary = snapshot(&run, 0.0);
        let usage = &summary["usage"];
        let _=state.analytics.record("ai.run",json!({"engine":summary["engine"],"model":usage.get("model").filter(|v|v.is_string()).or_else(||summary.get("model").filter(|v|v.is_string())),"task":run.request["kind"],"status":summary["status"],"ms":summary["endedAt"].as_u64().unwrap_or(0).saturating_sub(summary["startedAt"].as_u64().unwrap_or(0)),"costUsd":usage["costUsd"],"tokensIn":usage["inputTokens"],"tokensOut":usage["outputTokens"],"cacheRead":usage["cacheReadTokens"]})).await;
        if let Some(pin) = pin {
            pin.release().await;
        }
    });
    let mut handles = handles.lock().unwrap();
    handles.retain(|h| !h.is_finished());
    handles.push(handle);
    Ok(id)
}
/// @cc [owner:OctavianTocan,label:architecture] rust-followups-visible-before-completion
/// Eligible follow-up tasks MUST be registered before successful analysis completion
/// is observable, and a stopped analysis MUST start no further work (server-follow-ups-
/// run-on-the-server; Android ReviewSession completion observer; review R11, 16 Oct 2026).
async fn finish_success(state: &Arc<AppState>, run: &Arc<Run>, answer: String, usage: Value) {
    let result = run_storage(state, run, move |state, run| {
        if run.task && run.request["kind"] == "analyze" {
            tasks::followups(&Arc::new(state.clone()), run);
        }
        settle(run, "done", Some(answer), Some(usage), None);
        Ok(())
    })
    .await;
    if let Err(error) = result {
        settle(
            run,
            "failed",
            None,
            None,
            Some(sanitize(&error.to_string())),
        );
    }
}
/// @cc [owner:OctavianTocan,label:architecture] rust-shutdown-cancels-before-http-drain
/// Server shutdown MUST reject new jobs and cancel registered runs before waiting for
/// HTTP requests to drain, so a waiting llm-run cannot delay its own cancellation
/// (server-every-cli-through-start-agent; review R12 finding 10, 16 Oct 2026).
pub fn begin_shutdown(state: &AppState) {
    state
        .jobs
        .stopping
        .store(true, std::sync::atomic::Ordering::SeqCst);
    let runs: Vec<_> = state.jobs.runs.lock().unwrap().values().cloned().collect();
    for run in runs {
        cancel(&run);
    }
}
/// Drain jobs and daemon cleanup while the Tokio runtime still exists.
pub async fn shutdown(state: &AppState) {
    begin_shutdown(state);
    loop {
        let handles = std::mem::take(&mut *state.jobs.handles.lock().unwrap());
        if handles.is_empty() {
            break;
        }
        for handle in handles {
            let _ = handle.await;
        }
    }
    crate::review::shutdown_diff_pins(state).await;
    crate::agent_process::shutdown_cleanup().await;
    state.analytics.flush().await;
}
/// Find a UTF-8 suffix boundary without decoding or copying the preceding text.
fn tail_start(text: &str, limit: usize) -> usize {
    text.char_indices()
        .rev()
        .nth(limit - 1)
        .map_or(0, |(at, _)| at)
}
pub(super) fn tail(text: &str) -> String {
    let start = tail_start(text, 8000);
    if start > 0 {
        format!("…{}", &text[start..])
    } else {
        text.to_owned()
    }
}
const KNOWN_SECRETS: &[&str] = &[
    "PULLS_REVIEW_PI_API_KEY",
    "ANTHROPIC_API_KEY",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "OPENAI_API_KEY",
    "GITHUB_TOKEN",
    "GH_TOKEN",
    "TYPESAFE_API_KEY",
];

pub(super) fn sanitize(s: &str) -> String {
    let mut out = s.to_owned();
    for &name in KNOWN_SECRETS {
        if let Ok(key) = std::env::var(name) {
            if !key.is_empty() {
                out = out.replace(&key, "[redacted]")
            }
        }
    }
    for (name, value) in std::env::vars() {
        if value.len() >= 6
            && ["TOKEN", "KEY", "SECRET", "PASSWORD"]
                .iter()
                .any(|part| name.to_ascii_uppercase().contains(part))
        {
            out = out.replace(&value, "[redacted]");
        }
    }
    out
}
/// Redact string values and object keys without serializing first (escaped secrets stay protected).
pub(super) fn sanitize_value(value: &Value) -> Value {
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
/// Withhold an unfinished secret prefix so streaming cannot leak a key across chunks.
pub(super) fn safe_stream_text(s: &str) -> String {
    let mut cut = s.len();
    for (name, value) in std::env::vars() {
        if value.is_empty()
            || !["TOKEN", "KEY", "SECRET", "PASSWORD"]
                .iter()
                .any(|part| name.to_ascii_uppercase().contains(part))
        {
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
pub async fn change_chat(state: &Arc<AppState>, key: &str, id: Option<&str>) -> Result<Value> {
    let runs: Vec<_> = state
        .jobs
        .runs
        .lock()
        .unwrap()
        .values()
        .filter(|r| r.request["key"] == key && r.request["kind"] == "chat")
        .cloned()
        .collect();
    for run in runs {
        cancel(&run);
    }
    let guard = state.jobs.session(key).lock_owned().await;
    let key = key.to_owned();
    let id = id.map(str::to_owned);
    storage(state, move |state| {
        // A detached HTTP waiter must not release the session before its write.
        let _guard = guard;
        crate::review::change_chat_saved(state, &key, id.as_deref())
    })
    .await
}
pub async fn handle(state: &Arc<AppState>, name: &str, args: Value) -> Option<Result<Value>> {
    let a = if args.is_array() {
        args.get(0).cloned().unwrap_or(json!({}))
    } else {
        args
    };
    let result: Result<Value> = match name {
        "ai-task-start" | "ai-job-start" => start_async(state, a, name == "ai-task-start")
            .await
            .map(Value::String),
        "ai-task-wait" | "ai-job-wait" => {
            async {
                let r = lookup(state, required(&a, "id")?)?;
                let after = a["after"].as_f64().context("after must be a number")?;
                let notified = r.notify.notified();
                tokio::pin!(notified);
                notified.as_mut().enable();
                let should_wait = {
                    let state = r.snapshot.lock().unwrap();
                    wait_for_revision(&state, after)
                };
                if should_wait {
                    let _ = tokio::time::timeout(Duration::from_secs(25), notified).await;
                }
                Ok(snapshot(&r, after))
            }
            .await
        }
        "ai-task-cancel" | "ai-job-cancel" => required(&a, "id")
            .and_then(|id| lookup(state, id))
            .map(|r| json!(cancel(&r))),
        "ai-task-list" | "ai-task-list-all" | "ai-job-list" => {
            let mut rs: Vec<Value> = state
                .jobs
                .runs
                .lock()
                .unwrap()
                .values()
                .filter(|r| {
                    r.task == (name != "ai-job-list")
                        && (name != "ai-task-list" || r.request["key"] == a["key"])
                })
                .map(|r| snapshot(r, f64::INFINITY))
                .collect();
            rs.sort_by_key(|s| std::cmp::Reverse(s["startedAt"].as_u64().unwrap_or(0)));
            Ok(json!(rs))
        }
        "ai-models" => {
            provider::catalog_refresh(state, text(&a, "engine"), a["refresh"] == true).await
        }
        "llm-run" => {
            async {
                let id = start_async(state, a, false).await?;
                let run = lookup(state, &id)?;
                loop {
                    let notified = run.notify.notified();
                    tokio::pin!(notified);
                    notified.as_mut().enable();
                    if let Some(result) = terminal_result(&run) {
                        return result;
                    }
                    notified.await
                }
            }
            .await
        }
        "ask-threads" => {
            storage(state, move |state| {
                saved_threads(state, text(&a, "key")).map(|v| json!(v))
            })
            .await
        }
        "ask-thread-close" => {
            storage(state, move |state| {
                tasks::close_thread(state, text(&a, "key"), text(&a, "threadId"))
            })
            .await
        }
        "inbox-chat" => storage(state, saved_inbox).await,
        "inbox-chat-clear" => {
            for r in state
                .jobs
                .runs
                .lock()
                .unwrap()
                .values()
                .filter(|r| r.request["kind"] == "inbox")
            {
                cancel(r);
            }
            storage(state, |state| {
                state
                    .host
                    .storage
                    .set("inbox-chat", &json!({"turns":[]}))
                    .map(|_| Value::Null)
            })
            .await
        }
        "lens-list" => storage(state, lenses).await,
        "lens-get" => {
            storage(state, move |state| {
                lens(state, text(&a, "name")).map(|v| v.map(Value::String).unwrap_or(Value::Null))
            })
            .await
        }
        _ => return None,
    };
    Some(result)
}
pub fn saved_threads(state: &AppState, key: &str) -> Result<Vec<Value>> {
    let mut threads = tasks::threads(state.host.storage.get(&format!("ask-threads:{key}"))?);
    for thread in &mut threads {
        recover_turns(state, &mut thread["turns"]);
    }
    Ok(threads)
}
fn saved_inbox(state: &AppState) -> Result<Value> {
    let mut chat = tasks::inbox(state.host.storage.get("inbox-chat")?);
    recover_turns(state, &mut chat["turns"]);
    Ok(chat)
}
fn recover_turns(state: &AppState, turns: &mut Value) {
    for turn in turns.as_array_mut().into_iter().flatten() {
        if turn.get("answer").is_some() || turn.get("outcome").is_some() {
            continue;
        }
        let live = state
            .jobs
            .runs
            .lock()
            .unwrap()
            .get(text(turn, "taskId"))
            .cloned();
        if !live.is_some_and(|r| r.snapshot.lock().unwrap()["status"] == "running") {
            turn["outcome"] = json!("failed");
            turn["error"] =
                json!("The server restarted before this answer finished. Ask again to retry.");
        }
    }
}
fn skill_root(state: &AppState) -> std::path::PathBuf {
    std::env::var("PULLS_REVIEW_SKILLS_DIR")
        .ok()
        .filter(|value| !value.is_empty())
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            state
                .host
                .cwd
                .as_deref()
                .unwrap_or_else(|| std::path::Path::new("."))
                .join("docker/agents/skills")
        })
}
fn lens(state: &AppState, name: &str) -> Result<Option<String>> {
    lenses::get(&skill_root(state), name)
}
fn lenses(state: &AppState) -> Result<Value> {
    lenses::list(&skill_root(state))
}
/// Validate the JSON Schema subset used by the app's answer tools, failing closed on wrong shapes.
pub(super) fn validate_schema(schema: &Value, value: &Value) -> Result<()> {
    if schema.is_null() {
        return Ok(());
    }
    if let Some(variants) = schema["anyOf"].as_array() {
        if !variants.iter().any(|s| validate_schema(s, value).is_ok()) {
            bail!("Model answer did not match schema")
        };
        return Ok(());
    }
    if let Some(e) = schema["enum"].as_array() {
        if !e.contains(value) {
            bail!("Model answer has an invalid enum value")
        }
    }
    let valid = match text(schema, "type") {
        "object" => value.is_object(),
        "array" => value.is_array(),
        "string" => value.is_string(),
        "number" => value.is_number(),
        "integer" => value.is_i64() || value.is_u64(),
        "boolean" => value.is_boolean(),
        "null" => value.is_null(),
        _ => true,
    };
    if !valid {
        bail!("Model answer has an invalid value type")
    };
    if let Some(object) = value.as_object() {
        for k in schema["required"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
        {
            if !object.contains_key(k) {
                bail!("Model answer is missing required field {k}")
            }
        }
        if let Some(props) = schema["properties"].as_object() {
            for (k, v) in object {
                if let Some(s) = props.get(k) {
                    validate_schema(s, v)?
                } else if schema["additionalProperties"] == false {
                    bail!("Model answer contains unknown field {k}")
                }
            }
        }
    }
    if let Some(items) = value.as_array() {
        for item in items {
            validate_schema(&schema["items"], item)?
        }
    }
    Ok(())
}

#[cfg(test)]
mod publication_tests;
#[cfg(test)]
mod tests;
