use super::*;

const RPC: &str = "/workspace/api/rpc";

// This upstream exercises the real workspace router without starting an AI provider.
// Repository publication barriers make revocation deterministic rather than timing based.
#[derive(Default)]
struct WorkspaceGithub {
    unavailable_user: AtomicBool,
    deny: AtomicBool,
    deny_name: Mutex<Option<String>>,
    replacement: AtomicBool,
    repository_status: AtomicU64,
    calls: AtomicU64,
    active: AtomicU64,
    peak: AtomicU64,
    hold_at: AtomicU64,
    entered: Notify,
    release: Notify,
}
async fn workspace_user(State(state): State<Arc<WorkspaceGithub>>) -> Response {
    if state.unavailable_user.load(Ordering::SeqCst) {
        StatusCode::SERVICE_UNAVAILABLE.into_response()
    } else {
        Json(json!({"id":42,"login":"reader-a"})).into_response()
    }
}
async fn workspace_repository(
    State(state): State<Arc<WorkspaceGithub>>,
    headers: HeaderMap,
    Path((owner, name)): Path<(String, String)>,
) -> Response {
    assert_eq!(auth(&headers), "Bearer fixture-access-a-upgrade");
    let call = state.calls.fetch_add(1, Ordering::SeqCst) + 1;
    let active = state.active.fetch_add(1, Ordering::SeqCst) + 1;
    state.peak.fetch_max(active, Ordering::SeqCst);
    if call == state.hold_at.load(Ordering::SeqCst) {
        state.entered.notify_one();
        state.release.notified().await;
    }
    crate::rt::sleep(Duration::from_millis(5)).await;
    state.active.fetch_sub(1, Ordering::SeqCst);
    match state.repository_status.load(Ordering::SeqCst) {
        429 => return (StatusCode::TOO_MANY_REQUESTS, [("retry-after", "17")]).into_response(),
        503 => return StatusCode::SERVICE_UNAVAILABLE.into_response(),
        _ => {}
    }
    if state.deny.load(Ordering::SeqCst)
        || state.deny_name.lock().unwrap().as_deref() == Some(name.as_str())
    {
        return StatusCode::NOT_FOUND.into_response();
    }
    let id = if state.replacement.load(Ordering::SeqCst) {
        9999
    } else {
        701 + name
            .strip_prefix("project")
            .unwrap_or("")
            .parse::<u64>()
            .unwrap_or(0)
    };
    let mut repo = repository(&owner, id, true);
    repo["name"] = json!(name);
    repo["full_name"] = json!(format!("{owner}/{name}"));
    Json(repo).into_response()
}

impl Fixture {
    async fn workspace_harness(
        &mut self,
        session: &str,
        upstream: &Server,
    ) -> Arc<crate::AppState> {
        let origin = url::Url::parse(&format!("{}/", upstream.origin)).unwrap();
        let configured = config::load(configuration(&self._root))
            .await
            .unwrap()
            .unwrap();
        let (auth_router, auth) = PersonalAuthHost::fixture(configured.auth, origin.clone())
            .await
            .unwrap()
            .into_parts();
        let auth = auth.unwrap();
        let tenants = crate::identity::TenantStates::new(configured.tenant_root, 256).unwrap();
        let crate::identity::RequestIdentity::Account(credential) = auth
            .authenticate(session)
            .await
            .unwrap()
            .identity()
            .unwrap()
        else {
            panic!("personal account required")
        };
        let state = tenants.workspace(credential, origin.clone()).await.unwrap();
        let personal =
            personal::fixture_router_with_tenants(auth, tenants, origin.clone()).unwrap();
        self.server = Server::new(compose(
            crate::public_api::fixture_router(origin, true),
            auth_router,
            Some(personal),
        ))
        .await;
        state
    }
}

async fn workspace_upstream(state: Arc<WorkspaceGithub>) -> Server {
    Server::new(
        Router::new()
            .route("/user", get(workspace_user))
            .route("/repos/{owner}/{name}", get(workspace_repository))
            .with_state(state),
    )
    .await
}

#[tokio::test]
async fn workspace_failed_and_stopped_inbox_history_retains_repository_authority() {
    let mut fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let github = Arc::new(WorkspaceGithub::default());
    let upstream = workspace_upstream(github.clone()).await;
    let state = fixture.workspace_harness(&session, &upstream).await;
    let request = json!({"name":"inbox-chat","args":{}});
    for (stopped, async_writer) in [(false, false), (false, true), (true, false), (true, true)] {
        state
            .host
            .storage
            .set_async("inbox-chat", &json!({"turns":[]}))
            .await
            .unwrap();
        crate::ai::fixture_failed_workspace_inbox(
            &state,
            &json!([{"owner":"reader-a","repo":"project1","number":8}]),
            stopped,
            async_writer,
        )
        .await
        .unwrap();
        let response = fixture
            .server
            .post(RPC, request.clone(), Some(&session))
            .await;
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "stopped={stopped}, async={async_writer}"
        );
        let value = response.json::<Value>().await.unwrap();
        assert_eq!(
            value["result"]["turns"][0]["outcome"],
            if stopped { "stopped" } else { "failed" }
        );
        assert!(value.to_string().contains("private failed activity"));
        assert!(!value.to_string().contains("_workspaceRepositories"));
        *github.deny_name.lock().unwrap() = Some("project1".into());
        let denied = fixture
            .server
            .post(RPC, request.clone(), Some(&session))
            .await;
        assert_eq!(denied.status(), StatusCode::FORBIDDEN);
        assert!(
            !denied
                .text()
                .await
                .unwrap()
                .contains("private failed activity")
        );
        *github.deny_name.lock().unwrap() = None;
    }
    state
        .host
        .storage
        .set_async("inbox-chat", &json!({"turns":[]}))
        .await
        .unwrap();
    crate::ai::fixture_failed_workspace_inbox(&state, &json!([]), false, true)
        .await
        .unwrap();
    assert_eq!(
        fixture
            .server
            .post(RPC, request.clone(), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
    let mut saved = state
        .host
        .storage
        .get_async("inbox-chat")
        .await
        .unwrap()
        .unwrap();
    saved["turns"][0]
        .as_object_mut()
        .unwrap()
        .remove("_workspaceRepositories");
    state
        .host
        .storage
        .set_async("inbox-chat", &saved)
        .await
        .unwrap();
    assert_eq!(
        fixture
            .server
            .post(RPC, request, Some(&session))
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn workspace_completed_inbox_reauthorizes_all_context_before_wait_or_list() {
    let mut fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let github = Arc::new(WorkspaceGithub::default());
    let upstream = workspace_upstream(github.clone()).await;
    let state = fixture.workspace_harness(&session, &upstream).await;
    let pulls = json!([
        {"owner":"reader-a","repo":"project","number":7},
        {"owner":"reader-a","repo":"project1","number":8}
    ]);
    let id = crate::ai::fixture_completed_workspace_inbox(&state, &pulls)
        .await
        .unwrap();
    assert!(
        state
            .host
            .storage
            .get_async("diff:inbox")
            .await
            .unwrap()
            .is_none()
    );
    let methods = [
        ("ai-task-wait", json!({"id":id,"after":0})),
        ("ai-task-list-all", json!({})),
        ("ai-task-list", json!({"key":"inbox"})),
    ];
    for (name, args) in &methods {
        let response = fixture
            .server
            .post(RPC, json!({"name":name,"args":args}), Some(&session))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.text().await.unwrap();
        assert!(body.contains("private inbox answer"));
        assert!(!body.contains("_workspaceRepositories"));
    }
    // Only one repository was cited in the synthetic answer. Both selected repositories
    // remain required; the unmentioned repository must not disappear from provenance.
    *github.deny_name.lock().unwrap() = Some("project1".into());
    for (name, args) in &methods {
        let response = fixture
            .server
            .post(RPC, json!({"name":name,"args":args}), Some(&session))
            .await;
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        let body = response.text().await.unwrap();
        assert!(!body.contains("private inbox answer"));
        assert!(!body.contains("private inbox activity"));
    }
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
    *github.deny_name.lock().unwrap() = None;
    github.replacement.store(true, Ordering::SeqCst);
    // Even opening the replacement and updating the account's name mapping must
    // not relabel a task assembled under the previous immutable IDs.
    for repo in ["project", "project1"] {
        state
            .host
            .storage
            .set_async(
                &format!("workspace-repository:reader-a/{repo}"),
                &json!(9999),
            )
            .await
            .unwrap();
    }
    let response = fixture
        .server
        .post(
            RPC,
            json!({"name":"ai-task-wait","args":{"id":id,"after":0}}),
            Some(&session),
        )
        .await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert!(
        !response
            .text()
            .await
            .unwrap()
            .contains("private inbox answer")
    );
}

#[tokio::test]
async fn workspace_retryable_repository_checks_keep_status_retry_after_and_session() {
    let mut fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let github = Arc::new(WorkspaceGithub::default());
    let upstream = workspace_upstream(github.clone()).await;
    let state = fixture.workspace_harness(&session, &upstream).await;
    state.host.storage.set_async("my-pulls:open", &json!({"pulls":[{"owner":"reader-a","repo":"project","number":7,"title":"private cached pull"}],"fetchedAt":crate::now_ms()})).await.unwrap();
    let body = json!({"name":"my-pulls-cached","args":{"state":"open","revalidate":false}});
    for status in [429, 503] {
        github.repository_status.store(status, Ordering::SeqCst);
        let response = fixture.server.post(RPC, body.clone(), Some(&session)).await;
        assert_eq!(response.status().as_u16(), status as u16);
        if status == 429 {
            assert_eq!(response.headers()["retry-after"], "17");
        }
        let value = response.json::<Value>().await.unwrap();
        assert!(value["error"].is_string());
        assert!(!value.to_string().contains("private cached pull"));
    }
    github.repository_status.store(0, Ordering::SeqCst);
    assert_eq!(
        fixture
            .server
            .post(RPC, body, Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn workspace_twenty_repository_inbox_deduplicates_and_bounds_both_publication_phases() {
    let mut fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let github = Arc::new(WorkspaceGithub::default());
    let upstream = workspace_upstream(github.clone()).await;
    let state = fixture.workspace_harness(&session, &upstream).await;
    let mut pulls: Vec<Value> = (0..20).map(|i| json!({"owner":"reader-a","repo":format!("project{i}"),"number":7,"title":"private cached pull"})).collect();
    pulls.push(json!({"owner":"READER-A","repo":"PROJECT0","number":8,"title":"same repository"}));
    state
        .host
        .storage
        .set_async(
            "my-pulls:open",
            &json!({"pulls":pulls,"fetchedAt":crate::now_ms()}),
        )
        .await
        .unwrap();
    let body = json!({"name":"my-pulls-cached","args":{"state":"open","revalidate":false}});
    let response = fixture.server.post(RPC, body.clone(), Some(&session)).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert!(
        response
            .text()
            .await
            .unwrap()
            .contains("private cached pull")
    );
    assert_eq!(
        github.calls.load(Ordering::SeqCst),
        40,
        "one distinct-repository check per phase"
    );
    assert_eq!(
        github.peak.load(Ordering::SeqCst),
        3,
        "parallel checks must respect the existing three GitHub permits"
    );
    github.calls.store(0, Ordering::SeqCst);
    github.hold_at.store(21, Ordering::SeqCst);
    let client = fixture.server.client.clone();
    let url = format!("{}{RPC}", fixture.server.origin);
    let bearer = session.clone();
    let pending = crate::rt::spawn(async move {
        client
            .post(url)
            .bearer_auth(bearer)
            .json(&body)
            .send()
            .await
            .unwrap()
    });
    crate::rt::timeout(Duration::from_secs(3), github.entered.notified())
        .await
        .unwrap();
    github.deny.store(true, Ordering::SeqCst);
    github.release.notify_one();
    let response = pending.await.unwrap();
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert!(
        !response
            .text()
            .await
            .unwrap()
            .contains("private cached pull")
    );
}

#[tokio::test]
async fn workspace_temporary_verification_failure_returns_retryable_string_and_same_bearer_recovers()
 {
    let mut fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let github = Arc::new(WorkspaceGithub::default());
    github.unavailable_user.store(true, Ordering::SeqCst);
    let upstream = workspace_upstream(github.clone()).await;
    // Drop the old router/vault runtime so admission must verify the persisted
    // confirmed session, rather than reusing its cached credential binding.
    fixture.server.task.abort();
    let _ = (&mut fixture.server.task).await;
    let origin = url::Url::parse(&format!("{}/", upstream.origin)).unwrap();
    let configured = config::load(configuration(&fixture._root))
        .await
        .unwrap()
        .unwrap();
    let (auth_router, auth) = PersonalAuthHost::fixture(configured.auth, origin.clone())
        .await
        .unwrap()
        .into_parts();
    let personal = personal::fixture_router(auth.unwrap(), configured.tenant_root, origin.clone())
        .await
        .unwrap();
    fixture.server = Server::new(compose(
        crate::public_api::fixture_router(origin, true),
        auth_router,
        Some(personal),
    ))
    .await;
    for path in [RPC, "/workspace/api/events"] {
        let response = fixture
            .server
            .post(
                path,
                json!({"name":"account-me","args":{},"events":[]}),
                Some(&session),
            )
            .await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response.headers()["cache-control"], "no-store");
        assert!(response.json::<Value>().await.unwrap()["error"].is_string());
    }
    github.unavailable_user.store(false, Ordering::SeqCst);
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
}

impl Fixture {
    async fn restart_workspace(&mut self) {
        let origin = url::Url::parse(&format!("{}/", self._upstream.origin)).unwrap();
        let configured = config::load(configuration(&self._root))
            .await
            .unwrap()
            .unwrap();
        let (auth_router, auth) = PersonalAuthHost::fixture(configured.auth, origin.clone())
            .await
            .unwrap()
            .into_parts();
        let personal =
            personal::fixture_router(auth.unwrap(), configured.tenant_root, origin.clone())
                .await
                .unwrap();
        self.server = Server::new(compose(
            crate::public_api::fixture_router(origin, true),
            auth_router,
            Some(personal),
        ))
        .await;
    }
    fn workspace_store(&self, account: &str) -> crate::store::Store {
        let key = hex::encode(Sha256::digest(
            format!("workspace-account-v1:{account}").as_bytes(),
        ));
        crate::store::Store::new(self._root.0.join("isolated/personal-tenants").join(key)).unwrap()
    }
}

#[tokio::test]
async fn confirmed_private_github_session_opens_own_native_workspace_without_second_login() {
    let fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, owner) = fixture.upgrade(&public, "a-upgrade").await;
    let response = fixture
        .server
        .post(RPC, json!({"name":"account-me","args":{}}), Some(&session))
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["result"]["id"], owner["accountId"]);
    assert_eq!(body["result"]["email"], "reader-a");
    assert_eq!(body["result"]["githubLogin"], "reader-a");
    assert_eq!(body["result"]["provider"], "github");
    for secret in [&session, &public, "fixture-access-a-upgrade"] {
        assert!(!body.to_string().contains(secret));
    }
}

#[tokio::test]
async fn workspace_requires_confirmed_private_vault_authority_even_behind_access() {
    let fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    for session in [None, Some("wrong-session"), Some(public.as_str())] {
        let response = fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), session)
            .await;
        assert!(matches!(
            response.status(),
            StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN
        ));
        assert!(response.json::<Value>().await.unwrap()["error"].is_string());
    }
    let response = fixture
        .server
        .client
        .post(format!("{}{RPC}", fixture.server.origin))
        .header("cf-access-client-id", "fixture-access-id")
        .header("cf-access-client-secret", "fixture-access-secret")
        .json(&json!({"name":"account-me","args":{}}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let response = fixture
        .server
        .client
        .post(format!("{}{RPC}", fixture.server.origin))
        .bearer_auth(&session)
        .header("cf-access-client-id", "fixture-access-id")
        .header("cf-access-client-secret", "fixture-access-secret")
        .json(&json!({"name":"account-me","args":{}}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        fixture
            .server
            .post(wire::SIGN_OUT_PATH, json!({}), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(&session))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn workspace_keeps_settings_under_immutable_owner_and_relogin_keeps_them() {
    let mut fixture = Fixture::new().await;
    let (a_public, _) = fixture.sign_in("a").await;
    let (a, a_owner) = fixture.upgrade(&a_public, "a-upgrade").await;
    let (b_public, _) = fixture.sign_in("b").await;
    let (b, _) = fixture.upgrade(&b_public, "b-upgrade").await;
    let response = fixture.server.post(RPC, json!({"name":"account-settings-merge","args":{"accountId":"attacker","values":{"theme":{"value":"dark","at":1}}}}), Some(&a)).await;
    assert_eq!(response.status(), StatusCode::OK);
    let own = fixture
        .server
        .post(RPC, json!({"name":"account-settings","args":{}}), Some(&a))
        .await
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(own["result"]["values"]["theme"]["value"], "dark");
    let other = fixture
        .server
        .post(RPC, json!({"name":"account-settings","args":{}}), Some(&b))
        .await
        .json::<Value>()
        .await
        .unwrap();
    assert!(other["result"]["values"].as_object().unwrap().is_empty());
    fixture.restart_workspace().await;
    let restarted = fixture
        .server
        .post(RPC, json!({"name":"account-settings","args":{}}), Some(&a))
        .await
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(restarted["result"]["values"]["theme"]["value"], "dark");
    assert_eq!(
        fixture
            .server
            .post(wire::SIGN_OUT_PATH, json!({}), Some(&a))
            .await
            .status(),
        StatusCode::OK
    );
    let (renewed, _) = fixture.authorize("a-new", None, "publicRead").await;
    let (renewed, owner) = fixture.upgrade(&renewed, "a-upgrade").await;
    assert_eq!(owner["accountId"], a_owner["accountId"]);
    let saved = fixture
        .server
        .post(
            RPC,
            json!({"name":"account-settings","args":{}}),
            Some(&renewed),
        )
        .await
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(saved["result"]["values"]["theme"]["value"], "dark");
}

#[tokio::test]
async fn workspace_revalidates_cached_private_content_and_keeps_saved_tours_across_restart() {
    let mut fixture = Fixture::new().await;
    fixture.github.private.store(true, Ordering::SeqCst);
    let (public, _) = fixture.sign_in("a").await;
    let (session, owner) = fixture.upgrade(&public, "a-upgrade").await;
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
    let store = fixture.workspace_store(owner["accountId"].as_str().unwrap());
    let key = "github:reader-a/project#7";
    store.set(&format!("diff:{key}"), &json!({"key":key,"headSha":"head","lastViewedAt":1,"sizeBytes":1,"diff":{"ref":{"kind":"github-pr","owner":"reader-a","repo":"project","number":"7"},"title":"own private saved title","head":{"sha":"head"},"files":[]}})).unwrap();
    store
        .set(
            &format!("tour:{key}"),
            &json!({"headSha":"head","result":{"title":"saved own tour","stops":[]}}),
        )
        .unwrap();
    let first = fixture
        .server
        .post(
            RPC,
            json!({"name":"recent-diffs","args":{}}),
            Some(&session),
        )
        .await;
    assert_eq!(first.status(), StatusCode::OK);
    assert!(
        first
            .text()
            .await
            .unwrap()
            .contains("own private saved title")
    );
    fixture.restart_workspace().await;
    assert_eq!(
        store.get(&format!("tour:{key}")).unwrap().unwrap()["result"]["title"],
        "saved own tour"
    );
    let restarted = fixture
        .server
        .post(
            RPC,
            json!({"name":"recent-diffs","args":{}}),
            Some(&session),
        )
        .await;
    assert_eq!(restarted.status(), StatusCode::OK);
    assert!(
        restarted
            .text()
            .await
            .unwrap()
            .contains("own private saved title")
    );
    fixture.github.deny_private.store(true, Ordering::SeqCst);
    let denied = fixture
        .server
        .post(
            RPC,
            json!({"name":"recent-diffs","args":{}}),
            Some(&session),
        )
        .await;
    assert!(denied.status().is_client_error());
    assert!(
        !denied
            .text()
            .await
            .unwrap()
            .contains("own private saved title")
    );
    fixture.github.deny_private.store(false, Ordering::SeqCst);
    fixture.github.replacement.store(true, Ordering::SeqCst);
    let replaced = fixture
        .server
        .post(
            RPC,
            json!({"name":"review-state","args":{"key":key}}),
            Some(&session),
        )
        .await;
    assert!(replaced.status().is_client_error());
    assert!(
        !replaced
            .text()
            .await
            .unwrap()
            .contains("own private saved title")
    );
}

#[tokio::test]
async fn workspace_rejects_a_claim_until_the_existing_personal_delivery_confirmation() {
    let fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let owned = fixture.start_flow("a-upgrade", Some(&public)).await;
    let claim = fixture
        .server
        .post(wire::CLAIM_PATH, owned, None)
        .await
        .json::<Value>()
        .await
        .unwrap();
    let session = claim["session"].as_str().unwrap();
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(session))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        fixture
            .server
            .get(wire::SESSION_PATH, Some(session))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        fixture
            .server
            .post(RPC, json!({"name":"account-me","args":{}}), Some(session))
            .await
            .status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn workspace_is_a_bounded_phone_surface_and_never_an_arbitrary_rpc_or_provider_proxy() {
    let fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    for name in [
        "storage-get",
        "github-request",
        "llm-run",
        "ai-job-start",
        "account-sign-in",
        "account-google-sign-in",
        "agent-key-create",
    ] {
        assert_eq!(
            fixture
                .server
                .post(RPC, json!({"name":name,"args":{}}), Some(&session))
                .await
                .status(),
            StatusCode::NOT_FOUND,
            "{name}"
        );
    }
    for name in ["ai-models", "ai-task-start"] {
        let body = fixture.server.post(RPC, json!({"name":name,"args":{"engine":"pi","kind":"tour","key":"github:reader-a/project#7","locale":"en","model":{"engine":"pi"}}}), Some(&session)).await.json::<Value>().await.unwrap();
        assert!(
            body["error"]
                .as_str()
                .unwrap()
                .contains("own Claude Code or Codex"),
            "{body}"
        );
    }
    assert_eq!(
        fixture.server.get(RPC, Some(&session)).await.status(),
        StatusCode::METHOD_NOT_ALLOWED
    );
    let response = fixture
        .server
        .client
        .post(format!("{}{RPC}", fixture.server.origin))
        .bearer_auth(&session)
        .header("content-type", "application/json")
        .body("x".repeat(4 * 1024 * 1024 + 1))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn workspace_events_use_only_the_confirmed_owner_and_existing_sanitizer() {
    let fixture = Fixture::new().await;
    let (public, _) = fixture.sign_in("a").await;
    let events = "/workspace/api/events";
    let batch = json!({"events":[{"name":"settings.open","app":"android","props":{"method":"account-me","status":"ok","token":"secret-should-be-dropped","text":"private-review-text"}}]});
    assert_eq!(
        fixture
            .server
            .post(events, batch.clone(), None)
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        fixture
            .server
            .post(events, batch.clone(), Some(&public))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );
    let (session, owner) = fixture.upgrade(&public, "a-upgrade").await;
    assert_eq!(
        fixture
            .server
            .post(events, batch.clone(), Some(&session))
            .await
            .status(),
        StatusCode::NO_CONTENT
    );
    let store = fixture.workspace_store(owner["accountId"].as_str().unwrap());
    let stored = store
        .keys("analytics:")
        .unwrap()
        .into_iter()
        .map(|key| store.get(&key).unwrap().unwrap().to_string())
        .collect::<String>();
    assert!(stored.contains("settings.open"));
    assert!(!stored.contains("secret-should-be-dropped"));
    assert!(!stored.contains("private-review-text"));
    let (b_public, _) = fixture.sign_in("b").await;
    let (_, b_owner) = fixture.upgrade(&b_public, "b-upgrade").await;
    assert!(
        fixture
            .workspace_store(b_owner["accountId"].as_str().unwrap())
            .keys("analytics:")
            .unwrap()
            .is_empty()
    );
    let response = fixture
        .server
        .client
        .post(format!("{}{events}", fixture.server.origin))
        .bearer_auth(&session)
        .header("content-type", "application/json")
        .body("x".repeat(2 * 1024 * 1024 + 1))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        fixture
            .server
            .post(wire::SIGN_OUT_PATH, json!({}), Some(&session))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        fixture
            .server
            .post(events, batch, Some(&session))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn workspace_sync_wait_wakes_only_for_its_own_account() {
    let fixture = Fixture::new().await;
    let (public_a, _) = fixture.sign_in("a").await;
    let (a, a_owner) = fixture.upgrade(&public_a, "a-upgrade").await;
    let (public_b, _) = fixture.sign_in("b").await;
    let (b, _) = fixture.upgrade(&public_b, "b-upgrade").await;
    let server = &fixture.server;
    let wait = |session: String, epoch: Value, version: Value, ms: u64| async move {
        server
            .post(
                RPC,
                json!({"name":"sync-wait","args":{"epoch":epoch,"version":version,"waitMs":ms}}),
                Some(&session),
            )
            .await
            .json::<Value>()
            .await
            .unwrap()["result"]
            .clone()
    };
    let first_a = wait(a.clone(), json!(""), json!(0), 0).await;
    let first_b = wait(b.clone(), json!(""), json!(0), 0).await;
    assert_eq!(first_a["reset"], true);
    assert_eq!(first_b["reset"], true);
    let store = fixture.workspace_store(a_owner["accountId"].as_str().unwrap());
    let waiting_a = wait(a.clone(), first_a["epoch"].clone(), first_a["version"].clone(), 5_000);
    let waiting_b = wait(b.clone(), first_b["epoch"].clone(), first_b["version"].clone(), 400);
    let write = async {
        crate::rt::sleep(std::time::Duration::from_millis(100)).await;
        store
            .set("review:file-sha", &json!({"sha":"file-sha","reviewedAt":1}))
            .unwrap();
    };
    let (woken, quiet, ()) = tokio::join!(waiting_a, waiting_b, write);
    assert_eq!(woken["topics"], json!(["marks"]));
    assert_eq!(woken["reset"], false);
    assert_eq!(quiet["topics"], json!([]));
    assert_eq!(quiet["reset"], false);
}
