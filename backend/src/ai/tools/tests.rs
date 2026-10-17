use super::*;
#[tokio::test]
async fn posted_live_mcp_discovers_only_captured_diff_paths_without_a_checkout() {
    let root = std::env::temp_dir().join(format!("pulls-posted-tools-{}", uuid::Uuid::new_v4()));
    let state = AppState::from_host(crate::host::ServerHost::new(
        crate::store::Store::new(root.clone()).unwrap(),
        None,
        None,
    ))
    .unwrap();
    let mut ctx = json!({"ref":{"kind":"posted","id":"test"},"files":[
        {"path":"src/lib.rs","patch":"@@ -1 +1 @@\n-old\n+new\n"},
        {"path":"Cargo.lock","patch":"@@ -1 +1 @@\n-old\n+new\n"},
        {"path":"src/lib.rs"}
    ]});
    assert!(state.host.cwd.is_none());
    assert!(ctx["headSha"].is_null());
    let listed = mcp(Some(&state), &ctx, json!({"id":1,"method":"tools/list"})).await;
    // Pi's adapter consumes the same registry that the live MCP endpoint publishes.
    assert_eq!(listed["result"]["tools"], specs_for(&ctx));
    let names: Vec<_> = listed["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| text(tool, "name"))
        .collect();
    assert_eq!(
        names,
        ["read_file_diff", "read_hunks", "find_files", "search_diff"]
    );
    assert!(listed["result"]["tools"][2]["description"]
        .as_str()
        .unwrap()
        .contains("captured diff"));
    for (arguments, expected) in [
        (json!({}), "Cargo.lock\nsrc/lib.rs\n"),
        (json!({"pattern":"SRC/.*\\.RS$"}), "src/lib.rs\n"),
        (json!({"pattern":"missing"}), ""),
    ] {
        let response = mcp(
            Some(&state),
            &ctx,
            json!({"id":2,"method":"tools/call","params":{"name":"find_files","arguments":arguments}}),
        )
        .await;
        assert_ne!(response["result"]["isError"], true);
        assert_eq!(response["result"]["content"][0]["text"], expected);
    }
    let diff = call(
        Some(&state),
        &ctx,
        "read_hunks",
        &json!({"hunkIds":["src/lib.rs#0","Cargo.lock#0"]}),
    )
    .await
    .unwrap();
    assert!(diff.contains("+    1 | new"));
    assert!(!diff.contains("read_source"));
    ctx["triage"] = json!({"files":{"src/lib.rs":{"priority":"p1"}}});
    assert_eq!(specs_for(&ctx)[0]["name"], "triage_files");
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn posted_tools_deny_source_and_discussion_even_with_poisoned_snapshots() {
    let root = std::env::temp_dir().join(format!("pulls-posted-denials-{}", uuid::Uuid::new_v4()));
    let state = AppState::from_host(crate::host::ServerHost::new(
        crate::store::Store::new(root.clone()).unwrap(),
        None,
        None,
    ))
    .unwrap();
    for poison in [false, true] {
        let mut ctx = json!({"ref":{"kind":"posted","id":"test"},"files":[{"path":"changed.rs"}]});
        if poison {
            ctx["headSha"] = json!("abc123");
            ctx["sourceSnapshots"] =
                json!({"changed.rs":"must stay hidden","outside.rs":"must stay hidden"});
            ctx["discussionSnapshot"] = json!([{"body":"must stay hidden"}]);
            ctx["number"] = json!(42);
            ctx["owner"] = json!("owner");
            ctx["repo"] = json!("repo");
        }
        for live in [None, Some(state.as_ref())] {
            let listed = mcp(live, &ctx, json!({"id":1,"method":"tools/list"})).await;
            assert_eq!(listed["result"]["tools"], specs_for(&ctx));
            assert!(listed["result"]["tools"]
                .as_array()
                .unwrap()
                .iter()
                .all(|tool| !repository_tool(text(tool, "name"))));
            let found = call(live, &ctx, "find_files", &json!({})).await.unwrap();
            assert!(found.starts_with("changed.rs\n"));
            assert!(!found.contains("outside.rs"));
            for (name, arguments) in [
                ("read_source", json!({"path":"changed.rs"})),
                ("search_source", json!({"pattern":"hidden"})),
                ("read_discussion", json!({})),
            ] {
                let error = call(live, &ctx, name, &arguments).await.unwrap_err();
                assert!(error
                    .to_string()
                    .contains("Posted diffs have no repository"));
                let response = mcp(
                    live,
                    &ctx,
                    json!({"id":2,"method":"tools/call","params":{"name":name,"arguments":arguments}}),
                )
                .await;
                assert_eq!(response["result"]["isError"], true);
                assert!(!response.to_string().contains("must stay hidden"));
            }
        }
    }
    drop(state);
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn offline_mcp_reads_only_context() {
    let ctx = json!({"files":[{"path":"a.rs","patch":"@@ -1,2 +1,2 @@\n old\n-gone\n+new\n"}],"sourceSnapshots":{"a.rs":"old\nnew\n"}});
    let diff = call(None, &ctx, "read_hunks", &json!({"hunkIds":["a.rs#0"]}))
        .await
        .unwrap();
    assert!(diff.contains("a.rs#0"));
    assert!(diff.contains("+    2 | new"));
    let source = call(
        None,
        &ctx,
        "read_source",
        &json!({"path":"a.rs","startLine":2}),
    )
    .await
    .unwrap();
    assert_eq!(source, "    2 | new\n");
    assert!(
        call(None, &ctx, "read_source", &json!({"path":"../secret"}))
            .await
            .is_err()
    );
    assert!(call(None, &ctx, "read_source", &json!({"path":"missing"}))
        .await
        .is_err());
}
#[tokio::test]
async fn diff_selections_are_deduplicated_in_first_seen_order() {
    let ctx = json!({"files":[
        {"path":"a.rs","patch":"@@ -1 +1 @@\n+first\n@@ -9 +9 @@\n+second\n"},
        {"path":"b.rs","hunks":[{"oldStart":3,"newStart":4,"patch":"+other\n"}]}
    ]});
    for (tool, field, first, second) in [
        ("read_file_diff", "paths", "b.rs", "a.rs"),
        ("read_hunks", "hunkIds", "b.rs#0", "a.rs#0"),
    ] {
        let mut wanted = vec![first; 20_000];
        wanted.extend([second, first, second]);
        let result = call(None, &ctx, tool, &json!({(field):wanted}))
            .await
            .unwrap();
        assert_eq!(result.matches("[b.rs#0]").count(), 1);
        assert_eq!(result.matches("[a.rs#0]").count(), 1);
        assert!(result.find("[b.rs#0]") < result.find("[a.rs#0]"));
        assert_eq!(result.contains("[a.rs#1]"), tool == "read_file_diff");
        assert!(!result.contains(TRUNCATED));
    }
}
#[tokio::test]
async fn diff_tools_stop_at_the_shared_byte_budget() {
    let patch = format!("+{}\n", "x".repeat(60_000));
    let ctx = json!({"files":[
        {"path":"a.rs","hunks":[{"header":"@@ -1 +1 @@","patch":patch}]},
        {"path":"b.rs","patch":format!("@@ -1 +1 @@\n+{}\n", "é".repeat(40_000))},
        {"path":"never.rs","patch":"@@ -1 +1 @@\n+never rendered\n"}
    ]});
    for (tool, args) in [
        (
            "read_file_diff",
            json!({"paths":["a.rs","b.rs","never.rs","a.rs"]}),
        ),
        (
            "read_hunks",
            json!({"hunkIds":["a.rs#0","b.rs#0","never.rs#0","a.rs#0"]}),
        ),
        ("search_diff", json!({"pattern":"."})),
    ] {
        let result = call(None, &ctx, tool, &args).await.unwrap();
        assert!(result.len() <= 120_000);
        if tool == "search_diff" {
            assert!(result.ends_with(TRUNCATED));
        } else {
            assert!(result.contains("[Output continues;"));
        }
        assert_eq!(result.matches("[a.rs#0]").count(), 1);
        assert_eq!(result.matches("[b.rs#0]").count(), 1);
        assert!(!result.contains("never.rs"));
    }
}
#[tokio::test]
async fn diff_search_bounds_single_lines_and_match_notice() {
    let ctx = json!({"files":[{"path":"a.rs","patch":format!("@@ -1 +1 @@\n+{}\n", "🦀".repeat(40_000))}]});
    let result = call(None, &ctx, "search_diff", &json!({"pattern":"."}))
        .await
        .unwrap();
    assert!(result.len() <= 120_000);
    assert!(result.ends_with(TRUNCATED));
    for patch in [
        "+hit\n".repeat(201),
        format!(
            "{}+{}\n+never\n",
            format!("+hit{}\n", "x".repeat(577)).repeat(199),
            "hit".to_owned() + &"x".repeat(677)
        ),
    ] {
        let ctx = json!({"files":[{"path":"a.rs","patch":format!("@@ -1 +1 @@\n{patch}")}]});
        let result = call(None, &ctx, "search_diff", &json!({"pattern":"hit"}))
            .await
            .unwrap();
        assert!(result.len() <= 120_000);
        assert!(result.ends_with(TRUNCATED) || result.contains("Stopped at 200 matches"));
        assert!(!result.contains("a.rs:201 "));
    }
}
#[test]
fn bounded_writer_does_not_allocate_the_formatted_input() {
    let mut out = BoundedOutput::new(120_000);
    let text = "🦀".repeat(40_000);
    assert!(writeln!(out, "header {text}").is_err());
    assert!(out.text.len() <= 120_000);
    assert_eq!(out.text.capacity(), 120_000);
    assert!(out.write_str("must not be appended").is_err());
    let result = out.finish();
    assert!(result.len() <= 120_000);
    assert!(result.ends_with(TRUNCATED));
    assert!(!result.contains("must not"));
}
#[tokio::test]
async fn source_search_covers_unchanged_snapshots_and_reports_missing_reads() {
    let ctx = json!({"files":[{"path":"missing.rs"}],"sourceSnapshots":{"src/changed.rs":"no match","src/callers.rs":"call_target();\nCALL_TARGET();","docs/readme.md":"call_target"}});
    let result = call(
        None,
        &ctx,
        "search_source",
        &json!({"pattern":"call_target","path":"src/**/*.rs"}),
    )
    .await
    .unwrap();
    assert!(result.contains("src/callers.rs:1:call_target();"));
    assert!(result.contains("src/callers.rs:2:CALL_TARGET();"));
    assert!(!result.contains("docs/readme.md"));
    assert!(result.contains("Offline search"));
    let incomplete = call(None, &ctx, "search_source", &json!({"pattern":"not_found"}))
        .await
        .unwrap();
    assert!(incomplete.contains("Incomplete search: 1 source files"));
    assert!(call(None, &ctx, "search_source", &json!({"pattern":"["}))
        .await
        .is_err());
    assert!(call(
        None,
        &ctx,
        "search_source",
        &json!({"pattern":"x","path":"../*"})
    )
    .await
    .is_err());
}
#[test]
fn source_search_keeps_incomplete_status_after_output_truncation() {
    let out = search_output(
        "x",
        vec!["🦀".repeat(40_000)],
        1,
        3,
        1,
        Some("time limit"),
        false,
    );
    assert!(out.len() <= 120_000);
    assert!(out.contains(TRUNCATED));
    assert!(out.contains("Search stopped: time limit; examined 1 of 3 files"));
    assert!(out.contains("Incomplete search: 1 source files"));
}
#[tokio::test]
async fn source_search_charges_binary_files_before_skipping_them() {
    let binary = "\0".repeat(17 * 1024 * 1024);
    let ctx = json!({"sourceSnapshots":{"a.bin":binary,"b.bin":binary,"z.rs":"needle"}});
    let result = call(None, &ctx, "search_source", &json!({"pattern":"needle"}))
        .await
        .unwrap();
    assert!(result.contains("Search stopped: 32 MiB source budget"));
    assert!(result.contains("examined 2 of 3 files"));
    assert!(!result.contains("z.rs:1:needle"));
}
#[tokio::test]
async fn triage_and_grouping_capabilities_follow_context() {
    let base = json!({"files":[{"path":"a.rs"},{"path":"b.rs"}]});
    let basic = specs_for(&base);
    assert!(!basic
        .as_array()
        .unwrap()
        .iter()
        .any(|s| s["name"] == "triage_files" || s["name"] == "update_grouping"));
    let mut ctx = base;
    ctx["triage"] = json!({"pull":{"kind":"fix","kindConfidence":0.8,"risk":2.2},"files":{"a.rs":{"priority":"p0","confidence":0.9}}});
    ctx["allowGroupingUpdate"] = json!(true);
    let specs = specs_for(&ctx);
    assert_eq!(specs[0]["name"], "triage_files");
    assert!(specs
        .as_array()
        .unwrap()
        .iter()
        .any(|s| s["name"] == "update_grouping"));
    let triage = call(None, &ctx, "triage_files", &json!({})).await.unwrap();
    assert!(triage.contains("medium risk"));
    assert!(triage.contains("a.rs (90% sure)"));
    assert!(triage.contains("Not rated: 1 files"));
    assert!(call(None, &ctx, "update_grouping", &json!({}))
        .await
        .is_err());
}
#[tokio::test]
async fn numbered_hunks_use_canonical_offsets_and_inbox_filters_renames() {
    let ctx = json!({"files":[{"path":"a.rs","hunks":[{"oldStart":41,"newStart":42,"patch":"-old\n+new\n"}]}]});
    let result = call(None, &ctx, "read_hunks", &json!({"hunkIds":"a.rs#0"}))
        .await
        .unwrap();
    assert!(result.contains("-   41 | old"));
    assert!(result.contains("+   42 | new"));
    assert!(call(None, &ctx, "read_hunks", &json!({"hunkIds":[false]}))
        .await
        .is_err());
    let inbox = json!({"pulls":[{"owner":"O","repo":"R","number":1}],"diffSnapshots":{"O/R#1":[{"filename":"new.rs","previous_filename":"old.rs"},{"filename":"other.rs"}]}});
    let selected = call(
        None,
        &inbox,
        "read_pull_request_diff",
        &json!({"pull":" o/r#1 ","paths":["old.rs"]}),
    )
    .await
    .unwrap();
    assert!(selected.contains("new.rs"));
    assert!(!selected.contains("other.rs"));
    assert!(call(None, &inbox, "unknown", &json!({"pull":"O/R#1"}))
        .await
        .is_err());
}

#[test]
fn tool_result_preserves_text_error_and_field_order() {
    for message in ["", "unicode 😀\n\"quoted\"\\path", "context: detail"] {
        let success = json!({"content":[{"type":"text","text":super::super::sanitize(message)}]});
        assert_eq!(
            tool_result(Ok(message.into())).to_string(),
            success.to_string()
        );
        let error = json!({"content":[{"type":"text","text":super::super::sanitize(message)}],"isError":true});
        assert_eq!(
            tool_result(Err(anyhow::anyhow!(message.to_owned()))).to_string(),
            error.to_string()
        );
    }
}

#[tokio::test]
async fn shared_diff_tools_preserve_pure_rename_origin() {
    let ctx = json!({"ref":{"kind":"posted"},"files":[
        {"path":"new.rs","previousPath":"old.rs","status":"renamed","hunks":[]}
    ]});
    let args = json!({"paths":["new.rs"]});
    let pi = call(None, &ctx, "read_file_diff", &args).await.unwrap();
    let mcp = mcp(
        None,
        &ctx,
        json!({"id":1,"method":"tools/call","params":{"name":"read_file_diff","arguments":args}}),
    )
    .await;
    assert_eq!(mcp["result"]["content"][0]["text"], pi);
    assert!(pi.starts_with("### new.rs (renamed from old.rs) [renamed]\n"));
    assert!(pi.contains("No textual diff"));
}

#[tokio::test]
async fn shared_diff_tools_continue_through_oversized_posted_hunks() {
    let patch = format!(
        "{}-{}\n+TAIL_SENTINEL\n",
        "-old 🦀 text\n+new é text\n".repeat(3000),
        "🦀".repeat(40_000)
    );
    let ctx = json!({"ref":{"kind":"posted"},"files":[
        {"path":"a.rs","status":"modified","hunks":[{"header":"@@ -1 +1 @@","patch":patch}]}
    ]});
    let h = hunks(&ctx["files"][0]).next().unwrap();
    let mut rendered = String::from("[a.rs#0] @@ -1 +1 @@\n");
    for (sign, n, line) in numbered(&h) {
        writeln!(rendered, "{sign}{n:5} | {}", line.get(1..).unwrap_or("")).unwrap();
    }
    for (tool, field, selection) in [
        ("read_hunks", "hunkIds", "a.rs#0"),
        ("read_file_diff", "paths", "a.rs"),
    ] {
        let listed = mcp(None, &ctx, json!({"id":1,"method":"tools/list"})).await;
        let tools = specs_for(&ctx);
        assert_eq!(listed["result"]["tools"], tools);
        let spec = tools
            .as_array()
            .unwrap()
            .iter()
            .find(|s| s["name"] == tool)
            .unwrap();
        assert_eq!(spec["inputSchema"]["properties"]["offset"]["minimum"], 0);
        let expected = if tool == "read_file_diff" {
            format!("### a.rs [modified]\n{rendered}")
        } else {
            rendered.clone()
        };
        let mut joined = String::new();
        let mut offset = 0;
        let mut pages = 0;
        loop {
            let args = json!({field:[selection],"offset":offset});
            let page = call(None, &ctx, tool, &args).await.unwrap();
            let via_mcp = mcp(
                None,
                &ctx,
                json!({"id":2,"method":"tools/call","params":{"name":tool,"arguments":args}}),
            )
            .await;
            assert_eq!(via_mcp["result"]["content"][0]["text"], page);
            assert!(page.len() <= DIFF_OUTPUT_LIMIT);
            pages += 1;
            if let Some((content, notice)) = page.rsplit_once("\n[Output continues; ") {
                joined.push_str(content);
                let next: usize = notice
                    .split_once("offset: ")
                    .unwrap()
                    .1
                    .trim_end_matches("]\n")
                    .parse()
                    .unwrap();
                assert_eq!(next, offset + content.len());
                assert!(next > offset);
                offset = next;
            } else {
                joined.push_str(&page);
                break;
            }
            assert!(pages < 8, "bounded fixture must make progress");
        }
        assert!(pages > 1);
        assert_eq!(joined, expected);
        assert!(joined.contains("-    1 | old 🦀 text"));
        assert!(joined.contains("TAIL_SENTINEL"));
        for offset in [json!(-1), json!(1.5), json!("1")] {
            assert!(call(
                None,
                &ctx,
                tool,
                &json!({field:[selection],"offset":offset})
            )
            .await
            .is_err());
        }
        let inside_unicode = expected.find('🦀').unwrap() + 1;
        assert!(call(
            None,
            &ctx,
            tool,
            &json!({field:[selection],"offset":inside_unicode})
        )
        .await
        .is_err());
    }
}
