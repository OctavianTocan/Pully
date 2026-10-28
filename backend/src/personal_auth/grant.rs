//! Sessions resolved by the sign-in object and acted on by an account's own object.
//!
//! On Cloudflare the vault (sign-in, sessions, GitHub tokens) is one Durable Object, and each
//! account's workspace is another, named by [`account_key`]. The account's object never reads
//! the vault: the sign-in object resolves the caller's bearer, forwards the request with the
//! resulting [`SessionGrant`], and [`GrantedSessions`] turns it into the same
//! [`CurrentPersonalSession`] the vault would have made. Sign-out and the server's own
//! background work go back to the sign-in object through [`SessionAuthority`]. The native
//! server keeps both in one process and never uses this module.
use super::{
    flow::AuthError, vault::AuthenticatedSession, vault::SessionStatus, AuthClock,
    CurrentPersonalSession, GitHubAuthority, SystemClock,
};
use crate::identity::GitHubCredential;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use futures_util::future::BoxFuture;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

/// How long an account's object keeps acting on a grant without the sign-in object granting it
/// again. Every request and every background round renews it, and each admission and round
/// revokes the credentials whose lease ran out; a sign-out reaches the account's object at once
/// ([`GrantedSessions::retire`]), so this only bounds the case where that notice was lost. A
/// session's own deadline stays the grant's, so work in flight is not cut short while the
/// session keeps being granted.
pub const GRANT_LEASE_SECONDS: u64 = 10 * 60;

/// The name of an account's own Durable Object, and of its durable workspace store: the same
/// key `TenantStates::workspace` gives the account's saved data.
pub fn account_key(account_id: &str) -> String {
    hex::encode(Sha256::digest(
        format!("workspace-account-v1:{account_id}").as_bytes(),
    ))
}

/// One resolved session, as the sign-in object hands it to the account's object. It holds
/// the account's GitHub token, so it only ever travels between the two objects.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SessionGrant {
    hash: String,
    /// Resolved as `workspace_session` would: the session's delivery is confirmed.
    workspace: bool,
    token: String,
    credential: String,
    revision: u64,
    generation: u64,
    deadline: u64,
    status: SessionStatus,
}

impl SessionGrant {
    /// The account the grant is for, which names the object that may act on it.
    pub fn account_id(&self) -> &str {
        &self.status.account_id
    }
    /// The credential revision the grant carries, which [`GrantedSessions::retire`] takes.
    pub fn credential(&self) -> &str {
        &self.credential
    }
    /// The grant as one header value, for the request forwarded with it.
    pub fn header(&self) -> anyhow::Result<String> {
        Ok(URL_SAFE_NO_PAD.encode(serde_json::to_vec(self)?))
    }
    fn parse(header: &str) -> Option<Self> {
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(header).ok()?).ok()
    }
}

impl CurrentPersonalSession {
    /// This session as a grant for the account's own object.
    pub(crate) fn grant(&self, workspace: bool) -> anyhow::Result<SessionGrant> {
        let session = &self.0;
        session.identity()?;
        let (account_id, _, token, credential) = session.credential.grant_parts()?;
        anyhow::ensure!(
            account_id == session.status.account_id,
            "Invalid personal session"
        );
        Ok(SessionGrant {
            hash: session.hash.clone(),
            workspace,
            token: token.to_owned(),
            credential: credential.to_string(),
            revision: session.revision,
            generation: session.generation,
            deadline: session.deadline,
            status: session.status.clone(),
        })
    }
    /// Resolves when this session's authority ends: sign-out, disconnect, expiry or a newer
    /// credential. The sign-in object passes it on to the account's object.
    pub async fn retired(&self) {
        if let Ok(identity) = self.identity() {
            identity.retired().await;
        }
    }
    /// The vault hash of the session, which [`GrantedSessions::retire`] takes.
    pub fn hash(&self) -> &str {
        &self.0.hash
    }
}

/// The request header the sign-in object forwards a [`SessionGrant`] in. Only the sign-in
/// object can reach an account's object, and it always sets this header itself.
pub const GRANT_HEADER: &str = "x-pully-grant";

/// How an account's object reaches the sign-in object. The Worker implements it with Durable
/// Object calls; tests call a `PersonalAuth` directly.
pub trait SessionAuthority: Send + Sync {
    /// Signs the bearer's session out.
    fn sign_out<'a>(&'a self, bearer: &'a str) -> BoxFuture<'a, anyhow::Result<()>>;
    /// A session of `account_id` for the server's own background work, as a [`GRANT_HEADER`]
    /// value, if one is signed in with repository access.
    fn account<'a>(&'a self, account_id: &'a str) -> BoxFuture<'a, anyhow::Result<Option<String>>>;
}

/// Revokes and drops each held credential whose lease ran out or that was revoked elsewhere.
fn sweep(held: &mut HashMap<String, Held>, now: u64) {
    held.retain(|_, held| {
        let current = held.lease_until > now
            && crate::identity::RequestIdentity::Account(held.active.clone())
                .ensure_current()
                .is_ok();
        if !current {
            held.active.revoke();
        }
        current
    });
}

struct Held {
    credential: String,
    active: GitHubCredential,
    lease_until: u64,
}

/// The sessions one account's object acts on. It admits only grants for its own account, keeps
/// one credential per session while the sign-in object keeps granting it, and revokes it when
/// the session is retired or its lease runs out.
pub struct GrantedSessions {
    authority: Arc<dyn SessionAuthority>,
    account: String,
    clock: Arc<dyn AuthClock>,
    held: Mutex<HashMap<String, Held>>,
    account_id: Mutex<Option<String>>,
}

impl GrantedSessions {
    /// The sessions of the account whose [`account_key`] is `account`.
    pub fn new(authority: Arc<dyn SessionAuthority>, account: String) -> Self {
        Self {
            authority,
            account,
            clock: Arc::new(SystemClock),
            held: Mutex::default(),
            account_id: Mutex::default(),
        }
    }

    #[cfg(test)]
    fn with_clock(account: String, clock: Arc<dyn AuthClock>) -> Self {
        struct Unreachable;
        impl SessionAuthority for Unreachable {
            fn sign_out<'a>(&'a self, _: &'a str) -> BoxFuture<'a, anyhow::Result<()>> {
                Box::pin(async { anyhow::bail!("No sign-in object in this test") })
            }
            fn account<'a>(&'a self, _: &'a str) -> BoxFuture<'a, anyhow::Result<Option<String>>> {
                Box::pin(async { Ok(None) })
            }
        }
        Self {
            clock,
            ..Self::new(Arc::new(Unreachable), account)
        }
    }

    /// The session of the grant forwarded with this request, which must be the caller's own
    /// bearer's, and confirmed when `workspace`.
    pub(crate) fn authenticate(
        &self,
        bearer: &str,
        headers: &axum::http::HeaderMap,
        workspace: bool,
    ) -> Result<CurrentPersonalSession, AuthError> {
        let grant = headers
            .get(GRANT_HEADER)
            .and_then(|value| value.to_str().ok())
            .and_then(SessionGrant::parse)
            .ok_or(AuthError::Unauthorized)?;
        if super::vault::bearer_hash(bearer).as_deref() != Some(grant.hash.as_str())
            || (workspace && !grant.workspace)
        {
            return Err(AuthError::Unauthorized);
        }
        self.admit(grant)
    }

    /// The account the last admitted grant was for, which background work runs as.
    pub fn account_id(&self) -> Option<String> {
        self.account_id
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    pub(crate) async fn sign_out(&self, bearer: &str) -> Result<(), AuthError> {
        let result = self
            .authority
            .sign_out(bearer)
            .await
            .map_err(|_| AuthError::Unavailable);
        if let Some(hash) = super::vault::bearer_hash(bearer) {
            self.retire(&hash, None);
        }
        result
    }

    /// A session of this account for the server's own background work, if one is signed in
    /// with repository access.
    pub async fn background(&self, account_id: &str) -> Option<CurrentPersonalSession> {
        sweep(
            &mut self.held.lock().unwrap_or_else(|e| e.into_inner()),
            self.clock.now(),
        );
        let header = self.authority.account(account_id).await.ok()??;
        let grant = SessionGrant::parse(&header).filter(|grant| grant.workspace)?;
        self.admit(grant).ok().filter(|session| {
            session
                .status()
                .is_ok_and(|status| status.authority == GitHubAuthority::PrivateRepositories)
        })
    }

    /// Ends what this object holds for the session `hash` (only its credential revision
    /// `credential`, when given): the credential is revoked, so work in flight for it stops as
    /// it would on the sign-in object. A notice about an older revision leaves a newer one.
    pub fn retire(&self, hash: &str, credential: Option<&str>) {
        let mut held = self.held.lock().unwrap_or_else(|e| e.into_inner());
        if held
            .get(hash)
            .is_some_and(|held| credential.is_none_or(|credential| held.credential == credential))
        {
            if let Some(held) = held.remove(hash) {
                held.active.revoke();
            }
        }
    }

    fn admit(&self, grant: SessionGrant) -> Result<CurrentPersonalSession, AuthError> {
        if account_key(grant.account_id()) != self.account {
            return Err(AuthError::Denied);
        }
        let revision =
            uuid::Uuid::parse_str(&grant.credential).map_err(|_| AuthError::Unavailable)?;
        let now = self.clock.now();
        let mut held = self.held.lock().unwrap_or_else(|e| e.into_inner());
        sweep(&mut held, now);
        let lease_until = now.saturating_add(GRANT_LEASE_SECONDS);
        let credential = match held.get_mut(&grant.hash) {
            Some(existing) if existing.credential == grant.credential => {
                existing.lease_until = lease_until;
                existing.active.clone()
            }
            _ => {
                let active = GitHubCredential::granted(
                    grant.account_id(),
                    grant.status.github_id,
                    &grant.token,
                    revision,
                )
                .map_err(|_| AuthError::Unavailable)?;
                if let Some(old) = held.insert(
                    grant.hash.clone(),
                    Held {
                        credential: grant.credential.clone(),
                        active: active.clone(),
                        lease_until,
                    },
                ) {
                    old.active.revoke();
                }
                active
            }
        };
        let session = AuthenticatedSession {
            credential,
            hash: grant.hash,
            revision: grant.revision,
            generation: grant.generation,
            deadline: grant.deadline,
            clock: self.clock.clone(),
            status: grant.status,
        };
        session.identity().map_err(|_| AuthError::Unauthorized)?;
        drop(held);
        *self.account_id.lock().unwrap_or_else(|e| e.into_inner()) =
            Some(session.status.account_id.clone());
        Ok(CurrentPersonalSession(Arc::new(session)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct Clock(AtomicU64);
    impl AuthClock for Clock {
        fn now(&self) -> u64 {
            self.0.load(Ordering::SeqCst)
        }
    }

    fn grant(credential: &str) -> SessionGrant {
        SessionGrant {
            hash: "a".repeat(64),
            workspace: true,
            token: "fixture-token".into(),
            credential: credential.into(),
            revision: 1,
            generation: 1,
            deadline: 100_000,
            status: SessionStatus {
                account_id: "account-a".into(),
                github_id: 42,
                login: "reader-a".into(),
                authority: GitHubAuthority::PrivateRepositories,
                session_expires_at: 100_000,
                access_expires_at: None,
                refresh_available: true,
            },
        }
    }

    #[test]
    fn a_grant_not_renewed_within_its_lease_is_revoked_but_renewal_keeps_work_running() {
        let clock = Arc::new(Clock(AtomicU64::new(1_000)));
        let sessions = GrantedSessions::with_clock(account_key("account-a"), clock.clone());
        let credential = uuid::Uuid::new_v4().to_string();
        let first = sessions.admit(grant(&credential)).unwrap();
        // Renewed before the lease ends: the session admitted first keeps its authority past
        // the first lease, as long-running work in flight needs.
        clock
            .0
            .store(1_000 + GRANT_LEASE_SECONDS - 1, Ordering::SeqCst);
        sessions.admit(grant(&credential)).unwrap();
        clock
            .0
            .store(1_000 + GRANT_LEASE_SECONDS + 1, Ordering::SeqCst);
        assert!(first.identity().is_ok());
        // Not granted again for a whole lease: the next admission revokes it.
        clock
            .0
            .store(1_000 + 3 * GRANT_LEASE_SECONDS, Ordering::SeqCst);
        sessions
            .admit(SessionGrant {
                hash: "b".repeat(64),
                ..grant(&uuid::Uuid::new_v4().to_string())
            })
            .unwrap();
        assert!(first.identity().is_err());
        // Another account's grant is refused outright.
        let mut foreign = grant(&credential);
        foreign.status.account_id = "account-b".into();
        assert!(matches!(sessions.admit(foreign), Err(AuthError::Denied)));
    }
}
