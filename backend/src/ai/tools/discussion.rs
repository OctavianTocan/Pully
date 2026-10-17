use super::{pages_with, BoundedOutput};
use crate::{text, AppState};
use anyhow::{ensure, Context, Result};
use reqwest::Method;
use serde_json::Value;
use std::fmt::Write;

/// @cc [owner:OctavianTocan,label:architecture;security] rust-discussion-reads-selected-pr-description
/// Live discussion reads MUST retrieve the selected PR's title and body before its
/// comments and reviews, including when canonical AiPrContext has no description.
/// Reads MUST retain the selected identity and bounded output; offline snapshots MUST
/// remain usable without GitHub access (Tavi's Rust parity request, 16 Oct 2026;
/// original packages/cli/src/ai/pr-tools.ts readDiscussion).
pub(super) async fn read(state: Option<&AppState>, ctx: &Value) -> Result<String> {
    read_with(ctx, state.is_some(), |endpoint| async move {
        crate::source::github_request(
            state.context("Live source reads are unavailable in this sandbox")?,
            Method::GET,
            &endpoint,
            None,
        )
        .await
    })
    .await
}

async fn read_with<F, Fut>(ctx: &Value, live: bool, mut request: F) -> Result<String>
where
    F: FnMut(String) -> Fut,
    Fut: std::future::Future<Output = Result<Value>>,
{
    if ctx["number"].is_null() {
        return Ok(
            "This change is not a pull request, so it has no description or review discussion."
                .into(),
        );
    }
    let number = ctx["number"]
        .as_u64()
        .or_else(|| text(ctx, "number").parse().ok())
        .filter(|_| live);
    let repository = format!("/repos/{}/{}", text(ctx, "owner"), text(ctx, "repo"));
    let mut out = BoundedOutput::new(120_000);
    if let Some(number) = number {
        let pr = request(format!("{repository}/pulls/{number}")).await?;
        ensure!(pr.is_object(), "Invalid GitHub PR response");
        if writeln!(
            out,
            "{}\n\nDescription:\n{}",
            text(&pr, "title"),
            text(&pr, "body")
        )
        .is_err()
        {
            return Ok(out.finish());
        }
    } else if out.write_str(text(ctx, "description")).is_err() {
        return Ok(out.finish());
    }
    if let Some(snapshot) = ctx.get("discussionSnapshot") {
        if out.write_str(&serde_json::to_string(snapshot)?).is_err() {
            return Ok(out.finish());
        }
    }
    if let Some(number) = number {
        for suffix in [
            format!("issues/{number}/comments"),
            format!("pulls/{number}/comments"),
            format!("pulls/{number}/reviews"),
        ] {
            let comments = pages_with(&format!("{repository}/{suffix}"), &mut request).await?;
            if writeln!(out, "{}", serde_json::to_string(&comments)?).is_err() {
                break;
            }
        }
    }
    Ok(out.finish())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{cell::RefCell, future::ready};

    #[tokio::test]
    async fn canonical_context_reads_selected_pr_body_before_empty_discussion() {
        let ctx = json!({
            "owner":"selected-owner", "repo":"selected-repo", "number":17,
            "title":"Captured title", "headSha":"abc123", "baseSha":"def456", "files":[]
        });
        assert!(ctx.get("description").is_none());
        let requests = RefCell::new(Vec::new());
        let result = read_with(&ctx, true, |endpoint| {
            let value = if endpoint == "/repos/selected-owner/selected-repo/pulls/17" {
                json!({"title":"Current PR title", "body":"The author's full PR description."})
            } else {
                json!([])
            };
            requests.borrow_mut().push(endpoint);
            ready(Ok(value))
        })
        .await
        .unwrap();
        assert!(
            result.contains("Current PR title\n\nDescription:\nThe author's full PR description.")
        );
        assert_eq!(result.matches("[]").count(), 3);
        assert_eq!(
            requests.into_inner(),
            [
                "/repos/selected-owner/selected-repo/pulls/17",
                "/repos/selected-owner/selected-repo/issues/17/comments?per_page=100&page=1",
                "/repos/selected-owner/selected-repo/pulls/17/comments?per_page=100&page=1",
                "/repos/selected-owner/selected-repo/pulls/17/reviews?per_page=100&page=1",
            ]
        );
    }

    #[tokio::test]
    async fn snapshots_and_non_pr_contexts_never_request_github() {
        let ctx = json!({"number":17,"description":"Captured body\n", "discussionSnapshot":[{"body":"Captured comment"}]});
        let snapshot = read_with(&ctx, false, |_| {
            ready(Err(anyhow::anyhow!(
                "Offline discussion must not request GitHub"
            )))
        })
        .await
        .unwrap();
        assert_eq!(snapshot, "Captured body\n[{\"body\":\"Captured comment\"}]");
        let not_pr = read_with(&json!({"headSha":"abc123"}), true, |_| {
            ready(Err(anyhow::anyhow!(
                "A non-PR context must not request GitHub"
            )))
        })
        .await
        .unwrap();
        assert!(not_pr.starts_with("This change is not a pull request"));
    }

    #[tokio::test]
    async fn large_pr_body_respects_byte_budget_without_reading_comments() {
        let ctx = json!({"owner":"owner", "repo":"repo", "number":17});
        let result = read_with(&ctx, true, |endpoint| {
            assert_eq!(endpoint, "/repos/owner/repo/pulls/17");
            ready(Ok(json!({"title":"PR title", "body":"🦀".repeat(40_000)})))
        })
        .await
        .unwrap();
        assert!(result.len() <= 120_000);
        assert!(result.ends_with(super::super::TRUNCATED));
    }
}
