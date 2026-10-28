use super::github_read::{self, GitHubRead, StateGitHubRead};
use super::*;
use base64::Engine;
use futures_util::StreamExt;
/// @cc [owner:OctavianTocan,label:security;architecture] rust-github-credentials-stay-server-side
/// For LegacyPrivate, environment credentials MUST win over the server's bounded GitHub CLI login
/// lookup. Neither the token nor subprocess output may be returned or logged
/// (github-token-stays-on-server; original credentials.ts; Rust parity 16 Oct 2026).
/// Guest and personal contexts MUST use their explicit authority instead of this lookup
/// (Tavi's 21 Oct 2026 public browsing and personal GitHub decision).
/// The entire cache/lookup transaction, including mutex acquisition, MUST run off
/// Tokio workers (16 Oct 2026 CE review, credential lookup responsiveness).
async fn legacy_token() -> Option<String> {
    blocking_token(token_sync).await
}

pub async fn token(state: &AppState) -> Result<Option<String>> {
    state.identity.github_token(legacy_token).await
}
async fn blocking_token(
    lookup: impl FnOnce() -> Option<String> + Send + 'static,
) -> Option<String> {
    crate::rt::spawn_blocking(lookup).await.ok().flatten()
}
fn token_sync() -> Option<String> {
    if let Some(token) = ["PULLS_REVIEW_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"]
        .iter()
        .find_map(|k| {
            crate::rt::env::var(k)
                .ok()
                .map(|v| v.trim().to_owned())
                .filter(|v| !v.is_empty())
        })
    {
        return Some(token);
    }
    use std::sync::{Mutex, OnceLock};
    use std::time::Duration;
    use web_time::Instant;
    static LOGIN: OnceLock<Mutex<Option<(Instant, Option<String>)>>> = OnceLock::new();
    let mut cached = LOGIN
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if let Some((at, token)) = cached.as_ref() {
        // Re-read failed logins soon so signing in does not require a restart.
        let ttl = if token.is_some() { 600 } else { 30 };
        if at.elapsed() < Duration::from_secs(ttl) {
            return token.clone();
        }
    }
    let token = cli_token();
    *cached = Some((Instant::now(), token.clone()));
    token
}
fn cli_token() -> Option<String> {
    use std::{
        io::Read,
        process::{Command, Stdio},
        sync::mpsc,
        time::Duration,
    };
    use web_time::Instant;
    const LIMIT: u64 = 16 * 1024;
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut child = Command::new("gh")
        .args(["auth", "token", "--hostname", "github.com"])
        .env("GH_PROMPT_DISABLED", "1")
        .env("GIT_TERMINAL_PROMPT", "0")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let stdout = child.stdout.take()?;
    let (send, receive) = mpsc::sync_channel(1);
    // Drain concurrently so an unexpected large response cannot block the child.
    std::thread::spawn(move || {
        let mut bytes = Vec::new();
        let result = stdout.take(LIMIT + 1).read_to_end(&mut bytes);
        let _ = send.send(
            result
                .ok()
                .filter(|_| bytes.len() <= LIMIT as usize)
                .map(|_| bytes),
        );
    });
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) => {}
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return None;
        }
        std::thread::sleep(Duration::from_millis(10));
    };
    if !status.success() {
        return None;
    }
    let bytes = receive
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .ok()??;
    String::from_utf8(bytes)
        .ok()
        .map(|v| v.trim().to_owned())
        .filter(|v| !v.is_empty() && !v.contains(['\r', '\n']))
}
pub async fn raw(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
    accept: &str,
) -> Result<(u16, Value, String)> {
    let started = web_time::Instant::now();
    let result = raw_inner(state, method, path, body, accept).await;
    let _=state.analytics.record("github.calls",json!({"route":if path=="/graphql"{"graphql"}else{"rest"},"calls":1,"failures":if result.as_ref().is_ok_and(|(code,_,_)| (200..300).contains(code)){0}else{1},"ms":started.elapsed().as_millis() as u64})).await;
    result
}
async fn raw_inner(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
    accept: &str,
) -> Result<(u16, Value, String)> {
    state.identity.ensure_current()?;
    if state.identity.kind() == crate::identity::PrincipalKind::Guest {
        ensure!(
            method == reqwest::Method::GET || method == reqwest::Method::HEAD,
            "Public browsing is read-only"
        );
        ensure!(
            path.starts_with("/repos/") || path.starts_with("/search/"),
            "This GitHub route needs sign-in"
        );
    }
    ensure!(
        path.starts_with("/repos/")
            || path == "/user"
            || path.starts_with("/user?")
            || path.starts_with("/user/")
            || path.starts_with("/search/")
            || path == "/graphql",
        "Unsupported GitHub API path"
    );
    let url = url::Url::parse(&format!("https://api.github.com{path}"))
        .context("Invalid GitHub API path")?;
    ensure!(
        url.host_str() == Some("api.github.com")
            && url.scheme() == "https"
            && url.username().is_empty(),
        "Unsupported GitHub origin"
    );
    // Offline fixtures keep the same origin validation and execute the complete
    // source loader, without exposing an alternate production API origin.
    #[cfg(test)]
    if let Some(response) = snapshot_tests::response(&method, path, body.as_ref(), accept) {
        return response;
    }
    let url = if state.personal_workspace {
        state.github_origin.join(path.trim_start_matches('/'))?
    } else {
        url
    };
    let mut req = state
        .http
        .request(method, url)
        .header("Accept", accept)
        .header("User-Agent", "pulls-review-rust")
        .header("X-GitHub-Api-Version", "2022-11-28");
    if let Some(t) = token(state).await? {
        req = req.bearer_auth(t)
    }
    if let Some(b) = body {
        req = req.json(&b)
    }
    let response = crate::net::send(req)
        .await
        .map_err(|_| anyhow::anyhow!("GitHub request could not be completed"))?;
    let code = response.status().as_u16();
    ensure!(!(300..400).contains(&code), "GitHub redirect refused");
    let mut headers = json!({});
    for n in ["content-type", "link", "x-oauth-scopes"] {
        if let Some(v) = response.headers().get(n).and_then(|v| v.to_str().ok()) {
            headers[n] = json!(v)
        }
    }
    let mut bytes = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| anyhow::anyhow!("GitHub response could not be read"))?;
        ensure!(
            bytes.len().saturating_add(chunk.len()) <= 32 * 1024 * 1024,
            "GitHub response exceeds 32 MiB limit"
        );
        bytes.extend_from_slice(&chunk);
    }
    let text = String::from_utf8_lossy(&bytes).into_owned();
    state.identity.ensure_current()?;
    Ok((code, headers, text))
}
pub async fn request(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
) -> Result<Value> {
    let (code, _, text) = raw(state, method, path, body, "application/vnd.github+json").await?;
    ensure!(
        (200..300).contains(&code),
        "GitHub request failed (HTTP {code})"
    );
    if text.is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(&text).context("GitHub returned invalid JSON")
}
async fn get(state: &AppState, path: &str) -> Result<Value> {
    request(state, reqwest::Method::GET, path, None).await
}
pub async fn pages(state: &AppState, path: &str, field: Option<&str>) -> Result<Vec<Value>> {
    pages_with(&StateGitHubRead(state), path, field).await
}
async fn pages_with(read: &dyn GitHubRead, path: &str, field: Option<&str>) -> Result<Vec<Value>> {
    let mut all = vec![];
    for page in 1..=100 {
        let sep = if path.contains('?') { '&' } else { '?' };
        let mut v = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{path}{sep}per_page=100&page={page}"),
            None,
        )
        .await?;
        let xs = match field {
            Some(key) => v.get_mut(key).and_then(Value::as_array_mut),
            None => v.as_array_mut(),
        }
        .context("GitHub returned an invalid page")?;
        let count = xs.len();
        all.append(xs);
        if count < 100 {
            return Ok(all);
        }
    }
    bail!("GitHub pagination exceeded safe limit; result is incomplete")
}
pub async fn file(state: &AppState, r: &Value, path: &str, sha: &str) -> Result<Value> {
    file_with(&StateGitHubRead(state), r, path, sha).await
}
pub(crate) async fn file_with(
    read: &dyn GitHubRead,
    r: &Value,
    path: &str,
    sha: &str,
) -> Result<Value> {
    let prefix = prefix(r)?;
    ensure!(!sha.is_empty(), "Missing file revision");
    ensure!(
        !path.is_empty()
            && !path.starts_with('/')
            && !path.contains('\0')
            && path.split('/').all(|part| part != "." && part != ".."),
        "Invalid repository path"
    );
    let path = path.split('/').map(enc).collect::<Vec<_>>().join("/");
    let (code, _, text) = read
        .raw(
            reqwest::Method::GET,
            &format!("{prefix}/contents/{path}?ref={}", enc(sha)),
            None,
            "application/vnd.github+json",
        )
        .await?;
    if code == 404 {
        return Ok(Value::Null);
    }
    ensure!(code == 200, "GitHub file read failed (HTTP {code})");
    let v: Value = serde_json::from_str(&text)?;
    ensure!(v["type"] == "file", "Requested path is not a file");
    ensure!(
        v["size"].as_u64().unwrap_or(0) <= 32 * 1024 * 1024,
        "File exceeds 32 MiB read limit"
    );
    if v["encoding"] == "base64" {
        let b = base64::engine::general_purpose::STANDARD.decode(
            s(&v, "content")
                .chars()
                .filter(|c| !c.is_whitespace())
                .collect::<String>(),
        )?;
        return Ok(json!(String::from_utf8_lossy(&b)));
    }
    let (code, _, text) = read
        .raw(
            reqwest::Method::GET,
            &format!("{prefix}/contents/{path}?ref={}", enc(sha)),
            None,
            "application/vnd.github.raw",
        )
        .await?;
    ensure!(code == 200, "GitHub raw file read failed (HTTP {code})");
    Ok(json!(text))
}
pub fn prefix(r: &Value) -> Result<String> {
    let owner = s(r, "owner");
    let repo = s(r, "repo");
    let valid = |v: &str| {
        !v.is_empty()
            && v != "."
            && v != ".."
            && v.chars()
                .all(|c| c.is_ascii_alphanumeric() || "_.-".contains(c))
    };
    ensure!(valid(owner) && valid(repo), "Invalid repository");
    Ok(format!("/repos/{owner}/{repo}"))
}
fn commits(v: &[Value]) -> Vec<Value> {
    v.iter()
        .map(|c| json!({"sha":c["sha"],"message":c["commit"]["message"]}))
        .collect()
}
async fn normalize(
    read: &dyn GitHubRead,
    r: &Value,
    files: Vec<Value>,
    diffpath: &str,
    base: &str,
    head: &str,
) -> Result<Vec<Value>> {
    let needs = files.iter().any(|f| !f["patch"].is_string());
    let fallback = if needs {
        match read
            .raw(
                reqwest::Method::GET,
                diffpath,
                None,
                "application/vnd.github.diff",
            )
            .await
        {
            Ok((200, _, t)) => Some(patch::parse_patch(&t)),
            _ => None,
        }
    } else {
        None
    };
    let mut out = vec![];
    for f in files {
        let path = s(&f, "filename");
        ensure!(!path.is_empty(), "GitHub file missing filename");
        let status = match s(&f, "status") {
            "added" => "added",
            "removed" => "removed",
            "renamed" => "renamed",
            "copied" => "copied",
            _ => "modified",
        };
        let mut n = json!({"path":path,"status":status,"additions":f["additions"].as_u64().unwrap_or(0),"deletions":f["deletions"].as_u64().unwrap_or(0),"sha":f["sha"].as_str().map(str::to_string).unwrap_or_else(||format!("{head}:{path}")),"isBinary":false,"hunks":[]});
        if let Some(p) = f["previous_filename"].as_str() {
            n["previousPath"] = json!(p)
        }
        if let Some(p) = f["patch"].as_str() {
            n["hunks"] = json!(patch::hunks(p));
        } else if let Some(m) = fallback.as_ref().and_then(|fs| {
            fs.iter().find(|x| {
                x["path"] == path
                    || (!f["previous_filename"].is_null()
                        && x["previousPath"] == f["previous_filename"])
            })
        }) {
            n["hunks"] = m["hunks"].clone();
            n["isBinary"] = m["isBinary"].clone();
            if m["truncated"] == true {
                n["truncated"] = json!(true)
            }
        } else if n["additions"].as_u64().unwrap_or(0) + n["deletions"].as_u64().unwrap_or(0) == 0 {
            n["isBinary"] = json!(true)
        } else {
            n["truncated"] = json!(true);
            let changes =
                n["additions"].as_u64().unwrap_or(0) + n["deletions"].as_u64().unwrap_or(0);
            if changes <= 5000 {
                let old = if status == "added" || base.is_empty() {
                    Some(String::new())
                } else {
                    file_with(
                        read,
                        r,
                        f["previous_filename"].as_str().unwrap_or(path),
                        base,
                    )
                    .await
                    .ok()
                    .and_then(|v| v.as_str().map(str::to_string))
                };
                let new = if status == "removed" {
                    Some(String::new())
                } else {
                    file_with(read, r, path, head)
                        .await
                        .ok()
                        .and_then(|v| v.as_str().map(str::to_string))
                };
                if let (Some(a), Some(b)) = (old, new) {
                    if a.len() + b.len() <= 2 * 1024 * 1024
                        && !a.contains('\0')
                        && !b.contains('\0')
                    {
                        if let Some(h) = self_diff(&a, &b) {
                            n["hunks"] = json!(h);
                            n.as_object_mut().unwrap().remove("truncated");
                        }
                    }
                }
            }
        }
        out.push(n)
    }
    Ok(out)
}
// Bounded LCS reconstruction. Refuse work rather than silently represent an omitted patch as an empty file.
fn diff_line(prefix: char, line: &str) -> String {
    if let Some(l) = line.strip_suffix('\n') {
        format!("{prefix}{l}")
    } else {
        format!("{prefix}{line}\n\\ No newline at end of file")
    }
}
fn self_diff(a: &str, b: &str) -> Option<Vec<Value>> {
    let aa: Vec<_> = a.split_inclusive('\n').collect();
    let bb: Vec<_> = b.split_inclusive('\n').collect();
    if aa.len().saturating_mul(bb.len()) > 2_000_000 {
        return None;
    }
    let w = bb.len() + 1;
    let mut dp = vec![0u32; (aa.len() + 1) * w];
    for i in (0..aa.len()).rev() {
        for j in (0..bb.len()).rev() {
            dp[i * w + j] = if aa[i] == bb[j] {
                1 + dp[(i + 1) * w + j + 1]
            } else {
                dp[(i + 1) * w + j].max(dp[i * w + j + 1])
            }
        }
    }
    let (mut i, mut j) = (0, 0);
    let mut lines = vec![];
    while i < aa.len() || j < bb.len() {
        if i < aa.len() && j < bb.len() && aa[i] == bb[j] {
            lines.push(diff_line(' ', aa[i]));
            i += 1;
            j += 1
        } else if j < bb.len() && (i == aa.len() || dp[i * w + j + 1] >= dp[(i + 1) * w + j]) {
            lines.push(diff_line('+', bb[j]));
            j += 1
        } else {
            lines.push(diff_line('-', aa[i]));
            i += 1
        }
    }
    Some(vec![
        json!({"header":format!("@@ -{},{} +{},{} @@",if aa.is_empty(){0}else{1},aa.len(),if bb.is_empty(){0}else{1},bb.len()),"oldStart":if aa.is_empty(){0}else{1},"oldLines":aa.len(),"newStart":if bb.is_empty(){0}else{1},"newLines":bb.len(),"patch":lines.join("\n")}),
    ])
}
pub async fn fetch(state: &AppState, r: &Value) -> Result<Value> {
    fetch_with(&StateGitHubRead(state), r).await
}
pub(crate) async fn fetch_with(read: &dyn GitHubRead, r: &Value) -> Result<Value> {
    let p = prefix(r)?;
    let kind = s(r, "kind");
    if kind == "github-pr" {
        let number = r["number"]
            .as_str()
            .map(str::to_string)
            .or_else(|| r["number"].as_u64().map(|n| n.to_string()))
            .context("Missing PR number")?;
        ensure!(
            number.chars().all(|c| c.is_ascii_digit()),
            "Invalid PR number"
        );
        let pr = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{p}/pulls/{number}"),
            None,
        )
        .await?;
        let base = s(&pr["base"], "sha");
        let head = s(&pr["head"], "sha");
        let cp = format!("{p}/compare/{}...{}", enc(base), enc(head));
        let files_path = format!("{p}/pulls/{number}/files");
        let commits_path = format!("{p}/pulls/{number}/commits");
        // The three reads only need the revisions read above, and the second read of the pull
        // request below still rejects a push made meanwhile, so they go out together.
        let (comparison, files, cs) = tokio::try_join!(
            github_read::json(read, reqwest::Method::GET, &cp, None),
            pages_with(read, &files_path, None),
            pages_with(read, &commits_path, None),
        )?;
        let mb = s(&comparison["merge_base_commit"], "sha");
        ensure!(
            !mb.is_empty() && !head.is_empty(),
            "GitHub did not return captured revisions"
        );
        ensure!(
            pr["changed_files"]
                .as_u64()
                .is_none_or(|n| n == files.len() as u64),
            "GitHub omitted files from this PR; refusing incomplete diff"
        );
        ensure!(
            pr["commits"].as_u64().is_none_or(|n| n == cs.len() as u64),
            "GitHub omitted commits from this PR; refusing incomplete diff"
        );
        let current = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{p}/pulls/{number}"),
            None,
        )
        .await?;
        ensure!(
            current["head"]["sha"] == pr["head"]["sha"]
                && current["base"]["sha"] == pr["base"]["sha"],
            "Pull request changed during loading; retry for a consistent snapshot"
        );
        let files = normalize(read, r, files, &cp, mb, head).await?;
        let status = rest_pr_state(&pr);
        let mut reference = r.clone();
        reference["number"] = json!(number);
        return Ok(
            json!({"ref":reference,"title":pr["title"],"label":format!("#{number}"),"description":pr["body"].as_str().unwrap_or(""),"url":pr["html_url"],"author":{"name":pr["user"]["login"].as_str().unwrap_or("ghost"),"avatarUrl":pr["user"]["avatar_url"].as_str().unwrap_or("")},"base":{"sha":mb,"ref":pr["base"]["ref"]},"head":{"sha":head,"ref":pr["head"]["ref"]},"createdAt":pr["created_at"],"updatedAt":pr["updated_at"],"pullRequest":{"state":status,"baseSha":base},"commits":commits(&cs),"files":files}),
        );
    }
    if kind == "github-compare" {
        let base = s(r, "base");
        let head = s(r, "head");
        ensure!(
            !base.is_empty() && !head.is_empty(),
            "Compare needs two revisions"
        );
        let h = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{p}/commits/{}", enc(head)),
            None,
        )
        .await?;
        let bsnapshot = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{p}/commits/{}", enc(base)),
            None,
        )
        .await?;
        let hs = s(&h, "sha");
        let cp = format!("{p}/compare/{}...{}", enc(s(&bsnapshot, "sha")), enc(hs));
        let c = github_read::json(
            read,
            reqwest::Method::GET,
            &format!("{cp}?per_page=100&page=1"),
            None,
        )
        .await?;
        let cs = pages_with(read, &cp, Some("commits")).await?;
        let bs = s(&c["merge_base_commit"], "sha");
        let fs = c["files"]
            .as_array()
            .context("GitHub omitted compare files")?
            .clone();
        ensure!(
            fs.len() < 300,
            "GitHub compare reached its 300-file cap; refusing potentially incomplete diff"
        );
        let fs = normalize(read, r, fs, &format!("{p}/compare/{bs}...{hs}"), bs, hs).await?;
        return Ok(
            json!({"ref":r,"title":format!("{base}...{head}"),"url":c["html_url"],"base":{"sha":bs,"ref":base},"head":{"sha":hs,"ref":head},"commits":commits(&cs),"files":fs}),
        );
    }
    ensure!(kind == "github-commit", "Unsupported source kind");
    let cp = format!("{p}/commits/{}", enc(s(r, "sha")));
    let c = github_read::json(read, reqwest::Method::GET, &cp, None).await?;
    let hs = s(&c, "sha");
    let bs = c["parents"][0]["sha"].as_str().unwrap_or("");
    ensure!(!hs.is_empty(), "GitHub did not resolve the commit revision");
    let fs = pages_with(read, &format!("{p}/commits/{}", enc(hs)), Some("files")).await?;
    ensure!(
        fs.len() < 3000,
        "GitHub commit file cap reached; refusing potentially incomplete diff"
    );
    let fs = normalize(read, r, fs, &format!("{p}/commits/{hs}"), bs, hs).await?;
    let msg = s(&c["commit"], "message");
    let mut result = json!({"ref":r,"title":msg.lines().next().unwrap_or(""),"label":hs.chars().take(7).collect::<String>(),"description":msg.split_once('\n').map_or("",|(_,b)|b.trim()),"url":c["html_url"],"head":{"sha":hs,"ref":hs.chars().take(7).collect::<String>()},"commits":[{"sha":hs,"message":msg}],"files":fs});
    if c["author"].is_object() {
        result["author"] =
            json!({"name":c["author"]["login"],"avatarUrl":c["author"]["avatar_url"]});
    } else if c["commit"]["author"]["name"].is_string() {
        result["author"] = json!({"name":c["commit"]["author"]["name"]});
    }
    if c["commit"]["author"]["date"].is_string() {
        result["createdAt"] = c["commit"]["author"]["date"].clone();
    }
    if !bs.is_empty() {
        result["base"] = json!({"sha":bs,"ref":bs.chars().take(7).collect::<String>()})
    }
    Ok(result)
}

/// @cc [owner:OctavianTocan,label:architecture] rust-reopened-reviews-probe-live-identities
/// Refreshing a selected open inbox PR MUST probe its base and head even if the
/// cached review is closed or merged. Changed revisions MUST reload the complete
/// snapshot before the warmer's captured-snapshot admission (16 Oct 2026 CE review,
/// reopened-head regression; Tavi's Rust parity request).
pub async fn refresh_cached(state: &AppState, diff: &Value) -> Result<Option<Value>> {
    let r = &diff["ref"];
    let p = prefix(r)?;
    let number = s(r, "number");
    ensure!(
        !number.is_empty() && number.bytes().all(|b| b.is_ascii_digit()),
        "Invalid PR number"
    );
    let pr = get(state, &format!("{p}/pulls/{number}")).await?;
    refreshed_snapshot(diff, &pr, fetch(state, r)).await
}
/// How many pull requests one [`pull_states`] query asks about.
const STATES_PER_QUERY: usize = 50;

/// The current state of each pull request in `refs` (`{"owner","repo","number"}`), read with
/// one GraphQL query for every fifty instead of one REST call each, and shaped like the REST
/// pull request [`refresh_cached_from`] compares. An entry is `None` when GitHub did not
/// answer for it, so the caller can fall back to [`refresh_cached`].
pub async fn pull_states(state: &AppState, refs: &[Value]) -> Vec<Option<Value>> {
    let mut states = Vec::with_capacity(refs.len());
    for chunk in refs.chunks(STATES_PER_QUERY) {
        let mut query = String::from("query {");
        for (i, r) in chunk.iter().enumerate() {
            let number = s(r, "number");
            let (Ok(owner), Ok(repo)) = (
                serde_json::to_string(s(r, "owner")),
                serde_json::to_string(s(r, "repo")),
            ) else {
                continue;
            };
            if prefix(r).is_err() || number.is_empty() || !number.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            query.push_str(&format!(" p{i}: repository(owner:{owner},name:{repo}) {{ pullRequest(number:{number}) {{ headRefOid baseRefOid title body updatedAt state isDraft mergedAt closedAt }} }}"));
        }
        query.push('}');
        let response = raw(
            state,
            reqwest::Method::POST,
            "/graphql",
            Some(json!({"query":query})),
            "application/json",
        )
        .await
        .ok()
        .filter(|(code, _, _)| *code == 200)
        .map(|(_, value, _)| value);
        for i in 0..chunk.len() {
            states.push(response.as_ref().and_then(|response| {
                rest_shaped(&response["data"][format!("p{i}")]["pullRequest"])
            }));
        }
    }
    states
}

/// A GraphQL pull request as the REST fields [`refreshed_snapshot`] reads.
fn rest_shaped(pull: &Value) -> Option<Value> {
    let head = pull["headRefOid"].as_str().filter(|sha| !sha.is_empty())?;
    let state = pull["state"].as_str()?;
    Some(json!({
        "head":{"sha":head},
        "base":{"sha":pull["baseRefOid"]},
        "title":pull["title"],
        "body":pull["body"],
        "updated_at":pull["updatedAt"],
        "state":if state == "OPEN" { "open" } else { "closed" },
        "draft":pull["isDraft"],
        "merged":state == "MERGED",
        "merged_at":pull["mergedAt"],
        "closed_at":pull["closedAt"],
    }))
}

/// [`refresh_cached`] with the pull request already read, by [`pull_states`].
pub async fn refresh_cached_from(state: &AppState, diff: &Value, pr: &Value) -> Result<Option<Value>> {
    refreshed_snapshot(diff, pr, fetch(state, &diff["ref"])).await
}
async fn refreshed_snapshot(
    diff: &Value,
    pr: &Value,
    changed: impl std::future::Future<Output = Result<Value>>,
) -> Result<Option<Value>> {
    if cached_revisions_changed(diff, &pr) {
        return changed.await.map(Some);
    }
    let description = json!(pr["body"].as_str().unwrap_or(""));
    let state = json!(rest_pr_state(pr));
    if diff.get("title") == Some(&pr["title"])
        && diff.get("description") == Some(&description)
        && diff.get("updatedAt") == Some(&pr["updated_at"])
        && diff.get("pullRequest").and_then(|pull| pull.get("state")) == Some(&state)
    {
        return Ok(None);
    }
    let mut next = diff.clone();
    next["title"] = pr["title"].clone();
    next["description"] = description;
    next["updatedAt"] = pr["updated_at"].clone();
    next["pullRequest"]["state"] = state;
    Ok((next != *diff).then_some(next))
}

/// @cc [owner:OctavianTocan,label:product] github-rest-lifecycle-preserves-merged-state
/// A merged flag or merge timestamp MUST produce merged before closed or draft.
/// Missing or unrecognized state without terminal evidence MUST NOT become open
/// (Tavi, 20 Oct 2026, "On PRs that have already been merged, we have no indication that
/// they've been merged").
fn rest_pr_state(pr: &Value) -> &'static str {
    if pr["merged"] == true || pr["merged_at"].as_str().is_some_and(|at| !at.is_empty()) {
        "merged"
    } else if pr["state"] == "closed" || pr["closed_at"].as_str().is_some_and(|at| !at.is_empty()) {
        "closed"
    } else if pr["state"] == "open" {
        if pr["draft"] == true {
            "draft"
        } else {
            "open"
        }
    } else {
        "unknown"
    }
}

fn cached_revisions_changed(diff: &Value, pr: &Value) -> bool {
    diff["head"]["sha"] != pr["head"]["sha"] || diff["pullRequest"]["baseSha"] != pr["base"]["sha"]
}
#[cfg(test)]
mod warmer_tests {
    use super::*;
    #[test]
    fn graphql_states_read_as_the_rest_pull_request() {
        for (state, draft, merged_at, closed_at, expected) in [
            ("OPEN", false, None, None, "open"),
            ("OPEN", true, None, None, "draft"),
            ("CLOSED", false, None, Some("2026-10-28T10:00:00Z"), "closed"),
            ("MERGED", false, Some("2026-10-28T10:00:00Z"), Some("2026-10-28T10:00:00Z"), "merged"),
        ] {
            let pr = rest_shaped(&json!({"headRefOid":"h","baseRefOid":"b","title":"T","body":"","updatedAt":"2026-10-28T09:00:00Z","state":state,"isDraft":draft,"mergedAt":merged_at,"closedAt":closed_at})).unwrap();
            assert_eq!(rest_pr_state(&pr), expected);
            assert_eq!(pr["head"]["sha"], "h");
            assert_eq!(pr["base"]["sha"], "b");
            assert_eq!(pr["updated_at"], "2026-10-28T09:00:00Z");
        }
        assert!(rest_shaped(&Value::Null).is_none());
    }
    #[tokio::test]
    async fn unchanged_metadata_preserves_missing_versus_null_fields() {
        let pr = json!({"state":"open","title":null,"body":null,"updated_at":null,"head":{"sha":"h"},"base":{"sha":"b"}});
        let complete = json!({"title":null,"description":"","updatedAt":null,"head":{"sha":"h"},"pullRequest":{"baseSha":"b","state":"open"},"files":[]});
        assert!(refreshed_snapshot(&complete, &pr, async {
            panic!("unchanged revisions must not fetch")
        })
        .await
        .unwrap()
        .is_none());
        for field in ["title", "description", "updatedAt"] {
            let mut missing = complete.clone();
            missing.as_object_mut().unwrap().remove(field);
            let next = refreshed_snapshot(&missing, &pr, async {
                panic!("unchanged revisions must not fetch")
            })
            .await
            .unwrap()
            .unwrap();
            assert_eq!(next, complete);
        }
        let mut missing = complete.clone();
        missing["pullRequest"]
            .as_object_mut()
            .unwrap()
            .remove("state");
        assert_eq!(
            refreshed_snapshot(&missing, &pr, async {
                panic!("unchanged revisions must not fetch")
            })
            .await
            .unwrap()
            .unwrap(),
            complete
        );
    }

    #[test]
    fn rest_state_retains_literal_types_and_precedence() {
        let values = [
            Value::Null,
            json!(false),
            json!(true),
            json!(0),
            json!("true"),
            json!("closed"),
            json!("open"),
            json!("CLOSED"),
            json!([]),
            json!({}),
        ];
        for merged in &values {
            for state in &values {
                for draft in &values {
                    let pr = json!({"merged":merged,"state":state,"draft":draft});
                    let expected = if pr["merged"] == true {
                        "merged"
                    } else if pr["state"] == "closed" {
                        "closed"
                    } else if pr["state"] == "open" {
                        if pr["draft"] == true {
                            "draft"
                        } else {
                            "open"
                        }
                    } else {
                        "unknown"
                    };
                    assert_eq!(rest_pr_state(&pr), expected);
                }
            }
        }
        assert_eq!(rest_pr_state(&json!({})), "unknown");
        assert_eq!(
            rest_pr_state(
                &json!({"state":"closed","merged_at":"2026-10-20T12:00:00Z","draft":true})
            ),
            "merged"
        );
    }

    #[tokio::test]
    async fn reopened_new_head_is_refreshed_and_admitted_without_clobbering_newer_work() {
        let root = std::env::temp_dir().join(format!("pulls-reopened-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        let reference = json!({"kind":"github-pr","owner":"owner","repo":"repo","number":"1"});
        let key = serialize_ref(&reference).unwrap();
        let cached = json!({"ref":reference,"title":"Closed PR","files":[],"head":{"sha":"a"},"pullRequest":{"baseSha":"base","state":"closed"}});
        let before =
            crate::review::put_diff_if_unchanged(&state, &key, &cached, None, false).unwrap();
        let current = json!({"head":{"sha":"b"},"base":{"sha":"base"},"state":"open"});
        let fresh = json!({"ref":reference,"title":"Reopened PR","files":[],"head":{"sha":"b"},"pullRequest":{"baseSha":"base","state":"open"}});
        let attempted = std::cell::Cell::new(false);
        let updated = crate::rt::timeout(
            std::time::Duration::from_secs(1),
            refreshed_snapshot(&before["diff"], &current, async {
                attempted.set(true);
                Ok(fresh.clone())
            }),
        )
        .await
        .unwrap()
        .unwrap()
        .unwrap();
        assert!(attempted.get(), "The reopened head must reload its diff");
        let admitted =
            crate::review::put_warmed_diff_if_unchanged(&state, &key, &updated, Some(&before))
                .unwrap();
        let crate::review::WarmAdmission::Stored(admitted) = admitted else {
            panic!("The reopened snapshot must fit the empty cache");
        };
        assert_eq!(admitted["headSha"], "b");
        assert_eq!(admitted["diff"]["pullRequest"]["state"], "open");
        assert_eq!(admitted["lastViewedAt"], 0);

        // A later foreground snapshot must still win over an in-flight warm result.
        let mut newer = fresh.clone();
        newer["head"]["sha"] = json!("c");
        let newer =
            crate::review::put_diff_if_unchanged(&state, &key, &newer, Some(&admitted), true)
                .unwrap();
        crate::review::put_warmed_diff_if_unchanged(&state, &key, &updated, Some(&admitted))
            .unwrap();
        assert_eq!(
            state.host.storage.get(&format!("diff:{key}")).unwrap(),
            Some(newer)
        );
        drop(state);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn explicit_refresh_updates_reopened_cached_review() {
        let cached = json!({"head":{"sha":"h"},"pullRequest":{"baseSha":"b","state":"closed"}});
        let current =
            json!({"head":{"sha":"h"},"base":{"sha":"b"},"state":"open","title":"Reopened"});
        let updated = refreshed_snapshot(&cached, &current, async {
            panic!("unchanged revisions must not reload files")
        })
        .await
        .unwrap()
        .unwrap();
        assert_eq!(updated["pullRequest"]["state"], "open");
        assert_eq!(updated["title"], "Reopened");
        let pushed = json!({"head":{"sha":"new"},"base":{"sha":"b"},"state":"open"});
        let refreshed = json!({"head":{"sha":"new"},"pullRequest":{"state":"open"}});
        assert_eq!(
            refreshed_snapshot(&cached, &pushed, async { Ok(refreshed.clone()) })
                .await
                .unwrap(),
            Some(refreshed)
        );
    }
    #[test]
    fn unchanged_head_does_not_hide_base_changes() {
        let diff =
            json!({"head":{"sha":"h"},"base":{"sha":"merge-base"},"pullRequest":{"baseSha":"b"}});
        assert!(!cached_revisions_changed(
            &diff,
            &json!({"head":{"sha":"h"},"base":{"sha":"b"}})
        ));
        assert!(cached_revisions_changed(
            &diff,
            &json!({"head":{"sha":"h"},"base":{"sha":"new-base"}})
        ));
        assert!(cached_revisions_changed(
            &diff,
            &json!({"head":{"sha":"new-head"},"base":{"sha":"b"}})
        ));
        assert!(cached_revisions_changed(
            &json!({"head":{"sha":"h"}}),
            &json!({"head":{"sha":"h"},"base":{"sha":"b"}})
        ));
    }
}

#[cfg(test)]
mod credential_tests {
    use super::*;
    use std::{sync::Mutex, time::Duration};

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_lookup_and_cache_mutex_wait_leave_rpc_work_responsive() {
        let cache = Arc::new(Mutex::new(()));
        let first_cache = cache.clone();
        let (started, lookup_started) = tokio::sync::oneshot::channel();
        let (release, blocked_lookup) = std::sync::mpsc::sync_channel(1);
        let first = crate::rt::spawn(blocking_token(move || {
            let _cache = first_cache.lock().unwrap();
            let _ = started.send(());
            // Bound even a broken implementation so the regression cannot hang.
            blocked_lookup.recv_timeout(Duration::from_secs(1)).ok()?;
            Some("credential-fixture".to_owned())
        }));
        crate::rt::timeout(Duration::from_secs(1), lookup_started)
            .await
            .unwrap()
            .unwrap();
        let (waiting, cache_wait_started) = tokio::sync::oneshot::channel();
        let second = crate::rt::spawn(blocking_token(move || {
            let _ = waiting.send(());
            let _cache = cache.lock().unwrap();
            Some("credential-fixture".to_owned())
        }));
        crate::rt::timeout(Duration::from_secs(1), cache_wait_started)
            .await
            .unwrap()
            .unwrap();

        // Simulate unrelated RPC work on the only Tokio worker while one lookup
        // holds the cache and another lookup waits for that same mutex.
        let rpc = crate::rt::spawn(async move {
            crate::rt::sleep(Duration::from_millis(10)).await;
            release.send(()).unwrap();
            42
        });
        assert_eq!(
            crate::rt::timeout(Duration::from_millis(500), rpc)
                .await
                .unwrap()
                .unwrap(),
            42
        );
        for lookup in [first, second] {
            assert_eq!(lookup.await.unwrap().as_deref(), Some("credential-fixture"));
        }
    }
}

#[cfg(test)]
mod snapshot_tests;
