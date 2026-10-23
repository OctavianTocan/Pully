mod stream;
use stream::completion;

use super::{tail, Jobs, Run};
use crate::{text, AppState};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::sync::{Arc, OnceLock};
use tokio::time::{Duration, Instant};

const PI_TOTAL_LIMIT: Duration = Duration::from_secs(30 * 60);

async fn within_pi_deadline<T>(
    deadline: Instant,
    work: impl std::future::Future<Output = Result<T>>,
) -> Result<T> {
    tokio::time::timeout_at(deadline, work)
        .await
        .context("Pi did not finish within 30 minutes")?
}

fn config() -> Result<(String, String)> {
    let base = std::env::var("PULLS_REVIEW_PI_BASE_URL").unwrap_or_default();
    if base.trim().is_empty() {
        bail!("Pi is not set up. Set PULLS_REVIEW_PI_BASE_URL and PULLS_REVIEW_PI_API_KEY on the server.")
    };
    let u = url::Url::parse(base.trim()).context("Invalid Pi endpoint configuration")?;
    if !["http", "https"].contains(&u.scheme())
        || !u.username().is_empty()
        || u.password().is_some()
        || u.query().is_some()
        || u.fragment().is_some()
        || u.host_str().is_none()
    {
        bail!("Invalid Pi endpoint configuration")
    };
    Ok((
        base.trim().trim_end_matches('/').to_owned(),
        std::env::var("PULLS_REVIEW_PI_API_KEY")
            .unwrap_or_default()
            .trim()
            .to_owned(),
    ))
}
fn effort(id: &str) -> Option<String> {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(r"(?i)(?:-|\()(minimal|low|medium|high|xhigh)\)?$").unwrap()
    });
    re.captures(id).map(|c| c[1].to_ascii_lowercase())
}
pub async fn catalog(state: &AppState, engine: &str) -> Result<Value> {
    catalog_refresh(state, engine, false, &crate::agent_login::AgentLogin::Server).await
}
/// Claude Code and Codex list the models of the caller's own subscription, so their lists are
/// kept per login; Pi has one endpoint for the whole server.
pub async fn catalog_refresh(
    state: &AppState,
    engine: &str,
    refresh: bool,
    login: &crate::agent_login::AgentLogin,
) -> Result<Value> {
    anyhow::ensure!(
        state.identity.is_legacy(),
        "Connect a personal AI provider to list its models"
    );
    let engine = if engine == "claude" {
        "claude-code"
    } else {
        engine
    };
    if !["pi", "claude-code", "codex"].contains(&engine) {
        bail!("Unknown AI engine");
    }
    let cache_key = if engine == "pi" {
        engine.to_owned()
    } else {
        format!("{engine}:{}", login.volume())
    };
    cached_catalog(&state.jobs, &cache_key, refresh, async {
        if engine == "pi" {
            pi_catalog(state).await
        } else {
            crate::agent_process::catalog(engine, login).await
        }
    })
    .await
}

async fn cached_catalog(
    jobs: &Jobs,
    engine: &str,
    refresh: bool,
    fetch: impl std::future::Future<Output = Result<Value>>,
) -> Result<Value> {
    let refresh_lock = jobs
        .catalog_refreshes
        .lock()
        .unwrap()
        .entry(engine.to_owned())
        .or_default()
        .clone();
    let _refresh = refresh_lock.lock().await;
    if !refresh {
        let cache = jobs.catalogs.lock().await;
        if let Some((at, value)) = cache.get(engine) {
            if crate::now_ms().saturating_sub(*at) < 10 * 60 * 1000 {
                return Ok(value.clone());
            }
        }
    }
    let result = fetch.await;
    let mut cache = jobs.catalogs.lock().await;
    match result {
        Ok(value) => {
            cache.insert(engine.to_owned(), (crate::now_ms(), value.clone()));
            Ok(value)
        }
        Err(error) => {
            cache.remove(engine);
            Err(error)
        }
    }
}

async fn pi_catalog(state: &AppState) -> Result<Value> {
    let (base, key) = config()?;
    let mut req = state
        .http
        .get(format!("{base}/models"))
        .timeout(std::time::Duration::from_secs(15));
    if !key.is_empty() {
        req = req.bearer_auth(key);
    }
    let response = req
        .send()
        .await
        .map_err(|_| anyhow::anyhow!("Could not reach the Pi model endpoint"))?;
    if !response.status().is_success() {
        bail!(
            "Pi model endpoint returned HTTP {}",
            response.status().as_u16()
        );
    }
    let body = bounded_json(response, 4 * 1024 * 1024).await?;
    let preferred = std::env::var("PULLS_REVIEW_PI_MODEL")
        .unwrap_or_default()
        .trim()
        .to_owned();
    let models = pi_models(&body, &preferred);
    if models.is_empty() {
        bail!("Pi endpoint lists no text models");
    }
    let parsed = url::Url::parse(&base)?;
    let account = parsed[url::Position::BeforeHost..url::Position::AfterPort].to_owned();
    serde_json::from_str(&super::sanitize(
        // `listed` keeps every text id, including aliases the one-row-per-model list drops.
        &json!({"engine":"pi","models":models,"listed":pi_text_entries(&body).iter().map(|e| text(e, "id")).collect::<Vec<_>>(),"account":account}).to_string(),
    ))
    .context("Invalid catalog metadata")
}
/// A provider call owns its activity namespace, even when parallel tour stops
/// and repair passes share one task. Deltas remain stable within that call.
struct Activity {
    run: Arc<Run>,
    namespace: String,
}
impl Activity {
    fn new(run: &Arc<Run>) -> Self {
        Self {
            run: run.clone(),
            namespace: uuid::Uuid::new_v4().to_string(),
        }
    }
    fn patch(&self, mut patch: Value) {
        let Some(id) = patch["id"].as_str().filter(|id| !id.is_empty()) else {
            return;
        };
        patch["id"] = json!(format!("{}:{id}", self.namespace));
        super::record_patch(&self.run, patch);
    }
    fn record(&self, id: &str, kind: &str, title: &str, body: Option<&str>, status: &str) {
        let mut patch = json!({"id":id,"kind":kind,"title":title,"status":status});
        if let Some(body) = body {
            patch["text"] = json!(body);
        }
        self.patch(patch);
    }
}

pub async fn run(state: &Arc<AppState>, run: &Arc<Run>, req: &Value) -> Result<(String, Value)> {
    run_prepared(state, run, req, super::StructuredAnswerPreparation::Strict).await
}

/// @cc [owner:OctavianTocan,label:architecture] rust-provider-operations-share-three-slots
/// Every provider operation, including parallel tour stops, MUST share a three-slot
/// limit, acquire cancellation-aware before preparation or execution deadlines, and
/// release its slot on every exit (base jobs.ts MAX_RUNNING; jobs-release-acquired-slots;
/// review R11, 16 Oct 2026).
async fn acquire_provider(jobs: &Jobs, run: &Run) -> Result<tokio::sync::OwnedSemaphorePermit> {
    let mut cancelled = run.cancel.subscribe();
    super::check(run)?;
    let permit = tokio::select! {
        biased;
        _ = cancelled.changed() => bail!("Stopped."),
        permit = jobs.provider_slots.clone().acquire_owned() =>
            permit.context("Provider admission is closed")?,
    };
    super::check(run)?;
    Ok(permit)
}

pub(super) async fn run_prepared(
    state: &Arc<AppState>,
    run: &Arc<Run>,
    req: &Value,
    preparation: super::StructuredAnswerPreparation,
) -> Result<(String, Value)> {
    let _permit = acquire_provider(&state.jobs, run).await?;
    let activity = Activity::new(run);
    let safe_req = super::sanitize_value(req);
    let req = &safe_req;
    let engine = text(req, "engine");
    if engine != "pi" {
        let mut request = req.clone();
        let mut guard = None;
        if req["context"].is_object() {
            let (context, bridge) = super::scoped_bridge(state, run, &req["context"])?;
            request["_tools"] = json!(super::tools::specs_for(&req["context"])
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|tool| tool["name"].as_str())
                .collect::<Vec<_>>());
            request["context"] = context;
            guard = Some(bridge);
        }
        let sink = Arc::new(move |patch| activity.patch(patch));
        let result = crate::agent_process::run_agent_prepared(
            request,
            &run.login,
            run.cancel.subscribe(),
            sink,
            preparation,
        )
        .await;
        drop(guard);
        return result;
    }
    // One monotonic watchdog covers all requests and source tools, not each turn.
    let deadline = Instant::now() + PI_TOTAL_LIMIT;
    within_pi_deadline(
        deadline,
        run_pi(state, run, req, deadline, &activity, preparation),
    )
    .await
}

async fn pi_model(state: &AppState, wanted: &[&str], refresh: bool) -> Result<String> {
    match catalog_refresh(state, "pi", refresh, &crate::agent_login::AgentLogin::Server).await {
        Ok(listed) => listed_pi_model(&listed, wanted).context("Pi endpoint lists no text models"),
        // Without the list, the model the phone or the server names is still worth a try.
        Err(error) => wanted.first().map(|m| (*m).to_owned()).ok_or(error),
    }
}

/// @cc [owner:OctavianTocan,label:product] pi-runs-a-listed-model
/// A Pi run MUST use a model the Pi endpoint lists: the one the phone asked for, else the
/// server's PULLS_REVIEW_PI_MODEL, else the catalog's default. A saved model the endpoint
/// no longer lists MUST NOT fail the run (Tavi, 18 Oct 2026: tours kept failing because
/// "their pi model doesn't exist"). A model the endpoint rejects on the first request
/// MUST be checked once against a fresh list before the run fails.
fn listed_pi_model(catalog: &Value, wanted: &[&str]) -> Option<String> {
    let models = catalog["models"].as_array()?;
    let id = |m: &Value| m["id"].as_str().map(str::to_owned);
    let listed = |w: &str| {
        models.iter().any(|m| m["id"] == w)
            || catalog["listed"]
                .as_array()
                .into_iter()
                .flatten()
                .any(|l| l == w)
    };
    wanted
        .iter()
        .find(|w| listed(w))
        .map(|w| (*w).to_owned())
        .or_else(|| models.iter().find(|m| m["isDefault"] == true).and_then(id))
        .or_else(|| models.first().and_then(id))
}

async fn run_pi(
    state: &Arc<AppState>,
    run: &Arc<Run>,
    req: &Value,
    deadline: Instant,
    activity: &Activity,
    preparation: super::StructuredAnswerPreparation,
) -> Result<(String, Value)> {
    let (base, key) = config()?;
    let requested = text(req, "model").trim().to_owned();
    let preferred = std::env::var("PULLS_REVIEW_PI_MODEL")
        .unwrap_or_default()
        .trim()
        .to_owned();
    let wanted: Vec<&str> = [requested.as_str(), preferred.as_str()]
        .into_iter()
        .filter(|m| !m.is_empty())
        .collect();
    let swapped = |model: &str| {
        let gone = wanted.first().filter(|w| **w != model)?;
        activity.record(
            "model",
            "status",
            &format!("Pi no longer offers {gone}, so this used {model}"),
            None,
            "done",
        );
        Some(())
    };
    let mut model = pi_model(state, &wanted, false).await?;
    swapped(&model);
    let mut rechecked = false;
    let mut messages = vec![
        json!({"role":"system","content":text(req,"system")}),
        json!({"role":"user","content":text(req,"prompt")}),
    ];
    let mut specs: Vec<Value> = if req["context"].is_object() {
        super::tools::specs_for(&req["context"]).as_array().unwrap().iter().map(|t|json!({"type":"function","function":{"name":t["name"],"description":t["description"],"parameters":t["inputSchema"]}})).collect()
    } else {
        vec![]
    };
    let structured = req.get("schema").filter(|v| !v.is_null());
    if let Some(schema) = structured {
        specs.push(json!({"type":"function","function":{"name":"submit_answer","description":"Submit the complete final structured answer.","parameters":schema}}));
        messages[0]["content"] = json!(format!(
            "{}\nWhen done, call submit_answer with the complete answer.",
            text(req, "system")
        ))
    }
    let mut input = 0_u64;
    let mut output = 0_u64;
    let mut cached = 0_u64;
    let mut reasoning = 0_u64;
    let mut chars = 0;
    let mut nudged_submit = false;
    let started = crate::now_ms();
    for turn in 0..50 {
        super::check(run)?;
        if turn == 40 || chars > 400_000 {
            messages.push(json!({"role":"user","content":"Reading budget reached. Answer now with the evidence already available."}));
            chars = 0
        }
        let mut body = json!({"model":model,"messages":messages,"stream":true,"stream_options":{"include_usage":true}});
        if !specs.is_empty() {
            body["tools"] = json!(specs)
        }
        if !text(req, "effort").is_empty() && effort(&model).is_none() {
            body["reasoning_effort"] = req["effort"].clone()
        }
        let mut request = state
            .http
            .post(format!("{base}/chat/completions"))
            .timeout(deadline.saturating_duration_since(Instant::now()))
            .json(&body);
        if !key.is_empty() {
            request = request.bearer_auth(&key)
        }
        activity.record("request", "status", "Thinking", None, "running");
        let response = request
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("Pi model request could not be completed"))?;
        if !response.status().is_success() {
            // The list is cached for ten minutes; a model removed since then fails here.
            if turn == 0 && !rechecked && response.status().is_client_error() {
                rechecked = true;
                if let Ok(next) = pi_model(state, &wanted, true).await {
                    if next != model {
                        model = next;
                        swapped(&model);
                        continue;
                    }
                }
            }
            bail!(
                "Pi model endpoint returned HTTP {} for {model}. Check the server configuration.",
                response.status().as_u16()
            )
        };
        let result = completion(response, activity, turn).await?;
        let cache_tokens = result["usage"]["prompt_tokens_details"]["cached_tokens"]
            .as_u64()
            .unwrap_or(0);
        cached = cached.saturating_add(cache_tokens);
        input = input.saturating_add(
            result["usage"]["prompt_tokens"]
                .as_u64()
                .unwrap_or(0)
                .saturating_sub(cache_tokens),
        );
        output = output.saturating_add(result["usage"]["completion_tokens"].as_u64().unwrap_or(0));
        reasoning = reasoning.saturating_add(
            result["usage"]["completion_tokens_details"]["reasoning_tokens"]
                .as_u64()
                .unwrap_or(0),
        );
        let usage = super::sanitize_value(
            &json!({"inputTokens":input,"outputTokens":output,"cacheReadTokens":cached,"reasoningTokens":reasoning,"durationMs":crate::now_ms()-started,"model":result["model"].as_str().unwrap_or(&model)}),
        );
        let message = result["choices"][0]["message"].clone();
        if !message.is_object() {
            bail!("Pi returned no assistant message");
        }
        messages.push(message.clone());
        if let Some(calls) = message["tool_calls"].as_array().filter(|a| !a.is_empty()) {
            for call in calls {
                super::check(run)?;
                let name = text(&call["function"], "name");
                let args: Value = serde_json::from_str(text(&call["function"], "arguments"))
                    .context("Invalid model tool arguments")?;
                if name == "submit_answer" {
                    let schema = structured.context("Unexpected structured answer tool")?;
                    let args = super::prepare_structured_answer(schema, &args, preparation)?;
                    activity.patch(
                        json!({"id":format!("tool:{}",text(call,"id")),"kind":"tool","tool":"submit_answer","title":"Wrote the answer","status":"done"}),
                    );
                    return Ok((super::sanitize(&serde_json::to_string(&args)?), usage));
                }
                let id = format!("tool:{}", text(call, "id"));
                activity.record(&id, "tool", &format!("Reading {name}"), None, "running");
                activity.patch(json!({"id":id,"tool":name}));
                let r = super::tools::call_for_run(state, run, &req["context"], name, &args).await;
                let (status, result) = match r {
                    Ok(t) => ("done", t),
                    Err(e) => ("failed", e.to_string()),
                };
                let result = super::sanitize(&result);
                chars += result.len();
                activity.record(
                    &id,
                    "tool",
                    name,
                    Some(&result.chars().take(400).collect::<String>()),
                    status,
                );
                messages.push(json!({"role":"tool","tool_call_id":call["id"],"content":result}))
            }
            continue;
        }
        let answer = text(&message, "content");
        if structured.is_some() {
            if nudged_submit {
                bail!("The model did not call submit_answer");
            }
            nudged_submit = true;
            messages
                .push(json!({"role":"user","content":"Return the answer using submit_answer."}));
            continue;
        }
        if answer.trim().is_empty() {
            bail!("The model returned an empty answer")
        };
        activity.record(
            &format!("text:{turn}"),
            "text",
            "Wrote answer",
            Some(&tail(&super::sanitize(answer))),
            "done",
        );
        return Ok((super::sanitize(answer), usage));
    }
    bail!("Model exceeded the tool-loop turn limit")
}

async fn bounded_json(mut response: reqwest::Response, limit: usize) -> Result<Value> {
    if response.content_length().is_some_and(|n| n > limit as u64) {
        bail!("Pi response exceeded its size limit")
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| anyhow::anyhow!("Pi response could not be read"))?
    {
        if bytes.len() + chunk.len() > limit {
            bail!("Pi response exceeded its size limit")
        };
        bytes.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("Pi returned invalid JSON"))
}

fn route(word: &str) -> Option<&'static str> {
    match word {
        "agy" | "antigravity" => Some("AGY"),
        "claude" => Some("Claude"),
        "codex" => Some("Codex"),
        "gemini" => Some("Gemini"),
        "qwen" => Some("Qwen"),
        "iflow" => Some("iFlow"),
        "kiro" => Some("Kiro"),
        "copilot" => Some("Copilot"),
        "openrouter" => Some("OpenRouter"),
        "vertex" => Some("Vertex"),
        "aistudio" => Some("AI Studio"),
        _ => None,
    }
}
fn family(word: &str) -> String {
    let word = word.to_ascii_lowercase();
    if word.starts_with('o') && word.as_bytes().get(1).is_some_and(u8::is_ascii_digit) {
        "o".into()
    } else {
        word.chars().take_while(char::is_ascii_alphabetic).collect()
    }
}
fn maker(word: &str) -> Option<&'static str> {
    match word {
        "claude" => Some("Claude"),
        "gpt" | "chatgpt" | "codex" | "o" => Some("Codex"),
        "gemini" => Some("Gemini"),
        "qwen" | "qwq" => Some("Qwen"),
        "grok" => Some("xAI"),
        "deepseek" => Some("DeepSeek"),
        "kimi" => Some("Kimi"),
        "glm" => Some("GLM"),
        "minimax" => Some("MiniMax"),
        "mimo" => Some("Xiaomi MiMo"),
        "longcat" => Some("Meituan LongCat"),
        "hy" | "hunyuan" => Some("Tencent HY"),
        "llama" | "muse" => Some("Meta"),
        "mistral" | "codestral" | "devstral" | "magistral" => Some("Mistral"),
        _ => None,
    }
}
fn split_route(id: &str) -> (Option<String>, String) {
    static PREFIX: OnceLock<regex::Regex> = OnceLock::new();
    let parts: Vec<&str> = id.trim().split('/').collect();
    let mut group = if parts.len() > 1 {
        route(&parts[0].to_ascii_lowercase()).map(str::to_owned)
    } else {
        None
    };
    let mut rest = parts.last().unwrap_or(&"").to_string();
    let prefix =
        PREFIX.get_or_init(|| regex::Regex::new(r"(?i)^([a-z]+)[-_:.]([^-_:.].*)$").unwrap());
    for _ in 0..3 {
        let Some(found) = prefix.captures(&rest) else {
            break;
        };
        let head = found[1].to_ascii_lowercase();
        let next = family(&found[2]);
        let before_family = maker(&next).is_some();
        if let Some(route) = route(&head).filter(|_| {
            before_family || !["claude", "codex", "gemini", "qwen"].contains(&head.as_str())
        }) {
            if group.is_none() {
                group = Some(route.into());
            }
            rest = found[2].to_owned();
        } else if before_family
            && [
                "xai",
                "openai",
                "anthropic",
                "google",
                "moonshot",
                "moonshotai",
                "alibaba",
                "zai",
                "zhipu",
                "meta",
                "xiaomi",
                "meituan",
                "tencent",
                "deepseek",
                "minimax",
                "mistralai",
            ]
            .contains(&head.as_str())
        {
            rest = found[2].to_owned();
        } else {
            break;
        }
    }
    (group, rest)
}
fn pretty(word: &str) -> String {
    static CAPACITY: OnceLock<regex::Regex> = OnceLock::new();
    let lower = word.to_ascii_lowercase();
    match lower.as_str() {
        "gpt" => "GPT".into(),
        "oss" => "OSS".into(),
        "glm" => "GLM".into(),
        "deepseek" => "DeepSeek".into(),
        "minimax" => "MiniMax".into(),
        "highspeed" => "HighSpeed".into(),
        "vl" => "VL".into(),
        "mimo" => "MiMo".into(),
        "longcat" => "LongCat".into(),
        "agy" => "AGY".into(),
        "xai" => "xAI".into(),
        _ => {
            if CAPACITY
                .get_or_init(|| regex::Regex::new(r"^\d+(?:\.\d+)?[bkm]$").unwrap())
                .is_match(&lower)
            {
                return lower.to_ascii_uppercase();
            }
            let mut chars = lower.chars();
            chars
                .next()
                .map(|first| first.to_uppercase().collect::<String>() + chars.as_str())
                .unwrap_or_default()
        }
    }
}
fn model_name(id: &str) -> String {
    static BRACKET: OnceLock<regex::Regex> = OnceLock::new();
    static DATE: OnceLock<regex::Regex> = OnceLock::new();
    static SNAPSHOT: OnceLock<regex::Regex> = OnceLock::new();
    static SHORT: OnceLock<regex::Regex> = OnceLock::new();
    static O_MODEL: OnceLock<regex::Regex> = OnceLock::new();
    let (_, mut base) = split_route(id);
    let bracket = BRACKET.get_or_init(|| regex::Regex::new(r"\(([^)]*)\)$").unwrap());
    let mut level = None;
    if let Some(found) = bracket.captures(&base) {
        level = Some(found[1].trim().to_owned());
        base = base[..found.get(0).unwrap().start()].to_owned();
    }
    let date = DATE.get_or_init(|| regex::Regex::new(r"-\d{4}-\d{2}-\d{2}").unwrap());
    base = date.replace_all(&base, "").to_string();
    let snapshot = SNAPSHOT.get_or_init(|| {
        regex::Regex::new(r"^(?:\d{8}|0\d{2}|(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))$").unwrap()
    });
    let parts: Vec<&str> = base
        .split(|c: char| matches!(c, '-' | '_' | ':') || c.is_whitespace())
        .filter(|s| !s.is_empty() && !snapshot.is_match(s))
        .collect();
    let short = SHORT.get_or_init(|| regex::Regex::new(r"^\d{1,2}$").unwrap());
    let mut words: Vec<String> = vec![];
    let mut index = 0;
    while index < parts.len() {
        if index + 1 < parts.len()
            && short.is_match(parts[index])
            && short.is_match(parts[index + 1])
        {
            words.push(format!("{}.{}", parts[index], parts[index + 1]));
            index += 2;
        } else {
            words.push(parts[index].to_owned());
            index += 1;
        }
    }
    while words.len() > 1 && words[0].eq_ignore_ascii_case(&words[1]) {
        words.remove(0);
    }
    if level.is_none()
        && words.len() > 2
        && words.last().is_some_and(|w| {
            ["minimal", "low", "medium", "high", "xhigh"].contains(&w.to_ascii_lowercase().as_str())
        })
    {
        level = words.pop();
    }
    if words
        .first()
        .is_some_and(|w| w.eq_ignore_ascii_case("claude"))
        && words
            .get(1)
            .is_some_and(|w| w.starts_with(|c: char| c.is_ascii_digit()))
        && words
            .get(2)
            .is_some_and(|w| ["opus", "sonnet", "haiku"].contains(&w.as_str()))
    {
        words.swap(1, 2);
    }
    let name = if words.first().is_some_and(|w| {
        O_MODEL
            .get_or_init(|| regex::Regex::new(r"(?i)^o\d+$").unwrap())
            .is_match(w)
    }) {
        words.join("-").to_ascii_lowercase()
    } else {
        let mut name = words
            .iter()
            .map(|w| pretty(w))
            .collect::<Vec<_>>()
            .join(" ");
        if name.starts_with("GPT ")
            && name[4..].starts_with(|c: char| c.is_ascii_digit() || c == 'O')
        {
            name.replace_range(3..4, "-");
        }
        name.replace("Non ", "Non-")
    };
    if let Some(level) = level.filter(|s| !s.is_empty()) {
        format!("{name} ({})", pretty(&level))
    } else {
        name
    }
}
fn model_version(name: &str) -> i64 {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = RE.get_or_init(|| regex::Regex::new(r"(\d+)(?:\.(\d+))?([BKM])?\b").unwrap());
    re.captures_iter(name)
        .find(|c| c.get(3).is_none())
        .map(|c| {
            c[1].parse::<i64>().unwrap_or(0) * 1000
                + c.get(2)
                    .and_then(|c| c.as_str().parse::<i64>().ok())
                    .unwrap_or(0)
        })
        .unwrap_or(-1)
}
/// The endpoint's text models, each id once, in its order.
fn pi_text_entries(body: &Value) -> Vec<&Value> {
    let mut seen = std::collections::HashSet::new();
    let mut entries = vec![];
    for entry in body["data"].as_array().into_iter().flatten() {
        let id = text(entry, "id");
        if id.trim().is_empty() || id.len() > 256 || !seen.insert(id) {
            continue;
        }
        if id
            .to_ascii_lowercase()
            .split(|c: char| {
                matches!(c, '-' | '_' | '/' | ':' | '.' | '(' | ')') || c.is_whitespace()
            })
            .any(|word| {
                [
                    "image",
                    "images",
                    "imagine",
                    "imagen",
                    "dall",
                    "dalle",
                    "banana",
                    "sora",
                    "veo",
                    "video",
                    "embed",
                    "embedding",
                    "embeddings",
                    "tts",
                    "speech",
                    "audio",
                    "realtime",
                    "whisper",
                    "transcribe",
                    "moderation",
                    "rerank",
                    "reranker",
                ]
                .contains(&word)
            })
        {
            continue;
        }
        entries.push(entry);
    }
    entries
}
pub(crate) fn pi_models(body: &Value, preferred: &str) -> Vec<Value> {
    let entries = pi_text_entries(body);
    let fallback = entries
        .iter()
        .find(|e| text(e, "id") == preferred)
        .or(entries.first())
        .map(|e| text(e, "id"))
        .unwrap_or("");
    let mut rows: Vec<(String, String, String, bool)> = vec![];
    for entry in entries {
        let id = text(entry, "id");
        let (route_group, model) = split_route(id);
        let family = family(&model);
        let owner = text(entry, "owned_by").trim().to_ascii_lowercase();
        let group = route_group
            .or_else(|| {
                route(&owner)
                    .filter(|_| !["claude", "codex", "gemini", "qwen"].contains(&owner.as_str()))
                    .map(str::to_owned)
            })
            .or_else(|| maker(&family).map(str::to_owned))
            .unwrap_or_else(|| {
                if family.is_empty() {
                    "Other".into()
                } else {
                    pretty(&family)
                }
            });
        let name = model_name(id);
        let routed = model != id;
        let rank =
            |id: &str, routed: bool| (usize::from(id != fallback), usize::from(routed), id.len());
        if let Some(old) = rows.iter_mut().find(|(_, old_name, old_group, _)| {
            old_name.eq_ignore_ascii_case(&name) && old_group == &group
        }) {
            if rank(id, routed) < rank(&old.0, old.3) {
                *old = (id.into(), name, group, routed);
            }
        } else {
            rows.push((id.into(), name, group, routed));
        }
    }
    let lead = rows
        .iter()
        .find(|r| r.0 == fallback)
        .map(|r| r.2.clone())
        .unwrap_or_default();
    rows.sort_by(|a, b| {
        (b.0 == fallback)
            .cmp(&(a.0 == fallback))
            .then_with(|| (b.2 == lead).cmp(&(a.2 == lead)))
            .then_with(|| a.2.to_lowercase().cmp(&b.2.to_lowercase()))
            .then_with(|| family(&a.1).cmp(&family(&b.1)))
            .then_with(|| model_version(&b.1).cmp(&model_version(&a.1)))
            .then_with(|| a.1.cmp(&b.1))
    });
    rows.into_iter().map(|(id,name,group,_)|{
        let level=effort(&id);let mut row=json!({"id":id,"name":name,"group":group,"efforts":level.as_ref().map(|e|vec![e.as_str()]).unwrap_or_else(||vec!["low","medium","high"])});
        if id==fallback{row["isDefault"]=json!(true);}if let Some(level)=level{row["defaultEffort"]=json!(level);}row
    }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pi_runs_a_listed_model_when_the_saved_one_is_gone() {
        let catalog =
            json!({"models":[{"id":"gpt-5.1"},{"id":"claude-sonnet-4.5","isDefault":true}]});
        let pick = |wanted: &[&str]| listed_pi_model(&catalog, wanted);
        assert_eq!(
            pick(&["gpt-5.1", "claude-sonnet-4.5"]).as_deref(),
            Some("gpt-5.1")
        );
        assert_eq!(pick(&["gone", "gpt-5.1"]).as_deref(), Some("gpt-5.1"));
        assert_eq!(
            pick(&["gone", "also-gone"]).as_deref(),
            Some("claude-sonnet-4.5")
        );
        assert_eq!(pick(&[]).as_deref(), Some("claude-sonnet-4.5"));
        let no_default = json!({"models":[{"id":"a"},{"id":"b"}]});
        assert_eq!(
            listed_pi_model(&no_default, &["gone"]).as_deref(),
            Some("a")
        );
        assert_eq!(listed_pi_model(&json!({"models":[]}), &["gone"]), None);
        let alias = json!({"models":[{"id":"claude-opus-5-5","isDefault":true}],"listed":["claude-claude-opus-5-5","claude-opus-5-5"]});
        assert_eq!(
            listed_pi_model(&alias, &["claude-claude-opus-5-5"]).as_deref(),
            Some("claude-claude-opus-5-5")
        );
    }
    fn fixture_run(kind: &str) -> Arc<Run> {
        Arc::new(Run {
            snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0}))
                .into(),
            publication: Default::default(),
            request: json!({"kind":kind}),
            task: true,
            notify: tokio::sync::Notify::new().into(),
            cancel: tokio::sync::watch::channel(false).0,
            login: crate::agent_login::AgentLogin::Server,
        })
    }
    #[tokio::test(start_paused = true)]
    async fn provider_slots_bound_overlapping_tour_fanouts_and_chat() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let jobs = Jobs::default();
        let active = Arc::new(AtomicUsize::new(0));
        let maximum = Arc::new(AtomicUsize::new(0));
        let launches = Arc::new(AtomicUsize::new(0));
        let mut work = vec![];
        // Two concurrent tours fan out four stops each, beside one chat operation.
        for (run, count) in [
            (fixture_run("tour"), 4),
            (fixture_run("tour"), 4),
            (fixture_run("chat"), 1),
        ] {
            for _ in 0..count {
                let (jobs, run, active, maximum, launches) = (
                    jobs.clone(),
                    run.clone(),
                    active.clone(),
                    maximum.clone(),
                    launches.clone(),
                );
                work.push(tokio::spawn(async move {
                    let _permit = acquire_provider(&jobs, &run).await.unwrap();
                    let live = active.fetch_add(1, Ordering::SeqCst) + 1;
                    maximum.fetch_max(live, Ordering::SeqCst);
                    launches.fetch_add(1, Ordering::SeqCst);
                    tokio::time::sleep(Duration::from_millis(10)).await;
                    active.fetch_sub(1, Ordering::SeqCst);
                }));
            }
        }
        for task in work {
            task.await.unwrap();
        }
        assert_eq!(maximum.load(Ordering::SeqCst), 3);
        assert_eq!(launches.load(Ordering::SeqCst), 9);
        assert_eq!(active.load(Ordering::SeqCst), 0);
        assert_eq!(jobs.provider_slots.available_permits(), 3);
    }
    #[tokio::test(start_paused = true)]
    async fn provider_queue_cancellation_never_prepares_and_failure_releases_slot() {
        let root =
            std::env::temp_dir().join(format!("pulls-provider-queue-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.join("data")).unwrap();
        let occupied = state
            .jobs
            .provider_slots
            .clone()
            .acquire_many_owned(3)
            .await
            .unwrap();
        let run = fixture_run("tour");
        let request = json!({"engine":"codex","context":{"files":[]}});
        let queued = run_prepared(
            &state,
            &run,
            &request,
            super::super::StructuredAnswerPreparation::Strict,
        );
        tokio::pin!(queued);
        assert!(futures_util::poll!(queued.as_mut()).is_pending());
        assert!(state.jobs.bridges.lock().unwrap().is_empty());
        run.cancel.send_replace(true);
        assert_eq!(queued.await.unwrap_err().to_string(), "Stopped.");
        assert!(state.jobs.bridges.lock().unwrap().is_empty());
        assert_eq!(state.jobs.provider_slots.available_permits(), 0);
        drop(occupied);
        let fresh = fixture_run("chat");
        let error = run_prepared(
            &state,
            &fresh,
            &request,
            super::super::StructuredAnswerPreparation::Strict,
        )
        .await
        .unwrap_err();
        assert!(error.to_string().contains("tool bridge is not running"));
        assert_eq!(state.jobs.provider_slots.available_permits(), 3);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test(start_paused = true)]
    async fn provider_queue_time_is_excluded_from_execution_deadline() {
        let jobs = Jobs::default();
        let occupied = jobs
            .provider_slots
            .clone()
            .acquire_many_owned(3)
            .await
            .unwrap();
        let run = fixture_run("chat");
        let started = Instant::now();
        let queued = async {
            let _permit = acquire_provider(&jobs, &run).await?;
            within_pi_deadline(Instant::now() + PI_TOTAL_LIMIT, async {
                tokio::time::sleep(Duration::from_secs(60)).await;
                Ok(())
            })
            .await
        };
        tokio::pin!(queued);
        assert!(futures_util::poll!(queued.as_mut()).is_pending());
        tokio::time::advance(PI_TOTAL_LIMIT * 2).await;
        assert!(futures_util::poll!(queued.as_mut()).is_pending());
        drop(occupied);
        queued.await.unwrap();
        assert_eq!(
            Instant::now() - started,
            PI_TOTAL_LIMIT * 2 + Duration::from_secs(60)
        );
        assert_eq!(jobs.provider_slots.available_permits(), 3);
    }
    #[tokio::test]
    async fn parallel_provider_invocations_keep_separate_activity_rows() {
        let run = Arc::new(Run {
            snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0}))
                .into(),
            publication: Default::default(),
            request: json!({}),
            task: false,
            notify: tokio::sync::Notify::new().into(),
            cancel: tokio::sync::watch::channel(false).0,
            login: crate::agent_login::AgentLogin::Server,
        });
        let first = Activity::new(&run);
        let second = Activity::new(&run);
        first.record("text:0", "text", "First", Some("one"), "running");
        second.patch(
            json!({"id":"text:0","kind":"text","title":"Second","text":"two","status":"running"}),
        );
        first.record("text:0", "text", "First", Some("one done"), "done");
        second.patch(json!({"id":"text:0","text":"two ongoing"}));
        super::super::record(&run, "task", "status", "Task", None, "running");
        let snapshot = run.snapshot.lock().unwrap();
        let rows = snapshot["activities"].as_array().unwrap();
        assert_eq!(rows.len(), 3);
        assert_ne!(rows[0]["id"], rows[1]["id"]);
        assert_eq!(rows[0]["text"], "one done");
        assert_eq!(rows[0]["status"], "done");
        assert_eq!(rows[1]["text"], "two ongoing");
        assert_eq!(rows[1]["status"], "running");
        assert_eq!(rows[2]["id"], "task");
    }
    #[tokio::test(start_paused = true)]
    async fn slow_catalog_refresh_does_not_block_another_engine() {
        let jobs = Jobs::default();
        jobs.catalogs
            .lock()
            .await
            .insert("pi".into(), (crate::now_ms(), json!({"models":["cached"]})));
        let entered = Arc::new(tokio::sync::Notify::new());
        let release = Arc::new(tokio::sync::Notify::new());
        let pending = tokio::spawn({
            let jobs = jobs.clone();
            let entered = entered.clone();
            let release = release.clone();
            async move {
                cached_catalog(&jobs, "codex", true, async {
                    entered.notify_one();
                    release.notified().await;
                    Ok(json!({"models":["codex"]}))
                })
                .await
            }
        });
        entered.notified().await;
        let cached = tokio::time::timeout(
            Duration::from_secs(1),
            cached_catalog(&jobs, "pi", false, async {
                bail!("cache should be reused")
            }),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(cached["models"], json!(["cached"]));
        release.notify_one();
        assert_eq!(pending.await.unwrap().unwrap()["models"], json!(["codex"]));
    }
    #[tokio::test(start_paused = true)]
    async fn pi_watchdog_bounds_multiple_turns_and_tool_work() {
        for tool_duration in [Duration::ZERO, Duration::from_secs(20 * 60)] {
            let started = Instant::now();
            let requests_finished = std::cell::Cell::new(0);
            let tools_finished = std::cell::Cell::new(0);
            let outcome = within_pi_deadline(started + PI_TOTAL_LIMIT, async {
                // Both 20-minute HTTP turns fit the former per-request budget.
                tokio::time::sleep(Duration::from_secs(20 * 60)).await;
                requests_finished.set(1);
                tokio::time::sleep(tool_duration).await;
                tools_finished.set(1);
                tokio::time::sleep(Duration::from_secs(20 * 60)).await;
                requests_finished.set(2);
                Ok(())
            })
            .await;
            assert_eq!(
                outcome.unwrap_err().to_string(),
                "Pi did not finish within 30 minutes"
            );
            assert_eq!(Instant::now() - started, PI_TOTAL_LIMIT);
            assert_eq!(requests_finished.get(), 1);
            assert_eq!(tools_finished.get(), usize::from(tool_duration.is_zero()));
        }
        assert!(within_pi_deadline(Instant::now() + PI_TOTAL_LIMIT, async {
            tokio::time::sleep(Duration::from_secs(60)).await;
            Ok(())
        })
        .await
        .is_ok());
    }
    #[test]
    fn model_versions_ignore_capacity_suffixes() {
        for _ in 0..2 {
            assert_eq!(model_version("GPT-5.5"), 5005);
            assert_eq!(model_version("GPT-OSS 120B"), -1);
            assert_eq!(model_version("MiMo 7.1 120B"), 7001);
            assert_eq!(model_version("Unknown"), -1);
        }
    }
    #[test]
    fn pi_names_routes_efforts_and_sorting_match_reference() {
        for (id, name) in [
            ("claude-3-7-sonnet-20250219", "Claude Sonnet 3.7"),
            ("claude-opus-5-5-high", "Claude Opus 5.5 (High)"),
            ("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)"),
            ("o3-mini-2025-01-31", "o3-mini"),
            ("agy-gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"),
            ("openrouter/deepseek-v3.1", "DeepSeek V3.1"),
            ("grok-4.20-0309-non-reasoning", "Grok 4.20 Non-Reasoning"),
        ] {
            assert_eq!(model_name(id), name);
        }
        let rows = pi_models(
            &json!({"data":[{"id":"gpt-5.5"},{"id":"claude-opus-4-8"},{"id":"gpt-6"},{"id":"claude-opus-5-5"},{"id":"gpt-6"},{"id":"gemini-3-pro"},{"id":"gpt-image-2"}]}),
            "gemini-3-pro",
        );
        assert_eq!(
            rows.iter().map(|v| text(v, "id")).collect::<Vec<_>>(),
            vec![
                "gemini-3-pro",
                "claude-opus-5-5",
                "claude-opus-4-8",
                "gpt-6",
                "gpt-5.5"
            ]
        );
        let rows = pi_models(
            &json!({"data":[{"id":"claude-claude-opus-5-5"},{"id":"claude-opus-5-5"},{"id":"agy-gemini-3.8-flash-high"}]}),
            "claude-opus-5-5",
        );
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1]["efforts"], json!(["high"]));
        assert_eq!(rows[1]["group"], "AGY");
    }
}
