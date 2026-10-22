use super::*;
mod review_regressions;
use axum::{
    extract::{Request, State},
    http::{HeaderMap, Method},
};
use serde_json::{json, Value};
use std::sync::Mutex;

#[derive(Clone)]
struct Reply {
    status: StatusCode,
    headers: HeaderMap,
    body: String,
}
struct FixtureState {
    seen: Vec<(Method, String, HeaderMap)>,
    private: bool,
    search: Vec<Value>,
    pull_title: String,
    reply: Option<Reply>,
    repository_id: u64,
    pending_pull: Option<Arc<DeferredReply>>,
    pending_repository: Option<Arc<DeferredReply>>,
}
#[derive(Default)]
struct DeferredReply {
    arrived: tokio::sync::Notify,
    release: tokio::sync::Notify,
}
struct Server {
    origin: url::Url,
    task: tokio::task::JoinHandle<()>,
}
impl Server {
    async fn start(router: Router) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin =
            url::Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        Self { origin, task }
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
struct Fixture {
    server: Server,
    state: Arc<Mutex<FixtureState>>,
}
impl Fixture {
    async fn start() -> Self {
        let state = Arc::new(Mutex::new(FixtureState {
            seen: vec![],
            private: false,
            search: vec![repo(false)],
            pull_title: "Public pull request".into(),
            reply: None,
            repository_id: 42,
            pending_pull: None,
            pending_repository: None,
        }));
        let server =
            Server::start(Router::new().fallback(upstream).with_state(state.clone())).await;
        Self { server, state }
    }
    fn client(&self) -> PublicGitHub {
        PublicGitHub::fixture(self.server.origin.clone())
    }
}
fn repo(private: bool) -> Value {
    json!({"id":42,"owner":{"login":"public-owner"},"name":"public-repo","full_name":"public-owner/public-repo","private":private,"description":"A public project","stargazers_count":1000,"language":"Rust"})
}
async fn upstream(State(state): State<Arc<Mutex<FixtureState>>>, request: Request) -> Response {
    let (answer, delayed) = {
        let mut state = state.lock().unwrap();
        let path = request.uri().path().to_owned();
        state.seen.push((
            request.method().clone(),
            request.uri().to_string(),
            request.headers().clone(),
        ));
        let delayed = match path.as_str() {
            "/repos/public-owner/public-repo" => state.pending_repository.take(),
            "/repos/public-owner/public-repo/pulls" => state.pending_pull.take(),
            _ => None,
        };
        let answer = if let Some(reply) = state.reply.clone() {
            (reply.status, reply.headers, reply.body).into_response()
        } else {
            match path.as_str() {
            "/search/repositories" => Json(json!({"items":state.search,"incomplete_results":false})).into_response(),
            "/repos/public-owner/public-repo" => {
                let mut repo = repo(state.private);
                repo["id"] = json!(state.repository_id);
                Json(repo).into_response()
            }
            "/repos/public-owner/public-repo/pulls" => Json(json!([{"number":7,"state":"open","title":state.pull_title,"user":{"login":"reader"},"draft":false,"created_at":"2026-10-20T10:00:00Z","updated_at":"2026-10-21T10:00:00Z","base":{"repo":{"id":state.repository_id}}}])).into_response(),
            _ => StatusCode::NOT_FOUND.into_response(),
        }
        };
        (answer, delayed)
    };
    if let Some(delayed) = delayed {
        delayed.arrived.notify_one();
        delayed.release.notified().await;
    }
    answer
}
fn http() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap()
}

#[tokio::test]
async fn popular_and_search_are_tokenless_public_rest_with_a_separate_bounded_cache() {
    let fixture = Fixture::start().await;
    fixture
        .state
        .lock()
        .unwrap()
        .search
        .push(json!({"private":true,"name":"owner-private-secret"}));
    let client = fixture.client();
    let popular = client.repositories("", 1).await.unwrap();
    assert_eq!(popular.repositories.len(), 1);
    assert_eq!(
        popular.repositories[0].url,
        "https://github.com/public-owner/public-repo"
    );
    assert!(!serde_json::to_string(&popular)
        .unwrap()
        .contains("owner-private-secret"));
    client.repositories("", 1).await.unwrap();
    client.repositories("native mobile", 2).await.unwrap();
    let state = fixture.state.lock().unwrap();
    assert_eq!(state.seen.len(), 2);
    for (method, uri, headers) in &state.seen {
        assert_eq!(*method, Method::GET);
        for name in [
            "authorization",
            "cookie",
            "cf-access-client-id",
            "cf-access-client-secret",
            "x-pully-session",
        ] {
            assert!(!headers.contains_key(name));
        }
        let url = fixture.server.origin.join(uri).unwrap();
        let query: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
        assert!(query["q"].ends_with("is:public archived:false"));
        assert_eq!(query["sort"], "stars");
        assert_eq!(query["per_page"], "20");
    }
    assert!(state.seen[1].1.contains("page=2"));
}

#[tokio::test]
async fn repository_visibility_is_rechecked_before_cached_prs_and_failed_visibility_discards_them()
{
    let fixture = Fixture::start().await;
    let client = fixture.client();
    assert_eq!(
        client
            .pulls("public-owner", "public-repo", 1)
            .await
            .unwrap()
            .pulls[0]
            .title,
        "Public pull request"
    );
    client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 4); // initial/final visibility, list, cached visibility
    fixture.state.lock().unwrap().private = true;
    assert_eq!(
        client
            .pulls("public-owner", "public-repo", 1)
            .await
            .err()
            .unwrap()
            .code,
        ErrorCode::RepositoryUnavailable
    );
    {
        let mut state = fixture.state.lock().unwrap();
        state.private = false;
        state.pull_title = "New public-only snapshot".into();
    }
    let page = client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(page.pulls[0].title, "New public-only snapshot");
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 8);
}

#[tokio::test]
async fn unsafe_queries_paths_and_pagination_are_refused_before_network() {
    let fixture = Fixture::start().await;
    let client = fixture.client();
    for (query, page) in [("x is:private", 1), ("a\nsecret", 1), ("x", 0), ("x", 11)] {
        assert_eq!(
            client.repositories(query, page).await.err().unwrap().code,
            ErrorCode::InvalidRequest
        );
    }
    for (owner, repo) in [
        ("..", "repo"),
        ("owner", "a/b"),
        ("owner", "repo?access_token=secret"),
        ("https:", "repo"),
    ] {
        assert_eq!(
            client.pulls(owner, repo, 1).await.err().unwrap().code,
            ErrorCode::InvalidRequest
        );
    }
    assert!(fixture.state.lock().unwrap().seen.is_empty());
}

#[tokio::test]
async fn redirects_html_and_rate_limits_are_bounded_safe_errors_without_credential_fallback() {
    let fixture = Fixture::start().await;
    let client = fixture.client();
    let mut headers = HeaderMap::new();
    headers.insert(
        header::LOCATION,
        HeaderValue::from_str(&format!(
            "{}stolen?token=private-fixture",
            fixture.server.origin
        ))
        .unwrap(),
    );
    fixture.state.lock().unwrap().reply = Some(Reply {
        status: StatusCode::FOUND,
        headers,
        body: "private-fixture redirect".into(),
    });
    assert_eq!(
        client.repositories("redirect", 1).await.err().unwrap().code,
        ErrorCode::Unavailable
    );
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 1);
    fixture.state.lock().unwrap().reply = Some(Reply {
        status: StatusCode::OK,
        headers: HeaderMap::new(),
        body: "<!doctype html><title>private-fixture</title>".into(),
    });
    let error = client
        .repositories("html", 1)
        .await
        .err()
        .unwrap()
        .into_response();
    assert_eq!(error.status(), StatusCode::BAD_GATEWAY);
    let text = String::from_utf8(
        axum::body::to_bytes(error.into_body(), 8192)
            .await
            .unwrap()
            .to_vec(),
    )
    .unwrap();
    assert!(!text.contains("private-fixture"));
    assert!(!text.contains("html>"));
    let mut headers = HeaderMap::new();
    headers.insert("x-ratelimit-remaining", HeaderValue::from_static("0"));
    headers.insert(header::RETRY_AFTER, HeaderValue::from_static("120"));
    fixture.state.lock().unwrap().reply = Some(Reply {
        status: StatusCode::FORBIDDEN,
        headers,
        body: "private-fixture quota".into(),
    });
    let error = client.repositories("limited", 1).await.err().unwrap();
    assert_eq!(error.code, ErrorCode::RateLimited);
    assert_eq!(error.retry, Some(120));
    assert_eq!(
        client.repositories("retry", 1).await.err().unwrap().code,
        ErrorCode::RateLimited
    );
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 3);
}

#[tokio::test]
async fn oversize_and_wrongly_typed_upstream_pages_never_enter_public_cache() {
    let fixture = Fixture::start().await;
    let client = fixture.client();
    fixture.state.lock().unwrap().reply = Some(Reply {
        status: StatusCode::OK,
        headers: HeaderMap::new(),
        body: " ".repeat(2 * 1024 * 1024 + 1),
    });
    assert_eq!(
        client.repositories("large", 1).await.err().unwrap().code,
        ErrorCode::InvalidResponse
    );
    fixture.state.lock().unwrap().reply = Some(Reply {
        status: StatusCode::OK,
        headers: HeaderMap::new(),
        body: r#"{"items":[{"private":"false"}],"incomplete_results":false}"#.into(),
    });
    assert_eq!(
        client.repositories("large", 1).await.err().unwrap().code,
        ErrorCode::InvalidResponse
    );
    fixture.state.lock().unwrap().reply = None;
    assert_eq!(
        client
            .repositories("large", 1)
            .await
            .unwrap()
            .repositories
            .len(),
        1
    );
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 3);
}

#[tokio::test]
async fn public_router_has_only_typed_gets_and_never_forwards_caller_authority() {
    let fixture = Fixture::start().await;
    let api = Server::start(routes(fixture.client())).await;
    let http = http();
    for path in [
        "api/rpc",
        "api/web-rpc",
        "api/v1/posted",
        "api/update/restart",
        "__connection.json",
        "api/public/v1/credentials",
    ] {
        let response = http
            .post(api.origin.join(path).unwrap())
            .json(&json!({"name":"my-pulls","args":{}}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
    let capabilities: Value = http
        .get(api.origin.join("api/public/v1/capabilities").unwrap())
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(
        capabilities,
        json!({"apiVersion":1,"publicBrowsing":true,"githubSignIn":"notConfigured"})
    );
    let response = http
        .get(api.origin.join("api/public/v1/repositories").unwrap())
        .bearer_auth("caller-private-token")
        .header("X-Pully-Session", "caller-private-session")
        .header("CF-Access-Client-Secret", "caller-private-access")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
    assert_eq!(
        response.json::<Value>().await.unwrap()["repositories"][0]["fullName"],
        "public-owner/public-repo"
    );
    for (_, _, headers) in &fixture.state.lock().unwrap().seen {
        for name in [
            "authorization",
            "x-pully-session",
            "cf-access-client-secret",
        ] {
            assert!(!headers.contains_key(name));
        }
    }
    assert_eq!(
        http.post(api.origin.join("api/public/v1/repositories").unwrap())
            .body("{}")
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::METHOD_NOT_ALLOWED
    );
}

#[tokio::test]
async fn hourly_guest_budget_refuses_more_network_work_without_using_an_operator_token() {
    let fixture = Fixture::start().await;
    let client = fixture.client();
    for n in 0..60 {
        client.repositories(&format!("query{n}"), 1).await.unwrap();
    }
    assert_eq!(
        client.repositories("one-more", 1).await.err().unwrap().code,
        ErrorCode::RateLimited
    );
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 60);
    // An already public search page remains useful without spending another request.
    client.repositories("query0", 1).await.unwrap();
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 60);
}
#[tokio::test]
async fn a_confirmed_visibility_denial_retires_an_older_pending_pr_response_and_cache_write() {
    let fixture = Fixture::start().await;
    let client = Arc::new(fixture.client());
    let pending = Arc::new(DeferredReply::default());
    fixture.state.lock().unwrap().pending_pull = Some(pending.clone());
    let old = tokio::spawn({
        let client = client.clone();
        async move { client.pulls("public-owner", "public-repo", 1).await }
    });
    tokio::time::timeout(
        std::time::Duration::from_secs(2),
        pending.arrived.notified(),
    )
    .await
    .unwrap();
    fixture.state.lock().unwrap().private = true;
    assert_eq!(
        client
            .pulls("public-owner", "public-repo", 1)
            .await
            .err()
            .unwrap()
            .code,
        ErrorCode::RepositoryUnavailable
    );
    pending.release.notify_one();
    assert_eq!(
        old.await.unwrap().err().unwrap().code,
        ErrorCode::RepositoryUnavailable
    );
    {
        let mut state = fixture.state.lock().unwrap();
        state.private = false;
        state.pull_title = "Fresh public snapshot after denial".into();
    }
    let current = client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(current.pulls[0].title, "Fresh public snapshot after denial");
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 6);
}

#[tokio::test]
async fn name_reuse_never_relabels_the_previous_repositorys_cached_pull_requests() {
    let fixture = Fixture::start().await;
    let client = fixture.client();
    assert_eq!(
        client
            .pulls("public-owner", "public-repo", 1)
            .await
            .unwrap()
            .repository
            .id,
        42
    );
    {
        let mut state = fixture.state.lock().unwrap();
        state.repository_id = 99;
        state.pull_title = "Replacement repository change".into();
    }
    let replaced = client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(replaced.repository.id, 99);
    assert_eq!(replaced.pulls[0].title, "Replacement repository change");
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 6);
}

#[tokio::test]
async fn an_older_visibility_response_cannot_roll_back_a_newer_confirmed_repository_id() {
    let fixture = Fixture::start().await;
    let client = Arc::new(fixture.client());
    let pending = Arc::new(DeferredReply::default());
    fixture.state.lock().unwrap().pending_repository = Some(pending.clone());
    let old = tokio::spawn({
        let client = client.clone();
        async move { client.pulls("public-owner", "public-repo", 1).await }
    });
    tokio::time::timeout(
        std::time::Duration::from_secs(2),
        pending.arrived.notified(),
    )
    .await
    .unwrap();
    {
        let mut state = fixture.state.lock().unwrap();
        state.repository_id = 99;
        state.pull_title = "New repository only".into();
    }
    assert_eq!(
        client
            .pulls("public-owner", "public-repo", 1)
            .await
            .unwrap()
            .repository
            .id,
        99
    );
    pending.release.notify_one();
    assert_eq!(
        old.await.unwrap().err().unwrap().code,
        ErrorCode::RepositoryUnavailable
    );
    let current = client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(current.repository.id, 99);
    assert_eq!(current.pulls[0].title, "New repository only");
    assert_eq!(fixture.state.lock().unwrap().seen.len(), 5);
}
