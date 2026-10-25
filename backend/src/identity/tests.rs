use super::*;
use serde_json::json;
use std::sync::atomic::AtomicUsize;

// Like PersonalServer's ScopedRecords factory: each call constructs a fresh Store,
// but records for the same immutable scope share their backing storage.
fn record_backed_tenants(limit: usize) -> (TenantStates, Arc<AtomicUsize>) {
    let records = Arc::new(Mutex::new(HashMap::<
        String,
        crate::store::test_support::CountedRecords,
    >::new()));
    let calls = Arc::new(AtomicUsize::new(0));
    let counted = calls.clone();
    let tenants = TenantStates::with_stores(
        Arc::new(move |key| {
            counted.fetch_add(1, Ordering::SeqCst);
            let records = records
                .lock()
                .unwrap()
                .entry(key.into())
                .or_default()
                .clone();
            Ok(Store::with_records(
                PathBuf::from("/pully-personal").join(format!("tenant:{key}")),
                Box::new(records),
            ))
        }),
        limit,
    )
    .unwrap();
    (tenants, calls)
}

#[tokio::test]
async fn workspace_factory_bindings_share_durable_metadata_but_not_private_caches() {
    let (tenants, calls) = record_backed_tenants(4);
    let origin = url::Url::parse("https://api.github.com/").unwrap();
    let first = GitHubCredential::fixture("account-a", 42, "first-own-token");
    let second = GitHubCredential::fixture("account-a", 42, "second-own-token");
    let a = tenants
        .workspace(first.clone(), origin.clone())
        .await
        .unwrap();
    let b = tenants
        .workspace(second.clone(), origin.clone())
        .await
        .unwrap();
    assert!(!Arc::ptr_eq(&a, &b));
    assert_ne!(a.host.storage.root(), b.host.storage.root());
    assert!(!Arc::ptr_eq(&a.diff_cache, &b.diff_cache));
    assert!(Arc::ptr_eq(
        &b,
        &tenants
            .workspace(second.clone(), origin.clone())
            .await
            .unwrap()
    ));
    a.host
        .storage
        .set("my-pulls:open", &json!("a-only"))
        .unwrap();
    b.host
        .storage
        .set("full-file:private", &json!("b-only"))
        .unwrap();
    assert!(b.host.storage.get("my-pulls:open").unwrap().is_none());
    assert!(a.host.storage.get("full-file:private").unwrap().is_none());

    let key = "diff:one";
    a.host
        .storage
        .set(key, &json!({"sizeBytes":10,"lastViewedAt":1}))
        .unwrap();
    let old = b.host.storage.diff_snapshot(None).unwrap().unwrap();
    assert!(b
        .host
        .storage
        .diff_snapshot(Some(old.revision))
        .unwrap()
        .is_none());
    a.host
        .storage
        .update(key, |entry| {
            let mut entry = entry.unwrap();
            entry["sizeBytes"] = json!(25);
            entry["lastViewedAt"] = json!(2);
            Ok(entry)
        })
        .unwrap();
    // The actual eviction guard must reject metadata selected before A's mutation,
    // even before B refreshes its cached snapshot.
    assert!(b
        .host
        .storage
        .diff_entry_if_unchanged(key, old.entries[key].revision)
        .unwrap()
        .is_none());
    let changed = b
        .host
        .storage
        .diff_snapshot(Some(old.revision))
        .unwrap()
        .unwrap();
    assert_ne!(changed.revision, old.revision);
    assert_eq!(changed.size_bytes, 25);
    assert_eq!(changed.entries[key].last_viewed_at, 2);
    a.host
        .storage
        .update(key, |entry| {
            let mut entry = entry.unwrap();
            entry["lastViewedAt"] = json!(3);
            Ok(entry)
        })
        .unwrap();
    assert!(b
        .host
        .storage
        .diff_entry_if_unchanged(key, changed.entries[key].revision)
        .unwrap()
        .is_none());
    let touched = b
        .host
        .storage
        .diff_snapshot(Some(changed.revision))
        .unwrap()
        .unwrap();
    assert_eq!(touched.entries[key].last_viewed_at, 3);
    assert_eq!(touched.size_bytes, 25);
    assert_eq!(calls.load(Ordering::SeqCst), 1);

    second.revoke();
    assert!(b
        .host
        .storage
        .diff_snapshot(Some(touched.revision))
        .is_err());
    assert!(b
        .host
        .storage
        .diff_entry_if_unchanged(key, touched.entries[key].revision)
        .is_err());
    let renewed = tenants
        .workspace(
            GitHubCredential::fixture("account-a", 42, "renewed-own-token"),
            origin.clone(),
        )
        .await
        .unwrap();
    assert!(renewed
        .host
        .storage
        .get("full-file:private")
        .unwrap()
        .is_none());
    assert_eq!(
        renewed.host.storage.get(key).unwrap().unwrap()["lastViewedAt"],
        3
    );
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let other = tenants
        .workspace(
            GitHubCredential::fixture("account-b", 43, "other-own-token"),
            origin,
        )
        .await
        .unwrap();
    assert!(other.host.storage.get(key).unwrap().is_none());
    assert!(other
        .host
        .storage
        .diff_snapshot(None)
        .unwrap()
        .unwrap()
        .entries
        .is_empty());
    assert_eq!(calls.load(Ordering::SeqCst), 2);
    assert!(a.identity.ensure_current().is_ok());
}

#[tokio::test]
async fn workspace_factory_coordinator_capacity_is_bounded_and_reclaimed() {
    let (tenants, calls) = record_backed_tenants(1);
    let origin = url::Url::parse("https://api.github.com/").unwrap();
    let first = GitHubCredential::fixture("account-a", 42, "first-own-token");
    let a = tenants
        .workspace(first.clone(), origin.clone())
        .await
        .unwrap();
    assert!(tenants
        .workspace(
            GitHubCredential::fixture("account-a", 42, "second-own-token"),
            origin.clone()
        )
        .await
        .is_err());
    first.revoke();
    let renewed_binding = GitHubCredential::fixture("account-a", 42, "renewed-own-token");
    let renewed = tenants
        .workspace(renewed_binding.clone(), origin.clone())
        .await
        .unwrap();
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    renewed_binding.revoke();
    assert!(tenants
        .workspace(
            GitHubCredential::fixture("account-b", 43, "other-own-token"),
            origin.clone()
        )
        .await
        .is_err());
    drop(a);
    drop(renewed);
    tenants
        .workspace(
            GitHubCredential::fixture("account-b", 43, "other-own-token"),
            origin,
        )
        .await
        .unwrap();
    assert_eq!(calls.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn trusted_workspace_keeps_saved_data_but_retires_binding_caches_and_never_uses_operator_ai()
{
    let root = std::env::temp_dir().join(format!("pully-workspace-owner-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.clone(), 8).unwrap();
    let origin = url::Url::parse("https://api.github.com/").unwrap();
    let credential = GitHubCredential::fixture("account-a", 42, "own-token");
    let first = tenants
        .workspace(credential.clone(), origin.clone())
        .await
        .unwrap();
    assert_eq!(first.identity.kind(), PrincipalKind::Account);
    assert!(!first.identity.is_legacy());
    let login = crate::agent_login::AgentLogin::Account("account-a".into());
    assert!(first.authorize_subscription(&login, "claude-code").is_ok());
    assert!(first.authorize_subscription(&login, "codex").is_ok());
    assert!(first.authorize_subscription(&login, "pi").is_err());
    assert!(first
        .authorize_subscription(&crate::agent_login::AgentLogin::Server, "codex")
        .is_err());
    assert!(first
        .authorize_subscription(
            &crate::agent_login::AgentLogin::Account("account-b".into()),
            "codex"
        )
        .is_err());
    assert!(tenants
        .account(credential.clone())
        .await
        .unwrap()
        .authorize_subscription(&login, "codex")
        .is_err());
    first
        .host
        .storage
        .set(
            "tour:github:reader-a/project#7",
            &json!({"title":"own saved tour"}),
        )
        .unwrap();
    first
        .host
        .storage
        .set("my-pulls:open", &json!({"private":"old cache"}))
        .unwrap();
    first
        .host
        .storage
        .set("full-file:old", &json!("old file cache"))
        .unwrap();
    first
        .analytics
        .record("rpc.call", json!({"method":"account-me","status":"ok"}))
        .await
        .unwrap();
    credential.revoke();
    assert!(first
        .host
        .storage
        .get("tour:github:reader-a/project#7")
        .is_err());
    let renewed = TenantStates::new(root.clone(), 8)
        .unwrap()
        .workspace(
            GitHubCredential::fixture("account-a", 42, "renewed-own-token"),
            origin.clone(),
        )
        .await
        .unwrap();
    assert_eq!(
        renewed
            .host
            .storage
            .get("tour:github:reader-a/project#7")
            .unwrap()
            .unwrap()["title"],
        "own saved tour"
    );
    assert!(renewed.host.storage.get("my-pulls:open").unwrap().is_none());
    assert!(renewed.host.storage.get("full-file:old").unwrap().is_none());
    assert_eq!(
        crate::ai::handle(&renewed, "ai-task-list-all", json!({}))
            .await
            .unwrap()
            .unwrap(),
        json!([])
    );
    let other = tenants
        .workspace(
            GitHubCredential::fixture("account-b", 43, "other-token"),
            origin,
        )
        .await
        .unwrap();
    assert!(other
        .host
        .storage
        .get("tour:github:reader-a/project#7")
        .unwrap()
        .is_none());
    assert_ne!(other.analytics.root(), renewed.analytics.root());
    assert_ne!(
        crate::agent_login::AgentLogin::Account("account-b".into()).volume(),
        login.volume()
    );
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn guest_personal_and_revoked_credentials_never_call_operator_lookup() {
    let calls = AtomicUsize::new(0);
    let lookup = || async {
        calls.fetch_add(1, Ordering::SeqCst);
        Some("operator-only".to_owned())
    };
    assert!(RequestIdentity::Guest
        .github_token(lookup)
        .await
        .unwrap()
        .is_none());
    let credential = GitHubCredential::fixture("account-a", 42, "personal-only");
    let personal = RequestIdentity::Account(credential.clone());
    assert_eq!(
        personal.github_token(lookup).await.unwrap().as_deref(),
        Some("personal-only")
    );
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    credential.revoke();
    assert!(personal.github_token(lookup).await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    assert_eq!(
        RequestIdentity::LegacyPrivate
            .github_token(lookup)
            .await
            .unwrap()
            .as_deref(),
        Some("operator-only")
    );
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn tenant_caches_usage_and_sources_are_separate_and_reuse_the_same_binding() {
    let root = std::env::temp_dir().join(format!("pully-tenants-{}", uuid::Uuid::new_v4()));
    let legacy = AppState::new(root.clone(), root.join("legacy")).unwrap();
    legacy
        .host
        .storage
        .set(
            "my-pulls:open",
            &json!({"pulls":["private-owner-pull"],"fetchedAt":1}),
        )
        .unwrap();
    let tenants = TenantStates::new(root.join("tenants"), 8).unwrap();
    let first_binding = GitHubCredential::fixture("account-a", 42, "fixture-token-a");
    let second_binding = GitHubCredential::fixture("account-b", 43, "fixture-token-b");
    let a = tenants.account(first_binding.clone()).await.unwrap();
    let b = tenants.account(second_binding).await.unwrap();
    let guest = tenants.guest().await.unwrap();
    assert!(Arc::ptr_eq(
        &a,
        &tenants.account(first_binding.clone()).await.unwrap()
    ));
    assert!(Arc::ptr_eq(&guest, &tenants.guest().await.unwrap()));
    a.host
        .storage
        .set(
            "my-pulls:open",
            &json!({"pulls":["account-a-only"],"fetchedAt":2}),
        )
        .unwrap();
    a.host
        .storage
        .set(
            "diff:github:owner/repo#1",
            &json!({"private":"account-a-only"}),
        )
        .unwrap();
    for other in [&b, &guest] {
        assert!(other.host.storage.get("my-pulls:open").unwrap().is_none());
        assert!(other
            .host
            .storage
            .get("diff:github:owner/repo#1")
            .unwrap()
            .is_none());
        assert!(other.host.checkout().is_err());
        assert!(other.ensure_account().await.is_err());
        assert_ne!(other.analytics.root(), a.analytics.root());
        let cached = crate::source::handle(
            other,
            "my-pulls-cached",
            json!({"state":"open","revalidate":false}),
        )
        .await
        .unwrap()
        .unwrap();
        assert!(!cached.to_string().contains("private-owner-pull"));
        assert!(!cached.to_string().contains("account-a-only"));
    }
    assert_eq!(first_binding.github_id(), 42);
    assert!(!a
        .host
        .storage
        .root()
        .to_string_lossy()
        .contains("fixture-token"));
    let renewed = tenants
        .account(GitHubCredential::fixture(
            "account-a",
            42,
            "fixture-renewed-token",
        ))
        .await
        .unwrap();
    assert_ne!(a.host.storage.root(), renewed.host.storage.root());
    assert!(renewed.host.storage.get("my-pulls:open").unwrap().is_none());
    first_binding.revoke();
    assert!(crate::source::handle(
        &a,
        "my-pulls-cached",
        json!({"state":"open","revalidate":false})
    )
    .await
    .unwrap()
    .is_err());
    assert!(tenants.account(first_binding).await.is_err());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn tenant_capacity_is_bounded() {
    let root = std::env::temp_dir().join(format!("pully-tenant-limit-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.clone(), 1).unwrap();
    let guest = tenants.guest().await.unwrap();
    assert!(tenants
        .account(GitHubCredential::fixture("account-a", 42, "fixture-token"))
        .await
        .is_err());
    assert!(Arc::ptr_eq(&guest, &tenants.guest().await.unwrap()));
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn revoked_bindings_release_capacity_without_adopting_their_private_store() {
    let root = std::env::temp_dir().join(format!("pully-tenant-churn-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.clone(), 1).unwrap();
    let old = GitHubCredential::fixture("account-a", 42, "fixture-old-token");
    let first = tenants.account(old.clone()).await.unwrap();
    first
        .host
        .storage
        .set("private-result", &json!({"owner":"old-binding"}))
        .unwrap();
    let fresh = GitHubCredential::fixture("account-a", 42, "fixture-fresh-token");
    assert!(tenants.account(fresh.clone()).await.is_err());

    old.revoke();
    let second = tenants.account(fresh.clone()).await.unwrap();
    assert!(first.identity.ensure_current().is_err());
    assert_ne!(first.host.storage.root(), second.host.storage.root());
    assert!(second.host.storage.get("private-result").unwrap().is_none());
    assert_eq!(
        first.host.storage.get("private-result").unwrap(),
        Some(json!({"owner":"old-binding"}))
    );
    assert!(Arc::ptr_eq(&second, &tenants.account(fresh).await.unwrap()));
    assert!(tenants.account(old).await.is_err());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn guests_and_personal_accounts_cannot_use_operator_ai_or_mutate_github() {
    let root =
        std::env::temp_dir().join(format!("pully-tenant-authority-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.clone(), 2).unwrap();
    let guest = tenants.guest().await.unwrap();
    let personal = tenants
        .account(GitHubCredential::fixture("account-a", 42, "fixture-token"))
        .await
        .unwrap();
    for state in [&guest, &personal] {
        for engine in ["pi", "claude-code", "codex"] {
            for name in ["ai-task-start", "ai-job-start", "llm-run", "ai-models"] {
                let failure = crate::ai::handle(
                    state,
                    name,
                    json!({"kind":"tour","key":"owner/repo#1","engine":engine}),
                )
                .await
                .unwrap()
                .unwrap_err();
                assert!(failure.to_string().contains("personal AI provider"));
            }
        }
        assert_eq!(
            crate::ai::handle(state, "ai-task-list-all", json!({}))
                .await
                .unwrap()
                .unwrap(),
            json!([])
        );
    }
    // These would otherwise reach GitHub, the operator's identity or a mutation.
    for (method, path) in [
        ("POST", "/repos/owner/repo/issues"),
        ("GET", "/user"),
        ("POST", "/graphql"),
    ] {
        assert!(
            crate::source::github_proxy(&guest, &json!({"method":method,"path":path}))
                .await
                .is_err()
        );
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn a_symlinked_tenant_cannot_adopt_legacy_or_another_accounts_store() {
    let root = std::env::temp_dir().join(format!("pully-tenant-link-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.join("tenants"), 2).unwrap();
    let private = Store::new(root.join("private")).unwrap();
    private.set("my-pulls:open", &json!("private")).unwrap();
    let guest_path = std::fs::canonicalize(root.join("tenants"))
        .unwrap()
        .join(RequestIdentity::Guest.namespace().unwrap());
    std::os::unix::fs::symlink(private.root(), guest_path).unwrap();
    assert!(tenants.guest().await.is_err());
    assert_eq!(
        private.get("my-pulls:open").unwrap(),
        Some(json!("private"))
    );
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn account_identity_never_uses_email_or_a_mutable_login_as_ownership() {
    let account = Account {
        id: "fixed-account-id".into(),
        email: "changes@example.invalid".into(),
    };
    let identity = VerifiedGitHubIdentity::verified_response(
        "fixture-token".into(),
        br#"{"id":42,"login":"new-name","email":null}"#,
    )
    .unwrap();
    assert_eq!(identity.login, "new-name");
    assert_eq!(identity.bind(&account).unwrap().github_id(), 42);
    for body in [
        br#"{"id":0,"login":"bad"}"#.as_slice(),
        br#"{"id":"42","login":"bad"}"#.as_slice(),
        br#"{"login":"missing-id"}"#.as_slice(),
    ] {
        let failure =
            VerifiedGitHubIdentity::verified_response("private-fixture-token".into(), body)
                .err()
                .unwrap();
        assert!(!failure.to_string().contains("private-fixture-token"));
    }
}

#[tokio::test]
async fn revocation_observers_wake_after_the_canonical_authority_is_retired() {
    let credential = GitHubCredential::fixture("account-a", 42, "fixture-token");
    let identity = RequestIdentity::Account(credential.clone());
    let mut observed = credential.subscribe_revocation().unwrap();
    assert!(!*observed.borrow());
    credential.revoke();
    observed.changed().await.unwrap();
    assert!(*observed.borrow());
    assert!(identity.ensure_current().is_err());
    assert!(credential.subscribe_revocation().is_err());
}

#[test]
fn concurrent_subscribe_and_revoke_never_lose_the_retired_authority() {
    for _ in 0..64 {
        let credential = GitHubCredential::fixture("account-a", 42, "fixture-token");
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let worker = {
            let (credential, barrier) = (credential.clone(), barrier.clone());
            std::thread::spawn(move || {
                barrier.wait();
                credential.revoke();
            })
        };
        barrier.wait();
        let observed = credential.subscribe_revocation();
        worker.join().unwrap();
        if let Ok(observed) = observed {
            assert!(*observed.borrow());
        }
        assert!(RequestIdentity::Account(credential)
            .ensure_current()
            .is_err());
    }
}
