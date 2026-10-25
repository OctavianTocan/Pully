//! Source boundary: canonical diff data, fixed-origin GitHub access, and read-only local git.
pub use crate::now_ms as now;
use crate::AppState;
use anyhow::{bail, ensure, Context, Result};
use serde_json::{json, Value};
use std::sync::Arc;
pub(crate) mod content_cache;
mod github;
pub(crate) mod github_read;
mod grouping;
mod lists;
mod local;
mod patch;
pub use crate::{encode_component as enc, text as s};
pub(crate) use github::{fetch_with as fetch_github_with, file_with as github_file_with};
pub use github::{refresh_cached as refresh_cached_pr, token as github_token};
pub use grouping::groups;
pub use local::search as search_local;
pub use patch::{parse_patch, parse_patch_untruncated};

/// Server-selected context provenance. This is never accepted from a client as authority.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct WorkspaceRepository {
    pub owner: String,
    pub repo: String,
    pub repository_id: u64,
}

/// Cached AI tools retain the workspace's selected repository authority as well as its
/// session binding. This is also checked after awaited tool work before publishing it.
pub(crate) async fn workspace_repositories(state: &AppState, value: &Value) -> Result<()> {
    workspace_repository_provenance(state, value)
        .await
        .map(|_| ())
}

pub(crate) async fn workspace_repository_provenance(
    state: &AppState,
    value: &Value,
) -> Result<Vec<WorkspaceRepository>> {
    if !state.personal_workspace {
        return Ok(Vec::new());
    }
    fn collect(
        value: &Value,
        out: &mut std::collections::BTreeMap<(String, String), Option<u64>>,
    ) -> Result<()> {
        match value {
            Value::Array(items) => {
                for value in items {
                    collect(value, out)?;
                }
            }
            Value::Object(fields) => {
                if let (Some(owner), Some(repo)) = (
                    fields.get("owner").and_then(Value::as_str),
                    fields.get("repo").and_then(Value::as_str),
                ) {
                    let expected = value["repositoryId"].as_u64();
                    let key = (owner.to_ascii_lowercase(), repo.to_ascii_lowercase());
                    if let Some(previous) = out.get(&key).copied().flatten() {
                        ensure!(
                            expected.is_none_or(|id| id == previous),
                            "Conflicting repository identities"
                        );
                    }
                    let id = out.entry(key).or_insert(expected);
                    if expected.is_some() {
                        *id = expected;
                    }
                }
                for value in fields
                    .values()
                    .filter(|value| value.is_object() || value.is_array())
                {
                    collect(value, out)?;
                }
            }
            _ => {}
        }
        Ok(())
    }
    let mut repositories = std::collections::BTreeMap::new();
    collect(value, &mut repositories)?;
    use futures_util::{stream, StreamExt, TryStreamExt};
    let captured = stream::iter(repositories.into_iter().map(
        |((owner, repo), captured_id)| async move {
            ensure!(
                crate::public_api::github::valid_segment(&owner, 39)
                    && crate::public_api::github::valid_segment(&repo, 100),
                "Choose a valid GitHub repository"
            );
            let key = format!("workspace-repository:{owner}/{repo}");
            let expected = state.host.storage.get_async(&key).await?;
            let repository = github_request(
                state,
                reqwest::Method::GET,
                &format!("/repos/{owner}/{repo}"),
                None,
            )
            .await?;
            let id = repository["id"]
                .as_u64()
                .filter(|id| *id > 0)
                .context("GitHub returned an invalid repository")?;
            ensure!(
                repository["name"]
                    .as_str()
                    .is_some_and(|name| name.eq_ignore_ascii_case(&repo))
                    && repository["owner"]["login"]
                        .as_str()
                        .is_some_and(|login| login.eq_ignore_ascii_case(&owner)),
                "GitHub returned a different repository"
            );
            ensure!(
                captured_id.is_none_or(|expected| expected == id),
                "This repository's captured identity changed"
            );
            ensure!(
                expected.as_ref().is_none_or(|old| old.as_u64() == Some(id)),
                "This repository's identity changed"
            );
            if expected.is_none() {
                state.host.storage.set_async(&key, &json!(id)).await?;
            }
            Ok::<_, anyhow::Error>(WorkspaceRepository {
                owner,
                repo,
                repository_id: id,
            })
        },
    ))
    .buffer_unordered(3)
    .try_collect::<Vec<_>>()
    .await?;
    state.identity.ensure_current()?;
    Ok(captured)
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
pub async fn github_proxy(state: &AppState, args: &Value) -> Result<Value> {
    github_proxy_with(args, |method, path, body, accept| {
        github::raw(state, method, path, body, accept)
    })
    .await
}
/// @cc [owner:OctavianTocan,label:architecture;security] rust-github-proxy-shares-policy
/// Browser and source GitHub proxy dispatch MUST share path validation, method and
/// representation defaults and the status/headers/body envelope; the GitHub boundary
/// MUST retain fixed-origin credentials and reject redirects (16 Oct 2026 review fixes,
/// R13 #11; web-github-operations-stay-server-side).
async fn github_proxy_with<'a, F, Fut>(args: &'a Value, request: F) -> Result<Value>
where
    F: FnOnce(reqwest::Method, &'a str, Option<Value>, &'a str) -> Fut,
    Fut: std::future::Future<Output = Result<(u16, Value, String)>>,
{
    let path = crate::required(args, "path")?;
    let base = url::Url::parse("https://api.github.com")?;
    let url = base.join(path)?;
    if url.origin() != base.origin()
        || !path.starts_with('/')
        || path.starts_with("//")
        || !(path.starts_with("/repos/")
            || path == "/user"
            || path.starts_with("/user/")
            || path.starts_with("/user?")
            || path.starts_with("/search/")
            || path == "/graphql")
    {
        bail!("Unsupported GitHub API path")
    }
    let method = match s(args, "method") {
        "" | "GET" => reqwest::Method::GET,
        "POST" => reqwest::Method::POST,
        "PATCH" => reqwest::Method::PATCH,
        "PUT" => reqwest::Method::PUT,
        "DELETE" => reqwest::Method::DELETE,
        _ => bail!("Unsupported HTTP method"),
    };
    let accept = match s(args, "accept") {
        "" => "application/vnd.github+json",
        v @ ("application/vnd.github+json"
        | "application/vnd.github.raw"
        | "application/vnd.github.diff"
        | "application/vnd.github.sha") => v,
        _ => bail!("Unsupported response type"),
    };
    let body = args.get("body").filter(|body| !body.is_null()).cloned();
    let (status, headers, body) = request(method, path, body, accept).await?;
    Ok(json!({"status":status,"headers":headers,"body":body}))
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
            .host
            .storage
            .get_async(&format!("diff:{}", s(args, "key")))
            .await?
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
        let root = local::git(state.host.checkout()?, &["rev-parse", "--show-toplevel"]).await?;
        r["repo"] = json!(root.trim());
    }
    let scope = serialize_ref(&r)?;
    if let Some(content) = content_cache::get_async(&state.host.storage, &scope, sha, path).await? {
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
        content_cache::set_async(&state.host.storage, &scope, sha, path, content).await?;
    }
    Ok(result)
}
/// Eviction must discard every captured full-file revision of the removed diff.
pub fn remove_file_scope(state: &AppState, scope: &str) -> Result<()> {
    content_cache::remove_scope(&state.host.storage, scope)
}
pub fn put_diff(state: &AppState, key: &str, diff: &Value) -> Result<Value> {
    crate::review::put_diff(state, key, diff)
}
async fn open(state: &Arc<AppState>, r: &Value, args: &Value) -> Result<Value> {
    let mut canonical = r.clone();
    if r["kind"] == "local" {
        let root = local::git(state.host.checkout()?, &["rev-parse", "--show-toplevel"]).await?;
        canonical["repo"] = json!(root.trim());
    }
    let r = &canonical;
    let key = serialize_ref(r)?;
    let existing = state.host.storage.get_async(&format!("diff:{key}")).await?;
    if args["fresh"] != true && r["kind"] == "github-pr" {
        if let Some(e) = &existing {
            if e["diff"]["files"].is_array() {
                let storage_key = format!("diff:{key}");
                let viewed = state
                    .host
                    .storage
                    .transaction(move |store| {
                        store.update(&storage_key, |current| {
                            let mut value =
                                current.context("The cached diff was removed; reopen it.")?;
                            value["lastViewedAt"] = json!(now());
                            Ok(value)
                        })
                    })
                    .await?;
                let diff = viewed["diff"].clone();
                let owned_state = state.clone();
                let expected = Some(viewed);
                let owned_key = key.clone();
                let cached_diff = diff.clone();
                crate::rt::spawn(async move {
                    let diff = cached_diff;
                    if let Ok(Some(fresh)) = github::refresh_cached(&owned_state, &diff).await {
                        let _ = crate::store::blocking(move || {
                            crate::review::put_diff_if_unchanged(
                                &owned_state,
                                &owned_key,
                                &fresh,
                                expected.as_ref(),
                                false,
                            )
                        })
                        .await;
                    }
                });
                return Ok(opened_response(&key, &diff, args["lite"] == true, true));
            }
        }
    }
    let diff = fetch(state, r).await?;
    let key = serialize_ref(&diff["ref"])?;
    let owned_state = state.clone();
    let owned_key = key.clone();
    let saved = crate::store::blocking(move || {
        crate::review::put_diff_if_unchanged(
            &owned_state,
            &owned_key,
            &diff,
            existing.as_ref(),
            true,
        )
    })
    .await?;
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
    if let Err(error) = state.identity.ensure_current() {
        return Some(Err(error));
    }
    handle_inner(state, name, args).await.map(|result| {
        state.identity.ensure_current()?;
        result
    })
}

async fn handle_inner(state: &Arc<AppState>, name: &str, args: Value) -> Option<Result<Value>> {
    if state.host.checkout().is_err()
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
                let root = local::git(state.host.checkout()?, &["rev-parse", "--show-toplevel"]).await?;
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
            &json!({"kind":"local","target":s(&args,"target"),"repo":state.host.cwd.as_ref().expect("checkout checked").to_string_lossy()}),
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
        "github-authenticated" => github::token(state).await.map(|token| json!(token.is_some())),
        "github-request" => github_proxy(state, &args).await,
        "repo-pulls" => lists::repo_pulls(state, &args).await,
        "my-pulls" => lists::my_pulls(state, &args)
            .await
            .map(|v| v["pulls"].clone()),
        "my-pulls-cached" => {
            async {
                let key = format!("my-pulls:{}", args["state"].as_str().unwrap_or("open"));
                match state.host.storage.get_async(&key).await {
                    Err(e) => Err(e),
                    Ok(Some(mut v)) => {
                        let refreshing = args["revalidate"] != false
                            && now().saturating_sub(v["fetchedAt"].as_u64().unwrap_or(0)) > 30000;
                        if refreshing {
                            let _ = lists::start_inbox_refresh(state, &args).await?;
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
                                lists::warm_repositories(state, pulls).await?;
                            }
                        }
                        v = crate::jev::apply_pulls(state, v).await?;
                        v["refreshing"] =
                            json!(refreshing || lists::inbox_refreshing(state, &args).await?);
                        Ok(v)
                    }
                    Ok(None) => {
                        if args["revalidate"] != false {
                            let _ = lists::start_inbox_refresh(state, &args).await?;
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

#[cfg(test)]
mod contention_tests {
    use super::*;
    #[tokio::test(flavor = "current_thread")]
    async fn cached_and_cold_opens_yield_under_storage_contention() {
        let root = std::env::temp_dir().join(format!("source-contention-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(root.clone(), root.clone()).unwrap();
        // An invalid remote number makes the detached refresh fail locally, so the
        // fixture verifies the cached path without contacting GitHub.
        let reference =
            json!({"kind":"github-pr","owner":"owner","repo":"repo","number":"offline"});
        let key = serialize_ref(&reference).unwrap();
        put_diff(
            &state,
            &key,
            &json!({"ref":reference,"title":"Cached","files":[]}),
        )
        .unwrap();
        let result = crate::store::test_support::HeldLock::storage(&state.host.storage)
            .assert_yields(open(&state, &reference, &json!({})))
            .await
            .unwrap();
        assert_eq!(result["diff"]["title"], "Cached");
        assert_eq!(result["stale"], true);
        assert!(
            state
                .host
                .storage
                .get(&format!("diff:{key}"))
                .unwrap()
                .unwrap()["lastViewedAt"]
                .as_u64()
                .unwrap()
                > 0
        );
        let paste = json!({"kind":"paste","hash":"new","text":"diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n"});
        let result = crate::store::test_support::HeldLock::storage(&state.host.storage)
            .assert_yields(open(&state, &paste, &json!({})))
            .await
            .unwrap();
        assert_eq!(result["diff"]["files"][0]["path"], "a");
        std::fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
mod github_proxy_tests {
    use super::*;
    #[tokio::test]
    async fn shared_github_proxy_preserves_defaults_methods_and_envelope() {
        for method in ["", "GET", "POST", "PUT", "PATCH", "DELETE"] {
            for accept in [
                "",
                "application/vnd.github+json",
                "application/vnd.github.raw",
                "application/vnd.github.diff",
                "application/vnd.github.sha",
            ] {
                let args = json!({"path":"/repos/o/r/pulls/1","method":method,"accept":accept,"body":null});
                let result = github_proxy_with(
                    &args,
                    |actual_method, path, body, actual_accept| async move {
                        assert_eq!(
                            actual_method.as_str(),
                            if method.is_empty() { "GET" } else { method }
                        );
                        assert_eq!(
                            actual_accept,
                            if accept.is_empty() {
                                "application/vnd.github+json"
                            } else {
                                accept
                            }
                        );
                        assert_eq!(path, "/repos/o/r/pulls/1");
                        assert!(body.is_none());
                        Ok((
                            422,
                            json!({"content-type":"text/plain"}),
                            "provider response".into(),
                        ))
                    },
                )
                .await
                .unwrap();
                assert_eq!(
                    result,
                    json!({"status":422,"headers":{"content-type":"text/plain"},"body":"provider response"})
                );
            }
        }
        let args = json!({"path":"/graphql","body":{"query":"query { viewer { login } }"}});
        github_proxy_with(&args, |method, path, body, accept| async move {
            assert_eq!(method, reqwest::Method::GET);
            assert_eq!(path, "/graphql");
            assert_eq!(accept, "application/vnd.github+json");
            assert_eq!(body, Some(json!({"query":"query { viewer { login } }"})));
            Ok((200, json!({}), "{}".into()))
        })
        .await
        .unwrap();
    }
    #[tokio::test]
    async fn shared_github_proxy_rejects_invalid_policy_before_network() {
        for args in [
            json!({}),
            json!({"path":"https://example.invalid/repos/o/r"}),
            json!({"path":"//example.invalid/repos/o/r"}),
            json!({"path":"/admin"}),
            json!({"path":"/user","method":"HEAD"}),
            json!({"path":"/user","accept":"text/html"}),
        ] {
            assert!(github_proxy_with(&args, |_, _, _, _| async {
                panic!("invalid proxy request must fail before networking")
            })
            .await
            .is_err());
        }
    }
}
