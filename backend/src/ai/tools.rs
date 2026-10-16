use crate::{text, AppState};
use anyhow::{bail, Context, Result};
use futures_util::{stream, StreamExt};
use reqwest::Method;
use serde_json::{json, Value};
use std::{collections::HashSet, fmt::Write, sync::OnceLock, time::Duration};

mod discussion;
mod inbox;
pub fn specs() -> Value {
    json!([
    {"name":"read_file_diff","description":"Read changed files and their complete numbered hunks.","inputSchema":{"type":"object","properties":{"paths":{"type":"array","items":{"type":"string"}}},"required":["paths"]}},
    {"name":"read_hunks","description":"Read specific hunks by path#zero-based-index.","inputSchema":{"type":"object","properties":{"hunkIds":{"type":"array","items":{"type":"string"}}},"required":["hunkIds"]}},
    {"name":"find_files","description":"Find paths at the captured head commit using a regular expression.","inputSchema":{"type":"object","properties":{"pattern":{"type":"string"}}}},
    {"name":"read_source","description":"Read source at the captured head commit, with line numbers.","inputSchema":{"type":"object","properties":{"path":{"type":"string"},"startLine":{"type":"integer"},"endLine":{"type":"integer"}},"required":["path"]}},
    {"name":"search_diff","description":"Search changed lines with a regular expression.","inputSchema":{"type":"object","properties":{"pattern":{"type":"string"},"added":{"type":"boolean"},"removed":{"type":"boolean"}},"required":["pattern"]}},
    {"name":"read_discussion","description":"Read PR description, comments and review discussion.","inputSchema":{"type":"object","properties":{}}}
    ])
}
fn repository_tool(name: &str) -> bool {
    matches!(name, "read_source" | "search_source" | "read_discussion")
}
/// @cc [owner:OctavianTocan,label:product;security] rust-posted-tools-are-diff-only
/// Posted contexts MUST omit repository-source and discussion tools from the shared
/// MCP/Pi registry, and direct calls to those tools MUST fail, even if headSha or
/// sourceSnapshots are present (plan 13, "AI that Pully runs").
pub fn specs_for(ctx: &Value) -> Value {
    let posted = ctx["ref"]["kind"] == "posted";
    if !posted && ctx["pulls"].is_array() {
        return json!([
        {"name":"read_pull_request","description":"Read details of one pull request on the inbox list.","inputSchema":{"type":"object","properties":{"pull":{"type":"string"}},"required":["pull"]}},
        {"name":"read_pull_request_diff","description":"Read changed files of one pull request on the inbox list.","inputSchema":{"type":"object","properties":{"pull":{"type":"string"},"paths":{"type":"array","items":{"type":"string"}}},"required":["pull"]}}
        ]);
    }
    let mut list = specs();
    let tools = list.as_array_mut().unwrap();
    if posted {
        tools.retain(|tool| !repository_tool(text(tool, "name")));
        if let Some(tool) = tools.iter_mut().find(|tool| tool["name"] == "find_files") {
            tool["description"] =
                json!("Find paths in the captured diff using a regular expression.");
        }
    }
    if has_triage(ctx) {
        tools.insert(0, json!({"name":"triage_files","description":"A fast classifier's first read: PR kind and risk, then changed files rated p0 (human judgment), p1 (worth a look), or p2 (low attention). Call first when available; it is a hint, not a verdict.","inputSchema":{"type":"object","properties":{},"additionalProperties":false}}));
    }
    if !posted && (ctx["headSha"].is_string() || ctx["sourceSnapshots"].is_object()) {
        tools.push(json!({"name":"search_source","description":"Search source at the captured head, including unchanged files, with a case-insensitive regular expression. An optional path glob narrows the repository search.","inputSchema":{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"}},"required":["pattern"],"additionalProperties":false}}));
    }
    if ctx["allowGroupingUpdate"] == true {
        tools.push(json!({"name":"update_grouping","description":"Replace the complete grouping and overall summary. Call only when the user asks to change the grouping. Every changed file must appear exactly once.","inputSchema":super::tasks::grouping_schema()}));
    }
    list
}
fn has_triage(ctx: &Value) -> bool {
    ctx["triage"]["pull"].is_object()
        || ctx["triage"]["files"]
            .as_object()
            .is_some_and(|f| !f.is_empty())
}
fn triage_text(ctx: &Value) -> String {
    if !has_triage(ctx) {
        return "No triage is available for this change; read it file by file.".into();
    }
    let triage = &ctx["triage"];
    let files = ctx["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let mut parts = vec![];
    if let Some(pull) = triage.get("pull").filter(|p| p.is_object()) {
        let risk = pull["risk"].as_f64().unwrap_or(0.0);
        let level = risk.round().clamp(0.0, 3.0) as usize;
        parts.push(format!("Pull request: {} ({:.0}% sure), {} risk ({risk:.1} on a scale of 0 trivial to 3 high).", text(pull,"kind"), pull["kindConfidence"].as_f64().unwrap_or(0.0) * 100.0, ["trivial", "low", "medium", "high"][level]));
    }
    for (priority, meaning) in [("p0", "needs human judgment (logic, security, data handling, API contracts, concurrency or money)"), ("p1", "worth a look (ordinary changes that follow what the code already does)"), ("p2", "low attention (generated files, lockfiles, formatting, renames, snapshots or trivial docs)")] {
        let rows: Vec<_> = files.iter().filter_map(|f| {
            let path = text(f,"path");
            let rating = &triage["files"][path];
            (rating["priority"] == priority).then(|| format!("- {path} ({:.0}% sure)", rating["confidence"].as_f64().unwrap_or(0.0) * 100.0))
        }).collect();
        if !rows.is_empty() { parts.push(format!("{priority}, {meaning}:\n{}", rows.join("\n"))); }
    }
    let unrated = files
        .iter()
        .filter(|f| triage["files"].get(text(f, "path")).is_none())
        .count();
    if unrated > 0 {
        parts.push(format!("Not rated: {unrated} files (binary, past the first 300, or still being rated). Judge those yourself."));
    }
    parts.join("\n\n")
}
fn files(ctx: &Value) -> &[Value] {
    ctx["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default()
}
fn generated(path: &str) -> bool {
    let name = path.rsplit('/').next().unwrap_or(path);
    [
        "package-lock.json",
        "pnpm-lock.yaml",
        "yarn.lock",
        "bun.lock",
        "bun.lockb",
        "Cargo.lock",
        "Gemfile.lock",
        "poetry.lock",
        "composer.lock",
        "go.sum",
        "uv.lock",
    ]
    .contains(&name)
        || path.ends_with(".min.js")
        || path.ends_with(".min.css")
        || path.ends_with(".map")
}
struct Hunk<'a> {
    header: &'a str,
    patch: &'a str,
    old_start: Option<u64>,
    new_start: Option<u64>,
}
// Borrow both canonical hunks and raw patches. Unselected or truncated hunks never
// allocate a second copy of their patch or a vector of numbered source lines.
fn hunks(f: &Value) -> Box<dyn Iterator<Item = Hunk<'_>> + '_> {
    if let Some(h) = f["hunks"].as_array() {
        return Box::new(h.iter().map(|h| Hunk {
            header: text(h, "header"),
            patch: text(h, "patch"),
            old_start: h["oldStart"].as_u64(),
            new_start: h["newStart"].as_u64(),
        }));
    }
    let mut rest = text(f, "patch");
    Box::new(std::iter::from_fn(move || {
        let start = if rest.starts_with("@@") {
            0
        } else {
            rest.find("\n@@")? + 1
        };
        let (header, body) = rest[start..]
            .split_once('\n')
            .unwrap_or((&rest[start..], ""));
        let end = if body.starts_with("@@") {
            0
        } else {
            body.find("\n@@").map_or(body.len(), |i| i + 1)
        };
        rest = &body[end..];
        Some(Hunk {
            header: header.trim_end_matches('\r'),
            patch: &body[..end],
            old_start: None,
            new_start: None,
        })
    }))
}
fn numbered<'a>(h: &Hunk<'a>) -> impl Iterator<Item = (char, u64, &'a str)> + 'a {
    static HEADER: OnceLock<regex::Regex> = OnceLock::new();
    let re = HEADER.get_or_init(|| regex::Regex::new(r"@@ -(\d+)(?:,\d+)? \+(\d+)").unwrap());
    let c = re.captures(h.header);
    let mut old = h
        .old_start
        .or_else(|| c.as_ref().and_then(|c| c[1].parse().ok()))
        .unwrap_or(1);
    let mut new = h
        .new_start
        .or_else(|| c.as_ref().and_then(|c| c[2].parse().ok()))
        .unwrap_or(1);
    h.patch
        .lines()
        .filter(|l| !l.starts_with("@@"))
        .map(move |l| {
            let sign = l.chars().next().unwrap_or(' ');
            let n = if sign == '\\' {
                new.saturating_sub(1)
            } else if sign == '-' {
                let n = old;
                old = old.saturating_add(1);
                n
            } else {
                let n = new;
                new = new.saturating_add(1);
                if sign != '+' {
                    old = old.saturating_add(1);
                }
                n
            };
            (sign, n, l)
        })
}
fn optional_strings(value: Option<&Value>, name: &str) -> Result<Option<Vec<String>>> {
    value
        .map(|v| {
            if let Some(one) = v.as_str() {
                return Ok(vec![one.to_owned()]);
            }
            let mut seen = HashSet::new();
            let mut strings = vec![];
            for value in v
                .as_array()
                .context(format!("{name} must be an array of strings"))?
            {
                let item = value
                    .as_str()
                    .context(format!("{name} must be an array of strings"))?;
                if seen.insert(item) {
                    strings.push(item.to_owned());
                }
            }
            Ok(strings)
        })
        .transpose()
}
async fn pages(state: &AppState, endpoint: &str) -> Result<Vec<Value>> {
    pages_with(endpoint, &mut |endpoint| async move {
        crate::source::github_request(state, Method::GET, &endpoint, None).await
    })
    .await
}
async fn pages_with<F, Fut>(endpoint: &str, request: &mut F) -> Result<Vec<Value>>
where
    F: FnMut(String) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    let mut result = vec![];
    for page in 1..=30 {
        let body = request(format!("{endpoint}?per_page=100&page={page}")).await?;
        let items = body.as_array().context("Invalid GitHub list response")?;
        result.extend(items.iter().cloned());
        if items.len() < 100 {
            return Ok(result);
        }
    }
    bail!("GitHub list exceeds the supported inventory limit; it was not fully read")
}
fn check_path(path: &str) -> Result<()> {
    if path.is_empty()
        || path.starts_with('/')
        || path.split('/').any(|s| s == ".." || s == ".git")
        || path.contains('\\')
        || path.contains('\0')
    {
        bail!("Invalid repository path");
    }
    Ok(())
}
async fn source_content(state: Option<&AppState>, ctx: &Value, path: &str) -> Result<String> {
    check_path(path)?;
    if let Some(content) = ctx["sourceSnapshots"][path].as_str() {
        return Ok(content.to_owned());
    }
    let state = state.context(
        "This source file was not captured for the sandbox; only supplied snapshots are available",
    )?;
    let sha = crate::required(ctx, "headSha")?;
    let source_ref = if ctx["ref"].is_object() {
        ctx["ref"].clone()
    } else {
        json!({"kind":"github-commit","owner":ctx["owner"],"repo":ctx["repo"],"sha":sha})
    };
    let v =
        crate::source::load_file(state, &json!({"ref":source_ref,"path":path,"sha":sha})).await?;
    v.as_str()
        .or_else(|| v["content"].as_str())
        .map(str::to_owned)
        .context("The source file is unavailable at this revision")
}
/// @cc [owner:OctavianTocan,label:product;security] rust-posted-paths-use-only-captured-diff
/// Posted file discovery MUST enumerate only captured diff paths, including with live
/// state and no checkout or head, and MUST ignore source snapshots and repository trees
/// (plan 13, "AI that Pully runs").
async fn source_paths(state: Option<&AppState>, ctx: &Value) -> Result<Vec<String>> {
    let mut paths = vec![];
    if ctx["ref"]["kind"] != "posted" {
        if let Some(state) = state {
            return crate::source::ai_tree(state, ctx).await;
        }
        // Offline snapshots are an explicit captured capability, never an implicit host checkout.
        if let Some(snapshots) = ctx["sourceSnapshots"].as_object() {
            paths.extend(snapshots.keys().cloned());
        }
    }
    paths.extend(files(ctx).iter().map(|f| text(f, "path").to_owned()));
    paths.sort();
    paths.dedup();
    Ok(paths)
}
fn path_glob(pattern: &str) -> Result<regex::Regex> {
    if pattern.starts_with(':') || pattern.contains('\0') || pattern.split('/').any(|p| p == "..") {
        bail!("Invalid source path glob");
    }
    let mut out = String::from("^");
    let mut chars = pattern.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '*' if chars.peek() == Some(&'*') => {
                chars.next();
                if chars.peek() == Some(&'/') {
                    chars.next();
                    out.push_str("(?:.*/)?");
                } else {
                    out.push_str(".*");
                }
            }
            '*' => out.push_str("[^/]*"),
            '?' => out.push_str("[^/]"),
            c => out.push_str(&regex::escape(&c.to_string())),
        }
    }
    out.push('$');
    Ok(regex::RegexBuilder::new(&out)
        .size_limit(1_000_000)
        .build()?)
}
/// @cc [owner:OctavianTocan,label:security;architecture] rust-source-search-reports-incomplete
/// Server-owned source search MUST read only its captured revision, and MUST identify any
/// read failure or bounded scan limit rather than report a partial search as complete
/// (Tavi's Rust parity request, 16 Oct 2026; agents-act-through-pr-tools).
async fn search_source(state: Option<&AppState>, ctx: &Value, args: &Value) -> Result<String> {
    let pattern = crate::required(args, "pattern")?;
    let re = regex::RegexBuilder::new(pattern)
        .case_insensitive(true)
        .size_limit(1_000_000)
        .build()
        .context("Invalid source search regular expression")?;
    let glob = args
        .get("path")
        .map(|p| {
            p.as_str()
                .context("path must be a string")
                .and_then(path_glob)
        })
        .transpose()?;
    if let Some(state) = state.filter(|_| ctx["ref"]["kind"] == "local") {
        let result = crate::source::search_local(
            state,
            crate::required(ctx, "headSha")?,
            &ctx["sourceSnapshots"],
            &re,
            glob.as_ref(),
        )
        .await?;
        return Ok(search_output(
            pattern,
            result.hits,
            result.scanned,
            result.total,
            result.unavailable,
            result.stopped,
            false,
        ));
    }
    let paths = source_paths(state, ctx).await?;
    let paths: Vec<_> = paths
        .into_iter()
        .filter(|p| glob.as_ref().is_none_or(|g| g.is_match(p)))
        .collect();
    let total = paths.len();
    let mut reads = stream::iter(paths.into_iter().map(|path| async move {
        let result = source_content(state, ctx, &path).await;
        (path, result)
    }))
    .buffered(8);
    let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
    let (mut scanned, mut unavailable, mut bytes) = (0usize, 0usize, 0usize);
    let mut hits = vec![];
    let mut stopped = None;
    loop {
        let next = match tokio::time::timeout_at(deadline, reads.next()).await {
            Ok(next) => next,
            Err(_) => {
                stopped = Some("time limit");
                break;
            }
        };
        let Some((path, result)) = next else {
            break;
        };
        scanned += 1;
        match result {
            Ok(content) => {
                if content.len() > (32 * 1024 * 1024usize).saturating_sub(bytes) {
                    stopped = Some("32 MiB source budget; narrow the path glob");
                    break;
                }
                bytes += content.len();
                if content.as_bytes().contains(&0) {
                    continue;
                }
                for (line, text) in content.lines().enumerate() {
                    if re.is_match(text) {
                        let excerpt: String = text.chars().take(2000).collect();
                        hits.push(format!("{path}:{}:{excerpt}", line + 1));
                        if hits.len() >= 200 {
                            stopped = Some("200 matches; narrow the pattern");
                            break;
                        }
                    }
                }
            }
            Err(_) => unavailable += 1,
        }
        if stopped.is_some() {
            break;
        }
    }
    Ok(search_output(
        pattern,
        hits,
        scanned,
        total,
        unavailable,
        stopped,
        state.is_none(),
    ))
}
fn search_output(
    pattern: &str,
    hits: Vec<String>,
    scanned: usize,
    total: usize,
    unavailable: usize,
    stopped: Option<&str>,
    offline: bool,
) -> String {
    let mut summary = String::new();
    if let Some(reason) = stopped {
        summary.push_str(&format!(
            "\n[Search stopped: {reason}; examined {scanned} of {total} files.]"
        ));
    }
    if unavailable > 0 {
        summary.push_str(&format!(
            "\n[Incomplete search: {unavailable} source files could not be read at this revision.]"
        ));
    }
    if offline {
        summary.push_str("\n[Offline search covers only captured source snapshots.]");
    }
    // Reserve room for the scan status even when the match excerpts fill the output.
    let mut out = BoundedOutput::new(120_000 - summary.len());
    if hits.is_empty() {
        let _ = write!(out, "No readable source line matched {pattern}.");
    } else {
        for (i, hit) in hits.iter().enumerate() {
            if write!(out, "{}{hit}", if i == 0 { "" } else { "\n" }).is_err() {
                break;
            }
        }
    }
    out.finish() + &summary
}
const TRUNCATED: &str = "\n[Output truncated]";
/// @cc [owner:OctavianTocan,label:security] rust-diff-tools-bound-rendering
/// Diff tools MUST deduplicate selections and stop rendering within their byte budget,
/// including the truncation marker (Rust parity review #6, 16 Oct 2026).
struct BoundedOutput {
    text: String,
    limit: usize,
    truncated: bool,
}
impl BoundedOutput {
    fn new(limit: usize) -> Self {
        Self {
            text: String::with_capacity(limit),
            limit,
            truncated: false,
        }
    }
    fn finish(mut self) -> String {
        if self.truncated {
            let mut end = self
                .text
                .len()
                .min(self.limit.saturating_sub(TRUNCATED.len()));
            while !self.text.is_char_boundary(end) {
                end -= 1;
            }
            self.text.truncate(end);
            self.text.push_str(TRUNCATED);
        }
        self.text
    }
}
impl std::fmt::Write for BoundedOutput {
    fn write_str(&mut self, value: &str) -> std::fmt::Result {
        if self.truncated {
            return Err(std::fmt::Error);
        }
        let available = self.limit - self.text.len();
        if value.len() <= available {
            self.text.push_str(value);
            return Ok(());
        }
        let mut end = available;
        while !value.is_char_boundary(end) {
            end -= 1;
        }
        self.text.push_str(&value[..end]);
        self.truncated = true;
        Err(std::fmt::Error)
    }
}
fn cap(mut out: String, limit: usize) -> String {
    if out.len() > limit {
        let mut end = limit;
        while !out.is_char_boundary(end) {
            end -= 1;
        }
        out.truncate(end);
        out.push_str("\n[Output truncated]");
    }
    out
}
pub(super) async fn call_for_run(
    state: &AppState,
    run: &super::Run,
    ctx: &Value,
    name: &str,
    args: &Value,
) -> Result<String> {
    super::check(run)?;
    if name == "update_grouping" {
        return super::tasks::update_grouping(state, run, ctx, args);
    }
    call(Some(state), ctx, name, args).await
}

pub async fn call(
    state: Option<&AppState>,
    ctx: &Value,
    name: &str,
    args: &Value,
) -> Result<String> {
    let posted = ctx["ref"]["kind"] == "posted";
    if posted && repository_tool(name) {
        bail!("Posted diffs have no repository source or discussion; use the captured diff tools");
    }
    if !posted && ctx["pulls"].is_array() {
        return inbox::call(state, ctx, name, args).await;
    }
    if state.is_some() {
        for field in ["owner", "repo"] {
            let value = text(ctx, field);
            if !value.is_empty()
                && !value
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')
            {
                bail!("Invalid repository identity")
            }
        }
    }
    let fs = files(ctx);
    let mut out = String::new();
    match name {
        "read_file_diff" | "read_hunks" => {
            let field = if name == "read_file_diff" {
                "paths"
            } else {
                "hunkIds"
            };
            let wanted =
                optional_strings(args.get(field), field)?.context(format!("Missing {field}"))?;
            let mut out = BoundedOutput::new(120_000);
            for item in wanted {
                let selection = if name == "read_file_diff" {
                    Some((item.as_str(), None))
                } else {
                    item.rsplit_once('#').and_then(|(path, index)| {
                        let parsed = index.parse::<usize>().ok()?;
                        (index == parsed.to_string()).then_some((path, Some(parsed)))
                    })
                };
                let mut found = false;
                if let Some((path, index, f)) = selection.and_then(|(path, index)| {
                    fs.iter()
                        .find(|f| text(f, "path") == path)
                        .map(|f| (path, index, f))
                }) {
                    let mut selected = hunks(f)
                        .enumerate()
                        .filter(|(i, _)| index.is_none_or(|wanted| wanted == *i))
                        .peekable();
                    if index.is_none() {
                        found = true;
                        if writeln!(out, "### {path} [{}]", text(f, "status")).is_err() {
                            break;
                        }
                        if selected.peek().is_none()
                            && writeln!(
                                out,
                                "No textual diff (binary, too large, rename or mode change only)."
                            )
                            .is_err()
                        {
                            break;
                        }
                    }
                    for (i, h) in selected {
                        found = true;
                        let result = if generated(path) {
                            let hint = if posted {
                                ""
                            } else {
                                " Use read_source if needed."
                            };
                            writeln!(out, "[{path}#{i}] Lockfile or generated output; its diff is left out.{hint}")
                        } else {
                            writeln!(out, "[{path}#{i}] {}", h.header).and_then(|()| {
                                for (sign, n, line) in numbered(&h) {
                                    writeln!(out, "{sign}{n:5} | {}", line.get(1..).unwrap_or(""))?;
                                }
                                Ok(())
                            })
                        };
                        if result.is_err() {
                            return Ok(out.finish());
                        }
                    }
                }
                if !found && writeln!(out, "{item}: not changed here or no such hunk.").is_err() {
                    break;
                }
            }
            return Ok(out.finish());
        }

        "search_diff" => {
            let mut out = BoundedOutput::new(120_000);
            let re = regex::RegexBuilder::new(crate::required(args, "pattern")?)
                .case_insensitive(true)
                .size_limit(1_000_000)
                .build()?;
            let mut count = 0;
            for f in fs {
                if generated(text(f, "path")) {
                    continue;
                }
                for (i, h) in hunks(f).enumerate() {
                    for (s, n, l) in numbered(&h) {
                        if (s == '+' && args["added"] != false
                            || s == '-' && args["removed"] != false)
                            && re.is_match(l.get(1..).unwrap_or(""))
                        {
                            if writeln!(
                                out,
                                "{}:{n} [{}#{i}] {l}",
                                text(f, "path"),
                                text(f, "path")
                            )
                            .is_err()
                            {
                                return Ok(out.finish());
                            }
                            count += 1;
                            if count >= 200 {
                                let _ = out
                                    .write_str("[Stopped at 200 matches; narrow the pattern.]\n");
                                return Ok(out.finish());
                            }
                        }
                    }
                }
            }
            return Ok(out.finish());
        }
        "read_source" => {
            let path = crate::required(args, "path")?;
            if path.starts_with('/') || path.split('/').any(|s| s == "..") || path.contains('\\') {
                bail!("Invalid repository path")
            };
            let content = source_content(state, ctx, path).await?;
            let start = args["startLine"].as_u64().unwrap_or(1).max(1);
            let end = args["endLine"]
                .as_u64()
                .unwrap_or(start.saturating_add(399))
                .min(start.saturating_add(399));
            let total = content.lines().count() as u64;
            if start > total {
                return Ok(format!(
                    "{path} has {total} lines; {start} is past the end."
                ));
            }
            for (i, l) in content.lines().enumerate() {
                let n = i as u64 + 1;
                if n >= start && n <= end {
                    out.push_str(&format!("{n:5} | {l}\n"));
                }
            }
            if end < total {
                out.push_str(&format!(
                    "[{} more lines; continue with startLine {}.]\n",
                    total - end,
                    end.saturating_add(1)
                ));
            }
        }
        "triage_files" => out = triage_text(ctx),
        "search_source" => return search_source(state, ctx, args).await,
        "find_files" => {
            let re = regex::RegexBuilder::new(args["pattern"].as_str().unwrap_or(".*"))
                .case_insensitive(true)
                .size_limit(1_000_000)
                .build()?;
            let paths = source_paths(state, ctx).await?;
            let matches: Vec<_> = paths.iter().filter(|p| re.is_match(p)).collect();
            for p in matches.iter().take(300) {
                out.push_str(p);
                out.push('\n')
            }
            if matches.len() > 300 {
                out.push_str(&format!(
                    "[{} more matching paths; narrow the pattern.]\n",
                    matches.len() - 300
                ));
            }
            if state.is_none() {
                out.push_str("[Offline file list covers captured context only.]\n");
            }
        }
        "read_discussion" => return discussion::read(state, ctx).await,
        _ => bail!("Unknown PR tool"),
    };
    Ok(cap(out, 120_000))
}
pub(super) async fn mcp_for_run(
    state: &AppState,
    run: &super::Run,
    ctx: &Value,
    request: Value,
) -> Value {
    if text(&request, "method") != "tools/call" {
        return mcp(Some(state), ctx, request).await;
    }
    let id = request["id"].clone();
    let result = call_for_run(
        state,
        run,
        ctx,
        text(&request["params"], "name"),
        &request["params"]["arguments"],
    )
    .await;
    json!({"jsonrpc":"2.0","id":id,"result":tool_result(result)})
}
fn tool_result(result: Result<String>) -> Value {
    match result {
        Ok(t) => json!({"content":[{"type":"text","text":super::sanitize(&t)}]}),
        Err(e) => {
            json!({"content":[{"type":"text","text":super::sanitize(&e.to_string())}],"isError":true})
        }
    }
}
pub async fn mcp(state: Option<&AppState>, context: &Value, request: Value) -> Value {
    let id = request["id"].clone();
    let result = match text(&request, "method") {
        "initialize" => Ok(
            json!({"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"pulls-review","version":"0.1.0"}}),
        ),
        "tools/list" => {
            let mut list = specs_for(context);
            if state.is_none() && !context["sourceSnapshots"].is_object() {
                list.as_array_mut()
                    .unwrap()
                    .retain(|s| s["name"] != "read_source" && s["name"] != "search_source");
            }
            if state.is_none() {
                list.as_array_mut()
                    .unwrap()
                    .retain(|s| s["name"] != "update_grouping");
            }
            Ok(json!({"tools":list}))
        }
        "tools/call" => Ok(tool_result(
            call(
                state,
                context,
                text(&request["params"], "name"),
                &request["params"]["arguments"],
            )
            .await,
        )),
        "ping" => Ok(json!({})),
        _ => Err(anyhow::anyhow!("Method not found")),
    };
    match result {
        Ok(v) => json!({"jsonrpc":"2.0","id":id,"result":v}),
        Err(e) => json!({"jsonrpc":"2.0","id":id,"error":{"code":-32602,"message":e.to_string()}}),
    }
}

#[cfg(test)]
mod tests;
