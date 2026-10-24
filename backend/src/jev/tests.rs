use super::*;
#[test]
fn disabled_client_and_conservative_attention() {
    assert!(!Client::new(None).enabled());
    assert_eq!(
        attention(&json!({"choice":"p2","confidence":0.49}))["priority"],
        "p0"
    );
    assert_eq!(
        attention(&json!({"choice":"unknown","confidence":1}))["priority"],
        "p0"
    );
    assert_eq!(
        attention(&json!({"choice":"p2","confidence":0.5}))["priority"],
        "p2"
    );
}
#[test]
fn same_blob_different_change_has_different_key() {
    let file = json!({"path":"a","status":"modified","sha":"h","additions":1,"deletions":0,"hunks":[{"header":"@@ -0,0 +1 @@","patch":"+hi"}]});
    assert_eq!(
        file_key(&file),
        "cdda7da05640311319c6355387f92d42d99cfa051f21123989630332cdba2ec8"
    ); // Node JSON.stringify + SHA256 fixture.
    let mut other = file.clone();
    other["hunks"][0]["patch"] = json!("+bye");
    assert_ne!(file_key(&file), file_key(&other));
    assert_eq!(file_state(&file)["patch"], "@@ -0,0 +1 @@\n+hi");
}
#[test]
fn streamed_file_patch_matches_join_then_truncate() {
    for hunks in [
        json!([]),
        json!([{}]),
        json!([{"header":"@@ one @@","patch":"+😀\n-字"},{"patch":"+two"},{}]),
        json!([{"header":"a".repeat(5998),"patch":"😀x"}]),
        json!([{"header":"a".repeat(5997),"patch":"😀x"}]),
        json!([{"patch":"a".repeat(5998)},{"header":"😀","patch":"later"}]),
    ] {
        let joined = hunks
            .as_array()
            .unwrap()
            .iter()
            .map(|h| format!("{}\n{}", s(h, "header"), s(h, "patch")))
            .collect::<Vec<_>>()
            .join("\n");
        let file = json!({"hunks":hunks,"unused":"x".repeat(20_000)});
        let original = file.clone();
        let input = file_state(&file);
        assert_eq!(input, file_state(&file.clone()));
        assert_eq!(file, original);
        assert!(input.get("unused").is_none());
        assert_eq!(input["patch"], truncate_utf16(joined.chars(), 6000));
    }
}
#[test]
fn response_validation_and_retry_are_bounded() {
    assert!(validate_answer(
        &json!({"answers":{"attention":{"type":"choice","choice":"p0","confidence":0.8}}}),
        &file_questions()
    )
    .is_ok());
    assert!(validate_answer(
        &json!({"answers":{"attention":{"type":"score","score":1}}}),
        &file_questions()
    )
    .is_err());
    assert_eq!(retry_after("90"), Some(Duration::from_secs(20)));
    assert_eq!(retry_after("-1"), Some(Duration::ZERO));
    assert_eq!(
        retry_after("Wed, 21 Oct 2015 07:28:00 GMT"),
        Some(Duration::ZERO)
    );
    assert_eq!(retry_after("NaN"), None);
}
#[tokio::test]
async fn preparation_waits_in_memory_and_caches_completed_triage() {
    let svc = Arc::new(Service::new(Arc::new(Client::new(Some(
        "synthetic-not-sent".into(),
    )))));
    let diff = json!({"ref":{"owner":"o","repo":"r"},"base":{"sha":"b"},"head":{"sha":"h"},"files":(0..3).map(|i| json!({"path":format!("{i}.rs"),"sha":"h","hunks":[{"patch":"+x".repeat(3000)}]})).collect::<Vec<_>>()});
    let keys = svc.file_keys(&diff);
    let reads = std::cell::Cell::new(0);
    let cached = json!({"priority":"p2","confidence":0.9});
    let read = |key: &str| {
        reads.set(reads.get() + 1);
        // Hydration cannot hold the ratings lock across a store read.
        assert!(svc.ratings.try_lock().is_ok());
        (key == keys[0].1).then(|| cached.clone())
    };
    svc.hydrate_files(&keys, read);
    svc.hydrate_files(&keys, read);
    assert_eq!(reads.get(), 3, "including misses, each key is read once");
    let pull_version = "o/r#1@h\ntitle";
    {
        let mut ratings = svc.ratings.lock().unwrap();
        ratings.running_files.insert(keys[1].1.clone());
        ratings.running_pulls.insert(pull_version.to_owned());
    }
    let id = diff_identity(&diff).unwrap();
    let waiting = wait_for_preparation(&svc, &keys, Some(pull_version), Some(id.clone()), 2_000);
    tokio::pin!(waiting);
    tokio::select! {
        _ = &mut waiting => panic!("shared file and pull jobs are still pending"),
        _ = crate::rt::sleep(Duration::from_millis(80)) => {}
    }
    assert_eq!(reads.get(), 3);
    assert_eq!(svc.key_computations.load(Ordering::Relaxed), 3);
    assert!(svc.ratings.lock().unwrap().by_diff.is_empty());
    let completed = json!({"priority":"p0","confidence":0.8});
    {
        let mut ratings = svc.ratings.lock().unwrap();
        ratings
            .done_files
            .insert(keys[1].1.clone(), completed.clone());
        ratings.running_files.remove(&keys[1].1);
    }
    tokio::select! {
        _ = &mut waiting => panic!("the shared pull job is still pending"),
        _ = crate::rt::sleep(Duration::from_millis(30)) => {}
    }
    svc.ratings
        .lock()
        .unwrap()
        .running_pulls
        .remove(pull_version);
    let result = crate::rt::timeout(Duration::from_secs(1), &mut waiting)
        .await
        .expect("completed shared jobs should release preparation");
    assert_eq!(result["pending"], 0);
    assert_eq!(result["files"], json!({"0.rs":cached,"1.rs":completed}));
    assert_eq!(cached_files(&svc, &id), result["files"]);
    svc.hydrate_files(&keys, read);
    assert_eq!(reads.get(), 3);
    assert_eq!(svc.key_computations.load(Ordering::Relaxed), 3);

    // A zero wait keeps shared jobs alive; refusal still hides all ratings.
    svc.ratings
        .lock()
        .unwrap()
        .running_files
        .insert(keys[2].1.clone());
    assert_eq!(
        wait_for_preparation(&svc, &keys, None, Some(id.clone()), 0).await["pending"],
        1
    );
    assert!(svc
        .ratings
        .lock()
        .unwrap()
        .running_files
        .contains(&keys[2].1));
    svc.client.refused.store(true, Ordering::Relaxed);
    assert_eq!(
        wait_for_preparation(&svc, &keys, None, Some(id), 2_000).await,
        off()
    );
}
#[test]
fn stored_classification_requires_current_title_and_head() {
    let saved = json!({"headSha":"h","title":"title","kind":"fix","kindConfidence":0.9,"risk":1});
    assert!(current(&saved, &json!({"headSha":"h","title":"title"})));
    assert!(!current(&saved, &json!({"headSha":"h2","title":"title"})));
    assert!(!current(&saved, &json!({"headSha":"h","title":"renamed"})));
}

struct Fixture {
    app: Arc<AppState>,
    svc: Arc<Service>,
    diff: Value,
    pull: Value,
}
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!("pulls-jev-lock-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root).unwrap();
        // This client is injected only into this store's service. No environment
        // credential is read and these fixtures never ask a provider a question.
        let svc = Arc::new(Service::new(Arc::new(Client::new(Some("not-sent".into())))));
        SERVICES
            .get_or_init(Default::default)
            .lock()
            .unwrap()
            .insert(app.host.storage.root().to_path_buf(), svc.clone());
        let pull =
            json!({"owner":"o","repo":"r","number":1,"state":"open","headSha":"h","title":"title"});
        let diff = json!({"ref":{"kind":"github-pr","owner":"o","repo":"r","number":"1"},"title":"title","base":{"sha":"b"},"head":{"sha":"h"},"files":[{"path":"a.rs","sha":"f","hunks":[]}]});
        app.host
            .storage
            .set("diff:test", &json!({"diff":diff}))
            .unwrap();
        let key = file_key(&diff["files"][0]);
        {
            let mut ratings = svc.ratings.lock().unwrap();
            ratings
                .done_files
                .insert(key, json!({"priority":"p1","confidence":0.8}));
            ratings.done_pulls.insert(
                pull_key(&pull),
                json!({"headSha":"h","title":"title","kind":"fix","kindConfidence":0.9,"risk":1}),
            );
        }
        Self {
            app,
            svc,
            diff,
            pull,
        }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        SERVICES
            .get()
            .unwrap()
            .lock()
            .unwrap()
            .remove(self.app.host.storage.root());
        std::fs::remove_dir_all(self.app.host.storage.root()).unwrap();
    }
}
#[derive(Clone, Copy)]
enum LockKind {
    Store,
    Hydration,
    Ratings,
}
struct HeldTransaction {
    release: Option<std::sync::mpsc::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
    expired: Arc<AtomicBool>,
}
impl HeldTransaction {
    async fn new(fixture: &Fixture, kind: LockKind) -> Self {
        let app = fixture.app.clone();
        let svc = fixture.svc.clone();
        let (ready, started) = tokio::sync::oneshot::channel();
        let (release, released) = std::sync::mpsc::channel();
        let expired = Arc::new(AtomicBool::new(false));
        let did_expire = expired.clone();
        let thread = std::thread::spawn(move || {
            let wait = || {
                let _ = ready.send(());
                // The watchdog bounds regressions that block the sole async
                // worker, which would otherwise also prevent timeout polling.
                if released.recv_timeout(Duration::from_secs(2)).is_err() {
                    did_expire.store(true, Ordering::Relaxed);
                }
            };
            match kind {
                LockKind::Store => {
                    app.host
                        .storage
                        .update("jev-test-lock", |old| {
                            wait();
                            Ok(old.unwrap_or(json!({})))
                        })
                        .unwrap();
                }
                LockKind::Hydration => {
                    let _guard = svc.hydration.lock().unwrap();
                    wait();
                }
                LockKind::Ratings => {
                    let _guard = svc.ratings.lock().unwrap();
                    wait();
                }
            }
        });
        started.await.unwrap();
        Self {
            release: Some(release),
            thread: Some(thread),
            expired,
        }
    }
    fn release(&mut self) {
        if let Some(release) = self.release.take() {
            let _ = release.send(());
        }
        if let Some(thread) = self.thread.take() {
            thread.join().unwrap();
        }
    }
    async fn assert_responsive<T>(mut self, work: impl std::future::Future<Output = T>) -> T {
        tokio::pin!(work);
        tokio::select! {
            _ = crate::rt::sleep(Duration::from_millis(30)) => {
                assert!(!self.expired.load(Ordering::Relaxed), "a TypeSafe lock blocked the async runtime");
            }
            _ = &mut work => panic!("the transaction should still be waiting on its held lock"),
        }
        self.release();
        crate::rt::timeout(Duration::from_secs(1), work)
            .await
            .expect("released transaction should finish")
    }
}
impl Drop for HeldTransaction {
    fn drop(&mut self) {
        self.release();
    }
}
#[tokio::test(flavor = "current_thread")]
async fn jev_files_rpc_yields_during_entry_loading_and_hydration() {
    let fixture = Fixture::new();
    for kind in [LockKind::Store, LockKind::Hydration] {
        let held = HeldTransaction::new(&fixture, kind).await;
        let result = held
            .assert_responsive(crate::review::handle(
                &fixture.app,
                "jev-files",
                json!({"key":"test"}),
            ))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(result["files"]["a.rs"]["priority"], "p1");
        assert_eq!(result["pull"]["kind"], "fix");
        assert_eq!(result["pending"], 0);
    }
}
#[tokio::test(flavor = "current_thread")]
async fn type_safe_readers_yield_behind_completion_ratings_lock() {
    let fixture = Fixture::new();
    let held = HeldTransaction::new(&fixture, LockKind::Ratings).await;
    let keys = fixture.svc.file_keys(&fixture.diff);
    let context =
        json!({"owner":"o","repo":"r","baseSha":"b","headSha":"h","number":1,"title":"title"});
    let pulls = vec![fixture.pull.clone()];
    let snapshot = json!({"pulls":pulls});
    let (snapshot, started, triage, prepared) = held
        .assert_responsive(async {
            tokio::join!(
                apply_pulls(&fixture.app, snapshot),
                classify_pulls(&fixture.app, &pulls),
                triage_for(&fixture.app, &context),
                wait_for_preparation(&fixture.svc, &keys, None, diff_identity(&fixture.diff), 0),
            )
        })
        .await;
    assert_eq!(snapshot.unwrap()["pulls"][0]["jev"]["kind"], "fix");
    started.unwrap(); // Cached classification prevents any provider request.
    assert_eq!(triage.unwrap().unwrap()["pull"]["kind"], "fix");
    assert_eq!(prepared["files"]["a.rs"]["priority"], "p1");
}
fn file_answer() -> Value {
    json!({"answers":{"attention":{"choice":"p2","confidence":0.9}}})
}
fn pull_answer() -> Value {
    json!({"answers":{"kind":{"choice":"fix","confidence":0.9},"risk":{"score":1}}})
}
#[tokio::test(flavor = "current_thread")]
async fn file_completion_yields_and_cleans_running_state_for_every_outcome() {
    let fixture = Fixture::new();
    let key = "completion".to_owned();
    fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .running_files
        .insert(key.clone());
    let held = HeldTransaction::new(&fixture, LockKind::Store).await;
    held.assert_responsive(complete_file(
        fixture.app.clone(),
        fixture.svc.clone(),
        key.clone(),
        Ok(Some(file_answer())),
    ))
    .await;
    assert_eq!(
        fixture
            .app
            .host
            .storage
            .get("jev-file:completion")
            .unwrap()
            .unwrap()["priority"],
        "p2"
    );
    assert!(!fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .running_files
        .contains(&key));
    for result in [Ok(None), Err(anyhow::anyhow!("synthetic failure"))] {
        fixture
            .svc
            .ratings
            .lock()
            .unwrap()
            .running_files
            .insert(key.clone());
        complete_file(
            fixture.app.clone(),
            fixture.svc.clone(),
            key.clone(),
            result,
        )
        .await;
        assert!(!fixture
            .svc
            .ratings
            .lock()
            .unwrap()
            .running_files
            .contains(&key));
    }
    assert!(fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .failed_files
        .contains_key(&key));
}
#[tokio::test(flavor = "current_thread")]
async fn pull_completion_yields_preserves_latest_version_and_cleans_every_outcome() {
    let fixture = Fixture::new();
    let key = pull_key(&fixture.pull);
    let version = version_key(&fixture.pull);
    {
        let mut ratings = fixture.svc.ratings.lock().unwrap();
        ratings.latest_pulls.insert(key.clone(), version.clone());
        ratings.running_pulls.insert(version.clone());
    }
    let held = HeldTransaction::new(&fixture, LockKind::Store).await;
    held.assert_responsive(complete_pull(
        fixture.app.clone(),
        fixture.svc.clone(),
        fixture.pull.clone(),
        key.clone(),
        version.clone(),
        Ok(Some(pull_answer())),
    ))
    .await;
    let saved = fixture
        .app
        .host
        .storage
        .get(&format!("jev-pr:{key}"))
        .unwrap()
        .unwrap();
    assert_eq!(saved["headSha"], "h");
    assert!(!fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .running_pulls
        .contains(&version));
    let mut stale = fixture.pull.clone();
    stale["headSha"] = json!("old");
    let old_version = version_key(&stale);
    fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .running_pulls
        .insert(old_version.clone());
    complete_pull(
        fixture.app.clone(),
        fixture.svc.clone(),
        stale,
        key.clone(),
        old_version.clone(),
        Ok(Some(pull_answer())),
    )
    .await;
    assert_eq!(
        fixture
            .app
            .host
            .storage
            .get(&format!("jev-pr:{key}"))
            .unwrap()
            .unwrap(),
        saved
    );
    assert!(!fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .running_pulls
        .contains(&old_version));
    for result in [
        Ok(None),
        Err(anyhow::anyhow!("synthetic failure")),
        Ok(Some(json!({"answers":{"kind":{"choice":"invalid"}}}))),
    ] {
        fixture
            .svc
            .ratings
            .lock()
            .unwrap()
            .running_pulls
            .insert(version.clone());
        complete_pull(
            fixture.app.clone(),
            fixture.svc.clone(),
            fixture.pull.clone(),
            key.clone(),
            version.clone(),
            result,
        )
        .await;
        assert!(!fixture
            .svc
            .ratings
            .lock()
            .unwrap()
            .running_pulls
            .contains(&version));
    }
    assert!(fixture
        .svc
        .ratings
        .lock()
        .unwrap()
        .failed_pulls
        .contains_key(&key));
    assert_eq!(
        fixture
            .app
            .host
            .storage
            .get(&format!("jev-pr:{key}"))
            .unwrap()
            .unwrap(),
        saved
    );
}
