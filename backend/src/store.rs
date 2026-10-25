//! Atomic JSON storage. Reads the TypeScript unstorage flat-key layout directly.
//! Rust diff records use `diff:`; legacy `pr-meta:` + `pr-body:` records are joined
//! on read and retained, so upgrading never destructively rewrites the old cache.
use crate::{decode_component as decode, encode_component as encode};
use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock, Weak},
};

/// @cc [owner:OctavianTocan,label:architecture] rust-foreground-storage-off-runtime
/// Foreground async storage operations MUST move their complete synchronous work,
/// including mutex acquisition, to the blocking pool; network waits MUST remain
/// outside it (Rust parity review, 16 Oct 2026, round 12 finding 11).
pub(crate) async fn blocking<T: Send + 'static>(
    operation: impl FnOnce() -> Result<T> + Send + 'static,
) -> Result<T> {
    crate::rt::spawn_blocking(operation)
        .await
        .context("Storage task failed")?
}

/// Where a [`Store`] keeps its records: files on the native server, Durable Object SQLite in
/// the Worker (`packages/worker`). An id is a collection (`account`) or a collection and an
/// encoded name (`diff/github%3Ao%2Fr%231`); the Store validates ids before they get here and
/// holds its lock around every call.
pub trait Records: Send + Sync {
    fn get(&self, id: &str) -> Result<Option<Vec<u8>>>;
    /// Replaces the record atomically: a reader sees the old bytes or the new, never a mix.
    fn put(&self, id: &str, bytes: &[u8]) -> Result<()>;
    /// Returns whether there was a record to remove.
    fn delete(&self, id: &str) -> Result<bool>;
    /// Every stored id, in any order.
    fn ids(&self) -> Result<Vec<String>>;
    /// Matching encoded ids, with a literal prefix (not a glob). Persistent adapters
    /// narrow the lookup before enumerating records. The default keeps other adapters compatible.
    fn ids_with_prefix(&self, prefix: &str) -> Result<Vec<String>> {
        Ok(self
            .ids()?
            .into_iter()
            .filter(|id| id.starts_with(prefix))
            .collect())
    }
}

struct WorkspaceRecords {
    durable: Arc<Store>,
    identity: crate::identity::RequestIdentity,
    transient: Mutex<BTreeMap<String, Vec<u8>>>,
}
impl WorkspaceRecords {
    fn persistent(id: &str) -> bool {
        matches!(
            id.split('/').next().unwrap_or(id),
            "diff"
                | "review"
                | "tour"
                | "brief"
                | "critique"
                | "ask-threads"
                | "inbox-chat"
                | "account"
                | "account-settings"
                | "agent-key"
                | "posted-diff"
                | "posted-patch"
                | "posted-agent"
                | "feedback"
                | "workspace-repository"
                | "workspace-retired"
        )
    }
}
impl Records for WorkspaceRecords {
    fn get(&self, id: &str) -> Result<Option<Vec<u8>>> {
        self.identity.ensure_current()?;
        let result = if Self::persistent(id) {
            // Store holds the shared durable transaction lock for the entire operation.
            self.durable.lock.records.get(id)?
        } else {
            self.transient.lock().unwrap().get(id).cloned()
        };
        self.identity.ensure_current()?;
        Ok(result)
    }
    fn put(&self, id: &str, bytes: &[u8]) -> Result<()> {
        self.identity.ensure_current()?;
        if Self::persistent(id) {
            self.identity.ensure_current()?;
            self.durable.lock.records.put(id, bytes)
        } else {
            self.transient
                .lock()
                .unwrap()
                .insert(id.to_owned(), bytes.to_vec());
            Ok(())
        }
    }
    fn delete(&self, id: &str) -> Result<bool> {
        self.identity.ensure_current()?;
        if Self::persistent(id) {
            self.identity.ensure_current()?;
            self.durable.lock.records.delete(id)
        } else {
            Ok(self.transient.lock().unwrap().remove(id).is_some())
        }
    }
    fn ids(&self) -> Result<Vec<String>> {
        self.ids_with_prefix("")
    }
    fn ids_with_prefix(&self, prefix: &str) -> Result<Vec<String>> {
        self.identity.ensure_current()?;
        let mut ids: Vec<_> = self
            .durable
            .lock
            .records
            .ids_with_prefix(prefix)?
            .into_iter()
            .filter(|id| Self::persistent(id))
            .collect();
        ids.extend(
            self.transient
                .lock()
                .unwrap()
                .range(prefix.to_owned()..)
                .take_while(|(id, _)| id.starts_with(prefix))
                .map(|(id, _)| id.clone()),
        );
        self.identity.ensure_current()?;
        Ok(ids)
    }
}

#[derive(Clone)]
pub struct Store {
    root: PathBuf,
    lock: Arc<RootLock>,
}
struct RootLock {
    mutex: Arc<Mutex<()>>,
    diff_index: Arc<Mutex<DiffIndex>>,
    binding: Option<crate::identity::RequestIdentity>,
    #[cfg(test)]
    diff_body_reads: std::sync::atomic::AtomicUsize,
    records: Box<dyn Records>,
}
impl RootLock {
    fn new(records: Box<dyn Records>) -> Arc<Self> {
        Arc::new(Self {
            mutex: Arc::new(Mutex::new(())),
            diff_index: Arc::new(Mutex::new(DiffIndex::default())),
            binding: None,
            #[cfg(test)]
            diff_body_reads: std::sync::atomic::AtomicUsize::new(0),
            records,
        })
    }
}
/// Records as files under one directory, each written aside and renamed into place. The
/// directory is locked to one server process.
#[cfg(not(target_arch = "wasm32"))]
struct Files {
    root: PathBuf,
    _lock: fs::File,
}
#[cfg(not(target_arch = "wasm32"))]
impl Files {
    fn path(&self, id: &str) -> Result<PathBuf> {
        let path = self.root.join(id);
        // Refuse preexisting symlinks at every level, including collection directories.
        for p in [path.parent().unwrap_or(&self.root), path.as_path()] {
            if let Ok(meta) = fs::symlink_metadata(p) {
                if meta.file_type().is_symlink() {
                    bail!("Storage symlinks are not allowed");
                }
            }
        }
        Ok(path)
    }
}
#[cfg(not(target_arch = "wasm32"))]
impl Records for Files {
    fn get(&self, id: &str) -> Result<Option<Vec<u8>>> {
        match fs::read(self.path(id)?) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.into()),
        }
    }
    fn put(&self, id: &str, bytes: &[u8]) -> Result<()> {
        use std::io::Write;
        let path = self.path(id)?;
        let parent = path.parent().unwrap();
        fs::create_dir_all(parent)?;
        let temporary = parent.join(format!(".tmp-{}", uuid::Uuid::new_v4()));
        let result = (|| -> Result<()> {
            let mut file = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(bytes)?;
            file.sync_all()?;
            fs::rename(&temporary, &path)?;
            if let Ok(dir) = fs::File::open(parent) {
                let _ = dir.sync_all();
            }
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }
    fn delete(&self, id: &str) -> Result<bool> {
        match fs::remove_file(self.path(id)?) {
            Ok(()) => Ok(true),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(e) => Err(e.into()),
        }
    }
    fn ids(&self) -> Result<Vec<String>> {
        self.ids_with_prefix("")
    }
    fn ids_with_prefix(&self, prefix: &str) -> Result<Vec<String>> {
        let mut ids = Vec::new();
        if let Some((head, name_prefix)) = prefix.split_once('/') {
            // Prefixes are queries, not validated write ids. Never let one select a path.
            if head.is_empty()
                || !head
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(&b))
            {
                return Ok(ids);
            }
            let directory = self.path(head)?;
            let files = match fs::read_dir(directory) {
                Ok(files) => files,
                Err(e)
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::NotFound | std::io::ErrorKind::NotADirectory
                    ) =>
                {
                    return Ok(ids)
                }
                Err(e) => return Err(e.into()),
            };
            for file in files {
                let file = file?;
                let name = file.file_name().to_string_lossy().into_owned();
                if name.starts_with(name_prefix)
                    && !name.starts_with('.')
                    && file.file_type()?.is_file()
                {
                    ids.push(format!("{head}/{name}"));
                }
            }
            return Ok(ids);
        }
        for entry in fs::read_dir(&self.root)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            let head = entry.file_name().to_string_lossy().into_owned();
            if !head.starts_with(prefix) || head.starts_with('.') || kind.is_symlink() {
                continue;
            }
            if kind.is_dir() {
                for file in fs::read_dir(entry.path())? {
                    let file = file?;
                    if !file.file_type()?.is_file() {
                        continue;
                    }
                    let name = file.file_name().to_string_lossy().into_owned();
                    if !name.starts_with('.') {
                        ids.push(format!("{head}/{name}"));
                    }
                }
            } else if kind.is_file() {
                ids.push(head);
            }
        }
        Ok(ids)
    }
}
#[derive(Clone)]
pub(crate) struct DiffEntryMetadata {
    pub revision: u64,
    pub size_bytes: u64,
    pub last_viewed_at: u64,
    pub repository: Option<(String, String)>,
}
impl DiffEntryMetadata {
    fn new(entry: &Value, revision: u64) -> Self {
        Self {
            revision,
            size_bytes: entry["sizeBytes"].as_u64().unwrap_or(0),
            last_viewed_at: entry["lastViewedAt"].as_u64().unwrap_or(0),
            repository: (entry["diff"]["ref"]["kind"] == "github-pr").then(|| {
                (
                    entry["diff"]["ref"]["owner"]
                        .as_str()
                        .unwrap_or("")
                        .to_owned(),
                    entry["diff"]["ref"]["repo"]
                        .as_str()
                        .unwrap_or("")
                        .to_owned(),
                )
            }),
        }
    }
}
#[derive(Default)]
struct DiffIndex {
    revision: u64,
    entries: Option<BTreeMap<String, DiffEntryMetadata>>,
    dirty: HashMap<String, u64>,
}
pub(crate) struct DiffSnapshot {
    pub revision: u64,
    pub entries: BTreeMap<String, DiffEntryMetadata>,
    pub size_bytes: u64,
}
#[cfg(not(target_arch = "wasm32"))]
static LOCKS: OnceLock<Mutex<HashMap<PathBuf, Weak<RootLock>>>> = OnceLock::new();
impl Store {
    /// A workspace keeps saved reviews/tours/settings durably, but never adopts a prior
    /// binding's inbox, repository or full-file caches. Every access retains its binding.
    pub(crate) fn workspace(
        durable: Arc<Self>,
        identity: crate::identity::RequestIdentity,
        revision: &str,
    ) -> Self {
        let root = durable.root.join(crate::encode_component(revision));
        // Binding roots remain distinct for transient caches and account coordinators.
        // All storage operations, including read/transform/write and multi-key CAS,
        // share the stable account's lock; the adapter must not acquire it recursively.
        let mutex = durable.lock.mutex.clone();
        let diff_index = durable.lock.diff_index.clone();
        Self {
            root,
            lock: Arc::new(RootLock {
                mutex,
                diff_index,
                binding: Some(identity.clone()),
                #[cfg(test)]
                diff_body_reads: std::sync::atomic::AtomicUsize::new(0),
                records: Box::new(WorkspaceRecords {
                    durable,
                    identity,
                    transient: Mutex::new(BTreeMap::new()),
                }),
            }),
        }
    }
    pub(crate) async fn transaction<T: Send + 'static>(
        &self,
        operation: impl FnOnce(Self) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let store = self.clone();
        blocking(move || operation(store)).await
    }
    pub(crate) async fn get_async(&self, key: &str) -> Result<Option<Value>> {
        let key = key.to_owned();
        self.transaction(move |store| store.get(&key)).await
    }
    pub(crate) async fn set_async(&self, key: &str, value: &Value) -> Result<()> {
        let (key, value) = (key.to_owned(), value.clone());
        self.transaction(move |store| store.set(&key, &value)).await
    }
    pub fn root(&self) -> &std::path::Path {
        &self.root
    }
    /// Opens the file store at `root`, creating it, and locks it to this process.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn new(root: PathBuf) -> Result<Self> {
        fs::create_dir_all(&root).context("Cannot create server storage")?;
        let root = fs::canonicalize(root)?;
        let mut locks = LOCKS
            .get_or_init(Default::default)
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        let lock = if let Some(lock) = locks.get(&root).and_then(Weak::upgrade) {
            lock
        } else {
            let path = root.join(".rust-server.lock");
            if fs::symlink_metadata(&path)
                .map(|m| m.file_type().is_symlink())
                .unwrap_or(false)
            {
                bail!("Storage symlinks are not allowed");
            }
            let file = fs::OpenOptions::new()
                .create(true)
                .truncate(false)
                .read(true)
                .write(true)
                .open(path)?;
            fs2::FileExt::try_lock_exclusive(&file)
                .context("Another Rust server is using this data directory")?;
            let lock = RootLock::new(Box::new(Files {
                root: root.clone(),
                _lock: file,
            }));
            locks.insert(root.clone(), Arc::downgrade(&lock));
            lock
        };
        Ok(Self { root, lock })
    }
    /// A store over `records`. `root` names it: caches keyed by store use it, and nothing
    /// is read from or written to that path.
    pub fn with_records(root: PathBuf, records: Box<dyn Records>) -> Self {
        Self {
            root,
            lock: RootLock::new(records),
        }
    }
    fn id(&self, key: &str) -> Result<String> {
        if key.is_empty() || key.len() > 16000 {
            bail!("Invalid storage key");
        }
        let normalized = key.replace(['/', '\\'], ":");
        let mut parts = normalized.splitn(2, ':');
        let head = parts.next().unwrap();
        if head.is_empty()
            || head == "."
            || head == ".."
            || !head
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(&b))
        {
            bail!("Invalid storage collection");
        }
        if let Some(rest) = parts.next() {
            let name = encode(rest);
            if name.is_empty() || name == "." || name == ".." {
                bail!("Invalid storage key");
            }
            Ok(format!("{head}/{name}"))
        } else {
            Ok(head.to_owned())
        }
    }
    fn raw_get(&self, key: &str) -> Result<Option<Value>> {
        let Some(bytes) = self.lock.records.get(&self.id(key)?)? else {
            return Ok(None);
        };
        #[cfg(test)]
        if key.starts_with("diff:") || key.starts_with("pr-body:") {
            self.lock
                .diff_body_reads
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        Ok(Some(
            serde_json::from_slice(&bytes).context("Invalid stored JSON")?,
        ))
    }
    fn read(&self, key: &str) -> Result<Option<Value>> {
        if let Some(scope) = key
            .strip_prefix("pr-meta:")
            .or_else(|| key.strip_prefix("pr-body:"))
        {
            if let Some(mut entry) = self.raw_get(&format!("diff:{scope}"))? {
                return Ok(Some(if key.starts_with("pr-meta:") {
                    crate::review::metadata(&entry)
                } else {
                    let mut body = serde_json::Map::new();
                    for name in ["diff", "analyzedBy", "reviews", "llmSession"] {
                        if let Some(v) = entry.get_mut(name) {
                            body.insert(name.into(), v.take());
                        }
                    }
                    Value::Object(body)
                }));
            }
        }
        if let Some(value) = self.raw_get(key)? {
            return Ok(Some(value));
        }
        if let Some(scope) = key.strip_prefix("diff:") {
            if let (Some(meta), Some(mut body)) = (
                self.raw_get(&format!("pr-meta:{scope}"))?,
                self.raw_get(&format!("pr-body:{scope}"))?,
            ) {
                if let Some(object) = body.as_object_mut() {
                    for field in [
                        "key",
                        "headSha",
                        "lastViewedAt",
                        "sizeBytes",
                        "sharedComment",
                        "changedSinceReviewed",
                    ] {
                        if let Some(v) = meta.get(field) {
                            object.insert(field.into(), v.clone());
                        }
                    }
                    return Ok(Some(body));
                }
            }
        }
        Ok(None)
    }
    fn write(&self, key: &str, value: &Value) -> Result<()> {
        self.lock
            .records
            .put(&self.id(key)?, &serde_json::to_vec(value)?)?;
        self.note_diff_mutation(key, Some(value));
        Ok(())
    }
    // All callers hold the root lock, so mutation versions and their metadata are
    // published together. Alias changes can expose or modify a legacy split diff.
    fn note_diff_mutation(&self, key: &str, value: Option<&Value>) {
        let normalized = key.replace(['/', '\\'], ":");
        let Some((collection, scope)) = normalized.split_once(':') else {
            return;
        };
        if !matches!(collection, "diff" | "pr-meta" | "pr-body") {
            return;
        }
        let mut index = self
            .lock
            .diff_index
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        index.revision = index.revision.wrapping_add(1);
        let revision = index.revision;
        if index.entries.is_none() {
            // The first snapshot discovers all live records; do not retain a
            // lifetime history of deleted keys before the index is needed.
            return;
        }
        let key = format!("diff:{scope}");
        if collection == "diff" {
            if let (Some(entries), Some(value)) = (&mut index.entries, value) {
                entries.insert(key.clone(), DiffEntryMetadata::new(value, revision));
                index.dirty.remove(&key);
                return;
            }
        }
        index.dirty.insert(key, revision);
    }
    /// @cc [owner:OctavianTocan,label:architecture] rust-diff-budget-metadata
    /// Budget checks MUST reuse lightweight metadata without rereading unchanged
    /// diff bodies. Every Store mutation of a diff or legacy alias MUST invalidate
    /// its metadata across Store instances (16 Oct 2026 parity review, finding #10).
    pub(crate) fn diff_snapshot(&self, previous: Option<u64>) -> Result<Option<DiffSnapshot>> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        if let Some(identity) = &self.lock.binding {
            identity.ensure_current()?;
        }
        let mut index = self
            .lock
            .diff_index
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if index.entries.is_some() && previous == Some(index.revision) {
            return Ok(None);
        }
        if index.entries.is_none() {
            let mut entries = BTreeMap::new();
            for key in self.list_keys("diff:")? {
                if let Some(entry) = self.read(&key)? {
                    entries.insert(key, DiffEntryMetadata::new(&entry, index.revision));
                }
            }
            index.entries = Some(entries);
        } else {
            // Dirty records are legacy writes or deletes that may reveal legacy
            // fallback data. Native writes already updated their own metadata.
            let changes = index
                .dirty
                .iter()
                .map(|(key, revision)| {
                    self.read(key).map(|entry| {
                        (
                            key.clone(),
                            entry.map(|v| DiffEntryMetadata::new(&v, *revision)),
                        )
                    })
                })
                .collect::<Result<Vec<_>>>()?;
            let entries = index.entries.as_mut().unwrap();
            for (key, entry) in changes {
                if let Some(entry) = entry {
                    entries.insert(key, entry);
                } else {
                    entries.remove(&key);
                }
            }
        }
        index.dirty.clear();
        let entries = index.entries.as_ref().unwrap().clone();
        let size_bytes = entries
            .values()
            .fold(0u64, |total, e| total.saturating_add(e.size_bytes));
        if let Some(identity) = &self.lock.binding {
            identity.ensure_current()?;
        }
        Ok(Some(DiffSnapshot {
            revision: index.revision,
            entries,
            size_bytes,
        }))
    }
    // Read full bodies only for actual eviction candidates, and only if they still
    // match the metadata used to select them. The caller then CAS-deletes the body.
    pub(crate) fn diff_entry_if_unchanged(
        &self,
        key: &str,
        revision: u64,
    ) -> Result<Option<Value>> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        if let Some(identity) = &self.lock.binding {
            identity.ensure_current()?;
        }
        let index = self
            .lock
            .diff_index
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let current = index
            .dirty
            .get(key)
            .copied()
            .or_else(|| index.entries.as_ref()?.get(key).map(|e| e.revision));
        if current != Some(revision) {
            return Ok(None);
        }
        self.read(key)
    }
    #[cfg(test)]
    pub(crate) fn diff_body_reads(&self) -> usize {
        self.lock
            .diff_body_reads
            .load(std::sync::atomic::Ordering::Relaxed)
    }
    pub fn get(&self, key: &str) -> Result<Option<Value>> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        self.read(key)
    }
    pub fn set(&self, key: &str, value: &Value) -> Result<()> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        self.write(key, value)
    }
    pub fn remove(&self, key: &str) -> Result<()> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        self.erase(key)
    }
    fn erase(&self, key: &str) -> Result<()> {
        if self.lock.records.delete(&self.id(key)?)? {
            self.note_diff_mutation(key, None);
        }
        Ok(())
    }
    /// @cc [owner:OctavianTocan,label:architecture] rust-store-atomic-mutations
    /// The complete read/modify/write MUST share a lock across Store instances for
    /// the same root (port of engine-critique-writes-are-serialized, finding #24).
    pub fn update<F>(&self, key: &str, change: F) -> Result<Value>
    where
        F: FnOnce(Option<Value>) -> Result<Value>,
    {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        let next = change(self.read(key)?)?;
        self.write(key, &next)?;
        Ok(next)
    }
    pub fn compare_exchange(&self, items: &[(String, Option<Value>, Option<Value>)]) -> Result<()> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        let mut seen = std::collections::HashSet::new();
        for (key, expected, _) in items {
            let target = self.id(key)?;
            if !seen.insert(target) {
                bail!("Duplicate storage mutation");
            }
            if &self.read(key)? != expected {
                bail!("The cached data changed on the server. Reload before saving.");
            }
        }
        for (key, _, next) in items {
            if let Some(value) = next {
                self.write(key, value)?;
            } else {
                self.erase(key)?;
            }
        }
        Ok(())
    }
    pub fn keys(&self, prefix: &str) -> Result<Vec<String>> {
        let _guard = self
            .lock
            .mutex
            .lock()
            .map_err(|_| anyhow::anyhow!("Storage lock unavailable"))?;
        self.list_keys(prefix)
    }
    fn list_keys(&self, prefix: &str) -> Result<Vec<String>> {
        let prefix = prefix.replace(['/', '\\'], ":");
        let encoded_prefix = |prefix: &str| match prefix.split_once(':') {
            Some((head, rest)) => format!("{head}/{}", encode(rest)),
            None => prefix.to_owned(),
        };
        let mut prefixes = vec![encoded_prefix(&prefix)];
        // Native and legacy diff names remain aliases in prefix listings too.
        for (source, alias) in [
            ("diff", "pr-meta"),
            ("diff", "pr-body"),
            ("pr-meta", "diff"),
        ] {
            if let Some(rest) = prefix.strip_prefix(&format!("{alias}:")) {
                prefixes.push(format!("{source}/{}", encode(rest)));
            } else if alias.starts_with(&prefix) {
                prefixes.push(format!("{source}/"));
            }
        }
        prefixes.sort();
        prefixes.dedup();
        let mut keys = Vec::new();
        let mut ids = BTreeSet::new();
        for prefix in prefixes {
            ids.extend(self.lock.records.ids_with_prefix(&prefix)?);
        }
        for id in ids {
            let Some((head, name)) = id.split_once('/') else {
                if id.starts_with(&prefix) {
                    keys.push(id);
                }
                continue;
            };
            if let Some(name) = decode(name) {
                let key = format!("{head}:{name}");
                if key.starts_with(&prefix) {
                    keys.push(key);
                }
                if head == "diff" {
                    for p in ["pr-meta", "pr-body"] {
                        let synthetic = format!("{p}:{name}");
                        if synthetic.starts_with(&prefix) {
                            keys.push(synthetic);
                        }
                    }
                }
                if head == "pr-meta" {
                    let migrated = format!("diff:{name}");
                    if migrated.starts_with(&prefix) {
                        keys.push(migrated);
                    }
                }
            }
        }
        keys.sort();
        keys.dedup();
        Ok(keys)
    }
}
/// @cc [owner:OctavianTocan,label:architecture] rust-store-seeds-from-legacy-cache
/// When the default data root does not exist, startup MUST fill it with a copy of
/// the TypeScript server's cache, so a deploy of the native server keeps accounts,
/// agent keys, posted diffs and review state. The copy MUST NOT write to the
/// original, which stays the rollback cache, and MUST only appear complete (copied
/// aside, then renamed). Source: merging the rewrite auto-deploys the server
/// (Tavi, 16 Oct 12:18), and posted links are kept forever (Tavi, 16 Oct 12:24).
///
/// Returns whether a copy was made.
pub fn seed_from_legacy(root: &Path, legacy: &Path) -> Result<bool> {
    if fs::symlink_metadata(root).is_ok() || !fs::symlink_metadata(legacy).is_ok_and(|m| m.is_dir())
    {
        return Ok(false);
    }
    let parent = root.parent().context("Data directory has no parent")?;
    let staging = parent.join(format!(".pulls-review-seed-{}", uuid::Uuid::new_v4()));
    // Copy aside and rename into place, so an interrupted copy never looks seeded.
    let result = copy_tree(legacy, &staging).and_then(|()| Ok(fs::rename(&staging, root)?));
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
        // Another server seeded or created the root first.
        if fs::symlink_metadata(root).is_ok() {
            return Ok(false);
        }
    }
    result.map(|()| true)
}
fn copy_tree(from: &Path, to: &Path) -> Result<()> {
    fs::create_dir(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let target = to.join(entry.file_name());
        // Symlinks and special files are skipped; the store refuses symlinks anyway.
        if kind.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else if kind.is_file() {
            fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Pause the first settings read after taking its snapshot, inside a real merge.
    struct PausedSettings {
        inner: Store,
        pause: Mutex<Option<(std::sync::mpsc::Sender<()>, std::sync::mpsc::Receiver<()>)>>,
    }
    impl Records for PausedSettings {
        fn get(&self, id: &str) -> Result<Option<Vec<u8>>> {
            let value = self.inner.lock.records.get(id)?;
            if id == "account-settings/owner" {
                let pause = self.pause.lock().unwrap().take();
                if let Some((ready, release)) = pause {
                    ready.send(())?;
                    release.recv_timeout(std::time::Duration::from_secs(5))?;
                }
            }
            Ok(value)
        }
        fn put(&self, id: &str, bytes: &[u8]) -> Result<()> {
            self.inner.lock.records.put(id, bytes)
        }
        fn delete(&self, id: &str) -> Result<bool> {
            self.inner.lock.records.delete(id)
        }
        fn ids(&self) -> Result<Vec<String>> {
            self.inner.lock.records.ids()
        }
        fn ids_with_prefix(&self, prefix: &str) -> Result<Vec<String>> {
            self.inner.lock.records.ids_with_prefix(prefix)
        }
    }

    fn workspace_pair(durable: Store) -> (Store, Store, crate::identity::GitHubCredential) {
        use crate::identity::{GitHubCredential, RequestIdentity};
        let first = GitHubCredential::fixture("owner", 42, "fixture-first-session");
        let second = GitHubCredential::fixture("owner", 42, "fixture-second-session");
        let durable = Arc::new(durable);
        (
            Store::workspace(durable.clone(), RequestIdentity::Account(first), "first"),
            Store::workspace(durable, RequestIdentity::Account(second.clone()), "second"),
            second,
        )
    }

    #[tokio::test]
    async fn workspace_sessions_serialize_complete_settings_merges() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let root =
            std::env::temp_dir().join(format!("pulls-workspace-merge-{}", uuid::Uuid::new_v4()));
        let disk = Store::new(root.clone()).unwrap();
        let pause = Arc::new(PausedSettings {
            inner: disk,
            pause: Mutex::new(None),
        });
        // Keep the latch accessible while the adapter is boxed by Store.
        struct Shared(Arc<PausedSettings>);
        impl Records for Shared {
            fn get(&self, id: &str) -> Result<Option<Vec<u8>>> {
                self.0.get(id)
            }
            fn put(&self, id: &str, bytes: &[u8]) -> Result<()> {
                self.0.put(id, bytes)
            }
            fn delete(&self, id: &str) -> Result<bool> {
                self.0.delete(id)
            }
            fn ids(&self) -> Result<Vec<String>> {
                self.0.ids()
            }
        }
        let durable =
            Store::with_records(root.join("coordinator"), Box::new(Shared(pause.clone())));
        let (a, b, second) = workspace_pair(durable);
        a.set("my-pulls:open", &json!("first cache")).unwrap();
        b.set("my-pulls:open", &json!("second cache")).unwrap();
        let first_accounts = crate::account::Accounts::new(a.clone());
        let second_accounts = crate::account::Accounts::new(b.clone());
        let finished = Arc::new(AtomicUsize::new(0));
        let first_finished = finished.clone();
        let held = test_support::HeldLock::new(move |ready, release| {
            *pause.pause.lock().unwrap() = Some((ready, release));
            let result = first_accounts.merge_settings(
                "owner",
                json!({
                    "theme": {"value":"dark","at":30}, "sound": {"value":false,"at":10}
                }),
            );
            first_finished.fetch_add(1, Ordering::SeqCst);
            result.is_ok()
        });
        let merged = held
            .assert_yields(second_accounts.merge_settings_async(
                "owner",
                json!({
                    "language": {"value":"es","at":20}, "theme": {"value":"light","at":5},
                    "sound": {"value":true,"at":40}
                }),
            ))
            .await
            .unwrap();
        assert_eq!(finished.load(Ordering::SeqCst), 1);
        assert_eq!(
            merged,
            json!({
                "theme":{"value":"dark","at":30.0}, "sound":{"value":true,"at":40.0},
                "language":{"value":"es","at":20.0}
            })
        );
        assert_eq!(
            crate::account::Accounts::new(a.clone())
                .settings("owner")
                .unwrap(),
            merged
        );
        assert_eq!(a.get("my-pulls:open").unwrap(), Some(json!("first cache")));
        assert_eq!(b.get("my-pulls:open").unwrap(), Some(json!("second cache")));
        second.revoke();
        assert!(b
            .update("account-settings:owner", |_| Ok(json!({})))
            .is_err());
        assert!(b
            .compare_exchange(&[("account-settings:owner".into(), None, None)])
            .is_err());
        assert!(b.keys("account-settings:").is_err());
        assert!(b.get("my-pulls:open").is_err());
        assert_eq!(
            a.get("account-settings:owner").unwrap().unwrap()["values"],
            merged
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn workspace_cas_serializes_with_other_binding_updates_and_rechecks_revocation() {
        let root =
            std::env::temp_dir().join(format!("pulls-workspace-cas-{}", uuid::Uuid::new_v4()));
        let durable = Store::new(root.clone()).unwrap();
        let (a, b, second) = workspace_pair(durable.clone());
        a.set("review:one", &json!(0)).unwrap();
        let held = test_support::HeldLock::new(move |ready, release| {
            a.update("review:one", |_| {
                ready.send(())?;
                release.recv_timeout(std::time::Duration::from_secs(5))?;
                Ok(json!(1))
            })
            .is_ok()
        });
        let cas = held
            .assert_yields(b.transaction(|store| {
                store.compare_exchange(&[
                    ("review:one".into(), Some(json!(0)), Some(json!(2))),
                    ("my-pulls:new".into(), None, Some(json!("must not appear"))),
                ])
            }))
            .await;
        assert!(cas.is_err());
        assert_eq!(b.get("review:one").unwrap(), Some(json!(1)));
        assert!(b.get("my-pulls:new").unwrap().is_none());
        let held = test_support::HeldLock::storage(&durable);
        second.revoke();
        assert!(held
            .assert_yields(b.set_async("review:one", &json!(3)))
            .await
            .is_err());
        assert_eq!(durable.get("review:one").unwrap(), Some(json!(1)));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn prefix_queries_preserve_flat_keys_and_legacy_aliases() {
        let root =
            std::env::temp_dir().join(format!("pulls-store-prefix-{}", uuid::Uuid::new_v4()));
        let store = Store::new(root.clone()).unwrap();
        for key in [
            "analytics:2026-10-25:1",
            "analytics:2026-10-24:1",
            "account",
            "account-settings:owner",
            "diff:a/b#1",
            "pr-meta:legacy",
            "pr-body:legacy",
            "review:a%_é",
            "review:another",
            "different:one",
        ] {
            store.set(key, &json!({})).unwrap();
        }
        let all = store.keys("").unwrap();
        for prefix in [
            "",
            "a",
            "account",
            "analytics:2026-10-25:",
            "pr-",
            "pr-meta:",
            "pr-body:",
            "diff",
            "diff:a/b",
            "review:a%_é",
            "missing:",
        ] {
            let normalized = prefix.replace(['/', '\\'], ":");
            let expected: Vec<_> = all
                .iter()
                .filter(|key| key.starts_with(&normalized))
                .cloned()
                .collect();
            assert_eq!(store.keys(prefix).unwrap(), expected, "prefix {prefix}");
        }
        // A narrow collection lookup must not descend into unrelated record directories.
        fs::create_dir(root.join("unrelated")).unwrap();
        fs::write(root.join("unrelated/broken"), b"not JSON").unwrap();
        assert_eq!(
            store
                .lock
                .records
                .ids_with_prefix("analytics/2026-10-25%3A")
                .unwrap(),
            vec!["analytics/2026-10-25%3A1"]
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn workspace_bindings_share_diff_metadata_without_bypassing_revocation() {
        let root =
            std::env::temp_dir().join(format!("pulls-workspace-index-{}", uuid::Uuid::new_v4()));
        let durable = Store::new(root.clone()).unwrap();
        let (a, b, second) = workspace_pair(durable.clone());
        a.set("diff:one", &json!({"sizeBytes":10,"lastViewedAt":1}))
            .unwrap();
        let first = b.diff_snapshot(None).unwrap().unwrap();
        a.update("diff:one", |_| Ok(json!({"sizeBytes":20,"lastViewedAt":2})))
            .unwrap();
        let next = b.diff_snapshot(Some(first.revision)).unwrap().unwrap();
        assert_eq!(next.size_bytes, 20);
        assert!(b
            .diff_entry_if_unchanged("diff:one", first.entries["diff:one"].revision)
            .unwrap()
            .is_none());
        durable.set("diff:two", &json!({"sizeBytes":5})).unwrap();
        assert_eq!(
            a.diff_snapshot(Some(next.revision))
                .unwrap()
                .unwrap()
                .size_bytes,
            25
        );
        second.revoke();
        assert!(b.diff_snapshot(None).is_err());
        assert!(b.diff_snapshot(Some(next.revision)).is_err());
        assert!(b
            .diff_entry_if_unchanged("diff:one", next.entries["diff:one"].revision)
            .is_err());
        assert_eq!(a.diff_snapshot(None).unwrap().unwrap().size_bytes, 25);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn diff_metadata_tracks_all_mutations_across_store_instances() {
        let root =
            std::env::temp_dir().join(format!("pulls-store-metadata-{}", uuid::Uuid::new_v4()));
        let a = Store::new(root.clone()).unwrap();
        let b = Store::new(root.clone()).unwrap();
        let native = "diff:github:o/r#1";
        let normalized = "diff:github:o:r#1";
        a.set(native, &json!({"sizeBytes":10,"lastViewedAt":1}))
            .unwrap();
        a.set(
            "pr-meta:github:o/r#2",
            &json!({"sizeBytes":20,"lastViewedAt":2}),
        )
        .unwrap();
        a.set("pr-body:github:o/r#2", &json!({"diff":{"title":"legacy"}}))
            .unwrap();
        let initial = a.diff_snapshot(None).unwrap().unwrap();
        assert_eq!(initial.entries.len(), 2);
        assert_eq!(initial.size_bytes, 30);
        let reads = a.diff_body_reads();
        assert!(a.diff_snapshot(Some(initial.revision)).unwrap().is_none());
        assert_eq!(a.diff_body_reads(), reads);
        b.set(native, &json!({"sizeBytes":30,"lastViewedAt":3}))
            .unwrap();
        let changed = a.diff_snapshot(Some(initial.revision)).unwrap().unwrap();
        assert_eq!(changed.size_bytes, 50);
        assert_eq!(changed.entries[normalized].last_viewed_at, 3);
        assert_eq!(a.diff_body_reads(), reads);
        assert!(a
            .diff_entry_if_unchanged(normalized, initial.entries[normalized].revision)
            .unwrap()
            .is_none());
        b.update(native, |old| {
            let mut next = old.unwrap();
            next["sizeBytes"] = json!(40);
            next["lastViewedAt"] = json!(4);
            Ok(next)
        })
        .unwrap();
        let after_update = a.diff_snapshot(Some(changed.revision)).unwrap().unwrap();
        assert_eq!(after_update.size_bytes, 60);
        assert_eq!(after_update.entries[normalized].last_viewed_at, 4);
        let before = b.get(native).unwrap();
        b.compare_exchange(&[(
            native.into(),
            before.clone(),
            Some(json!({"sizeBytes":50,"lastViewedAt":5})),
        )])
        .unwrap();
        let after_cas = a
            .diff_snapshot(Some(after_update.revision))
            .unwrap()
            .unwrap();
        assert_eq!(after_cas.size_bytes, 70);
        assert_eq!(after_cas.entries[normalized].last_viewed_at, 5);
        assert!(b
            .compare_exchange(&[(native.into(), before, None)])
            .is_err());
        assert!(a.diff_snapshot(Some(after_cas.revision)).unwrap().is_none());
        b.set("review:unrelated", &json!(1)).unwrap();
        assert!(a.diff_snapshot(Some(after_cas.revision)).unwrap().is_none());
        b.remove(native).unwrap();
        let removed = a.diff_snapshot(Some(after_cas.revision)).unwrap().unwrap();
        assert_eq!(removed.entries.len(), 1);
        assert_eq!(removed.size_bytes, 20);

        // Both spellings address the same legacy record, including separator aliases.
        b.update("pr-meta:github:o/r#2", |_| {
            Ok(json!({"sizeBytes":25,"lastViewedAt":9}))
        })
        .unwrap();
        let legacy = a.diff_snapshot(Some(removed.revision)).unwrap().unwrap();
        assert_eq!(legacy.size_bytes, 25);
        assert_eq!(legacy.entries["diff:github:o:r#2"].last_viewed_at, 9);
        b.set(
            "pr-body/github/o/r#2",
            &json!({"diff":{"title":"new legacy"}}),
        )
        .unwrap();
        let body_changed = a.diff_snapshot(Some(legacy.revision)).unwrap().unwrap();
        assert_ne!(
            body_changed.entries["diff:github:o:r#2"].revision,
            legacy.entries["diff:github:o:r#2"].revision
        );
        b.remove("pr-body:github:o/r#2").unwrap();
        assert!(a
            .diff_snapshot(Some(body_changed.revision))
            .unwrap()
            .unwrap()
            .entries
            .is_empty());
        b.set(
            "pr-body:github:o/r#2",
            &json!({"diff":{"title":"restored"}}),
        )
        .unwrap();
        assert_eq!(a.diff_snapshot(None).unwrap().unwrap().size_bytes, 25);

        // Removing a native override must reveal and reindex its legacy fallback.
        b.set(
            "diff:github:o/r#2",
            &json!({"sizeBytes":80,"lastViewedAt":10}),
        )
        .unwrap();
        assert_eq!(a.diff_snapshot(None).unwrap().unwrap().size_bytes, 80);
        b.remove("diff:github:o/r#2").unwrap();
        assert_eq!(a.diff_snapshot(None).unwrap().unwrap().size_bytes, 25);
        let meta = b.get("pr-meta:github:o/r#2").unwrap();
        let body = b.get("pr-body:github:o/r#2").unwrap();
        b.compare_exchange(&[
            ("pr-meta:github:o/r#2".into(), meta, None),
            ("pr-body:github:o/r#2".into(), body, None),
        ])
        .unwrap();
        assert!(a.diff_snapshot(None).unwrap().unwrap().entries.is_empty());
        drop((a, b));
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn metadata_selected_eviction_does_not_delete_an_intervening_write() {
        let root =
            std::env::temp_dir().join(format!("pulls-store-eviction-{}", uuid::Uuid::new_v4()));
        let store = Store::new(root.clone()).unwrap();
        let key = "diff:one";
        store
            .set(key, &json!({"sizeBytes":10,"lastViewedAt":1,"headSha":"a"}))
            .unwrap();
        let snapshot = store.diff_snapshot(None).unwrap().unwrap();
        let revision = snapshot.entries[key].revision;
        let selected = store
            .diff_entry_if_unchanged(key, revision)
            .unwrap()
            .unwrap();
        // Even an unchanged size/view must not hide a newer captured head.
        store
            .set(key, &json!({"sizeBytes":10,"lastViewedAt":1,"headSha":"b"}))
            .unwrap();
        assert!(store
            .diff_entry_if_unchanged(key, revision)
            .unwrap()
            .is_none());
        assert!(store
            .compare_exchange(&[(key.into(), Some(selected), None)])
            .is_err());
        assert_eq!(store.get(key).unwrap().unwrap()["headSha"], "b");
        drop(store);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn legacy_split_reads_and_cas() {
        let root = std::env::temp_dir().join(format!("pulls-store-{}", uuid::Uuid::new_v4()));
        let a = Store::new(root.clone()).unwrap();
        a.set(
            "pr-meta:github:a/b#2",
            &json!({"key":"github:a/b#2","headSha":"h","lastViewedAt":1,"sizeBytes":2}),
        )
        .unwrap();
        a.set(
            "pr-body:github:a/b#2",
            &json!({"diff":{"title":"Old","files":[],"ref":{}},"analyzedBy":{}}),
        )
        .unwrap();
        let mut record = a.get("diff:github:a/b#2").unwrap().unwrap();
        assert_eq!(record["headSha"], "h");
        record["diff"]["title"] = json!("New");
        a.set("diff:github:a/b#2", &record).unwrap();
        assert_eq!(
            a.get("pr-meta:github:a/b#2").unwrap().unwrap()["title"],
            "New"
        );
        a.set("review:a", &json!(1)).unwrap();
        a.set("review:b", &json!(2)).unwrap();
        assert!(a
            .compare_exchange(&[
                ("review:a".into(), Some(json!(1)), Some(json!(4))),
                ("review:b".into(), Some(json!(9)), None)
            ])
            .is_err());
        assert_eq!(a.get("review:a").unwrap(), Some(json!(1)));
        drop(a);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn seeds_an_absent_root_from_the_legacy_cache_once() {
        let base = std::env::temp_dir().join(format!("pulls-seed-{}", uuid::Uuid::new_v4()));
        let (legacy, root) = (base.join("pulls-review"), base.join("pulls-review-rust"));
        assert!(!seed_from_legacy(&root, &legacy).unwrap());
        fs::create_dir_all(legacy.join("account")).unwrap();
        fs::write(legacy.join("account").join("me"), b"{\"id\":1}").unwrap();
        fs::write(legacy.join("review"), b"0").unwrap();
        assert!(seed_from_legacy(&root, &legacy).unwrap());
        let store = Store::new(root.clone()).unwrap();
        assert_eq!(store.get("account:me").unwrap(), Some(json!({"id":1})));
        store.set("account:me", &json!({"id":2})).unwrap();
        assert_eq!(
            fs::read(legacy.join("account").join("me")).unwrap(),
            b"{\"id\":1}"
        );
        assert!(!seed_from_legacy(&root, &legacy).unwrap());
        assert_eq!(store.get("account:me").unwrap(), Some(json!({"id":2})));
        drop(store);
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn flat_keys_and_atomic_updates() {
        let root = std::env::temp_dir().join(format!("pulls-store-{}", uuid::Uuid::new_v4()));
        let a = Store::new(root.clone()).unwrap();
        let b = Store::new(root.clone()).unwrap();
        a.set("review:github:a/b#2", &json!(0)).unwrap();
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let b = b.clone();
                std::thread::spawn(move || {
                    for _ in 0..20 {
                        b.update("review:github:a/b#2", |v| {
                            Ok(json!(v.unwrap().as_i64().unwrap() + 1))
                        })
                        .unwrap();
                    }
                })
            })
            .collect();
        for w in workers {
            w.join().unwrap();
        }
        assert_eq!(a.get("review:github:a/b#2").unwrap(), Some(json!(160)));
        assert!(a.set("../escape:x", &json!(1)).is_err());
        assert_eq!(a.keys("review:").unwrap(), vec!["review:github:a:b#2"]);
        fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
pub(crate) mod test_support;
