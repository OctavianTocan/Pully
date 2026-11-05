//! Which parts of a login's CLI home are its sign-in, shared by the runner (which saves and
//! restores them) and `AgentHome` (which takes them back from a checkout).
use serde_json::{Map, Value};

/// The login files kept while the container sleeps, by their path in the CLI home: the field
/// that makes each a subscription sign-in, then the others kept with it. Nothing else of the
/// home is kept, so an API key or a setting a CLI wrote never leaves the container.
pub(crate) const LOGIN_FILES: [(&str, &str, &[&str]); 3] = [
    (".codex/auth.json", "tokens", &["auth_mode", "last_refresh"]),
    (".claude/.credentials.json", "claudeAiOauth", &[]),
    (".claude.json", "oauthAccount", &[]),
];

/// The kept part of the login file at `path`, or `None` when it holds no sign-in.
pub(crate) fn kept(path: &str, file: &Value) -> Option<Value> {
    let (_, main, others) = LOGIN_FILES.iter().find(|(name, ..)| *name == path)?;
    let mut kept = Map::new();
    kept.insert(main.to_string(), file.get(*main)?.clone());
    for field in *others {
        if let Some(value) = file.get(*field) {
            kept.insert(field.to_string(), value.clone());
        }
    }
    Some(Value::Object(kept))
}

/// The saved login files (`saved`, the JSON text kept in storage) with the sign-ins in
/// `returned` laid over them, path by path. Only the kept part of a known file that still
/// holds a sign-in is taken, so a file a checkout lost or emptied leaves the saved one in place.
///
/// @param saved the stored files, `None` when there are none
/// @param returned the files a checkout gave back, as path to content
/// @returns the merged JSON text, or `None` when `returned` adds nothing
pub fn merge(saved: Option<&str>, returned: &Value) -> Option<String> {
    let mut files: Map<String, Value> = saved
        .and_then(|text| serde_json::from_str(text).ok())
        .unwrap_or_default();
    let mut changed = false;
    for (path, content) in returned.as_object()? {
        if let Some(content) = kept(path, content) {
            if files.get(path) != Some(&content) {
                files.insert(path.clone(), content);
                changed = true;
            }
        }
    }
    changed.then(|| Value::Object(files).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn merge_lays_refreshed_sign_ins_over_the_saved_ones() {
        let saved = json!({
            ".codex/auth.json": {"tokens": {"a": 1}},
            ".claude/.credentials.json": {"claudeAiOauth": {"t": "old"}},
        })
        .to_string();
        let returned = json!({
            ".claude/.credentials.json": {"claudeAiOauth": {"t": "new"}, "apiKey": "x"},
            ".claude.json": {"theme": "dark"},
            "elsewhere.json": {"tokens": 1},
        });
        let merged: Value = serde_json::from_str(&merge(Some(&saved), &returned).unwrap()).unwrap();
        assert_eq!(
            merged,
            json!({
                ".codex/auth.json": {"tokens": {"a": 1}},
                ".claude/.credentials.json": {"claudeAiOauth": {"t": "new"}},
            })
        );
    }

    #[test]
    fn merge_with_nothing_new_changes_nothing() {
        let saved = json!({".claude.json": {"oauthAccount": {"e": 1}}}).to_string();
        assert_eq!(
            merge(
                Some(&saved),
                &json!({".claude.json": {"oauthAccount": {"e": 1}}})
            ),
            None
        );
        assert_eq!(merge(Some(&saved), &json!({})), None);
        assert_eq!(merge(Some(&saved), &json!([1])), None);
        assert!(merge(None, &json!({".claude.json": {"oauthAccount": {}}})).is_some());
    }
}
