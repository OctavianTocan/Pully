mod wire;

use super::{
    github::RepositoryRead, wire::ReadRepository, Code, Context, Inner, ReadError, ReadScope,
    Result,
};
use crate::{
    public_api::github::{valid_segment, IdentityFence, IdentityLease},
    source::github_read::{GitHubFuture, GitHubRead},
};
use axum::{
    extract::{
        rejection::{PathRejection, QueryRejection},
        OriginalUri, Path, Query, State,
    },
    http::HeaderMap,
    routing::get,
    Json, Router,
};
use reqwest::Method;
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
use wire::{Selection, Target, BASE};

const MAX_BODY: usize = 32 * 1024 * 1024;
const MAX_TOTAL: usize = 64 * 1024 * 1024;
const MAX_REQUESTS: usize = 128;

#[derive(Clone, PartialEq, Eq)]
pub(super) struct PullIdentity {
    target: Target,
    node_id: String,
}
pub(super) type PullFence = IdentityFence<PullIdentity>;
struct Captured {
    repository: RepositoryRead,
    identity: PullIdentity,
    scope: String,
    lease: IdentityLease<PullIdentity>,
}

pub(super) fn routes() -> Router<Arc<Inner>> {
    let mut router = Router::new().route(BASE, get(read));
    for suffix in ["overview", "diff", "files", "reviews"] {
        router = router.route(&format!("{BASE}/{suffix}"), get(read));
    }
    router
}

fn stale() -> ReadError {
    ReadError::new(Code::StaleReview)
}
fn invalid() -> ReadError {
    ReadError::new(Code::InvalidResponse)
}

fn identity(repository: &ReadRepository, number: u32, pull: &Value) -> Result<PullIdentity> {
    if pull["number"].as_u64() != Some(number as u64)
        || pull["base"]["repo"]["id"].as_u64() != Some(repository.id)
    {
        return Err(ReadError::new(Code::Forbidden));
    }
    let node_id = pull["node_id"]
        .as_str()
        .filter(|id| !id.is_empty() && id.len() <= 256 && !id.chars().any(char::is_control))
        .ok_or_else(invalid)?;
    Ok(PullIdentity {
        target: Target {
            repository_id: repository.id,
            pull_id: pull["id"]
                .as_u64()
                .filter(|id| *id > 0)
                .ok_or_else(invalid)?,
            number,
            base_sha: wire::sha(pull["base"]["sha"].as_str().ok_or_else(invalid)?)
                .map_err(|_| invalid())?,
            head_sha: wire::sha(pull["head"]["sha"].as_str().ok_or_else(invalid)?)
                .map_err(|_| invalid())?,
            private: repository.private.ok_or_else(invalid)?,
        },
        node_id: node_id.to_owned(),
    })
}

async fn capture(
    context: &Context,
    owner: &str,
    repo: &str,
    number: u32,
    repository_id: u64,
    expected: Option<&Target>,
) -> Result<Captured> {
    let github = &context.github;
    let repository = github.accessible_repository(context, owner, repo).await?;
    if repository.repository.id != repository_id {
        return Err(ReadError::new(Code::Forbidden));
    }
    let scope = format!(
        "{}/{}#{number}",
        owner.to_ascii_lowercase(),
        repo.to_ascii_lowercase()
    );
    let probe = github
        .reviews
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .open(&scope)
        .ok_or_else(|| ReadError::new(Code::Unavailable))?;
    let pull = github
        .get(
            context,
            &["repos", owner, repo, "pulls", &number.to_string()],
            &[],
        )
        .await
        .and_then(|pull| identity(&repository.repository, number, &pull));
    let identity = match pull {
        Ok(identity) => identity,
        Err(error) => {
            github
                .reviews
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .deny(&scope, probe);
            return Err(error);
        }
    };
    github.check_repository(&repository.scope, repository.lease)?;
    let (lease, _) = github
        .reviews
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .confirm(&scope, probe, identity.clone())
        .ok_or_else(stale)?;
    if expected.is_some_and(|expected| expected != &identity.target) {
        return Err(stale());
    }
    context.current()?;
    Ok(Captured {
        repository,
        identity,
        scope,
        lease,
    })
}

struct Reader<'a> {
    context: &'a Context,
    captured: &'a Captured,
    owner: &'a str,
    repo: &'a str,
    requests: AtomicUsize,
    bytes: AtomicUsize,
    failure: Mutex<Option<ReadError>>,
}
impl Reader<'_> {
    fn current(&self) -> Result<()> {
        self.context.current()?;
        if let Some(error) = self
            .failure
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
        {
            return Err(error.clone());
        }
        self.context.github.check_repository(
            &self.captured.repository.scope,
            self.captured.repository.lease,
        )?;
        if !self
            .context
            .github
            .reviews
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .current(&self.captured.scope, self.captured.lease.clone())
        {
            return Err(stale());
        }
        Ok(())
    }
    async fn request(
        &self,
        method: Method,
        path: &str,
        body: Option<Value>,
        accept: &str,
    ) -> Result<(u16, Value, String)> {
        self.current()?;
        let prefix = format!("/repos/{}/{}/", self.owner, self.repo);
        let read = method == Method::GET && path.starts_with(&prefix) && body.is_none();
        let graphql = method == Method::POST
            && path == "/graphql"
            && body.as_ref().is_some_and(|body| {
                body["query"]
                    .as_str()
                    .is_some_and(|query| query.trim_start().starts_with("query "))
                    && body["variables"]["owner"] == self.owner
                    && body["variables"]["repo"] == self.repo
                    && body["variables"]["number"].as_u64()
                        == Some(self.captured.identity.target.number as u64)
            });
        if !(read || graphql)
            || !matches!(
                accept,
                "application/vnd.github+json"
                    | "application/vnd.github.diff"
                    | "application/vnd.github.raw"
            )
        {
            return Err(ReadError::new(Code::InvalidRequest));
        }
        if self.requests.fetch_add(1, Ordering::SeqCst) >= MAX_REQUESTS {
            return Err(invalid());
        }
        let remaining = MAX_TOTAL.saturating_sub(self.bytes.load(Ordering::SeqCst));
        if remaining == 0 {
            return Err(invalid());
        }
        let response = self
            .context
            .github
            .raw(
                self.context,
                method,
                self.context.github.url(path)?,
                body,
                accept,
                MAX_BODY.min(remaining),
            )
            .await?;
        self.bytes
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |bytes| {
                bytes
                    .checked_add(response.2.len())
                    .filter(|bytes| *bytes <= MAX_TOTAL)
            })
            .map_err(|_| invalid())?;
        self.current()?;
        if response.0 == 404 && !path.starts_with(&format!("{prefix}contents/")) {
            return Err(ReadError::new(Code::Forbidden));
        }
        if accept == "application/vnd.github+json" && response.0 == 200 {
            let value: Value = serde_json::from_str(&response.2).map_err(|_| invalid())?;
            self.context
                .github
                .check_output(self.context, &value)
                .await?;
            if path.split('?').next()
                == Some(format!("{prefix}pulls/{}", self.captured.identity.target.number).as_str())
            {
                let latest = identity(
                    &self.captured.repository.repository,
                    self.captured.identity.target.number,
                    &value,
                )?;
                if latest != self.captured.identity {
                    return Err(stale());
                }
            }
        }
        Ok(response)
    }
}
impl GitHubRead for Reader<'_> {
    fn raw<'a>(
        &'a self,
        method: Method,
        path: &'a str,
        body: Option<Value>,
        accept: &'a str,
    ) -> GitHubFuture<'a> {
        Box::pin(async move {
            let result = self.request(method, path, body, accept).await;
            if let Err(error) = &result {
                if matches!(error.code, Code::Forbidden | Code::StaleReview) {
                    self.context
                        .github
                        .reviews
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .retire(&self.captured.scope, self.captured.lease.clone());
                }
                if matches!(
                    error.code,
                    Code::Unauthorized
                        | Code::Reconnect
                        | Code::Forbidden
                        | Code::StaleReview
                        | Code::InvalidRequest
                        | Code::InvalidResponse
                ) {
                    let mut failed = self
                        .failure
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    if failed.is_none() {
                        *failed = Some(error.clone());
                    }
                }
            }
            result.map_err(anyhow::Error::new)
        })
    }
}

async fn full_file(read: &Reader<'_>, reference: &Value, path: &str) -> anyhow::Result<Value> {
    let diff = crate::source::fetch_github_with(read, reference).await?;
    let requests = crate::review::snapshot_file_reads(&diff, path)?;
    let mut result = json!({"old":null,"new":null});
    for (side, request) in [("old", requests.old), ("new", requests.new)] {
        if let Some(request) = request {
            result[side] = crate::source::github_file_with(
                read,
                reference,
                request["path"].as_str().ok_or_else(invalid)?,
                request["sha"].as_str().ok_or_else(invalid)?,
            )
            .await?;
        }
    }
    if requests.require_new && result["new"].is_null() {
        return Err(ReadError::new(Code::Forbidden).into());
    }
    Ok(result)
}

async fn read(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    path_selection: std::result::Result<Path<(String, String, u32)>, PathRejection>,
    OriginalUri(uri): OriginalUri,
    query: std::result::Result<Query<Selection>, QueryRejection>,
) -> Result<Json<Value>> {
    let Path((owner, repo, number)) =
        path_selection.map_err(|_| ReadError::new(Code::InvalidRequest))?;
    let Query(selection) = query.map_err(|_| ReadError::new(Code::InvalidRequest))?;
    if !valid_segment(&owner, 39) || !valid_segment(&repo, 100) {
        return Err(ReadError::new(Code::InvalidRequest));
    }
    let operation = uri.path().rsplit('/').next().unwrap_or("").to_owned();
    let opening = operation == "review";
    let expected = selection.target(number, opening)?;
    let path = selection.file_path(operation == "files")?;
    let _memory = inner
        .review_requests
        .clone()
        .try_acquire_owned()
        .map_err(|_| ReadError::rate(5))?;
    inner.read(async {
        let context = inner.context(&headers, ReadScope::Account).await?;
        let initial = capture(&context, &owner, &repo, number, selection.repository_id, expected.as_ref()).await?;
        let reader = Reader { context: &context, captured: &initial, owner: &owner, repo: &repo, requests: AtomicUsize::new(0), bytes: AtomicUsize::new(0), failure: Mutex::new(None) };
        let args = json!({"owner":owner,"repo":repo,"number":number});
        let reference = json!({"kind":"github-pr","owner":owner,"repo":repo,"number":number.to_string()});
        let result = match operation.as_str() {
            "review" => Ok(None),
            "overview" => crate::review::github_read_with(&reader, crate::review::GitHubReadKind::Overview, &args).await.map(Some),
            "reviews" => crate::review::github_read_with(&reader, crate::review::GitHubReadKind::Reviews, &args).await.map(Some),
            "diff" => crate::source::fetch_github_with(&reader, &reference).await.map(Some),
            "files" => full_file(&reader, &reference, path.ok_or_else(|| ReadError::new(Code::InvalidRequest))?).await.map(Some),
            _ => return Err(ReadError::new(Code::InvalidRequest)),
        };
        reader.current()?;
        let mut value = result.map_err(|error| error.downcast_ref::<ReadError>().cloned().unwrap_or_else(invalid))?;
        if operation == "overview" {
            let overview = value.as_ref().ok_or_else(invalid)?;
            if overview["overview"]["id"] != initial.identity.node_id || overview["overview"]["number"] != number
                || overview["overview"]["head"]["oid"] != initial.identity.target.head_sha || overview["overview"]["base"]["oid"] != initial.identity.target.base_sha { return Err(stale()); }
        }
        if let Some(body) = value.as_mut() {
            let method = match operation.as_str() { "diff" => Some("source-fetch"), "reviews" => Some("review-data"), _ => None };
            if let Some(method) = method { *body = crate::rpc_validation::result(method, body.take()).map_err(|_| invalid())?; }
            if operation == "reviews" { *body = crate::rpc_validation::canonical_reviews(body).map_err(|_| invalid())?; }
            context.github.check_output(&context, body).await?;
        }
        let final_read = capture(&context, &owner, &repo, number, selection.repository_id, Some(&initial.identity.target)).await?;
        reader.current()?;
        context.github.check_repository(&final_read.repository.scope, final_read.repository.lease)?;
        let mut response = json!({"accountId":context.owner()?.account_id,"githubId":context.owner()?.github_id,"authority":"privateRepositories","repository":final_read.repository.repository,"target":initial.identity.target});
        if let Some(value) = value { response["value"] = value; }
        if serde_json::to_vec(&response).map_err(|_| invalid())?.len() > MAX_BODY { return Err(invalid()); }
        context.current()?;
        reader.current()?;
        Ok(Json(response))
    }).await
}
