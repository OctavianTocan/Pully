use crate::identity::VerifiedGitHubIdentity;
use anyhow::{bail, ensure, Result};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeSet, future::Future, pin::Pin, sync::Arc, time::Duration};
use url::Url;

const AUTHORIZE_URL: &str = "https://github.com/login/oauth/authorize";
const TOKEN_URL: &str = "https://github.com/login/oauth/access_token";
const MAX_RESPONSE_BYTES: usize = 32 * 1024;
const MAX_ACCESS_LIFETIME: u64 = 31 * 24 * 60 * 60;
const MAX_REFRESH_LIFETIME: u64 = 366 * 24 * 60 * 60;

/// Application authority is independent of an OAuth token's possibly inherited scopes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum GitHubAuthority {
    PublicRead,
    PrivateRepositories,
}

/// Supplied only by the operator's registered Pully OAuth App configuration.
pub(crate) struct OAuthConfig {
    client_id: String,
    client_secret: String,
    callback: Url,
}

impl OAuthConfig {
    pub(crate) fn new(client_id: String, client_secret: String, callback: &str) -> Result<Self> {
        ensure!(
            valid_secret(&client_id, 256) && valid_secret(&client_secret, 4096),
            "Pully OAuth App configuration is unavailable"
        );
        let callback = Url::parse(callback)
            .map_err(|_| anyhow::anyhow!("Invalid Pully OAuth callback configuration"))?;
        ensure!(
            callback.scheme() == "https"
                && callback.host_str().is_some()
                && callback.username().is_empty()
                && callback.password().is_none()
                && callback.port().is_none()
                && callback.query().is_none()
                && callback.fragment().is_none(),
            "Invalid Pully OAuth callback configuration"
        );
        Ok(Self {
            client_id,
            client_secret,
            callback,
        })
    }

    pub(super) fn authorize_url(
        &self,
        state: &str,
        challenge: &str,
        authority: GitHubAuthority,
    ) -> Result<Url> {
        ensure!(
            url_secret(state, 43, 43) && url_secret(challenge, 43, 43),
            "Invalid authorization attempt"
        );
        let mut url = Url::parse(AUTHORIZE_URL).expect("fixed GitHub authorization URL");
        url.query_pairs_mut()
            .append_pair("client_id", &self.client_id)
            .append_pair("redirect_uri", self.callback.as_str())
            .append_pair("response_type", "code")
            .append_pair("state", state)
            .append_pair("code_challenge", challenge)
            .append_pair("code_challenge_method", "S256")
            .append_pair(
                "scope",
                match authority {
                    GitHubAuthority::PublicRead => "offline_access",
                    GitHubAuthority::PrivateRepositories => "repo offline_access",
                },
            )
            .append_pair("prompt", "select_account");
        Ok(url)
    }
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(super) struct GitHubTokens {
    pub access_token: String,
    pub expires_at: Option<u64>,
    pub refresh: Option<RefreshToken>,
    pub scopes: BTreeSet<String>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(super) struct RefreshToken {
    pub token: String,
    pub expires_at: u64,
}

impl GitHubTokens {
    pub(super) fn authority(&self, requested: GitHubAuthority) -> GitHubAuthority {
        if requested == GitHubAuthority::PrivateRepositories && self.scopes.contains("repo") {
            GitHubAuthority::PrivateRepositories
        } else {
            GitHubAuthority::PublicRead
        }
    }

    fn from_response(body: &[u8], now: u64) -> Result<Self> {
        ensure!(
            body.len() <= MAX_RESPONSE_BYTES,
            "Invalid GitHub authorization response"
        );
        #[derive(Deserialize)]
        struct Response {
            access_token: Option<String>,
            token_type: Option<String>,
            scope: Option<String>,
            expires_in: Option<u64>,
            refresh_token: Option<String>,
            refresh_token_expires_in: Option<u64>,
            error: Option<String>,
        }
        let response: Response = serde_json::from_slice(body)
            .map_err(|_| anyhow::anyhow!("Invalid GitHub authorization response"))?;
        ensure!(
            response.error.is_none(),
            "GitHub authorization needs reconnecting"
        );
        let access_token = response.access_token.unwrap_or_default();
        ensure!(
            valid_secret(&access_token, 4096)
                && response
                    .token_type
                    .as_deref()
                    .is_some_and(|value| value.eq_ignore_ascii_case("bearer")),
            "Invalid GitHub authorization response"
        );
        let (expires_at, refresh) = match (
            response.expires_in,
            response.refresh_token,
            response.refresh_token_expires_in,
        ) {
            (None, None, None) => (None, None),
            (Some(access_lifetime), Some(refresh_token), Some(refresh_lifetime)) => {
                ensure!(
                    (1..=MAX_ACCESS_LIFETIME).contains(&access_lifetime)
                        && (1..=MAX_REFRESH_LIFETIME).contains(&refresh_lifetime)
                        && refresh_lifetime > access_lifetime
                        && valid_secret(&refresh_token, 4096),
                    "Invalid GitHub authorization response"
                );
                (
                    Some(
                        now.checked_add(access_lifetime)
                            .ok_or_else(|| anyhow::anyhow!("Invalid authorization expiry"))?,
                    ),
                    Some(RefreshToken {
                        token: refresh_token,
                        expires_at: now
                            .checked_add(refresh_lifetime)
                            .ok_or_else(|| anyhow::anyhow!("Invalid authorization expiry"))?,
                    }),
                )
            }
            _ => bail!("Invalid GitHub authorization response"),
        };
        let raw_scopes = response.scope.unwrap_or_default();
        ensure!(raw_scopes.len() <= 4096, "Invalid GitHub grant metadata");
        let scopes: BTreeSet<String> = raw_scopes
            .split(|character: char| character == ',' || character.is_ascii_whitespace())
            .filter(|scope| !scope.is_empty())
            .map(str::to_owned)
            .collect();
        ensure!(
            scopes.len() <= 64
                && scopes.iter().all(|scope| scope.len() <= 128
                    && scope
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || b":_-".contains(&byte))),
            "Invalid GitHub grant metadata"
        );
        Ok(Self {
            access_token,
            expires_at,
            refresh,
            scopes,
        })
    }
}

pub(super) type ProviderFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T>> + Send + 'a>>;

/// The production implementation below has fixed origins; offline fixtures replace the entire provider.
pub(super) trait GitHubProvider: Send + Sync {
    fn exchange<'a>(
        &'a self,
        code: &'a str,
        verifier: &'a str,
        now: u64,
    ) -> ProviderFuture<'a, GitHubTokens>;
    fn refresh<'a>(&'a self, token: &'a str, now: u64) -> ProviderFuture<'a, GitHubTokens>;
    fn verify<'a>(&'a self, token: String) -> ProviderFuture<'a, VerifiedGitHubIdentity>;
}

/// Only a verified acquisition can create or replace a stable account's credential.
pub(super) struct VerifiedLogin {
    identity: VerifiedGitHubIdentity,
    tokens: GitHubTokens,
}

impl VerifiedLogin {
    pub(super) async fn acquire(
        provider: &dyn GitHubProvider,
        tokens: GitHubTokens,
    ) -> Result<Self> {
        let identity = provider.verify(tokens.access_token.clone()).await?;
        Ok(Self { identity, tokens })
    }

    pub(super) fn into_parts(self) -> (VerifiedGitHubIdentity, GitHubTokens) {
        (self.identity, self.tokens)
    }
}

pub(super) struct OAuthProvider {
    config: Arc<OAuthConfig>,
    client: reqwest::Client,
}

impl OAuthProvider {
    pub(super) fn new(config: Arc<OAuthConfig>) -> Result<Self> {
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|_| anyhow::anyhow!("Could not configure GitHub authorization"))?;
        Ok(Self { config, client })
    }

    async fn token_request(&self, fields: &[(&str, &str)], now: u64) -> Result<GitHubTokens> {
        let response = self
            .client
            .post(TOKEN_URL)
            .header(reqwest::header::ACCEPT, "application/json")
            .header(reqwest::header::USER_AGENT, "pully")
            .form(fields)
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("GitHub authorization is unavailable"))?;
        ensure!(
            response.status() == reqwest::StatusCode::OK,
            "GitHub authorization needs reconnecting"
        );
        ensure!(
            response
                .content_length()
                .is_none_or(|length| length <= MAX_RESPONSE_BYTES as u64),
            "Invalid GitHub authorization response"
        );
        let mut bytes = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk =
                chunk.map_err(|_| anyhow::anyhow!("GitHub authorization is unavailable"))?;
            ensure!(
                bytes.len() + chunk.len() <= MAX_RESPONSE_BYTES,
                "Invalid GitHub authorization response"
            );
            bytes.extend_from_slice(&chunk);
        }
        GitHubTokens::from_response(&bytes, now)
    }
}

impl GitHubProvider for OAuthProvider {
    fn exchange<'a>(
        &'a self,
        code: &'a str,
        verifier: &'a str,
        now: u64,
    ) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async move {
            ensure!(
                valid_secret(code, 4096) && url_secret(verifier, 43, 128),
                "Invalid authorization attempt"
            );
            self.token_request(
                &[
                    ("client_id", &self.config.client_id),
                    ("client_secret", &self.config.client_secret),
                    ("code", code),
                    ("redirect_uri", self.config.callback.as_str()),
                    ("code_verifier", verifier),
                ],
                now,
            )
            .await
        })
    }

    fn refresh<'a>(&'a self, token: &'a str, now: u64) -> ProviderFuture<'a, GitHubTokens> {
        Box::pin(async move {
            ensure!(
                valid_secret(token, 4096),
                "GitHub authorization needs reconnecting"
            );
            self.token_request(
                &[
                    ("client_id", &self.config.client_id),
                    ("client_secret", &self.config.client_secret),
                    ("grant_type", "refresh_token"),
                    ("refresh_token", token),
                ],
                now,
            )
            .await
        })
    }

    fn verify<'a>(&'a self, token: String) -> ProviderFuture<'a, VerifiedGitHubIdentity> {
        Box::pin(VerifiedGitHubIdentity::verify(token))
    }
}

fn valid_secret(value: &str, maximum: usize) -> bool {
    !value.is_empty() && value.len() <= maximum && value.bytes().all(|byte| byte.is_ascii_graphic())
}

pub(super) fn url_secret(value: &str, minimum: usize, maximum: usize) -> bool {
    (minimum..=maximum).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-_".contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn config() -> OAuthConfig {
        OAuthConfig::new(
            "pully-fixture-client".into(),
            "fixture-server-secret".into(),
            "https://auth.pully.example/github/callback",
        )
        .unwrap()
    }
    fn parse(value: serde_json::Value) -> Result<GitHubTokens> {
        GitHubTokens::from_response(&serde_json::to_vec(&value).unwrap(), 100)
    }

    #[test]
    fn fixed_authorize_origin_carries_pkce_and_no_client_or_native_secret() {
        let url = config()
            .authorize_url(
                &"a".repeat(43),
                &"b".repeat(43),
                GitHubAuthority::PublicRead,
            )
            .unwrap();
        assert_eq!(url.origin().ascii_serialization(), "https://github.com");
        assert_eq!(url.path(), "/login/oauth/authorize");
        let fields: std::collections::BTreeMap<_, _> = url.query_pairs().into_owned().collect();
        assert_eq!(fields["scope"], "offline_access");
        assert_eq!(fields["code_challenge_method"], "S256");
        assert_eq!(
            fields["redirect_uri"],
            "https://auth.pully.example/github/callback"
        );
        assert_eq!(fields["prompt"], "select_account");
        assert!(!url.as_str().contains("fixture-server-secret"));
        let private = config()
            .authorize_url(
                &"a".repeat(43),
                &"b".repeat(43),
                GitHubAuthority::PrivateRepositories,
            )
            .unwrap();
        assert_eq!(
            private
                .query_pairs()
                .find(|(key, _)| key == "scope")
                .unwrap()
                .1,
            "repo offline_access"
        );
    }

    #[test]
    fn callbacks_and_pkce_cannot_be_replaced_with_native_input() {
        for callback in [
            "http://auth.pully.example/callback",
            "https://user:pass@auth.pully.example/callback",
            "https://auth.pully.example:8443/callback",
            "https://auth.pully.example/callback?return=https://evil.example",
            "https://auth.pully.example/callback#secret",
        ] {
            assert!(
                OAuthConfig::new("pully-client".into(), "server-secret".into(), callback).is_err()
            );
        }
        assert!(config()
            .authorize_url("short", &"a".repeat(43), GitHubAuthority::PublicRead)
            .is_err());
        assert!(config()
            .authorize_url(
                &"a".repeat(43),
                &"/".repeat(43),
                GitHubAuthority::PublicRead
            )
            .is_err());
    }

    #[test]
    fn expiring_response_retains_the_rotating_pair_and_real_grant_metadata() {
        let tokens = parse(json!({"access_token":"access-one","token_type":"bearer","scope":"repo,read:user","expires_in":28800,"refresh_token":"refresh-one","refresh_token_expires_in":15897600})).unwrap();
        assert_eq!(tokens.expires_at, Some(28900));
        let refresh = tokens.refresh.as_ref().unwrap();
        assert_eq!(refresh.token, "refresh-one");
        assert_eq!(refresh.expires_at, 15897700);
        assert!(tokens.scopes.contains("read:user"));
        assert_eq!(
            tokens.authority(GitHubAuthority::PublicRead),
            GitHubAuthority::PublicRead
        );
        assert_eq!(
            tokens.authority(GitHubAuthority::PrivateRepositories),
            GitHubAuthority::PrivateRepositories
        );
    }

    #[test]
    fn a_deliberately_nonexpiring_response_and_reduced_grants_are_explicit() {
        let tokens =
            parse(json!({"access_token":"non-expiring","token_type":"Bearer","scope":""})).unwrap();
        assert!(tokens.expires_at.is_none() && tokens.refresh.is_none());
        assert_eq!(
            tokens.authority(GitHubAuthority::PrivateRepositories),
            GitHubAuthority::PublicRead
        );
    }

    #[test]
    fn partial_pairs_invalid_lifetimes_and_provider_errors_fail_without_echoing_secrets() {
        for value in [
            json!({"access_token":"secret-access","token_type":"bearer","expires_in":28800}),
            json!({"access_token":"secret-access","token_type":"bearer","refresh_token":"secret-refresh","refresh_token_expires_in":15897600}),
            json!({"access_token":"secret-access","token_type":"bearer","expires_in":0,"refresh_token":"secret-refresh","refresh_token_expires_in":15897600}),
            json!({"access_token":"secret-access","token_type":"bearer","expires_in":28800,"refresh_token":"secret-refresh","refresh_token_expires_in":10}),
            json!({"access_token":"secret-access","token_type":"not-bearer"}),
            json!({"error":"secret-error","error_description":"secret-access"}),
        ] {
            let error = parse(value).err().unwrap().to_string();
            assert!(!error.contains("secret-"));
        }
    }

    #[test]
    fn oversized_or_malformed_response_and_expiry_overflow_are_bounded() {
        assert!(GitHubTokens::from_response(&vec![b'a'; MAX_RESPONSE_BYTES + 1], 1).is_err());
        assert!(parse(json!({"access_token":"a".repeat(4097),"token_type":"bearer"})).is_err());
        assert!(parse(
            json!({"access_token":"good","token_type":"bearer","scope":"repo\u{0}secret"})
        )
        .is_err());
        let body = serde_json::to_vec(&json!({"access_token":"a","token_type":"bearer","expires_in":28800,"refresh_token":"r","refresh_token_expires_in":15897600})).unwrap();
        assert!(GitHubTokens::from_response(&body, u64::MAX).is_err());
    }
}
