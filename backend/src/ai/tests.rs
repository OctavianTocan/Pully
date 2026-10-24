use super::*;

#[tokio::test]
async fn idle_update_defers_running_and_reserved_work_without_cancelling() {
    let root = std::env::temp_dir().join(format!("pulls-idle-update-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let active = run();
    state.jobs.runs.lock().unwrap().insert("reserved".into(), active.clone());
    assert!(!begin_idle_update_shutdown(&state));
    assert!(!*active.cancel.borrow());
    assert!(!state.jobs.stopping.load(std::sync::atomic::Ordering::SeqCst));
    for status in ["done", "failed", "cancelled"] {
        active.snapshot.lock().unwrap()["status"] = json!(status);
        assert!(begin_idle_update_shutdown(&state));
    }
    crate::rt::timeout(Duration::from_secs(1), wait_for_update_shutdown(&state)).await.unwrap();
    let request = json!({"engine":"pi", "system":"test", "prompt":"test"});
    assert!(start_async(&state, request.clone(), false, &crate::agent_login::AgentLogin::Server).await.unwrap_err().to_string().contains("shutting down"));
    assert!(start(&state, request, false, None, crate::agent_login::AgentLogin::Server).unwrap_err().to_string().contains("shutting down"));
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn idle_update_counts_admissions_before_the_run_registry_is_populated() {
    let root = std::env::temp_dir().join(format!("pulls-idle-admission-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let (finish, pending) = tokio::sync::oneshot::channel::<()>();
    state.jobs.handles.lock().unwrap().push(crate::rt::spawn(async move { let _ = pending.await; }));
    assert!(!begin_idle_update_shutdown(&state));
    assert!(!state.jobs.stopping.load(std::sync::atomic::Ordering::SeqCst));
    finish.send(()).unwrap();
    crate::rt::timeout(Duration::from_secs(1), async {
        while !state.jobs.handles.lock().unwrap()[0].is_finished() { crate::rt::yield_now().await; }
    }).await.unwrap();
    assert!(begin_idle_update_shutdown(&state));
    std::fs::remove_dir_all(root).unwrap();
}

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
        snapshot: Mutex::new(json!({"id":"test","status":"running","rev":0,"activities":[]}))
            .into(),
        publication: Default::default(),
        request: json!({}),
        task: true,
        notify: Notify::new().into(),
        cancel: tx,
        login: crate::agent_login::AgentLogin::Server,
    })
}

#[tokio::test]
async fn personal_task_ids_never_cross_account_or_revoked_source_boundaries() {
    use crate::identity::{GitHubCredential, TenantStates};
    let root = std::env::temp_dir().join(format!("pully-task-owners-{}", uuid::Uuid::new_v4()));
    let tenants = TenantStates::new(root.clone(), 4).unwrap();
    let binding = GitHubCredential::fixture("account-a", 42, "fixture-token-a");
    let a = tenants.account(binding.clone()).await.unwrap();
    let b = tenants
        .account(GitHubCredential::fixture(
            "account-b",
            43,
            "fixture-token-b",
        ))
        .await
        .unwrap();
    let guest = tenants.guest().await.unwrap();
    a.jobs.runs.lock().unwrap().insert("test".into(), run());
    assert_eq!(
        handle(&a, "ai-task-list-all", json!({}))
            .await
            .unwrap()
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        1
    );
    for other in [&b, &guest] {
        assert_eq!(
            handle(other, "ai-task-list-all", json!({}))
                .await
                .unwrap()
                .unwrap(),
            json!([])
        );
        assert!(
            handle(other, "ai-task-wait", json!({"id":"test","after":-1}))
                .await
                .unwrap()
                .is_err()
        );
        assert!(handle(other, "ai-task-cancel", json!({"id":"test"}))
            .await
            .unwrap()
            .is_err());
        assert!(task_key(other, "test").is_err());
    }
    binding.revoke();
    assert!(handle(&a, "ai-task-wait", json!({"id":"test","after":-1}))
        .await
        .unwrap()
        .is_err());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn admitted_task_titles_survive_wait_and_list_routes() {
    let root = std::env::temp_dir().join(format!("pulls-title-routes-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state
        .host
        .storage
        .set(
            "diff:test",
            &json!({"diff":{"title":"Readable pull request"}}),
        )
        .unwrap();
    let session = state.jobs.session("test");
    let guard = session.lock().await;
    let id = crate::dispatch(
        &state,
        "ai-task-start",
        json!({"kind":"chat","key":"test","locale":"en","text":"Why?","model":{"engine":"pi"}}),
    )
    .await
    .unwrap();
    for (name, args) in [
        ("ai-task-wait", json!({"id":id,"after":-1})),
        ("ai-task-list", json!({"key":"test"})),
        ("ai-task-list-all", json!({})),
    ] {
        let reply =
            crate::rt::timeout(Duration::from_secs(2), crate::dispatch(&state, name, args))
                .await
                .unwrap()
                .unwrap();
        let task = if name == "ai-task-wait" {
            &reply
        } else {
            &reply[0]
        };
        assert_eq!(task["title"], "Readable pull request", "{name}");
        assert_eq!(task["id"], id);
        assert_eq!(task["status"], "running");
    }
    begin_shutdown(&state);
    drop(guard);
    crate::rt::timeout(Duration::from_secs(2), shutdown(&state))
        .await
        .unwrap();
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn shutdown_cancels_pending_http_llm_run_before_server_drain() {
    let root = std::env::temp_dir().join(format!("pulls-http-shutdown-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    // No provider can start or complete naturally, and no external service is used.
    let held_slots = state
        .jobs
        .provider_slots
        .clone()
        .acquire_many_owned(3)
        .await
        .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let http =
        crate::transport::HttpState::new(state.clone(), "127.0.0.1", port, vec![], true, None)
            .unwrap();
    let (stop, stopped) = tokio::sync::oneshot::channel();
    let shutdown_state = state.clone();
    let server = crate::rt::spawn(async move {
        axum::serve(listener, crate::transport::router(http))
            .with_graceful_shutdown(async move {
                stopped.await.unwrap();
                begin_shutdown(&shutdown_state);
            })
            .await
            .unwrap();
    });
    let request = crate::rt::spawn(async move {
        reqwest::Client::builder().no_proxy().timeout(Duration::from_secs(3)).build().unwrap()
            .post(format!("http://127.0.0.1:{port}/api/web-rpc"))
            .json(&json!({"name":"llm-run","args":{"engine":"pi","system":"Fixture","prompt":"Wait"}}))
            .send().await.unwrap()
    });
    crate::rt::timeout(Duration::from_secs(2), async {
        loop {
            if !state.jobs.runs.lock().unwrap().is_empty() {
                break;
            }
            crate::rt::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert!(!request.is_finished());
    assert_eq!(
        state
            .jobs
            .runs
            .lock()
            .unwrap()
            .values()
            .next()
            .unwrap()
            .snapshot
            .lock()
            .unwrap()["status"],
        "running"
    );
    stop.send(()).unwrap();
    let response = crate::rt::timeout(Duration::from_secs(2), request)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(response.status(), reqwest::StatusCode::BAD_REQUEST);
    assert!(response.json::<Value>().await.unwrap()["error"].is_string());
    crate::rt::timeout(Duration::from_secs(2), server)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        state
            .jobs
            .runs
            .lock()
            .unwrap()
            .values()
            .next()
            .unwrap()
            .snapshot
            .lock()
            .unwrap()["status"],
        "cancelled"
    );
    assert!(start_async(
        &state,
        json!({"engine":"pi","system":"Fixture","prompt":"Late"}),
        false,
        &crate::agent_login::AgentLogin::Server,
    )
    .await
    .unwrap_err()
    .to_string()
    .contains("shutting down"));
    crate::rt::timeout(Duration::from_secs(2), shutdown(&state))
        .await
        .unwrap();
    assert!(state
        .jobs
        .handles
        .lock()
        .unwrap()
        .iter()
        .all(|h| h.is_finished()));
    // Keep all slots held until after drain: cancellation must release the HTTP waiter.
    drop(held_slots);
    std::fs::remove_dir_all(root).unwrap();
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
        let error = crate::rt::timeout(Duration::from_secs(1), work)
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
    let result = crate::rt::timeout(Duration::from_secs(1), work)
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(result["rev"], 1);
    assert_eq!(result["activities"].as_array().unwrap().len(), 1);
    let immediate = crate::rt::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-task-wait", json!({"id":"test","after":0})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(immediate, result);
    settle(&r, "done", Some("answer".into()), None, None);
    let terminal = crate::rt::timeout(
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
    let immediate = crate::rt::timeout(
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
    let immediate = crate::rt::timeout(
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
    let result = crate::rt::timeout(Duration::from_secs(1), work)
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
    finish_success(&state, &analysis, "analysis".into(), json!({})).await;
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
        finish_success(&state, &analysis, "analysis".into(), json!({})).await;
        assert_eq!(snapshot(&analysis, 0.0)["status"], "done");
        assert!(state.jobs.runs.lock().unwrap().is_empty());
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
    finish_success(&state, &analysis, "analysis".into(), json!({})).await;
    assert_eq!(snapshot(&analysis, 0.0)["status"], "cancelled");
    assert!(state.jobs.runs.lock().unwrap().is_empty());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_rpc_transactions_yield_under_contention() {
    use crate::store::test_support::HeldLock;
    let root = std::env::temp_dir().join(format!("pulls-ai-storage-rpc-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state.host.storage.set("diff:test", &json!({"headSha":"head","diff":{"title":"Fixture"},"llmSession":{"messages":[{"role":"user","content":"Why?"}],"chatStartIndex":0}})).unwrap();
    for (name, args) in [
        ("ask-threads", json!({"key":"test"})),
        (
            "ask-thread-close",
            json!({"key":"test","threadId":"thread"}),
        ),
        ("inbox-chat", json!({})),
        ("inbox-chat-clear", json!({})),
    ] {
        HeldLock::storage(&state.host.storage)
            .assert_yields(handle(&state, name, args))
            .await
            .unwrap()
            .unwrap();
    }
    let changed = HeldLock::storage(&state.host.storage)
        .assert_yields(change_chat(&state, "test", None))
        .await
        .unwrap();
    assert_eq!(changed["cleared"], 1);
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_admission_keeps_dedup_poll_cancel_and_shutdown_responsive() {
    use crate::store::test_support::HeldLock;
    let root = std::env::temp_dir().join(format!(
        "pulls-ai-storage-admission-{}",
        uuid::Uuid::new_v4()
    ));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let held = HeldLock::storage(&state.host.storage);
    let request = json!({"kind":"inbox","key":"inbox","question":"Why?","model":{"engine":"pi"}});
    let mut admission = Box::pin(start_async(&state, request.clone(), true, &crate::agent_login::AgentLogin::Server));
    assert!(futures_util::poll!(&mut admission).is_pending());
    let id = crate::rt::timeout(Duration::from_secs(1), async {
        loop {
            let id = state
                .jobs
                .runs
                .try_lock()
                .ok()
                .and_then(|runs| runs.keys().next().cloned());
            if let Some(id) = id {
                break id;
            }
            crate::rt::yield_now().await;
        }
    })
    .await
    .expect("admission did not reserve a run without its storage lock");
    let joined = crate::rt::timeout(Duration::from_secs(1), start_async(&state, request, true, &crate::agent_login::AgentLogin::Server))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(joined, id);
    let pending = crate::rt::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-task-wait", json!({"id":id,"after":-1})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(pending["status"], "running");
    let stopped = crate::rt::timeout(
        Duration::from_secs(1),
        handle(&state, "ai-task-cancel", json!({"id":id})),
    )
    .await
    .unwrap()
    .unwrap()
    .unwrap();
    assert_eq!(stopped, true);
    begin_shutdown(&state);
    assert!(start_async(&state, json!({}), true, &crate::agent_login::AgentLogin::Server).await.is_err());
    let error = held.assert_yields(admission).await.unwrap_err();
    assert_eq!(error.to_string(), "Stopped.");
    assert_eq!(
        snapshot(&lookup(&state, &id).unwrap(), -1.0)["status"],
        "cancelled"
    );
    assert!(
        tasks::inbox(state.host.storage.get("inbox-chat").unwrap())["turns"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(state.jobs.provider_slots.available_permits(), 3);
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_followup_reads_yield_before_completion() {
    use crate::store::test_support::HeldLock;
    let root = std::env::temp_dir().join(format!(
        "pulls-ai-storage-followup-{}",
        uuid::Uuid::new_v4()
    ));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state
        .host
        .storage
        .set("diff:test", &json!({"diff":{"head":{"sha":"head"}}}))
        .unwrap();
    state
        .host
        .storage
        .set(
            "tour:test",
            &json!({"entries":[{"headSha":"head","locale":"en"}]}),
        )
        .unwrap();
    let mut analysis = run();
    Arc::get_mut(&mut analysis).unwrap().request =
        json!({"kind":"analyze","key":"test","then":["tour"],"model":{"engine":"pi"}});
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("test".into(), analysis.clone());
    let held = HeldLock::storage(&state.host.storage);
    let mut completion = Box::pin(finish_success(
        &state,
        &analysis,
        "Analysis".into(),
        json!({}),
    ));
    assert!(futures_util::poll!(&mut completion).is_pending());
    crate::rt::sleep(Duration::from_millis(10)).await;
    assert_eq!(snapshot(&analysis, -1.0)["status"], "running");
    held.assert_yields(completion).await;
    assert_eq!(snapshot(&analysis, -1.0)["status"], "done");
    assert_eq!(state.jobs.runs.lock().unwrap().len(), 1);
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn experimental_reviews_cannot_join_an_ordinary_running_review() {
    let ordinary = json!({"key":"same-pr","kind":"critique","variant":"same"});
    let mut experiment = ordinary.clone();
    experiment["experiments"] = json!({"mergeRisk":true});
    assert!(!same(&ordinary, &experiment, true));
    assert!(!same(&experiment, &ordinary, true));
    experiment["experiments"]["mergeRisk"] = json!(false);
    assert!(same(&ordinary, &experiment, true));
}

#[tokio::test(flavor = "current_thread")]
async fn another_persons_run_is_never_joined() {
    use crate::agent_login::AgentLogin;
    let root = std::env::temp_dir().join(format!("pulls-ai-login-join-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let request = json!({"kind":"inbox","key":"inbox","question":"Why?","model":{"engine":"pi"}});
    let first = start(&state, request.clone(), true, None, AgentLogin::Account("a".into())).unwrap();
    let joined = start(&state, request.clone(), true, None, AgentLogin::Account("a".into())).unwrap();
    let other = start(&state, request, true, None, AgentLogin::Account("b".into())).unwrap();
    assert_eq!(first, joined);
    assert_ne!(first, other);
    assert_eq!(lookup(&state, &other).unwrap().login, AgentLogin::Account("b".into()));
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}
