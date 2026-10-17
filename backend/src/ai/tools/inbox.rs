use super::{cap, optional_strings, pages};
use crate::{text, AppState};
use anyhow::{bail, Context, Result};
use reqwest::Method;
use serde_json::{json, Value};

pub(super) async fn call(
    state: Option<&AppState>,
    ctx: &Value,
    name: &str,
    args: &Value,
) -> Result<String> {
    let wanted = crate::required(args, "pull")?.trim();
    if !["read_pull_request", "read_pull_request_diff"].contains(&name) {
        bail!("Unknown inbox tool");
    }
    let selected_paths = optional_strings(args.get("paths"), "paths")?;
    let pull = ctx["pulls"]
        .as_array()
        .context("No inbox context")?
        .iter()
        .find(|p| {
            format!("{}/{}#{}", text(p, "owner"), text(p, "repo"), p["number"])
                .eq_ignore_ascii_case(wanted)
        })
        .context("That pull request is not on this inbox list")?;
    for field in ["owner", "repo"] {
        if !text(pull, field)
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
        {
            bail!("Invalid repository identity")
        }
    }
    let n = pull["number"].as_u64().context("Invalid pull number")?;
    let prefix = format!(
        "/repos/{}/{}/pulls/{n}",
        text(pull, "owner"),
        text(pull, "repo")
    );
    let value = if let Some(state) = state {
        let initial = crate::source::github_request(state, Method::GET, &prefix, None).await?;
        // Files are served from a moving PR endpoint, unlike checks pinned to head SHA.
        // Capture both identities before pagination and recheck before exposing any result.
        same_revisions(&initial, &initial)?;
        if name == "read_pull_request" {
            let mut pr = initial.clone();
            let repository = format!("/repos/{}/{}", text(pull, "owner"), text(pull, "repo"));
            for (field, endpoint) in [
                ("comments", format!("{repository}/issues/{n}/comments")),
                ("reviews", format!("{prefix}/reviews")),
                ("inlineComments", format!("{prefix}/comments")),
                ("files", format!("{prefix}/files")),
            ] {
                pr[field] = json!(pages(state, &endpoint).await?);
            }
            let sha = text(&pr["head"], "sha");
            if !sha.is_empty() && sha.chars().all(|c| c.is_ascii_hexdigit()) {
                let check_runs = crate::source::github_request(
                    state,
                    Method::GET,
                    &format!("{repository}/commits/{sha}/check-runs?per_page=100"),
                    None,
                )
                .await;
                let statuses = crate::source::github_request(
                    state,
                    Method::GET,
                    &format!("{repository}/commits/{sha}/status"),
                    None,
                )
                .await;
                match check_runs {
                    Ok(checks) => {
                        if checks["total_count"].as_u64().unwrap_or(0) > 100 {
                            pr["checksTruncated"] = json!(true);
                        }
                        pr["checkRuns"] = checks;
                    }
                    Err(_) => pr["checkRunsUnavailable"] = json!(true),
                }
                match statuses {
                    Ok(statuses) => pr["commitStatus"] = statuses,
                    Err(_) => pr["commitStatusUnavailable"] = json!(true),
                }
            }
            let latest = crate::source::github_request(state, Method::GET, &prefix, None).await?;
            same_revisions(&initial, &latest)?;
            return inbox_details(&pr);
        } else {
            let mut files = vec![];
            for page in 1..=30 {
                let v = crate::source::github_request(
                    state,
                    Method::GET,
                    &format!("{prefix}/files?per_page=100&page={page}"),
                    None,
                )
                .await?;
                let batch = v.as_array().context("Invalid PR files response")?;
                files.extend(
                    batch
                        .iter()
                        .filter(|f| {
                            selected_paths.as_ref().is_none_or(|paths| {
                                paths.is_empty()
                                    || paths.iter().any(|p| {
                                        f["filename"] == p.as_str()
                                            || f["previous_filename"] == p.as_str()
                                    })
                            })
                        })
                        .cloned(),
                );
                if batch.len() < 100 {
                    break;
                }
                if page == 30 {
                    bail!(
                        "PR file list exceeds the supported inventory limit; it was not fully read"
                    );
                }
            }
            let latest = crate::source::github_request(state, Method::GET, &prefix, None).await?;
            same_revisions(&initial, &latest)?;
            json!(files)
        }
    } else if name == "read_pull_request" {
        return inbox_details(pull);
    } else {
        let canonical = format!(
            "{}/{}#{}",
            text(pull, "owner"),
            text(pull, "repo"),
            pull["number"]
        );
        let mut snapshot = ctx["diffSnapshots"]
            .get(&canonical)
            .cloned()
            .context("This PR diff was not captured for the sandbox")?;
        if let (Some(items), Some(paths)) = (snapshot.as_array_mut(), selected_paths.as_ref()) {
            if !paths.is_empty() {
                items.retain(|f| {
                    paths.iter().any(|p| {
                        f["filename"] == p.as_str()
                            || f["path"] == p.as_str()
                            || f["previous_filename"] == p.as_str()
                    })
                });
            }
        }
        snapshot
    };
    Ok(cap(serde_json::to_string(&value)?, 80_000))
}
fn same_revisions(initial: &Value, latest: &Value) -> Result<()> {
    for side in ["base", "head"] {
        let expected = text(&initial[side], "sha");
        let actual = text(&latest[side], "sha");
        anyhow::ensure!(
            !expected.is_empty() && !actual.is_empty() && expected == actual,
            "Pull request revisions changed or were unavailable while reading; retry the request"
        );
    }
    Ok(())
}
fn clipped(value: &str, limit: usize) -> String {
    let value = value.trim();
    match value.char_indices().nth(limit) {
        Some((end, _)) => format!("{}…", &value[..end]),
        None => value.to_owned(),
    }
}
fn detail_fields(value: &Value, fields: &[&str]) -> Value {
    let mut out = json!({});
    for &field in fields {
        if let Some(value) = value.get(field).filter(|v| !v.is_array() && !v.is_object()) {
            out[field] = if let Some(text) = value.as_str() {
                json!(clipped(text, if field == "body" { 1_500 } else { 512 }))
            } else {
                value.clone()
            };
        }
    }
    for field in ["user", "author"] {
        if let Some(login) = value[field]["login"].as_str() {
            out[field] = json!({"login":clipped(login, 256)});
        }
    }
    out
}
fn check_state(check: &Value) -> &str {
    ["conclusion", "state", "status"]
        .into_iter()
        .find_map(|field| check[field].as_str().filter(|s| !s.is_empty()))
        .unwrap_or("")
}
fn check_failed(check: &Value) -> bool {
    [
        "failure",
        "error",
        "timed_out",
        "cancelled",
        "action_required",
        "startup_failure",
    ]
    .iter()
    .any(|state| check_state(check).eq_ignore_ascii_case(state))
}
fn detail_items(
    target: &mut Value,
    field: &str,
    source: &Value,
    fields: &[&str],
    budget: &mut usize,
    checks_first: bool,
) -> Result<()> {
    let Some(items) = source.as_array() else {
        return Ok(());
    };
    let mut projected = vec![];
    // Failures come first when the check inventory itself exceeds the output budget.
    let ordered = items
        .iter()
        .filter(|v| !checks_first || check_failed(v))
        .chain(items.iter().filter(|v| checks_first && !check_failed(v)));
    for item in ordered {
        let item = detail_fields(item, fields);
        let bytes = serde_json::to_vec(&item)?.len() + 1;
        if bytes > *budget {
            break;
        }
        *budget -= bytes;
        projected.push(item);
    }
    if projected.len() < items.len() {
        target[format!("{field}Truncated")] = json!(true);
    }
    target[field] = json!(projected);
    Ok(())
}
/// @cc [owner:OctavianTocan,label:architecture] rust-inbox-details-keep-ci
/// Inbox details MUST retain bounded check/status summaries before large collections,
/// omit file patch bodies, and disclose omitted items (Rust parity review #13,
/// 16 Oct 2026; original inbox-tools describePull behavior).
fn inbox_details(pr: &Value) -> Result<String> {
    let mut detail = detail_fields(
        pr,
        &[
            "owner",
            "repo",
            "number",
            "title",
            "html_url",
            "url",
            "state",
            "draft",
            "isDraft",
            "headRef",
            "baseRef",
            "headRefName",
            "baseRefName",
            "headSha",
            "additions",
            "deletions",
            "changed_files",
            "changedFiles",
            "mergeable",
            "mergeable_state",
            "reviewDecision",
            "created_at",
            "updated_at",
            "merged_at",
            "closed_at",
            "createdAt",
            "updatedAt",
            "mergedAt",
            "closedAt",
            "checks",
            "checksTruncated",
            "checkRunsUnavailable",
            "commitStatusUnavailable",
        ],
    );
    for field in ["head", "base"] {
        if pr[field].is_object() {
            detail[field] = detail_fields(&pr[field], &["ref", "sha", "label"]);
        }
    }
    let mut counts = [0usize; 3];
    let checks = [
        &pr["checkRuns"]["check_runs"],
        &pr["commitStatus"]["statuses"],
        &pr["statusCheckRollup"],
    ];
    for check in checks.into_iter().filter_map(Value::as_array).flatten() {
        let passing = ["success", "neutral", "skipped"]
            .iter()
            .any(|s| check_state(check).eq_ignore_ascii_case(s));
        counts[if check_failed(check) {
            0
        } else if passing {
            2
        } else {
            1
        }] += 1;
    }
    if checks.iter().any(|checks| checks.is_array()) {
        detail["checksSummary"] =
            json!({"failing":counts[0],"running":counts[1],"passing":counts[2]});
    }
    if pr["checkRuns"].is_object() {
        detail["checkRuns"] = detail_fields(&pr["checkRuns"], &["total_count"]);
    }
    if pr["commitStatus"].is_object() {
        detail["commitStatus"] =
            detail_fields(&pr["commitStatus"], &["state", "total_count", "sha"]);
    }
    // Match describePull's 6,000-character description and 1,500-character comments.
    for field in ["body", "description"] {
        if let Some(body) = pr[field].as_str() {
            detail[field] = json!(clipped(body, 6_000));
        }
    }
    // Reserve JSON keys, array brackets and explicit truncation flags. Each projected
    // item is charged before retaining it; raw patches and API metadata are never cloned.
    let mut budget = 80_000usize.saturating_sub(serde_json::to_vec(&detail)?.len() + 1_024);
    let check_fields = [
        "name",
        "context",
        "conclusion",
        "status",
        "state",
        "html_url",
        "details_url",
        "target_url",
    ];
    if pr["checkRuns"].is_object() {
        detail_items(
            &mut detail["checkRuns"],
            "check_runs",
            &pr["checkRuns"]["check_runs"],
            &check_fields,
            &mut budget,
            true,
        )?;
    }
    if pr["commitStatus"].is_object() {
        detail_items(
            &mut detail["commitStatus"],
            "statuses",
            &pr["commitStatus"]["statuses"],
            &check_fields,
            &mut budget,
            true,
        )?;
    }
    detail_items(
        &mut detail,
        "statusCheckRollup",
        &pr["statusCheckRollup"],
        &check_fields,
        &mut budget,
        true,
    )?;
    detail_items(
        &mut detail,
        "labels",
        &pr["labels"],
        &["name"],
        &mut budget,
        false,
    )?;
    for field in ["reviews", "comments", "inlineComments"] {
        detail_items(
            &mut detail,
            field,
            &pr[field],
            &[
                "body",
                "state",
                "created_at",
                "submitted_at",
                "updated_at",
                "createdAt",
                "submittedAt",
                "html_url",
                "url",
                "path",
                "line",
                "original_line",
                "side",
                "in_reply_to_id",
            ],
            &mut budget,
            false,
        )?;
    }
    detail_items(
        &mut detail,
        "files",
        &pr["files"],
        &[
            "filename",
            "path",
            "previous_filename",
            "status",
            "additions",
            "deletions",
        ],
        &mut budget,
        false,
    )?;
    let output = serde_json::to_string(&detail)?;
    anyhow::ensure!(
        output.len() <= 80_000,
        "PR detail metadata exceeds the supported output budget"
    );
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn live_inbox_requires_stable_base_and_head_revisions() {
        let captured = json!({"base":{"sha":"base"},"head":{"sha":"head"}});
        assert!(same_revisions(&captured, &captured).is_ok());
        for side in ["base", "head"] {
            let mut changed = captured.clone();
            changed[side]["sha"] = json!("advanced");
            assert!(same_revisions(&captured, &changed).is_err());
            changed[side]["sha"] = Value::Null;
            assert!(same_revisions(&captured, &changed).is_err());
            assert!(same_revisions(&changed, &changed).is_err());
        }
    }
    #[tokio::test]
    async fn inbox_details_preserve_ci_without_file_patches() {
        let ctx = json!({"pulls":[{
            "owner":"o","repo":"r","number":1,"title":"large change","body":"D".repeat(7_000),
            "files":[{"filename":"new.rs","previous_filename":"old.rs","status":"renamed","additions":1,"deletions":2,"patch":"P".repeat(81_000)}],
            "comments":[{"body":"C".repeat(2_000),"user":{"login":"reviewer"}}],
            "checkRuns":{"total_count":1,"check_runs":[{"name":"integration tests","status":"completed","conclusion":"failure","output":{"text":"noise".repeat(20_000)}}]},
            "commitStatus":{"state":"failure","statuses":[{"context":"deploy","state":"pending"}]}
        }]});
        let result = call(None, &ctx, "read_pull_request", &json!({"pull":"o/r#1"}))
            .await
            .unwrap();
        assert!(result.len() < 80_000);
        let detail: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(
            detail["checksSummary"],
            json!({"failing":1,"running":1,"passing":0})
        );
        assert_eq!(
            detail["checkRuns"]["check_runs"][0]["name"],
            "integration tests"
        );
        assert_eq!(detail["commitStatus"]["state"], "failure");
        assert_eq!(detail["files"][0]["previous_filename"], "old.rs");
        assert!(detail["files"][0].get("patch").is_none());
        assert!(detail["checkRuns"]["check_runs"][0].get("output").is_none());
        assert_eq!(text(&detail, "body").chars().count(), 6_001);
        assert_eq!(text(&detail["comments"][0], "body").chars().count(), 1_501);
        assert_eq!(detail["comments"][0]["user"]["login"], "reviewer");
        assert!(result.find("checksSummary") < result.find("files"));
    }
    #[test]
    fn inbox_details_bound_collections_and_preserve_unavailable_checks() {
        let pr = json!({"title":"busy discussion","checkRunsUnavailable":true,
            "commitStatus":{"state":"failure","statuses":[{"context":"build","state":"failure"}]},
            "comments":vec![json!({"body":"é".repeat(2_000),"user":{"login":"reviewer"}});100],
            "inlineComments":[{"body":"review note","path":"a.rs","line":4}],
            "files":[{"filename":"a.rs","patch":"large patch"}]
        });
        let result = inbox_details(&pr).unwrap();
        assert!(result.len() <= 80_000);
        let detail: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(detail["checkRunsUnavailable"], true);
        assert_eq!(detail["commentsTruncated"], true);
        assert_eq!(detail["commitStatus"]["statuses"][0]["context"], "build");
        assert_eq!(detail["checksSummary"]["failing"], 1);
    }
    #[tokio::test]
    async fn inbox_cannot_read_an_unlisted_pr() {
        let ctx = json!({"pulls":[{"owner":"o","repo":"r","number":1,"title":"safe"}]});
        assert!(
            call(None, &ctx, "read_pull_request", &json!({"pull":"o/r#2"}))
                .await
                .is_err()
        );
        assert!(
            call(None, &ctx, "read_pull_request", &json!({"pull":"o/r#1"}))
                .await
                .unwrap()
                .contains("safe")
        );
    }
}
