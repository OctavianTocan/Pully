//! HTTP boundary: the native API remains network-authenticated, while browser-only
//! storage/proxy operations require the terminal-code session and a same-origin POST.
use crate::{required, text, AppState};
mod account;
#[path = "devframe.rs"]
mod devframe;
use anyhow::{bail, Context, Result};
use axum::{
    body::Body,
    extract::{DefaultBodyLimit, Request, State},
    http::{header, HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

#[derive(Clone)]
pub struct HttpState {
    pub app: Arc<AppState>,
    pub auth: Arc<Mutex<Auth>>,
    pub no_auth: bool,
    pub origins: HashSet<String>,
    pub hosts: HashSet<String>,
    pub assets: Option<PathBuf>,
    pub android: PathBuf,
}
pub struct Auth {
    token: String,
    dev_tokens: HashSet<String>,
    code: String,
    issued: Instant,
    attempts: u32,
    attempt_window: Instant,
}
impl Default for Auth {
    fn default() -> Self {
        let random = uuid::Uuid::new_v4();
        Self {
            token: format!(
                "{}{}",
                uuid::Uuid::new_v4().simple(),
                uuid::Uuid::new_v4().simple()
            ),
            dev_tokens: HashSet::new(),
            code: format!("{:06}", random.as_u128() % 1_000_000),
            issued: Instant::now(),
            attempts: 0,
            attempt_window: Instant::now(),
        }
    }
}
impl HttpState {
    pub fn new(
        app: Arc<AppState>,
        host: &str,
        port: u16,
        allow: Vec<String>,
        no_auth: bool,
        assets: Option<PathBuf>,
        android: PathBuf,
    ) -> Result<Self> {
        let mut origins = HashSet::new();
        let mut hosts = HashSet::from([
            "localhost".into(),
            "127.0.0.1".into(),
            "[::1]".into(),
            host.into(),
        ]);
        for origin in allow {
            let url = url::Url::parse(&origin).context("Invalid allowed origin")?;
            if !matches!(url.scheme(), "http" | "https")
                || url.host_str().is_none()
                || !url.username().is_empty()
                || url.password().is_some()
                || url.path() != "/"
                || url.query().is_some()
                || url.fragment().is_some()
            {
                bail!("Allowed origin must be an HTTP(S) origin")
            }
            hosts.insert(url.host_str().unwrap().into());
            origins.insert(url.origin().ascii_serialization());
        }
        for host in &hosts {
            origins.insert(format!("http://{host}:{port}"));
        }
        Ok(Self {
            app,
            auth: Arc::new(Mutex::new(Auth::default())),
            no_auth,
            origins,
            hosts,
            assets,
            android,
        })
    }
    pub fn print_code(&self) {
        if !self.no_auth {
            if let Ok(auth) = self.auth.lock() {
                eprintln!("Browser trust code: {} (valid for 10 minutes)", auth.code);
            }
        }
    }
}
fn same(a: &str, b: &str) -> bool {
    a.len() == b.len()
        && a.as_bytes()
            .iter()
            .zip(b.as_bytes())
            .fold(0u8, |n, (a, b)| n | (a ^ b))
            == 0
}
fn trusted(state: &HttpState, headers: &HeaderMap) -> bool {
    if state.no_auth {
        return true;
    }
    let Ok(auth) = state.auth.lock() else {
        return false;
    };
    headers
        .get(header::COOKIE)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|cookies| {
            cookies.split(';').any(|pair| {
                pair.trim()
                    .strip_prefix("pulls_session=")
                    .is_some_and(|v| same(v, &auth.token))
            })
        })
}
fn error(status: StatusCode, message: impl Into<String>) -> Response {
    (status, Json(json!({"error":message.into()}))).into_response()
}
/// Never expose credentials in errors returned by a provider, Git, or an adapter.
pub fn safe_error(error: &anyhow::Error) -> String {
    let mut message = error.to_string();
    for (name, value) in std::env::vars() {
        let name = name.to_ascii_uppercase();
        if value.len() >= 4
            && ["TOKEN", "KEY", "PASSWORD", "SECRET"]
                .iter()
                .any(|word| name.contains(word))
        {
            message = message.replace(&value, "[redacted]");
        }
    }
    message.chars().take(1200).collect()
}
async fn boundary(State(state): State<HttpState>, request: Request, next: Next) -> Response {
    // Claim agent paths before routing, Host checks or browser fallbacks.
    if let Some(path) = agent_path(request.uri().path()) {
        return crate::agent_api::handle(state.app.clone(), request, &path).await;
    }
    let host = request
        .headers()
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let hostname = if host.starts_with('[') {
        host.split(']')
            .next()
            .map(|s| format!("{s}]"))
            .unwrap_or_default()
    } else {
        host.split(':').next().unwrap_or("").to_string()
    };
    if !state.hosts.contains(&hostname) {
        return error(
            StatusCode::FORBIDDEN,
            "Unrecognized Host. Configure --allow-origin for this address.",
        );
    }
    if let Some(origin) = request.headers().get(header::ORIGIN) {
        if !origin
            .to_str()
            .ok()
            .is_some_and(|v| state.origins.contains(v))
        {
            return error(StatusCode::FORBIDDEN, "Origin is not allowed.");
        }
    }
    let mut response = next.run(request).await;
    response.headers_mut().insert(
        header::X_CONTENT_TYPE_OPTIONS,
        header::HeaderValue::from_static("nosniff"),
    );
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        header::HeaderValue::from_static("no-referrer"),
    );
    response
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Rpc {
    name: String,
    #[serde(default)]
    args: Value,
}
fn phone_allowed(name: &str) -> bool {
    // Mirrors native-api.ts and its native review handlers. Raw storage, credentials,
    // arbitrary prompts, and the GitHub proxy are deliberately not included.
    matches!(
        name,
        "analytics-summary"
            | "github-stats"
            | "pr-text"
            | "repo-info"
            | "source-key"
            | "source-fetch"
            | "source-fingerprint"
            | "source-load-file"
            | "my-pulls"
            | "my-pulls-cached"
            | "lens-list"
            | "lens-get"
            | "ai-models"
            | "ai-task-start"
            | "ai-task-wait"
            | "ai-task-cancel"
            | "ai-task-list"
            | "ai-task-list-all"
            | "ask-threads"
            | "ask-thread-close"
            | "inbox-chat"
            | "inbox-chat-clear"
            | "github-pr"
            | "github-compare"
            | "github-commit"
            | "local-diff"
            | "review-state"
            | "review-threads"
            | "review-reply"
            | "review-resolve"
            | "highlight"
            | "repo-viewed"
            | "mermaid-svg"
            | "review-comment"
            | "reviewed-get"
            | "reviewed-set"
            | "file-content"
            | "pr-overview"
            | "diff-patches"
            | "pr-stack"
            | "pr-action"
            | "review-data"
            | "review-submit"
            | "review-discard"
            | "review-edit"
            | "review-delete"
            | "critique-post"
            | "share-analysis"
            | "shared-list"
            | "shared-load"
            | "repo-pulls"
            | "chat-clear"
            | "chat-history"
            | "chat-restore"
            | "jev-files"
            | "recent-diffs"
    )
}
async fn native(
    State(state): State<HttpState>,
    headers: HeaderMap,
    Json(rpc): Json<Rpc>,
) -> Response {
    if let Some(response) = account::call(&state.app, &headers, &rpc.name, &rpc.args).await {
        return response;
    }
    if !phone_allowed(&rpc.name) {
        return error(
            StatusCode::NOT_FOUND,
            "That method is not available to the app.",
        );
    }
    if let Err(response) =
        account::authorize_posted(&state.app, &headers, &rpc.name, &rpc.args).await
    {
        return response;
    }
    let result = crate::dispatch(&state.app, &rpc.name, rpc.args).await;
    if rpc.name == "github-stats" {
        return match result { Ok(value) => reply(Ok(value)), Err(e) => error(StatusCode::BAD_GATEWAY, safe_error(&e)) };
    }
    reply(result.and_then(|value| account::filter_owned(&state.app, &headers, value)))
}
fn reply(result: Result<Value>) -> Response {
    match result {
        Ok(value) => Json(json!({"result":value})).into_response(),
        Err(e) => error(
            if e.to_string() == "That method is not available to the app." {
                StatusCode::NOT_FOUND
            } else {
                StatusCode::BAD_REQUEST
            },
            safe_error(&e),
        ),
    }
}
async fn web(State(state): State<HttpState>, headers: HeaderMap, Json(rpc): Json<Rpc>) -> Response {
    if !trusted(&state, &headers) {
        return error(
            StatusCode::UNAUTHORIZED,
            "Enter the server's terminal code first.",
        );
    }
    reply(web_dispatch(&state.app, &rpc.name, rpc.args).await)
}
async fn events(State(state): State<HttpState>, body: axum::body::Bytes) -> Response {
    if body.len() > 2 * 1024 * 1024 {
        return error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "Analytics batch is too large.",
        );
    }
    if let Ok(value) = serde_json::from_slice::<Value>(&body) {
        // Analytics failure must not interrupt the client workflow, and never echoes data.
        if state.app.analytics.ingest(value).await.is_err() {
            eprintln!("Usage statistics could not be stored.");
        }
    }
    StatusCode::NO_CONTENT.into_response()
}
async fn session_status(State(state): State<HttpState>, headers: HeaderMap) -> Json<Value> {
    Json(json!({"trusted":trusted(&state,&headers)}))
}
async fn auth_code(State(state): State<HttpState>) -> Response {
    let Ok(mut auth) = state.auth.lock() else {
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Authentication is unavailable.",
        );
    };
    if auth.issued.elapsed() > Duration::from_secs(600) {
        let new = Auth::default();
        auth.code = new.code;
        auth.issued = Instant::now();
    }
    eprintln!("Browser trust code: {}", auth.code);
    Json(json!({"ok":true})).into_response()
}
async fn login(State(state): State<HttpState>, Json(body): Json<Value>) -> Response {
    let Ok(mut auth) = state.auth.lock() else {
        return error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Authentication is unavailable.",
        );
    };
    if auth.attempt_window.elapsed() > Duration::from_secs(60) {
        auth.attempts = 0;
        auth.attempt_window = Instant::now();
    }
    auth.attempts += 1;
    if auth.attempts > 10 {
        return error(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many attempts. Try again in a minute.",
        );
    }
    if auth.issued.elapsed() > Duration::from_secs(600) || !same(text(&body, "code"), &auth.code) {
        return error(StatusCode::UNAUTHORIZED, "Invalid or expired code.");
    }
    let cookie = format!(
        "pulls_session={}; HttpOnly; SameSite=Strict; Path=/",
        auth.token
    );
    (
        [(header::SET_COOKIE, cookie)],
        Json(json!({"trusted":true})),
    )
        .into_response()
}
pub fn router(state: HttpState) -> Router {
    let routes = Router::new()
        .route(
            "/api/backend",
            get(|| async { Json(json!({"backend":"rust","version":env!("CARGO_PKG_VERSION")})) }),
        )
        .route(
            "/api/events",
            post(events).layer(DefaultBodyLimit::max(2 * 1024 * 1024)),
        )
        .route("/api/rpc", post(native))
        .route("/api/web-rpc", post(web))
        .route("/__connection.json", get(devframe::metadata))
        .route("/__ws", get(devframe::connect))
        .route("/api/session", get(session_status).post(login))
        .route("/api/auth-code", post(auth_code))
        .route("/health", get(|| async { Json(json!({"ok":true})) }))
        .fallback(get(static_file));
    Router::new()
        .merge(routes.clone())
        .nest("/__pulls.review", routes)
        .layer(DefaultBodyLimit::max(4 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(state.clone(), boundary))
        .with_state(state)
}
async fn read_static(root: &Path, relative: &str) -> Result<Option<(Vec<u8>, String)>> {
    let root = match tokio::fs::canonicalize(root).await {
        Ok(r) => r,
        Err(_) => return Ok(None),
    };
    let target = match tokio::fs::canonicalize(root.join(relative)).await {
        Ok(p) => p,
        Err(_) => return Ok(None),
    };
    if !target.starts_with(&root) {
        bail!("Invalid asset path")
    }
    if !tokio::fs::metadata(&target).await?.is_file() {
        return Ok(None);
    }
    let mime = match target.extension().and_then(|e| e.to_str()).unwrap_or("") {
        "html" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript",
        "css" => "text/css",
        "json" => "application/json",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "woff2" => "font/woff2",
        "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    };
    Ok(Some((tokio::fs::read(target).await?, mime.into())))
}
async fn static_file(
    State(state): State<HttpState>,
    uri: axum::http::Uri,
    headers: HeaderMap,
) -> Response {
    let path = uri.path();
    if path.starts_with("/-/") {
        return (
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            "Posted diffs open only in the Pully app.",
        )
            .into_response();
    }
    let android = path.starts_with("/android/");
    let result = if android {
        let relative = path.trim_start_matches("/android/");
        let allowed = matches!(relative, "latest.json" | "pulls.apk" | "past/index.json")
            || regex::Regex::new(r"^past/\d+\.\d+\.\d+\.apk$")
                .unwrap()
                .is_match(relative)
            || regex::Regex::new(r"^changelog/\d+\.\d+\.\d+/[a-z0-9-]+\.(png|webp|gif|jpg)$")
                .unwrap()
                .is_match(relative);
        if !allowed {
            return error(
                StatusCode::NOT_FOUND,
                "No Android build has been published.",
            );
        }
        read_static(&state.android, relative).await
    } else if let Some(root) = &state.assets {
        if path.starts_with("/api/") {
            return error(StatusCode::NOT_FOUND, "Unknown API route.");
        }
        let found = read_static(
            root,
            if path == "/" {
                "index.html"
            } else {
                path.trim_start_matches('/')
            },
        )
        .await;
        if matches!(&found, Ok(None))
            && headers
                .get(header::ACCEPT)
                .and_then(|h| h.to_str().ok())
                .is_some_and(|v| v.contains("text/html"))
        {
            read_static(root, "index.html").await
        } else {
            found
        }
    } else {
        return error(StatusCode::NOT_FOUND,"Web assets are not configured. Pass --assets pointing at the existing local web build.");
    };
    match result {
        Ok(Some((bytes, mime))) => {
            let mut response = Response::new(Body::from(bytes));
            response
                .headers_mut()
                .insert(header::CONTENT_TYPE, mime.parse().unwrap());
            if android {
                response.headers_mut().insert(
                    header::CACHE_CONTROL,
                    header::HeaderValue::from_static("no-store"),
                );
            }
            response
        }
        Ok(None) if path == "/android/past/index.json" => (
            [(header::CACHE_CONTROL, "no-store")],
            Json(json!({"versions":[]})),
        )
            .into_response(),
        Ok(None) => error(StatusCode::NOT_FOUND, "File not found."),
        Err(_) => error(StatusCode::BAD_REQUEST, "Invalid asset path."),
    }
}

async fn web_only(state: &AppState, name: &str, args: &Value) -> Option<Result<Value>> {
    if !matches!(name, "github-authenticated" | "github-request") && !name.starts_with("storage-") {
        return None;
    }
    let args = match crate::rpc_validation::arguments(name, args.clone()) {
        Ok(args) => args,
        Err(error) => return Some(Err(error)),
    };
    web_only_inner(state, name, &args)
        .await
        .map(|result| result.and_then(|value| crate::rpc_validation::result(name, value)))
}
async fn web_only_inner(state: &AppState, name: &str, args: &Value) -> Option<Result<Value>> {
    if name == "github-authenticated" {
        return Some(Ok(json!(crate::source::github_token().await.is_some())));
    }
    if name == "github-request" {
        return Some(github_proxy(state, args).await);
    }
    if !name.starts_with("storage-") {
        return None;
    }
    Some((|| {
        let parse = |v: &Value| -> Result<Option<Value>> {
            if v.is_null() {
                Ok(None)
            } else {
                Ok(Some(serde_json::from_str(
                    v.as_str().context("Expected serialized JSON")?,
                )?))
            }
        };
        let serial = |value: Option<Value>| -> Result<Value> {
            Ok(match value {
                Some(v) => Value::String(serde_json::to_string(&v)?),
                None => Value::Null,
            })
        };
        match name {
            "storage-get-item" => serial(public_get(state, required(args, "key")?)?),
            "storage-get-keys" => Ok(json!(state
                .store
                .keys(text(args, "base"))?
                .into_iter()
                .filter(|k| !private_key(k))
                .collect::<Vec<_>>())),
            "storage-get-items" => Ok(Value::Array(
                args["keys"]
                    .as_array()
                    .context("keys must be an array")?
                    .iter()
                    .map(|k| serial(public_get(state, k.as_str().context("Invalid key")?)?))
                    .collect::<Result<Vec<_>>>()?,
            )),
            "storage-set-item" | "storage-remove-item" | "storage-set-items" => {
                let items = if name == "storage-set-items" {
                    args["items"]
                        .as_array()
                        .context("items must be an array")?
                        .clone()
                } else {
                    vec![args.clone()]
                };
                if items.len() > 200 {
                    bail!("Too many storage writes")
                }
                let mut changes = Vec::new();
                for item in items {
                    let key = required(&item, "key")?.replace(['/', '\\'], ":");
                    if private_key(&key) {
                        bail!("That key is not the page's to change.");
                    }
                    if key.starts_with("pr-meta:")
                        || key.starts_with("pr-body:")
                        || key.starts_with("diff:")
                    {
                        bail!("Use the server diff cache operations to change review data.")
                    }
                    let expected = parse(item.get("expected").context("expected is required")?)?;
                    let next = if name == "storage-remove-item" {
                        None
                    } else {
                        parse(item.get("value").context("value is required")?)?
                    };
                    changes.push((key, expected, next));
                }
                state.store.compare_exchange(&changes)?;
                Ok(Value::Null)
            }
            _ => bail!("That method is not available to the app."),
        }
    })())
}
async fn github_proxy(state: &AppState, args: &Value) -> Result<Value> {
    let path = required(args, "path")?;
    let base = url::Url::parse("https://api.github.com")?;
    let url = base.join(path)?;
    if url.origin() != base.origin()
        || !path.starts_with('/')
        || path.starts_with("//")
        || !(path.starts_with("/repos/")
            || path == "/user"
            || path.starts_with("/user/")
            || path.starts_with("/user?")
            || path.starts_with("/search/")
            || path == "/graphql")
    {
        bail!("Unsupported GitHub API path")
    }
    let method = match text(args, "method") {
        "" | "GET" => reqwest::Method::GET,
        "POST" => reqwest::Method::POST,
        "PATCH" => reqwest::Method::PATCH,
        "PUT" => reqwest::Method::PUT,
        "DELETE" => reqwest::Method::DELETE,
        _ => bail!("Unsupported HTTP method"),
    };
    let accept = match text(args, "accept") {
        "" => "application/vnd.github+json",
        v @ ("application/vnd.github+json"
        | "application/vnd.github.raw"
        | "application/vnd.github.diff"
        | "application/vnd.github.sha") => v,
        _ => bail!("Unsupported response type"),
    };
    let body = args.get("body").filter(|body| !body.is_null()).cloned();
    let (status, headers, body) =
        crate::source::github_raw(state, method, path, body, accept).await?;
    Ok(json!({"status":status,"headers":headers,"body":body}))
}

/// Dedicated capability endpoint: no general RPC or storage methods are exposed to containers.
pub async fn start_agent_bridge(app: Arc<AppState>) -> Result<tokio::task::JoinHandle<()>> {
    async fn call(
        State(app): State<Arc<AppState>>,
        headers: HeaderMap,
        Json(request): Json<Value>,
    ) -> Response {
        let token = headers
            .get(header::AUTHORIZATION)
            .and_then(|h| h.to_str().ok())
            .and_then(|h| h.strip_prefix("Bearer "))
            .unwrap_or("");
        match crate::ai::bridge_mcp(&app, token, request).await {
            Some(value) => Json(value).into_response(),
            None => error(StatusCode::UNAUTHORIZED, "Agent capability is unavailable."),
        }
    }
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", 0))
        .await
        .context("Cannot bind agent bridge")?;
    crate::ai::configure_bridge(
        &app,
        format!(
            "http://host.docker.internal:{}/mcp",
            listener.local_addr()?.port()
        ),
    );
    let router = Router::new()
        .route("/mcp", post(call))
        .layer(DefaultBodyLimit::max(1024 * 1024))
        .with_state(app);
    Ok(tokio::spawn(async move {
        let _ = axum::serve(listener, router).await;
    }))
}

fn is_posted(key: &str) -> bool {
    key.to_ascii_lowercase().starts_with("posted:")
}
fn private_key(key: &str) -> bool {
    let key = key.replace(['/', '\\'], ":").to_ascii_lowercase();
    key.starts_with("account:")
        || key.starts_with("account-")
        || key.starts_with("agent-key:")
        || ["posted-diff:", "posted-agent:", "posted-patch:", "posted:"]
            .iter()
            .any(|p| key.starts_with(p))
        || key.contains(":posted:")
}
fn public_get(state: &AppState, key: &str) -> Result<Option<Value>> {
    if private_key(key) {
        Ok(None)
    } else {
        state.store.get(key)
    }
}
fn requested_key(app: &AppState, name: &str, args: &Value) -> Result<String> {
    let args = if args.is_array() { &args[0] } else { args };
    for field in ["key", "target"] {
        if is_posted(text(args, field)) {
            return Ok(text(args, field).into());
        }
    }
    if matches!(
        name,
        "ai-task-wait" | "ai-task-cancel" | "ai-job-wait" | "ai-job-cancel"
    ) {
        return crate::ai::task_key(app, text(args, "id"));
    }
    Ok(String::new())
}
fn filter_posted(value: Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .filter(|item| !is_posted(text(item, "key")))
                .collect(),
        ),
        other => other,
    }
}
async fn web_dispatch(app: &Arc<AppState>, name: &str, args: Value) -> Result<Value> {
    anyhow::ensure!(
        !is_posted(&requested_key(app, name, &args)?),
        "Posted diffs open only in the Pully app."
    );
    if let Some(result) = web_only(app, name, &args).await {
        return result;
    }
    crate::dispatch(app, name, args).await.map(filter_posted)
}

/// @cc [owner:OctavianTocan,label:security] rust-agent-paths-fail-closed
/// Encoded separators and dot segments, case variants and repeated slashes of the
/// agent API MUST reach key authentication before any browser route (Tavi, 16 Oct
/// 14:32; root agent-api-needs-a-key).
fn agent_path(raw: &str) -> Option<String> {
    let mut decoded = raw.to_owned();
    // Each pass removes at least one percent escape; bounded by the request length.
    for _ in 0..raw.len() {
        let bytes = decoded.as_bytes();
        let mut next = Vec::with_capacity(bytes.len());
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' && i + 2 < bytes.len() {
                if let (Some(a), Some(b)) = (
                    (bytes[i + 1] as char).to_digit(16),
                    (bytes[i + 2] as char).to_digit(16),
                ) {
                    next.push((a * 16 + b) as u8);
                    i += 3;
                    continue;
                }
            }
            next.push(bytes[i]);
            i += 1;
        }
        let next = String::from_utf8_lossy(&next).replace('\\', "/");
        if next == decoded {
            break;
        }
        decoded = next;
    }
    let decoded = decoded
        .strip_prefix("/__pulls.review/")
        .map(|p| format!("/{p}"))
        .unwrap_or(decoded);
    let mut parts = Vec::new();
    let mut claimed = false;
    for part in decoded.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            _ => parts.push(part),
        }
        if parts.len() >= 2
            && parts[0].eq_ignore_ascii_case("api")
            && parts[1].eq_ignore_ascii_case("v1")
        {
            claimed = true;
        }
    }
    if !claimed {
        return None;
    }
    if parts.len() < 2
        || !parts[0].eq_ignore_ascii_case("api")
        || !parts[1].eq_ignore_ascii_case("v1")
    {
        return Some("/api/v1/__invalid".into());
    }
    Some(format!(
        "/api/v1{}",
        if parts.len() > 2 {
            format!("/{}", parts[2..].join("/"))
        } else {
            String::new()
        }
    ))
}

#[cfg(test)]
mod privacy_tests {
    use super::*;
    #[test]
    fn claims_encoded_and_normalized_agent_paths() {
        for path in [
            "/API/v1",
            "//api//v1/me",
            "/%61pi%2fv1/diffs",
            "/api%252fv1/me",
            "/api/./v1",
            "/x/../api/v1",
            "/api/v1/../web-rpc",
            "/api%5cv1",
            "/__pulls.review/api/v1/me",
        ] {
            assert!(agent_path(path).is_some(), "{path}");
        }
        for path in ["/api/v10", "/api/rpc", "/health"] {
            assert!(agent_path(path).is_none());
        }
    }
    #[test]
    fn private_keys_include_normalized_storage_aliases() {
        for key in [
            "account:abc",
            "ACCOUNT-email:a",
            "agent-key:hash",
            "posted-patch:id:0",
            "pr-meta:posted:abc",
            "tour/posted/abc",
            "diff:POSTED:abc",
        ] {
            assert!(private_key(key), "{key}");
        }
        assert!(!private_key("diff:github:owner:repo:1"));
    }
    #[tokio::test]
    async fn web_masks_private_storage_and_rejects_posted_targets() {
        let root = std::env::temp_dir().join(format!("pulls-transport-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        app.store
            .set("account:abc", &json!({"password":"hashed"}))
            .unwrap();
        app.store
            .set("tour:posted:abc", &json!({"text":"private"}))
            .unwrap();
        app.store.set("shared", &json!("public")).unwrap();
        assert_eq!(
            web_dispatch(&app, "storage-get-item", json!({"key":"account:abc"}))
                .await
                .unwrap(),
            Value::Null
        );
        assert_eq!(
            web_dispatch(&app, "storage-get-keys", json!({"base":""}))
                .await
                .unwrap(),
            json!(["shared"])
        );
        assert!(web_dispatch(
            &app,
            "storage-set-item",
            json!({"key":"account:abc","value":"null","expected":null})
        )
        .await
        .is_err());
        assert!(
            web_dispatch(&app, "review-state", json!({"key":"posted:abc"}))
                .await
                .is_err()
        );
        assert!(
            web_dispatch(&app, "source-fetch", json!({"target":"POSTED:abc"}))
                .await
                .is_err()
        );
        assert!(account::authorize_posted(
            &app,
            &HeaderMap::new(),
            "review-state",
            &json!({"key":"posted:abc"})
        )
        .await
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn unauthenticated_agent_variants_never_fall_through() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let root = std::env::temp_dir().join(format!("pulls-http-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let state =
            HttpState::new(app, "127.0.0.1", port, vec![], true, None, root.clone()).unwrap();
        let server =
            tokio::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
        for path in [
            "/api/v1",
            "/API/v1/nope",
            "//api//v1/me",
            "/api%2fv1/diffs",
            "/api/./v1",
            "/api/v1/../web-rpc",
            "/__pulls.review/api/v1/me",
        ] {
            let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
                .await
                .unwrap();
            stream.write_all(format!("POST {path} HTTP/1.1\r\nHost: untrusted.example\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").as_bytes()).await.unwrap();
            let mut answer = String::new();
            stream.read_to_string(&mut answer).await.unwrap();
            assert!(answer.starts_with("HTTP/1.1 401"), "{path}: {answer}");
        }
        server.abort();
        std::fs::remove_dir_all(root).unwrap();
    }
}
