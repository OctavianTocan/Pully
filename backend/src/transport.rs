//! HTTP boundary: the native API remains network-authenticated, while browser-only
//! storage/proxy operations require the terminal-code session and a same-origin POST.
use crate::{required, text, AppState};
pub(crate) mod account;
mod downloads;
#[cfg(not(target_arch = "wasm32"))]
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
    time::Duration,
};
use web_time::Instant;

#[derive(Clone)]
pub struct HttpState {
    pub app: Arc<AppState>,
    pub auth: Arc<Mutex<Auth>>,
    pub no_auth: bool,
    pub origins: HashSet<String>,
    pub hosts: HashSet<String>,
    pub assets: Option<PathBuf>,
    update_token: Option<String>,
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
    ) -> Result<Self> {
        let mut origins = HashSet::new();
        let mut hosts = HashSet::from([
            "localhost".into(),
            "127.0.0.1".into(),
            "[::1]".into(),
            if let Ok(address) = host.parse::<std::net::Ipv6Addr>() {
                format!("[{address}]")
            } else {
                host.into()
            },
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
            origins.insert(http_origin(host, port));
        }
        Ok(Self {
            app,
            auth: Arc::new(Mutex::new(Auth::default())),
            no_auth,
            origins,
            hosts,
            assets,
            update_token: None,
        })
    }
    pub fn print_code(&self) {
        if !self.no_auth {
            if let Ok(auth) = self.auth.lock() {
                eprintln!("Browser trust code: {} (valid for 10 minutes)", auth.code);
            }
        }
    }
    pub fn with_update_token(mut self, token: String) -> Self {
        self.update_token = Some(token);
        self
    }
}

/// @cc [owner:OctavianTocan,label:security] deployment-control-is-operator-only
/// Idle-update control MUST require its own private token even when browser auth
/// is disabled. The token MUST rotate at startup, be written with owner-only
/// permissions and MUST NOT appear in process arguments or logs. Source: Tavi,
/// 21 Oct 2026, "Resolve all reviews and merge those PRs", safe-deployment review.
pub fn create_update_token(path: &Path) -> Result<String> {
    use std::io::Write;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
    let temporary = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4().simple()));
    let result = (|| -> Result<()> {
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        file.write_all(token.as_bytes())?;
        file.sync_all()?;
        std::fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result?;
    Ok(token)
}

async fn update_restart(State(state): State<HttpState>, headers: HeaderMap) -> Response {
    let authorized = state.update_token.as_deref().is_some_and(|token| {
        headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer ")).is_some_and(|supplied| same(token, supplied))
    });
    if !authorized {
        return error(StatusCode::UNAUTHORIZED, "Update authorization is required.");
    }
    if !crate::ai::begin_idle_update_shutdown(&state.app) {
        return error(StatusCode::CONFLICT, "AI work is active; retry the update later.");
    }
    (StatusCode::ACCEPTED, Json(json!({"ready":true}))).into_response()
}
/// Serialize the origin like a browser: bracket IPv6 and omit HTTP's default port.
pub fn http_origin(host: &str, port: u16) -> String {
    let host = if let Ok(address) = host.parse::<std::net::Ipv6Addr>() {
        format!("[{address}]")
    } else {
        host.to_owned()
    };
    if port == 80 {
        format!("http://{host}")
    } else {
        format!("http://{host}:{port}")
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
#[derive(Clone)]
struct ErrorMessage(String);
fn error(status: StatusCode, message: impl Into<String>) -> Response {
    let message = message.into();
    let mut response = (status, Json(json!({"error":message}))).into_response();
    // Retain only the already client-visible error for the sanitized telemetry
    // boundary; never consume or rebuild the outgoing response body.
    response.extensions_mut().insert(ErrorMessage(message));
    response
}
/// Never expose credentials in errors returned by a provider, Git, or an adapter.
pub fn safe_error(error: &anyhow::Error) -> String {
    let mut message = error.to_string();
    for (name, value) in crate::rt::env::vars() {
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
    // Authenticate large browser requests before JSON extraction allocates their body.
    if matches!(
        request.uri().path(),
        "/api/web-rpc" | "/__pulls.review/api/web-rpc"
    ) && !trusted(&state, request.headers())
    {
        return error(
            StatusCode::UNAUTHORIZED,
            "Enter the server's terminal code first.",
        );
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
pub(crate) fn phone_allowed(name: &str) -> bool {
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
            | "sync-wait"
    )
}
async fn native(
    State(state): State<HttpState>,
    headers: HeaderMap,
    Json(rpc): Json<Rpc>,
) -> Response {
    let name = rpc.name.clone();
    let response = native_response(&state, &headers, rpc).await;
    if !response.status().is_success() {
        let message = response
            .extensions()
            .get::<ErrorMessage>()
            .map(|m| m.0.as_str());
        let _ = state
            .app
            .analytics
            .record(
                "error.server",
                json!({
                    "route": "rpc", "method": name.chars().take(64).collect::<String>(),
                    "status": response.status().as_u16(), "message": message,
                }),
            )
            .await;
    }
    response
}
async fn native_response(state: &HttpState, headers: &HeaderMap, rpc: Rpc) -> Response {
    if let Some(response) = account::call(&state.app, headers, &rpc.name, &rpc.args).await {
        return response;
    }
    if let Some(response) =
        account::agent_login_call(&state.app, headers, &rpc.name, &rpc.args).await
    {
        return response;
    }
    if !phone_allowed(&rpc.name) {
        return error(
            StatusCode::NOT_FOUND,
            "That method is not available to the app.",
        );
    }
    if rpc.name == "local-diff" && state.app.host.checkout().is_err() {
        return error(StatusCode::NOT_FOUND, crate::host::NO_CHECKOUT);
    }
    if let Err(response) =
        account::authorize_posted(&state.app, headers, &rpc.name, &rpc.args).await
    {
        return response;
    }
    // AI work and model lists use the caller's own Claude Code and Codex sign-ins.
    let login = if matches!(rpc.name.as_str(), "ai-task-start" | "ai-models") {
        match account::agent_login(&state.app, headers).await {
            Ok(login) => login,
            Err(response) => return response,
        }
    } else {
        crate::agent_login::AgentLogin::Server
    };
    let result = crate::dispatch_as(&state.app, &rpc.name, rpc.args, &login).await;
    if rpc.name == "github-stats" {
        return match result {
            Ok(value) => reply(Ok(value)),
            Err(e) => error(StatusCode::BAD_GATEWAY, safe_error(&e)),
        };
    }
    reply(match result {
        Ok(value) => account::filter_owned(&state.app, headers, value).await,
        Err(e) => Err(e),
    })
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
// A 32 MiB source patch can grow through JSON escaping and structured-clone framing.
// Only authenticated browser RPCs need this budget; other HTTP endpoints stay small.
const MAX_CACHE_RPC_BYTES: usize = 256 * 1024 * 1024;

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
        .route("/api/update/restart", post(update_restart))
        .route(
            "/api/web-rpc",
            post(web).layer(DefaultBodyLimit::max(MAX_CACHE_RPC_BYTES)),
        )
        .route("/api/session", get(session_status).post(login))
        .route("/api/auth-code", post(auth_code))
        .route("/health", get(|| async { Json(json!({"ok":true})) }))
        .fallback(get(static_file));
    // The Worker has no development tools to connect to.
    #[cfg(not(target_arch = "wasm32"))]
    let routes = routes
        .route("/__connection.json", get(devframe::metadata))
        .route("/__ws", get(devframe::connect));
    Router::new()
        .merge(routes.clone())
        .nest("/__pulls.review", routes)
        .layer(DefaultBodyLimit::max(4 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(state.clone(), boundary))
        .with_state(state)
}
async fn read_static(root: &Path, relative: &str) -> Result<Option<(Vec<u8>, String)>> {
    let root = match crate::rt::fs::canonicalize(root).await {
        Ok(r) => r,
        Err(_) => return Ok(None),
    };
    let target = match crate::rt::fs::canonicalize(root.join(relative)).await {
        Ok(p) => p,
        Err(_) => return Ok(None),
    };
    if !target.starts_with(&root) {
        bail!("Invalid asset path")
    }
    if !crate::rt::fs::metadata(&target).await?.is_file() {
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
    Ok(Some((crate::rt::fs::read(target).await?, mime.into())))
}
async fn static_file(
    State(state): State<HttpState>,
    uri: axum::http::Uri,
    headers: HeaderMap,
) -> Response {
    let path = uri.path();
    if path.starts_with("/-/") && state.assets.is_none() {
        return (
            [(header::CONTENT_TYPE, "text/plain; charset=utf-8")],
            "Posted diffs open only in the Pully app.",
        )
            .into_response();
    }
    if path == "/download" || path.starts_with("/download/") || path.starts_with("/android/") {
        return downloads::response(&state.app.host, path).await;
    }
    let result = if let Some(root) = &state.assets {
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
            response
        }
        Ok(None) => error(StatusCode::NOT_FOUND, "File not found."),
        Err(_) => error(StatusCode::BAD_REQUEST, "Invalid asset path."),
    }
}

async fn android_file_response(host: &crate::host::ServerHost, relative: &str) -> Response {
    match host.android_file(relative).await {
        Ok(Some(file)) => {
            let mime = if relative.ends_with(".json") {
                "application/json"
            } else if relative.ends_with(".apk") {
                "application/vnd.android.package-archive"
            } else if relative.ends_with(".png") {
                "image/png"
            } else if relative.ends_with(".webp") {
                "image/webp"
            } else if relative.ends_with(".gif") {
                "image/gif"
            } else {
                "image/jpeg"
            };
            let mut response = Response::new(Body::from_stream(file.body));
            response
                .headers_mut()
                .insert(header::CONTENT_TYPE, mime.parse().unwrap());
            response
                .headers_mut()
                .insert(header::CONTENT_LENGTH, file.size.into());
            response.headers_mut().insert(
                header::CACHE_CONTROL,
                header::HeaderValue::from_static("no-store"),
            );
            response
        }
        Ok(None) if matches!(relative, "past/index.json" | "staging/past/index.json") => (
            [(header::CACHE_CONTROL, "no-store")],
            Json(json!({"versions":[]})),
        )
            .into_response(),
        Ok(None) => error(StatusCode::NOT_FOUND, "File not found."),
        Err(_) => error(StatusCode::BAD_REQUEST, "Invalid asset path."),
    }
}

async fn web_only(state: &AppState, name: &str, args: &Value) -> Option<Result<Value>> {
    if !matches!(
        name,
        "github-authenticated" | "github-request" | "cache-file-content"
    ) && !name.starts_with("storage-")
    {
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
/// @cc [owner:OctavianTocan,label:architecture;security] rust-web-fullfile-cache-shares-native-transactions
/// Browser full-file cache operations MUST use the same native transactions as source
/// loads, preserving body and metadata together; posted scopes MUST remain inaccessible
/// (Rust parity review, 16 Oct 2026, round 12 finding 2).
async fn web_only_inner(state: &AppState, name: &str, args: &Value) -> Option<Result<Value>> {
    if name == "github-authenticated" {
        return Some(crate::source::github_token(state).await.map(|token| json!(token.is_some())));
    }
    if name == "github-request" {
        return Some(crate::source::github_proxy(state, args).await);
    }
    if name == "cache-file-content" {
        return Some(
            async {
                let scope = required(args, "scope")?;
                anyhow::ensure!(
                    !is_posted(scope) && !private_key(scope),
                    "Posted diffs open only in the Pully app."
                );
                let store = &state.host.storage;
                match text(args, "method") {
                    "get" => Ok(json!(
                        crate::source::content_cache::get_async(
                            store,
                            scope,
                            required(args, "sha")?,
                            required(args, "path")?
                        )
                        .await?
                    )),
                    "set" => {
                        crate::source::content_cache::set_async(
                            store,
                            scope,
                            required(args, "sha")?,
                            required(args, "path")?,
                            args["content"].as_str().context("content is required")?,
                        )
                        .await?;
                        Ok(Value::Null)
                    }
                    "removeScope" => {
                        crate::source::content_cache::remove_scope_async(store, scope).await?;
                        Ok(Value::Null)
                    }
                    _ => bail!("Unknown file content cache operation."),
                }
            }
            .await,
        );
    }
    if !name.starts_with("storage-") {
        return None;
    }
    let store = state.host.storage.clone();
    let (name, args) = (name.to_owned(), args.clone());
    Some(
        crate::store::blocking(move || {
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
            match name.as_str() {
                "storage-get-item" => serial(public_get(&store, required(&args, "key")?)?),
                "storage-get-keys" => Ok(json!(store
                    .keys(text(&args, "base"))?
                    .into_iter()
                    .filter(|k| !private_key(k))
                    .collect::<Vec<_>>())),
                "storage-get-items" => Ok(Value::Array(
                    args["keys"]
                        .as_array()
                        .context("keys must be an array")?
                        .iter()
                        .map(|k| serial(public_get(&store, k.as_str().context("Invalid key")?)?))
                        .collect::<Result<Vec<_>>>()?,
                )),
                "storage-set-item" | "storage-remove-item" | "storage-set-items" => {
                    let items = if name == "storage-set-items" {
                        args["items"]
                            .as_array()
                            .context("items must be an array")?
                            .as_slice()
                    } else {
                        std::slice::from_ref(&args)
                    };
                    if items.len() > 200 {
                        bail!("Too many storage writes")
                    }
                    let mut changes = Vec::new();
                    for item in items {
                        let key = required(item, "key")?.replace(['/', '\\'], ":");
                        if private_key(&key) {
                            bail!("That key is not the page's to change.");
                        }
                        if key.starts_with("pr-meta:")
                            || key.starts_with("pr-body:")
                            || key.starts_with("diff:")
                        {
                            bail!("Use the server diff cache operations to change review data.")
                        }
                        let expected =
                            parse(item.get("expected").context("expected is required")?)?;
                        let next = if name == "storage-remove-item" {
                            None
                        } else {
                            parse(item.get("value").context("value is required")?)?
                        };
                        changes.push((key, expected, next));
                    }
                    store.compare_exchange(&changes)?;
                    Ok(Value::Null)
                }
                _ => bail!("That method is not available to the app."),
            }
        })
        .await,
    )
}

#[cfg(not(target_arch = "wasm32"))]
/// Dedicated capability endpoint: no general RPC or storage methods are exposed to containers.
pub async fn start_agent_bridge(app: Arc<AppState>) -> Result<crate::rt::JoinHandle<()>> {
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
    Ok(crate::rt::spawn(async move {
        let _ = axum::serve(listener, router).await;
    }))
}

/// @cc [owner:OctavianTocan,label:security] posted-authorization-canonicalizes-storage-aliases
/// Posted access checks MUST recognize the same slash/backslash aliases as Store;
/// authorization MUST resolve their canonical ID before access (16 Oct 2026 review fixes).
fn is_posted(key: &str) -> bool {
    key.replace(['/', '\\'], ":")
        .to_ascii_lowercase()
        .starts_with("posted:")
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
fn public_get(store: &crate::store::Store, key: &str) -> Result<Option<Value>> {
    if private_key(key) {
        Ok(None)
    } else {
        Ok(store
            .get(key)?
            .filter(|value| posted_reference(value).is_none()))
    }
}
fn posted_reference(value: &Value) -> Option<String> {
    for reference in [&value["diff"]["ref"], &value["ref"]] {
        if reference["kind"] == "posted" {
            return Some(format!("posted:{}", text(reference, "id")));
        }
    }
    None
}
/// @cc [owner:OctavianTocan,label:security] rust-task-ownership-follows-task-id
/// Wait and cancel authorization MUST resolve only the stored task's key and its
/// posted provenance, never caller-supplied key, target or values fields (root
/// phone-api-one-account; R15 finding 2, 16 Oct 2026).
async fn requested_key(app: &AppState, name: &str, args: &Value) -> Result<String> {
    let args = if args.is_array() { &args[0] } else { args };
    let keys: Vec<String> = if matches!(
        name,
        "ai-task-wait" | "ai-task-cancel" | "ai-job-wait" | "ai-job-cancel"
    ) {
        vec![crate::ai::task_key(app, text(args, "id"))?]
    } else {
        for field in ["key", "target"] {
            if is_posted(text(args, field)) {
                return Ok(text(args, field).replace(['/', '\\'], ":"));
            }
        }
        if let Some(scope) = posted_reference(&args["values"][0]) {
            return Ok(scope);
        }
        ["key", "target"]
            .into_iter()
            .map(|field| text(args, field))
            .filter(|key| !key.is_empty())
            .map(str::to_owned)
            .collect()
    };
    for key in keys {
        if is_posted(&key) {
            return Ok(key.replace(['/', '\\'], ":"));
        }
        // Authorization follows persisted provenance, including legacy malformed
        // aliases. Keep the complete storage read off the async runtime worker.
        if let Some(saved) = app.host.storage.get_async(&format!("diff:{key}")).await? {
            if let Some(scope) = posted_reference(&saved) {
                return Ok(scope);
            }
        }
    }
    Ok(String::new())
}
fn filter_posted(value: Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(
            items
                .into_iter()
                .filter(|item| !is_posted(text(item, "key")) && posted_reference(item).is_none())
                .collect(),
        ),
        other => other,
    }
}
async fn web_dispatch(app: &Arc<AppState>, name: &str, args: Value) -> Result<Value> {
    if let Some(result) = web_only(app, name, &args).await {
        return result;
    }
    anyhow::ensure!(
        !is_posted(&requested_key(app, name, &args).await?),
        "Posted diffs open only in the Pully app."
    );
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
    #[tokio::test]
    async fn update_shutdown_needs_its_private_token_even_without_browser_auth() {
        let root = std::env::temp_dir().join(format!("pulls-update-auth-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        let state = HttpState::new(app.clone(), "127.0.0.1", 7390, vec![], true, None).unwrap()
            .with_update_token("operator-only".into());
        for supplied in [None, Some("Bearer wrong")] {
            let mut headers = HeaderMap::new();
            if let Some(value) = supplied { headers.insert(header::AUTHORIZATION, value.parse().unwrap()); }
            assert_eq!(update_restart(State(state.clone()), headers).await.status(), StatusCode::UNAUTHORIZED);
            assert!(!app.jobs.stopping.load(std::sync::atomic::Ordering::SeqCst));
        }
        let mut headers = HeaderMap::new();
        headers.insert(header::AUTHORIZATION, "Bearer operator-only".parse().unwrap());
        let session = app.jobs.hold_session_for_test("update-fixture");
        let guard = session.lock().await;
        let id = crate::dispatch(&app, "ai-task-start", json!({"kind":"chat","key":"update-fixture","locale":"en","text":"test","model":{"engine":"pi"}})).await.unwrap();
        assert_eq!(update_restart(State(state.clone()), headers.clone()).await.status(), StatusCode::CONFLICT);
        assert!(!app.jobs.stopping.load(std::sync::atomic::Ordering::SeqCst));
        crate::dispatch(&app, "ai-task-cancel", json!({"id":id})).await.unwrap();
        drop(guard);
        crate::rt::timeout(Duration::from_secs(1), async {
            while update_restart(State(state.clone()), headers.clone()).await.status() == StatusCode::CONFLICT {
                crate::rt::yield_now().await;
            }
        }).await.unwrap();
        assert_eq!(update_restart(State(state), headers).await.status(), StatusCode::ACCEPTED);
        crate::rt::timeout(Duration::from_secs(1), crate::ai::wait_for_update_shutdown(&app)).await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn update_token_is_private_and_rotated_without_leaving_temporary_files() {
        let root = std::env::temp_dir().join(format!("pulls-update-token-{}", uuid::Uuid::new_v4()));
        let path = root.join("token");
        let first = create_update_token(&path).unwrap();
        assert_eq!(first.len(), 64);
        assert_eq!(std::fs::read_to_string(&path).unwrap(), first);
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        }
        assert_ne!(create_update_token(&path).unwrap(), first);
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn listener_origins_match_browser_serialization() {
        assert_eq!(http_origin("127.0.0.1", 7390), "http://127.0.0.1:7390");
        assert_eq!(http_origin("::1", 7390), "http://[::1]:7390");
        assert_eq!(http_origin("0:0:0:0:0:0:0:1", 7390), "http://[::1]:7390");
        assert_eq!(http_origin("[::1]", 80), "http://[::1]");
        let root = std::env::temp_dir().join(format!("pulls-origin-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(std::env::current_dir().unwrap(), root.clone()).unwrap();
        let state = HttpState::new(app, "2001:db8::1", 80, vec![], false, None).unwrap();
        assert!(state.hosts.contains("[2001:db8::1]"));
        for origin in ["http://[2001:db8::1]", "http://[::1]", "http://localhost"] {
            assert!(state.origins.contains(origin), "{origin}");
        }
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }

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
        app.host
            .storage
            .set("account:abc", &json!({"password":"hashed"}))
            .unwrap();
        app.host
            .storage
            .set("tour:posted:abc", &json!({"text":"private"}))
            .unwrap();
        app.host.storage.set("shared", &json!("public")).unwrap();
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
    async fn posted_aliases_require_ownership_and_never_reach_the_web() {
        let root = std::env::temp_dir().join(format!("pulls-alias-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        app.host
            .storage
            .set(
                "diff:posted:abc",
                &json!({"key":"posted:abc","diff":{"files":[]}}),
            )
            .unwrap();
        for key in ["posted:abc", "posted/abc", "posted\\abc"] {
            assert_eq!(
                requested_key(&app, "review-state", &json!({"key":key}))
                    .await
                    .unwrap(),
                "posted:abc"
            );
            for name in ["review-state", "diff-patches", "cache-diff"] {
                let args = json!({"key":key,"method":"get"});
                assert!(web_dispatch(&app, name, args.clone()).await.is_err());
                assert!(
                    account::authorize_posted(&app, &HeaderMap::new(), name, &args)
                        .await
                        .is_err()
                );
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn posted_task_ids_override_decoys_for_wait_cancel_and_job_aliases() {
        let root =
            std::env::temp_dir().join(format!("posted-task-owners-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        app.account_seed.set(true).unwrap();
        let mut sessions = Vec::new();
        for (account_id, posted_id) in [("caller", "ownref"), ("owner", "victim")] {
            app.host
                .storage
                .set(
                    &format!("account:{account_id}"),
                    &json!({
                        "id":account_id,"email":format!("{account_id}@example.invalid"),
                        "createdAt":"2026-10-16T00:00:00Z",
                        "password":{"salt":"","hash":"","iterations":1}
                    }),
                )
                .unwrap();
            let session = format!("{account_id}-session");
            app.host
                .storage
                .set(
                    &format!("account-session:{}", crate::account::sha256(&session)),
                    &json!({"accountId":account_id}),
                )
                .unwrap();
            app.host
                .storage
                .set(
                    &format!("posted-diff:{posted_id}"),
                    &json!({"id":posted_id,"accountId":account_id}),
                )
                .unwrap();
            let mut headers = HeaderMap::new();
            headers.insert("x-pully-session", session.parse().unwrap());
            sessions.push(headers);
        }
        let http = HttpState::new(app.clone(), "127.0.0.1", 1234, vec![], true, None).unwrap();
        let verify_ownership = async {
            // Both normalized key aliases and persisted public-key aliases must
            // authorize the task's actual posted provenance.
            for key in [
                "posted:victim",
                "posted/victim",
                "posted\\victim",
                "paste:alias",
            ] {
                app.host.storage.set(&format!("diff:{key}"), &json!({
                    "key":key,"diff":{"ref":{"kind":"posted","id":"victim"},"title":"Private","files":[]}
                })).unwrap();
                let held_session = app.jobs.hold_session_for_test(key).lock_owned().await;
                for (wait, cancel) in [
                    ("ai-task-wait", "ai-task-cancel"),
                    ("ai-job-wait", "ai-job-cancel"),
                ] {
                    // Hold the chat lock before admission so no provider runs.
                    let id = crate::dispatch(&app, "ai-task-start", json!({
                        "kind":"chat","key":key,"locale":"en","text":"Private question","model":{"engine":"pi"}
                    })).await.unwrap();
                    for decoy in [
                        json!({"key":"posted:ownref"}),
                        json!({"target":"posted/ownref"}),
                        json!({"values":[{"ref":{"kind":"posted","id":"ownref"}}]}),
                        json!({"values":[{"diff":{"ref":{"kind":"posted","id":"ownref"}}}]}),
                        json!({"key":"posted\\ownref","target":"posted:ownref","values":[{"ref":{"kind":"posted","id":"ownref"}}]}),
                    ] {
                        // Prove the decoy really is owned by the caller.
                        assert!(account::authorize_posted(
                            &app,
                            &sessions[0],
                            "review-state",
                            &decoy
                        )
                        .await
                        .is_ok());
                        let mut args = decoy;
                        args["id"] = id.clone();
                        args["after"] = json!(-1);
                        for name in [wait, cancel] {
                            assert_eq!(
                                requested_key(&app, name, &args).await.unwrap(),
                                "posted:victim",
                                "{name}: {key}"
                            );
                            assert_eq!(
                                account::authorize_posted(&app, &sessions[0], name, &args)
                                    .await
                                    .unwrap_err()
                                    .status(),
                                StatusCode::NOT_FOUND,
                                "{name}: {key}"
                            );
                            assert!(
                                web_dispatch(&app, name, args.clone()).await.is_err(),
                                "{name}: {key}"
                            );
                            if name.starts_with("ai-task-") {
                                let response = native(
                                    State(http.clone()),
                                    sessions[0].clone(),
                                    Json(Rpc {
                                        name: name.into(),
                                        args: args.clone(),
                                    }),
                                )
                                .await;
                                assert_eq!(
                                    response.status(),
                                    StatusCode::NOT_FOUND,
                                    "{name}: {key}"
                                );
                            }
                            // An owning account still succeeds even with the
                            // other account's decoy fields present.
                            assert!(account::authorize_posted(&app, &sessions[1], name, &args)
                                .await
                                .is_ok());
                        }
                        let snapshot = if wait == "ai-task-wait" {
                            let response = native(
                                State(http.clone()),
                                sessions[1].clone(),
                                Json(Rpc {
                                    name: wait.into(),
                                    args,
                                }),
                            )
                            .await;
                            assert_eq!(response.status(), StatusCode::OK);
                            let bytes = axum::body::to_bytes(response.into_body(), 16 * 1024)
                                .await
                                .unwrap();
                            serde_json::from_slice::<Value>(&bytes).unwrap()["result"].clone()
                        } else {
                            crate::dispatch(&app, wait, args).await.unwrap()
                        };
                        assert_eq!(snapshot["id"], id);
                        assert_eq!(
                            snapshot["status"], "running",
                            "denied cancellation changed the victim task"
                        );
                        assert_eq!(snapshot["text"], "Private question");
                    }
                    let args = json!({"id":id,"key":"posted:ownref"});
                    assert!(account::authorize_posted(&app, &sessions[1], cancel, &args)
                        .await
                        .is_ok());
                    if cancel == "ai-task-cancel" {
                        let response = native(
                            State(http.clone()),
                            sessions[1].clone(),
                            Json(Rpc {
                                name: cancel.into(),
                                args,
                            }),
                        )
                        .await;
                        assert_eq!(response.status(), StatusCode::OK);
                        let bytes = axum::body::to_bytes(response.into_body(), 1024)
                            .await
                            .unwrap();
                        assert_eq!(
                            serde_json::from_slice::<Value>(&bytes).unwrap(),
                            json!({"result":true})
                        );
                    } else {
                        assert_eq!(crate::dispatch(&app, cancel, args).await.unwrap(), true);
                    }
                    let snapshot = crate::dispatch(&app, wait, json!({"id":id,"after":-1}))
                        .await
                        .unwrap();
                    assert_eq!(snapshot["status"], "cancelled");
                }
                drop(held_session);
            }
            for name in [
                "ai-task-wait",
                "ai-task-cancel",
                "ai-job-wait",
                "ai-job-cancel",
            ] {
                assert!(
                    requested_key(&app, name, &json!({"id":"missing","key":"posted:ownref"}))
                        .await
                        .is_err()
                );
            }
            crate::ai::shutdown(&app).await;
        };
        crate::rt::timeout(Duration::from_secs(10), Box::pin(verify_ownership))
            .await
            .expect("bounded posted-task authorization fixture");
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn persisted_public_aliases_cannot_expose_private_posted_artifacts() {
        let root = std::env::temp_dir().join(format!("posted-provenance-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        app.account_seed.set(true).unwrap();
        let (_user, session, _) = app.accounts.fixture_for_test();
        let mut headers = HeaderMap::new();
        headers.insert("x-pully-session", session.parse().unwrap());
        let private = json!({"ref":{"kind":"posted","id":"victim"},"title":"Private","files":[]});
        assert!(web_dispatch(
            &app,
            "cache-diff",
            json!({"method":"putDiff","key":"paste:alias","values":[private,"",null]})
        )
        .await
        .is_err());
        // A malformed record from an earlier release must be blocked on reads too.
        app.host
            .storage
            .set(
                "diff:paste:alias",
                &json!({"key":"paste:alias","diff":private}),
            )
            .unwrap();
        app.host
            .storage
            .set(
                "posted-diff:victim",
                &json!({"id":"victim","accountId":"someone-else"}),
            )
            .unwrap();
        for prefix in ["tour", "brief", "critique"] {
            app.host.storage.set(&format!("{prefix}:posted:victim"), &json!({"entries":[{"secret":"private artifact","result":{"secret":"private review"}}]})).unwrap();
        }
        for name in ["review-state", "cache-diff", "diff-patches"] {
            let args = json!({"key":"paste:alias","method":"get"});
            assert!(web_dispatch(&app, name, args.clone()).await.is_err());
            assert_eq!(
                account::authorize_posted(&app, &HeaderMap::new(), name, &args)
                    .await
                    .unwrap_err()
                    .status(),
                StatusCode::UNAUTHORIZED
            );
            assert_eq!(
                account::authorize_posted(&app, &headers, name, &args)
                    .await
                    .unwrap_err()
                    .status(),
                StatusCode::NOT_FOUND
            );
        }
        assert_eq!(
            web_dispatch(&app, "storage-get-item", json!({"key":"diff:paste:alias"}))
                .await
                .unwrap(),
            Value::Null
        );
        assert_eq!(
            filter_posted(json!([{"key":"paste:alias","ref":{"kind":"posted","id":"victim"}}])),
            json!([])
        );
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn android_responses_use_the_configured_host() {
        let root = std::env::temp_dir().join(format!("pulls-artifacts-{}", uuid::Uuid::new_v4()));
        let files = root.join("artifacts");
        std::fs::create_dir_all(&files).unwrap();
        std::fs::write(files.join("pulls.apk"), b"apk-fixture").unwrap();
        let host = crate::host::ServerHost::new(
            crate::store::Store::new(root.join("data")).unwrap(),
            None,
            Some(files),
        );
        let state = HttpState::new(
            AppState::from_host(host).unwrap(),
            "127.0.0.1",
            1234,
            vec![],
            true,
            None,
        )
        .unwrap();
        let response = static_file(
            State(state.clone()),
            "/download/pully.apk".parse().unwrap(),
            HeaderMap::new(),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
        assert_eq!(
            response.headers()[header::CONTENT_TYPE],
            "application/vnd.android.package-archive"
        );
        assert_eq!(
            axum::body::to_bytes(response.into_body(), 100)
                .await
                .unwrap()
                .as_ref(),
            b"apk-fixture"
        );
        let empty = static_file(
            State(state.clone()),
            "/android/past/index.json".parse().unwrap(),
            HeaderMap::new(),
        )
        .await;
        assert_eq!(empty.status(), StatusCode::OK);
        let denied = static_file(
            State(state),
            "/android/private.txt".parse().unwrap(),
            HeaderMap::new(),
        )
        .await;
        assert_eq!(denied.status(), StatusCode::NOT_FOUND);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn unauthenticated_agent_variants_never_fall_through() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let root = std::env::temp_dir().join(format!("pulls-http-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = HttpState::new(app, "127.0.0.1", port, vec![], true, None).unwrap();
        let server =
            crate::rt::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
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

#[cfg(test)]
mod storage_contention_tests {
    use super::*;
    use crate::store::test_support::HeldLock;

    #[tokio::test(flavor = "current_thread")]
    async fn browser_storage_transactions_yield_and_preserve_cas() {
        let root =
            std::env::temp_dir().join(format!("web-storage-contention-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        for (name, args) in [
            (
                "storage-set-item",
                json!({"key":"review:test","value":"true","expected":null}),
            ),
            ("storage-get-item", json!({"key":"review:test"})),
            ("storage-get-items", json!({"keys":["review:test"]})),
            ("storage-get-keys", json!({"base":"review:"})),
            (
                "storage-remove-item",
                json!({"key":"review:test","expected":"true"}),
            ),
        ] {
            HeldLock::storage(&app.host.storage)
                .assert_yields(web_dispatch(&app, name, args))
                .await
                .unwrap();
        }
        assert!(web_dispatch(
            &app,
            "storage-set-item",
            json!({"key":"review:test","value":"true","expected":"true"})
        )
        .await
        .is_err());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test(flavor = "current_thread")]
    async fn browser_fullfile_cache_uses_native_transactions_and_blocks_posted_scopes() {
        let root =
            std::env::temp_dir().join(format!("web-fullfile-cache-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        let scope = "github:owner/repo#1";
        let request = |method| json!({"method":method,"scope":scope,"sha":"head","path":"a","content":"contents"});
        assert_eq!(
            web_dispatch(&app, "cache-file-content", request("get"))
                .await
                .unwrap(),
            Value::Null
        );
        crate::source::content_cache::set_async(&app.host.storage, scope, "head", "a", "contents")
            .await
            .unwrap();
        HeldLock::storage(&app.host.storage)
            .assert_yields(web_dispatch(&app, "cache-file-content", request("set")))
            .await
            .unwrap();
        for _ in 0..2 {
            assert_eq!(
                web_dispatch(&app, "cache-file-content", request("get"))
                    .await
                    .unwrap(),
                "contents"
            );
        }
        assert_eq!(app.host.storage.keys("file-content:").unwrap().len(), 1);
        assert_eq!(
            app.host.storage.keys("file-content-meta:").unwrap().len(),
            1
        );
        for scope in ["posted:secret", "POSTED/secret", "posted\\secret"] {
            for method in ["get", "set", "removeScope"] {
                let mut args = request(method);
                args["scope"] = json!(scope);
                assert!(web_dispatch(&app, "cache-file-content", args)
                    .await
                    .is_err());
            }
        }
        assert!(!phone_allowed("cache-file-content"));
        assert!(web_dispatch(
            &app,
            "cache-file-content",
            json!({"method":"set","scope":scope,"path":"a","sha":"head"})
        )
        .await
        .is_err());
        web_dispatch(&app, "cache-file-content", request("removeScope"))
            .await
            .unwrap();
        assert_eq!(
            web_dispatch(&app, "cache-file-content", request("get"))
                .await
                .unwrap(),
            Value::Null
        );
        std::fs::remove_dir_all(root).unwrap();
    }
}
