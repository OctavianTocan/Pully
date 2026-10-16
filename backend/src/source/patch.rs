use regex::Regex;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
pub fn hash(s: &str) -> String {
    hex::encode(Sha256::digest(s.as_bytes()))
}
fn unquote(s: &str) -> String {
    if !s.starts_with('"') {
        return s.into();
    }
    let s = s.trim_matches('"').as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < s.len() {
        if s[i] == b'\\' && i + 1 < s.len() {
            i += 1;
            if i + 2 < s.len() && s[i..i + 3].iter().all(|b| (b'0'..=b'7').contains(b)) {
                out.push(
                    ((s[i] - b'0') as u16 * 64
                        + (s[i + 1] - b'0') as u16 * 8
                        + (s[i + 2] - b'0') as u16) as u8,
                );
                i += 3;
                continue;
            }
            out.push(match s[i] {
                b'n' => 10,
                b't' => 9,
                b'r' => 13,
                b'a' => 7,
                b'b' => 8,
                b'v' => 11,
                b'f' => 12,
                x => x,
            });
        } else {
            out.push(s[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into()
}
fn hr() -> Regex {
    Regex::new(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$").unwrap()
}
pub fn hunks(text: &str) -> Vec<Value> {
    let lines: Vec<_> = text.split('\n').collect();
    let re = hr();
    let mut out = vec![];
    let mut i = 0;
    while i < lines.len() {
        if let Some(c) = re.captures(lines[i]) {
            let n = |k| c.get(k).map(|x| x.as_str().parse::<i64>().unwrap_or(0));
            let (os, ol, ns, nl) = (
                n(1).unwrap(),
                n(2).unwrap_or(1),
                n(3).unwrap(),
                n(4).unwrap_or(1),
            );
            let header = lines[i];
            i += 1;
            let (mut a, mut b) = (ol, nl);
            let mut body = vec![];
            while i < lines.len() && !re.is_match(lines[i]) {
                let l = lines[i];
                if !l.starts_with('\\') && a <= 0 && b <= 0 {
                    break;
                }
                if l.starts_with('-') || l.starts_with(' ') {
                    a -= 1
                }
                if l.starts_with('+') || l.starts_with(' ') {
                    b -= 1
                }
                body.push(l);
                i += 1;
            }
            while body.last() == Some(&"") {
                body.pop();
            }
            out.push(json!({"header":header,"oldStart":os,"oldLines":ol,"newStart":ns,"newLines":nl,"patch":body.join("\n")}));
        } else {
            i += 1
        }
    }
    out
}
pub fn parse_patch(text: &str) -> Vec<Value> {
    parse_patch_with_limit(text, Some(512 * 1024))
}
/// Preserve complete hunks for bounded posted patches, including secret validation.
pub fn parse_patch_untruncated(text: &str) -> Vec<Value> {
    parse_patch_with_limit(text, None)
}
fn parse_patch_with_limit(text: &str, max_file_bytes: Option<usize>) -> Vec<Value> {
    let lines: Vec<_> = text.split('\n').collect();
    let git = lines.iter().any(|l| l.starts_with("diff --git "));
    let re = hr();
    let mut starts = vec![];
    let (mut old, mut new) = (0i64, 0i64);
    let mut i = 0;
    while i < lines.len() {
        let l = lines[i];
        if git {
            if l.starts_with("diff --git ") {
                starts.push(i)
            }
        } else if old > 0 || new > 0 {
            if l.starts_with('-') || l.starts_with(' ') {
                old -= 1
            }
            if l.starts_with('+') || l.starts_with(' ') {
                new -= 1
            }
        } else if l.starts_with("--- ") && lines.get(i + 1).is_some_and(|x| x.starts_with("+++ ")) {
            starts.push(i);
            i += 1;
        } else if let Some(c) = re.captures(l) {
            old = c.get(2).map_or(1, |m| m.as_str().parse().unwrap_or(0));
            new = c.get(4).map_or(1, |m| m.as_str().parse().unwrap_or(0));
        }
        i += 1;
    }
    starts.push(lines.len());
    let mut out = vec![];
    for w in starts.windows(2) {
        let ls = &lines[w[0]..w[1]];
        let mut chunk = ls.join("\n");
        if w[1] < lines.len() {
            chunk.push('\n');
        }
        let mut path = String::new();
        let mut prev = None;
        let mut status = "modified";
        let mut sha = None;
        let mut binary = false;
        if git {
            let names = ls[0].trim_start_matches("diff --git ");
            if names.starts_with('"') {
                let tokens = Regex::new(r#""(?:[^"\\]|\\.)*""#).unwrap();
                let found = tokens.find_iter(names).nth(1);
                if let Some(m) = found {
                    path = unquote(m.as_str());
                    path = path.split_once('/').map_or(path.clone(), |(_, p)| p.into());
                }
            } else {
                let mid = names.len() / 2;
                if names.is_char_boundary(mid) && names.as_bytes().get(mid) == Some(&b' ') {
                    let (a, b) = (&names[..mid], &names[mid + 1..]);
                    let strip = |s: &str| s.split_once('/').map_or(s, |(_, p)| p).to_string();
                    if a == b {
                        path = b.into()
                    } else if strip(a) == strip(b) {
                        path = strip(b)
                    }
                }
                if path.is_empty() {
                    path = names.rsplit_once(" b/").map_or("", |(_, p)| p).into();
                }
            }
            for l in ls.iter().skip(1).take_while(|l| !l.starts_with("@@ ")) {
                if l.starts_with("new file mode") {
                    status = "added"
                }
                if l.starts_with("deleted file mode") {
                    status = "removed"
                }
                if *l == "GIT binary patch" || l.starts_with("Binary files ") {
                    binary = true
                }
                for (prefix, which) in [
                    ("rename from ", 0),
                    ("rename to ", 1),
                    ("copy from ", 2),
                    ("copy to ", 3),
                ] {
                    if let Some(p) = l.strip_prefix(prefix) {
                        if which % 2 == 0 {
                            prev = Some(unquote(p))
                        } else {
                            path = unquote(p)
                        }
                        status = if which < 2 { "renamed" } else { "copied" };
                    }
                }
                if let Some(idx) = l.strip_prefix("index ") {
                    if let Some((a, b)) =
                        idx.split_whitespace().next().unwrap_or("").split_once("..")
                    {
                        sha = Some(if status == "removed" { a } else { b }.to_string());
                    }
                }
            }
        } else {
            let a = ls[0]
                .trim_start_matches("--- ")
                .split('\t')
                .next()
                .unwrap_or("");
            let b = ls[1]
                .trim_start_matches("+++ ")
                .split('\t')
                .next()
                .unwrap_or("");
            path = if b == "/dev/null" { a } else { b }.into();
            status = if a == "/dev/null" {
                "added"
            } else if b == "/dev/null" {
                "removed"
            } else {
                "modified"
            };
        }
        if path.is_empty() {
            continue;
        }
        let hs = if binary { vec![] } else { hunks(&chunk) };
        let (mut adds, mut dels) = (0, 0);
        for h in &hs {
            for l in h["patch"].as_str().unwrap_or("").split('\n') {
                if l.starts_with('+') {
                    adds += 1
                } else if l.starts_with('-') {
                    dels += 1
                }
            }
        }
        let mut f = json!({"path":path,"status":status,"sha":sha.unwrap_or_else(||hash(&chunk)),"additions":adds,"deletions":dels,"isBinary":binary,"hunks":hs});
        if let Some(p) = prev {
            f["previousPath"] = json!(p)
        }
        if max_file_bytes.is_some_and(|limit| chunk.len() > limit) {
            f["hunks"] = json!([]);
            f["truncated"] = json!(true)
        }
        out.push(f);
    }
    out
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn posix_headers_in_hunk() {
        let f = parse_patch("--- old\n+++ new\n@@ -1,1 +1,1 @@\n--- old code\n+++ new code\n");
        assert_eq!(f.len(), 1);
        assert_eq!(f[0]["deletions"], 1);
        assert_eq!(f[0]["additions"], 1);
    }
    #[test]
    fn deletion_sha() {
        let f=parse_patch("diff --git a/a b/a\ndeleted file mode 100644\nindex abc..000\n--- a/a\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n");
        assert_eq!(f[0]["sha"], "abc");
    }
}

#[cfg(test)]
mod regression_tests {
    use super::*;
    #[test]
    fn parses_renames_and_binary() {
        let fs=parse_patch("diff --git a/a b/b\nsimilarity index 100%\nrename from a\nrename to b\nindex abc..def 100644\ndiff --git a/pic b/pic\nindex aaa..bbb 100644\nBinary files a/pic and b/pic differ\n");
        assert_eq!(fs.len(), 2);
        assert_eq!(fs[0]["previousPath"], "a");
        assert_eq!(fs[0]["path"], "b");
        assert_eq!(fs[0]["sha"], "def");
        assert_eq!(fs[1]["isBinary"], true);
    }
    #[test]
    fn preserves_no_newline_marker() {
        let hs = hunks(
            "@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n",
        );
        assert_eq!(
            hs[0]["patch"],
            "-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file"
        );
    }
    #[test]
    fn quoted_octal_path() {
        let fs=parse_patch("diff --git \"a/a\\303\\251.txt\" \"b/a\\303\\251.txt\"\nindex aaa..bbb 100644\n@@ -1 +1 @@\n-a\n+b\n");
        assert_eq!(fs[0]["path"], "aé.txt");
    }
    #[test]
    fn plain_diff_multiple_files() {
        let fs=parse_patch("--- old\n+++ new\n@@ -1 +1 @@\n--- source\n+++ source\n--- old2\n+++ new2\n@@ -0,0 +1 @@\n+x\n");
        assert_eq!(fs.len(), 2);
        assert_eq!(fs[0]["path"], "new");
        assert_eq!(fs[1]["path"], "new2");
    }
}
