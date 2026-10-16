use super::*;
#[test]
fn activity_tail_preserves_unicode_and_boundary() {
    assert_eq!(tail(""), "");
    let boundary = "☃".repeat(8000);
    assert_eq!(tail(&boundary), boundary);
    assert_eq!(tail(&format!("a{boundary}")), format!("…{boundary}"));
}
fn run() -> Arc<Run> {
    let (tx, _) = watch::channel(false);
    Arc::new(Run {
        snapshot: Mutex::new(json!({"id":"test","status":"running","rev":0,"activities":[]})),
        request: json!({}),
        task: true,
        notify: Notify::new(),
        cancel: tx,
    })
}
#[test]
fn revisions_and_cancellation_are_terminal() {
    let r = run();
    record(&r, "a", "tool", "Read", Some("complete"), "running");
    assert_eq!(snapshot(&r, 0.0)["activities"].as_array().unwrap().len(), 1);
    assert!(snapshot(&r, 1.0)["activities"]
        .as_array()
        .unwrap()
        .is_empty());
    assert!(cancel(&r));
    record(&r, "b", "text", "Late", None, "done");
    settle(&r, "done", Some("late".into()), None, None);
    assert_eq!(snapshot(&r, 0.0)["status"], "cancelled");
    assert_eq!(snapshot(&r, 0.0)["activities"].as_array().unwrap().len(), 1);
    assert!(!cancel(&r));
}
#[tokio::test]
async fn bridge_tokens_are_scoped_and_expire_with_their_run() {
    let root = std::env::temp_dir().join(format!("pulls-bridge-fixture-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    configure_bridge(&state, "http://host.docker.internal:12345/mcp".into());
    let live = run();
    let (payload, guard) =
        scoped_bridge(&state, &live, &json!({"headSha":"abc","files":[]})).unwrap();
    let token = payload["_rustMcpBridge"]["token"].as_str().unwrap();
    let request = json!({"jsonrpc":"2.0","id":1,"method":"tools/list"});
    assert!(bridge_mcp(&state, "invalid", request.clone())
        .await
        .is_none());
    let response = bridge_mcp(&state, token, request.clone()).await.unwrap();
    assert!(response["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .any(|tool| tool["name"] == "search_source"));
    assert!(cancel(&live));
    assert!(bridge_mcp(&state, token, request.clone()).await.is_none());
    drop(guard);
    assert!(state.jobs.bridges.lock().unwrap().is_empty());
    let live = run();
    let (_, guard) = scoped_bridge(&state, &live, &json!({})).unwrap();
    let token = guard.token.clone();
    drop(guard);
    assert!(bridge_mcp(&state, &token, request).await.is_none());
    drop(state);
    let _ = std::fs::remove_dir_all(root);
}
#[test]
fn dedupe_matches_task_scope() {
    let a = json!({"kind":"ask","key":"x","threadId":"one"});
    assert!(same(&a, &a, true));
    assert!(!same(
        &a,
        &json!({"kind":"ask","key":"x","threadId":"two"}),
        true
    ));
    assert!(!same(&a, &json!({"kind":"chat","key":"x"}), true));
    assert!(!same(&json!({}), &json!({}), false));
    let a = json!({"kind":"analyze","key":"x","expectedHeadSha":"a"});
    let b = json!({"kind":"analyze","key":"x","expectedHeadSha":"b"});
    assert!(same(&a, &a, true));
    assert!(!same(&a, &b, true));
    assert!(!same(&a, &json!({"kind":"analyze","key":"x"}), true));
}
#[tokio::test]
async fn analysis_rechecks_reserved_head_after_waiting_for_session_lock() {
    let root = std::env::temp_dir().join(format!("pulls-head-wait-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.clone()).unwrap();
    state
        .host
        .storage
        .set(
            "diff:test",
            &json!({"headSha":"a","diff":{"head":{"sha":"a"}}}),
        )
        .unwrap();
    let mut r = run();
    Arc::get_mut(&mut r).unwrap().request =
        json!({"kind":"analyze","key":"test","expectedHeadSha":"a","model":{"engine":"pi"}});
    let lock = state.jobs.session("test");
    let guard = lock.lock().await;
    {
        let work = tasks::perform(&state, &r);
        tokio::pin!(work);
        assert!(futures_util::poll!(work.as_mut()).is_pending());
        state
            .host
            .storage
            .set(
                "diff:test",
                &json!({"headSha":"b","diff":{"head":{"sha":"b"}}}),
            )
            .unwrap();
        drop(guard);
        let error = tokio::time::timeout(Duration::from_secs(1), work)
            .await
            .unwrap()
            .unwrap_err();
        assert!(error
            .to_string()
            .contains("changed before analysis started"));
    }
    assert!(snapshot(&r, 0.0)["activities"]
        .as_array()
        .unwrap()
        .is_empty());
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn structured_answers_validate_nested_values() {
    let schema = json!({"type":"object","required":["items"],"properties":{"items":{"type":"array","items":{"type":"integer"}}},"additionalProperties":false});
    assert!(validate_schema(&schema, &json!({"items":[1,2]})).is_ok());
    assert!(validate_schema(&schema, &json!({"items":["wrong"]})).is_err());
    assert!(validate_schema(&schema, &json!({"items":[],"unknown":true})).is_err());
    assert!(validate_schema(&schema, &json!({})).is_err());
}

#[test]
fn snapshot_projects_activity_deltas_without_changing_fields_or_order() {
    let r = run();
    let stored = json!({"id":"test","activities":[{"rev":0,"text":"old"},{"rev":1,"text":"α"},{"rev":3,"text":"β"}],"status":"running","rev":3,"usage":{"inputTokens":8}});
    *r.snapshot.lock().unwrap() = stored.clone();
    for after in [0.0, 1.0, 2.0, 3.0, f64::INFINITY] {
        let mut expected = stored.clone();
        expected["activities"] = json!(expected["activities"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|activity| activity["rev"].as_f64().unwrap_or(0.0) > after)
            .cloned()
            .collect::<Vec<_>>());
        assert_eq!(snapshot(&r, after).to_string(), expected.to_string());
        assert_eq!(*r.snapshot.lock().unwrap(), stored);
    }
}

#[test]
fn terminal_result_preserves_status_values_and_error_text() {
    let r = run();
    assert!(terminal_result(&r).is_none());
    for result in [Value::Null, json!("answer"), json!({"answer":"α"})] {
        *r.snapshot.lock().unwrap() = json!({"status":"done","result":result,"activities":[]});
        assert_eq!(terminal_result(&r).unwrap().unwrap(), result);
    }
    for status in ["failed", "cancelled", "unknown"] {
        *r.snapshot.lock().unwrap() =
            json!({"status":status,"error":"Exact failure.\nSecond line.","activities":[]});
        assert_eq!(
            terminal_result(&r).unwrap().unwrap_err().to_string(),
            "Exact failure.\nSecond line."
        );
    }
    *r.snapshot.lock().unwrap() = json!({"status":"failed","activities":[]});
    assert_eq!(terminal_result(&r).unwrap().unwrap_err().to_string(), "");
}

#[tokio::test]
async fn wait_keeps_revision_predicate_and_notification_order() {
    let root = std::env::temp_dir().join(format!("pulls-wait-projection-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("cache")).unwrap();
    let r = run();
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("test".into(), r.clone());
    let work = handle(&state, "ai-task-wait", json!({"id":"test","after":0}));
    tokio::pin!(work);
    assert!(futures_util::poll!(work.as_mut()).is_pending());
    record(&r, "one", "text", "Progress", Some("α"), "done");
    let result = tokio::time::timeout(Duration::from_secs(1), work)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(result["rev"], 1);
    assert_eq!(result["activities"].as_array().unwrap().len(), 1);
    let immediate = tokio::time::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-task-wait", json!({"id":"test","after":0})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(immediate, result);
    settle(&r, "done", Some("answer".into()), None, None);
    let terminal = tokio::time::timeout(
        Duration::from_secs(1),
        handle(
            &state,
            "ai-task-wait",
            json!({"id":"test","after":u64::MAX}),
        ),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(terminal["status"], "done");
    assert!(terminal["activities"].as_array().unwrap().is_empty());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn numeric_wait_cursors_preserve_comparison_and_delta_semantics() {
    let r = run();
    assert!(!wait_for_revision(&r.snapshot.lock().unwrap(), -1.0));
    assert!(wait_for_revision(&r.snapshot.lock().unwrap(), 0.0));
    assert!(wait_for_revision(&r.snapshot.lock().unwrap(), 0.5));
    *r.snapshot.lock().unwrap() = json!({"id":"test","status":"running","rev":2,
        "activities":[{"rev":0},{"rev":1},{"rev":2}]});
    for (after, revisions) in [
        (-1.0, vec![0, 1, 2]),
        (0.0, vec![1, 2]),
        (1.0, vec![2]),
        (1.5, vec![2]),
        (2.0, vec![]),
        (f64::INFINITY, vec![]),
    ] {
        let actual = snapshot(&r, after)["activities"]
            .as_array()
            .unwrap()
            .iter()
            .map(|entry| entry["rev"].as_u64().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(actual, revisions, "after={after}");
    }
    assert!(!wait_for_revision(&r.snapshot.lock().unwrap(), 1.5));
    assert!(wait_for_revision(&r.snapshot.lock().unwrap(), 2.5));
    r.snapshot.lock().unwrap()["status"] = json!("done");
    assert!(!wait_for_revision(
        &r.snapshot.lock().unwrap(),
        f64::INFINITY
    ));
}

#[tokio::test]
async fn numeric_wait_cursors_return_or_wait_at_the_declared_number() {
    let root = std::env::temp_dir().join(format!("pulls-numeric-wait-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("cache")).unwrap();
    let r = run();
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("test".into(), r.clone());
    let immediate = tokio::time::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-task-wait", json!({"id":"test","after":-1})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(immediate["rev"], 0);
    record(&r, "one", "text", "First", None, "done");
    record(&r, "two", "text", "Second", None, "done");
    let immediate = tokio::time::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-job-wait", json!({"id":"test","after":1.5})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(immediate["activities"].as_array().unwrap().len(), 1);
    assert_eq!(immediate["activities"][0]["rev"], 2);
    let work = handle(&state, "ai-task-wait", json!({"id":"test","after":2.5}));
    tokio::pin!(work);
    assert!(futures_util::poll!(work.as_mut()).is_pending());
    record(&r, "three", "text", "Third", None, "done");
    let result = tokio::time::timeout(Duration::from_secs(1), work)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(result["activities"].as_array().unwrap().len(), 1);
    assert_eq!(result["activities"][0]["rev"], 3);
    let listed = handle(&state, "ai-task-list-all", json!({}))
        .await
        .unwrap()
        .unwrap();
    assert!(listed[0]["activities"].as_array().unwrap().is_empty());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn analysis_completion_already_lists_all_eligible_followups() {
    let root = std::env::temp_dir().join(format!("pulls-followup-order-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let mut analysis = run();
    Arc::get_mut(&mut analysis).unwrap().request = json!({"kind":"analyze","key":"posted:fixture","model":{"engine":"pi"},"locale":"en","then":["tour","brief"]});
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("test".into(), analysis.clone());
    let wait = handle(&state, "ai-task-wait", json!({"id":"test","after":0}));
    tokio::pin!(wait);
    assert!(futures_util::poll!(wait.as_mut()).is_pending());
    finish_success(&state, &analysis, "analysis".into(), json!({}));
    assert_eq!(wait.await.unwrap().unwrap()["status"], "done");
    let listed = handle(&state, "ai-task-list", json!({"key":"posted:fixture"}))
        .await
        .unwrap()
        .unwrap();
    for kind in ["tour", "brief"] {
        assert!(listed
            .as_array()
            .unwrap()
            .iter()
            .any(|task| task["kind"] == kind));
    }
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn current_posted_tour_is_kept_without_starting_followup_work() {
    let root = std::env::temp_dir().join(format!("pulls-posted-tour-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state.host.storage.set("diff:posted:fixture", &json!({"headSha":"v1.12345","diff":{"ref":{"kind":"posted","id":"fixture"},"files":[]}})).unwrap();
    let saved = json!({"entries":[{"locale":"en","title":"Agent supplied tour","stops":[]}]});
    state
        .host
        .storage
        .set("tour:posted:fixture", &saved)
        .unwrap();
    for locale in [Value::Null, json!("en")] {
        let mut analysis = run();
        Arc::get_mut(&mut analysis).unwrap().request = json!({"kind":"analyze","key":"posted:fixture","locale":locale,"model":{"engine":"pi"},"then":["tour"]});
        finish_success(&state, &analysis, "analysis".into(), json!({}));
        assert_eq!(snapshot(&analysis, 0.0)["status"], "done");
        assert!(state.jobs.runs.lock().unwrap().is_empty());
        assert!(state.jobs.handles.lock().unwrap().is_empty());
        assert_eq!(state.jobs.provider_slots.available_permits(), 3);
        assert_eq!(
            state
                .host
                .storage
                .get("tour:posted:fixture")
                .unwrap()
                .unwrap(),
            saved
        );
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn stopped_analysis_cannot_register_followups_or_publish_done() {
    let root =
        std::env::temp_dir().join(format!("pulls-stopped-followup-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let mut analysis = run();
    Arc::get_mut(&mut analysis).unwrap().request =
        json!({"kind":"analyze","key":"test","model":{"engine":"pi"},"then":["tour","brief"]});
    cancel(&analysis);
    finish_success(&state, &analysis, "analysis".into(), json!({}));
    assert_eq!(snapshot(&analysis, 0.0)["status"], "cancelled");
    assert!(state.jobs.runs.lock().unwrap().is_empty());
    std::fs::remove_dir_all(root).unwrap();
}
