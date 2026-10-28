//! The one-off copy of the VPS server's data into the Worker (`scripts/import-vps.mjs`). The
//! route is off, answering 404 like any unknown path, unless the `PULLY_IMPORT_TOKEN` secret is
//! set, and then answers only a request carrying that token. It never replaces a record the
//! Worker already has, except the sign-in store, which is copied whole. Each batch goes to the
//! object that keeps its data: the server's records and analytics to `PullyServer`, the sign-in
//! store to `PersonalServer`, and each account's saved data, through `PersonalServer`, to that
//! account's own `AccountServer`. Deleting the secret afterwards turns the route off and, as a
//! new deployment, restarts every object, so none keeps state read before the copy.

use base64::{engine::general_purpose::STANDARD, Engine};
use pulls_review_server::{analytics::DayFiles, store::Records};
use serde::{Deserialize, Serialize};
use worker::{Env, Method, Request, Response, Result};

/// The request header the import token travels in.
const HEADER: &str = "x-pully-import";

/// One stored record, its bytes in base64.
#[derive(Serialize, Deserialize)]
pub(crate) struct Record {
    pub id: String,
    pub value: String,
}

/// Records for one store; `key` names the account for an account's store.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Batch {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key: Option<String>,
    pub records: Vec<Record>,
}

/// Analytics lines by UTC day (`YYYY-MM-DD`).
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Days {
    days: Vec<Day>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Day {
    day: String,
    lines: Vec<String>,
}

/// What one batch did.
#[derive(Serialize, Default)]
pub(crate) struct Imported {
    written: usize,
    existing: usize,
    /// The batch named a store that is not an account's, so nothing was written.
    ignored: bool,
}

impl Imported {
    pub(crate) fn skipped() -> Self {
        Self {
            ignored: true,
            ..Self::default()
        }
    }
}

/// Answers with what a batch did, or 400 with why it was refused.
pub(crate) fn answer(result: anyhow::Result<Imported>) -> Result<Response> {
    match result {
        Ok(imported) => Response::from_json(&imported),
        Err(error) => Response::error(error.to_string(), 400),
    }
}

/// Checks the import token and passes the request to the object that keeps its data.
pub(crate) async fn route(request: Request, env: &Env) -> Result<Response> {
    let Ok(token) = env
        .secret("PULLY_IMPORT_TOKEN")
        .map(|token| token.to_string())
    else {
        return Response::error("Not found", 404);
    };
    let presented = request.headers().get(HEADER)?.unwrap_or_default();
    if token.len() < 32
        || !same(presented.as_bytes(), token.as_bytes())
        || request.method() != Method::Post
    {
        return Response::error("Not found", 404);
    }
    let object = match request.path().as_str() {
        "/import/v1/server" | "/import/v1/analytics" => {
            env.durable_object("PULLY_SERVER")?.get_by_name("server")?
        }
        "/import/v1/personal-auth" | "/import/v1/tenant" => env
            .durable_object("PERSONAL_SERVER")?
            .get_by_name("personal")?,
        _ => return Response::error("Not found", 404),
    };
    object.fetch_with_request(request).await
}

/// Compares the token without stopping at the first difference.
fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |diff, (x, y)| diff | (x ^ y)) == 0
}

fn decoded(record: &Record) -> anyhow::Result<Vec<u8>> {
    anyhow::ensure!(
        !record.id.is_empty()
            && record.id.len() <= 1024
            && record.id.bytes().all(|b| b.is_ascii_graphic())
            && !record
                .id
                .split('/')
                .any(|part| part.is_empty() || part.starts_with('.')),
        "Invalid record id"
    );
    STANDARD
        .decode(&record.value)
        .map_err(|_| anyhow::anyhow!("Invalid record value"))
}

/// Writes each record of `batch` whose id `records` does not have yet.
pub(crate) fn keep_absent(records: &dyn Records, batch: Batch) -> anyhow::Result<Imported> {
    let mut imported = Imported::default();
    for record in &batch.records {
        let bytes = decoded(record)?;
        if records.get(&record.id)?.is_some() {
            imported.existing += 1;
        } else {
            records.put(&record.id, &bytes)?;
            imported.written += 1;
        }
    }
    Ok(imported)
}

/// Writes every record of `batch`, replacing what `records` had under the same ids.
pub(crate) fn replace(records: &dyn Records, batch: Batch) -> anyhow::Result<Imported> {
    let mut imported = Imported::default();
    for record in &batch.records {
        records.put(&record.id, &decoded(record)?)?;
        imported.written += 1;
    }
    Ok(imported)
}

/// Every record of `records`.
pub(crate) fn dump(records: &dyn Records) -> anyhow::Result<Vec<Record>> {
    let mut all = Vec::new();
    for id in records.ids()? {
        if let Some(bytes) = records.get(&id)? {
            all.push(Record {
                id,
                value: STANDARD.encode(bytes),
            });
        }
    }
    Ok(all)
}

/// Appends each day's lines to `analytics`, unless it already has lines for that day.
pub(crate) fn days(analytics: &dyn DayFiles, days: Days) -> anyhow::Result<Imported> {
    let mut imported = Imported::default();
    for day in days.days {
        anyhow::ensure!(
            day.day.len() == 10
                && day
                    .day
                    .bytes()
                    .enumerate()
                    .all(|(i, b)| if i == 4 || i == 7 {
                        b == b'-'
                    } else {
                        b.is_ascii_digit()
                    }),
            "Invalid analytics day"
        );
        if analytics.read(&day.day)?.is_empty() {
            analytics.append(&day.day, &day.lines)?;
            imported.written += 1;
        } else {
            imported.existing += 1;
        }
    }
    Ok(imported)
}
