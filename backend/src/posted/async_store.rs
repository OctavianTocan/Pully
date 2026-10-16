use super::*;

impl PostedStore {
    /// @cc [owner:OctavianTocan,label:architecture] rust-posted-transactions-off-runtime
    /// Async posted endpoints MUST run complete storage transactions, including lock
    /// acquisition, parsing and compression, on the blocking pool (Rust parity
    /// review, 16 Oct 2026, round 11 finding 4).
    async fn transaction<T: Send + 'static>(
        &self,
        operation: impl FnOnce(Self) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let store = self.clone();
        tokio::task::spawn_blocking(move || operation(store))
            .await
            .context("Posted storage task failed")?
    }

    pub async fn meta_async(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |store| store.meta(&account_id, &id))
            .await
    }

    pub async fn list_async(&self, account_id: &str) -> Result<Vec<Value>> {
        let account_id = account_id.to_owned();
        self.transaction(move |store| store.list(&account_id)).await
    }

    pub async fn read_async(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |store| store.read(&account_id, &id))
            .await
    }

    pub async fn save_async(
        &self,
        account_id: &str,
        key_name: &str,
        id: Option<&str>,
        patch: Option<&str>,
        fields: &Value,
    ) -> Result<Value> {
        let (account_id, key_name, id, patch, fields) = (
            account_id.to_owned(),
            key_name.to_owned(),
            id.map(str::to_owned),
            patch.map(str::to_owned),
            fields.clone(),
        );
        self.transaction(move |store| {
            store.save(
                &account_id,
                &key_name,
                id.as_deref(),
                patch.as_deref(),
                &fields,
            )
        })
        .await
    }

    pub async fn open_async(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |store| store.open(&account_id, &id))
            .await
    }

    pub async fn remove_async(&self, account_id: &str, id: &str) -> Result<bool> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |store| store.remove(&account_id, &id))
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{future::Future, time::Duration};

    // A watchdog releases the lock if a regression blocks the single runtime
    // thread. Success depends on yielding before release, not a timing race.
    struct HeldWrites {
        release: std::sync::mpsc::Sender<()>,
        holder: Option<std::thread::JoinHandle<bool>>,
    }
    impl HeldWrites {
        fn new() -> Self {
            let (ready_tx, ready_rx) = std::sync::mpsc::channel();
            let (release, released) = std::sync::mpsc::channel();
            let holder = std::thread::spawn(move || {
                let _guard = WRITES.lock().unwrap();
                ready_tx.send(()).unwrap();
                released.recv_timeout(Duration::from_secs(5)).is_ok()
            });
            ready_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            Self {
                release,
                holder: Some(holder),
            }
        }
        fn release(mut self) {
            let _ = self.release.send(());
            assert!(
                self.holder.take().unwrap().join().unwrap(),
                "posted transaction lock watchdog fired"
            );
        }
    }
    impl Drop for HeldWrites {
        fn drop(&mut self) {
            let _ = self.release.send(());
            if let Some(holder) = self.holder.take() {
                let _ = holder.join();
            }
        }
    }
    async fn while_locked<T>(future: impl Future<Output = Result<T>>) -> T {
        let held = HeldWrites::new();
        let mut operation = Box::pin(future);
        assert!(futures_util::poll!(&mut operation).is_pending());
        tokio::time::sleep(Duration::from_millis(1)).await;
        assert_eq!(
            tokio::spawn(async { "runtime available" }).await.unwrap(),
            "runtime available"
        );
        held.release();
        operation.await.unwrap()
    }

    #[tokio::test(flavor = "current_thread")]
    async fn posted_transactions_yield_before_waiting_for_storage() {
        let root = std::env::temp_dir().join(format!("posted-async-{}", uuid::Uuid::new_v4()));
        let store = PostedStore::new(Store::new(root.clone()).unwrap());
        let patch = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n";
        let saved =
            while_locked(store.save_async("owner", "Agent", None, Some(patch), &json!({}))).await;
        let id = text(&saved["meta"], "id");
        assert_eq!(store.list_async("owner").await.unwrap().len(), 1);
        assert!(store.meta_async("other", id).await.unwrap().is_none());
        let read = while_locked(store.read_async("owner", id)).await.unwrap();
        assert_eq!(read["patch"], patch);
        let opened = while_locked(store.open_async("owner", id)).await.unwrap();
        assert_eq!(opened["diff"]["files"][0]["path"], "a");
        assert!(while_locked(store.remove_async("owner", id)).await);
        assert!(store.list_async("owner").await.unwrap().is_empty());
        std::fs::remove_dir_all(root).unwrap();
    }
}
