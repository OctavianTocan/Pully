//! Each personal account's own Durable Object, named by `account_key` of its account. It keeps
//! the account's saved data and caches in its own SQLite, answers the account's personal reads
//! and workspace with the server's own router, and refreshes the account's open pull requests on
//! its alarm while someone is signed in to it. Only `PersonalServer` reaches it: it forwards each
//! request with the session it resolved, and answers sign-out and background sessions for it.

use crate::{
    answer, import,
    personal::{store, ScopedRecords},
    storage,
};
use futures_util::future::BoxFuture;
use pulls_review_server::{
    public_server::{HostedAccount, HostedAccountServer, SessionAuthority},
    scheduler::WARM_EVERY,
};
use send_wrapper::SendWrapper;
use std::{sync::Arc, time::Duration};
use tokio::sync::OnceCell;
use worker::{
    durable_object, Env, Headers, Method, ObjectNamespace, Request, RequestInit, Response, Result,
    State,
};

/// How soon after a request the first refresh runs, as the native server waits.
const FIRST_WARM: Duration = Duration::from_secs(5);
/// Set once the records this account kept in `PersonalServer` have been copied here.
const COPIED: &str = "legacy-records-copied";
/// The account the object last served, which its alarm refreshes.
const ACCOUNT_ID: &str = "account-id";

#[durable_object]
pub struct AccountServer {
    state: State,
    env: Env,
    server: OnceCell<HostedAccountServer>,
}

impl worker::DurableObject for AccountServer {
    fn new(state: State, env: Env) -> Self {
        // The object may be the first in this isolate: AI work goes to the login's container.
        ai_kit::home::install(&env, "AGENT_HOME").expect("Agent homes");
        Self {
            state,
            env,
            server: OnceCell::new(),
        }
    }

    async fn fetch(&self, mut request: Request) -> Result<Response> {
        if request.path() == "/internal/v1/import" {
            let batch: import::Batch = request.json().await?;
            let account = self.state.id().name().unwrap_or_default();
            let sql = self.state.storage().sql();
            ScopedRecords::create(&sql)?;
            let records = ScopedRecords::scope(sql, format!("tenant:{account}"));
            return import::answer(import::keep_absent(&records, batch));
        }
        let server = self.server().await?;
        if request.path() == "/internal/v1/retire" {
            let (hash, credential): (String, String) = request.json().await?;
            server.retire(&hash, &credential);
            return Response::empty();
        }
        let response = answer(server.router(), &mut request).await;
        if let Some(account_id) = server.account_id() {
            let storage = self.state.storage();
            if storage.get::<String>(ACCOUNT_ID).await?.as_deref() != Some(&account_id) {
                storage.put(ACCOUNT_ID, account_id).await?;
            }
            if storage.get_alarm().await?.is_none() {
                storage.set_alarm(FIRST_WARM).await?;
            }
        }
        response
    }

    /// Refreshes the account's open pull requests, then sets the next alarm, until nobody is
    /// signed in to the account with repository access.
    async fn alarm(&self) -> Result<Response> {
        let storage = self.state.storage();
        let Some(account_id) = storage.get::<String>(ACCOUNT_ID).await? else {
            return Response::empty();
        };
        if self.server().await?.refresh(&account_id).await {
            storage.set_alarm(WARM_EVERY).await?;
        }
        Response::empty()
    }
}

impl AccountServer {
    async fn server(&self) -> Result<&HostedAccountServer> {
        self.server
            .get_or_try_init(|| async {
                let account = self
                    .state
                    .id()
                    .name()
                    .ok_or_else(|| worker::Error::RustError("Unnamed account object".into()))?;
                let sql = self.state.storage().sql();
                ScopedRecords::create(&sql)?;
                let personal = self.env.durable_object("PERSONAL_SERVER")?;
                self.copy_legacy_records(&personal, &account).await?;
                let stores_sql = sql.clone();
                HostedAccountServer::new(HostedAccount {
                    account,
                    stores: Arc::new(move |key| {
                        Ok(store(stores_sql.clone(), format!("tenant:{key}")))
                    }),
                    authority: Arc::new(SignIn {
                        personal: SendWrapper::new(personal),
                    }),
                })
                .map_err(|e| worker::Error::RustError(e.to_string()))
            })
            .await
    }

    /// Copies, once, the saved data this account kept in `PersonalServer` before accounts had
    /// their own objects. The copy there is left as it was.
    async fn copy_legacy_records(&self, personal: &ObjectNamespace, account: &str) -> Result<()> {
        let kept = self.state.storage();
        if kept.get::<bool>(COPIED).await?.is_some() {
            return Ok(());
        }
        let mut response = post(personal, "/internal/v1/legacy-records", None, &account).await?;
        if response.status_code() != 200 {
            return Err(worker::Error::RustError(
                "The sign-in object did not return the account's records".into(),
            ));
        }
        let records: Vec<import::Record> = response.json().await?;
        let scope = ScopedRecords::scope(self.state.storage().sql(), format!("tenant:{account}"));
        import::keep_absent(&scope, import::Batch { key: None, records }).map_err(storage)?;
        kept.put(COPIED, true).await
    }
}

/// The account object's way to the sign-in object.
struct SignIn {
    personal: SendWrapper<ObjectNamespace>,
}

impl SessionAuthority for SignIn {
    fn sign_out<'a>(&'a self, bearer: &'a str) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(SendWrapper::new(async move {
            let authorization = format!("Bearer {bearer}");
            let response = post(
                &self.personal,
                "/internal/v1/sign-out",
                Some(&authorization),
                &(),
            )
            .await
            .map_err(|e| anyhow::anyhow!("Sign-out failed: {e}"))?;
            anyhow::ensure!(response.status_code() == 200, "Sign-out failed");
            Ok(())
        }))
    }
    fn account<'a>(&'a self, account_id: &'a str) -> BoxFuture<'a, anyhow::Result<Option<String>>> {
        Box::pin(SendWrapper::new(async move {
            let mut response = post(&self.personal, "/internal/v1/account", None, &account_id)
                .await
                .map_err(|e| anyhow::anyhow!("The sign-in object is unavailable: {e}"))?;
            anyhow::ensure!(
                response.status_code() == 200,
                "The sign-in object is unavailable"
            );
            response
                .json()
                .await
                .map_err(|e| anyhow::anyhow!("Invalid session answer: {e}"))
        }))
    }
}

/// Sends one import batch on to the account object `account`.
pub(crate) async fn import(
    accounts: &ObjectNamespace,
    account: &str,
    batch: &import::Batch,
) -> Result<Response> {
    post_to(accounts, account, "/internal/v1/import", None, batch).await
}

/// Tells the account object `account` that the session `hash` at credential revision
/// `credential` has ended.
pub(crate) async fn retire(
    accounts: &ObjectNamespace,
    account: &str,
    hash: &str,
    credential: &str,
) -> Result<()> {
    post_to(
        accounts,
        account,
        "/internal/v1/retire",
        None,
        &(hash, credential),
    )
    .await?;
    Ok(())
}

/// Posts `body` as JSON to the sign-in object.
async fn post(
    personal: &ObjectNamespace,
    path: &str,
    authorization: Option<&str>,
    body: &impl serde::Serialize,
) -> Result<Response> {
    post_to(personal, "personal", path, authorization, body).await
}

async fn post_to(
    namespace: &ObjectNamespace,
    name: &str,
    path: &str,
    authorization: Option<&str>,
    body: &impl serde::Serialize,
) -> Result<Response> {
    let headers = Headers::new();
    headers.set("content-type", "application/json")?;
    if let Some(authorization) = authorization {
        headers.set("authorization", authorization)?;
    }
    let mut init = RequestInit::new();
    init.with_method(Method::Post)
        .with_headers(headers)
        .with_body(Some(serde_json::to_string(body)?.into()));
    let request = Request::new_with_init(&format!("https://internal{path}"), &init)?;
    namespace
        .get_by_name(name)?
        .fetch_with_request(request)
        .await
}
