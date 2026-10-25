mod github;
mod review;
mod wire;
mod workspace;

use crate::{
    identity::{RequestIdentity, TenantStates},
    personal_auth::{
        personal_bearer, AuthError, CurrentPersonalSession, GitHubAuthority, PersonalAuth,
    },
    public_api::github::valid_segment,
    store::blocking,
    AppState,
};
use axum::{
    extract::{rejection::QueryRejection, Path, Query, State},
    http::{header, HeaderMap, HeaderValue, Method, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    future::Future,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};
use wire::*;

const ROUTE_DEADLINE: Duration = Duration::from_secs(45);
type Result<T> = std::result::Result<T, ReadError>;
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
enum Code {
    InvalidRequest,
    Unauthorized,
    Reconnect,
    Forbidden,
    RateLimited,
    Unavailable,
    InvalidResponse,
    StaleReview,
}
#[derive(Debug, Clone)]
struct ReadError {
    code: Code,
    retry: Option<u64>,
}
impl ReadError {
    fn new(code: Code) -> Self {
        Self { code, retry: None }
    }
    fn rate(seconds: u64) -> Self {
        Self {
            code: Code::RateLimited,
            retry: Some(seconds.clamp(1, 3600)),
        }
    }
}
impl std::fmt::Display for ReadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Personal GitHub read failed")
    }
}
impl std::error::Error for ReadError {}
impl IntoResponse for ReadError {
    fn into_response(self) -> Response {
        let (status, message) = match self.code {
            Code::InvalidRequest => (
                StatusCode::BAD_REQUEST,
                "Choose a valid public repository or page.",
            ),
            Code::Unauthorized => (
                StatusCode::UNAUTHORIZED,
                "This personal read needs your current Pully session.",
            ),
            Code::Reconnect => (
                StatusCode::UNAUTHORIZED,
                "Reconnect your GitHub account to continue.",
            ),
            Code::Forbidden => (
                StatusCode::FORBIDDEN,
                "This GitHub read is not available to this account.",
            ),
            Code::RateLimited => (
                StatusCode::TOO_MANY_REQUESTS,
                "GitHub reads are busy. Try again later.",
            ),
            Code::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "Your GitHub repositories are temporarily unavailable.",
            ),
            Code::StaleReview => (
                StatusCode::CONFLICT,
                "This pull request changed. Open it again to continue.",
            ),
            Code::InvalidResponse => (
                StatusCode::BAD_GATEWAY,
                "GitHub could not return these public repositories.",
            ),
        };
        let mut response=(status,Json(serde_json::json!({"error":{"code":self.code,"message":message,"retryAfterSeconds":self.retry}}))).into_response();
        if let Some(seconds) = self.retry {
            response.headers_mut().insert(
                header::RETRY_AFTER,
                HeaderValue::from_str(&seconds.to_string()).unwrap(),
            );
        }
        response
    }
}
struct Inner {
    auth: PersonalAuth,
    tenants: TenantStates,
    clients: Mutex<HashMap<PathBuf, Arc<github::PersonalGitHub>>>,
    http: reqwest::Client,
    origin: url::Url,
    requests: Arc<tokio::sync::Semaphore>,
    review_requests: Arc<tokio::sync::Semaphore>,
}
pub(super) fn router(auth: PersonalAuth, tenants: TenantStates) -> anyhow::Result<Router> {
    let http = crate::net::client()
        .no_proxy()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(15))
        .build()?;
    Ok(routes(Inner {
        auth,
        tenants,
        clients: Default::default(),
        http,
        origin: url::Url::parse("https://api.github.com/")?,
        requests: Arc::new(tokio::sync::Semaphore::new(64)),
        review_requests: Arc::new(tokio::sync::Semaphore::new(4)),
    }))
}
#[cfg(test)]
pub(super) async fn fixture_router(
    auth: PersonalAuth,
    root: PathBuf,
    origin: url::Url,
) -> anyhow::Result<Router> {
    anyhow::ensure!(
        origin.scheme() == "http" && origin.host_str() == Some("127.0.0.1") && origin.path() == "/",
        "Invalid fixture origin"
    );
    let tenants = blocking(move || TenantStates::new(root, 256)).await?;
    fixture_router_with_tenants(auth, tenants, origin)
}
#[cfg(test)]
pub(super) fn fixture_router_with_tenants(
    auth: PersonalAuth,
    tenants: TenantStates,
    origin: url::Url,
) -> anyhow::Result<Router> {
    anyhow::ensure!(
        origin.scheme() == "http" && origin.host_str() == Some("127.0.0.1") && origin.path() == "/",
        "Invalid fixture origin"
    );
    let http = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(5))
        .build()?;
    Ok(routes(Inner {
        auth,
        tenants,
        clients: Default::default(),
        http,
        origin,
        requests: Arc::new(tokio::sync::Semaphore::new(64)),
        review_requests: Arc::new(tokio::sync::Semaphore::new(4)),
    }))
}
fn routes(inner: Inner) -> Router {
    let inner = Arc::new(inner);
    let typed = Router::new()
        .route(REPOSITORIES_PATH, get(repositories))
        .route(REPOSITORY_PULLS_PATH, get(pulls))
        .route(PULLS_PATH, get(inbox))
        .route(ACCOUNT_REPOSITORIES_PATH, get(account_repositories))
        .route(ACCOUNT_REPOSITORY_PULLS_PATH, get(account_pulls))
        .route(ACCOUNT_PULLS_PATH, get(account_inbox))
        .route(ACCOUNT_CAPABILITIES_PATH, get(account_capabilities))
        .merge(review::routes())
        .with_state(inner.clone())
        .layer(middleware::from_fn(boundary));
    typed.merge(workspace::routes(inner))
}
async fn boundary(request: axum::extract::Request, next: Next) -> Response {
    let forbidden = [
        "cookie",
        "x-pully-session",
        "cf-access-client-id",
        "cf-access-client-secret",
        "cf-access-jwt-assertion",
        "x-api-key",
        "x-forwarded-authorization",
    ];
    let invalid = request.method() != Method::GET
        || (request.uri().path() == ACCOUNT_CAPABILITIES_PATH && request.uri().query().is_some())
        || request.uri().path().len() > 512
        || request.uri().query().is_some_and(|query| {
            query.len()
                > if request.uri().path().ends_with("/review/files") {
                    16384
                } else {
                    512
                }
        })
        || request.headers().contains_key(header::TRANSFER_ENCODING)
        || request
            .headers()
            .get(header::CONTENT_LENGTH)
            .is_some_and(|length| length.as_bytes() != b"0")
        || forbidden
            .iter()
            .any(|name| request.headers().contains_key(*name));
    let mut response = if invalid {
        ReadError::new(Code::InvalidRequest).into_response()
    } else {
        next.run(request).await
    };
    for (name, value) in [
        (header::CACHE_CONTROL, "no-store"),
        (header::REFERRER_POLICY, "no-referrer"),
        (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
    ] {
        response
            .headers_mut()
            .insert(name, HeaderValue::from_static(value));
    }
    response
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum ReadScope {
    Public,
    Account,
}
#[derive(Clone)]
struct Context {
    session: CurrentPersonalSession,
    state: Arc<AppState>,
    github: Arc<github::PersonalGitHub>,
    scope: ReadScope,
}
impl Context {
    fn current(&self) -> Result<()> {
        self.session
            .identity()
            .map_err(|_| ReadError::new(Code::Unauthorized))?;
        self.state
            .identity
            .ensure_current()
            .map_err(|_| ReadError::new(Code::Unauthorized))?;
        if self.scope == ReadScope::Account
            && self
                .session
                .status()
                .map_err(|_| ReadError::new(Code::Unauthorized))?
                .authority
                != GitHubAuthority::PrivateRepositories
        {
            return Err(ReadError::new(Code::Forbidden));
        }
        Ok(())
    }
    fn owner(&self) -> Result<ReadOwner> {
        self.current()?;
        let status = self
            .session
            .status()
            .map_err(|_| ReadError::new(Code::Unauthorized))?;
        Ok(ReadOwner {
            account_id: status.account_id.clone(),
            github_id: status.github_id,
            authority: match self.scope {
                ReadScope::Public => "publicRead",
                ReadScope::Account => "privateRepositories",
            },
        })
    }
}
impl Inner {
    async fn context(&self, headers: &HeaderMap, scope: ReadScope) -> Result<Context> {
        let session = self
            .auth
            .authenticate(personal_bearer(headers).map_err(|_| ReadError::new(Code::Unauthorized))?)
            .await
            .map_err(|error| {
                ReadError::new(match error {
                    AuthError::Reconnect => Code::Reconnect,
                    AuthError::Unauthorized | AuthError::Expired => Code::Unauthorized,
                    AuthError::Denied => Code::Forbidden,
                    AuthError::RateLimited => Code::RateLimited,
                    _ => Code::Unavailable,
                })
            })?;
        if scope == ReadScope::Account
            && session
                .status()
                .map_err(|_| ReadError::new(Code::Unauthorized))?
                .authority
                != GitHubAuthority::PrivateRepositories
        {
            return Err(ReadError::new(Code::Forbidden));
        }
        let identity = session
            .identity()
            .map_err(|_| ReadError::new(Code::Unauthorized))?;
        let RequestIdentity::Account(credential) = identity else {
            return Err(ReadError::new(Code::Unauthorized));
        };
        let state = self
            .tenants
            .account(credential)
            .await
            .map_err(|_| ReadError::new(Code::Unavailable))?;
        session
            .identity()
            .map_err(|_| ReadError::new(Code::Unauthorized))?;
        let github = {
            let mut clients = self
                .clients
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            clients.retain(|_, client| client.current());
            if let Some(client) = clients.get(state.host.storage.root()) {
                client.clone()
            } else {
                if clients.len() >= 256 {
                    return Err(ReadError::rate(30));
                }
                let client = Arc::new(github::PersonalGitHub::new(
                    state.clone(),
                    self.http.clone(),
                    self.origin.clone(),
                ));
                clients.insert(state.host.storage.root().to_owned(), client.clone());
                client
            }
        };
        Ok(Context {
            session,
            state,
            github,
            scope,
        })
    }
    async fn read<T>(&self, operation: impl Future<Output = Result<T>>) -> Result<T> {
        let _permit = self
            .requests
            .clone()
            .try_acquire_owned()
            .map_err(|_| ReadError::rate(5))?;
        crate::rt::timeout(ROUTE_DEADLINE, operation)
            .await
            .map_err(|_| ReadError::new(Code::Unavailable))?
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Page {
    #[serde(default = "first_page")]
    page: u32,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct InboxQuery {
    #[serde(default = "first_page")]
    page: u32,
    #[serde(default)]
    relation: Relation,
}
fn first_page() -> u32 {
    1
}
fn valid_page(page: u32) -> Result<()> {
    if (1..=10).contains(&page) {
        Ok(())
    } else {
        Err(ReadError::new(Code::InvalidRequest))
    }
}
fn next_page(count: usize, page: u32) -> Option<u32> {
    (count == 20 && page < 10).then_some(page + 1)
}
async fn account_capabilities(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
) -> Result<Json<ReadCapabilities>> {
    inner
        .read(async {
            let context = inner.context(&headers, ReadScope::Public).await?;
            let private = context
                .session
                .status()
                .map_err(|_| ReadError::new(Code::Unauthorized))?
                .authority
                == GitHubAuthority::PrivateRepositories;
            let mut owner = context.owner()?;
            if private {
                owner.authority = "privateRepositories";
            }
            Ok(Json(ReadCapabilities {
                owner,
                private_repositories: PrivateRepositoryCapabilities {
                    consent_available: true,
                    read_enabled: private,
                },
            }))
        })
        .await
}
async fn repositories(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    query: std::result::Result<Query<Page>, QueryRejection>,
) -> Result<Json<RepositoryPage>> {
    repositories_for(inner, headers, query, ReadScope::Public).await
}
async fn account_repositories(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    query: std::result::Result<Query<Page>, QueryRejection>,
) -> Result<Json<RepositoryPage>> {
    repositories_for(inner, headers, query, ReadScope::Account).await
}
async fn repositories_for(
    inner: Arc<Inner>,
    headers: HeaderMap,
    query: std::result::Result<Query<Page>, QueryRejection>,
    scope: ReadScope,
) -> Result<Json<RepositoryPage>> {
    let Query(query) = query.map_err(|_| ReadError::new(Code::InvalidRequest))?;
    valid_page(query.page)?;
    inner
        .read(async {
            let context = inner.context(&headers, scope).await?;
            let repositories = context.github.repositories(&context, query.page).await?;
            let count = repositories.len();
            Ok(Json(RepositoryPage {
                owner: context.owner()?,
                repositories,
                next_page: next_page(count, query.page),
                incomplete: count == 20 && query.page == 10,
            }))
        })
        .await
}
async fn pulls(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    Path((owner, repo)): Path<(String, String)>,
    query: std::result::Result<Query<Page>, QueryRejection>,
) -> Result<Json<PullPage>> {
    pulls_for(inner, headers, owner, repo, query, ReadScope::Public).await
}
async fn account_pulls(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    Path((owner, repo)): Path<(String, String)>,
    query: std::result::Result<Query<Page>, QueryRejection>,
) -> Result<Json<PullPage>> {
    pulls_for(inner, headers, owner, repo, query, ReadScope::Account).await
}
async fn pulls_for(
    inner: Arc<Inner>,
    headers: HeaderMap,
    owner: String,
    repo: String,
    query: std::result::Result<Query<Page>, QueryRejection>,
    scope: ReadScope,
) -> Result<Json<PullPage>> {
    let Query(query) = query.map_err(|_| ReadError::new(Code::InvalidRequest))?;
    valid_page(query.page)?;
    if !valid_segment(&owner, 39) || !valid_segment(&repo, 100) {
        return Err(ReadError::new(Code::InvalidRequest));
    }
    inner
        .read(async {
            let context = inner.context(&headers, scope).await?;
            let (repository, pulls) = context
                .github
                .pulls(&context, &owner, &repo, query.page)
                .await?;
            let count = pulls.len();
            Ok(Json(PullPage {
                owner: context.owner()?,
                repository,
                pulls,
                next_page: next_page(count, query.page),
                incomplete: count == 20 && query.page == 10,
            }))
        })
        .await
}
async fn inbox(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    query: std::result::Result<Query<InboxQuery>, QueryRejection>,
) -> Result<Json<InboxPage>> {
    inbox_for(inner, headers, query, ReadScope::Public).await
}
async fn account_inbox(
    State(inner): State<Arc<Inner>>,
    headers: HeaderMap,
    query: std::result::Result<Query<InboxQuery>, QueryRejection>,
) -> Result<Json<InboxPage>> {
    inbox_for(inner, headers, query, ReadScope::Account).await
}
async fn inbox_for(
    inner: Arc<Inner>,
    headers: HeaderMap,
    query: std::result::Result<Query<InboxQuery>, QueryRejection>,
    scope: ReadScope,
) -> Result<Json<InboxPage>> {
    let Query(query) = query.map_err(|_| ReadError::new(Code::InvalidRequest))?;
    valid_page(query.page)?;
    inner
        .read(async {
            let context = inner.context(&headers, scope).await?;
            let (pulls, count, incomplete) = context
                .github
                .inbox(&context, query.relation, query.page)
                .await?;
            Ok(Json(InboxPage {
                owner: context.owner()?,
                relation: query.relation,
                pulls,
                next_page: next_page(count, query.page),
                incomplete: incomplete || (count == 20 && query.page == 10),
            }))
        })
        .await
}
