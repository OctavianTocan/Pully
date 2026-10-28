//! Opt-in public/personal host. No legacy listener, checkout or operator account is initialized.
mod config;
mod personal;

use crate::{
    identity::{RequestIdentity, TenantStates},
    personal_auth::{account_key, CurrentPersonalSession, GrantedSessions, PersonalAuth, PersonalAuthHost},
    scheduler::Rejections,
    store::{blocking, Store},
};
pub use crate::personal_auth::{SessionAuthority, GRANT_HEADER};
use axum::Router;
pub use config::{PersonalAuthFiles, PublicServerConfig, PublicServerError};
use std::sync::Arc;

/// How many personal accounts one server keeps state for at once.
const TENANT_LIMIT: usize = 256;

/// Construction does not bind or publish a service; the executable explicitly opts into serving it.
pub async fn router(config: PublicServerConfig) -> Result<Router, PublicServerError> {
    let configured = config::load(config).await?;
    let (auth_config, tenant_root) = match configured {
        Some(configured) => (Some(configured.auth), Some(configured.tenant_root)),
        None => (None, None),
    };
    let host = PersonalAuthHost::open(auth_config)
        .await
        .map_err(|_| PublicServerError)?;
    #[cfg(target_arch = "wasm32")]
    let tenants = tenant_root.map(|_| unreachable!("the Worker opens no configured files"));
    #[cfg(not(target_arch = "wasm32"))]
    let tenants = match tenant_root {
        Some(root) => Some(
            blocking(move || TenantStates::new(root, TENANT_LIMIT))
                .await
                .map_err(|_| PublicServerError)?,
        ),
        None => None,
    };
    serve(host, tenants).map(|(router, _)| router)
}

/// Stores the Worker supplies for the personal service: the sign-in store, and one store for
/// each personal account, named by its namespace. The Worker keeps account data in each
/// account's own object ([`HostedAccountServer`]), so its factory here refuses every account.
pub struct HostedPersonal {
    pub auth: Store,
    pub tenants: Arc<dyn Fn(&str) -> anyhow::Result<Store> + Send + Sync>,
    /// The OAuth file's text (`{"githubCli": true}`).
    pub oauth: String,
    /// The vault keys file's text.
    pub vault_keys: String,
}

/// What the Worker's sign-in object serves: its router, and, when GitHub sign-in is
/// configured, the authority it resolves sessions with for each account's own object.
pub struct HostedSignIn {
    pub router: Router,
    pub authority: Option<HostedAuthority>,
}

/// The Worker's form of [`router`]: the same service over the stores and secrets the Worker
/// supplies. Without `personal` it serves public browsing and disabled GitHub discovery.
pub async fn hosted_router(
    personal: Option<HostedPersonal>,
) -> Result<HostedSignIn, PublicServerError> {
    let Some(personal) = personal else {
        let host = PersonalAuthHost::open(None)
            .await
            .map_err(|_| PublicServerError)?;
        let (router, _) = serve(host, None)?;
        return Ok(HostedSignIn {
            router,
            authority: None,
        });
    };
    let (oauth, keys) = config::parse(personal.oauth.as_bytes(), personal.vault_keys.as_bytes())
        .map_err(|_| PublicServerError)?;
    let tenants =
        TenantStates::with_stores(personal.tenants, TENANT_LIMIT).map_err(|_| PublicServerError)?;
    let host = PersonalAuthHost::open_store(personal.auth, oauth, keys)
        .await
        .map_err(|_| PublicServerError)?;
    let (router, auth) = serve(host, Some(tenants))?;
    Ok(HostedSignIn {
        router,
        authority: auth.map(HostedAuthority),
    })
}

fn serve(
    host: PersonalAuthHost,
    tenants: Option<TenantStates>,
) -> Result<(Router, Option<PersonalAuth>), PublicServerError> {
    let (auth_router, auth) = host.into_parts();
    let public = crate::public_api::router_with_personal_github(auth.is_some())
        .map_err(|_| PublicServerError)?;
    let personal = if let (Some(auth), Some(tenants)) = (auth.clone(), tenants) {
        Some(personal::router(auth, tenants).map_err(|_| PublicServerError)?)
    } else {
        None
    };
    Ok((compose(public, auth_router, personal), auth))
}

/// Whether `path` is one of an account's own routes: its personal GitHub reads and its
/// workspace. The Worker's sign-in object forwards these to the account's own object.
pub fn served_by_account(path: &str) -> bool {
    path.starts_with("/workspace/")
        || [
            "/api/personal/v1/github/repositories",
            "/api/personal/v1/github/pulls",
            "/api/personal/v1/github/account/",
        ]
        .iter()
        .any(|prefix| {
            path.strip_prefix(prefix)
                .is_some_and(|rest| rest.is_empty() || rest.starts_with('/') || prefix.ends_with('/'))
        })
}

/// The sign-in object's side of the hand-off to each account's own object.
#[derive(Clone)]
pub struct HostedAuthority(PersonalAuth);

/// A session resolved by the sign-in object for one request or one background round.
pub struct HostedGrant {
    /// The [`GRANT_HEADER`] value to forward.
    pub header: String,
    /// The name of the account's own object.
    pub account: String,
    /// The session's vault hash and credential revision, which [`HostedAccountServer::retire`]
    /// takes.
    pub hash: String,
    pub credential: String,
    session: CurrentPersonalSession,
}

impl HostedGrant {
    /// Resolves when the session's authority ends: sign-out, disconnect, expiry or a newer
    /// credential. The sign-in object then tells the account's object.
    pub async fn retired(&self) {
        self.session.retired().await
    }
}

impl HostedAuthority {
    /// Resolves the caller's bearer for a request to the account's object; `workspace` for its
    /// workspace routes, which need a confirmed session. `None` when it does not resolve: the
    /// sign-in object then answers the request itself, with the same refusal.
    pub async fn grant(&self, authorization: &str, workspace: bool) -> Option<HostedGrant> {
        let mut headers = axum::http::HeaderMap::new();
        headers.insert(axum::http::header::AUTHORIZATION, authorization.parse().ok()?);
        let bearer = crate::personal_auth::personal_bearer(&headers).ok()?;
        let (session, grant) = self.0.grant_session(bearer, workspace).await.ok()?;
        hosted_grant(session, grant)
    }
    /// Signs the session of the `Authorization` header out.
    pub async fn sign_out(&self, authorization: &str) -> anyhow::Result<()> {
        let mut headers = axum::http::HeaderMap::new();
        headers.insert(axum::http::header::AUTHORIZATION, authorization.parse()?);
        let bearer = crate::personal_auth::personal_bearer(&headers)
            .map_err(|_| anyhow::anyhow!("Invalid personal session"))?;
        Ok(self.0.sign_out(bearer).await?)
    }
    /// The names of the objects of every account the vault knows, as [`HostedGrant::account`].
    pub async fn account_keys(&self) -> anyhow::Result<Vec<String>> {
        Ok(self
            .0
            .account_ids()
            .await?
            .iter()
            .map(|id| account_key(id))
            .collect())
    }
    /// A session of `account_id` for the server's own background work, if one is signed in
    /// with repository access.
    pub async fn account(&self, account_id: &str) -> anyhow::Result<Option<HostedGrant>> {
        Ok(self
            .0
            .account_grant(account_id)
            .await?
            .and_then(|(session, grant)| hosted_grant(session, grant)))
    }
}

fn hosted_grant(
    session: CurrentPersonalSession,
    grant: crate::personal_auth::SessionGrant,
) -> Option<HostedGrant> {
    Some(HostedGrant {
        header: grant.header().ok()?,
        account: account_key(grant.account_id()),
        hash: session.hash().to_owned(),
        credential: grant.credential().to_owned(),
        session,
    })
}

/// What the Worker supplies for one account's own object.
pub struct HostedAccount {
    /// The object's name, [`HostedGrant::account`].
    pub account: String,
    /// Opens one of the account's stores, named by its namespace, in the object's storage.
    pub stores: Arc<dyn Fn(&str) -> anyhow::Result<Store> + Send + Sync>,
    /// How the object reaches the sign-in object.
    pub authority: Arc<dyn SessionAuthority>,
}

/// One account's own object: its personal reads and workspace, over the grants the sign-in
/// object forwards, and the background refresh of its open pull requests.
pub struct HostedAccountServer {
    router: Router,
    granted: Arc<GrantedSessions>,
    tenants: TenantStates,
    rejected: tokio::sync::Mutex<Rejections>,
}

impl HostedAccountServer {
    pub fn new(account: HostedAccount) -> anyhow::Result<Self> {
        let granted = Arc::new(GrantedSessions::new(account.authority, account.account));
        let tenants = TenantStates::with_stores(account.stores, TENANT_LIMIT)?;
        Ok(Self {
            router: personal::account_router(granted.clone(), tenants.clone())?,
            granted,
            tenants,
            rejected: Default::default(),
        })
    }
    pub fn router(&self) -> Router {
        self.router.clone()
    }
    /// Ends what this object holds for the session `hash` at credential revision `credential`,
    /// as the sign-in object did.
    pub fn retire(&self, hash: &str, credential: &str) {
        self.granted.retire(hash, Some(credential))
    }
    /// The account this object has served, once a request has reached it.
    pub fn account_id(&self) -> Option<String> {
        self.granted.account_id()
    }
    /// Refreshes the account's open pull requests once, as the native server's warm loop does.
    /// Returns `false` when nobody is signed in to the account with repository access, so the
    /// caller stops scheduling rounds.
    pub async fn refresh(&self, account_id: &str) -> bool {
        let Some(session) = self.granted.background(account_id).await else {
            return false;
        };
        let Ok(RequestIdentity::Account(credential)) = session.identity() else {
            return false;
        };
        let origin = url::Url::parse("https://api.github.com/").expect("GitHub origin");
        let Ok(state) = self.tenants.workspace(credential, origin).await else {
            return true;
        };
        let mut rejected = self.rejected.lock().await;
        crate::scheduler::warm_round(&state, &mut rejected).await;
        true
    }
}

fn compose(public: Router, auth: Router, personal: Option<Router>) -> Router {
    let router = public.merge(auth);
    match personal {
        Some(personal) => router.merge(personal),
        None => router,
    }
}

#[cfg(test)]
mod tests;
