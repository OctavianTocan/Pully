use super::*;

const A: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const B: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const C: &str = "cccccccccccccccccccccccccccccccccccccccc";
const D: &str = "dddddddddddddddddddddddddddddddddddddddd";
const REVIEW: &str = "/api/personal/v1/github/account/repositories/reader-a/project/pulls/7/review";

#[tokio::test]
async fn personal_review_unconfigured_host_has_no_routes_and_configured_host_requires_its_session()
{
    let disabled = Server::new(router(PublicServerConfig::default()).await.unwrap()).await;
    let root = Root::new();
    let configured = Server::new(router(configuration(&root)).await.unwrap()).await;
    let target = json!({"repositoryId":701,"pullId":8001,"baseSha":A,"headSha":B,"private":true});
    for operation in ["overview", "diff", "files", "reviews"] {
        let mut path = Harness::path(&target, operation);
        if operation == "files" {
            path.push_str("&path=file.rs");
        }
        assert_eq!(
            disabled.get(&path, None).await.status(),
            StatusCode::NOT_FOUND
        );
        assert_eq!(
            configured.get(&path, None).await.status(),
            StatusCode::UNAUTHORIZED
        );
    }
}

struct ReviewGitHub {
    account: Arc<Github>,
    changed_head: AtomicBool,
    changed_pull: AtomicBool,
    incomplete: AtomicBool,
    hold: Mutex<Option<String>>,
    entered: Notify,
    release: Notify,
    omitted_patch: AtomicBool,
    patch_denied: AtomicBool,
    leak_file: AtomicBool,
}
impl ReviewGitHub {
    fn new(account: Arc<Github>) -> Self {
        Self {
            account,
            changed_head: AtomicBool::new(false),
            changed_pull: AtomicBool::new(false),
            incomplete: AtomicBool::new(false),
            hold: Mutex::new(None),
            entered: Notify::new(),
            release: Notify::new(),
            omitted_patch: AtomicBool::new(false),
            patch_denied: AtomicBool::new(false),
            leak_file: AtomicBool::new(false),
        }
    }
    fn head(&self) -> &'static str {
        if self.changed_head.load(Ordering::SeqCst) {
            D
        } else {
            B
        }
    }
    fn pull(&self, owner: &str) -> Value {
        let repo_id = repository_id(&self.account, owner);
        let id = if self.changed_pull.load(Ordering::SeqCst) {
            9001
        } else if owner == "reader-b" {
            8002
        } else {
            8001
        };
        json!({"id":id,"node_id":format!("PR_{id}"),"number":7,"title":format!("private review {owner}"),"body":"PR body","html_url":format!("https://github.com/{owner}/project/pull/7"),"user":{"login":owner,"avatar_url":"https://avatars.githubusercontent.com/u/42"},"state":"open","draft":false,"created_at":"2026-10-21T00:00:00Z","updated_at":"2026-10-22T00:00:00Z","changed_files":if self.incomplete.load(Ordering::SeqCst){2}else{1},"commits":1,"base":{"sha":A,"ref":"main","repo":{"id":repo_id}},"head":{"sha":self.head(),"ref":"feature","repo":{"id":repo_id}}})
    }
    async fn response(&self, name: &str, response: Response) -> Response {
        let hold = {
            let mut chosen = self.hold.lock().unwrap();
            if chosen.as_deref() == Some(name) {
                chosen.take();
                true
            } else {
                false
            }
        };
        if hold {
            self.entered.notify_one();
            self.release.notified().await;
        }
        response
    }
    fn permitted(&self, owner: &str, headers: &HeaderMap) -> bool {
        let (login, _) = current_subject(&self.account, headers);
        self.account
            .requests
            .lock()
            .unwrap()
            .push((format!("review:{owner}"), Some(auth(headers))));
        login == owner && !self.account.deny_private.load(Ordering::SeqCst)
    }
}

async fn repo(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _)): Path<(String, String)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(repository(
        &owner,
        repository_id(&state.account, &owner),
        state.account.private.load(Ordering::SeqCst),
    ))
    .into_response()
}
async fn inventory(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _)): Path<(String, String)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(json!([state.pull(&owner)])).into_response()
}
async fn detail(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, number)): Path<(String, String, u32)>,
) -> Response {
    if number != 7 || !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    state
        .response("pull", Json(state.pull(&owner)).into_response())
        .await
}
async fn compare(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, _)): Path<(String, String, String)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    if headers
        .get("accept")
        .is_some_and(|value| value == "application/vnd.github.diff")
    {
        return if state.patch_denied.load(Ordering::SeqCst) {
            StatusCode::FORBIDDEN
        } else {
            StatusCode::INTERNAL_SERVER_ERROR
        }
        .into_response();
    }
    Json(json!({"merge_base_commit":{"sha":C}})).into_response()
}
async fn files(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, _)): Path<(String, String, u32)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let mut file = json!({"filename":"file.rs","status":"modified","additions":1,"deletions":1,"sha":state.head(),"patch":"@@ -1 +1 @@\n-old\n+new"});
    if state.omitted_patch.load(Ordering::SeqCst) {
        file.as_object_mut().unwrap().remove("patch");
    }
    state
        .response("files", Json(json!([file])).into_response())
        .await
}
async fn commits(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, _)): Path<(String, String, u32)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(json!([{"sha":state.head(),"commit":{"message":"Review commit"}}])).into_response()
}
async fn content(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, path)): Path<(String, String, String)>,
    Query(query): Query<BTreeMap<String, String>>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    assert_eq!(path, "file.rs");
    assert!([C, state.head()].contains(&query["ref"].as_str()));
    let content = if state.leak_file.load(Ordering::SeqCst) {
        "fixture-access-a-upgrade"
    } else if query["ref"] == C {
        "old\n"
    } else {
        "new\n"
    };
    state
        .account
        .requests
        .lock()
        .unwrap()
        .push((format!("contents:{}", query["ref"]), Some(auth(&headers))));
    Json(json!({"type":"file","size":content.len(),"encoding":"base64","content":base64::engine::general_purpose::STANDARD.encode(content)})).into_response()
}
async fn line_comments(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, _)): Path<(String, String, u32)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(json!([{"id":81,"path":"file.rs","side":"RIGHT","line":1,"body":"line note","created_at":"2026-10-22T00:00:00Z","html_url":format!("https://github.com/{owner}/project/pull/7#discussion_r81"),"user":{"login":owner,"avatar_url":"https://avatars.githubusercontent.com/u/42"}}])).into_response()
}
async fn reviews(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Path((owner, _, _)): Path<(String, String, u32)>,
) -> Response {
    if !state.permitted(&owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    Json(json!([{"id":91,"node_id":"PRR_91","state":"COMMENTED","body":"review summary","submitted_at":"2026-10-22T00:00:00Z","html_url":format!("https://github.com/{owner}/project/pull/7#pullrequestreview-91"),"user":{"login":owner,"avatar_url":"https://avatars.githubusercontent.com/u/42"}}])).into_response()
}
async fn graphql(
    State(state): State<Arc<ReviewGitHub>>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Response {
    let owner = body["variables"]["owner"].as_str().unwrap();
    if !state.permitted(owner, &headers) {
        return StatusCode::NOT_FOUND.into_response();
    }
    let query = body["query"].as_str().unwrap();
    assert!(query.trim_start().starts_with("query "));
    if query.contains("ReviewThreads") {
        return Json(json!({"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"PRRT_1","isResolved":true,"comments":{"nodes":[{"fullDatabaseId":81}]}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}})).into_response();
    }
    assert!(query.contains("PullRequestOverview"));
    let pull = state.pull(owner);
    let response = Json(json!({"data":{"repository":{"viewerPermission":"READ","pullRequest":{
        "id":pull["node_id"],"number":7,"title":pull["title"],"body":"PR body","bodyHTML":"<p>PR body</p>","url":pull["html_url"],"createdAt":pull["created_at"],"updatedAt":pull["updated_at"],"author":{"__typename":"User","login":owner,"avatarUrl":"https://avatars.githubusercontent.com/u/42"},"state":"OPEN","merged":false,"isDraft":false,"headRefOid":state.head(),"headRefName":"feature","baseRefOid":A,"baseRefName":"main","headRepositoryOwner":{"login":owner},"mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","viewerDidAuthor":true,"headCommit":{"nodes":[{"commit":{"statusCheckRollup":{"state":"SUCCESS","contexts":{"nodes":[],"totalCount":0}}}}]},"commits":{"nodes":[],"totalCount":1},"timelineItems":{"nodes":[],"totalCount":0},"reviewRequests":{"nodes":[]},"latestOpinionatedReviews":{"nodes":[]}
    }}}})).into_response();
    state.response("overview", response).await
}

struct Harness {
    fixture: Fixture,
    upstream: Arc<ReviewGitHub>,
    tenant_root: PathBuf,
}

#[tokio::test]
async fn personal_review_live_data_ignores_hidden_overview_and_diff_cache_and_rechecks_access() {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    let mut poisoned = 0;
    for entry in std::fs::read_dir(&h.tenant_root).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            let store = crate::store::Store::new(path).unwrap();
            store
                .set(
                    "pr-overview:reader-a/project#7",
                    &json!({"value":{"overview":{"title":"operator cache must not win"}}}),
                )
                .unwrap();
            store
                .set(
                    "diff:github:reader-a/project#7",
                    &json!({"diff":{"title":"operator cache must not win","head":{"sha":D}}}),
                )
                .unwrap();
            poisoned += 1;
        }
    }
    assert!(poisoned > 0);
    for operation in ["overview", "diff"] {
        let response = h
            .fixture
            .server
            .get(&Harness::path(&open["target"], operation), Some(&session))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.json::<Value>().await.unwrap();
        assert!(!body.to_string().contains("operator cache must not win"));
        assert!(body.to_string().contains("private review reader-a"));
    }
    h.fixture.github.deny_private.store(true, Ordering::SeqCst);
    assert_eq!(
        h.fixture
            .server
            .get(&Harness::path(&open["target"], "diff"), Some(&session))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn personal_review_canonical_loader_refuses_a_partial_file_inventory() {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    h.upstream.incomplete.store(true, Ordering::SeqCst);
    let response = h
        .fixture
        .server
        .get(&Harness::path(&open["target"], "diff"), Some(&session))
        .await;
    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    assert!(response
        .json::<Value>()
        .await
        .unwrap()
        .get("value")
        .is_none());
}
impl Harness {
    async fn new() -> Self {
        let root = Root::new();
        let github = Arc::new(Github::default());
        github.private.store(true, Ordering::SeqCst);
        let upstream = Arc::new(ReviewGitHub::new(github.clone()));
        let oauth = Router::new()
            .route("/login/oauth/access_token", post(fake_token))
            .route("/user", get(fake_user))
            .with_state(github.clone());
        let reads = Router::new()
            .route("/repos/{owner}/{repo}", get(repo))
            .route("/repos/{owner}/{repo}/pulls", get(inventory))
            .route("/repos/{owner}/{repo}/pulls/{number}", get(detail))
            .route("/repos/{owner}/{repo}/pulls/{number}/files", get(files))
            .route("/repos/{owner}/{repo}/pulls/{number}/commits", get(commits))
            .route(
                "/repos/{owner}/{repo}/pulls/{number}/comments",
                get(line_comments),
            )
            .route("/repos/{owner}/{repo}/pulls/{number}/reviews", get(reviews))
            .route("/repos/{owner}/{repo}/compare/{range}", get(compare))
            .route("/repos/{owner}/{repo}/contents/{*path}", get(content))
            .route("/graphql", post(graphql))
            .with_state(upstream.clone());
        let server = Server::new(oauth.merge(reads)).await;
        let origin = url::Url::parse(&format!("{}/", server.origin)).unwrap();
        let configured = config::load(configuration(&root)).await.unwrap().unwrap();
        let tenant_root = configured.tenant_root.clone();
        let (auth_router, auth) = PersonalAuthHost::fixture(configured.auth, origin.clone())
            .await
            .unwrap()
            .into_parts();
        let personal =
            personal::fixture_router(auth.unwrap(), configured.tenant_root, origin.clone())
                .await
                .unwrap();
        let host = Server::new(compose(
            crate::public_api::fixture_router(origin, true),
            auth_router,
            Some(personal),
        ))
        .await;
        Self {
            fixture: Fixture {
                _root: root,
                github,
                _upstream: server,
                server: host,
            },
            upstream,
            tenant_root,
        }
    }
    async fn private(&self) -> (String, Value) {
        let (session, _) = self.fixture.sign_in("a").await;
        self.fixture.upgrade(&session, "a-upgrade").await
    }
    async fn open(&self, session: &str) -> Value {
        let selected = self
            .fixture
            .server
            .get(
                "/api/personal/v1/github/account/repositories/reader-a/project/pulls",
                Some(session),
            )
            .await;
        assert_eq!(selected.status(), StatusCode::OK);
        let repository_id = selected.json::<Value>().await.unwrap()["repository"]["id"]
            .as_u64()
            .unwrap();
        let response = self
            .fixture
            .server
            .get(
                &format!("{REVIEW}?repositoryId={repository_id}"),
                Some(session),
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        response.json().await.unwrap()
    }
    fn path(target: &Value, operation: &str) -> String {
        format!(
            "{REVIEW}/{operation}?repositoryId={}&pullId={}&baseSha={}&headSha={}&private={}",
            target["repositoryId"],
            target["pullId"],
            target["baseSha"].as_str().unwrap(),
            target["headSha"].as_str().unwrap(),
            target["private"]
        )
    }
}

#[tokio::test]
async fn personal_review_real_oauth_private_overview_diff_files_and_reviews_share_the_captured_credential(
) {
    let h = Harness::new().await;
    let (session, status) = h.private().await;
    let open = h.open(&session).await;
    assert_eq!(open["accountId"], status["accountId"]);
    assert_eq!(open["githubId"], 42);
    assert_eq!(open["authority"], "privateRepositories");
    assert_eq!(open["target"]["private"], true);
    for operation in ["overview", "diff", "files", "reviews"] {
        let mut path = Harness::path(&open["target"], operation);
        if operation == "files" {
            path.push_str("&path=file.rs");
        }
        let response = h.fixture.server.get(&path, Some(&session)).await;
        let status_code = response.status();
        assert_eq!(response.headers()["cache-control"], "no-store");
        let body = response.json::<Value>().await.unwrap();
        assert_eq!(status_code, StatusCode::OK, "{operation}: {body}");
        assert_eq!(body["accountId"], status["accountId"]);
        assert_eq!(body["target"], open["target"]);
        match operation {
            "overview" => {
                assert_eq!(body["value"]["overview"]["id"], "PR_8001");
                assert_eq!(body["value"]["overview"]["viewer"]["permission"], "READ");
                assert_eq!(body["value"]["overview"]["viewer"]["canPush"], false);
            }
            "diff" => {
                assert_eq!(body["value"]["head"]["sha"], B);
                assert_eq!(body["value"]["base"]["sha"], C);
                assert_eq!(body["value"]["files"].as_array().unwrap().len(), 1);
                assert_eq!(body["value"]["files"][0]["path"], "file.rs");
                assert!(!body["value"]["files"][0]["hunks"]
                    .as_array()
                    .unwrap()
                    .is_empty());
            }
            "files" => {
                assert_eq!(body["value"], json!({"old":"old\n","new":"new\n"}));
            }
            "reviews" => {
                assert_eq!(
                    body["value"]["threads"][0]["comments"][0]["body"],
                    "line note"
                );
                assert_eq!(body["value"]["threads"][0]["resolved"], true);
                assert_eq!(body["value"]["summaries"][0]["body"], "review summary");
            }
            _ => unreachable!(),
        }
        let text = body.to_string();
        for secret in [
            session.as_str(),
            "fixture-access-a-upgrade",
            "fixture-pully-secret",
        ] {
            assert!(!text.contains(secret));
        }
    }
    let calls = h.fixture.github.requests.lock().unwrap();
    assert!(calls
        .iter()
        .any(|(path, _)| path == &format!("contents:{C}")));
    assert!(calls
        .iter()
        .filter(|(path, _)| path.starts_with("review:") || path.starts_with("contents:"))
        .all(|(_, token)| token.as_deref() == Some("Bearer fixture-access-a-upgrade")));
}

#[tokio::test]
async fn personal_review_guest_public_grant_and_another_tenant_cannot_read_private_data() {
    let h = Harness::new().await;
    assert_eq!(
        h.fixture
            .server
            .get(&format!("{REVIEW}?repositoryId=701"), None)
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    let (public, _) = h.fixture.sign_in("a").await;
    assert_eq!(
        h.fixture
            .server
            .get(&format!("{REVIEW}?repositoryId=701"), Some(&public))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );
    assert!(!h
        .fixture
        .github
        .requests
        .lock()
        .unwrap()
        .iter()
        .any(|(path, _)| path.starts_with("review:")));
    let (a, _) = h.fixture.upgrade(&public, "a-upgrade").await;
    let (b, _) = h.fixture.sign_in("b").await;
    let (b, _) = h.fixture.upgrade(&b, "b-upgrade").await;
    let foreign = h
        .fixture
        .server
        .get(&format!("{REVIEW}?repositoryId=701"), Some(&b))
        .await;
    assert_eq!(foreign.status(), StatusCode::FORBIDDEN);
    assert!(!foreign
        .text()
        .await
        .unwrap()
        .contains("private review reader-a"));
    let own = REVIEW.replace("reader-a", "reader-b");
    let response = h
        .fixture
        .server
        .get(&format!("{own}?repositoryId=702"), Some(&b))
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.json::<Value>().await.unwrap()["githubId"], 43);
    assert_eq!(
        h.fixture
            .server
            .get(&format!("{REVIEW}?repositoryId=701"), Some(&a))
            .await
            .status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn personal_review_requires_every_selected_identity_revision_and_visibility_pin() {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    for (field, value) in [
        ("repositoryId", json!(999)),
        ("pullId", json!(999)),
        ("baseSha", json!(D)),
        ("headSha", json!(D)),
        ("private", json!(false)),
    ] {
        let mut target = open["target"].clone();
        target[field] = value;
        let response = h
            .fixture
            .server
            .get(&Harness::path(&target, "diff"), Some(&session))
            .await;
        assert_eq!(
            response.status(),
            if field == "repositoryId" {
                StatusCode::FORBIDDEN
            } else {
                StatusCode::CONFLICT
            },
            "{field}"
        );
        let body = response.json::<Value>().await.unwrap();
        assert_eq!(
            body["error"]["code"],
            if field == "repositoryId" {
                "forbidden"
            } else {
                "staleReview"
            }
        );
        assert!(body.get("value").is_none());
    }
    let mut uppercase = open["target"].clone();
    uppercase["headSha"] = json!(B.to_uppercase());
    uppercase["baseSha"] = json!(A.to_uppercase());
    let response = h
        .fixture
        .server
        .get(&Harness::path(&uppercase, "diff"), Some(&session))
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.json::<Value>().await.unwrap()["target"]["headSha"],
        B
    );
}

async fn held_read_then_change(operation: &str, change: &str) {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    *h.upstream.hold.lock().unwrap() = Some(
        if operation == "overview" {
            "overview"
        } else {
            "files"
        }
        .into(),
    );
    let request = h
        .fixture
        .server
        .client
        .get(format!(
            "{}{}",
            h.fixture.server.origin,
            Harness::path(&open["target"], operation)
        ))
        .bearer_auth(&session);
    let older = crate::rt::spawn(async move { request.send().await.unwrap() });
    crate::rt::timeout(Duration::from_secs(2), h.upstream.entered.notified())
        .await
        .unwrap();
    match change {
        "access" => h.fixture.github.deny_private.store(true, Ordering::SeqCst),
        "repository" => h.fixture.github.replacement.store(true, Ordering::SeqCst),
        "head" => h.upstream.changed_head.store(true, Ordering::SeqCst),
        "pull" => h.upstream.changed_pull.store(true, Ordering::SeqCst),
        "visibility" => h.fixture.github.private.store(false, Ordering::SeqCst),
        _ => unreachable!(),
    }
    let current = h
        .fixture
        .server
        .get(&format!("{REVIEW}?repositoryId=701"), Some(&session))
        .await;
    assert_eq!(
        current.status(),
        if ["access", "repository"].contains(&change) {
            StatusCode::FORBIDDEN
        } else {
            StatusCode::OK
        }
    );
    h.upstream.release.notify_one();
    let response = crate::rt::timeout(Duration::from_secs(2), older)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        response.status(),
        if ["access", "repository"].contains(&change) {
            StatusCode::FORBIDDEN
        } else {
            StatusCode::CONFLICT
        },
        "{operation}:{change}"
    );
    let body = response.json::<Value>().await.unwrap();
    assert!(body.get("value").is_none());
    assert!(!body.to_string().contains("private review reader-a"));
}

#[tokio::test]
async fn personal_review_later_access_denial_or_repository_replacement_retires_older_canonical_reads(
) {
    for operation in ["overview", "diff"] {
        for change in ["access", "repository"] {
            held_read_then_change(operation, change).await;
        }
    }
}

#[tokio::test]
async fn personal_review_later_head_pull_or_visibility_confirmation_retires_older_canonical_reads()
{
    for operation in ["overview", "diff"] {
        for change in ["head", "pull", "visibility"] {
            held_read_then_change(operation, change).await;
        }
    }
}

#[tokio::test]
async fn personal_review_credential_replacement_and_signout_stop_nested_reads_without_fallback() {
    for replacement in [false, true] {
        let h = Harness::new().await;
        let (session, _) = h.private().await;
        let open = h.open(&session).await;
        *h.upstream.hold.lock().unwrap() = Some("files".into());
        let request = h
            .fixture
            .server
            .client
            .get(format!(
                "{}{}",
                h.fixture.server.origin,
                Harness::path(&open["target"], "diff")
            ))
            .bearer_auth(&session);
        let held = crate::rt::spawn(async move { request.send().await.unwrap() });
        crate::rt::timeout(Duration::from_secs(2), h.upstream.entered.notified())
            .await
            .unwrap();
        if replacement {
            h.fixture.sign_in("a-new").await;
        } else {
            let response = h
                .fixture
                .server
                .post(wire::SIGN_OUT_PATH, json!({}), Some(&session))
                .await;
            assert_eq!(response.status(), StatusCode::OK);
        }
        let before = h
            .fixture
            .github
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(path, _)| path.starts_with("review:"))
            .count();
        h.upstream.release.notify_one();
        let response = crate::rt::timeout(Duration::from_secs(2), held)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert!(response
            .json::<Value>()
            .await
            .unwrap()
            .get("value")
            .is_none());
        let after = h
            .fixture
            .github
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|(path, _)| path.starts_with("review:"))
            .count();
        assert_eq!(
            before, after,
            "a retired source issued another nested request"
        );
    }
}

#[tokio::test]
async fn personal_review_canonical_patch_fallback_recovers_transport_failure_but_never_a_denial() {
    for denied in [false, true] {
        let h = Harness::new().await;
        let (session, _) = h.private().await;
        let open = h.open(&session).await;
        h.upstream.omitted_patch.store(true, Ordering::SeqCst);
        h.upstream.patch_denied.store(denied, Ordering::SeqCst);
        let response = h
            .fixture
            .server
            .get(&Harness::path(&open["target"], "diff"), Some(&session))
            .await;
        assert_eq!(
            response.status(),
            if denied {
                StatusCode::FORBIDDEN
            } else {
                StatusCode::OK
            }
        );
        let body = response.json::<Value>().await.unwrap();
        if denied {
            assert!(body.get("value").is_none());
        } else {
            assert!(!body["value"]["files"][0]["hunks"]
                .as_array()
                .unwrap()
                .is_empty());
            assert!(body["value"]["files"][0].get("truncated").is_none());
        }
    }
}

#[tokio::test]
async fn personal_review_decoded_blob_cannot_echo_its_github_credential() {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    h.upstream.leak_file.store(true, Ordering::SeqCst);
    let path = format!("{}&path=file.rs", Harness::path(&open["target"], "files"));
    let response = h.fixture.server.get(&path, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::BAD_GATEWAY);
    let body = response.text().await.unwrap();
    assert!(!body.contains("fixture-access-a-upgrade"));
    assert!(!body.contains(&session));
}

#[tokio::test]
async fn personal_review_paths_missing_pins_and_arbitrary_rpc_options_fail_before_github() {
    let h = Harness::new().await;
    let (session, _) = h.private().await;
    let open = h.open(&session).await;
    for path in [
        format!("{REVIEW}?repositoryId=0"),
        format!("{REVIEW}/diff?repositoryId=701"),
        format!("{REVIEW}?repositoryId=701&cached=true"),
        format!(
            "{}&path=../private",
            Harness::path(&open["target"], "files")
        ),
        format!("{}&path=/absolute", Harness::path(&open["target"], "files")),
        format!("{}&path=a//b", Harness::path(&open["target"], "files")),
        format!("{}&path=a%00b", Harness::path(&open["target"], "files")),
        format!(
            "{}&path={}",
            Harness::path(&open["target"], "files"),
            "x".repeat(4097)
        ),
    ] {
        let before = h.fixture.github.requests.lock().unwrap().len();
        let response = h.fixture.server.get(&path, Some(&session)).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{path}");
        assert_eq!(h.fixture.github.requests.lock().unwrap().len(), before);
    }
    assert_eq!(
        h.fixture
            .server
            .post(
                &format!("{REVIEW}/diff"),
                json!({"method":"pr-action"}),
                Some(&session)
            )
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        h.fixture
            .server
            .get("/api/rpc", Some(&session))
            .await
            .status(),
        StatusCode::NOT_FOUND
    );
}
