use super::{
    policy::{Endpoint, Route},
    ports::{AiCredentialCapability, AiFuture, AiPrincipal},
    types::{MAX_PI_CREDENTIAL_BYTES, MAX_PI_ENDPOINT_BYTES, MAX_PI_MODEL_BYTES},
    ConnectionKind, ConnectionState, ConnectionStatus, ExpectedConnection, PiCatalog,
    PiCredentialChange, PiEndpointPolicy, PiSetupInput, ProviderCapabilities,
};
use crate::{
    identity::RequestIdentity,
    personal_auth::{VaultContext, VaultEnvelope, VaultKeys, VaultPurpose},
    store::Store,
};
use anyhow::{ensure, Result};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, OnceLock, Weak,
    },
    time::{Duration, Instant},
};
use tokio::sync::watch;

const STATE_KEY: &str = "personal-provider-v1:pi";
const MAX_OWNERS: usize = 1024;
const MAX_LEASES: usize = 256;
const MAX_CATALOG_BYTES: usize = 4 * 1024 * 1024;
const MAX_CATALOG_ENTRIES: usize = 1024;
const CATALOG_TTL: Duration = Duration::from_secs(10 * 60);

#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Database {
    owners: BTreeMap<String, Slot>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct Slot {
    github_id: u64,
    revision: u64,
    connection: Option<StoredPi>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct StoredPi {
    id: String,
    sealed: VaultEnvelope,
}
/// The endpoint/model belong to the encrypted record too: modifying a public URL must not redirect a key.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct PiSecret {
    connection_id: String,
    endpoint: String,
    preferred_model: Option<String>,
    credential: Option<String>,
}

struct PersonalPrincipal {
    identity: RequestIdentity,
    account_id: String,
    github_id: u64,
}
impl PersonalPrincipal {
    fn capture(identity: &RequestIdentity) -> Result<Arc<Self>> {
        let account_id = identity.account_id().map_err(|_| unavailable())?.to_owned();
        let RequestIdentity::Account(credential) = identity else {
            return Err(unavailable());
        };
        Ok(Arc::new(Self {
            identity: identity.clone(),
            account_id,
            github_id: credential.github_id(),
        }))
    }
    fn context(&self, revision: u64) -> VaultContext<'_> {
        VaultContext {
            account_id: &self.account_id,
            github_id: self.github_id,
            revision,
            purpose: VaultPurpose::PiCredential,
        }
    }
}
impl AiPrincipal for PersonalPrincipal {
    fn owner_id(&self) -> &str {
        &self.account_id
    }
    fn ensure_current(&self) -> Result<()> {
        self.identity.ensure_current().map_err(|_| unavailable())
    }
    fn subscribe_retirement(&self) -> Result<watch::Receiver<bool>> {
        self.ensure_current()?;
        let RequestIdentity::Account(credential) = &self.identity else {
            return Err(unavailable());
        };
        credential.subscribe_revocation().map_err(|_| unavailable())
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct LeaseKey {
    owner: String,
    id: String,
    revision: u64,
}
struct LeaseState {
    retired: watch::Sender<bool>,
    catalog: Mutex<Option<(Instant, Vec<serde_json::Value>)>>,
    reconnect: AtomicBool,
    refresh: tokio::sync::Mutex<()>,
}
#[derive(Default)]
struct Runtime {
    leases: HashMap<LeaseKey, Arc<LeaseState>>,
    blocked: HashMap<String, LeaseKey>,
}
impl Runtime {
    fn for_store(store: &Store) -> Arc<Mutex<Self>> {
        static RUNTIMES: OnceLock<Mutex<HashMap<PathBuf, Weak<Mutex<Runtime>>>>> = OnceLock::new();
        let mut runtimes = RUNTIMES
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        runtimes.retain(|_, state| state.strong_count() > 0);
        if let Some(runtime) = runtimes.get(store.root()).and_then(Weak::upgrade) {
            return runtime;
        }
        let runtime = Arc::new(Mutex::new(Self::default()));
        runtimes.insert(store.root().to_owned(), Arc::downgrade(&runtime));
        runtime
    }
    fn lease(&mut self, key: &LeaseKey) -> Result<Arc<LeaseState>> {
        ensure!(!self.is_blocked(key), "Pi connection needs setup again");
        if let Some(state) = self.leases.get(key) {
            return Ok(state.clone());
        }
        if self.leases.len() >= MAX_LEASES {
            let disposable = self
                .leases
                .iter()
                .find(|(_, state)| Arc::strong_count(state) == 1)
                .map(|(key, _)| key.clone());
            if let Some(disposable) = disposable {
                self.leases.remove(&disposable);
            }
        }
        ensure!(self.leases.len() < MAX_LEASES, "Pi connections are busy");
        let state = Arc::new(LeaseState {
            retired: watch::channel(false).0,
            catalog: Mutex::new(None),
            reconnect: AtomicBool::new(false),
            refresh: tokio::sync::Mutex::new(()),
        });
        self.leases.insert(key.clone(), state.clone());
        Ok(state)
    }
    fn retire_before(&mut self, owner: &str, revision: u64) {
        self.leases.retain(|key, state| {
            if key.owner == owner && key.revision < revision {
                state.retired.send_replace(true);
                false
            } else {
                true
            }
        });
        self.blocked
            .retain(|_, key| key.owner != owner || key.revision >= revision);
    }
    fn block(&mut self, key: &LeaseKey) {
        if self
            .blocked
            .get(&key.owner)
            .is_none_or(|current| current.revision <= key.revision)
        {
            self.blocked.insert(key.owner.clone(), key.clone());
        }
        if let Some(state) = self.leases.remove(key) {
            state.retired.send_replace(true);
        }
    }
    fn is_blocked(&self, key: &LeaseKey) -> bool {
        self.blocked.get(&key.owner) == Some(key)
    }
}

struct Inner {
    store: Store,
    keys: Arc<VaultKeys>,
    policy: Arc<PiEndpointPolicy>,
    runtime: Arc<Mutex<Runtime>>,
    catalog_timeout: Duration,
}
#[derive(Clone)]
pub(crate) struct PersonalProviders(Arc<Inner>);
impl PersonalProviders {
    /// This is one GitHub-backed personal capability, not the global AI eligibility gate.
    pub(crate) fn new(store: Store, keys: Arc<VaultKeys>, policy: Arc<PiEndpointPolicy>) -> Self {
        let runtime = Runtime::for_store(&store);
        Self(Arc::new(Inner {
            store,
            keys,
            policy,
            runtime,
            catalog_timeout: Duration::from_secs(15),
        }))
    }

    pub(crate) async fn load(&self, identity: &RequestIdentity) -> Result<Vec<ConnectionStatus>> {
        let principal = PersonalPrincipal::capture(identity)?;
        let owner = principal.clone();
        let inner = self.0.clone();
        self.0
            .store
            .transaction(move |store| {
                owner.ensure_current()?;
                let database = read(&store)?;
                let pi = status(&inner, &owner, database.owners.get(&owner.account_id))?;
                owner.ensure_current()?;
                Ok(vec![
                    unsupported(&owner, ConnectionKind::ClaudeSubscription),
                    unsupported(&owner, ConnectionKind::ChatGptSubscription),
                    pi,
                ])
            })
            .await
            .map_err(|_| unavailable())
    }

    pub(crate) async fn configure_pi(
        &self,
        identity: &RequestIdentity,
        expected: ExpectedConnection,
        input: PiSetupInput,
    ) -> Result<ConnectionStatus> {
        let owner = PersonalPrincipal::capture(identity)?;
        validate_expected(&expected)?;
        let snapshot = self.snapshot(owner.clone()).await?;
        matches_expected(snapshot.as_ref(), &expected)?;
        let old = snapshot
            .as_ref()
            .map(|slot| open(&self.0, &owner, slot))
            .transpose()?
            .flatten();
        let endpoint = match input.endpoint {
            Some(raw) => {
                ensure!(raw.len() <= MAX_PI_ENDPOINT_BYTES, "Invalid Pi endpoint");
                Endpoint::parse(&raw)?
            }
            None => Endpoint::parse(&old.as_ref().ok_or_else(unavailable)?.endpoint)?,
        };
        let preferred_model = match input.preferred_model {
            Some(model) if model.is_empty() => None,
            Some(model) => {
                validate_model(&model)?;
                Some(model)
            }
            None => old.as_ref().and_then(|old| old.preferred_model.clone()),
        };
        let changed_endpoint = old
            .as_ref()
            .is_some_and(|old| old.endpoint != endpoint.as_str());
        let credential = match input.credential {
            PiCredentialChange::Keep => {
                ensure!(
                    !changed_endpoint || old.as_ref().is_none_or(|old| old.credential.is_none()),
                    "Changing a Pi endpoint requires replacing or clearing its credential"
                );
                old.as_ref().and_then(|old| old.credential.clone())
            }
            PiCredentialChange::Replace { value } => {
                validate_credential(&value)?;
                Some(value)
            }
            PiCredentialChange::Clear => None,
        };
        let mut retired = owner.subscribe_retirement()?;
        tokio::select! {
            biased;
            _ = retired.changed() => return Err(unavailable()),
            prepared = self.0.policy.prepare(&endpoint, Route::Models) => { prepared?; }
        }
        owner.ensure_current()?;
        let secret = PiSecret {
            connection_id: old
                .as_ref()
                .map(|old| old.connection_id.clone())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            endpoint: endpoint.as_str().to_owned(),
            preferred_model,
            credential,
        };
        let inner = self.0.clone();
        self.0
            .store
            .transaction(move |store| {
                let mut admitted = None;
                let result = store.update(STATE_KEY, |value| {
                    owner.ensure_current()?;
                    let mut database = decode(value)?;
                    let previous = database.owners.get(&owner.account_id);
                    matches_expected(previous, &expected)?;
                    verify_subject(previous, &owner)?;
                    ensure!(
                        previous.is_some() || database.owners.len() < MAX_OWNERS,
                        "Pi connection capacity reached"
                    );
                    let revision = next_revision(previous)?;
                    let sealed = inner
                        .keys
                        .seal(&owner.context(revision), &serde_json::to_vec(&secret)?)?;
                    admitted = previous.and_then(|slot| lease_key(&owner.account_id, slot));
                    database.owners.insert(
                        owner.account_id.clone(),
                        Slot {
                            github_id: owner.github_id,
                            revision,
                            connection: Some(StoredPi {
                                id: secret.connection_id.clone(),
                                sealed,
                            }),
                        },
                    );
                    owner.ensure_current()?;
                    Ok(serde_json::to_value(database)?)
                });
                if result.is_err() {
                    if let Some(key) = admitted {
                        inner
                            .runtime
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .block(&key);
                    }
                    return Err(unavailable());
                }
                let database = decode(Some(result?))?;
                let slot = database
                    .owners
                    .get(&owner.account_id)
                    .ok_or_else(unavailable)?;
                inner
                    .runtime
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .retire_before(&owner.account_id, slot.revision);
                owner.ensure_current()?;
                status(&inner, &owner, Some(slot))
            })
            .await
            .map_err(|_| unavailable())
    }

    pub(crate) async fn remove_pi(
        &self,
        identity: &RequestIdentity,
        expected: ExpectedConnection,
    ) -> Result<ConnectionStatus> {
        let owner = PersonalPrincipal::capture(identity)?;
        validate_expected(&expected)?;
        let inner = self.0.clone();
        self.0
            .store
            .transaction(move |store| {
                let mut admitted = None;
                let result = store.update(STATE_KEY, |value| {
                    owner.ensure_current()?;
                    let mut database = decode(value)?;
                    let previous = database.owners.get(&owner.account_id);
                    matches_expected(previous, &expected)?;
                    verify_subject(previous, &owner)?;
                    let previous = previous.ok_or_else(unavailable)?;
                    ensure!(
                        previous.connection.is_some(),
                        "Pi connection changed; reload setup"
                    );
                    let revision = next_revision(Some(previous))?;
                    admitted = lease_key(&owner.account_id, previous);
                    database.owners.insert(
                        owner.account_id.clone(),
                        Slot {
                            github_id: owner.github_id,
                            revision,
                            connection: None,
                        },
                    );
                    owner.ensure_current()?;
                    Ok(serde_json::to_value(database)?)
                });
                if result.is_err() {
                    if let Some(key) = admitted {
                        inner
                            .runtime
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .block(&key);
                    }
                    return Err(unavailable());
                }
                let database = decode(Some(result?))?;
                let slot = database
                    .owners
                    .get(&owner.account_id)
                    .ok_or_else(unavailable)?;
                inner
                    .runtime
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .retire_before(&owner.account_id, slot.revision);
                owner.ensure_current()?;
                status(&inner, &owner, Some(slot))
            })
            .await
            .map_err(|_| unavailable())
    }

    pub(crate) async fn pi_lease(
        &self,
        identity: &RequestIdentity,
        expected: ExpectedConnection,
    ) -> Result<PiLease> {
        validate_expected(&expected)?;
        let owner = PersonalPrincipal::capture(identity)?;
        let slot = self
            .snapshot(owner.clone())
            .await?
            .ok_or_else(unavailable)?;
        matches_expected(Some(&slot), &expected)?;
        let key = lease_key(&owner.account_id, &slot).ok_or_else(unavailable)?;
        open(&self.0, &owner, &slot)?.ok_or_else(unavailable)?;
        let state = self
            .0
            .runtime
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .lease(&key)?;
        let lease = PiLease {
            inner: self.0.clone(),
            owner,
            key,
            state,
        };
        lease.check_current().await?;
        Ok(lease)
    }

    pub(crate) async fn pi_models(
        &self,
        identity: &RequestIdentity,
        expected: ExpectedConnection,
        refresh: bool,
    ) -> Result<PiCatalog> {
        tokio::time::timeout(self.0.catalog_timeout, async {
            self.pi_lease(identity, expected)
                .await?
                .models(refresh)
                .await
        })
        .await
        .map_err(|_| unavailable())?
    }

    async fn snapshot(&self, owner: Arc<PersonalPrincipal>) -> Result<Option<Slot>> {
        self.0
            .store
            .transaction(move |store| {
                owner.ensure_current()?;
                let mut database = read(&store)?;
                let slot = database.owners.remove(&owner.account_id);
                verify_subject(slot.as_ref(), &owner)?;
                owner.ensure_current()?;
                Ok(slot)
            })
            .await
            .map_err(|_| unavailable())
    }
}

/// Opaque captured credential capability. Neither its endpoint nor its secret can be read by callers.
#[derive(Clone)]
pub(crate) struct PiLease {
    inner: Arc<Inner>,
    owner: Arc<PersonalPrincipal>,
    key: LeaseKey,
    state: Arc<LeaseState>,
}
impl PiLease {
    fn check_local(&self) -> Result<()> {
        self.owner.ensure_current()?;
        ensure!(
            !*self.state.retired.borrow(),
            "Pi connection changed; reload setup"
        );
        Ok(())
    }
    async fn check_current(&self) -> Result<()> {
        self.check_local()?;
        let lease = self.clone();
        self.inner
            .store
            .transaction(move |store| {
                lease.check_local()?;
                let database = read(&store)?;
                let slot = database
                    .owners
                    .get(&lease.key.owner)
                    .ok_or_else(unavailable)?;
                verify_subject(Some(slot), &lease.owner)?;
                ensure!(
                    lease_key(&lease.key.owner, slot).as_ref() == Some(&lease.key),
                    "Pi connection changed; reload setup"
                );
                ensure!(
                    !lease
                        .inner
                        .runtime
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .is_blocked(&lease.key),
                    "Pi connection needs setup again"
                );
                open(&lease.inner, &lease.owner, slot)?.ok_or_else(unavailable)?;
                lease.check_local()
            })
            .await
            .map_err(|_| unavailable())?;
        self.check_local()
    }

    /// Every cached return and actual request retains both the source and exact connection revision.
    pub(crate) async fn models(&self, refresh: bool) -> Result<PiCatalog> {
        let mut connection = self.state.retired.subscribe();
        let mut owner = self.owner.subscribe_retirement()?;
        self.check_local()?;
        tokio::select! {
            biased;
            _ = connection.changed() => Err(unavailable()),
            _ = owner.changed() => Err(unavailable()),
            result = tokio::time::timeout(self.inner.catalog_timeout, self.models_current(refresh)) => result.map_err(|_| unavailable())?,
        }
    }

    async fn models_current(&self, refresh: bool) -> Result<PiCatalog> {
        self.check_current().await?;
        let _refresh = self.state.refresh.lock().await;
        self.check_current().await?;
        if !refresh {
            let cached = self
                .state
                .catalog
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
                .filter(|(at, _)| at.elapsed() < CATALOG_TTL)
                .map(|(_, models)| models.clone());
            if let Some(models) = cached {
                self.check_current().await?;
                return Ok(self.catalog_reply(models));
            }
        }
        let lease = self.clone();
        let secret = self
            .inner
            .store
            .transaction(move |store| {
                lease.check_local()?;
                let database = read(&store)?;
                let slot = database
                    .owners
                    .get(&lease.key.owner)
                    .ok_or_else(unavailable)?;
                ensure!(
                    lease_key(&lease.key.owner, slot).as_ref() == Some(&lease.key),
                    "Pi connection changed; reload setup"
                );
                open(&lease.inner, &lease.owner, slot)?.ok_or_else(unavailable)
            })
            .await
            .map_err(|_| unavailable())?;
        let operation = async {
            let prepared = self
                .inner
                .policy
                .prepare(&Endpoint::parse(&secret.endpoint)?, Route::Models)
                .await?;
            self.check_current().await?;
            let mut request = prepared.client.get(prepared.url);
            if let Some(key) = &secret.credential {
                request = request.bearer_auth(key);
            }
            let response = request.send().await.map_err(|_| unavailable())?;
            if matches!(response.status().as_u16(), 401 | 403) {
                self.check_current().await?;
                self.state.reconnect.store(true, Ordering::SeqCst);
                *self.state.catalog.lock().unwrap_or_else(|e| e.into_inner()) = None;
                return Err(unavailable());
            }
            ensure!(
                response.status().is_success(),
                "Personal Pi connection is unavailable"
            );
            ensure!(
                response
                    .content_length()
                    .is_none_or(|length| length <= MAX_CATALOG_BYTES as u64),
                "Personal Pi connection is unavailable"
            );
            let mut bytes = Vec::new();
            let mut stream = response.bytes_stream();
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.map_err(|_| unavailable())?;
                ensure!(
                    chunk.len() <= MAX_CATALOG_BYTES.saturating_sub(bytes.len()),
                    "Personal Pi connection is unavailable"
                );
                bytes.extend_from_slice(&chunk);
            }
            let body: serde_json::Value =
                serde_json::from_slice(&bytes).map_err(|_| unavailable())?;
            let entries = body
                .get("data")
                .and_then(serde_json::Value::as_array)
                .ok_or_else(unavailable)?;
            ensure!(
                entries.len() <= MAX_CATALOG_ENTRIES,
                "Personal Pi connection is unavailable"
            );
            if let Some(key) = &secret.credential {
                ensure!(
                    !contains_secret(&body, key),
                    "Personal Pi connection is unavailable"
                );
            }
            let models = crate::ai::provider::pi_models(
                &body,
                secret.preferred_model.as_deref().unwrap_or(""),
            );
            ensure!(!models.is_empty(), "Pi endpoint lists no text models");
            self.check_current().await?;
            *self.state.catalog.lock().unwrap_or_else(|e| e.into_inner()) =
                Some((Instant::now(), models.clone()));
            self.state.reconnect.store(false, Ordering::SeqCst);
            self.check_current().await?;
            Ok(self.catalog_reply(models))
        };
        operation.await
    }

    fn catalog_reply(&self, models: Vec<serde_json::Value>) -> PiCatalog {
        PiCatalog {
            account_id: self.key.owner.clone(),
            connection_id: self.key.id.clone(),
            revision: self.key.revision,
            models,
        }
    }
}
impl AiCredentialCapability for PiLease {
    fn principal(&self) -> &dyn AiPrincipal {
        self.owner.as_ref()
    }
    fn ensure_current(&self) -> AiFuture<'_, ()> {
        Box::pin(self.check_current())
    }
    fn retired(&self) -> AiFuture<'_, ()> {
        Box::pin(async move {
            let mut connection = self.state.retired.subscribe();
            let mut owner = self.owner.subscribe_retirement()?;
            self.check_local()?;
            tokio::select! {
                biased;
                _ = connection.changed() => {},
                _ = owner.changed() => {},
            }
            Err(unavailable())
        })
    }
}

fn read(store: &Store) -> Result<Database> {
    decode(store.get(STATE_KEY)?)
}
fn decode(value: Option<serde_json::Value>) -> Result<Database> {
    let database: Database = match value {
        Some(value) => serde_json::from_value(value).map_err(|_| unavailable())?,
        None => Database::default(),
    };
    ensure!(
        database.owners.len() <= MAX_OWNERS,
        "Pi connection capacity reached"
    );
    for (owner, slot) in &database.owners {
        ensure!(
            !owner.is_empty()
                && owner.len() <= 128
                && slot.github_id > 0
                && (1..=i64::MAX as u64).contains(&slot.revision),
            "Pi configuration is unavailable"
        );
        if let Some(connection) = &slot.connection {
            ensure!(
                uuid::Uuid::parse_str(&connection.id).is_ok(),
                "Pi configuration is unavailable"
            );
        }
    }
    Ok(database)
}
fn verify_subject(slot: Option<&Slot>, owner: &PersonalPrincipal) -> Result<()> {
    ensure!(
        slot.is_none_or(|slot| slot.github_id == owner.github_id),
        "Pi configuration is unavailable"
    );
    Ok(())
}
fn open(inner: &Inner, owner: &PersonalPrincipal, slot: &Slot) -> Result<Option<PiSecret>> {
    verify_subject(Some(slot), owner)?;
    let Some(connection) = &slot.connection else {
        return Ok(None);
    };
    let plaintext = inner
        .keys
        .open(&owner.context(slot.revision), &connection.sealed)
        .map_err(|_| unavailable())?;
    let secret: PiSecret = serde_json::from_slice(&plaintext).map_err(|_| unavailable())?;
    ensure!(
        secret.connection_id == connection.id,
        "Pi configuration is unavailable"
    );
    Endpoint::parse(&secret.endpoint).map_err(|_| unavailable())?;
    if let Some(model) = &secret.preferred_model {
        validate_model(model)?;
    }
    if let Some(key) = &secret.credential {
        validate_credential(key)?;
    }
    Ok(Some(secret))
}
fn lease_key(owner: &str, slot: &Slot) -> Option<LeaseKey> {
    slot.connection.as_ref().map(|connection| LeaseKey {
        owner: owner.to_owned(),
        id: connection.id.clone(),
        revision: slot.revision,
    })
}
fn validate_expected(expected: &ExpectedConnection) -> Result<()> {
    ensure!(
        matches!((&expected.connection_id, expected.revision), (None, None))
            || (expected
                .connection_id
                .as_ref()
                .is_some_and(|id| uuid::Uuid::parse_str(id).is_ok())
                && expected
                    .revision
                    .is_some_and(|revision| (1..=i64::MAX as u64).contains(&revision))),
        "Pi connection changed; reload setup"
    );
    Ok(())
}
fn matches_expected(slot: Option<&Slot>, expected: &ExpectedConnection) -> Result<()> {
    let actual = slot.and_then(|slot| {
        slot.connection
            .as_ref()
            .map(|connection| (&connection.id, slot.revision))
    });
    ensure!(
        match actual {
            Some((id, revision)) =>
                expected.connection_id.as_ref() == Some(id) && expected.revision == Some(revision),
            None => expected.connection_id.is_none() && expected.revision.is_none(),
        },
        "Pi connection changed; reload setup"
    );
    Ok(())
}
fn next_revision(slot: Option<&Slot>) -> Result<u64> {
    let revision = slot
        .map_or(0, |slot| slot.revision)
        .checked_add(1)
        .ok_or_else(unavailable)?;
    ensure!(
        revision <= i64::MAX as u64,
        "Pi connection needs setup again"
    );
    Ok(revision)
}
fn validate_model(model: &str) -> Result<()> {
    ensure!(
        !model.trim().is_empty()
            && model.len() <= MAX_PI_MODEL_BYTES
            && !model.chars().any(char::is_control),
        "Invalid Pi model"
    );
    Ok(())
}
fn validate_credential(value: &str) -> Result<()> {
    ensure!(
        !value.is_empty()
            && value.len() <= MAX_PI_CREDENTIAL_BYTES
            && value.bytes().all(|byte| (0x21..=0x7e).contains(&byte)),
        "Invalid Pi credential"
    );
    Ok(())
}
fn status(
    inner: &Inner,
    owner: &PersonalPrincipal,
    slot: Option<&Slot>,
) -> Result<ConnectionStatus> {
    let secret = slot
        .map(|slot| open(inner, owner, slot))
        .transpose()?
        .flatten();
    let key = slot.and_then(|slot| lease_key(&owner.account_id, slot));
    let blocked = key.as_ref().is_some_and(|key| {
        inner
            .runtime
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_blocked(key)
    });
    let live = key.as_ref().and_then(|key| {
        inner
            .runtime
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .leases
            .get(key)
            .cloned()
    });
    let reconnect = live
        .as_ref()
        .is_some_and(|state| state.reconnect.load(Ordering::SeqCst));
    let verified = live.as_ref().is_some_and(|state| {
        state
            .catalog
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .is_some_and(|(at, _)| at.elapsed() < CATALOG_TTL)
    });
    let endpoint_origin = secret
        .as_ref()
        .map(|secret| Endpoint::parse(&secret.endpoint).map(|endpoint| endpoint.display_origin()))
        .transpose()?;
    Ok(ConnectionStatus {
        kind: ConnectionKind::PiEndpoint,
        state: if blocked {
            ConnectionState::Unavailable
        } else if reconnect {
            ConnectionState::RefreshNeeded
        } else if verified {
            ConnectionState::Connected
        } else if secret.is_some() {
            ConnectionState::Unverified
        } else {
            ConnectionState::Disconnected
        },
        account_id: owner.account_id.clone(),
        connection_id: key.as_ref().map(|key| key.id.clone()),
        revision: key.as_ref().map(|key| key.revision),
        endpoint_origin,
        preferred_model: secret
            .as_ref()
            .and_then(|secret| secret.preferred_model.clone()),
        has_credential: secret
            .as_ref()
            .is_some_and(|secret| secret.credential.is_some()),
        approval_required: false,
        capabilities: ProviderCapabilities {
            configure: true,
            models: secret.is_some() && !blocked,
            execute: false,
        },
    })
}
fn unsupported(owner: &PersonalPrincipal, kind: ConnectionKind) -> ConnectionStatus {
    ConnectionStatus {
        kind,
        state: ConnectionState::Unsupported,
        account_id: owner.account_id.clone(),
        connection_id: None,
        revision: None,
        endpoint_origin: None,
        preferred_model: None,
        has_credential: false,
        approval_required: true,
        capabilities: ProviderCapabilities {
            configure: false,
            models: false,
            execute: false,
        },
    }
}
fn unavailable() -> anyhow::Error {
    anyhow::anyhow!("Personal Pi connection is unavailable")
}

fn contains_secret(value: &serde_json::Value, secret: &str) -> bool {
    match value {
        serde_json::Value::String(value) => value.contains(secret),
        serde_json::Value::Array(values) => {
            values.iter().any(|value| contains_secret(value, secret))
        }
        serde_json::Value::Object(values) => values
            .iter()
            .any(|(key, value)| key.contains(secret) || contains_secret(value, secret)),
        _ => false,
    }
}

#[cfg(test)]
mod tests;
