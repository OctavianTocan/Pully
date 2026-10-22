//! Official user-operated SIWC protocol. This does not claim approval for Pully's hosted service.
mod provider;

use anyhow::{ensure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;

const ISSUER: &str = "https://auth.openai.com";
const AUTHORIZE_PATH: &str = "/api/accounts/authorize";
const TOKEN_PATH: &str = "/api/accounts/oauth/token";
const DISCOVERY_PATH: &str = "/.well-known/openid-configuration";
const JWKS_PATH: &str = "/.well-known/jwks.json";
const RESOURCE: &str = "https://api.openai.com/v1";
const PLAN_SCOPE: &str = "chatgpt.tokens.use.direct";
const REQUESTED_SCOPES: &str =
    "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
const DYNAMIC_CLIENT: &str = "dynamic_agent_client";

pub(crate) use provider::OpenAiSiwc;

/// A host persists this opaque ID before its first registration. Constructing this config
/// does not register an app, authorize a plan or enable Pully's shared hosted deployment.
pub(crate) struct SiwcHostConfig {
    host_id: String,
    callback: url::Url,
}
impl SiwcHostConfig {
    pub(crate) fn user_operated(host_id: String, callback: url::Url) -> Result<Self> {
        ensure!(
            !host_id.is_empty()
                && host_id.len() <= 128
                && host_id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"-_.:".contains(&byte))
                && callback.scheme() == "http"
                && callback.host_str() == Some("127.0.0.1")
                && callback
                    .port_or_known_default()
                    .is_some_and(|port| port > 0)
                && callback.path() == "/auth/callback"
                && callback.username().is_empty()
                && callback.password().is_none()
                && callback.query().is_none()
                && callback.fragment().is_none(),
            "The user-operated ChatGPT callback is not configured"
        );
        Ok(Self { host_id, callback })
    }
}

/// Not Debug/Serialize: this owns one pending attempt's nonce and PKCE verifier.
pub(crate) struct SiwcAttempt {
    state: String,
    nonce: String,
    verifier: String,
    callback: url::Url,
    host_id: String,
    issued_client: Option<String>,
    expected_subject: Option<String>,
    expires_at: u64,
}
impl SiwcAttempt {
    pub(crate) fn new(
        config: &SiwcHostConfig,
        selected: Option<(&str, &str)>,
        now: u64,
    ) -> Result<Self> {
        if let Some((client, subject)) = selected {
            ensure!(
                valid_client(client) && valid_text(subject, 2048),
                "Invalid ChatGPT registration"
            );
        }
        Ok(Self {
            state: random_value()?,
            nonce: random_value()?,
            verifier: random_value()?,
            callback: config.callback.clone(),
            host_id: config.host_id.clone(),
            issued_client: selected.map(|(client, _)| client.to_owned()),
            expected_subject: selected.map(|(_, subject)| subject.to_owned()),
            expires_at: now.checked_add(600).ok_or_else(failure)?,
        })
    }
    pub(crate) fn authorization_url(&self) -> Result<url::Url> {
        let mut url = url::Url::parse(&format!("{ISSUER}{AUTHORIZE_PATH}"))?;
        {
            let mut query = url.query_pairs_mut();
            query.append_pair(
                "client_id",
                self.issued_client.as_deref().unwrap_or(DYNAMIC_CLIENT),
            );
            if self.issued_client.is_none() {
                query.append_pair("agent_name_hint", "Pully");
            }
            query
                .append_pair("ext_agent_host_id", &self.host_id)
                .append_pair("response_type", "code")
                .append_pair("redirect_uri", self.callback.as_str())
                .append_pair("scope", REQUESTED_SCOPES)
                .append_pair("resource", RESOURCE)
                .append_pair("state", &self.state)
                .append_pair("nonce", &self.nonce)
                .append_pair("code_challenge_method", "S256")
                .append_pair(
                    "code_challenge",
                    &URL_SAFE_NO_PAD.encode(Sha256::digest(self.verifier.as_bytes())),
                );
        }
        Ok(url)
    }
    /// Consumes the pending transaction before code exchange. The owning flow removes it from
    /// its source-owned bounded map before calling this method, including denied/error returns.
    pub(crate) fn consume(self, callback: &url::Url, now: u64) -> Result<SiwcCode> {
        ensure!(now < self.expires_at, "ChatGPT authorization expired");
        let mut bare = callback.clone();
        bare.set_query(None);
        ensure!(
            bare == self.callback,
            "ChatGPT authorization could not be verified"
        );
        let mut fields = std::collections::BTreeMap::new();
        ensure!(
            callback.as_str().len() <= 16 * 1024,
            "ChatGPT authorization could not be verified"
        );
        for (name, value) in callback.query_pairs() {
            ensure!(
                fields
                    .insert(name.into_owned(), value.into_owned())
                    .is_none(),
                "ChatGPT authorization could not be verified"
            );
        }
        let state = fields.get("state").ok_or_else(failure)?;
        ensure!(
            ring::constant_time::verify_slices_are_equal(state.as_bytes(), self.state.as_bytes())
                .is_ok(),
            "ChatGPT authorization could not be verified"
        );
        ensure!(
            !fields.contains_key("error"),
            "ChatGPT authorization was not completed"
        );
        let client = match (self.issued_client.as_deref(), fields.get("client_id")) {
            (Some(expected), Some(returned)) => {
                ensure!(returned == expected, "ChatGPT registration changed");
                expected.to_owned()
            }
            (Some(expected), None) => expected.to_owned(),
            (None, Some(returned)) if valid_client(returned) => returned.clone(),
            _ => return Err(failure()),
        };
        let code = fields
            .remove("code")
            .filter(|code| valid_text(code, 8192))
            .ok_or_else(failure)?;
        Ok(SiwcCode {
            code,
            client_id: client,
            nonce: self.nonce,
            verifier: self.verifier,
            callback: self.callback,
            expected_subject: self.expected_subject,
        })
    }
}

pub(crate) struct SiwcCode {
    code: String,
    client_id: String,
    nonce: String,
    verifier: String,
    callback: url::Url,
    expected_subject: Option<String>,
}

/// Immutable provider identity; email is display-only and never an account-linking key.
pub(crate) struct SiwcIdentity {
    pub issuer: String,
    pub client_id: String,
    pub subject: String,
    pub email: Option<String>,
}

#[derive(serde::Serialize, Deserialize)]
pub(crate) struct SiwcTokens {
    access_token: String,
    refresh_token: Option<String>,
    id_token: String,
    scopes: BTreeSet<String>,
    expires_at: u64,
    earliest_refresh_at: Option<serde_json::Value>,
    original_nonce: String,
}
impl SiwcTokens {
    pub(crate) fn plan_enabled(&self) -> bool {
        self.scopes.contains(PLAN_SCOPE) && self.scopes.contains("resource.invoke")
    }
}

pub(crate) struct VerifiedSiwc {
    pub identity: SiwcIdentity,
    pub tokens: SiwcTokens,
}

/// Accepted exchange owns its code. Custody may persist the encrypted pending tokens before
/// verification; it cannot run exchange again with this consumed code.
pub(crate) struct SiwcAcquisition {
    code: SiwcCode,
    tokens: SiwcTokens,
}

fn random_value() -> Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).map_err(|_| failure())?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}
fn valid_text(value: &str, maximum: usize) -> bool {
    !value.is_empty() && value.len() <= maximum && !value.chars().any(char::is_control)
}
fn valid_client(value: &str) -> bool {
    !value.is_empty()
        && value != DYNAMIC_CLIENT
        && value.len() <= 256
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
}
fn failure() -> anyhow::Error {
    anyhow::anyhow!("ChatGPT authorization could not be verified")
}

#[cfg(test)]
mod tests;
