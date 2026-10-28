//! The sync feed: which of a store's synced records changed since a version. A phone waits
//! on it (`sync-wait`) instead of asking again and again, then reads only what changed
//! through the methods it already calls, the way Zero's clients are poked by zero-cache.
//! The feed holds names of topics, never their records, and lives as long as its store:
//! a new server or Durable Object starts a new epoch, and its clients read everything once.
use crate::AppState;
use anyhow::Result;
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// The longest a `sync-wait` holds its request; under the proxies' idle limits.
pub const LONGEST_WAIT: Duration = Duration::from_secs(25);
/// How old the kept Inbox may be while a phone watches it before the server asks GitHub.
pub const INBOX_FRESH_MS: u64 = 60_000;
/// Distinct topics remembered; a client further behind than the oldest one forgotten reads everything.
const KEPT_TOPICS: usize = 512;

/// One store's change feed, shared by every Store instance over the same account.
pub(crate) struct Changes {
    epoch: String,
    log: Mutex<Log>,
    version: tokio::sync::watch::Sender<u64>,
}
#[derive(Default)]
struct Log {
    version: u64,
    /// Newest change of each topic, oldest first.
    topics: VecDeque<(u64, String)>,
    /// The version of the newest topic forgotten.
    floor: u64,
}
impl Changes {
    pub(crate) fn new() -> Self {
        Self {
            epoch: uuid::Uuid::new_v4().simple().to_string(),
            log: Mutex::new(Log::default()),
            version: tokio::sync::watch::channel(0).0,
        }
    }
    /// Records a write to `key` if a client reads it; called under the store's lock.
    pub(crate) fn note(&self, key: &str) {
        let Some(topic) = topic(key) else {
            return;
        };
        let mut log = self.log.lock().unwrap_or_else(|e| e.into_inner());
        log.version += 1;
        let version = log.version;
        log.topics.retain(|(_, t)| *t != topic);
        log.topics.push_back((version, topic));
        while log.topics.len() > KEPT_TOPICS {
            if let Some((forgotten, _)) = log.topics.pop_front() {
                log.floor = forgotten;
            }
        }
        drop(log);
        self.version.send_replace(version);
    }
    /// The current version, the topics changed after `since`, and whether the client
    /// must read everything because it is from another epoch or too far behind.
    fn since(&self, epoch: &str, since: u64) -> (u64, Vec<String>, bool) {
        let log = self.log.lock().unwrap_or_else(|e| e.into_inner());
        if epoch != self.epoch || since > log.version || since < log.floor {
            return (log.version, Vec::new(), true);
        }
        let topics = log
            .topics
            .iter()
            .filter(|(v, _)| *v > since)
            .map(|(_, t)| t.clone())
            .collect();
        (log.version, topics, false)
    }
    /// Answers once a topic changes after `since`, at once for a client that must read
    /// everything, and with no topics when `wait` passes first.
    pub(crate) async fn wait(&self, epoch: &str, since: u64, wait: Duration) -> Value {
        // Subscribing first means a change between the check and the wait still wakes it.
        let mut changed = self.version.subscribe();
        let deadline = crate::rt::Instant::now() + wait.min(LONGEST_WAIT);
        loop {
            let (version, topics, reset) = self.since(epoch, since);
            if reset || !topics.is_empty() {
                return json!({"epoch":self.epoch,"version":version,"topics":topics,"reset":reset});
            }
            match crate::rt::timeout_at(deadline, changed.changed()).await {
                Ok(Ok(())) => continue,
                _ => return json!({"epoch":self.epoch,"version":version,"topics":[],"reset":false}),
            }
        }
    }
}

/// The topic a write to `key` changes for a client: `inbox` for the Inbox and Jev's kinds,
/// `review:<diff key>` for a pull request's stored diff, review state and Ask threads, and
/// `marks` for files marked reviewed. Posted diffs and every other record have none.
pub(crate) fn topic(key: &str) -> Option<String> {
    // The store files `a/b` and `a:b` as one record, so the collection is read either way;
    // the diff key keeps its own spelling, the one the phone opened it with.
    let split = key.find([':', '/', '\\'])?;
    let (collection, rest) = (&key[..split], &key[split + 1..]);
    match collection {
        "my-pulls" | "jev-pr" => Some("inbox".into()),
        "review" => Some("marks".into()),
        "diff" | "pr-meta" | "pr-body" | "ask-threads"
            if !rest.is_empty() && !rest.starts_with("posted:") =>
        {
            Some(format!("review:{rest}"))
        }
        _ => None,
    }
}

/// `sync-wait`: waits for the caller's own store to change, with `{ epoch, version }` from
/// its last answer. `inbox: true` says the Inbox is on screen, so a kept Inbox older than
/// [`INBOX_FRESH_MS`] is refreshed from GitHub meanwhile and its change answers the wait.
pub async fn wait(state: &Arc<AppState>, args: &Value) -> Result<Value> {
    state.identity.ensure_current()?;
    let epoch = args["epoch"].as_str().unwrap_or("");
    let since = args["version"].as_u64().unwrap_or(0);
    let wait = Duration::from_millis(args["waitMs"].as_u64().unwrap_or(0));
    if args["inbox"] == true {
        // A failed refresh leaves the kept list as it was; the phone still gets its answer.
        let _ = crate::source::refresh_stale_inbox(state, INBOX_FRESH_MS).await;
    }
    let answer = state.host.storage.changes().wait(epoch, since, wait).await;
    state.identity.ensure_current()?;
    Ok(answer)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn topics_name_only_what_a_client_reads() {
        assert_eq!(topic("my-pulls:open").as_deref(), Some("inbox"));
        assert_eq!(topic("jev-pr:github:o/r#1").as_deref(), Some("inbox"));
        assert_eq!(topic("review:abc123").as_deref(), Some("marks"));
        assert_eq!(
            topic("diff:github:o/r#1").as_deref(),
            Some("review:github:o/r#1")
        );
        assert_eq!(
            topic("ask-threads:github:o/r#1").as_deref(),
            Some("review:github:o/r#1")
        );
        assert_eq!(topic("my-pulls/closed").as_deref(), Some("inbox"));
        for silent in [
            "diff:posted:p1",
            "pr-meta:posted:p1",
            "account:me",
            "agent-key:k",
            "posted-diff:p1",
            "feedback:f",
            "diff:",
            "my-pulls",
        ] {
            assert_eq!(topic(silent), None, "{silent}");
        }
    }

    #[test]
    fn a_client_gets_each_changed_topic_once_and_a_stranger_resets() {
        let changes = Changes::new();
        let epoch = changes.epoch.clone();
        changes.note("my-pulls:open");
        changes.note("review:a");
        changes.note("my-pulls:open");
        changes.note("account:me");
        let (version, topics, reset) = changes.since(&epoch, 0);
        assert_eq!((version, reset), (3, false));
        assert_eq!(topics, vec!["marks", "inbox"]);
        assert_eq!(changes.since(&epoch, 2).1, vec!["inbox"]);
        assert!(changes.since(&epoch, 3).1.is_empty());
        assert!(changes.since("other", 3).2);
        assert!(changes.since(&epoch, 9).2);
    }

    #[test]
    fn a_client_behind_the_forgotten_topics_reads_everything() {
        let changes = Changes::new();
        let epoch = changes.epoch.clone();
        for i in 0..KEPT_TOPICS + 3 {
            changes.note(&format!("diff:github:o/r#{i}"));
        }
        assert!(changes.since(&epoch, 2).2);
        let (version, topics, reset) = changes.since(&epoch, 3);
        assert!(!reset);
        assert_eq!(topics.len(), KEPT_TOPICS);
        assert_eq!(version, (KEPT_TOPICS + 3) as u64);
    }

    #[tokio::test]
    async fn a_wait_answers_when_a_topic_changes_and_times_out_quietly() {
        let changes = Arc::new(Changes::new());
        let epoch = changes.epoch.clone();
        let quiet = changes.wait(&epoch, 0, Duration::from_millis(20)).await;
        assert_eq!(quiet["topics"], json!([]));
        assert_eq!(quiet["reset"], false);
        let writer = changes.clone();
        let wake = crate::rt::spawn(async move {
            crate::rt::sleep(Duration::from_millis(20)).await;
            writer.note("account:me");
            writer.note("my-pulls:open");
        });
        let answer = changes.wait(&epoch, 0, Duration::from_secs(5)).await;
        wake.await.unwrap();
        assert_eq!(answer["topics"], json!(["inbox"]));
        assert_eq!(answer["version"], 1);
        let stranger = changes.wait("old", 1, Duration::from_secs(5)).await;
        assert_eq!(stranger["reset"], true);
        assert_eq!(stranger["epoch"], json!(changes.epoch));
    }
}

#[cfg(test)]
mod dispatch_tests {
    use serde_json::{json, Value};

    #[tokio::test]
    async fn marking_a_file_wakes_a_waiting_phone_through_the_phone_method() {
        assert!(crate::transport::phone_allowed("sync-wait"));
        let root = std::env::temp_dir().join(format!("sync-wait-{}", uuid::Uuid::new_v4()));
        let state = crate::AppState::new(root.clone(), root.clone()).unwrap();
        let first = crate::dispatch(
            &state,
            "sync-wait",
            json!({"epoch":"","version":0,"waitMs":0}),
        )
        .await
        .unwrap();
        assert_eq!(first["reset"], true);
        let waiting = crate::dispatch(
            &state,
            "sync-wait",
            json!({"epoch":first["epoch"],"version":first["version"],"waitMs":5000}),
        );
        let mark = async {
            crate::rt::sleep(std::time::Duration::from_millis(50)).await;
            crate::dispatch(
                &state,
                "reviewed-set",
                json!({"key":"paste:none","shas":["file-sha"],"reviewed":true}),
            )
            .await
            .unwrap();
        };
        let (woken, ()) = tokio::join!(waiting, mark);
        let woken: Value = woken.unwrap();
        assert_eq!(woken["topics"], json!(["marks"]));
        assert_eq!(woken["epoch"], first["epoch"]);
        for invalid in [json!({}), json!({"epoch":"","version":"0","waitMs":0})] {
            assert!(crate::dispatch(&state, "sync-wait", invalid).await.is_err());
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
