//! Source-bound Responses jobs. A trusted factory must install custody, repository/tool
//! authority and a configured allowance; this module never constructs those authorities.
use super::{
    authority::{AiConnectionRef, AiCredentialCapability, AiFuture, AiProviderKind},
    chatgpt_responses::{
        validate_history, ResponsesAnswer, ResponsesClient, ResponsesProgress, ResponsesRequest,
        ResponsesTools,
    },
    Jobs, StructuredAnswerPreparation,
};
use crate::{now_ms, store::Store};
use anyhow::{anyhow, bail, ensure, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock, Weak},
    time::Duration,
};
use tokio::sync::{mpsc, oneshot, watch};

const MAX_TASKS: usize = 200;
const MAX_TEXT: usize = 2 * 1024 * 1024;
const MAX_STORED_BYTES: usize = 16 * 1024 * 1024;
const MAX_BINDINGS: usize = 256;
const QUOTA_KEY: &str = "ai:chatgpt:quota:v1";
static RUNTIMES: OnceLock<Mutex<HashMap<std::path::PathBuf, Weak<ChatGptJobs>>>> = OnceLock::new();

/// Factory-captured metadata, never a deserialized native request or an authorizing token.
#[derive(Clone, PartialEq, Eq)]
pub(crate) struct ChatGptJobBinding {
    pub owner_id: String,
    pub connection: AiConnectionRef,
    /// Stable, trusted origin/repository authority partition, not a display repository name.
    pub source_id: String,
    pub target_key: String,
    pub head: String,
    pub conversation_id: String,
    pub kind: String,
    pub model: String,
    /// New for each captured source/model authority, including source/head ABA.
    pub generation: String,
}

/// @cc [owner:OctavianTocan,label:security;architecture] chatgpt-job-scope-fences-real-commits
/// The installed factory MUST capture one immutable owner, connection/revision, source,
/// target/head and admitted model. with_current MUST serialize its complete synchronous
/// operation with EVERY retirement/replacement of those authorities; a Boolean or a check
/// before/after an await is insufficient. Tools MUST be constrained to that same source
/// and MUST apply current permission and commit fences to their own effects. Retirement
/// observation MUST be installed before admission. No shell, legacy cache or operator
/// credential MAY be supplied. Source: Tavi's22Oct2026 provider isolation/default-AI
/// requirements and the approved captured-lease Responses runtime boundary.
pub(crate) trait ChatGptJobScope: Send + Sync {
    fn binding(&self) -> &ChatGptJobBinding;
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>>;
    fn with_current(&self, operation: &mut dyn FnMut() -> Result<()>) -> Result<()>;
    fn tools(&self) -> &dyn ResponsesTools;
    /// Pure captured credential/source redaction. Streaming output must withhold a partial
    /// protected-secret prefix; this method neither reads custody nor acquires its commit lock.
    fn safe_output(&self, text: &str, complete: bool) -> Result<String>;
}

/// Explicit configured admission limits; a principal/local() handle grants none of these.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct ChatGptJobLimits {
    pub window_ms: u64,
    pub global_starts: u64,
    pub owner_starts: u64,
    pub owner_inflight: usize,
    pub owner_buckets: usize,
}

struct Allowance {
    limits: ChatGptJobLimits,
    active: Mutex<HashMap<String, usize>>,
}
struct Admission {
    allowance: Arc<Allowance>,
    owner: String,
}
impl Drop for Admission {
    fn drop(&mut self) {
        let mut active = self.allowance.active.lock().unwrap();
        if let Some(count) = active.get_mut(&self.owner) {
            *count -= 1;
            if *count == 0 {
                active.remove(&self.owner);
            }
        }
    }
}

/// @cc [owner:OctavianTocan,label:architecture;security] chatgpt-runtime-keeps-owned-tasks-and-budget
/// Accepted starts MUST outlive their HTTP observer, join identical work without a new
/// quota charge, use the shared three provider slots, and retain Stop/error/result state.
/// Admission MUST enforce configured global and per-owner start/inflight limits; persisted
/// window counts MUST NOT reset on a runtime reopen. Provider queue time MUST NOT consume
/// its execution deadline. Source: server-ai-work-outlives-the-page, jobs-release-acquired-slots
/// and Tavi's22Oct2026 approved GitHub-independent runtime/allowance implementation.
pub(crate) struct ChatGptJobs {
    jobs: Jobs,
    store: Store,
    client: Arc<ResponsesClient>,
    allowance: Arc<Allowance>,
    bindings: Mutex<HashMap<String, Weak<Session>>>,
    shutdown: watch::Sender<bool>,
}

#[derive(Clone)]
pub(crate) struct ChatGptJobSession(Arc<Session>);
struct Session {
    runtime: Arc<ChatGptJobs>,
    lease: Arc<dyn AiCredentialCapability>,
    scope: Arc<dyn ChatGptJobScope>,
    binding: ChatGptJobBinding,
    partition: String,
    initialized: tokio::sync::OnceCell<()>,
    live: Mutex<Option<Arc<Run>>>,
    serial: tokio::sync::Mutex<()>,
}
struct Run {
    state: Mutex<Task>,
    raw_output: Mutex<(String, String)>,
    cancel: watch::Sender<bool>,
    changed: watch::Sender<u64>,
    finished: watch::Sender<bool>,
}

#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
enum Status {
    Running,
    Done,
    Failed,
    Cancelled,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Task {
    id: String,
    request_id: String,
    #[serde(default)]
    joined_requests: Vec<String>,
    fingerprint: String,
    model: String,
    question: String,
    effort: Option<String>,
    status: Status,
    rev: u64,
    started_at: u64,
    ended_at: Option<u64>,
    result: Option<String>,
    error: Option<String>,
    usage: Option<Value>,
    #[serde(default)]
    text: String,
    #[serde(default)]
    summary: String,
}
#[derive(Clone)]
struct Stored {
    history: Vec<Value>,
    tasks: Vec<Task>,
}

impl ChatGptJobs {
    /// One lifecycle-owned instance is shared by all handlers for this AI context. The
    /// supplied Store is the dedicated provider state, not a legacy AppState/cache. The host
    /// must call shutdown before draining requests; no listener/factory is installed here.
    pub(crate) fn new(
        jobs: Jobs,
        store: Store,
        client: ResponsesClient,
        limits: ChatGptJobLimits,
    ) -> Result<Arc<Self>> {
        ensure!(
            limits.window_ms > 0
                && limits.global_starts > 0
                && limits.owner_starts > 0
                && limits.owner_starts <= limits.global_starts
                && (1..=3).contains(&limits.owner_inflight)
                && (1..=4096).contains(&limits.owner_buckets),
            "The AI allowance is not configured"
        );
        let mut runtimes = RUNTIMES
            .get_or_init(Default::default)
            .lock()
            .map_err(|_| unavailable())?;
        runtimes.retain(|_, runtime| runtime.strong_count() > 0);
        if let Some(existing) = runtimes.get(store.root()).and_then(Weak::upgrade) {
            ensure!(
                existing.allowance.limits == limits
                    && Arc::ptr_eq(&existing.jobs.provider_slots, &jobs.provider_slots),
                "This AI storage already has a different runtime owner"
            );
            return Ok(existing);
        }
        ensure!(runtimes.len() < 128, "Too many AI runtime owners");
        let runtime = Arc::new(Self {
            jobs,
            store,
            client: Arc::new(client),
            allowance: Arc::new(Allowance {
                limits,
                active: Default::default(),
            }),
            bindings: Default::default(),
            shutdown: watch::channel(false).0,
        });
        runtimes.insert(runtime.store.root().to_owned(), Arc::downgrade(&runtime));
        Ok(runtime)
    }

    pub(crate) async fn bind(
        self: &Arc<Self>,
        lease: Arc<dyn AiCredentialCapability>,
        scope: Arc<dyn ChatGptJobScope>,
    ) -> Result<ChatGptJobSession> {
        lease.ensure_current().await?;
        let binding = scope.binding().clone();
        binding.validate(lease.as_ref())?;
        let partition = binding.partition();
        let runtime = self.clone();
        let session = crate::store::blocking(move || {
            let retired = scope.subscribe_retirement()?;
            ensure!(!*retired.borrow(), "AI authority changed");
            fenced(scope.as_ref(), || Ok(()))?;
            let mut bindings = runtime.bindings.lock().map_err(|_| unavailable())?;
            bindings.retain(|_, value| value.strong_count() > 0);
            let existing = bindings.get(&partition).and_then(Weak::upgrade);
            let session = if let Some(existing) = existing.filter(|old| old.binding == binding) {
                fenced(existing.scope.as_ref(), || Ok(()))?;
                existing
            } else {
                if let Some(existing) = bindings.get(&partition).and_then(Weak::upgrade) {
                    ensure!(
                        fenced(existing.scope.as_ref(), || Ok(())).is_err(),
                        "The existing AI conversation must retire before its authority changes"
                    );
                }
                ensure!(
                    bindings.len() < MAX_BINDINGS,
                    "Too many active AI conversations"
                );
                let session = Arc::new(Session {
                    runtime: runtime.clone(),
                    lease,
                    scope,
                    binding,
                    partition: partition.clone(),
                    initialized: Default::default(),
                    live: Default::default(),
                    serial: Default::default(),
                });
                bindings.insert(partition, Arc::downgrade(&session));
                session
            };
            Ok(session)
        })
        .await?;
        session.initialize().await?;
        Ok(ChatGptJobSession(session))
    }

    /// Explicit server shutdown, not an observer's drop/detach. The host must retain this
    /// runtime and wire this operation alongside its native server shutdown sequence.
    pub(crate) async fn shutdown(&self) {
        self.shutdown.send_replace(true);
        let sessions: Vec<_> = self
            .bindings
            .lock()
            .unwrap()
            .values()
            .filter_map(Weak::upgrade)
            .collect();
        for session in sessions {
            let run = session.live.lock().unwrap().clone();
            if let Some(run) = run {
                run.cancel.send_replace(true);
                wait_finished(&run).await;
            }
        }
    }
}

impl ChatGptJobSession {
    /// `request` is prepared by the trusted task factory. It is not a raw JSON RPC payload:
    /// owner, target, credentials and model admission are captured by this session's scope.
    pub(crate) async fn start(
        &self,
        request_id: String,
        request: ResponsesRequest,
    ) -> Result<Value> {
        ensure!(valid_id(&request_id), "Invalid AI request identity");
        ensure!(
            request.model == self.0.binding.model,
            "The AI model is not admitted"
        );
        validate_history(&request.input)?;
        self.0.current().await?;
        let (sender, receiver) = oneshot::channel();
        let session = self.0.clone();
        {
            let mut handles = self
                .0
                .runtime
                .jobs
                .handles
                .lock()
                .map_err(|_| unavailable())?;
            ensure!(
                !self
                    .0
                    .runtime
                    .jobs
                    .stopping
                    .load(std::sync::atomic::Ordering::SeqCst)
                    && !*self.0.runtime.shutdown.borrow(),
                "Server is shutting down"
            );
            handles.retain(|handle| !handle.is_finished());
            // This handle owns both admission and execution. Dropping the caller never cancels
            // an accepted operation or loses the worker between durable admission and spawn.
            handles.push(tokio::spawn(async move {
                match session.admit(request_id, request).await {
                    Ok(Admitted::Joined(task)) => {
                        let receipt = session.publish(task).await;
                        let _ = sender.send(receipt);
                    }
                    Ok(Admitted::New(run, request, allowance)) => {
                        let task = run.state.lock().unwrap().clone();
                        let receipt = session.publish(task).await;
                        let _ = sender.send(receipt);
                        session.execute(run, request, allowance).await;
                    }
                    Err(_) => {
                        let _ = sender.send(Err(anyhow!("The AI task could not be admitted")));
                    }
                }
            }));
        }
        let value = receiver.await.map_err(|_| unavailable())??;
        self.0.current().await?;
        let session = self.0.clone();
        crate::store::blocking(move || fenced(session.scope.as_ref(), || Ok(value))).await
    }

    pub(crate) async fn poll(&self, id: &str) -> Result<Value> {
        self.0.current().await?;
        let id = id.to_owned();
        let session = self.0.clone();
        let task = session
            .transaction(move |session, stored| {
                let live = session.live.lock().map_err(|_| unavailable())?.clone();
                if let Some(run) = live {
                    let task = run.state.lock().map_err(|_| unavailable())?.clone();
                    if task.id == id {
                        return Ok(task);
                    }
                }
                stored
                    .tasks
                    .iter()
                    .find(|task| task.id == id)
                    .cloned()
                    .context("AI task not found")
            })
            .await?;
        self.0.publish(task).await
    }

    pub(crate) async fn result(&self, id: &str) -> Result<String> {
        let snapshot = self.poll(id).await?;
        ensure!(
            snapshot["status"] == "done",
            "The AI task has no completed result"
        );
        Ok(snapshot["result"]
            .as_str()
            .context("The AI task has no completed result")?
            .to_owned())
    }

    /// History exposes task/result snapshots, never opaque provider reasoning or credentials.
    pub(crate) async fn history(&self) -> Result<Vec<Value>> {
        self.0.current().await?;
        let tasks = self
            .0
            .clone()
            .transaction(|_, stored| Ok(stored.tasks.clone()))
            .await?;
        self.0.current().await?;
        let session = self.0.clone();
        crate::store::blocking(move || {
            fenced(session.scope.as_ref(), || {
                Ok(tasks.iter().map(|task| session.public(task)).collect())
            })
        })
        .await
    }

    pub(crate) async fn stop(&self, id: &str) -> Result<Value> {
        self.0.current().await?;
        let _serial = self.0.serial.lock().await;
        let session = self.0.clone();
        let id = id.to_owned();
        let task = session
            .transaction(move |session, stored| {
                let selected = stored
                    .tasks
                    .iter_mut()
                    .find(|task| task.id == id)
                    .context("AI task not found")?;
                if selected.status == Status::Running {
                    let live = session.live.lock().map_err(|_| unavailable())?.clone();
                    let run = live.filter(|run| run.state.lock().unwrap().id == id);
                    let mut next = run
                        .as_ref()
                        .map(|run| run.state.lock().unwrap().clone())
                        .unwrap_or_else(|| selected.clone());
                    next.finish(Status::Cancelled, None, None, None)?;
                    *selected = next.clone();
                    session.write(stored)?;
                    if let Some(run) = run {
                        *run.state.lock().unwrap() = next.clone();
                        run.cancel.send_replace(true);
                        run.changed.send_replace(next.rev);
                    }
                    return Ok(next);
                }
                Ok(selected.clone())
            })
            .await?;
        self.0.publish(task).await
    }

    pub(crate) async fn wait(&self, id: &str, after: u64) -> Result<Value> {
        let live = self.0.live.lock().map_err(|_| unavailable())?.clone();
        let Some(run) = live.filter(|run| run.state.lock().unwrap().id == id) else {
            return self.poll(id).await;
        };
        let mut changed = run.changed.subscribe();
        let scope = self.0.scope.clone();
        let mut retired = crate::store::blocking(move || scope.subscribe_retirement()).await?;
        loop {
            let value = self.poll(id).await?;
            if value["status"] != "running" || value["rev"].as_u64().unwrap_or(0) > after {
                return Ok(value);
            }
            tokio::select! {
                _ = changed.changed() => {},
                _ = retired.changed() => bail!("AI authority changed"),
                _ = self.0.lease.retired() => bail!("AI authority changed"),
                _ = tokio::time::sleep(Duration::from_secs(25)) => return self.poll(id).await,
            }
        }
    }
}

enum Admitted {
    Joined(Task),
    New(Arc<Run>, ResponsesRequest, Admission),
}

impl Session {
    async fn initialize(self: &Arc<Self>) -> Result<()> {
        self.initialized
            .get_or_try_init(|| async {
                let _serial = self.serial.lock().await;
                self.current().await?;
                self.clone()
                    .transaction(|session, stored| {
                        let mut changed = false;
                        for task in &mut stored.tasks {
                            if task.status == Status::Running {
                                task.finish(
                                    Status::Failed,
                                    None,
                                    None,
                                    Some(
                                        "The AI task was interrupted; start again to retry".into(),
                                    ),
                                )?;
                                changed = true;
                            }
                        }
                        if changed {
                            session.write(stored)?;
                        }
                        Ok(())
                    })
                    .await
            })
            .await?;
        Ok(())
    }
    async fn current(self: &Arc<Self>) -> Result<()> {
        self.lease
            .ensure_current()
            .await
            .map_err(|_| authority_changed())?;
        self.binding.validate(self.lease.as_ref())?;
        let session = self.clone();
        crate::store::blocking(move || fenced(session.scope.as_ref(), || Ok(()))).await
    }

    async fn publish(self: &Arc<Self>, task: Task) -> Result<Value> {
        self.current().await?;
        let session = self.clone();
        crate::store::blocking(move || fenced(session.scope.as_ref(), || Ok(session.public(&task))))
            .await
    }

    async fn transaction<T: Send + 'static>(
        self: Arc<Self>,
        operation: impl FnOnce(&Session, &mut Stored) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let store = self.runtime.store.clone();
        store
            .transaction(move |_| {
                fenced(self.scope.as_ref(), || {
                    let mut stored = self.read()?;
                    operation(&self, &mut stored)
                })
            })
            .await
    }

    async fn admit(
        self: &Arc<Self>,
        request_id: String,
        mut request: ResponsesRequest,
    ) -> Result<Admitted> {
        let _serial = self.serial.lock().await;
        self.current().await?;
        ensure!(!*self.runtime.shutdown.borrow(), "Server is shutting down");
        let fingerprint = digest(&json!({"model":request.model,"input":request.input,
            "effort":request.reasoning_effort,"schema":request.answer_schema,
            "preparation":match request.preparation {StructuredAnswerPreparation::Strict=>"strict",StructuredAnswerPreparation::Grouping=>"grouping"}}))?;
        let expected_request_id = request_id.clone();
        let expected_fingerprint = fingerprint.clone();
        let (mut stored, existing) = self
            .clone()
            .transaction(move |session, stored| {
                if let Some(task) = stored
                    .tasks
                    .iter()
                    .find(|task| task.accepts(&expected_request_id))
                {
                    ensure!(
                        task.fingerprint == expected_fingerprint,
                        "AI request identity was reused"
                    );
                    return Ok((stored.clone(), Some(task.clone())));
                }
                if let Some(run) = session.live.lock().unwrap().as_ref() {
                    let mut state = run.state.lock().unwrap();
                    let mut task = state.clone();
                    if task.status == Status::Running {
                        ensure!(
                            task.fingerprint == expected_fingerprint,
                            "An AI reply is already running"
                        );
                        ensure!(
                            task.joined_requests.len() < 64,
                            "Too many joined AI receipts"
                        );
                        task.joined_requests.push(expected_request_id);
                        let at = stored
                            .tasks
                            .iter()
                            .position(|old| old.id == task.id)
                            .context("AI task not found")?;
                        stored.tasks[at] = task.clone();
                        session.write(stored)?;
                        *state = task.clone();
                        return Ok((stored.clone(), Some(task)));
                    }
                }
                // No live owner exists for a persisted running task (restart or retired source).
                // Reconcile it as interrupted; never replay a paid start from a saved record.
                for task in &mut stored.tasks {
                    if task.status == Status::Running {
                        task.finish(
                            Status::Failed,
                            None,
                            None,
                            Some("The AI task was interrupted; start again to retry".into()),
                        )?;
                    }
                }
                Ok((stored.clone(), None))
            })
            .await?;
        if let Some(task) = existing {
            return Ok(Admitted::Joined(task));
        }
        let session = self.clone();
        let prepared_input = request.input;
        request.input = stored.history.clone();
        request.input.extend(prepared_input);
        validate_history(&request.input)?;
        let model = self.binding.model.clone();
        let effort = request.reasoning_effort.clone();
        let question = request
            .input
            .iter()
            .rev()
            .find(|item| item["role"] == "user")
            .map(|item| {
                item["content"]
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| {
                        item["content"]
                            .as_array()
                            .map(|parts| {
                                parts
                                    .iter()
                                    .filter_map(|part| part["text"].as_str())
                                    .collect::<Vec<_>>()
                                    .join("\n")
                            })
                            .unwrap_or_default()
                    })
            })
            .unwrap_or_default();
        let admitted = self
            .runtime
            .store
            .transaction(move |_| {
                fenced(session.scope.as_ref(), || {
                    ensure!(
                        !*session.runtime.shutdown.borrow(),
                        "Server is shutting down"
                    );
                    let question = session.scope.safe_output(&question, true)?;
                    ensure!(
                        question.len() <= MAX_TEXT,
                        "The AI question exceeded its text budget"
                    );
                    let allowance = session.reserve_allowance()?;
                    let task = Task {
                        id: uuid::Uuid::new_v4().to_string(),
                        request_id,
                        joined_requests: vec![],
                        fingerprint,
                        model,
                        question,
                        effort,
                        status: Status::Running,
                        rev: 0,
                        started_at: now_ms(),
                        ended_at: None,
                        result: None,
                        error: None,
                        usage: None,
                        text: String::new(),
                        summary: String::new(),
                    };
                    if stored.tasks.len() == MAX_TASKS {
                        stored.tasks.remove(0);
                    }
                    stored.tasks.push(task.clone());
                    session.write(&stored)?;
                    let run = Arc::new(Run {
                        state: Mutex::new(task),
                        raw_output: Mutex::new((String::new(), String::new())),
                        cancel: watch::channel(false).0,
                        changed: watch::channel(0).0,
                        finished: watch::channel(false).0,
                    });
                    *session.live.lock().unwrap() = Some(run.clone());
                    Ok((run, allowance))
                })
            })
            .await?;
        Ok(Admitted::New(admitted.0, request, admitted.1))
    }

    async fn execute(
        self: Arc<Self>,
        run: Arc<Run>,
        request: ResponsesRequest,
        _allowance: Admission,
    ) {
        let mut cancelled = run.cancel.subscribe();
        let mut shutdown = self.runtime.shutdown.subscribe();
        let scope = self.scope.clone();
        let mut retired = match crate::store::blocking(move || scope.subscribe_retirement()).await {
            Ok(retired) => retired,
            Err(_) => {
                run.finished.send_replace(true);
                return;
            }
        };
        let result = async {
            self.current().await?;
            ensure!(!*cancelled.borrow() && !*shutdown.borrow() && !*retired.borrow(), "Stopped.");
            let _slot = tokio::select! {
                biased;
                _ = cancelled.changed() => bail!("Stopped."),
                _ = shutdown.changed() => bail!("Stopped."),
                _ = retired.changed() => bail!("AI authority changed"),
                _ = self.lease.retired() => bail!("AI authority changed"),
                slot = self.runtime.jobs.provider_slots.clone().acquire_owned() => slot.map_err(|_| unavailable())?,
            };
            self.current().await?;
            let scope=self.scope.clone();
            let specs=crate::store::blocking(move || fenced(scope.as_ref(), || Ok(scope.tools().specs()))).await?;
            let tools = BoundTools {session:self.clone(),specs};
            let (updates,mut receive)=mpsc::channel::<ResponsesProgress>(64);
            let session=self.clone();let publishing=run.clone();
            let publisher=tokio::spawn(async move {
                while let Some(update)=receive.recv().await {
                    let session=session.clone();let publishing=publishing.clone();
                    crate::store::blocking(move || session.progress(&publishing,update)).await?;
                }
                Result::<()>::Ok(())
            });
            let progress = |update| updates.try_send(update).map_err(|_|anyhow!("AI progress is unavailable"));
            let answer=tokio::select! {
                biased;
                _ = shutdown.changed() => Err(anyhow!("Stopped.")),
                _ = retired.changed() => Err(authority_changed()),
                result = self.runtime.client.run(self.lease.as_ref(),cancelled.clone(),request,&tools,&progress) => result,
            };
            drop(updates);
            let published=publisher.await.map_err(|_|unavailable())?;
            if answer.is_ok() { published?; }
            answer
        }.await;
        let _serial = self.serial.lock().await;
        let session = self.clone();
        let selected = run.clone();
        let saved = self
            .runtime
            .store
            .transaction(move |_| {
                fenced(session.scope.as_ref(), || session.commit(&selected, result))
            })
            .await;
        if saved.is_err() {
            let session = self.clone();
            let selected = run.clone();
            let recovered = self
                .runtime
                .store
                .transaction(move |_| {
                    fenced(session.scope.as_ref(), || {
                        session.commit(&selected, Err(unavailable()))
                    })
                })
                .await;
            if recovered.is_err() {
                let scope = self.scope.clone();
                let selected = run.clone();
                let _ = crate::store::blocking(move || {
                    fenced(scope.as_ref(), || {
                        let mut task = selected.state.lock().map_err(|_| unavailable())?;
                        if task.status == Status::Running {
                            task.finish(
                                Status::Failed,
                                None,
                                None,
                                Some("The AI task could not store its result; try again".into()),
                            )?;
                            selected.changed.send_replace(task.rev);
                        }
                        Ok(())
                    })
                })
                .await;
            }
        }
        run.finished.send_replace(true);
    }

    fn progress(&self, run: &Run, update: ResponsesProgress) -> Result<()> {
        fenced(self.scope.as_ref(), || {
            let mut task = run.state.lock().map_err(|_| unavailable())?;
            ensure!(
                task.status == Status::Running && !*run.cancel.borrow(),
                "Stopped."
            );
            let mut raw = run.raw_output.lock().map_err(|_| unavailable())?;
            ensure!(
                raw.0.len() + update.text_delta.len() <= MAX_TEXT
                    && raw.1.len() + update.summary_delta.len() <= MAX_TEXT,
                "The AI task exceeded its text budget"
            );
            raw.0.push_str(&update.text_delta);
            raw.1.push_str(&update.summary_delta);
            let text = self.scope.safe_output(&raw.0, false)?;
            let summary = self.scope.safe_output(&raw.1, false)?;
            ensure!(
                text.len() <= MAX_TEXT && summary.len() <= MAX_TEXT,
                "The AI output exceeded its text budget"
            );
            if text == task.text && summary == task.summary {
                return Ok(());
            }
            task.text = text;
            task.summary = summary;
            task.rev += 1;
            run.changed.send_replace(task.rev);
            Ok(())
        })
    }

    fn commit(&self, run: &Run, result: Result<ResponsesAnswer>) -> Result<()> {
        let mut task = run.state.lock().map_err(|_| unavailable())?;
        if task.status != Status::Running {
            return Ok(());
        }
        let mut stored = self.read()?;
        let at = stored
            .tasks
            .iter()
            .position(|item| item.id == task.id)
            .context("AI task not found")?;
        if stored.tasks[at].status != Status::Running {
            *task = stored.tasks[at].clone();
            run.changed.send_replace(task.rev);
            return Ok(());
        }
        let mut next = task.clone();
        match result {
            Ok(answer) if !*run.cancel.borrow() && !*self.runtime.shutdown.borrow() => {
                validate_history(&answer.history)?;
                ensure!(
                    answer.text.len() <= MAX_TEXT,
                    "The AI answer exceeded its text budget"
                );
                let usage = answer.usage.map(|usage| json!({"inputTokens":usage.input_tokens,"outputTokens":usage.output_tokens,"totalTokens":usage.total_tokens,"cacheReadTokens":usage.input_tokens_details.cached_tokens,"reasoningTokens":usage.output_tokens_details.reasoning_tokens}));
                let text = self.scope.safe_output(&answer.text, true)?;
                ensure!(
                    text.len() <= MAX_TEXT,
                    "The AI output exceeded its text budget"
                );
                let history = clean_history(self.scope.as_ref(), answer.history)?;
                validate_history(&history)?;
                next.finish(Status::Done, Some(text), usage, None)?;
                stored.history = history;
            }
            _ if *run.cancel.borrow() || *self.runtime.shutdown.borrow() => {
                next.finish(Status::Cancelled, None, None, None)?
            }
            Err(_) => next.finish(
                Status::Failed,
                None,
                None,
                Some("The ChatGPT task could not complete; try again".into()),
            )?,
            Ok(_) => unreachable!(),
        }
        stored.tasks[at] = next.clone();
        self.write(&stored)?;
        *task = next;
        run.changed.send_replace(task.rev);
        Ok(())
    }

    /// @cc [owner:OctavianTocan,label:architecture] chatgpt-stored-pending-history-is-not-a-request
    /// A same-binding saved conversation MAY have no completed continuation while its first
    /// task is pending, failed or stopped. Nonempty saved continuations MUST retain the
    /// canonical nested Responses validation, and every incoming/merged provider request
    /// MUST retain its nonempty validation. Source: Tavi's22Oct2026 runtime/recovery
    /// requirement and the approved persisted-pending-state correction.
    fn read(&self) -> Result<Stored> {
        let Some(value) = self.runtime.store.get(&self.partition)? else {
            return Ok(Stored {
                history: vec![],
                tasks: vec![],
            });
        };
        ensure!(
            serde_json::to_vec(&value)?.len() <= MAX_STORED_BYTES
                && value["version"] == 1
                && value["binding"] == self.binding.storage_identity(),
            "AI history is not valid"
        );
        let history: Vec<Value> =
            serde_json::from_value(value["history"].clone()).map_err(|_| unavailable())?;
        if !history.is_empty() {
            validate_history(&history)?;
        }
        let raw = value["tasks"]
            .as_array()
            .context("AI history is not valid")?;
        ensure!(raw.len() <= MAX_TASKS, "AI history is not valid");
        let mut tasks = vec![];
        for item in raw {
            let Ok(task) = serde_json::from_value::<Task>(item.clone()) else {
                continue;
            };
            if task.valid()
                && !tasks.iter().any(|old: &Task| {
                    old.id == task.id
                        || old.accepts(&task.request_id)
                        || task.joined_requests.iter().any(|id| old.accepts(id))
                })
            {
                tasks.push(task);
            }
        }
        Ok(Stored { history, tasks })
    }
    fn write(&self, stored: &Stored) -> Result<()> {
        let value = json!({"version":1,"binding":self.binding.storage_identity(),"history":stored.history,"tasks":stored.tasks});
        ensure!(
            serde_json::to_vec(&value)?.len() <= MAX_STORED_BYTES,
            "AI history exceeded its budget"
        );
        self.runtime.store.set(&self.partition, &value)
    }
    fn reserve_allowance(&self) -> Result<Admission> {
        let allowance = self.runtime.allowance.clone();
        let owner = digest(&json!(self.binding.owner_id))?;
        let limits = allowance.limits;
        let mut active = allowance.active.lock().map_err(|_| unavailable())?;
        ensure!(
            active.get(&owner).copied().unwrap_or(0) < limits.owner_inflight,
            "This AI owner has too many running tasks"
        );
        let now = now_ms();
        self.runtime.store.update(QUOTA_KEY, |before| {
            let mut state = before.unwrap_or_else(|| json!({"since":now,"total":0,"owners":{}}));
            let since = state["since"]
                .as_u64()
                .context("AI allowance is unavailable")?;
            ensure!(since <= now, "AI allowance clock is unavailable");
            if now - since >= limits.window_ms {
                state = json!({"since":now,"total":0,"owners":{}});
            }
            let total = state["total"]
                .as_u64()
                .context("AI allowance is unavailable")?;
            let owners = state["owners"]
                .as_object_mut()
                .context("AI allowance is unavailable")?;
            ensure!(
                owners.contains_key(&owner) || owners.len() < limits.owner_buckets,
                "AI allowance is unavailable"
            );
            let used = owners
                .get(&owner)
                .map(Value::as_u64)
                .unwrap_or(Some(0))
                .context("AI allowance is unavailable")?;
            ensure!(
                total < limits.global_starts && used < limits.owner_starts,
                "The configured AI allowance is exhausted"
            );
            owners.insert(owner.clone(), json!(used + 1));
            state["total"] = json!(total + 1);
            Ok(state)
        })?;
        *active.entry(owner.clone()).or_default() += 1;
        drop(active);
        Ok(Admission { allowance, owner })
    }
    fn public(&self, task: &Task) -> Value {
        let mut value = serde_json::to_value(task).unwrap();
        let object = value.as_object_mut().unwrap();
        object.remove("requestId");
        object.remove("joinedRequests");
        object.remove("fingerprint");
        object.remove("summary");
        object.insert("text".into(), json!(task.question));
        object.insert("engine".into(), json!("codex"));
        object.insert("providerKind".into(), json!("chatGPTSubscription"));
        object.insert("model".into(), json!(task.model));
        object.insert("key".into(), json!(self.binding.target_key));
        object.insert("headSha".into(), json!(self.binding.head));
        object.insert("kind".into(), json!(self.binding.kind));
        object.insert("threadId".into(), json!(self.binding.conversation_id));
        let status = if task.status == Status::Running {
            "running"
        } else if task.status == Status::Failed {
            "failed"
        } else {
            "done"
        };
        let mut activities = vec![];
        for (id, title, text) in [
            ("answer", "Answer", &task.text),
            ("summary", "Thinking", &task.summary),
        ] {
            if !text.is_empty() {
                activities.push(json!({"id":id,"kind":"text","title":title,"text":text,"status":status,"startedAt":task.started_at,"endedAt":task.ended_at,"rev":task.rev}));
            }
        }
        object.insert("activities".into(), json!(activities));
        value
    }
}

impl Task {
    fn accepts(&self, id: &str) -> bool {
        self.request_id == id || self.joined_requests.iter().any(|old| old == id)
    }
    fn finish(
        &mut self,
        status: Status,
        result: Option<String>,
        usage: Option<Value>,
        error: Option<String>,
    ) -> Result<()> {
        ensure!(
            self.status == Status::Running,
            "The AI task already settled"
        );
        self.status = status;
        self.result = result;
        self.usage = usage;
        self.error = error;
        self.ended_at = Some(now_ms());
        self.rev += 1;
        Ok(())
    }
    fn valid(&self) -> bool {
        uuid::Uuid::parse_str(&self.id).is_ok()
            && valid_id(&self.request_id)
            && self.joined_requests.len() <= 64
            && self
                .joined_requests
                .iter()
                .all(|id| valid_id(id) && id != &self.request_id)
            && self.fingerprint.len() == 64
            && self
                .fingerprint
                .bytes()
                .all(|value| value.is_ascii_hexdigit())
            && !self.model.trim().is_empty()
            && self.model.len() <= 256
            && !self.model.chars().any(char::is_control)
            && self.rev < (1u64 << 53)
            && self.text.len() <= MAX_TEXT
            && self.question.len() <= MAX_TEXT
            && self.summary.len() <= MAX_TEXT
            && self
                .result
                .as_ref()
                .is_none_or(|value| value.len() <= MAX_TEXT)
            && self
                .error
                .as_ref()
                .is_none_or(|value| value.len() <= MAX_TEXT)
            && (self.status == Status::Running) == self.ended_at.is_none()
            && (self.status == Status::Done) == self.result.is_some()
    }
}

impl ChatGptJobBinding {
    fn validate(&self, lease: &dyn AiCredentialCapability) -> Result<()> {
        ensure!(
            self.connection.provider_kind == AiProviderKind::ChatGPTSubscription
                && self.connection == *lease.connection()
                && self.owner_id == lease.principal().owner_id()
                && valid_id(&self.owner_id)
                && valid_id(&self.generation)
                && valid_id(&self.conversation_id)
                && !self.source_id.is_empty()
                && self.source_id.len() <= 2048
                && !self.target_key.is_empty()
                && self.target_key.len() <= 2048
                && !self.head.is_empty()
                && self.head.len() <= 256
                && !self.model.trim().is_empty()
                && self.model.len() <= 256
                && !self.model.chars().any(char::is_control)
                && ["analyze", "tour", "brief", "critique", "ask", "chat"]
                    .contains(&self.kind.as_str()),
            "The captured AI task authority is invalid"
        );
        Ok(())
    }
    fn storage_identity(&self) -> Value {
        json!({"owner":self.owner_id,"connection":self.connection.connection_id,"revision":self.connection.revision,"source":self.source_id,"target":self.target_key,"head":self.head,"conversation":self.conversation_id,"kind":self.kind})
    }
    fn partition(&self) -> String {
        format!(
            "ai:chatgpt:session:v1:{}",
            digest(&self.storage_identity()).unwrap()
        )
    }
}
struct BoundTools {
    session: Arc<Session>,
    specs: Value,
}
impl ResponsesTools for BoundTools {
    fn specs(&self) -> Value {
        self.specs.clone()
    }
    fn call<'a>(&'a self, name: &'a str, arguments: &'a Value) -> AiFuture<'a, Value> {
        Box::pin(async move {
            let scope = self.session.scope.clone();
            let mut retired = crate::store::blocking(move || scope.subscribe_retirement()).await?;
            self.session.current().await?;
            ensure!(!*retired.borrow(), "AI authority changed");
            let value = tokio::select! {
                biased;
                _ = retired.changed() => bail!("AI authority changed"),
                value = self.session.scope.tools().call(name,arguments) => value?,
            };
            self.session.current().await?;
            Ok(value)
        })
    }
}
fn clean_history(scope: &dyn ChatGptJobScope, history: Vec<Value>) -> Result<Vec<Value>> {
    fn clean(scope: &dyn ChatGptJobScope, value: Value) -> Result<Value> {
        match value {
            Value::String(text) => Ok(json!(scope.safe_output(&text, true)?)),
            Value::Array(items) => Ok(Value::Array(
                items
                    .into_iter()
                    .map(|item| clean(scope, item))
                    .collect::<Result<_>>()?,
            )),
            Value::Object(mut object) => {
                // This ciphertext is a provider-managed continuation token, not displayed text.
                let opaque = if object.get("type") == Some(&json!("reasoning")) {
                    object.remove("encrypted_content")
                } else {
                    None
                };
                let mut filtered = serde_json::Map::new();
                for (name, value) in object {
                    filtered.insert(scope.safe_output(&name, true)?, clean(scope, value)?);
                }
                if let Some(opaque) = opaque {
                    filtered.insert("encrypted_content".into(), opaque);
                }
                Ok(Value::Object(filtered))
            }
            value => Ok(value),
        }
    }
    history.into_iter().map(|item| clean(scope, item)).collect()
}
fn fenced<T>(scope: &dyn ChatGptJobScope, operation: impl FnOnce() -> Result<T>) -> Result<T> {
    let mut operation = Some(operation);
    let mut result = None;
    scope.with_current(&mut || {
        result = Some(operation.take().context("AI commit was repeated")?()?);
        Ok(())
    })?;
    result.context("AI commit did not run")
}
fn digest(value: &Value) -> Result<String> {
    Ok(hex::encode(Sha256::digest(serde_json::to_vec(value)?)))
}
fn valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || b"-_.".contains(&value))
}
fn unavailable() -> anyhow::Error {
    anyhow!("AI task state is unavailable")
}
fn authority_changed() -> anyhow::Error {
    anyhow!("AI authority changed")
}
async fn wait_finished(run: &Run) {
    let mut finished = run.finished.subscribe();
    while !*finished.borrow_and_update() {
        if finished.changed().await.is_err() {
            break;
        }
    }
}

#[cfg(test)]
mod tests;
