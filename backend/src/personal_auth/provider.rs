use crate::identity::VerifiedGitHubIdentity;
use anyhow::{bail, ensure, Result};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::{collections::BTreeSet, future::Future, pin::Pin, sync::Arc, time::Duration};
use url::Url;

const AUTHORIZE_URL: &str = "https://github.com/login/oauth/authorize";
const TOKEN_URL: &str = "https://github.com/login/oauth/access_token";
const DEVICE_CODE_URL: &str = "https://github.com/login/device/code";
/// Where a person types the device flow's code; the native app opens only this fixed page.
pub(crate) const DEVICE_VERIFICATION_URL: &str = "https://github.com/login/device";
/// The GitHub CLI's own OAuth App ID, public in cli/cli (internal/authflow/flow.go). GitHub lists
/// "GitHub CLI" among its privileged OAuth apps, which organization OAuth restrictions do not block,
/// so signing in as it reaches what `gh auth login` reaches (Tavi, 23 Oct 2026 13:36).
pub(crate) const GITHUB_CLI_CLIENT_ID: &str = "178c6fc778ccc68e1d6a";
const MAX_RESPONSE_BYTES: usize = 32 * 1024;
const MAX_ACCESS_LIFETIME: u64 = 31 * 24 * 60 * 60;
const MAX_REFRESH_LIFETIME: u64 = 366 * 24 * 60 * 60;

#[derive(Debug)]
pub(super) struct ReconnectRequired;
impl std::fmt::Display for ReconnectRequired {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("GitHub authorization needs reconnecting")
    }
}
impl std::error::Error for ReconnectRequired {}

/// Application authority is independent of an OAuth token's possibly inherited scopes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum GitHubAuthority {
    #[default]
    PublicRead,
    PrivateRepositories,
}

/// Supplied only by the operator's startup configuration: a registered Pully OAuth App, or the
/// GitHub CLI's device flow, which needs no secret or callback.
pub(crate) struct OAuthConfig {
    client_id: String,
    flow: OAuthFlow,
}

enum OAuthFlow {
    Web {
        client_secret: String,
        callback: Url,
    },
    Device,
}

/// A device-flow start: the code the person types at [DEVICE_VERIFICATION_URL] and the server's own poll handle.
pub(super) struct DeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub interval: u64,
    pub expires_in: u64,
}

pub(super) enum DevicePoll {
    Pending,
    SlowDown(u64),
    Tokens(GitHubTokens),
}

/// The person declined on GitHub's device page.
#[derive(Debug)]
pub(super) struct DeviceDenied;
impl std::fmt::Display for DeviceDenied {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("GitHub authorization was not approved")
    }
}
impl std::error::Error for DeviceDenied {}

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
                && callback.path() == super::wire::CALLBACK_PATH
                && callback.query().is_none()
                && callback.fragment().is_none(),
            "Invalid Pully OAuth callback configuration"
        );
        Ok(Self {
            client_id,
            flow: OAuthFlow::Web {
                client_secret,
                callback,
            },
        })
    }

    /// Signs in as the GitHub CLI through GitHub's device flow; nothing secret is configured.
    pub(crate) fn github_cli() -> Self {
        Self {
            client_id: GITHUB_CLI_CLIENT_ID.into(),
            flow: OAuthFlow::Device,
        }
    }

    pub(super) fn uses_device_flow(&self) -> bool {
        matches!(self.flow, OAuthFlow::Device)
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
        let OAuthFlow::Web { callback, .. } = &self.flow else {
            bail!("Invalid authorization attempt");
        };
        let mut url = Url::parse(AUTHORIZE_URL).expect("fixed GitHub authorization URL");
        url.query_pairs_mut()
            .append_pair("client_id", &self.client_id)
            .append_pair("redirect_uri", callback.as_str())
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
        if let Some(error) = response.error {
            if matches!(
                error.as_str(),
                "bad_refresh_token" | "expired_token" | "invalid_grant" | "bad_verification_code"
            ) {
                return Err(ReconnectRequired.into());
            }
            bail!("GitHub authorization is temporarily unavailable");
        }
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

impl DeviceCode {
    fn from_response(body: &[u8]) -> Result<Self> {
        ensure!(
            body.len() <= MAX_RESPONSE_BYTES,
            "Invalid GitHub authorization response"
        );
        #[derive(Deserialize)]
        struct Response {
            device_code: String,
            user_code: String,
            verification_uri: String,
            expires_in: u64,
            interval: Option<u64>,
        }
        let response: Response = serde_json::from_slice(body)
            .map_err(|_| anyhow::anyhow!("GitHub authorization is temporarily unavailable"))?;
        ensure!(
            valid_secret(&response.device_code, 256)
                && valid_user_code(&response.user_code)
                && response.verification_uri == DEVICE_VERIFICATION_URL
                && (60..=1800).contains(&response.expires_in),
            "Invalid GitHub authorization response"
        );
        Ok(Self {
            device_code: response.device_code,
            user_code: response.user_code,
            interval: response.interval.unwrap_or(5).clamp(1, 60),
            expires_in: response.expires_in,
        })
    }
}

/// GitHub's codes read like `WDJB-MJHT`; anything else is not shown to the person.
pub(super) fn valid_user_code(code: &str) -> bool {
    (4..=32).contains(&code.len())
        && code
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'-')
}

impl DevicePoll {
    fn from_response(body: &[u8], now: u64) -> Result<Self> {
        ensure!(
            body.len() <= MAX_RESPONSE_BYTES,
            "Invalid GitHub authorization response"
        );
        #[derive(Deserialize)]
        struct Waiting {
            error: Option<String>,
            interval: Option<u64>,
        }
        let waiting: Waiting = serde_json::from_slice(body)
            .map_err(|_| anyhow::anyhow!("Invalid GitHub authorization response"))?;
        match waiting.error.as_deref() {
            None => Ok(Self::Tokens(GitHubTokens::from_response(body, now)?)),
            Some("authorization_pending") => Ok(Self::Pending),
            Some("slow_down") => Ok(Self::SlowDown(waiting.interval.unwrap_or(10).clamp(1, 60))),
            Some("access_denied") => Err(DeviceDenied.into()),
            Some("expired_token" | "incorrect_device_code") => Err(ReconnectRequired.into()),
            Some(_) => bail!("GitHub authorization is temporarily unavailable"),
        }
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
    fn device_code<'a>(&'a self, _authority: GitHubAuthority) -> ProviderFuture<'a, DeviceCode> {
        Box::pin(async { bail!("GitHub device sign-in is not configured") })
    }
    fn poll_device<'a>(
        &'a self,
        _device_code: &'a str,
        _now: u64,
    ) -> ProviderFuture<'a, DevicePoll> {
        Box::pin(async { bail!("GitHub device sign-in is not configured") })
    }
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

    pub(super) fn github_id(&self) -> u64 {
        self.identity.github_id()
    }
}

pub(super) struct OAuthProvider {
    config: Arc<OAuthConfig>,
    client: reqwest::Client,
    #[cfg(test)]
    fixture_origin: Option<Url>,
}

impl OAuthProvider {
    pub(super) fn new(config: Arc<OAuthConfig>) -> Result<Self> {
        let client = crate::net::client()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(|_| anyhow::anyhow!("Could not configure GitHub authorization"))?;
        Ok(Self {
            config,
            client,
            #[cfg(test)]
            fixture_origin: None,
        })
    }

    #[cfg(test)]
    pub(super) fn fixture(config: Arc<OAuthConfig>, origin: Url) -> Result<Self> {
        ensure!(
            origin.scheme() == "http"
                && origin.host_str() == Some("127.0.0.1")
                && origin.path() == "/",
            "Invalid fixture origin"
        );
        let mut provider = Self::new(config)?;
        provider.client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(5))
            .build()?;
        provider.fixture_origin = Some(origin);
        Ok(provider)
    }

    async fn token_request(&self, fields: &[(&str, &str)], now: u64) -> Result<GitHubTokens> {
        let (status, bytes) = self.post_form(TOKEN_URL, fields).await?;
        let decoded = GitHubTokens::from_response(&bytes, now);
        if status != reqwest::StatusCode::OK {
            return match decoded {
                Err(error) if error.is::<ReconnectRequired>() => Err(error),
                _ => Err(anyhow::anyhow!(
                    "GitHub authorization is temporarily unavailable"
                )),
            };
        }
        decoded
    }

    /// A bounded form POST to one of GitHub's fixed sign-in endpoints; redirects and large bodies fail.
    async fn post_form(
        &self,
        url: &str,
        fields: &[(&str, &str)],
    ) -> Result<(reqwest::StatusCode, Vec<u8>)> {
        #[cfg(test)]
        let url = match &self.fixture_origin {
            Some(origin) => origin
                .join(url.trim_start_matches("https://github.com/"))?
                .to_string(),
            None => url.to_owned(),
        };
        let response = crate::net::send(
            self.client
                .post(url)
                .header(reqwest::header::ACCEPT, "application/json")
                .header(reqwest::header::USER_AGENT, "pully")
                .form(fields),
        )
        .await
        .map_err(|_| anyhow::anyhow!("GitHub authorization is unavailable"))?;
        let status = response.status();
        if status.is_server_error()
            || status == reqwest::StatusCode::TOO_MANY_REQUESTS
            || status.is_redirection()
        {
            bail!("GitHub authorization is temporarily unavailable");
        }
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
        Ok((status, bytes))
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
            let OAuthFlow::Web {
                client_secret,
                callback,
            } = &self.config.flow
            else {
                bail!("Invalid authorization attempt");
            };
            self.token_request(
                &[
                    ("client_id", &self.config.client_id),
                    ("client_secret", client_secret),
                    ("code", code),
                    ("redirect_uri", callback.as_str()),
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
            // The GitHub CLI's tokens do not expire, so a device sign-in never holds a refresh token.
            let OAuthFlow::Web { client_secret, .. } = &self.config.flow else {
                return Err(ReconnectRequired.into());
            };
            self.token_request(
                &[
                    ("client_id", &self.config.client_id),
                    ("client_secret", client_secret),
                    ("grant_type", "refresh_token"),
                    ("refresh_token", token),
                ],
                now,
            )
            .await
        })
    }

    fn device_code<'a>(&'a self, authority: GitHubAuthority) -> ProviderFuture<'a, DeviceCode> {
        Box::pin(async move {
            ensure!(
                self.config.uses_device_flow(),
                "GitHub device sign-in is not configured"
            );
            let (status, bytes) = self
                .post_form(
                    DEVICE_CODE_URL,
                    &[
                        ("client_id", &self.config.client_id),
                        ("scope", device_scope(authority)),
                    ],
                )
                .await?;
            ensure!(
                status == reqwest::StatusCode::OK,
                "GitHub authorization is temporarily unavailable"
            );
            DeviceCode::from_response(&bytes)
        })
    }

    fn poll_device<'a>(&'a self, device_code: &'a str, now: u64) -> ProviderFuture<'a, DevicePoll> {
        Box::pin(async move {
            ensure!(
                self.config.uses_device_flow() && valid_secret(device_code, 256),
                "GitHub device sign-in is not configured"
            );
            let (status, bytes) = self
                .post_form(
                    TOKEN_URL,
                    &[
                        ("client_id", &self.config.client_id),
                        ("device_code", device_code),
                        ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                    ],
                )
                .await?;
            ensure!(
                status == reqwest::StatusCode::OK,
                "GitHub authorization is temporarily unavailable"
            );
            DevicePoll::from_response(&bytes, now)
        })
    }

    fn verify<'a>(&'a self, token: String) -> ProviderFuture<'a, VerifiedGitHubIdentity> {
        #[cfg(test)]
        if let Some(origin) = &self.fixture_origin {
            return Box::pin(async move {
                let response = self
                    .client
                    .get(origin.join("user")?)
                    .bearer_auth(&token)
                    .send()
                    .await?;
                ensure!(
                    response.status() == reqwest::StatusCode::OK,
                    "Fixture identity unavailable"
                );
                let mut bytes = Vec::new();
                let mut stream = response.bytes_stream();
                while let Some(chunk) = stream.next().await {
                    let chunk = chunk?;
                    ensure!(
                        bytes.len() + chunk.len() <= MAX_RESPONSE_BYTES,
                        "Fixture identity too large"
                    );
                    bytes.extend_from_slice(&chunk);
                }
                VerifiedGitHubIdentity::fixture_response(token, &bytes)
            });
        }
        Box::pin(VerifiedGitHubIdentity::verify(token))
    }
}

/// Public sign-in asks for nothing beyond public data; private access asks for what `gh` asks for repositories.
fn device_scope(authority: GitHubAuthority) -> &'static str {
    match authority {
        GitHubAuthority::PublicRead => "",
        GitHubAuthority::PrivateRepositories => "repo read:org",
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
            "https://auth.pully.example/api/personal/v1/auth/github/callback",
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
            "https://auth.pully.example/api/personal/v1/auth/github/callback"
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

    #[test]
    fn server_app_misconfiguration_and_temporary_failures_do_not_claim_the_users_refresh_was_revoked(
    ) {
        assert!(parse(json!({"error":"bad_refresh_token"}))
            .err()
            .unwrap()
            .is::<ReconnectRequired>());
        for code in [
            "incorrect_client_credentials",
            "temporarily_unavailable",
            "server_error",
            "unknown-private-error",
        ] {
            let error = parse(json!({"error":code,"error_description":"fixture-secret-detail"}))
                .err()
                .unwrap();
            assert!(!error.is::<ReconnectRequired>());
            assert!(
                !error.to_string().contains("fixture-secret") && !error.to_string().contains(code)
            );
        }
    }

    #[test]
    fn device_flow_uses_the_github_cli_client_and_never_a_callback() {
        let cli = OAuthConfig::github_cli();
        assert!(cli.uses_device_flow());
        assert_eq!(cli.client_id, "178c6fc778ccc68e1d6a");
        assert!(cli
            .authorize_url(
                &"a".repeat(43),
                &"b".repeat(43),
                GitHubAuthority::PublicRead
            )
            .is_err());
        assert_eq!(device_scope(GitHubAuthority::PublicRead), "");
        assert_eq!(
            device_scope(GitHubAuthority::PrivateRepositories),
            "repo read:org"
        );
    }

    #[test]
    fn device_codes_are_checked_before_the_person_sees_them() {
        let code = |value: serde_json::Value| {
            DeviceCode::from_response(&serde_json::to_vec(&value).unwrap())
        };
        let good = code(json!({"device_code":"3584d83530557fdd1f46af8289938c8ef79f9dc5","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":899,"interval":5})).unwrap();
        assert_eq!(good.user_code, "WDJB-MJHT");
        assert_eq!((good.interval, good.expires_in), (5, 899));
        let defaulted = code(json!({"device_code":"d","user_code":"ABCD","verification_uri":"https://github.com/login/device","expires_in":60,"interval":0})).unwrap();
        assert_eq!(defaulted.interval, 1);
        for value in [
            json!({"device_code":"d","user_code":"wdjb-mjht","verification_uri":"https://github.com/login/device","expires_in":899}),
            json!({"device_code":"d","user_code":"WDJB MJHT","verification_uri":"https://github.com/login/device","expires_in":899}),
            json!({"device_code":"d","user_code":"WDJB-MJHT","verification_uri":"https://evil.example/login/device","expires_in":899}),
            json!({"device_code":"d","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":5}),
            json!({"device_code":"","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device","expires_in":899}),
            json!({"error":"device_flow_disabled"}),
        ] {
            assert!(code(value).is_err());
        }
    }

    #[test]
    fn device_polls_wait_slow_down_finish_or_stop_for_good() {
        let poll = |value: serde_json::Value| {
            DevicePoll::from_response(&serde_json::to_vec(&value).unwrap(), 100)
        };
        assert!(matches!(
            poll(json!({"error":"authorization_pending"})).unwrap(),
            DevicePoll::Pending
        ));
        assert!(matches!(
            poll(json!({"error":"slow_down","interval":10})).unwrap(),
            DevicePoll::SlowDown(10)
        ));
        assert!(matches!(
            poll(json!({"error":"slow_down","interval":900})).unwrap(),
            DevicePoll::SlowDown(60)
        ));
        let DevicePoll::Tokens(tokens) = poll(
            json!({"access_token":"gho_fixture","token_type":"bearer","scope":"repo,read:org"}),
        )
        .unwrap() else {
            panic!("approved device code did not return tokens");
        };
        assert!(tokens.refresh.is_none() && tokens.expires_at.is_none());
        assert_eq!(
            tokens.authority(GitHubAuthority::PrivateRepositories),
            GitHubAuthority::PrivateRepositories
        );
        assert!(poll(json!({"error":"access_denied"}))
            .err()
            .unwrap()
            .is::<DeviceDenied>());
        for code in ["expired_token", "incorrect_device_code"] {
            assert!(poll(json!({"error":code}))
                .err()
                .unwrap()
                .is::<ReconnectRequired>());
        }
        let other =
            poll(json!({"error":"unsupported_grant_type","error_description":"fixture-secret"}))
                .err()
                .unwrap();
        assert!(!other.is::<DeviceDenied>() && !other.is::<ReconnectRequired>());
        assert!(!other.to_string().contains("fixture-secret"));
    }
}
