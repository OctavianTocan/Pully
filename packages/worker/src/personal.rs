//! Public browsing and personal GitHub sign-in: the native server's separate public listener
//! (`--public-listen`, `backend/src/public_server.rs`) as its own Durable Object. Its storage is apart from `PullyServer`'s, so the legacy server can never
//! read a personal account's sign-in or data, and each personal account's records are a scope of
//! their own.

use crate::{agent, answer, failed, PART};
use pulls_review_server::{
    public_server::{hosted_router, HostedPersonal},
    store::{Records, Store},
};
use send_wrapper::SendWrapper;
use std::{path::PathBuf, sync::Arc};
use tokio::sync::OnceCell;
use worker::{durable_object, Env, Request, Response, Result, SqlStorage, SqlStorageValue, State};

/// Whether the public/personal object answers `path`. The native public listener serves only
/// these two families; everything else is the legacy server's.
pub(crate) fn serves(path: &str) -> bool {
    path.starts_with("/api/public/")
        || path.starts_with("/api/personal/")
        || path.starts_with("/workspace/")
}

/// The GitHub sign-in Pully uses: the GitHub CLI's device flow (Tavi, 23 Oct 2026 13:36,
/// "what you get when you use GH auth login").
const OAUTH: &str = r#"{"githubCli":true}"#;

#[durable_object]
pub struct PersonalServer {
    state: State,
    /// The vault keys file's text, from the `PULLY_PERSONAL_VAULT_KEYS` secret. Without it
    /// GitHub sign-in is reported as not configured.
    vault_keys: Option<String>,
    router: OnceCell<axum::Router>,
}

impl worker::DurableObject for PersonalServer {
    fn new(state: State, env: Env) -> Self {
        // PersonalServer may be the first object in this isolate. Its workspace uses
        // exactly the same own-login container remote, never a local wasm process.
        let _ = pulls_review_server::agent_remote::set(Arc::new(agent::ObjectRemote {
            homes: SendWrapper::new(env.durable_object("AGENT_HOME").expect("Agent homes")),
        }));
        let mut variables: std::collections::BTreeMap<_, _> =
            pulls_review_server::rt::env::vars().into_iter().collect();
        if let Ok(value) = env.var("PULLS_REVIEW_ANALYTICS") {
            variables.insert("PULLS_REVIEW_ANALYTICS".into(), value.to_string());
        }
        // Pi on the server's own proxy, for the one GitHub account PULLY_PI_GITHUB_ID names (backend CONTRACTS,
        // personal-workspace-never-spends-operator-ai). Read here too, as this object may be the isolate's first.
        for name in ["PULLS_REVIEW_PI_BASE_URL", "PULLS_REVIEW_PI_API_KEY", "PULLS_REVIEW_PI_MODEL", "PULLY_PI_GITHUB_ID"] {
            if let Some(value) = env.secret(name).map(|s| s.to_string()).or_else(|_| env.var(name).map(|v| v.to_string())).ok() {
                variables.insert(name.into(), value);
            }
        }
        pulls_review_server::rt::env::set(variables);
        let vault_keys = env
            .secret("PULLY_PERSONAL_VAULT_KEYS")
            .ok()
            .map(|keys| keys.to_string());
        Self {
            state,
            vault_keys,
            router: OnceCell::new(),
        }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        let router = self
            .router
            .get_or_try_init(|| async {
                let personal = match &self.vault_keys {
                    Some(keys) => Some(self.personal(keys)?),
                    None => None,
                };
                hosted_router(personal)
                    .await
                    .map_err(|e| worker::Error::RustError(e.to_string()))
            })
            .await?;
        answer(router.clone(), &mut request).await
    }
}

impl PersonalServer {
    /// The sign-in store and the factory for each account's store.
    fn personal(&self, vault_keys: &str) -> Result<HostedPersonal> {
        let sql = self.state.storage().sql();
        ScopedRecords::create(&sql)?;
        let auth = store(sql.clone(), "personal-auth".into());
        Ok(HostedPersonal {
            auth,
            tenants: Arc::new(move |key| Ok(store(sql.clone(), format!("tenant:{key}")))),
            oauth: OAUTH.into(),
            vault_keys: vault_keys.into(),
        })
    }
}

fn store(sql: SqlStorage, scope: String) -> Store {
    let root = PathBuf::from("/pully-personal").join(&scope);
    Store::with_records(root, Box::new(ScopedRecords { sql, scope }))
}

/// [`Records`] of one store among several in the same Durable Object SQLite: every row carries
/// its store's scope and every query is bound to it. Records are split into parts like
/// `SqlRecords`, and each call runs without an `await`, so its statements commit together.
struct ScopedRecords {
    sql: SqlStorage,
    scope: String,
}

impl ScopedRecords {
    fn create(sql: &SqlStorage) -> Result<()> {
        sql.exec(
            "CREATE TABLE IF NOT EXISTS scoped_records (scope TEXT NOT NULL, id TEXT NOT NULL, \
             part INTEGER NOT NULL, value BLOB NOT NULL, PRIMARY KEY (scope, id, part)) \
             WITHOUT ROWID",
            None,
        )?;
        Ok(())
    }
}

impl Records for ScopedRecords {
    fn get(&self, id: &str) -> anyhow::Result<Option<Vec<u8>>> {
        let cursor = self
            .sql
            .exec(
                "SELECT value FROM scoped_records WHERE scope = ? AND id = ? ORDER BY part",
                vec![self.scope.as_str().into(), id.into()],
            )
            .map_err(failed)?;
        let mut bytes = None::<Vec<u8>>;
        for row in cursor.raw() {
            match row.map_err(failed)?.into_iter().next() {
                Some(SqlStorageValue::Blob(part)) => {
                    bytes.get_or_insert_with(Vec::new).extend_from_slice(&part)
                }
                _ => anyhow::bail!("Invalid stored record"),
            }
        }
        Ok(bytes)
    }
    fn put(&self, id: &str, bytes: &[u8]) -> anyhow::Result<()> {
        self.sql
            .exec(
                "DELETE FROM scoped_records WHERE scope = ? AND id = ?",
                vec![self.scope.as_str().into(), id.into()],
            )
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
                    "INSERT INTO scoped_records (scope, id, part, value) VALUES (?, ?, ?, ?)",
                    vec![
                        self.scope.as_str().into(),
                        id.into(),
                        (index as i64).into(),
                        part.to_vec().into(),
                    ],
                )
                .map_err(failed)?;
        }
        Ok(())
    }
    fn delete(&self, id: &str) -> anyhow::Result<bool> {
        let cursor = self
            .sql
            .exec(
                "DELETE FROM scoped_records WHERE scope = ? AND id = ?",
                vec![self.scope.as_str().into(), id.into()],
            )
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
        // Store ids are ASCII-encoded. A bound above every encoded suffix keeps the
        // primary-key range literal, including '%' and '_', without a LIKE table scan.
        let end = format!("{prefix}\u{7f}");
        let cursor = self
            .sql
            .exec(
                "SELECT DISTINCT id FROM scoped_records WHERE scope = ? AND id >= ? AND id < ? ORDER BY id",
                vec![self.scope.as_str().into(), prefix.into(), end.as_str().into()],
            )
            .map_err(failed)?;
        let mut ids = Vec::new();
        for row in cursor.raw() {
            match row.map_err(failed)?.into_iter().next() {
                Some(SqlStorageValue::String(id)) => ids.push(id),
                _ => anyhow::bail!("Invalid stored record id"),
            }
        }
        Ok(ids)
    }
}
