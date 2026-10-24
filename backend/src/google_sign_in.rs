//! Google ID tokens are verified locally using Google's fixed, cached public keys.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use jsonwebtoken::{decode, decode_header, Algorithm, DecodingKey, Validation};
use serde::Deserialize;
use std::{
    collections::HashMap,
    time::Duration,
};
use web_time::Instant;
use tokio::sync::Mutex;

const KEYS_URL: &str = "https://www.googleapis.com/oauth2/v3/certs";
const MAX_TOKEN: usize = 16 * 1024;
const MAX_KEYS_BODY: usize = 64 * 1024;
const REFRESH_COOLDOWN: Duration = Duration::from_secs(30);

pub fn allowed(email: &str) -> bool {
    let who = email.trim().to_lowercase();
    who == "tocanoctavian@gmail.com"
        || (who.ends_with("@octaviantocan.com") && who.len() > "@octaviantocan.com".len())
}
pub fn client_id() -> Option<String> {
    crate::rt::env::var("PULLY_GOOGLE_CLIENT_ID")
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
}
#[derive(Default)]
struct Cache {
    keys: HashMap<String, DecodingKey>,
    expires: Option<Instant>,
    attempted: Option<Instant>,
}
pub struct GoogleSignIn {
    http: reqwest::Client,
    cache: Mutex<Cache>,
}
#[derive(Deserialize)]
struct KeySet {
    keys: Vec<Key>,
}
#[derive(Deserialize)]
struct Key {
    kid: String,
    kty: String,
    alg: Option<String>,
    #[serde(rename = "use")]
    usage: Option<String>,
    n: String,
    e: String,
}
#[derive(Clone, Deserialize)]
struct Claims {
    #[serde(rename = "iss")]
    _issuer: String,
    #[serde(default, deserialize_with = "optional_number")]
    nbf: Option<f64>,
    #[serde(rename = "iat", default, deserialize_with = "optional_number")]
    _issued_at: Option<f64>,
    email: String,
    email_verified: bool,
    exp: u64,
}
// Present numeric dates must be numbers: Option alone would accept JSON null.
fn optional_number<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<f64>, D::Error> {
    f64::deserialize(deserializer).map(Some)
}
impl GoogleSignIn {
    pub fn new() -> anyhow::Result<Self> {
        Ok(Self {
            http: crate::net::client()
                .https_only(true)
                .connect_timeout(Duration::from_secs(3))
                .timeout(Duration::from_secs(5))
                .build()?,
            cache: Mutex::new(Cache::default()),
        })
    }
    /// @cc [owner:OctavianTocan,label:security] rust-google-token-verification
    /// Google sign-in MUST verify RS256 signatures using only Google's fixed HTTPS
    /// key endpoint, require issuer, audience, expiration and verified email, and
    /// bound token size, key downloads, refresh frequency and network waits
    /// (Tavi, 16 Oct 16:52; Rust port request, 16 Oct 14:57).
    pub async fn email(&self, token: &str, client_id: &str) -> Option<String> {
        if client_id.is_empty() {
            return None;
        }
        let kid = token_kid(token)?;
        let key = crate::rt::timeout(Duration::from_secs(7), self.key(&kid))
            .await
            .ok()??;
        verify(token, client_id, &key)
    }
    async fn key(&self, kid: &str) -> Option<DecodingKey> {
        self.resolve(kid, || self.fetch()).await
    }
    async fn resolve<F, Fut>(&self, kid: &str, fetch: F) -> Option<DecodingKey>
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = Option<(HashMap<String, DecodingKey>, Duration)>>,
    {
        // Hold the async mutex through refresh to coalesce concurrent requests.
        let mut cache = self.cache.lock().await;
        let now = Instant::now();
        if cache.expires.is_some_and(|expires| expires > now) {
            if let Some(key) = cache.keys.get(kid) {
                return Some(key.clone());
            }
        }
        if cache
            .attempted
            .is_some_and(|last| now.duration_since(last) < REFRESH_COOLDOWN)
        {
            return None;
        }
        cache.attempted = Some(now);
        let (keys, lifetime) = fetch().await?;
        cache.keys = keys;
        cache.expires = Some(Instant::now() + lifetime);
        cache.keys.get(kid).cloned()
    }
    async fn fetch(&self) -> Option<(HashMap<String, DecodingKey>, Duration)> {
        let mut response = crate::net::send(self.http.get(KEYS_URL))
            .await
            .ok()?
            .error_for_status()
            .ok()?;
        if response
            .content_length()
            .is_some_and(|n| n > MAX_KEYS_BODY as u64)
        {
            return None;
        }
        let seconds = response
            .headers()
            .get(reqwest::header::CACHE_CONTROL)
            .and_then(|h| h.to_str().ok())
            .and_then(|h| {
                h.split(',')
                    .find_map(|v| v.trim().strip_prefix("max-age=")?.parse::<u64>().ok())
            })
            .unwrap_or(300)
            .min(3600);
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.ok()? {
            if chunk.len() > MAX_KEYS_BODY.saturating_sub(bytes.len()) {
                return None;
            }
            bytes.extend_from_slice(&chunk);
        }
        Some((parse_keys(&bytes)?, Duration::from_secs(seconds)))
    }
}
fn parse_keys(bytes: &[u8]) -> Option<HashMap<String, DecodingKey>> {
    if bytes.len() > MAX_KEYS_BODY {
        return None;
    }
    let set: KeySet = serde_json::from_slice(bytes).ok()?;
    if set.keys.is_empty() || set.keys.len() > 32 {
        return None;
    }
    let mut keys = HashMap::new();
    for key in set.keys {
        if key.kty != "RSA"
            || key.alg.as_deref().is_some_and(|a| a != "RS256")
            || key.usage.as_deref().is_some_and(|u| u != "sig")
        {
            continue;
        }
        if key.kid.is_empty() || key.kid.len() > 256 || key.n.len() > 2048 || key.e.len() > 16 {
            return None;
        }
        let decoding = DecodingKey::from_rsa_components(&key.n, &key.e).ok()?;
        if keys.insert(key.kid, decoding).is_some() {
            return None;
        }
    }
    (!keys.is_empty()).then_some(keys)
}
fn token_kid(token: &str) -> Option<String> {
    if token.is_empty() || token.len() > MAX_TOKEN {
        return None;
    }
    let segments: Vec<_> = token.split('.').collect();
    if segments.len() != 3
        || segments.iter().any(|s| {
            s.is_empty()
                || !s
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
    {
        return None;
    }
    let raw: serde_json::Value =
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(segments[0]).ok()?).ok()?;
    // Unsupported JOSE extensions must not silently alter signature semantics.
    if raw.get("crit").is_some() || raw.get("b64").is_some() {
        return None;
    }
    let header = decode_header(token).ok()?;
    if header.alg != Algorithm::RS256 {
        return None;
    }
    header.kid.filter(|kid| !kid.is_empty() && kid.len() <= 256)
}
fn verify(token: &str, client_id: &str, key: &DecodingKey) -> Option<String> {
    token_kid(token)?;
    let mut validation = Validation::new(Algorithm::RS256);
    validation.leeway = 0;
    validation.validate_nbf = true;
    validation.set_required_spec_claims(&["exp", "iss", "aud"]);
    validation.set_issuer(&["https://accounts.google.com", "accounts.google.com"]);
    validation.set_audience(&[client_id]);
    let claims = decode::<Claims>(token, key, &validation).ok()?.claims;
    // Reject at the exact expiry instant as well as earlier expirations.
    if claims.exp <= crate::now_ms() / 1000
        || claims
            .nbf
            .is_some_and(|nbf| nbf > (crate::now_ms() / 1000) as f64)
        || !claims.email_verified
        || claims.email.trim().is_empty()
        || claims.email.len() > 320
    {
        return None;
    }
    Some(claims.email)
}

#[cfg(test)]
pub(crate) mod tests;
