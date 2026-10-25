use super::*;
use crate::{
    identity::VerifiedGitHubIdentity,
    personal_auth::provider::{
        DeviceCode, GitHubTokens, ProviderFuture, ReconnectRequired, RefreshToken,
    },
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
    device_steps: Mutex<VecDeque<&'static str>>,
    device_authorities: Mutex<Vec<GitHubAuthority>>,
    device_polls: AtomicUsize,
    github_id: AtomicU64,
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
        Box::pin(async move {
            let id = match self.github_id.load(Ordering::SeqCst) {
                0 => 42,
                id => id,
            };
            VerifiedGitHubIdentity::fixture(id, "fixture-reader", &token)
        })
    }
    fn device_code<'a>(&'a self, authority: GitHubAuthority) -> ProviderFuture<'a, DeviceCode> {
        Box::pin(async move {
            self.device_authorities.lock().unwrap().push(authority);
            Ok(DeviceCode {
                device_code: "fixture-device-code".into(),
                user_code: "WDJB-MJHT".into(),
                interval: 1,
                expires_in: 900,
            })
        })
    }
    fn poll_device<'a>(&'a self, device_code: &'a str, _: u64) -> ProviderFuture<'a, DevicePoll> {
        Box::pin(async move {
            assert_eq!(device_code, "fixture-device-code");
            self.device_polls.fetch_add(1, Ordering::SeqCst);
            match self
                .device_steps
                .lock()
                .unwrap()
                .pop_front()
                .unwrap_or("pending")
            {
                "pending" => Ok(DevicePoll::Pending),
                "slow" => Ok(DevicePoll::SlowDown(10)),
                "flaky" => anyhow::bail!("fixture-secret-network"),
                "denied" => Err(DeviceDenied.into()),
                "expired" => Err(ReconnectRequired.into()),
                approved => Ok(DevicePoll::Tokens(GitHubTokens {
                    access_token: "fixture-gh-cli".into(),
                    expires_at: None,
                    refresh: None,
                    scopes: if approved == "public" {
                        Default::default()
                    } else {
                        std::collections::BTreeSet::from(["repo".into(), "read:org".into()])
                    },
                })),
            }
        })
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
        Self::with(
            OAuthConfig::new(
                "pully-own-fixture-app".into(),
                "fixture-server-secret".into(),
                "https://auth.pully.example/api/personal/v1/auth/github/callback",
            )
            .unwrap(),
        )
    }
    fn device() -> Self {
        Self::with(OAuthConfig::github_cli())
    }
    fn with(config: OAuthConfig) -> Self {
        let root = Root(
            std::env::temp_dir().join(format!("pully-personal-flow-{}", uuid::Uuid::new_v4())),
        );
        let store = Store::new(root.0.clone()).unwrap();
        let clock = Arc::new(Clock(AtomicU64::new(100)));
        let provider = Arc::new(Provider::default());
        let config = Arc::new(config);
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
    async fn start(&self) -> (StartReply, String) {
        let proof = random_secret().unwrap();
        let reply = self
            .service
            .start(StartRequest {
                native_challenge: wire::native_challenge(&proof).unwrap(),
            })
            .await
            .unwrap();
        (reply, proof)
    }
    async fn ready(&self) -> (StartReply, String) {
        let (start, proof) = self.start().await;
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
    crate::rt::timeout(Duration::from_secs(2), async {
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
            crate::rt::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .expect("accepted work did not reach its terminal phase");
}

#[tokio::test]
async fn actual_start_callback_claim_vault_path_keeps_all_github_tokens_server_side() {
    let h = Harness::new();
    let (start, proof) = h.start().await;
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
async fn sign_out_during_repository_consent_exchange_denies_late_upgrade_installation() {
    let h = Harness::new();
    let (initial, proof) = h.ready().await;
    let issued = h.service.claim(request(&initial, &proof)).await.unwrap();
    h.service.session_status(&issued.session).await.unwrap();
    let private_proof = random_secret().unwrap();
    let consent = h
        .service
        .start_repository_consent(
            &issued.session,
            StartRequest {
                native_challenge: wire::native_challenge(&private_proof).unwrap(),
            },
        )
        .await
        .unwrap();
    h.provider.block.store(true, Ordering::SeqCst);
    let service = h.service.clone();
    let callback_state = state(&consent);
    let callback = crate::rt::spawn(async move {
        service
            .callback(callback_state, Some("fixture-code".into()), false)
            .await
    });
    crate::rt::timeout(Duration::from_secs(2), h.provider.entered.notified())
        .await
        .unwrap();
    h.service.sign_out(&issued.session).await.unwrap();
    let after_sign_out = h.store.get("personal-auth-v1:state").unwrap();
    h.provider.release.notify_one();
    callback.await.unwrap().unwrap();
    let status = h.service.status(request(&consent, &private_proof)).unwrap();
    assert!(matches!(status.phase, AttemptPhase::Failed));
    assert!(matches!(status.failure, Some(FailureKind::Denied)));
    assert!(h
        .service
        .claim(request(&consent, &private_proof))
        .await
        .is_err());
    assert_eq!(
        h.store.get("personal-auth-v1:state").unwrap(),
        after_sign_out
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
    let (start, proof) = h.start().await;
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
    let (start, proof) = h.start().await;
    h.provider.block.store(true, Ordering::SeqCst);
    let callback = {
        let service = h.service.clone();
        let state = state(&start);
        crate::rt::spawn(async move {
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
    let (start, proof) = h.start().await;
    h.provider.block.store(true, Ordering::SeqCst);
    let callback = {
        let service = h.service.clone();
        let state = state(&start);
        crate::rt::spawn(async move {
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
        crate::rt::spawn(async move { service.claim(request).await })
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
async fn lost_claim_observers_past_the_attempt_deadline_cannot_exhaust_the_account_session_cap() {
    let h = Harness::new();
    for lost_handoffs in 1..=33 {
        let (start, proof) = h.ready().await;
        let held = HeldLock::storage(&h.store);
        let claim = {
            let service = h.service.clone();
            let request = request(&start, &proof);
            crate::rt::spawn(async move { service.claim(request).await })
        };
        wait_phase(&h.service, &start.attempt_id, |phase| {
            matches!(phase, Phase::Claiming)
        })
        .await;
        claim.abort();
        assert!(matches!(claim.await, Err(error) if error.is_cancelled()));
        drop(held);
        wait_phase(&h.service, &start.attempt_id, |phase| {
            matches!(phase, Phase::Claimed(_) | Phase::Failed(_))
        })
        .await;
        assert!(
            matches!(
                h.service.0.attempts.lock().unwrap().attempts[&start.attempt_id].phase,
                Phase::Claimed(_)
            ),
            "lost handoff {lost_handoffs} exhausted session capacity"
        );
        h.clock.0.store(start.expires_at + 1, Ordering::SeqCst);
        assert!(matches!(
            h.service.cancel(request(&start, &proof)).await,
            Err(AuthError::Expired)
        ));
    }
    let (start, proof) = h.ready().await;
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    let confirmed = h.service.session_status(&claimed.session).await.unwrap();
    assert_eq!(confirmed.github_id, 42);
    assert!(confirmed.session_expires_at > start.expires_at);
    let value = h.store.get("personal-auth-v1:state").unwrap().unwrap();
    assert_eq!(value["accounts"].as_object().unwrap().len(), 1);
    assert_eq!(value["sessions"].as_object().unwrap().len(), 1);
    assert!(value["sessions"][sha256(&claimed.session)]["confirmBy"].is_null());
}

#[tokio::test]
async fn bearer_confirmation_keeps_a_received_session_current_after_its_attempt_expires() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    assert_eq!(claimed.status.session_expires_at, start.expires_at);
    let confirmed = h.service.session_status(&claimed.session).await.unwrap();
    assert_eq!(confirmed.session_expires_at, 100 + 30 * 24 * 60 * 60);
    h.clock.0.store(start.expires_at + 1, Ordering::SeqCst);
    assert!(matches!(
        h.service.status(request(&start, &proof)),
        Err(AuthError::Expired)
    ));
    assert_eq!(
        h.service
            .session_status(&claimed.session)
            .await
            .unwrap()
            .session_expires_at,
        confirmed.session_expires_at
    );
}

#[tokio::test]
async fn an_unconfirmed_session_cannot_be_promoted_after_the_original_attempt_deadline() {
    let h = Harness::new();
    let (start, proof) = h.ready().await;
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    h.clock.0.store(start.expires_at, Ordering::SeqCst);
    assert!(matches!(
        h.service.session_status(&claimed.session).await,
        Err(AuthError::Reconnect)
    ));
    let value = h.store.get("personal-auth-v1:state").unwrap().unwrap();
    assert_eq!(
        value["sessions"][sha256(&claimed.session)]["confirmBy"],
        start.expires_at
    );
}

#[tokio::test]
async fn browser_handoff_keeps_pending_attempt_until_return_without_background_cancellation() {
    let h = Harness::new();
    let (start, proof) = h.start().await;
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
    crate::rt::yield_now().await;
    tokio::time::advance(Duration::from_secs(601)).await;
    crate::rt::yield_now().await;
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
    let (first, proof) = h.start().await;
    assert!(h.service.status(request(&first, &proof)).is_ok());
    assert!(matches!(
        h.service.status(request(&first, &proof)),
        Err(AuthError::RateLimited)
    ));
    for _ in 1..STARTS_PER_MINUTE {
        h.start().await;
    }
    let challenge = wire::native_challenge(&proof).unwrap();
    assert!(matches!(
        h.service
            .start(StartRequest {
                native_challenge: challenge.clone()
            })
            .await,
        Err(AuthError::RateLimited)
    ));
    for _ in 0..3 {
        h.clock.0.fetch_add(60, Ordering::SeqCst);
        for _ in 0..STARTS_PER_MINUTE {
            h.start().await;
        }
    }
    h.clock.0.fetch_add(60, Ordering::SeqCst);
    assert_eq!(
        h.service.0.attempts.lock().unwrap().attempts.len(),
        MAX_ATTEMPTS
    );
    assert!(matches!(
        h.service
            .start(StartRequest {
                native_challenge: challenge
            })
            .await,
        Err(AuthError::RateLimited)
    ));
}

#[tokio::test]
async fn denied_or_failed_provider_results_are_fixed_safe_states_without_secret_readback() {
    let h = Harness::new();
    let (denied, proof) = h.start().await;
    h.service
        .callback(state(&denied), None, true)
        .await
        .unwrap();
    assert_eq!(
        serde_json::to_value(h.service.status(request(&denied, &proof)).unwrap()).unwrap(),
        serde_json::json!({"phase":"failed","failure":"denied"})
    );
    h.provider.fail.store(true, Ordering::SeqCst);
    let (failed, proof) = h.start().await;
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

#[tokio::test]
async fn explicit_legacy_host_link_runs_the_same_browser_proof_claim_flow_for_that_actual_account()
{
    let h = Harness::new();
    let accounts = crate::account::Accounts::new(h.store.clone());
    let (account, session, _) = accounts.fixture_for_test();
    let proof = random_secret().unwrap();
    let start = h
        .service
        .start_legacy_link(
            StartRequest {
                native_challenge: wire::native_challenge(&proof).unwrap(),
            },
            accounts,
            session,
        )
        .await
        .unwrap();
    h.service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .unwrap();
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    assert_eq!(claimed.status.account_id, account.id);
    assert_eq!(claimed.status.github_id, 42);
    assert_eq!(claimed.status.authority, GitHubAuthority::PublicRead);
}

#[tokio::test]
async fn legacy_link_owner_retired_during_browser_handoff_cannot_install_a_personal_session() {
    let h = Harness::new();
    let accounts = crate::account::Accounts::new(h.store.clone());
    let (_, session, _) = accounts.fixture_for_test();
    let proof = random_secret().unwrap();
    let start = h
        .service
        .start_legacy_link(
            StartRequest {
                native_challenge: wire::native_challenge(&proof).unwrap(),
            },
            accounts.clone(),
            session.clone(),
        )
        .await
        .unwrap();
    h.service
        .callback(state(&start), Some("fixture-code".into()), false)
        .await
        .unwrap();
    accounts.sign_out_async(&session).await.unwrap();
    assert!(h.service.claim(request(&start, &proof)).await.is_err());
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}

fn steps(h: &Harness, steps: &[&'static str]) {
    h.provider.device_steps.lock().unwrap().extend(steps);
}

#[tokio::test(start_paused = true)]
async fn connect_device_login_requests_private_access_once_and_refuses_a_missing_repository_grant()
{
    for approval in ["approve", "public", "denied"] {
        let h = Harness::device();
        let proof = random_secret().unwrap();
        let start = h
            .service
            .connect(StartRequest {
                native_challenge: wire::native_challenge(&proof).unwrap(),
            })
            .await
            .unwrap();
        assert_eq!(
            h.provider.device_authorities.lock().unwrap().as_slice(),
            [GitHubAuthority::PrivateRepositories]
        );
        steps(&h, &[approval]);
        if approval == "approve" {
            assert_eq!(settle(&h, &start.attempt_id).await, "ready");
            let issued = h.service.claim(request(&start, &proof)).await.unwrap();
            assert_eq!(
                issued.status.authority,
                GitHubAuthority::PrivateRepositories
            );
            let confirmed = h.service.session_status(&issued.session).await.unwrap();
            assert_eq!(confirmed.authority, GitHubAuthority::PrivateRepositories);
            assert_eq!(confirmed.github_id, 42);
            assert!(h.service.claim(request(&start, &proof)).await.is_err());
        } else {
            assert_eq!(settle(&h, &start.attempt_id).await, "failed");
            assert!(matches!(
                h.service.status(request(&start, &proof)).unwrap().failure,
                Some(FailureKind::Denied)
            ));
            assert!(h.service.claim(request(&start, &proof)).await.is_err());
            assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
        }
    }
}

/// Device attempts finish on the server's own timer; paused test time lets it run instantly.
async fn settle(h: &Harness, id: &str) -> &'static str {
    for _ in 0..600 {
        crate::rt::sleep(Duration::from_millis(100)).await;
        let attempts = h.service.0.attempts.lock().unwrap();
        match attempts.attempts.get(id).map(|attempt| &attempt.phase) {
            Some(Phase::Ready(_)) => return "ready",
            Some(Phase::Failed(_)) => return "failed",
            Some(Phase::Device(_) | Phase::Exchanging) => {}
            Some(_) => return "other",
            None => return "gone",
        }
    }
    "waiting"
}

#[tokio::test(start_paused = true)]
async fn device_sign_in_shows_githubs_code_and_the_server_polls_until_approval() {
    let h = Harness::device();
    steps(&h, &["pending", "flaky", "pending", "approve"]);
    let (start, proof) = h.start().await;
    assert_eq!(
        serde_json::to_value(&start).unwrap()["authorizeUrl"],
        "https://github.com/login/device"
    );
    assert_eq!(start.user_code.as_deref(), Some("WDJB-MJHT"));
    assert!(!serde_json::to_string(&start)
        .unwrap()
        .contains("fixture-device-code"));
    assert_eq!(start.expires_at, 700);
    assert_eq!(
        h.provider.device_authorities.lock().unwrap().as_slice(),
        [GitHubAuthority::PublicRead]
    );
    assert!(h.service.status(request(&start, &proof)).unwrap().phase == AttemptPhase::Pending);
    assert_eq!(settle(&h, &start.attempt_id).await, "ready");
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 4);
    assert_eq!(h.provider.exchanges.load(Ordering::SeqCst), 0);
    let claimed = h.service.claim(request(&start, &proof)).await.unwrap();
    assert_eq!(claimed.status.github_id, 42);
    let stored = h
        .store
        .get("personal-auth-v1:state")
        .unwrap()
        .unwrap()
        .to_string();
    assert!(!stored.contains("fixture-gh-cli"));
    assert!(matches!(
        h.service
            .callback("a".repeat(43), Some("fixture-code".into()), false)
            .await,
        Err(_)
    ));
}

#[tokio::test(start_paused = true)]
async fn device_sign_in_honours_slow_down_and_ends_on_denial_or_expiry() {
    let h = Harness::device();
    steps(&h, &["slow", "denied"]);
    let (denied, proof) = h.start().await;
    crate::rt::sleep(Duration::from_millis(1500)).await;
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 1);
    // The next poll waits the ten seconds GitHub asked for, not the original one.
    crate::rt::sleep(Duration::from_secs(8)).await;
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 1);
    assert_eq!(settle(&h, &denied.attempt_id).await, "failed");
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 2);
    assert_eq!(
        serde_json::to_value(h.service.status(request(&denied, &proof)).unwrap()).unwrap(),
        serde_json::json!({"phase":"failed","failure":"denied"})
    );
    steps(&h, &["expired"]);
    let (expired, proof) = h.start().await;
    assert_eq!(settle(&h, &expired.attempt_id).await, "failed");
    assert!(matches!(
        h.service.status(request(&expired, &proof)).unwrap().failure,
        Some(FailureKind::Reconnect)
    ));
}

#[tokio::test(start_paused = true)]
async fn cancelling_a_device_sign_in_stops_polling_github() {
    let h = Harness::device();
    let (start, proof) = h.start().await;
    crate::rt::sleep(Duration::from_millis(1500)).await;
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 1);
    assert!(
        h.service
            .cancel(request(&start, &proof))
            .await
            .unwrap()
            .cancelled
    );
    steps(&h, &["approve"]);
    crate::rt::sleep(Duration::from_secs(5)).await;
    assert_eq!(h.provider.device_polls.load(Ordering::SeqCst), 1);
    assert!(h.service.claim(request(&start, &proof)).await.is_err());
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}

#[tokio::test(start_paused = true)]
async fn device_repository_consent_asks_for_repo_scopes_and_must_be_the_same_github_user() {
    let h = Harness::device();
    steps(&h, &["approve"]);
    let (initial, proof) = h.start().await;
    assert_eq!(settle(&h, &initial.attempt_id).await, "ready");
    let issued = h.service.claim(request(&initial, &proof)).await.unwrap();
    let consent_with = |proof: &str| StartRequest {
        native_challenge: wire::native_challenge(proof).unwrap(),
    };
    let other_proof = random_secret().unwrap();
    let other = h
        .service
        .start_repository_consent(&issued.session, consent_with(&other_proof))
        .await
        .unwrap();
    assert_eq!(
        h.provider.device_authorities.lock().unwrap().last(),
        Some(&GitHubAuthority::PrivateRepositories)
    );
    h.provider.github_id.store(7, Ordering::SeqCst);
    steps(&h, &["approve"]);
    assert_eq!(settle(&h, &other.attempt_id).await, "failed");
    assert!(matches!(
        h.service
            .status(request(&other, &other_proof))
            .unwrap()
            .failure,
        Some(FailureKind::Denied)
    ));
    h.provider.github_id.store(42, Ordering::SeqCst);
    h.clock.0.fetch_add(POLL_SECONDS, Ordering::SeqCst);
    let same_proof = random_secret().unwrap();
    let same = h
        .service
        .start_repository_consent(&issued.session, consent_with(&same_proof))
        .await
        .unwrap();
    steps(&h, &["approve"]);
    assert_eq!(settle(&h, &same.attempt_id).await, "ready");
    let upgraded = h.service.claim(request(&same, &same_proof)).await.unwrap();
    assert_eq!(upgraded.status.github_id, 42);
}
