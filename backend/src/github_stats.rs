//! Profile counts fetched with the server's GitHub identity.
use crate::{source::github_request, AppState};
use anyhow::{ensure, Context, Result};
use serde_json::{json, Value};
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::{watch, Mutex};

const TTL: Duration = Duration::from_secs(600);
const QUERY: &str = r#"query { viewer {
  login name followers { totalCount } repositories(ownerAffiliations: OWNER) { totalCount }
  contributionsCollection {
    totalCommitContributions totalPullRequestContributions totalPullRequestReviewContributions
    totalIssueContributions restrictedContributionsCount
    contributionCalendar { totalContributions weeks { contributionDays { date contributionCount } } }
  }
} }"#;
type Refresh = watch::Receiver<Option<std::result::Result<Value, RefreshError>>>;
#[derive(Clone, Default)]
pub struct Cache(Arc<Mutex<Option<Entry>>>);
struct Entry {
    at: Instant,
    result: Refresh,
}
#[derive(Clone, Debug)]
struct RefreshError(Arc<anyhow::Error>);
impl std::fmt::Display for RefreshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(self.0.as_ref(), f)
    }
}
impl std::error::Error for RefreshError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        self.0.source()
    }
}

/// @cc [owner:OctavianTocan,label:security;product] rust-github-profile-counts-only
/// Profile stats MUST use the server token and return counts, dates and profile identity,
/// never contribution text or repository names; successful answers MUST be reused for
/// a few minutes unless fresh is requested (16 Oct 09:50, M5).
pub async fn stats(state: &AppState, fresh: bool) -> Result<Value> {
    let owned_state = state.clone();
    let refresh = start_refresh(&state.github_stats, fresh, move || async move {
        let raw = github_request(
            &owned_state,
            reqwest::Method::POST,
            "/graphql",
            Some(json!({"query":QUERY,"variables":{}})),
        )
        .await?;
        ensure!(
            raw["errors"].as_array().is_none_or(Vec::is_empty),
            "GitHub could not load profile statistics."
        );
        normalize(&raw["data"])
    })
    .await;
    refresh_result(refresh).await
}
/// @cc [owner:OctavianTocan,label:architecture] rust-profile-refreshes-share-failures
/// Overlapping non-fresh profile reads MUST share one fetch and its success or failure.
/// A failed fetch MUST allow a later retry without clearing a newer refresh; fresh MUST
/// bypass the current entry (16 Oct 2026 parity request; R13 finding 7; former stats cache).
async fn start_refresh<F, Fut>(cache: &Cache, fresh: bool, fetch: F) -> Refresh
where
    F: FnOnce() -> Fut + Send + 'static,
    Fut: std::future::Future<Output = Result<Value>> + Send + 'static,
{
    let mut current = cache.0.lock().await;
    if !fresh {
        if let Some(entry) = current.as_ref().filter(|entry| entry.at.elapsed() < TTL) {
            return entry.result.clone();
        }
    }
    let (sender, receiver) = watch::channel(None);
    *current = Some(Entry {
        at: Instant::now(),
        result: receiver.clone(),
    });
    let cache = cache.clone();
    let active = receiver.clone();
    tokio::spawn(async move {
        let result = fetch().await.map_err(|error| RefreshError(Arc::new(error)));
        let mut current = cache.0.lock().await;
        if result.is_err()
            && current
                .as_ref()
                .is_some_and(|entry| active.same_channel(&entry.result))
        {
            *current = None;
        }
        sender.send_replace(Some(result));
    });
    receiver
}
async fn refresh_result(mut receiver: Refresh) -> Result<Value> {
    loop {
        if let Some(result) = receiver.borrow().clone() {
            return result.map_err(anyhow::Error::new);
        }
        receiver
            .changed()
            .await
            .context("GitHub profile refresh stopped")?;
    }
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
    #[tokio::test]
    async fn profile_refresh_shares_delayed_success_and_failure() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        tokio::time::timeout(Duration::from_secs(2), async {
            for fail in [false, true] {
                let cache = Cache::default();
                let calls = Arc::new(AtomicUsize::new(0));
                let release = Arc::new(tokio::sync::Semaphore::new(0));
                let mut waiting = Vec::new();
                for _ in 0..16 {
                    let (calls, release) = (calls.clone(), release.clone());
                    waiting.push(
                        start_refresh(&cache, false, move || async move {
                            calls.fetch_add(1, Ordering::SeqCst);
                            release.acquire().await.unwrap().forget();
                            if fail {
                                return Err(anyhow::anyhow!("fixture failure")
                                    .context("Profile unavailable"));
                            }
                            Ok(json!({"total":7}))
                        })
                        .await,
                    );
                }
                tokio::task::yield_now().await;
                assert_eq!(calls.load(Ordering::SeqCst), 1);
                // A dropped HTTP waiter must not cancel the shared refresh.
                waiting.pop();
                release.add_permits(16);
                let mut previous_error = None;
                for receiver in waiting {
                    let result = refresh_result(receiver).await;
                    if fail {
                        let error = result.unwrap_err();
                        assert_eq!(format!("{error:#}"), "Profile unavailable: fixture failure");
                        let shared = &error.downcast_ref::<RefreshError>().unwrap().0;
                        if let Some(previous) = &previous_error {
                            assert!(Arc::ptr_eq(previous, shared));
                        }
                        previous_error = Some(shared.clone());
                    } else {
                        assert_eq!(result.unwrap(), json!({"total":7}));
                    }
                }
                assert_eq!(calls.load(Ordering::SeqCst), 1);
                let calls_copy = calls.clone();
                let next = start_refresh(&cache, false, move || async move {
                    calls_copy.fetch_add(1, Ordering::SeqCst);
                    Ok(json!({"total":8}))
                })
                .await;
                assert_eq!(
                    refresh_result(next).await.unwrap()["total"],
                    if fail { 8 } else { 7 }
                );
                assert_eq!(calls.load(Ordering::SeqCst), if fail { 2 } else { 1 });
            }
        })
        .await
        .expect("profile waiters must share one bounded completion");
    }
    #[tokio::test]
    async fn profile_fresh_bypasses_cache_without_old_completion_replacing_it() {
        tokio::time::timeout(Duration::from_secs(2), async {
            for fail in [false, true] {
                let cache = Cache::default();
                let (release, wait) = tokio::sync::oneshot::channel();
                let older = start_refresh(&cache, false, move || async move {
                    wait.await.unwrap();
                    if fail {
                        anyhow::bail!("old failure");
                    }
                    Ok(json!({"total":1}))
                })
                .await;
                let fresh = start_refresh(&cache, true, || async { Ok(json!({"total":2})) }).await;
                assert_eq!(refresh_result(fresh).await.unwrap()["total"], 2);
                release.send(()).unwrap();
                let old = refresh_result(older).await;
                assert_eq!(old.is_err(), fail);
                let cached = start_refresh(&cache, false, || async {
                    panic!("old completions must not clear the fresh cache")
                })
                .await;
                assert_eq!(refresh_result(cached).await.unwrap()["total"], 2);
                cache.0.lock().await.as_mut().unwrap().at = Instant::now() - TTL;
                let expired =
                    start_refresh(&cache, false, || async { Ok(json!({"total":3})) }).await;
                assert_eq!(refresh_result(expired).await.unwrap()["total"], 3);
            }
        })
        .await
        .expect("fresh profile reads must settle independently");
    }
}
