//! Personal GitHub authorization primitives. No listener or legacy RPC exposes this module.
mod crypto;

pub(crate) use crypto::{VaultContext, VaultEnvelope, VaultKeys};

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
