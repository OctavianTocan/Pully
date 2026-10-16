//! Bounded captured evidence for Ask, including generated files and either diff side.
use crate::text;
use serde_json::Value;

const EXCERPT_BYTES: usize = 24_000;

struct Excerpt {
    text: String,
    truncated: bool,
}
impl Excerpt {
    fn new() -> Self {
        Self {
            text: String::new(),
            truncated: false,
        }
    }
    fn push(&mut self, value: &str) {
        let remaining = EXCERPT_BYTES.saturating_sub(self.text.len());
        let mut end = value.len().min(remaining);
        while !value.is_char_boundary(end) {
            end -= 1;
        }
        self.text.push_str(&value[..end]);
        self.truncated |= end < value.len();
    }
    fn line(&mut self, old: Option<u64>, new: Option<u64>, body: &str) {
        self.push(&format!(
            "L{} R{} ",
            old.map_or_else(|| "-".into(), |n| n.to_string()),
            new.map_or_else(|| "-".into(), |n| n.to_string())
        ));
        self.push(body);
        self.push("\n");
    }
    fn finish(mut self) -> String {
        if self.truncated {
            self.text.push_str("\n[truncated]\n");
        }
        self.text
    }
}
fn numbered(hunk: &Value) -> impl Iterator<Item = (Option<u64>, Option<u64>, &str)> {
    let mut old = hunk["oldStart"].as_u64().unwrap_or(1);
    let mut new = hunk["newStart"].as_u64().unwrap_or(1);
    text(hunk, "patch").lines().filter_map(move |line| {
        let (left, right) = match line.as_bytes().first() {
            Some(b'-') => (Some(old), None),
            Some(b'+') => (None, Some(new)),
            Some(b' ') => (Some(old), Some(new)),
            _ => return None,
        };
        old = old.saturating_add(u64::from(left.is_some()));
        new = new.saturating_add(u64::from(right.is_some()));
        Some((left, right, line))
    })
}

/// @cc [owner:OctavianTocan,label:product] rust-ask-keeps-selected-evidence
/// Ask MUST include bounded numbered evidence on the selected old or new side and
/// surrounding captured hunks, including generated files, without requiring source
/// access (core selectionContext/buildAskPrompt; Rust review R11, 16 Oct 2026).
pub(super) fn context(diff: &Value, selection: &Value) -> String {
    let low = selection["startLine"].as_u64().unwrap_or(0);
    let high = selection["endLine"].as_u64().unwrap_or(0);
    let deletions = selection["side"] == "deletions";
    let selected_line = |old: Option<u64>, new: Option<u64>| {
        (if deletions { old } else { new }).is_some_and(|n| n >= low && n <= high)
    };
    let mut selected = Excerpt::new();
    let mut surrounding = Excerpt::new();
    let mut found = false;
    if let Some(file) = diff["files"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|file| file["path"] == selection["path"])
    {
        for (index, hunk) in file["hunks"].as_array().into_iter().flatten().enumerate() {
            if !numbered(hunk).any(|(old, new, _)| selected_line(old, new)) {
                continue;
            }
            found = true;
            surrounding.push(&format!("Hunk {index}: "));
            surrounding.push(text(hunk, "header"));
            surrounding.push("\n");
            for (old, new, line) in numbered(hunk) {
                if selected_line(old, new) {
                    selected.line(old, new, line);
                }
                surrounding.line(old, new, line);
            }
        }
    }
    if !found {
        return "The captured diff does not show these selected lines. Do not infer their contents.".into();
    }
    format!("Selected evidence (L = old-file line, R = new-file line):\n{}\nSurrounding captured hunks:\n{}", selected.finish(), surrounding.finish())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn diff(patch: &str) -> Value {
        json!({"ref":{"kind":"posted","id":"postedfixture"},"files":[{"path":"Cargo.lock","hunks":[{"oldStart":10,"newStart":20,"header":"@@ -10,3 +20,4 @@","patch":patch}]}]})
    }
    #[test]
    fn posted_generated_deletion_selection_has_old_side_and_surrounding_hunk() {
        let captured =
            diff(" same\n-old_dependency = 1\n+new_dependency = 2\n+extra_dependency = 3\n tail\n");
        let result = context(
            &captured,
            &json!({"path":"Cargo.lock","side":"deletions","startLine":11,"endLine":12}),
        );
        let (selected, surrounding) = result.split_once("Surrounding captured hunks:").unwrap();
        assert!(selected.contains("L11 R- -old_dependency = 1"));
        assert!(selected.contains("L12 R23  tail"));
        assert!(!selected.contains("new_dependency"));
        assert!(surrounding.contains("L- R21 +new_dependency = 2"));
        assert!(surrounding.contains("L- R22 +extra_dependency = 3"));
    }
    #[test]
    fn addition_selection_uses_new_line_numbers_and_handles_absent_lines() {
        let captured = diff("-old\n+new\n context\n");
        let result = context(
            &captured,
            &json!({"path":"Cargo.lock","side":"additions","startLine":20,"endLine":21}),
        );
        let (selected, _) = result.split_once("Surrounding captured hunks:").unwrap();
        assert!(selected.contains("L- R20 +new"));
        assert!(selected.contains("L11 R21  context"));
        assert!(!selected.contains("-old"));
        assert!(context(
            &captured,
            &json!({"path":"Cargo.lock","side":"additions","startLine":100,"endLine":100})
        )
        .contains("does not show"));
    }
    #[test]
    fn selection_and_surrounding_hunks_have_independent_utf8_bounds() {
        let captured = diff(&format!("-{}\n+replacement\n", "😀".repeat(EXCERPT_BYTES)));
        let result = context(
            &captured,
            &json!({"path":"Cargo.lock","side":"deletions","startLine":10,"endLine":10}),
        );
        assert!(result.len() <= 2 * EXCERPT_BYTES + 200);
        assert_eq!(result.matches("[truncated]").count(), 2);
        assert!(result.contains("L10 R- -😀"));
    }
}
