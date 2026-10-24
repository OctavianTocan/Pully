use super::{
    provider::{GitHubAuthority, GitHubProvider, GitHubTokens, ReconnectRequired, VerifiedLogin},
    AuthClock, VaultContext, VaultEnvelope, VaultKeys, VaultPurpose,
};
use crate::{
    account::{sha256, Account, Accounts},
    identity::{GitHubCredential, RequestIdentity},
    store::{blocking, Store},
};
use anyhow::{ensure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock, Weak},
    time::Duration,
};

const STATE_KEY: &str = "personal-auth-v1:state";
const SESSION_LIFETIME: u64 = 30 * 24 * 60 * 60;
const MAX_ACCOUNTS: usize = 1024;
const MAX_SESSIONS: usize = 4096;
const MAX_ACCOUNT_SESSIONS: usize = 32;

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Database {
    version: u32,
    subjects: BTreeMap<String, String>,
    accounts: BTreeMap<String, StoredAccount>,
    sessions: BTreeMap<String, StoredSession>,
}
impl Default for Database {
    fn default() -> Self {
        Self {
            version: 1,
            subjects: BTreeMap::new(),
            accounts: BTreeMap::new(),
            sessions: BTreeMap::new(),
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct StoredAccount {
    github_id: u64,
    login: String,
    revision: u64,
    generation: u64,
    credentials: Option<VaultEnvelope>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct StoredSession {
    account_id: String,
    github_id: u64,
    generation: u64,
    expires_at: u64,
    // A lost one-use claim cannot occupy a long-lived session slot without bearer confirmation.
    confirm_by: Option<u64>,
    #[serde(default)]
    authority: GitHubAuthority,
    sealed: VaultEnvelope,
}
impl StoredSession {
    fn deadline(&self) -> u64 {
        self.confirm_by
            .unwrap_or(self.expires_at)
            .min(self.expires_at)
    }
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SessionClaims {
    hash: String,
    account_id: String,
    github_id: u64,
    generation: u64,
    expires_at: u64,
    confirm_by: Option<u64>,
    #[serde(default)]
    authority: GitHubAuthority,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Credentials {
    tokens: GitHubTokens,
    authority: GitHubAuthority,
    #[serde(default)]
    verified: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SessionStatus {
    pub account_id: String,
    pub github_id: u64,
    pub login: String,
    pub authority: GitHubAuthority,
    pub session_expires_at: u64,
    pub access_expires_at: Option<u64>,
    pub refresh_available: bool,
}

/// The random Pully session is returned once to native storage, never persisted in plaintext.
pub(super) struct IssuedSession {
    pub session: String,
    pub status: SessionStatus,
}

pub(super) struct AuthenticatedSession {
    credential: GitHubCredential,
    hash: String,
    revision: u64,
    generation: u64,
    deadline: u64,
    clock: Arc<dyn AuthClock>,
    pub status: SessionStatus,
}
impl AuthenticatedSession {
    /// Kept inside personal_auth until a reviewed personal request allowlist consumes this authority.
    pub(super) fn identity(&self) -> Result<RequestIdentity> {
        if self.clock.now() >= self.deadline {
            self.credential.revoke();
        }
        let identity = RequestIdentity::Account(self.credential.clone());
        identity.ensure_current()?;
        Ok(identity)
    }
}

/// This capability can only be minted from an actual current legacy account session.
pub(super) struct LegacyLink {
    accounts: Accounts,
    session: String,
    account_id: String,
}
impl LegacyLink {
    pub(super) async fn authorize(accounts: Accounts, session: String) -> Result<Self> {
        let account = accounts
            .by_session_async(Some(&session))
            .await?
            .ok_or_else(|| {
                anyhow::anyhow!("Sign in to the existing account before linking GitHub")
            })?;
        Ok(Self {
            accounts,
            session,
            account_id: account.id,
        })
    }
    fn current_account(&self) -> Result<Account> {
        let account = self
            .accounts
            .by_session(Some(&self.session))?
            .filter(|account| account.id == self.account_id)
            .ok_or_else(|| anyhow::anyhow!("The account linking authorization has expired"))?;
        Ok(account)
    }
}

struct Binding {
    account_id: String,
    revision: u64,
    deadline: u64,
    credential: GitHubCredential,
    expiry: crate::rt::AbortHandle,
}
impl Drop for Binding {
    fn drop(&mut self) {
        self.credential.revoke();
        self.expiry.abort();
    }
}
#[derive(Default)]
struct Runtime {
    bindings: HashMap<String, Binding>,
    refresh_locks: HashMap<String, Arc<tokio::sync::Mutex<()>>>,
    refreshing: Arc<Mutex<HashSet<String>>>,
}
static RUNTIMES: OnceLock<Mutex<HashMap<PathBuf, Weak<Mutex<Runtime>>>>> = OnceLock::new();

#[derive(Clone)]
pub(super) struct PersonalVault {
    store: Store,
    keys: Arc<VaultKeys>,
    runtime: Arc<Mutex<Runtime>>,
    clock: Arc<dyn AuthClock>,
}

struct Snapshot {
    hash: String,
    account_id: String,
    account: StoredAccount,
    session: StoredSession,
    credentials: Credentials,
}

impl PersonalVault {
    /// Store::new and this constructor run in the host's blocking startup path.
    pub(super) fn new(
        store: Store,
        keys: Arc<VaultKeys>,
        clock: Arc<dyn AuthClock>,
    ) -> Result<Self> {
        let mut roots = RUNTIMES
            .get_or_init(Default::default)
            .lock()
            .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
        roots.retain(|_, runtime| runtime.strong_count() > 0);
        let runtime = roots
            .get(store.root())
            .and_then(Weak::upgrade)
            .unwrap_or_else(|| {
                let runtime = Arc::new(Mutex::new(Runtime::default()));
                roots.insert(store.root().to_owned(), Arc::downgrade(&runtime));
                runtime
            });
        Ok(Self {
            store,
            keys,
            runtime,
            clock,
        })
    }

    pub(super) async fn install(
        &self,
        login: VerifiedLogin,
        requested: GitHubAuthority,
        link: Option<LegacyLink>,
        confirm_by: u64,
    ) -> Result<IssuedSession> {
        self.install_authorized(login, requested, link, confirm_by, None)
            .await
    }

    pub(super) async fn install_upgrade(
        &self,
        login: VerifiedLogin,
        source: Arc<AuthenticatedSession>,
        confirm_by: u64,
    ) -> Result<IssuedSession> {
        self.install_authorized(
            login,
            GitHubAuthority::PrivateRepositories,
            None,
            confirm_by,
            Some(source),
        )
        .await
    }

    async fn install_authorized(
        &self,
        login: VerifiedLogin,
        requested: GitHubAuthority,
        link: Option<LegacyLink>,
        confirm_by: u64,
        source: Option<Arc<AuthenticatedSession>>,
    ) -> Result<IssuedSession> {
        let this = self.clone();
        blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            let now = this.clock.now();
            ensure!(confirm_by > now, "Personal sign-in has expired");
            runtime.bindings.retain(|_, binding| binding.deadline > now);
            let (identity, tokens) = login.into_parts();
            ensure!(
                tokens.expires_at.is_none_or(|expiry| expiry > now),
                "GitHub authorization has expired"
            );
            let github_id = identity.github_id();
            let login_name = identity.login().to_owned();
            let authority = tokens.authority(requested);
            let session = format!("pully_{}", random_secret()?);
            let hash = sha256(&session);
            let expires_at = now
                .checked_add(SESSION_LIFETIME)
                .ok_or_else(|| anyhow::anyhow!("Invalid session expiry"))?;
            let confirm_by = confirm_by.min(expires_at);
            let target = link.as_ref().map(LegacyLink::current_account).transpose()?;
            let mut issued = None;
            this.store
                .update(STATE_KEY, |value| {
                    let mut database = decode(value)?;
                    if let Some(source) = &source {
                        source.identity()?;
                        let captured = database
                            .sessions
                            .get(&source.hash)
                            .ok_or(ReconnectRequired)?;
                        this.validate_session(&source.hash, captured)?;
                        let account = database
                            .accounts
                            .get(&captured.account_id)
                            .ok_or(ReconnectRequired)?;
                        ensure!(
                            captured.account_id == source.status.account_id
                                && captured.github_id == github_id
                                && captured.github_id == source.status.github_id
                                && captured.generation == source.generation
                                && captured.confirm_by.is_none()
                                && captured.deadline() > now
                                && account.generation == source.generation
                                && account.revision == source.revision,
                            "The repository consent source changed"
                        );
                    }
                    database
                        .sessions
                        .retain(|_, session| session.deadline() > now);
                    let subject = format!("github.com:{github_id}");
                    let existing = database.subjects.get(&subject).cloned();
                    if let (Some(existing), Some(target)) = (&existing, &target) {
                        ensure!(
                            *existing == target.id,
                            "GitHub is already connected to a different account"
                        );
                    }
                    let account_id = existing
                        .or_else(|| target.as_ref().map(|account| account.id.clone()))
                        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
                    let previous = database.accounts.get(&account_id);
                    if let Some(previous) = previous {
                        ensure!(
                            previous.github_id == github_id,
                            "This account is connected to a different GitHub identity"
                        );
                    }
                    ensure!(
                        previous.is_some() || database.accounts.len() < MAX_ACCOUNTS,
                        "Personal account capacity reached"
                    );
                    ensure!(
                        database.sessions.len() < MAX_SESSIONS
                            && database
                                .sessions
                                .values()
                                .filter(|stored| stored.account_id == account_id)
                                .count()
                                < MAX_ACCOUNT_SESSIONS,
                        "Personal session capacity reached"
                    );
                    let revision = previous.map_or(Ok(1), |previous| {
                        previous
                            .revision
                            .checked_add(1)
                            .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))
                    })?;
                    let generation = previous.map_or(1, |previous| previous.generation);
                    let credentials = Credentials {
                        tokens,
                        authority,
                        verified: true,
                    };
                    let status = status(
                        &account_id,
                        github_id,
                        &login_name,
                        confirm_by,
                        &credentials,
                        now,
                    );
                    let account = StoredAccount {
                        github_id,
                        login: login_name,
                        revision,
                        generation,
                        credentials: Some(this.keys.seal(
                            &credential_context(&account_id, github_id, revision),
                            &serde_json::to_vec(&credentials)?,
                        )?),
                    };
                    let claims = SessionClaims {
                        hash: hash.clone(),
                        account_id: account_id.clone(),
                        github_id,
                        generation,
                        expires_at,
                        confirm_by: Some(confirm_by),
                        authority,
                    };
                    let stored_session = StoredSession {
                        account_id: account_id.clone(),
                        github_id,
                        generation,
                        expires_at,
                        confirm_by: Some(confirm_by),
                        authority,
                        sealed: this.keys.seal(
                            &session_context(&account_id, github_id, generation),
                            &serde_json::to_vec(&claims)?,
                        )?,
                    };
                    database.subjects.insert(subject, account_id.clone());
                    database.accounts.insert(account_id.clone(), account);
                    database.sessions.insert(hash, stored_session);
                    issued = Some((account_id, status));
                    Ok(serde_json::to_value(database)?)
                })
                .map_err(safe_storage)?;
            let (account_id, status) =
                issued.ok_or_else(|| anyhow::anyhow!("Personal sign-in failed"))?;
            runtime
                .bindings
                .retain(|_, binding| binding.account_id != account_id);
            Ok(IssuedSession { session, status })
        })
        .await
    }

    pub(super) async fn resolve(
        &self,
        session: &str,
        provider: &dyn GitHubProvider,
    ) -> Result<AuthenticatedSession> {
        let hash = session_hash(session)?;
        let this = self.clone();
        let first_hash = hash.clone();
        let (snapshot, cached) = blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            runtime
                .bindings
                .retain(|_, binding| binding.deadline > this.clock.now());
            let snapshot = match this.snapshot(first_hash.clone(), false) {
                Ok(snapshot) => snapshot,
                Err(error) => {
                    runtime.bindings.remove(&first_hash);
                    return Err(error);
                }
            };
            let cached = runtime
                .bindings
                .get(&snapshot.hash)
                .filter(|binding| {
                    snapshot.session.confirm_by.is_none()
                        && snapshot.credentials.verified
                        && binding.account_id == snapshot.account_id
                        && binding.revision == snapshot.account.revision
                        && binding.deadline > this.clock.now()
                        && RequestIdentity::Account(binding.credential.clone())
                            .ensure_current()
                            .is_ok()
                })
                .map(|binding| binding.credential.clone());
            ensure!(
                !runtime
                    .refreshing
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?
                    .contains(&snapshot.account_id),
                "GitHub authorization is refreshing"
            );
            if cached.is_none() {
                runtime.bindings.remove(&snapshot.hash);
            }
            Ok((snapshot, cached))
        })
        .await?;
        if let Some(credential) = cached {
            return self.resolved(&snapshot, credential);
        }
        let verified = provider
            .verify(snapshot.credentials.tokens.access_token.clone())
            .await?;
        if verified.github_id() != snapshot.account.github_id {
            self.invalidate_account(snapshot).await?;
            return Err(ReconnectRequired.into());
        }
        let this = self.clone();
        let handle = tokio::runtime::Handle::current();
        blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            let mut current = this.snapshot(hash, false)?;
            ensure!(
                !runtime
                    .refreshing
                    .lock()
                    .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?
                    .contains(&current.account_id),
                "GitHub authorization is refreshing"
            );
            ensure!(
                current.account.revision == snapshot.account.revision
                    && current.account.generation == snapshot.account.generation,
                "Personal authorization changed; sign in again"
            );
            this.confirm_verified(&mut current, verified.login())?;
            let credential = if let Some(binding) =
                runtime.bindings.get(&current.hash).filter(|binding| {
                    current.credentials.verified
                        && binding.account_id == current.account_id
                        && binding.revision == current.account.revision
                        && binding.deadline > this.clock.now()
                        && RequestIdentity::Account(binding.credential.clone())
                            .ensure_current()
                            .is_ok()
                }) {
                binding.credential.clone()
            } else {
                let credential = verified.bind(&Account {
                    id: current.account_id.clone(),
                    email: String::new(),
                })?;
                let deadline = deadline(&current);
                let delay = Duration::from_secs(deadline.saturating_sub(this.clock.now()));
                let timer_credential = credential.clone();
                let timer = handle.spawn(async move {
                    crate::rt::sleep(delay).await;
                    timer_credential.revoke();
                });
                runtime.bindings.insert(
                    current.hash.clone(),
                    Binding {
                        account_id: current.account_id.clone(),
                        revision: current.account.revision,
                        deadline,
                        credential: credential.clone(),
                        expiry: timer.abort_handle(),
                    },
                );
                credential
            };
            this.resolved(&current, credential)
        })
        .await
    }

    pub(super) async fn sign_out(&self, session: &str) -> Result<()> {
        let hash = session_hash(session)?;
        self.sign_out_hash(hash).await
    }

    /// Internal cancellation uses only the hash retained after a one-use native claim.
    pub(super) async fn sign_out_hash(&self, hash: String) -> Result<()> {
        ensure!(
            hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "Invalid personal session"
        );
        let this = self.clone();
        blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            // Revoke captured authority even when storage subsequently becomes unavailable.
            runtime.bindings.remove(&hash);
            this.store
                .update(STATE_KEY, |value| {
                    let mut database = decode(value)?;
                    database.sessions.remove(&hash);
                    Ok(serde_json::to_value(database)?)
                })
                .map_err(safe_storage)?;
            Ok(())
        })
        .await
    }

    pub(super) async fn unlink(&self, session: &str) -> Result<()> {
        let hash = session_hash(session)?;
        let this = self.clone();
        blocking(move || {
            let mut runtime = this
                .runtime
                .lock()
                .map_err(|_| anyhow::anyhow!("Personal authorization is unavailable"))?;
            let snapshot = this.snapshot(hash, true)?;
            runtime
                .bindings
                .retain(|_, binding| binding.account_id != snapshot.account_id);
            this.store
                .update(STATE_KEY, |value| {
                    let mut database = decode(value)?;
                    let account = database
                        .accounts
                        .get_mut(&snapshot.account_id)
                        .ok_or_else(|| anyhow::anyhow!("Personal authorization is unavailable"))?;
                    ensure!(
                        account.revision == snapshot.account.revision,
                        "Personal authorization changed; try again"
                    );
                    account.credentials = None;
                    account.generation = account
                        .generation
                        .checked_add(1)
                        .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))?;
                    account.revision = account
                        .revision
                        .checked_add(1)
                        .ok_or_else(|| anyhow::anyhow!("Credential revision unavailable"))?;
                    database
                        .sessions
                        .retain(|_, session| session.account_id != snapshot.account_id);
                    Ok(serde_json::to_value(database)?)
                })
                .map_err(safe_storage)?;
            Ok(())
        })
        .await
    }

    fn snapshot(&self, hash: String, allow_expired_access: bool) -> Result<Snapshot> {
        let database = decode(self.store.get(STATE_KEY).map_err(safe_storage)?)?;
        let session = database
            .sessions
            .get(&hash)
            .cloned()
            .ok_or(ReconnectRequired)?;
        let account = database
            .accounts
            .get(&session.account_id)
            .cloned()
            .ok_or(ReconnectRequired)?;
        if account.github_id != session.github_id
            || account.generation != session.generation
            || session.deadline() <= self.clock.now()
        {
            return Err(ReconnectRequired.into());
        }
        self.validate_session(&hash, &session)?;
        let envelope = account.credentials.as_ref().ok_or(ReconnectRequired)?;
        let credentials: Credentials = serde_json::from_slice(&self.keys.open(
            &credential_context(&session.account_id, account.github_id, account.revision),
            envelope,
        )?)
        .map_err(|_| anyhow::anyhow!("Invalid personal credential record"))?;
        ensure!(
            allow_expired_access
                || credentials
                    .tokens
                    .expires_at
                    .is_none_or(|expiry| expiry > self.clock.now()),
            "GitHub authorization needs refreshing"
        );
        Ok(Snapshot {
            hash,
            account_id: session.account_id.clone(),
            account,
            session,
            credentials,
        })
    }

    fn validate_session(&self, hash: &str, session: &StoredSession) -> Result<()> {
        let claims: SessionClaims = serde_json::from_slice(&self.keys.open(
            &session_context(&session.account_id, session.github_id, session.generation),
            &session.sealed,
        )?)
        .map_err(|_| anyhow::anyhow!("Invalid personal session record"))?;
        ensure!(
            claims.hash == hash
                && claims.account_id == session.account_id
                && claims.github_id == session.github_id
                && claims.generation == session.generation
                && claims.expires_at == session.expires_at
                && claims.confirm_by == session.confirm_by
                && claims.authority == session.authority,
            "Invalid personal session record"
        );
        Ok(())
    }

    fn resolved(
        &self,
        snapshot: &Snapshot,
        credential: GitHubCredential,
    ) -> Result<AuthenticatedSession> {
        let mut resolved = AuthenticatedSession {
            credential,
            hash: snapshot.hash.clone(),
            revision: snapshot.account.revision,
            generation: snapshot.account.generation,
            deadline: deadline(snapshot),
            clock: self.clock.clone(),
            status: status(
                &snapshot.account_id,
                snapshot.account.github_id,
                &snapshot.account.login,
                snapshot.session.deadline(),
                &snapshot.credentials,
                self.clock.now(),
            ),
        };
        if snapshot.session.authority == GitHubAuthority::PublicRead {
            resolved.status.authority = GitHubAuthority::PublicRead;
        }
        resolved.identity()?;
        Ok(resolved)
    }
}

fn decode(value: Option<serde_json::Value>) -> Result<Database> {
    let database: Database = match value {
        Some(value) => serde_json::from_value(value)
            .map_err(|_| anyhow::anyhow!("Invalid personal authorization state"))?,
        None => Database::default(),
    };
    ensure!(
        database.version == 1
            && database.accounts.len() <= MAX_ACCOUNTS
            && database.subjects.len() <= MAX_ACCOUNTS
            && database.sessions.len() <= MAX_SESSIONS,
        "Invalid personal authorization state"
    );
    ensure!(
        database.accounts.iter().all(|(id, account)| {
            valid_account_id(id)
                && account.github_id > 0
                && account.revision > 0
                && account.generation > 0
                && !account.login.is_empty()
                && account.login.len() <= 128
                && !account.login.chars().any(char::is_control)
                && database
                    .subjects
                    .get(&format!("github.com:{}", account.github_id))
                    == Some(id)
        }) && database.subjects.iter().all(|(subject, id)| {
            database
                .accounts
                .get(id)
                .is_some_and(|account| *subject == format!("github.com:{}", account.github_id))
        }) && database.sessions.iter().all(|(hash, session)| {
            hash.len() == 64
                && hash.bytes().all(|byte| byte.is_ascii_hexdigit())
                && session.expires_at > 0
                && session
                    .confirm_by
                    .is_none_or(|deadline| deadline > 0 && deadline <= session.expires_at)
                && database
                    .accounts
                    .get(&session.account_id)
                    .is_some_and(|account| {
                        account.github_id == session.github_id
                            && account.generation == session.generation
                    })
        }),
        "Invalid personal authorization state"
    );
    Ok(database)
}

fn valid_account_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
}

fn credential_context(account_id: &str, github_id: u64, revision: u64) -> VaultContext<'_> {
    VaultContext {
        account_id,
        github_id,
        revision,
        purpose: VaultPurpose::GitHubCredential,
    }
}
fn session_context(account_id: &str, github_id: u64, generation: u64) -> VaultContext<'_> {
    VaultContext {
        account_id,
        github_id,
        revision: generation,
        purpose: VaultPurpose::PersonalSession,
    }
}
fn status(
    account_id: &str,
    github_id: u64,
    login: &str,
    expires_at: u64,
    credentials: &Credentials,
    now: u64,
) -> SessionStatus {
    SessionStatus {
        account_id: account_id.to_owned(),
        github_id,
        login: login.to_owned(),
        authority: credentials.authority,
        session_expires_at: expires_at,
        access_expires_at: credentials.tokens.expires_at,
        refresh_available: credentials
            .tokens
            .refresh
            .as_ref()
            .is_some_and(|refresh| refresh.expires_at > now),
    }
}
fn deadline(snapshot: &Snapshot) -> u64 {
    snapshot
        .credentials
        .tokens
        .expires_at
        .unwrap_or(snapshot.session.deadline())
        .min(snapshot.session.deadline())
}
fn session_hash(session: &str) -> Result<String> {
    ensure!(
        session
            .strip_prefix("pully_")
            .is_some_and(|secret| super::provider::url_secret(secret, 43, 43)),
        "Invalid personal session"
    );
    Ok(sha256(session))
}
pub(super) fn random_secret() -> Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes)
        .map_err(|_| anyhow::anyhow!("Secure random source unavailable"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}
fn safe_storage(_error: anyhow::Error) -> anyhow::Error {
    anyhow::anyhow!("Personal authorization operation failed")
}

mod refresh;
#[cfg(test)]
mod tests;
