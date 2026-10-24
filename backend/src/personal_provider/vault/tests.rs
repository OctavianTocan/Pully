use super::*;
use crate::{
    account::Account, identity::VerifiedGitHubIdentity, personal_provider::TrustedPiEndpoint,
};
use axum::{
    body::{Body, Bytes},
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
    Json, Router,
};
use serde_json::{json, Value};
use std::{
    convert::Infallible,
    net::{IpAddr, Ipv4Addr},
    sync::atomic::AtomicUsize,
};
use tokio::sync::Notify;

struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("pully-personal-pi-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
struct Harness {
    _root: Root,
    store: Store,
    providers: PersonalProviders,
    endpoint: String,
}
impl Harness {
    fn new(endpoint: &str) -> Self {
        let root = Root::new();
        let store = Store::new(root.0.clone()).unwrap();
        let keys = Arc::new(VaultKeys::new(1, BTreeMap::from([(1, [39; 32])])).unwrap());
        let policy = Arc::new(
            PiEndpointPolicy::with_trusted(vec![TrustedPiEndpoint::pinned(
                endpoint,
                vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            )
            .unwrap()])
            .unwrap(),
        );
        let providers = PersonalProviders::new(store.clone(), keys, policy);
        Self {
            _root: root,
            store,
            providers,
            endpoint: endpoint.into(),
        }
    }
    async fn configure(&self, identity: &RequestIdentity) -> ConnectionStatus {
        self.providers
            .configure_pi(
                identity,
                initial(),
                setup(
                    Some(&self.endpoint),
                    Some("gpt-5.1"),
                    PiCredentialChange::Replace {
                        value: "fixture-pi-bearer".into(),
                    },
                ),
            )
            .await
            .unwrap()
    }
    fn state(&self) -> Option<Value> {
        self.store.get(STATE_KEY).unwrap()
    }
}
fn identity(account: &str, github_id: u64) -> RequestIdentity {
    RequestIdentity::Account(
        VerifiedGitHubIdentity::fixture(github_id, "renameable-login", "fixture-github-authority")
            .unwrap()
            .bind(&Account {
                id: account.into(),
                email: String::new(),
            })
            .unwrap(),
    )
}
fn revoke(identity: &RequestIdentity) {
    let RequestIdentity::Account(credential) = identity else {
        panic!("fixture needs an account")
    };
    credential.revoke();
}
fn initial() -> ExpectedConnection {
    ExpectedConnection {
        connection_id: None,
        revision: None,
    }
}
fn expected(status: &ConnectionStatus) -> ExpectedConnection {
    ExpectedConnection {
        connection_id: status.connection_id.clone(),
        revision: status.revision,
    }
}
fn setup(
    endpoint: Option<&str>,
    model: Option<&str>,
    credential: PiCredentialChange,
) -> PiSetupInput {
    PiSetupInput {
        endpoint: endpoint.map(str::to_owned),
        preferred_model: model.map(str::to_owned),
        credential,
    }
}
fn pi(rows: Vec<ConnectionStatus>) -> ConnectionStatus {
    rows.into_iter().last().unwrap()
}
fn state_name(status: &ConnectionStatus) -> String {
    serde_json::to_value(status).unwrap()["state"]
        .as_str()
        .unwrap()
        .to_owned()
}

#[tokio::test]
async fn configuration_encrypts_the_whole_destination_and_secret_and_reopens_without_readback() {
    let h = Harness::new("http://127.0.0.1:8317/private-fixture-path");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    assert_eq!(state_name(&status), "unverified");
    assert!(!status.capabilities.execute);
    let state = h.state().unwrap().to_string();
    for private in [
        "fixture-pi-bearer",
        "private-fixture-path",
        "gpt-5.1",
        "fixture-github-authority",
    ] {
        assert!(!state.contains(private));
    }
    let public = serde_json::to_string(&status).unwrap();
    assert!(!public.contains("fixture-pi-bearer"));
    assert!(!public.contains("private-fixture-path"));
    assert_eq!(
        status.endpoint_origin.as_deref(),
        Some("http://127.0.0.1:8317")
    );
    let reopened = PersonalProviders::new(
        Store::new(h.store.root().to_owned()).unwrap(),
        h.providers.0.keys.clone(),
        h.providers.0.policy.clone(),
    );
    let loaded = pi(reopened.load(&owner).await.unwrap());
    assert_eq!(loaded.connection_id, status.connection_id);
    assert_eq!(loaded.preferred_model.as_deref(), Some("gpt-5.1"));
    assert!(loaded.has_credential);
    reopened
        .pi_lease(&owner, expected(&status))
        .await
        .unwrap()
        .ensure_current()
        .await
        .unwrap();
}

#[tokio::test]
async fn guest_legacy_and_different_immutable_subjects_cannot_enter_the_personal_vault() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let before = h.state();
    for wrong in [
        RequestIdentity::Guest,
        RequestIdentity::LegacyPrivate,
        identity("account-a", 43),
    ] {
        assert!(h.providers.load(&wrong).await.is_err());
        assert!(h
            .providers
            .pi_lease(&wrong, expected(&status))
            .await
            .is_err());
        assert!(h
            .providers
            .configure_pi(
                &wrong,
                expected(&status),
                setup(None, None, PiCredentialChange::Keep {})
            )
            .await
            .is_err());
        assert!(h
            .providers
            .remove_pi(&wrong, expected(&status))
            .await
            .is_err());
        assert_eq!(h.state(), before);
    }
    let other = identity("account-b", 43);
    let empty = pi(h.providers.load(&other).await.unwrap());
    assert_eq!(state_name(&empty), "disconnected");
    assert!(!empty.has_credential);
    assert!(h
        .providers
        .pi_lease(&other, expected(&status))
        .await
        .is_err());
}

#[tokio::test]
async fn keep_replace_clear_and_changed_endpoint_never_move_a_captured_credential_implicitly() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let before = h.state();
    assert!(h
        .providers
        .configure_pi(
            &owner,
            expected(&status),
            setup(
                Some("http://127.0.0.1:8317/other"),
                None,
                PiCredentialChange::Keep {}
            )
        )
        .await
        .is_err());
    assert_eq!(h.state(), before);
    let kept = h
        .providers
        .configure_pi(
            &owner,
            expected(&status),
            setup(None, Some(""), PiCredentialChange::Keep {}),
        )
        .await
        .unwrap();
    assert!(kept.has_credential);
    assert!(kept.preferred_model.is_none());
    assert_eq!(kept.revision, Some(status.revision.unwrap() + 1));
    let cleared = h
        .providers
        .configure_pi(
            &owner,
            expected(&kept),
            setup(None, None, PiCredentialChange::Clear {}),
        )
        .await
        .unwrap();
    assert!(!cleared.has_credential);
    let replaced = h
        .providers
        .configure_pi(
            &owner,
            expected(&cleared),
            setup(
                None,
                None,
                PiCredentialChange::Replace {
                    value: "fixture-next-bearer".into(),
                },
            ),
        )
        .await
        .unwrap();
    assert!(replaced.has_credential);
    assert!(!h
        .state()
        .unwrap()
        .to_string()
        .contains("fixture-next-bearer"));
}

#[tokio::test]
async fn concurrent_updates_admit_only_one_exact_connection_revision() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let (a, b) = tokio::join!(
        h.providers.configure_pi(
            &owner,
            expected(&status),
            setup(None, Some("model-a"), PiCredentialChange::Keep {})
        ),
        h.providers.configure_pi(
            &owner,
            expected(&status),
            setup(None, Some("model-b"), PiCredentialChange::Keep {})
        ),
    );
    assert_ne!(a.is_ok(), b.is_ok());
    let loaded = pi(h.providers.load(&owner).await.unwrap());
    assert_eq!(loaded.revision, Some(status.revision.unwrap() + 1));
    assert!(matches!(
        loaded.preferred_model.as_deref(),
        Some("model-a" | "model-b")
    ));
}

#[tokio::test]
async fn remove_from_another_store_instance_retires_old_leases_and_reconfigure_keeps_a_revision_fence(
) {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let original = h.configure(&owner).await;
    let lease = h
        .providers
        .pi_lease(&owner, expected(&original))
        .await
        .unwrap();
    let other = PersonalProviders::new(
        Store::new(h.store.root().to_owned()).unwrap(),
        h.providers.0.keys.clone(),
        h.providers.0.policy.clone(),
    );
    let removed = other.remove_pi(&owner, expected(&original)).await.unwrap();
    assert_eq!(state_name(&removed), "disconnected");
    assert!(removed.connection_id.is_none());
    assert!(
        crate::rt::timeout(Duration::from_secs(1), lease.retired())
            .await
            .unwrap()
            .is_err()
    );
    assert!(lease.ensure_current().await.is_err());
    let replacement = h.configure(&owner).await;
    assert_ne!(replacement.connection_id, original.connection_id);
    assert!(replacement.revision.unwrap() > original.revision.unwrap() + 1);
    let fresh = other
        .pi_lease(&owner, expected(&replacement))
        .await
        .unwrap();
    h.providers.0.runtime.lock().unwrap().block(&lease.key);
    h.providers
        .0
        .runtime
        .lock()
        .unwrap()
        .retire_before("account-a", original.revision.unwrap() + 1);
    fresh.ensure_current().await.unwrap();
}

#[tokio::test]
async fn sign_out_retires_only_the_captured_principal_not_another_current_session() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let first = identity("account-a", 42);
    let second = identity("account-a", 42);
    let status = h.configure(&first).await;
    let old = h
        .providers
        .pi_lease(&first, expected(&status))
        .await
        .unwrap();
    let current = h
        .providers
        .pi_lease(&second, expected(&status))
        .await
        .unwrap();
    revoke(&first);
    assert!(crate::rt::timeout(Duration::from_secs(1), old.retired())
        .await
        .unwrap()
        .is_err());
    assert!(old.ensure_current().await.is_err());
    current.ensure_current().await.unwrap();
    assert_eq!(current.principal().owner_id(), "account-a");
}

#[tokio::test]
async fn ciphertext_copied_between_owners_revisions_or_connection_ids_cannot_be_opened() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let a = identity("account-a", 42);
    let b = identity("account-b", 43);
    let a_status = h.configure(&a).await;
    let b_status = h.configure(&b).await;
    let baseline = h.state().unwrap();
    for field in ["owner", "revision", "connectionId"] {
        let mut corrupted = baseline.clone();
        match field {
            "owner" => {
                corrupted["owners"]["account-b"]["connection"]["sealed"] =
                    baseline["owners"]["account-a"]["connection"]["sealed"].clone()
            }
            "revision" => {
                corrupted["owners"]["account-b"]["revision"] = json!(b_status.revision.unwrap() + 1)
            }
            _ => {
                corrupted["owners"]["account-b"]["connection"]["id"] =
                    json!(uuid::Uuid::new_v4().to_string())
            }
        }
        h.store.set(STATE_KEY, &corrupted).unwrap();
        assert!(h.providers.load(&b).await.is_err());
        assert!(h.providers.pi_lease(&b, expected(&b_status)).await.is_err());
        h.providers
            .pi_lease(&a, expected(&a_status))
            .await
            .unwrap()
            .ensure_current()
            .await
            .unwrap();
    }
    h.store.set(STATE_KEY, &baseline).unwrap();
}

#[tokio::test]
async fn setup_bounds_and_paired_expected_fields_fail_before_mutating_state() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let before = h.state();
    for model in [
        " ".to_string(),
        "model\nsecret".into(),
        "a".repeat(MAX_PI_MODEL_BYTES + 1),
        "é".repeat(MAX_PI_MODEL_BYTES / 2 + 1),
    ] {
        assert!(h
            .providers
            .configure_pi(
                &owner,
                expected(&status),
                setup(None, Some(&model), PiCredentialChange::Keep {})
            )
            .await
            .is_err());
    }
    for value in [
        String::new(),
        "a key".into(),
        "key\nvalue".into(),
        "clé".into(),
        "a".repeat(MAX_PI_CREDENTIAL_BYTES + 1),
    ] {
        assert!(h
            .providers
            .configure_pi(
                &owner,
                expected(&status),
                setup(None, None, PiCredentialChange::Replace { value })
            )
            .await
            .is_err());
    }
    for expected in [
        ExpectedConnection {
            connection_id: status.connection_id.clone(),
            revision: None,
        },
        ExpectedConnection {
            connection_id: None,
            revision: status.revision,
        },
        ExpectedConnection {
            connection_id: status.connection_id.clone(),
            revision: Some(u64::MAX),
        },
    ] {
        assert!(h.providers.remove_pi(&owner, expected).await.is_err());
    }
    assert_eq!(h.state(), before);
    let max_model = "a".repeat(MAX_PI_MODEL_BYTES);
    h.providers
        .configure_pi(
            &owner,
            expected(&status),
            setup(None, Some(&max_model), PiCredentialChange::Keep {}),
        )
        .await
        .unwrap();
    assert!(serde_json::from_value::<PiSetupInput>(
        json!({"endpoint":h.endpoint,"credential":{"action":"keep","value":"secret"}})
    )
    .is_err());
    assert!(serde_json::from_value::<PiSetupInput>(
        json!({"endpoint":h.endpoint,"credential":{"action":"clear"},"accountId":"someone-else"})
    )
    .is_err());
    assert!(serde_json::from_value::<PiSetupInput>(
        json!({"endpoint":h.endpoint,"credential":{"action":"clear","value":"secret"}})
    )
    .is_err());
    for credential in [
        json!({"action":"keep"}),
        json!({"action":"clear"}),
        json!({"action":"replace","value":"fixture-pi-bearer"}),
    ] {
        assert!(serde_json::from_value::<PiSetupInput>(
            json!({"endpoint":h.endpoint,"credential":credential})
        )
        .is_ok());
    }
}

#[tokio::test]
async fn revoked_source_waiting_for_actual_storage_cannot_commit_configuration() {
    let h = Harness::new("http://127.0.0.1:8317/v1");
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let before = h.state();
    let held = crate::store::test_support::HeldLock::storage(&h.store);
    let mut operation = Box::pin(h.providers.configure_pi(
        &owner,
        expected(&status),
        setup(None, Some("new-model"), PiCredentialChange::Keep {}),
    ));
    assert!(futures_util::poll!(&mut operation).is_pending());
    revoke(&owner);
    drop(held);
    assert!(crate::rt::timeout(Duration::from_secs(1), operation)
        .await
        .unwrap()
        .is_err());
    assert_eq!(h.state(), before);
}

#[derive(Default)]
struct Reply {
    mode: AtomicUsize,
    calls: AtomicUsize,
    outside: AtomicUsize,
    authorizations: Mutex<Vec<Option<String>>>,
    entered: Notify,
    release: Notify,
}
async fn models(State(reply): State<Arc<Reply>>, headers: HeaderMap) -> Response {
    reply.calls.fetch_add(1, Ordering::SeqCst);
    reply.authorizations.lock().unwrap().push(
        headers
            .get("authorization")
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned),
    );
    let mode = reply.mode.load(Ordering::SeqCst);
    if mode == 4 {
        reply.entered.notify_one();
        reply.release.notified().await;
    }
    match mode {
        1 => (StatusCode::UNAUTHORIZED, "fixture-pi-bearer upstream error").into_response(),
        2 => (StatusCode::FOUND, [("location", "/outside")], "redirect denied").into_response(),
        3 => {
            let chunk = Bytes::from(vec![b' '; MAX_CATALOG_BYTES / 2 + 1]);
            let chunks = futures_util::stream::iter([Ok::<_, Infallible>(chunk.clone()), Ok(chunk)]);
            Response::new(Body::from_stream(chunks))
        },
        5 => Json(json!({"data":[{"id":"fixture-pi-bearer","owned_by":"test"}]})).into_response(),
        6 => Json(json!({"error":"fixture-pi-bearer invalid body"})).into_response(),
        7 => {
            let stream = futures_util::stream::once(async move { reply.entered.notify_one(); Ok::<_, Infallible>(Bytes::from_static(b"{\"data\":")) })
                .chain(futures_util::stream::once(std::future::pending::<Result<Bytes, Infallible>>()));
            Response::new(Body::from_stream(stream))
        },
        _ => Json(json!({"data":[{"id":"gpt-5.1","owned_by":"openai"},{"id":"gpt-5.1"},{"id":"text-embedding-3-large"}]})).into_response(),
    }
}
struct Provider {
    base: String,
    reply: Arc<Reply>,
    task: crate::rt::JoinHandle<()>,
}
impl Provider {
    async fn new() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}/v1", listener.local_addr().unwrap());
        let reply = Arc::new(Reply::default());
        let router = Router::new()
            .route("/v1/models", get(models))
            .route(
                "/outside",
                get(|State(reply): State<Arc<Reply>>| async move {
                    reply.outside.fetch_add(1, Ordering::SeqCst);
                    Json(json!({"data":[{"id":"outside"}]}))
                }),
            )
            .with_state(reply.clone());
        let task = crate::rt::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        Self { base, reply, task }
    }
}
impl Drop for Provider {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[tokio::test]
async fn actual_models_request_uses_only_its_credential_and_canonical_rows_then_current_cache() {
    let server = Provider::new().await;
    let h = Harness::new(&server.base);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let first = h
        .providers
        .pi_models(&owner, expected(&status), false)
        .await
        .unwrap();
    assert_eq!(first.account_id, "account-a");
    assert_eq!(
        first.connection_id,
        status.connection_id.as_deref().unwrap()
    );
    assert_eq!(first.revision, status.revision.unwrap());
    assert_eq!(first.models.len(), 1);
    assert_eq!(first.models[0]["id"], "gpt-5.1");
    assert_eq!(
        server.reply.authorizations.lock().unwrap().as_slice(),
        [Some("Bearer fixture-pi-bearer".into())]
    );
    assert_eq!(
        state_name(&pi(h.providers.load(&owner).await.unwrap())),
        "connected"
    );
    h.providers
        .pi_models(&owner, expected(&status), false)
        .await
        .unwrap();
    assert_eq!(server.reply.calls.load(Ordering::SeqCst), 1);
    h.providers
        .pi_models(&owner, expected(&status), true)
        .await
        .unwrap();
    assert_eq!(server.reply.calls.load(Ordering::SeqCst), 2);
    revoke(&owner);
    assert!(h
        .providers
        .pi_models(&owner, expected(&status), false)
        .await
        .is_err());
    assert_eq!(server.reply.calls.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn redirects_chunk_overflow_secret_echo_and_malformed_bodies_are_safe_failures() {
    let server = Provider::new().await;
    let h = Harness::new(&server.base);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    for mode in [2, 3, 5, 6] {
        server.reply.mode.store(mode, Ordering::SeqCst);
        let error = h
            .providers
            .pi_models(&owner, expected(&status), true)
            .await
            .err()
            .expect("unsafe provider result accepted");
        assert!(!format!("{error:#}").contains("fixture-pi-bearer"));
        assert_eq!(
            state_name(&pi(h.providers.load(&owner).await.unwrap())),
            "unverified"
        );
    }
    assert_eq!(server.reply.outside.load(Ordering::SeqCst), 0);
    assert_eq!(server.reply.calls.load(Ordering::SeqCst), 4);
}

#[tokio::test]
async fn credential_rejection_marks_only_that_current_connection_for_reconnect() {
    let server = Provider::new().await;
    let h = Harness::new(&server.base);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    h.providers
        .pi_models(&owner, expected(&status), true)
        .await
        .unwrap();
    server.reply.mode.store(1, Ordering::SeqCst);
    assert!(h
        .providers
        .pi_models(&owner, expected(&status), true)
        .await
        .is_err());
    let rejected = pi(h.providers.load(&owner).await.unwrap());
    assert_eq!(state_name(&rejected), "refreshNeeded");
    assert!(!rejected.capabilities.execute);
    assert_eq!(rejected.connection_id, status.connection_id);
    server.reply.mode.store(0, Ordering::SeqCst);
    h.providers
        .pi_models(&owner, expected(&status), true)
        .await
        .unwrap();
    assert_eq!(
        state_name(&pi(h.providers.load(&owner).await.unwrap())),
        "connected"
    );
}

#[tokio::test]
async fn source_retirement_cancels_a_held_real_request_and_denies_its_late_catalog() {
    let server = Provider::new().await;
    server.reply.mode.store(4, Ordering::SeqCst);
    let h = Harness::new(&server.base);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let lease = h
        .providers
        .pi_lease(&owner, expected(&status))
        .await
        .unwrap();
    let work = crate::rt::spawn(async move { lease.models(true).await });
    crate::rt::timeout(Duration::from_secs(1), server.reply.entered.notified())
        .await
        .unwrap();
    revoke(&owner);
    assert!(crate::rt::timeout(Duration::from_secs(1), work)
        .await
        .unwrap()
        .unwrap()
        .is_err());
    server.reply.release.notify_one();
    let replacement_source = identity("account-a", 42);
    assert_eq!(
        state_name(&pi(h.providers.load(&replacement_source).await.unwrap())),
        "unverified"
    );
}

#[tokio::test]
async fn replacing_a_held_connection_retires_old_work_without_damaging_the_fresh_revision() {
    let server = Provider::new().await;
    server.reply.mode.store(4, Ordering::SeqCst);
    let h = Harness::new(&server.base);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let old = h
        .providers
        .pi_lease(&owner, expected(&status))
        .await
        .unwrap();
    let work = crate::rt::spawn(async move { old.models(true).await });
    crate::rt::timeout(Duration::from_secs(1), server.reply.entered.notified())
        .await
        .unwrap();
    let replacement = h
        .providers
        .configure_pi(
            &owner,
            expected(&status),
            setup(
                None,
                None,
                PiCredentialChange::Replace {
                    value: "fixture-replacement-key".into(),
                },
            ),
        )
        .await
        .unwrap();
    assert!(crate::rt::timeout(Duration::from_secs(1), work)
        .await
        .unwrap()
        .unwrap()
        .is_err());
    server.reply.mode.store(0, Ordering::SeqCst);
    server.reply.release.notify_one();
    h.providers
        .pi_models(&owner, expected(&replacement), true)
        .await
        .unwrap();
    assert_eq!(
        state_name(&pi(h.providers.load(&owner).await.unwrap())),
        "connected"
    );
    assert_eq!(
        server.reply.authorizations.lock().unwrap().last(),
        Some(&Some("Bearer fixture-replacement-key".into()))
    );
}

#[tokio::test]
async fn a_never_finishing_chunked_body_has_a_bounded_catalog_deadline() {
    let server = Provider::new().await;
    server.reply.mode.store(7, Ordering::SeqCst);
    let mut h = Harness::new(&server.base);
    Arc::get_mut(&mut h.providers.0).unwrap().catalog_timeout = Duration::from_millis(500);
    let owner = identity("account-a", 42);
    let status = h.configure(&owner).await;
    let providers = h.providers.clone();
    let captured_owner = owner.clone();
    let captured_expected = expected(&status);
    let operation = crate::rt::spawn(async move {
        providers
            .pi_models(&captured_owner, captured_expected, true)
            .await
    });
    crate::rt::timeout(Duration::from_secs(1), server.reply.entered.notified())
        .await
        .unwrap();
    let result = crate::rt::timeout(Duration::from_secs(2), operation)
        .await
        .unwrap()
        .unwrap();
    assert!(result.is_err());
    assert_eq!(server.reply.calls.load(Ordering::SeqCst), 1);
    assert_eq!(
        state_name(&pi(h.providers.load(&owner).await.unwrap())),
        "unverified"
    );
}
