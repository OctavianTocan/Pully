use super::*;
use std::{cell::RefCell, collections::VecDeque, future::Future, time::Duration};

const PR: &str = "/repos/owner/repo/pulls/7";
const BASE: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const HEAD: &str = "dddddddddddddddddddddddddddddddddddddddd";
const MERGE: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const JSON: &str = "application/vnd.github+json";
const DIFF: &str = "application/vnd.github.diff";

#[derive(Clone)]
struct Reply {
    path: String,
    accept: &'static str,
    code: u16,
    text: String,
}
impl Reply {
    fn json(path: impl Into<String>, value: Value) -> Self {
        Self {
            path: path.into(),
            accept: JSON,
            code: 200,
            text: value.to_string(),
        }
    }
    fn diff(path: impl Into<String>, code: u16, text: &str) -> Self {
        Self {
            path: path.into(),
            accept: DIFF,
            code,
            text: text.into(),
        }
    }
}

tokio::task_local! {
    static RESPONSES: RefCell<VecDeque<Reply>>;
}

// Scoped to the calling task: parallel fixtures cannot intercept each other's
// requests, and an unexpected request fails instead of reaching the network.
pub(super) fn response(
    method: &reqwest::Method,
    path: &str,
    body: Option<&Value>,
    accept: &str,
) -> Option<Result<(u16, Value, String)>> {
    RESPONSES
        .try_with(|replies| {
            assert_eq!(method, &reqwest::Method::GET);
            assert!(body.is_none());
            let reply = replies
                .borrow_mut()
                .pop_front()
                .expect("unexpected GitHub request");
            assert_eq!(path, reply.path);
            assert_eq!(accept, reply.accept);
            Ok((reply.code, json!({}), reply.text))
        })
        .ok()
}

async fn replay<T>(replies: Vec<Reply>, future: impl Future<Output = T>) -> T {
    RESPONSES
        .scope(RefCell::new(replies.into()), async {
            let result = crate::rt::timeout(Duration::from_secs(10), future)
                .await
                .expect("offline GitHub fixture timed out");
            RESPONSES
                .with(|replies| assert!(replies.borrow().is_empty(), "unused GitHub responses"));
            result
        })
        .await
}

struct Fixture {
    dir: std::path::PathBuf,
    state: Arc<AppState>,
}
impl Fixture {
    fn new() -> Self {
        let dir =
            std::env::temp_dir().join(format!("pulls-github-snapshot-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(dir.clone(), dir.clone()).unwrap();
        Self { dir, state }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn reference() -> Value {
    json!({"kind":"github-pr","owner":"owner","repo":"repo","number":"7"})
}
fn compare_path() -> String {
    format!("/repos/owner/repo/compare/{BASE}...{HEAD}")
}
fn metadata(files: usize, commits: usize) -> Value {
    json!({"title":"Fixture pull","body":"Description","html_url":"https://github.com/owner/repo/pull/7",
        "user":{"login":"fixture","avatar_url":"https://example.invalid/avatar"},
        "base":{"sha":BASE,"ref":"main"},"head":{"sha":HEAD,"ref":"feature"},
        "created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z",
        "state":"open","draft":true,"changed_files":files,"commits":commits})
}
fn api_file(path: &str) -> Value {
    json!({"filename":path,"status":"added","additions":1,"deletions":0,"sha":format!("blob:{path}"),"patch":"@@ -0,0 +1 @@\n+new"})
}
fn hunk(
    header: &str,
    old_start: u64,
    old_lines: u64,
    new_start: u64,
    new_lines: u64,
    text: &str,
) -> Value {
    json!({"header":header,"oldStart":old_start,"oldLines":old_lines,"newStart":new_start,"newLines":new_lines,"patch":text})
}
fn normalized_file(path: &str) -> Value {
    json!({"path":path,"status":"added","additions":1,"deletions":0,"sha":format!("blob:{path}"),"isBinary":false,
        "hunks":[hunk("@@ -0,0 +1 @@",0,0,1,1,"+new")]})
}
fn paginated(path: &str, values: &[Value], field: Option<&str>) -> Vec<Reply> {
    let mut replies = vec![];
    for page in 0..=values.len() / 100 {
        let start = page * 100;
        let slice = &values[start..values.len().min(start + 100)];
        let value = match field {
            Some(field) => {
                let mut value = json!({});
                value[field] = json!(slice);
                value
            }
            None => json!(slice),
        };
        replies.push(Reply::json(
            format!("{path}?per_page=100&page={}", page + 1),
            value,
        ));
    }
    replies
}
fn pr_replies(pr: &Value, files: &[Value], commits: &[Value], current: &Value) -> Vec<Reply> {
    let mut replies = vec![
        Reply::json(PR, pr.clone()),
        Reply::json(compare_path(), json!({"merge_base_commit":{"sha":MERGE}})),
    ];
    replies.extend(paginated(&format!("{PR}/files"), files, None));
    replies.extend(paginated(&format!("{PR}/commits"), commits, None));
    replies.push(Reply::json(PR, current.clone()));
    replies
}
async fn refused(reference: Value, replies: Vec<Reply>, expected: &str) {
    // A failed cold open creates nothing; a failed forced refresh preserves the
    // complete prior cache entry, including its last-viewed metadata.
    for seeded in [false, true] {
        let f = Fixture::new();
        let key = serialize_ref(&reference).unwrap();
        let before = seeded.then(|| {
            crate::review::put_diff(
                &f.state,
                &key,
                &json!({"ref":reference,"title":"Keep existing","head":{"sha":"old"},"files":[]}),
            )
            .unwrap()
        });
        let error = replay(
            replies.clone(),
            super::super::open(&f.state, &reference, &json!({"fresh":true})),
        )
        .await
        .unwrap_err();
        assert_eq!(error.to_string(), expected);
        assert_eq!(
            f.state.host.storage.get(&format!("diff:{key}")).unwrap(),
            before
        );
    }
}

#[tokio::test]
async fn cold_pr_load_paginates_files_and_commits_and_publishes_exact_snapshot() {
    let f = Fixture::new();
    let files: Vec<_> = (0..101)
        .map(|n| api_file(&format!("file-{n:03}.rs")))
        .collect();
    let commits: Vec<_> = (0..101).map(|n| json!({"sha":format!("{n:040x}"),"commit":{"message":format!("Commit {n}\n\nBody")}})).collect();
    let pr = metadata(files.len(), commits.len());
    let mut r = reference();
    r["number"] = json!(7);
    let opened = replay(
        pr_replies(&pr, &files, &commits, &pr),
        super::super::open(&f.state, &r, &json!({})),
    )
    .await
    .unwrap();
    let expected = json!({"ref":reference(),"title":"Fixture pull","label":"#7","description":"Description",
        "url":"https://github.com/owner/repo/pull/7","author":{"name":"fixture","avatarUrl":"https://example.invalid/avatar"},
        "base":{"sha":MERGE,"ref":"main"},"head":{"sha":HEAD,"ref":"feature"},
        "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z",
        "pullRequest":{"state":"draft","baseSha":BASE},
        "commits":(0..101).map(|n| json!({"sha":format!("{n:040x}"),"message":format!("Commit {n}\n\nBody")})).collect::<Vec<_>>(),
        "files":(0..101).map(|n| normalized_file(&format!("file-{n:03}.rs"))).collect::<Vec<_>>()});
    assert_eq!(opened["diff"], expected);
    assert_eq!(opened["key"], "github:owner/repo#7");
    let stored = f
        .state
        .host
        .storage
        .get("diff:github:owner/repo#7")
        .unwrap()
        .unwrap();
    assert_eq!(stored["diff"], expected);
    assert_eq!(stored["headSha"], HEAD);
}

#[tokio::test]
async fn moving_pr_head_or_base_refuses_publication() {
    let pr = metadata(0, 0);
    for moved in ["head", "base"] {
        let mut current = pr.clone();
        current[moved]["sha"] = json!("moved");
        refused(
            reference(),
            pr_replies(&pr, &[], &[], &current),
            "Pull request changed during loading; retry for a consistent snapshot",
        )
        .await;
    }
}

#[tokio::test]
async fn missing_pr_files_or_commits_refuses_publication() {
    let pr = metadata(1, 0);
    let replies = vec![
        Reply::json(PR, pr),
        Reply::json(compare_path(), json!({"merge_base_commit":{"sha":MERGE}})),
        Reply::json(format!("{PR}/files?per_page=100&page=1"), json!([])),
    ];
    refused(
        reference(),
        replies,
        "GitHub omitted files from this PR; refusing incomplete diff",
    )
    .await;
    let pr = metadata(0, 1);
    let mut replies = pr_replies(&pr, &[], &[], &pr);
    replies.pop();
    refused(
        reference(),
        replies,
        "GitHub omitted commits from this PR; refusing incomplete diff",
    )
    .await;
}

#[tokio::test]
async fn compare_resolves_refs_before_pagination_and_uses_merge_base() {
    let f = Fixture::new();
    let r = json!({"kind":"github-compare","owner":"owner","repo":"repo","base":"release/v1","head":"feature/v2"});
    let comparison = json!({"merge_base_commit":{"sha":MERGE},"html_url":"https://github.com/owner/repo/compare/release...feature","files":[api_file("new.rs")],"commits":[{"sha":HEAD,"commit":{"message":"Feature"}}]});
    let replies = vec![
        Reply::json(
            "/repos/owner/repo/commits/feature%2Fv2",
            json!({"sha":HEAD}),
        ),
        Reply::json(
            "/repos/owner/repo/commits/release%2Fv1",
            json!({"sha":BASE}),
        ),
        Reply::json(
            format!("{}?per_page=100&page=1", compare_path()),
            comparison.clone(),
        ),
        Reply::json(
            format!("{}?per_page=100&page=1", compare_path()),
            comparison,
        ),
    ];
    let actual = replay(replies, fetch(&f.state, &r)).await.unwrap();
    assert_eq!(
        actual,
        json!({"ref":r,"title":"release/v1...feature/v2","url":"https://github.com/owner/repo/compare/release...feature","base":{"sha":MERGE,"ref":"release/v1"},"head":{"sha":HEAD,"ref":"feature/v2"},"commits":[{"sha":HEAD,"message":"Feature"}],"files":[normalized_file("new.rs")]})
    );
}

#[tokio::test]
async fn compare_commit_and_pagination_caps_refuse_publication() {
    let r =
        json!({"kind":"github-compare","owner":"owner","repo":"repo","base":"base","head":"head"});
    let c = json!({"merge_base_commit":{"sha":MERGE},"files":vec![json!({});300],"commits":[]});
    let replies = vec![
        Reply::json("/repos/owner/repo/commits/head", json!({"sha":HEAD})),
        Reply::json("/repos/owner/repo/commits/base", json!({"sha":BASE})),
        Reply::json(format!("{}?per_page=100&page=1", compare_path()), c),
        Reply::json(
            format!("{}?per_page=100&page=1", compare_path()),
            json!({"commits":[]}),
        ),
    ];
    refused(
        r,
        replies,
        "GitHub compare reached its 300-file cap; refusing potentially incomplete diff",
    )
    .await;
    let r = json!({"kind":"github-commit","owner":"owner","repo":"repo","sha":"topic"});
    let mut replies = vec![Reply::json(
        "/repos/owner/repo/commits/topic",
        json!({"sha":HEAD,"parents":[{"sha":BASE}]}),
    )];
    replies.extend(paginated(
        &format!("/repos/owner/repo/commits/{HEAD}"),
        &vec![json!({}); 3000],
        Some("files"),
    ));
    refused(
        r,
        replies,
        "GitHub commit file cap reached; refusing potentially incomplete diff",
    )
    .await;
    let pr = metadata(10000, 0);
    let mut replies = vec![
        Reply::json(PR, pr),
        Reply::json(compare_path(), json!({"merge_base_commit":{"sha":MERGE}})),
    ];
    for page in 1..=100 {
        replies.push(Reply::json(
            format!("{PR}/files?per_page=100&page={page}"),
            json!(vec![json!({}); 100]),
        ));
    }
    refused(
        reference(),
        replies,
        "GitHub pagination exceeded safe limit; result is incomplete",
    )
    .await;
}

#[tokio::test]
async fn commit_load_resolves_revision_and_preserves_metadata() {
    let f = Fixture::new();
    let r = json!({"kind":"github-commit","owner":"owner","repo":"repo","sha":"topic"});
    let replies = vec![
        Reply::json(
            "/repos/owner/repo/commits/topic",
            json!({"sha":HEAD,"parents":[{"sha":BASE}],"html_url":"https://github.com/owner/repo/commit/head","commit":{"message":"Subject\n\nDetails","author":{"name":"Git author","date":"2026-10-01T00:00:00Z"}}}),
        ),
        Reply::json(
            format!("/repos/owner/repo/commits/{HEAD}?per_page=100&page=1"),
            json!({"files":[api_file("new.rs")]}),
        ),
    ];
    let actual = replay(replies, fetch(&f.state, &r)).await.unwrap();
    assert_eq!(
        actual,
        json!({"ref":r,"title":"Subject","label":"ddddddd","description":"Details","url":"https://github.com/owner/repo/commit/head","author":{"name":"Git author"},"createdAt":"2026-10-01T00:00:00Z","base":{"sha":BASE,"ref":"bbbbbbb"},"head":{"sha":HEAD,"ref":"ddddddd"},"commits":[{"sha":HEAD,"message":"Subject\n\nDetails"}],"files":[normalized_file("new.rs")]})
    );
}

fn contents(path: &str, sha: &str, text: &str) -> Reply {
    Reply::json(
        format!("/repos/owner/repo/contents/{path}?ref={sha}"),
        json!({"type":"file","size":text.len(),"encoding":"base64","content":base64::engine::general_purpose::STANDARD.encode(text)}),
    )
}

#[tokio::test]
async fn omitted_patches_recover_raw_and_content_or_remain_explicitly_truncated() {
    let f = Fixture::new();
    let files = vec![
        json!({"filename":"renamed.rs","previous_filename":"old.rs","status":"renamed","additions":1,"deletions":1,"sha":"raw-blob"}),
        json!({"filename":"content.rs","previous_filename":"before.rs","status":"renamed","additions":1,"deletions":1,"sha":"content-blob"}),
        json!({"filename":"binary.bin","status":"modified","additions":0,"deletions":0,"sha":"binary-blob"}),
        json!({"filename":"large.rs","status":"modified","additions":5001,"deletions":0,"sha":"large-blob"}),
        json!({"filename":"missing.rs","status":"modified","additions":1,"deletions":1,"sha":"missing-blob"}),
    ];
    let pr = metadata(files.len(), 0);
    let mut replies = pr_replies(&pr, &files, &[], &pr);
    replies.push(Reply::diff(compare_path(),200,"diff --git a/old.rs b/renamed.rs\nrename from old.rs\nrename to renamed.rs\n--- a/old.rs\n+++ b/renamed.rs\n@@ -1 +1 @@\n-old\n+new\n"));
    replies.extend([
        contents("before.rs", MERGE, "before\n"),
        contents("content.rs", HEAD, "after\n"),
    ]);
    let mut missing = contents("missing.rs", MERGE, "");
    missing.code = 404;
    replies.extend([missing, contents("missing.rs", HEAD, "new\n")]);
    let actual = replay(replies, fetch(&f.state, &reference()))
        .await
        .unwrap();
    assert_eq!(
        actual["files"],
        json!([
            {"path":"renamed.rs","previousPath":"old.rs","status":"renamed","additions":1,"deletions":1,"sha":"raw-blob","isBinary":false,"hunks":[hunk("@@ -1 +1 @@",1,1,1,1,"-old\n+new")]},
            {"path":"content.rs","previousPath":"before.rs","status":"renamed","additions":1,"deletions":1,"sha":"content-blob","isBinary":false,"hunks":[hunk("@@ -1,1 +1,1 @@",1,1,1,1,"+after\n-before")]},
            {"path":"binary.bin","status":"modified","additions":0,"deletions":0,"sha":"binary-blob","isBinary":true,"hunks":[]},
            {"path":"large.rs","status":"modified","additions":5001,"deletions":0,"sha":"large-blob","isBinary":false,"hunks":[],"truncated":true},
            {"path":"missing.rs","status":"modified","additions":1,"deletions":1,"sha":"missing-blob","isBinary":false,"hunks":[],"truncated":true}
        ])
    );
}

#[tokio::test]
async fn unavailable_raw_diff_uses_contents_and_refuses_unbounded_reconstruction() {
    for (old, new, expected) in [
        (
            String::new(),
            "new".into(),
            Some(hunk(
                "@@ -0,0 +1,1 @@",
                0,
                0,
                1,
                1,
                "+new\n\\ No newline at end of file",
            )),
        ),
        ("a\n".repeat(1500), "b\n".repeat(1500), None),
        ("a\0".into(), "b\0".into(), None),
    ] {
        let f = Fixture::new();
        let file = json!({"filename":"file.rs","status":"modified","additions":1,"deletions":1});
        let pr = metadata(1, 0);
        let mut replies = pr_replies(&pr, &[file], &[], &pr);
        replies.extend([
            Reply::diff(compare_path(), 503, "unavailable"),
            contents("file.rs", MERGE, &old),
            contents("file.rs", HEAD, &new),
        ]);
        let actual = replay(replies, fetch(&f.state, &reference()))
            .await
            .unwrap();
        let file = &actual["files"][0];
        if let Some(hunk) = expected {
            assert_eq!(file["hunks"], json!([hunk]));
            assert!(file.get("truncated").is_none());
        } else {
            assert_eq!(file["hunks"], json!([]));
            assert_eq!(file["truncated"], true);
        }
        assert_eq!(file["sha"], format!("{HEAD}:file.rs"));
        assert_eq!(file["isBinary"], false);
    }
}

#[tokio::test]
async fn offline_seam_does_not_bypass_fixed_origin_path_policy() {
    let f = Fixture::new();
    for path in [
        "https://example.invalid/repos/owner/repo",
        "//example.invalid/repos/owner/repo",
        "/admin",
    ] {
        let error = replay(
            vec![],
            raw(&f.state, reqwest::Method::GET, path, None, JSON),
        )
        .await
        .unwrap_err();
        assert_eq!(error.to_string(), "Unsupported GitHub API path");
    }
}
