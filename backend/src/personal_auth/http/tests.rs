use super::*;
use crate::personal_auth::{flow::tests::Harness, vault::random_secret};
use serde_json::{json, Value};
use std::time::Duration;
use url::Url;

struct Server {
    origin: String,
    client: reqwest::Client,
    task: crate::rt::JoinHandle<()>,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Server {
    async fn new(router: Router) -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("personal auth loopback fixture must bind");
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let task = crate::rt::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let client = reqwest::Client::builder()
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
    async fn post(&self, path: &str, body: Value) -> reqwest::Response {
        self.client
            .post(format!("{}{path}", self.origin))
            .json(&body)
            .send()
            .await
            .unwrap()
    }
    async fn get(&self, path: &str) -> reqwest::Response {
        self.client
            .get(format!("{}{path}", self.origin))
            .send()
            .await
            .unwrap()
    }
    async fn start(&self) -> (Value, String) {
        let proof = random_secret().unwrap();
        let response = self
            .post(
                wire::START_PATH,
                json!({"nativeChallenge":wire::native_challenge(&proof).unwrap()}),
            )
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        (response.json().await.unwrap(), proof)
    }
    async fn callback(&self, start: &Value) -> reqwest::Response {
        let authorize = Url::parse(start["authorizeUrl"].as_str().unwrap()).unwrap();
        let state = authorize
            .query_pairs()
            .find(|(key, _)| key == "state")
            .unwrap()
            .1
            .into_owned();
        self.client
            .get(format!("{}{}", self.origin, wire::CALLBACK_PATH))
            .query(&[("state", state.as_str()), ("code", "fixture-code")])
            .send()
            .await
            .unwrap()
    }
}
fn owned(start: &Value, proof: &str) -> Value {
    json!({"attemptId":start["attemptId"],"nativeProof":proof})
}

#[tokio::test]
async fn disabled_discovery_has_no_auth_or_legacy_methods() {
    let server = Server::new(unconfigured_router()).await;
    let response = server.get(wire::CAPABILITIES_PATH).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"github":{"enabled":false}})
    );
    for path in [wire::START_PATH, "/api/rpc", "/api/v1/feedback"] {
        assert_eq!(
            server.post(path, json!({})).await.status(),
            StatusCode::NOT_FOUND
        );
    }
}

#[tokio::test]
async fn real_http_browser_callback_native_claim_and_session_sign_out_use_the_actual_service_and_vault(
) {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service.clone())).await;
    assert_eq!(
        server
            .get(wire::CAPABILITIES_PATH)
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"github":{"enabled":true}})
    );
    let (start, proof) = server.start().await;
    let authorize = Url::parse(start["authorizeUrl"].as_str().unwrap()).unwrap();
    assert_eq!(
        authorize.origin().ascii_serialization(),
        "https://github.com"
    );
    assert!(!authorize.as_str().contains(&proof));
    let callback = server.callback(&start).await;
    assert_eq!(callback.status(), StatusCode::OK);
    assert_eq!(callback.headers()["referrer-policy"], "no-referrer");
    assert_eq!(callback.headers()[header::CACHE_CONTROL], "no-store");
    assert!(callback.headers()["content-security-policy"]
        .to_str()
        .unwrap()
        .contains("default-src 'none'"));
    let page = callback.text().await.unwrap();
    for private in [
        &proof,
        "fixture-code",
        "fixture-access",
        "fixture-refresh",
        "fixture-server-secret",
    ] {
        assert!(!page.contains(private));
    }
    assert_eq!(
        server
            .post(wire::STATUS_PATH, owned(&start, &proof))
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"phase":"ready"})
    );
    let claim = server.post(wire::CLAIM_PATH, owned(&start, &proof)).await;
    assert_eq!(claim.status(), StatusCode::OK);
    assert_eq!(claim.headers()[header::CACHE_CONTROL], "no-store");
    let claimed: Value = claim.json().await.unwrap();
    let session = claimed["session"].as_str().unwrap();
    assert_eq!(claimed["status"]["githubId"], 42);
    assert_eq!(claimed["status"]["authority"], "publicRead");
    assert_eq!(claimed["status"]["sessionExpiresAt"], start["expiresAt"]);
    assert!(
        !claimed.to_string().contains("fixture-access")
            && !claimed.to_string().contains("fixture-refresh")
    );
    let profile = server
        .client
        .get(format!("{}{}", server.origin, wire::SESSION_PATH))
        .bearer_auth(session)
        .send()
        .await
        .unwrap();
    assert_eq!(profile.status(), StatusCode::OK);
    let confirmed: Value = profile.json().await.unwrap();
    assert_eq!(confirmed["githubId"], 42);
    assert_eq!(confirmed["sessionExpiresAt"], 100 + 30 * 24 * 60 * 60);
    let stored = h
        .store
        .get("personal-auth-v1:state")
        .unwrap()
        .unwrap()
        .to_string();
    assert!(
        !stored.contains(session)
            && !stored.contains("fixture-access")
            && !stored.contains("fixture-refresh")
    );
    let logout = server
        .client
        .post(format!("{}{}", server.origin, wire::SIGN_OUT_PATH))
        .bearer_auth(session)
        .send()
        .await
        .unwrap();
    assert_eq!(logout.status(), StatusCode::OK);
    let rejected = server
        .client
        .get(format!("{}{}", server.origin, wire::SESSION_PATH))
        .bearer_auth(session)
        .send()
        .await
        .unwrap();
    assert_eq!(rejected.status(), StatusCode::UNAUTHORIZED);
    assert!(!rejected.text().await.unwrap().contains(session));
}

#[tokio::test]
async fn wrong_native_proof_and_repeated_claims_do_not_consume_or_reissue_the_legitimate_session() {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service)).await;
    let (start, proof) = server.start().await;
    assert_eq!(server.callback(&start).await.status(), StatusCode::OK);
    let wrong = random_secret().unwrap();
    assert_eq!(
        server
            .post(wire::CLAIM_PATH, owned(&start, &wrong))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        server
            .post(wire::CLAIM_PATH, owned(&start, &proof))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        server
            .post(wire::CLAIM_PATH, owned(&start, &proof))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn malformed_oversized_or_extra_config_bodies_never_echo_the_native_secret() {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service)).await;
    let secret = "fixture-native-secret";
    for value in [
        json!({"nativeChallenge":secret,"clientSecret":secret}),
        json!({"nativeChallenge":secret,"fixture-native-secret":secret}),
        json!({"nativeChallenge":secret.repeat(MAX_BODY)}),
    ] {
        let response = server.post(wire::START_PATH, value).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = response.text().await.unwrap();
        assert!(!body.contains(secret));
        assert!(body.contains("invalidRequest"));
    }
    let response = server
        .client
        .post(format!("{}{}", server.origin, wire::START_PATH))
        .header(header::CONTENT_TYPE, "text/plain")
        .body(secret)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert!(!response.text().await.unwrap().contains(secret));
}

#[tokio::test]
async fn legacy_access_sessions_cookies_or_forwarded_credentials_cannot_select_personal_identity() {
    let h = Harness::new();
    h.store
        .set("my-pulls:open", &json!({"private":"owner-private-cache"}))
        .unwrap();
    let server = Server::new(configured_router(h.service)).await;
    let challenge = wire::native_challenge(&random_secret().unwrap()).unwrap();
    for header in [
        "x-pully-session",
        "cf-access-client-id",
        "cf-access-client-secret",
        "cf-access-jwt-assertion",
        "cookie",
        "authorization",
        "x-api-key",
        "x-forwarded-authorization",
    ] {
        let response = server
            .client
            .post(format!("{}{}", server.origin, wire::START_PATH))
            .header(header, "fixture-operator-secret")
            .json(&json!({"nativeChallenge":challenge}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let body = response.text().await.unwrap();
        assert!(!body.contains("fixture-operator-secret") && !body.contains("owner-private-cache"));
    }
    for path in [
        "/api/rpc",
        "/api/v1/feedback",
        "/api/public/v1/repositories",
        "/api/personal/v1/admin",
    ] {
        assert_eq!(
            server
                .post(
                    path,
                    json!({"name":"storage-get","args":{"key":"my-pulls:open"}})
                )
                .await
                .status(),
            StatusCode::NOT_FOUND
        );
    }
}

#[tokio::test]
async fn callback_replay_and_duplicate_state_fail_without_cookie_authority_or_reflection() {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service)).await;
    let (start, _) = server.start().await;
    let authorize = Url::parse(start["authorizeUrl"].as_str().unwrap()).unwrap();
    let state = authorize
        .query_pairs()
        .find(|(key, _)| key == "state")
        .unwrap()
        .1
        .into_owned();
    let duplicated = server
        .client
        .get(format!("{}{}", server.origin, wire::CALLBACK_PATH))
        .query(&[
            ("state", state.as_str()),
            ("state", state.as_str()),
            ("code", "fixture-code"),
        ])
        .send()
        .await
        .unwrap();
    assert_eq!(duplicated.status(), StatusCode::BAD_REQUEST);
    assert!(!duplicated.text().await.unwrap().contains(&state));
    let valid = server
        .client
        .get(format!("{}{}", server.origin, wire::CALLBACK_PATH))
        .header(header::COOKIE, "legacy=fixture-cookie-secret")
        .query(&[("state", state.as_str()), ("code", "fixture-code")])
        .send()
        .await
        .unwrap();
    assert_eq!(valid.status(), StatusCode::OK);
    let body = valid.text().await.unwrap();
    assert!(!body.contains(&state) && !body.contains("fixture-cookie-secret"));
    assert_eq!(
        server.callback(&start).await.status(),
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn personal_session_requires_its_bearer_and_proof_is_rejected_in_native_urls() {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service)).await;
    assert_eq!(
        server.get(wire::SESSION_PATH).await.status(),
        StatusCode::UNAUTHORIZED
    );
    let wrong = server
        .client
        .get(format!("{}{}", server.origin, wire::SESSION_PATH))
        .bearer_auth("fixture-github-access")
        .send()
        .await
        .unwrap();
    assert_eq!(wrong.status(), StatusCode::UNAUTHORIZED);
    assert!(!wrong
        .text()
        .await
        .unwrap()
        .contains("fixture-github-access"));
    let (start, proof) = server.start().await;
    let response = server
        .post(
            &format!("{}?nativeProof={}", wire::STATUS_PATH, proof),
            owned(&start, &proof),
        )
        .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert!(!response.text().await.unwrap().contains(&proof));
    assert_eq!(
        server
            .post(wire::STATUS_PATH, owned(&start, &proof))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        server
            .post(wire::STATUS_PATH, owned(&start, &proof))
            .await
            .status(),
        StatusCode::TOO_MANY_REQUESTS
    );
}

#[tokio::test]
async fn native_http_cancellation_retires_browser_callback_and_exposes_only_safe_cancelled_state() {
    let h = Harness::new();
    let server = Server::new(configured_router(h.service)).await;
    let (start, proof) = server.start().await;
    assert_eq!(
        server
            .post(wire::CANCEL_PATH, owned(&start, &proof))
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"cancelled":true})
    );
    assert_eq!(
        server.callback(&start).await.status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        server
            .post(wire::STATUS_PATH, owned(&start, &proof))
            .await
            .json::<Value>()
            .await
            .unwrap(),
        json!({"phase":"cancelled"})
    );
    assert!(h.store.get("personal-auth-v1:state").unwrap().is_none());
}
