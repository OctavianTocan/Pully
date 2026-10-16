//! Source boundary: canonical diff data, fixed-origin GitHub access, and read-only local git.
pub use crate::now_ms as now;
use crate::AppState;
use anyhow::{bail, ensure, Context, Result};
use serde_json::{json, Value};
use std::sync::Arc;
mod content_cache;
mod github;
mod grouping;
mod lists;
mod local;
mod patch;
pub use github::{raw as github_raw, refresh_cached as refresh_cached_pr, token as github_token};
pub use grouping::groups;
pub use local::search as search_local;
pub use patch::parse_patch;
pub fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or("")
}
pub fn enc(s: &str) -> String {
    s.as_bytes()
        .iter()
        .map(|b| {
            if b.is_ascii_alphanumeric() || b"-_.!~*'()".contains(b) {
                (*b as char).to_string()
            } else {
                format!("%{b:02X}")
            }
        })
        .collect()
}

pub fn serialize_ref(r: &Value) -> Result<String> {
    Ok(match s(r, "kind") {
        "github-pr" => format!(
            "github:{}/{}#{}",
            s(r, "owner"),
            s(r, "repo"),
            r["number"]
                .as_str()
                .map(str::to_string)
                .unwrap_or_else(|| r["number"].to_string())
        ),
        "github-compare" => format!(
            "github:{}/{}@{}...{}",
            s(r, "owner"),
            s(r, "repo"),
            s(r, "base"),
            s(r, "head")
        ),
        "github-commit" => format!("github:{}/{}@{}", s(r, "owner"), s(r, "repo"), s(r, "sha")),
        "paste" => format!("paste:{}", s(r, "hash")),
        "posted" => format!("posted:{}", s(r, "id")),
        "local" => format!("local:{}@{}", enc(s(r, "repo")), enc(s(r, "target"))),
        _ => bail!("Unknown source kind"),
    })
}
pub async fn github_request(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
) -> Result<Value> {
    github::request(state, method, path, body).await
}
pub async fn fetch(state: &AppState, r: &Value) -> Result<Value> {
    match s(r, "kind") {
        "local" => local::fetch_local(state, s(r, "target")).await,
        "paste" => {
            let text = r["text"]
                .as_str()
                .context("A pasted diff needs its patch text")?;
            Ok(
                json!({"ref":{"kind":"paste","hash":patch::hash(text)},"title":r["title"].as_str().unwrap_or("Pasted diff"),"description":"","files":parse_patch(text)}),
            )
        }
        _ => github::fetch(state, r).await,
    }
}
pub async fn load_file(state: &AppState, args: &Value) -> Result<Value> {
    let mut r = if args["ref"].is_object() {
        args["ref"].clone()
    } else if !s(args, "key").is_empty() {
        state
            .store
            .get(&format!("diff:{}", s(args, "key")))?
            .context("Diff is not cached")?["diff"]["ref"]
            .clone()
    } else {
        json!({"kind":"local","target":s(args,"target")})
    };
    let path = args["path"].as_str().context("Missing file path")?;
    let sha = args["sha"].as_str().context("Missing file revision")?;
    if r["kind"] == "local" {
        if !local::allowed_path(state, path).await? {
            return Ok(Value::Null);
        }
        let root = local::git(&state.cwd, &["rev-parse", "--show-toplevel"]).await?;
        r["repo"] = json!(root.trim());
    }
    let scope = serialize_ref(&r)?;
    if let Some(content) = content_cache::get_async(&state.store, &scope, sha, path).await? {
        return Ok(json!(content));
    }
    let result = match s(&r, "kind") {
        "local" => local::file(state, path, sha).await,
        "github-pr" | "github-commit" | "github-compare" => {
            github::file(state, &r, path, sha).await
        }
        _ => bail!("This source does not provide full file content"),
    }?;
    if let Some(content) = result.as_str() {
        content_cache::set_async(&state.store, &scope, sha, path, content).await?;
    }
    Ok(result)
}
/// Eviction must discard every captured full-file revision of the removed diff.
pub fn remove_file_scope(state: &AppState, scope: &str) -> Result<()> {
    content_cache::remove_scope(&state.store, scope)
}
pub fn put_diff(state: &AppState, key: &str, diff: &Value) -> Result<Value> {
    crate::review::put_diff(state, key, diff)
}
async fn open(state: &Arc<AppState>, r: &Value, args: &Value) -> Result<Value> {
    let mut canonical = r.clone();
    if r["kind"] == "local" {
        let root = local::git(&state.cwd, &["rev-parse", "--show-toplevel"]).await?;
        canonical["repo"] = json!(root.trim());
    }
    let r = &canonical;
    let key = serialize_ref(r)?;
    let existing = state.store.get(&format!("diff:{key}"))?;
    if args["fresh"] != true && r["kind"] == "github-pr" {
        if let Some(e) = &existing {
            if e["diff"]["files"].is_array() {
                let viewed = state.store.update(&format!("diff:{key}"), |current| {
                    let mut value = current.context("The cached diff was removed; reopen it.")?;
                    value["lastViewedAt"] = json!(now());
                    Ok(value)
                })?;
                let diff = viewed["diff"].clone();
                let owned_state = state.clone();
                let expected = Some(viewed);
                let owned_key = key.clone();
                let cached_diff = diff.clone();
                tokio::spawn(async move {
                    let diff = cached_diff;
                    if let Ok(Some(fresh)) = github::refresh_cached(&owned_state, &diff).await {
                        let _ = crate::review::put_diff_if_unchanged(
                            &owned_state,
                            &owned_key,
                            &fresh,
                            expected.as_ref(),
                            false,
                        );
                    }
                });
                return Ok(opened_response(&key, &diff, args["lite"] == true, true));
            }
        }
    }
    let diff = fetch(state, r).await?;
    let key = serialize_ref(&diff["ref"])?;
    let saved = crate::review::put_diff_if_unchanged(state, &key, &diff, existing.as_ref(), true)?;
    let diff = saved["diff"].clone();
    Ok(opened_response(&key, &diff, args["lite"] == true, false))
}
fn opened_response(key: &str, diff: &Value, lite: bool, stale: bool) -> Value {
    let groups = groups(diff);
    let mut sent = diff.clone();
    let mut omitted = 0;
    let mut total = 0;
    if lite {
        if let Some(files) = sent["files"].as_array_mut() {
            for file in files {
                let size: usize = file["hunks"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(|h| h["patch"].as_str())
                    .map(str::len)
                    .sum();
                if size == 0 || (size < 150_000 && total + size < 600_000) {
                    total += size;
                } else {
                    file["hunks"] = json!([]);
                    file["patchOmitted"] = json!(true);
                    omitted += 1;
                }
            }
        }
    }
    let mut out = json!({"key":key,"diff":sent,"groups":groups,"omitted":omitted});
    if stale {
        out["stale"] = json!(true);
    }
    out
}
pub async fn handle(state: &Arc<AppState>, name: &str, args: Value) -> Option<Result<Value>> {
    if !state.has_checkout
        && matches!(
            name,
            "repo-info"
                | "source-key"
                | "source-fetch"
                | "source-fingerprint"
                | "source-load-file"
                | "local-diff"
                | "local-open"
        )
    {
        return Some(Err(anyhow::anyhow!(crate::host::NO_CHECKOUT)));
    }
    if matches!(name, "local-diff" | "local-open") && !args["target"].is_string() {
        return Some(Err(anyhow::anyhow!("A local diff needs a git target.")));
    }

    if matches!(name, "my-pulls" | "my-pulls-cached")
        && !["open", "closed"].contains(&args["state"].as_str().unwrap_or("open"))
    {
        return Some(Err(anyhow::anyhow!("Invalid pull state")));
    }
    Some(match name {
        "repo-info" => local::info(state).await,
        "source-key" => {
            async {
                let root = local::git(&state.cwd, &["rev-parse", "--show-toplevel"]).await?;
                Ok(json!(serialize_ref(
                    &json!({"kind":"local","repo":root.trim(),"target":s(&args,"target")})
                )?))
            }
            .await
        }
        "source-fetch" => local::fetch_local(state, s(&args, "target")).await,
        "source-fingerprint" => local::fetch_local(state, s(&args, "target"))
            .await
            .map(|d| d["head"]["sha"].clone()),
        "source-load-file" => load_file(state, &args).await,
        "local-diff" | "local-open" => open(
            state,
            &json!({"kind":"local","target":s(&args,"target"),"repo":state.cwd.to_string_lossy()}),
            &args,
        )
        .await,
        "paste-open" | "paste-diff" => {
            let mut r = args.clone();
            r["kind"] = json!("paste");
            r["hash"] = json!(patch::hash(s(&args, "text")));
            open(state, &r, &args).await
        }
        "github-pr" | "github-compare" | "github-commit" => {
            let mut r = args.clone();
            r["kind"] = json!(name);
            if r["number"].is_number() {
                r["number"] = json!(r["number"].to_string())
            }
            open(state, &r, &args).await
        }
        "github-authenticated" => Ok(json!(github::token().await.is_some())),
        "github-request" => {
            async {
                let method = reqwest::Method::from_bytes(
                    args["method"].as_str().unwrap_or("GET").as_bytes(),
                )?;
                ensure!(
                    [
                        reqwest::Method::GET,
                        reqwest::Method::POST,
                        reqwest::Method::PUT,
                        reqwest::Method::PATCH,
                        reqwest::Method::DELETE
                    ]
                    .contains(&method),
                    "Unsupported method"
                );
                let accept = args["accept"]
                    .as_str()
                    .unwrap_or("application/vnd.github+json");
                ensure!(
                    [
                        "application/vnd.github+json",
                        "application/vnd.github.raw",
                        "application/vnd.github.diff",
                        "application/vnd.github.sha"
                    ]
                    .contains(&accept),
                    "Unsupported GitHub representation"
                );
                let (code, headers, body) = github::raw(
                    state,
                    method,
                    s(&args, "path"),
                    args.get("body").cloned(),
                    accept,
                )
                .await?;
                Ok(json!({"status":code,"headers":headers,"body":body}))
            }
            .await
        }
        "repo-pulls" => lists::repo_pulls(state, &args).await,
        "my-pulls" => lists::my_pulls(state, &args)
            .await
            .map(|v| v["pulls"].clone()),
        "my-pulls-cached" => {
            async {
                let key = format!("my-pulls:{}", args["state"].as_str().unwrap_or("open"));
                match state.store.get(&key) {
                    Err(e) => Err(e),
                    Ok(Some(mut v)) => {
                        let refreshing = args["revalidate"] != false
                            && now().saturating_sub(v["fetchedAt"].as_u64().unwrap_or(0)) > 30000;
                        if refreshing {
                            let state = state.clone();
                            let args = args.clone();
                            tokio::spawn(async move {
                                let _ = lists::my_pulls(&state, &args).await;
                            });
                        }
                        if args["classify"] == true
                            && args["state"].as_str().unwrap_or("open") == "open"
                        {
                            if let Some(pulls) = v["pulls"].as_array() {
                                crate::jev::classify_pulls(state, pulls).await?;
                            }
                        }
                        if args["revalidate"] != false
                            && args["state"].as_str().unwrap_or("open") == "open"
                        {
                            if let Some(pulls) = v["pulls"].as_array() {
                                lists::warm_repositories(state, pulls);
                            }
                        }
                        v = crate::jev::apply_pulls(state, v).await?;
                        v["refreshing"] =
                            json!(refreshing || state.my_pulls_lock.try_lock().is_err());
                        Ok(v)
                    }
                    Ok(None) => {
                        if args["revalidate"] != false {
                            let state = state.clone();
                            let args = args.clone();
                            tokio::spawn(async move {
                                let _ = lists::my_pulls(&state, &args).await;
                            });
                        }
                        Ok(Value::Null)
                    }
                }
            }
            .await
        }
        _ => return None,
    })
}

/// Resolve storage placement through the same bounded, read-only Git boundary.
pub async fn git_common_dir(cwd: &std::path::Path) -> Result<String> {
    local::git(cwd, &["rev-parse", "--git-common-dir"]).await
}

/// Complete server-owned tree for the captured AI context. GitHub's truncated
/// recursive inventories are errors, never a misleading complete tool response.
pub async fn ai_tree(state: &AppState, context: &Value) -> Result<Vec<String>> {
    let sha = context["headSha"]
        .as_str()
        .context("Missing captured revision")?;
    ensure!(!sha.is_empty(), "Missing captured revision");
    if context["ref"]["kind"] == "local" {
        return local::tree(state, sha).await;
    }
    ensure!(
        matches!(
            s(&context["ref"], "kind"),
            "github-pr" | "github-commit" | "github-compare"
        ) || (context["ref"].is_null() && !s(context, "owner").is_empty()),
        "This source does not provide a repository tree"
    );
    let reference = if context["ref"].is_object() {
        &context["ref"]
    } else {
        context
    };
    let prefix = github::prefix(reference)?;
    let response = github::request(
        state,
        reqwest::Method::GET,
        &format!("{prefix}/git/trees/{}?recursive=1", enc(sha)),
        None,
    )
    .await?;
    ensure!(
        response["truncated"] == false,
        "GitHub tree is incomplete; narrow the source before searching"
    );
    let entries = response["tree"]
        .as_array()
        .context("GitHub returned an invalid repository tree")?;
    let mut paths = entries
        .iter()
        .filter(|v| v["type"] == "blob")
        .map(|v| {
            v["path"]
                .as_str()
                .map(str::to_owned)
                .context("GitHub tree entry has no path")
        })
        .collect::<Result<Vec<_>>>()?;
    paths.sort();
    paths.dedup();
    Ok(paths)
}
