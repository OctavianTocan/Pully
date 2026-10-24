use super::*;
use crate::{
    identity::TenantStates,
    personal_auth::{
        wire as auth_wire, OAuthConfig, PersonalAuthHost, PersonalAuthHostConfig, VaultKeys,
    },
};
use axum::{
    body::{Body, Bytes},
    extract::Request,
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::any,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap},
    path::PathBuf,
    sync::atomic::AtomicBool,
    time::Duration,
};
use tokio::sync::{Notify, Semaphore};

const BASE_SHA: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const HEAD_SHA: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const NEXT_HEAD: &str = "dddddddddddddddddddddddddddddddddddddddd";
const REPOSITORY: &str = "/repos/reader-a/project";
const PULL: &str = "/repos/reader-a/project/pulls/7";
const CONTENTS: &str = "/repos/reader-a/project/contents/file.rs";
const REVIEW: &str = "/api/personal/v1/github/account/repositories/reader-a/project/pulls/7/review";

struct Gate {
    entered: Notify,
    release: Semaphore,
}
impl Gate {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            entered: Notify::new(),
            release: Semaphore::new(0),
        })
    }
    async fn hold(&self) {
        self.entered.notify_one();
        self.release.acquire().await.unwrap().forget();
    }
    async fn arrived(&self) {
        crate::rt::timeout(Duration::from_secs(2), self.entered.notified())
            .await
            .unwrap();
    }
    fn open(&self) {
        self.release.add_permits(1);
    }
}

struct Provider {
    denied: AtomicBool,
    moved: AtomicBool,
    pull_unavailable: AtomicBool,
    gates: Mutex<HashMap<String, Arc<Gate>>>,
    calls: Mutex<Vec<String>>,
    challenge: Mutex<Option<String>>,
    graphql_error: Mutex<Option<Value>>,
    first_chunk: Notify,
    second_chunk: Semaphore,
    last_chunk: Semaphore,
}
impl Provider {
    fn new() -> Self {
        Self {
            denied: AtomicBool::new(false),
            moved: AtomicBool::new(false),
            pull_unavailable: AtomicBool::new(false),
            gates: Default::default(),
            calls: Default::default(),
            challenge: Default::default(),
            graphql_error: Default::default(),
            first_chunk: Notify::new(),
            second_chunk: Semaphore::new(0),
            last_chunk: Semaphore::new(0),
        }
    }
    fn gate(&self, path: &str) -> Arc<Gate> {
        let gate = Gate::new();
        self.gates
            .lock()
            .unwrap()
            .insert(path.to_owned(), gate.clone());
        gate
    }
    fn count(&self, path: &str) -> usize {
        self.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|call| call.as_str() == path)
            .count()
    }
    fn pull(&self) -> Value {
        json!({"id":8001,"node_id":"PR_8001","number":7,"base":{"sha":BASE_SHA,"repo":{"id":701}},"head":{"sha":if self.moved.load(Ordering::SeqCst){NEXT_HEAD}else{HEAD_SHA}}})
    }
}

async fn respond(State(state): State<Arc<Provider>>, request: Request) -> Response {
    let path = request.uri().path().to_owned();
    let headers = request.headers().clone();
    let body = axum::body::to_bytes(request.into_body(), 64 * 1024)
        .await
        .unwrap();
    if path == "/login/oauth/access_token" {
        let fields = url::form_urlencoded::parse(&body)
            .into_owned()
            .collect::<BTreeMap<_, _>>();
        assert_eq!(fields["client_id"], "boundary-pully-owned");
        assert_eq!(fields["client_secret"], "boundary-pully-secret");
        assert_eq!(
            Some(URL_SAFE_NO_PAD.encode(Sha256::digest(fields["code_verifier"].as_bytes()))),
            *state.challenge.lock().unwrap()
        );
        return Json(
            json!({"access_token":"boundary-github-key","token_type":"bearer","scope":"repo"}),
        )
        .into_response();
    }
    assert_eq!(headers[header::AUTHORIZATION], "Bearer boundary-github-key");
    if path == "/user" {
        return Json(json!({"id":42,"login":"reader-a"})).into_response();
    }
    let value = if path == "/graphql" {
        Some(serde_json::from_slice::<Value>(&body).unwrap())
    } else {
        None
    };
    let key = if value
        .as_ref()
        .is_some_and(|v| v["query"].as_str().unwrap().contains("PullRequestOverview"))
    {
        "overview"
    } else {
        path.as_str()
    };
    state.calls.lock().unwrap().push(path.clone());
    let gate = state.gates.lock().unwrap().get(key).cloned();
    if let Some(gate) = gate {
        gate.hold().await;
    }
    match path.as_str() {
        REPOSITORY if state.denied.load(Ordering::SeqCst) => StatusCode::NOT_FOUND.into_response(),
        REPOSITORY => Json(json!({"id":701,"owner":{"login":"reader-a"},"name":"project","full_name":"reader-a/project","private":true,"stargazers_count":0})).into_response(),
        PULL if state.pull_unavailable.load(Ordering::SeqCst) => StatusCode::SERVICE_UNAVAILABLE.into_response(),
        PULL => Json(state.pull()).into_response(),
        "/repos/reader-a/project/contents/latin1.txt" => Response::new(Body::from(&b"caf\xe9\n"[..])),
        CONTENTS => Json(json!({"type":"file","size":4,"encoding":"base64","content":"bmV3Cg=="})).into_response(),
        "/repos/reader-a/project/contents/stream.rs" => {
            let stream=futures_util::stream::unfold((0,state),|(step,state)|async move {
                let chunk=match step {
                    0=>{state.first_chunk.notify_one(); Bytes::from_static(b"{\"type\":\"file\",")},
                    1=>{state.second_chunk.acquire().await.unwrap().forget(); Bytes::from_static(b"\"encoding\":\"base64\",")},
                    2=>{state.last_chunk.acquire().await.unwrap().forget(); Bytes::from_static(b"\"size\":4,\"content\":\"bmV3Cg==\"}")},
                    _=>return None,
                }; Some((Ok::<_,std::convert::Infallible>(chunk),(step+1,state)))
            });
            Response::new(Body::from_stream(stream))
        },
        "/repos/reader-a/project/pulls/7/comments" => Json(json!([{"id":81,"path":"file.rs","side":"RIGHT","line":1,"body":"line note","created_at":"2026-10-22T00:00:00Z","user":{"login":"reader-a"}}])).into_response(),
        "/repos/reader-a/project/pulls/7/reviews" => Json(json!([])).into_response(),
        "/graphql" => {
            let query=value.as_ref().unwrap()["query"].as_str().unwrap();
            if query.contains("ReviewThreads") {
                if let Some(error)=state.graphql_error.lock().unwrap().clone(){return Json(error).into_response();}
                return Json(json!({"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}})).into_response();
            }
            Json(json!({"data":{"repository":{"viewerPermission":"READ","pullRequest":{"id":"PR_8001","number":7,"title":"private boundary","state":"OPEN","headRefOid":HEAD_SHA,"baseRefOid":BASE_SHA,"headRefName":"feature","baseRefName":"main","mergeable":"MERGEABLE","mergeStateStatus":"CLEAN","createdAt":"2026-10-22T00:00:00Z","updatedAt":"2026-10-22T00:00:00Z","author":{"__typename":"User","login":"reader-a"}}}}})).into_response()
        },
        path if path.starts_with("/repos/reader-a/project/hold/") => Json(json!({})).into_response(),
        _=>StatusCode::NOT_FOUND.into_response(),
    }
}

struct Server {
    origin: String,
    client: reqwest::Client,
    task: crate::rt::JoinHandle<()>,
}
impl Server {
    async fn new(router: Router) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let task = crate::rt::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        Self {
            origin,
            client: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap(),
            task,
        }
    }
    async fn get(&self, path: &str, session: &str) -> reqwest::Response {
        self.client
            .get(format!("{}{path}", self.origin))
            .bearer_auth(session)
            .send()
            .await
            .unwrap()
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
struct Fixture {
    root: PathBuf,
    _upstream: Server,
    server: Server,
    provider: Arc<Provider>,
    inner: Arc<Inner>,
    session: String,
}
impl Fixture {
    async fn new() -> Self {
        let root =
            std::env::temp_dir().join(format!("pully-review-boundaries-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let provider = Arc::new(Provider::new());
        let upstream = Server::new(
            Router::new()
                .route("/{*path}", any(respond))
                .with_state(provider.clone()),
        )
        .await;
        let origin = url::Url::parse(&format!("{}/", upstream.origin)).unwrap();
        let config = PersonalAuthHostConfig::new(
            root.join("auth"),
            OAuthConfig::new(
                "boundary-pully-owned".into(),
                "boundary-pully-secret".into(),
                "https://pully.example/api/personal/v1/auth/github/callback",
            )
            .unwrap(),
            VaultKeys::new(1, BTreeMap::from([(1, [29; 32])])).unwrap(),
        );
        let (auth_router, auth) = PersonalAuthHost::fixture(config, origin.clone())
            .await
            .unwrap()
            .into_parts();
        let inner = Arc::new(Inner {
            auth: auth.unwrap(),
            tenants: TenantStates::new(root.join("tenants"), 256).unwrap(),
            clients: Default::default(),
            http: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap(),
            origin,
            requests: Arc::new(Semaphore::new(64)),
            review_requests: Arc::new(Semaphore::new(4)),
        });
        let reads = routes()
            .with_state(inner.clone())
            .layer(axum::middleware::from_fn(super::super::boundary));
        let server = Server::new(auth_router.merge(reads)).await;
        let mut fixture = Self {
            root,
            _upstream: upstream,
            server,
            provider,
            inner,
            session: String::new(),
        };
        let public = fixture.authorize(None, 31).await;
        fixture.session = fixture.authorize(Some(&public), 47).await;
        fixture
    }
    async fn authorize(&self, session: Option<&str>, seed: u8) -> String {
        let proof = URL_SAFE_NO_PAD.encode([seed; 32]);
        let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(proof.as_bytes()));
        let path = if session.is_some() {
            auth_wire::REPOSITORY_CONSENT_PATH
        } else {
            auth_wire::START_PATH
        };
        let mut request = self
            .server
            .client
            .post(format!("{}{path}", self.server.origin))
            .json(&json!({"nativeChallenge":challenge}));
        if let Some(session) = session {
            request = request.bearer_auth(session);
        }
        let response = request.send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let start: Value = response.json().await.unwrap();
        let url = url::Url::parse(start["authorizeUrl"].as_str().unwrap()).unwrap();
        let params = url.query_pairs().into_owned().collect::<BTreeMap<_, _>>();
        *self.provider.challenge.lock().unwrap() = Some(params["code_challenge"].clone());
        let callback = self
            .server
            .client
            .get(format!(
                "{}{}",
                self.server.origin,
                auth_wire::CALLBACK_PATH
            ))
            .query(&[("code", "boundary"), ("state", params["state"].as_str())])
            .send()
            .await
            .unwrap();
        assert_eq!(callback.status(), StatusCode::OK);
        let claim = self
            .server
            .client
            .post(format!("{}{}", self.server.origin, auth_wire::CLAIM_PATH))
            .json(&json!({"attemptId":start["attemptId"],"nativeProof":proof}))
            .send()
            .await
            .unwrap();
        assert_eq!(claim.status(), StatusCode::OK);
        let claim: Value = claim.json().await.unwrap();
        let session = claim["session"].as_str().unwrap().to_owned();
        let confirmed = self.server.get(auth_wire::SESSION_PATH, &session).await;
        assert_eq!(confirmed.status(), StatusCode::OK);
        assert_eq!(
            confirmed.json::<Value>().await.unwrap()["authority"],
            if seed == 31 {
                "publicRead"
            } else {
                "privateRepositories"
            }
        );
        session
    }
    async fn context(&self) -> Arc<Context> {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::AUTHORIZATION,
            format!("Bearer {}", self.session).parse().unwrap(),
        );
        Arc::new(
            self.inner
                .context(&headers, ReadScope::Account)
                .await
                .unwrap(),
        )
    }
    async fn open(&self) -> Value {
        let response = self
            .server
            .get(&format!("{REVIEW}?repositoryId=701"), &self.session)
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
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.root);
    }
}
fn reader<'a>(context: &'a Context, captured: &'a Captured) -> Reader<'a> {
    Reader {
        context,
        captured,
        owner: "reader-a",
        repo: "project",
        requests: AtomicUsize::new(0),
        bytes: AtomicUsize::new(0),
        failure: Mutex::new(None),
    }
}
fn reference() -> Value {
    json!({"kind":"github-pr","owner":"reader-a","repo":"project","number":"7"})
}
async fn retire(fixture: &Fixture, context: &Context, kind: &str) {
    if kind == "repository" {
        fixture.provider.denied.store(true, Ordering::SeqCst);
        assert!(context
            .github
            .accessible_repository(context, "reader-a", "project")
            .await
            .is_err());
    } else {
        fixture.provider.moved.store(true, Ordering::SeqCst);
        let new = capture(context, "reader-a", "project", 7, 701, None)
            .await
            .unwrap();
        assert_eq!(new.identity.target.head_sha, NEXT_HEAD);
    }
}

async fn queued(kind: &'static str) {
    let fixture = Fixture::new().await;
    let context = fixture.context().await;
    let captured = capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .unwrap();
    let mut holders = Vec::new();
    let mut gates = Vec::new();
    for id in [1, 2] {
        let path = format!("/repos/reader-a/project/hold/{id}");
        let gate = fixture.provider.gate(&path);
        let c = context.clone();
        holders.push(crate::rt::spawn(async move {
            c.github
                .get(
                    &c,
                    &["repos", "reader-a", "project", "hold", &id.to_string()],
                    &[],
                )
                .await
        }));
        gate.arrived().await;
        gates.push(gate);
    }
    let path = if kind == "repository" {
        REPOSITORY
    } else {
        PULL
    };
    let denial = fixture.provider.gate(path);
    let c = context.clone();
    let probe = crate::rt::spawn(async move {
        if kind == "repository" {
            c.github
                .accessible_repository(&c, "reader-a", "project")
                .await
                .map(|_| ())
        } else {
            capture(&c, "reader-a", "project", 7, 701, None)
                .await
                .map(|_| ())
        }
    });
    denial.arrived().await;
    let reader = reader(&context, &captured);
    let reference = reference();
    let mut future = Box::pin(crate::source::github_file_with(
        &reader, &reference, "file.rs", HEAD_SHA,
    ));
    assert!(futures_util::poll!(future.as_mut()).is_pending());
    assert_eq!(fixture.provider.count(CONTENTS), 0);
    if kind == "repository" {
        fixture.provider.denied.store(true, Ordering::SeqCst);
    } else {
        fixture.provider.moved.store(true, Ordering::SeqCst);
    }
    denial.open();
    let result = probe.await.unwrap();
    assert_eq!(result.is_err(), kind == "repository");
    let result = crate::rt::timeout(Duration::from_secs(2), future)
        .await
        .unwrap();
    assert!(result.is_err());
    for gate in gates {
        gate.open();
    }
    for holder in holders {
        holder.await.unwrap().unwrap();
    }
    assert_eq!(
        fixture.provider.count(CONTENTS),
        0,
        "a retired queued canonical file read reached GitHub"
    );
}
#[tokio::test]
async fn ce_review_queued_repository_retirement_never_sends_the_canonical_read() {
    queued("repository").await;
}
#[tokio::test]
async fn ce_review_queued_head_retirement_never_sends_the_canonical_read() {
    queued("pull").await;
}

async fn streaming(kind: &str) {
    let fixture = Fixture::new().await;
    let context = fixture.context().await;
    let captured = capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .unwrap();
    let reader = reader(&context, &captured);
    let reference = reference();
    let read = crate::source::github_file_with(&reader, &reference, "stream.rs", HEAD_SHA);
    let control = async {
        crate::rt::timeout(
            Duration::from_secs(2),
            fixture.provider.first_chunk.notified(),
        )
        .await
        .unwrap();
        retire(&fixture, &context, kind).await;
        fixture.provider.second_chunk.add_permits(1);
    };
    let (result, ()) = tokio::join!(crate::rt::timeout(Duration::from_secs(2), read), control);
    fixture.provider.last_chunk.add_permits(1);
    let result = result.expect("retired body stayed alive until the withheld final chunk");
    assert!(result.is_err());
    assert_eq!(
        fixture
            .provider
            .count("/repos/reader-a/project/contents/stream.rs"),
        1
    );
}
#[tokio::test]
async fn ce_review_stream_repository_retirement_stops_before_final_chunk() {
    streaming("repository").await;
}
#[tokio::test]
async fn ce_review_stream_head_retirement_stops_before_final_chunk() {
    streaming("pull").await;
}

async fn semantic_denial(kind: &str, partial: bool) {
    let fixture = Fixture::new().await;
    let open = fixture.open().await;
    let mut value = json!({"errors":[{"type":kind,"message":"boundary permission failure"}]});
    if partial {
        value["data"] = json!({"repository":{"pullRequest":null}});
    }
    *fixture.provider.graphql_error.lock().unwrap() = Some(value);
    let response = fixture
        .server
        .get(&Fixture::path(&open["target"], "reviews"), &fixture.session)
        .await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["error"]["code"], "forbidden");
    assert!(body.get("value").is_none());
    assert!(!body.to_string().contains("boundary permission failure"));
}
#[tokio::test]
async fn ce_review_graphql_forbidden_errors_only_is_a_typed_denial() {
    semantic_denial("FORBIDDEN", false).await;
}
#[tokio::test]
async fn ce_review_graphql_not_found_partial_data_is_a_typed_denial() {
    semantic_denial("NOT_FOUND", true).await;
}
#[tokio::test]
async fn ce_review_graphql_permission_denial_retires_a_held_older_reader() {
    let fixture = Fixture::new().await;
    let open = fixture.open().await;
    let held = fixture.provider.gate("overview");
    let request = fixture
        .server
        .client
        .get(format!(
            "{}{}",
            fixture.server.origin,
            Fixture::path(&open["target"], "overview")
        ))
        .bearer_auth(&fixture.session);
    let older = crate::rt::spawn(async move { request.send().await.unwrap() });
    held.arrived().await;
    *fixture.provider.graphql_error.lock().unwrap() =
        Some(json!({"errors":[{"type":"FORBIDDEN","message":"not accessible"}]}));
    let denial = fixture
        .server
        .get(&Fixture::path(&open["target"], "reviews"), &fixture.session)
        .await;
    let status = denial.status();
    held.open();
    let old = older.await.unwrap();
    assert_eq!(status, StatusCode::FORBIDDEN);
    assert_eq!(old.status(), StatusCode::CONFLICT);
    assert!(old.json::<Value>().await.unwrap().get("value").is_none());
}
#[tokio::test]
async fn ce_review_transient_optional_graphql_failure_keeps_real_review_data() {
    let fixture = Fixture::new().await;
    let open = fixture.open().await;
    *fixture.provider.graphql_error.lock().unwrap() =
        Some(json!({"errors":[{"type":"INTERNAL","message":"temporarily unavailable"}]}));
    let response = fixture
        .server
        .get(&Fixture::path(&open["target"], "reviews"), &fixture.session)
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = response.json().await.unwrap();
    assert_eq!(
        body["value"]["threads"][0]["comments"][0]["body"],
        "line note"
    );
    assert!(body["value"]["threads"][0].get("resolved").is_none());
}
#[tokio::test]
async fn ce_review_transient_pull_failure_keeps_older_readers_current() {
    let fixture = Fixture::new().await;
    let context = fixture.context().await;
    let captured = capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .unwrap();
    fixture
        .provider
        .pull_unavailable
        .store(true, Ordering::SeqCst);
    let error = capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .err()
        .unwrap();
    assert!(matches!(error.code, Code::Unavailable));
    reader(&context, &captured)
        .current()
        .expect("a transient PR read failure retired a current reader");
    fixture
        .provider
        .pull_unavailable
        .store(false, Ordering::SeqCst);
    fixture.provider.denied.store(true, Ordering::SeqCst);
    assert!(capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .is_err());
    assert!(reader(&context, &captured).current().is_err());
}
#[tokio::test]
async fn ce_review_non_utf8_raw_bodies_stay_readable() {
    let fixture = Fixture::new().await;
    let context = fixture.context().await;
    let captured = capture(&context, "reader-a", "project", 7, 701, None)
        .await
        .unwrap();
    let reader = reader(&context, &captured);
    let (status, _, text) = reader
        .request(
            Method::GET,
            "/repos/reader-a/project/contents/latin1.txt",
            None,
            "application/vnd.github.raw",
        )
        .await
        .unwrap();
    assert_eq!(status, 200);
    assert_eq!(text, "caf\u{fffd}\n");
    reader.current().unwrap();
}
