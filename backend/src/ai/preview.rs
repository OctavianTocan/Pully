//! Bounded activity previews. The leading secret overlap is context, never display text.

use super::KNOWN_SECRETS;

const DISPLAY_CHARS: usize = 8000;
const REDACTED: &str = "[redacted]";

struct Secret {
    value: String,
    redact: bool,
}
struct Secrets(Vec<Secret>);
impl Secrets {
    fn from_environment() -> Self {
        Self::from_entries(std::env::vars())
    }
    fn from_entries(entries: impl Iterator<Item = (String, String)>) -> Self {
        Self(
            entries
                .filter(|(name, value)| {
                    !value.is_empty()
                        && ["TOKEN", "KEY", "SECRET", "PASSWORD"]
                            .iter()
                            .any(|part| name.to_ascii_uppercase().contains(part))
                })
                .map(|(name, value)| Secret {
                    redact: value.len() >= 6 || KNOWN_SECRETS.contains(&name.as_str()),
                    value,
                })
                .collect(),
        )
    }
    fn overlap(&self) -> usize {
        self.0
            .iter()
            .map(|secret| secret.value.len())
            .max()
            .unwrap_or(0)
    }
    fn ranges(&self, text: &str) -> Vec<(usize, usize)> {
        let mut ranges: Vec<(usize, usize)> = Vec::new();
        for secret in self.0.iter().filter(|secret| secret.redact) {
            let mut from = 0;
            while let Some(at) = text[from..].find(&secret.value) {
                let start = from + at;
                ranges.push((start, start + secret.value.len()));
                // Include overlapping matches of the same secret, on UTF-8 boundaries.
                from = start + text[start..].chars().next().unwrap().len_utf8();
            }
        }
        ranges.sort_unstable();
        let mut merged: Vec<(usize, usize)> = Vec::new();
        for (start, end) in ranges {
            if let Some(last) = merged.last_mut().filter(|last| start <= last.1) {
                last.1 = last.1.max(end);
            } else {
                merged.push((start, end));
            }
        }
        merged
    }
    fn stream_end(&self, text: &str, ranges: &[(usize, usize)]) -> usize {
        let mut end = text.len();
        for secret in &self.0 {
            for (index, _) in secret.value.char_indices().skip(1) {
                if text.ends_with(&secret.value[..index]) {
                    let start = text.len() - index;
                    // A complete match already hides this suffix. Cutting through it
                    // would otherwise expose a prefix of a self-overlapping secret.
                    if !ranges
                        .last()
                        .is_some_and(|&(from, to)| to == text.len() && from <= start)
                    {
                        end = end.min(start);
                    }
                }
            }
        }
        end
    }
}

struct Window<'a> {
    text: &'a str,
    display_start: usize,
    clipped: bool,
}
impl<'a> Window<'a> {
    /// @cc [owner:OctavianTocan,label:architecture] rust-preview-processing-is-bounded
    /// Preview processing MUST select only the final 8,000 Unicode characters plus
    /// longest-secret overlap before redaction, without decoding the preceding text
    /// (Tavi, 16 Oct 2026, fix each code-review finding; round 9 performance finding).
    fn new(text: &'a str, overlap: usize) -> Self {
        let display_start = super::tail_start(text, DISPLAY_CHARS);
        let mut start = display_start.saturating_sub(overlap);
        while !text.is_char_boundary(start) {
            start -= 1;
        }
        Self {
            text: &text[start..],
            display_start: display_start - start,
            clipped: display_start > 0,
        }
    }
}

fn preview(text: &str, done: bool, secrets: &Secrets) -> String {
    let window = Window::new(text, secrets.overlap());
    let ranges = secrets.ranges(window.text);
    let end = if done {
        window.text.len()
    } else {
        secrets.stream_end(window.text, &ranges)
    };
    let mut output = String::new();
    let mut from = window.display_start.min(end);
    for (start, stop) in ranges {
        if stop <= from || start >= end {
            continue;
        }
        output.push_str(&window.text[from..start.max(from)]);
        output.push_str(REDACTED);
        from = stop.min(end);
    }
    output.push_str(&window.text[from..end]);
    // Clip only rendered display text. Redaction may shrink the context to less
    // than 8,000 characters, but no part of that context can be backfilled here.
    let start = super::tail_start(&output, DISPLAY_CHARS);
    if window.clipped || start > 0 {
        format!("…{}", &output[start..])
    } else {
        output
    }
}

/// @cc [owner:OctavianTocan,label:security] rust-preview-overlap-remains-private
/// A preview MUST NOT publish its leading overlap, including after redaction shrinks
/// it. Complete configured secrets MUST be redacted even across clipping boundaries;
/// unfinished prefixes MUST be withheld until terminal text is available (root
/// secrets-come-from-the-environment; Tavi, 16 Oct 2026, fix code-review findings).
pub(crate) fn safe_preview(text: &str, done: bool) -> String {
    preview(text, done, &Secrets::from_environment())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(values: &[&str]) -> Secrets {
        Secrets(
            values
                .iter()
                .map(|value| Secret {
                    value: (*value).into(),
                    redact: true,
                })
                .collect(),
        )
    }

    #[test]
    fn preview_work_stays_bounded_across_many_small_unicode_deltas() {
        let secrets = fixture(&["synthetic-credential-雪"]);
        let mut text = String::new();
        for _ in 0..2000 {
            text.push_str("🦀123456789");
            let window = Window::new(&text, secrets.overlap());
            assert!(window.text.len() <= DISPLAY_CHARS * 4 + secrets.overlap() + 3);
            let output = preview(&text, false, &secrets);
            assert!(output.chars().count() <= DISPLAY_CHARS + 1);
            assert!(output.ends_with("🦀123456789"));
        }
        assert_eq!(text.chars().count(), 20_000);
        assert_eq!(
            preview(&text, true, &secrets),
            format!("…{}", "🦀123456789".repeat(800))
        );
    }

    #[test]
    fn preview_redacts_secret_across_delta_and_display_boundaries() {
        let secret = "synthetic-secret-雪🦀";
        let secrets = fixture(&[secret]);
        let mut text = "before ".to_owned();
        for ch in secret.chars() {
            text.push(ch);
            let output = preview(&text, false, &secrets);
            assert_eq!(
                output,
                if text.ends_with(secret) {
                    "before [redacted]"
                } else {
                    "before "
                }
            );
        }
        for after in [7990, 7985, 7982] {
            let text = format!("{} {secret}{}", "x".repeat(100), "z".repeat(after));
            assert_eq!(
                preview(&text, false, &secrets),
                format!("…[redacted]{}", "z".repeat(after))
            );
            assert_eq!(
                preview(&text, true, &secrets),
                format!("…[redacted]{}", "z".repeat(after))
            );
        }
    }

    #[test]
    fn preview_never_backfills_nonpublishable_context_after_redaction_shrinks_it() {
        let long = "credential-".repeat(80);
        let secrets = fixture(&["hidden-secret-suffix", &long]);
        // The overlap starts inside the first secret. A second complete secret
        // shrinks the window enough that sanitize(window)-then-tail would leak it.
        let text = format!(
            "{}hidden-secret-suffix{}{long}{}",
            "x".repeat(100),
            "p".repeat(486),
            "z".repeat(7500)
        );
        let window = Window::new(&text, secrets.overlap());
        assert!(!window.text.starts_with("hidden-secret-suffix"));
        assert!(window.text.starts_with("-secret-suffix"));
        let output = preview(&text, true, &secrets);
        assert_eq!(output, format!("…[redacted]{}", "z".repeat(7500)));
        assert!(!output.contains("secret-suffix"));
        assert_eq!(preview(&text, false, &secrets), output);
    }

    #[test]
    fn preview_preserves_known_short_key_and_generic_environment_selection() {
        let secrets = Secrets::from_entries(
            [
                ("GH_TOKEN", "abc"),
                ("SERVICE_KEY", "xy"),
                ("SERVICE_SECRET", "long-secret"),
                ("ORDINARY_SETTING", "visible"),
            ]
            .into_iter()
            .map(|(name, value)| (name.into(), value.into())),
        );
        assert_eq!(preview("abc", true, &secrets), "[redacted]");
        assert_eq!(preview("a", false, &secrets), "");
        assert_eq!(preview("a", true, &secrets), "a");
        assert_eq!(preview("x", false, &secrets), "");
        assert_eq!(preview("xy", true, &secrets), "xy");
        assert_eq!(
            preview("long-secret visible", true, &secrets),
            "[redacted] visible"
        );
    }

    #[test]
    fn preview_redacts_overlapping_secrets_and_withholds_unfinished_ones() {
        let secrets = fixture(&["abcabc", "bcabcDEF", "🦀雪🦀", "雪🦀end"]);
        assert_eq!(preview("abcabc", false, &secrets), "[redacted]");
        assert_eq!(preview("abcabcD", false, &secrets), "[redacted]");
        assert_eq!(preview("abcabcDEF", false, &secrets), "[redacted]");
        assert_eq!(preview("🦀雪🦀", false, &secrets), "[redacted]");
        assert_eq!(preview("🦀雪🦀end", false, &secrets), "[redacted]");
        let same = fixture(&["ababa"]);
        assert_eq!(preview("abababa", true, &same), "[redacted]");
    }

    #[test]
    fn preview_restores_ordinary_terminal_suffixes_without_truncating_source() {
        let secrets = fixture(&["synthetic-credential"]);
        let text = format!("{}synthetic-", "雪".repeat(9000));
        assert_eq!(
            preview(&text, false, &secrets),
            format!("…{}", "雪".repeat(7990))
        );
        assert_eq!(
            preview(&text, true, &secrets),
            format!("…{}synthetic-", "雪".repeat(7990))
        );
        assert_eq!(text.chars().count(), 9010);
    }

    #[test]
    fn shared_secret_names_preserve_order_and_short_value_redaction() {
        assert_eq!(
            KNOWN_SECRETS,
            &[
                "PULLS_REVIEW_PI_API_KEY",
                "ANTHROPIC_API_KEY",
                "CLAUDE_CODE_OAUTH_TOKEN",
                "OPENAI_API_KEY",
                "GITHUB_TOKEN",
                "GH_TOKEN",
                "TYPESAFE_API_KEY",
            ]
        );
        for &name in KNOWN_SECRETS {
            let secrets = Secrets::from_entries([(name.to_owned(), "abc".to_owned())].into_iter());
            assert_eq!(preview("abc", true, &secrets), "[redacted]");
            assert_eq!(preview("a", false, &secrets), "");
        }
    }
}
