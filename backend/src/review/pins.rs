use super::*;
use crate::rt::{Handle, JoinHandle};
use tokio::sync::oneshot;

/// A task's residency lease does not count as a user view. Dropping it only
/// signals the tracked cleanup task; the pin stays registered until eviction runs.
pub(crate) struct DiffPin {
    release: oneshot::Sender<()>,
    finished: oneshot::Receiver<()>,
}
impl DiffPin {
    pub(crate) async fn release(self) {
        drop(self.release);
        let _ = self.finished.await;
    }
}
fn track(state: &crate::AppState, spawn: impl FnOnce() -> JoinHandle<()>) {
    let mut handles = state.jobs.diff_pin_tasks.lock().unwrap();
    handles.retain(|h| !h.is_finished());
    handles.push(spawn());
}
/// @cc [owner:OctavianTocan,label:architecture] rust-diff-pin-transactions-yield
/// Async acquisition and release MUST keep the complete cache-lock transaction,
/// including storage-backed eviction, off Tokio workers. Cancellation MUST retain
/// balanced residency leases, and shutdown MUST drain their cleanup (CE round 8,
/// 16 Oct 2026; rust-background-does-not-mark-viewed).
pub(crate) async fn pin_diff(state: &Arc<crate::AppState>, key: &str) -> Result<DiffPin> {
    let stored_state = state.clone();
    let key = key.to_owned();
    let (send, result) = oneshot::channel();
    {
        let mut handles = state.jobs.diff_pin_tasks.lock().unwrap();
        if state
            .jobs
            .stopping
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            bail!("Server is shutting down");
        }
        handles.retain(|h| !h.is_finished());
        handles.push(crate::rt::spawn_blocking(move || {
            // If the waiter was cancelled, sending drops the returned lease and
            // signals its cleanup. The tracked acquisition finishes afterwards.
            let _ = send.send(pin_diff_blocking(&stored_state, &key));
        }));
    }
    result.await.context("Diff pin acquisition stopped")
}
/// Only blocking admission code may call this synchronous transaction.
pub(crate) fn pin_diff_blocking(state: &Arc<crate::AppState>, key: &str) -> DiffPin {
    let runtime = Handle::current();
    let key = key.replace(['/', '\\'], ":");
    *state
        .diff_cache
        .lock()
        .unwrap()
        .pins
        .entry(key.clone())
        .or_default() += 1;
    let (release, released) = oneshot::channel();
    let (done, finished) = oneshot::channel();
    let stored_state = state.clone();
    track(state, || {
        runtime.spawn(async move {
            let _ = released.await;
            let _ = crate::rt::spawn_blocking(move || {
                let mut cache = stored_state.diff_cache.lock().unwrap();
                if let Some(count) = cache.pins.get_mut(&key) {
                    *count -= 1;
                    if *count == 0 {
                        cache.pins.remove(&key);
                    }
                }
                let _ = enforce_diff_budget_locked(&stored_state, &mut cache);
            })
            .await;
            let _ = done.send(());
        })
    });
    DiffPin { release, finished }
}
/// Acquisition tasks register their lease cleanup before completing, so draining
/// repeatedly also covers cancelled callers whose blocking acquisition is pending.
pub(crate) async fn shutdown_diff_pins(state: &crate::AppState) {
    loop {
        let pending = std::mem::take(&mut *state.jobs.diff_pin_tasks.lock().unwrap());
        if pending.is_empty() {
            break;
        }
        for task in pending {
            let _ = task.await;
        }
    }
}
