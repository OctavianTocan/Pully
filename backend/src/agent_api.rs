//! Key-only, account-scoped API. No call here can invoke phone RPCs or agent jobs.
use crate::{
    posted::{PostError, MAX_POST_BYTES},
    AppState,
};
use axum::{
    body::to_bytes,
    extract::Request,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock},
};
pub const POSTS_PER_HOUR: usize = 120;
static POSTS: OnceLock<Mutex<HashMap<String, Vec<u64>>>> = OnceLock::new();
pub fn normalized_path(path: &str) -> String {
    let mut out = String::new();
    for c in path.chars() {
        if c != '/' || !out.ends_with('/') {
            out.push(c);
        }
    }
    out
}
pub fn is_agent_path(path: &str) -> bool {
    let path = normalized_path(path).to_ascii_lowercase();
    path == "/api/v1" || path.starts_with("/api/v1/")
}
fn fail(status: u16, error: impl Into<String>) -> Response {
    (
        StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
        Json(json!({"error":error.into()})),
    )
        .into_response()
}
fn limit(root: &str, key: &str) -> Option<u64> {
    let now = crate::now_ms();
    let mut map = POSTS
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    map.retain(|_, v| {
        v.retain(|at| now.saturating_sub(*at) < 3_600_000);
        !v.is_empty()
    });
    let recent = map.entry(format!("{root}:{key}")).or_default();
    if recent.len() >= POSTS_PER_HOUR {
        Some((recent[0] + 3_600_000 - now).div_ceil(1000))
    } else {
        recent.push(now);
        None
    }
}
fn url(origin: &str, id: &Value) -> String {
    format!(
        "{}/-/{}",
        origin.trim_end_matches('/'),
        id.as_str().unwrap_or("")
    )
}
fn described(mut meta: Value, origin: &str) -> Value {
    let url = url(origin, &meta["id"]);
    if let Some(m) = meta.as_object_mut() {
        m.remove("accountId");
        m.remove("chunks");
        m.remove("updatedMs");
    }
    meta["url"] = json!(url);
    meta
}
fn decode(value: &str) -> Result<String, ()> {
    let mut out = vec![];
    let b = value.as_bytes();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            if i + 2 >= b.len() {
                return Err(());
            }
            let h = (b[i + 1] as char).to_digit(16).ok_or(())?;
            let l = (b[i + 2] as char).to_digit(16).ok_or(())?;
            out.push((h * 16 + l) as u8);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| ())
}
fn header(headers: &HeaderMap, name: &str) -> Result<Option<String>, ()> {
    headers
        .get(name)
        .map(|h| h.to_str().map_err(|_| ()).and_then(decode))
        .transpose()
}
async fn read_post(request: Request) -> Result<(Option<String>, Value), Response> {
    let headers = request.headers().clone();
    let bytes = to_bytes(request.into_body(), MAX_POST_BYTES)
        .await
        .map_err(|_| fail(413, "A request can be at most 8 MB."))?;
    let body = String::from_utf8_lossy(&bytes).into_owned();
    if headers
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|s| s.contains("json"))
    {
        let mut parsed: Value =
            serde_json::from_str(&body).map_err(|_| fail(400, "That body is not JSON."))?;
        let object = parsed.as_object_mut().ok_or_else(|| {
            fail(
                400,
                "Send a JSON object: { \"patch\": \"...\", \"title\": \"...\" }.",
            )
        })?;
        let patch = match object.remove("patch") {
            None => None,
            Some(Value::String(s)) => Some(s),
            _ => return Err(fail(400, "`patch` must be the diff as text.")),
        };
        Ok((patch, parsed))
    } else {
        let title = header(&headers, "x-pully-title")
            .map_err(|_| fail(400, "A post header is not valid percent-encoded text."))?;
        let author = header(&headers, "x-pully-author")
            .map_err(|_| fail(400, "A post header is not valid percent-encoded text."))?;
        let allow = header(&headers, "x-pully-allow-secrets")
            .map_err(|_| fail(400, "A post header is not valid percent-encoded text."))?;
        Ok((
            if body.trim().is_empty() {
                None
            } else {
                Some(body)
            },
            json!({"title":title,"author":author,"allowSecrets":allow.as_deref()==Some("true")}),
        ))
    }
}
fn bearer(headers: &HeaderMap) -> Option<&str> {
    let mut parts = headers
        .get("authorization")?
        .to_str()
        .ok()?
        .split_whitespace();
    let scheme = parts.next()?;
    let key = parts.next()?;
    if scheme.eq_ignore_ascii_case("bearer") && parts.next().is_none() {
        Some(key)
    } else {
        None
    }
}
pub async fn handle(app: Arc<AppState>, request: Request, path: &str) -> Response {
    let unauthorized = || {
        let mut response = fail(401, "This needs an API key: send `Authorization: Bearer pully_...`. Make one in Pully, Settings, Agent keys.");
        response
            .headers_mut()
            .insert("www-authenticate", "Bearer".parse().unwrap());
        response
    };
    let Some(key) = bearer(request.headers()) else {
        return unauthorized();
    };
    if app.ensure_account().await.is_err() {
        return unauthorized();
    }
    let caller = match app.accounts.by_key(Some(key)) {
        Ok(Some(caller)) => caller,
        _ => return unauthorized(),
    };
    let origin = std::env::var("PULLY_PUBLIC_URL")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.trim().to_owned())
        .unwrap_or_else(|| {
            let scheme = request.uri().scheme_str().unwrap_or("http");
            let host = request
                .uri()
                .authority()
                .map(|a| a.as_str())
                .or_else(|| request.headers().get("host").and_then(|h| h.to_str().ok()))
                .unwrap_or("localhost");
            format!("{scheme}://{host}")
        });
    let normalized = normalized_path(path);
    let route = normalized.get(7..).unwrap_or("").trim_end_matches('/');
    let method = request.method().as_str().to_owned();
    let result: anyhow::Result<Response>=async {
 if route=="/me"&&method=="GET"{return Ok(Json(json!({"account":caller.account.email,"key":caller.key.name})).into_response())}
 if route=="/diffs"&&method=="GET"{return Ok(Json(json!({"diffs":app.posted.list(&caller.account.id)?.into_iter().map(|m|described(m,&origin)).collect::<Vec<_>>()})).into_response())}
 let id=route.strip_prefix("/diffs/").filter(|id|!id.is_empty()&&!id.contains('/'));
 if (route=="/diffs"&&method=="POST")||(id.is_some()&&method=="PUT"){
 if let Some(wait)=limit(&app.store.root().to_string_lossy(),&caller.key.id){let mut r=fail(429,format!("This key made {POSTS_PER_HOUR} posts in the last hour. Try again in {} minutes.",wait.div_ceil(60)));r.headers_mut().insert("retry-after",wait.to_string().parse().unwrap());return Ok(r)}
 let (patch,fields)=match read_post(request).await{Ok(v)=>v,Err(r)=>return Ok(r)};
 let saved=app.posted.save(&caller.account.id,&caller.key.name,id,patch.as_deref(),&fields)?;let m=&saved["meta"];return Ok((if id.is_some(){StatusCode::OK}else{StatusCode::CREATED},Json(json!({"id":m["id"],"url":url(&origin,&m["id"]),"version":m["version"],"files":m["files"],"warnings":saved["warnings"]}))).into_response())}
 if let Some(id)=id {if method=="GET"{let Some(found)=app.posted.read(&caller.account.id,id)? else{return Ok(fail(404,"There is no posted diff with that id."))};let mut answer=described(found["meta"].clone(),&origin);answer["patch"]=found["patch"].clone();if let Some(fields)=found["agent"].as_object(){for (k,v) in fields{answer[k]=v.clone();}}return Ok(Json(answer).into_response())}if method=="DELETE"{return Ok(if app.posted.remove(&caller.account.id,id)?{StatusCode::NO_CONTENT.into_response()}else{fail(404,"There is no posted diff with that id.")})}}
 Ok(fail(404,"There is no such call. The API has GET /api/v1/me, GET and POST /api/v1/diffs, and GET, PUT and DELETE /api/v1/diffs/<id>."))
 }.await;
    match result {
        Ok(r) => r,
        Err(e) => {
            if let Some(e) = e.downcast_ref::<PostError>() {
                fail(e.status, &e.message)
            } else {
                fail(500, "The server could not do that.")
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn claims_every_spelling() {
        for path in [
            "/API/v1/diffs",
            "/Api/V1",
            "//api/v1/diffs",
            "/api//v1/me",
            "/api/v1//diffs",
        ] {
            assert!(is_agent_path(path));
        }
        assert!(!is_agent_path("/api/v10"));
    }
    #[test]
    fn headers_decode_utf8_and_fail_closed() {
        assert_eq!(
            decode("Retry%20calls%20%E2%9C%93").unwrap(),
            "Retry calls ✓"
        );
        assert!(decode("%zz").is_err());
    }
}
