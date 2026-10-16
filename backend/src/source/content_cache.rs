//! TypeScript-compatible, persistent full-file byte/count LRU.
use crate::{now_ms, store::Store};
use anyhow::{Context, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
};
const MAX_BYTES: u64 = 25 * 1024 * 1024;
const MAX_ENTRIES: usize = 500;
static LOCKS: OnceLock<Mutex<HashMap<PathBuf, Arc<Mutex<()>>>>> = OnceLock::new();
fn lock(store: &Store) -> Arc<Mutex<()>> {
    LOCKS
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entry(store.root().to_path_buf())
        .or_default()
        .clone()
}
fn key(scope: &str, sha: &str, path: &str) -> String {
    hex::encode(Sha256::digest(
        serde_json::to_vec(&json!([scope, sha, path])).unwrap(),
    ))
}
fn remove(store: &Store, key: &str) -> Result<()> {
    store.remove(&format!("file-content:{key}"))?;
    store.remove(&format!("file-content-meta:{key}"))
}
fn quota(error: &anyhow::Error) -> bool {
    error
        .chain()
        .filter_map(|e| e.downcast_ref::<std::io::Error>())
        .any(|e| matches!(e.raw_os_error(), Some(28 | 122)))
}
fn read(store: &Store, key: &str) -> Result<Option<Value>> {
    match store.get(key) {
        Err(error) if error.chain().any(|e| e.is::<serde_json::Error>()) => Ok(None),
        value => value,
    }
}
fn valid_meta(meta: &Value) -> bool {
    meta["scope"].is_string()
        && meta["sizeBytes"].as_u64().is_some()
        && meta["lastAccessedAt"].as_u64().is_some()
}
fn list(store: &Store) -> Result<Vec<(String, Value)>> {
    let mut metas = vec![];
    let mut known = HashSet::new();
    for path in store.keys("file-content-meta:")? {
        let suffix = path.strip_prefix("file-content-meta:").unwrap();
        if let Some(meta) = read(store, &path)?.filter(valid_meta) {
            known.insert(format!("file-content:{suffix}"));
            metas.push((suffix.to_owned(), meta));
        } else {
            remove(store, suffix)?;
        }
    }
    for path in store.keys("file-content:")? {
        if !known.contains(&path) {
            store.remove(&path)?;
        }
    }
    Ok(metas)
}
/// @cc [owner:OctavianTocan,label:architecture] rust-fullfile-cache-off-runtime
/// Async source reads MUST run complete cache transactions, including lock acquisition,
/// on the blocking pool (16 Oct 2026 parity review, round 2 finding #6).
pub async fn get_async(
    store: &Store,
    scope: &str,
    sha: &str,
    path: &str,
) -> Result<Option<String>> {
    let store = store.clone();
    let (scope, sha, path) = (scope.to_owned(), sha.to_owned(), path.to_owned());
    tokio::task::spawn_blocking(move || get(&store, &scope, &sha, &path))
        .await
        .context("Full-file cache read task failed")?
}
pub async fn set_async(
    store: &Store,
    scope: &str,
    sha: &str,
    path: &str,
    content: &str,
) -> Result<()> {
    let store = store.clone();
    let (scope, sha, path, content) = (
        scope.to_owned(),
        sha.to_owned(),
        path.to_owned(),
        content.to_owned(),
    );
    tokio::task::spawn_blocking(move || set(&store, &scope, &sha, &path, &content))
        .await
        .context("Full-file cache write task failed")?
}
pub fn get(store: &Store, scope: &str, sha: &str, path: &str) -> Result<Option<String>> {
    let mutex = lock(store);
    let _guard = mutex
        .lock()
        .map_err(|_| anyhow::anyhow!("Full-file cache lock unavailable"))?;
    let key = key(scope, sha, path);
    let Some(mut meta) = read(store, &format!("file-content-meta:{key}"))?.filter(valid_meta)
    else {
        remove(store, &key)?;
        return Ok(None);
    };
    let Some(content) = read(store, &format!("file-content:{key}"))?
        .and_then(|v| v["content"].as_str().map(str::to_owned))
    else {
        remove(store, &key)?;
        return Ok(None);
    };
    // Corrupt or unaccounted bodies are misses, never an escape from the budget.
    if meta["sizeBytes"].as_u64() != Some(content.len() as u64)
        || meta["scope"] != scope
        || content.len() as u64 > MAX_BYTES
    {
        remove(store, &key)?;
        return Ok(None);
    }
    meta["lastAccessedAt"] = json!(now_ms());
    if let Err(error) = store.set(&format!("file-content-meta:{key}"), &meta) {
        if !quota(&error) {
            return Err(error);
        }
    }
    Ok(Some(content))
}
/// @cc [owner:OctavianTocan,label:architecture] rust-fullfile-persistent-budget
/// Full-file text MUST be persisted under a separate byte and count LRU budget;
/// quota failures MUST NOT fail a successful source read (port of
/// engine-file-content-cache-is-bounded, requested review fixes 15 Oct 2026 #30).
pub fn set(store: &Store, scope: &str, sha: &str, path: &str, content: &str) -> Result<()> {
    set_budget(store, scope, sha, path, content, MAX_BYTES, MAX_ENTRIES)
}
fn set_budget(
    store: &Store,
    scope: &str,
    sha: &str,
    path: &str,
    content: &str,
    max_bytes: u64,
    max_entries: usize,
) -> Result<()> {
    let mutex = lock(store);
    let _guard = mutex
        .lock()
        .map_err(|_| anyhow::anyhow!("Full-file cache lock unavailable"))?;
    let result = (|| -> Result<()> {
        let key = key(scope, sha, path);
        let bytes = content.len() as u64;
        let mut metas = list(store)?;
        metas.retain(|(other, _)| other != &key);
        metas.sort_by(|a, b| {
            b.1["lastAccessedAt"]
                .as_u64()
                .cmp(&a.1["lastAccessedAt"].as_u64())
        });
        if bytes > max_bytes || max_entries == 0 {
            remove(store, &key)?;
            return Ok(());
        }
        let mut total = bytes;
        let mut count = 1;
        for (key, meta) in metas {
            let size = meta["sizeBytes"]
                .as_u64()
                .context("Invalid full-file size")?;
            if total.saturating_add(size) <= max_bytes && count < max_entries {
                total += size;
                count += 1;
            } else {
                remove(store, &key)?;
            }
        }
        let written = store
            .set(&format!("file-content:{key}"), &json!({"content":content}))
            .and_then(|_| {
                store.set(
                    &format!("file-content-meta:{key}"),
                    &json!({"scope":scope,"sizeBytes":bytes,"lastAccessedAt":now_ms()}),
                )
            });
        if let Err(error) = written {
            let _ = remove(store, &key);
            return Err(error);
        }
        Ok(())
    })();
    match result {
        Err(error) if quota(&error) => Ok(()),
        result => result,
    }
}
pub fn remove_scope(store: &Store, scope: &str) -> Result<()> {
    let mutex = lock(store);
    let _guard = mutex
        .lock()
        .map_err(|_| anyhow::anyhow!("Full-file cache lock unavailable"))?;
    for (key, meta) in list(store)? {
        if meta["scope"] == scope {
            remove(store, &key)?;
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn store() -> Store {
        Store::new(std::env::temp_dir().join(format!("pulls-fullfile-{}", uuid::Uuid::new_v4())))
            .unwrap()
    }
    // A separate lock holder releases on a channel. The timeout only bounds a
    // broken synchronous implementation; assertions depend on Pending, not timing.
    struct HeldCache {
        release: std::sync::mpsc::Sender<()>,
        holder: Option<std::thread::JoinHandle<bool>>,
    }
    impl HeldCache {
        fn new(store: &Store) -> Self {
            let mutex = lock(store);
            let (ready_tx, ready_rx) = std::sync::mpsc::channel();
            let (release, released) = std::sync::mpsc::channel();
            let holder = std::thread::spawn(move || {
                let _guard = mutex.lock().unwrap();
                ready_tx.send(()).unwrap();
                released
                    .recv_timeout(std::time::Duration::from_secs(5))
                    .is_ok()
            });
            ready_rx
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
            Self {
                release,
                holder: Some(holder),
            }
        }
        fn release(mut self) {
            let _ = self.release.send(());
            assert!(
                self.holder.take().unwrap().join().unwrap(),
                "cache lock watchdog fired"
            );
        }
    }
    impl Drop for HeldCache {
        fn drop(&mut self) {
            let _ = self.release.send(());
            if let Some(holder) = self.holder.take() {
                let _ = holder.join();
            }
        }
    }
    #[tokio::test(flavor = "current_thread")]
    async fn async_cache_transactions_yield_while_waiting_for_the_lock() {
        let store = store();
        let state =
            crate::AppState::new(store.root().to_path_buf(), store.root().to_path_buf()).unwrap();
        let reference = json!({"kind":"github-commit","owner":"fixture","repo":"repo","sha":"h"});
        let scope = super::super::serialize_ref(&reference).unwrap();
        set(&store, &scope, "h", "file.rs", "cached text").unwrap();
        let args = json!({"ref":reference,"sha":"h","path":"file.rs"});

        // Exercise the production load_file path, without a GitHub request.
        let held = HeldCache::new(&store);
        let mut read = Box::pin(super::super::load_file(&state, &args));
        assert!(futures_util::poll!(&mut read).is_pending());
        assert_eq!(
            tokio::spawn(async { "runtime available" }).await.unwrap(),
            "runtime available"
        );
        held.release();
        assert_eq!(read.await.unwrap(), json!("cached text"));

        // This is the same write boundary load_file awaits after a source miss.
        let held = HeldCache::new(&store);
        let mut write = Box::pin(set_async(&store, &scope, "h", "other.rs", "new text"));
        assert!(futures_util::poll!(&mut write).is_pending());
        assert_eq!(tokio::spawn(async { 42 }).await.unwrap(), 42);
        held.release();
        write.await.unwrap();
        assert_eq!(
            get(&store, &scope, "h", "other.rs").unwrap(),
            Some("new text".into())
        );
        assert_eq!(
            get_async(&store, "other scope", "h", "other.rs")
                .await
                .unwrap(),
            None
        );
        std::fs::remove_dir_all(store.root()).unwrap();
    }
    #[test]
    fn byte_count_lru_and_scope_eviction() {
        let store = store();
        set_budget(&store, "a", "h", "a", "éé", 8, 2).unwrap();
        let a = key("a", "h", "a");
        store
            .set(
                &format!("file-content-meta:{a}"),
                &json!({"scope":"a","sizeBytes":4,"lastAccessedAt":1}),
            )
            .unwrap();
        set_budget(&store, "a", "h", "b", "1234", 8, 2).unwrap();
        set_budget(&store, "other", "h", "c", "5678", 8, 2).unwrap();
        assert_eq!(get(&store, "a", "h", "a").unwrap(), None);
        assert_eq!(get(&store, "a", "h", "b").unwrap(), Some("1234".into()));
        set_budget(&store, "a", "h", "oversize", "123456789", 8, 2).unwrap();
        assert_eq!(get(&store, "a", "h", "oversize").unwrap(), None);
        remove_scope(&store, "a").unwrap();
        assert_eq!(get(&store, "a", "h", "b").unwrap(), None);
        assert_eq!(get(&store, "other", "h", "c").unwrap(), Some("5678".into()));
        std::fs::remove_dir_all(store.root()).unwrap();
    }
    #[test]
    fn persistent_corrupt_and_legacy_entries() {
        assert_eq!(
            key("github:o/r#1", "sha-a", "src/foo.ts"),
            "e7b0a61b8e91e060396d765bb3823a366ef0453a37c75654ee159025ad99c323"
        ); // Node JSON.stringify + SHA256 fixture.
        let store = store();
        store
            .set("file-content:legacy", &json!("unbudgeted"))
            .unwrap();
        set(&store, "repo/a", "h", "long/path", "text").unwrap();
        assert!(store.get("file-content:legacy").unwrap().is_none());
        let reopened = Store::new(store.root().to_path_buf()).unwrap();
        assert_eq!(
            get(&reopened, "repo/a", "h", "long/path").unwrap(),
            Some("text".into())
        );
        assert_eq!(get(&reopened, "repo:a", "h", "long/path").unwrap(), None);
        let key = key("repo/a", "h", "long/path");
        store
            .set(&format!("file-content-meta:{key}"), &json!({"bad":true}))
            .unwrap();
        assert_eq!(get(&reopened, "repo/a", "h", "long/path").unwrap(), None);
        assert!(store.get(&format!("file-content:{key}")).unwrap().is_none());
        std::fs::remove_dir_all(store.root()).unwrap();
    }
}
