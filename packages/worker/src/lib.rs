//! The hosted Pully server: the Rust server (`backend/`) built for Cloudflare Workers. The
//! Worker sends every request to one `PullyServer` Durable Object, which answers it with the
//! server's own router and keeps the server's data and analytics in its SQLite storage.
//! Published Android builds come from the `PULLY_ANDROID` R2 bucket. Public browsing and
//! personal GitHub sign-in (`/api/public/`, `/api/personal/`, `/workspace/`) go to the
//! `PersonalServer` object instead, the Worker's form of the native server's separate public
//! listener, which hands each account's own requests to that account's `AccountServer` object.

use axum::body::Body;
use futures_util::{future::BoxFuture, StreamExt};
use pulls_review_server::scheduler::{warm_round, Rejections, WARM_EVERY};
use pulls_review_server::{
    analytics::{Analytics, DayFiles},
    host::{AndroidFiles, HostFile, ServerHost},
    store::{Records, Store},
    transport::{router, HttpState},
    AppState,
};
use send_wrapper::SendWrapper;
use std::{cell::RefCell, path::PathBuf, sync::Arc, time::Duration};
use tower_service::Service;
use worker::{
    durable_object, event, Bucket, Context, Env, Request, Response, Result, SqlStorage, State,
};

mod account;
mod import;
mod personal;
mod request_headers;

/// The Worker variables and secrets the server reads, when they are set.
const SERVER_ENVIRONMENT: &[&str] = &[
    "PULLS_REVIEW_GITHUB_TOKEN",
    "PULLY_ACCOUNT_EMAIL",
    "PULLY_ACCOUNT_PASSWORD",
    "PULLY_GOOGLE_CLIENT_ID",
    "PULLY_PUBLIC_URL",
    "TYPESAFE_API_KEY",
    "PULLS_REVIEW_PI_BASE_URL",
    "PULLS_REVIEW_PI_API_KEY",
    "PULLS_REVIEW_PI_MODEL",
    "PULLS_REVIEW_SUGGEST_MODEL",
    "PULLS_REVIEW_ANALYTICS",
];

/// How soon after the first request the first refresh runs, as the native server waits.
const FIRST_WARM: Duration = Duration::from_secs(5);

/// The port `wrangler dev` listens on, so local browser origins are trusted.
const DEV_PORT: u16 = 8787;

// Every Claude Code and Codex container is an `AgentHome` of the shared AI kit.
pub use ai_kit::home::AgentHome;

#[event(start)]
fn start() {
    console_error_panic_hook::set_once();
    // Before any object answers: an `AgentHome` checks logins against Pully's names.
    pulls_review_server::configure_agents();
}

#[event(fetch)]
async fn fetch(request: Request, env: Env, _: Context) -> Result<Response> {
    let path = request.path();
    if path.starts_with("/import/v1/") {
        return import::route(request, &env).await;
    }
    let object = if personal::serves(&path) {
        env.durable_object("PERSONAL_SERVER")?
            .get_by_name("personal")?
    } else {
        env.durable_object("PULLY_SERVER")?.get_by_name("server")?
    };
    object.fetch_with_request(request).await
}

#[durable_object]
pub struct PullyServer {
    state: State,
    http: HttpState,
    rejected: RefCell<Rejections>,
}

impl worker::DurableObject for PullyServer {
    fn new(state: State, env: Env) -> Self {
        pulls_review_server::rt::env::set(SERVER_ENVIRONMENT.iter().filter_map(|name| {
            let value = env
                .secret(name)
                .map(|s| s.to_string())
                .or_else(|_| env.var(name).map(|v| v.to_string()))
                .ok()?;
            Some((name.to_string(), value))
        }));
        // Every Claude Code and Codex call goes to its login's container; a second object in
        // this isolate finds the remote already set.
        ai_kit::home::install(&env, "AGENT_HOME").expect("Agent homes");
        let sql = state.storage().sql();
        let records = SqlRecords::new(sql.clone()).expect("Durable Object storage");
        let store = Store::with_records(PathBuf::from("/pully-server"), Box::new(records));
        let days = SqlDays::new(sql).expect("Durable Object storage");
        let android = R2Android {
            bucket: SendWrapper::new(env.bucket("PULLY_ANDROID").expect("Android bucket")),
        };
        let host = ServerHost::new(store, None, None).with_android_files(Arc::new(android));
        let app = AppState::from_host_with_analytics(host, Analytics::in_day_files(Arc::new(days)))
            .expect("server state");
        let allow = pulls_review_server::rt::env::var("PULLY_PUBLIC_URL")
            .into_iter()
            .collect();
        let http =
            HttpState::new(app, "localhost", DEV_PORT, allow, true, None).expect("server address");
        Self {
            state,
            http,
            rejected: RefCell::default(),
        }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        // Only the Worker's import route sends these, after checking the import token.
        match request.path().as_str() {
            "/import/v1/server" => {
                let batch: import::Batch = request.json().await?;
                let records = SqlRecords::new(self.state.storage().sql())?;
                return import::answer(import::keep_absent(&records, batch));
            }
            "/import/v1/analytics" => {
                let days: import::Days = request.json().await?;
                let analytics = SqlDays::new(self.state.storage().sql())?;
                return import::answer(import::days(&analytics, days));
            }
            _ => {}
        }
        // The alarm persists, so the first request after a deploy starts the refresh for good.
        let storage = self.state.storage();
        if storage.get_alarm().await?.is_none() {
            storage.set_alarm(FIRST_WARM).await?;
        }
        answer(router(self.http.clone()), &mut request).await
    }

    /// Refreshes the open pull requests the way the native server's `warm` loop does, then
    /// sets the next alarm. Alarms never overlap, so the rejections are not borrowed twice.
    async fn alarm(&self) -> Result<Response> {
        let mut rejected = self.rejected.take();
        warm_round(&self.http.app, &mut rejected).await;
        self.rejected.replace(rejected);
        self.state.storage().set_alarm(WARM_EVERY).await?;
        Response::empty()
    }
}

/// Answers `request` with `router`.
async fn answer(mut router: axum::Router, request: &mut Request) -> Result<Response> {
    let head = request.method() == worker::Method::Head;
    let path = request.path();
    let mut builder = axum::http::Request::builder()
        .method(request.method().as_ref())
        .uri(request.url()?.as_str());
    for (name, value) in request.headers() {
        if name.eq_ignore_ascii_case("cookie") {
            if let Some(value) = request_headers::forwarded_cookie(&path, &value) {
                builder = builder.header(name, value);
            }
        } else {
            builder = builder.header(name, value);
        }
    }
    // Bound the new personal workspace before materializing the Worker's request body;
    // the backend applies the same limits after adapting it into axum.
    let body = if request.path().starts_with("/workspace/") {
        let limit = if request.path() == "/workspace/api/events" {
            2
        } else {
            4
        } * 1024
            * 1024;
        let mut body = Vec::new();
        if let Ok(mut stream) = request.stream() {
            while let Some(chunk) = stream.next().await {
                let chunk = chunk?;
                if body.len().saturating_add(chunk.len()) > limit {
                    return Response::from_json(&std::collections::BTreeMap::from([(
                        "error",
                        "Workspace request is too large.",
                    )]))
                    .map(|response| response.with_status(413));
                }
                body.extend_from_slice(&chunk);
            }
        }
        body
    } else {
        request.bytes().await?
    };
    let request = builder
        .body(Body::from(body))
        .map_err(|e| worker::Error::RustError(e.to_string()))?;
    let response = match router.call(request).await {
        Ok(response) => response,
        Err(never) => match never {},
    };
    worker_response(response, head)
}

/// Converts the router's response. The runtime drops `Content-Length` from a streamed body
/// unless the stream's length is fixed, and the phone shows update download progress from it,
/// so a response that states its length is sent through a `FixedLengthStream`.
fn worker_response(response: axum::response::Response, head: bool) -> Result<Response> {
    let length = response
        .headers()
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok()?.parse::<u64>().ok());
    // A response with no body (HEAD, 204, 304, or a stated length of 0) needs no stream.
    let Some(length) =
        length.filter(|&length| length > 0 && !head && !null_body(response.status()))
    else {
        return Response::try_from(response);
    };
    let (parts, body) = response.into_parts();
    let chunks = body.into_data_stream().map(|chunk| {
        chunk
            .map(|bytes| bytes.to_vec())
            .map_err(|e| worker::Error::RustError(e.to_string()))
    });
    let fixed: worker::worker_sys::FixedLengthStream =
        worker::FixedLengthStream::wrap(chunks, length).into();
    let mut sent = Response::from_body(worker::ResponseBody::Stream(fixed.readable()))?
        .with_status(parts.status.as_u16());
    for (name, value) in &parts.headers {
        if let Ok(value) = value.to_str() {
            sent.headers_mut().append(name.as_str(), value)?;
        }
    }
    Ok(sent)
}

/// Whether the runtime refuses any body for `status`.
fn null_body(status: axum::http::StatusCode) -> bool {
    matches!(status.as_u16(), 101 | 204 | 205 | 304)
}

/// [`Records`] in Durable Object SQLite. A row holds at most 2 MB, so a record is stored as
/// numbered parts of up to 1 MiB. Each call runs without an `await`, so its statements commit
/// together.
struct SqlRecords {
    sql: SqlStorage,
}

pub(crate) const PART: usize = 1024 * 1024;

impl SqlRecords {
    fn new(sql: SqlStorage) -> Result<Self> {
        sql.exec(
            "CREATE TABLE IF NOT EXISTS records (id TEXT NOT NULL, part INTEGER NOT NULL, \
             value BLOB NOT NULL, PRIMARY KEY (id, part)) WITHOUT ROWID",
            None,
        )?;
        Ok(Self { sql })
    }
}

pub(crate) fn failed(error: worker::Error) -> anyhow::Error {
    anyhow::anyhow!("Durable Object storage failed: {error}")
}

/// A storage error as the Worker's own.
pub(crate) fn storage(error: anyhow::Error) -> worker::Error {
    worker::Error::RustError(error.to_string())
}

impl Records for SqlRecords {
    fn get(&self, id: &str) -> anyhow::Result<Option<Vec<u8>>> {
        let cursor = self
            .sql
            .exec(
                "SELECT value FROM records WHERE id = ? ORDER BY part",
                vec![id.into()],
            )
            .map_err(failed)?;
        let mut bytes = None::<Vec<u8>>;
        for row in cursor.raw() {
            let row = row.map_err(failed)?;
            match row.into_iter().next() {
                Some(worker::SqlStorageValue::Blob(part)) => {
                    bytes.get_or_insert_with(Vec::new).extend_from_slice(&part)
                }
                _ => anyhow::bail!("Invalid stored record"),
            }
        }
        Ok(bytes)
    }
    fn put(&self, id: &str, bytes: &[u8]) -> anyhow::Result<()> {
        self.sql
            .exec("DELETE FROM records WHERE id = ?", vec![id.into()])
            .map_err(failed)?;
        // An empty record still needs one row to exist.
        let parts: Vec<&[u8]> = if bytes.is_empty() {
            vec![bytes]
        } else {
            bytes.chunks(PART).collect()
        };
        for (index, part) in parts.into_iter().enumerate() {
            self.sql
                .exec(
                    "INSERT INTO records (id, part, value) VALUES (?, ?, ?)",
                    vec![id.into(), (index as i64).into(), part.to_vec().into()],
                )
                .map_err(failed)?;
        }
        Ok(())
    }
    fn delete(&self, id: &str) -> anyhow::Result<bool> {
        let cursor = self
            .sql
            .exec("DELETE FROM records WHERE id = ?", vec![id.into()])
            .map_err(failed)?;
        // Writes are counted as the cursor is read.
        for row in cursor.raw() {
            row.map_err(failed)?;
        }
        Ok(cursor.rows_written() > 0)
    }
    fn ids(&self) -> anyhow::Result<Vec<String>> {
        self.ids_with_prefix("")
    }
    fn ids_with_prefix(&self, prefix: &str) -> anyhow::Result<Vec<String>> {
        let end = format!("{prefix}\u{7f}");
        let cursor = self
            .sql
            .exec(
                "SELECT DISTINCT id FROM records WHERE id >= ? AND id < ? ORDER BY id",
                vec![prefix.into(), end.as_str().into()],
            )
            .map_err(failed)?;
        let mut ids = Vec::new();
        for row in cursor.raw() {
            match row.map_err(failed)?.into_iter().next() {
                Some(worker::SqlStorageValue::String(id)) => ids.push(id),
                _ => anyhow::bail!("Invalid stored record id"),
            }
        }
        Ok(ids)
    }
}

/// Analytics [`DayFiles`] in Durable Object SQLite: one row per event, each under 8 KiB.
struct SqlDays {
    sql: SqlStorage,
}

impl SqlDays {
    fn new(sql: SqlStorage) -> Result<Self> {
        sql.exec(
            "CREATE TABLE IF NOT EXISTS analytics (day TEXT NOT NULL, line TEXT NOT NULL)",
            None,
        )?;
        sql.exec(
            "CREATE INDEX IF NOT EXISTS analytics_day ON analytics (day)",
            None,
        )?;
        Ok(Self { sql })
    }
}

impl DayFiles for SqlDays {
    fn append(&self, day: &str, lines: &[String]) -> anyhow::Result<()> {
        // No await between the inserts, so they commit together or not at all.
        for line in lines {
            self.sql
                .exec(
                    "INSERT INTO analytics (day, line) VALUES (?, ?)",
                    vec![day.into(), line.as_str().into()],
                )
                .map_err(failed)?;
        }
        Ok(())
    }
    fn read(&self, day: &str) -> anyhow::Result<Vec<String>> {
        let mut lines = Vec::new();
        self.visit(day, &mut |line| lines.push(line.to_owned()))?;
        Ok(lines)
    }
    fn visit(&self, day: &str, line: &mut dyn FnMut(&str)) -> anyhow::Result<()> {
        let cursor = self
            .sql
            .exec(
                "SELECT line FROM analytics WHERE day = ? ORDER BY rowid",
                vec![day.into()],
            )
            .map_err(failed)?;
        for row in cursor.raw() {
            match row.map_err(failed)?.into_iter().next() {
                Some(worker::SqlStorageValue::String(stored)) => line(&stored),
                _ => anyhow::bail!("Invalid stored analytics line"),
            }
        }
        Ok(())
    }
    fn days(&self) -> anyhow::Result<Vec<String>> {
        let cursor = self
            .sql
            .exec("SELECT DISTINCT day FROM analytics", None)
            .map_err(failed)?;
        let mut days = Vec::new();
        for row in cursor.raw() {
            match row.map_err(failed)?.into_iter().next() {
                Some(worker::SqlStorageValue::String(day)) => days.push(day),
                _ => anyhow::bail!("Invalid stored analytics day"),
            }
        }
        Ok(days)
    }
    fn remove(&self, day: &str) -> anyhow::Result<()> {
        self.sql
            .exec("DELETE FROM analytics WHERE day = ?", vec![day.into()])
            .map_err(failed)?;
        Ok(())
    }
}

/// Published Android files (`pulls.apk`, `latest.json`, `past/`, `changelog/`) in R2, under
/// the same names as in the native server's Android directory.
struct R2Android {
    bucket: SendWrapper<Bucket>,
}

impl AndroidFiles for R2Android {
    fn open<'a>(&'a self, name: &'a str) -> BoxFuture<'a, anyhow::Result<Option<HostFile>>> {
        Box::pin(SendWrapper::new(async move {
            let Some(object) = self
                .bucket
                .get(name)
                .execute()
                .await
                .map_err(|e| anyhow::anyhow!("Reading the Android bucket failed: {e}"))?
            else {
                return Ok(None);
            };
            let size = object.size();
            let Some(body) = object.body() else {
                return Ok(None);
            };
            let stream = body
                .stream()
                .map_err(|e| anyhow::anyhow!("Reading the Android bucket failed: {e}"))?
                .map(|chunk| chunk.map_err(|e| std::io::Error::other(e.to_string())));
            Ok(Some(HostFile {
                body: Box::pin(SendWrapper::new(stream)),
                size,
            }))
        }))
    }
}
