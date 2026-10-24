//! Opt-in preanalysis and bounded, non-viewing inbox cache warming.
use crate::{ai, now_ms, review, source, text, AppState};
use anyhow::{Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    future::Future,
    sync::Arc,
    time::Duration,
};

fn reference(pull: &Value) -> Value {
    json!({"kind":"github-pr","owner":pull["owner"],"repo":pull["repo"],"number":pull["number"].as_str().map(str::to_owned).unwrap_or_else(||pull["number"].to_string())})
}
fn needs_analysis(pull: &Value, entry: Option<&Value>) -> bool {
    pull["state"] == "open"
        && pull["requestedFromMe"]
            .as_bool()
            .unwrap_or(pull["role"] == "review-requested")
        && !text(pull, "headSha").is_empty()
        && pull["changedFiles"].as_u64().unwrap_or(0) <= 300
        && entry.is_none_or(|e| review::llm_analyzed_head(e) != pull["headSha"].as_str())
}
async fn pulls(state: &Arc<AppState>) -> Result<Vec<Value>> {
    let value = source::handle(state, "my-pulls", json!({"state":"open"}))
        .await
        .context("Inbox method unavailable")??;
    Ok(value
        .as_array()
        .or_else(|| value["pulls"].as_array())
        .cloned()
        .unwrap_or_default())
}
#[derive(Clone)]
struct RejectedSnapshot {
    version: Value,
    capacity: review::DiffCapacity,
}
enum WarmedDiff {
    Cached(bool),
    Rejected(RejectedSnapshot),
}
fn version(pull: &Value) -> Value {
    json!([pull["headSha"], pull["updatedAt"]])
}
// The injected source keeps the admission path testable without GitHub calls.
async fn warm_diff<F, Fut>(
    state: &Arc<AppState>,
    pull: &Value,
    rejected: Option<&RejectedSnapshot>,
    fetch: F,
) -> Result<WarmedDiff>
where
    F: FnOnce(Option<Value>) -> Fut,
    Fut: Future<Output = Result<Option<Value>>>,
{
    let r = reference(pull);
    let key = source::serialize_ref(&r)?;
    let stored_state = state.clone();
    let stored_key = format!("diff:{key}");
    let before =
        crate::rt::spawn_blocking(move || stored_state.host.storage.get(&stored_key)).await??;
    let version = version(pull);
    if before.is_none() {
        let stored_state = state.clone();
        // Initial indexing can read existing snapshots once; keep that work off
        // Tokio workers. Later checks use only the mutation-tracked metadata.
        let capacity =
            crate::rt::spawn_blocking(move || review::diff_capacity(&stored_state)).await??;
        if !capacity.available()
            || rejected.is_some_and(|r| r.version == version && r.capacity == capacity)
        {
            return Ok(WarmedDiff::Rejected(RejectedSnapshot { version, capacity }));
        }
    }
    let mut updated = false;
    if let Some(diff) = fetch(before.clone()).await? {
        let stored_state = state.clone();
        let (admission, wrote_diff) = crate::rt::spawn_blocking(move || {
            let admission = review::put_warmed_diff_if_unchanged(&stored_state, &key, &diff, before.as_ref())?;
            let wrote_diff = matches!(&admission, review::WarmAdmission::Stored(stored) if stored["diff"] == diff);
            Ok::<_, anyhow::Error>((admission, wrote_diff))
        }).await??;
        match admission {
            review::WarmAdmission::Stored(_) => updated = wrote_diff,
            review::WarmAdmission::Rejected(capacity) => {
                return Ok(WarmedDiff::Rejected(RejectedSnapshot { version, capacity }));
            }
        };
    }
    Ok(WarmedDiff::Cached(updated))
}
/// @cc [owner:OctavianTocan,label:architecture] rust-overview-storage-yields
/// Overview warming MUST keep storage reads and complete guarded diff updates,
/// including lock acquisition, off Tokio workers (16 Oct 2026 CE review, round 7).
async fn warm_overview<F, Fut>(
    state: &Arc<AppState>,
    pull: &Value,
    key: &str,
    fetch: F,
) -> Result<bool>
where
    F: FnOnce() -> Fut,
    Fut: Future<Output = Result<Value>>,
{
    let overview_key = format!(
        "pr-overview:{}/{}#{}",
        text(pull, "owner"),
        text(pull, "repo"),
        text(&reference(pull), "number")
    );
    let stored_state = state.clone();
    let overview =
        crate::rt::spawn_blocking(move || stored_state.host.storage.get(&overview_key)).await??;
    let stale = overview.as_ref().is_none_or(|o| {
        o["updatedAt"] != pull["updatedAt"]
            || now_ms().saturating_sub(o["at"].as_u64().unwrap_or(0)) >= 30 * 60 * 1000
    });
    if !stale {
        return Ok(false);
    }
    let overview = fetch().await?["overview"].clone();
    let stored_state = state.clone();
    let stored_key = format!("diff:{key}");
    crate::rt::spawn_blocking(move || {
        stored_state.host.storage.update(&stored_key, |old| {
            let mut e = old.context("Cached diff was evicted")?;
            if e.is_object() && e["headSha"] == overview["head"]["oid"] {
                e["diff"]["title"] = overview["title"].clone();
                e["diff"]["description"] = overview["body"].clone();
                e["diff"]["pullRequest"]["state"] = overview["state"].clone();
                e["diff"]["updatedAt"] = overview["updatedAt"].clone();
                e["sizeBytes"] = json!(serde_json::to_vec(&e)?.len());
            }
            Ok(e)
        })
    })
    .await??;
    Ok(true)
}
async fn cache_pull(
    state: &Arc<AppState>,
    pull: &Value,
    rejected: Option<&RejectedSnapshot>,
) -> Result<(bool, bool, Option<RejectedSnapshot>)> {
    let r = reference(pull);
    let key = source::serialize_ref(&r)?;
    // Probe both base and head: base pushes can change the diff without a head push.
    let fetch_ref = &r;
    let warmed = warm_diff(state, pull, rejected, |before| async move {
        if let Some(old) = before {
            source::refresh_cached_pr(state, &old["diff"]).await
        } else {
            Ok(Some(source::fetch(state, fetch_ref).await?))
        }
    })
    .await?;
    let updated = match warmed {
        WarmedDiff::Cached(updated) => updated,
        WarmedDiff::Rejected(rejected) => return Ok((false, false, Some(rejected))),
    };
    let stale = warm_overview(state, pull, &key, || async {
        review::handle(
            state,
            "pr-overview",
            json!({"owner":pull["owner"],"repo":pull["repo"],"number":pull["number"]}),
        )
        .await
        .context("Overview unavailable")?
    })
    .await?;
    // A changed diff was already budgeted during admission. Only overview growth
    // needs another pass; unchanged warm pulls must not rescan retained snapshots.
    let rejected = if stale {
        let stored_state = state.clone();
        crate::rt::spawn_blocking(move || review::finish_warming(&stored_state, &key))
            .await??
            .map(|capacity| RejectedSnapshot {
                version: version(pull),
                capacity,
            })
    } else {
        None
    };
    Ok((updated, stale, rejected))
}
pub async fn warm(state: Arc<AppState>) {
    let mut rejected = HashMap::new();
    crate::rt::sleep(Duration::from_secs(5)).await;
    let mut interval = crate::rt::interval(Duration::from_secs(180));
    interval.set_missed_tick_behavior(crate::rt::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        if !matches!(source::github_token(&state).await, Ok(Some(_))) {
            continue;
        }
        let started = now_ms();
        let Ok(mut list) = pulls(&state).await else {
            continue;
        };
        list.retain(|p| p["state"] == "open" && !text(p, "headSha").is_empty());
        list.sort_by(|a, b| text(b, "updatedAt").cmp(text(a, "updatedAt")));
        list.truncate(150);
        let keys: HashSet<_> = list
            .iter()
            .filter_map(|p| source::serialize_ref(&reference(p)).ok())
            .collect();
        rejected.retain(|key, _| keys.contains(key));
        let (mut diffs, mut overviews) = (0, 0);
        for batch in list.chunks(2) {
            let results = futures_util::future::join_all(batch.iter().map(|p| {
                let key = source::serialize_ref(&reference(p)).unwrap_or_default();
                cache_pull(&state, p, rejected.get(&key))
            }))
            .await;
            for (pull, result) in batch.iter().zip(results) {
                if let Ok((d, o, rejection)) = result {
                    diffs += usize::from(d);
                    overviews += usize::from(o);
                    let key = source::serialize_ref(&reference(pull)).unwrap_or_default();
                    if let Some(rejection) = rejection {
                        rejected.insert(key, rejection);
                    } else {
                        rejected.remove(&key);
                    }
                }
            }
        }
        let _=state.analytics.record("store.refresh",json!({"pulls":list.len(),"diffs":diffs,"overviews":overviews,"ms":now_ms()-started})).await;
    }
}
fn store_preanalysis_diff(
    state: &Arc<AppState>,
    key: &str,
    expected_head: &Value,
    diff: &Value,
    before: Option<&Value>,
) -> Result<()> {
    anyhow::ensure!(
        &diff["head"]["sha"] == expected_head,
        "Head changed during preanalysis fetch"
    );
    let entry = review::put_diff_if_unchanged(state, key, diff, before, false)?;
    anyhow::ensure!(
        &entry["headSha"] == expected_head,
        "Head changed during preanalysis fetch"
    );
    Ok(())
}
/// @cc [owner:OctavianTocan,label:architecture] rust-preanalysis-eligibility-storage-yields
/// Initial preanalysis eligibility reads MUST move the complete storage operation,
/// including lock acquisition, off async workers while preserving cache-miss behavior
/// (Tavi, 16 Oct 2026, fix each CE review finding; round 10 performance finding).
async fn preanalysis_cached_entry(state: &Arc<AppState>, key: &str) -> Option<Value> {
    state
        .host
        .storage
        .get_async(&format!("diff:{key}"))
        .await
        .ok()
        .flatten()
}
async fn preanalyze_head(
    state: &Arc<AppState>,
    engine: &str,
    model: &Option<String>,
    pull: Value,
    reference: Value,
    key: String,
) -> Result<()> {
    let _pin = review::pin_diff(state, &key).await?;
    let stored_state = state.clone();
    let stored_key = format!("diff:{key}");
    let before =
        crate::rt::spawn_blocking(move || stored_state.host.storage.get(&stored_key)).await??;
    let expected_head = &pull["headSha"];
    if before
        .as_ref()
        .is_none_or(|e| e["headSha"] != pull["headSha"])
    {
        let diff = source::fetch(state, &reference).await?;
        let stored_state = state.clone();
        let stored_key = key.clone();
        let captured_head = expected_head.clone();
        crate::rt::spawn_blocking(move || {
            store_preanalysis_diff(
                &stored_state,
                &stored_key,
                &captured_head,
                &diff,
                before.as_ref(),
            )
        })
        .await??;
    }
    let mut selected = json!({"engine":engine});
    if let Some(model) = model {
        selected["model"] = json!(model);
    }
    let id = ai::handle(
        state,
        "ai-task-start",
        json!({"key":key,"kind":"analyze","model":selected,"locale":"en","expectedHeadSha":expected_head}),
    ).await.context("AI unavailable")??;
    let mut rev = 0;
    loop {
        let run = ai::handle(state, "ai-task-wait", json!({"id":id,"after":rev}))
            .await
            .context("AI unavailable")??;
        if run["status"] != "running" {
            anyhow::ensure!(run["status"] == "done", "Preanalysis did not complete");
            return Ok(());
        }
        rev = run["rev"].as_u64().unwrap_or(rev);
    }
}
// The worker retains attempted across rounds. Inject the listed heads and the
// fetch/AI attempt so failure retention can be checked without providers or timers.
async fn preanalysis_round<F, Fut>(
    state: &Arc<AppState>,
    attempted: &mut HashSet<String>,
    list: Vec<Value>,
    mut attempt: F,
) where
    F: FnMut(Value, Value, String) -> Fut,
    Fut: Future<Output = Result<()>>,
{
    for pull in list {
        let r = reference(&pull);
        let Ok(key) = source::serialize_ref(&r) else {
            continue;
        };
        let before = preanalysis_cached_entry(state, &key).await;
        if !needs_analysis(&pull, before.as_ref()) {
            continue;
        }
        let id = format!("{key}@{}", text(&pull, "headSha"));
        if !attempted.insert(id) {
            continue;
        }
        let started = now_ms();
        let result = attempt(pull, r, key).await;
        let _ = state
            .analytics
            .record(
                "preanalyze.run",
                json!({"status":if result.is_ok(){"done"}else{"failed"},"ms":now_ms()-started}),
            )
            .await;
    }
}
pub async fn preanalyze(state: Arc<AppState>, engine: String, model: Option<String>) {
    let mut attempted = HashSet::new();
    let mut interval = crate::rt::interval(Duration::from_secs(600));
    interval.set_missed_tick_behavior(crate::rt::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        let Ok(list) = pulls(&state).await else {
            continue;
        };
        preanalysis_round(&state, &mut attempted, list, |pull, r, key| {
            preanalyze_head(&state, &engine, &model, pull, r, key)
        })
        .await;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn pull(number: usize) -> Value {
        json!({"owner":"owner","repo":"repo","number":number,"headSha":"a","updatedAt":"now","state":"open"})
    }
    fn diff(pull: &Value) -> Value {
        json!({"ref":reference(pull),"title":"PR","files":[],"head":{"sha":pull["headSha"],"ref":"feature"}})
    }
    fn seed_viewed(state: &AppState, count: usize) {
        for i in 0..count {
            let p = pull(i);
            let key = source::serialize_ref(&reference(&p)).unwrap();
            state.host.storage.set(&format!("diff:{key}"), &json!({"key":key,"headSha":"a","diff":diff(&p),"lastViewedAt":i+1,"sizeBytes":500})).unwrap();
        }
    }
    struct HeldStore {
        release: Option<std::sync::mpsc::Sender<()>>,
        holder: Option<std::thread::JoinHandle<bool>>,
    }
    impl HeldStore {
        async fn new(state: &Arc<AppState>) -> Self {
            let state = state.clone();
            let (ready, started) = tokio::sync::oneshot::channel();
            let (release, released) = std::sync::mpsc::channel();
            let holder = std::thread::spawn(move || {
                let mut on_time = false;
                state
                    .host
                    .storage
                    .update("overview-test-lock", |old| {
                        let _ = ready.send(());
                        // A synchronous regression must fail instead of hanging
                        // the sole Tokio worker and its timeout indefinitely.
                        on_time = released.recv_timeout(Duration::from_secs(2)).is_ok();
                        Ok(old.unwrap_or(json!({})))
                    })
                    .unwrap();
                on_time
            });
            crate::rt::timeout(Duration::from_secs(1), started)
                .await
                .unwrap()
                .unwrap();
            Self {
                release: Some(release),
                holder: Some(holder),
            }
        }
        fn release(&mut self) -> bool {
            if let Some(release) = self.release.take() {
                let _ = release.send(());
            }
            self.holder
                .take()
                .is_none_or(|holder| holder.join().unwrap())
        }
        async fn assert_responsive<T>(mut self, work: impl Future<Output = T>) -> T {
            tokio::pin!(work);
            let waiting = futures_util::poll!(&mut work).is_pending();
            assert_eq!(crate::rt::spawn(async { 42 }).await.unwrap(), 42);
            let on_time = self.release();
            assert!(waiting, "overview transaction must wait for the held lock");
            assert!(on_time, "overview storage blocked the async runtime");
            crate::rt::timeout(Duration::from_secs(1), work)
                .await
                .expect("released overview transaction should finish")
        }
    }
    impl Drop for HeldStore {
        fn drop(&mut self) {
            self.release();
        }
    }
    fn overview(pull: &Value) -> Value {
        json!({"overview":{"head":{"oid":pull["headSha"]},"title":"Refreshed title","body":"Refreshed body","state":"OPEN","updatedAt":"later"}})
    }
    #[tokio::test(flavor = "current_thread")]
    async fn failed_preanalysis_head_is_attempted_once_until_a_new_push() {
        let root =
            std::env::temp_dir().join(format!("pulls-preanalysis-round-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        let mut requested = pull(1);
        requested["requestedFromMe"] = json!(true);
        let key = source::serialize_ref(&reference(&requested)).unwrap();
        let mut attempted = HashSet::new();
        let mut calls = Vec::new();
        for head in ["a", "a", "b", "b"] {
            requested["headSha"] = json!(head);
            preanalysis_round(
                &state,
                &mut attempted,
                vec![requested.clone(), pull(2)],
                |pull, r, actual_key| {
                    assert_eq!(actual_key, key);
                    assert_eq!(r, reference(&pull));
                    calls.push(text(&pull, "headSha").to_owned());
                    std::future::ready(Err(anyhow::anyhow!("offline AI failure")))
                },
            )
            .await;
            assert_eq!(calls.len(), if head == "a" { 1 } else { 2 });
        }
        assert_eq!(calls, ["a", "b"]);
        assert_eq!(attempted.len(), 2);
        assert!(state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .is_none());
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test(flavor = "current_thread")]
    async fn preanalysis_eligibility_read_yields_and_preserves_cache_semantics() {
        let root =
            std::env::temp_dir().join(format!("pulls-eligibility-io-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        let mut p = pull(1);
        p["requestedFromMe"] = json!(true);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let entry = json!({"headSha":"a","llmAnalyzedHeadSha":"a","analyzedBy":{"llm":{"groups":[]}},"diff":diff(&p)});
        state
            .host
            .storage
            .set(&format!("diff:{key}"), &entry)
            .unwrap();
        let cached = HeldStore::new(&state)
            .await
            .assert_responsive(preanalysis_cached_entry(&state, &key))
            .await;
        assert_eq!(cached, Some(entry.clone()));
        assert!(!needs_analysis(&p, cached.as_ref()));
        p["headSha"] = json!("b");
        assert!(needs_analysis(&p, cached.as_ref()));
        assert!(preanalysis_cached_entry(&state, "missing").await.is_none());
        assert!(state.host.storage.get("diff:").is_err());
        assert!(preanalysis_cached_entry(&state, "").await.is_none());
        assert_eq!(
            state.host.storage.get(&format!("diff:{key}")).unwrap(),
            Some(entry)
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test(flavor = "current_thread")]
    async fn overview_storage_transactions_yield_while_waiting_for_the_lock() {
        let root = std::env::temp_dir().join(format!("pulls-overview-io-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 1);
        let p = pull(0);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let value = overview(&p);
        let held = HeldStore::new(&state).await;
        assert!(held
            .assert_responsive(warm_overview(&state, &p, &key, || {
                std::future::ready(Ok(value.clone()))
            }))
            .await
            .unwrap());

        let before = state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .unwrap();
        {
            let (fetched, fetch_started) = tokio::sync::oneshot::channel();
            let (provide, response) = tokio::sync::oneshot::channel();
            let work = warm_overview(&state, &p, &key, || async {
                fetched.send(()).unwrap();
                Ok(response.await.unwrap())
            });
            tokio::pin!(work);
            // Acquire the lock only after the staleness read has finished, so the
            // second check exercises the guarded write rather than another read.
            tokio::select! {
                result = &mut work => panic!("overview must await its fetch: {result:?}"),
                started = crate::rt::timeout(Duration::from_secs(1), fetch_started) => {
                    started.unwrap().unwrap();
                }
            }
            let held = HeldStore::new(&state).await;
            provide.send(value.clone()).unwrap();
            assert!(held.assert_responsive(work).await.unwrap());
        }
        let mut expected = before;
        expected["diff"]["title"] = value["overview"]["title"].clone();
        expected["diff"]["description"] = value["overview"]["body"].clone();
        expected["diff"]["pullRequest"]["state"] = value["overview"]["state"].clone();
        expected["diff"]["updatedAt"] = value["overview"]["updatedAt"].clone();
        expected["sizeBytes"] = json!(serde_json::to_vec(&expected).unwrap().len());
        assert_eq!(
            state.host.storage.get(&format!("diff:{key}")).unwrap(),
            Some(expected)
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn overview_refresh_preserves_fresh_entries_new_heads_and_errors() {
        let root =
            std::env::temp_dir().join(format!("pulls-overview-guards-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 1);
        let p = pull(0);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let diff_key = format!("diff:{key}");
        let overview_key = "pr-overview:owner/repo#0";
        let before = state.host.storage.get(&diff_key).unwrap().unwrap();
        state
            .host
            .storage
            .set(overview_key, &json!({"at":now_ms(),"updatedAt":"now"}))
            .unwrap();
        assert!(!warm_overview(&state, &p, &key, || async {
            anyhow::bail!("A fresh overview must not be fetched")
        })
        .await
        .unwrap());
        assert_eq!(
            state.host.storage.get(&diff_key).unwrap(),
            Some(before.clone())
        );

        state
            .host
            .storage
            .set(overview_key, &json!({"at":now_ms(),"updatedAt":"old"}))
            .unwrap();
        let mut newer = before;
        newer["headSha"] = json!("b");
        newer["diff"]["head"]["sha"] = json!("b");
        assert!(warm_overview(&state, &p, &key, || async {
            state.host.storage.set(&diff_key, &newer).unwrap();
            Ok(overview(&p))
        })
        .await
        .unwrap());
        assert_eq!(state.host.storage.get(&diff_key).unwrap(), Some(newer));

        state
            .host
            .storage
            .set(overview_key, &json!({"at":0,"updatedAt":"now"}))
            .unwrap();
        state.host.storage.remove(&diff_key).unwrap();
        let evicted = warm_overview(&state, &p, &key, || async { Ok(overview(&p)) })
            .await
            .unwrap_err();
        assert_eq!(evicted.to_string(), "Cached diff was evicted");
        let failed = warm_overview(&state, &p, &key, || async {
            anyhow::bail!("Overview fetch failed")
        })
        .await
        .unwrap_err();
        assert_eq!(failed.to_string(), "Overview fetch failed");
        assert!(state.host.storage.get(&diff_key).unwrap().is_none());
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn unchanged_warming_reads_only_each_pulls_own_snapshot() {
        let root =
            std::env::temp_dir().join(format!("pulls-warm-metadata-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 50);
        review::diff_capacity(&state).unwrap();
        let reads = state.host.storage.diff_body_reads();
        let mut rejected = HashMap::new();
        for _ in 0..2 {
            for i in 0..55 {
                let p = pull(i);
                let result = warm_diff(&state, &p, rejected.get(&i), |old| {
                    assert!(old.is_some(), "Full capacity must prevent cold downloads");
                    std::future::ready(Ok(None))
                })
                .await
                .unwrap();
                match result {
                    WarmedDiff::Cached(updated) => {
                        assert!(!updated);
                        // Even an explicit repeated budget pass needs no bodies.
                        let key = source::serialize_ref(&reference(&p)).unwrap();
                        assert!(review::finish_warming(&state, &key).unwrap().is_none());
                    }
                    WarmedDiff::Rejected(r) => {
                        rejected.insert(i, r);
                    }
                }
            }
        }
        assert_eq!(state.host.storage.diff_body_reads() - reads, 100);
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn two_rounds_refresh_full_viewed_cache_without_cold_downloads() {
        let root = std::env::temp_dir().join(format!("pulls-warm-full-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 50);
        let refreshes = std::cell::Cell::new(0);
        let downloads = std::cell::Cell::new(0);
        let mut rejected = HashMap::new();
        for _ in 0..2 {
            for i in 0..55 {
                let p = pull(i);
                let result = warm_diff(&state, &p, rejected.get(&i), |old| {
                    if old.is_some() {
                        refreshes.set(refreshes.get() + 1);
                    } else {
                        downloads.set(downloads.get() + 1);
                    }
                    std::future::ready(Ok(Some(diff(&p))))
                })
                .await
                .unwrap();
                if let WarmedDiff::Rejected(r) = result {
                    rejected.insert(i, r);
                }
            }
        }
        assert_eq!(refreshes.get(), 100);
        assert_eq!(downloads.get(), 0);
        assert_eq!(state.host.storage.keys("diff:").unwrap().len(), 50);
        for i in 0..50 {
            let key = source::serialize_ref(&reference(&pull(i))).unwrap();
            assert_eq!(
                state
                    .host
                    .storage
                    .get(&format!("diff:{key}"))
                    .unwrap()
                    .unwrap()["lastViewedAt"],
                i + 1
            );
        }
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn concurrent_last_slot_admission_does_not_redownload_the_loser() {
        let root = std::env::temp_dir().join(format!("pulls-warm-race-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 49);
        let barrier = tokio::sync::Barrier::new(2);
        let first = pull(50);
        let second = pull(51);
        let run = |p: Value| {
            let state = &state;
            let barrier = &barrier;
            async move {
                warm_diff(state, &p, None, |_| async {
                    barrier.wait().await;
                    Ok(Some(diff(&p)))
                })
                .await
                .unwrap()
            }
        };
        let (a, b) = tokio::join!(run(first.clone()), run(second.clone()));
        let (loser, rejected) = match (a, b) {
            (WarmedDiff::Cached(true), WarmedDiff::Rejected(r)) => (second, r),
            (WarmedDiff::Rejected(r), WarmedDiff::Cached(true)) => (first, r),
            _ => panic!("Exactly one cold snapshot should fit the last slot"),
        };
        let result = warm_diff(&state, &loser, Some(&rejected), |_| async {
            panic!("An unchanged capacity-rejected snapshot must not be downloaded twice");
            #[allow(unreachable_code)]
            Ok(None)
        })
        .await
        .unwrap();
        assert!(matches!(result, WarmedDiff::Rejected(_)));
        assert_eq!(state.host.storage.keys("diff:").unwrap().len(), 50);
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn overview_eviction_records_settled_capacity_without_repeat_downloads() {
        let root =
            std::env::temp_dir().join(format!("pulls-warm-overview-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 1);
        let p = pull(1);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let first = warm_diff(&state, &p, None, |_| std::future::ready(Ok(Some(diff(&p)))))
            .await
            .unwrap();
        assert!(matches!(first, WarmedDiff::Cached(true)));
        // Model an overview growing the entry over the byte budget without
        // allocating a large response in this bounded fixture.
        state
            .host
            .storage
            .update(&format!("diff:{key}"), |entry| {
                let mut entry = entry.unwrap();
                entry["sizeBytes"] = json!(50 * 1024 * 1024 - 100);
                Ok(entry)
            })
            .unwrap();
        let capacity = review::finish_warming(&state, &key).unwrap().unwrap();
        assert_eq!(capacity, review::diff_capacity(&state).unwrap());
        assert!(state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .is_none());
        let rejected = RejectedSnapshot {
            version: version(&p),
            capacity,
        };
        let second = warm_diff(&state, &p, Some(&rejected), |_| async {
            panic!("Unchanged overview overflow must not redownload next round");
            #[allow(unreachable_code)]
            Ok(None)
        })
        .await
        .unwrap();
        assert!(matches!(second, WarmedDiff::Rejected(_)));
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn admission_rejection_keeps_capacity_from_before_pin_release() {
        let root =
            std::env::temp_dir().join(format!("pulls-warm-release-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        seed_viewed(&state, 1);
        let pin = review::pin_diff(&state, "oversized").await.unwrap();
        state
            .host
            .storage
            .set(
                "diff:oversized",
                &json!({"lastViewedAt":0,"sizeBytes":50*1024*1024}),
            )
            .unwrap();
        let p = pull(1);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let admission =
            review::put_warmed_diff_if_unchanged(&state, &key, &diff(&p), None).unwrap();
        // Reproduce the interleaving between admission and recording its marker.
        pin.release().await;
        let review::WarmAdmission::Rejected(capacity) = admission else {
            panic!("The pinned snapshot must exhaust byte capacity");
        };
        assert!(!capacity.available());
        assert_ne!(capacity, review::diff_capacity(&state).unwrap());
        let rejected = RejectedSnapshot {
            version: version(&p),
            capacity,
        };
        let result = warm_diff(&state, &p, Some(&rejected), |_| {
            std::future::ready(Ok(Some(diff(&p))))
        })
        .await
        .unwrap();
        assert!(matches!(result, WarmedDiff::Cached(true)));
        assert_eq!(
            state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap()["lastViewedAt"],
            0
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn byte_rejection_is_retried_only_when_version_or_capacity_changes() {
        let root = std::env::temp_dir().join(format!("pulls-warm-bytes-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        state
            .host
            .storage
            .set(
                "diff:large",
                &json!({"lastViewedAt":1,"sizeBytes":50*1024*1024-100}),
            )
            .unwrap();
        let mut p = pull(1);
        let calls = std::cell::Cell::new(0);
        let mut rejected = None;
        for _ in 0..2 {
            let result = warm_diff(&state, &p, rejected.as_ref(), |_| {
                calls.set(calls.get() + 1);
                std::future::ready(Ok(Some(diff(&p))))
            })
            .await
            .unwrap();
            if let WarmedDiff::Rejected(r) = result {
                rejected = Some(r);
            }
        }
        assert_eq!(calls.get(), 1);
        p["headSha"] = json!("b");
        let result = warm_diff(&state, &p, rejected.as_ref(), |_| {
            calls.set(calls.get() + 1);
            std::future::ready(Ok(Some(diff(&p))))
        })
        .await
        .unwrap();
        assert_eq!(calls.get(), 2);
        if let WarmedDiff::Rejected(r) = result {
            rejected = Some(r);
        }
        state.host.storage.remove("diff:large").unwrap();
        let result = warm_diff(&state, &p, rejected.as_ref(), |_| {
            calls.set(calls.get() + 1);
            std::future::ready(Ok(Some(diff(&p))))
        })
        .await
        .unwrap();
        assert!(matches!(result, WarmedDiff::Cached(true)));
        assert_eq!(calls.get(), 3);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        assert_eq!(
            state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap()["lastViewedAt"],
            0
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn reserved_head_rejects_a_push_during_fetch_without_publishing_it() {
        let root =
            std::env::temp_dir().join(format!("pulls-preanalysis-head-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        let p = pull(1);
        let key = source::serialize_ref(&reference(&p)).unwrap();
        let mut fetched = diff(&p);
        fetched["head"]["sha"] = json!("b");
        let pin = review::pin_diff(&state, &key).await.unwrap();
        assert!(store_preanalysis_diff(&state, &key, &p["headSha"], &fetched, None).is_err());
        assert!(state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .is_none());
        store_preanalysis_diff(&state, &key, &json!("b"), &fetched, None).unwrap();
        assert_eq!(
            state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap()["headSha"],
            "b"
        );
        pin.release().await;
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn only_unanalyzed_requested_heads() {
        let mut p =
            json!({"state":"open","role":"review-requested","headSha":"h","changedFiles":300});
        assert!(needs_analysis(&p, None));
        assert!(!needs_analysis(
            &p,
            Some(&json!({"headSha":"h","analyzedBy":{"llm":{}}}))
        ));
        assert!(needs_analysis(
            &p,
            Some(&json!({"headSha":"h","llmAnalyzedHeadSha":"old","analyzedBy":{"llm":{}}}))
        ));
        assert!(needs_analysis(
            &p,
            Some(&json!({"headSha":"h","llmAnalyzedHeadSha":null,"analyzedBy":{"llm":{}}}))
        ));
        assert!(needs_analysis(
            &p,
            Some(&json!({"headSha":"h","llmAnalyzedHeadSha":"h","analyzedBy":{}}))
        ));
        p["requestedFromMe"] = json!(false);
        assert!(!needs_analysis(&p, None));
        p["requestedFromMe"] = json!(true);
        p["changedFiles"] = json!(301);
        assert!(!needs_analysis(&p, None));
    }
    #[tokio::test]
    async fn warming_new_heads_keeps_analysis_eligible_until_grouping_is_saved() {
        for legacy in [false, true] {
            let root = std::env::temp_dir()
                .join(format!("pulls-warm-analysis-head-{}", uuid::Uuid::new_v4()));
            let state = AppState::new(root.clone(), root.clone()).unwrap();
            let mut p = pull(1);
            p["requestedFromMe"] = json!(true);
            let key = source::serialize_ref(&reference(&p)).unwrap();
            let seeded =
                review::put_diff_if_unchanged(&state, &key, &diff(&p), None, false).unwrap();
            crate::rpc_validation::cache_entry(&seeded)
                .expect("the warming fixture must be a canonical cache entry");
            let analysis = json!({"overallSummary":"Analysis of A","groups":[],"source":"llm","schemaVersion":1,"generatedAt":"2026-10-16T00:00:00.000Z"});
            let save_args =
                json!({"key":key,"method":"setAnalyzedResult","values":["llm",analysis]});
            review::handle(&state, "cache-diff", save_args.clone())
                .await
                .unwrap()
                .unwrap();
            assert!(!needs_analysis(
                &p,
                state
                    .host
                    .storage
                    .get(&format!("diff:{key}"))
                    .unwrap()
                    .as_ref()
            ));
            let history = json!({"messages":[{"role":"assistant","content":"Analysis of A"}],"chatStartIndex":1,"archive":[{"id":"prior","archivedAt":1,"messages":[{"role":"user","content":"Keep this history"}]}]});
            crate::rpc_validation::cache_session(&history)
                .expect("the retained history fixture must be a canonical session");
            state
                .host
                .storage
                .update(&format!("diff:{key}"), |old| {
                    let mut old = old.unwrap();
                    old["llmSession"] = history.clone();
                    if legacy {
                        old.as_object_mut().unwrap().remove("llmAnalyzedHeadSha");
                    }
                    Ok(old)
                })
                .unwrap();
            p["headSha"] = json!("b");
            let warmed = warm_diff(&state, &p, None, |_| std::future::ready(Ok(Some(diff(&p)))))
                .await
                .unwrap();
            assert!(matches!(warmed, WarmedDiff::Cached(true)));
            let entry = state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap();
            assert_eq!(entry["headSha"], "b");
            assert_eq!(entry["llmAnalyzedHeadSha"], "a");
            assert_eq!(entry["analyzedBy"]["llm"], analysis);
            assert_eq!(entry["llmSession"], history);
            assert_eq!(entry["lastViewedAt"], 0);
            assert!(needs_analysis(&p, Some(&entry)));
            // The web shared-analysis path sends A's result without A's head.
            let mut imported = save_args.clone();
            imported["values"][1]["sharedBy"] = json!("colleague");
            review::handle(&state, "cache-diff", imported.clone())
                .await
                .unwrap()
                .unwrap();
            let entry = state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap();
            assert_eq!(entry["analyzedBy"]["llm"], imported["values"][1]);
            assert!(entry["llmAnalyzedHeadSha"].is_null());
            assert_eq!(entry["headSha"], "b");
            assert!(needs_analysis(&p, Some(&entry)));
            let mut invalid = save_args.clone();
            invalid["values"][1] = json!({"groups":null});
            assert!(review::handle(&state, "cache-diff", invalid)
                .await
                .unwrap()
                .is_err());
            assert_eq!(
                state
                    .host
                    .storage
                    .get(&format!("diff:{key}"))
                    .unwrap()
                    .unwrap(),
                entry
            );
            let mut saved = save_args;
            saved["values"][1]["overallSummary"] = json!("Analysis of B");
            review::handle(&state, "cache-diff", saved)
                .await
                .unwrap()
                .unwrap();
            let entry = state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap();
            assert_eq!(entry["llmAnalyzedHeadSha"], "b");
            assert_eq!(entry["llmSession"], history);
            assert_eq!(entry["lastViewedAt"], 0);
            assert!(!needs_analysis(&p, Some(&entry)));
            drop(state);
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}
