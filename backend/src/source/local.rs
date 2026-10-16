use super::*;
use std::path::{Component, Path};
use tokio::process::Command;
const SOURCE_BYTE_LIMIT: usize = 32 * 1024 * 1024;
fn git_command(root: &Path) -> Command {
    let mut command = Command::new("git");
    command
        .arg("-C")
        .arg(root)
        .args([
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.hooksPath=/dev/null",
        ])
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_OPTIONAL_LOCKS", "0")
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    command
}
async fn git_bytes(root: &Path, args: &[&str]) -> Result<Vec<u8>> {
    use tokio::io::AsyncReadExt;
    let mut child = git_command(root)
        .args(args)
        .spawn()
        .context("Could not run git")?;
    let output = child.stdout.take().context("Git output unavailable")?;
    let mut bytes = vec![];
    tokio::time::timeout(
        std::time::Duration::from_secs(60),
        output.take(32 * 1024 * 1024 + 1).read_to_end(&mut bytes),
    )
    .await
    .context("Git read timed out")??;
    if bytes.len() > 32 * 1024 * 1024 {
        let _ = child.kill().await;
        bail!("Git output exceeds 32 MiB limit")
    }
    let status = tokio::time::timeout(std::time::Duration::from_secs(60), child.wait())
        .await
        .context("Git operation timed out")??;
    ensure!(status.success(), "Git operation failed");
    Ok(bytes)
}
pub async fn git(root: &Path, args: &[&str]) -> Result<String> {
    if root.as_os_str().is_empty() {
        bail!(crate::host::NO_CHECKOUT);
    }
    Ok(String::from_utf8_lossy(&git_bytes(root, args).await?).into())
}
async fn root(state: &AppState) -> Result<std::path::PathBuf> {
    Ok(std::fs::canonicalize(
        git(state.host.checkout()?, &["rev-parse", "--show-toplevel"])
            .await?
            .trim(),
    )?)
}
async fn excluded_roots(state: &AppState, root: &Path) -> Result<Vec<std::path::PathBuf>> {
    let mut excluded = vec![
        state.host.storage.root().to_path_buf(),
        state.analytics.root().to_path_buf(),
    ];
    for flag in ["--absolute-git-dir", "--git-common-dir"] {
        let directory = git(root, &["rev-parse", flag]).await?;
        excluded.push(std::fs::canonicalize(root.join(directory.trim()))?);
    }
    Ok(excluded)
}
async fn rev(root: &Path, s: &str) -> Result<String> {
    ensure!(!s.starts_with('-') && !s.contains('\0'), "Invalid revision");
    Ok(git(
        root,
        &[
            "rev-parse",
            "--verify",
            "--end-of-options",
            &format!("{}^{{commit}}", if s.is_empty() { "HEAD" } else { s }),
        ],
    )
    .await?
    .trim()
    .into())
}
const EMPTY: &str = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
pub async fn fetch_local(state: &AppState, target: &str) -> Result<Value> {
    let root = root(state).await?;
    let excluded = excluded_roots(state, &root).await?;
    fetch_at(&root, &excluded, target).await
}
async fn fetch_at(root: &Path, excluded: &[std::path::PathBuf], target: &str) -> Result<Value> {
    let original_target = target;
    let target = target.trim();
    let (base, head, title, commits, br, hr): (
        String,
        Option<String>,
        String,
        Vec<Value>,
        String,
        String,
    ) = if target.is_empty() {
        (
            rev(&root, "HEAD").await.unwrap_or_else(|_| EMPTY.into()),
            None,
            "Working tree".into(),
            vec![],
            String::new(),
            "working tree".into(),
        )
    } else if let Some((a, b, merge)) = target
        .split_once("...")
        .map(|(a, b)| (a, b, true))
        .or_else(|| target.split_once("..").map(|(a, b)| (a, b, false)))
    {
        let mut base = rev(&root, a).await?;
        let head = rev(&root, b).await?;
        if merge {
            base = git(&root, &["merge-base", &base, &head])
                .await?
                .trim()
                .into()
        }
        let cs = log(&root, &[&format!("{base}..{head}")]).await?;
        (
            base,
            Some(head),
            target.to_string(),
            cs,
            if a.is_empty() { "HEAD" } else { a }.into(),
            if b.is_empty() { "HEAD" } else { b }.into(),
        )
    } else {
        let head = rev(&root, target).await?;
        let base = rev(&root, &format!("{head}^"))
            .await
            .unwrap_or_else(|_| EMPTY.into());
        let cs = log(&root, &["-1", &head]).await?;
        let title = cs
            .first()
            .and_then(|c| c["message"].as_str())
            .unwrap_or(target)
            .lines()
            .next()
            .unwrap_or(target)
            .to_string();
        (base, Some(head), title, cs, String::new(), target.into())
    };
    let mut args = vec![
        "-c",
        "core.quotePath=false",
        "diff",
        "--no-color",
        "--no-ext-diff",
        "--no-textconv",
        "--src-prefix=a/",
        "--dst-prefix=b/",
        "--full-index",
        "-M",
        &base,
    ];
    if let Some(h) = &head {
        args.push(h)
    }
    args.push("--");
    let mut text = git(&root, &args).await?;
    if head.is_none() {
        let untracked = git(&root, &["ls-files", "--others", "--exclude-standard", "-z"]).await?;
        let mut untracked_bytes = 0usize;
        for path in untracked.split('\0').filter(|p| !p.is_empty()) {
            tokio::task::yield_now().await;
            if let Some(bytes) = read_worktree_bytes(&root, path, &excluded)? {
                untracked_bytes += bytes.len();
                ensure!(
                    untracked_bytes <= SOURCE_BYTE_LIMIT,
                    "Working-tree source exceeds 32 MiB limit"
                );
                use sha2::{Digest, Sha256};
                let content_hash = hex::encode(Sha256::digest(&bytes));
                let content = String::from_utf8_lossy(&bytes);
                let binary = content.contains('\0');
                text.push_str(&format!(
                    "diff --git {} {}\nnew file mode 100644\nindex 0000000000000000000000000000000000000000..{content_hash}\n",
                    serde_json::to_string(&format!("a/{path}"))?,
                    serde_json::to_string(&format!("b/{path}"))?
                ));
                if binary {
                    text.push_str(&format!("Binary files /dev/null and b/{path} differ\n"))
                } else {
                    let lines: Vec<_> = content.split_terminator('\n').collect();
                    text.push_str(&format!(
                        "--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{} @@\n",
                        lines.len()
                    ));
                    for l in lines {
                        text.push('+');
                        text.push_str(l);
                        text.push('\n');
                    }
                    if !content.ends_with('\n') && !content.is_empty() {
                        text.push_str("\\ No newline at end of file\n")
                    }
                }
                ensure!(
                    text.len() <= SOURCE_BYTE_LIMIT,
                    "Working-tree diff exceeds 32 MiB limit"
                );
            }
        }
    }
    let head =
        head.unwrap_or_else(|| format!("worktree:{}", patch::hash(&format!("{base}\n{text}"))));
    Ok(
        json!({"ref":{"kind":"local","repo":root.to_string_lossy(),"target":original_target},"title":title,"base":{"sha":base,"ref":if br.is_empty(){base.chars().take(7).collect::<String>()}else{br}},"head":{"sha":head,"ref":hr},"commits":commits,"files":patch::parse_patch(&text).into_iter().filter(|f| !private_source_path(&root,&root.join(super::s(f,"path")),&excluded)).collect::<Vec<_>>()}),
    )
}
async fn log(root: &Path, args: &[&str]) -> Result<Vec<Value>> {
    let mut all = vec!["log", "--reverse", "--format=%H%x00%B%x1e"];
    all.extend_from_slice(args);
    all.push("--");
    Ok(git(root, &all)
        .await?
        .split('\x1e')
        .filter_map(|s| {
            s.trim()
                .split_once('\0')
                .map(|(sha, msg)| json!({"sha":sha,"message":msg.trim()}))
        })
        .collect())
}
#[cfg(test)]
pub fn read_worktree(root: &Path, path: &str) -> Result<Option<String>> {
    read_worktree_excluding(root, path, &[])
}
fn private_source_path(root: &Path, target: &Path, excluded: &[std::path::PathBuf]) -> bool {
    target.strip_prefix(root).ok().is_some_and(|p|p.components().any(|c|matches!(c,Component::Normal(n) if n.to_string_lossy().eq_ignore_ascii_case(".git")))) || excluded.iter().any(|p|target.starts_with(p))
}
fn read_worktree_excluding(
    root: &Path,
    path: &str,
    excluded: &[std::path::PathBuf],
) -> Result<Option<String>> {
    Ok(read_worktree_bytes(root, path, excluded)?
        .map(|bytes| String::from_utf8_lossy(&bytes).into_owned()))
}
fn read_worktree_bytes(
    root: &Path,
    path: &str,
    excluded: &[std::path::PathBuf],
) -> Result<Option<Vec<u8>>> {
    use std::io::Read;
    let root = std::fs::canonicalize(root)?;
    let Some(mut f) = open_worktree_file(&root, path, excluded)? else {
        return Ok(None);
    };
    ensure!(
        f.metadata()?.len() <= SOURCE_BYTE_LIMIT as u64,
        "File exceeds 32 MiB read limit"
    );
    let mut b = vec![];
    f.by_ref()
        .take(SOURCE_BYTE_LIMIT as u64 + 1)
        .read_to_end(&mut b)?;
    ensure!(
        b.len() <= SOURCE_BYTE_LIMIT,
        "File exceeds 32 MiB read limit"
    );
    Ok(Some(b))
}
// The caller resolves the root once. Keep both pathname and opened-file checks here,
// so a batched read cannot accidentally weaken the single-file containment boundary.
fn open_worktree_file(
    root: &Path,
    path: &str,
    excluded: &[std::path::PathBuf],
) -> Result<Option<std::fs::File>> {
    let p = Path::new(path);
    if p.components()
        .any(|c| !matches!(c, Component::Normal(_) | Component::CurDir))
    {
        return Ok(None);
    }
    if private_source_path(root, &root.join(p), excluded) {
        return Ok(None);
    }
    let target = match std::fs::canonicalize(root.join(p)) {
        Ok(p) => p,
        Err(_) => return Ok(None),
    };
    if !target.starts_with(&root) || target == root || private_source_path(&root, &target, excluded)
    {
        return Ok(None);
    }
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(target_os = "linux")]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(0x20000);
    }
    let f = match options.open(&target) {
        Ok(f) => f,
        Err(_) => return Ok(None),
    };
    if !f.metadata()?.is_file() {
        return Ok(None);
    }
    #[cfg(target_os = "linux")]
    {
        use std::os::fd::AsRawFd;
        let opened = std::fs::canonicalize(format!("/proc/self/fd/{}", f.as_raw_fd()))?;
        if !opened.starts_with(&root)
            || opened == root
            || private_source_path(&root, &opened, excluded)
        {
            return Ok(None);
        }
    }
    #[cfg(all(unix, not(target_os = "linux")))]
    {
        use std::os::unix::fs::MetadataExt;
        let current = std::fs::symlink_metadata(&target)?;
        let opened = f.metadata()?;
        if std::fs::canonicalize(&target)? != target
            || !current.is_file()
            || current.dev() != opened.dev()
            || current.ino() != opened.ino()
        {
            return Ok(None);
        }
    }
    #[cfg(not(unix))]
    {
        bail!("Secure working-tree reads are not supported on this platform");
    }
    Ok(Some(f))
}
pub async fn allowed_path(state: &AppState, path: &str) -> Result<bool> {
    if path.is_empty()
        || Path::new(path).is_absolute()
        || Path::new(path)
            .components()
            .any(|c| !matches!(c, Component::Normal(_) | Component::CurDir))
    {
        return Ok(false);
    }
    let root = root(state).await?;
    let excluded = excluded_roots(state, &root).await?;
    let target = root.join(path);
    if private_source_path(&root, &target, &excluded) {
        return Ok(false);
    }
    Ok(std::fs::canonicalize(target)
        .map(|target| target.starts_with(&root) && !private_source_path(&root, &target, &excluded))
        .unwrap_or(true))
}
pub async fn file(state: &AppState, path: &str, sha: &str) -> Result<Value> {
    let root = root(state).await?;
    ensure!(
        !sha.starts_with('-') && !sha.contains(':') || sha.starts_with("worktree:"),
        "Invalid revision"
    );
    ensure!(
        !Path::new(path).is_absolute()
            && !Path::new(path)
                .components()
                .any(|c| matches!(c, Component::ParentDir)),
        "Invalid file path"
    );
    if sha.starts_with("worktree:") {
        ensure!(
            fetch_local(state, "").await?["head"]["sha"] == sha,
            "The working tree changed; reopen the diff before reading this revision"
        );
        let listed = git(
            &root,
            &[
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "-z",
                "--",
                path,
            ],
        )
        .await?;
        if !listed.split('\0').any(|p| p == path) {
            return Ok(Value::Null);
        }
        let excluded = excluded_roots(state, &root).await?;
        let content =
            read_worktree_excluding(&root, path, &excluded)?.map_or(Value::Null, Value::String);
        ensure!(
            fetch_local(state, "").await?["head"]["sha"] == sha,
            "The working tree changed while reading; reopen the diff"
        );
        return Ok(content);
    }
    let sha = rev(&root, sha).await?;
    Ok(git(&root, &["cat-file", "blob", &format!("{sha}:{path}")])
        .await
        .ok()
        .map_or(Value::Null, Value::String))
}
pub async fn info(state: &AppState) -> Result<Value> {
    let root = root(state).await?;
    let branch = git(&root, &["symbolic-ref", "--short", "-q", "HEAD"])
        .await
        .ok()
        .map(|s| s.trim().to_string());
    let origin = git(
        &root,
        &["symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD"],
    )
    .await
    .ok();
    let names = origin
        .as_ref()
        .map(|s| vec![s.trim().trim_start_matches("origin/").to_string()])
        .unwrap_or_else(|| vec!["main".into(), "master".into()]);
    let mut default = Value::Null;
    for name in names {
        for r in [format!("origin/{name}"), name.clone()] {
            if rev(&root, &r).await.is_ok() {
                default = json!({"name":name,"ref":r});
                break;
            }
        }
        if !default.is_null() {
            break;
        }
    }
    let branches = git(
        &root,
        &[
            "for-each-ref",
            "--sort=-committerdate",
            "--format=%(refname:short)",
            "refs/heads",
        ],
    )
    .await?;
    let tags = git(
        &root,
        &[
            "for-each-ref",
            "--sort=-creatordate",
            "--format=%(refname:short)",
            "refs/tags",
        ],
    )
    .await?;
    let log = git(&root, &["log", "-10", "--format=%H%x00%s"])
        .await
        .unwrap_or_default();
    let mut result = json!({"currentBranch":branch,"defaultBranch":default,"branches":branches.lines().collect::<Vec<_>>(),"tags":tags.lines().collect::<Vec<_>>(),"commits":log.lines().filter_map(|s|s.split_once('\0').map(|(sha,subject)|json!({"sha":sha,"subject":subject}))).collect::<Vec<_>>()});
    if branch.is_none() {
        result.as_object_mut().unwrap().remove("currentBranch");
    }
    if default.is_null() {
        result.as_object_mut().unwrap().remove("defaultBranch");
    }
    Ok(result)
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    #[test]
    fn private_directories_are_not_source_files() {
        let dir = std::env::temp_dir().join(format!("pulls-private-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(dir.join(".git")).unwrap();
        std::fs::create_dir_all(dir.join("cache")).unwrap();
        std::fs::write(dir.join(".git/config"), "private").unwrap();
        std::fs::write(dir.join("cache/chat"), "private").unwrap();
        std::os::unix::fs::symlink(dir.join(".git/config"), dir.join("config-link")).unwrap();
        assert_eq!(read_worktree(&dir, ".git/config").unwrap(), None);
        assert_eq!(read_worktree(&dir, "config-link").unwrap(), None);
        assert_eq!(
            read_worktree_excluding(&dir, "cache/chat", &[dir.join("cache")]).unwrap(),
            None
        );
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn read_containment_blocks_symlink_escape() {
        let dir = std::env::temp_dir().join(format!("pulls-safe-read-{}", uuid::Uuid::new_v4()));
        let repo = dir.join("repo");
        std::fs::create_dir_all(&repo).unwrap();
        std::fs::write(dir.join("secret"), "secret").unwrap();
        std::fs::write(repo.join("ok"), "hello").unwrap();
        std::os::unix::fs::symlink(dir.join("secret"), repo.join("escape")).unwrap();
        std::os::unix::fs::symlink(&dir, repo.join("outside")).unwrap();
        assert_eq!(read_worktree(&repo, "ok").unwrap(), Some("hello".into()));
        assert_eq!(read_worktree(&repo, "escape").unwrap(), None);
        assert_eq!(read_worktree(&repo, "outside/secret").unwrap(), None);
        assert_eq!(read_worktree(&repo, "../secret").unwrap(), None);
        std::fs::remove_dir_all(dir).unwrap();
    }
}

/// A complete path inventory at the captured revision, excluding server state.
pub async fn tree(state: &AppState, sha: &str) -> Result<Vec<String>> {
    let root = root(state).await?;
    let excluded = excluded_roots(state, &root).await?;
    let listing = if sha.starts_with("worktree:") {
        ensure!(
            fetch_local(state, "").await?["head"]["sha"] == sha,
            "The working tree changed; reopen the diff before listing it"
        );
        git(
            &root,
            &[
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "-z",
            ],
        )
        .await?
    } else {
        let sha = rev(&root, sha).await?;
        git(&root, &["ls-tree", "-r", "-z", "--name-only", &sha]).await?
    };
    let mut paths: Vec<String> = listing
        .split('\0')
        .filter(|p| !p.is_empty())
        .filter(|p| {
            let target = root.join(p);
            !private_source_path(&root, &target, &excluded)
                && std::fs::canonicalize(&target)
                    .map(|target| {
                        target.starts_with(&root) && !private_source_path(&root, &target, &excluded)
                    })
                    .unwrap_or(true)
        })
        .map(str::to_owned)
        .collect();
    paths.sort();
    paths.dedup();
    if sha.starts_with("worktree:") {
        ensure!(
            fetch_local(state, "").await?["head"]["sha"] == sha,
            "The working tree changed while listing; reopen the diff"
        );
    }
    Ok(paths)
}

#[derive(Default)]
pub struct SearchResult {
    pub hits: Vec<String>,
    pub scanned: usize,
    pub total: usize,
    pub unavailable: usize,
    pub stopped: Option<&'static str>,
    bytes: usize,
}
impl SearchResult {
    fn fits(&mut self, size: usize) -> bool {
        if size > SOURCE_BYTE_LIMIT.saturating_sub(self.bytes) {
            self.stopped = Some("32 MiB source budget; narrow the path glob");
            return false;
        }
        true
    }
    fn content(
        &mut self,
        path: &str,
        content: &str,
        re: &regex::Regex,
        deadline: tokio::time::Instant,
    ) {
        if !self.fits(content.len()) {
            return;
        }
        self.bytes += content.len();
        if content.as_bytes().contains(&0) {
            return;
        }
        for (line, text) in content.lines().enumerate() {
            if tokio::time::Instant::now() >= deadline {
                self.stopped = Some("time limit");
                break;
            }
            if re.is_match(text) {
                let excerpt: String = text.chars().take(2000).collect();
                self.hits.push(format!("{path}:{}:{excerpt}", line + 1));
                if self.hits.len() >= 200 {
                    self.stopped = Some("200 matches; narrow the pattern");
                    break;
                }
            }
        }
    }
}
struct SearchEntry {
    path: String,
    blob: Option<String>,
}
fn visible_source_path(root: &Path, path: &str, excluded: &[std::path::PathBuf]) -> bool {
    let target = root.join(path);
    !path.is_empty()
        && Path::new(path)
            .components()
            .all(|c| matches!(c, Component::Normal(_) | Component::CurDir))
        && !private_source_path(root, &target, excluded)
        && std::fs::canonicalize(&target)
            .map(|target| target.starts_with(root) && !private_source_path(root, &target, excluded))
            .unwrap_or(true)
}
async fn search_inventory(
    root: &Path,
    excluded: &[std::path::PathBuf],
    sha: &str,
    glob: Option<&regex::Regex>,
) -> Result<Vec<SearchEntry>> {
    let worktree = sha.starts_with("worktree:");
    let listing = if worktree {
        git_bytes(
            root,
            &[
                "ls-files",
                "--cached",
                "--others",
                "--exclude-standard",
                "-z",
            ],
        )
        .await?
    } else {
        let sha = rev(root, sha).await?;
        git_bytes(root, &["--no-replace-objects", "ls-tree", "-r", "-z", &sha]).await?
    };
    // Fail rather than silently change a non-UTF-8 path into a different pathname.
    let listing =
        std::str::from_utf8(&listing).context("Source inventory contains unsupported paths")?;
    ensure!(
        listing.is_empty() || listing.ends_with('\0'),
        "Incomplete source inventory"
    );
    let mut entries = vec![];
    for (i, record) in listing.split_terminator('\0').enumerate() {
        if i % 128 == 0 {
            tokio::task::yield_now().await;
        }
        let (path, blob) = if worktree {
            (record, None)
        } else {
            let (metadata, path) = record
                .split_once('\t')
                .context("Invalid source tree entry")?;
            let fields: Vec<_> = metadata.split(' ').collect();
            ensure!(fields.len() == 3, "Invalid source tree entry");
            let oid = fields[2];
            ensure!(
                (oid.len() == 40 || oid.len() == 64) && oid.bytes().all(|b| b.is_ascii_hexdigit()),
                "Invalid source object ID"
            );
            (path, (fields[1] == "blob").then(|| oid.to_owned()))
        };
        if glob.is_none_or(|g| g.is_match(path)) && visible_source_path(root, path, excluded) {
            entries.push(SearchEntry {
                path: path.to_owned(),
                blob,
            });
        }
    }
    entries.sort_by(|a, b| a.path.cmp(&b.path));
    entries.dedup_by(|a, b| a.path == b.path);
    Ok(entries)
}

/// @cc [owner:OctavianTocan,label:architecture;security] rust-local-search-batches-captured-source
/// Local source search MUST use a bounded server-owned batch at the captured revision,
/// preserve Rust regex and path-glob matching, and check a worktree fingerprint around
/// the whole scan. It MUST retain private-path containment and report incomplete reads
/// or scan limits (Rust parity review #8, 16 Oct 2026; agents-act-through-pr-tools).
pub async fn search(
    state: &AppState,
    sha: &str,
    snapshots: &Value,
    re: &regex::Regex,
    glob: Option<&regex::Regex>,
) -> Result<SearchResult> {
    use tokio::{
        io::AsyncReadExt,
        time::{timeout_at, Duration, Instant},
    };
    ensure!(!sha.is_empty(), "Missing captured revision");
    let deadline = Instant::now() + Duration::from_secs(60);
    let worktree = sha.starts_with("worktree:");
    let (root, excluded, entries) = timeout_at(deadline, async {
        let root = root(state).await?;
        let excluded = excluded_roots(state, &root).await?;
        if worktree {
            ensure!(
                fetch_at(&root, &excluded, "").await?["head"]["sha"] == sha,
                "The working tree changed; reopen the diff before searching this revision"
            );
        }
        let entries = search_inventory(&root, &excluded, sha, glob).await?;
        Ok::<_, anyhow::Error>((root, excluded, entries))
    })
    .await
    .context("Incomplete search: time limit while preparing the source inventory")??;
    let mut result = SearchResult {
        total: entries.len(),
        ..Default::default()
    };
    let scan = async {
        if !worktree {
            return search_blobs(&root, &entries, snapshots, re, deadline, &mut result).await;
        }
        for entry in &entries {
            tokio::task::yield_now().await;
            result.scanned += 1;
            if let Some(content) = snapshots[&entry.path].as_str() {
                result.content(&entry.path, content, re, deadline);
            } else {
                match open_worktree_file(&root, &entry.path, &excluded) {
                    Ok(Some(file)) => {
                        let size = file.metadata()?.len();
                        if size > SOURCE_BYTE_LIMIT.saturating_sub(result.bytes) as u64 {
                            result.fits(SOURCE_BYTE_LIMIT + 1);
                            break;
                        }
                        let mut bytes = vec![];
                        let limit = SOURCE_BYTE_LIMIT - result.bytes;
                        let read = tokio::fs::File::from_std(file)
                            .take(limit as u64 + 1)
                            .read_to_end(&mut bytes)
                            .await;
                        if !result.fits(bytes.len()) {
                            break;
                        }
                        if read.is_err() {
                            result.bytes += bytes.len();
                            result.unavailable += 1;
                        } else {
                            result.content(
                                &entry.path,
                                &String::from_utf8_lossy(&bytes),
                                re,
                                deadline,
                            );
                        }
                    }
                    _ => result.unavailable += 1,
                }
            }
            if result.stopped.is_some() {
                break;
            }
        }
        Ok(())
    };
    match timeout_at(deadline, scan).await {
        Ok(read) => {
            read.context("Incomplete search: source batch could not be read at this revision")?
        }
        Err(_) => result.stopped = Some("time limit"),
    }
    if worktree {
        // Even limited results may only be returned after verifying their captured state.
        let after = timeout_at(deadline, fetch_at(&root, &excluded, ""))
            .await
            .context(
                "Incomplete search: time limit before the working-tree revision could be verified",
            )??;
        ensure!(
            after["head"]["sha"] == sha,
            "The working tree changed while searching; reopen the diff"
        );
    }
    Ok(result)
}
async fn search_blobs(
    root: &Path,
    entries: &[SearchEntry],
    snapshots: &Value,
    re: &regex::Regex,
    deadline: tokio::time::Instant,
    result: &mut SearchResult,
) -> Result<()> {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    if entries.is_empty() {
        return Ok(());
    }
    let mut child = git_command(root)
        .args(["--no-replace-objects", "cat-file", "--batch"])
        .stdin(std::process::Stdio::piped())
        .spawn()
        .context("Could not read source batch")?;
    let mut input = child.stdin.take().context("Git input unavailable")?;
    let mut output = BufReader::new(child.stdout.take().context("Git output unavailable")?);
    for entry in entries {
        tokio::task::yield_now().await;
        result.scanned += 1;
        if let Some(content) = snapshots[&entry.path].as_str() {
            result.content(&entry.path, content, re, deadline);
        } else if let Some(oid) = &entry.blob {
            // Only ls-tree's validated hex IDs enter this newline-framed protocol.
            // Repository paths (which may contain newlines) are never commands.
            input.write_all(format!("{oid}\n").as_bytes()).await?;
            let mut header = vec![];
            (&mut output)
                .take(256)
                .read_until(b'\n', &mut header)
                .await?;
            ensure!(header.ends_with(b"\n"), "Incomplete source object header");
            let fields: Vec<_> = std::str::from_utf8(&header)?.split_whitespace().collect();
            if fields.len() == 2 && fields[0] == oid && fields[1] == "missing" {
                result.unavailable += 1;
                continue;
            }
            ensure!(
                fields.len() == 3 && fields[0] == oid && fields[1] == "blob",
                "Invalid source object header"
            );
            let size = fields[2].parse::<usize>()?;
            if !result.fits(size) {
                break;
            }
            let mut bytes = vec![0; size];
            output.read_exact(&mut bytes).await?;
            ensure!(
                output.read_u8().await? == b'\n',
                "Incomplete source object body"
            );
            result.content(&entry.path, &String::from_utf8_lossy(&bytes), re, deadline);
        } else {
            result.unavailable += 1;
        }
        if result.stopped.is_some() {
            break;
        }
    }
    drop(input);
    if result.stopped.is_some() {
        child.kill().await?;
    } else {
        ensure!(child.wait().await?.success(), "Source batch failed");
    }
    Ok(())
}

#[cfg(all(test, target_os = "linux"))]
mod search_tests;
