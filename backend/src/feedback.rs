//! Feedback the account's owner sends from the phone. Each note is kept with when it came
//! and what it came from, until an agent with one of the account's keys has acted on it.
use crate::{account::random_id, posted::PostError, store::Store, text};
use anyhow::{Context, Result};
use serde_json::{json, Map, Value};

/// The longest note, in characters.
pub const MAX_TEXT: usize = 10_000;
/// The longest agent note on a handled entry, in characters.
pub const MAX_NOTE: usize = 2_000;

fn refuse<T>(message: impl Into<String>) -> Result<T> {
    Err(PostError {
        status: 400,
        message: message.into(),
    }
    .into())
}
fn valid_id(id: &str) -> bool {
    id.len() == 12 && id.bytes().all(|b| b.is_ascii_alphanumeric())
}
/// A short string field, trimmed and cut to `max` characters; empty is left out.
fn short(v: &Value, key: &str, max: usize) -> Option<Value> {
    let s = text(v, key).trim();
    (!s.is_empty()).then(|| json!(s.chars().take(max).collect::<String>()))
}
fn number(v: &Value, key: &str) -> Option<Value> {
    v.get(key).and_then(Value::as_u64).map(|n| json!(n))
}
fn put(map: &mut Map<String, Value>, key: &str, value: Option<Value>) {
    if let Some(value) = value {
        map.insert(key.into(), value);
    }
}

/// What the phone sent, kept to known fields of bounded size. The time is the server's own.
pub fn clean(input: &Value, now_ms: u64) -> Result<Map<String, Value>> {
    let note = text(input, "text").trim();
    if note.is_empty() {
        return refuse("Write what you want changed first.");
    }
    if note.chars().count() > MAX_TEXT {
        return refuse(format!("Feedback can be at most {MAX_TEXT} characters."));
    }
    let mut out = Map::new();
    out.insert("text".into(), json!(note));
    out.insert("createdAt".into(), json!(crate::iso_ms(now_ms)));
    out.insert("status".into(), json!("open"));
    let app = &input["app"];
    let mut app_out = Map::new();
    put(&mut app_out, "version", short(app, "version", 32));
    put(&mut app_out, "versionCode", number(app, "versionCode"));
    out.insert("app".into(), Value::Object(app_out));
    let device = &input["device"];
    let mut device_out = Map::new();
    put(
        &mut device_out,
        "manufacturer",
        short(device, "manufacturer", 64),
    );
    put(&mut device_out, "model", short(device, "model", 64));
    put(&mut device_out, "android", short(device, "android", 16));
    put(&mut device_out, "ios", short(device, "ios", 16));
    put(&mut device_out, "sdk", number(device, "sdk"));
    out.insert("device".into(), Value::Object(device_out));
    // The screen is one of the app's fixed names (`home`, `review`, `settings-stats`).
    let screen = text(input, "screen");
    if !screen.is_empty()
        && screen.len() <= 64
        && screen
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
    {
        out.insert("screen".into(), json!(screen));
    }
    put(&mut out, "place", short(input, "place", 300));
    if matches!(text(input, "theme"), "light" | "dark") {
        out.insert("theme".into(), json!(text(input, "theme")));
    }
    put(&mut out, "locale", short(input, "locale", 16));
    put(&mut out, "sentAt", short(input, "sentAt", 40));
    Ok(out)
}

/// The entry as a client sees it: everything but the account it belongs to.
fn described(mut item: Value) -> Value {
    if let Some(m) = item.as_object_mut() {
        m.remove("accountId");
    }
    item
}

#[derive(Clone)]
pub struct FeedbackStore {
    store: Store,
}
impl FeedbackStore {
    pub fn new(store: Store) -> Self {
        Self { store }
    }
    fn owned(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        if !valid_id(id) {
            return Ok(None);
        }
        Ok(self
            .store
            .get(&format!("feedback:{id}"))?
            .filter(|item| item["accountId"] == account_id))
    }
    pub fn save(&self, account_id: &str, input: &Value) -> Result<Value> {
        let mut item = clean(input, crate::now_ms())?;
        let id = random_id(12)?;
        item.insert("id".into(), json!(id));
        item.insert("accountId".into(), json!(account_id));
        let item = Value::Object(item);
        self.store.set(&format!("feedback:{id}"), &item)?;
        Ok(described(item))
    }
    /// The account's feedback, newest first; `status` keeps only `open` or only `done`.
    pub fn list(&self, account_id: &str, status: Option<&str>) -> Result<Vec<Value>> {
        let mut out = vec![];
        for key in self.store.keys("feedback:")? {
            if let Some(item) = self
                .store
                .get(&key)?
                .filter(|item| item["accountId"] == account_id)
                .filter(|item| status.is_none_or(|s| text(item, "status") == s))
            {
                out.push(described(item));
            }
        }
        out.sort_by(|a, b| text(b, "createdAt").cmp(text(a, "createdAt")));
        Ok(out)
    }
    pub fn get(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        Ok(self.owned(account_id, id)?.map(described))
    }
    /// Marks an entry `done` (with an optional note on what was done) or `open` again.
    pub fn mark(&self, account_id: &str, id: &str, change: &Value) -> Result<Option<Value>> {
        let status = text(change, "status");
        if !matches!(status, "open" | "done") {
            return refuse("Send { \"status\": \"done\" } or { \"status\": \"open\" }, with an optional \"note\".");
        }
        let note = text(change, "note").trim();
        if note.chars().count() > MAX_NOTE {
            return refuse(format!("A note can be at most {MAX_NOTE} characters."));
        }
        if self.owned(account_id, id)?.is_none() {
            return Ok(None);
        }
        let account = account_id.to_owned();
        let item = self.store.update(&format!("feedback:{id}"), |old| {
            let mut item = old
                .filter(|item| item["accountId"] == account)
                .context("Feedback changed while marking it")?;
            item["status"] = json!(status);
            if status == "done" {
                item["doneAt"] = json!(crate::iso_ms(crate::now_ms()));
                if !note.is_empty() {
                    item["note"] = json!(note);
                }
            } else if let Some(m) = item.as_object_mut() {
                m.remove("doneAt");
                m.remove("note");
            }
            Ok(item)
        })?;
        Ok(Some(described(item)))
    }

    async fn transaction<T: Send + 'static>(
        &self,
        operation: impl FnOnce(Self) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let store = self.clone();
        crate::store::blocking(move || operation(store)).await
    }
    pub async fn save_async(&self, account_id: &str, input: &Value) -> Result<Value> {
        let (account_id, input) = (account_id.to_owned(), input.clone());
        self.transaction(move |s| s.save(&account_id, &input)).await
    }
    pub async fn list_async(&self, account_id: &str, status: Option<&str>) -> Result<Vec<Value>> {
        let (account_id, status) = (account_id.to_owned(), status.map(str::to_owned));
        self.transaction(move |s| s.list(&account_id, status.as_deref()))
            .await
    }
    pub async fn get_async(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let (account_id, id) = (account_id.to_owned(), id.to_owned());
        self.transaction(move |s| s.get(&account_id, &id)).await
    }
    pub async fn mark_async(
        &self,
        account_id: &str,
        id: &str,
        change: &Value,
    ) -> Result<Option<Value>> {
        let (account_id, id, change) = (account_id.to_owned(), id.to_owned(), change.clone());
        self.transaction(move |s| s.mark(&account_id, &id, &change))
            .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn store() -> FeedbackStore {
        FeedbackStore::new(
            Store::new(
                std::env::temp_dir().join(format!("feedback-tests-{}", uuid::Uuid::new_v4())),
            )
            .unwrap(),
        )
    }
    fn sent() -> Value {
        json!({
            "text": "  The Inbox chip jumps when it loads.  ",
            "app": {"version": "0.51.0", "versionCode": 65, "extra": "dropped"},
            "device": {"manufacturer": "Google", "model": "Pixel 8", "android": "16", "sdk": 36},
            "screen": "review",
            "place": "octaviantocan/pulls.review/70",
            "theme": "dark",
            "locale": "en",
            "accountId": "someone-else",
            "status": "done",
        })
    }

    #[test]
    fn keeps_the_note_with_its_time_version_screen_and_device() {
        let item = clean(&sent(), 1_760_940_645_000).unwrap();
        assert_eq!(item["text"], "The Inbox chip jumps when it loads.");
        assert_eq!(item["createdAt"], "2025-10-20T06:10:45.000Z");
        assert_eq!(item["status"], "open", "a new note is always open");
        assert_eq!(item["app"], json!({"version":"0.51.0","versionCode":65}));
        assert_eq!(
            item["device"],
            json!({"manufacturer":"Google","model":"Pixel 8","android":"16","sdk":36})
        );
        assert_eq!(item["screen"], "review");
        assert_eq!(item["place"], "octaviantocan/pulls.review/70");
        assert_eq!(item["theme"], "dark");
        assert!(
            item.get("accountId").is_none(),
            "the client never names the account"
        );
    }

    #[test]
    fn refuses_empty_or_long_notes_and_drops_odd_fields() {
        for text in ["", "   "] {
            let error = clean(&json!({"text":text}), 0).unwrap_err();
            assert_eq!(error.downcast_ref::<PostError>().unwrap().status, 400);
        }
        assert!(clean(&json!({"text":"x".repeat(MAX_TEXT)}), 0).is_ok());
        assert!(clean(&json!({"text":"x".repeat(MAX_TEXT + 1)}), 0).is_err());
        let item = clean(
            &json!({"text":"ok","screen":"Review Page","theme":"sepia","device":{"model":"m".repeat(100)}}),
            0,
        )
        .unwrap();
        assert!(item.get("screen").is_none());
        assert!(item.get("theme").is_none());
        assert_eq!(item["device"]["model"].as_str().unwrap().len(), 64);
    }

    #[test]
    fn keeps_bounded_ios_version_without_inventing_android_or_device_identifiers() {
        let item = clean(
            &json!({"text":"A native note", "device": {
                "manufacturer":"Apple", "model":"iPhone", "ios":"1".repeat(40),
                "identifierForVendor":"private", "name":"A person's phone"
            }}),
            0,
        )
        .unwrap();
        assert_eq!(
            item["device"],
            json!({"manufacturer":"Apple", "model":"iPhone", "ios":"1".repeat(16)})
        );
    }

    #[test]
    fn each_account_reads_and_marks_only_its_own_feedback() {
        let s = store();
        let first = s.save("owner", &sent()).unwrap();
        let id = first["id"].as_str().unwrap();
        assert!(first.get("accountId").is_none());
        s.save("other", &json!({"text":"Not yours"})).unwrap();
        let mine = s.list("owner", None).unwrap();
        assert_eq!(mine.len(), 1);
        assert_eq!(mine[0]["id"], id);
        assert!(s.get("other", id).unwrap().is_none());
        assert!(s
            .mark("other", id, &json!({"status":"done"}))
            .unwrap()
            .is_none());
        assert_eq!(s.get("owner", id).unwrap().unwrap()["status"], "open");

        let done = s
            .mark(
                "owner",
                id,
                &json!({"status":"done","note":"Fixed in PR 70"}),
            )
            .unwrap()
            .unwrap();
        assert_eq!(done["status"], "done");
        assert_eq!(done["note"], "Fixed in PR 70");
        assert!(done["doneAt"].is_string());
        assert!(s.list("owner", Some("open")).unwrap().is_empty());
        assert_eq!(s.list("owner", Some("done")).unwrap().len(), 1);

        let reopened = s
            .mark("owner", id, &json!({"status":"open"}))
            .unwrap()
            .unwrap();
        assert_eq!(reopened["status"], "open");
        assert!(reopened.get("note").is_none() && reopened.get("doneAt").is_none());
        assert!(s.mark("owner", id, &json!({"status":"maybe"})).is_err());
        assert!(s.get("owner", "../../etc").unwrap().is_none());
    }

    #[test]
    fn lists_newest_first() {
        let s = store();
        for (at, text) in [(1_000, "older"), (2_000, "newer")] {
            let mut item = clean(&json!({"text":text}), at).unwrap();
            let id = random_id(12).unwrap();
            item.insert("id".into(), json!(id));
            item.insert("accountId".into(), json!("owner"));
            s.store
                .set(&format!("feedback:{id}"), &Value::Object(item))
                .unwrap();
        }
        let texts: Vec<_> = s
            .list("owner", None)
            .unwrap()
            .into_iter()
            .map(|i| i["text"].as_str().unwrap().to_owned())
            .collect();
        assert_eq!(texts, ["newer", "older"]);
    }
}
