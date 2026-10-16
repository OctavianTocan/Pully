use super::test_support::Fixture;
use super::*;

impl Fixture {
    fn private_links(&self) {
        self.write("private-cache/chat.rs", "call_target private state");
        std::fs::write(self.dir.join("secret"), "call_target outside").unwrap();
        for (target, name) in [
            (
                self.state
                    .host
                    .cwd
                    .as_ref()
                    .unwrap()
                    .join("private-cache/chat.rs"),
                "private-link.rs",
            ),
            (
                self.state.host.cwd.as_ref().unwrap().join(".git/config"),
                "git-link.rs",
            ),
            (self.dir.join("secret"), "outside-link.rs"),
        ] {
            std::os::unix::fs::symlink(target, self.state.host.cwd.as_ref().unwrap().join(name))
                .unwrap();
        }
    }
}
#[tokio::test]
async fn committed_batch_uses_captured_objects_and_rust_matching() {
    let f = Fixture::new().await;
    f.write("src/callers.rs", "éclair\nCALL_TARGET();\n");
    f.write("src/unchanged.rs", "call_target();\n");
    f.write("src/newline\nHEAD:secret.rs", "call_target newline\n");
    f.write("src/binary.rs", b"\0call_target\n");
    f.write("docs/readme.md", "call_target docs\n");
    f.private_links();
    let captured = f.commit().await;
    f.write("src/callers.rs", "new revision\n");
    f.commit().await;
    let context = json!({"ref":{"kind":"local"},"headSha":captured});
    let reply = crate::ai::mcp(&f.state, &context, json!({
        "jsonrpc":"2.0","id":1,"method":"tools/call",
        "params":{"name":"search_source","arguments":{"pattern":"(?:call_target|^\\p{L}+$)","path":"src/**/*.rs"}}
    })).await;
    assert_ne!(reply["result"]["isError"], true, "{reply}");
    let out = reply["result"]["content"][0]["text"].as_str().unwrap();
    assert!(out.contains("src/callers.rs:1:éclair"), "{out}");
    assert!(out.contains("src/callers.rs:2:CALL_TARGET();"));
    assert!(out.contains("src/unchanged.rs:1:call_target();"));
    assert!(out.contains("src/newline\nHEAD:secret.rs:1:call_target newline"));
    assert!(!out.contains("binary.rs") && !out.contains("docs/readme.md"));
    let re = regex::Regex::new("call_target").unwrap();
    let all = search(&f.state, &captured, &json!({"src/callers.rs":"call_target snapshot", "private-cache/chat.rs":"call_target forbidden"}), &re, None).await.unwrap();
    assert_eq!(all.total, 5);
    assert_eq!(all.unavailable, 0);
    assert!(all.stopped.is_none());
    assert!(all
        .hits
        .iter()
        .any(|hit| hit.contains("callers.rs:1:call_target snapshot")));
    assert!(!all
        .hits
        .iter()
        .any(|hit| hit.contains("private") || hit.contains("outside") || hit.contains("git-link")));
}
#[tokio::test]
async fn worktree_batch_excludes_private_paths_and_rejects_stale_revisions() {
    let f = Fixture::new().await;
    f.write("tracked.rs", "baseline");
    f.write("gone.rs", "baseline");
    f.commit().await;
    f.write("tracked.rs", "call_target tracked");
    f.write("new.rs", "call_target untracked");
    f.write("binary.rs", b"\0call_target");
    f.private_links();
    std::fs::remove_file(f.state.host.cwd.as_ref().unwrap().join("gone.rs")).unwrap();
    let captured = fetch_local(&f.state, "").await.unwrap()["head"]["sha"]
        .as_str()
        .unwrap()
        .to_owned();
    let re = regex::Regex::new("call_target").unwrap();
    let result = search(&f.state, &captured, &Value::Null, &re, None)
        .await
        .unwrap();
    assert_eq!(result.hits.len(), 2);
    assert_eq!(result.total, 4);
    assert_eq!(result.unavailable, 1);
    assert!(result.stopped.is_none());
    f.write("new.rs", "changed");
    let error = search(&f.state, &captured, &Value::Null, &re, None)
        .await
        .err()
        .unwrap();
    assert!(error.to_string().contains("working tree changed"));
}
#[tokio::test]
async fn batch_reports_missing_objects_and_stops_before_oversize_body() {
    let f = Fixture::new().await;
    f.write("a.rs", "needle");
    let sha = f.commit().await;
    let mut entries = search_inventory(&f.state.host.cwd.as_ref().unwrap(), &[], &sha, None)
        .await
        .unwrap();
    entries.insert(
        0,
        SearchEntry {
            path: "missing.rs".into(),
            blob: Some("0".repeat(40)),
        },
    );
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
    let re = regex::Regex::new("needle").unwrap();
    let mut result = SearchResult::default();
    search_blobs(
        &f.state.host.cwd.as_ref().unwrap(),
        &entries,
        &Value::Null,
        &re,
        deadline,
        &mut result,
    )
    .await
    .unwrap();
    assert_eq!(result.unavailable, 1);
    assert_eq!(result.hits, ["a.rs:1:needle"]);
    let mut limited = SearchResult {
        bytes: SOURCE_BYTE_LIMIT - 1,
        ..Default::default()
    };
    search_blobs(
        &f.state.host.cwd.as_ref().unwrap(),
        &entries[1..],
        &Value::Null,
        &re,
        deadline,
        &mut limited,
    )
    .await
    .unwrap();
    assert!(limited.hits.is_empty());
    assert!(limited.stopped.unwrap().contains("32 MiB"));
    assert_eq!(limited.bytes, SOURCE_BYTE_LIMIT - 1);
}
#[test]
fn batch_matching_obeys_hit_byte_and_time_caps() {
    let re = regex::Regex::new("needle").unwrap();
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(5);
    let mut result = SearchResult::default();
    result.content("a.rs", &"needle\n".repeat(201), &re, deadline);
    assert_eq!(result.hits.len(), 200);
    assert!(result.stopped.unwrap().starts_with("200 matches"));
    let mut result = SearchResult {
        bytes: SOURCE_BYTE_LIMIT - 1,
        ..Default::default()
    };
    result.content("a.rs", "needle", &re, deadline);
    assert!(result.hits.is_empty());
    assert!(result.stopped.unwrap().contains("32 MiB"));
    let mut result = SearchResult::default();
    result.content("a.rs", "needle", &re, tokio::time::Instant::now());
    assert!(result.hits.is_empty());
    assert_eq!(result.stopped, Some("time limit"));
}
