//! The deliberately small frontmatter dialect from packages/cli/src/lenses.ts.
use anyhow::{bail, Result};
use serde_json::{json, Value};
use std::{collections::HashMap, fs::File, io::Read, path::Path};

const MAX_INSTRUCTIONS: usize = 30_000;
const MAX_SKILL_BYTES: usize = 1024 * 1024;
const MAX_LIST_READ_BYTES: usize = 8 * 1024 * 1024;
const MAX_LIST_BYTES: usize = 1024 * 1024;
const MAX_ENTRIES: usize = 1024;

// The Worker cannot read the curated directory. Bundle the same shipped skills rather
// than silently offering an empty catalog or reading an account's CLI home.
#[cfg(any(target_arch = "wasm32", test))]
const SHIPPED: &[(&str, &str)] = &[
    ("android-compose", include_str!("../../../docker/agents/skills/android-compose/SKILL.md")),
    ("api-and-contracts", include_str!("../../../docker/agents/skills/api-and-contracts/SKILL.md")),
    ("correctness", include_str!("../../../docker/agents/skills/correctness/SKILL.md")),
    ("performance", include_str!("../../../docker/agents/skills/performance/SKILL.md")),
    ("security", include_str!("../../../docker/agents/skills/security/SKILL.md")),
    ("tests", include_str!("../../../docker/agents/skills/tests/SKILL.md")),
];

fn valid_name(name: &str) -> bool {
    let word = |b: u8| b.is_ascii_alphanumeric() || b == b'_' || b == b'-';
    name.as_bytes().first().is_some_and(|b| word(*b)) && name.bytes().all(|b| word(b) || b == b'.')
}

/// @cc [owner:OctavianTocan,label:security] rust-lens-reads-stay-curated
/// Lens reads MUST validate the original REVIEW_LENS_NAME grammar and stay inside
/// the canonical curated directory, including file and directory symlinks. Reads
/// MUST be bounded and MUST NOT search account skills (server-curated-skills-only;
/// Tavi's 16 Oct 2026 backend parity request).
fn read_skill(root: &Path, name: &str) -> Result<Option<String>> {
    if !valid_name(name) {
        return Ok(None);
    }
    let Ok(path) = root.join(name).join("SKILL.md").canonicalize() else {
        return Ok(None);
    };
    if !path.starts_with(root) {
        bail!("Lens escapes the curated skills directory");
    }
    // Do not open directories, devices or FIFOs as skills.
    if !path.metadata().is_ok_and(|metadata| metadata.is_file()) {
        return Ok(None);
    }
    let Ok(file) = File::open(&path) else {
        return Ok(None);
    };
    #[cfg(target_os = "linux")]
    {
        use std::os::fd::AsRawFd;
        let Ok(opened) = std::fs::canonicalize(format!("/proc/self/fd/{}", file.as_raw_fd()))
        else {
            return Ok(None);
        };
        if !opened.starts_with(root) {
            bail!("Lens escapes the curated skills directory");
        }
    }
    let Ok(metadata) = file.metadata() else {
        return Ok(None);
    };
    if !metadata.is_file() {
        return Ok(None);
    }
    if metadata.len() > MAX_SKILL_BYTES as u64 {
        bail!("Curated skill exceeds the 1 MiB read limit");
    }
    let mut bytes = Vec::new();
    if file
        .take(MAX_SKILL_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .is_err()
    {
        return Ok(None);
    }
    if bytes.len() > MAX_SKILL_BYTES {
        bail!("Curated skill exceeds the 1 MiB read limit");
    }
    // Node's readFile(..., 'utf8') also replaces malformed UTF-8.
    Ok(Some(String::from_utf8_lossy(&bytes).into_owned()))
}

// ECMAScript trim/\s differ from Rust's Unicode whitespace (notably BOM and U+0085).
fn whitespace(c: char) -> bool {
    matches!(c, '\u{0009}'..='\u{000d}' | '\u{0020}' | '\u{00a0}' | '\u{1680}'
        | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}'
        | '\u{205f}' | '\u{3000}' | '\u{feff}')
}

fn trim(value: &str) -> &str {
    value.trim_matches(whitespace)
}

fn parse_skill(text: &str) -> (HashMap<&str, String>, &str) {
    let mut fields = HashMap::new();
    let Some(rest) = text
        .strip_prefix("---\n")
        .or_else(|| text.strip_prefix("---\r\n"))
    else {
        return (fields, trim(text));
    };
    let Some(end) = rest.find("\n---") else {
        return (fields, trim(text));
    };
    let header = rest[..end].strip_suffix('\r').unwrap_or(&rest[..end]);
    let body = &rest[end + 4..];
    let body = body.strip_prefix('\r').unwrap_or(body);
    let body = body.strip_prefix('\n').unwrap_or(body);
    let mut lines = header
        .split_inclusive('\n')
        .map(|line| match line.strip_suffix('\n') {
            Some(line) => line.strip_suffix('\r').unwrap_or(line),
            None => line,
        })
        .peekable();
    while let Some(line) = lines.next() {
        let Some((key, raw)) = line.split_once(':') else {
            continue;
        };
        if key.is_empty()
            || !key
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            continue;
        }
        // JavaScript's dot excludes these line terminators as well as newline.
        if raw.contains(['\r', '\u{2028}', '\u{2029}']) {
            continue;
        }
        let mut value = trim(raw).to_owned();
        if matches!(value.as_str(), "" | "|" | "|-" | "|+" | ">" | ">-" | ">+") {
            let mut continued = Vec::new();
            while lines.peek().is_some_and(|line| {
                line.chars().next().is_some_and(whitespace) && line.chars().any(|c| !whitespace(c))
            }) {
                continued.push(trim(lines.next().unwrap()));
            }
            value = continued.join(" ");
        }
        if value.len() >= 2
            && matches!(value.as_bytes()[0], b'\'' | b'"')
            && value.as_bytes().first() == value.as_bytes().last()
        {
            value = value[1..value.len() - 1].to_owned();
        }
        // Assigning a string to the original plain object's __proto__ is ignored.
        if key != "__proto__" {
            fields.insert(key, value);
        }
    }
    (fields, trim(body))
}

fn instructions(body: &str) -> Option<String> {
    if body.is_empty() {
        return None;
    }
    let mut units = 0;
    for (index, c) in body.char_indices() {
        units += c.len_utf16();
        if units > MAX_INSTRUCTIONS {
            // JS slice can leave a lone surrogate here. Keep a valid Unicode scalar
            // boundary instead, never exceeding its 30,000 UTF-16-unit budget.
            return Some(format!("{}\n[truncated]", &body[..index]));
        }
    }
    Some(body.to_owned())
}

/// @cc [owner:OctavianTocan,label:architecture] rust-lens-instructions-match-client
/// Missing, invalid or empty lenses MUST return no instructions. Frontmatter MUST
/// be stripped with the original plain-field parser; overlong bodies MUST retain
/// the newline and [truncated] marker at a valid Unicode boundary within 30,000
/// UTF-16 units (packages/cli/src/lenses.ts; Tavi's 16 Oct 2026 parity request).
#[cfg(not(target_arch = "wasm32"))]
pub(super) fn get(root: &Path, name: &str) -> Result<Option<String>> {
    if !valid_name(name) {
        return Ok(None);
    }
    let Ok(root) = root.canonicalize() else {
        return Ok(None);
    };
    Ok(read_skill(&root, name)?.and_then(|text| instructions(parse_skill(&text).1)))
}

#[cfg(target_arch = "wasm32")]
pub(super) fn get(_: &Path, name: &str) -> Result<Option<String>> {
    Ok(SHIPPED.iter().find(|(key, _)| *key == name).and_then(|(_, text)| instructions(parse_skill(text).1)))
}

/// @cc [owner:OctavianTocan,label:architecture] rust-lens-catalog-preserves-metadata
/// Within the read/output limits, each skill MUST retain its top-level title (or folder-name
/// fallback) and nonempty description, even when its body is empty. The current
/// curated titles MUST sort in the original display order; enumeration and output
/// MUST be bounded (packages/cli/src/lenses.ts; Tavi's 16 Oct 2026 parity request).
#[cfg(not(target_arch = "wasm32"))]
pub(super) fn list(root: &Path) -> Result<Value> {
    let Ok(root) = root.canonicalize() else {
        return Ok(json!([]));
    };
    let Ok(entries) = std::fs::read_dir(&root) else {
        return Ok(json!([]));
    };
    let mut names = Vec::new();
    for (index, entry) in entries.enumerate() {
        if index >= MAX_ENTRIES {
            bail!("Curated skill directory exceeds the 1024 entry limit");
        }
        if let Ok(entry) = entry {
            if let Ok(name) = entry.file_name().into_string() {
                if valid_name(&name) {
                    names.push(name);
                }
            }
        }
    }
    names.sort();
    let mut out = Vec::new();
    let mut read_bytes = 0;
    for name in names {
        // Unreadable, oversized or escaping files are not offered as lenses.
        let Some(text) = read_skill(&root, &name).ok().flatten() else {
            continue;
        };
        read_bytes += text.len();
        if read_bytes > MAX_LIST_READ_BYTES {
            bail!("Curated skills exceed the 8 MiB catalog read limit");
        }
        let (fields, _) = parse_skill(&text);
        let title = fields.get("title").filter(|value| !value.is_empty());
        let mut lens = json!({"name": name, "title": title.unwrap_or(&name)});
        if let Some(description) = fields.get("description").filter(|value| !value.is_empty()) {
            lens["description"] = json!(description);
        }
        out.push(lens);
    }
    finish_catalog(out)
}

#[cfg(target_arch = "wasm32")]
pub(super) fn list(_: &Path) -> Result<Value> {
    let mut out = Vec::new();
    for (name, text) in SHIPPED {
        let (fields, _) = parse_skill(text);
        let mut lens = json!({"name":name,"title":fields.get("title").filter(|v| !v.is_empty()).map(String::as_str).unwrap_or(name)});
        if let Some(description) = fields.get("description").filter(|v| !v.is_empty()) {
            lens["description"] = json!(description);
        }
        out.push(lens);
    }
    finish_catalog(out)
}

fn finish_catalog(mut out: Vec<Value>) -> Result<Value> {
    // The shipped ASCII titles match localeCompare's order. Arbitrary Unicode
    // locale collation needs an ICU dependency; use deterministic case-folded
    // ordering here, with lowercase before uppercase and folder name for ties.
    out.sort_by_cached_key(|lens| {
        let title = lens["title"].as_str().unwrap();
        (
            title.to_lowercase(),
            std::cmp::Reverse(title.to_owned()),
            lens["name"].as_str().unwrap().to_owned(),
        )
    });
    let out = json!(out);
    if serde_json::to_vec(&out)?.len() > MAX_LIST_BYTES {
        bail!("Curated lens catalog exceeds the 1 MiB output limit");
    }
    Ok(out)
}

#[cfg(test)]
mod tests;
