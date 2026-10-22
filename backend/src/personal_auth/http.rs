//! Explicit router factories only: no production listener, legacy RPC or operator account is installed here.
use super::{wire, AuthError, PersonalAuth};
use axum::{
    body::Bytes,
    extract::{rejection::BytesRejection, DefaultBodyLimit, Request, State},
    http::{header, HeaderMap, HeaderValue, StatusCode, Uri},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{de::DeserializeOwned, Serialize};

const MAX_BODY: usize = 8 * 1024;
const CALLBACK_PAGE: &str = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Pully</title><body>Return to Pully to continue.</body></html>";

/// A host can expose only disabled discovery without loading any credential, account, store or provider.
pub(crate) fn unconfigured_router() -> Router {
    Router::new()
        .route(
            wire::CAPABILITIES_PATH,
            get(|| async { Json(PersonalAuth::disabled_capabilities()) }),
        )
        .layer(middleware::from_fn(boundary))
}

/// Kept separate from every legacy/private route; a reviewed host must explicitly opt into this surface.
pub(crate) fn configured_router(service: PersonalAuth) -> Router {
    Router::new()
        .route(wire::CAPABILITIES_PATH, get(capabilities))
        .route(wire::START_PATH, post(start))
        .route(wire::STATUS_PATH, post(status))
        .route(wire::CLAIM_PATH, post(claim))
        .route(wire::CANCEL_PATH, post(cancel))
        .route(wire::CALLBACK_PATH, get(callback))
        .route(wire::SESSION_PATH, get(session))
        .route(wire::SIGN_OUT_PATH, post(sign_out))
        .route(wire::DISCONNECT_PATH, post(disconnect))
        .with_state(service)
        .layer(DefaultBodyLimit::max(MAX_BODY))
        .layer(middleware::from_fn(boundary))
}

async fn boundary(request: Request, next: Next) -> Response {
    let path = request.uri().path();
    let personal_session_path = [
        wire::SESSION_PATH,
        wire::SIGN_OUT_PATH,
        wire::DISCONNECT_PATH,
    ]
    .contains(&path);
    let forbidden = [
        "x-pully-session",
        "cf-access-client-id",
        "cf-access-client-secret",
        "cf-access-jwt-assertion",
        "x-api-key",
        "x-forwarded-authorization",
    ];
    let invalid = forbidden
        .iter()
        .any(|name| request.headers().contains_key(*name))
        || (!personal_session_path && request.headers().contains_key(header::AUTHORIZATION))
        || (path != wire::CALLBACK_PATH
            && (request.headers().contains_key(header::COOKIE) || request.uri().query().is_some()));
    let mut response = if invalid {
        AuthError::InvalidRequest.into_response()
    } else {
        next.run(request).await
    };
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
        .headers_mut()
        .insert("referrer-policy", HeaderValue::from_static("no-referrer"));
    response.headers_mut().insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    response
}

fn parse<T: DeserializeOwned>(
    headers: &HeaderMap,
    body: std::result::Result<Bytes, BytesRejection>,
) -> Result<T, AuthError> {
    if !headers
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| {
            value
                .split(';')
                .next()
                .unwrap_or("")
                .trim()
                .eq_ignore_ascii_case("application/json")
        })
    {
        return Err(AuthError::InvalidRequest);
    }
    let body = body.map_err(|_| AuthError::InvalidRequest)?;
    if body.len() > MAX_BODY {
        return Err(AuthError::InvalidRequest);
    }
    serde_json::from_slice(&body).map_err(|_| AuthError::InvalidRequest)
}

fn bearer(headers: &HeaderMap) -> Result<&str, AuthError> {
    let mut values = headers.get_all(header::AUTHORIZATION).iter();
    let value = values
        .next()
        .and_then(|value| value.to_str().ok())
        .ok_or(AuthError::Unauthorized)?;
    if values.next().is_some() {
        return Err(AuthError::Unauthorized);
    }
    let session = value
        .strip_prefix("Bearer ")
        .ok_or(AuthError::Unauthorized)?;
    let proof = session
        .strip_prefix("pully_")
        .ok_or(AuthError::Unauthorized)?;
    wire::parse_fixed_secret(proof).map_err(|_| AuthError::Unauthorized)?;
    Ok(session)
}

async fn capabilities(State(service): State<PersonalAuth>) -> Json<wire::Capabilities> {
    Json(service.capabilities())
}
async fn start(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, BytesRejection>,
) -> Result<Json<wire::StartReply>, AuthError> {
    service.start(parse(&headers, body)?).map(Json)
}
async fn status(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, BytesRejection>,
) -> Result<Json<wire::StatusReply>, AuthError> {
    service.status(parse(&headers, body)?).map(Json)
}
async fn claim(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, BytesRejection>,
) -> Result<Json<wire::ClaimReply>, AuthError> {
    service.claim(parse(&headers, body)?).await.map(Json)
}
async fn cancel(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
    body: std::result::Result<Bytes, BytesRejection>,
) -> Result<Json<wire::CancelReply>, AuthError> {
    service.cancel(parse(&headers, body)?).await.map(Json)
}
async fn session(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
) -> Result<Json<super::vault::SessionStatus>, AuthError> {
    service.session_status(bearer(&headers)?).await.map(Json)
}
async fn sign_out(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
) -> Result<Json<bool>, AuthError> {
    service.sign_out(bearer(&headers)?).await?;
    Ok(Json(true))
}
async fn disconnect(
    State(service): State<PersonalAuth>,
    headers: HeaderMap,
) -> Result<Json<bool>, AuthError> {
    service.disconnect(bearer(&headers)?).await?;
    Ok(Json(true))
}

async fn callback(State(service): State<PersonalAuth>, uri: Uri) -> Result<Response, AuthError> {
    let query = uri
        .query()
        .filter(|query| query.len() <= MAX_BODY)
        .ok_or(AuthError::InvalidRequest)?;
    let (mut state, mut code, mut error) = (None, None, None);
    for (key, value) in url::form_urlencoded::parse(query.as_bytes()) {
        let target = match key.as_ref() {
            "state" => &mut state,
            "code" => &mut code,
            "error" => &mut error,
            "error_description" | "error_uri" => continue,
            _ => return Err(AuthError::InvalidRequest),
        };
        if target.replace(value.into_owned()).is_some() {
            return Err(AuthError::InvalidRequest);
        }
    }
    if code.is_some() && error.is_some() {
        return Err(AuthError::InvalidRequest);
    }
    let denied = error.as_deref() == Some("access_denied");
    service
        .callback(state.ok_or(AuthError::InvalidRequest)?, code, denied)
        .await?;
    let mut response = Html(CALLBACK_PAGE).into_response();
    response.headers_mut().insert(
        "content-security-policy",
        HeaderValue::from_static(
            "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
        ),
    );
    Ok(response)
}

impl IntoResponse for AuthError {
    fn into_response(self) -> Response {
        let status = match self {
            Self::InvalidRequest => StatusCode::BAD_REQUEST,
            Self::Unauthorized | Self::Reconnect => StatusCode::UNAUTHORIZED,
            Self::Denied => StatusCode::FORBIDDEN,
            Self::Expired => StatusCode::GONE,
            Self::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            Self::Unavailable => StatusCode::SERVICE_UNAVAILABLE,
        };
        #[derive(Serialize)]
        struct ErrorBody {
            error: Details,
        }
        #[derive(Serialize)]
        struct Details {
            kind: AuthError,
            message: String,
        }
        (
            status,
            Json(ErrorBody {
                error: Details {
                    kind: self,
                    message: self.to_string(),
                },
            }),
        )
            .into_response()
    }
}

#[cfg(test)]
mod tests;
