//! Grouping generation, reconciliation and the chat-bound persistence capability.
use super::{add_usage, array, iso, locale, obj, string, strings, update_diff, update_diff_async};
use crate::ai::{check, prepare_structured_answer, record, Run, StructuredAnswerPreparation};
use crate::{now_ms, required, text, AppState};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::sync::Arc;

pub(in crate::ai) fn grouping_schema() -> Value {
    let leaf = obj(
        json!({"key":string(),"label":string(),"summary":string(),"category":{"type":"string","enum":["ui","api","core","data","cli","security","tests","docs","examples","deps","build","scripts","config","i18n","assets","other"]},"filePaths":strings()}),
        &["key", "label", "category", "filePaths"],
    );
    let mut group = leaf.clone();
    group["properties"]["children"] = json!({"type":"array","items":leaf});
    obj(
        json!({"overallSummary":string(),"groups":{"type":"array","items":group},"files":{"type":"array","items":obj(json!({"path":string(),"summary":string(),"hunks":{"type":"array","items":obj(json!({"index":{"type":"number"},"note":string()}),&["index","note"])}}),&["path","summary"])}}),
        &["overallSummary", "groups"],
    )
}

/// @cc [owner:OctavianTocan,label:product] rust-group-labels-read-apart
/// Grouping instructions MUST request short distinct labels without shared opening
/// words or a child repeating its parent (16 Oct 12:10, N34).
pub(super) async fn analyze(
    state: &Arc<AppState>,
    run: &Arc<Run>,
    mut job: Value,
    entry: &Value,
    heading: &str,
    invoke_provider: super::TaskProvider,
) -> Result<(String, Value)> {
    let r = &run.request;
    let diff = &entry["diff"];
    job["schema"] = grouping_schema();
    job["prompt"] = json!(format!("{heading}\nRead the diff. Group files by intent, with at most one child level. Every changed path must appear exactly once. Labels must be at most four words and distinct at a glance: no two groups, and no two children, start with the same words. A child label must never repeat its parent label, since the phone shows Parent › Child and cuts long labels. Explain the change overall and summarize each file."));
    let (mut answer, mut usage) =
        invoke_provider(state, run, &job, StructuredAnswerPreparation::Grouping).await?;
    check(run)?;
    let first = grouping_arguments(&serde_json::from_str::<Value>(&answer)?);
    if let Some(error) = coverage_error(diff, &first) {
        record(
            run,
            "grouping-repair",
            "status",
            "Repairing grouping coverage",
            Some(&error),
            "running",
        );
        let mut repair = job.clone();
        repair["prompt"] = json!(format!("{}\nPrevious grouping: {}\n{}\nSubmit the complete corrected grouping, with every changed path exactly once.",text(&job,"prompt"),first,error));
        let (repaired, spent) =
            invoke_provider(state, run, &repair, StructuredAnswerPreparation::Grouping).await?;
        check(run)?;
        answer = repaired;
        add_usage(&mut usage, &spent);
        record(
            run,
            "grouping-repair",
            "status",
            "Reconciled grouping coverage",
            None,
            "done",
        );
    }
    let result = grouped_result(diff, &serde_json::from_str(&answer)?, r, &usage, false)?;
    let saved_result = result.clone();
    let head = entry["headSha"].clone();
    update_diff_async(state, run, &entry["headSha"], move |e| {
        let mut previous = e
            .get("llmSession")
            .cloned()
            .unwrap_or(json!({"messages":[],"chatStartIndex":0}));
        archive(&mut previous);
        let transcript = vec![
            json!({"role":"assistant","content":[{"type":"text","text":serde_json::to_string(&saved_result)?}],"timestamp":now_ms()}),
        ];
        e["llmSession"] = json!({"messages":transcript,"chatStartIndex":1,"archive":previous["archive"].as_array().cloned().unwrap_or_default()});
        crate::review::set_llm_analysis(e, saved_result, head);
        Ok(())
    }).await?;
    Ok((serde_json::to_string(&result)?, usage))
}

fn parsed_nested(value: &Value) -> Value {
    if let Some(text) = value.as_str() {
        serde_json::from_str(text).unwrap_or_else(|_| value.clone())
    } else {
        value.clone()
    }
}
pub(super) fn grouping_arguments(raw: &Value) -> Value {
    let mut value = parsed_nested(raw);
    if !value.is_object() {
        return value;
    }
    value["groups"] = parsed_nested(&value["groups"]);
    fn group(raw: &mut Value) {
        *raw = parsed_nested(raw);
        if !raw.is_object() {
            return;
        }
        raw["filePaths"] = parsed_nested(&raw["filePaths"]);
        if let Some(children) = raw.get_mut("children") {
            *children = parsed_nested(children);
            if let Some(children) = children.as_array_mut() {
                for child in children {
                    group(child);
                }
            }
        }
    }
    if let Some(groups) = value["groups"].as_array_mut() {
        for raw in groups {
            group(raw);
        }
    }
    if let Some(files) = value.get_mut("files") {
        *files = parsed_nested(files);
        if let Some(notes) = files.as_array_mut() {
            for note in notes {
                *note = parsed_nested(note);
                if let Some(hunks) = note.get_mut("hunks") {
                    *hunks = parsed_nested(hunks);
                }
            }
        }
    }
    value
}
fn coverage_error(diff: &Value, analysis: &Value) -> Option<String> {
    use std::collections::HashSet;
    let expected: HashSet<_> = diff["files"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|f| text(f, "path").to_owned())
        .collect();
    let mut seen = HashSet::new();
    let mut duplicates = HashSet::new();
    let mut unknown = HashSet::new();
    for group in analysis["groups"].as_array().into_iter().flatten() {
        for node in std::iter::once(group).chain(group["children"].as_array().into_iter().flatten())
        {
            for path in node["filePaths"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                if !expected.contains(path) {
                    unknown.insert(path.to_owned());
                } else if !seen.insert(path.to_owned()) {
                    duplicates.insert(path.to_owned());
                }
            }
        }
    }
    let mut lines = vec![];
    for (label, paths) in [
        (
            "Missing paths",
            expected.difference(&seen).cloned().collect::<Vec<_>>(),
        ),
        ("Duplicated paths", duplicates.into_iter().collect()),
        ("Unknown paths", unknown.into_iter().collect()),
    ] {
        if !paths.is_empty() {
            let mut paths = paths;
            paths.sort();
            lines.push(format!("{label}: {}", paths.join(", ")));
        }
    }
    (!lines.is_empty()).then(|| lines.join("\n"))
}
/// @cc [owner:OctavianTocan,label:product] rust-grouping-reconciles-model-notes
/// Grouping results MUST exclude unknown and duplicate paths and invalid hunk notes;
/// chat updates MUST reject incomplete path coverage before changing saved analysis
/// (Rust parity request, 16 Oct 2026; core llm toGroupedResult and update_grouping).
fn grouped_result(
    diff: &Value,
    raw: &Value,
    request: &Value,
    usage: &Value,
    strict: bool,
) -> Result<Value> {
    let analysis = prepare_structured_answer(
        &grouping_schema(),
        raw,
        StructuredAnswerPreparation::Grouping,
    )?;
    if strict {
        if let Some(error) = coverage_error(diff, &analysis) {
            bail!("{error}\nFix these and call update_grouping again.");
        }
    }
    let diff_files = diff["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let known: std::collections::HashSet<_> = diff_files.iter().map(|f| text(f, "path")).collect();
    let mut seen = std::collections::HashSet::new();
    let mut groups = vec![];
    for group in array(&analysis["groups"]) {
        let mut normalize = |node: &Value| {
            let mut result = json!({"key":node["key"],"label":node["label"],"category":node["category"],"filePaths":array(&node["filePaths"]).into_iter().filter(|p| p.as_str().is_some_and(|p| known.contains(p) && seen.insert(p.to_owned()))).collect::<Vec<_>>()});
            if let Some(summary) = node.get("summary") {
                result["summary"] = summary.clone();
            }
            result
        };
        let mut parent = normalize(&group);
        let children: Vec<_> = array(&group["children"])
            .iter()
            .map(&mut normalize)
            .filter(|child| !array(&child["filePaths"]).is_empty())
            .collect();
        if !children.is_empty() {
            parent["children"] = json!(children);
        }
        if !array(&parent["filePaths"]).is_empty() || !array(&parent["children"]).is_empty() {
            groups.push(parent);
        }
    }
    let mut note_paths = std::collections::HashSet::new();
    let mut notes = vec![];
    for note in analysis["files"].as_array().into_iter().flatten() {
        let path = text(note, "path");
        let summary = text(note, "summary").trim();
        let Some(file) = diff_files.iter().find(|f| f["path"] == path) else {
            continue;
        };
        if summary.is_empty() || !note_paths.insert(path.to_owned()) {
            continue;
        }
        let mut indices = std::collections::HashSet::new();
        let hunk_count = file["hunks"].as_array().map_or(0, Vec::len) as u64;
        let mut hunks: Vec<_> = note["hunks"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|h| {
                let index = h["index"].as_u64()?;
                let note = text(h, "note").trim();
                (index < hunk_count && !note.is_empty() && indices.insert(index))
                    .then(|| json!({"index":index,"note":note}))
            })
            .collect();
        hunks.sort_by_key(|h| h["index"].as_u64().unwrap_or(0));
        let mut entry = json!({"path":path,"summary":summary});
        if !hunks.is_empty() {
            entry["hunks"] = json!(hunks);
        }
        notes.push(entry);
    }
    let model = usage["model"]
        .as_str()
        .or_else(|| request["model"]["model"].as_str())
        .unwrap_or("");
    let mut result = json!({"overallSummary":analysis["overallSummary"],"groups":groups,"source":"llm","schemaVersion":1,"generatedAt":iso(now_ms()),"locale":locale(request),"model":format!("{}/{}",text(&request["model"],"engine"),model)});
    if !notes.is_empty() {
        result["files"] = json!(notes);
    }
    Ok(result)
}
pub(in crate::ai) fn update_grouping(
    state: &AppState,
    run: &Run,
    context: &Value,
    args: &Value,
) -> Result<String> {
    check(run)?;
    if !run.task || run.request["kind"] != "chat" || context["allowGroupingUpdate"] != true {
        bail!("Grouping updates are available only to the active chat task");
    }
    let key = required(&run.request, "key")?;
    let entry = state
        .host
        .storage
        .get(&format!("diff:{key}"))?
        .context("This diff is no longer cached; open it again.")?;
    if entry["diff"]["head"]["sha"] != context["headSha"]
        || entry["diff"]["files"] != context["files"]
    {
        bail!(
            "The pull request changed while the model was working. Run again on the current head."
        );
    }
    let result = grouped_result(&entry["diff"], args, &run.request, &json!({}), true)?;
    update_diff(state, key, &entry["headSha"], run, |e| {
        if e["diff"] != entry["diff"] {
            bail!("The diff changed while the grouping was being saved");
        }
        crate::review::set_llm_analysis(e, result.clone(), entry["headSha"].clone());
        Ok(())
    })?;
    record(
        run,
        &format!("grouping-update:{}", uuid::Uuid::new_v4()),
        "tool",
        "Updated grouping",
        Some(&serde_json::to_string(&result)?),
        "done",
    );
    Ok(format!(
        "Grouping updated: {} groups.",
        array(&result["groups"]).len()
    ))
}

fn archive(session: &mut Value) {
    let msgs = array(&session["messages"]);
    let n = (session["chatStartIndex"].as_u64().unwrap_or(0) as usize).min(msgs.len());
    let chat = &msgs[n..];
    if chat.iter().any(|m| m["role"] == "user") {
        let mut saved =
            json!({"id":uuid::Uuid::new_v4().to_string(),"archivedAt":now_ms(),"messages":chat});
        if let Some(s) = session.get("suggestions") {
            saved["suggestions"] = s.clone()
        }
        let mut ar = array(&session["archive"]);
        ar.insert(0, saved);
        ar.truncate(30);
        session["archive"] = json!(ar)
    }
    session["messages"] = json!(&msgs[..n]);
    session.as_object_mut().unwrap().remove("suggestions");
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ai::tasks::context;
    use std::sync::Mutex;
    use tokio::sync::{watch, Notify};
    #[test]
    fn archives_preserve_analysis_and_full_answers() {
        let long = "x".repeat(50000);
        let mut session = json!({"messages":[{"role":"assistant","content":"analysis"},{"role":"user","content":"Why?"},{"role":"assistant","content":long}],"chatStartIndex":1});
        archive(&mut session);
        assert_eq!(session["messages"].as_array().unwrap().len(), 1);
        assert_eq!(
            session["archive"][0]["messages"][1]["content"]
                .as_str()
                .unwrap()
                .len(),
            50000
        );
        for _ in 0..35 {
            session["messages"]
                .as_array_mut()
                .unwrap()
                .push(json!({"role":"user","content":"again"}));
            archive(&mut session);
        }
        assert_eq!(session["archive"].as_array().unwrap().len(), 30);
    }
    #[test]
    fn grouping_normalizes_nested_arrays_and_reconciles_notes() {
        let diff = json!({"files":[{"path":"a.rs","hunks":[{},{}]},{"path":"b.rs","hunks":[]}]});
        let raw = json!({"overallSummary":"Together.","groups":"[{\"key\":\"all\",\"label\":\"All\",\"category\":\"core\",\"filePaths\":\"[\\\"a.rs\\\",\\\"b.rs\\\"]\"}]","files":[{"path":"a.rs","summary":" Summary ","hunks":[{"index":1,"note":" Valid "},{"index":1,"note":"duplicate"},{"index":99,"note":"outside"}]},{"path":"a.rs","summary":"duplicate"},{"path":"unknown","summary":"wrong"}]});
        let result = grouped_result(
            &diff,
            &raw,
            &json!({"locale":"fr","model":{"engine":"pi","model":"fallback"}}),
            &json!({"model":"actual"}),
            true,
        )
        .unwrap();
        assert_eq!(result["groups"][0]["filePaths"], json!(["a.rs", "b.rs"]));
        assert_eq!(
            result["files"],
            json!([{"path":"a.rs","summary":"Summary","hunks":[{"index":1,"note":"Valid"}]}])
        );
        assert_eq!(result["model"], "pi/actual");
        assert_eq!(result["locale"], "fr");
        let incomplete = json!({"overallSummary":"Missing.","groups":[{"key":"a","label":"A","category":"core","filePaths":["a.rs","ghost"]}]});
        assert!(grouped_result(&diff, &incomplete, &json!({}), &json!({}), true).is_err());
        let reconciled = grouped_result(&diff, &incomplete, &json!({}), &json!({}), false).unwrap();
        assert_eq!(reconciled["groups"][0]["filePaths"], json!(["a.rs"]));
    }
    #[test]
    fn grouping_borrowed_diff_arrays_preserve_note_reconciliation() {
        let diff = json!({"files":[{"path":"a.rs","hunks":[{"patch":"+large patch\n".repeat(256)},{"patch":"+second"},{"patch":"+third"}]},{"path":"b.rs","hunks":[]},{"path":"c.rs"}]});
        let before = diff.clone();
        let raw = json!({"overallSummary":"Together.","groups":[{"key":"all","label":"All","category":"core","filePaths":["a.rs","b.rs","c.rs"]}],"files":[
            {"path":"a.rs","summary":" A ","hunks":[{"index":2,"note":" Third "},{"index":0,"note":" First "},{"index":2,"note":"duplicate"},{"index":3,"note":"out of bounds"},{"index":1,"note":" "}]},
            {"path":"b.rs","summary":" B ","hunks":[{"index":0,"note":"no hunks"}]},
            {"path":"c.rs","summary":" C ","hunks":[{"index":0,"note":"missing hunks"}]},
            {"path":"a.rs","summary":"duplicate"},{"path":"ghost.rs","summary":"unknown"}
        ]});
        let result = grouped_result(&diff, &raw, &json!({}), &json!({}), true).unwrap();
        assert_eq!(
            result["groups"][0]["filePaths"],
            json!(["a.rs", "b.rs", "c.rs"])
        );
        assert_eq!(
            result["files"],
            json!([
                {"path":"a.rs","summary":"A","hunks":[{"index":0,"note":"First"},{"index":2,"note":"Third"}]},
                {"path":"b.rs","summary":"B"},{"path":"c.rs","summary":"C"}
            ])
        );
        assert_eq!(diff, before);
    }
    #[test]
    fn grouping_coverage_reports_nested_duplicates_missing_and_unknown_paths() {
        let diff = json!({"files":[{"path":"c.rs"},{"path":"a.rs"},{"path":"b.rs"}]});
        let child = json!({"key":"child","label":"Child","category":"core","filePaths":"[\"a.rs\",\"ghost.rs\"]"});
        let raw = json!({"overallSummary":"Nested.","groups":[{"key":"parent","label":"Parent","category":"core","filePaths":["a.rs"],"children":serde_json::to_string(&vec![child]).unwrap()}]});
        assert_eq!(
            coverage_error(&diff, &grouping_arguments(&raw)).as_deref(),
            Some("Missing paths: b.rs, c.rs\nDuplicated paths: a.rs\nUnknown paths: ghost.rs")
        );
        assert!(grouped_result(&diff, &raw, &json!({}), &json!({}), true).is_err());
        let reconciled = grouped_result(&diff, &raw, &json!({}), &json!({}), false).unwrap();
        assert_eq!(reconciled["groups"][0]["filePaths"], json!(["a.rs"]));
        assert!(reconciled["groups"][0].get("children").is_none());
    }
    #[test]
    fn grouping_updates_persist_emit_and_reject_stale_context() {
        let dir = std::env::temp_dir().join(format!("pulls-grouping-{}", uuid::Uuid::new_v4()));
        let state = AppState::new(dir.clone(), dir.clone()).unwrap();
        let diff = json!({"ref":{"kind":"github-pr","owner":"o","repo":"r","number":1},"head":{"sha":"captured"},"files":[{"path":"a.rs","hunks":[]}]});
        state.host.storage
            .set(
                "diff:test",
                &json!({"headSha":"captured","diff":diff,"analyzedBy":{},"llmAnalyzedHeadSha":"old","lastViewedAt":0}),
            )
            .unwrap();
        let (cancel, _) = watch::channel(false);
        let run = Run {
            request: json!({"kind":"chat","key":"test","locale":"en","model":{"engine":"pi","model":"test"}}),
            snapshot: Mutex::new(json!({"id":"chat","status":"running","rev":0,"activities":[]}))
                .into(),
            publication: Default::default(),
            task: true,
            notify: Notify::new().into(),
            cancel,
        };
        let mut ctx = context(&diff);
        ctx["allowGroupingUpdate"] = json!(true);
        let args = json!({"overallSummary":"New.","groups":[{"key":"all","label":"All","category":"core","filePaths":["a.rs"]}]});
        let before = state.host.storage.get("diff:test").unwrap();
        assert!(update_grouping(&state, &run, &context(&diff), &args).is_err());
        let incomplete = json!({"overallSummary":"Missing.","groups":[]});
        assert!(update_grouping(&state, &run, &ctx, &incomplete).is_err());
        assert_eq!(state.host.storage.get("diff:test").unwrap(), before);
        assert!(array(&run.snapshot.lock().unwrap()["activities"]).is_empty());
        assert!(update_grouping(&state, &run, &ctx, &args)
            .unwrap()
            .contains("1 groups"));
        let saved = state.host.storage.get("diff:test").unwrap().unwrap();
        assert_eq!(saved["analyzedBy"]["llm"]["overallSummary"], "New.");
        assert_eq!(saved["llmAnalyzedHeadSha"], "captured");
        assert_eq!(saved["lastViewedAt"], 0);
        assert!(text(&run.snapshot.lock().unwrap()["activities"][0], "id")
            .starts_with("grouping-update:"));
        ctx["headSha"] = json!("stale");
        assert!(update_grouping(&state, &run, &ctx, &args).is_err());
        assert_eq!(state.host.storage.get("diff:test").unwrap().unwrap(), saved);
        run.cancel.send_replace(true);
        assert!(update_grouping(&state, &run, &context(&diff), &args).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
