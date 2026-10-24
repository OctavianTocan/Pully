//! A bounded Responses protocol adapter. Connection authority and approved endpoint selection
//! belong to the provider host; this module never discovers credentials or chooses a fallback.
//!
//! Wire reference: openai/codex at07316174c6f62deb20278c53add2dbd9dcf6da70,
//! codex-rs/codex-api/src/{common.rs,endpoint/responses.rs,sse/responses.rs}.
//! That source establishes protocol shape, not Pully's SIWC registration or approved base URL.
//! The primary SIWC models-and-inference documentation selects the public API Responses URL;
//! this adapter still needs an explicitly installed, current SIWC credential capability.
//! https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
//! https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

use crate::ai::authority::{AiConnectionRef, AiCredentialCapability, AiFuture, AiProviderKind};
use anyhow::{anyhow, bail, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashSet},
    future::Future,
    time::Duration,
};
use crate::rt::{timeout_at, Instant};
use tokio::sync::watch;

const MAX_EVENT_BYTES: usize = 2 * 1024 * 1024;
const MAX_STREAM_BYTES: usize = 16 * 1024 * 1024;
const MAX_HISTORY_BYTES: usize = 8 * 1024 * 1024;
const MAX_ITEMS: usize = 1024;
const MAX_TOOL_CALLS: usize = 128;
const MAX_REQUEST_BYTES: usize = 4 * 1024 * 1024;
const TOTAL_LIMIT: Duration = Duration::from_secs(30 * 60);
pub(crate) const SIWC_RESPONSES_ENDPOINT: &str = "https://api.openai.com/v1/responses";
const TOOL_NAMESPACE: &str = "pully";

/// The production implementation supplies the current job's canonical PR tool registry and
/// source-owned executor. Tool results must pass that executor's existing secret redaction.
pub(crate) trait ResponsesTools: Send + Sync {
    fn specs(&self) -> Value;
    fn call<'a>(&'a self, name: &'a str, arguments: &'a Value) -> AiFuture<'a, Value>;
}

pub(crate) struct ResponsesProgress {
    pub text_delta: String,
    pub summary_delta: String,
}

pub(crate) struct ResponsesRequest {
    pub model: String,
    /// The host converts its instructions to developer messages, not explicit system items.
    pub input: Vec<Value>,
    pub reasoning_effort: Option<String>,
    pub answer_schema: Option<Value>,
    pub preparation: super::StructuredAnswerPreparation,
}

/// This includes encrypted reasoning and tool receipts, not merely the displayed answer.
/// The captured source must own persistence; this type deliberately has no Debug implementation.
pub(crate) struct ResponsesAnswer {
    pub text: String,
    pub history: Vec<Value>,
    pub usage: Option<ResponsesUsage>,
}

/// @cc [owner:OctavianTocan,label:security;architecture] chatgpt-responses-requires-current-explicit-authority
/// Every request, tool execution, progress publication and answer MUST retain a current supplied
/// AI principal and ChatGPT connection/revision. The adapter MUST use only its trusted configured
/// target, MUST NOT discover environment/CLI credentials or select a fallback, and MUST cancel
/// in-flight work on Stop or lease retirement. The host MUST supply shared provider-slot admission
/// and recheck the same lease at its actual task/result storage commit. Source: Tavi's21/22Oct2026
/// per-user provider and GitHub-independent AI decisions and the approved provider adapter plan.
pub(crate) struct ResponsesClient {
    client: reqwest::Client,
    endpoint: url::Url,
}

impl ResponsesClient {
    /// Only the trusted provider factory selects this URL. Native jobs cannot supply it, and
    /// the credential custodian separately checks this exact method/target before adding auth.
    pub(crate) fn new(approved_endpoint: &str) -> Result<Self> {
        let endpoint = url::Url::parse(approved_endpoint)
            .map_err(|_| anyhow!("The ChatGPT deployment is not configured"))?;
        if endpoint.as_str() != SIWC_RESPONSES_ENDPOINT {
            bail!("The ChatGPT deployment is not configured");
        }
        Ok(Self {
            client: Self::transport_client()?,
            endpoint,
        })
    }

    fn transport_client() -> Result<reqwest::Client> {
        crate::net::client()
            .no_proxy()
            .connect_timeout(Duration::from_secs(10))
            .read_timeout(Duration::from_secs(90))
            .timeout(TOTAL_LIMIT)
            .build()
            .map_err(|_| anyhow!("The ChatGPT connection is unavailable"))
    }

    /// @cc [owner:OctavianTocan,label:architecture;security] chatgpt-responses-keeps-stateless-complete-history
    /// Requests MUST use streaming Responses with store:false and full bounded client-managed
    /// history, including opaque reasoning and function-call results. Only a genuine completed
    /// response MAY authorize tools or an answer; incomplete/error/unknown-tool output MUST fail
    /// closed. Tools MUST come from the supplied scoped PR registry, not browser, shell or host
    /// execution. Source: Tavi's22Oct2026 official ChatGPT subscription-adapter requirement and
    /// the pinned official protocol reference above, preserving agents-act-through-pr-tools.
    pub(crate) async fn run(
        &self,
        lease: &dyn AiCredentialCapability,
        cancellation: watch::Receiver<bool>,
        request: ResponsesRequest,
        tools: &dyn ResponsesTools,
        progress: &(dyn Fn(ResponsesProgress) -> Result<()> + Send + Sync),
    ) -> Result<ResponsesAnswer> {
        validate_request(&request)?;
        let mut guard = JobGuard::new(lease, cancellation)?;
        guard.check().await?;
        let (mut definitions, mut allowed) = tool_definitions(tools.specs())?;
        if let Some(schema) = &request.answer_schema {
            if !schema.is_object() || !allowed.insert("submit_answer".to_owned()) {
                return Err(invalid_stream());
            }
            definitions.push(json!({"type":"function","name":"submit_answer","description":"Return the completed structured answer.","parameters":schema,"strict":false}));
        }
        let mut history = request.input;
        let mut calls_seen = history
            .iter()
            .filter(|item| item["type"] == "function_call")
            .map(|item| identifier(&item["call_id"]))
            .collect::<Result<HashSet<_>>>()?;
        let mut usage = ResponsesUsage::default();
        let mut usage_complete = true;
        let mut calls_used = 0usize;
        for _ in 0..50 {
            let mut body = json!({"model":request.model,"input":history,
                "tools":[{"type":"namespace","name":TOOL_NAMESPACE,"description":"Tools for the captured Pully review only.","tools":definitions}],
                "tool_choice":"auto","parallel_tool_calls":false,"stream":true,"store":false,
                "include":["reasoning.encrypted_content"]});
            if let Some(effort) = &request.reasoning_effort {
                body["reasoning"] = json!({"effort":effort});
            }
            let bytes = serde_json::to_vec(&body).map_err(|_| invalid_stream())?;
            if bytes.len() > MAX_REQUEST_BYTES {
                bail!("ChatGPT conversation exceeded its size limit");
            }
            let builder = self
                .client
                .post(self.endpoint.clone())
                .header(reqwest::header::ACCEPT, "text/event-stream")
                .header(reqwest::header::CONTENT_TYPE, "application/json")
                .body(bytes);
            let authorized = guard
                .wait(async {
                    lease
                        .authorize_request(builder)
                        .await
                        .map_err(|_| anyhow!("The ChatGPT connection is no longer available"))
                })
                .await?;
            let mut response = guard
                .wait(async {
                    crate::net::send(authorized)
                        .await
                        .map_err(|_| anyhow!("The ChatGPT connection failed"))
                })
                .await?;
            match response.status().as_u16() {
                200 => {}
                401 | 403 => bail!("The ChatGPT connection needs authorization again"),
                429 => bail!("ChatGPT is busy or its allowance is exhausted; try again later"),
                _ => bail!("ChatGPT could not complete this request; try again later"),
            }
            if response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .is_none_or(|v| {
                    !v.split(';')
                        .next()
                        .is_some_and(|v| v.trim().eq_ignore_ascii_case("text/event-stream"))
                })
            {
                return Err(invalid_stream());
            }
            let mut decoder = SseDecoder::default();
            let mut round = Round::default();
            loop {
                let chunk = guard
                    .wait(async {
                        response
                            .chunk()
                            .await
                            .map_err(|_| anyhow!("The ChatGPT response was interrupted"))
                    })
                    .await?
                    .ok_or_else(invalid_stream)?;
                let mut update = ResponsesProgress {
                    text_delta: String::new(),
                    summary_delta: String::new(),
                };
                for frame in decoder.push(&chunk)? {
                    match round.accept(frame)? {
                        Some(Delta::Text(delta)) => update.text_delta.push_str(&delta),
                        Some(Delta::Summary(delta)) => update.summary_delta.push_str(&delta),
                        None => {}
                    }
                }
                if !update.text_delta.is_empty() || !update.summary_delta.is_empty() {
                    guard.check().await?;
                    progress(update)
                        .map_err(|_| anyhow!("The ChatGPT task could not publish its progress"))?;
                    guard.check().await?;
                }
                if round.completed.is_some() {
                    break;
                }
            }
            let round = round.completed.take().ok_or_else(invalid_stream)?;
            if let Some(round_usage) = round.usage {
                usage.add(&round_usage)?;
            } else {
                usage_complete = false;
            }
            // Validate every call before executing any: a later unknown/replayed call cannot
            // leave earlier tools partially applied and then induce an unsafe network retry.
            for call in &round.calls {
                if !allowed.contains(&call.name) || !calls_seen.insert(call.id.clone()) {
                    return Err(invalid_stream());
                }
            }
            calls_used += round.calls.len();
            if calls_used > MAX_TOOL_CALLS {
                bail!("ChatGPT exceeded the task tool limit");
            }
            let structured = round.calls.iter().find(|call| call.name == "submit_answer");
            if structured.is_some() && round.calls.len() != 1 {
                return Err(invalid_stream());
            }
            history.extend(round.items);
            ensure_history_budget(&history)?;
            if let Some(call) = structured {
                let value = super::prepare_structured_answer(
                    request.answer_schema.as_ref().ok_or_else(invalid_stream)?,
                    &call.arguments,
                    request.preparation,
                )
                .map_err(|_| anyhow!("ChatGPT returned an invalid structured answer"))?;
                history.push(json!({"type":"function_call_output","call_id":call.id,"name":call.name,"namespace":TOOL_NAMESPACE,"output":"Answer accepted."}));
                validate_history(&history)?;
                guard.check().await?;
                return Ok(ResponsesAnswer {
                    text: serde_json::to_string(&value).map_err(|_| invalid_stream())?,
                    history,
                    usage: usage_complete.then_some(usage),
                });
            }
            if round.calls.is_empty() {
                if request.answer_schema.is_some() || round.text.trim().is_empty() {
                    bail!("ChatGPT did not return the requested answer");
                }
                validate_history(&history)?;
                guard.check().await?;
                return Ok(ResponsesAnswer {
                    text: round.text,
                    history,
                    usage: usage_complete.then_some(usage),
                });
            }
            for call in round.calls {
                let result = guard
                    .wait(async {
                        tools.call(&call.name, &call.arguments).await.map_err(|_| {
                            anyhow!("A scoped PR tool could not complete this request")
                        })
                    })
                    .await?;
                let output = serde_json::to_string(&result).map_err(|_| invalid_stream())?;
                if output.len() > MAX_EVENT_BYTES {
                    bail!("The PR tool result exceeded its size limit");
                }
                history.push(json!({"type":"function_call_output","call_id":call.id,"name":call.name,"namespace":TOOL_NAMESPACE,"output":output}));
            }
            validate_history(&history)?;
        }
        bail!("ChatGPT exceeded the task turn limit")
    }
}

fn validate_request(request: &ResponsesRequest) -> Result<()> {
    if request.model.is_empty()
        || request.model.trim().is_empty()
        || request.model.len() > 256
        || request.model.chars().any(char::is_control)
    {
        bail!("Choose a valid ChatGPT model");
    }
    if request.reasoning_effort.as_deref().is_some_and(|effort| {
        !matches!(
            effort,
            "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
        )
    }) {
        bail!("Choose a supported ChatGPT reasoning effort");
    }
    validate_history(&request.input)
}

pub(super) fn validate_history(history: &[Value]) -> Result<()> {
    ensure_history_budget(history)?;
    let mut pending_calls = HashSet::new();
    let mut calls_seen = HashSet::new();
    for item in history {
        match item
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or("message")
        {
            "message"
                if matches!(
                    item["role"].as_str(),
                    Some("developer" | "user" | "assistant")
                ) && (item["content"].is_string()
                    || item["content"].as_array().is_some_and(|parts| {
                        parts.iter().all(|part| {
                            matches!(part["type"].as_str(), Some("input_text" | "output_text"))
                                && part["text"].is_string()
                        })
                    })) => {}
            "reasoning" if item.is_object() => {}
            "function_call" if item["arguments"].is_string() => {
                if item["namespace"] != TOOL_NAMESPACE {
                    return Err(invalid_stream());
                }
                let id = identifier(&item["call_id"])?;
                identifier(&item["name"])?;
                if !calls_seen.insert(id.clone()) {
                    return Err(invalid_stream());
                }
                pending_calls.insert(id);
            }
            "function_call_output" if item["output"].is_string() => {
                if item["namespace"] != TOOL_NAMESPACE {
                    return Err(invalid_stream());
                }
                if !pending_calls.remove(&identifier(&item["call_id"])?) {
                    return Err(invalid_stream());
                }
            }
            _ => return Err(invalid_stream()),
        }
    }
    if !pending_calls.is_empty() {
        return Err(invalid_stream());
    }
    Ok(())
}

fn ensure_history_budget(history: &[Value]) -> Result<()> {
    if history.is_empty()
        || history.len() > MAX_ITEMS
        || serde_json::to_vec(history)
            .map_err(|_| invalid_stream())?
            .len()
            > MAX_HISTORY_BYTES
    {
        bail!("ChatGPT conversation exceeded its size limit");
    }
    Ok(())
}

fn tool_definitions(specs: Value) -> Result<(Vec<Value>, HashSet<String>)> {
    let specs = specs
        .as_array()
        .filter(|specs| specs.len() <= 32)
        .ok_or_else(invalid_stream)?;
    let mut allowed = HashSet::new();
    let mut definitions = Vec::new();
    for spec in specs {
        let name = identifier(&spec["name"])?;
        if name == "submit_answer"
            || name.len() > 64
            || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            || !spec["inputSchema"].is_object()
            || !allowed.insert(name.clone())
        {
            return Err(invalid_stream());
        }
        definitions.push(json!({"type":"function","name":name,"description":spec["description"].as_str().unwrap_or(""),"parameters":spec["inputSchema"],"strict":false}));
    }
    Ok((definitions, allowed))
}

struct JobGuard<'a> {
    lease: &'a dyn AiCredentialCapability,
    connection: AiConnectionRef,
    owner: String,
    cancellation: watch::Receiver<bool>,
    retirement: AiFuture<'a, ()>,
    deadline: Instant,
}

impl<'a> JobGuard<'a> {
    fn new(
        lease: &'a dyn AiCredentialCapability,
        cancellation: watch::Receiver<bool>,
    ) -> Result<Self> {
        let guard = Self {
            lease,
            connection: lease.connection().clone(),
            owner: lease.principal().owner_id().to_owned(),
            cancellation,
            retirement: lease.retired(),
            deadline: Instant::now() + TOTAL_LIMIT,
        };
        guard.check_local()?;
        Ok(guard)
    }
    fn check_local(&self) -> Result<()> {
        if *self.cancellation.borrow() || self.cancellation.has_changed().is_err() {
            bail!("Stopped.");
        }
        if self.owner.is_empty()
            || self.owner != self.lease.principal().owner_id()
            || self.connection != *self.lease.connection()
            || self.connection.revision == 0
            || self.connection.connection_id.is_empty()
            || self.connection.provider_kind != AiProviderKind::ChatGPTSubscription
        {
            bail!("The ChatGPT connection is no longer available");
        }
        self.lease
            .principal()
            .ensure_current()
            .map_err(|_| anyhow!("The ChatGPT connection is no longer available"))
    }
    async fn check(&mut self) -> Result<()> {
        self.check_local()?;
        tokio::select! { biased;
            _ = wait_for_stop(&mut self.cancellation) => bail!("Stopped."),
            _ = &mut self.retirement => bail!("The ChatGPT connection is no longer available"),
            result = timeout_at(self.deadline, self.lease.ensure_current()) => {
                result.map_err(|_| anyhow!("The ChatGPT task timed out"))?
                    .map_err(|_| anyhow!("The ChatGPT connection is no longer available"))?;
            }
        }
        self.check_local()
    }
    async fn wait<T>(&mut self, future: impl Future<Output = Result<T>>) -> Result<T> {
        self.check().await?;
        let result = tokio::select! { biased;
            _ = wait_for_stop(&mut self.cancellation) => bail!("Stopped."),
            _ = &mut self.retirement => bail!("The ChatGPT connection is no longer available"),
            result = timeout_at(self.deadline, future) => result.map_err(|_| anyhow!("The ChatGPT task timed out"))??,
        };
        self.check().await?;
        Ok(result)
    }
}

async fn wait_for_stop(cancellation: &mut watch::Receiver<bool>) {
    loop {
        if *cancellation.borrow_and_update() {
            return;
        }
        if cancellation.changed().await.is_err() {
            return;
        }
    }
}

fn invalid_stream() -> anyhow::Error {
    anyhow!("ChatGPT returned an invalid or incomplete response")
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
pub(crate) struct ResponsesUsage {
    pub input_tokens: u64,
    pub output_tokens: u64,
    pub total_tokens: u64,
    #[serde(default, deserialize_with = "null_default")]
    pub input_tokens_details: InputTokenDetails,
    #[serde(default, deserialize_with = "null_default")]
    pub output_tokens_details: OutputTokenDetails,
}

fn null_default<'de, D: serde::Deserializer<'de>, T: Deserialize<'de> + Default>(
    deserializer: D,
) -> std::result::Result<T, D::Error> {
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
pub(crate) struct InputTokenDetails {
    #[serde(default)]
    pub cached_tokens: u64,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
pub(crate) struct OutputTokenDetails {
    #[serde(default)]
    pub reasoning_tokens: u64,
}

impl ResponsesUsage {
    fn add(&mut self, other: &Self) -> Result<()> {
        fn sum(left: &mut u64, right: u64) -> Result<()> {
            *left = left.checked_add(right).ok_or_else(invalid_stream)?;
            Ok(())
        }
        sum(&mut self.input_tokens, other.input_tokens)?;
        sum(&mut self.output_tokens, other.output_tokens)?;
        sum(&mut self.total_tokens, other.total_tokens)?;
        sum(
            &mut self.input_tokens_details.cached_tokens,
            other.input_tokens_details.cached_tokens,
        )?;
        sum(
            &mut self.output_tokens_details.reasoning_tokens,
            other.output_tokens_details.reasoning_tokens,
        )
    }
}

enum Frame {
    Event(Value),
    Done,
}

/// SSE framing keeps UTF-8 intact across arbitrary byte chunks, including CR/LF boundaries.
#[derive(Default)]
struct SseDecoder {
    line: Vec<u8>,
    data: Vec<u8>,
    event: Option<String>,
    skip_lf: bool,
    bytes: usize,
}

impl SseDecoder {
    fn push(&mut self, bytes: &[u8]) -> Result<Vec<Frame>> {
        self.bytes = self
            .bytes
            .checked_add(bytes.len())
            .ok_or_else(invalid_stream)?;
        if self.bytes > MAX_STREAM_BYTES {
            bail!("ChatGPT response exceeded its size limit");
        }
        let mut frames = Vec::new();
        for &byte in bytes {
            if self.skip_lf && byte == b'\n' {
                self.skip_lf = false;
                continue;
            }
            self.skip_lf = byte == b'\r';
            if byte == b'\n' || byte == b'\r' {
                if let Some(frame) = self.finish_line()? {
                    frames.push(frame);
                }
            } else {
                if self.line.len() >= MAX_EVENT_BYTES {
                    bail!("ChatGPT stream event exceeded its size limit");
                }
                self.line.push(byte);
            }
        }
        Ok(frames)
    }

    fn finish_line(&mut self) -> Result<Option<Frame>> {
        let line = std::mem::take(&mut self.line);
        let line = std::str::from_utf8(&line).map_err(|_| invalid_stream())?;
        if !line.is_empty() {
            let (field, value) = line.split_once(':').unwrap_or((line, ""));
            let value = value.strip_prefix(' ').unwrap_or(value);
            match field {
                "data" => {
                    if self.data.len() + value.len() + 1 > MAX_EVENT_BYTES {
                        bail!("ChatGPT stream event exceeded its size limit");
                    }
                    self.data.extend_from_slice(value.as_bytes());
                    self.data.push(b'\n');
                }
                "event" => self.event = Some(value.to_owned()),
                _ => {} // SSE comments, IDs and retry metadata never become model output.
            }
            return Ok(None);
        }
        let event_name = self.event.take();
        if self.data.is_empty() {
            return Ok(None);
        }
        let mut data = std::mem::take(&mut self.data);
        data.pop();
        if data == b"[DONE]" {
            return Ok(Some(Frame::Done));
        }
        let event: Value = serde_json::from_slice(&data).map_err(|_| invalid_stream())?;
        let kind = event["type"].as_str().ok_or_else(invalid_stream)?;
        if event_name
            .as_deref()
            .is_some_and(|name| name != "message" && name != kind)
        {
            return Err(invalid_stream());
        }
        Ok(Some(Frame::Event(event)))
    }
}

struct ToolCall {
    id: String,
    name: String,
    arguments: Value,
}

struct CompletedRound {
    items: Vec<Value>,
    text: String,
    calls: Vec<ToolCall>,
    usage: Option<ResponsesUsage>,
}

enum Delta {
    Text(String),
    Summary(String),
}

#[derive(Default)]
struct Round {
    id: Option<String>,
    items: BTreeMap<usize, Value>,
    completed: Option<CompletedRound>,
}

impl Round {
    fn accept(&mut self, frame: Frame) -> Result<Option<Delta>> {
        if self.completed.is_some() {
            return match frame {
                Frame::Done => Ok(None),
                _ => Err(invalid_stream()),
            };
        }
        let Frame::Event(event) = frame else {
            return Err(invalid_stream());
        };
        match event["type"].as_str().ok_or_else(invalid_stream)? {
            "error" | "response.failed" => {
                let error = event
                    .get("error")
                    .or_else(|| event["response"].get("error"));
                match error.and_then(|e| e["code"].as_str()).or_else(|| event["code"].as_str()) {
                    Some("subscription_sharing_usage_limit_exceeded") => bail!("The ChatGPT subscription allowance is exhausted; try again later"),
                    Some("subscription_sharing_usage_unavailable") => bail!("ChatGPT subscription inference is currently unavailable"),
                    _ => bail!("ChatGPT could not complete this request; check the connection and try again"),
                }
            }
            "response.incomplete" => bail!("ChatGPT did not complete the answer"),
            "response.refusal.delta" | "response.refusal.done" => {
                bail!("ChatGPT declined this request")
            }
            "response.created" => {
                let id = identifier(&event["response"]["id"])?;
                if self.id.replace(id).is_some() {
                    return Err(invalid_stream());
                }
            }
            "response.output_item.done" => {
                let index = event["output_index"]
                    .as_u64()
                    .filter(|n| *n < MAX_ITEMS as u64)
                    .ok_or_else(invalid_stream)? as usize;
                let item = event
                    .get("item")
                    .filter(|item| item.is_object())
                    .ok_or_else(invalid_stream)?;
                if self.items.insert(index, item.clone()).is_some() {
                    return Err(invalid_stream());
                }
            }
            "response.output_text.delta" | "response.reasoning_summary_text.delta" => {
                let value = event["delta"]
                    .as_str()
                    .ok_or_else(invalid_stream)?
                    .to_owned();
                return Ok(Some(if event["type"] == "response.output_text.delta" {
                    Delta::Text(value)
                } else {
                    Delta::Summary(value)
                }));
            }
            "response.completed" => {
                let response = event
                    .get("response")
                    .filter(|r| r.is_object())
                    .ok_or_else(invalid_stream)?;
                let id = identifier(&response["id"])?;
                if self.id.as_ref().is_some_and(|opening| *opening != id)
                    || response
                        .get("status")
                        .is_some_and(|status| status != "completed")
                {
                    return Err(invalid_stream());
                }
                let items = if let Some(output) = response.get("output") {
                    let output = output.as_array().ok_or_else(invalid_stream)?;
                    if output.len() > MAX_ITEMS
                        || self
                            .items
                            .iter()
                            .any(|(i, item)| output.get(*i) != Some(item))
                    {
                        return Err(invalid_stream());
                    }
                    output.clone()
                } else {
                    if self.items.keys().copied().ne(0..self.items.len()) {
                        return Err(invalid_stream());
                    }
                    std::mem::take(&mut self.items).into_values().collect()
                };
                let usage = response
                    .get("usage")
                    .filter(|u| !u.is_null())
                    .map(|u| serde_json::from_value(u.clone()).map_err(|_| invalid_stream()))
                    .transpose()?;
                self.completed = Some(complete_round(items, usage)?);
            }
            _ => {} // Created/added/argument-fragment metadata cannot authorize tools or completion.
        }
        Ok(None)
    }
}

fn identifier(value: &Value) -> Result<String> {
    let value = value
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 256 && !s.chars().any(char::is_control))
        .ok_or_else(invalid_stream)?;
    Ok(value.to_owned())
}

fn complete_round(items: Vec<Value>, usage: Option<ResponsesUsage>) -> Result<CompletedRound> {
    if items.is_empty()
        || serde_json::to_vec(&items)
            .map_err(|_| invalid_stream())?
            .len()
            > MAX_HISTORY_BYTES
    {
        return Err(invalid_stream());
    }
    let mut text = String::new();
    let mut calls = Vec::new();
    for item in &items {
        if item
            .get("status")
            .is_some_and(|status| status != "completed")
        {
            return Err(invalid_stream());
        }
        match item["type"].as_str() {
            Some("message") => {
                if item["role"] != "assistant" {
                    return Err(invalid_stream());
                }
                for part in item["content"].as_array().ok_or_else(invalid_stream)? {
                    match part["type"].as_str() {
                        Some("output_text") => {
                            text.push_str(part["text"].as_str().ok_or_else(invalid_stream)?)
                        }
                        Some("refusal") => bail!("ChatGPT declined this request"),
                        _ => return Err(invalid_stream()),
                    }
                }
            }
            Some("reasoning") => {} // Preserve the opaque encrypted reasoning item in client-owned history.
            Some("function_call") => {
                if calls.len() >= MAX_TOOL_CALLS {
                    bail!("ChatGPT returned too many tool calls");
                }
                if item["namespace"] != TOOL_NAMESPACE
                    || item["async"] == true
                    || item
                        .get("caller")
                        .is_some_and(|caller| !caller.is_null() && caller["type"] != "direct")
                {
                    return Err(invalid_stream());
                }
                let id = identifier(&item["call_id"])?;
                let name = identifier(&item["name"])?;
                let arguments: Value =
                    serde_json::from_str(item["arguments"].as_str().ok_or_else(invalid_stream)?)
                        .map_err(|_| invalid_stream())?;
                if !arguments.is_object() || calls.iter().any(|call: &ToolCall| call.id == id) {
                    return Err(invalid_stream());
                }
                calls.push(ToolCall {
                    id,
                    name,
                    arguments,
                });
            }
            _ => return Err(invalid_stream()), // Built-in browser/shell tools are never part of this adapter.
        }
    }
    Ok(CompletedRound {
        items,
        text,
        calls,
        usage,
    })
}

#[cfg(test)]
mod tests;
