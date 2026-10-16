use super::*;
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashSet};
use std::sync::OnceLock;
fn glob(p: &str) -> String {
    let chars: Vec<_> = p.chars().collect();
    let mut s = String::from("^");
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '*' => {
                if chars.get(i + 1) == Some(&'*') {
                    i += 1;
                    if chars.get(i + 1) == Some(&'/') {
                        i += 1;
                        s.push_str("(?:.*/)?")
                    } else {
                        s.push_str(".*")
                    }
                } else {
                    s.push_str("[^/]*")
                }
            }
            '?' => s.push_str("[^/]"),
            '{' => s.push_str("(?:"),
            '}' => s.push(')'),
            ',' => s.push('|'),
            c => s.push_str(&regex::escape(&c.to_string())),
        }
        i += 1
    }
    s.push('$');
    s
}
fn name_cmp(a: &str, b: &str) -> Ordering {
    static TOKENS: OnceLock<regex::Regex> = OnceLock::new();
    let re = TOKENS.get_or_init(|| regex::Regex::new(r"\d+|\D+").unwrap());
    let al = a.to_lowercase();
    let bl = b.to_lowercase();
    let aa: Vec<_> = re.find_iter(&al).map(|m| m.as_str()).collect();
    let bb: Vec<_> = re.find_iter(&bl).map(|m| m.as_str()).collect();
    for (x, y) in aa.iter().zip(&bb) {
        if x == y {
            continue;
        }
        if let (Ok(xn), Ok(yn)) = (x.parse::<u128>(), y.parse::<u128>()) {
            if xn != yn {
                return xn.cmp(&yn);
            }
        }
        return x.cmp(y);
    }
    aa.len().cmp(&bb.len()).then(a.cmp(b))
}
fn path_cmp(a: &str, b: &str) -> Ordering {
    let aa: Vec<_> = a.split('/').collect();
    let bb: Vec<_> = b.split('/').collect();
    for (x, y) in aa[..aa.len() - 1].iter().zip(&bb[..bb.len() - 1]) {
        if x != y {
            return name_cmp(x, y);
        }
    }
    let rank = |s: &str| {
        let (stem, ext) = s.split_once('.').unwrap_or((s, ""));
        if ["index", "main", "mod", "lib", "__init__", "entry"]
            .contains(&stem.to_lowercase().as_str())
            && !ext.contains('.')
        {
            0
        } else {
            1
        }
    };
    aa.len()
        .cmp(&bb.len())
        .then_with(|| rank(aa.last().unwrap()).cmp(&rank(bb.last().unwrap())))
        .then_with(|| name_cmp(aa.last().unwrap(), bb.last().unwrap()))
}
fn stem(p: &str) -> &str {
    p.rsplit_once('.').map_or(p, |(a, _)| a)
}
fn test_keys(p: &str) -> Vec<String> {
    let mut parts: Vec<_> = p.split('/').collect();
    let name = parts.pop().unwrap_or("");
    static TEST_NAMES: OnceLock<regex::Regex> = OnceLock::new();
    let re = TEST_NAMES.get_or_init(|| {
        regex::Regex::new(
            r"^(.+)\.(?:test|spec|stories|story)\.[^.]+$|^(.+)_test\.[^.]+$|^test_(.+)\.py$",
        )
        .unwrap()
    });
    let td = parts
        .iter()
        .rposition(|p| ["__tests__", "test", "tests", "spec", "e2e"].contains(p));
    let subj = re
        .captures(name)
        .and_then(|c| (1..=3).find_map(|i| c.get(i).map(|x| x.as_str().to_string())));
    let stems = if let Some(subj) = subj {
        let p: Vec<_> = subj.split('.').collect();
        (1..=p.len())
            .rev()
            .map(|n| p[..n].join("."))
            .collect::<Vec<_>>()
    } else if td.is_some() {
        vec![stem(name).into()]
    } else {
        return vec![];
    };
    let mut dirs = vec![parts.join("/")];
    if let Some(at) = td {
        for mid in ["", "src", "lib"] {
            let mut ds = parts[..at].to_vec();
            if !mid.is_empty() {
                ds.push(mid)
            }
            ds.extend_from_slice(&parts[at + 1..]);
            dirs.push(ds.join("/"))
        }
    }
    dirs.iter()
        .flat_map(|d| {
            stems.iter().map(move |s| {
                if d.is_empty() {
                    s.clone()
                } else {
                    format!("{d}/{s}")
                }
            })
        })
        .collect()
}
fn area(p: &str) -> String {
    let p: Vec<_> = p.split('/').collect();
    if p.len() < 2 {
        return String::new();
    }
    if p.len() > 2
        && [
            "packages",
            "apps",
            "libs",
            "services",
            "crates",
            "modules",
            "plugins",
            "extensions",
            "src",
            "lib",
            "app",
            "source",
            "internal",
            "pkg",
        ]
        .contains(&p[0])
    {
        format!("{}/{}", p[0], p[1])
    } else {
        p[0].into()
    }
}
type CompiledRule = (String, String, Vec<regex::Regex>);
/// @cc [owner:OctavianTocan,label:architecture] rust-grouping-reuses-static-matchers
/// Grouping MUST reuse its immutable rule and name expressions across requests, rather
/// than compiling them per file or comparison (R14 review #11, 16 Oct 2026).
fn compiled_rules() -> &'static [CompiledRule] {
    static RULES: OnceLock<Vec<CompiledRule>> = OnceLock::new();
    RULES.get_or_init(|| {
        let rules: Vec<(String, String, Vec<String>)> =
            serde_json::from_str(include_str!("rules.json")).unwrap();
        rules
            .into_iter()
            .map(|(key, category, patterns)| {
                (
                    key,
                    category,
                    patterns
                        .iter()
                        .map(|p| regex::Regex::new(&glob(p)).unwrap())
                        .collect(),
                )
            })
            .collect()
    })
}
pub fn groups(diff: &Value) -> Value {
    let rules = compiled_rules();
    let mut buckets: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut bypath = BTreeMap::new();
    for f in diff["files"].as_array().unwrap_or(&vec![]) {
        let p = s(f, "path");
        bypath.insert(p.to_string(), f.clone());
        let key = if f["isBinary"] == true {
            if rules
                .iter()
                .find(|(k, _, _)| k.as_str() == "assets")
                .unwrap()
                .2
                .iter()
                .any(|r| r.is_match(p))
            {
                "assets"
            } else {
                "other"
            }
        } else {
            rules
                .iter()
                .find(|(_, _, rs)| rs.iter().any(|r| r.is_match(p)))
                .map_or("code", |(k, _, _)| k.as_str())
        };
        buckets.entry(key.into()).or_default().push(p.into());
    }
    for paths in buckets.values_mut() {
        paths.sort_by(|a, b| path_cmp(a, b));
        paths.dedup();
    }
    let sources = buckets.get("code").cloned().unwrap_or_default();
    let tests = buckets.get("tests").cloned().unwrap_or_default();
    let mut pairs: BTreeMap<String, Vec<String>> = BTreeMap::new();
    let mut paired = HashSet::new();
    for test in tests {
        let keys = test_keys(&test);
        if let Some(src) = keys
            .iter()
            .find_map(|k| sources.iter().find(|s| stem(s) == k))
        {
            pairs.entry(src.clone()).or_default().push(test.clone());
            paired.insert(test);
        }
    }
    let order = [
        ("code", "Code", "Source changes", "core"),
        ("types", "Types", "Type declarations", "api"),
        ("data", "Data", "Database schema and migrations", "data"),
        ("styles", "Styles", "Stylesheets and styling config", "ui"),
        ("tests", "Tests", "Test coverage for the change", "tests"),
        (
            "scripts",
            "Scripts",
            "Build, dev and automation scripts",
            "scripts",
        ),
        ("ci", "CI", "CI workflows and pipelines", "build"),
        ("config", "Config", "Configuration changes", "config"),
        ("deps", "Dependencies", "Dependency version changes", "deps"),
        (
            "i18n",
            "Translations",
            "Locale and translation files",
            "i18n",
        ),
        ("docs", "Docs", "Documentation updates", "docs"),
        ("assets", "Assets", "Images, fonts and media", "assets"),
        (
            "generated",
            "Generated",
            "Generated or build output changes",
            "other",
        ),
        (
            "other",
            "Other",
            "Other changes that don't fit an existing category",
            "other",
        ),
    ];
    let mut result = vec![];
    for (key, label, blurb, cat) in order {
        let mut paths = buckets.remove(key).unwrap_or_default();
        paths.retain(|p| !paired.contains(p));
        let mut partitions = vec![(key.to_string(), label.to_string(), paths.clone())];
        if key == "code" {
            paths = paths
                .into_iter()
                .flat_map(|p| {
                    let mut v = vec![p.clone()];
                    v.extend(pairs.get(&p).cloned().unwrap_or_default());
                    v
                })
                .collect();
            let mut areas: BTreeMap<String, Vec<String>> = BTreeMap::new();
            for src in &sources {
                let xs = areas.entry(area(src)).or_default();
                xs.push(src.clone());
                xs.extend(pairs.get(src).cloned().unwrap_or_default());
            }
            if areas.values().filter(|v| v.len() >= 3).count() >= 2 {
                partitions.clear();
                let mut small = vec![];
                for (a, ps) in areas {
                    if ps.len() >= 3 {
                        partitions.push((
                            format!("code:{}", if a.is_empty() { "." } else { &a }),
                            format!("Code · {}", if a.is_empty() { "repo root" } else { &a }),
                            ps,
                        ))
                    } else {
                        small.push((a, ps))
                    }
                }
                if small.len() == 1 {
                    let (a, ps) = small.remove(0);
                    partitions.push((
                        format!("code:{}", if a.is_empty() { "." } else { &a }),
                        format!("Code · {}", if a.is_empty() { "repo root" } else { &a }),
                        ps,
                    ))
                } else if !small.is_empty() {
                    partitions.push((
                        "code:*".into(),
                        "Code · other areas".into(),
                        small.into_iter().flat_map(|(_, ps)| ps).collect(),
                    ))
                }
            } else {
                partitions = vec![(key.into(), label.into(), paths)]
            }
        }
        for (k, l, ps) in partitions {
            if ps.is_empty() {
                continue;
            }
            let mut facts = vec![];
            for (st, one, many) in [
                ("added", "new file", "new files"),
                ("removed", "deleted", "deleted"),
                ("renamed", "renamed", "renamed"),
            ] {
                let n = ps.iter().filter(|p| bypath[*p]["status"] == st).count();
                if n > 0 {
                    facts.push(format!("{n} {}", if n == 1 { one } else { many }))
                }
            }
            let n = ps.iter().filter(|p| paired.contains(*p)).count();
            if n > 0 {
                facts.push(format!(
                    "{n} {}",
                    if n == 1 {
                        "test right after its source"
                    } else {
                        "tests right after their source"
                    }
                ))
            }
            let summary = if facts.is_empty() {
                format!("{blurb}.")
            } else {
                format!("{blurb}. {}.", facts.join(", "))
            };
            result.push(json!({"key":k,"label":l,"summary":summary,"category":cat,"filePaths":ps}));
        }
    }
    json!(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn groups_cover_files_and_pair_tests() {
        let d = json!({"files":[{"path":"src/a.test.ts","isBinary":false},{"path":"README.md","isBinary":false},{"path":"src/a.ts","isBinary":false},{"path":"Cargo.lock","isBinary":false}]});
        let g = groups(&d);
        assert_eq!(g[0]["key"], "code");
        assert_eq!(g[0]["filePaths"], json!(["src/a.ts", "src/a.test.ts"]));
        assert_eq!(g[1]["key"], "docs");
        assert_eq!(g[2]["key"], "generated");
    }
    #[test]
    fn deterministic_order_and_binary_categories() {
        let mut files = vec![
            json!({"path":"src/10.ts"}),
            json!({"path":"src/2.ts"}),
            json!({"path":"src/index.ts"}),
            json!({"path":"asset.png","isBinary":true}),
            json!({"path":"data.db","isBinary":true}),
        ];
        let a = groups(&json!({"files":files}));
        files.reverse();
        let b = groups(&json!({"files":files}));
        assert_eq!(a, b);
        assert_eq!(
            a[0]["filePaths"],
            json!(["src/index.ts", "src/2.ts", "src/10.ts"])
        );
        assert_eq!(a[1]["key"], "assets");
        assert_eq!(a[2]["key"], "other");
    }
    #[test]
    fn repeated_grouping_reuses_compiled_rules() {
        let rules = compiled_rules();
        let files: Vec<_> = (0..32)
            .rev()
            .flat_map(|n| {
                [
                    json!({"path":format!("src/item{n}.rs")}),
                    json!({"path":format!("src/item{n}.test.rs")}),
                ]
            })
            .collect();
        let diff = json!({"files":files});
        let first = groups(&diff);
        assert_eq!(first[0]["filePaths"].as_array().unwrap().len(), 64);
        assert_eq!(first[0]["filePaths"][0], "src/item0.rs");
        assert_eq!(first[0]["filePaths"][1], "src/item0.test.rs");
        for _ in 0..3 {
            assert_eq!(groups(&diff), first);
            assert!(std::ptr::eq(rules, compiled_rules()));
            assert!(std::ptr::eq(&rules[0].2[0], &compiled_rules()[0].2[0]));
        }
    }
    #[test]
    fn split_large_code_areas() {
        let files: Vec<_> = [
            "packages/a/a.rs",
            "packages/a/b.rs",
            "packages/a/c.rs",
            "packages/b/a.rs",
            "packages/b/b.rs",
            "packages/b/c.rs",
        ]
        .iter()
        .map(|p| json!({"path":p}))
        .collect();
        let g = groups(&json!({"files":files}));
        assert_eq!(g.as_array().unwrap().len(), 2);
        assert_eq!(g[0]["key"], "code:packages/a");
    }
}
