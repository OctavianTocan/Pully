//! The hosted Pully server: the Rust server (`backend/`) built for Cloudflare Workers. The
//! Worker sends every request to one `PullyServer` Durable Object, which answers it with the
//! server's own router and keeps the server's data in its SQLite storage.

use axum::body::Body;
use pulls_review_server::{
    host::ServerHost,
    store::{Records, Store},
    transport::{router, HttpState},
    AppState,
};
use std::path::PathBuf;
use tower_service::Service;
use worker::{durable_object, event, Context, Env, Request, Response, Result, SqlStorage, State};

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

/// The port `wrangler dev` listens on, so local browser origins are trusted.
const DEV_PORT: u16 = 8787;

#[event(start)]
fn start() {
    console_error_panic_hook::set_once();
}

#[event(fetch)]
async fn fetch(request: Request, env: Env, _: Context) -> Result<Response> {
    env.durable_object("PULLY_SERVER")?
        .get_by_name("server")?
        .fetch_with_request(request)
        .await
}

#[durable_object]
pub struct PullyServer {
    http: HttpState,
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
        let records = SqlRecords::new(state.storage().sql()).expect("Durable Object storage");
        let store = Store::with_records(PathBuf::from("/pully-server"), Box::new(records));
        let app = AppState::from_host(ServerHost::new(store, None, None)).expect("server state");
        let allow = pulls_review_server::rt::env::var("PULLY_PUBLIC_URL")
            .into_iter()
            .collect();
        let http = HttpState::new(app, "localhost", DEV_PORT, allow, true, None)
            .expect("server address");
        Self { http }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        let mut builder = axum::http::Request::builder()
            .method(request.method().as_ref())
            .uri(request.url()?.as_str());
        for (name, value) in request.headers() {
            builder = builder.header(name, value);
        }
        let body = request.bytes().await?;
        let request = builder
            .body(Body::from(body))
            .map_err(|e| worker::Error::RustError(e.to_string()))?;
        let response = match router(self.http.clone()).call(request).await {
            Ok(response) => response,
            Err(never) => match never {},
        };
        Response::try_from(response)
    }
}

/// [`Records`] in Durable Object SQLite. A row holds at most 2 MB, so a record is stored as
/// numbered parts of up to 1 MiB. Each call runs without an `await`, so its statements commit
/// together.
struct SqlRecords {
    sql: SqlStorage,
}

const PART: usize = 1024 * 1024;

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

fn failed(error: worker::Error) -> anyhow::Error {
    anyhow::anyhow!("Durable Object storage failed: {error}")
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
        let cursor = self
            .sql
            .exec("SELECT DISTINCT id FROM records", None)
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
