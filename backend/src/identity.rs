//! Server-selected principals and isolated state. No HTTP route accepts these as input.
use crate::{account::Account, host::ServerHost, store::Store, AppState};
use anyhow::{bail, ensure, Context, Result};
use futures_util::StreamExt;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, Weak,
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PrincipalKind {
    LegacyPrivate,
    Guest,
    Account,
}

#[derive(Clone)]
pub(crate) enum RequestIdentity {
    LegacyPrivate,
    Guest,
    Account(GitHubCredential),
}

/// Secret authority is not serializable or debug-printable. A binding is created only after
/// GET /user verifies the OAuth token, then explicitly assigned to a server-owned account.
#[derive(Clone)]
pub(crate) struct GitHubCredential(Arc<Credential>);
struct Credential {
    account_id: String,
    github_id: u64,
    token: String,
    revision: uuid::Uuid,
    active: AtomicBool,
    revoked: tokio::sync::watch::Sender<bool>,
}

pub(crate) struct VerifiedGitHubIdentity {
    github_id: u64,
    login: String,
    token: String,
}

impl VerifiedGitHubIdentity {
    #[cfg(test)]
    pub(crate) fn fixture_response(token: String, body: &[u8]) -> Result<Self> {
        Self::verified_response(token, body)
    }
    pub(crate) fn github_id(&self) -> u64 {
        self.github_id
    }
    pub(crate) fn login(&self) -> &str {
        &self.login
    }
    #[cfg(test)]
    pub(crate) fn fixture(github_id: u64, login: &str, token: &str) -> Result<Self> {
        Self::verified_response(
            token.to_owned(),
            &serde_json::to_vec(&serde_json::json!({"id": github_id, "login": login}))?,
        )
    }
    /// @cc [owner:OctavianTocan,label:security] personal-github-identity-is-verified
    /// Personal GitHub authority MUST be verified against the fixed HTTPS /user API with
    /// redirects refused and a bounded body. Tokens and upstream response bodies MUST NOT
    /// appear in diagnostics. Source: Tavi's 21 Oct 2026 personal-GitHub account request.
    pub(crate) async fn verify(token: String) -> Result<Self> {
        ensure!(
            !token.is_empty() && token.len() <= 4096 && token.bytes().all(|v| v.is_ascii_graphic()),
            "Invalid GitHub credential"
        );
        let http = crate::net::client()
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .context("GitHub sign-in is unavailable")?;
        let response = crate::net::send(
            http.get("https://api.github.com/user")
                .header("User-Agent", "pully")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .bearer_auth(&token),
        )
        .await
        .map_err(|_| anyhow::anyhow!("GitHub could not verify this sign-in"))?;
        ensure!(
            response.status() == reqwest::StatusCode::OK,
            "GitHub could not verify this sign-in"
        );
        let mut bytes = Vec::new();
        let mut chunks = response.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk =
                chunk.map_err(|_| anyhow::anyhow!("GitHub could not verify this sign-in"))?;
            ensure!(
                bytes.len().saturating_add(chunk.len()) <= 64 * 1024,
                "GitHub account response is too large"
            );
            bytes.extend_from_slice(&chunk);
        }
        Self::verified_response(token, &bytes)
    }

    fn verified_response(token: String, body: &[u8]) -> Result<Self> {
        #[derive(Deserialize)]
        struct User {
            id: u64,
            login: String,
        }
        let user: User = serde_json::from_slice(body)
            .map_err(|_| anyhow::anyhow!("GitHub returned an invalid account response"))?;
        ensure!(
            user.id > 0
                && !user.login.is_empty()
                && user.login.len() <= 128
                && !user.login.chars().any(char::is_control),
            "GitHub returned an invalid account response"
        );
        Ok(Self {
            github_id: user.id,
            login: user.login,
            token,
        })
    }

    /// The caller supplies an account obtained through a verified session or a new GitHub identity record.
    pub(crate) fn bind(self, account: &Account) -> Result<GitHubCredential> {
        ensure!(
            !account.id.is_empty()
                && account.id.len() <= 128
                && account
                    .id
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c)),
            "Invalid account identity"
        );
        Ok(GitHubCredential(Arc::new(Credential {
            account_id: account.id.clone(),
            github_id: self.github_id,
            token: self.token,
            revision: uuid::Uuid::new_v4(),
            active: AtomicBool::new(true),
            revoked: tokio::sync::watch::channel(false).0,
        })))
    }
}

impl GitHubCredential {
    /// The credential an account's own Durable Object acts through for a session the sign-in
    /// object resolved (`personal_auth::grant`): the same account, GitHub user, token and
    /// binding revision, so the account's state is the one that binding would have.
    pub(crate) fn granted(
        account_id: &str,
        github_id: u64,
        token: &str,
        revision: uuid::Uuid,
    ) -> Result<Self> {
        ensure!(
            !token.is_empty() && token.len() <= 4096 && token.bytes().all(|v| v.is_ascii_graphic()),
            "Invalid GitHub credential"
        );
        let mut credential = VerifiedGitHubIdentity {
            github_id,
            login: String::new(),
            token: token.to_owned(),
        }
        .bind(&Account {
            id: account_id.to_owned(),
            email: String::new(),
        })?;
        Arc::get_mut(&mut credential.0)
            .expect("new credential")
            .revision = revision;
        Ok(credential)
    }
    /// What [`Self::granted`] needs to make this credential again elsewhere: account, GitHub
    /// user, token and binding revision. Only the sign-in object's grant reads it.
    pub(crate) fn grant_parts(&self) -> Result<(&str, u64, &str, uuid::Uuid)> {
        ensure!(
            self.0.active.load(Ordering::SeqCst),
            "Your GitHub connection needs sign-in again"
        );
        Ok((&self.0.account_id, self.0.github_id, &self.0.token, self.0.revision))
    }
    pub(crate) fn revoke(&self) {
        self.0.active.store(false, Ordering::SeqCst);
        self.0.revoked.send_replace(true);
    }
    /// @cc [owner:OctavianTocan,label:security] personal-revocation-notification-cannot-grant-authority
    /// Revocation MUST invalidate the canonical active binding before notifying captured
    /// work. Subscription MUST install its observer before checking that binding;
    /// notification loss or races MUST NOT revive authority. Source: Tavi's 21 Oct 2026
    /// per-user provider/source-revocation decision and approved multiuser plan.
    pub(crate) fn subscribe_revocation(&self) -> Result<tokio::sync::watch::Receiver<bool>> {
        let observed = self.0.revoked.subscribe();
        ensure!(
            self.0.active.load(Ordering::SeqCst),
            "Your GitHub connection needs sign-in again"
        );
        Ok(observed)
    }
    pub(crate) fn github_id(&self) -> u64 {
        self.0.github_id
    }
    #[cfg(test)]
    pub(crate) fn fixture(account: &str, github_id: u64, token: &str) -> Self {
        VerifiedGitHubIdentity::verified_response(
            token.to_owned(),
            &serde_json::to_vec(&serde_json::json!({"id":github_id,"login":"fixture"})).unwrap(),
        )
        .unwrap()
        .bind(&Account {
            id: account.to_owned(),
            email: "fixture@example.invalid".into(),
        })
        .unwrap()
    }
}

impl RequestIdentity {
    pub(crate) async fn retired(&self) {
        match self {
            Self::Account(credential) => {
                if let Ok(mut receiver) = credential.subscribe_revocation() {
                    let _ = receiver.wait_for(|retired| *retired).await;
                }
            }
            _ => std::future::pending().await,
        }
    }
    pub(crate) fn kind(&self) -> PrincipalKind {
        match self {
            Self::LegacyPrivate => PrincipalKind::LegacyPrivate,
            Self::Guest => PrincipalKind::Guest,
            Self::Account(_) => PrincipalKind::Account,
        }
    }
    pub(crate) fn ensure_current(&self) -> Result<()> {
        if let Self::Account(credential) = self {
            ensure!(
                credential.0.active.load(Ordering::SeqCst),
                "Your GitHub connection needs sign-in again"
            );
        }
        Ok(())
    }
    pub(crate) fn is_legacy(&self) -> bool {
        matches!(self, Self::LegacyPrivate)
    }
    /// The GitHub user id of a current personal session, `None` for every other principal.
    pub(crate) fn github_id(&self) -> Option<u64> {
        match self {
            Self::Account(credential) if self.ensure_current().is_ok() => Some(credential.github_id()),
            _ => None,
        }
    }
    pub(crate) fn account_id(&self) -> Result<&str> {
        self.ensure_current()?;
        match self {
            Self::Account(credential) => Ok(&credential.0.account_id),
            _ => bail!("A personal GitHub session is required"),
        }
    }

    /// @cc [owner:OctavianTocan,label:security] personal-and-guest-never-use-operator-github
    /// Guest requests MUST have no GitHub token. Personal requests MUST use only their
    /// bound account credential and MUST fail after revocation. Neither MAY invoke or
    /// fall back to environment credentials or gh CLI login, even after failure. Source:
    /// Tavi's 21 Oct 2026 public browsing and personal GitHub sign-in request.
    pub(crate) async fn github_token<F, Fut>(&self, legacy: F) -> Result<Option<String>>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Option<String>>,
    {
        self.ensure_current()?;
        match self {
            Self::LegacyPrivate => Ok(legacy().await),
            Self::Guest => Ok(None),
            Self::Account(credential) => Ok(Some(credential.0.token.clone())),
        }
    }

    fn namespace(&self) -> Result<String> {
        self.ensure_current()?;
        let key = match self {
            Self::LegacyPrivate => bail!("Legacy private state is not a public tenant"),
            Self::Guest => "public-guest-v1".to_string(),
            Self::Account(credential) => format!(
                "account-v1:{}:{}",
                credential.0.account_id, credential.0.revision
            ),
        };
        Ok(hex::encode(Sha256::digest(key.as_bytes())))
    }
}

/// Opens the store of one tenant, named by its namespace (64 hex digits). Natively each tenant
/// has its own directory; the Worker keeps each in its own scope of Durable Object storage.
pub(crate) type TenantStores = Arc<dyn Fn(&str) -> Result<Store> + Send + Sync>;

/// One bounded owner for each tenant's caches, jobs, model catalog and analytics in this server lifetime.
#[derive(Clone)]
pub(crate) struct TenantStates {
    stores: TenantStores,
    limit: usize,
    states: Arc<Mutex<HashMap<String, Arc<AppState>>>>,
    workspace_stores: Arc<Mutex<HashMap<String, Weak<Store>>>>,
}

impl TenantStates {
    /// Tenants in their own directories under `root`.
    #[cfg(not(target_arch = "wasm32"))]
    pub(crate) fn new(root: PathBuf, limit: usize) -> Result<Self> {
        let root = Store::new(root)?.root().to_owned();
        Self::with_stores(
            Arc::new(move |key| {
                let path = root.join(key);
                match std::fs::create_dir(&path) {
                    Ok(()) => {}
                    Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
                    Err(_) => bail!("Could not create account storage"),
                }
                let metadata = std::fs::symlink_metadata(&path)
                    .context("Could not inspect account storage")?;
                ensure!(
                    metadata.is_dir()
                        && !metadata.file_type().is_symlink()
                        && std::fs::canonicalize(&path)? == path,
                    "Account storage must not be a symlink"
                );
                Store::new(path)
            }),
            limit,
        )
    }
    pub(crate) fn with_stores(stores: TenantStores, limit: usize) -> Result<Self> {
        ensure!((1..=1024).contains(&limit), "Invalid tenant-state limit");
        Ok(Self {
            stores,
            limit,
            states: Default::default(),
            workspace_stores: Default::default(),
        })
    }

    pub(crate) async fn guest(&self) -> Result<Arc<AppState>> {
        self.state(RequestIdentity::Guest).await
    }
    pub(crate) async fn account(&self, credential: GitHubCredential) -> Result<Arc<AppState>> {
        self.state(RequestIdentity::Account(credential)).await
    }

    /// Durable saved data is keyed by the immutable account, while live state and caches
    /// are keyed by the captured credential revision. The typed-read constructor stays separate.
    pub(crate) async fn workspace(
        &self,
        credential: GitHubCredential,
        origin: url::Url,
    ) -> Result<Arc<AppState>> {
        let identity = RequestIdentity::Account(credential);
        let key = format!("workspace:{}", identity.namespace()?);
        let account = identity.account_id()?;
        let stable = hex::encode(Sha256::digest(
            format!("workspace-account-v1:{account}").as_bytes(),
        ));
        let (states, stores, limit) = (self.states.clone(), self.stores.clone(), self.limit);
        let workspace_stores = self.workspace_stores.clone();
        crate::store::blocking(move || {
            identity.ensure_current()?;
            let mut states = states.lock().unwrap_or_else(|e| e.into_inner());
            states.retain(|_, state| state.identity.ensure_current().is_ok());
            if let Some(state) = states.get(&key) {
                return Ok(state.clone());
            }
            ensure!(
                states.len() < limit,
                "Pully is serving its current account limit. Try again later"
            );
            // Record factories (notably the Worker) may return a fresh Store on every
            // call. Reuse one coordinator per immutable account, while every binding
            // below still gets its own overlay. Weak entries keep revoked states from
            // retaining storage forever, but reuse their coordinator while it is live.
            let mut workspace_stores = workspace_stores.lock().unwrap_or_else(|e| e.into_inner());
            workspace_stores.retain(|_, store| store.strong_count() > 0);
            let durable = match workspace_stores.get(&stable).and_then(Weak::upgrade) {
                Some(store) => store,
                None => {
                    ensure!(
                        workspace_stores.len() < limit,
                        "Pully is serving its current account limit. Try again later"
                    );
                    let store = Arc::new(stores(&stable)?);
                    workspace_stores.insert(stable, Arc::downgrade(&store));
                    store
                }
            };
            drop(workspace_stores);
            let analytics = crate::analytics::Analytics::in_records(durable.as_ref().clone());
            let store = Store::workspace(durable, identity.clone(), &key);
            let mut state =
                AppState::build(ServerHost::new(store, None, None), identity, analytics)?;
            let inner = Arc::get_mut(&mut state).expect("new workspace state");
            inner.personal_workspace = true;
            inner.github_origin = origin;
            inner.identity.ensure_current()?;
            states.insert(key, state.clone());
            Ok(state)
        })
        .await
    }

    /// @cc [owner:OctavianTocan,label:security;architecture] tenant-state-does-not-adopt-legacy-data
    /// Guest and account contexts MUST have separate stores, task registries, caches and
    /// usage records; the same binding MUST reuse its existing context. A new credential
    /// revision MUST NOT adopt prior authority's private caches. Contexts MUST have no
    /// local checkout and MUST NOT resolve state through symlinks or legacy data. Source:
    /// Tavi's 21 Oct 2026 any-user GitHub and public browsing request.
    async fn state(&self, identity: RequestIdentity) -> Result<Arc<AppState>> {
        let key = identity.namespace()?;
        let (states, stores, limit) = (self.states.clone(), self.stores.clone(), self.limit);
        crate::store::blocking(move || {
            identity.ensure_current()?;
            let mut states = states.lock().unwrap_or_else(|e| e.into_inner());
            states.retain(|_, state| state.identity.ensure_current().is_ok());
            if let Some(state) = states.get(&key) {
                return Ok(state.clone());
            }
            ensure!(
                states.len() < limit,
                "Pully is serving its current account limit. Try again later"
            );
            let state =
                AppState::from_identity_host(ServerHost::new(stores(&key)?, None, None), identity)?;
            state.identity.ensure_current()?;
            states.insert(key, state.clone());
            Ok(state)
        })
        .await
    }
}

#[cfg(test)]
mod tests;
