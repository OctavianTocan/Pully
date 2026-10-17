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
            _ = tokio::time::sleep(Duration::from_millis(10)) => {},
        }
        assert!(tokio::spawn(async { true }).await.unwrap());
        let _ = self.release.send(());
        assert!(
            self.holder.take().unwrap().join().unwrap(),
            "storage lock watchdog fired"
        );
        tokio::time::timeout(Duration::from_secs(5), future)
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
