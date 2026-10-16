use super::*;

fn valid_diff() -> Value {
    json!({
        "ref":{"kind":"local","repo":"/repo","target":""},
        "title":"Working tree", "base":{"sha":"abc","ref":"main"},
        "head":{"sha":"worktree:123","ref":"working tree"},
        "files":[{"path":"a.rs","status":"modified","sha":"blob","additions":1,"deletions":0,
            "isBinary":false,"hunks":[{"header":"@@ -1 +1,2 @@","oldStart":1,"oldLines":1,
                "newStart":1,"newLines":2,"patch":" line\n+new\n"}]}]
    })
}
fn valid_task(kind: &str) -> Value {
    let mut request = json!({"kind":kind,"key":"key","model":{"engine":"pi"},"locale":"en"});
    match kind {
        "critique" => request["variant"] = json!("review"),
        "ask" => {
            request["threadId"] = json!("thread-1");
            request["selection"] =
                json!({"path":"a.rs","side":"additions","startLine":1,"endLine":2});
            request["question"] = json!("Why?");
        }
        "inbox" => {
            request["key"] = json!("inbox");
            request["question"] = json!("What changed?");
        }
        _ => {}
    }
    request
}
fn rejected(name: &str, input: Value) {
    assert_eq!(
        arguments(name, input).unwrap_err().to_string(),
        "That method received invalid arguments.",
        "{name}"
    );
}

#[test]
fn every_declared_argument_object_rejects_missing_or_scalar_inputs() {
    for name in [
        "source-key",
        "source-fetch",
        "source-fingerprint",
        "source-load-file",
        "storage-get-item",
        "storage-set-item",
        "storage-remove-item",
        "storage-get-keys",
        "storage-get-items",
        "storage-set-items",
        "github-request",
        "my-pulls",
        "my-pulls-cached",
        "lens-get",
        "llm-run",
        "ai-job-start",
        "ai-models",
        "ai-job-wait",
        "ai-task-wait",
        "ai-job-cancel",
        "ai-task-cancel",
        "ai-task-start",
        "ai-task-list",
        "ai-task-list-all",
        "ask-threads",
        "inbox-chat",
        "inbox-chat-clear",
        "analytics-summary",
        "ask-thread-close",
        "cache-diff",
    ] {
        for invalid in [Value::Null, json!([]), json!(7), json!("request")] {
            rejected(name, invalid);
        }
    }
}

#[test]
fn source_arguments_require_string_targets_but_keep_worktree_empty_string() {
    for name in ["source-key", "source-fetch", "source-fingerprint"] {
        for invalid in [
            json!({}),
            json!({"target":null}),
            json!({"target":42}),
            json!({"target":false}),
        ] {
            rejected(name, invalid);
        }
        assert_eq!(
            arguments(name, json!({"target":"","extra":true})).unwrap(),
            json!({"target":""})
        );
    }
    assert!(arguments(
        "source-load-file",
        json!({"target":"","path":"a.rs","sha":"worktree:123"})
    )
    .is_ok());
    rejected("source-load-file", json!({"target":"","path":"a.rs"}));
    rejected(
        "source-load-file",
        json!({"target":"","path":false,"sha":"abc"}),
    );
}

#[test]
fn valid_browser_and_phone_argument_fixtures_preserve_declared_fields() {
    for (name, args) in [
        ("storage-get-item", json!({"key":"a"})),
        (
            "storage-set-item",
            json!({"key":"a","value":"{}","expected":null}),
        ),
        ("storage-remove-item", json!({"key":"a","expected":"{}"})),
        ("storage-get-keys", json!({"base":""})),
        ("storage-get-items", json!({"keys":["a","b"]})),
        (
            "storage-set-items",
            json!({"items":[{"key":"a","value":"[]","expected":null}]}),
        ),
        ("my-pulls", json!({"state":"open"})),
        (
            "my-pulls-cached",
            json!({"state":"closed","classify":true,"revalidate":false}),
        ),
        ("lens-get", json!({"name":"security.audit-v2"})),
        ("ai-models", json!({"engine":"claude-code","refresh":false})),
        ("ai-job-wait", json!({"id":"id","after":0})),
        ("ai-task-wait", json!({"id":"id","after":1.5})),
        ("ai-job-cancel", json!({"id":"id"})),
        ("ai-task-cancel", json!({"id":"id"})),
        ("ai-task-list", json!({"key":"key"})),
        ("ai-task-list-all", json!({})),
        ("ask-threads", json!({"key":"key"})),
        ("ask-thread-close", json!({"key":"key","threadId":"thread"})),
        ("inbox-chat", json!({})),
        ("inbox-chat-clear", json!({})),
        (
            "analytics-summary",
            json!({"from":"2026-10-01","to":"2026-10-16","days":7}),
        ),
    ] {
        assert_eq!(arguments(name, args.clone()).unwrap(), args, "{name}");
    }
    for name in [
        "repo-info",
        "github-authenticated",
        "lens-list",
        "ai-job-list",
    ] {
        assert!(arguments(name, Value::Null).is_ok());
    }
}

#[test]
fn invalid_optional_fields_and_enums_do_not_fall_back_to_defaults() {
    for (name, args) in [
        ("my-pulls", json!({})),
        ("my-pulls-cached", json!({"state":7})),
        ("my-pulls-cached", json!({"state":"open","revalidate":null})),
        ("my-pulls-cached", json!({"state":"open","classify":"yes"})),
        ("ai-models", json!({"engine":"claude"})),
        ("ai-models", json!({"engine":"pi","refresh":0})),
        ("ai-task-wait", json!({"id":"id"})),
        ("ai-job-wait", json!({"id":"id","after":"0"})),
        ("lens-get", json!({"name":"../secret"})),
        ("analytics-summary", json!({"days":"7"})),
        ("analytics-summary", json!({"from":null})),
    ] {
        rejected(name, args);
    }
}

#[test]
fn github_proxy_validates_method_body_path_and_accept_before_network() {
    let input = json!({"path":"/repos/o/r/pulls/1","method":"PATCH","body":{"title":"x"},"accept":"application/vnd.github+json"});
    assert_eq!(arguments("github-request", input.clone()).unwrap(), input);
    for bad in [
        json!({"path":"https://evil.test"}),
        json!({"path":"//evil.test"}),
        json!({"path":"/user","method":null}),
        json!({"path":"/user","method":"TRACE"}),
        json!({"path":"/user","body":[]}),
        json!({"path":"/user","body":null}),
        json!({"path":"/user","accept":"text/html"}),
    ] {
        rejected("github-request", bad);
    }
}

#[test]
fn storage_batches_are_fully_validated_before_any_write() {
    rejected(
        "storage-set-items",
        json!({"items":[
        {"key":"a","value":"{}","expected":null},{"key":"b","value":null,"expected":null}]}),
    );
    rejected("storage-set-item", json!({"key":"a","value":"{}"}));
    rejected("storage-get-items", json!({"keys":["a",1]}));
}

#[test]
fn jobs_strip_untrusted_context_extensions_and_validate_nested_fields() {
    let input = json!({"engine":"codex","system":"","prompt":"","schema":null,"inbox":{"pulls":[]},
        "context":{"owner":"owner","repo":"repo","headSha":"123abcd","files":[{"path":"a","patch":"diff","private":true}],
            "ref":{"kind":"local"},"_rustMcpBridge":{"url":"http://evil.test"}}});
    let parsed = arguments("ai-job-start", input.clone()).unwrap();
    assert!(parsed.get("inbox").is_none());
    assert!(parsed["context"].get("ref").is_none());
    assert!(parsed["context"].get("_rustMcpBridge").is_none());
    assert!(parsed["context"]["files"][0].get("private").is_none());
    assert_eq!(parsed["schema"], Value::Null);
    for (pointer, value) in [
        ("/context/headSha", json!("not-a-sha")),
        ("/context/owner", json!("a/b")),
        ("/context/files/0/path", json!(7)),
        ("/context/files/0/patch", Value::Null),
    ] {
        let mut invalid = input.clone();
        *invalid.pointer_mut(pointer).unwrap() = value;
        rejected("ai-job-start", invalid);
    }
    let parsed = arguments("llm-run", input).unwrap();
    assert!(parsed.get("context").is_none());
    rejected("llm-run", json!({"engine":"pi","system":""}));
}

#[test]
fn all_task_variants_validate_and_questions_use_js_trim_and_utf16_limits() {
    for kind in [
        "analyze", "critique", "ask", "chat", "tour", "brief", "inbox",
    ] {
        let valid = valid_task(kind);
        assert!(arguments("ai-task-start", valid.clone()).is_ok(), "{kind}");
        let mut invalid = valid;
        invalid["model"]["engine"] = json!("unknown");
        rejected("ai-task-start", invalid);
    }
    let mut ask = valid_task("ask");
    ask["question"] = json!("\u{feff}  Why? \r\n");
    assert_eq!(
        arguments("ai-task-start", ask.clone()).unwrap()["question"],
        "Why?"
    );
    ask["question"] = json!(" \t\n");
    rejected("ai-task-start", ask);
    let mut inbox = valid_task("inbox");
    inbox["question"] = json!("😀".repeat(2000));
    assert!(arguments("ai-task-start", inbox.clone()).is_ok());
    inbox["question"] = json!("😀".repeat(2001));
    rejected("ai-task-start", inbox);
}

#[test]
fn task_variant_fields_are_not_silently_defaulted_or_carried_between_variants() {
    for (kind, field, value) in [
        ("ask", "threadId", json!("bad/thread")),
        ("ask", "selection", json!({})),
        ("inbox", "key", json!("different")),
        ("inbox", "skip", json!([1])),
        ("analyze", "then", json!(["ask"])),
        ("critique", "lens", json!("../lens")),
        ("chat", "retry", json!("true")),
        ("tour", "locale", Value::Null),
    ] {
        let mut input = valid_task(kind);
        input[field] = value;
        rejected("ai-task-start", input);
    }
    let mut request = valid_task("analyze");
    request["question"] = json!("ignored");
    request["expectedHeadSha"] = json!("untrusted");
    let parsed = arguments("ai-task-start", request).unwrap();
    assert!(parsed.get("question").is_none());
    assert!(parsed.get("expectedHeadSha").is_none());
}

#[test]
fn cache_defaults_and_optional_array_nulls_keep_browser_wire_behavior() {
    assert_eq!(
        arguments("cache-diff", json!({"method":"get","key":"key"})).unwrap(),
        json!({"method":"get","key":"key","values":[]})
    );
    for values in [
        json!([valid_diff(), "head", null]),
        json!([valid_diff(), "head", null, null]),
        json!([valid_diff(),"head",null,{"viewed":false}]),
    ] {
        assert!(arguments(
            "cache-diff",
            json!({"method":"putDiff","key":"key","values":values})
        )
        .is_ok());
    }
    for (method, values) in [
        ("setLlmSession", json!([null, "null"])),
        ("setSharedComment", json!([null])),
        ("setSharedComment", json!([])),
    ] {
        assert!(arguments(
            "cache-diff",
            json!({"method":method,"key":"key","values":values})
        )
        .is_ok());
    }
    for values in [
        json!([valid_diff(), "head"]),
        json!([valid_diff(),"head",null,{"viewed":1}]),
    ] {
        rejected(
            "cache-diff",
            json!({"method":"putDiff","key":"key","values":values}),
        );
    }
    rejected("cache-diff", json!({"method":"invalid","key":"key"}));
    rejected(
        "cache-diff",
        json!({"method":"get","key":"key","values":null}),
    );
}

#[test]
fn cache_nested_data_is_validated_and_loose_chat_messages_are_preserved() {
    let chat = json!({"messages":[{"role":"assistant","content":[{"type":"text","text":"Hello"}],"toolCalls":[]}],"chatStartIndex":0,"suggestions":["Why?"],
        "archive":[{"id":"old","archivedAt":1,"messages":[{"role":"user","content":"Question"}]}],"discard":true});
    let parsed = arguments(
        "cache-diff",
        json!({"method":"setLlmSession","key":"key","values":[chat,"null"]}),
    )
    .unwrap();
    assert_eq!(
        parsed["values"][0]["messages"][0]["content"][0]["text"],
        "Hello"
    );
    assert!(parsed["values"][0].get("discard").is_none());
    let analysis = json!({"source":"llm","generatedAt":"now","schemaVersion":1,"groups":[{"key":"a","label":"A","filePaths":["a"],"children":[{"key":"b","label":"B","filePaths":[]}]}]});
    assert!(arguments(
        "cache-diff",
        json!({"method":"setAnalyzedResult","key":"key","values":["llm",analysis]})
    )
    .is_ok());
    let reviews = json!({"threads":[{"rootId":1,"path":"a","side":"additions","outdated":false,"pending":false,"comments":[{"id":1,"body":"Hi","createdAt":"now","pending":false}]}],"summaries":[]});
    assert!(arguments(
        "cache-diff",
        json!({"method":"setReviewData","key":"key","values":[reviews]})
    )
    .is_ok());
    for (method, values) in [
        ("setChangedSinceReviewed", json!([[42]])),
        (
            "setLlmSession",
            json!([{"messages":[{}],"chatStartIndex":0},"null"]),
        ),
        (
            "setLlmSession",
            json!([{"messages":[],"chatStartIndex":0,"archive":[{}]},"null"]),
        ),
        (
            "setAnalyzedResult",
            json!(["llm",{"source":"llm","generatedAt":"now","schemaVersion":1,"groups":[{}]}]),
        ),
        ("setReviewData", json!([{"threads":[{}],"summaries":[]}])),
        ("setReviewData", json!([{"threads":[],"summaries":[{}]}])),
        ("setSharedComment", json!([{"id":"1","url":"url"}])),
    ] {
        rejected(
            "cache-diff",
            json!({"method":method,"key":"key","values":values}),
        );
    }
}

#[test]
fn source_fetch_return_schema_normalizes_and_rejects_nested_corruption() {
    let mut value = valid_diff();
    value["extra"] = json!(true);
    value["files"][0]["internal"] = json!(true);
    let normalized = result("source-fetch", value.clone()).unwrap();
    assert!(normalized.get("extra").is_none());
    assert!(normalized["files"][0].get("internal").is_none());
    for (pointer, bad) in [
        ("/ref/target", json!(0)),
        ("/head/sha", Value::Null),
        ("/files/0/hunks/0/oldStart", json!("1")),
        ("/files/0/status", json!("unknown")),
    ] {
        let mut invalid = value.clone();
        *invalid.pointer_mut(pointer).unwrap() = bad;
        assert_eq!(
            result("source-fetch", invalid).unwrap_err().to_string(),
            "That method returned an invalid result."
        );
    }
    value["files"][0]["truncated"] = json!(false);
    assert!(result("source-fetch", value).is_err());
}

#[test]
fn declared_return_shapes_are_checked_without_inventing_trusted_schemas() {
    for (name, valid, invalid) in [
        ("source-key", json!("key"), Value::Null),
        ("source-fingerprint", json!("sha"), json!(7)),
        ("source-load-file", Value::Null, json!([])),
        ("lens-get", json!("instructions"), json!(false)),
        ("llm-run", json!("answer"), json!({})),
        ("ai-job-start", json!("id"), json!(false)),
        ("ai-task-start", json!("id"), json!(false)),
        ("ai-task-cancel", json!(true), json!(1)),
        ("ai-job-cancel", json!(false), Value::Null),
        ("storage-get-keys", json!(["a"]), json!([1])),
        ("storage-get-items", json!([null, "{}"]), json!([{}])),
        ("storage-get-item", Value::Null, json!({})),
        ("storage-set-item", Value::Null, json!(true)),
        ("inbox-chat-clear", Value::Null, json!(false)),
        ("ask-thread-close", Value::Null, json!(true)),
        (
            "github-request",
            json!({"status":200,"headers":{"content-type":"text/plain"},"body":"ok"}),
            json!({"status":200,"headers":{},"body":{}}),
        ),
    ] {
        assert!(result(name, valid).is_ok(), "{name}");
        assert!(result(name, invalid).is_err(), "{name}");
    }
    for name in [
        "cache-diff",
        "my-pulls",
        "ai-models",
        "ai-task-wait",
        "analytics-summary",
    ] {
        assert_eq!(
            result(name, json!({"extension":true})).unwrap(),
            json!({"extension":true})
        );
    }
}

#[test]
fn record_and_loose_object_keys_match_valibot_without_rewriting_unknown_data() {
    let payload: Value = serde_json::from_str(
        r#"{
        "safe":{"__proto__":"nested unknown stays","constructor":1,"prototype":2},
        "__proto__":{"polluted":true},"constructor":0,"prototype":false
    }"#,
    )
    .unwrap();
    let parsed = arguments("github-request", json!({"path":"/graphql","body":payload})).unwrap();
    assert_eq!(parsed["body"], json!({"safe":payload["safe"]}));
    let mut message = payload.clone();
    message["role"] = json!("assistant");
    let parsed = arguments(
        "cache-diff",
        json!({"method":"setLlmSession","key":"key",
        "values":[{"messages":[message],"chatStartIndex":0},"null"]}),
    )
    .unwrap();
    assert_eq!(
        parsed["values"][0]["messages"][0],
        json!({"safe":payload["safe"],"role":"assistant"})
    );
    let parsed = arguments(
        "llm-run",
        json!({"engine":"pi","system":"","prompt":"","schema":payload}),
    )
    .unwrap();
    assert_eq!(parsed["schema"], payload);
}
