//! Fixed personal-auth protocol. All `*At` fields are Unix epoch seconds; polling is milliseconds.
//! None of these declarations registers routes or changes the legacy phone API.
use super::vault::SessionStatus;
use anyhow::{ensure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub(crate) const CAPABILITIES_PATH: &str = "/api/personal/v1/capabilities";
pub(crate) const START_PATH: &str = "/api/personal/v1/auth/github/start";
pub(crate) const REPOSITORY_CONSENT_PATH: &str = "/api/personal/v1/auth/github/repositories/start";
pub(crate) const STATUS_PATH: &str = "/api/personal/v1/auth/github/status";
pub(crate) const CLAIM_PATH: &str = "/api/personal/v1/auth/github/claim";
pub(crate) const CANCEL_PATH: &str = "/api/personal/v1/auth/github/cancel";
pub(crate) const CALLBACK_PATH: &str = "/api/personal/v1/auth/github/callback";
pub(crate) const SESSION_PATH: &str = "/api/personal/v1/session";
pub(crate) const SIGN_OUT_PATH: &str = "/api/personal/v1/session/sign-out";
pub(crate) const DISCONNECT_PATH: &str = "/api/personal/v1/github/disconnect";

#[derive(Serialize)]
pub(crate) struct Capabilities {
    pub github: GitHubCapabilities,
}
#[derive(Serialize)]
pub(crate) struct GitHubCapabilities {
    pub enabled: bool,
}

/// BASE64URL_NO_PAD(SHA256(ASCII(nativeProof))); proof encodes 32 independent native CSPRNG bytes.
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct StartRequest {
    pub native_challenge: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StartReply {
    pub attempt_id: String,
    pub authorize_url: String,
    /// Present only for a device sign-in: the code the person types at `authorizeUrl`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_code: Option<String>,
    pub expires_at: u64,
    pub poll_after_ms: u32,
}

/// Proof is the original native secret, sent only in an HTTPS request body.
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct AttemptRequest {
    pub attempt_id: String,
    pub native_proof: String,
}

#[derive(Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum AttemptPhase {
    Pending,
    Ready,
    Failed,
    Cancelled,
    Expired,
}

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum FailureKind {
    Unavailable,
    Reconnect,
    Denied,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StatusReply {
    pub phase: AttemptPhase,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure: Option<FailureKind>,
}

/// Only a successful single-use claim returns this native Pully session, never a GitHub token.
#[derive(Serialize)]
pub(crate) struct ClaimReply {
    pub session: String,
    pub status: SessionStatus,
}

#[derive(Serialize)]
pub(crate) struct CancelReply {
    pub cancelled: bool,
}

pub(super) fn native_challenge(proof: &str) -> Result<String> {
    parse_fixed_secret(proof)?;
    Ok(URL_SAFE_NO_PAD.encode(Sha256::digest(proof.as_bytes())))
}

pub(super) fn parse_fixed_secret(encoded: &str) -> Result<[u8; 32]> {
    ensure!(
        super::provider::url_secret(encoded, 43, 43),
        "Invalid authorization proof"
    );
    let decoded: [u8; 32] = URL_SAFE_NO_PAD
        .decode(encoded)
        .ok()
        .and_then(|bytes| bytes.try_into().ok())
        .ok_or_else(|| anyhow::anyhow!("Invalid authorization proof"))?;
    ensure!(
        URL_SAFE_NO_PAD.encode(decoded) == encoded,
        "Invalid authorization proof"
    );
    Ok(decoded)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::personal_auth::provider::GitHubAuthority;
    use serde_json::json;

    #[test]
    fn exact_capability_pending_failure_and_once_only_claim_shapes_are_stable() {
        assert_eq!(
            serde_json::to_value(Capabilities {
                github: GitHubCapabilities { enabled: false }
            })
            .unwrap(),
            json!({"github":{"enabled":false}})
        );
        assert_eq!(
            serde_json::to_value(StatusReply {
                phase: AttemptPhase::Pending,
                failure: None
            })
            .unwrap(),
            json!({"phase":"pending"})
        );
        assert_eq!(
            serde_json::to_value(StatusReply {
                phase: AttemptPhase::Failed,
                failure: Some(FailureKind::Reconnect)
            })
            .unwrap(),
            json!({"phase":"failed","failure":"reconnect"})
        );
        assert_eq!(
            serde_json::to_value(StartReply {
                attempt_id: "opaque-attempt".into(),
                authorize_url: "https://github.com/login/oauth/authorize".into(),
                user_code: None,
                expires_at: 1792627200,
                poll_after_ms: 2000
            })
            .unwrap(),
            json!({"attemptId":"opaque-attempt","authorizeUrl":"https://github.com/login/oauth/authorize","expiresAt":1792627200u64,"pollAfterMs":2000})
        );
        assert_eq!(
            serde_json::to_value(StartReply {
                attempt_id: "opaque-attempt".into(),
                authorize_url: "https://github.com/login/device".into(),
                user_code: Some("WDJB-MJHT".into()),
                expires_at: 1792627200,
                poll_after_ms: 2000
            })
            .unwrap(),
            json!({"attemptId":"opaque-attempt","authorizeUrl":"https://github.com/login/device","userCode":"WDJB-MJHT","expiresAt":1792627200u64,"pollAfterMs":2000})
        );
        let result = serde_json::to_value(ClaimReply {
            session: "one-time-pully-session".into(),
            status: SessionStatus {
                account_id: "stable-account".into(),
                github_id: 42,
                login: "reader".into(),
                authority: GitHubAuthority::PublicRead,
                session_expires_at: 1795219200,
                access_expires_at: None,
                refresh_available: false,
            },
        })
        .unwrap();
        assert_eq!(
            result,
            json!({"session":"one-time-pully-session","status":{"accountId":"stable-account","githubId":42,"login":"reader","authority":"publicRead","sessionExpiresAt":1795219200u64,"accessExpiresAt":null,"refreshAvailable":false}})
        );
    }

    #[test]
    fn native_requests_cannot_supply_operator_app_redirect_session_or_target_account() {
        let valid =
            serde_json::from_value::<StartRequest>(json!({"nativeChallenge":"challenge"})).unwrap();
        assert_eq!(valid.native_challenge, "challenge");
        for forbidden in [
            "clientId",
            "clientSecret",
            "redirectUri",
            "returnUrl",
            "accountId",
            "session",
            "authority",
        ] {
            let mut request = json!({"nativeChallenge":"challenge"});
            request[forbidden] = json!("untrusted");
            assert!(serde_json::from_value::<StartRequest>(request).is_err());
        }
        let valid = serde_json::from_value::<AttemptRequest>(
            json!({"attemptId":"id","nativeProof":"secret"}),
        )
        .unwrap();
        assert_eq!(valid.attempt_id, "id");
        assert_eq!(valid.native_proof, "secret");
        assert!(serde_json::from_value::<AttemptRequest>(
            json!({"attemptId":"id","nativeProof":"secret","accountId":"other"})
        )
        .is_err());
    }

    #[test]
    fn native_proof_challenge_hashes_the_ascii_proof_with_unambiguous_s256() {
        let proof = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assert_eq!(
            native_challenge(proof).unwrap(),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
        assert_eq!(parse_fixed_secret(proof).unwrap().len(), 32);
        for invalid in ["short", &"/".repeat(43), &"a".repeat(43)] {
            assert!(native_challenge(invalid).is_err());
        }
    }
}
