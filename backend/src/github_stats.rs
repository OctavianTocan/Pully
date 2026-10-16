//! Profile counts fetched with the server's GitHub identity.
use crate::{source::github_request, AppState};
use anyhow::{ensure, Context, Result};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

const TTL: Duration = Duration::from_secs(600);
const QUERY: &str = r#"query { viewer {
  login name followers { totalCount } repositories(ownerAffiliations: OWNER) { totalCount }
  contributionsCollection {
    totalCommitContributions totalPullRequestContributions totalPullRequestReviewContributions
    totalIssueContributions restrictedContributionsCount
    contributionCalendar { totalContributions weeks { contributionDays { date contributionCount } } }
  }
} }"#;
#[derive(Clone, Default)]
pub struct Cache(std::sync::Arc<Mutex<Option<(Instant, Value)>>>);

/// @cc [owner:OctavianTocan,label:security;product] rust-github-profile-counts-only
/// Profile stats MUST use the server token and return counts, dates and profile identity,
/// never contribution text or repository names; successful answers MUST be reused for
/// a few minutes unless fresh is requested (16 Oct 09:50, M5).
pub async fn stats(state: &AppState, fresh: bool) -> Result<Value> {
    // Serialize refreshes so simultaneous profile opens share the fetched answer.
    let mut cache = state.github_stats.0.lock().await;
    if !fresh {
        if let Some((_, value)) = cache.as_ref().filter(|(at, _)| at.elapsed() < TTL) {
            return Ok(value.clone());
        }
    }
    // Invalidate before fetching: failures must not leave an old answer cached.
    *cache = None;
    let at = Instant::now();
    let raw = github_request(
        state,
        reqwest::Method::POST,
        "/graphql",
        Some(json!({"query":QUERY,"variables":{}})),
    )
    .await?;
    ensure!(
        raw["errors"].as_array().is_none_or(Vec::is_empty),
        "GitHub could not load profile statistics."
    );
    let value = normalize(&raw["data"])?;
    *cache = Some((at, value.clone()));
    Ok(value)
}
fn streaks(days: &[u64]) -> (usize, usize) {
    let mut longest = 0;
    let mut run = 0;
    for count in days {
        run = if *count > 0 { run + 1 } else { 0 };
        longest = longest.max(run);
    }
    let end = days.len() - usize::from(days.last() == Some(&0));
    (
        days[..end].iter().rev().take_while(|n| **n > 0).count(),
        longest,
    )
}
fn normalize(data: &Value) -> Result<Value> {
    let v = &data["viewer"];
    let login = v["login"]
        .as_str()
        .context("GitHub returned no profile statistics.")?;
    let c = &v["contributionsCollection"];
    let calendar = &c["contributionCalendar"];
    let weeks = calendar["weeks"]
        .as_array()
        .context("GitHub returned no contribution calendar.")?;
    let mut days = Vec::new();
    let mut first = String::new();
    for week in weeks {
        for day in week["contributionDays"]
            .as_array()
            .context("GitHub returned an invalid contribution calendar.")?
        {
            if days.is_empty() {
                first = day["date"]
                    .as_str()
                    .context("GitHub returned an invalid contribution date.")?
                    .into();
            }
            days.push(
                day["contributionCount"]
                    .as_u64()
                    .context("GitHub returned an invalid contribution count.")?,
            );
        }
    }
    let (streak, longest) = streaks(&days);
    Ok(
        json!({"login":login,"name":v["name"].as_str().filter(|s| !s.is_empty()),
        "followers":v["followers"]["totalCount"],"repositories":v["repositories"]["totalCount"],
        "total":calendar["totalContributions"],"commits":c["totalCommitContributions"],
        "pullRequests":c["totalPullRequestContributions"],"reviews":c["totalPullRequestReviewContributions"],
        "issues":c["totalIssueContributions"],"firstDay":first,"days":days,"streak":streak,"longestStreak":longest}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn streak_today_or_yesterday_and_empty() {
        assert_eq!(streaks(&[]), (0, 0));
        assert_eq!(streaks(&[0]), (0, 0));
        assert_eq!(streaks(&[1, 2, 0, 3, 4, 5, 0]), (3, 3));
        assert_eq!(streaks(&[1, 2, 0, 0]), (0, 2));
        assert_eq!(streaks(&[1, 2]), (2, 2));
    }
    #[test]
    fn response_has_counts_without_contribution_details() {
        let result = normalize(&json!({"viewer":{"login":"someone","name":"","followers":{"totalCount":2},"repositories":{"totalCount":3},"contributionsCollection":{"totalCommitContributions":4,"totalPullRequestContributions":5,"totalPullRequestReviewContributions":6,"totalIssueContributions":7,"restrictedContributionsCount":9,"contributionCalendar":{"totalContributions":22,"weeks":[{"contributionDays":[{"date":"2026-10-15","contributionCount":2},{"date":"2026-10-16","contributionCount":0}]}]}}}})).unwrap();
        assert_eq!(result["streak"], 1);
        assert_eq!(result["firstDay"], "2026-10-15");
        assert_eq!(result["total"], 22);
        assert!(result["name"].is_null());
        assert!(result.get("contributionsCollection").is_none());
    }
}
