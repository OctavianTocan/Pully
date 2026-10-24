use super::*;
use crate::ai::authority::{
    AiConnectionAuthority, AiLease, AiPrincipalSource, AiRequestAuthorizer,
};
use axum::{
    body::Body,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::Response,
    routing::post,
    Router,
};
use futures_util::{stream, StreamExt};
use std::{
    collections::VecDeque,
    convert::Infallible,
    sync::{
        atomic::{AtomicBool, AtomicUsize, Ordering},
        Arc, Mutex,
    },
};
use tokio::sync::Notify;

struct Lease {
    source: Arc<AiPrincipalSource>,
    authority: AiConnectionAuthority,
    captured: AiLease,
    authorizer: Arc<Authorizer>,
}

struct Authorizer {
    endpoint: String,
    authorizations: AtomicUsize,
}

impl Lease {
    fn new(endpoint: &str) -> Arc<Self> {
        Self::for_kind(endpoint, AiProviderKind::ChatGPTSubscription)
    }
    fn for_kind(endpoint: &str, kind: AiProviderKind) -> Arc<Self> {
        let source = AiPrincipalSource::verified("independently-verified-ai-owner".into()).unwrap();
        let authorizer = Arc::new(Authorizer {
            endpoint: endpoint.into(),
            authorizations: AtomicUsize::new(0),
        });
        let authority = AiConnectionAuthority::new(
            source.clone(),
            AiConnectionRef {
                connection_id: "sample-chatgpt-connection".into(),
                revision: 7,
                provider_kind: kind,
            },
            authorizer.clone(),
        )
        .unwrap();
        let captured = authority.capture().unwrap();
        Arc::new(Self {
            source,
            authority,
            captured,
            authorizer,
        })
    }
    fn revoke(&self) {
        self.authority.retire();
    }
}

impl AiRequestAuthorizer for Authorizer {
    fn authorize_request(
        &self,
        builder: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder> {
        Box::pin(async move {
            let (client, request) = builder.build_split();
            let mut request = request?;
            if request.method() != reqwest::Method::POST || request.url().as_str() != self.endpoint
            {
                bail!("Wrong credential target");
            }
            request.headers_mut().insert(
                reqwest::header::AUTHORIZATION,
                "Bearer sample-siwc-token".parse().unwrap(),
            );
            self.authorizations.fetch_add(1, Ordering::SeqCst);
            Ok(reqwest::RequestBuilder::from_parts(client, request))
        })
    }
}

enum Reply {
    Stream(String),
    Reject(u16, String),
    Redirect(String),
    Held { prefix: String, suffix: String },
}

struct ProviderState {
    replies: Mutex<VecDeque<Reply>>,
    requests: Mutex<Vec<Value>>,
    headers_valid: AtomicBool,
    entered: Notify,
    release: Notify,
}

struct Provider {
    endpoint: String,
    state: Arc<ProviderState>,
    server: crate::rt::JoinHandle<()>,
}

impl Provider {
    async fn new(replies: Vec<Reply>) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/responses", listener.local_addr().unwrap());
        let state = Arc::new(ProviderState {
            replies: Mutex::new(replies.into()),
            requests: Mutex::new(vec![]),
            headers_valid: AtomicBool::new(true),
            entered: Notify::new(),
            release: Notify::new(),
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
    fn client(&self) -> ResponsesClient {
        // The actual production run uses the same transport builder; only the fixture's
        // endpoint is supplied directly in this private test module, never by a runtime flag.
        ResponsesClient {
            client: ResponsesClient::transport_client().unwrap(),
            endpoint: url::Url::parse(&self.endpoint).unwrap(),
        }
    }
    async fn entered(&self) {
        crate::rt::timeout(Duration::from_secs(2), self.state.entered.notified())
            .await
            .unwrap();
    }
}

impl Drop for Provider {
    fn drop(&mut self) {
        self.server.abort();
    }
}

async fn provider(
    State(state): State<Arc<ProviderState>>,
    headers: HeaderMap,
    bytes: axum::body::Bytes,
) -> Response {
    let valid = headers
        .get("authorization")
        .is_some_and(|v| v == "Bearer sample-siwc-token")
        && headers
            .get("accept")
            .is_some_and(|v| v == "text/event-stream");
    if !valid {
        state.headers_valid.store(false, Ordering::SeqCst);
    }
    state
        .requests
        .lock()
        .unwrap()
        .push(serde_json::from_slice(&bytes).unwrap());
    let reply = state
        .replies
        .lock()
        .unwrap()
        .pop_front()
        .unwrap_or(Reply::Reject(500, "Unexpected fixture request".into()));
    let (status, body, location) = match reply {
        Reply::Stream(body) => (200, Body::from(body), None),
        Reply::Reject(status, body) => (status, Body::from(body), None),
        Reply::Redirect(location) => (307, Body::empty(), Some(location)),
        Reply::Held { prefix, suffix } => {
            let first = stream::once(async move { Ok::<_, Infallible>(prefix) });
            let second = stream::once(async move {
                state.entered.notify_one();
                state.release.notified().await;
                Ok::<_, Infallible>(suffix)
            });
            (200, Body::from_stream(first.chain(second)), None)
        }
    };
    let mut response = Response::builder()
        .status(StatusCode::from_u16(status).unwrap())
        .header("content-type", "text/event-stream");
    if let Some(location) = location {
        response = response.header("location", location);
    }
    response.body(body).unwrap()
}

#[derive(Default)]
struct Tools {
    calls: Mutex<Vec<(String, Value)>>,
    revoke: Option<Arc<Lease>>,
    held: Option<Arc<Notify>>,
    entered: Notify,
}

impl ResponsesTools for Tools {
    fn specs(&self) -> Value {
        crate::ai::tools::specs_for(&json!({"headSha":"sample-captured-head"}))
    }
    fn call<'a>(&'a self, name: &'a str, arguments: &'a Value) -> AiFuture<'a, Value> {
        Box::pin(async move {
            self.calls
                .lock()
                .unwrap()
                .push((name.to_owned(), arguments.clone()));
            self.entered.notify_one();
            if let Some(held) = &self.held {
                held.notified().await;
            }
            if let Some(lease) = &self.revoke {
                lease.revoke();
            }
            Ok(json!({"text":"captured PR tool result"}))
        })
    }
}

fn request() -> ResponsesRequest {
    ResponsesRequest {
        model: "server-selected-model".into(),
        input: vec![json!({"role":"user","content":"Review this captured PR"})],
        reasoning_effort: Some("high".into()),
        answer_schema: None,
        preparation: super::super::StructuredAnswerPreparation::Strict,
    }
}

fn event(value: Value) -> String {
    format!(
        "event: {}\r\ndata: {}\r\n\r\n",
        value["type"].as_str().unwrap(),
        value
    )
}
fn message(text: &str) -> Value {
    json!({"type":"message","id":"sample-message","role":"assistant","status":"completed","content":[{"type":"output_text","text":text,"annotations":[]}]})
}
fn function(id: &str, name: &str) -> Value {
    json!({"type":"function_call","id":format!("item-{id}"),"call_id":id,"name":name,"namespace":"pully","arguments":"{\"paths\":[\"src/lib.rs\"]}","status":"completed"})
}
fn completed(id: &str, items: Vec<Value>) -> String {
    let mut out = event(json!({"type":"response.created","response":{"id":id}}));
    for (index, item) in items.iter().enumerate() {
        out.push_str(&event(
            json!({"type":"response.output_item.done","output_index":index,"item":item}),
        ));
    }
    out.push_str(&event(json!({"type":"response.completed","response":{"id":id,"status":"completed","output":items,
        "usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":2}}}})));
    out
}

#[tokio::test]
async fn actual_http_loop_preserves_stateless_history_reasoning_tools_and_usage() {
    let reasoning = json!({"type":"reasoning","id":"reasoning-1","summary":[],"encrypted_content":"opaque-fixture-reasoning"});
    let tool = function("call-1", "read_file_diff");
    let provider = Provider::new(vec![
        Reply::Stream(completed("r1", vec![reasoning.clone(), tool.clone()])),
        Reply::Stream(completed("r2", vec![message("The PR is ready.")])),
    ])
    .await;
    let lease = Lease::new(&provider.endpoint);
    let tools = Tools::default();
    let (_stop, cancellation) = watch::channel(false);
    let answer = provider
        .client()
        .run(
            &lease.captured,
            cancellation,
            request(),
            &tools,
            &|_| Ok(()),
        )
        .await
        .unwrap();
    assert_eq!(answer.text, "The PR is ready.");
    assert_eq!(answer.history[1], reasoning);
    assert_eq!(answer.history[2], tool);
    assert_eq!(answer.history[3]["type"], "function_call_output");
    assert_eq!(answer.history[3]["call_id"], "call-1");
    assert_eq!(
        answer.usage.unwrap(),
        ResponsesUsage {
            input_tokens: 20,
            output_tokens: 8,
            total_tokens: 28,
            input_tokens_details: InputTokenDetails { cached_tokens: 6 },
            output_tokens_details: OutputTokenDetails {
                reasoning_tokens: 4
            }
        }
    );
    let requests = provider.state.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    assert!(provider.state.headers_valid.load(Ordering::SeqCst));
    assert_eq!(lease.authorizer.authorizations.load(Ordering::SeqCst), 2);
    for sent in requests.iter() {
        assert_eq!(sent["store"], false);
        assert_eq!(sent["stream"], true);
        assert_eq!(sent["model"], "server-selected-model");
        assert_eq!(sent["reasoning"]["effort"], "high");
        assert_eq!(sent["include"], json!(["reasoning.encrypted_content"]));
        assert!(sent.get("previous_response_id").is_none());
        assert_eq!(sent["tools"][0]["type"], "namespace");
        assert_eq!(sent["tools"][0]["name"], "pully");
        assert!(sent["tools"][0]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .all(|tool| tool["type"] == "function"));
        assert!(!sent.to_string().contains("sample-siwc-token"));
    }
    assert_eq!(requests[1]["input"], json!(answer.history[..4]));
    assert_eq!(
        tools.calls.lock().unwrap().as_slice(),
        &[("read_file_diff".into(), json!({"paths":["src/lib.rs"]}))]
    );
}

#[tokio::test]
async fn structured_answers_use_the_existing_validator_and_keep_a_complete_tool_receipt() {
    for valid in [true, false] {
        let mut call = function("submit-1", "submit_answer");
        call["arguments"] = json!(if valid {
            "{\"summary\":\"Ready\"}"
        } else {
            "{\"summary\":4}"
        });
        let provider = Provider::new(vec![Reply::Stream(completed("r1", vec![call]))]).await;
        let lease = Lease::new(&provider.endpoint);
        let (_stop, cancellation) = watch::channel(false);
        let mut req = request();
        req.answer_schema = Some(
            json!({"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}),
        );
        let tools = Tools::default();
        let result = provider
            .client()
            .run(&lease.captured, cancellation, req, &tools, &|_| Ok(()))
            .await;
        if valid {
            let answer = result.unwrap();
            assert_eq!(
                serde_json::from_str::<Value>(&answer.text).unwrap(),
                json!({"summary":"Ready"})
            );
            assert_eq!(answer.history.last().unwrap()["call_id"], "submit-1");
            validate_history(&answer.history).unwrap();
        } else {
            assert!(result.is_err());
        }
        assert!(tools.calls.lock().unwrap().is_empty());
    }
}

#[tokio::test]
async fn failed_incomplete_refused_and_premature_streams_never_authorize_tools_or_answers() {
    for tail in [
        event(json!({"type":"error","error":{"message":"sample-siwc-token private detail"}})),
        event(
            json!({"type":"response.failed","response":{"error":{"message":"sample-siwc-token"}}}),
        ),
        event(
            json!({"type":"response.incomplete","response":{"id":"r1","incomplete_details":{"reason":"max_output_tokens"}}}),
        ),
        event(json!({"type":"response.refusal.delta","delta":"No"})),
        "data: [DONE]\n\n".into(),
        String::new(),
    ] {
        let prefix = event(
            json!({"type":"response.output_item.done","output_index":0,"item":function("call-1","read_file_diff")}),
        );
        let provider = Provider::new(vec![Reply::Stream(prefix + &tail)]).await;
        let lease = Lease::new(&provider.endpoint);
        let (_stop, cancellation) = watch::channel(false);
        let tools = Tools::default();
        let error = provider
            .client()
            .run(
                &lease.captured,
                cancellation,
                request(),
                &tools,
                &|_| Ok(()),
            )
            .await
            .err()
            .unwrap()
            .to_string();
        assert!(!error.contains("sample-siwc-token"));
        assert!(tools.calls.lock().unwrap().is_empty());
        assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
    }
}

#[tokio::test]
async fn unknown_duplicate_and_mixed_final_calls_fail_before_any_scoped_tool_runs() {
    let mut repeated = function("call-1", "read_source");
    repeated["arguments"] = json!("{\"path\":\"src/lib.rs\"}");
    for items in [
        vec![
            function("call-1", "read_file_diff"),
            function("call-2", "shell"),
        ],
        vec![function("call-1", "read_file_diff"), repeated],
        vec![
            function("call-1", "read_file_diff"),
            function("call-2", "submit_answer"),
        ],
        vec![json!({"type":"web_search_call","id":"unscoped"})],
    ] {
        let provider = Provider::new(vec![Reply::Stream(completed("r1", items))]).await;
        let lease = Lease::new(&provider.endpoint);
        let (_stop, cancellation) = watch::channel(false);
        let tools = Tools::default();
        let mut req = request();
        req.answer_schema = Some(json!({"type":"object"}));
        assert!(provider
            .client()
            .run(&lease.captured, cancellation, req, &tools, &|_| Ok(()))
            .await
            .is_err());
        assert!(tools.calls.lock().unwrap().is_empty());
    }
}

#[tokio::test]
async fn revoked_or_wrong_provider_authority_cannot_start_a_request() {
    let provider = Provider::new(vec![]).await;
    for kind in [
        AiProviderKind::ChatGPTSubscription,
        AiProviderKind::PiEndpoint,
    ] {
        let lease = Lease::for_kind(&provider.endpoint, kind);
        if kind == AiProviderKind::ChatGPTSubscription {
            lease.revoke();
        }
        let (_stop, cancellation) = watch::channel(false);
        let error = provider
            .client()
            .run(
                &lease.captured,
                cancellation,
                request(),
                &Tools::default(),
                &|_| Ok(()),
            )
            .await
            .err()
            .unwrap()
            .to_string();
        assert!(!error.contains("private lease detail"));
    }
    assert!(provider.state.requests.lock().unwrap().is_empty());
}

#[tokio::test]
async fn stop_and_lease_retirement_drop_a_held_stream_without_late_progress_or_replay() {
    for retirement in ["stop", "connection", "source"] {
        let suffix = completed("r1", vec![message("Late private answer")]);
        let provider = Provider::new(vec![Reply::Held {
            prefix: ": keepalive\n\n".into(),
            suffix,
        }])
        .await;
        let lease = Lease::new(&provider.endpoint);
        let client = provider.client();
        let tools = Arc::new(Tools::default());
        let progress = Arc::new(AtomicUsize::new(0));
        let (stop, cancellation) = watch::channel(false);
        let worker_lease = lease.clone();
        let worker_tools = tools.clone();
        let seen = progress.clone();
        let job = crate::rt::spawn(async move {
            client
                .run(
                    &worker_lease.captured,
                    cancellation,
                    request(),
                    worker_tools.as_ref(),
                    &move |_| {
                        seen.fetch_add(1, Ordering::SeqCst);
                        Ok(())
                    },
                )
                .await
        });
        provider.entered().await;
        match retirement {
            "stop" => {
                stop.send_replace(true);
            }
            "source" => lease.source.retire(),
            _ => lease.revoke(),
        }
        let result = crate::rt::timeout(Duration::from_secs(2), job)
            .await
            .unwrap()
            .unwrap();
        assert!(result.is_err());
        provider.state.release.notify_one();
        assert_eq!(progress.load(Ordering::SeqCst), 0);
        assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
        assert!(tools.calls.lock().unwrap().is_empty());
    }
}

#[tokio::test]
async fn a_revoked_tool_result_cannot_enter_history_or_start_another_request() {
    let provider = Provider::new(vec![Reply::Stream(completed(
        "r1",
        vec![function("call-1", "read_file_diff")],
    ))])
    .await;
    let lease = Lease::new(&provider.endpoint);
    let tools = Tools {
        revoke: Some(lease.clone()),
        ..Tools::default()
    };
    let (_stop, cancellation) = watch::channel(false);
    assert!(provider
        .client()
        .run(
            &lease.captured,
            cancellation,
            request(),
            &tools,
            &|_| Ok(())
        )
        .await
        .is_err());
    assert_eq!(tools.calls.lock().unwrap().len(), 1);
    assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn retirement_cancels_a_held_tool_instead_of_waiting_for_it_to_reply() {
    let provider = Provider::new(vec![Reply::Stream(completed(
        "r1",
        vec![function("call-1", "read_file_diff")],
    ))])
    .await;
    let lease = Lease::new(&provider.endpoint);
    let held = Arc::new(Notify::new());
    let tools = Arc::new(Tools {
        held: Some(held.clone()),
        ..Tools::default()
    });
    let client = provider.client();
    let worker_lease = lease.clone();
    let worker_tools = tools.clone();
    let (_stop, cancellation) = watch::channel(false);
    let job = crate::rt::spawn(async move {
        client
            .run(
                &worker_lease.captured,
                cancellation,
                request(),
                worker_tools.as_ref(),
                &|_| Ok(()),
            )
            .await
    });
    crate::rt::timeout(Duration::from_secs(2), tools.entered.notified())
        .await
        .unwrap();
    lease.revoke();
    assert!(crate::rt::timeout(Duration::from_secs(2), job)
        .await
        .unwrap()
        .unwrap()
        .is_err());
    held.notify_one();
    assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn redirect_and_provider_error_bodies_never_leak_or_replay_the_bearer() {
    let outside = Provider::new(vec![]).await;
    for reply in [
        Reply::Redirect(outside.endpoint.clone()),
        Reply::Reject(401, "sample-siwc-token".into()),
        Reply::Reject(429, "sample-siwc-token".into()),
        Reply::Reject(503, "sample-siwc-token".into()),
    ] {
        let provider = Provider::new(vec![reply]).await;
        let lease = Lease::new(&provider.endpoint);
        let (_stop, cancellation) = watch::channel(false);
        let error = provider
            .client()
            .run(
                &lease.captured,
                cancellation,
                request(),
                &Tools::default(),
                &|_| Ok(()),
            )
            .await
            .err()
            .unwrap()
            .to_string();
        assert!(!error.contains("sample-siwc-token"));
        assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
    }
    assert!(outside.state.requests.lock().unwrap().is_empty());
}

#[test]
fn byte_split_utf8_multiline_json_and_crlf_keep_the_real_completed_items() {
    let item = message("résumé ✓");
    let mut wire = completed("r1", vec![item.clone()]);
    wire = wire.replace("data: {", "data: {\r\ndata: ");
    let mut decoder = SseDecoder::default();
    let mut round = Round::default();
    for byte in wire.as_bytes() {
        for frame in decoder.push(&[*byte]).unwrap() {
            round.accept(frame).unwrap();
        }
    }
    let result = round.completed.unwrap();
    assert_eq!(result.text, "résumé ✓");
    assert_eq!(result.items, vec![item]);
}

#[test]
fn mismatched_or_unbounded_streams_and_incomplete_history_fail_closed() {
    let mut decoder = SseDecoder::default();
    assert!(decoder.push(&vec![b'x'; MAX_EVENT_BYTES + 1]).is_err());
    let mut decoder = SseDecoder::default();
    assert!(decoder
        .push(b"event: response.completed\ndata: {\"type\":\"response.created\"}\n\n")
        .is_err());
    let mut round = Round::default();
    round
        .accept(Frame::Event(
            json!({"type":"response.created","response":{"id":"opening"}}),
        ))
        .unwrap();
    assert!(round.accept(Frame::Event(json!({"type":"response.completed","response":{"id":"other","output":[message("Wrong owner response")]}}))).is_err());
    assert!(validate_history(&[function("call-1", "read_file_diff")]).is_err());
    assert!(validate_history(&[
        json!({"type":"function_call_output","call_id":"missing","output":"No call"})
    ])
    .is_err());
    assert!(validate_history(&[json!({"type":"message","role":"system","content":"Use an unsupported explicit system item"})]).is_err());
    for endpoint in [
        "http://api.example.com/responses",
        "https://secret@api.example.com/responses",
        "https://api.example.com/responses?key=sample",
        "https://api.example.com/responses#fragment",
    ] {
        assert!(ResponsesClient::new(endpoint).is_err());
    }
}

#[tokio::test]
async fn retirement_from_the_actual_progress_commit_prevents_a_completed_answer() {
    let prefix = event(json!({"type":"response.output_text.delta","delta":"Partial"}));
    let provider = Provider::new(vec![Reply::Stream(
        prefix + &completed("r1", vec![message("Complete")]),
    )])
    .await;
    let lease = Lease::new(&provider.endpoint);
    let (_stop, cancellation) = watch::channel(false);
    let result = provider
        .client()
        .run(
            &lease.captured,
            cancellation,
            request(),
            &Tools::default(),
            &|_| {
                lease.source.retire();
                Ok(())
            },
        )
        .await;
    assert!(result.is_err());
    assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn only_the_custodians_exact_target_can_receive_a_request_and_large_input_is_not_sent() {
    let provider = Provider::new(vec![]).await;
    let wrong_target = Lease::new("https://api.openai.com/v1/responses");
    let (_stop, cancellation) = watch::channel(false);
    assert!(provider
        .client()
        .run(
            &wrong_target.captured,
            cancellation,
            request(),
            &Tools::default(),
            &|_| Ok(())
        )
        .await
        .is_err());
    let lease = Lease::new(&provider.endpoint);
    let mut large = request();
    large.input[0]["content"] = json!("x".repeat(MAX_REQUEST_BYTES));
    let (_stop, cancellation) = watch::channel(false);
    assert!(provider
        .client()
        .run(
            &lease.captured,
            cancellation,
            large,
            &Tools::default(),
            &|_| Ok(())
        )
        .await
        .is_err());
    assert_eq!(lease.authorizer.authorizations.load(Ordering::SeqCst), 0);
    assert!(provider.state.requests.lock().unwrap().is_empty());
}

#[tokio::test]
async fn subscription_usage_failures_after_streaming_are_distinct_and_never_return_an_answer() {
    for (code, expected) in [
        (
            "subscription_sharing_usage_limit_exceeded",
            "allowance is exhausted",
        ),
        (
            "subscription_sharing_usage_unavailable",
            "inference is currently unavailable",
        ),
    ] {
        let wire = event(json!({"type":"response.output_text.delta","delta":"Partial"}))
            + &event(
                json!({"type":"response.failed","response":{"error":{"code":code,"message":"sample-siwc-token"}}}),
            );
        let provider = Provider::new(vec![Reply::Stream(wire)]).await;
        let lease = Lease::new(&provider.endpoint);
        let (_stop, cancellation) = watch::channel(false);
        let error = provider
            .client()
            .run(
                &lease.captured,
                cancellation,
                request(),
                &Tools::default(),
                &|_| Ok(()),
            )
            .await
            .err()
            .unwrap()
            .to_string();
        assert!(error.contains(expected));
        assert!(!error.contains("sample-siwc-token"));
        assert_eq!(provider.state.requests.lock().unwrap().len(), 1);
    }
}

#[tokio::test(start_paused = true)]
async fn the_whole_operation_deadline_cancels_a_pending_await_without_replaying_it() {
    let lease = Lease::new("https://api.openai.com/v1/responses");
    let (_stop, cancellation) = watch::channel(false);
    let mut guard = JobGuard::new(&lease.captured, cancellation).unwrap();
    let error = guard
        .wait(std::future::pending::<Result<()>>())
        .await
        .err()
        .unwrap()
        .to_string();
    assert!(error.contains("timed out"));
    assert_eq!(lease.authorizer.authorizations.load(Ordering::SeqCst), 0);
}
