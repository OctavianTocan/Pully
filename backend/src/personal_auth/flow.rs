use super::{
    provider::{
        DeviceDenied, DevicePoll, GitHubAuthority, GitHubProvider, GitHubTokens, OAuthConfig,
        OAuthProvider, VerifiedLogin, DEVICE_VERIFICATION_URL,
    },
    vault::{random_secret, LegacyLink, PersonalVault},
    wire::{
        self, AttemptPhase, AttemptRequest, CancelReply, Capabilities, ClaimReply, FailureKind,
        GitHubCapabilities, StartReply, StartRequest, StatusReply,
    },
    AuthClock, SystemClock, VaultKeys,
};
use crate::{
    account::sha256,
    store::{blocking, Store},
};
use ring::hmac;
use serde::Serialize;
use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Mutex},
    time::Duration,
};

const ATTEMPT_LIFETIME: u64 = 10 * 60;
const POLL_SECONDS: u64 = 2;
const MAX_ATTEMPTS: usize = 128;
const STARTS_PER_MINUTE: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum AuthError {
    InvalidRequest,
    Unauthorized,
    Expired,
    RateLimited,
    Unavailable,
    Reconnect,
    Denied,
}
impl std::fmt::Display for AuthError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::InvalidRequest => "Invalid personal authorization request",
            Self::Unauthorized => "This authorization attempt is unavailable",
            Self::Expired => "This authorization attempt expired; start again",
            Self::RateLimited => "Wait before trying personal authorization again",
            Self::Unavailable => "Personal authorization is temporarily unavailable",
            Self::Reconnect => "GitHub authorization needs reconnecting",
            Self::Denied => "GitHub authorization was not approved",
        })
    }
}
impl std::error::Error for AuthError {}
type Result<T> = std::result::Result<T, AuthError>;

fn vault_error(error: anyhow::Error) -> AuthError {
    if let Some(error) = error.downcast_ref::<AuthError>() {
        *error
    } else if error.is::<super::provider::ReconnectRequired>() {
        AuthError::Reconnect
    } else {
        AuthError::Unavailable
    }
}

/// What an attempt will install once GitHub answers, whichever way it was asked.
struct Grant {
    authority: GitHubAuthority,
    link: Option<LegacyLink>,
    upgrade: Option<CurrentPersonalSession>,
}
struct Pending {
    verifier: String,
    grant: Grant,
}
struct Ready {
    login: VerifiedLogin,
    authority: GitHubAuthority,
    link: Option<LegacyLink>,
    upgrade: Option<CurrentPersonalSession>,
}
enum Phase {
    Pending(Pending),
    /// Waiting for the person to enter the device code; the server polls GitHub itself.
    Device(Grant),
    Exchanging,
    Ready(Ready),
    Claiming,
    Claimed(String),
    Cancelled(Option<String>),
    Failed(FailureKind),
}
struct Attempt {
    proof: hmac::Tag,
    expires_at: u64,
    next_poll: u64,
    phase: Phase,
}
#[derive(Default)]
struct Attempts {
    attempts: HashMap<String, Attempt>,
    states: HashMap<String, String>,
    starts: VecDeque<u64>,
}
impl Attempts {
    fn purge(&mut self, now: u64) {
        self.attempts.retain(|_, attempt| attempt.expires_at > now);
        self.states.retain(|_, id| self.attempts.contains_key(id));
        self.starts.retain(|at| now.saturating_sub(*at) < 60);
    }
}
struct Inner {
    config: Arc<OAuthConfig>,
    provider: Arc<dyn GitHubProvider>,
    vault: PersonalVault,
    clock: Arc<dyn AuthClock>,
    proof_key: hmac::Key,
    attempts: Mutex<Attempts>,
}

/// No listener is registered by construction; a reviewed host must explicitly install an auth surface.
#[derive(Clone)]
pub(crate) struct PersonalAuth(Arc<Inner>);

/// Canonical resolved authority, never reconstructed from the public session status.
#[derive(Clone)]
pub(crate) struct CurrentPersonalSession(Arc<super::vault::AuthenticatedSession>);
impl CurrentPersonalSession {
    pub(crate) fn identity(&self) -> anyhow::Result<crate::identity::RequestIdentity> {
        self.0.identity()
    }
    pub(crate) fn status(&self) -> anyhow::Result<&super::vault::SessionStatus> {
        self.identity()?;
        Ok(&self.0.status)
    }
}

impl PersonalAuth {
    pub(crate) async fn workspace_session(&self, session: &str) -> Result<CurrentPersonalSession> {
        self.0
            .vault
            .require_confirmed(session)
            .await
            .map_err(vault_error)?;
        self.authenticate(session).await
    }
    #[cfg(test)]
    pub(crate) async fn fixture(
        config: OAuthConfig,
        store: Store,
        keys: VaultKeys,
        origin: url::Url,
    ) -> anyhow::Result<Self> {
        let config = Arc::new(config);
        let provider = Arc::new(OAuthProvider::fixture(config.clone(), origin)?);
        blocking(move || {
            let clock: Arc<dyn AuthClock> = Arc::new(SystemClock);
            let vault = PersonalVault::new(store, Arc::new(keys), clock.clone())?;
            Self::construct(config, provider, vault, clock)
        })
        .await
    }
    /// @cc [owner:OctavianTocan,label:security;architecture] personal-read-authority-comes-from-the-vault
    /// A personal read handler MUST obtain its current request identity only from the
    /// canonical session/vault resolver, including refresh and delivery confirmation.
    /// A status body, caller account/email or inherited OAuth scope MUST NOT construct
    /// that authority. Source: Tavi's 21 Oct 2026 isolation-before-ingress decision and
    /// 22 Oct requirement to complete the executable personal read boundary.
    pub(crate) async fn authenticate(&self, session: &str) -> Result<CurrentPersonalSession> {
        let resolved = self
            .0
            .vault
            .resolve_or_refresh(session, self.0.provider.clone())
            .await
            .map_err(vault_error)?;
        resolved.identity().map_err(|_| AuthError::Unauthorized)?;
        Ok(CurrentPersonalSession(Arc::new(resolved)))
    }

    pub(crate) async fn new(
        config: OAuthConfig,
        store: Store,
        keys: VaultKeys,
    ) -> anyhow::Result<Self> {
        let config = Arc::new(config);
        let provider = Arc::new(OAuthProvider::new(config.clone())?);
        blocking(move || {
            let clock: Arc<dyn AuthClock> = Arc::new(SystemClock);
            let vault = PersonalVault::new(store, Arc::new(keys), clock.clone())?;
            Self::construct(config, provider, vault, clock)
        })
        .await
    }

    fn construct(
        config: Arc<OAuthConfig>,
        provider: Arc<dyn GitHubProvider>,
        vault: PersonalVault,
        clock: Arc<dyn AuthClock>,
    ) -> anyhow::Result<Self> {
        let mut key = [0u8; 32];
        getrandom::getrandom(&mut key)
            .map_err(|_| anyhow::anyhow!("Secure random source unavailable"))?;
        Ok(Self(Arc::new(Inner {
            config,
            provider,
            vault,
            clock,
            proof_key: hmac::Key::new(hmac::HMAC_SHA256, &key),
            attempts: Mutex::new(Attempts::default()),
        })))
    }

    pub(crate) fn capabilities(&self) -> Capabilities {
        Capabilities {
            github: GitHubCapabilities { enabled: true },
        }
    }
    pub(crate) fn disabled_capabilities() -> Capabilities {
        Capabilities {
            github: GitHubCapabilities { enabled: false },
        }
    }

    pub(crate) async fn start(&self, request: StartRequest) -> Result<StartReply> {
        self.begin(request, GitHubAuthority::PublicRead, None, None)
            .await
    }

    /// @cc [owner:OctavianTocan,label:product;security] github-connect-includes-repository-consent
    /// Connect GitHub MUST request repository consent in the initial authorization and issue
    /// privateRepositories authority only after verified GitHub approval and the owned claim.
    /// The older public-only start MUST retain its publicRead maximum for installed clients.
    /// Source: Tavi, 25 Oct 2026, one login including private repositories, opening them immediately.
    pub(crate) async fn connect(&self, request: StartRequest) -> Result<StartReply> {
        self.begin(request, GitHubAuthority::PrivateRepositories, None, None)
            .await
    }

    /// The separate repository-consent action captures the existing personal source.
    pub(crate) async fn start_repository_consent(
        &self,
        session: &str,
        request: StartRequest,
    ) -> Result<StartReply> {
        let source = self.authenticate(session).await?;
        source.identity().map_err(|_| AuthError::Unauthorized)?;
        self.begin(
            request,
            GitHubAuthority::PrivateRepositories,
            None,
            Some(source),
        )
        .await
    }

    /// Private hosts may explicitly link an already authenticated compatibility account; the public router has no link route.
    pub(crate) async fn start_legacy_link(
        &self,
        request: StartRequest,
        accounts: crate::account::Accounts,
        legacy_session: String,
    ) -> Result<StartReply> {
        let link = LegacyLink::authorize(accounts, legacy_session)
            .await
            .map_err(|_| AuthError::Unauthorized)?;
        self.begin(request, GitHubAuthority::PublicRead, Some(link), None)
            .await
    }

    async fn begin(
        &self,
        request: StartRequest,
        authority: GitHubAuthority,
        link: Option<LegacyLink>,
        upgrade: Option<CurrentPersonalSession>,
    ) -> Result<StartReply> {
        let challenge = wire::parse_fixed_secret(&request.native_challenge)
            .map_err(|_| AuthError::InvalidRequest)?;
        let grant = Grant {
            authority,
            link,
            upgrade,
        };
        if self.0.config.uses_device_flow() {
            return self.begin_device(challenge, grant).await;
        }
        let (id, state, verifier) = (
            random_secret().map_err(|_| AuthError::Unavailable)?,
            random_secret().map_err(|_| AuthError::Unavailable)?,
            random_secret().map_err(|_| AuthError::Unavailable)?,
        );
        let authorize_url = self
            .0
            .config
            .authorize_url(
                &state,
                &wire::native_challenge(&verifier).map_err(|_| AuthError::Unavailable)?,
                authority,
            )
            .map_err(|_| AuthError::Unavailable)?
            .to_string();
        let now = self.0.clock.now();
        let expires_at = now
            .checked_add(ATTEMPT_LIFETIME)
            .ok_or(AuthError::Unavailable)?;
        {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            Self::admit(&mut attempts, now)?;
            if attempts.attempts.contains_key(&id) || attempts.states.contains_key(&sha256(&state))
            {
                return Err(AuthError::Unavailable);
            }
            attempts.starts.push_back(now);
            attempts.states.insert(sha256(&state), id.clone());
            attempts.attempts.insert(
                id.clone(),
                Attempt {
                    proof: hmac::sign(&self.0.proof_key, &challenge),
                    expires_at,
                    next_poll: now,
                    phase: Phase::Pending(Pending { verifier, grant }),
                },
            );
        }
        self.retire_after(id.clone(), ATTEMPT_LIFETIME);
        Ok(StartReply {
            attempt_id: id,
            authorize_url,
            user_code: None,
            expires_at,
            poll_after_ms: (POLL_SECONDS * 1000) as u32,
        })
    }

    /// @cc [owner:OctavianTocan,label:security;product] personal-device-sign-in-is-server-polled
    /// A device sign-in MUST ask GitHub for its code only after the start passes the attempt limits,
    /// MUST show the person only GitHub's user code and fixed device page, and MUST keep the device
    /// code on the server, which alone polls GitHub at GitHub's interval (slowing when told) until
    /// approval, denial or the attempt's end. Approval MUST pass the same identity verification and
    /// single-use claim as the callback. Source: Tavi, 23 Oct 2026 13:36 ("what you get when you use
    /// GH auth login") and GitHub's device flow documentation.
    async fn begin_device(&self, challenge: [u8; 32], grant: Grant) -> Result<StartReply> {
        let id = random_secret().map_err(|_| AuthError::Unavailable)?;
        {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            let now = self.0.clock.now();
            Self::admit(&mut attempts, now)?;
            attempts.starts.push_back(now);
        }
        let code = self
            .0
            .provider
            .device_code(grant.authority)
            .await
            .map_err(|_| AuthError::Unavailable)?;
        let now = self.0.clock.now();
        let lifetime = ATTEMPT_LIFETIME.min(code.expires_in);
        let expires_at = now.checked_add(lifetime).ok_or(AuthError::Unavailable)?;
        {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            if attempts.attempts.len() >= MAX_ATTEMPTS || attempts.attempts.contains_key(&id) {
                return Err(AuthError::RateLimited);
            }
            attempts.attempts.insert(
                id.clone(),
                Attempt {
                    proof: hmac::sign(&self.0.proof_key, &challenge),
                    expires_at,
                    next_poll: now,
                    phase: Phase::Device(grant),
                },
            );
        }
        self.retire_after(id.clone(), lifetime);
        self.poll_device(id.clone(), code.device_code, code.interval);
        Ok(StartReply {
            attempt_id: id,
            authorize_url: DEVICE_VERIFICATION_URL.into(),
            user_code: Some(code.user_code),
            expires_at,
            poll_after_ms: (POLL_SECONDS * 1000) as u32,
        })
    }

    fn admit(attempts: &mut Attempts, now: u64) -> Result<()> {
        attempts.purge(now);
        if attempts.attempts.len() >= MAX_ATTEMPTS || attempts.starts.len() >= STARTS_PER_MINUTE {
            return Err(AuthError::RateLimited);
        }
        Ok(())
    }

    fn retire_after(&self, id: String, seconds: u64) {
        let weak = Arc::downgrade(&self.0);
        crate::rt::spawn(async move {
            crate::rt::sleep(Duration::from_secs(seconds)).await;
            if let Some(inner) = weak.upgrade() {
                if let Ok(mut attempts) = inner.attempts.lock() {
                    attempts.attempts.remove(&id);
                    attempts.states.retain(|_, state| *state != id);
                }
            }
        });
    }

    /// Polls GitHub for the person's approval while the attempt still waits for it; a cancelled,
    /// expired or dropped attempt ends the loop at its next turn.
    fn poll_device(&self, id: String, device_code: String, mut interval: u64) {
        let weak = Arc::downgrade(&self.0);
        crate::rt::spawn(async move {
            loop {
                crate::rt::sleep(Duration::from_secs(interval)).await;
                let Some(inner) = weak.upgrade() else { return };
                let this = PersonalAuth(inner);
                let now = this.0.clock.now();
                let waiting = this.0.attempts.lock().is_ok_and(|attempts| {
                    attempts.attempts.get(&id).is_some_and(|attempt| {
                        attempt.expires_at > now && matches!(attempt.phase, Phase::Device(_))
                    })
                });
                if !waiting {
                    return;
                }
                let polled = this.0.provider.poll_device(&device_code, now).await;
                let tokens = match polled {
                    Ok(DevicePoll::Pending) => continue,
                    Ok(DevicePoll::SlowDown(next)) => {
                        interval = next.max(interval + 5).min(60);
                        continue;
                    }
                    Ok(DevicePoll::Tokens(tokens)) => Ok(tokens),
                    Err(error) if error.is::<DeviceDenied>() => Err(AuthError::Denied),
                    Err(error) if error.is::<super::provider::ReconnectRequired>() => {
                        Err(AuthError::Reconnect)
                    }
                    // A passing GitHub or network failure is retried until the attempt ends.
                    Err(_) => continue,
                };
                let grant = {
                    let Ok(mut attempts) = this.0.attempts.lock() else {
                        return;
                    };
                    let Some(attempt) = attempts.attempts.get_mut(&id) else {
                        return;
                    };
                    let Phase::Device(grant) =
                        std::mem::replace(&mut attempt.phase, Phase::Exchanging)
                    else {
                        return;
                    };
                    grant
                };
                let _ = this.complete(&id, tokens, grant).await;
                return;
            }
        });
    }

    /// Verifies freshly acquired tokens and moves the exchanging attempt to Ready, or to its failure.
    async fn complete(&self, id: &str, tokens: Result<GitHubTokens>, grant: Grant) -> Result<()> {
        let acquired = async {
            if let Some(source) = &grant.upgrade {
                source.identity().map_err(|_| AuthError::Denied)?;
            }
            let login = VerifiedLogin::acquire(self.0.provider.as_ref(), tokens?)
                .await
                .map_err(|_| AuthError::Unavailable)?;
            // A new Connect must obtain the requested grant. An existing, captured
            // same-account consent may instead receive a reduced grant; the vault
            // installs that actual publicRead authority and retires the old binding.
            if !login.grants(grant.authority) && grant.upgrade.is_none() {
                return Err(AuthError::Denied);
            }
            if let Some(source) = &grant.upgrade {
                let status = source.status().map_err(|_| AuthError::Denied)?;
                if status.github_id != login.github_id() {
                    return Err(AuthError::Denied);
                }
            }
            Ok(Ready {
                login,
                authority: grant.authority,
                link: grant.link,
                upgrade: grant.upgrade,
            })
        }
        .await;
        let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
        let attempt = attempts.attempts.get_mut(id).ok_or(AuthError::Expired)?;
        if attempt.expires_at <= self.0.clock.now() {
            return Err(AuthError::Expired);
        }
        if !matches!(attempt.phase, Phase::Exchanging) {
            return Err(AuthError::Unauthorized);
        }
        attempt.phase = match acquired {
            Ok(ready) => Phase::Ready(ready),
            Err(AuthError::Denied) => Phase::Failed(FailureKind::Denied),
            Err(AuthError::Reconnect) => Phase::Failed(FailureKind::Reconnect),
            Err(_) => Phase::Failed(FailureKind::Unavailable),
        };
        Ok(())
    }

    pub(crate) fn status(&self, request: AttemptRequest) -> Result<StatusReply> {
        let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
        let attempt = self.owned(&mut attempts, &request)?;
        let now = self.0.clock.now();
        if attempt.next_poll > now {
            return Err(AuthError::RateLimited);
        }
        attempt.next_poll = now.saturating_add(POLL_SECONDS);
        Ok(match attempt.phase {
            Phase::Pending(_) | Phase::Device(_) | Phase::Exchanging | Phase::Claiming => {
                StatusReply {
                    phase: AttemptPhase::Pending,
                    failure: None,
                }
            }
            Phase::Ready(_) => StatusReply {
                phase: AttemptPhase::Ready,
                failure: None,
            },
            Phase::Claimed(_) => StatusReply {
                phase: AttemptPhase::Failed,
                failure: Some(FailureKind::Reconnect),
            },
            Phase::Cancelled(_) => StatusReply {
                phase: AttemptPhase::Cancelled,
                failure: None,
            },
            Phase::Failed(failure) => StatusReply {
                phase: AttemptPhase::Failed,
                failure: Some(failure),
            },
        })
    }

    /// The callback consumes state before starting an accepted exchange. Dropping its HTTP observer cannot lose the receipt.
    pub(crate) async fn callback(
        &self,
        state: String,
        code: Option<String>,
        denied: bool,
    ) -> Result<()> {
        wire::parse_fixed_secret(&state).map_err(|_| AuthError::InvalidRequest)?;
        let (id, pending) = {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            attempts.purge(self.0.clock.now());
            let id = attempts
                .states
                .remove(&sha256(&state))
                .ok_or(AuthError::Unauthorized)?;
            let attempt = attempts
                .attempts
                .get_mut(&id)
                .ok_or(AuthError::Unauthorized)?;
            let Phase::Pending(pending) = std::mem::replace(&mut attempt.phase, Phase::Exchanging)
            else {
                return Err(AuthError::Unauthorized);
            };
            if denied {
                attempt.phase = Phase::Failed(FailureKind::Denied);
                return Ok(());
            }
            if !code.as_ref().is_some_and(|code| {
                !code.is_empty()
                    && code.len() <= 4096
                    && code.bytes().all(|byte| byte.is_ascii_graphic())
            }) {
                attempt.phase = Phase::Failed(FailureKind::Reconnect);
                return Err(AuthError::InvalidRequest);
            }
            (id, pending)
        };
        let this = self.clone();
        crate::rt::spawn(async move {
            let tokens = this
                .0
                .provider
                .exchange(
                    code.as_deref().ok_or(AuthError::InvalidRequest)?,
                    &pending.verifier,
                    this.0.clock.now(),
                )
                .await
                .map_err(|error| {
                    if error.is::<super::provider::ReconnectRequired>() {
                        AuthError::Reconnect
                    } else {
                        AuthError::Unavailable
                    }
                });
            this.complete(&id, tokens, pending.grant).await
        })
        .await
        .map_err(|_| AuthError::Unavailable)?
    }

    pub(crate) async fn claim(&self, request: AttemptRequest) -> Result<ClaimReply> {
        let (ready, confirm_by) = {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            let attempt = self.owned(&mut attempts, &request)?;
            if !matches!(attempt.phase, Phase::Ready(_)) {
                return Err(AuthError::Reconnect);
            }
            let Phase::Ready(ready) = std::mem::replace(&mut attempt.phase, Phase::Claiming) else {
                return Err(AuthError::Reconnect);
            };
            (ready, attempt.expires_at)
        };
        let this = self.clone();
        crate::rt::spawn(async move {
            let install = match ready.upgrade {
                Some(source) => {
                    this.0
                        .vault
                        .install_upgrade(ready.login, source.0, confirm_by)
                        .await
                }
                None => {
                    this.0
                        .vault
                        .install(ready.login, ready.authority, ready.link, confirm_by)
                        .await
                }
            };
            let issued = match install {
                Ok(issued) => issued,
                Err(_) => {
                    if let Ok(mut attempts) = this.0.attempts.lock() {
                        if let Some(attempt) = attempts.attempts.get_mut(&request.attempt_id) {
                            if matches!(attempt.phase, Phase::Claiming) {
                                attempt.phase = Phase::Failed(FailureKind::Unavailable);
                            }
                        }
                    }
                    return Err(AuthError::Unavailable);
                }
            };
            let hash = sha256(&issued.session);
            let publish = {
                let mut attempts = this.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
                if let Some(attempt) = attempts.attempts.get_mut(&request.attempt_id) {
                    if attempt.expires_at > this.0.clock.now()
                        && matches!(attempt.phase, Phase::Claiming)
                    {
                        attempt.phase = Phase::Claimed(hash.clone());
                        true
                    } else {
                        attempt.phase = Phase::Cancelled(Some(hash.clone()));
                        false
                    }
                } else {
                    false
                }
            };
            if !publish {
                this.0
                    .vault
                    .sign_out_hash(hash)
                    .await
                    .map_err(|_| AuthError::Unavailable)?;
                return Err(AuthError::Expired);
            }
            Ok(ClaimReply {
                session: issued.session,
                status: issued.status,
            })
        })
        .await
        .map_err(|_| AuthError::Unavailable)?
    }

    pub(crate) async fn cancel(&self, request: AttemptRequest) -> Result<CancelReply> {
        let hash = {
            let mut attempts = self.0.attempts.lock().map_err(|_| AuthError::Unavailable)?;
            let attempt = self.owned(&mut attempts, &request)?;
            let hash = match &attempt.phase {
                Phase::Claimed(hash) => Some(hash.clone()),
                Phase::Cancelled(hash) => hash.clone(),
                _ => None,
            };
            attempt.phase = Phase::Cancelled(hash.clone());
            attempts.states.retain(|_, id| *id != request.attempt_id);
            hash
        };
        if let Some(hash) = hash {
            let vault = self.0.vault.clone();
            crate::rt::spawn(async move { vault.sign_out_hash(hash).await })
                .await
                .map_err(|_| AuthError::Unavailable)?
                .map_err(|_| AuthError::Unavailable)?;
        }
        Ok(CancelReply { cancelled: true })
    }

    pub(crate) async fn sign_out(&self, session: &str) -> Result<()> {
        self.0
            .vault
            .sign_out(session)
            .await
            .map_err(|_| AuthError::Unavailable)
    }
    pub(crate) async fn disconnect(&self, session: &str) -> Result<()> {
        self.0
            .vault
            .unlink(session)
            .await
            .map_err(|_| AuthError::Unavailable)
    }

    pub(crate) async fn session_status(
        &self,
        session: &str,
    ) -> Result<super::vault::SessionStatus> {
        self.0
            .vault
            .resolve_or_refresh(session, self.0.provider.clone())
            .await
            .map(|resolved| resolved.status)
            .map_err(|error| {
                if error.is::<super::provider::ReconnectRequired>() {
                    AuthError::Reconnect
                } else {
                    AuthError::Unavailable
                }
            })
    }

    fn owned<'a>(
        &self,
        attempts: &'a mut Attempts,
        request: &AttemptRequest,
    ) -> Result<&'a mut Attempt> {
        wire::parse_fixed_secret(&request.attempt_id).map_err(|_| AuthError::Unauthorized)?;
        let challenge = wire::native_challenge(&request.native_proof)
            .and_then(|challenge| wire::parse_fixed_secret(&challenge))
            .map_err(|_| AuthError::Unauthorized)?;
        let attempt = attempts
            .attempts
            .get_mut(&request.attempt_id)
            .ok_or(AuthError::Unauthorized)?;
        hmac::verify(&self.0.proof_key, &challenge, attempt.proof.as_ref())
            .map_err(|_| AuthError::Unauthorized)?;
        if attempt.expires_at <= self.0.clock.now() {
            return Err(AuthError::Expired);
        }
        Ok(attempt)
    }
}

#[cfg(test)]
pub(super) mod tests;
