use anyhow::{ensure, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

const FORMAT: u32 = 1;
const MAX_RECORD_BYTES: usize = 64 * 1024;

/// Keys are supplied by the operator's secret store; no environment/CLI credential fallback exists.
pub(crate) struct VaultKeys {
    active: u32,
    keys: BTreeMap<u32, LessSafeKey>,
}

/// This context is supplied from the authenticated account record, never the envelope being opened.
pub(crate) struct VaultContext<'a> {
    pub account_id: &'a str,
    pub github_id: u64,
    pub revision: u64,
    pub purpose: VaultPurpose,
}

/// @cc [owner:OctavianTocan,label:security;architecture] siwc-vault-has-an-independent-owner-domain
/// SIWC credentials MUST authenticate the verified AI owner, connection ID/revision and
/// OpenAI issuer, issued client and immutable subject in a domain distinct from GitHub
/// credentials/sessions. Email equality and dynamic_agent_client MUST NOT select an issued
/// registration. Existing GitHub/session AAD bytes MUST remain unchanged. Source: Tavi's
/// 22 Oct independent AI-principal decision and official SIWC accounts/session contract.
pub(crate) struct AiVaultContext<'a> {
    pub owner_id: &'a str,
    pub connection_id: &'a str,
    pub revision: u64,
    pub issuer: &'a str,
    pub client_id: &'a str,
    pub subject: &'a str,
}

#[derive(Clone, Copy)]
pub(crate) enum VaultPurpose {
    GitHubCredential,
    PersonalSession,
    PiCredential,
}

/// Only authenticated ciphertext and nonsecret format/key metadata can be persisted.
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct VaultEnvelope {
    format: u32,
    key_version: u32,
    nonce: String,
    ciphertext: String,
}

impl VaultKeys {
    pub(crate) fn new(active: u32, supplied: BTreeMap<u32, [u8; 32]>) -> Result<Self> {
        ensure!(
            active > 0 && (1..=8).contains(&supplied.len()),
            "Invalid vault key configuration"
        );
        ensure!(
            supplied.contains_key(&active) && supplied.keys().all(|key| *key > 0),
            "Invalid vault key configuration"
        );
        let keys = supplied
            .into_iter()
            .map(|(version, bytes)| {
                UnboundKey::new(&AES_256_GCM, &bytes)
                    .map(|key| (version, LessSafeKey::new(key)))
                    .map_err(|_| anyhow::anyhow!("Invalid vault key configuration"))
            })
            .collect::<Result<_>>()?;
        Ok(Self { active, keys })
    }

    pub(crate) fn seal(
        &self,
        context: &VaultContext<'_>,
        plaintext: &[u8],
    ) -> Result<VaultEnvelope> {
        self.seal_aad(context.aad(self.active)?, plaintext)
    }

    pub(crate) fn seal_ai(
        &self,
        context: &AiVaultContext<'_>,
        plaintext: &[u8],
    ) -> Result<VaultEnvelope> {
        self.seal_aad(context.aad(self.active)?, plaintext)
    }

    fn seal_aad(&self, aad: Vec<u8>, plaintext: &[u8]) -> Result<VaultEnvelope> {
        ensure!(
            plaintext.len() <= MAX_RECORD_BYTES,
            "Personal credential record is too large"
        );
        let mut nonce = [0u8; 12];
        getrandom::getrandom(&mut nonce)
            .map_err(|_| anyhow::anyhow!("Secure random source unavailable"))?;
        let mut ciphertext = plaintext.to_vec();
        self.keys
            .get(&self.active)
            .ok_or_else(|| anyhow::anyhow!("Vault key is unavailable"))?
            .seal_in_place_append_tag(
                Nonce::assume_unique_for_key(nonce),
                Aad::from(aad),
                &mut ciphertext,
            )
            .map_err(|_| anyhow::anyhow!("Could not protect personal credentials"))?;
        Ok(VaultEnvelope {
            format: FORMAT,
            key_version: self.active,
            nonce: URL_SAFE_NO_PAD.encode(nonce),
            ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
        })
    }

    pub(crate) fn open(
        &self,
        context: &VaultContext<'_>,
        envelope: &VaultEnvelope,
    ) -> Result<Vec<u8>> {
        self.open_aad(context.aad(envelope.key_version)?, envelope)
    }

    pub(crate) fn open_ai(
        &self,
        context: &AiVaultContext<'_>,
        envelope: &VaultEnvelope,
    ) -> Result<Vec<u8>> {
        self.open_aad(context.aad(envelope.key_version)?, envelope)
    }

    fn open_aad(&self, aad: Vec<u8>, envelope: &VaultEnvelope) -> Result<Vec<u8>> {
        ensure!(
            envelope.format == FORMAT
                && envelope.nonce.len() == 16
                && envelope.ciphertext.len()
                    <= (MAX_RECORD_BYTES + AES_256_GCM.tag_len()).div_ceil(3) * 4,
            "Invalid personal credential record"
        );
        let key = self
            .keys
            .get(&envelope.key_version)
            .ok_or_else(|| anyhow::anyhow!("Vault key is unavailable"))?;
        let nonce: [u8; 12] = URL_SAFE_NO_PAD
            .decode(&envelope.nonce)
            .ok()
            .and_then(|bytes| bytes.try_into().ok())
            .ok_or_else(|| anyhow::anyhow!("Invalid personal credential record"))?;
        let mut ciphertext = URL_SAFE_NO_PAD
            .decode(&envelope.ciphertext)
            .map_err(|_| anyhow::anyhow!("Invalid personal credential record"))?;
        ensure!(
            ciphertext.len() >= AES_256_GCM.tag_len()
                && ciphertext.len() <= MAX_RECORD_BYTES + AES_256_GCM.tag_len(),
            "Invalid personal credential record"
        );
        let plaintext = key
            .open_in_place(
                Nonce::assume_unique_for_key(nonce),
                Aad::from(aad),
                &mut ciphertext,
            )
            .map_err(|_| anyhow::anyhow!("Personal credentials could not be opened"))?;
        Ok(plaintext.to_vec())
    }
}

impl AiVaultContext<'_> {
    fn aad(&self, key_version: u32) -> Result<Vec<u8>> {
        ensure!(
            self.revision > 0
                && key_version > 0
                && [self.owner_id, self.connection_id]
                    .into_iter()
                    .all(|value| {
                        !value.is_empty()
                            && value.len() <= 128
                            && value
                                .bytes()
                                .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
                    })
                && self.issuer == "https://auth.openai.com"
                && !self.client_id.is_empty()
                && self.client_id != "dynamic_agent_client"
                && self.client_id.len() <= 256
                && self
                    .client_id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
                && !self.subject.is_empty()
                && self.subject.len() <= 2048
                && !self.subject.chars().any(char::is_control),
            "Invalid AI credential owner"
        );
        let mut aad = b"pully-ai-subscription-vault\0openai-siwc\0".to_vec();
        aad.extend_from_slice(&FORMAT.to_be_bytes());
        aad.extend_from_slice(&key_version.to_be_bytes());
        for value in [
            self.owner_id,
            self.connection_id,
            self.issuer,
            self.client_id,
            self.subject,
        ] {
            aad.extend_from_slice(&(value.len() as u32).to_be_bytes());
            aad.extend_from_slice(value.as_bytes());
        }
        aad.extend_from_slice(&self.revision.to_be_bytes());
        Ok(aad)
    }
}

impl VaultContext<'_> {
    fn aad(&self, key_version: u32) -> Result<Vec<u8>> {
        ensure!(
            !self.account_id.is_empty()
                && self.account_id.len() <= 128
                && self
                    .account_id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte))
                && self.github_id > 0
                && self.revision > 0,
            "Invalid personal credential owner"
        );
        let (prefix, purpose): (&[u8], u8) = match self.purpose {
            VaultPurpose::GitHubCredential => (b"pully-personal-github-vault\0github.com\0", 1),
            VaultPurpose::PersonalSession => (b"pully-personal-github-vault\0github.com\0", 2),
            VaultPurpose::PiCredential => {
                (b"pully-personal-provider-vault\0pi-openai-compatible\0", 1)
            }
        };
        let mut aad = prefix.to_vec();
        aad.push(purpose);
        aad.extend_from_slice(&FORMAT.to_be_bytes());
        aad.extend_from_slice(&key_version.to_be_bytes());
        aad.extend_from_slice(&(self.account_id.len() as u32).to_be_bytes());
        aad.extend_from_slice(self.account_id.as_bytes());
        aad.extend_from_slice(&self.github_id.to_be_bytes());
        aad.extend_from_slice(&self.revision.to_be_bytes());
        Ok(aad)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn owner() -> VaultContext<'static> {
        VaultContext {
            account_id: "account-a",
            github_id: 42,
            revision: 1,
            purpose: VaultPurpose::GitHubCredential,
        }
    }
    fn keyring(active: u32) -> VaultKeys {
        VaultKeys::new(active, BTreeMap::from([(1, [17; 32]), (2, [29; 32])])).unwrap()
    }

    fn ai_owner() -> AiVaultContext<'static> {
        AiVaultContext {
            owner_id: "independent-ai-owner",
            connection_id: "connection-a",
            revision: 1,
            issuer: "https://auth.openai.com",
            client_id: "oaiapp_fixture_a",
            subject: "verified-openai-subject",
        }
    }

    #[test]
    fn ai_credentials_bind_owner_connection_registration_subject_and_revision() {
        let keys = keyring(1);
        let sealed = keys
            .seal_ai(&ai_owner(), b"fixture-subscription-refresh")
            .unwrap();
        assert_eq!(
            keys.open_ai(&ai_owner(), &sealed).unwrap(),
            b"fixture-subscription-refresh"
        );
        for changed in [
            AiVaultContext {
                owner_id: "different-owner",
                ..ai_owner()
            },
            AiVaultContext {
                connection_id: "different-connection",
                ..ai_owner()
            },
            AiVaultContext {
                client_id: "oaiapp_fixture_b",
                ..ai_owner()
            },
            AiVaultContext {
                subject: "different-subject",
                ..ai_owner()
            },
            AiVaultContext {
                revision: 2,
                ..ai_owner()
            },
        ] {
            assert!(keys.open_ai(&changed, &sealed).is_err());
        }
        assert!(keys.open(&owner(), &sealed).is_err());
        let github = keys.seal(&owner(), b"fixture-github").unwrap();
        assert!(keys.open_ai(&ai_owner(), &github).is_err());
        assert!(!serde_json::to_string(&sealed)
            .unwrap()
            .contains("fixture-subscription-refresh"));
    }

    #[test]
    fn github_aad_stays_byte_identical_and_ai_cannot_use_dynamic_registration_identity() {
        let expected = concat!(
            "70756c6c792d706572736f6e616c2d6769746875622d7661756c74006769746875622e636f6d00",
            "010000000100000001000000096163636f756e742d61000000000000002a0000000000000001"
        );
        assert_eq!(hex::encode(owner().aad(1).unwrap()), expected);
        assert!(keyring(1)
            .seal_ai(
                &AiVaultContext {
                    client_id: "dynamic_agent_client",
                    ..ai_owner()
                },
                b"credential"
            )
            .is_err());
        assert!(keyring(1)
            .seal_ai(
                &AiVaultContext {
                    issuer: "https://other.example",
                    ..ai_owner()
                },
                b"credential"
            )
            .is_err());
    }

    #[test]
    fn adding_pi_preserves_both_existing_github_vault_aad_formats() {
        let mut context = owner();
        assert_eq!(hex::encode(context.aad(1).unwrap()), "70756c6c792d706572736f6e616c2d6769746875622d7661756c74006769746875622e636f6d00010000000100000001000000096163636f756e742d61000000000000002a0000000000000001");
        context.purpose = VaultPurpose::PersonalSession;
        assert_eq!(hex::encode(context.aad(1).unwrap()), "70756c6c792d706572736f6e616c2d6769746875622d7661756c74006769746875622e636f6d00020000000100000001000000096163636f756e742d61000000000000002a0000000000000001");
    }

    #[test]
    fn pi_credentials_cannot_be_opened_as_github_credentials_or_sessions() {
        let keys = keyring(1);
        let mut context = owner();
        context.purpose = VaultPurpose::PiCredential;
        let sealed = keys.seal(&context, b"synthetic-pi-credential").unwrap();
        assert_eq!(
            keys.open(&context, &sealed).unwrap(),
            b"synthetic-pi-credential"
        );
        context.purpose = VaultPurpose::GitHubCredential;
        assert!(keys.open(&context, &sealed).is_err());
        context.purpose = VaultPurpose::PersonalSession;
        assert!(keys.open(&context, &sealed).is_err());
    }

    #[test]
    fn real_aead_uses_fresh_nonces_and_only_exposes_ciphertext() {
        let keys = keyring(1);
        let first = keys.seal(&owner(), b"fixture-access-token").unwrap();
        let second = keys.seal(&owner(), b"fixture-access-token").unwrap();
        assert_ne!(first.nonce, second.nonce);
        assert_ne!(first.ciphertext, second.ciphertext);
        assert_eq!(
            keys.open(&owner(), &first).unwrap(),
            b"fixture-access-token"
        );
        assert!(!serde_json::to_string(&first)
            .unwrap()
            .contains("fixture-access-token"));
    }

    #[test]
    fn copying_a_record_to_another_account_subject_or_revision_fails_closed() {
        let keys = keyring(1);
        let sealed = keys.seal(&owner(), b"private-token").unwrap();
        for context in [
            VaultContext {
                account_id: "account-b",
                ..owner()
            },
            VaultContext {
                github_id: 43,
                ..owner()
            },
            VaultContext {
                revision: 2,
                ..owner()
            },
            VaultContext {
                purpose: VaultPurpose::PersonalSession,
                ..owner()
            },
        ] {
            assert!(keys.open(&context, &sealed).is_err());
        }
    }

    #[test]
    fn tampering_or_missing_rotation_keys_never_returns_plaintext() {
        let keys = keyring(1);
        let mut sealed = keys.seal(&owner(), b"private-token").unwrap();
        let mut corrupted = URL_SAFE_NO_PAD.decode(&sealed.ciphertext).unwrap();
        corrupted[0] ^= 1;
        sealed.ciphertext = URL_SAFE_NO_PAD.encode(corrupted);
        assert!(keys.open(&owner(), &sealed).is_err());
        let old = keys.seal(&owner(), b"old-token").unwrap();
        let rotated = keyring(2);
        assert_eq!(rotated.open(&owner(), &old).unwrap(), b"old-token");
        let current = rotated.seal(&owner(), b"new-token").unwrap();
        assert_eq!(current.key_version, 2);
        let retired = VaultKeys::new(2, BTreeMap::from([(2, [29; 32])])).unwrap();
        assert!(retired.open(&owner(), &old).is_err());
        assert_eq!(retired.open(&owner(), &current).unwrap(), b"new-token");
    }
}
