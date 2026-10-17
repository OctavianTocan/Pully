//! Environment-seeded accounts, hashed bearer credentials and timestamped settings.
use crate::{iso_ms, now_ms, store::Store};
use anyhow::{bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock, Weak},
};

mod async_store;

pub const KEY_PREFIX: &str = "pully_";
pub const MAX_KEYS: usize = 50;
pub const MAX_KEY_NAME: usize = 60;
pub const MAX_SETTINGS: usize = 200;
pub const MAX_SETTING_BYTES: usize = 8 * 1024;
pub const MAX_SETTINGS_TOTAL: usize = 64 * 1024;
pub const SIGN_IN_TRIES: usize = 5;
pub const SIGN_IN_WINDOW_MS: u64 = 15 * 60 * 1000;

#[derive(Clone, Serialize, Deserialize)]
pub struct Account {
    pub id: String,
    pub email: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentKey {
    pub id: String,
    pub name: String,
    pub hint: String,
    pub created_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_used_at: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
struct PasswordHash {
    salt: String,
    iterations: u32,
    hash: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredAccount {
    #[serde(flatten)]
    account: Account,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    password: Option<PasswordHash>,
    created_at: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredKey {
    #[serde(flatten)]
    key: AgentKey,
    account_id: String,
}
#[derive(Serialize)]
#[serde(untagged)]
pub enum SignIn {
    Signed { session: String, account: Account },
    Error { error: &'static str },
}
#[derive(Serialize)]
pub struct CreatedKey {
    pub key: String,
    pub item: AgentKey,
}
#[derive(Serialize)]
pub struct KeyAccount {
    pub account: Account,
    pub key: AgentKey,
}

#[derive(Clone)]
pub struct Accounts {
    store: Store,
    serial: Arc<Mutex<()>>,
    failures: Arc<Mutex<HashMap<String, Vec<u64>>>>,
    env_seed: Arc<tokio::sync::OnceCell<Option<Account>>>,
}

pub fn sha256(text: &str) -> String {
    hex::encode(Sha256::digest(text.as_bytes()))
}
pub fn random_id(length: usize) -> Result<String> {
    const BASE62: &[u8] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    let mut out = String::with_capacity(length);
    while out.len() < length {
        let mut bytes = [0u8; 64];
        getrandom::getrandom(&mut bytes)
            .map_err(|_| anyhow::anyhow!("Secure random source unavailable"))?;
        for byte in bytes {
            if byte < 248 && out.len() < length {
                out.push(BASE62[(byte % 62) as usize] as char);
            }
        }
    }
    Ok(out)
}
fn hash_password(password: &str) -> Result<PasswordHash> {
    let mut salt = [0u8; 16];
    getrandom::getrandom(&mut salt)
        .map_err(|_| anyhow::anyhow!("Secure random source unavailable"))?;
    let mut hash = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, 100_000, &mut hash);
    Ok(PasswordHash {
        salt: STANDARD.encode(salt),
        iterations: 100_000,
        hash: STANDARD.encode(hash),
    })
}
fn check_password(password: &str, stored: &PasswordHash) -> Result<bool> {
    // Bound corrupted persisted work factors, without exposing the stored hash in errors.
    anyhow::ensure!(
        (1..=1_000_000).contains(&stored.iterations),
        "Invalid account password record"
    );
    let salt = STANDARD
        .decode(&stored.salt)
        .context("Invalid account password record")?;
    let expected = STANDARD
        .decode(&stored.hash)
        .context("Invalid account password record")?;
    anyhow::ensure!(
        salt.len() == 16 && expected.len() == 32,
        "Invalid account password record"
    );
    let mut hash = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, stored.iterations, &mut hash);
    Ok(hash
        .iter()
        .zip(expected)
        .fold(0u8, |different, (a, b)| different | (a ^ b))
        == 0)
}
// All handles for one canonical store root share the account transaction lock.
static ACCOUNT_LOCKS: OnceLock<Mutex<HashMap<std::path::PathBuf, Weak<Mutex<()>>>>> =
    OnceLock::new();
impl Accounts {
    pub fn new(store: Store) -> Self {
        let serial = {
            let mut locks = ACCOUNT_LOCKS
                .get_or_init(Default::default)
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            locks.retain(|_, lock| lock.strong_count() > 0);
            if let Some(lock) = locks.get(store.root()).and_then(Weak::upgrade) {
                lock
            } else {
                let lock = Arc::new(Mutex::new(()));
                locks.insert(store.root().to_path_buf(), Arc::downgrade(&lock));
                lock
            }
        };
        Self {
            store,
            serial,
            failures: Arc::new(Mutex::new(HashMap::new())),
            env_seed: Arc::new(tokio::sync::OnceCell::new()),
        }
    }
    fn account(&self, id: &str) -> Result<Option<StoredAccount>> {
        self.store
            .get(&format!("account:{id}"))?
            .map(serde_json::from_value)
            .transpose()
            .context("Invalid account record")
    }
    fn by_email(&self, email: &str) -> Result<Option<StoredAccount>> {
        match self
            .store
            .get(&format!("account-email:{}", email.trim().to_lowercase()))?
            .and_then(|v| v.as_str().map(str::to_owned))
        {
            Some(id) => self.account(&id),
            None => Ok(None),
        }
    }
    pub async fn seed_env(&self) -> Result<Option<Account>> {
        self.env_seed
            .get_or_try_init(|| {
                self.seed(
                    std::env::var("PULLY_ACCOUNT_EMAIL").ok(),
                    std::env::var("PULLY_ACCOUNT_PASSWORD").ok(),
                )
            })
            .await
            .cloned()
    }
    /// @cc [owner:OctavianTocan,label:security] rust-account-seed-hashes-password
    /// Environment seeding MUST store only salted PBKDF2 password hashes, with no registration route (Tavi, 16 Oct 12:40; plan 13).
    pub async fn seed(
        &self,
        email: Option<String>,
        password: Option<String>,
    ) -> Result<Option<Account>> {
        let this = self.clone();
        tokio::task::spawn_blocking(move || {
            let (email, password) = match (email, password) {
                (Some(e), Some(p)) if !e.trim().is_empty() && !p.is_empty() => {
                    (e.trim().to_owned(), p)
                }
                _ => return Ok(None),
            };
            let _guard = this
                .serial
                .lock()
                .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
            if let Some(mut existing) = this.by_email(&email)? {
                if !existing
                    .password
                    .as_ref()
                    .map(|hash| check_password(&password, hash))
                    .transpose()?
                    .unwrap_or(false)
                {
                    existing.password = Some(hash_password(&password)?);
                    this.store.set(
                        &format!("account:{}", existing.account.id),
                        &serde_json::to_value(&existing)?,
                    )?;
                }
                return Ok(Some(existing.account));
            }
            let stored = StoredAccount {
                account: Account {
                    id: random_id(16)?,
                    email: email.clone(),
                },
                password: Some(hash_password(&password)?),
                created_at: iso_ms(now_ms()),
            };
            this.store.set(
                &format!("account:{}", stored.account.id),
                &serde_json::to_value(&stored)?,
            )?;
            this.store.set(
                &format!("account-email:{}", email.to_lowercase()),
                &json!(stored.account.id),
            )?;
            Ok(Some(stored.account))
        })
        .await
        .context("Account initialization failed")?
    }
    pub async fn sign_in(&self, email: &str, password: &str) -> Result<SignIn> {
        self.sign_in_at(email, password, now_ms()).await
    }
    pub async fn sign_in_at(&self, email: &str, password: &str, now: u64) -> Result<SignIn> {
        let this = self.clone();
        let who = email.trim().to_lowercase();
        let password = password.to_owned();
        tokio::task::spawn_blocking(move || {
            // Serialize the check and insertion so concurrent attempts cannot bypass the limit.
            let mut failures = this
                .failures
                .lock()
                .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
            failures.retain(|_, times| {
                times.retain(|at| now.saturating_sub(*at) < SIGN_IN_WINDOW_MS);
                !times.is_empty()
            });
            if failures.get(&who).is_some_and(|v| v.len() >= SIGN_IN_TRIES) {
                return Ok(SignIn::Error { error: "wait" });
            }
            let account = this.by_email(&who)?;
            let right = if let Some(hash) = account.as_ref().and_then(|a| a.password.as_ref()) {
                check_password(&password, hash)?
            } else {
                hash_password(&password)?;
                false
            };
            if !right {
                failures.entry(who).or_default().push(now);
                return Ok(SignIn::Error { error: "wrong" });
            }
            failures.remove(&who);
            let account = account.context("Account unavailable")?.account;
            let session = random_id(43)?;
            this.store.set(
                &format!("account-session:{}", sha256(&session)),
                &json!({"accountId":account.id,"createdAt":iso_ms(now)}),
            )?;
            Ok(SignIn::Signed { session, account })
        })
        .await
        .context("Sign in unavailable")?
    }
    /// @cc [owner:OctavianTocan,label:security] rust-google-account-creation-serialized
    /// A verified Google email MUST reuse its existing account; concurrent creation
    /// and session writes MUST serialize, with no password created (Tavi, 16 Oct 16:52).
    pub(crate) async fn sign_in_with_google(&self, email: &str) -> Result<SignIn> {
        let this = self.clone();
        let email = email.trim().to_lowercase();
        anyhow::ensure!(
            crate::google_sign_in::allowed(&email),
            "Google email is not allowed"
        );
        crate::store::blocking(move || {
            let _guard = this
                .serial
                .lock()
                .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
            let session = random_id(43)?;
            let mut writes = Vec::new();
            let account = match this.by_email(&email)? {
                Some(stored) => stored.account,
                None => {
                    let stored = StoredAccount {
                        account: Account {
                            id: random_id(16)?,
                            email: email.clone(),
                        },
                        password: None,
                        created_at: iso_ms(now_ms()),
                    };
                    writes.push((
                        format!("account:{}", stored.account.id),
                        None,
                        Some(serde_json::to_value(&stored)?),
                    ));
                    writes.push((
                        format!("account-email:{email}"),
                        None,
                        Some(json!(stored.account.id)),
                    ));
                    stored.account
                }
            };
            writes.push((
                format!("account-session:{}", sha256(&session)),
                None,
                Some(json!({"accountId":account.id,"createdAt":iso_ms(now_ms())})),
            ));
            this.store.compare_exchange(&writes)?;
            Ok(SignIn::Signed { session, account })
        })
        .await
    }
    pub fn by_session(&self, token: Option<&str>) -> Result<Option<Account>> {
        let Some(token) = token.filter(|t| !t.is_empty() && t.len() <= 1024) else {
            return Ok(None);
        };
        let Some(session) = self
            .store
            .get(&format!("account-session:{}", sha256(token)))?
        else {
            return Ok(None);
        };
        match session["accountId"].as_str() {
            Some(id) => Ok(self.account(id)?.map(|a| a.account)),
            None => Ok(None),
        }
    }
    pub fn sign_out(&self, token: &str) -> Result<()> {
        self.store
            .remove(&format!("account-session:{}", sha256(token)))
    }
    fn stored_keys(&self, account_id: &str) -> Result<Vec<(String, StoredKey)>> {
        let mut out = Vec::new();
        for name in self.store.keys("agent-key:")? {
            let Some(value) = self.store.get(&name)? else {
                continue;
            };
            let Ok(key) = serde_json::from_value::<StoredKey>(value) else {
                continue;
            };
            if key.account_id == account_id {
                out.push((name, key));
            }
        }
        Ok(out)
    }
    pub fn keys(&self, account_id: &str) -> Result<Vec<AgentKey>> {
        let mut keys: Vec<_> = self
            .stored_keys(account_id)?
            .into_iter()
            .map(|(_, v)| v.key)
            .collect();
        keys.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        Ok(keys)
    }
    pub fn create_key(&self, account_id: &str, name: &str) -> Result<CreatedKey> {
        let _guard = self
            .serial
            .lock()
            .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
        anyhow::ensure!(self.account(account_id)?.is_some(), "Sign in first.");
        let label: String = name.trim().chars().take(MAX_KEY_NAME).collect();
        anyhow::ensure!(!label.is_empty(), "A key needs a name.");
        anyhow::ensure!(
            self.stored_keys(account_id)?.len() < MAX_KEYS,
            "An account can hold 50 keys. Revoke one first."
        );
        let key = format!("{KEY_PREFIX}{}", random_id(32)?);
        let item = AgentKey {
            id: random_id(12)?,
            name: label,
            hint: key[..KEY_PREFIX.len() + 4].into(),
            created_at: iso_ms(now_ms()),
            last_used_at: None,
        };
        self.store.set(
            &format!("agent-key:{}", sha256(&key)),
            &serde_json::to_value(StoredKey {
                key: item.clone(),
                account_id: account_id.into(),
            })?,
        )?;
        Ok(CreatedKey { key, item })
    }
    pub fn revoke_key(&self, account_id: &str, id: &str) -> Result<bool> {
        let _guard = self
            .serial
            .lock()
            .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
        for (name, stored) in self.stored_keys(account_id)? {
            if stored.key.id == id {
                self.store.remove(&name)?;
                return Ok(true);
            }
        }
        Ok(false)
    }
    pub fn by_key(&self, key: Option<&str>) -> Result<Option<KeyAccount>> {
        let Some(key) = key.filter(|k| k.starts_with(KEY_PREFIX) && k.len() <= 1024) else {
            return Ok(None);
        };
        let _guard = self
            .serial
            .lock()
            .map_err(|_| anyhow::anyhow!("Account lock unavailable"))?;
        let name = format!("agent-key:{}", sha256(key));
        let Some(value) = self.store.get(&name)? else {
            return Ok(None);
        };
        let stored: StoredKey = serde_json::from_value(value).context("Invalid key record")?;
        let Some(account) = self.account(&stored.account_id)? else {
            return Ok(None);
        };
        let now = now_ms();
        // ISO timestamps have sortable fixed-width fields for ordinary civil dates.
        if stored
            .key
            .last_used_at
            .as_deref()
            .is_none_or(|last| last < iso_ms(now.saturating_sub(60_000)).as_str())
        {
            let mut updated = stored.clone();
            updated.key.last_used_at = Some(iso_ms(now));
            self.store.set(&name, &serde_json::to_value(updated)?)?;
        }
        Ok(Some(KeyAccount {
            account: account.account,
            key: stored.key,
        }))
    }
    pub fn settings(&self, account_id: &str) -> Result<Value> {
        Ok(self
            .store
            .get(&format!("account-settings:{account_id}"))?
            .and_then(|v| v.get("values").filter(|v| v.is_object()).cloned())
            .unwrap_or(json!({})))
    }
    /// @cc [owner:OctavianTocan,label:product;architecture] rust-settings-merge-is-atomic
    /// Each setting MUST replace stored data only with a strictly newer timestamp; merges MUST serialize and enforce 200 settings, 8 KB per value and 64 KB total (Tavi, 16 Oct 12:47).
    pub fn merge_settings(&self, account_id: &str, values: Value) -> Result<Value> {
        let values =
            clean_settings(&values).context("Settings need `values`: { name: { value, at } }.")?;
        let result = self
            .store
            .update(&format!("account-settings:{account_id}"), |old| {
                let mut merged = old
                    .and_then(|v| v.get("values").and_then(Value::as_object).cloned())
                    .unwrap_or_default();
                for (name, setting) in values.as_object().expect("clean settings object") {
                    if merged
                        .get(name)
                        .and_then(|s| s["at"].as_f64())
                        .is_none_or(|at| at < setting["at"].as_f64().unwrap())
                    {
                        merged.insert(name.clone(), setting.clone());
                    }
                }
                if merged.len() > MAX_SETTINGS {
                    bail!("An account keeps at most 200 settings.");
                }
                let values = Value::Object(merged);
                if serde_json::to_string(&values)?.encode_utf16().count() > MAX_SETTINGS_TOTAL {
                    bail!("An account keeps at most 64 KB of settings.");
                }
                Ok(json!({"values":values}))
            })?;
        Ok(result["values"].clone())
    }
}
pub fn clean_settings(input: &Value) -> Option<Value> {
    let mut out = Map::new();
    for (name, raw) in input.as_object()? {
        if name.is_empty()
            || name.len() > 64
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
        {
            continue;
        }
        let Some(at) = raw
            .get("at")
            .and_then(Value::as_f64)
            .filter(|n| n.is_finite())
        else {
            continue;
        };
        let Some(value) = raw.get("value") else {
            continue;
        };
        if serde_json::to_string(value).ok()?.encode_utf16().count() > MAX_SETTING_BYTES {
            continue;
        }
        out.insert(name.clone(), json!({"value":value,"at":at}));
    }
    Some(Value::Object(out))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn accounts() -> (Accounts, std::path::PathBuf) {
        let root = std::env::temp_dir().join(format!("pulls-account-{}", uuid::Uuid::new_v4()));
        (Accounts::new(Store::new(root.clone()).unwrap()), root)
    }
    #[tokio::test]
    async fn google_concurrent_signup_reuses_one_account_and_seed_can_add_password() {
        let (accounts, root) = accounts();
        let other = Accounts::new(Store::new(root.clone()).unwrap());
        let mut jobs = Vec::new();
        for i in 0..12 {
            let handle = if i % 2 == 0 {
                accounts.clone()
            } else {
                other.clone()
            };
            jobs.push(tokio::spawn(async move {
                handle
                    .sign_in_with_google(" Person@OctavianTocan.com ")
                    .await
                    .unwrap()
            }));
        }
        let mut ids = std::collections::HashSet::new();
        let mut sessions = std::collections::HashSet::new();
        for job in jobs {
            let SignIn::Signed { session, account } = job.await.unwrap() else {
                panic!("missing session")
            };
            assert_eq!(account.email, "person@octaviantocan.com");
            assert_eq!(
                accounts.by_session(Some(&session)).unwrap().unwrap().id,
                account.id
            );
            ids.insert(account.id);
            sessions.insert(session);
        }
        assert_eq!(ids.len(), 1);
        assert_eq!(sessions.len(), 12);
        assert_eq!(accounts.store.keys("account:").unwrap().len(), 1);
        let id = ids.into_iter().next().unwrap();
        assert!(accounts
            .store
            .get(&format!("account:{id}"))
            .unwrap()
            .unwrap()
            .get("password")
            .is_none());
        assert!(matches!(
            accounts
                .sign_in("person@octaviantocan.com", "")
                .await
                .unwrap(),
            SignIn::Error { error: "wrong" }
        ));
        let seeded = accounts
            .seed(
                Some("PERSON@octaviantocan.com".into()),
                Some("test-password".into()),
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!(seeded.id, id);
        assert!(matches!(
            accounts
                .sign_in("person@octaviantocan.com", "test-password")
                .await
                .unwrap(),
            SignIn::Signed { .. }
        ));
        assert!(accounts
            .sign_in_with_google("forbidden@example.com")
            .await
            .is_err());
        assert_eq!(accounts.store.keys("account:").unwrap().len(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn cleaner_filters_invalid_values_and_merge_keeps_newer_fields() {
        let (accounts, root) = accounts();
        let cleaned = clean_settings(&json!({"theme":{"value":"dark","at":2},"bad key":{"value":1,"at":3},"missing":{"at":2},"null":{"value":null,"at":4}})).unwrap();
        assert_eq!(cleaned.as_object().unwrap().len(), 2);
        accounts.merge_settings("test", cleaned).unwrap();
        let merged = accounts
            .merge_settings(
                "test",
                json!({"theme":{"value":"light","at":2},"sound":{"value":true,"at":1}}),
            )
            .unwrap();
        assert_eq!(merged["theme"]["value"], "dark");
        assert_eq!(merged["sound"]["value"], true);
        assert!(merged["null"]["value"].is_null());
        assert!(clean_settings(&json!([])).is_none());
        let too_large = "x".repeat(MAX_SETTING_BYTES);
        assert_eq!(
            clean_settings(&json!({"large":{"value":too_large,"at":1}})).unwrap(),
            json!({})
        );
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn account_credentials_are_hashed_and_revocation_is_scoped() {
        let (accounts, root) = accounts();
        let account = accounts
            .seed(
                Some(" Person@Example.com ".into()),
                Some("test-password".into()),
            )
            .await
            .unwrap()
            .unwrap();
        let signed = accounts
            .sign_in("person@example.com", "test-password")
            .await
            .unwrap();
        let SignIn::Signed { session, .. } = signed else {
            panic!("sign in failed")
        };
        assert_eq!(
            accounts.by_session(Some(&session)).unwrap().unwrap().id,
            account.id
        );
        let created = accounts.create_key(&account.id, " Agent ").unwrap();
        assert_eq!(created.item.name, "Agent");
        assert_eq!(
            accounts
                .by_key(Some(&created.key))
                .unwrap()
                .unwrap()
                .account
                .id,
            account.id
        );
        assert!(!accounts
            .revoke_key("other-account", &created.item.id)
            .unwrap());
        for name in accounts.store.keys("").unwrap() {
            let contents = serde_json::to_string(&accounts.store.get(&name).unwrap()).unwrap();
            assert!(!contents.contains("test-password"));
            assert!(!contents.contains(&session));
            assert!(!contents.contains(&created.key));
            assert!(!name.contains(&session));
            assert!(!name.contains(&created.key));
        }
        accounts.sign_out(&session).unwrap();
        assert!(accounts.by_session(Some(&session)).unwrap().is_none());
        assert!(accounts.revoke_key(&account.id, &created.item.id).unwrap());
        assert!(accounts.by_key(Some(&created.key)).unwrap().is_none());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn sign_in_waits_after_five_failures_even_for_correct_password() {
        let (accounts, root) = accounts();
        accounts
            .seed(Some("person@example.com".into()), Some("right".into()))
            .await
            .unwrap();
        // Inject only the first four timestamps to keep this compatibility test lightweight.
        accounts
            .failures
            .lock()
            .unwrap()
            .insert("person@example.com".into(), vec![1000; 4]);
        assert!(matches!(
            accounts
                .sign_in_at("person@example.com", "wrong", 1000)
                .await
                .unwrap(),
            SignIn::Error { error: "wrong" }
        ));
        assert!(matches!(
            accounts
                .sign_in_at("person@example.com", "right", 1001)
                .await
                .unwrap(),
            SignIn::Error { error: "wait" }
        ));
        assert!(matches!(
            accounts
                .sign_in_at("person@example.com", "right", 1000 + SIGN_IN_WINDOW_MS)
                .await
                .unwrap(),
            SignIn::Signed { .. }
        ));
        std::fs::remove_dir_all(root).unwrap();
    }
}
