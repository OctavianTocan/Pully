use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashSet;
fn present_optional<'de, D: serde::Deserializer<'de>, T: Deserialize<'de>>(
    deserializer: D,
) -> Result<Option<T>, D::Error> {
    T::deserialize(deserializer).map(Some)
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Group {
    key: String,
    label: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    summary: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    category: Option<Category>,
    file_paths: Vec<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    children: Option<Vec<Leaf>>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Leaf {
    key: String,
    label: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    summary: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    category: Option<Category>,
    file_paths: Vec<String>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum Category {
    Ui,
    Api,
    Core,
    Data,
    Cli,
    Security,
    Tests,
    Docs,
    Examples,
    Deps,
    Build,
    Scripts,
    Config,
    I18n,
    Assets,
    Other,
}
#[derive(Deserialize, Serialize)]
struct HunkNote {
    index: f64,
    note: String,
}
#[derive(Deserialize, Serialize)]
struct FileNote {
    path: String,
    summary: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    hunks: Option<Vec<HunkNote>>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Groups {
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    overall_summary: Option<String>,
    groups: Vec<Group>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    files: Option<Vec<FileNote>>,
}
#[derive(Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum Block {
    Text {
        text: String,
    },
    Shape {
        text: String,
        lang: String,
        body: String,
    },
    Diagram {
        text: String,
        body: String,
    },
}
#[derive(Deserialize, Serialize)]
struct Source {
    path: String,
    line: f64,
    #[serde(default)]
    why: String,
}
#[derive(Deserialize, Serialize)]
struct Stop {
    title: String,
    #[serde(default)]
    purpose: String,
    #[serde(default)]
    files: Vec<String>,
    #[serde(default)]
    takeaway: String,
    #[serde(default)]
    blocks: Vec<Block>,
    #[serde(default)]
    sources: Vec<Source>,
}
#[derive(Deserialize, Serialize)]
struct Glossary {
    term: String,
    meaning: String,
}
fn english() -> String {
    "en".into()
}
#[derive(Deserialize, Serialize)]
struct Tour {
    title: String,
    #[serde(default)]
    overview: String,
    stops: Vec<Stop>,
    #[serde(default)]
    glossary: Vec<Glossary>,
    #[serde(default = "english")]
    locale: String,
}
#[derive(Deserialize, Serialize)]
struct Brief {
    lang: String,
    #[serde(default = "english")]
    locale: String,
}
#[derive(Deserialize, Serialize, Default)]
#[serde(rename_all = "lowercase")]
enum Side {
    #[default]
    Additions,
    Deletions,
}
#[derive(Deserialize, Serialize, Default)]
#[serde(rename_all = "lowercase")]
enum Severity {
    Bug,
    Risk,
    #[default]
    Nit,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Finding {
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    id: Option<String>,
    path: String,
    #[serde(default)]
    side: Side,
    line: f64,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    start_line: Option<f64>,
    #[serde(default)]
    severity: Severity,
    title: String,
    #[serde(default)]
    body: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "present_optional"
    )]
    suggestion: Option<String>,
}
#[derive(Deserialize, Serialize)]
struct Review {
    #[serde(default)]
    summary: String,
    findings: Vec<Finding>,
}
fn paths(diff: &Value) -> HashSet<String> {
    diff["files"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|f| f["path"].as_str().map(str::to_owned))
        .collect()
}
pub fn keep_in_diff(mut agent: Value, diff: &Value) -> Value {
    let paths = paths(diff);
    if let Some(groups) = agent
        .pointer_mut("/groups/groups")
        .and_then(Value::as_array_mut)
    {
        for g in groups {
            filter(g, "filePaths", &paths, false);
            if let Some(children) = g["children"].as_array_mut() {
                for c in children {
                    filter(c, "filePaths", &paths, false);
                }
            }
        }
    }
    if let Some(g) = agent.get_mut("groups") {
        filter(g, "files", &paths, true);
    }
    if let Some(stops) = agent
        .pointer_mut("/tour/stops")
        .and_then(Value::as_array_mut)
    {
        stops.retain_mut(|s| {
            let had = s["files"].as_array().map_or(0, Vec::len)
                + s["sources"].as_array().map_or(0, Vec::len);
            filter(s, "files", &paths, false);
            filter(s, "sources", &paths, true);
            had == 0
                || s["files"].as_array().map_or(0, Vec::len)
                    + s["sources"].as_array().map_or(0, Vec::len)
                    > 0
        });
    }
    if let Some(r) = agent.get_mut("review") {
        filter(r, "findings", &paths, true);
    }
    agent
}
fn filter(v: &mut Value, key: &str, paths: &HashSet<String>, object: bool) {
    if let Some(a) = v[key].as_array_mut() {
        a.retain(|v| {
            paths.contains(
                if object {
                    v["path"].as_str()
                } else {
                    v.as_str()
                }
                .unwrap_or(""),
            )
        });
    }
}
pub fn check_agent_fields(
    fields: &Value,
    diff: &Value,
    mut previous: Value,
) -> (Value, Vec<String>) {
    let paths = paths(diff);
    let mut warnings = vec![];
    if !previous.is_object() {
        previous = json!({});
    }
    for name in ["groups", "tour", "brief", "review"] {
        let Some(value) = fields.get(name) else {
            continue;
        };
        let parsed: Result<Value, serde_json::Error> = (|| {
            Ok(match name {
                "groups" => {
                    let mut g: Groups = serde_json::from_value(value.clone())?;
                    let mut unknown = 0;
                    for group in &mut g.groups {
                        let before = group.file_paths.len();
                        group.file_paths.retain(|p| paths.contains(p));
                        unknown += before - group.file_paths.len();
                        for child in group.children.iter_mut().flatten() {
                            let before = child.file_paths.len();
                            child.file_paths.retain(|p| paths.contains(p));
                            unknown += before - child.file_paths.len();
                        }
                    }
                    if unknown > 0 {
                        warnings.push(format!(
                            "groups named {unknown} {} not in the diff; they were left out.",
                            if unknown == 1 { "path" } else { "paths" }
                        ));
                    }
                    if let Some(files) = &mut g.files {
                        files.retain(|f| paths.contains(&f.path));
                    }
                    let mut out = serde_json::to_value(g)?;
                    out["schemaVersion"] = json!(1);
                    out
                }
                "tour" => {
                    let mut t: Tour = serde_json::from_value(value.clone())?;
                    for stop in &mut t.stops {
                        stop.files.retain(|p| paths.contains(p));
                        stop.sources.retain(|s| paths.contains(&s.path));
                    }
                    serde_json::to_value(t)?
                }
                "brief" => {
                    if let Some(lang) = value.as_str() {
                        json!({"lang":lang,"locale":"en"})
                    } else {
                        serde_json::to_value(serde_json::from_value::<Brief>(value.clone())?)?
                    }
                }
                _ => {
                    let mut r: Review = serde_json::from_value(value.clone())?;
                    let before = r.findings.len();
                    r.findings.retain(|f| paths.contains(&f.path));
                    let dropped = before - r.findings.len();
                    if dropped > 0 {
                        warnings.push(format!(
                            "review named {dropped} {} outside the diff; they were left out.",
                            if dropped == 1 { "finding" } else { "findings" }
                        ));
                    }
                    for (i, f) in r.findings.iter_mut().enumerate() {
                        if f.id.is_none() {
                            f.id = Some(format!("agent-{}", i + 1));
                        }
                    }
                    let mut out = serde_json::to_value(r)?;
                    out["dropped"] = json!(dropped);
                    out
                }
            })
        })();
        match parsed {
            Ok(v) => previous[name] = v,
            Err(_) => warnings.push(format!(
                "{name} was left out: it is not the expected shape."
            )),
        }
    }
    (previous, warnings)
}
