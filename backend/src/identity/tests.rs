use super::*;
use serde_json::json;
use std::sync::atomic::AtomicUsize;

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
    let guest_path = tenants
        .root
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
