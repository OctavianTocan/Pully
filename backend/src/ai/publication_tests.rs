use super::*;

fn run() -> Arc<Run> {
    Arc::new(Run {
        snapshot: Mutex::new(json!({"status":"running","rev":0,"activities":[]})).into(),
        publication: Default::default(),
        request: json!({}),
        task: true,
        notify: Notify::new().into(),
        cancel: watch::channel(false).0,
        login: crate::agent_login::AgentLogin::Server,
    })
}

#[tokio::test(start_paused = true)]
async fn streamed_text_many_deltas_share_one_publication_and_timer() {
    let run = run();
    record(&run, "answer", "text", "Writing", Some(""), "running");
    record(
        &run,
        "thinking",
        "thinking",
        "Thinking",
        Some(""),
        "running",
    );
    let before = snapshot(&run, -1.0);
    let notification = run.notify.notified();
    tokio::pin!(notification);
    assert!(futures_util::poll!(notification.as_mut()).is_pending());
    for i in 1..=100 {
        for id in ["answer", "thinking"] {
            record_patch(&run, json!({"id":id,"text":format!("delta {i}")}));
        }
    }
    assert_eq!(snapshot(&run, -1.0), before);
    assert!(wait_for_revision(&run.snapshot.lock().unwrap(), 2.0));
    assert!(snapshot(&run, 2.0)["activities"]
        .as_array()
        .unwrap()
        .is_empty());
    assert!(futures_util::poll!(notification.as_mut()).is_pending());
    assert_eq!(run.publication.lock().unwrap().pending.len(), 2);
    tokio::time::advance(Duration::from_millis(249)).await;
    crate::rt::yield_now().await;
    assert_eq!(snapshot(&run, -1.0), before);
    // The deadline starts at the first patch, even if the timer is polled later.
    tokio::time::advance(Duration::from_millis(1)).await;
    crate::rt::yield_now().await;
    assert!(futures_util::poll!(notification.as_mut()).is_ready());
    let published = snapshot(&run, 2.0);
    assert_eq!(published["rev"], 3);
    for entry in published["activities"].as_array().unwrap() {
        assert_eq!(entry["rev"], 3);
        assert_eq!(entry["text"], "delta 100");
    }
    assert_eq!(published["activities"].as_array().unwrap().len(), 2);
    assert!(run.publication.lock().unwrap().timer.is_none());
    for _ in 0..50 {
        record_patch(&run, json!({"id":"answer","text":"delta 100"}));
    }
    tokio::time::advance(Duration::from_secs(1)).await;
    crate::rt::yield_now().await;
    assert_eq!(snapshot(&run, 2.0), published);
    assert!(run.publication.lock().unwrap().pending.is_empty());
    assert!(run.publication.lock().unwrap().timer.is_none());
}

#[tokio::test(start_paused = true)]
async fn streamed_text_structural_updates_publish_pending_text_immediately() {
    let run = run();
    record_patch(
        &run,
        json!({"id":"tool","kind":"tool","title":"Reading","tool":"read_source","text":"start"}),
    );
    let started = snapshot(&run, 0.0)["activities"][0]["startedAt"].clone();
    record_patch(&run, json!({"id":"tool","text":"latest text"}));
    let timer = run
        .publication
        .lock()
        .unwrap()
        .timer
        .as_ref()
        .unwrap()
        .abort_handle();
    record_patch(&run, json!({"id":"tool","title":"Read evidence"}));
    let updated = snapshot(&run, 1.0);
    assert_eq!(updated["rev"], 2);
    assert_eq!(updated["activities"][0]["text"], "latest text");
    assert_eq!(updated["activities"][0]["title"], "Read evidence");
    assert_eq!(updated["activities"][0]["tool"], "read_source");
    assert_eq!(updated["activities"][0]["startedAt"], started);
    assert!(run.publication.lock().unwrap().timer.is_none());
    crate::rt::yield_now().await;
    assert!(timer.is_finished());
    record_patch(&run, json!({"id":"tool","text":"final text"}));
    record_patch(&run, json!({"id":"tool","status":"done"}));
    let done = snapshot(&run, 2.0);
    assert_eq!(done["rev"], 3);
    assert_eq!(done["activities"][0]["text"], "final text");
    assert!(done["activities"][0]["endedAt"].is_number());
    record_patch(
        &run,
        json!({"id":"tool","status":"done","text":"final text"}),
    );
    tokio::time::advance(Duration::from_secs(1)).await;
    crate::rt::yield_now().await;
    assert_eq!(snapshot(&run, 2.0), done);
}

#[tokio::test(start_paused = true)]
async fn streamed_text_terminal_settlement_flushes_and_stops_timer() {
    for status in ["done", "failed", "cancelled"] {
        let run = run();
        record(&run, "answer", "text", "Writing", Some("old"), "running");
        record_patch(&run, json!({"id":"answer","text":"complete final text 🦀"}));
        let timer = run
            .publication
            .lock()
            .unwrap()
            .timer
            .as_ref()
            .unwrap()
            .abort_handle();
        if status == "cancelled" {
            assert!(cancel(&run));
        } else {
            settle(&run, status, None, None, None);
        }
        let terminal = snapshot(&run, 1.0);
        assert_eq!(terminal["status"], status);
        assert_eq!(terminal["rev"], 2);
        assert_eq!(terminal["activities"][0]["rev"], 2);
        assert_eq!(terminal["activities"][0]["text"], "complete final text 🦀");
        assert!(run.publication.lock().unwrap().pending.is_empty());
        assert!(run.publication.lock().unwrap().timer.is_none());
        crate::rt::yield_now().await;
        assert!(timer.is_finished());
        record_patch(&run, json!({"id":"answer","text":"too late"}));
        tokio::time::advance(Duration::from_secs(1)).await;
        crate::rt::yield_now().await;
        assert_eq!(snapshot(&run, 1.0), terminal);
    }
}

#[tokio::test(start_paused = true)]
async fn streamed_text_reverting_to_published_value_does_not_publish() {
    let run = run();
    record(
        &run,
        "answer",
        "text",
        "Writing",
        Some("original"),
        "running",
    );
    let before = snapshot(&run, 0.0);
    record_patch(&run, json!({"id":"answer","text":"temporary"}));
    record_patch(&run, json!({"id":"answer","text":"original"}));
    assert!(run.publication.lock().unwrap().pending.is_empty());
    assert!(run.publication.lock().unwrap().timer.is_none());
    tokio::time::advance(Duration::from_secs(1)).await;
    crate::rt::yield_now().await;
    assert_eq!(snapshot(&run, 0.0), before);
}

#[tokio::test(start_paused = true)]
async fn streamed_text_aborted_timer_cannot_publish_a_later_window() {
    let run = run();
    record(&run, "answer", "text", "Writing", Some("old"), "running");
    record_patch(&run, json!({"id":"answer","text":"first window"}));
    let old_generation = run.publication.lock().unwrap().timer_generation;
    record_patch(&run, json!({"id":"answer","title":"Still writing"}));
    record_patch(&run, json!({"id":"answer","text":"second window"}));
    {
        let mut pending = run.publication.lock().unwrap();
        let mut snapshot = run.snapshot.lock().unwrap();
        // Model a cancelled callback that had already woken and waited on the lock.
        assert!(!pending.flush_timer(&mut snapshot, old_generation));
        assert!(pending.timer.is_some());
        assert_eq!(snapshot["rev"], 2);
        assert_eq!(snapshot["activities"][0]["text"], "first window");
    }
    tokio::time::advance(TEXT_FLUSH_DELAY).await;
    crate::rt::yield_now().await;
    assert_eq!(snapshot(&run, 2.0)["rev"], 3);
    assert_eq!(
        snapshot(&run, 2.0)["activities"][0]["text"],
        "second window"
    );
}
