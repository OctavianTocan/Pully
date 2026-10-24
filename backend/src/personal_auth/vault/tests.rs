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
    refresh_calls: AtomicUsize,
    refresh_block: AtomicBool,
    refresh_entered: Notify,
    refresh_release: Notify,
    refresh_failure: AtomicUsize,
    reduced: AtomicBool,
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
    fn refresh<'a>(&'a self, token: &'a str, now: u64) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async move {
            self.refresh_calls.fetch_add(1, Ordering::SeqCst);
            if self.refresh_block.load(Ordering::SeqCst) {
                self.refresh_entered.notify_one();
                self.refresh_release.notified().await;
            }
            match self.refresh_failure.load(Ordering::SeqCst) {
                1 => anyhow::bail!("GitHub authorization is temporarily unavailable"),
                2 => return Err(ReconnectRequired.into()),
                _ => {}
            }
            anyhow::ensure!(
                token == "refresh-before",
                "Consumed refresh token was reused"
            );
            Ok(GitHubTokens {
                access_token: "access-rotated".into(),
                expires_at: Some(now + 28800),
                refresh: Some(RefreshToken {
                    token: "refresh-after".into(),
                    expires_at: now + 15897600,
                }),
                scopes: if self.reduced.load(Ordering::SeqCst) {
                    Default::default()
                } else {
                    std::collections::BTreeSet::from(["repo".into()])
                },
            })
        })
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
            .install(
                verified,
                GitHubAuthority::PublicRead,
                None,
                self.clock.now() + 600,
            )
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

#[tokio::test]
async fn legacy_session_claims_default_to_public_read_and_outer_authority_cannot_be_forged() {
    let h = Harness::new();
    let public = h.sign_in("public-before", 42, "reader").await;
    let source = Arc::new(h.resolve(&public.session).await);
    h.provider.user("private-after", 42, "reader");
    let verified = VerifiedLogin::acquire(h.provider.as_ref(), tokens("private-after"))
        .await
        .unwrap();
    let private = h
        .vault
        .install_upgrade(verified, source, h.clock.now() + 600)
        .await
        .unwrap();
    assert_eq!(
        h.resolve(&private.session).await.status.authority,
        GitHubAuthority::PrivateRepositories
    );
    let hash = session_hash(&public.session).unwrap();
    h.store
        .update(STATE_KEY, |value| {
            let mut database = decode(value)?;
            let stored = database.sessions.get_mut(&hash).unwrap();
            let context = session_context(&stored.account_id, stored.github_id, stored.generation);
            let mut claims: serde_json::Value =
                serde_json::from_slice(&h.vault.keys.open(&context, &stored.sealed)?)?;
            claims.as_object_mut().unwrap().remove("authority");
            stored.sealed = h.vault.keys.seal(&context, &serde_json::to_vec(&claims)?)?;
            let mut raw = serde_json::to_value(database)?;
            raw["sessions"][&hash]
                .as_object_mut()
                .unwrap()
                .remove("authority");
            Ok(raw)
        })
        .unwrap();
    assert_eq!(
        h.resolve(&public.session).await.status.authority,
        GitHubAuthority::PublicRead
    );
    h.store
        .update(STATE_KEY, |value| {
            let mut raw = value.unwrap();
            raw["sessions"][&hash]["authority"] = serde_json::json!("privateRepositories");
            Ok(raw)
        })
        .unwrap();
    assert!(h
        .vault
        .resolve(&public.session, h.provider.as_ref())
        .await
        .is_err());
    assert_eq!(
        h.resolve(&private.session).await.status.authority,
        GitHubAuthority::PrivateRepositories
    );
}
fn tokens(access: &str) -> GitHubTokens {
    GitHubTokens {
        access_token: access.into(),
        expires_at: None,
        refresh: None,
        scopes: std::collections::BTreeSet::from(["repo".into()]),
    }
}

async fn expiring(h: &Harness, authority: GitHubAuthority) -> IssuedSession {
    h.provider.user("access-before", 42, "before");
    let mut pair = tokens("access-before");
    pair.expires_at = Some(105);
    pair.refresh = Some(RefreshToken {
        token: "refresh-before".into(),
        expires_at: 10000,
    });
    let verified = VerifiedLogin::acquire(h.provider.as_ref(), pair)
        .await
        .unwrap();
    h.vault
        .install(verified, authority, None, h.clock.now() + 600)
        .await
        .unwrap()
}

#[tokio::test]
async fn concurrent_refresh_consumes_one_pair_and_retires_old_captured_authority() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    let old = h.resolve(&issued.session).await.identity().unwrap();
    h.provider.user("access-rotated", 42, "after");
    h.provider.refresh_block.store(true, Ordering::SeqCst);
    let a = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        crate::rt::spawn(async move { vault.resolve_or_refresh(&session, provider).await })
    };
    h.provider.refresh_entered.notified().await;
    assert!(old.ensure_current().is_err());
    assert!(h
        .vault
        .resolve(&issued.session, h.provider.as_ref())
        .await
        .is_err());
    let b = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        crate::rt::spawn(async move { vault.resolve_or_refresh(&session, provider).await })
    };
    h.provider.refresh_release.notify_one();
    assert_eq!(a.await.unwrap().unwrap().status.login, "after");
    assert_eq!(b.await.unwrap().unwrap().status.github_id, 42);
    assert_eq!(h.provider.refresh_calls.load(Ordering::SeqCst), 1);
    let database = decode(h.store.get(STATE_KEY).unwrap()).unwrap();
    assert_eq!(database.accounts[&issued.status.account_id].revision, 2);
}

#[tokio::test]
async fn a_rotated_pair_survives_temporary_identity_verification_failure_without_refresh_reuse() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    assert!(h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .is_err());
    assert_eq!(h.provider.refresh_calls.load(Ordering::SeqCst), 1);
    let pending = h.vault.snapshot(sha256(&issued.session), true).unwrap();
    assert!(!pending.credentials.verified);
    assert_eq!(pending.credentials.tokens.access_token, "access-rotated");
    assert_eq!(
        pending.credentials.tokens.refresh.as_ref().unwrap().token,
        "refresh-after"
    );
    assert!(h.vault.runtime.lock().unwrap().bindings.is_empty());
    h.provider.user("access-rotated", 42, "after");
    let ready = h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .unwrap();
    assert_eq!(ready.status.account_id, issued.status.account_id);
    assert_eq!(h.provider.refresh_calls.load(Ordering::SeqCst), 1);
    assert!(
        h.vault
            .snapshot(sha256(&issued.session), true)
            .unwrap()
            .credentials
            .verified
    );
}

#[tokio::test]
async fn transient_refresh_failure_keeps_the_pair_for_retry_and_never_uses_operator_authority() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    h.clock.0.store(106, Ordering::SeqCst);
    h.provider.refresh_failure.store(1, Ordering::SeqCst);
    assert!(h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .is_err());
    assert!(h.vault.snapshot(sha256(&issued.session), true).is_ok());
    h.provider.refresh_failure.store(0, Ordering::SeqCst);
    h.provider.user("access-rotated", 42, "after");
    assert!(h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .unwrap()
        .identity()
        .is_ok());
    assert_eq!(h.provider.refresh_calls.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn terminal_refresh_failure_retires_sessions_and_old_session_cannot_revive_after_reconnect() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    let captured = h.resolve(&issued.session).await.identity().unwrap();
    h.provider.refresh_failure.store(2, Ordering::SeqCst);
    assert!(h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .err()
        .unwrap()
        .is::<ReconnectRequired>());
    assert!(captured.ensure_current().is_err());
    let database = decode(h.store.get(STATE_KEY).unwrap()).unwrap();
    assert!(database.sessions.is_empty());
    assert!(database.accounts[&issued.status.account_id]
        .credentials
        .is_none());
    let new = h.sign_in("new-sign-in", 42, "reader").await;
    assert_eq!(new.status.account_id, issued.status.account_id);
    assert!(h
        .vault
        .resolve(&issued.session, h.provider.as_ref())
        .await
        .is_err());
}

#[tokio::test]
async fn rotated_access_for_a_different_subject_revokes_instead_of_rebinding_the_account() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    h.provider.user("access-rotated", 99, "wrong-subject");
    assert!(h
        .vault
        .resolve_or_refresh(&issued.session, h.provider.clone())
        .await
        .is_err());
    let database = decode(h.store.get(STATE_KEY).unwrap()).unwrap();
    assert!(database.sessions.is_empty());
    assert_eq!(database.accounts[&issued.status.account_id].github_id, 42);
    assert!(!database.subjects.contains_key("github.com:99"));
}

#[tokio::test]
async fn unlink_during_refresh_denies_the_late_rotated_pair() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    h.provider.user("access-rotated", 42, "after");
    h.provider.refresh_block.store(true, Ordering::SeqCst);
    let refresh = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        crate::rt::spawn(async move { vault.resolve_or_refresh(&session, provider).await })
    };
    h.provider.refresh_entered.notified().await;
    h.vault.unlink(&issued.session).await.unwrap();
    h.provider.refresh_release.notify_one();
    assert!(refresh.await.unwrap().is_err());
    let database = decode(h.store.get(STATE_KEY).unwrap()).unwrap();
    assert!(database.accounts[&issued.status.account_id]
        .credentials
        .is_none());
    assert!(database.sessions.is_empty());
}

#[tokio::test]
async fn scope_reduction_downgrades_authority_even_for_an_explicit_private_connection() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PrivateRepositories).await;
    assert_eq!(
        issued.status.authority,
        GitHubAuthority::PrivateRepositories
    );
    h.provider.user("access-rotated", 42, "after");
    h.provider.reduced.store(true, Ordering::SeqCst);
    assert_eq!(
        h.vault
            .resolve_or_refresh(&issued.session, h.provider.clone())
            .await
            .unwrap()
            .status
            .authority,
        GitHubAuthority::PublicRead
    );
}

#[tokio::test]
async fn accepted_refresh_survives_a_dropped_http_observer() {
    let h = Harness::new();
    let issued = expiring(&h, GitHubAuthority::PublicRead).await;
    h.provider.user("access-rotated", 42, "after");
    h.provider.refresh_block.store(true, Ordering::SeqCst);
    let refresh = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        crate::rt::spawn(async move { vault.resolve_or_refresh(&session, provider).await })
    };
    h.provider.refresh_entered.notified().await;
    refresh.abort();
    let _ = refresh.await;
    h.provider.refresh_release.notify_one();
    crate::rt::timeout(Duration::from_secs(2), async {
        loop {
            let current = h.vault.snapshot(sha256(&issued.session), true).unwrap();
            if current.account.revision == 2 && current.credentials.verified {
                break;
            }
            crate::rt::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(h.provider.refresh_calls.load(Ordering::SeqCst), 1);
    assert!(h.resolve(&issued.session).await.identity().is_ok());
}

#[tokio::test]
async fn old_mismatched_user_verification_cannot_revoke_a_newer_sign_ins_captured_authority() {
    let h = Harness::new();
    let old = h.sign_in("old-access", 42, "before").await;
    h.provider
        .user("old-access", 99, "stale-mismatched-subject");
    h.provider.delayed.store(true, Ordering::SeqCst);
    let old_read = {
        let (vault, provider, session) = (h.vault.clone(), h.provider.clone(), old.session);
        crate::rt::spawn(async move { vault.resolve(&session, provider.as_ref()).await })
    };
    h.provider.started.notified().await;
    h.provider.delayed.store(false, Ordering::SeqCst);
    let fresh = h.sign_in("fresh-access", 42, "after").await;
    let fresh_identity = h.resolve(&fresh.session).await.identity().unwrap();
    let before = h.store.get(STATE_KEY).unwrap();
    h.provider.release.notify_one();
    assert!(old_read.await.unwrap().is_err());
    assert!(fresh_identity.ensure_current().is_ok());
    assert_eq!(h.store.get(STATE_KEY).unwrap(), before);
    assert!(h.resolve(&fresh.session).await.identity().is_ok());
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
        .install(login, GitHubAuthority::PublicRead, None, 700)
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
async fn removing_or_extending_a_provisional_deadline_cannot_bypass_its_authenticated_claims() {
    let h = Harness::new();
    let issued = h.sign_in("access-before", 42, "reader").await;
    let hash = sha256(&issued.session);
    let original = h.store.get(STATE_KEY).unwrap().unwrap();
    for changed_deadline in [serde_json::Value::Null, serde_json::json!(701)] {
        let mut modified = original.clone();
        modified["sessions"][&hash]["confirmBy"] = changed_deadline;
        h.store.set(STATE_KEY, &modified).unwrap();
        assert!(h
            .vault
            .resolve(&issued.session, h.provider.as_ref())
            .await
            .is_err());
        assert_eq!(h.store.get(STATE_KEY).unwrap().unwrap(), modified);
    }
    h.store.set(STATE_KEY, &original).unwrap();
    assert_eq!(
        h.resolve(&issued.session).await.status.session_expires_at,
        100 + SESSION_LIFETIME
    );
}

#[tokio::test]
async fn verification_finishing_after_the_provisional_deadline_cannot_confirm_delivery() {
    let h = Harness::new();
    let issued = h.sign_in("access-before", 42, "reader").await;
    h.provider.delayed.store(true, Ordering::SeqCst);
    let resolve = {
        let (vault, provider, session) =
            (h.vault.clone(), h.provider.clone(), issued.session.clone());
        crate::rt::spawn(async move { vault.resolve(&session, provider.as_ref()).await })
    };
    h.provider.started.notified().await;
    h.clock.0.store(700, Ordering::SeqCst);
    h.provider.release.notify_one();
    assert!(resolve.await.unwrap().is_err());
    let value = h.store.get(STATE_KEY).unwrap().unwrap();
    assert_eq!(value["sessions"][sha256(&issued.session)]["confirmBy"], 700);
}

#[tokio::test]
async fn reopening_the_vault_preserves_pending_expiry_and_confirmed_delivery() {
    let h = Harness::new();
    let pending = h.sign_in("access-pending", 42, "reader").await;
    let received = h.sign_in("access-received", 42, "reader").await;
    let confirmed_expiry = h.resolve(&received.session).await.status.session_expires_at;
    drop(h.vault);
    h.clock.0.store(701, Ordering::SeqCst);
    let reopened = PersonalVault::new(
        h.store.clone(),
        Arc::new(VaultKeys::new(1, BTreeMap::from([(1, [73; 32])])).unwrap()),
        h.clock.clone(),
    )
    .unwrap();
    assert!(reopened
        .resolve(&pending.session, h.provider.as_ref())
        .await
        .is_err());
    let restored = reopened
        .resolve(&received.session, h.provider.as_ref())
        .await
        .unwrap();
    assert_eq!(restored.status.session_expires_at, confirmed_expiry);
    assert_eq!(
        restored.identity().unwrap().account_id().unwrap(),
        received.status.account_id
    );
    assert_eq!(h.provider.calls.load(Ordering::SeqCst), 4);
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
        crate::rt::spawn(async move { vault.resolve(&session, provider.as_ref()).await })
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
        .install(login, GitHubAuthority::PublicRead, Some(stale_link), 700)
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
        .install(login, GitHubAuthority::PublicRead, Some(link), 700)
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
        .install(login, GitHubAuthority::PublicRead, Some(link), 700)
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
        .install(login, GitHubAuthority::PublicRead, None, 700)
        .await
        .unwrap();
    let identity = h.resolve(&issued.session).await.identity().unwrap();
    crate::rt::yield_now().await;
    h.clock.0.store(106, Ordering::SeqCst);
    tokio::time::advance(Duration::from_secs(6)).await;
    crate::rt::yield_now().await;
    assert!(identity.ensure_current().is_err());
    assert!(h
        .vault
        .resolve(&issued.session, h.provider.as_ref())
        .await
        .is_err());
    h.vault.unlink(&issued.session).await.unwrap();
}
