//! Atomic JSON storage. Reads the TypeScript unstorage flat-key layout directly.
//! Rust diff records use `diff:`; legacy `pr-meta:` + `pr-body:` records are joined
//! on read and retained, so upgrading never destructively rewrites the old cache.
use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::{
    collections::{BTreeMap, HashMap},
    fs,
    io::Write,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock, Weak},
};

#[derive(Clone)]
pub struct Store {
    root: PathBuf,
    lock: Arc<RootLock>,
}
struct RootLock {
    mutex: Mutex<()>,
    diff_index: Mutex<DiffIndex>,
    #[cfg(test)]
    diff_body_reads: std::sync::atomic::AtomicUsize,
    _file: fs::File,
}
#[derive(Clone, Copy)]
pub(crate) struct DiffEntryMetadata {
    pub revision: u64,
    pub size_bytes: u64,
    pub last_viewed_at: u64,
}
impl DiffEntryMetadata {
    fn new(entry: &Value, revision: u64) -> Self {
        Self {
            revision,
            size_bytes: entry["sizeBytes"].as_u64().unwrap_or(0),
            last_viewed_at: entry["lastViewedAt"].as_u64().unwrap_or(0),
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
static LOCKS: OnceLock<Mutex<HashMap<PathBuf, Weak<RootLock>>>> = OnceLock::new();
fn encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}
fn decode(s: &str) -> Option<String> {
    let bytes = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let high = (bytes[i + 1] as char).to_digit(16)? as u8;
            let low = (bytes[i + 2] as char).to_digit(16)? as u8;
            out.push(high * 16 + low);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}
impl Store {
    pub fn root(&self) -> &std::path::Path {
        &self.root
    }
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
            let lock = Arc::new(RootLock {
                mutex: Mutex::new(()),
                diff_index: Mutex::new(DiffIndex::default()),
                #[cfg(test)]
                diff_body_reads: std::sync::atomic::AtomicUsize::new(0),
                _file: file,
            });
            locks.insert(root.clone(), Arc::downgrade(&lock));
            lock
        };
        Ok(Self { root, lock })
    }
    fn path(&self, key: &str) -> Result<PathBuf> {
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
        let path = if let Some(rest) = parts.next() {
            let name = encode(rest);
            if name.is_empty() || name == "." || name == ".." {
                bail!("Invalid storage key");
            }
            self.root.join(head).join(name)
        } else {
            self.root.join(head)
        };
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
    fn raw_get(&self, key: &str) -> Result<Option<Value>> {
        let path = self.path(key)?;
        match fs::read(path) {
            Ok(bytes) => {
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
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.into()),
        }
    }
    fn read(&self, key: &str) -> Result<Option<Value>> {
        if let Some(scope) = key
            .strip_prefix("pr-meta:")
            .or_else(|| key.strip_prefix("pr-body:"))
        {
            if let Some(entry) = self.raw_get(&format!("diff:{scope}"))? {
                return Ok(Some(if key.starts_with("pr-meta:") {
                    crate::review::metadata(&entry)
                } else {
                    let mut body = serde_json::Map::new();
                    for name in ["diff", "analyzedBy", "reviews", "llmSession"] {
                        if let Some(v) = entry.get(name) {
                            body.insert(name.into(), v.clone());
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
        let path = self.path(key)?;
        let parent = path.parent().unwrap();
        fs::create_dir_all(parent)?;
        let temporary = parent.join(format!(".tmp-{}", uuid::Uuid::new_v4()));
        let result = (|| -> Result<()> {
            let mut file = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(&serde_json::to_vec(value)?)?;
            file.sync_all()?;
            fs::rename(&temporary, &path)?;
            self.note_diff_mutation(key, Some(value));
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
        match fs::remove_file(self.path(key)?) {
            Ok(()) => {
                self.note_diff_mutation(key, None);
                Ok(())
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.into()),
        }
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
            let target = self.path(key)?;
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
        let mut keys = Vec::new();
        for entry in fs::read_dir(&self.root)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            let head = entry.file_name().to_string_lossy().into_owned();
            if head.starts_with('.') || kind.is_symlink() {
                continue;
            }
            if kind.is_dir() {
                for file in fs::read_dir(entry.path())? {
                    let file = file?;
                    if !file.file_type()?.is_file() {
                        continue;
                    }
                    let name = file.file_name().to_string_lossy().into_owned();
                    if name.starts_with('.') {
                        continue;
                    }
                    if let Some(name) = decode(&name) {
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
            } else if kind.is_file() && head.starts_with(&prefix) {
                keys.push(head);
            }
        }
        keys.sort();
        keys.dedup();
        Ok(keys)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
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
