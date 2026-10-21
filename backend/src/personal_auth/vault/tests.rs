use super::*;
use crate::identity::VerifiedGitHubIdentity;
use crate::personal_auth::provider::{ProviderFuture, RefreshToken};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use tokio::sync::Notify;

struct Clock(AtomicU64);
impl AuthClock for Clock {
    fn now(&self) -> u64 {
        self.0.load(Ordering::SeqCst)
    }
}

#[derive(Default)]
struct Provider {
    users: Mutex<HashMap<String, (u64, String)>>,
    calls: AtomicUsize,
    delayed: AtomicBool,
    started: Notify,
    release: Notify,
}
impl Provider {
    fn user(&self, token: &str, id: u64, login: &str) {
        self.users
            .lock()
            .unwrap()
            .insert(token.into(), (id, login.into()));
    }
}
impl GitHubProvider for Provider {
    fn exchange<'a>(&'a self, _: &'a str, _: &'a str, _: u64) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async { anyhow::bail!("Unexpected exchange") })
    }
    fn refresh<'a>(&'a self, _: &'a str, _: u64) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async { anyhow::bail!("Unexpected refresh") })
    }
    fn verify<'a>(&'a self, token: String) -> ProviderFuture<'a, VerifiedGitHubIdentity> {
        Box::pin(async move {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.delayed.load(Ordering::SeqCst) {
                self.started.notify_one();
                self.release.notified().await;
            }
            let (id, login) = self
                .users
                .lock()
                .unwrap()
                .get(&token)
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("GitHub could not verify this sign-in"))?;
            VerifiedGitHubIdentity::fixture(id, &login, &token)
        })
    }
}

struct Root(PathBuf);
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
struct Harness {
    _root: Root,
    store: Store,
    vault: PersonalVault,
    provider: Arc<Provider>,
    clock: Arc<Clock>,
}
impl Harness {
    fn new() -> Self {
        let root = Root(
            std::env::temp_dir().join(format!("pully-personal-vault-{}", uuid::Uuid::new_v4())),
        );
        let store = Store::new(root.0.join("personal")).unwrap();
        let clock = Arc::new(Clock(AtomicU64::new(100)));
        let vault = PersonalVault::new(
            store.clone(),
            Arc::new(VaultKeys::new(1, BTreeMap::from([(1, [73; 32])])).unwrap()),
            clock.clone(),
        )
        .unwrap();
        Self {
            _root: root,
            store,
            vault,
            provider: Arc::new(Provider::default()),
            clock,
        }
    }
    async fn sign_in(&self, token: &str, id: u64, login: &str) -> IssuedSession {
        self.provider.user(token, id, login);
        let verified = VerifiedLogin::acquire(self.provider.as_ref(), tokens(token))
            .await
            .unwrap();
        self.vault
            .install(verified, GitHubAuthority::PublicRead, None)
            .await
            .unwrap()
    }
    async fn resolve(&self, session: &str) -> AuthenticatedSession {
        self.vault
            .resolve(session, self.provider.as_ref())
            .await
            .unwrap()
    }
}
fn tokens(access: &str) -> GitHubTokens {
    GitHubTokens {
        access_token: access.into(),
        expires_at: None,
        refresh: None,
        scopes: std::collections::BTreeSet::from(["repo".into()]),
    }
}

#[tokio::test]
async fn tokens_are_encrypted_sessions_are_hashed_and_status_is_public_only() {
    let h = Harness::new();
    h.provider.user("fixture-secret-access", 42, "reader");
    let mut pair = tokens("fixture-secret-access");
    pair.expires_at = Some(28900);
    pair.refresh = Some(RefreshToken {
        token: "fixture-secret-refresh".into(),
        expires_at: 15897700,
    });
    let login = VerifiedLogin::acquire(h.provider.as_ref(), pair)
        .await
        .unwrap();
    let issued = h
        .vault
        .install(login, GitHubAuthority::PublicRead, None)
        .await
        .unwrap();
    let stored = serde_json::to_string(&h.store.get(STATE_KEY).unwrap().unwrap()).unwrap();
    for secret in [
        &issued.session,
        "fixture-secret-access",
        "fixture-secret-refresh",
    ] {
        assert!(!stored.contains(secret));
    }
    assert!(stored.contains(&sha256(&issued.session)));
    let status = serde_json::to_string(&issued.status).unwrap();
    assert!(!status.contains("fixture-secret"));
    assert_eq!(issued.status.authority, GitHubAuthority::PublicRead);
    assert!(issued.status.refresh_available);
    let resolved = h.resolve(&issued.session).await;
    assert_eq!(
        resolved.identity().unwrap().account_id().unwrap(),
        issued.status.account_id
    );
    assert_eq!(resolved.status.authority, GitHubAuthority::PublicRead);
}

#[tokio::test]
async fn numeric_identity_survives_login_rename_without_merging_other_users() {
    let h = Harness::new();
    let first = h.sign_in("access-first", 42, "old-name").await;
    let old = h.resolve(&first.session).await.identity().unwrap();
    let renamed = h.sign_in("access-renamed", 42, "new-name").await;
    assert_eq!(first.status.account_id, renamed.status.account_id);
    assert!(old.ensure_current().is_err());
    let current = h.resolve(&first.session).await;
    assert_eq!(current.status.login, "new-name");
    assert_eq!(current.status.github_id, 42);
    let different = h.sign_in("other-access", 43, "new-name").await;
    assert_ne!(first.status.account_id, different.status.account_id);
}

#[tokio::test]
async fn different_users_only_resolve_their_own_verified_credential() {
    let h = Harness::new();
    let a = h.sign_in("access-a", 42, "reader-a").await;
    let b = h.sign_in("access-b", 43, "reader-b").await;
    for issued in [&a, &b] {
        let resolved = h.resolve(&issued.session).await;
        let identity = resolved.identity().unwrap();
        assert_eq!(identity.account_id().unwrap(), issued.status.account_id);
        let token = identity
            .github_token(|| async { panic!("operator token must never be requested") })
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            token,
            if issued.status.github_id == 42 {
                "access-a"
            } else {
                "access-b"
            }
        );
    }
    assert!(h
        .vault
        .resolve("missing", h.provider.as_ref())
        .await
        .is_err());
    let unknown = format!("pully_{}", random_secret().unwrap());
    assert!(h
        .vault
        .resolve(&unknown, h.provider.as_ref())
        .await
        .is_err());
}

#[tokio::test]
async fn sign_out_revokes_captured_authority_and_only_the_matching_session() {
    let h = Harness::new();
    let first = h.sign_in("first", 42, "reader").await;
    let second = h.sign_in("second", 42, "reader").await;
    let a = h.resolve(&first.session).await.identity().unwrap();
    let b = h.resolve(&second.session).await.identity().unwrap();
    h.vault.sign_out(&first.session).await.unwrap();
    assert!(a.ensure_current().is_err());
    assert!(b.ensure_current().is_ok());
    assert!(h
        .vault
        .resolve(&first.session, h.provider.as_ref())
        .await
        .is_err());
    assert!(h.resolve(&second.session).await.identity().is_ok());
}

#[tokio::test]
async fn unlink_revokes_all_sessions_and_reconnect_keeps_the_stable_account() {
    let h = Harness::new();
    let first = h.sign_in("first", 42, "reader").await;
    let second = h.sign_in("second", 42, "reader").await;
    let a = h.resolve(&first.session).await.identity().unwrap();
    let b = h.resolve(&second.session).await.identity().unwrap();
    h.vault.unlink(&first.session).await.unwrap();
    assert!(a.ensure_current().is_err() && b.ensure_current().is_err());
    for session in [&first.session, &second.session] {
        assert!(h.vault.resolve(session, h.provider.as_ref()).await.is_err());
    }
    let reconnected = h.sign_in("reconnected", 42, "reader").await;
    assert_eq!(reconnected.status.account_id, first.status.account_id);
    assert!(h.resolve(&reconnected.session).await.identity().is_ok());
}

#[tokio::test]
async fn copied_credential_envelope_fails_before_returning_a_cached_identity() {
    let h = Harness::new();
    let a = h.sign_in("access-a", 42, "reader-a").await;
    let b = h.sign_in("access-b", 43, "reader-b").await;
    let captured = h.resolve(&b.session).await.identity().unwrap();
    h.store
        .update(STATE_KEY, |value| {
            let mut database = decode(value)?;
            let copied = database.accounts[&a.status.account_id].credentials.clone();
            database
                .accounts
                .get_mut(&b.status.account_id)
                .unwrap()
                .credentials = copied;
            Ok(serde_json::to_value(database)?)
        })
        .unwrap();
    assert!(h
        .vault
        .resolve(&b.session, h.provider.as_ref())
        .await
        .is_err());
    assert!(captured.ensure_current().is_err());
    assert!(h.resolve(&a.session).await.identity().is_ok());
}

#[tokio::test]
async fn copying_a_same_account_session_record_cannot_rebind_another_session_hash() {
    let h = Harness::new();
    let a = h.sign_in("first", 42, "reader").await;
    let b = h.sign_in("second", 42, "reader").await;
    h.store
        .update(STATE_KEY, |value| {
            let mut database = decode(value)?;
            let copied = database.sessions[&sha256(&a.session)].clone();
            database.sessions.insert(sha256(&b.session), copied);
            Ok(serde_json::to_value(database)?)
        })
        .unwrap();
    assert!(h
        .vault
        .resolve(&b.session, h.provider.as_ref())
        .await
        .is_err());
    assert!(h.resolve(&a.session).await.identity().is_ok());
}

#[tokio::test]
async fn sign_out_while_user_verification_is_in_flight_denies_the_late_result() {
    let h = Harness::new();
    let issued = h.sign_in("access", 42, "reader").await;
    h.provider.delayed.store(true, Ordering::SeqCst);
    let task = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        tokio::spawn(async move { vault.resolve(&session, provider.as_ref()).await })
    };
    h.provider.started.notified().await;
    h.vault.sign_out(&issued.session).await.unwrap();
    h.provider.release.notify_one();
    assert!(task.await.unwrap().is_err());
    assert!(h.vault.runtime.lock().unwrap().bindings.is_empty());
}

#[tokio::test]
async fn recovered_credentials_reverify_the_immutable_subject_before_creating_authority() {
    let h = Harness::new();
    let issued = h.sign_in("access", 42, "reader").await;
    let before = h.resolve(&issued.session).await.identity().unwrap();
    h.vault.runtime.lock().unwrap().bindings.clear();
    assert!(before.ensure_current().is_err());
    h.provider.user("access", 99, "different-user");
    assert!(h
        .vault
        .resolve(&issued.session, h.provider.as_ref())
        .await
        .is_err());
    let database = decode(h.store.get(STATE_KEY).unwrap()).unwrap();
    assert_eq!(database.accounts[&issued.status.account_id].github_id, 42);
    assert!(h.vault.runtime.lock().unwrap().bindings.is_empty());
}

#[tokio::test]
async fn legacy_link_requires_the_actual_still_current_legacy_session() {
    let h = Harness::new();
    let legacy = Accounts::new(Store::new(h._root.0.join("legacy")).unwrap());
    let (account, session, _) = legacy.fixture_for_test();
    assert!(
        LegacyLink::authorize(legacy.clone(), "unknown-session".into())
            .await
            .is_err()
    );
    let stale_link = LegacyLink::authorize(legacy.clone(), session.clone())
        .await
        .unwrap();
    legacy.sign_out_async(&session).await.unwrap();
    h.provider.user("access", 42, "reader");
    let login = VerifiedLogin::acquire(h.provider.as_ref(), tokens("access"))
        .await
        .unwrap();
    assert!(h
        .vault
        .install(login, GitHubAuthority::PublicRead, Some(stale_link))
        .await
        .is_err());
    assert!(h.store.get(STATE_KEY).unwrap().is_none());
    let (_, valid_session, _) = legacy.fixture_for_test();
    let link = LegacyLink::authorize(legacy.clone(), valid_session)
        .await
        .unwrap();
    let login = VerifiedLogin::acquire(h.provider.as_ref(), tokens("access"))
        .await
        .unwrap();
    let issued = h
        .vault
        .install(login, GitHubAuthority::PublicRead, Some(link))
        .await
        .unwrap();
    assert_eq!(issued.status.account_id, account.id);
    assert_eq!(
        h.resolve(&issued.session)
            .await
            .identity()
            .unwrap()
            .account_id()
            .unwrap(),
        account.id
    );
    // Linking does not copy legacy API keys, posted data or sessions into the personal store.
    assert!(h.store.keys("account:").unwrap().is_empty());
    assert!(h.store.keys("agent-key:").unwrap().is_empty());
}

#[tokio::test]
async fn explicit_link_does_not_merge_two_preexisting_personal_accounts() {
    let h = Harness::new();
    let issued = h.sign_in("access", 42, "reader").await;
    let legacy = Accounts::new(Store::new(h._root.0.join("legacy")).unwrap());
    let (_, session, _) = legacy.fixture_for_test();
    let link = LegacyLink::authorize(legacy, session).await.unwrap();
    let login = VerifiedLogin::acquire(h.provider.as_ref(), tokens("access"))
        .await
        .unwrap();
    assert!(h
        .vault
        .install(login, GitHubAuthority::PublicRead, Some(link))
        .await
        .is_err());
    assert!(h.resolve(&issued.session).await.identity().is_ok());
}

#[tokio::test(start_paused = true)]
async fn expiration_revokes_already_captured_authority_and_still_allows_unlink() {
    let h = Harness::new();
    h.provider.user("expiring", 42, "reader");
    let mut pair = tokens("expiring");
    pair.expires_at = Some(105);
    let login = VerifiedLogin::acquire(h.provider.as_ref(), pair)
        .await
        .unwrap();
    let issued = h
        .vault
        .install(login, GitHubAuthority::PublicRead, None)
        .await
        .unwrap();
    let identity = h.resolve(&issued.session).await.identity().unwrap();
    tokio::task::yield_now().await;
    h.clock.0.store(106, Ordering::SeqCst);
    tokio::time::advance(Duration::from_secs(6)).await;
    tokio::task::yield_now().await;
    assert!(identity.ensure_current().is_err());
    assert!(h
        .vault
        .resolve(&issued.session, h.provider.as_ref())
        .await
        .is_err());
    h.vault.unlink(&issued.session).await.unwrap();
}
