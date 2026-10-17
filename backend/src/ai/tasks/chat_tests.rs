use super::*;

struct Fixture {
    root: std::path::PathBuf,
    state: Arc<AppState>,
}
impl Fixture {
    fn new(session: Option<Value>) -> Self {
        let root = std::env::temp_dir().join(format!("pulls-chat-turn-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.join("data")).unwrap();
        let mut entry = json!({"headSha":"head","diff":{
            "ref":{"kind":"posted","id":"fixture"},"head":{"sha":"head"},
            "title":"A change", "files":[], "commits":[
                {"sha":"1234567890abcdef","message":"First subject\nBody stays out"},
                {"sha":"abcdef1234567890","message":"Second subject\nAnother body"}
            ]
        }});
        if let Some(session) = session {
            entry["llmSession"] = session;
        }
        state.host.storage.set("diff:test", &entry).unwrap();
        Self { root, state }
    }
    fn entry(&self) -> Value {
        self.state.host.storage.get("diff:test").unwrap().unwrap()
    }
    async fn perform(&self, mut request: Value) -> Result<(String, Value)> {
        request["key"] = json!("test");
        request["model"] = json!({"engine":"fixture"});
        let run = Arc::new(super::tests::preparation_run(request));
        tokio::time::timeout(
            Duration::from_secs(3),
            perform_with_provider(&self.state, &run, fixture_provider),
        )
        .await
        .expect("offline chat fixture timed out")
    }
    async fn close(self) {
        shutdown(&self.state).await;
        std::fs::remove_dir_all(self.root).unwrap();
    }
}

fn fixture_provider<'a>(
    state: &'a Arc<AppState>,
    run: &'a Arc<Run>,
    job: &'a Value,
    preparation: StructuredAnswerPreparation,
) -> futures_util::future::BoxFuture<'a, Result<(String, Value)>> {
    Box::pin(async move {
        // Suggested questions use the same injected provider, never the network/CLI.
        if job["schema"]["properties"].get("questions").is_some() {
            return Ok((json!({"questions":[]}).to_string(), json!({})));
        }
        let prompt = text(job, "prompt");
        assert!(prompt.contains("---COMMITS--- (2, oldest first)"));
        assert!(
            prompt.find("1234567 First subject").unwrap()
                < prompt.find("abcdef1 Second subject").unwrap()
        );
        assert!(!prompt.contains("Body stays out"));
        assert!(!prompt.contains("Another body"));
        match text(&run.request, "fixtureAction") {
            "fail" => bail!("Fixture provider rejected the request"),
            "cancel" => {
                cancel(run);
                bail!("Fixture provider stopped");
            }
            "change-head" | "change-session" => {
                let change_head = run.request["fixtureAction"] == "change-head";
                let state = state.clone();
                crate::store::blocking(move || {
                    state.host.storage.update("diff:test", |entry| {
                        let mut entry = entry.unwrap();
                        if change_head {
                            entry["headSha"] = json!("new-head");
                        } else {
                            entry["llmSession"] =
                                json!({"messages":[],"chatStartIndex":0,"archive":[]});
                        }
                        Ok(entry)
                    })
                })
                .await?;
                bail!("Fixture provider rejected the request");
            }
            _ => {}
        }
        if run.request["kind"] == "analyze" {
            assert!(matches!(preparation, StructuredAnswerPreparation::Grouping));
            Ok((
                json!({"overallSummary":"Two ordered commits.","groups":[]}).to_string(),
                json!({}),
            ))
        } else {
            let conversation = prompt
                .split_once("\nConversation: ")
                .unwrap()
                .1
                .split_once("\nAnswer the last question")
                .unwrap()
                .0;
            let messages: Vec<Value> = serde_json::from_str(conversation).unwrap();
            let last = messages.last().unwrap();
            assert_eq!(last["role"], "user");
            assert!(!messages
                .iter()
                .any(|message| message["stopReason"] == "error"));
            Ok((format!("Answer to {}", text(last, "content")), json!({})))
        }
    })
}

fn assert_failed_turn(entry: &Value, question: &str, count: usize) {
    let messages = entry["llmSession"]["messages"].as_array().unwrap();
    assert_eq!(messages.len(), count);
    assert_eq!(messages[count - 2]["role"], "user");
    assert_eq!(messages[count - 2]["content"], question);
    assert_eq!(messages[count - 1]["role"], "assistant");
    assert_eq!(messages[count - 1]["content"], json!([]));
    assert_eq!(messages[count - 1]["stopReason"], "error");
    assert_eq!(
        messages[count - 1]["errorMessage"],
        "Fixture provider rejected the request"
    );
    assert!(messages[count - 1]["timestamp"].is_number());
    assert!(entry["llmSession"].get("suggestions").is_none());
}

#[tokio::test]
async fn failed_chat_first_and_later_questions_survive_reload_and_retry() {
    let fixture = Fixture::new(None);
    let error = fixture
        .perform(json!({"kind":"chat","text":"First question?","fixtureAction":"fail"}))
        .await
        .unwrap_err();
    assert_eq!(error.to_string(), "Fixture provider rejected the request");
    let first = fixture.entry();
    assert_failed_turn(&first, "First question?", 2);
    // A repeated failure replaces the error turn without adding another user message.
    fixture
        .perform(json!({"kind":"chat","retry":true,"fixtureAction":"fail"}))
        .await
        .unwrap_err();
    assert_failed_turn(&fixture.entry(), "First question?", 2);
    let (answer, _) = fixture
        .perform(json!({"kind":"chat","retry":true}))
        .await
        .unwrap();
    assert_eq!(answer, "Answer to First question?");
    let saved = fixture.entry();
    assert_eq!(saved["llmSession"]["messages"].as_array().unwrap().len(), 2);
    assert_eq!(
        saved["llmSession"]["messages"][0],
        first["llmSession"]["messages"][0]
    );
    assert!(saved["llmSession"]["messages"][1]
        .get("stopReason")
        .is_none());
    fixture
        .perform(json!({"kind":"chat","text":"Later question?","fixtureAction":"fail"}))
        .await
        .unwrap_err();
    let later = fixture.entry();
    assert_failed_turn(&later, "Later question?", 4);
    assert_eq!(
        later["llmSession"]["messages"][0],
        saved["llmSession"]["messages"][0]
    );
    assert_eq!(
        later["llmSession"]["messages"][1],
        saved["llmSession"]["messages"][1]
    );
    let (answer, _) = fixture
        .perform(json!({"kind":"chat","retry":true}))
        .await
        .unwrap();
    assert_eq!(answer, "Answer to Later question?");
    assert_eq!(
        fixture.entry()["llmSession"]["messages"]
            .as_array()
            .unwrap()
            .len(),
        4
    );
    fixture.close().await;
}

#[tokio::test]
async fn failed_chat_preserves_analysis_archive_and_session_boundaries() {
    let session = json!({"messages":[{"role":"assistant","content":"Saved analysis"}],"chatStartIndex":1,
        "archive":[{"id":"earlier","messages":[{"role":"user","content":"Earlier chat"}]}],"suggestions":["Old?"]});
    let fixture = Fixture::new(Some(session.clone()));
    fixture
        .perform(json!({"kind":"chat","text":"First chat question?","fixtureAction":"fail"}))
        .await
        .unwrap_err();
    let saved = fixture.entry();
    assert_failed_turn(&saved, "First chat question?", 3);
    assert_eq!(saved["llmSession"]["messages"][0], session["messages"][0]);
    assert_eq!(saved["llmSession"]["chatStartIndex"], 1);
    assert_eq!(saved["llmSession"]["archive"], session["archive"]);
    let (answer, _) = fixture
        .perform(json!({"kind":"chat","retry":true}))
        .await
        .unwrap();
    assert_eq!(answer, "Answer to First chat question?");
    assert_eq!(
        fixture.entry()["llmSession"]["messages"]
            .as_array()
            .unwrap()
            .len(),
        3
    );
    fixture.close().await;
}

#[tokio::test]
async fn failed_chat_cancellation_and_stale_head_or_session_never_overwrite() {
    for action in ["cancel", "change-head", "change-session"] {
        let fixture = Fixture::new(None);
        let before = fixture.entry();
        let error = fixture
            .perform(json!({"kind":"chat","text":"Never overwrite","fixtureAction":action}))
            .await
            .unwrap_err();
        let after = fixture.entry();
        match action {
            "cancel" => {
                assert_eq!(after, before);
                assert_eq!(error.to_string(), "Fixture provider stopped");
            }
            "change-head" => {
                let mut expected = before;
                expected["headSha"] = json!("new-head");
                assert_eq!(after, expected);
                assert!(error.to_string().contains("pull request changed"));
            }
            _ => {
                let mut expected = before;
                expected["llmSession"] = json!({"messages":[],"chatStartIndex":0,"archive":[]});
                assert_eq!(after, expected);
                assert!(error.to_string().contains("conversation changed"));
            }
        }
        fixture.close().await;
    }
}

#[tokio::test]
async fn failed_chat_queued_session_write_observes_cancellation() {
    let fixture = Fixture::new(None);
    let before = fixture.entry();
    let run = Arc::new(super::tests::preparation_run(
        json!({"kind":"chat","key":"test"}),
    ));
    let held = crate::store::test_support::HeldLock::storage(&fixture.state.host.storage);
    let mut write = Box::pin(save_chat_session(
        &fixture.state,
        &run,
        &before,
        json!({"messages":[{"role":"user","content":"Late question"},{"role":"assistant","content":[],"stopReason":"error","errorMessage":"Late failure"}],"chatStartIndex":0}),
    ));
    assert!(futures_util::poll!(&mut write).is_pending());
    cancel(&run);
    drop(held);
    let error = tokio::time::timeout(Duration::from_secs(3), &mut write)
        .await
        .unwrap()
        .unwrap_err();
    assert_eq!(error.to_string(), "Stopped.");
    drop(write);
    assert_eq!(fixture.entry(), before);
    fixture.close().await;
}

#[tokio::test]
async fn shared_commit_context_reaches_analysis_and_unanalysed_chat() {
    // The injected provider asserts both ordered headlines on the actual prompt.
    for kind in ["analyze", "chat"] {
        let fixture = Fixture::new(None);
        fixture
            .perform(json!({"kind":kind,"text":"Explain the commit sequence"}))
            .await
            .unwrap();
        fixture.close().await;
    }
}

#[test]
fn shared_commit_context_is_bounded_and_keeps_only_subjects() {
    for commits in [
        json!([]),
        json!([{"sha":"one","message":"Subject"}]),
        Value::Null,
    ] {
        assert_eq!(commit_heading(&json!({"commits":commits})), "");
    }
    let commits: Vec<_> = (0..1000).map(|i| json!({"sha":format!("{i:07x}abcdef"),"message":format!("{}\nBody is excluded", "🦀".repeat(1000))})).collect();
    let heading = commit_heading(&json!({"commits":commits}));
    assert!(heading.len() <= COMMIT_PROMPT_LIMIT);
    assert!(heading.contains("0000000 "));
    assert!(heading.contains("0000001 "));
    assert!(heading.contains("more commits omitted]"));
    assert!(heading.contains('…'));
    assert!(!heading.contains("Body is excluded"));
}

#[tokio::test]
async fn failed_turn_persists_latest_buffered_activity_text() {
    let fixture = Fixture::new(None);
    let run = super::tests::preparation_run(json!({"kind":"inbox","key":"test","question":"Why?"}));
    prepare(&fixture.state, &run).unwrap();
    record(&run, "answer", "text", "Writing", Some("old"), "running");
    record_patch(&run, json!({"id":"answer","text":"last visible progress"}));
    failed(&fixture.state, &run, "Fixture failure", false).unwrap();
    let saved = fixture
        .state
        .host
        .storage
        .get("inbox-chat")
        .unwrap()
        .unwrap();
    assert_eq!(
        saved["turns"][0]["activities"][0]["text"],
        "last visible progress"
    );
    assert!(run.publication.lock().unwrap().timer.is_none());
    fixture.close().await;
}

#[test]
fn chat_dispatch_future_does_not_embed_provider_or_tour_state_in_callers() {
    let fixture = Fixture::new(None);
    let run = Arc::new(super::tests::preparation_run(
        json!({"kind":"chat","key":"test"}),
    ));
    let native = perform(&fixture.state, &run);
    let offline = perform_with_provider(&fixture.state, &run, fixture_provider);
    // Keep both the production entry and test seam small before the first poll.
    assert!(std::mem::size_of_val(&native) <= 64);
    assert!(std::mem::size_of_val(&offline) <= 64);
    drop((native, offline));
    std::fs::remove_dir_all(fixture.root).unwrap();
}
