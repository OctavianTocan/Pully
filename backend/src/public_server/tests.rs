use super::*;
mod personal_review;
mod review_regressions;
use crate::personal_auth::{wire, PersonalAuthHost};
use axum::{
    extract::{Form, Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use tokio::sync::Notify;

const REPOSITORIES_PATH: &str = "/api/personal/v1/github/repositories";
const PULLS_PATH: &str = "/api/personal/v1/github/pulls";
const ACCOUNT_REPOSITORIES_PATH: &str = "/api/personal/v1/github/account/repositories";
const ACCOUNT_PULLS_PATH: &str = "/api/personal/v1/github/account/pulls";
const ACCOUNT_CAPABILITIES_PATH: &str = "/api/personal/v1/github/account/capabilities";

struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("pully-public-host-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
fn private(path: &std::path::Path, value: &Value) {
    std::fs::write(path, serde_json::to_vec(value).unwrap()).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).unwrap();
    }
}
fn configuration(root: &Root) -> PublicServerConfig {
    let oauth = root.0.join("oauth.json");
    let keys = root.0.join("vault.json");
    private(
        &oauth,
        &json!({"clientId":"fixture-pully-owned","clientSecret":"fixture-pully-secret","callback":format!("https://pully.example{}",wire::CALLBACK_PATH)}),
    );
    private(
        &keys,
        &json!({"activeVersion":1,"keys":{"1":URL_SAFE_NO_PAD.encode([29;32])}}),
    );
    PublicServerConfig {
        personal: Some(PersonalAuthFiles {
            data_root: root.0.join("isolated"),
            oauth_app_file: oauth,
            vault_keys_file: keys,
        }),
    }
}
struct Server {
    origin: String,
    client: reqwest::Client,
    task: tokio::task::JoinHandle<()>,
}
impl Server {
    async fn new(router: Router) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(5))
            .build()
            .unwrap();
        Self {
            origin,
            client,
            task,
        }
    }
    async fn get(&self, path: &str, session: Option<&str>) -> reqwest::Response {
        let mut request = self.client.get(format!("{}{path}", self.origin));
        if let Some(session) = session {
            request = request.bearer_auth(session);
        }
        request.send().await.unwrap()
    }
    async fn post(&self, path: &str, body: Value, session: Option<&str>) -> reqwest::Response {
        let mut request = self
            .client
            .post(format!("{}{path}", self.origin))
            .json(&body);
        if let Some(session) = session {
            request = request.bearer_auth(session);
        }
        request.send().await.unwrap()
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[derive(Default)]
struct Github {
    private: AtomicBool,
    replacement: AtomicBool,
    hold: AtomicBool,
    wrong_issue: AtomicBool,
    reduced_scope: AtomicBool,
    deny_private: AtomicBool,
    deny_a_only: AtomicBool,
    stale_search_draft: AtomicBool,
    omit_search_draft: AtomicBool,
    omit_issue_draft: AtomicBool,
    wrong_pull_base: AtomicBool,
    rename_during_search: AtomicBool,
    current_a_login: Mutex<Option<String>>,
    repo_calls: AtomicU64,
    hold_repo_at: AtomicU64,
    requests: Mutex<Vec<(String, Option<String>)>>,
    pkce: Mutex<BTreeMap<String, String>>,
    queries: Mutex<Vec<String>>,
    entered: Notify,
    release: Notify,
    repo_entered: Notify,
    repo_release: Notify,
}
fn auth(headers: &HeaderMap) -> String {
    headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_owned()
}
fn subject(headers: &HeaderMap) -> (&'static str, u64) {
    match auth(headers).as_str() {
        "Bearer fixture-access-a"
        | "Bearer fixture-access-a-upgrade"
        | "Bearer fixture-access-a-new" => ("reader-a", 42),
        "Bearer fixture-access-b" | "Bearer fixture-access-b-upgrade" => ("reader-b", 43),
        _ => panic!("the personal transport must use its own verified credential"),
    }
}
fn current_subject(state: &Github, headers: &HeaderMap) -> (String, u64) {
    let (login, id) = subject(headers);
    let login = if id == 42 {
        state
            .current_a_login
            .lock()
            .unwrap()
            .clone()
            .unwrap_or_else(|| login.into())
    } else {
        login.into()
    };
    (login, id)
}
fn repository_id(state: &Github, owner: &str) -> u64 {
    if state.replacement.load(Ordering::SeqCst) {
        999
    } else if owner == "reader-b" {
        702
    } else if owner == "reader-a" && state.current_a_login.lock().unwrap().is_some() {
        703 // The old login was reassigned to a different GitHub subject/repository.
    } else {
        701
    }
}
fn issue_id(state: &Github, owner: &str) -> u64 {
    match repository_id(state, owner) {
        702 => 3002,
        703 => 4001,
        _ => 3001,
    }
}
fn repository(owner: &str, id: u64, private: bool) -> Value {
    json!({"id":id,"owner":{"login":owner},"name":"project","full_name":format!("{owner}/project"),"private":private,"description":"public description","stargazers_count":11,"language":"Rust"})
}
fn pull(owner: &str, id: u64) -> Value {
    json!({"id":9001,"number":7,"state":"open","title":format!("current public title {owner}"),"user":{"login":owner},"draft":false,"created_at":"2026-10-21T00:00:00Z","updated_at":"2026-10-22T00:00:00Z","base":{"repo":{"id":id}}})
}
fn issue(owner: &str, id: u64) -> Value {
    let mut value = pull(owner, 701);
    value["id"] = json!(id);
    value["pull_request"] =
        json!({"url":format!("https://api.github.com/repos/{owner}/project/pulls/7")});
    value
}
async fn fake_token(
    State(state): State<Arc<Github>>,
    Form(fields): Form<BTreeMap<String, String>>,
) -> Response {
    assert_eq!(fields["client_id"], "fixture-pully-owned");
    assert_eq!(fields["client_secret"], "fixture-pully-secret");
    assert_eq!(
        fields["redirect_uri"],
        format!("https://pully.example{}", wire::CALLBACK_PATH)
    );
    let code = fields["code"].clone();
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(fields["code_verifier"].as_bytes()));
    assert_eq!(state.pkce.lock().unwrap().get(&code), Some(&challenge));
    let scope = if state.reduced_scope.load(Ordering::SeqCst) {
        "offline_access"
    } else {
        "repo"
    };
    Json(json!({"access_token":format!("fixture-access-{code}"),"token_type":"bearer","scope":scope})).into_response()
}
async fn fake_user(State(state): State<Arc<Github>>, headers: HeaderMap) -> Json<Value> {
    state
        .requests
        .lock()
        .unwrap()
        .push(("/user".into(), Some(auth(&headers))));
    let (login, id) = current_subject(&state, &headers);
    Json(json!({"id":id,"login":login}))
}
async fn fake_repositories(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Query(query): Query<BTreeMap<String, String>>,
) -> Json<Value> {
    state
        .requests
        .lock()
        .unwrap()
        .push(("/user/repos".into(), Some(auth(&headers))));
    assert!(["public", "all"].contains(&query["visibility"].as_str()));
    state
        .queries
        .lock()
        .unwrap()
        .push(format!("visibility:{}", query["visibility"]));
    assert_eq!(query["per_page"], "20");
    let (login, id) = current_subject(&state, &headers);
    Json(json!([repository(
        &login,
        if id == 42 { 701 } else { 702 },
        state.private.load(Ordering::SeqCst)
    )]))
}
async fn fake_repository(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Path((owner, _name)): Path<(String, String)>,
) -> Response {
    let call = state.repo_calls.fetch_add(1, Ordering::SeqCst) + 1;
    state
        .requests
        .lock()
        .unwrap()
        .push((format!("/repos/{owner}/project"), Some(auth(&headers))));
    if state.private.load(Ordering::SeqCst)
        && (current_subject(&state, &headers).0 != owner
            || state.deny_private.load(Ordering::SeqCst)
            || (owner == "reader-a" && state.deny_a_only.load(Ordering::SeqCst)))
    {
        return StatusCode::NOT_FOUND.into_response();
    }
    let id = repository_id(&state, &owner);
    let response =
        Json(repository(&owner, id, state.private.load(Ordering::SeqCst))).into_response();
    if state.hold_repo_at.load(Ordering::SeqCst) == call {
        state.repo_entered.notify_one();
        state.repo_release.notified().await;
    }
    response
}
async fn fake_pulls(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Path((owner, _name)): Path<(String, String)>,
) -> Json<Value> {
    state.requests.lock().unwrap().push((
        format!("/repos/{owner}/project/pulls"),
        Some(auth(&headers)),
    ));
    // The PR inventory remains the captured old repository when only metadata is replaced.
    let id = if owner == "reader-b" {
        702
    } else if owner == "reader-a" && state.current_a_login.lock().unwrap().is_some() {
        703
    } else {
        701
    };
    if state.hold.load(Ordering::SeqCst) {
        state.entered.notify_one();
        state.release.notified().await;
    }
    Json(json!([pull(&owner, id)]))
}
async fn fake_search(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Query(query): Query<BTreeMap<String, String>>,
) -> Json<Value> {
    let login = query["q"].rsplit(':').next().unwrap();
    assert!(["reader-a", "reader-b", "renamed-a"].contains(&login));
    state.queries.lock().unwrap().push(query["q"].clone());
    state
        .requests
        .lock()
        .unwrap()
        .push(("/search/issues".into(), Some(auth(&headers))));
    let owner = if state.rename_during_search.load(Ordering::SeqCst) {
        "reader-b" // A stable third-party repository keeps this test about the mutable selector.
    } else {
        login
    };
    let mut value = issue(owner, issue_id(&state, owner));
    value["repository_url"] = json!(format!("https://api.github.com/repos/{owner}/project"));
    value["title"] = json!("old search title must not be published");
    if state.stale_search_draft.load(Ordering::SeqCst) {
        value["draft"] = json!(true);
    }
    if state.omit_search_draft.load(Ordering::SeqCst) {
        value.as_object_mut().unwrap().remove("draft");
    }
    if state.rename_during_search.load(Ordering::SeqCst) {
        *state.current_a_login.lock().unwrap() = Some("renamed-a".into());
    }
    Json(json!({"incomplete_results":false,"items":[value]}))
}
async fn fake_issue(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Path((owner, _name, _number)): Path<(String, String, u64)>,
) -> Json<Value> {
    state.requests.lock().unwrap().push((
        format!("/repos/{owner}/project/issues/7"),
        Some(auth(&headers)),
    ));
    let id = if state.wrong_issue.load(Ordering::SeqCst) {
        9999
    } else {
        issue_id(&state, &owner)
    };
    let mut value = issue(&owner, id);
    if state.omit_issue_draft.load(Ordering::SeqCst) {
        value.as_object_mut().unwrap().remove("draft");
    }
    Json(value)
}
async fn fake_pull_detail(
    State(state): State<Arc<Github>>,
    headers: HeaderMap,
    Path((owner, _name, number)): Path<(String, String, u64)>,
) -> Json<Value> {
    assert_eq!(number, 7);
    state.requests.lock().unwrap().push((
        format!("/repos/{owner}/project/pulls/7"),
        Some(auth(&headers)),
    ));
    let id = if state.wrong_pull_base.load(Ordering::SeqCst) {
        9999
    } else {
        repository_id(&state, &owner)
    };
    let mut value = pull(&owner, id);
    value["draft"] = json!(true);
    Json(value)
}
struct Fixture {
    _root: Root,
    github: Arc<Github>,
    _upstream: Server,
    server: Server,
}
impl Fixture {
    async fn new() -> Self {
        let root = Root::new();
        let github = Arc::new(Github::default());
        let routes = Router::new()
            .route("/login/oauth/access_token", post(fake_token))
            .route("/user", get(fake_user))
            .route("/user/repos", get(fake_repositories))
            .route("/repos/{owner}/{name}", get(fake_repository))
            .route("/repos/{owner}/{name}/pulls", get(fake_pulls))
            .route(
                "/repos/{owner}/{name}/pulls/{number}",
                get(fake_pull_detail),
            )
            .route("/search/issues", get(fake_search))
            .route("/repos/{owner}/{name}/issues/{number}", get(fake_issue))
            .with_state(github.clone());
        let upstream = Server::new(routes).await;
        let origin = url::Url::parse(&format!("{}/", upstream.origin)).unwrap();
        let loaded = config::load(configuration(&root)).await.unwrap().unwrap();
        let host = PersonalAuthHost::fixture(loaded.auth, origin.clone())
            .await
            .unwrap();
        let (auth_router, auth) = host.into_parts();
        let auth = auth.unwrap();
        let reads = personal::fixture_router(auth, loaded.tenant_root, origin.clone())
            .await
            .unwrap();
        let server = Server::new(compose(
            crate::public_api::fixture_router(origin, true),
            auth_router,
            Some(reads),
        ))
        .await;
        Self {
            _root: root,
            github,
            _upstream: upstream,
            server,
        }
    }
    async fn sign_in(&self, code: &str) -> (String, Value) {
        self.authorize(code, None, "publicRead").await
    }
    async fn upgrade(&self, session: &str, code: &str) -> (String, Value) {
        self.authorize(code, Some(session), "privateRepositories")
            .await
    }
    async fn start_flow(&self, code: &str, session: Option<&str>) -> Value {
        let proof = URL_SAFE_NO_PAD.encode([if code == "a" { 31 } else { 47 }; 32]);
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(proof.as_bytes()));
        let response = self
            .server
            .post(
                if session.is_some() {
                    wire::REPOSITORY_CONSENT_PATH
                } else {
                    wire::START_PATH
                },
                json!({"nativeChallenge":challenge}),
                session,
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let start: Value = response.json().await.unwrap();
        let url = url::Url::parse(start["authorizeUrl"].as_str().unwrap()).unwrap();
        assert_eq!(url.host_str(), Some("github.com"));
        let params = url.query_pairs().into_owned().collect::<BTreeMap<_, _>>();
        assert_eq!(
            params["scope"],
            if session.is_some() {
                "repo offline_access"
            } else {
                "offline_access"
            }
        );
        self.github
            .pkce
            .lock()
            .unwrap()
            .insert(code.into(), params["code_challenge"].clone());
        let callback = self
            .server
            .client
            .get(format!("{}{}", self.server.origin, wire::CALLBACK_PATH))
            .query(&[("code", code), ("state", params["state"].as_str())])
            .send()
            .await
            .unwrap();
        assert_eq!(callback.status(), StatusCode::OK);
        let page = callback.text().await.unwrap();
        assert!(!page.contains(&proof));
        assert!(!page.contains("fixture-access"));
        json!({"attemptId":start["attemptId"],"nativeProof":proof})
    }
    async fn authorize(
        &self,
        code: &str,
        session: Option<&str>,
        authority: &str,
    ) -> (String, Value) {
        let owned = self.start_flow(code, session).await;
        let status = self
            .server
            .post(wire::STATUS_PATH, owned.clone(), None)
            .await;
        assert_eq!(status.json::<Value>().await.unwrap()["phase"], "ready");
        let claim = self
            .server
            .post(wire::CLAIM_PATH, owned.clone(), None)
            .await;
        assert_eq!(claim.status(), StatusCode::OK);
        let claim: Value = claim.json().await.unwrap();
        let session = claim["session"].as_str().unwrap().to_owned();
        assert!(self
            .server
            .post(wire::CLAIM_PATH, owned, None)
            .await
            .status()
            .is_client_error());
        let confirmed = self.server.get(wire::SESSION_PATH, Some(&session)).await;
        assert_eq!(confirmed.status(), StatusCode::OK);
        let confirmed: Value = confirmed.json().await.unwrap();
        assert_eq!(confirmed["authority"], authority);
        (session, confirmed)
    }
}

#[tokio::test]
async fn unconfigured_real_host_serves_disabled_auth_and_no_private_or_personal_reads() {
    let server = Server::new(router(PublicServerConfig::default()).await.unwrap()).await;
    assert_eq!(
        server
            .get(wire::CAPABILITIES_PATH, None)
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"github":{"enabled":false}})
    );
    let discovery = server
        .get("/api/public/v1/capabilities", None)
        .await
        .json::<Value>()
        .await
        .unwrap();
    assert_eq!(discovery["githubSignIn"], "notConfigured");
    for path in ["/api/rpc", "/android/latest.json", REPOSITORIES_PATH] {
        assert_eq!(server.get(path, None).await.status(), StatusCode::NOT_FOUND);
    }
}

#[tokio::test]
async fn configured_production_factory_installs_actual_start_and_read_routes() {
    let root = Root::new();
    let server = Server::new(router(configuration(&root)).await.unwrap()).await;
    assert_eq!(
        server
            .get(wire::CAPABILITIES_PATH, None)
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"github":{"enabled":true}})
    );
    assert_eq!(
        server
            .get("/api/public/v1/capabilities", None)
            .await
            .json::<Value>()
            .await
            .unwrap()["githubSignIn"],
        "configured"
    );
    assert_eq!(
        server.get(REPOSITORIES_PATH, None).await.status(),
        StatusCode::UNAUTHORIZED
    );
    let proof = URL_SAFE_NO_PAD.encode([19; 32]);
    let response = server
        .post(
            wire::START_PATH,
            json!({"nativeChallenge":URL_SAFE_NO_PAD.encode(Sha256::digest(proof.as_bytes()))}),
            None,
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = response.json::<Value>().await.unwrap().to_string();
    assert!(!body.contains("fixture-pully-secret"));
    assert!(!body.contains(&proof));
}

#[tokio::test]
async fn real_exchange_claim_confirmation_and_two_users_reach_only_their_own_typed_inventory() {
    let fixture = Fixture::new().await;
    let (a, a_status) = fixture.sign_in("a").await;
    let (b, b_status) = fixture.sign_in("b").await;
    assert_ne!(a_status["accountId"], b_status["accountId"]);
    for (session, status, login) in [(&a, &a_status, "reader-a"), (&b, &b_status, "reader-b")] {
        let response = fixture.server.get(REPOSITORIES_PATH, Some(session)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["accountId"], status["accountId"]);
        assert_eq!(body["githubId"], status["githubId"]);
        assert_eq!(body["authority"], "publicRead");
        assert_eq!(body["repositories"][0]["owner"], login);
        assert_eq!(body["nextPage"], Value::Null);
        assert_eq!(body["incomplete"], false);
        let public = body.to_string();
        for secret in [
            session.as_str(),
            "fixture-access-a",
            "fixture-access-b",
            "fixture-pully-secret",
        ] {
            assert!(!public.contains(secret));
        }
    }
    let reads = fixture.github.requests.lock().unwrap();
    assert!(reads.iter().any(|(path, token)| path == "/user/repos"
        && token.as_deref() == Some("Bearer fixture-access-a")));
    assert!(reads.iter().any(|(path, token)| path == "/user/repos"
        && token.as_deref() == Some("Bearer fixture-access-b")));
    assert!(fixture
        .github
        .queries
        .lock()
        .unwrap()
        .iter()
        .all(|query| query == "visibility:public"));
}

#[tokio::test]
async fn inherited_repo_scope_cannot_publish_private_inventory_or_prs() {
    let fixture = Fixture::new().await;
    let (session, status) = fixture.sign_in("a").await;
    assert_eq!(status["authority"], "publicRead");
    fixture.github.private.store(true, Ordering::SeqCst);
    assert_eq!(
        fixture
            .server
            .get(REPOSITORIES_PATH, Some(&session))
            .await
            .status(),
        StatusCode::BAD_GATEWAY
    );
    let response = fixture
        .server
        .get(
            "/api/personal/v1/github/repositories/reader-a/project/pulls",
            Some(&session),
        )
        .await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert!(!response
        .text()
        .await
        .unwrap()
        .contains("current public title"));
}

#[tokio::test]
async fn actual_repository_consent_enables_own_private_reads_without_cross_tenant_or_public_session_upgrade(
) {
    let fixture = Fixture::new().await;
    let (public_a, public_a_status) = fixture.sign_in("a").await;
    let (public_b, public_b_status) = fixture.sign_in("b").await;
    let before: Value = fixture
        .server
        .get(ACCOUNT_CAPABILITIES_PATH, Some(&public_a))
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(before["authority"], "publicRead");
    assert_eq!(
        before["privateRepositories"],
        json!({"consentAvailable":true,"readEnabled":false})
    );
    assert_eq!(
        fixture
            .server
            .get(ACCOUNT_REPOSITORIES_PATH, Some(&public_a))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );

    let (private_a, private_a_status) = fixture.upgrade(&public_a, "a-upgrade").await;
    let (private_b, private_b_status) = fixture.upgrade(&public_b, "b-upgrade").await;
    assert_eq!(public_a_status["accountId"], private_a_status["accountId"]);
    assert_eq!(public_b_status["accountId"], private_b_status["accountId"]);
    fixture.github.private.store(true, Ordering::SeqCst);
    for (session, public_session, status, login, other) in [
        (
            &private_a,
            &public_a,
            &private_a_status,
            "reader-a",
            "reader-b",
        ),
        (
            &private_b,
            &public_b,
            &private_b_status,
            "reader-b",
            "reader-a",
        ),
    ] {
        let caps: Value = fixture
            .server
            .get(ACCOUNT_CAPABILITIES_PATH, Some(session))
            .await
            .json()
            .await
            .unwrap();
        assert_eq!(caps["authority"], "privateRepositories");
        assert_eq!(caps["privateRepositories"]["readEnabled"], true);
        let old_status: Value = fixture
            .server
            .get(wire::SESSION_PATH, Some(public_session))
            .await
            .json()
            .await
            .unwrap();
        assert_eq!(old_status["authority"], "publicRead");
        assert_eq!(
            fixture
                .server
                .get(ACCOUNT_REPOSITORIES_PATH, Some(public_session))
                .await
                .status(),
            StatusCode::FORBIDDEN
        );

        let response = fixture
            .server
            .get(ACCOUNT_REPOSITORIES_PATH, Some(session))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let inventory: Value = response.json().await.unwrap();
        assert_eq!(inventory["accountId"], status["accountId"]);
        assert_eq!(inventory["githubId"], status["githubId"]);
        assert_eq!(inventory["authority"], "privateRepositories");
        assert_eq!(inventory["repositories"][0]["owner"], login);
        assert_eq!(inventory["repositories"][0]["private"], true);
        let own_path =
            format!("/api/personal/v1/github/account/repositories/{login}/project/pulls");
        let selected = fixture.server.get(&own_path, Some(session)).await;
        assert_eq!(selected.status(), StatusCode::OK);
        let selected: Value = selected.json().await.unwrap();
        assert_eq!(selected["repository"]["private"], true);
        assert_eq!(
            selected["pulls"][0]["title"],
            format!("current public title {login}")
        );
        for relation in ["reviewRequested", "authored", "involved"] {
            let page = fixture
                .server
                .get(
                    &format!("{ACCOUNT_PULLS_PATH}?relation={relation}"),
                    Some(session),
                )
                .await;
            assert_eq!(page.status(), StatusCode::OK);
            let page: Value = page.json().await.unwrap();
            assert_eq!(page["accountId"], status["accountId"]);
            assert_eq!(page["pulls"][0]["repository"]["owner"], login);
            assert_eq!(page["pulls"][0]["repository"]["private"], true);
        }
        let foreign = fixture
            .server
            .get(
                &format!("/api/personal/v1/github/account/repositories/{other}/project/pulls"),
                Some(session),
            )
            .await;
        assert_eq!(foreign.status(), StatusCode::FORBIDDEN);
        assert!(!foreign
            .text()
            .await
            .unwrap()
            .contains("current public title"));
        let old_public_path = format!("/api/personal/v1/github/repositories/{login}/project/pulls");
        assert_eq!(
            fixture
                .server
                .get(&old_public_path, Some(session))
                .await
                .status(),
            StatusCode::FORBIDDEN
        );
    }
    let queries = fixture.github.queries.lock().unwrap();
    assert!(queries
        .iter()
        .filter(|query| query.starts_with("visibility:"))
        .all(|query| query == "visibility:all"));
    assert!(queries
        .iter()
        .filter(|query| query.starts_with("is:pr"))
        .all(|query| !query.contains("is:public")));
}

#[tokio::test]
async fn private_consent_rejects_a_different_github_subject_without_changing_the_origin_account() {
    let fixture = Fixture::new().await;
    let (a, status) = fixture.sign_in("a").await;
    let state = crate::store::Store::new(fixture._root.0.join("isolated/personal-auth")).unwrap();
    let before = state.get("personal-auth-v1:state").unwrap();
    let attempt = fixture.start_flow("b-upgrade", Some(&a)).await;
    let phase: Value = fixture
        .server
        .post(wire::STATUS_PATH, attempt.clone(), None)
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(phase["phase"], "failed");
    assert_eq!(phase["failure"], "denied");
    assert_eq!(
        fixture
            .server
            .post(wire::CLAIM_PATH, attempt, None)
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(state.get("personal-auth-v1:state").unwrap(), before);
    let current: Value = fixture
        .server
        .get(wire::SESSION_PATH, Some(&a))
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(current["accountId"], status["accountId"]);
    assert_eq!(current["authority"], "publicRead");
}

#[tokio::test]
async fn a_ready_private_consent_cannot_survive_sign_out_or_a_newer_sign_in() {
    for retire in ["signOut", "replace"] {
        let fixture = Fixture::new().await;
        let (a, _) = fixture.sign_in("a").await;
        let attempt = fixture.start_flow("a-upgrade", Some(&a)).await;
        let fresh = if retire == "signOut" {
            assert_eq!(
                fixture
                    .server
                    .post(wire::SIGN_OUT_PATH, json!({}), Some(&a))
                    .await
                    .status(),
                StatusCode::OK
            );
            None
        } else {
            Some(fixture.sign_in("a-new").await.0)
        };
        let store =
            crate::store::Store::new(fixture._root.0.join("isolated/personal-auth")).unwrap();
        let before = store.get("personal-auth-v1:state").unwrap();
        assert!(!fixture
            .server
            .post(wire::CLAIM_PATH, attempt, None)
            .await
            .status()
            .is_success());
        assert_eq!(store.get("personal-auth-v1:state").unwrap(), before);
        if let Some(fresh) = fresh {
            let current = fixture.server.get(REPOSITORIES_PATH, Some(&fresh)).await;
            assert_eq!(current.status(), StatusCode::OK);
            assert_eq!(
                current.json::<Value>().await.unwrap()["authority"],
                "publicRead"
            );
        }
    }
}

#[tokio::test]
async fn an_actual_reduced_repo_grant_keeps_the_new_session_public_read() {
    let fixture = Fixture::new().await;
    let (a, _) = fixture.sign_in("a").await;
    fixture.github.reduced_scope.store(true, Ordering::SeqCst);
    let (reduced, status) = fixture.authorize("a-upgrade", Some(&a), "publicRead").await;
    assert_eq!(status["authority"], "publicRead");
    assert_eq!(
        fixture
            .server
            .get(ACCOUNT_REPOSITORIES_PATH, Some(&reduced))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn held_private_prs_cannot_publish_after_revocation_permission_loss_or_repository_replacement(
) {
    for mode in ["signOut", "permission", "replace"] {
        let fixture = Fixture::new().await;
        let (a, _) = fixture.sign_in("a").await;
        let (private, _) = fixture.upgrade(&a, "a-upgrade").await;
        fixture.github.private.store(true, Ordering::SeqCst);
        fixture.github.hold.store(true, Ordering::SeqCst);
        let client = fixture.server.client.clone();
        let path = format!(
            "{}/api/personal/v1/github/account/repositories/reader-a/project/pulls",
            fixture.server.origin
        );
        let captured = private.clone();
        let request =
            tokio::spawn(
                async move { client.get(path).bearer_auth(captured).send().await.unwrap() },
            );
        tokio::time::timeout(Duration::from_secs(2), fixture.github.entered.notified())
            .await
            .unwrap();
        match mode {
            "signOut" => {
                assert_eq!(
                    fixture
                        .server
                        .post(wire::SIGN_OUT_PATH, json!({}), Some(&private))
                        .await
                        .status(),
                    StatusCode::OK
                );
            }
            "permission" => fixture.github.deny_private.store(true, Ordering::SeqCst),
            _ => fixture.github.replacement.store(true, Ordering::SeqCst),
        }
        fixture.github.release.notify_one();
        let response = tokio::time::timeout(Duration::from_secs(2), request)
            .await
            .unwrap()
            .unwrap();
        assert!(response.status().is_client_error());
        assert!(!response
            .text()
            .await
            .unwrap()
            .contains("current public title"));
    }
}

#[tokio::test]
async fn selected_prs_and_own_public_pulls_use_fresh_identity_checked_metadata() {
    let fixture = Fixture::new().await;
    let (session, status) = fixture.sign_in("a").await;
    let selected = fixture
        .server
        .get(
            "/api/personal/v1/github/repositories/reader-a/project/pulls?page=1",
            Some(&session),
        )
        .await;
    assert_eq!(selected.status(), StatusCode::OK);
    let selected: Value = selected.json().await.unwrap();
    assert_eq!(selected["repository"]["id"], 701);
    assert_eq!(selected["accountId"], status["accountId"]);
    for relation in ["reviewRequested", "authored", "involved"] {
        let response = fixture
            .server
            .get(
                &format!("{PULLS_PATH}?relation={relation}&page=1"),
                Some(&session),
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value = response.json().await.unwrap();
        assert_eq!(body["relation"], relation);
        assert_eq!(body["pulls"][0]["repository"]["id"], 701);
        assert_eq!(body["pulls"][0]["title"], "current public title reader-a");
        assert!(!body.to_string().contains("old search title"));
    }
    fixture.github.wrong_issue.store(true, Ordering::SeqCst);
    assert!(fixture
        .github
        .queries
        .lock()
        .unwrap()
        .iter()
        .all(|query| query.contains("is:public")));
    assert_eq!(
        fixture
            .server
            .get(PULLS_PATH, Some(&session))
            .await
            .status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn read_boundary_refuses_legacy_headers_caller_identity_queries_and_arbitrary_routes() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    for header in [
        "cookie",
        "x-pully-session",
        "cf-access-client-secret",
        "x-forwarded-authorization",
    ] {
        let response = fixture
            .server
            .client
            .get(format!("{}{REPOSITORIES_PATH}", fixture.server.origin))
            .bearer_auth(&session)
            .header(header, "fixture-forbidden")
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
    for path in [
        format!("{REPOSITORIES_PATH}?accountId=other"),
        format!("{REPOSITORIES_PATH}?page=11"),
        format!("{PULLS_PATH}?relation=arbitrary"),
        format!("{PULLS_PATH}?q=is:private"),
    ] {
        assert_eq!(
            fixture.server.get(&path, Some(&session)).await.status(),
            StatusCode::BAD_REQUEST
        );
    }
    for path in [
        "/api/rpc",
        "/api/personal/v1/github/token",
        "/api/personal/v1/ai/start",
    ] {
        assert_eq!(
            fixture
                .server
                .post(path, json!({}), Some(&session))
                .await
                .status(),
            StatusCode::NOT_FOUND
        );
    }
}

#[tokio::test]
async fn held_inventory_cannot_publish_after_sign_out_visibility_denial_or_repository_replacement()
{
    for mode in ["signOut", "private", "replace"] {
        let fixture = Fixture::new().await;
        let (session, _) = fixture.sign_in("a").await;
        fixture.github.hold.store(true, Ordering::SeqCst);
        let client = fixture.server.client.clone();
        let path = format!(
            "{}/api/personal/v1/github/repositories/reader-a/project/pulls",
            fixture.server.origin
        );
        let captured = session.clone();
        let work =
            tokio::spawn(
                async move { client.get(path).bearer_auth(captured).send().await.unwrap() },
            );
        tokio::time::timeout(Duration::from_secs(2), fixture.github.entered.notified())
            .await
            .unwrap();
        match mode {
            "signOut" => {
                assert_eq!(
                    fixture
                        .server
                        .post(wire::SIGN_OUT_PATH, json!({}), Some(&session))
                        .await
                        .status(),
                    StatusCode::OK
                );
            }
            "private" => {
                fixture.github.private.store(true, Ordering::SeqCst);
            }
            _ => {
                fixture.github.replacement.store(true, Ordering::SeqCst);
            }
        }
        fixture.github.release.notify_one();
        let response = tokio::time::timeout(Duration::from_secs(2), work)
            .await
            .unwrap()
            .unwrap();
        assert!(response.status().is_client_error());
        let body = response.text().await.unwrap();
        assert!(!body.contains("current public title"));
        assert!(!body.contains("fixture-access"));
    }
}

#[cfg(unix)]
#[tokio::test]
async fn startup_secret_file_permissions_and_invalid_configuration_fail_without_store_or_secret_errors(
) {
    use std::os::unix::fs::PermissionsExt;
    let root = Root::new();
    let config = configuration(&root);
    let files = config.personal.as_ref().unwrap();
    std::fs::set_permissions(
        &files.oauth_app_file,
        std::fs::Permissions::from_mode(0o644),
    )
    .unwrap();
    let error = match router(config).await {
        Err(error) => error,
        Ok(_) => panic!("world-readable OAuth config accepted"),
    };
    assert_eq!(
        error.to_string(),
        "Public Pully service configuration is unavailable"
    );
    assert!(std::error::Error::source(&error).is_none());
    assert!(!root.0.join("isolated").exists());
    let config = configuration(&root);
    let files = config.personal.as_ref().unwrap();
    private(
        &files.vault_keys_file,
        &json!({"activeVersion":1,"keys":{"1":"fixture-private-invalid-key"}}),
    );
    assert!(router(config).await.is_err());
    assert!(!root.0.join("isolated").exists());
}
