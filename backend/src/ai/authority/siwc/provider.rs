use super::*;
use futures_util::StreamExt;
use jsonwebtoken::{decode, decode_header, Algorithm, DecodingKey, Validation};
use std::time::Duration;

const MAX_BODY: usize = 128 * 1024;

/// Transport errors do not imply a terminal grant rejection.
#[derive(Debug)]
pub(super) struct Reconnect;
impl std::fmt::Display for Reconnect {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Reconnect your ChatGPT registration")
    }
}
impl std::error::Error for Reconnect {}

pub(crate) struct OpenAiSiwc {
    client: reqwest::Client,
    origin: url::Url,
}
impl OpenAiSiwc {
    pub(crate) fn new() -> Result<Self> {
        Ok(Self {
            client: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(15))
                .build()
                .map_err(|_| failure())?,
            origin: url::Url::parse(ISSUER).map_err(|_| failure())?,
        })
    }

    /// Acquisition and verification are separate so custody can protect a rotating replacement
    /// before any fallible JWKS read. No raw acquisition result is an AI credential capability.
    pub(crate) async fn exchange(&self, code: SiwcCode, now: u64) -> Result<SiwcAcquisition> {
        let body = self
            .token(&[
                ("grant_type", "authorization_code"),
                ("client_id", &code.client_id),
                ("code", &code.code),
                ("code_verifier", &code.verifier),
                ("redirect_uri", code.callback.as_str()),
                ("resource", RESOURCE),
            ])
            .await?;
        let tokens = parse_tokens(body, None, &code.nonce, now)?;
        Ok(SiwcAcquisition { code, tokens })
    }

    pub(crate) async fn refresh(
        &self,
        identity: &SiwcIdentity,
        previous: &SiwcTokens,
        now: u64,
    ) -> Result<SiwcTokens> {
        ensure!(
            identity.issuer == ISSUER && valid_client(&identity.client_id),
            "Invalid ChatGPT registration"
        );
        let refresh = previous.refresh_token.as_deref().ok_or(Reconnect)?;
        let body = self
            .token(&[
                ("grant_type", "refresh_token"),
                ("client_id", &identity.client_id),
                ("refresh_token", refresh),
                ("resource", RESOURCE),
            ])
            .await?;
        parse_tokens(body, Some(&previous.scopes), &previous.original_nonce, now)
    }

    pub(crate) async fn verify_exchange(
        &self,
        acquired: SiwcAcquisition,
        now: u64,
    ) -> Result<VerifiedSiwc> {
        let SiwcAcquisition { code, tokens } = acquired;
        let identity = self
            .verify(
                &tokens,
                &code.client_id,
                code.expected_subject.as_deref(),
                true,
                now,
            )
            .await?;
        Ok(VerifiedSiwc { identity, tokens })
    }

    pub(crate) async fn verify_refresh(
        &self,
        identity: &SiwcIdentity,
        tokens: &SiwcTokens,
        now: u64,
    ) -> Result<SiwcIdentity> {
        self.verify(
            tokens,
            &identity.client_id,
            Some(&identity.subject),
            false,
            now,
        )
        .await
    }

    async fn verify(
        &self,
        tokens: &SiwcTokens,
        client_id: &str,
        subject: Option<&str>,
        initial: bool,
        now: u64,
    ) -> Result<SiwcIdentity> {
        ensure!(
            valid_client(client_id) && tokens.expires_at > now,
            "ChatGPT authorization expired"
        );
        let header = decode_header(&tokens.id_token).map_err(|_| failure())?;
        ensure!(
            matches!(
                header.alg,
                Algorithm::RS256 | Algorithm::ES256 | Algorithm::PS256
            ),
            "ChatGPT identity could not be verified"
        );
        let kid = header
            .kid
            .as_deref()
            .filter(|kid| valid_text(kid, 256))
            .ok_or_else(failure)?;
        // Only fixed discovery/JWKS origins are used. Untrusted JWT jku/x5u values are ignored.
        self.discovery().await?;
        let response = self
            .value(self.client.get(self.endpoint(JWKS_PATH)))
            .await?;
        ensure!(
            response["keys"]
                .as_array()
                .is_some_and(|keys| (1..=16).contains(&keys.len())),
            "ChatGPT identity keys are unavailable"
        );
        let keys: jsonwebtoken::jwk::JwkSet =
            serde_json::from_value(response).map_err(|_| failure())?;
        let matching = keys
            .keys
            .iter()
            .filter(|key| key.common.key_id.as_deref() == Some(kid))
            .collect::<Vec<_>>();
        ensure!(matching.len() == 1, "ChatGPT identity keys are unavailable");
        let key = DecodingKey::from_jwk(matching[0]).map_err(|_| failure())?;
        let mut validation = Validation::new(header.alg);
        validation.set_issuer(&[ISSUER]);
        validation.set_audience(&[client_id]);
        validation.set_required_spec_claims(&["sub", "exp", "iat", "iss", "aud"]);
        // The injected server clock below validates time; tests do not disable a production check.
        validation.validate_exp = false;
        validation.validate_nbf = false;
        let claims = decode::<IdClaims>(&tokens.id_token, &key, &validation)
            .map_err(|_| failure())?
            .claims;
        ensure!(
            claims.exp > now
                && claims.exp >= claims.iat
                && claims.iat <= now.saturating_add(5)
                && claims.nbf.is_none_or(|nbf| nbf <= now.saturating_add(5))
                && valid_text(&claims.sub, 2048)
                && subject.is_none_or(|expected| expected == claims.sub),
            "ChatGPT identity changed or expired"
        );
        if initial || claims.nonce.is_some() {
            let nonce = claims.nonce.as_deref().ok_or_else(failure)?;
            ensure!(
                ring::constant_time::verify_slices_are_equal(
                    nonce.as_bytes(),
                    tokens.original_nonce.as_bytes()
                )
                .is_ok(),
                "ChatGPT authorization could not be verified"
            );
        }
        let email = claims.email.filter(|email| valid_text(email, 320));
        Ok(SiwcIdentity {
            issuer: ISSUER.into(),
            client_id: client_id.into(),
            subject: claims.sub,
            email,
        })
    }

    pub(crate) async fn revoke(&self, identity: &SiwcIdentity, tokens: &SiwcTokens) -> Result<()> {
        ensure!(
            identity.issuer == ISSUER && valid_client(&identity.client_id),
            "Invalid ChatGPT registration"
        );
        let token = tokens.refresh_token.as_deref().ok_or(Reconnect)?;
        let metadata = self.discovery().await?;
        let endpoint = metadata["revocation_endpoint"]
            .as_str()
            .ok_or_else(failure)?;
        let target = fixed_auth_url(endpoint)?;
        let response = self
            .client
            .post(self.endpoint(target.path()))
            .header("accept", "application/json")
            .form(&[
                ("token", token),
                ("token_type_hint", "refresh_token"),
                ("client_id", &identity.client_id),
            ])
            .send()
            .await
            .map_err(|_| failure())?;
        ensure!(
            response.status() == reqwest::StatusCode::OK,
            "ChatGPT remote revocation was not confirmed"
        );
        Ok(())
    }

    async fn discovery(&self) -> Result<serde_json::Value> {
        let metadata = self
            .value(self.client.get(self.endpoint(DISCOVERY_PATH)))
            .await?;
        for (field, expected) in [
            ("issuer", ISSUER.to_string()),
            (
                "authorization_endpoint",
                format!("{ISSUER}{AUTHORIZE_PATH}"),
            ),
            ("token_endpoint", format!("{ISSUER}{TOKEN_PATH}")),
            ("jwks_uri", format!("{ISSUER}{JWKS_PATH}")),
        ] {
            ensure!(
                metadata[field].as_str() == Some(expected.as_str()),
                "ChatGPT discovery is unavailable"
            );
        }
        Ok(metadata)
    }
    async fn token(&self, fields: &[(&str, &str)]) -> Result<serde_json::Value> {
        self.value(
            self.client
                .post(self.endpoint(TOKEN_PATH))
                .header("accept", "application/json")
                .form(fields),
        )
        .await
    }
    async fn value(&self, request: reqwest::RequestBuilder) -> Result<serde_json::Value> {
        let response = request.send().await.map_err(|_| failure())?;
        let status = response.status();
        ensure!(
            response
                .content_length()
                .is_none_or(|size| size <= MAX_BODY as u64),
            "ChatGPT response was invalid"
        );
        let mut bytes = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| failure())?;
            ensure!(
                chunk.len() <= MAX_BODY.saturating_sub(bytes.len()),
                "ChatGPT response was invalid"
            );
            bytes.extend_from_slice(&chunk);
        }
        let body: serde_json::Value = serde_json::from_slice(&bytes).map_err(|_| failure())?;
        if matches!(status.as_u16(), 400 | 401)
            && matches!(
                body["error"].as_str(),
                Some("invalid_grant" | "invalid_client" | "unauthorized_client" | "access_denied")
            )
        {
            return Err(Reconnect.into());
        }
        ensure!(
            status == reqwest::StatusCode::OK,
            "ChatGPT authorization is temporarily unavailable"
        );
        Ok(body)
    }
    fn endpoint(&self, path: &str) -> url::Url {
        let mut url = self.origin.clone();
        url.set_path(path);
        url
    }

    #[cfg(test)]
    pub(super) fn fixture(origin: url::Url) -> Self {
        assert!(
            origin.scheme() == "http"
                && origin.host_str() == Some("127.0.0.1")
                && origin.path() == "/"
                && origin.query().is_none()
                && origin.fragment().is_none()
        );
        let mut provider = Self::new().unwrap();
        provider.origin = origin;
        provider
    }
}

#[derive(Deserialize)]
struct IdClaims {
    sub: String,
    exp: u64,
    iat: u64,
    nbf: Option<u64>,
    nonce: Option<String>,
    email: Option<String>,
}

fn parse_tokens(
    body: serde_json::Value,
    previous_scope: Option<&BTreeSet<String>>,
    nonce: &str,
    now: u64,
) -> Result<SiwcTokens> {
    let secret = |name: &str| -> Result<String> {
        body[name]
            .as_str()
            .filter(|value| {
                !value.is_empty()
                    && value.len() <= 16 * 1024
                    && value.bytes().all(|byte| (0x21..=0x7e).contains(&byte))
            })
            .map(str::to_owned)
            .ok_or_else(failure)
    };
    ensure!(
        body["token_type"]
            .as_str()
            .is_some_and(|value| value.eq_ignore_ascii_case("Bearer")),
        "ChatGPT token response was invalid"
    );
    let lifetime = body["expires_in"]
        .as_u64()
        .filter(|value| (1..=86400).contains(value))
        .ok_or_else(failure)?;
    let scopes = match body.get("scope") {
        Some(scope) => scope
            .as_str()
            .filter(|value| value.len() <= 4096 && !value.chars().any(char::is_control))
            .ok_or_else(failure)?
            .split_ascii_whitespace()
            .map(str::to_owned)
            .collect(),
        None => previous_scope.cloned().ok_or_else(failure)?,
    };
    Ok(SiwcTokens {
        access_token: secret("access_token")?,
        refresh_token: if body.get("refresh_token").is_some() {
            Some(secret("refresh_token")?)
        } else {
            None
        },
        id_token: secret("id_token")?,
        scopes,
        expires_at: now.checked_add(lifetime).ok_or_else(failure)?,
        earliest_refresh_at: body.get("earliest_refresh_at").cloned(),
        original_nonce: nonce.into(),
    })
}
fn fixed_auth_url(raw: &str) -> Result<url::Url> {
    let url = url::Url::parse(raw).map_err(|_| failure())?;
    ensure!(
        url.scheme() == "https"
            && url.host_str() == Some("auth.openai.com")
            && url.port().is_none()
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
            && url.path().len() <= 256,
        "ChatGPT discovery is unavailable"
    );
    Ok(url)
}
