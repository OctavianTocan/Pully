//! Personal GitHub authorization primitives. No listener or legacy RPC exposes this module.
mod crypto;
mod flow;
mod grant;
mod host;
mod http;
mod provider;
mod vault;
pub(crate) mod wire;

pub(crate) use crypto::{VaultContext, VaultEnvelope, VaultKeys, VaultPurpose};
pub(crate) use flow::{AuthError, CurrentPersonalSession, PersonalAuth};
pub(crate) use grant::{account_key, GrantedSessions, SessionGrant};
pub use grant::{SessionAuthority, GRANT_HEADER};
pub(crate) use host::{PersonalAuthHost, PersonalAuthHostConfig};
pub(crate) use http::bearer as personal_bearer;
pub(crate) use http::{configured_router, unconfigured_router};
pub(crate) use provider::{GitHubAuthority, OAuthConfig};

pub(super) trait AuthClock: Send + Sync {
    fn now(&self) -> u64;
}

pub(super) struct SystemClock;
impl AuthClock for SystemClock {
    fn now(&self) -> u64 {
        web_time::SystemTime::now()
            .duration_since(web_time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }
}

#[cfg(test)]
mod contract_tests {
    use crate::{
        account::Account,
        identity::{RequestIdentity, VerifiedGitHubIdentity},
    };

    #[test]
    fn stable_account_accessor_requires_an_active_personal_binding() {
        assert!(RequestIdentity::Guest.account_id().is_err());
        assert!(RequestIdentity::LegacyPrivate.account_id().is_err());
        let verified =
            VerifiedGitHubIdentity::fixture(42, "a-renamable-login", "fixture-token").unwrap();
        let credential = verified
            .bind(&Account {
                id: "stable-account-a".into(),
                email: String::new(),
            })
            .unwrap();
        let personal = RequestIdentity::Account(credential.clone());
        assert_eq!(personal.account_id().unwrap(), "stable-account-a");
        credential.revoke();
        assert!(personal.account_id().is_err());
    }

    #[test]
    fn fake_provider_still_uses_the_canonical_verified_response_parser() {
        assert!(VerifiedGitHubIdentity::fixture(0, "login", "fixture").is_err());
        assert!(VerifiedGitHubIdentity::fixture(42, "", "fixture").is_err());
        assert!(VerifiedGitHubIdentity::fixture(42, "login\nspoof", "fixture").is_err());
    }
}
