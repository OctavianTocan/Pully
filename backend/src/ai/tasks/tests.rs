use super::*;

fn preparation_run(request: Value) -> Run {
    Run {
        snapshot: Mutex::new(json!({"id":"prepared","status":"running","activities":[],"rev":0})),
        request,
        task: true,
        notify: Notify::new(),
        cancel: watch::channel(false).0,
    }
}

#[test]
fn task_preparation_copies_only_available_non_inbox_titles() {
    let root = std::env::temp_dir().join(format!("pulls-task-title-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state
        .host
        .storage
        .set(
            "diff:test",
            &json!({"diff":{"title":"Readable pull request"}}),
        )
        .unwrap();
    for kind in [
        "analyze", "critique", "ask", "chat", "tour", "brief", "inbox",
    ] {
        let run = preparation_run(
            json!({"key":"test","kind":kind,"question":"Why?","threadId":"thread","selection":{"path":"a.rs","side":"additions","startLine":1,"endLine":1}}),
        );
        prepare(&state, &run).unwrap();
        let snapshot = run.snapshot.lock().unwrap();
        if kind == "inbox" {
            assert!(snapshot.get("title").is_none());
        } else {
            assert_eq!(snapshot["title"], "Readable pull request", "{kind}");
        }
    }
    for entry in [
        None,
        Some(json!({"diff":{}})),
        Some(json!({"diff":{"title":12}})),
    ] {
        state.host.storage.remove("diff:test").unwrap();
        if let Some(entry) = entry {
            state.host.storage.set("diff:test", &entry).unwrap();
        }
        let run = preparation_run(json!({"key":"test","kind":"chat"}));
        prepare(&state, &run).unwrap();
        assert!(run.snapshot.lock().unwrap().get("title").is_none());
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn inbox_retry_replaces_only_the_matching_latest_question() {
    let root = std::env::temp_dir().join(format!("pulls-inbox-retry-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let a = json!({"question":"A","answer":"Answer A","outcome":"done"});
    let b = json!({"question":"B","answer":"Answer B","outcome":"done"});
    for (question, retry, kept) in [("A", true, 2), ("B", true, 1), ("B", false, 2)] {
        state
            .host
            .storage
            .set(
                "inbox-chat",
                &json!({"turns":[a,b],"suggestions":["Old suggestion"]}),
            )
            .unwrap();
        let run = preparation_run(
            json!({"kind":"inbox","key":"inbox","question":question,"retry":retry}),
        );
        prepare(&state, &run).unwrap();
        let saved = state.host.storage.get("inbox-chat").unwrap().unwrap();
        let turns = saved["turns"].as_array().unwrap();
        assert_eq!(turns.len(), kept + 1);
        assert_eq!(turns[0], a);
        if kept == 2 {
            assert_eq!(turns[1], b);
        }
        assert_eq!(turns[kept]["question"], question);
        assert_eq!(turns[kept]["taskId"], "prepared");
        assert!(turns[kept].get("answer").is_none());
        assert!(saved.get("suggestions").is_none());
    }
    state.host.storage.remove("inbox-chat").unwrap();
    prepare(
        &state,
        &preparation_run(json!({"kind":"inbox","question":"A","retry":true})),
    )
    .unwrap();
    assert_eq!(
        state.host.storage.get("inbox-chat").unwrap().unwrap()["turns"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn tour_stop_previews_include_only_successes() {
    let run = Run {
        snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0})),
        request: json!({}),
        task: true,
        notify: tokio::sync::Notify::new(),
        cancel: tokio::sync::watch::channel(false).0,
    };
    let plan = json!({"title":"Planned","purpose":"Explain","files":["a.rs"]});
    let success = json!({"title":"Written","takeaway":"Useful","blocks":[],"sources":[]});
    assert_eq!(
        finish_tour_stop(&run, 0, &plan, Ok(success.clone())).unwrap(),
        success
    );
    let failed = finish_tour_stop(&run, 1, &plan, Err(anyhow::anyhow!("Fixture failed"))).unwrap();
    assert_eq!(failed["error"], "Fixture failed");
    assert_eq!(failed["title"], plan["title"]);
    assert_eq!(failed["files"], plan["files"]);
    finish_tour_stop(&run, 2, &plan, Ok(success)).unwrap();
    let snapshot = run.snapshot.lock().unwrap();
    let activities = snapshot["activities"].as_array().unwrap();
    assert_eq!(activities.len(), 2);
    assert_eq!(activities[0]["id"], "tour-stop:0");
    assert_eq!(activities[1]["id"], "tour-stop:2");
    assert!(activities.iter().all(|a| a["status"] == "done"));
}

#[test]
fn provider_grouping_preparation_normalizes_before_strict_validation() {
    let expected = json!({
        "overallSummary":"Together.",
        "groups":[{
            "key":"parent","label":"Parent","category":"core","filePaths":["a.rs"],
            "children":[{"key":"child","label":"Child","category":"tests","filePaths":["b.rs"]}]
        }],
        "files":[{"path":"a.rs","summary":"Changed.","hunks":[{"index":0,"note":"Updated."}]}]
    });
    let mut encoded = expected.clone();
    let parent = &mut encoded["groups"][0];
    parent["filePaths"] = json!(parent["filePaths"].to_string());
    parent["children"][0]["filePaths"] = json!(parent["children"][0]["filePaths"].to_string());
    parent["children"][0] = json!(parent["children"][0].to_string());
    parent["children"] = json!(parent["children"].to_string());
    *parent = json!(parent.to_string());
    encoded["groups"] = json!(encoded["groups"].to_string());
    encoded["files"][0]["hunks"] = json!(encoded["files"][0]["hunks"].to_string());
    encoded["files"][0] = json!(encoded["files"][0].to_string());
    encoded["files"] = json!(encoded["files"].to_string());
    let schema = grouping_schema();

    // Both provider transports use this exact preparation/validation boundary.
    for raw in [&encoded, &json!(encoded.to_string())] {
        assert!(
            prepare_structured_answer(&schema, raw, StructuredAnswerPreparation::Strict,).is_err()
        );
        let prepared =
            prepare_structured_answer(&schema, raw, StructuredAnswerPreparation::Grouping).unwrap();
        assert_eq!(prepared, expected);
        assert!(validate_schema(&schema, &prepared).is_ok());
    }
    assert_eq!(
        prepare_structured_answer(&schema, &expected, StructuredAnswerPreparation::Strict,)
            .unwrap(),
        expected
    );
}

#[test]
fn provider_grouping_preparation_rejects_invalid_values_and_other_schemas() {
    let schema = grouping_schema();
    let valid = json!({"overallSummary":"Together.","groups":[{"key":"all","label":"All","category":"core","filePaths":["a.rs"]}]});
    for (field, value) in [
        ("category", json!("invalid")),
        ("filePaths", json!("[1]")),
        ("filePaths", json!("not JSON")),
        ("children", json!("[{\"filePaths\":[]}]")),
    ] {
        let mut invalid = valid.clone();
        invalid["groups"][0][field] = value;
        invalid["groups"] = json!(invalid["groups"].to_string());
        assert!(prepare_structured_answer(
            &schema,
            &invalid,
            StructuredAnswerPreparation::Grouping,
        )
        .is_err());
    }
    let other_schema = json!({"type":"object","properties":{"groups":{"type":"array","items":{"type":"integer"}}},"required":["groups"],"additionalProperties":false});
    let other_answer = json!({"groups":"[1,2]"});
    for preparation in [
        StructuredAnswerPreparation::Strict,
        StructuredAnswerPreparation::Grouping,
    ] {
        assert!(prepare_structured_answer(&other_schema, &other_answer, preparation).is_err());
    }
    assert!(prepare_structured_answer(
        &other_schema,
        &json!({"groups":[1,2]}),
        StructuredAnswerPreparation::Strict,
    )
    .is_ok());
}

#[test]
fn tours_save_partial_successes_but_never_all_failed_stops() {
    let dir = std::env::temp_dir().join(format!("pulls-tour-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(dir.clone(), dir.clone()).unwrap();
    let request = preparation_run(json!({"key":"test","locale":"en"}));
    let diff = json!({"head":{"sha":"head"}});
    let failed = json!({"title":"Failed","error":"First stop failed"});
    let later = json!({"title":"Later","error":"Second stop failed"});
    let stops = vec![failed.clone(), later];
    let error = save_tour(
        &state,
        &request,
        &diff,
        json!({}),
        stops.clone(),
        &json!({}),
    )
    .unwrap_err();
    assert_eq!(error.to_string(), "First stop failed");
    assert!(state.host.storage.get("tour:test").unwrap().is_none());

    let success = json!({"title":"Worked","takeaway":"Useful","blocks":[],"sources":[]});
    let mixed = vec![failed, success];
    let result = save_tour(
        &state,
        &request,
        &diff,
        json!({}),
        mixed.clone(),
        &json!({}),
    )
    .unwrap();
    assert_eq!(result["stops"], json!(mixed));
    let saved = state.host.storage.get("tour:test").unwrap().unwrap();
    assert_eq!(saved["entries"][0], result);
    assert!(save_tour(&state, &request, &diff, json!({}), stops, &json!({})).is_err());
    assert_eq!(state.host.storage.get("tour:test").unwrap().unwrap(), saved);
    std::fs::remove_dir_all(dir).unwrap();
}
#[test]
fn saved_threads_salvage_valid_turns() {
    let v = json!({"threads":[{"id":"a","label":"a:1","selection":{"path":"a","side":"additions","startLine":1,"endLine":1},"turns":[{"question":"valid"},{"question":2}]},{"id":3}]});
    let ts = threads(Some(v));
    assert_eq!(ts.len(), 1);
    assert_eq!(ts[0]["turns"].as_array().unwrap().len(), 1);
}
#[test]
fn findings_must_cover_real_diff_lines() {
    let diff = json!({"files":[{"path":"a","hunks":[{"oldStart":4,"newStart":4,"patch":" x\n-old\n+new\n"}]}]});
    assert!(valid_finding(
        &diff,
        &json!({"path":"a","line":5,"side":"additions"})
    ));
    assert!(!valid_finding(
        &diff,
        &json!({"path":"a","line":6,"side":"additions"})
    ));
    assert!(!valid_finding(
        &diff,
        &json!({"path":"missing","line":5,"side":"additions"})
    ));
}
#[test]
fn inbox_citations_match_only_listed_pulls_in_mention_order() {
    let listed =
        json!([{"owner":"Org","repo":"Repo","number":2},{"owner":"Org","repo":"Repo","number":1}]);
    let cited = cited_pulls("See https://github.com/org/repo/pull/1 then ORG/REPO#2 and Org/Repo#1. Ignore org/repo#3 and prefix/org/repo#2.",&listed);
    assert_eq!(
        cited,
        json!([{"owner":"Org","repo":"Repo","number":1},{"owner":"Org","repo":"Repo","number":2}])
            .as_array()
            .unwrap()
            .clone()
    );
}
#[test]
fn malformed_nested_turns_and_repeat_suggestions_are_filtered() {
    let turns = turns(
        &json!([{"question":"ok","usage":{"costUsd":0.1}},{"question":"bad","usage":{"costUsd":"bad"}},{"question":"bad","activities":[{"id":"incomplete"}]}]),
    );
    assert_eq!(turns.len(), 1);
    let suggestions = suggestions(
        &json!({"questions":["Why this?","  Next   step? ","next step？","他には？"]}),
        "why THIS?",
    );
    assert_eq!(suggestions, vec!["Next step?", "他には？"]);
}

#[test]
fn brief_references_preserve_nested_validation_and_non_array_fallbacks() {
    let diff =
        json!({"files":[{"path":"a.rs","hunks":[{"patch":"large".repeat(1024)}]},{"path":"b.rs"}]});
    let before = diff.clone();
    let tree = json!({"path":"a.rs","children":[null,{"path":"b.rs","nested":{"path":4}}]});
    assert!(validate_brief_refs(&tree, &diff).is_ok());
    let unknown = json!({"children":[{"path":"missing.rs"},{"path":"a.rs"}]});
    assert_eq!(
        validate_brief_refs(&unknown, &diff)
            .unwrap_err()
            .to_string(),
        "Brief references a file outside the diff"
    );
    assert_eq!(diff, before);
    for files in [Value::Null, json!({}), json!(false), json!([])] {
        let empty = json!({"files":files});
        assert!(validate_brief_refs(&json!({"path":4}), &empty).is_ok());
        assert!(validate_brief_refs(&json!({"path":"a.rs"}), &empty).is_err());
        assert!(cited_pulls("org/repo#1", &files).is_empty());
    }
}

#[test]
fn answered_history_borrows_but_keeps_last_eight_pairs_in_order() {
    fn reference(turns: &Value) -> Value {
        let mut history: Vec<_> = array(turns)
            .into_iter()
            .filter(|t| t["question"].is_string() && t["answer"].is_string())
            .rev()
            .take(8)
            .map(|t| json!({"question":t["question"],"answer":t["answer"]}))
            .collect();
        history.reverse();
        json!(history)
    }
    let mut turns = vec![Value::Null, json!({"question":false,"answer":"x"})];
    for i in 0..12 {
        turns.push(json!({"question":format!("q{i}"),"answer":format!("😀{i}"),"activities":[{"data":"x".repeat(4096)}]}));
        turns.push(json!({"question":"pending","answer":null}));
    }
    turns.push(json!({"question":"","answer":""}));
    let cases = [
        Value::Null,
        json!({}),
        json!(false),
        json!([]),
        json!(turns),
    ];
    for turns in cases {
        let original = turns.clone();
        let result = answered_history(&turns);
        assert_eq!(result.to_string(), reference(&turns).to_string());
        assert_eq!(turns, original);
        assert!(result
            .as_array()
            .unwrap()
            .iter()
            .all(|t| t.as_object().unwrap().len() == 2));
        if turns.as_array().is_some_and(|a| a.len() > 8) {
            assert_eq!(result[0]["question"], "q5");
            assert_eq!(result[7], json!({"question":"","answer":""}));
        }
    }
}

#[test]
fn tour_plan_and_stop_json_survive_task_snapshots_whole() {
    let run = Run {
        snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0})),
        request: json!({}),
        task: true,
        notify: tokio::sync::Notify::new(),
        cancel: tokio::sync::watch::channel(false).0,
    };
    let long = "A useful description. ".repeat(1000);
    let plan = json!({"title":"Plan","overview":long,"stops":[]});
    record(
        &run,
        "tour-plan",
        "status",
        "Planned tour",
        Some(&plan.to_string()),
        "done",
    );
    let stop = json!({"title":"Stop","takeaway":long,"blocks":[],"sources":[]});
    finish_tour_stop(&run, 0, &plan, Ok(stop.clone())).unwrap();
    let snapshot = super::super::snapshot(&run, 0.0);
    assert_eq!(
        serde_json::from_str::<Value>(snapshot["activities"][0]["text"].as_str().unwrap()).unwrap(),
        plan
    );
    assert_eq!(
        serde_json::from_str::<Value>(snapshot["activities"][1]["text"].as_str().unwrap()).unwrap(),
        stop
    );
    assert!(super::super::snapshot(&run, 2.0)["activities"]
        .as_array()
        .unwrap()
        .is_empty());
}

#[test]
fn inbox_closed_window_uses_closure_date_and_missing_or_invalid_fallback() {
    let now = 1_792_152_000_000; // 2026-10-16T12:00:00Z
    let today = iso(now);
    let cutoff = now - 30 * 24 * 60 * 60 * 1000;
    assert!(!recently_closed(
        &json!({"closedAt":"2020-01-01T00:00:00Z","updatedAt":today}),
        now
    ));
    assert!(recently_closed(
        &json!({"closedAt":iso(now - 86_400_000),"updatedAt":"2020-01-01T00:00:00Z"}),
        now
    ));
    for closed in [
        Value::Null,
        json!(""),
        json!("invalid"),
        json!("2026-02-31T00:00:00Z"),
    ] {
        assert!(recently_closed(
            &json!({"closedAt":closed,"updatedAt":today}),
            now
        ));
    }
    assert!(recently_closed(&json!({"closedAt":iso(cutoff)}), now));
    assert!(!recently_closed(
        &json!({"closedAt":iso(cutoff - 1),"updatedAt":today}),
        now
    ));
    assert!(!recently_closed(
        &json!({"closedAt":"invalid","updatedAt":"invalid"}),
        now
    ));
}

#[test]
fn posted_task_system_explains_diff_only_capabilities() {
    let posted = json!({"ref":{"kind":"posted","id":"fixture"}});
    assert!(system_for(&posted).contains("There is no repository"));
    assert!(system_for(&posted).contains("captured changed paths"));
    assert_eq!(system_for(&json!({"ref":{"kind":"github-pr"}})), SYSTEM);
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_task_read_yields_and_observes_cancel() {
    use crate::store::test_support::HeldLock;
    let root = std::env::temp_dir().join(format!("pulls-ai-storage-read-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state
        .host
        .storage
        .set("diff:test", &json!({"headSha":"head","diff":{"files":[]}}))
        .unwrap();
    let run = Arc::new(preparation_run(
        json!({"kind":"chat","key":"test","text":"Why?","model":{"engine":"pi"}}),
    ));
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("prepared".into(), run.clone());
    let held = HeldLock::storage(&state.host.storage);
    let mut work = Box::pin(perform(&state, &run));
    assert!(futures_util::poll!(&mut work).is_pending());
    assert_eq!(
        handle(&state, "ai-task-cancel", json!({"id":"prepared"}))
            .await
            .unwrap()
            .unwrap(),
        true
    );
    let error = held.assert_yields(work).await.unwrap_err();
    assert_eq!(error.to_string(), "Stopped.");
    assert_eq!(state.jobs.provider_slots.available_permits(), 3);
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_task_writes_and_failure_cleanup_yield() {
    use crate::store::test_support::HeldLock;
    let root =
        std::env::temp_dir().join(format!("pulls-ai-storage-writes-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state
        .host
        .storage
        .set("diff:test", &json!({"headSha":"head","diff":{"files":[]}}))
        .unwrap();
    let run = Arc::new(preparation_run(
        json!({"kind":"inbox","key":"test","question":"Why?","model":{"engine":"pi"}}),
    ));
    prepare(&state, &run).unwrap();
    HeldLock::storage(&state.host.storage)
        .assert_yields(update_diff_async(&state, &run, &json!("head"), |entry| {
            entry["llmSession"] = json!({"messages":[],"chatStartIndex":0});
            Ok(())
        }))
        .await
        .unwrap();
    HeldLock::storage(&state.host.storage)
        .assert_yields(save_artifact_async(
            &state,
            &run,
            "brief",
            &json!({"lang":"Fixture","headSha":"head","locale":"en"}),
        ))
        .await
        .unwrap();
    HeldLock::storage(&state.host.storage)
        .assert_yields(patch_turn_async(
            &state,
            &run,
            json!({"answer":"Saved answer","outcome":"done"}),
        ))
        .await
        .unwrap();
    HeldLock::storage(&state.host.storage)
        .assert_yields(run_storage(&state, &run, |state, run| {
            save_inbox_suggestions(state, run, vec!["Next?".into()])
        }))
        .await
        .unwrap();
    cancel(&run);
    HeldLock::storage(&state.host.storage)
        .assert_yields(failed_async(&state, &run, "Stopped.", true))
        .await
        .unwrap();
    let saved = state.host.storage.get("inbox-chat").unwrap().unwrap();
    assert_eq!(saved["turns"][0]["answer"], "Saved answer");
    assert_eq!(saved["turns"][0]["outcome"], "done");
    assert_eq!(saved["suggestions"], json!(["Next?"]));
    // Failure cleanup must also persist on an unanswered, stopped turn.
    let failed = Arc::new(preparation_run(
        json!({"kind":"inbox","key":"test","question":"Again?"}),
    ));
    failed.snapshot.lock().unwrap()["id"] = json!("failed");
    prepare(&state, &failed).unwrap();
    cancel(&failed);
    HeldLock::storage(&state.host.storage)
        .assert_yields(failed_async(&state, &failed, "Stopped.", true))
        .await
        .unwrap();
    assert_eq!(
        state.host.storage.get("inbox-chat").unwrap().unwrap()["turns"][1]["outcome"],
        "stopped"
    );
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_detached_write_is_guarded_and_drained_on_shutdown() {
    use crate::store::test_support::HeldLock;
    let root =
        std::env::temp_dir().join(format!("pulls-ai-storage-drain-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let original = json!({"headSha":"head","diff":{"files":[]}});
    state.host.storage.set("diff:test", &original).unwrap();
    let run = Arc::new(preparation_run(json!({"kind":"chat","key":"test"})));
    state
        .jobs
        .runs
        .lock()
        .unwrap()
        .insert("prepared".into(), run.clone());
    let held = HeldLock::storage(&state.host.storage);
    let result = json!({"lang":"Late","headSha":"head","locale":"en"});
    let mut write = Box::pin(save_artifact_async(&state, &run, "brief", &result));
    assert!(futures_util::poll!(&mut write).is_pending());
    // Dropping the async future models the task's cancellation select.
    drop(write);
    cancel(&run);
    held.assert_yields(shutdown(&state)).await;
    assert_eq!(
        state.host.storage.get("diff:test").unwrap().unwrap(),
        original
    );
    assert!(state.host.storage.get("brief:test").unwrap().is_none());
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test(flavor = "current_thread")]
async fn ai_storage_grouping_tool_transaction_yields_and_preserves_guards() {
    use crate::store::test_support::HeldLock;
    let root = std::env::temp_dir().join(format!("pulls-ai-storage-tool-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    let diff = json!({"ref":{"kind":"posted","id":"fixture"},"head":{"sha":"head"},"files":[{"path":"a.rs","hunks":[]}]});
    state
        .host
        .storage
        .set(
            "diff:test",
            &json!({"headSha":"head","diff":diff,"analyzedBy":{}}),
        )
        .unwrap();
    let run = Arc::new(preparation_run(
        json!({"kind":"chat","key":"test","model":{"engine":"pi"}}),
    ));
    let mut ctx = context(&diff);
    ctx["allowGroupingUpdate"] = json!(true);
    let args = json!({"overallSummary":"Saved.","groups":[{"key":"all","label":"All","category":"core","filePaths":["a.rs"]}]});
    let result = HeldLock::storage(&state.host.storage)
        .assert_yields(tools::call_for_run(
            &state,
            &run,
            &ctx,
            "update_grouping",
            &args,
        ))
        .await
        .unwrap();
    assert!(result.contains("1 groups"));
    let saved = state.host.storage.get("diff:test").unwrap().unwrap();
    assert_eq!(saved["analyzedBy"]["llm"]["overallSummary"], "Saved.");
    let held = HeldLock::storage(&state.host.storage);
    let mut update = Box::pin(tools::call_for_run(
        &state,
        &run,
        &ctx,
        "update_grouping",
        &args,
    ));
    assert!(futures_util::poll!(&mut update).is_pending());
    cancel(&run);
    drop(held);
    assert!(tokio::time::timeout(Duration::from_secs(1), update)
        .await
        .unwrap()
        .is_err());
    assert_eq!(state.host.storage.get("diff:test").unwrap().unwrap(), saved);
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn shared_task_manifest_preserves_pure_rename_origin() {
    let diff = json!({"title":"Rename", "files":[
        {"path":"new.rs","previousPath":"old.rs","status":"renamed","hunks":[],"additions":0,"deletions":0},
        {"path":"added.rs","status":"added","hunks":[]}
    ]});
    let heading = prompt_heading(&diff).unwrap();
    let manifest: Value = serde_json::from_str(heading.split_once("\nFiles: ").unwrap().1).unwrap();
    assert_eq!(manifest[0]["previousPath"], "old.rs");
    assert_eq!(manifest[0]["path"], "new.rs");
    assert!(manifest[1].get("previousPath").is_none());
}
