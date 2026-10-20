use super::*;
fn pull() -> Pull {
    Pull {
        owner: "owner".into(),
        repo: "repo".into(),
        number: 1,
    }
}
#[test]
fn rewrites_mentions_from_root_but_not_code() {
    let source="[file](../../.github/workflows/ci.yml#L3-L9) [one](index.ts:12) [web](https://example.com)\n`[code](src/code.rs)`\n```rust\n[x](src/code.rs)\n```";
    let result = github_cites(source, &pull(), "abc123");
    assert!(
        result.contains("https://github.com/owner/repo/blob/abc123/.github/workflows/ci.yml#L3-L9")
    );
    assert!(result.contains("https://github.com/owner/repo/blob/abc123/index.ts#L12"));
    assert!(result.contains("[web](https://example.com)"));
    assert!(result.contains("`[code](src/code.rs)`"));
    assert!(result.contains("\n[x](src/code.rs)\n"));
}
#[test]
fn cite_paths_decode_and_fold_without_losing_dot_folders() {
    assert_eq!(
        cite_url("./.agents/a%20b.md#L1", &pull(), "head").unwrap(),
        "https://github.com/owner/repo/blob/head/.agents/a%20b.md#L1"
    );
    for target in [
        "https://a.test/x",
        "#anchor",
        "//host/file",
        "folder/",
        "..",
        "mailto:person@test",
    ] {
        assert!(cite_url(target, &pull(), "head").is_none(), "{target}");
    }
}
#[test]
fn critique_identity_and_every_selected_id_are_checked() {
    let result = json!({"headSha":"abc","engine":"codex","findings":[{"id":"f1","path":"a.rs","side":"additions","line":2,"title":"Fix","body":"Body"}]});
    let revision = hex::encode(Sha256::digest(serde_json::to_vec(&result).unwrap()));
    let entry = json!({"diff":{"ref":{"kind":"github-pr","owner":"owner","repo":"repo","number":"1"},"head":{"sha":"abc"}}});
    let stored = json!({"entries":[{"variant":"exact","result":result}]});
    let mut args = json!({"variant":"exact","revision":revision,"headSha":"abc","ids":["f1"]});
    assert!(critique_selection(&entry, &stored, &args).is_ok());
    args["ids"] = json!(["f1", "missing"]);
    assert!(critique_selection(&entry, &stored, &args).is_err());
    args["ids"] = json!(["f1"]);
    args["revision"] = json!("wrong");
    assert!(critique_selection(&entry, &stored, &args).is_err());
    args["revision"] = json!(revision);
    args["headSha"] = json!("new");
    assert!(critique_selection(&entry, &stored, &args).is_err());
}
#[test]
fn comments_are_threaded_and_pending_review_is_separate() {
    let comments = json!([{"id":1,"path":"a","side":"LEFT","line":3,"user":{"login":"a","avatar_url":"url"},"body":"one"},{"id":2,"in_reply_to_id":1,"path":"a","side":"LEFT","line":3,"body":"two"},{"id":3,"path":"old","line":null,"body":"old"}]);
    let reviews = json!([{"id":10,"state":"PENDING","node_id":"node","body":null},{"id":11,"state":"COMMENTED","body":""},{"id":12,"state":"APPROVED","body":"Looks good"}]);
    let data = normalize_reviews(
        list(&comments),
        list(&reviews),
        &[],
        &BTreeMap::from([(1, json!({"threadId":"thread","resolved":true}))]),
    );
    assert_eq!(list(&data["threads"]).len(), 2);
    assert_eq!(list(&data["threads"][0]["comments"]).len(), 2);
    assert_eq!(data["threads"][0]["side"], "deletions");
    assert_eq!(data["threads"][0]["resolved"], true);
    assert_eq!(data["threads"][1]["outdated"], true);
    assert_eq!(data["pendingReview"]["nodeId"], "node");
    assert_eq!(list(&data["summaries"]).len(), 1);
}
#[test]
fn mergeability_preserves_draft_conflict_and_admin_states() {
    let mut o = json!({"state":"open","mergeable":"mergeable","mergeStateStatus":"behind","viewer":{"canPush":true,"canMergeAsAdmin":false},"merge":{"methods":["merge"]}});
    assert_eq!(
        mergeability(&o),
        json!({"allowed":true,"ready":false,"blocker":"behind","warning":null})
    );
    o["viewer"]["canMergeAsAdmin"] = json!(true);
    assert_eq!(mergeability(&o)["warning"], "bypass");
    o["state"] = json!("draft");
    assert_eq!(mergeability(&o)["ready"], false);
    o["state"] = json!("closed");
    assert_eq!(mergeability(&o)["allowed"], false);
}
#[test]
fn native_stack_is_bottom_first_and_siblings_sorted() {
    let raw = json!({"number":9,"baseRefName":"main","entries":{"nodes":[{"position":2,"pullRequest":{"number":3,"title":"Three","state":"OPEN"}},{"position":1,"pullRequest":{"number":1,"title":"One","state":"OPEN"}},{"position":2,"pullRequest":{"number":2,"title":"Two","state":"OPEN"}}]}});
    let stack = native_stack(&raw, 2).unwrap();
    assert_eq!(stack["entries"][0]["number"], 1);
    assert_eq!(stack["entries"][1]["number"], 2);
    assert_eq!(stack["entries"][1]["current"], true);
}
#[test]
fn sharing_roundtrips_and_rejects_invalid_compression() {
    let analysis = json!({"headSha":"abc","result":{"overallSummary":"[a](src/a.rs#L1)","groups":[],"schemaVersion":1,"source":"llm","generatedAt":"2026-10-15T12:00:00Z"}});
    let body = shared_body(&pull(), "viewer", &analysis).unwrap();
    let decoded = parse_shared(&body).unwrap();
    assert!(decoded["result"].get("files").is_none());
    assert!(decoded["result"].get("children").is_none());
    assert_eq!(decoded["headSha"], "abc");
    assert!(text(&decoded["result"], "overallSummary")
        .contains("https://github.com/owner/repo/blob/abc/src/a.rs#L1"));
    assert!(parse_shared("<!-- pulls.review data -->\n```lz-string\ncompressed\n```").is_none());
    assert!(parse_shared("unrelated comment").is_none());
    let mut large = analysis.clone();
    large["result"]["overallSummary"] = json!("Unicode 😀 summary. ".repeat(5000));
    let body = shared_body(&pull(), "viewer", &large).unwrap();
    assert!(body.contains("```lz-string"));
    assert_eq!(
        parse_shared(&body).unwrap()["result"]["overallSummary"],
        large["result"]["overallSummary"]
    );
}
#[test]
fn suggestions_use_a_fence_longer_than_the_content() {
    let body = finding_body(&json!({"title":"Title","body":"Body","suggestion":"a ``` b"}));
    assert!(body.contains("````suggestion\na ``` b\n````"));
}
#[test]
fn pull_identifiers_cannot_escape_api_path() {
    for owner in ["../other", "user?x=1", "..", "user%2fother"] {
        assert!(Pull::from(&json!({"owner":owner,"repo":"repo","number":1})).is_err());
    }
    assert!(Pull::from(&json!({"owner":"owner","repo":"repo","number":1.5})).is_err());
}
#[test]
fn comment_mutations_are_bound_to_the_requested_pull() {
    assert!(comment_belongs_to_pull(
        &json!({"pull_request_url":"https://api.github.com/repos/OWNER/repo/pulls/1"}),
        &pull()
    ));
    for url in [
        "https://api.github.com/repos/owner/repo/pulls/2",
        "https://evil.test/repos/owner/repo/pulls/1",
        "https://api.github.com@evil.test/repos/owner/repo/pulls/1",
        "https://api.github.com/repos/owner/repo/pulls/1?other=2",
    ] {
        assert!(!comment_belongs_to_pull(
            &json!({"pull_request_url":url}),
            &pull()
        ));
    }
}
#[test]
fn draft_paths_and_pending_heads_fail_closed() {
    for path in ["/etc/passwd", "../file", "a/../file", "a\\b", "a\nfile"] {
        assert!(draft_target(&json!({"path":path,"line":1,"side":"additions"})).is_err());
    }
    assert!(
        draft_target(&json!({"path":".github/workflow.yml","line":1,"side":"additions"})).is_ok()
    );
    assert!(verify_pending_head(&json!({"commitId":"old"}), "new").is_err());
    assert!(verify_pending_head(&json!({"commitId":"new"}), "new").is_ok());
}
#[test]
fn malformed_percent_unicode_cannot_panic() {
    assert_eq!(decode_path("%é/path"), "%é/path");
    assert_eq!(decode_path("a%2Fb"), "a/b");
    assert!(cite_url("%é/file", &pull(), "head")
        .unwrap()
        .contains("%25%C3%A9/file"));
}
#[test]
fn duplicate_saved_finding_ids_cannot_hide_missing_selection() {
    let finding = json!({"id":"f1","path":"a.rs","side":"additions","line":2});
    let result = json!({"headSha":"abc","findings":[finding.clone(),finding]});
    let revision = hex::encode(Sha256::digest(serde_json::to_vec(&result).unwrap()));
    let entry = json!({"diff":{"ref":{"kind":"github-pr","owner":"owner","repo":"repo","number":"1"},"head":{"sha":"abc"}}});
    let stored = json!({"entries":[{"variant":"exact","result":result}]});
    let args =
        json!({"variant":"exact","revision":revision,"headSha":"abc","ids":["f1","missing"]});
    assert!(critique_selection(&entry, &stored, &args).is_err());
}

#[test]
fn citation_regexes_are_stable_across_repeated_and_concurrent_calls() {
    let input = "[one](src/a.rs:2) [two](./dir/b.rs#L3-L5) [web](https://example.test)";
    let expected = github_cites(input, &pull(), "head");
    std::thread::scope(|scope| {
        for _ in 0..4 {
            let expected = &expected;
            scope.spawn(move || {
                for _ in 0..8 {
                    assert_eq!(github_cites(input, &pull(), "head"), *expected);
                }
            });
        }
    });
}

#[test]
fn shared_parser_retains_absence_for_malformed_fences_and_values() {
    for payload in [
        "no fence",
        "```json\n{\n```",
        "```json\n{}\n```",
        "```other\n{}\n```",
    ] {
        let body = format!("{SHARED_MARKER}\n{payload}");
        for _ in 0..3 {
            assert!(parse_shared(&body).is_none());
        }
    }
}

#[test]
fn lifecycle_state_preserves_merges_closures_and_unknowns() {
    for (raw, expected) in [
        (json!({"state":"MERGED"}), "merged"),
        (json!({"state":"CLOSED","merged":true,"isDraft":true}), "merged"),
        (json!({"state":"CLOSED","mergedAt":"2026-10-20T12:00:00Z"}), "merged"),
        (json!({"state":"CLOSED","isDraft":true}), "closed"),
        (json!({"state":"OPEN","isDraft":true,"closedAt":"2026-10-20T12:00:00Z"}), "closed"),
        (json!({"state":"OPEN","isDraft":true}), "draft"),
        (json!({"state":"OPEN"}), "open"),
        (json!({"state":"NEW_STATE","isDraft":true}), "unknown"),
        (json!({}), "unknown"),
    ] {
        assert_eq!(pr_state(&raw), expected, "{raw}");
    }
}
