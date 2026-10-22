//! A separate, opt-in public surface. It has no handle to legacy AppState or storage.
pub(crate) mod github;

use axum::{
    extract::{rejection::QueryRejection, Path, Query, State},
    http::{header, HeaderValue, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use github::PublicGitHub;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ErrorCode {
    InvalidRequest,
    RepositoryUnavailable,
    RateLimited,
    Unavailable,
    InvalidResponse,
}

#[derive(Debug)]
pub(crate) struct PublicError {
    code: ErrorCode,
    retry: Option<u64>,
}

type Result<T> = std::result::Result<T, PublicError>;

impl PublicError {
    fn new(code: ErrorCode) -> Self {
        Self { code, retry: None }
    }
    fn rate_limited(seconds: u64) -> Self {
        Self {
            code: ErrorCode::RateLimited,
            retry: Some(seconds.clamp(1, 3600)),
        }
    }
}

impl IntoResponse for PublicError {
    fn into_response(self) -> Response {
        let (status, message) = match self.code {
            ErrorCode::InvalidRequest => (
                StatusCode::BAD_REQUEST,
                "Choose a repository or enter a shorter repository search.",
            ),
            ErrorCode::RepositoryUnavailable => (
                StatusCode::NOT_FOUND,
                "This repository is unavailable for public browsing.",
            ),
            ErrorCode::RateLimited => (
                StatusCode::TOO_MANY_REQUESTS,
                "GitHub's public browsing limit has been reached. Try again later.",
            ),
            ErrorCode::Unavailable => (
                StatusCode::SERVICE_UNAVAILABLE,
                "Public repositories are temporarily unavailable. Try again.",
            ),
            ErrorCode::InvalidResponse => (
                StatusCode::BAD_GATEWAY,
                "GitHub could not return these public repositories. Try again.",
            ),
        };
        let mut response = (
            status,
            Json(serde_json::json!({"error":{
                "code":self.code,"message":message,"retryAfterSeconds":self.retry
            }})),
        )
            .into_response();
        if let Some(seconds) = self.retry {
            response.headers_mut().insert(
                header::RETRY_AFTER,
                HeaderValue::from_str(&seconds.to_string()).unwrap(),
            );
        }
        response
    }
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct RepositoryQuery {
    #[serde(default)]
    query: String,
    #[serde(default = "first_page")]
    page: u32,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PageQuery {
    #[serde(default = "first_page")]
    page: u32,
}

fn first_page() -> u32 {
    1
}

/// @cc [owner:OctavianTocan,label:security;architecture] public-routes-have-no-private-dispatch
/// The optional public listener MUST expose only its explicit typed browsing routes.
/// It MUST NOT accept an RPC method or carry legacy AppState, operator credentials,
/// local checkout, account records or private caches. Ordinary signed-out readers
/// MUST be able to browse without operator Access credentials. Source: Tavi's
/// 21 Oct 2026 any-GitHub-account and anonymous popular-public-repository request.
pub fn router() -> anyhow::Result<Router> {
    Ok(routes(PublicGitHub::new()?))
}

pub(crate) fn router_with_personal_github(enabled: bool) -> anyhow::Result<Router> {
    Ok(routes_with_personal_github(PublicGitHub::new()?, enabled))
}
#[cfg(test)]
pub(crate) fn fixture_router(origin: url::Url, enabled: bool) -> Router {
    routes_with_personal_github(PublicGitHub::fixture(origin), enabled)
}

fn routes(github: PublicGitHub) -> Router {
    routes_with_personal_github(github, false)
}

fn routes_with_personal_github(github: PublicGitHub, enabled: bool) -> Router {
    let sign_in = if enabled {
        "configured"
    } else {
        "notConfigured"
    };
    Router::new()
        .route("/api/public/v1/capabilities", get(move || async move {
            Json(serde_json::json!({"apiVersion":1,"publicBrowsing":true,"githubSignIn":sign_in}))
        }))
        .route("/api/public/v1/repositories", get(repositories))
        .route("/api/public/v1/repositories/{owner}/{repo}/pulls", get(pulls))
        .fallback(|| async { StatusCode::NOT_FOUND })
        .layer(middleware::from_fn(response_boundary))
        .with_state(Arc::new(github))
}

async fn response_boundary(request: axum::extract::Request, next: Next) -> Response {
    let mut response = if request.uri().path().len() > 512
        || request.uri().query().is_some_and(|q| q.len() > 1024)
    {
        PublicError::new(ErrorCode::InvalidRequest).into_response()
    } else {
        next.run(request).await
    };
    // Browsing caches contain only this client's public facts. Do not let an external
    // proxy keep PR titles after visibility has changed or bypass our visibility check.
    for (name, value) in [
        (header::CACHE_CONTROL, "no-store"),
        (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        (header::REFERRER_POLICY, "no-referrer"),
    ] {
        response
            .headers_mut()
            .insert(name, HeaderValue::from_static(value));
    }
    response
}

async fn repositories(
    State(github): State<Arc<PublicGitHub>>,
    query: std::result::Result<Query<RepositoryQuery>, QueryRejection>,
) -> Result<Json<github::RepositoryPage>> {
    let Query(query) = query.map_err(|_| PublicError::new(ErrorCode::InvalidRequest))?;
    Ok(Json(github.repositories(&query.query, query.page).await?))
}

async fn pulls(
    State(github): State<Arc<PublicGitHub>>,
    Path((owner, repo)): Path<(String, String)>,
    query: std::result::Result<Query<PageQuery>, QueryRejection>,
) -> Result<Json<github::PullPage>> {
    let Query(query) = query.map_err(|_| PublicError::new(ErrorCode::InvalidRequest))?;
    Ok(Json(github.pulls(&owner, &repo, query.page).await?))
}

#[cfg(test)]
mod tests;
