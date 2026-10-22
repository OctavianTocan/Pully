use super::*;
use axum::{
    extract::State,
    routing::{get, post},
    Form, Json, Router,
};
use jsonwebtoken::{EncodingKey, Header};
use ring::{
    rand::SystemRandom,
    signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_ASN1_SIGNING},
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

const NOW: u64 = 1_800_000_000;
const CLIENT: &str = "oaiapp_fixture_owned";

fn config() -> SiwcHostConfig {
    SiwcHostConfig::user_operated(
        "urn:uuid:fixture-host".into(),
        url::Url::parse("http://127.0.0.1:1455/auth/callback").unwrap(),
    )
    .unwrap()
}
fn query(url: &url::Url) -> HashMap<String, String> {
    url.query_pairs()
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect()
}
fn callback(attempt: &SiwcAttempt, client: Option<&str>) -> url::Url {
    let mut url = attempt.callback.clone();
    url.query_pairs_mut()
        .append_pair("state", &attempt.state)
        .append_pair("code", "fixture-code");
    if let Some(client) = client {
        url.query_pairs_mut().append_pair("client_id", client);
    }
    url
}

#[test]
fn initial_authorization_uses_official_public_registration_and_independent_pkce() {
    let first = SiwcAttempt::new(&config(), None, NOW).unwrap();
    let second = SiwcAttempt::new(&config(), None, NOW).unwrap();
    let url = first.authorization_url().unwrap();
    assert_eq!(url.origin().ascii_serialization(), ISSUER);
    assert_eq!(url.path(), AUTHORIZE_PATH);
    let q = query(&url);
    assert_eq!(q["client_id"], DYNAMIC_CLIENT);
    assert_eq!(q["agent_name_hint"], "Pully");
    assert_eq!(q["ext_agent_host_id"], "urn:uuid:fixture-host");
    assert_eq!(q["resource"], RESOURCE);
    assert_eq!(q["scope"], REQUESTED_SCOPES);
    assert_eq!(q["code_challenge_method"], "S256");
    assert_eq!(
        q["code_challenge"],
        URL_SAFE_NO_PAD.encode(Sha256::digest(first.verifier.as_bytes()))
    );
    assert_ne!(first.state, second.state);
    assert_ne!(first.nonce, first.verifier);
    assert!(!url.as_str().contains(&first.verifier));
    assert!(!q.contains_key("client_secret"));
}

#[test]
fn returning_registration_retains_issued_client_and_rejects_callback_replacement() {
    let attempt = SiwcAttempt::new(&config(), Some((CLIENT, "subject-a")), NOW).unwrap();
    let q = query(&attempt.authorization_url().unwrap());
    assert_eq!(q["client_id"], CLIENT);
    assert!(!q.contains_key("agent_name_hint"));
    let returned = callback(&attempt, None);
    let code = attempt.consume(&returned, NOW).unwrap();
    assert_eq!(code.client_id, CLIENT);
    assert_eq!(code.expected_subject.as_deref(), Some("subject-a"));
    let attempt = SiwcAttempt::new(&config(), Some((CLIENT, "subject-a")), NOW).unwrap();
    let returned = callback(&attempt, Some("oaiapp_other"));
    assert!(attempt.consume(&returned, NOW).is_err());
}

#[test]
fn callback_rejects_expiry_wrong_state_duplicates_denial_and_unissued_client() {
    for mode in [
        "expired",
        "wrongState",
        "duplicate",
        "denied",
        "missingClient",
        "dynamicClient",
    ] {
        let attempt = SiwcAttempt::new(&config(), None, NOW).unwrap();
        let mut returned = callback(&attempt, Some(CLIENT));
        match mode {
            "wrongState" => {
                returned.set_query(Some("state=wrong&code=fixture&client_id=oaiapp_fixture"))
            }
            "duplicate" => {
                returned
                    .query_pairs_mut()
                    .append_pair("state", &attempt.state);
            }
            "denied" => {
                returned
                    .query_pairs_mut()
                    .append_pair("error", "access_denied");
            }
            "missingClient" => {
                returned = callback(&attempt, None);
            }
            "dynamicClient" => {
                returned = callback(&attempt, Some(DYNAMIC_CLIENT));
            }
            _ => {}
        }
        assert!(
            attempt
                .consume(&returned, if mode == "expired" { NOW + 600 } else { NOW })
                .is_err(),
            "{mode}"
        );
    }
}

#[test]
fn loopback_contract_rejects_hosted_localhost_and_altered_callback_paths() {
    for invalid in [
        "https://pully.example/auth/callback",
        "http://localhost:1455/auth/callback",
        "http://127.0.0.1:1455/callback",
        "http://user@127.0.0.1:1455/auth/callback",
        "http://127.0.0.1:1455/auth/callback?q=x",
    ] {
        assert!(
            SiwcHostConfig::user_operated("host".into(), url::Url::parse(invalid).unwrap())
                .is_err()
        );
    }
}

struct Upstream {
    response: Mutex<Value>,
    jwks: Value,
    requests: Mutex<Vec<HashMap<String, String>>>,
}
struct Fixture {
    provider: OpenAiSiwc,
    state: Arc<Upstream>,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Fixture {
    async fn new(claims: Value) -> Self {
        let random = SystemRandom::new();
        let pkcs8 = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, &random).unwrap();
        let pair =
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_ASN1_SIGNING, pkcs8.as_ref(), &random)
                .unwrap();
        let public = pair.public_key().as_ref();
        let jwks = json!({"keys":[{"kty":"EC","crv":"P-256","alg":"ES256","use":"sig","kid":"fixture-signing","x":URL_SAFE_NO_PAD.encode(&public[1..33]),"y":URL_SAFE_NO_PAD.encode(&public[33..65])}]});
        let mut header = Header::new(jsonwebtoken::Algorithm::ES256);
        header.kid = Some("fixture-signing".into());
        let signed =
            jsonwebtoken::encode(&header, &claims, &EncodingKey::from_ec_der(pkcs8.as_ref()))
                .unwrap();
        let state = Arc::new(Upstream {
            response: Mutex::new(
                json!({"access_token":"fixture-access", "refresh_token":"fixture-refresh", "id_token":signed,"token_type":"Bearer","expires_in":3600,"scope":REQUESTED_SCOPES,"earliest_refresh_at":NOW+3000}),
            ),
            jwks,
            requests: Default::default(),
        });
        let routes = Router::new()
            .route(DISCOVERY_PATH,get(|| async { Json(json!({"issuer":ISSUER,"authorization_endpoint":format!("{ISSUER}{AUTHORIZE_PATH}"),"token_endpoint":format!("{ISSUER}{TOKEN_PATH}"),"jwks_uri":format!("{ISSUER}{JWKS_PATH}"),"revocation_endpoint":format!("{ISSUER}/oauth/revoke")})) }))
            .route(JWKS_PATH,get(|State(state):State<Arc<Upstream>>| async move {Json(state.jwks.clone())}))
            .route(TOKEN_PATH,post(|State(state):State<Arc<Upstream>>,Form(fields):Form<HashMap<String,String>>| async move {
                state.requests.lock().unwrap().push(fields);
                Json(state.response.lock().unwrap().clone())
            }))
            .route("/oauth/revoke",post(|State(state):State<Arc<Upstream>>,Form(fields):Form<HashMap<String,String>>| async move {
                state.requests.lock().unwrap().push(fields);
                axum::http::StatusCode::OK
            }))
            .with_state(state.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin =
            url::Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let task = tokio::spawn(async move {
            axum::serve(listener, routes).await.unwrap();
        });
        Self {
            provider: OpenAiSiwc::fixture(origin),
            state,
            task,
        }
    }
}
fn claims(nonce: &str) -> Value {
    json!({"iss":ISSUER,"aud":CLIENT,"sub":"subject-a","iat":NOW,"exp":NOW+3600,"nonce":nonce,"email":"same@example.invalid"})
}

#[tokio::test]
async fn actual_loopback_exchange_verifies_signature_nonce_identity_and_granted_plan() {
    let attempt = SiwcAttempt::new(&config(), None, NOW).unwrap();
    let fixture = Fixture::new(claims(&attempt.nonce)).await;
    let callback = callback(&attempt, Some(CLIENT));
    let code = attempt.consume(&callback, NOW).unwrap();
    let verifier = code.verifier.clone();
    let acquired = fixture.provider.exchange(code, NOW).await.unwrap();
    let result = fixture
        .provider
        .verify_exchange(acquired, NOW)
        .await
        .unwrap();
    assert_eq!(result.identity.subject, "subject-a");
    assert_eq!(result.identity.client_id, CLIENT);
    assert!(result.tokens.plan_enabled());
    let fields = fixture.state.requests.lock().unwrap()[0].clone();
    assert_eq!(fields["client_id"], CLIENT);
    assert_ne!(fields["client_id"], DYNAMIC_CLIENT);
    assert_eq!(fields["code_verifier"], verifier);
    assert_eq!(
        fields["redirect_uri"],
        "http://127.0.0.1:1455/auth/callback"
    );
    assert_eq!(fields["resource"], RESOURCE);
    assert!(!fields.contains_key("client_secret"));
}

#[tokio::test]
async fn valid_signed_identity_without_direct_scope_does_not_grant_plan_usage() {
    let attempt = SiwcAttempt::new(&config(), None, NOW).unwrap();
    let fixture = Fixture::new(claims(&attempt.nonce)).await;
    fixture.state.response.lock().unwrap()["scope"] = json!("openid profile email");
    let callback = callback(&attempt, Some(CLIENT));
    let acquired = fixture
        .provider
        .exchange(attempt.consume(&callback, NOW).unwrap(), NOW)
        .await
        .unwrap();
    let result = fixture
        .provider
        .verify_exchange(acquired, NOW)
        .await
        .unwrap();
    assert!(!result.tokens.plan_enabled());
}

#[tokio::test]
async fn signature_claim_checks_reject_wrong_nonce_subject_audience_issuer_and_time() {
    for mode in [
        "nonce",
        "subject",
        "audience",
        "issuer",
        "expired",
        "future",
        "notBefore",
        "signature",
    ] {
        let attempt = SiwcAttempt::new(&config(), Some((CLIENT, "subject-a")), NOW).unwrap();
        let mut data = claims(&attempt.nonce);
        match mode {
            "nonce" => data["nonce"] = json!("different"),
            "subject" => data["sub"] = json!("subject-b"),
            "audience" => data["aud"] = json!("oaiapp_other"),
            "issuer" => data["iss"] = json!("https://other.example"),
            "expired" => data["exp"] = json!(NOW),
            "future" => data["iat"] = json!(NOW + 60),
            "notBefore" => data["nbf"] = json!(NOW + 60),
            _ => {}
        }
        let fixture = Fixture::new(data).await;
        if mode == "signature" {
            let mut response = fixture.state.response.lock().unwrap();
            let token = response["id_token"].as_str().unwrap();
            let mut pieces = token.split('.').map(str::to_owned).collect::<Vec<_>>();
            let mut signature = URL_SAFE_NO_PAD.decode(&pieces[2]).unwrap();
            signature[0] ^= 1;
            pieces[2] = URL_SAFE_NO_PAD.encode(signature);
            response["id_token"] = json!(pieces.join("."));
        }
        let callback = callback(&attempt, None);
        let acquired = fixture
            .provider
            .exchange(attempt.consume(&callback, NOW).unwrap(), NOW)
            .await
            .unwrap();
        assert!(
            fixture
                .provider
                .verify_exchange(acquired, NOW)
                .await
                .is_err(),
            "{mode}"
        );
    }
}

#[tokio::test]
async fn refresh_and_revoke_use_the_captured_issued_registration_not_dynamic_client() {
    let attempt = SiwcAttempt::new(&config(), None, NOW).unwrap();
    let fixture = Fixture::new(claims(&attempt.nonce)).await;
    let callback = callback(&attempt, Some(CLIENT));
    let acquired = fixture
        .provider
        .exchange(attempt.consume(&callback, NOW).unwrap(), NOW)
        .await
        .unwrap();
    let result = fixture
        .provider
        .verify_exchange(acquired, NOW)
        .await
        .unwrap();
    fixture.state.response.lock().unwrap()["refresh_token"] = json!("fixture-rotated-refresh");
    let rotated = fixture
        .provider
        .refresh(&result.identity, &result.tokens, NOW + 60)
        .await
        .unwrap();
    assert_eq!(
        rotated.refresh_token.as_deref(),
        Some("fixture-rotated-refresh")
    );
    fixture
        .provider
        .verify_refresh(&result.identity, &rotated, NOW + 60)
        .await
        .unwrap();
    fixture
        .provider
        .revoke(&result.identity, &rotated)
        .await
        .unwrap();
    let calls = fixture.state.requests.lock().unwrap();
    assert_eq!(calls[1]["grant_type"], "refresh_token");
    assert_eq!(calls[1]["client_id"], CLIENT);
    assert_eq!(calls[1]["refresh_token"], "fixture-refresh");
    assert!(!calls[1].contains_key("scope"));
    assert_eq!(calls[2]["token"], "fixture-rotated-refresh");
    assert_eq!(calls[2]["client_id"], CLIENT);
    assert_eq!(calls[2]["token_type_hint"], "refresh_token");
}
