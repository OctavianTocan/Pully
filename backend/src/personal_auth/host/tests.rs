use super::*;
use crate::personal_auth::wire;
use axum::http::{header, StatusCode};
use serde_json::{json, Value};
use std::{collections::BTreeMap, time::Duration};

struct Root(PathBuf);
impl Root {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("pully-auth-host-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        Self(root)
    }
    fn path(&self) -> &Path {
        &self.0
    }
}
impl Drop for Root {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
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
        let task = crate::rt::spawn(async move { axum::serve(listener, router).await.unwrap() });
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
    async fn capabilities(&self) -> reqwest::Response {
        self.client
            .get(format!("{}{}", self.origin, wire::CAPABILITIES_PATH))
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
fn config(root: PathBuf) -> PersonalAuthHostConfig {
    PersonalAuthHostConfig::new(
        root,
        OAuthConfig::new(
            "pully-owned-fixture-client".into(),
            "fixture-secret-never-returned".into(),
            &format!("https://pully.example{}", wire::CALLBACK_PATH),
        )
        .unwrap(),
        VaultKeys::new(1, BTreeMap::from([(1, [71; 32])])).unwrap(),
    )
}

#[tokio::test]
async fn absent_host_config_installs_only_disabled_discovery_without_a_service() {
    let (router, service) = PersonalAuthHost::open(None).await.unwrap().into_parts();
    assert!(service.is_none());
    let server = Server::new(router).await;
    let response = server.capabilities().await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()[header::CACHE_CONTROL], "no-store");
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"github":{"enabled":false}})
    );
    for path in [wire::START_PATH, "/api/rpc", "/api/v1/feedback"] {
        assert_eq!(
            server
                .client
                .post(format!("{}{path}", server.origin))
                .json(&json!({}))
                .send()
                .await
                .unwrap()
                .status(),
            StatusCode::NOT_FOUND
        );
    }
}

#[tokio::test]
async fn explicit_config_uses_the_actual_public_read_service_and_no_legacy_state() {
    let directory = Root::new();
    let root = directory.path().join("isolated-personal");
    let untouched_legacy = directory.path().join("legacy");
    std::fs::create_dir(&untouched_legacy).unwrap();
    std::fs::write(untouched_legacy.join("private.json"), "legacy fixture").unwrap();
    let (router, service) = PersonalAuthHost::open(Some(config(root.clone())))
        .await
        .unwrap()
        .into_parts();
    assert!(service.is_some());
    assert!(root.is_dir());
    assert!(!root.join("private.json").exists());
    assert_eq!(
        std::fs::read_to_string(untouched_legacy.join("private.json")).unwrap(),
        "legacy fixture"
    );
    let server = Server::new(router).await;
    assert_eq!(
        server.capabilities().await.json::<Value>().await.unwrap(),
        json!({"github":{"enabled":true}})
    );
    let proof = base64::Engine::encode(&base64::engine::general_purpose::URL_SAFE_NO_PAD, [43; 32]);
    let response = server
        .client
        .post(format!("{}{}", server.origin, wire::START_PATH))
        .json(&json!({"nativeChallenge":wire::native_challenge(&proof).unwrap()}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body: Value = response.json().await.unwrap();
    let url = url::Url::parse(body["authorizeUrl"].as_str().unwrap()).unwrap();
    assert_eq!(url.origin().ascii_serialization(), "https://github.com");
    let params: BTreeMap<_, _> = url.query_pairs().into_owned().collect();
    assert_eq!(params["client_id"], "pully-owned-fixture-client");
    assert_eq!(params["scope"], "offline_access");
    assert_eq!(params["code_challenge_method"], "S256");
    let body = body.to_string();
    assert!(!body.contains("fixture-secret-never-returned"));
    assert!(!body.contains(&proof));
    assert_eq!(
        server
            .client
            .post(format!("{}/api/rpc", server.origin))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn invalid_paths_and_storage_failure_return_one_safe_error_without_fallback() {
    let directory = Root::new();
    let blocked = directory.path().join("private-path-not-for-errors");
    std::fs::write(&blocked, "a file cannot be the personal store").unwrap();
    for root in [
        PathBuf::from("relative-personal"),
        PathBuf::from("/"),
        directory.path().join("missing/../other"),
        blocked,
    ] {
        let error = match PersonalAuthHost::open(Some(config(root))).await {
            Err(error) => error,
            Ok(_) => panic!("invalid explicit storage unexpectedly opened"),
        };
        assert_eq!(
            error.to_string(),
            "Personal GitHub authorization could not be initialized"
        );
        assert!(std::error::Error::source(&error).is_none());
        assert_eq!(format!("{error:?}"), "PersonalAuthHostError");
    }
    assert!(!directory.path().join("other").exists());
}

#[cfg(unix)]
#[tokio::test]
async fn a_storage_symlink_cannot_alias_another_host_store() {
    let directory = Root::new();
    let other = directory.path().join("other-store");
    std::fs::create_dir(&other).unwrap();
    let alias = directory.path().join("personal-alias");
    std::os::unix::fs::symlink(&other, &alias).unwrap();
    assert!(PersonalAuthHost::open(Some(config(alias.join("child"))))
        .await
        .is_err());
    assert_eq!(std::fs::read_dir(other).unwrap().count(), 0);
}
