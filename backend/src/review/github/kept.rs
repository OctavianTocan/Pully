//! A pull request's GitHub reads kept on the server as `{kind}:{owner}/{repo}#{number}`, so a
//! phone shows them before GitHub answers again (`server-keeps-pull-request-pages`).
use super::Pull;
use crate::AppState;
use anyhow::Result;
use serde_json::{json, Value};

pub(super) const OVERVIEW: &str = "pr-overview";
pub(super) const REVIEWS: &str = "pr-reviews";
pub(super) const STACK: &str = "pr-stack";

fn key(kind: &str, pull: &Pull) -> String {
    format!("{kind}:{}/{}#{}", pull.owner, pull.repo, pull.number)
}

/// The kept copy with `storedAt`, or null when none was kept or it is not an object.
pub(super) async fn read(state: &AppState, kind: &str, pull: &Pull) -> Result<Value> {
    Ok(
        match state.host.storage.get_async(&key(kind, pull)).await? {
            Some(stored) if stored["value"].is_object() => {
                let mut value = stored["value"].clone();
                value["storedAt"] = stored["at"].clone();
                value
            }
            _ => Value::Null,
        },
    )
}

/// When a kept copy was read from GitHub and for which `updatedAt` of the pull request; `None`
/// when there is none.
pub(super) async fn version(
    state: &AppState,
    kind: &str,
    pull: &Pull,
) -> Result<Option<(u64, String)>> {
    Ok(state
        .host
        .storage
        .get_async(&key(kind, pull))
        .await?
        .map(|stored| {
            (
                stored["at"].as_u64().unwrap_or(0),
                stored["updatedAt"].as_str().unwrap_or("").to_string(),
            )
        }))
}

/// Keeps `value`, read from GitHub at `at` for the pull request as of `updated_at`; a slower
/// earlier read may answer its caller but never replaces a newer copy.
pub(super) async fn keep(
    state: &AppState,
    kind: &str,
    pull: &Pull,
    at: u64,
    updated_at: &str,
    value: &Value,
) -> Result<()> {
    let key = key(kind, pull);
    let stored = json!({"at":at,"updatedAt":updated_at,"value":value});
    state
        .host
        .storage
        .transaction(move |store| {
            store.update(&key, |current| {
                Ok(match current {
                    Some(current) if current["at"].as_u64().unwrap_or(0) > at => current,
                    _ => stored,
                })
            })
        })
        .await
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn state() -> Arc<AppState> {
        let root = std::env::temp_dir().join(format!("pulls-kept-{}", uuid::Uuid::new_v4()));
        AppState::new(root.clone(), root).unwrap()
    }

    fn pull() -> Value {
        json!({"owner":"o","repo":"r","number":7})
    }

    #[tokio::test]
    async fn a_kept_copy_is_answered_without_github_and_a_slower_older_read_never_replaces_it() {
        let state = state();
        let pull = pull();
        for name in ["review-data", "pr-stack", "pr-overview"] {
            let cached = crate::review::handle(
                &state,
                name,
                json!({"owner":"o","repo":"r","number":7,"cached":true}),
            )
            .await
            .unwrap()
            .unwrap();
            assert!(cached.is_null(), "{name}: nothing kept yet");
        }
        let p = Pull::from(&pull).unwrap();
        keep(&state, REVIEWS, &p, 20, "t2", &json!({"threads":[1]}))
            .await
            .unwrap();
        keep(&state, REVIEWS, &p, 10, "t1", &json!({"threads":[]}))
            .await
            .unwrap();
        keep(&state, STACK, &p, 5, "t1", &json!({"stack":null}))
            .await
            .unwrap();
        let reviews = crate::review::handle(
            &state,
            "review-data",
            json!({"owner":"o","repo":"r","number":7,"cached":true}),
        )
        .await
        .unwrap()
        .unwrap();
        assert_eq!(reviews["threads"], json!([1]));
        assert_eq!(reviews["storedAt"], 20);
        assert_eq!(
            version(&state, REVIEWS, &p).await.unwrap(),
            Some((20, "t2".into()))
        );
        let stack = crate::review::handle(
            &state,
            "pr-stack",
            json!({"owner":"o","repo":"r","number":7,"cached":true}),
        )
        .await
        .unwrap()
        .unwrap();
        assert!(stack["stack"].is_null() && stack["storedAt"] == 5);
    }

    #[tokio::test]
    async fn the_warm_round_reads_reviews_and_stack_again_only_when_the_pull_request_changed() {
        let state = state();
        let p = Pull::from(&pull()).unwrap();
        keep(&state, REVIEWS, &p, 1, "t1", &json!({"threads":[]}))
            .await
            .unwrap();
        keep(&state, STACK, &p, 1, "t1", &json!({"stack":null}))
            .await
            .unwrap();
        let read = crate::review::keep_pull_reads(
            &state,
            &json!({"owner":"o","repo":"r","number":7,"updatedAt":"t1"}),
        )
        .await
        .unwrap();
        assert_eq!(
            read, 0,
            "both copies are of this version, so GitHub is not asked"
        );
    }
}
