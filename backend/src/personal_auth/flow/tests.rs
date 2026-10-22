use super::*;
use crate::{
    identity::VerifiedGitHubIdentity,
    personal_auth::provider::{GitHubTokens, ProviderFuture, RefreshToken},
    store::test_support::HeldLock,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
};
use tokio::sync::Notify;
use url::Url;

struct Clock(AtomicU64);
impl AuthClock for Clock {
    fn now(&self) -> u64 {
        self.0.load(Ordering::SeqCst)
    }
}

#[derive(Default)]
struct Provider {
    exchanges: AtomicUsize,
    block: AtomicBool,
    fail: AtomicBool,
    entered: Notify,
    release: Notify,
    verifiers: Mutex<Vec<String>>,
}
impl GitHubProvider for Provider {
    fn exchange<'a>(
        &'a self,
        code: &'a str,
        verifier: &'a str,
        now: u64,
    ) -> super::super::provider::ProviderFuture<'a, GitHubTokens> {
        Box::pin(async move {
            self.exchanges.fetch_add(1, Ordering::SeqCst);
            self.verifiers.lock().unwrap().push(verifier.to_owned());
            if self.block.load(Ordering::SeqCst) {
                self.entered.notify_one();
                self.release.notified().await;
            }
            if self.fail.load(Ordering::SeqCst) {
                anyhow::bail!("fixture-secret-provider-body");
            }
            if code != "fixture-code" {
                anyhow::bail!("Invalid fixture code");
            }
            Ok(GitHubTokens {
                access_token: "fixture-access".into(),
                expires_at: Some(now + 28800),
                refresh: Some(RefreshToken {
                    token: "fixture-refresh".into(),
                    expires_at: now + 15897600,
                }),
                scopes: std::collections::BTreeSet::from(["repo".into()]),
            })
        })
    }
    fn refresh<'a>(&'a self, _: &'a str, _: u64) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async { anyhow::bail!("Unexpected refresh") })
    }
    fn verify<'a>(&'a self, token: String) -> ProviderFuture<'a, VerifiedGitHubIdentity> {
        Box::pin(async move { VerifiedGitHubIdentity::fixture(42, "fixture-reader", &token) })
    }
}
struct Root(PathBuf);
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
pub(in crate::personal_auth) struct Harness {
    _root: Root,
    pub(in crate::personal_auth) store: Store,
    pub(in crate::personal_auth) service: PersonalAuth,
    clock: Arc<Clock>,
    provider: Arc<Provider>,
}
impl Harness {
    pub(in crate::personal_auth) fn new() -> Self {
        let root = Root(
            std::env::temp_dir().join(format!("pully-personal-flow-{}", uuid::Uuid::new_v4())),
        );
        let store = Store::new(root.0.clone()).unwrap();
        let clock = Arc::new(Clock(AtomicU64::new(100)));
        let provider = Arc::new(Provider::default());
        let config = Arc::new(
            OAuthConfig::new(
                "pully-own-fixture-app".into(),
                "fixture-server-secret".into(),
                "https://auth.pully.example/api/personal/v1/auth/github/callback",
            )
            .unwrap(),
        );
        let vault = PersonalVault::new(
            store.clone(),
            Arc::new(VaultKeys::new(1, BTreeMap::from([(1, [19; 32])])).unwrap()),
            clock.clone(),
        )
        .unwrap();
        let service =
            PersonalAuth::construct(config, provider.clone(), vault, clock.clone()).unwrap();
        Self {
            _root: root,
            store,
            service,
            clock,
            provider,
        }
    }
    fn start(&self) -> (StartReply, String) {
        let proof = random_secret().unwrap();
        let reply = self
            .service
            .start(StartRequest {
                native_challenge: wire::native_challenge(&proof).unwrap(),
            })
            .unwrap();
        (reply, proof)
    }
    async fn ready(&self) -> (StartReply, String) {
        let (start, proof) = self.start();
        self.service
            .callback(state(&start), Some("fixture-code".into()), false)
            .await
            .unwrap();
        (start, proof)
    }
}
fn state(start: &StartReply) -> String {
    Url::parse(&start.authorize_url)
        .unwrap()
        .query_pairs()
        .find(|(key, _)| key == "state")
        .unwrap()
        .1
        .into_owned()
}
fn request(start: &StartReply, proof: &str) -> AttemptRequest {
    AttemptRequest {
        attempt_id: start.attempt_id.clone(),
        native_proof: proof.into(),
    }
}
async fn wait_phase(service: &PersonalAuth, id: &str, predicate: impl Fn(&Phase) -> bool) {
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if service
                .0
                .attempts
                .lock()
                .unwrap()
                .attempts
                .get(id)
                .is_some_and(|attempt| predicate(&attempt.phase))
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .expect("accepted work did not reach its terminal phase");
}

#[tokio::test]
async fn actual_start_callback_claim_vault_path_keeps_all_github_tokens_server_side() {
    let h = Harness::new();
    let (start, proof) = h.start();
    let url = Url::parse(&start.authorize_url).unwrap();
    assert_eq!(url.origin().ascii_serialization(), "https://github.com");
    assert!(!url.as_str().contains(&proof));
    assert!(!url.as_str().contains("fixture-server-secret"));
    assert_eq!(start.expires_at, 700);
    assert_eq!(start.poll_after_ms, 2000);
    h.service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .unwrap();
    let status = h.service.status(request(&start, &proof)).unwrap();
    assert!(status.phase == AttemptPhase::Ready);
    let verifier = h.provider.verifiers.lock().unwrap()[0].clone();
    let browser_challenge = url
        .query_pairs()
        .find(|(key, _)| key == "code_challenge")
        .unwrap()
        .1
        .into_owned();
    assert_eq!(
        browser_challenge,
        wire::native_challenge(&verifier).unwrap()
    );
    assert_ne!(browser_challenge, wire::native_challenge(&proof).unwrap());
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    assert_eq!(claimed.status.github_id, 42);
    assert_eq!(claimed.status.authority, GitHubAuthority::PublicRead);
    let body = serde_json::to_string(&claimed).unwrap();
    assert!(!body.contains("fixture-access") && !body.contains("fixture-refresh"));
    let stored = h
        .store
        .get("personal-auth-v1:state")
        .unwrap()
        .unwrap()
        .to_string();
    assert!(
        !stored.contains(&claimed.session)
            && !stored.contains("fixture-access")
            && !stored.contains("fixture-refresh")
    );
    let resolved = h
        .service
        .0
        .vault
        .resolve(&claimed.session, h.provider.as_ref())
        .await
        .unwrap();
    assert_eq!(
        resolved.identity().unwrap().account_id().unwrap(),
        claimed.status.account_id
    );
}

#[tokio::test]
async fn wrong_proof_cannot_status_cancel_or_consume_the_legitimate_claim() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let wrong = URL_SAFE_NO_PAD.encode([27; 32]);
    assert!(matches!(
        h.service.status(request(&start, &wrong)),
        Err(AuthError::Unauthorized)
    ));
    assert!(matches!(
        h.service.cancel(request(&start, &wrong)).await,
        Err(AuthError::Unauthorized)
    ));
    assert!(matches!(
        h.service.claim(request(&start, &wrong)).await,
        Err(AuthError::Unauthorized)
    ));
    assert!(h.service.claim(request(&start, &proof)).await.is_ok());
}

#[tokio::test]
async fn callback_state_is_consumed_once_before_exchange_and_cannot_be_replayed() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    assert!(matches!(
        h.service
            .callback(state(&start), Some("fixture-code".into()), false)
            .await,
        Err(AuthError::Unauthorized)
    ));
    assert_eq!(h.provider.exchanges.load(Ordering::SeqCst), 1);
    assert!(h.service.claim(request(&start, &proof)).await.is_ok());
}

#[tokio::test]
async fn concurrent_claims_publish_exactly_one_session_without_reusing_the_receipt() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let (a, b) = tokio::join!(
        h.service.claim(request(&start, &proof)),
        h.service.claim(request(&start, &proof))
    );
    assert_eq!(usize::from(a.is_ok()) + usize::from(b.is_ok()), 1);
    assert!(matches!(
        h.service.claim(request(&start, &proof)).await,
        Err(AuthError::Reconnect)
    ));
    assert_eq!(
        h.store.get("personal-auth-v1:state").unwrap().unwrap()["sessions"]
            .as_object()
            .unwrap()
            .len(),
        1
    );
}

#[tokio::test]
async fn explicit_cancel_removes_callback_authority_and_never_exchanges_a_late_code() {
    let h = Harness::new();
    let (start, proof) = h.start();
    assert!(
        h.service
            .cancel(request(&start, &proof))
            .await
            .unwrap()
            .cancelled
    );
    assert!(h
        .service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .is_err());
    assert_eq!(h.provider.exchanges.load(Ordering::SeqCst), 0);
    assert!(h.service.status(request(&start, &proof)).unwrap().phase == AttemptPhase::Cancelled);
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}

#[tokio::test]
async fn cancellation_during_accepted_exchange_denies_its_late_tokens() {
    let h = Harness::new();
    let (start, proof) = h.start();
    h.provider.block.store(true, Ordering::SeqCst);
    let callback = {
        let service = h.service.clone();
        let state = state(&start);
        tokio::spawn(async move {
            service
                .callback(state, Some("fixture-code".into()), false)
                .await
        })
    };
    h.provider.entered.notified().await;
    h.service.cancel(request(&start, &proof)).await.unwrap();
    h.provider.release.notify_one();
    assert!(matches!(
        callback.await.unwrap(),
        Err(AuthError::Unauthorized)
    ));
    assert!(h.service.claim(request(&start, &proof)).await.is_err());
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}

#[tokio::test]
async fn dropping_callback_http_observer_does_not_cancel_the_accepted_exchange() {
    let h = Harness::new();
    let (start, proof) = h.start();
    h.provider.block.store(true, Ordering::SeqCst);
    let callback = {
        let service = h.service.clone();
        let state = state(&start);
        tokio::spawn(async move {
            service
                .callback(state, Some("fixture-code".into()), false)
                .await
        })
    };
    h.provider.entered.notified().await;
    callback.abort();
    let _ = callback.await;
    h.provider.release.notify_one();
    wait_phase(&h.service, &start.attempt_id, |phase| {
        matches!(phase, Phase::Ready(_))
    })
    .await;
    assert!(h.service.claim(request(&start, &proof)).await.is_ok());
    assert_eq!(h.provider.exchanges.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn cancellation_during_claim_commit_retires_the_created_session_before_publication() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let held = HeldLock::storage(&h.store);
    let claim = {
        let service = h.service.clone();
        let request = request(&start, &proof);
        tokio::spawn(async move { service.claim(request).await })
    };
    wait_phase(&h.service, &start.attempt_id, |phase| {
        matches!(phase, Phase::Claiming)
    })
    .await;
    h.service.cancel(request(&start, &proof)).await.unwrap();
    drop(held);
    assert!(matches!(claim.await.unwrap(), Err(AuthError::Expired)));
    let value = h.store.get("personal-auth-v1:state").unwrap().unwrap();
    assert!(value["sessions"].as_object().unwrap().is_empty());
}

#[tokio::test]
async fn cancellation_after_a_successful_claim_revokes_its_exact_session_and_captured_binding() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    let identity = h
        .service
        .0
        .vault
        .resolve(&claimed.session, h.provider.as_ref())
        .await
        .unwrap()
        .identity()
        .unwrap();
    h.service.cancel(request(&start, &proof)).await.unwrap();
    assert!(identity.ensure_current().is_err());
    assert!(h
        .service
        .0
        .vault
        .resolve(&claimed.session, h.provider.as_ref())
        .await
        .is_err());
    assert!(h.service.claim(request(&start, &proof)).await.is_err());
}

#[tokio::test]
async fn browser_handoff_keeps_pending_attempt_until_return_without_background_cancellation() {
    let h = Harness::new();
    let (start, proof) = h.start();
    assert!(h.service.status(request(&start, &proof)).unwrap().phase == AttemptPhase::Pending);
    h.clock.0.fetch_add(120, Ordering::SeqCst);
    h.service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .unwrap();
    assert!(h.service.status(request(&start, &proof)).unwrap().phase == AttemptPhase::Ready);
    assert!(h.service.claim(request(&start, &proof)).await.is_ok());
}

#[tokio::test]
async fn deadline_and_monotonic_cleanup_drop_ready_private_tokens_without_claiming() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    h.clock.0.store(701, Ordering::SeqCst);
    assert!(matches!(
        h.service.status(request(&start, &proof)),
        Err(AuthError::Expired)
    ));
    assert!(matches!(
        h.service.claim(request(&start, &proof)).await,
        Err(AuthError::Expired)
    ));
    assert!(h
        .service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .is_err());
    assert!(h.service.0.attempts.lock().unwrap().attempts.is_empty());
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}

#[tokio::test(start_paused = true)]
async fn unattended_ready_attempt_is_removed_by_its_lifetime_timer() {
    let h = Harness::new();
    let (start, _) = h.ready().await;
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(601)).await;
    tokio::task::yield_now().await;
    assert!(!h
        .service
        .0
        .attempts
        .lock()
        .unwrap()
        .attempts
        .contains_key(&start.attempt_id));
}

#[tokio::test]
async fn start_and_poll_rates_and_total_pending_attempts_are_bounded() {
    let h = Harness::new();
    let (first, proof) = h.start();
    assert!(h.service.status(request(&first, &proof)).is_ok());
    assert!(matches!(
        h.service.status(request(&first, &proof)),
        Err(AuthError::RateLimited)
    ));
    for _ in 1..STARTS_PER_MINUTE {
        h.start();
    }
    let challenge = wire::native_challenge(&proof).unwrap();
    assert!(matches!(
        h.service.start(StartRequest {
            native_challenge: challenge.clone()
        }),
        Err(AuthError::RateLimited)
    ));
    for _ in 0..3 {
        h.clock.0.fetch_add(60, Ordering::SeqCst);
        for _ in 0..STARTS_PER_MINUTE {
            h.start();
        }
    }
    h.clock.0.fetch_add(60, Ordering::SeqCst);
    assert_eq!(
        h.service.0.attempts.lock().unwrap().attempts.len(),
        MAX_ATTEMPTS
    );
    assert!(matches!(
        h.service.start(StartRequest {
            native_challenge: challenge
        }),
        Err(AuthError::RateLimited)
    ));
}

#[tokio::test]
async fn denied_or_failed_provider_results_are_fixed_safe_states_without_secret_readback() {
    let h = Harness::new();
    let (denied, proof) = h.start();
    h.service
        .callback(state(&denied), None, true)
        .await
        .unwrap();
    assert_eq!(
        serde_json::to_value(h.service.status(request(&denied, &proof)).unwrap()).unwrap(),
        serde_json::json!({"phase":"failed","failure":"denied"})
    );
    h.provider.fail.store(true, Ordering::SeqCst);
    let (failed, proof) = h.start();
    h.service
        .callback(state(&failed), Some("fixture-code".into()), false)
        .await
        .unwrap();
    let status =
        serde_json::to_string(&h.service.status(request(&failed, &proof)).unwrap()).unwrap();
    assert!(!status.contains("fixture-secret") && !status.contains(&proof));
    assert!(status.contains("unavailable"));
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}
