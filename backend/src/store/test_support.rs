//! Bounded contention checks: the watchdog breaks regressions that block Tokio.
use super::Store;
use std::{future::Future, sync::mpsc, time::Duration};

pub(crate) struct HeldLock {
    release: mpsc::Sender<()>,
    holder: Option<std::thread::JoinHandle<bool>>,
}
impl HeldLock {
    pub(crate) fn new(
        hold: impl FnOnce(mpsc::Sender<()>, mpsc::Receiver<()>) -> bool + Send + 'static,
    ) -> Self {
        let (ready_tx, ready_rx) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let holder = std::thread::spawn(move || hold(ready_tx, released));
        ready_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        Self {
            release,
            holder: Some(holder),
        }
    }
    pub(crate) fn storage(store: &Store) -> Self {
        let store = store.clone();
        Self::new(move |ready, release| {
            let _guard = store.lock.mutex.lock().unwrap();
            ready.send(()).unwrap();
            release.recv_timeout(Duration::from_secs(5)).is_ok()
        })
    }
    pub(crate) async fn assert_yields<T>(mut self, future: impl Future<Output = T>) -> T {
        let mut future = Box::pin(future);
        assert!(
            futures_util::poll!(&mut future).is_pending(),
            "operation did not yield while storage was locked"
        );
        tokio::select! {
            biased;
            _ = &mut future => panic!("operation completed before its storage lock was released"),
            _ = crate::rt::sleep(Duration::from_millis(10)) => {},
        }
        assert!(crate::rt::spawn(async { true }).await.unwrap());
        let _ = self.release.send(());
        assert!(
            self.holder.take().unwrap().join().unwrap(),
            "storage lock watchdog fired"
        );
        crate::rt::timeout(Duration::from_secs(5), future)
            .await
            .expect("storage operation did not complete")
    }
}
impl Drop for HeldLock {
    fn drop(&mut self) {
        let _ = self.release.send(());
        if let Some(holder) = self.holder.take() {
            let _ = holder.join();
        }
    }
}

/// An indexed record fixture that counts only the ids and bodies actually visited.
#[derive(Clone, Default)]
pub(crate) struct CountedRecords {
    pub(crate) records:
        std::sync::Arc<std::sync::Mutex<std::collections::BTreeMap<String, Vec<u8>>>>,
    pub(crate) visits: std::sync::Arc<std::sync::atomic::AtomicUsize>,
    pub(crate) reads: std::sync::Arc<std::sync::atomic::AtomicUsize>,
    pub(crate) full_scans: std::sync::Arc<std::sync::atomic::AtomicUsize>,
}
impl super::Records for CountedRecords {
    fn get(&self, id: &str) -> anyhow::Result<Option<Vec<u8>>> {
        self.reads.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Ok(self.records.lock().unwrap().get(id).cloned())
    }
    fn put(&self, id: &str, bytes: &[u8]) -> anyhow::Result<()> {
        self.records
            .lock()
            .unwrap()
            .insert(id.into(), bytes.to_vec());
        Ok(())
    }
    fn delete(&self, id: &str) -> anyhow::Result<bool> {
        Ok(self.records.lock().unwrap().remove(id).is_some())
    }
    fn ids(&self) -> anyhow::Result<Vec<String>> {
        self.full_scans
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        Ok(self.records.lock().unwrap().keys().cloned().collect())
    }
    fn ids_with_prefix(&self, prefix: &str) -> anyhow::Result<Vec<String>> {
        let records = self.records.lock().unwrap();
        let ids: Vec<_> = records
            .range(prefix.to_owned()..)
            .take_while(|(id, _)| id.starts_with(prefix))
            .map(|(id, _)| id.clone())
            .collect();
        self.visits
            .fetch_add(ids.len(), std::sync::atomic::Ordering::SeqCst);
        Ok(ids)
    }
}
