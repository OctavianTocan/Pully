//! Public browsing and personal GitHub sign-in: the native server's separate public listener
//! (`--public-listen`, `backend/src/public_server.rs`) as its own Durable Object. Its storage is
//! apart from `PullyServer`'s, so the legacy server can never read a personal account's sign-in.
//! Each account's reads and workspace go on to that account's own `AccountServer` object
//! (`account.rs`), with the session this object resolved for the request.

use crate::{account, agent, answer, failed, import, storage, PART};
use pulls_review_server::{
    public_server::{
        hosted_router, served_by_account, HostedAuthority, HostedGrant, HostedPersonal,
        HostedSignIn, GRANT_HEADER,
    },
    store::{Records, Store},
};
use send_wrapper::SendWrapper;
use std::{cell::RefCell, collections::HashSet, path::PathBuf, rc::Rc, sync::Arc};
use tokio::sync::OnceCell;
use worker::{
    durable_object, Env, Headers, Method, ObjectNamespace, Request, RequestInit, Response, Result,
    SqlStorage, SqlStorageValue, State,
};

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
    sign_in: OnceCell<HostedSignIn>,
    accounts: Rc<ObjectNamespace>,
    /// The sessions whose end is already being watched, so each account object hears of it.
    watched: Rc<RefCell<HashSet<(String, String)>>>,
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
            sign_in: OnceCell::new(),
            accounts: Rc::new(env.durable_object("ACCOUNT_SERVER").expect("Account objects")),
            watched: Rc::default(),
        }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        // Only the Worker's import route sends these, after checking the import token.
        if request.path() == "/import/v1/personal-auth" {
            let batch: import::Batch = request.json().await?;
            let sql = self.state.storage().sql();
            ScopedRecords::create(&sql)?;
            let records = ScopedRecords::scope(sql, "personal-auth".into());
            return import::answer(import::replace(&records, batch));
        }
        let sign_in = self
            .sign_in
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
        let path = request.path();
        if path == "/import/v1/tenant" {
            let Some(authority) = &sign_in.authority else {
                return Response::error("GitHub sign-in is not configured", 503);
            };
            let batch: import::Batch = request.json().await?;
            let key = batch.key.clone().unwrap_or_default();
            // Only an account the imported sign-in store knows gets an object; the other
            // stores were caches of one sign-in's reads.
            let accounts = authority.account_keys().await.map_err(storage)?;
            if !accounts.contains(&key) {
                return Response::from_json(&import::Imported::skipped());
            }
            return account::import(&self.accounts, &key, &batch).await;
        }
        if let Some(authority) = &sign_in.authority {
            // Only account objects call these: the Worker forwards no `/internal/` path here.
            if path.starts_with("/internal/") {
                return self.internal(authority, &mut request, &path).await;
            }
            if served_by_account(&path) {
                let authorization = request.headers().get("authorization")?;
                let workspace = path.starts_with("/workspace/");
                if let Some(authorization) = authorization {
                    if let Some(grant) = authority.grant(&authorization, workspace).await {
                        let (account, header) = (grant.account.clone(), grant.header.clone());
                        self.watch(grant);
                        return forward(&self.accounts, &mut request, &account, &header).await;
                    }
                }
                // A bearer that does not resolve gets this object's own answer, the same
                // refusal; its account stores refuse everything, so nothing is saved here.
            }
        }
        answer(sign_in.router.clone(), &mut request).await
    }
}

impl PersonalServer {
    /// The sign-in store. Account data lives in each account's own object, so the factory for
    /// account stores here refuses every account.
    fn personal(&self, vault_keys: &str) -> Result<HostedPersonal> {
        let sql = self.state.storage().sql();
        ScopedRecords::create(&sql)?;
        let auth = store(sql.clone(), "personal-auth".into());
        Ok(HostedPersonal {
            auth,
            tenants: Arc::new(|_| anyhow::bail!("Account data is kept in the account's own object")),
            oauth: OAUTH.into(),
            vault_keys: vault_keys.into(),
        })
    }

    /// The calls account objects make: sign-out, a session for their background work, and,
    /// once, the records an account kept in this object before it had its own.
    async fn internal(
        &self,
        authority: &HostedAuthority,
        request: &mut Request,
        path: &str,
    ) -> Result<Response> {
        if request.method() != Method::Post {
            return Response::error("Not found", 404);
        }
        match path {
            "/internal/v1/sign-out" => {
                let authorization = request.headers().get("authorization")?.unwrap_or_default();
                match authority.sign_out(&authorization).await {
                    Ok(()) => Response::empty(),
                    Err(_) => Response::error("Sign-out failed", 401),
                }
            }
            "/internal/v1/account" => {
                let account_id: String = request.json().await?;
                match authority.account(&account_id).await {
                    Ok(Some(grant)) => {
                        let header = grant.header.clone();
                        self.watch(grant);
                        Response::from_json(&Some(header))
                    }
                    Ok(None) => Response::from_json(&None::<String>),
                    Err(_) => Response::error("Unavailable", 503),
                }
            }
            "/internal/v1/legacy-records" => {
                let account: String = request.json().await?;
                let sql = self.state.storage().sql();
                ScopedRecords::create(&sql)?;
                let records = ScopedRecords::scope(sql, format!("tenant:{account}"));
                Response::from_json(&import::dump(&records).map_err(storage)?)
            }
            _ => Response::error("Not found", 404),
        }
    }

    /// Tells the grant's account object when the session ends (sign-out, disconnect, expiry or
    /// a newer credential), so work in flight there stops as it would here. One watcher per
    /// session and credential revision.
    fn watch(&self, grant: HostedGrant) {
        let key = (grant.hash.clone(), grant.credential.clone());
        if !self.watched.borrow_mut().insert(key.clone()) {
            return;
        }
        let (accounts, watched) = (self.accounts.clone(), self.watched.clone());
        wasm_bindgen_futures::spawn_local(async move {
            grant.retired().await;
            watched.borrow_mut().remove(&key);
            let _ = account::retire(&accounts, &grant.account, &grant.hash, &grant.credential).await;
        });
    }
}

/// Sends `request` on to the account object `account`, with the session resolved for it.
async fn forward(
    accounts: &ObjectNamespace,
    request: &mut Request,
    account: &str,
    grant: &str,
) -> Result<Response> {
    let headers = Headers::new();
    for (name, value) in request.headers() {
        if !name.eq_ignore_ascii_case(GRANT_HEADER) {
            headers.append(&name, &value)?;
        }
    }
    headers.set(GRANT_HEADER, grant)?;
    let mut init = RequestInit::new();
    init.with_method(request.method()).with_headers(headers);
    if let Some(body) = request.inner().body() {
        init.with_body(Some(body.into()));
    }
    let forwarded = Request::new_with_init(&request.url()?.to_string(), &init)?;
    accounts
        .get_by_name(account)?
        .fetch_with_request(forwarded)
        .await
}

pub(crate) fn store(sql: SqlStorage, scope: String) -> Store {
    let root = PathBuf::from("/pully-personal").join(&scope);
    Store::with_records(root, Box::new(ScopedRecords { sql, scope }))
}

/// [`Records`] of one store among several in the same Durable Object SQLite: every row carries
/// its store's scope and every query is bound to it. Records are split into parts like
/// `SqlRecords`, and each call runs without an `await`, so its statements commit together.
pub(crate) struct ScopedRecords {
    sql: SqlStorage,
    scope: String,
}

impl ScopedRecords {
    /// The records of `scope`.
    pub(crate) fn scope(sql: SqlStorage, scope: String) -> Self {
        Self { sql, scope }
    }
    pub(crate) fn create(sql: &SqlStorage) -> Result<()> {
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
