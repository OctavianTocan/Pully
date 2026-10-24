use super::*;
use crate::ai::authority::{
    AiConnectionAuthority, AiPrincipal, AiPrincipalSource, AiRequestAuthorizer,
};
use axum::{
    body::Body, extract::State, http::StatusCode, response::Response, routing::post, Router,
};
use futures_util::{stream, StreamExt};
use std::{
    collections::VecDeque,
    convert::Infallible,
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Condvar,
    },
};
use tokio::sync::{Notify, Semaphore};

const SECRET: &str = "sample-owned-siwc-bearer";

enum Reply {
    Stream(String),
    Held(String),
    Split { prefix: String, suffix: String },
    Reject,
}
struct Wire {
    replies: Mutex<VecDeque<Reply>>,
    requests: Mutex<Vec<Value>>,
    entered: Notify,
    release: Arc<Semaphore>,
}
struct Provider {
    endpoint: String,
    state: Arc<Wire>,
    server: crate::rt::JoinHandle<()>,
}
impl Provider {
    async fn new(replies: Vec<Reply>) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/responses", listener.local_addr().unwrap());
        let state = Arc::new(Wire {
            replies: Mutex::new(replies.into()),
            requests: Default::default(),
            entered: Notify::new(),
            release: Arc::new(Semaphore::new(0)),
        });
        let router = Router::new()
            .route("/responses", post(provider))
            .with_state(state.clone());
        let server = crate::rt::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        Self {
            endpoint,
            state,
            server,
        }
    }
    async fn entered(&self, count: usize) {
        crate::rt::timeout(Duration::from_secs(5), async {
            loop {
                let notice = self.state.entered.notified();
                if self.state.requests.lock().unwrap().len() >= count {
                    return;
                }
                notice.await;
            }
        })
        .await
        .unwrap();
    }
    fn release(&self, count: usize) {
        self.state.release.add_permits(count);
    }
    fn requests(&self) -> Vec<Value> {
        self.state.requests.lock().unwrap().clone()
    }
}
impl Drop for Provider {
    fn drop(&mut self) {
        self.server.abort();
    }
}
async fn provider(
    State(state): State<Arc<Wire>>,
    headers: axum::http::HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    assert_eq!(headers["authorization"], format!("Bearer {SECRET}"));
    let request: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(request["store"], false);
    assert_eq!(request["stream"], true);
    assert!(request.get("previous_response_id").is_none());
    state.requests.lock().unwrap().push(request);
    state.entered.notify_one();
    let reply = state
        .replies
        .lock()
        .unwrap()
        .pop_front()
        .expect("Unexpected paid-shaped fixture request");
    let (status, body) = match reply {
        Reply::Stream(text) => (StatusCode::OK, Body::from(text)),
        Reply::Reject => (
            StatusCode::SERVICE_UNAVAILABLE,
            Body::from("untrusted provider failure body"),
        ),
        Reply::Held(suffix) => {
            let release = state.release.clone();
            let prefix = stream::once(async { Ok::<_, Infallible>(String::from(": waiting\n\n")) });
            let suffix = stream::once(async move {
                release.acquire().await.unwrap().forget();
                Ok::<_, Infallible>(suffix)
            });
            (StatusCode::OK, Body::from_stream(prefix.chain(suffix)))
        }
        Reply::Split { prefix, suffix } => {
            let release = state.release.clone();
            let first = stream::once(async move { Ok::<_, Infallible>(prefix) });
            let second = stream::once(async move {
                release.acquire().await.unwrap().forget();
                Ok::<_, Infallible>(suffix)
            });
            (StatusCode::OK, Body::from_stream(first.chain(second)))
        }
    };
    Response::builder()
        .status(status)
        .header("content-type", "text/event-stream")
        .body(body)
        .unwrap()
}

struct Authorizer {
    fixture: String,
}
impl AiRequestAuthorizer for Authorizer {
    fn authorize_request(
        &self,
        builder: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder> {
        Box::pin(async move {
            let (client, request) = builder.build_split();
            let mut request = request?;
            ensure!(
                request.method() == reqwest::Method::POST
                    && request.url().as_str()
                        == super::super::chatgpt_responses::SIWC_RESPONSES_ENDPOINT,
                "Wrong captured target"
            );
            // Test-only credential custody validates the exact production target first,
            // then sends this request to our loopback provider. No runtime URL flag exists.
            *request.url_mut() = url::Url::parse(&self.fixture)?;
            request.headers_mut().insert(
                reqwest::header::AUTHORIZATION,
                format!("Bearer {SECRET}").parse()?,
            );
            Ok(reqwest::RequestBuilder::from_parts(client, request))
        })
    }
}

#[derive(Default)]
struct HeldFence {
    armed: AtomicBool,
    entered: Notify,
    released: Mutex<bool>,
    resume: Condvar,
}
impl HeldFence {
    fn arm(&self) {
        *self.released.lock().unwrap() = false;
        self.armed.store(true, Ordering::SeqCst);
    }
    fn before_lock(&self) {
        if self.armed.swap(false, Ordering::SeqCst) {
            self.entered.notify_one();
            let mut released = self.released.lock().unwrap();
            while !*released {
                released = self.resume.wait(released).unwrap();
            }
        }
    }
    async fn entered(&self) {
        crate::rt::timeout(Duration::from_secs(5), self.entered.notified())
            .await
            .unwrap();
    }
    fn release(&self) {
        *self.released.lock().unwrap() = true;
        self.resume.notify_all();
    }
}
struct Gate {
    active: bool,
    tools: bool,
}
struct Scope {
    binding: ChatGptJobBinding,
    principal: Arc<AiPrincipalSource>,
    authority: AiConnectionAuthority,
    gate: Mutex<Gate>,
    retired: watch::Sender<bool>,
    tool_entered: Notify,
    tool_release: Notify,
    hold_tool: AtomicBool,
    effects: AtomicUsize,
    fence: HeldFence,
}
impl Scope {
    fn new(provider: &Provider, owner: &str, conversation: &str) -> Arc<Self> {
        let principal = AiPrincipalSource::verified(owner.into()).unwrap();
        let connection = AiConnectionRef {
            connection_id: "approved-siwc-connection".into(),
            revision: 7,
            provider_kind: AiProviderKind::ChatGPTSubscription,
        };
        let authority = AiConnectionAuthority::new(
            principal.clone(),
            connection.clone(),
            Arc::new(Authorizer {
                fixture: provider.endpoint.clone(),
            }),
        )
        .unwrap();
        Arc::new(Self {
            binding: ChatGptJobBinding {
                owner_id: owner.into(),
                connection,
                source_id: "fixed-public-github-review-authority".into(),
                target_key: "gh:sample/repo:76".into(),
                head: "captured-head".into(),
                conversation_id: conversation.into(),
                kind: "chat".into(),
                model: "admitted-siwc-model".into(),
                generation: uuid::Uuid::new_v4().to_string(),
            },
            principal,
            authority,
            gate: Mutex::new(Gate {
                active: true,
                tools: true,
            }),
            retired: watch::channel(false).0,
            tool_entered: Notify::new(),
            tool_release: Notify::new(),
            hold_tool: AtomicBool::new(false),
            effects: AtomicUsize::new(0),
            fence: Default::default(),
        })
    }
    fn retire(&self, principal: bool) {
        let mut gate = self.gate.lock().unwrap();
        gate.active = false;
        if principal {
            self.principal.retire();
        } else {
            self.authority.retire();
        }
        self.retired.send_replace(true);
    }
    fn lease(&self) -> Arc<dyn AiCredentialCapability> {
        Arc::new(self.authority.capture().unwrap())
    }
}
impl ChatGptJobScope for Scope {
    fn binding(&self) -> &ChatGptJobBinding {
        &self.binding
    }
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>> {
        Ok(self.retired.subscribe())
    }
    fn with_current(&self, operation: &mut dyn FnMut() -> Result<()>) -> Result<()> {
        self.fence.before_lock();
        let gate = self.gate.lock().unwrap();
        ensure!(gate.active, "Source retired");
        self.principal.ensure_current()?;
        self.authority.capture()?;
        operation()
    }
    fn tools(&self) -> &dyn ResponsesTools {
        self
    }
    fn safe_output(&self, text: &str, complete: bool) -> Result<String> {
        let mut safe = text.replace(SECRET, "[redacted]");
        if !complete {
            for len in (1..SECRET.len()).rev() {
                if safe.ends_with(&SECRET[..len]) {
                    safe.truncate(safe.len() - len);
                    break;
                }
            }
        }
        Ok(safe)
    }
}
impl ResponsesTools for Scope {
    fn specs(&self) -> Value {
        crate::ai::tools::specs_for(&json!({"headSha":self.binding.head}))
    }
    fn call<'a>(&'a self, name: &'a str, arguments: &'a Value) -> AiFuture<'a, Value> {
        Box::pin(async move {
            ensure!(
                name == "read_file_diff" && arguments["paths"] == json!(["src/lib.rs"]),
                "Unexpected scoped tool"
            );
            self.tool_entered.notify_one();
            if self.hold_tool.load(Ordering::SeqCst) {
                self.tool_release.notified().await;
            }
            let gate = self.gate.lock().unwrap();
            ensure!(gate.active && gate.tools, "Tool permission retired");
            self.authority.capture()?;
            self.effects.fetch_add(1, Ordering::SeqCst);
            Ok(json!({"headSha":self.binding.head,"text":"captured source diff"}))
        })
    }
}

struct Root {
    path: std::path::PathBuf,
    store: Store,
}
impl Root {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("pully-chatgpt-jobs-{}", uuid::Uuid::new_v4()));
        let store = Store::new(path.clone()).unwrap();
        Self { path, store }
    }
    fn runtime(&self, jobs: Jobs, limits: ChatGptJobLimits) -> Arc<ChatGptJobs> {
        ChatGptJobs::new(
            jobs,
            self.store.clone(),
            ResponsesClient::new(super::super::chatgpt_responses::SIWC_RESPONSES_ENDPOINT).unwrap(),
            limits,
        )
        .unwrap()
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}
fn limits() -> ChatGptJobLimits {
    ChatGptJobLimits {
        window_ms: 60_000,
        global_starts: 20,
        owner_starts: 10,
        owner_inflight: 3,
        owner_buckets: 16,
    }
}
fn request(text: &str) -> ResponsesRequest {
    ResponsesRequest {
        model: "admitted-siwc-model".into(),
        input: vec![json!({"role":"user","content":text})],
        reasoning_effort: Some("high".into()),
        answer_schema: None,
        preparation: StructuredAnswerPreparation::Strict,
    }
}
fn event(value: Value) -> String {
    format!(
        "event: {}\ndata: {}\n\n",
        value["type"].as_str().unwrap(),
        value
    )
}
fn message(text: &str) -> Value {
    json!({"type":"message","id":"answer","role":"assistant","status":"completed","content":[{"type":"output_text","text":text,"annotations":[]}]})
}
fn completed(id: &str, output: Vec<Value>) -> String {
    let mut body = event(json!({"type":"response.created","response":{"id":id}}));
    for (i, item) in output.iter().enumerate() {
        body.push_str(&event(
            json!({"type":"response.output_item.done","output_index":i,"item":item}),
        ));
    }
    body + &event(
        json!({"type":"response.completed","response":{"id":id,"status":"completed","output":output,"usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}),
    )
}
fn answer(text: &str) -> Reply {
    Reply::Stream(completed("response", vec![message(text)]))
}
async fn bind(runtime: &Arc<ChatGptJobs>, scope: &Arc<Scope>) -> ChatGptJobSession {
    runtime.bind(scope.lease(), scope.clone()).await.unwrap()
}
async fn terminal(session: &ChatGptJobSession, id: &str) -> Value {
    crate::rt::timeout(Duration::from_secs(5), async {
        let mut value = session.poll(id).await.unwrap();
        while value["status"] == "running" {
            value = session
                .wait(id, value["rev"].as_u64().unwrap())
                .await
                .unwrap();
        }
        value
    })
    .await
    .unwrap()
}
async fn worker_finished(session: &ChatGptJobSession) {
    let run = session.0.live.lock().unwrap().clone().unwrap();
    crate::rt::timeout(Duration::from_secs(5), wait_finished(&run))
        .await
        .unwrap();
}

#[tokio::test]
async fn real_sse_tool_result_and_reopened_history_retain_exact_continuation_without_replay() {
    let reasoning = json!({"type":"reasoning","id":"r","summary":[],"encrypted_content":"opaque-exact-content"});
    let tool = json!({"type":"function_call","id":"tool-item","call_id":"call-1","name":"read_file_diff","namespace":"pully","arguments":"{\"paths\":[\"src/lib.rs\"]}","status":"completed"});
    let provider = Provider::new(vec![
        Reply::Stream(completed("first", vec![reasoning.clone(), tool])),
        answer("Reviewed"),
        answer("Follow-up"),
    ])
    .await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "anonymous-allowance-fixture", "thread");
    let session = bind(&runtime, &scope).await;
    let started = session
        .start("request-1".into(), request("Review"))
        .await
        .unwrap();
    let id = started["id"].as_str().unwrap();
    assert_eq!(terminal(&session, id).await["status"], "done");
    assert_eq!(session.result(id).await.unwrap(), "Reviewed");
    assert_eq!(scope.effects.load(Ordering::SeqCst), 1);
    let wire = provider.requests();
    assert_eq!(wire.len(), 2);
    assert_eq!(wire[1]["input"][1], reasoning);
    assert_eq!(wire[1]["input"][3]["type"], "function_call_output");
    assert_eq!(wire[1]["input"][3]["call_id"], "call-1");
    worker_finished(&session).await;
    drop(session);
    drop(runtime);
    let reopened = root.runtime(Jobs::default(), limits());
    let session = bind(&reopened, &scope).await;
    let repeated = session
        .start("request-1".into(), request("Review"))
        .await
        .unwrap();
    assert_eq!(repeated["id"], id);
    assert_eq!(repeated["status"], "done");
    assert_eq!(provider.requests().len(), 2);
    assert_eq!(session.history().await.unwrap().len(), 1);
    let next = session
        .start("request-2".into(), request("Why?"))
        .await
        .unwrap();
    assert_eq!(
        terminal(&session, next["id"].as_str().unwrap()).await["result"],
        "Follow-up"
    );
    assert_eq!(provider.requests()[2]["input"][1], reasoning);
    assert_eq!(
        provider.requests()[2]["input"]
            .as_array()
            .unwrap()
            .last()
            .unwrap()["content"],
        "Why?"
    );
    assert!(!session.history().await.unwrap()[0]
        .to_string()
        .contains("opaque-exact-content"));
}

#[tokio::test]
async fn identical_running_work_joins_once_and_its_second_receipt_remains_idempotent() {
    let provider = Provider::new(vec![Reply::Held(completed("one", vec![message("Done")]))]).await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner-a", "thread");
    let session = bind(&runtime, &scope).await;
    let one = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    provider.entered(1).await;
    let joined = session
        .start("second".into(), request("Review"))
        .await
        .unwrap();
    assert_eq!(one["id"], joined["id"]);
    provider.release(1);
    terminal(&session, one["id"].as_str().unwrap()).await;
    let again = session
        .start("second".into(), request("Review"))
        .await
        .unwrap();
    assert_eq!(again["id"], one["id"]);
    assert_eq!(provider.requests().len(), 1);
    let quota = root.store.get_async(QUOTA_KEY).await.unwrap().unwrap();
    assert_eq!(quota["total"], 1);
    assert!(session
        .start("second".into(), request("Different words"))
        .await
        .is_err());
}

#[tokio::test]
async fn global_and_per_owner_start_quota_are_real_and_survive_runtime_reopen() {
    let provider = Provider::new(vec![answer("A"), answer("B")]).await;
    let root = Root::new();
    let policy = ChatGptJobLimits {
        global_starts: 2,
        owner_starts: 1,
        ..limits()
    };
    let runtime = root.runtime(Jobs::default(), policy);
    for owner in ["owner-a", "owner-b"] {
        let scope = Scope::new(&provider, owner, "thread");
        let session = bind(&runtime, &scope).await;
        let started = session
            .start("first".into(), request("Review"))
            .await
            .unwrap();
        terminal(&session, started["id"].as_str().unwrap()).await;
        worker_finished(&session).await;
        assert!(session
            .start("second".into(), request("More"))
            .await
            .is_err());
    }
    assert_eq!(provider.requests().len(), 2);
    drop(runtime);
    let reopened = root.runtime(Jobs::default(), policy);
    let scope = Scope::new(&provider, "owner-c", "thread");
    let session = bind(&reopened, &scope).await;
    assert!(session
        .start("third".into(), request("Review"))
        .await
        .is_err());
    assert_eq!(provider.requests().len(), 2);
    assert_eq!(
        root.store.get_async(QUOTA_KEY).await.unwrap().unwrap()["total"],
        2
    );
    assert!(ChatGptJobs::new(
        Jobs::default(),
        root.store.clone(),
        ResponsesClient::new(super::super::chatgpt_responses::SIWC_RESPONSES_ENDPOINT).unwrap(),
        ChatGptJobLimits {
            global_starts: 0,
            ..limits()
        }
    )
    .is_err());
}

#[tokio::test]
async fn queued_fourth_job_stops_without_network_and_releases_actual_three_provider_slots() {
    let provider = Provider::new(
        (0..3)
            .map(|_| Reply::Held(completed("held", vec![message("Done")])))
            .collect(),
    )
    .await;
    let root = Root::new();
    let jobs = Jobs::default();
    let runtime = root.runtime(jobs.clone(), limits());
    let mut sessions = vec![];
    for n in 0..4 {
        let scope = Scope::new(&provider, &format!("owner-{n}"), "thread");
        let session = bind(&runtime, &scope).await;
        let task = session
            .start("first".into(), request("Review"))
            .await
            .unwrap();
        sessions.push((scope, session, task));
    }
    provider.entered(3).await;
    assert_eq!(jobs.provider_slots.available_permits(), 0);
    assert_eq!(provider.requests().len(), 3);
    let fourth = &sessions[3];
    let stopped = fourth
        .1
        .stop(fourth.2["id"].as_str().unwrap())
        .await
        .unwrap();
    assert_eq!(stopped["status"], "cancelled");
    worker_finished(&fourth.1).await;
    assert_eq!(provider.requests().len(), 3);
    provider.release(3);
    for (_, session, task) in sessions.iter().take(3) {
        assert_eq!(
            terminal(session, task["id"].as_str().unwrap()).await["status"],
            "done"
        );
        worker_finished(session).await;
    }
    assert_eq!(jobs.provider_slots.available_permits(), 3);
    assert!(runtime.allowance.active.lock().unwrap().is_empty());
}

#[tokio::test]
async fn per_owner_inflight_admission_is_released_after_explicit_stop() {
    let provider = Provider::new(vec![
        Reply::Held(completed("held", vec![message("Old")])),
        answer("Fresh"),
    ])
    .await;
    let root = Root::new();
    let runtime = root.runtime(
        Jobs::default(),
        ChatGptJobLimits {
            owner_inflight: 1,
            ..limits()
        },
    );
    let one = Scope::new(&provider, "owner", "thread-one");
    let first = bind(&runtime, &one).await;
    let two = Scope::new(&provider, "owner", "thread-two");
    let second = bind(&runtime, &two).await;
    let task = first
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    provider.entered(1).await;
    assert!(second
        .start("second".into(), request("More"))
        .await
        .is_err());
    assert_eq!(
        first.stop(task["id"].as_str().unwrap()).await.unwrap()["status"],
        "cancelled"
    );
    worker_finished(&first).await;
    let fresh = second
        .start("second".into(), request("More"))
        .await
        .unwrap();
    assert_eq!(
        terminal(&second, fresh["id"].as_str().unwrap()).await["result"],
        "Fresh"
    );
    assert_eq!(provider.requests().len(), 2);
}

#[tokio::test]
async fn held_network_loses_real_connection_or_principal_without_committing_a_result() {
    for principal in [false, true] {
        let provider = Provider::new(vec![Reply::Held(completed(
            "held",
            vec![message("Must not publish")],
        ))])
        .await;
        let root = Root::new();
        let jobs = Jobs::default();
        let runtime = root.runtime(jobs.clone(), limits());
        let scope = Scope::new(&provider, "old-owner", "thread");
        let session = bind(&runtime, &scope).await;
        let task = session
            .start("first".into(), request("Review"))
            .await
            .unwrap();
        provider.entered(1).await;
        scope.retire(principal);
        worker_finished(&session).await;
        provider.release(1);
        assert!(session.poll(task["id"].as_str().unwrap()).await.is_err());
        assert!(session.history().await.is_err());
        let stored = root
            .store
            .get_async(&session.0.partition)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(stored["history"], json!([]));
        assert!(stored["tasks"][0]["result"].is_null());
        assert_eq!(jobs.provider_slots.available_permits(), 3);
        let next = Scope::new(&provider, "replacement-owner", "thread");
        let replacement = bind(&runtime, &next).await;
        assert!(replacement.history().await.unwrap().is_empty());
        assert!(replacement
            .poll(task["id"].as_str().unwrap())
            .await
            .is_err());
    }
}

#[tokio::test]
async fn retiring_between_completed_sse_and_actual_store_commit_denies_the_late_answer() {
    let provider = Provider::new(vec![Reply::Held(completed(
        "held",
        vec![message("Late result")],
    ))])
    .await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    provider.entered(1).await;
    let before = root.store.get_async(&session.0.partition).await.unwrap();
    scope.fence.arm();
    provider.release(1);
    scope.fence.entered().await;
    scope.retire(false);
    scope.fence.release();
    worker_finished(&session).await;
    assert_eq!(
        root.store.get_async(&session.0.partition).await.unwrap(),
        before
    );
    assert_eq!(provider.requests().len(), 1);
}

#[tokio::test]
async fn revoked_tool_permission_during_held_call_cannot_commit_or_trigger_another_provider_turn() {
    let tool = json!({"type":"function_call","id":"tool","call_id":"call","name":"read_file_diff","namespace":"pully","arguments":"{\"paths\":[\"src/lib.rs\"]}","status":"completed"});
    let provider = Provider::new(vec![Reply::Stream(completed("tool", vec![tool]))]).await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    scope.hold_tool.store(true, Ordering::SeqCst);
    let session = bind(&runtime, &scope).await;
    let task = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    crate::rt::timeout(Duration::from_secs(5), scope.tool_entered.notified())
        .await
        .unwrap();
    scope.gate.lock().unwrap().tools = false;
    scope.tool_release.notify_one();
    assert_eq!(
        terminal(&session, task["id"].as_str().unwrap()).await["status"],
        "failed"
    );
    assert_eq!(scope.effects.load(Ordering::SeqCst), 0);
    assert_eq!(provider.requests().len(), 1);
    assert!(session.result(task["id"].as_str().unwrap()).await.is_err());
}

#[tokio::test]
async fn accepted_admission_survives_lost_http_observer_while_real_store_lock_is_held() {
    let provider = Provider::new(vec![answer("Still completed")]).await;
    let root = Root::new();
    let jobs = Jobs::default();
    let runtime = root.runtime(jobs.clone(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let fence = Arc::new(HeldFence::default());
    fence.arm();
    let holding = fence.clone();
    let store = root.store.clone();
    let lock = crate::rt::spawn_blocking(move || {
        store.update("fixture:store-lock", |_| {
            holding.before_lock();
            Ok(json!(true))
        })
    });
    fence.entered().await;
    let caller_session = session.clone();
    let caller = crate::rt::spawn(async move {
        caller_session
            .start("lost-observer".into(), request("Review"))
            .await
    });
    crate::rt::timeout(Duration::from_secs(5), async {
        loop {
            if !jobs.handles.lock().unwrap().is_empty() {
                break;
            }
            crate::rt::yield_now().await;
        }
    })
    .await
    .unwrap();
    caller.abort();
    assert!(caller.await.unwrap_err().is_cancelled());
    fence.release();
    lock.await.unwrap().unwrap();
    provider.entered(1).await;
    let history = session.history().await.unwrap();
    assert_eq!(history.len(), 1);
    let id = history[0]["id"].as_str().unwrap();
    assert_eq!(terminal(&session, id).await["result"], "Still completed");
    assert_eq!(provider.requests().len(), 1);
}

#[tokio::test]
async fn failed_task_and_stop_receipts_survive_observer_reopens_without_automatic_paid_retry() {
    let provider = Provider::new(vec![Reply::Reject, answer("Retried")]).await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let first = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    let id = first["id"].as_str().unwrap();
    let failed = terminal(&session, id).await;
    assert_eq!(failed["status"], "failed");
    assert!(failed["error"]
        .as_str()
        .unwrap()
        .contains("could not complete"));
    worker_finished(&session).await;
    assert_eq!(session.stop(id).await.unwrap()["status"], "failed");
    drop(session);
    drop(runtime);
    let reopened = root.runtime(Jobs::default(), limits());
    let session = bind(&reopened, &scope).await;
    assert_eq!(
        session
            .start("first".into(), request("Review"))
            .await
            .unwrap()["status"],
        "failed"
    );
    assert_eq!(provider.requests().len(), 1);
    let retry = session
        .start("explicit-retry".into(), request("Review"))
        .await
        .unwrap();
    assert_eq!(
        terminal(&session, retry["id"].as_str().unwrap()).await["result"],
        "Retried"
    );
}

#[tokio::test]
async fn credential_redaction_preserves_opaque_reasoning_and_never_stores_plaintext_bearer() {
    let text = format!("Answer {SECRET}");
    let opaque = json!({"type":"reasoning","id":"opaque","summary":[],"encrypted_content":format!("ciphertext-{SECRET}")});
    let ending = completed("safe", vec![opaque.clone(), message(&text)]);
    let ending = &ending[ending.find("\n\n").unwrap() + 2..];
    let prefix = event(json!({"type":"response.created","response":{"id":"safe"}}))
        + &event(json!({"type":"response.output_text.delta","delta":"Answer sample-owned-"}));
    let suffix = event(json!({"type":"response.output_text.delta","delta":"siwc-bearer"})) + ending;
    let provider = Provider::new(vec![Reply::Split { prefix, suffix }]).await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let started = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    let id = started["id"].as_str().unwrap();
    let partial = session.wait(id, 0).await.unwrap();
    assert_eq!(partial["activities"][0]["text"], "Answer ");
    assert!(!partial.to_string().contains("sample-owned-"));
    provider.release(1);
    let done = terminal(&session, id).await;
    assert_eq!(done["result"], "Answer [redacted]");
    assert!(!done.to_string().contains(SECRET));
    let stored = root
        .store
        .get_async(&session.0.partition)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(stored["history"][1], opaque);
    assert!(!stored["tasks"].to_string().contains(SECRET));
    assert!(!stored["history"][2].to_string().contains(SECRET));
    assert_eq!(
        scope.safe_output("Answer sample-owned-", false).unwrap(),
        "Answer "
    );
}

#[tokio::test]
async fn unadmitted_model_and_owner_connection_mismatch_are_rejected_before_network_or_quota() {
    let provider = Provider::new(vec![]).await;
    let root = Root::new();
    let runtime = root.runtime(Jobs::default(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let mut other = request("Review");
    other.model = "request-chosen-unadmitted-model".into();
    assert!(session.start("first".into(), other).await.is_err());
    let outsider = Scope::new(&provider, "other-owner", "thread");
    assert!(runtime.bind(outsider.lease(), scope).await.is_err());
    assert!(provider.requests().is_empty());
    assert!(root.store.get_async(QUOTA_KEY).await.unwrap().is_none());
}

#[tokio::test]
async fn server_restart_reconciles_persisted_running_receipt_without_replaying_the_request() {
    let provider = Provider::new(vec![Reply::Held(completed(
        "old",
        vec![message("Interrupted")],
    ))])
    .await;
    let root = Root::new();
    let jobs = Jobs::default();
    let runtime = root.runtime(jobs.clone(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let started = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    provider.entered(1).await;
    let handles = std::mem::take(&mut *jobs.handles.lock().unwrap());
    for handle in handles {
        handle.abort();
        let _ = handle.await;
    }
    drop(session);
    drop(runtime);
    let reopened = root.runtime(Jobs::default(), limits());
    let session = bind(&reopened, &scope).await;
    let recovered = session.poll(started["id"].as_str().unwrap()).await.unwrap();
    assert_eq!(recovered["status"], "failed");
    assert!(recovered["error"].as_str().unwrap().contains("interrupted"));
    assert_eq!(
        session
            .start("first".into(), request("Review"))
            .await
            .unwrap()["id"],
        started["id"]
    );
    assert_eq!(provider.requests().len(), 1);
    assert_eq!(
        root.store.get_async(QUOTA_KEY).await.unwrap().unwrap()["total"],
        1
    );
}

#[tokio::test]
async fn explicit_runtime_shutdown_settles_owned_work_and_stopped_receipt_survives_reopen() {
    let provider = Provider::new(vec![Reply::Held(completed(
        "old",
        vec![message("Stopped")],
    ))])
    .await;
    let root = Root::new();
    let jobs = Jobs::default();
    let runtime = root.runtime(jobs.clone(), limits());
    let scope = Scope::new(&provider, "owner", "thread");
    let session = bind(&runtime, &scope).await;
    let started = session
        .start("first".into(), request("Review"))
        .await
        .unwrap();
    provider.entered(1).await;
    runtime.shutdown().await;
    assert_eq!(
        session.poll(started["id"].as_str().unwrap()).await.unwrap()["status"],
        "cancelled"
    );
    assert_eq!(jobs.provider_slots.available_permits(), 3);
    assert!(session
        .start("new-after-stop".into(), request("More"))
        .await
        .is_err());
    drop(session);
    drop(runtime);
    let reopened = root.runtime(Jobs::default(), limits());
    let session = bind(&reopened, &scope).await;
    assert_eq!(
        session
            .start("first".into(), request("Review"))
            .await
            .unwrap()["status"],
        "cancelled"
    );
    assert_eq!(provider.requests().len(), 1);
}

#[tokio::test]
async fn handlers_for_same_store_share_runtime_and_cannot_reset_inflight_limits_with_new_jobs() {
    let provider = Provider::new(vec![Reply::Held(completed("held", vec![message("Done")]))]).await;
    let root = Root::new();
    let jobs = Jobs::default();
    let runtime = root.runtime(jobs.clone(), limits());
    let same = root.runtime(jobs, limits());
    assert!(Arc::ptr_eq(&runtime, &same));
    assert!(ChatGptJobs::new(
        Jobs::default(),
        root.store.clone(),
        ResponsesClient::new(super::super::chatgpt_responses::SIWC_RESPONSES_ENDPOINT).unwrap(),
        limits()
    )
    .is_err());
    let scope = Scope::new(&provider, "owner", "thread");
    let one = bind(&runtime, &scope).await;
    let two = bind(&same, &scope).await;
    let first = one.start("first".into(), request("Review")).await.unwrap();
    provider.entered(1).await;
    assert_eq!(
        two.start("second".into(), request("Review")).await.unwrap()["id"],
        first["id"]
    );
    provider.release(1);
    assert_eq!(
        terminal(&one, first["id"].as_str().unwrap()).await["result"],
        "Done"
    );
    assert_eq!(provider.requests().len(), 1);
}
