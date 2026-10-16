use super::*;
#[test]
fn legacy_analysis_identity_is_captured_before_advancing_the_diff() {
    let analysis = json!({"overallSummary":"Prior analysis","groups":[]});
    let diff = json!({"title":"New","files":[],"head":{"sha":"b"}});
    for old_head in [json!("a"), Value::Null] {
        let previous = json!({"headSha":old_head,"analyzedBy":{"llm":analysis},"lastViewedAt":17});
        let next = next_diff("test", &diff, Some(previous), &HashSet::new(), false);
        assert_eq!(next["headSha"], "b");
        assert_eq!(next["llmAnalyzedHeadSha"], old_head);
        assert_eq!(next["analyzedBy"]["llm"], analysis);
        assert_eq!(next["lastViewedAt"], 17);
        assert_eq!(llm_analyzed_head(&next), old_head.as_str());
        let again = next_diff("test", &diff, Some(next), &HashSet::new(), false);
        assert_eq!(again["llmAnalyzedHeadSha"], old_head);
    }
}
#[test]
fn imported_grouping_retains_its_original_head_even_when_stale_or_unknown() {
    let mut entry = json!({"headSha":"current","analyzedBy":{},"lastViewedAt":0,"llmSession":{"messages":[],"archive":[{"id":"keep"}]}});
    let history = entry["llmSession"].clone();
    for imported_head in [json!("stale"), Value::Null, json!("current")] {
        let result = json!({"overallSummary":"Shared grouping","groups":[],"sharedBy":"colleague"});
        set_llm_analysis(&mut entry, result.clone(), imported_head.clone());
        assert_eq!(entry["analyzedBy"]["llm"], result);
        assert_eq!(entry["llmAnalyzedHeadSha"], imported_head);
        assert_eq!(llm_analyzed_head(&entry), imported_head.as_str());
        assert_eq!(entry["headSha"], "current");
        assert_eq!(entry["lastViewedAt"], 0);
        assert_eq!(entry["llmSession"], history);
    }
}
#[test]
fn eviction_removes_every_canonical_full_file_revision() {
    for use_reference in [false, true] {
        let root = std::env::temp_dir().join(format!("pulls-evict-scope-{}", uuid::Uuid::new_v4()));
        let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
        let scope = "github:owner/repo#1";
        let mut old = json!({"key":scope,"lastViewedAt":0,"sizeBytes":DIFF_BYTES});
        if use_reference {
            old.as_object_mut().unwrap().remove("key");
            old["diff"] =
                json!({"ref":{"kind":"github-pr","owner":"owner","repo":"repo","number":"1"}});
        }
        state
            .host
            .storage
            .set(&format!("diff:{scope}"), &old)
            .unwrap();
        state
            .host
            .storage
            .set("diff:keep", &json!({"lastViewedAt":1,"sizeBytes":1}))
            .unwrap();
        for (name, cached_scope) in [
            ("old-base", scope),
            ("old-head", scope),
            ("keep", "github:owner/repo#2"),
        ] {
            state
                .host
                .storage
                .set(&format!("file-content:{name}"), &json!({"content":"body"}))
                .unwrap();
            state
                .host
                .storage
                .set(
                    &format!("file-content-meta:{name}"),
                    &json!({"scope":cached_scope,"sizeBytes":4,"lastAccessedAt":1}),
                )
                .unwrap();
        }
        assert!(finish_warming(&state, scope).unwrap().is_some());
        assert!(state
            .host
            .storage
            .get(&format!("diff:{scope}"))
            .unwrap()
            .is_none());
        assert_eq!(
            state.host.storage.keys("file-content:").unwrap(),
            ["file-content:keep"]
        );
        assert_eq!(
            state.host.storage.keys("file-content-meta:").unwrap(),
            ["file-content-meta:keep"]
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
}
#[test]
fn repeated_capacity_checks_reuse_metadata_after_direct_changes() {
    let root =
        std::env::temp_dir().join(format!("pulls-capacity-metadata-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state
        .host
        .storage
        .set("diff:one", &json!({"lastViewedAt":1,"sizeBytes":10}))
        .unwrap();
    assert_eq!(
        diff_capacity(&state).unwrap(),
        DiffCapacity {
            slots: 49,
            bytes: DIFF_BYTES - 10
        }
    );
    let reads = state.host.storage.diff_body_reads();
    for _ in 0..10 {
        assert!(finish_warming(&state, "one").unwrap().is_none());
        assert_eq!(diff_capacity(&state).unwrap().bytes, DIFF_BYTES - 10);
    }
    assert_eq!(state.host.storage.diff_body_reads(), reads);
    state
        .host
        .storage
        .set("diff:two", &json!({"lastViewedAt":2,"sizeBytes":20}))
        .unwrap();
    assert_eq!(
        diff_capacity(&state).unwrap(),
        DiffCapacity {
            slots: 48,
            bytes: DIFF_BYTES - 30
        }
    );
    assert_eq!(state.host.storage.diff_body_reads(), reads);
    state.host.storage.remove("diff:one").unwrap();
    assert_eq!(
        diff_capacity(&state).unwrap(),
        DiffCapacity {
            slots: 49,
            bytes: DIFF_BYTES - 20
        }
    );
    assert_eq!(state.host.storage.diff_body_reads(), reads);
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[tokio::test]
async fn unseen_analysis_pins_preserve_fifty_viewed_diffs_until_last_release() {
    let root = std::env::temp_dir().join(format!("pulls-pin-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    for i in 0..50 {
        state
            .host
            .storage
            .set(
                &format!("diff:viewed-{i}"),
                &json!({"lastViewedAt":i+1,"sizeBytes":100}),
            )
            .unwrap();
    }
    let key = "github:owner/repo#51";
    let diff = json!({"ref":{"kind":"github-pr","owner":"owner","repo":"repo","number":"51"},"title":"Unseen","files":[],"head":{"sha":"h"}});
    let fetch_pin = pin_diff(&state, key).await.unwrap();
    let saved = put_diff_if_unchanged(&state, key, &diff, None, false).unwrap();
    assert_eq!(saved["lastViewedAt"], 0);
    let job_pin = pin_diff(&state, key).await.unwrap();
    fetch_pin.release().await;
    finish_warming(&state, key).unwrap();
    assert_eq!(state.host.storage.keys("diff:").unwrap().len(), 51);
    assert_eq!(
        state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .unwrap()["headSha"],
        "h"
    );
    for i in 0..50 {
        assert_eq!(
            state
                .host
                .storage
                .get(&format!("diff:viewed-{i}"))
                .unwrap()
                .unwrap()["lastViewedAt"],
            i + 1
        );
    }
    job_pin.release().await;
    assert_eq!(state.host.storage.keys("diff:").unwrap().len(), 50);
    assert!(state
        .host
        .storage
        .get(&format!("diff:{key}"))
        .unwrap()
        .is_none());
    assert!(state.diff_cache.lock().unwrap().pins.is_empty());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[tokio::test]
async fn warming_respects_byte_capacity_and_pins_temporarily_exempt_it() {
    let root = std::env::temp_dir().join(format!("pulls-byte-budget-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state
        .host
        .storage
        .set(
            "diff:viewed",
            &json!({"lastViewedAt":1,"sizeBytes":DIFF_BYTES-1}),
        )
        .unwrap();
    let diff =
        json!({"ref":{"kind":"paste","hash":"new"},"title":"New","files":[],"head":{"sha":"h"}});
    assert!(matches!(
        put_warmed_diff_if_unchanged(&state, "paste:new", &diff, None).unwrap(),
        WarmAdmission::Rejected(_)
    ));
    let pin = pin_diff(&state, "paste:new").await.unwrap();
    put_diff_if_unchanged(&state, "paste:new", &diff, None, false).unwrap();
    assert_eq!(state.host.storage.keys("diff:").unwrap().len(), 2);
    pin.release().await;
    assert!(state.host.storage.get("diff:paste:new").unwrap().is_none());
    assert!(state.host.storage.get("diff:viewed").unwrap().is_some());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn stale_base_fetch_cannot_replace_newer_diff() {
    let root = std::env::temp_dir().join(format!("pulls-base-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let old = json!({"key":"test","headSha":"same","diff":{"ref":{"kind":"paste","hash":"x"},"title":"old","files":[],"base":{"sha":"b1"},"head":{"sha":"same"}}});
    let mut newer = old.clone();
    newer["diff"]["base"]["sha"] = json!("b2");
    newer["llmSession"] = json!({"messages":[]});
    state.host.storage.set("diff:test", &newer).unwrap();
    let got = put_diff_if_unchanged(&state, "test", &old["diff"], Some(&old), false).unwrap();
    assert_eq!(got["diff"]["base"]["sha"], "b2");
    assert_eq!(got["llmSession"], newer["llmSession"]);
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn history_preserves_full_messages_and_analysis() {
    let long = "a".repeat(50000);
    let session = json!({"messages":[{"role":"assistant","content":"analysis"},{"role":"user","content":"Why?","timestamp":0},{"role":"assistant","content":long}],"chatStartIndex":1});
    let archived = archive_session(session);
    assert_eq!(archived["messages"].as_array().unwrap().len(), 1);
    assert_eq!(
        archived["archive"][0]["messages"][1]["content"]
            .as_str()
            .unwrap()
            .len(),
        50000
    );
    assert_eq!(history(&archived)["chats"][0]["title"], "Why?");
    assert_eq!(iso(0), "1970-01-01T00:00:00.000Z");
}
#[test]
fn restore_removes_chosen_before_capacity_limit() {
    let root = std::env::temp_dir().join(format!("pulls-chat-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let archive:Vec<_>=(0..30).map(|i|json!({"id":format!("chat-{i}"),"archivedAt":i,"messages":[{"role":"user","content":format!("Question {i}")}]})).collect();
    state.host.storage.set("diff:test",&json!({"llmSession":{"messages":[{"role":"assistant","content":"Analysis"},{"role":"user","content":"Current"}],"chatStartIndex":1,"archive":archive}})).unwrap();
    change_chat_saved(&state, "test", Some("chat-29")).unwrap();
    let e = state.host.storage.get("diff:test").unwrap().unwrap();
    assert_eq!(e["llmSession"]["archive"].as_array().unwrap().len(), 30);
    assert!(array(&e["llmSession"]["archive"])
        .iter()
        .any(|v| v["id"] == "chat-0"));
    assert_eq!(e["llmSession"]["messages"][0]["content"], "Analysis");
    assert_eq!(e["llmSession"]["messages"][1]["content"], "Question 29");
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn stale_fetch_preserves_new_head_and_saved_chat() {
    let root = std::env::temp_dir().join(format!("pulls-cas-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let first = json!({"ref":{"kind":"paste","hash":"test"},"title":"First","files":[],"head":{"sha":"one","ref":"main"}});
    let observed = put_diff(&state, "paste:test", &first).unwrap();
    let mut newer = first.clone();
    newer["head"]["sha"] = json!("two");
    put_diff(&state, "paste:test", &newer).unwrap();
    state
        .host
        .storage
        .update("diff:paste:test", |old| {
            let mut e = old.unwrap();
            e["llmSession"] =
                json!({"messages":[{"role":"user","content":"Keep me"}],"chatStartIndex":0});
            Ok(e)
        })
        .unwrap();
    let result =
        put_diff_if_unchanged(&state, "paste:test", &first, Some(&observed), true).unwrap();
    assert_eq!(result["headSha"], "two");
    assert_eq!(result["llmSession"]["messages"][0]["content"], "Keep me");
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn patch_reads_reject_stale_heads_and_match_wire_shape() {
    let root = std::env::temp_dir().join(format!("pulls-patches-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state.host.storage.set("diff:test",&json!({"diff":{"head":{"sha":"new"},"files":[{"path":"x.rs","sha":"blob","hunks":[{"patch":"+new"}]}]}})).unwrap();
    assert!(diff_patches(
        &state,
        &json!({"key":"test","paths":["x.rs"],"headSha":"old"})
    )
    .is_err());
    let result = diff_patches(
        &state,
        &json!({"key":"test","paths":["x.rs","missing"],"headSha":"new"}),
    )
    .unwrap();
    assert_eq!(
        result,
        json!({"files":{"x.rs":{"sha":"blob","hunks":[{"patch":"+new"}]}}})
    );
    assert_eq!(
        diff_patches(&state, &json!({"key":"test","paths":["x.rs"]})).unwrap(),
        result
    );
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}

struct HeldDiffCache {
    release: Option<std::sync::mpsc::Sender<()>>,
    holder: Option<std::thread::JoinHandle<bool>>,
}
impl HeldDiffCache {
    async fn new(state: &Arc<crate::AppState>) -> Self {
        let state = state.clone();
        let (ready, started) = tokio::sync::oneshot::channel();
        let (release, released) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let _cache = state.diff_cache.lock().unwrap();
            let _ = ready.send(());
            // A regression must fail instead of hanging the sole runtime worker.
            released
                .recv_timeout(std::time::Duration::from_secs(2))
                .is_ok()
        });
        tokio::time::timeout(std::time::Duration::from_secs(1), started)
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
        self.holder.take().is_none_or(|h| h.join().unwrap())
    }
    async fn assert_responsive<T>(mut self, work: impl std::future::Future<Output = T>) -> T {
        tokio::pin!(work);
        let waiting = futures_util::poll!(&mut work).is_pending();
        assert_eq!(tokio::spawn(async { 42 }).await.unwrap(), 42);
        let on_time = self.release();
        assert!(waiting, "pin acquisition must wait for the held cache lock");
        assert!(on_time, "pin transaction blocked the async runtime");
        tokio::time::timeout(std::time::Duration::from_secs(1), work)
            .await
            .unwrap()
    }
}
impl Drop for HeldDiffCache {
    fn drop(&mut self) {
        self.release();
    }
}
#[tokio::test(flavor = "current_thread")]
async fn pin_acquisition_and_drop_yield_while_the_cache_lock_is_held() {
    let root = std::env::temp_dir().join(format!("pulls-pin-yield-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let pin = HeldDiffCache::new(&state)
        .await
        .assert_responsive(pin_diff(&state, "unseen"))
        .await
        .unwrap();
    state
        .host
        .storage
        .set(
            "diff:unseen",
            &json!({"lastViewedAt":0,"sizeBytes":DIFF_BYTES+1}),
        )
        .unwrap();
    let mut held = HeldDiffCache::new(&state).await;
    drop(pin);
    assert_eq!(tokio::spawn(async { 42 }).await.unwrap(), 42);
    assert!(held.release(), "dropping a pin must not block the runtime");
    shutdown_diff_pins(&state).await;
    assert!(state.diff_cache.lock().unwrap().pins.is_empty());
    assert!(state.host.storage.get("diff:unseen").unwrap().is_none());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[tokio::test(flavor = "current_thread")]
async fn cancelled_pin_acquisition_is_balanced_and_shutdown_drains_cleanup() {
    let root = std::env::temp_dir().join(format!("pulls-pin-cancel-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let mut held = HeldDiffCache::new(&state).await;
    let acquiring = state.clone();
    let task = tokio::spawn(async move { pin_diff(&acquiring, "github:owner/repo#1").await });
    tokio::task::yield_now().await;
    assert!(!state.jobs.diff_pin_tasks.lock().unwrap().is_empty());
    task.abort();
    assert!(matches!(task.await, Err(error) if error.is_cancelled()));
    assert!(held.release());
    shutdown_diff_pins(&state).await;
    assert!(state.diff_cache.lock().unwrap().pins.is_empty());
    assert!(state.jobs.diff_pin_tasks.lock().unwrap().is_empty());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[tokio::test(flavor = "current_thread")]
async fn ai_admission_yields_and_releases_pins_when_the_request_is_cancelled() {
    let root = std::env::temp_dir().join(format!("pulls-admit-yield-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state
        .host
        .storage
        .set("diff:test", &json!({"headSha":"a"}))
        .unwrap();
    let request = json!({"key":"test","kind":"analyze","model":{"engine":"invalid"}});
    let result = HeldDiffCache::new(&state)
        .await
        .assert_responsive(crate::ai::handle(&state, "ai-task-start", request.clone()))
        .await
        .unwrap();
    assert!(result
        .unwrap_err()
        .to_string()
        .contains("Unknown AI engine"));
    shutdown_diff_pins(&state).await;
    let mut held = HeldDiffCache::new(&state).await;
    let admitting = state.clone();
    let task =
        tokio::spawn(async move { crate::ai::handle(&admitting, "ai-task-start", request).await });
    tokio::task::yield_now().await;
    task.abort();
    assert!(matches!(task.await, Err(error) if error.is_cancelled()));
    assert!(held.release());
    crate::ai::shutdown(&state).await;
    assert!(state.diff_cache.lock().unwrap().pins.is_empty());
    assert!(pin_diff(&state, "after-shutdown").await.is_err());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[tokio::test(flavor = "current_thread")]
async fn queued_analysis_admission_preserves_its_reserved_head() {
    let root = std::env::temp_dir().join(format!("pulls-admit-head-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state
        .host
        .storage
        .set(
            "diff:test",
            &json!({"headSha":"a","diff":{"head":{"sha":"a"}}}),
        )
        .unwrap();
    let mut held = HeldDiffCache::new(&state).await;
    let id = {
        let admission = crate::ai::handle(
            &state,
            "ai-task-start",
            json!({"key":"test","kind":"analyze","expectedHeadSha":"a","model":{"engine":"pi"}}),
        );
        tokio::pin!(admission);
        assert!(futures_util::poll!(&mut admission).is_pending());
        state
            .host
            .storage
            .set(
                "diff:test",
                &json!({"headSha":"b","diff":{"head":{"sha":"b"}}}),
            )
            .unwrap();
        assert!(held.release());
        admission.await.unwrap().unwrap()
    };
    let settled = tokio::time::timeout(std::time::Duration::from_secs(1), async {
        let mut revision = 0;
        loop {
            let run = crate::ai::handle(&state, "ai-task-wait", json!({"id":id,"after":revision}))
                .await
                .unwrap()
                .unwrap();
            if run["status"] != "running" {
                break run;
            }
            revision = run["rev"].as_u64().unwrap();
        }
    })
    .await
    .unwrap();
    assert_eq!(settled["status"], "failed");
    assert!(settled["error"]
        .as_str()
        .unwrap()
        .contains("changed before analysis started"));
    crate::ai::shutdown(&state).await;
    assert!(state.diff_cache.lock().unwrap().pins.is_empty());
    assert!(pin_diff(&state, "after-shutdown").await.is_err());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn metadata_reads_only_scalar_file_fields_and_preserves_empty_fallback() {
    let entry = json!({"key":"test","headSha":"head","lastViewedAt":7,"sizeBytes":19,"diff":{"ref":{"kind":"local"},"title":"Title","files":[{"sha":"a","additions":3,"deletions":1,"hunks":[{"patch":"large".repeat(1024)}]},{"sha":"b","additions":2,"deletions":0}]}});
    let before = entry.clone();
    assert_eq!(
        metadata(&entry),
        json!({"key":"test","ref":{"kind":"local"},"headSha":"head","title":"Title","additions":5,"deletions":1,"fileShas":["a","b"],"lastViewedAt":7,"sizeBytes":19})
    );
    assert_eq!(entry, before);
    for files in [Value::Null, json!({}), json!(false)] {
        let mut no_files = entry.clone();
        no_files["diff"]["files"] = files;
        let result = metadata(&no_files);
        assert_eq!(result["additions"], 0);
        assert_eq!(result["deletions"], 0);
        assert_eq!(result["fileShas"], json!([]));
    }
}

#[test]
fn borrowed_diff_validation_keeps_exact_first_errors() {
    fn reference(diff: &Value) -> Result<()> {
        if !diff["ref"].is_object() || !diff["title"].is_string() || !diff["files"].is_array() {
            bail!("Invalid diff payload.");
        }
        crate::source::serialize_ref(&diff["ref"])?;
        for f in array(&diff["files"]) {
            if !f["path"].is_string()
                || !f["sha"].is_string()
                || !f["hunks"].is_array()
                || !f["additions"].is_number()
                || !f["deletions"].is_number()
                || !f["isBinary"].is_boolean()
                || !["added", "removed", "modified", "renamed", "copied"]
                    .contains(&text(&f["status"]))
            {
                bail!("Invalid diff file.");
            }
            for h in array(&f["hunks"]) {
                if !h["header"].is_string()
                    || !h["patch"].is_string()
                    || !["oldStart", "oldLines", "newStart", "newLines"]
                        .iter()
                        .all(|k| h[*k].is_number())
                {
                    bail!("Invalid diff hunk.");
                }
            }
        }
        Ok(())
    }
    let file = json!({"path":"a","sha":"h","status":"modified","additions":1,"deletions":0,"isBinary":false,"hunks":[{"header":"@@","patch":"x".repeat(4096),"oldStart":1,"oldLines":1,"newStart":1,"newLines":1}]});
    let valid = json!({"ref":{"kind":"paste","hash":"h"},"title":"Title","files":[file,file]});
    let mut cases = vec![
        valid.clone(),
        Value::Null,
        json!({}),
        json!({"ref":{"kind":"unknown"},"title":"t","files":[]}),
    ];
    for path in [
        "/ref",
        "/title",
        "/files",
        "/files/0/path",
        "/files/0/sha",
        "/files/0/status",
        "/files/0/additions",
        "/files/0/deletions",
        "/files/0/isBinary",
        "/files/0/hunks",
        "/files/0/hunks/0/header",
        "/files/0/hunks/0/patch",
        "/files/0/hunks/0/oldStart",
        "/files/0/hunks/0/newLines",
    ] {
        let mut bad = valid.clone();
        *bad.pointer_mut(path).unwrap() = Value::Null;
        cases.push(bad);
    }
    let mut mixed = valid.clone();
    mixed["files"][0]["hunks"][0]["patch"] = json!(false);
    mixed["files"][1]["path"] = json!(false);
    assert_eq!(
        valid_diff(&mixed).unwrap_err().to_string(),
        "Invalid diff hunk."
    );
    cases.push(mixed);
    for diff in cases {
        let original = diff.clone();
        assert_eq!(
            valid_diff(&diff).map_err(|e| e.to_string()),
            reference(&diff).map_err(|e| e.to_string())
        );
        assert_eq!(diff, original);
    }
}

#[test]
fn borrowed_change_tracking_keeps_order_duplicates_and_complete_record() {
    fn reference(
        key: &str,
        diff: &Value,
        current: Option<Value>,
        reviewed: &HashSet<String>,
        viewed: bool,
    ) -> Value {
        let mut next = current.unwrap_or_else(|| json!({"analyzedBy":{},"lastViewedAt":0}));
        // Materialize legacy identity before replacing the cached head. Even an
        // unknown old head must stay unknown rather than inheriting the new head.
        if !next["analyzedBy"]["llm"].is_null() && next.get("llmAnalyzedHeadSha").is_none() {
            next["llmAnalyzedHeadSha"] = next["headSha"].clone();
        }
        let old = array(&next["diff"]["files"]);
        let flagged = array(&next["changedSinceReviewed"]);
        let changed: Vec<_> = array(&diff["files"])
            .into_iter()
            .filter(|f| {
                flagged.contains(&f["path"])
                    || old.iter().any(|p| {
                        p["path"] == f["path"]
                            && p["sha"] != f["sha"]
                            && reviewed.contains(text(&p["sha"]))
                    })
            })
            .map(|f| f["path"].clone())
            .collect();
        next["key"] = json!(key);
        next["headSha"] = json!(text(&diff["head"]["sha"]));
        next["diff"] = diff.clone();
        next["changedSinceReviewed"] = json!(changed);
        if viewed {
            next["lastViewedAt"] = json!(now());
        }
        next["sizeBytes"] = json!(serde_json::to_vec(&next).map(|b| b.len()).unwrap_or(0));
        next
    }
    let file =
        |path: &str, sha: &str| json!({"path":path,"sha":sha,"hunks":[{"patch":"x".repeat(4096)}]});
    let diff = json!({"title":"new","head":{"sha":"new-head"},"files":[file("flagged","a"),file("changed","new"),file("changed","new"),file("unreviewed","new"),file("same","old")]});
    let old = json!([
        file("changed", "old"),
        file("unreviewed", "not-reviewed"),
        file("same", "old"),
        file("removed", "old")
    ]);
    let reviewed = HashSet::from(["old".to_owned()]);
    for files in [old, Value::Null, json!({})] {
        for flags in [json!(["flagged"]), Value::Null, json!({})] {
            let prior = json!({"diff":{"files":files},"headSha":"old-head","analyzedBy":{"llm":{"overallSummary":"saved"}},"changedSinceReviewed":flags,"lastViewedAt":17});
            let original = diff.clone();
            let result = next_diff("key", &diff, Some(prior.clone()), &reviewed, false);
            let expected = reference("key", &diff, Some(prior), &reviewed, false);
            assert_eq!(result.to_string(), expected.to_string());
            assert_eq!(diff, original);
            assert_eq!(result["lastViewedAt"], 17);
            if files.is_array() && flags.is_array() {
                assert_eq!(
                    result["changedSinceReviewed"],
                    json!(["flagged", "changed", "changed"])
                );
            }
        }
    }
}

#[tokio::test]
async fn recent_and_repository_lists_hydrate_only_selected_bodies() {
    let root = std::env::temp_dir().join(format!("pulls-list-metadata-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    for i in 0..30 {
        state.host.storage.set(&format!("diff:posted:p{i}"), &json!({"key":format!("posted:p{i}"),"lastViewedAt":i+1,"diff":{"ref":{"kind":"posted","id":format!("p{i}")},"files":[]}})).unwrap();
    }
    state.host.storage.set("diff:github:o/r#1", &json!({"key":"github:o/r#1","lastViewedAt":31,"diff":{"ref":{"kind":"github-pr","owner":"o","repo":"r","number":"1"},"files":[]}})).unwrap();
    state.host.storage.diff_snapshot(None).unwrap();
    let reads = state.host.storage.diff_body_reads();
    let recent = handle(&state, "recent-diffs", json!({}))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(recent.as_array().unwrap().len(), 12);
    assert_eq!(state.host.storage.diff_body_reads() - reads, 12);
    let reads = state.host.storage.diff_body_reads();
    let repo = handle(&state, "repo-viewed", json!({"owner":"o","repo":"r"}))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(repo.as_array().unwrap().len(), 1);
    assert_eq!(state.host.storage.diff_body_reads() - reads, 1);
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn foreground_review_transactions_yield_under_storage_contention() {
    let root = std::env::temp_dir().join(format!("review-contention-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let key = "paste:test";
    let diff = json!({"ref":{"kind":"paste","hash":"test"},"title":"Fixture","files":[{"path":"a","sha":"file-sha","status":"modified","additions":1,"deletions":1,"isBinary":false,"hunks":[]}],"head":{"sha":"head"}});
    put_diff(&state, key, &diff).unwrap();
    for (name, args) in [
        (
            "reviewed-set",
            json!({"key":key,"shas":["file-sha"],"reviewed":true}),
        ),
        ("reviewed-get", json!({"key":key})),
        ("review-state", json!({"key":key})),
        ("diff-patches", json!({"key":key,"paths":["a"]})),
        ("cache-diff", json!({"key":key,"method":"get"})),
        ("recent-diffs", json!({})),
    ] {
        crate::store::test_support::HeldLock::storage(&state.host.storage)
            .assert_yields(handle(&state, name, args))
            .await
            .unwrap()
            .unwrap();
    }
    assert_eq!(
        state.host.storage.get("review:file-sha").unwrap().unwrap()["sha"],
        "file-sha"
    );
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn browser_cache_writes_compare_complete_operation_revisions() {
    let root = std::env::temp_dir().join(format!("cache-revisions-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let key = "paste:revision";
    let original = json!({"ref":{"kind":"paste","hash":"revision"},"title":"Old title","files":[],"base":{"sha":"base-1","ref":"main"},"head":{"sha":"head","ref":"branch"}});
    for change in ["base", "metadata"] {
        let observed = cache_diff(&state, &json!({"method":"get","key":key})).unwrap();
        cache_diff(
            &state,
            &json!({"method":"putDiff","key":key,"values":[original,"head",observed["revision"]]}),
        )
        .unwrap();
        let observed = cache_diff(&state, &json!({"method":"getMeta","key":key})).unwrap();
        assert_eq!(
            observed["revision"],
            cache_diff(&state, &json!({"method":"get","key":key})).unwrap()["revision"]
        );
        let mut newer = original.clone();
        if change == "base" {
            newer["base"]["sha"] = json!("base-2");
        } else {
            newer["title"] = json!("New title");
            newer["description"] = json!("Updated metadata");
        }
        // Simulate the native warmer publishing a same-head replacement while
        // this browser fetch is pending, followed by unrelated reads.
        put_diff(&state, key, &newer).unwrap();
        let winner = state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .unwrap();
        let latest = cache_diff(&state, &json!({"method":"get","key":key})).unwrap();
        assert_ne!(latest["revision"], observed["revision"]);
        cache_diff(&state, &json!({"method":"getMeta","key":key})).unwrap();
        let returned = cache_diff(
            &state,
            &json!({"method":"putDiff","key":key,"values":[original,"head",observed["revision"]]}),
        )
        .unwrap();
        assert_eq!(returned, with_revision(winner.clone()));
        assert_eq!(
            state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap(),
            winner
        );
        state.host.storage.remove(&format!("diff:{key}")).unwrap();
        assert!(cache_diff(
            &state,
            &json!({"method":"putDiff","key":key,"values":[original,"head",observed["revision"]]})
        )
        .is_err());
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn browser_session_growth_updates_metadata_and_enforces_small_budgets() {
    let root = std::env::temp_dir().join(format!("cache-growth-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state.diff_cache.lock().unwrap().limits = Some(DiffCapacity {
        slots: 50,
        bytes: 1500,
    });
    let diff = json!({"ref":{"kind":"paste","hash":"growth"},"title":"Tiny","files":[]});
    let key = "paste:growth";
    put_diff(&state, key, &diff).unwrap();
    cache_diff(&state, &json!({"method":"setLlmSession","key":key,"values":[{"messages":[{"role":"user","content":"x".repeat(2000)}],"chatStartIndex":0},"null"]})).unwrap();
    assert!(state
        .host
        .storage
        .get(&format!("diff:{key}"))
        .unwrap()
        .is_none());

    put_diff(&state, key, &diff).unwrap();
    cache_diff(&state, &json!({"method":"setLlmSession","key":key,"values":[{"messages":[{"role":"user","content":"x".repeat(700)}],"chatStartIndex":0},"null"]})).unwrap();
    let grown = state
        .host
        .storage
        .get(&format!("diff:{key}"))
        .unwrap()
        .unwrap();
    assert_eq!(
        grown["sizeBytes"].as_u64().unwrap(),
        serde_json::to_vec(&grown).unwrap().len() as u64
    );
    assert_eq!(
        diff_capacity(&state).unwrap().bytes,
        1500 - grown["sizeBytes"].as_u64().unwrap()
    );
    state
        .host
        .storage
        .update(&format!("diff:{key}"), |old| {
            let mut old = old.unwrap();
            old["lastViewedAt"] = json!(1);
            Ok(old)
        })
        .unwrap();
    let other = json!({"ref":{"kind":"paste","hash":"next"},"title":"y".repeat(700),"files":[]});
    put_diff(&state, "paste:next", &other).unwrap();
    assert!(state
        .host
        .storage
        .get(&format!("diff:{key}"))
        .unwrap()
        .is_none());
    assert!(state.host.storage.get("diff:paste:next").unwrap().is_some());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn browser_cache_admission_and_saved_reads_reject_posted_aliases() {
    let root = std::env::temp_dir().join(format!("cache-provenance-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    let private = json!({"ref":{"kind":"posted","id":"victim"},"title":"Private","files":[]});
    assert!(put_diff(&state, "paste:alias", &private).is_err());
    // Internal non-posted fixtures may still intentionally use arbitrary keys.
    assert!(put_diff(
        &state,
        "fixture-key",
        &json!({"ref":{"kind":"paste","hash":"safe"},"title":"Safe","files":[]})
    )
    .is_ok());
    for key in ["paste:alias", "posted:victim"] {
        assert!(cache_diff(
            &state,
            &json!({"method":"putDiff","key":key,"values":[private,"",null]})
        )
        .is_err());
    }
    assert!(cache_diff(&state, &json!({"method":"putDiff","key":"paste:alias","values":[{"ref":{"kind":"paste","hash":"another"},"title":"Mismatch","files":[]},"",null]})).is_err());
    state
        .host
        .storage
        .set(
            "diff:paste:alias",
            &json!({"key":"paste:alias","diff":private}),
        )
        .unwrap();
    for method in ["get", "getMeta", "touch"] {
        assert!(cache_diff(&state, &json!({"method":method,"key":"paste:alias"})).is_err());
    }
    assert!(review_state(&state, &json!({"key":"paste:alias"})).is_err());
    assert!(entry(&state, "paste:alias").is_err());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn browser_growth_keeps_posted_entries_and_active_pins() {
    let root = std::env::temp_dir().join(format!("cache-growth-pins-{}", uuid::Uuid::new_v4()));
    let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
    state.diff_cache.lock().unwrap().limits = Some(DiffCapacity {
        slots: 50,
        bytes: 1000,
    });
    let key = "paste:pinned";
    let pin = pin_diff(&state, key).await.unwrap();
    for (key, reference) in [
        (key, json!({"kind":"paste","hash":"pinned"})),
        ("posted:owned", json!({"kind":"posted","id":"owned"})),
    ] {
        put_diff(
            &state,
            key,
            &json!({"ref":reference,"title":"Tiny","files":[]}),
        )
        .unwrap();
        cache_diff(&state, &json!({"method":"setLlmSession","key":key,"values":[{"messages":[{"role":"user","content":"x".repeat(1500)}],"chatStartIndex":0},"null"]})).unwrap();
        let saved = state
            .host
            .storage
            .get(&format!("diff:{key}"))
            .unwrap()
            .unwrap();
        assert!(saved["sizeBytes"].as_u64().unwrap() > 1000);
    }
    pin.release().await;
    assert!(state
        .host
        .storage
        .get("diff:paste:pinned")
        .unwrap()
        .is_none());
    assert!(state
        .host
        .storage
        .get("diff:posted:owned")
        .unwrap()
        .is_some());
    std::fs::remove_dir_all(root).unwrap();
}
