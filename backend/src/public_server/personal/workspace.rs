//! Full native workspace, admitted only by the canonical private personal-session vault.
//! The old typed routes retain their separate GET-only middleware and authority.
use super::{github::RepositoryRead, *};
use crate::{account::Account, agent_login::AgentLogin, transport};
use anyhow::ensure;
use axum::{body::Bytes, extract::DefaultBodyLimit, routing::post};
use futures_util::{stream, StreamExt, TryStreamExt};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const RPC: &str = "/workspace/api/rpc";
const MAX_BODY: usize = 4 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Rpc {
    name: String,
    #[serde(default)]
    args: Value,
}

pub(super) fn routes(inner: Arc<Inner>) -> Router {
    Router::new()
        .route(RPC, post(call))
        .route(
            "/workspace/api/events",
            post(events).layer(DefaultBodyLimit::max(2 * 1024 * 1024)),
        )
        .layer(DefaultBodyLimit::max(MAX_BODY))
        .layer(middleware::from_fn(workspace_boundary))
        .with_state(inner)
}

fn error(status: StatusCode, message: &str) -> Response {
    (status, Json(json!({"error":message}))).into_response()
}

// Preserve typed admission/read failures while retaining the native error-string envelope.
fn read_error(error_value: ReadError) -> Response {
    let (status, message) = match error_value.code {
        Code::Unauthorized | Code::Reconnect => (
            StatusCode::UNAUTHORIZED,
            "Your GitHub sign-in ended. Sign in again.",
        ),
        Code::Forbidden => (
            StatusCode::FORBIDDEN,
            "This GitHub repository is not available to this account.",
        ),
        Code::RateLimited => (
            StatusCode::TOO_MANY_REQUESTS,
            "GitHub reads are busy. Try again later.",
        ),
        Code::Unavailable => (
            StatusCode::SERVICE_UNAVAILABLE,
            "Your workspace is temporarily unavailable. Try again.",
        ),
        Code::InvalidResponse => (
            StatusCode::BAD_GATEWAY,
            "GitHub could not return this repository. Try again.",
        ),
        Code::StaleReview => (
            StatusCode::CONFLICT,
            "This pull request changed. Open it again to continue.",
        ),
        Code::InvalidRequest => (StatusCode::BAD_REQUEST, "Choose a valid GitHub repository."),
    };
    let mut response = error(status, message);
    if let Some(seconds) = error_value.retry {
        response.headers_mut().insert(
            header::RETRY_AFTER,
            HeaderValue::from_str(&seconds.to_string()).unwrap(),
        );
    }
    response
}

fn auth_error(error_value: AuthError) -> Response {
    read_error(ReadError::new(match error_value {
        AuthError::Unauthorized | AuthError::Expired => Code::Unauthorized,
        AuthError::Reconnect => Code::Reconnect,
        AuthError::Denied => Code::Forbidden,
        AuthError::RateLimited => Code::RateLimited,
        AuthError::InvalidRequest => Code::InvalidRequest,
        AuthError::Unavailable => Code::Unavailable,
    }))
}

async fn workspace_boundary(request: axum::extract::Request, next: Next) -> Response {
    // Access admits transport only. Cookies and legacy session headers cannot select an owner.
    let invalid = request.uri().query().is_some()
        || [
            "cookie",
            "x-pully-session",
            "x-api-key",
            "x-forwarded-authorization",
        ]
        .iter()
        .any(|name| request.headers().contains_key(*name));
    let mut response = if invalid {
        error(
            StatusCode::BAD_REQUEST,
            "Use your personal Pully session for this workspace.",
        )
    } else {
        next.run(request).await
    };
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response.headers_mut().insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    response.headers_mut().insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    response
}

fn allowed(name: &str) -> bool {
    transport::phone_allowed(name)
        || matches!(
            name,
            "account-status"
                | "account-me"
                | "account-sign-out"
                | "account-settings"
                | "account-settings-merge"
                | "agent-keys"
                | "agent-key-revoke"
                | "posted-list"
                | "posted-diff"
                | "posted-delete"
                | "feedback-send"
                | "feedback-list"
                | "ai-login-status"
                | "ai-login-start"
                | "ai-login-finish"
                | "ai-login-cancel"
                | "ai-login-sign-out"
        )
}

async fn workspace_context(
    inner: &Inner,
    headers: &HeaderMap,
) -> std::result::Result<(Context, Arc<AppState>), Response> {
    let bearer = personal_bearer(headers)
        .map_err(|_| error(StatusCode::UNAUTHORIZED, "Sign in to GitHub first."))?;
    let session = inner
        .auth
        .workspace_session(bearer)
        .await
        .map_err(auth_error)?;
    if session
        .status()
        .map_err(|_| auth_error(AuthError::Unauthorized))?
        .authority
        != GitHubAuthority::PrivateRepositories
    {
        return Err(error(
            StatusCode::FORBIDDEN,
            "Connect GitHub with repository access first.",
        ));
    }
    let context = inner
        .context(headers, ReadScope::Account)
        .await
        .map_err(read_error)?;
    let RequestIdentity::Account(credential) = context
        .session
        .identity()
        .map_err(|_| error(StatusCode::UNAUTHORIZED, "Sign in to GitHub first."))?
    else {
        return Err(error(StatusCode::UNAUTHORIZED, "Sign in to GitHub first."));
    };
    let state = inner
        .tenants
        .workspace(credential, inner.origin.clone())
        .await
        .map_err(|_| {
            error(
                StatusCode::SERVICE_UNAVAILABLE,
                "Your workspace is temporarily unavailable.",
            )
        })?;
    Ok((context, state))
}

async fn events(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, axum::extract::rejection::BytesRejection>,
) -> Response {
    let Ok(_permit) = inner.requests.clone().try_acquire_owned() else {
        return error(
            StatusCode::TOO_MANY_REQUESTS,
            "Your workspace is busy. Try again later.",
        );
    };
    let (context, state) = match workspace_context(&inner, &headers).await {
        Ok(context) => context,
        Err(response) => return response,
    };
    let body = match body {
        Ok(body) if body.len() <= 2 * 1024 * 1024 => body,
        _ => {
            return error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "Analytics batch is too large.",
            )
        }
    };
    let batch: Value = match serde_json::from_slice(&body) {
        Ok(batch) => batch,
        Err(_) => return error(StatusCode::BAD_REQUEST, "Analytics needs an events batch."),
    };
    if context.current().is_err() || state.identity.ensure_current().is_err() {
        return error(
            StatusCode::UNAUTHORIZED,
            "Your GitHub sign-in ended. Sign in again.",
        );
    }
    tokio::select! {
        biased;
        _ = state.identity.retired() => error(StatusCode::UNAUTHORIZED, "Your GitHub sign-in ended. Sign in again."),
        result = state.analytics.ingest(batch) => {
            if context.current().is_err() || state.identity.ensure_current().is_err() {
                error(StatusCode::UNAUTHORIZED, "Your GitHub sign-in ended. Sign in again.")
            } else if result.is_err() {
                error(StatusCode::SERVICE_UNAVAILABLE, "Usage statistics could not be stored.")
            } else {
                StatusCode::NO_CONTENT.into_response()
            }
        }
    }
}

async fn call(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, axum::extract::rejection::BytesRejection>,
) -> Response {
    let Ok(_permit) = inner.requests.clone().try_acquire_owned() else {
        return error(
            StatusCode::TOO_MANY_REQUESTS,
            "Your workspace is busy. Try again later.",
        );
    };
    let (context, state) = match workspace_context(&inner, &headers).await {
        Ok(context) => context,
        Err(response) => return response,
    };
    let bearer = personal_bearer(&headers).expect("resolved personal bearer");
    let body = match body {
        Ok(body) => body,
        Err(_) => {
            return error(
                StatusCode::PAYLOAD_TOO_LARGE,
                "Workspace request is too large.",
            )
        }
    };
    let rpc: Rpc = match serde_json::from_slice(&body) {
        Ok(rpc) => rpc,
        Err(_) => {
            return error(
                StatusCode::BAD_REQUEST,
                "That method received invalid arguments.",
            )
        }
    };
    if !allowed(&rpc.name) {
        return error(
            StatusCode::NOT_FOUND,
            "That method is not available to the app.",
        );
    }
    let status = match context.session.status() {
        Ok(status) => status,
        Err(_) => {
            return error(
                StatusCode::UNAUTHORIZED,
                "Your GitHub sign-in ended. Sign in again.",
            )
        }
    };
    let user = Account {
        id: status.account_id.clone(),
        email: status.login.clone(),
    };
    let login = AgentLogin::Account(user.id.clone());
    if rpc.name == "account-sign-out" {
        return match inner.auth.sign_out(bearer).await {
            Ok(()) => Json(json!({"result":true})).into_response(),
            Err(_) => error(StatusCode::UNAUTHORIZED, "Your GitHub sign-in ended."),
        };
    }
    let operation = async {
        context.current().map_err(anyhow::Error::from)?;
        state.accounts.personal_owner(user.clone()).await?;
        let result = match rpc.name.as_str() {
            "account-status" => json!({"hasAccount":true,"provider":"github"}),
            "account-me" => {
                json!({"id":user.id,"email":user.email,"githubLogin":user.email,"githubId":status.github_id,"provider":"github"})
            }
            name if name.starts_with("ai-login-") => {
                let response =
                    transport::account::agent_login_value(&state, &login, name, &rpc.args).await;
                let status = response.status();
                let bytes = axum::body::to_bytes(response.into_body(), 128 * 1024).await?;
                let value: Value = serde_json::from_slice(&bytes)?;
                if !status.is_success() {
                    anyhow::bail!("{}", value["error"].as_str().unwrap_or("AI sign-in failed"));
                }
                value["result"].clone()
            }
            name if matches!(
                name,
                "account-settings"
                    | "account-settings-merge"
                    | "agent-keys"
                    | "agent-key-revoke"
                    | "posted-list"
                    | "posted-diff"
                    | "posted-delete"
                    | "feedback-send"
                    | "feedback-list"
            ) =>
            {
                transport::account::owned_value(&state, &user, name, &rpc.args).await?
            }
            name => {
                // This preflight happens before a cached title, diff, file or saved AI result
                // is read. The final pass also validates references discovered in list results.
                let opening = matches!(name, "github-pr" | "github-compare" | "github-commit");
                let captured = capture(&context, &state, &rpc.args, opening, &[]).await?;
                let mut args = rpc.args.clone();
                if opening && args.is_object() {
                    args["fresh"] = json!(true);
                }
                let mut value = crate::dispatch_as(&state, name, args, &login).await?;
                validate(&context, &captured).await?;
                let provenance =
                    crate::ai::workspace_publication_repositories(&state, name, &value)?;
                let discovered = capture(&context, &state, &value, false, &provenance).await?;
                validate(&context, &discovered).await?;
                strip_provenance(&mut value);
                value
            }
        };
        context
            .github
            .check_output(&context, &result)
            .await
            .map_err(anyhow::Error::from)?;
        state.identity.ensure_current()?;
        Ok::<_, anyhow::Error>(result)
    };
    let outcome = tokio::select! {
        biased;
        _ = state.identity.retired() => return error(StatusCode::UNAUTHORIZED, "Your GitHub sign-in ended. Sign in again."),
        outcome = crate::rt::timeout(Duration::from_secs(120), operation) => match outcome {
            Ok(outcome) => outcome,
            Err(_) => return error(StatusCode::GATEWAY_TIMEOUT, "The workspace request timed out. Try again."),
        },
    };
    match outcome {
        Ok(result) => Json(json!({"result":result})).into_response(),
        Err(e) => {
            if let Some(read) = e.downcast_ref::<ReadError>() {
                return read_error(read.clone());
            }
            if context.current().is_err() || state.identity.ensure_current().is_err() {
                return auth_error(AuthError::Unauthorized);
            }
            let mut message = transport::safe_error(&e);
            if let Ok(Some(token)) = state.identity.github_token(|| async { None }).await {
                message = message.replace(&token, "[redacted]");
            }
            let _ = state
                .analytics
                .record(
                    "error.server",
                    json!({"route":"workspace","method":rpc.name,"message":message}),
                )
                .await;
            error(StatusCode::BAD_REQUEST, &message)
        }
    }
}

fn references(
    value: &Value,
    repositories: &mut BTreeSet<(String, String)>,
    keys: &mut BTreeSet<String>,
) {
    match value {
        Value::Array(values) => {
            for value in values {
                references(value, repositories, keys);
            }
        }
        Value::Object(object) => {
            if let (Some(owner), Some(repo)) = (
                object.get("owner").and_then(Value::as_str),
                object.get("repo").and_then(Value::as_str),
            ) {
                repositories.insert((owner.to_ascii_lowercase(), repo.to_ascii_lowercase()));
            }
            if let Some(key) = object.get("key").and_then(Value::as_str) {
                keys.insert(key.to_owned());
            }
            for value in object
                .values()
                .filter(|value| value.is_object() || value.is_array())
            {
                references(value, repositories, keys);
            }
        }
        _ => {}
    }
}

async fn capture(
    context: &Context,
    state: &Arc<AppState>,
    value: &Value,
    opening: bool,
    provenance: &[crate::source::WorkspaceRepository],
) -> anyhow::Result<Vec<RepositoryRead>> {
    let (mut repositories, mut keys) = (BTreeSet::new(), BTreeSet::new());
    references(value, &mut repositories, &mut keys);
    let mut expected_ids = BTreeMap::new();
    for repository in provenance {
        let key = (
            repository.owner.to_ascii_lowercase(),
            repository.repo.to_ascii_lowercase(),
        );
        if expected_ids
            .insert(key.clone(), repository.repository_id)
            .is_some_and(|old| old != repository.repository_id)
        {
            return Err(ReadError::new(Code::Forbidden).into());
        }
        repositories.insert(key);
    }
    for key in keys {
        if let Some(entry) = state.host.storage.get_async(&format!("diff:{key}")).await? {
            references(
                &entry["diff"]["ref"],
                &mut repositories,
                &mut BTreeSet::new(),
            );
        }
    }
    // Both phases share PersonalGitHub's three-request permit budget. Do not queue
    // an unbounded set of futures or serialize a normal twenty-repository Inbox.
    let expected_ids = Arc::new(expected_ids);
    let context = context.clone();
    let state = state.clone();
    let captured = stream::iter(repositories)
        .map(move |(owner, repo)| {
            let context = context.clone();
            let state = state.clone();
            let expected_ids = expected_ids.clone();
            async move {
                ensure!(
                    valid_segment(&owner, 39) && valid_segment(&repo, 100),
                    "Choose a valid GitHub repository"
                );
                let read = context
                    .github
                    .accessible_repository(&context, &owner, &repo)
                    .await?;
                if expected_ids
                    .get(&(owner.clone(), repo.clone()))
                    .is_some_and(|id| *id != read.repository.id)
                {
                    context.github.retire_repository(&read);
                    return Err(ReadError::new(Code::Forbidden).into());
                }
                let key = format!(
                    "workspace-repository:{}/{}",
                    owner.to_ascii_lowercase(),
                    repo.to_ascii_lowercase()
                );
                let expected = state.host.storage.get_async(&key).await?;
                if let Some(previous) = expected
                    .as_ref()
                    .filter(|expected| expected.as_u64() != Some(read.repository.id))
                {
                    if !opening {
                        return Err(ReadError::new(Code::Forbidden).into());
                    }
                    retire_saved_repository(&state, &owner, &repo, previous.as_u64().unwrap_or(0))
                        .await?;
                    state
                        .host
                        .storage
                        .set_async(&key, &json!(read.repository.id))
                        .await?;
                }
                if expected.is_none() {
                    state
                        .host
                        .storage
                        .set_async(&key, &json!(read.repository.id))
                        .await?;
                }
                Ok::<_, anyhow::Error>(read)
            }
        })
        .buffer_unordered(3)
        .try_collect::<Vec<_>>()
        .await?;
    Ok(captured)
}

fn strip_provenance(value: &mut Value) {
    match value {
        Value::Array(values) => {
            for value in values {
                strip_provenance(value);
            }
        }
        Value::Object(fields) => {
            fields.remove("_workspaceRepositories");
            for value in fields.values_mut() {
                strip_provenance(value);
            }
        }
        _ => {}
    }
}

/// A freshly opened name-reused repository must not inherit the old repository's saved
/// private content even when commit SHAs happen to match. Keep that data archived under
/// the old immutable ID; the fixed phone allowlist cannot read the archive.
async fn retire_saved_repository(
    state: &Arc<AppState>,
    owner: &str,
    repo: &str,
    previous: u64,
) -> anyhow::Result<()> {
    let (owner, repo) = (owner.to_owned(), repo.to_owned());
    state
        .host
        .storage
        .transaction(move |store| {
            for key in store.keys("diff:")? {
                let Some(entry) = store.get(&key)? else {
                    continue;
                };
                let reference = &entry["diff"]["ref"];
                if !crate::text(reference, "owner").eq_ignore_ascii_case(&owner)
                    || !crate::text(reference, "repo").eq_ignore_ascii_case(&repo)
                {
                    continue;
                }
                let scope = key.strip_prefix("diff:").unwrap_or(&key);
                for collection in ["diff", "tour", "brief", "critique", "ask-threads"] {
                    let key = format!("{collection}:{scope}");
                    if let Some(saved) = store.get(&key)? {
                        store.set(&format!("workspace-retired:{previous}:{key}"), &saved)?;
                        store.remove(&key)?;
                    }
                }
            }
            Ok(())
        })
        .await
}

async fn validate(context: &Context, captured: &[RepositoryRead]) -> anyhow::Result<()> {
    let captured_context = context.clone();
    stream::iter(captured.to_vec())
        .map(move |original| {
            let context = captured_context.clone();
            async move {
                let repository = &original.repository;
                let current = context
                    .github
                    .accessible_repository(&context, &repository.owner, &repository.name)
                    .await?;
                if current.repository.id != repository.id {
                    context.github.retire_repository(&original);
                    return Err(ReadError::new(Code::Forbidden));
                }
                Ok(())
            }
        })
        .buffer_unordered(3)
        .try_collect::<Vec<_>>()
        .await?;
    // Check every original lease after *all* awaited rechecks: a concurrent denial
    // while another repository was pending must fence the complete response.
    for original in captured {
        context
            .github
            .check_repository(&original.scope, original.lease)?;
    }
    context.current()?;
    Ok(())
}
