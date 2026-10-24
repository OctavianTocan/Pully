use super::test_support::Fixture;
use super::*;

struct Checkout {
    head: Vec<u8>,
    index: Vec<u8>,
    revision: String,
    status: String,
}
impl Checkout {
    async fn capture(f: &Fixture) -> Self {
        let root = f.state.host.cwd.as_ref().unwrap();
        Self {
            head: std::fs::read(root.join(".git/HEAD")).unwrap(),
            index: std::fs::read(root.join(".git/index")).unwrap(),
            revision: git(root, &["rev-parse", "HEAD"]).await.unwrap(),
            status: git(root, &["status", "--porcelain=v1", "-z"])
                .await
                .unwrap(),
        }
    }
    async fn assert_unchanged(&self, f: &Fixture) {
        let after = Self::capture(f).await;
        assert_eq!(after.head, self.head, "source loading changed HEAD");
        assert_eq!(
            after.index, self.index,
            "source loading changed the real index"
        );
        assert_eq!(after.revision, self.revision);
        assert_eq!(
            after.status, self.status,
            "source loading changed the working tree"
        );
    }
}

async fn dirty_checkout(f: &Fixture) {
    f.write("a.rs", "staged\n");
    git(f.state.host.cwd.as_ref().unwrap(), &["add", "a.rs"])
        .await
        .unwrap();
    f.write("a.rs", "unstaged\n");
    f.write("untracked.rs", "untracked\n");
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
async fn file(
    f: &Fixture,
    revision: &str,
    path: &str,
    status: &str,
    additions: u64,
    deletions: u64,
    hunk: Value,
) -> Value {
    let sha = git(
        f.state.host.cwd.as_ref().unwrap(),
        &["rev-parse", &format!("{revision}:{path}")],
    )
    .await
    .unwrap();
    json!({"path":path,"status":status,"sha":sha.trim(),"additions":additions,"deletions":deletions,"isBinary":false,"hunks":[hunk]})
}
async fn snapshot(f: &Fixture, target: &str) -> Value {
    crate::rt::timeout(
        std::time::Duration::from_secs(10),
        fetch_local(&f.state, target),
    )
    .await
    .expect("local snapshot fixture timed out")
    .unwrap()
}
fn expected(
    f: &Fixture,
    target: &str,
    title: &str,
    base: (&str, &str),
    head: (&str, &str),
    commits: Value,
    files: Vec<Value>,
) -> Value {
    json!({"ref":{"kind":"local","repo":std::fs::canonicalize(f.state.host.cwd.as_ref().unwrap()).unwrap().to_string_lossy(),"target":target},
        "title":title,"base":{"sha":base.0,"ref":base.1},"head":{"sha":head.0,"ref":head.1},"commits":commits,"files":files})
}

#[tokio::test]
async fn root_and_child_commit_snapshots_preserve_exact_revisions_hunks_and_checkout() {
    let f = Fixture::new().await;
    f.write("a.rs", "one\n");
    let root = f.commit_message("Root commit").await;
    f.write("a.rs", "two\n");
    let child = f.commit_message("Change a\n\nBecause it matters").await;
    dirty_checkout(&f).await;
    let before = Checkout::capture(&f).await;
    let root_file = file(
        &f,
        &root,
        "a.rs",
        "added",
        1,
        0,
        hunk("@@ -0,0 +1 @@", 0, 0, 1, 1, "+one"),
    )
    .await;
    assert_eq!(
        snapshot(&f, &root).await,
        expected(
            &f,
            &root,
            "Root commit",
            (EMPTY, &EMPTY[..7]),
            (&root, &root),
            json!([{"sha":root,"message":"Root commit"}]),
            vec![root_file]
        )
    );
    before.assert_unchanged(&f).await;
    let child_file = file(
        &f,
        &child,
        "a.rs",
        "modified",
        1,
        1,
        hunk("@@ -1 +1 @@", 1, 1, 1, 1, "-one\n+two"),
    )
    .await;
    for target in [child.as_str(), "HEAD"] {
        assert_eq!(
            snapshot(&f, target).await,
            expected(
                &f,
                target,
                "Change a",
                (&root, &root[..7]),
                (&child, target),
                json!([{"sha":child,"message":"Change a\n\nBecause it matters"}]),
                vec![child_file.clone()]
            )
        );
        before.assert_unchanged(&f).await;
    }
}

#[tokio::test]
async fn diverged_two_and_three_dot_ranges_and_empty_endpoints_preserve_checkout() {
    let f = Fixture::new().await;
    let repo = f.state.host.cwd.as_ref().unwrap();
    git(repo, &["symbolic-ref", "HEAD", "refs/heads/main"])
        .await
        .unwrap();
    f.write("a.rs", "one\n");
    let root = f.commit_message("Root commit").await;
    git(repo, &["switch", "-q", "-c", "feature"]).await.unwrap();
    f.write("feature.rs", "feature\n");
    let feature = f.commit_message("Feature first\n\nReason").await;
    f.write("second.rs", "second\n");
    let second = f.commit_message("Feature second").await;
    git(repo, &["switch", "-q", "main"]).await.unwrap();
    f.write("main.rs", "main\n");
    let main = f.commit_message("Main moves on").await;
    dirty_checkout(&f).await;
    let before = Checkout::capture(&f).await;
    let feature_files = vec![
        file(
            &f,
            &second,
            "feature.rs",
            "added",
            1,
            0,
            hunk("@@ -0,0 +1 @@", 0, 0, 1, 1, "+feature"),
        )
        .await,
        file(
            &f,
            &second,
            "second.rs",
            "added",
            1,
            0,
            hunk("@@ -0,0 +1 @@", 0, 0, 1, 1, "+second"),
        )
        .await,
    ];
    let main_added = file(
        &f,
        &main,
        "main.rs",
        "added",
        1,
        0,
        hunk("@@ -0,0 +1 @@", 0, 0, 1, 1, "+main"),
    )
    .await;
    let main_removed = file(
        &f,
        &main,
        "main.rs",
        "removed",
        0,
        1,
        hunk("@@ -1 +0,0 @@", 1, 1, 0, 0, "-main"),
    )
    .await;
    let feature_commits = json!([{"sha":feature,"message":"Feature first\n\nReason"},{"sha":second,"message":"Feature second"}]);
    for (target, base_ref) in [("main...feature", "main"), ("...feature", "HEAD")] {
        assert_eq!(
            snapshot(&f, target).await,
            expected(
                &f,
                target,
                target,
                (&root, base_ref),
                (&second, "feature"),
                feature_commits.clone(),
                feature_files.clone()
            )
        );
        before.assert_unchanged(&f).await;
    }
    let tree_files = vec![
        feature_files[0].clone(),
        main_removed,
        feature_files[1].clone(),
    ];
    for (target, base_ref) in [("main..feature", "main"), ("..feature", "HEAD")] {
        assert_eq!(
            snapshot(&f, target).await,
            expected(
                &f,
                target,
                target,
                (&main, base_ref),
                (&second, "feature"),
                feature_commits.clone(),
                tree_files.clone()
            )
        );
        before.assert_unchanged(&f).await;
    }
    let reverse_files = vec![
        file(
            &f,
            &second,
            "feature.rs",
            "removed",
            0,
            1,
            hunk("@@ -1 +0,0 @@", 1, 1, 0, 0, "-feature"),
        )
        .await,
        main_added.clone(),
        file(
            &f,
            &second,
            "second.rs",
            "removed",
            0,
            1,
            hunk("@@ -1 +0,0 @@", 1, 1, 0, 0, "-second"),
        )
        .await,
    ];
    for (target, base, files) in [
        ("feature..", second.as_str(), reverse_files),
        ("feature...", root.as_str(), vec![main_added]),
    ] {
        assert_eq!(
            snapshot(&f, target).await,
            expected(
                &f,
                target,
                target,
                (base, "feature"),
                (&main, "HEAD"),
                json!([{"sha":main,"message":"Main moves on"}]),
                files
            )
        );
        before.assert_unchanged(&f).await;
    }
    for target in ["..", "..."] {
        assert_eq!(
            snapshot(&f, target).await,
            expected(
                &f,
                target,
                target,
                (&main, "HEAD"),
                (&main, "HEAD"),
                json!([]),
                vec![]
            )
        );
        before.assert_unchanged(&f).await;
    }
}
