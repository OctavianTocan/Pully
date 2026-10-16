use super::*;
use serde_json::{json, Value};

// Synthetic, public test fixture. Never used by the production key resolver.
pub(crate) fn claims(email: &str) -> Value {
    json!({"iss":"https://accounts.google.com", "aud":"test-client", "exp":crate::now_ms()/1000 + 300,
        "email":email,"email_verified":true})
}
pub(crate) fn token(claims: &Value) -> String {
    let mut header = jsonwebtoken::Header::new(Algorithm::RS256);
    header.kid = Some("test".into());
    jsonwebtoken::encode(
        &header,
        claims,
        &jsonwebtoken::EncodingKey::from_rsa_der(include_bytes!(
            "test_fixtures/test-only-private.der"
        )),
    )
    .unwrap()
}
pub(crate) async fn install_keys(verifier: &GoogleSignIn) {
    let mut cache = verifier.cache.lock().await;
    cache.keys = parse_keys(include_bytes!("test_fixtures/public-jwks.json")).unwrap();
    cache.expires = Some(Instant::now() + Duration::from_secs(300));
    cache.attempted = Some(Instant::now());
}
#[tokio::test]
async fn google_tokens_verify_signature_claims_and_cached_rotation_without_network() {
    let verifier = GoogleSignIn::new().unwrap();
    install_keys(&verifier).await;
    let good = claims("tocanoctavian@gmail.com");
    assert_eq!(
        verifier
            .email(&token(&good), "test-client")
            .await
            .as_deref(),
        Some("tocanoctavian@gmail.com")
    );
    for (field, bad) in [
        ("iss", json!(["https://accounts.google.com"])),
        ("nbf", json!("bad")),
        ("nbf", json!(null)),
        ("iat", json!("bad")),
        ("iat", json!(null)),
        ("iss", json!("https://attacker.invalid")),
        ("aud", json!("other-client")),
        ("exp", json!(1)),
        ("exp", json!(crate::now_ms() / 1000)),
        ("email_verified", json!(false)),
        ("email_verified", json!("true")),
        ("nbf", json!(crate::now_ms() / 1000 + 100)),
        ("email", json!("")),
    ] {
        let mut changed = good.clone();
        changed[field] = bad;
        assert!(
            verifier
                .email(&token(&changed), "test-client")
                .await
                .is_none(),
            "{field}"
        );
    }
    for field in ["exp", "iss", "aud", "email", "email_verified"] {
        let mut changed = good.clone();
        changed.as_object_mut().unwrap().remove(field);
        assert!(
            verifier
                .email(&token(&changed), "test-client")
                .await
                .is_none(),
            "missing {field}"
        );
    }
    let mut alternate = good.clone();
    alternate["iss"] = json!("accounts.google.com");
    assert!(verifier
        .email(&token(&alternate), "test-client")
        .await
        .is_some());
    let signed = token(&good);
    let mut parts: Vec<_> = signed.split('.').map(str::to_owned).collect();
    parts[1] =
        URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims("changed@octaviantocan.com")).unwrap());
    assert!(verifier
        .email(&parts.join("."), "test-client")
        .await
        .is_none());
    for invalid in ["".into(), "a.b.c".into(), "x".repeat(MAX_TOKEN + 1)] {
        assert!(verifier.email(&invalid, "test-client").await.is_none());
    }
    // Unknown kids and expired caches fail closed during the refresh cooldown.
    assert!(verifier.key("unknown").await.is_none());
    verifier.cache.lock().await.expires = Some(Instant::now());
    assert!(verifier.email(&signed, "test-client").await.is_none());
}
#[test]
fn google_allowlist_is_exact_and_case_normalized() {
    for email in [
        "tocanoctavian@gmail.com",
        " TOCANOCTAVIAN@gmail.com ",
        "a@octaviantocan.com",
    ] {
        assert!(allowed(email));
    }
    for email in [
        "",
        "@octaviantocan.com",
        "other@gmail.com",
        "a@sub.octaviantocan.com",
        "a@octaviantocan.com.evil",
        "tocanoctavian+tag@gmail.com",
    ] {
        assert!(!allowed(email));
    }
}
#[test]
fn google_rejects_unsupported_algorithms_and_jose_extensions() {
    let payload = URL_SAFE_NO_PAD.encode(b"{}");
    for header in [
        json!({"alg":"HS256","kid":"test"}),
        json!({"alg":"none","kid":"test"}),
        json!({"alg":"RS256"}),
        json!({"alg":"RS256","kid":"test","crit":["unknown"]}),
        json!({"alg":"RS256","kid":"test","b64":false}),
    ] {
        let compact = format!(
            "{}.{}.AA",
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(&header).unwrap()),
            payload
        );
        assert!(token_kid(&compact).is_none());
    }
}

#[tokio::test]
async fn google_key_refresh_coalesces_and_recovers_after_failed_fetch() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let verifier = GoogleSignIn::new().unwrap();
    let calls = AtomicUsize::new(0);
    let fetch = || async {
        calls.fetch_add(1, Ordering::SeqCst);
        tokio::task::yield_now().await;
        Some((
            parse_keys(include_bytes!("test_fixtures/public-jwks.json")).unwrap(),
            Duration::from_secs(300),
        ))
    };
    let (one, two) = tokio::join!(
        verifier.resolve("test", fetch),
        verifier.resolve("test", fetch)
    );
    assert!(one.is_some() && two.is_some());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    {
        let mut cache = verifier.cache.lock().await;
        cache.expires = Some(Instant::now());
        cache.attempted = Some(Instant::now() - REFRESH_COOLDOWN);
    }
    assert!(verifier.resolve("test", || async { None }).await.is_none());
    assert!(verifier.resolve("test", fetch).await.is_none());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    verifier.cache.lock().await.attempted = Some(Instant::now() - REFRESH_COOLDOWN);
    assert!(verifier.resolve("test", fetch).await.is_some());
    assert_eq!(calls.load(Ordering::SeqCst), 2);
}
#[test]
fn google_key_document_limits_and_duplicate_ids_fail_closed() {
    assert!(parse_keys(&vec![b' '; MAX_KEYS_BODY + 1]).is_none());
    let fixture: Value =
        serde_json::from_slice(include_bytes!("test_fixtures/public-jwks.json")).unwrap();
    let key = fixture["keys"][0].clone();
    assert!(
        parse_keys(&serde_json::to_vec(&json!({"keys":[key.clone(),key.clone()]})).unwrap())
            .is_none()
    );
    assert!(parse_keys(&serde_json::to_vec(&json!({"keys":vec![key;33]})).unwrap()).is_none());
}
