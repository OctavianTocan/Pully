//! Review state shared by native and browser transports.
use crate::iso_ms as iso;
pub use crate::now_ms as now;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
};
mod github;
const DIFF_LIMIT: usize = 50;
const DIFF_BYTES: u64 = 50 * 1024 * 1024;

#[derive(Default)]
pub(crate) struct DiffCache {
    pins: HashMap<String, usize>,
    snapshot: Option<crate::store::DiffSnapshot>,
    #[cfg(test)]
    limits: Option<DiffCapacity>,
}
mod pins;
pub(crate) use pins::{pin_diff, pin_diff_blocking, shutdown_diff_pins};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct DiffCapacity {
    slots: usize,
    bytes: u64,
}
pub(crate) enum WarmAdmission {
    Stored(Value),
    Rejected(DiffCapacity),
}
impl DiffCapacity {
    pub(crate) fn available(self) -> bool {
        self.slots > 0 && self.bytes > 0
    }
    fn fits(self, bytes: u64) -> bool {
        self.slots > 0 && bytes <= self.bytes
    }
}
fn diff_snapshot_locked<'a>(
    state: &crate::AppState,
    cache: &'a mut DiffCache,
) -> Result<&'a crate::store::DiffSnapshot> {
    let previous = cache.snapshot.as_ref().map(|s| s.revision);
    if let Some(snapshot) = state.host.storage.diff_snapshot(previous)? {
        cache.snapshot = Some(snapshot);
    }
    Ok(cache.snapshot.as_ref().unwrap())
}
fn diff_limits(_cache: &DiffCache) -> DiffCapacity {
    #[cfg(test)]
    if let Some(limits) = _cache.limits {
        return limits;
    }
    DiffCapacity {
        slots: DIFF_LIMIT,
        bytes: DIFF_BYTES,
    }
}
fn diff_capacity_locked(state: &crate::AppState, cache: &mut DiffCache) -> Result<DiffCapacity> {
    let limits = diff_limits(cache);
    let snapshot = diff_snapshot_locked(state, cache)?;
    Ok(DiffCapacity {
        slots: limits.slots.saturating_sub(
            snapshot
                .entries
                .keys()
                .filter(|k| !k.starts_with("diff:posted:"))
                .count(),
        ),
        bytes: limits.bytes.saturating_sub(
            snapshot
                .entries
                .iter()
                .filter(|(k, _)| !k.starts_with("diff:posted:"))
                .map(|(_, m)| m.size_bytes)
                .sum(),
        ),
    })
}
pub(crate) fn diff_capacity(state: &crate::AppState) -> Result<DiffCapacity> {
    let mut cache = state.diff_cache.lock().unwrap();
    diff_capacity_locked(state, &mut cache)
}

fn text(v: &Value) -> &str {
    v.as_str().unwrap_or("")
}
fn array(v: &Value) -> Vec<Value> {
    v.as_array().cloned().unwrap_or_default()
}
fn key(args: &Value) -> Result<&str> {
    let key = text(&args["key"]);
    if key.is_empty() {
        bail!("That needs the diff key.");
    }
    Ok(key)
}
fn entry(state: &crate::AppState, key: &str) -> Result<Value> {
    let saved = state
        .host
        .storage
        .get(&format!("diff:{key}"))?
        .context("Open the diff first.")?;
    validate_posted_provenance(key, &saved)?;
    Ok(saved)
}
/// @cc [owner:OctavianTocan,label:security] rust-posted-cache-provenance-is-canonical
/// A stored posted reference MUST NOT be read or mutated through a different cache
/// key, even when that alias predates admission validation (R13 finding 1, 16 Oct 2026).
fn validate_posted_provenance(key: &str, saved: &Value) -> Result<()> {
    validate_posted_diff_key(key, &saved["diff"])
}
fn validate_posted_diff_key(key: &str, diff: &Value) -> Result<()> {
    if diff["ref"]["kind"] == "posted" {
        let canonical = crate::source::serialize_ref(&diff["ref"])?;
        anyhow::ensure!(
            key.replace(['/', '\\'], ":") == canonical,
            "Invalid posted diff cache alias."
        );
    }
    Ok(())
}
/// @cc [owner:OctavianTocan,label:architecture] rust-browser-cache-revisions-cover-complete-diffs
/// Browser get/getMeta observations and atomic putDiff comparisons MUST identify the
/// complete stored diff and head, so same-head base and metadata updates cannot be
/// replaced by an older operation (R13 finding 8, 16 Oct 2026).
fn diff_revision(entry: &Value) -> String {
    hex::encode(Sha256::digest(
        serde_json::to_vec(&json!({"diff":entry["diff"],"headSha":entry["headSha"]}))
            .expect("JSON diff serialization"),
    ))
}
fn with_revision(mut entry: Value) -> Value {
    if entry.is_object() {
        entry["revision"] = json!(diff_revision(&entry));
    }
    entry
}
fn update_entry_size(entry: &mut Value) -> Result<()> {
    // Include sizeBytes itself, including a possible change in its digit count.
    loop {
        let bytes = serde_json::to_vec(entry)?.len();
        if entry["sizeBytes"].as_u64() == Some(bytes as u64) {
            return Ok(());
        }
        entry["sizeBytes"] = json!(bytes);
    }
}
pub fn metadata(entry: &Value) -> Value {
    let diff = &entry["diff"];
    let files = diff["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let mut out = json!({"key":entry["key"],"ref":diff["ref"],"headSha":entry["headSha"],"title":diff["title"],"additions":files.iter().filter_map(|f|f["additions"].as_u64()).sum::<u64>(),"deletions":files.iter().filter_map(|f|f["deletions"].as_u64()).sum::<u64>(),"fileShas":files.iter().map(|f|f["sha"].clone()).collect::<Vec<_>>(),"lastViewedAt":entry["lastViewedAt"],"sizeBytes":entry["sizeBytes"]});
    for field in ["label", "url"] {
        if let Some(v) = diff.get(field) {
            out[field] = v.clone();
        }
    }
    if let Some(v) = diff.pointer("/pullRequest/state") {
        out["pullRequestState"] = v.clone();
    }
    for field in ["sharedComment", "changedSinceReviewed"] {
        if let Some(v) = entry.get(field) {
            out[field] = v.clone();
        }
    }
    out
}
fn valid_diff(diff: &Value) -> Result<()> {
    if !diff["ref"].is_object() || !diff["title"].is_string() || !diff["files"].is_array() {
        bail!("Invalid diff payload.");
    }
    crate::source::serialize_ref(&diff["ref"])?;
    for f in diff["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default()
    {
        if !f["path"].is_string()
            || !f["sha"].is_string()
            || !f["hunks"].is_array()
            || !f["additions"].is_number()
            || !f["deletions"].is_number()
            || !f["isBinary"].is_boolean()
            || !["added", "removed", "modified", "renamed", "copied"].contains(&text(&f["status"]))
        {
            bail!("Invalid diff file.");
        }
        for h in f["hunks"].as_array().map(Vec::as_slice).unwrap_or_default() {
            if !h["header"].is_string()
                || !h["patch"].is_string()
                || !["oldStart", "oldLines", "newStart", "newLines"]
                    .iter()
                    .all(|k| h[*k].is_number())
            {
                bail!("Invalid diff hunk.");
            }
        }
    }
    Ok(())
}
fn marked(state: &crate::AppState) -> Result<HashSet<String>> {
    Ok(state
        .host
        .storage
        .keys("review:")?
        .into_iter()
        .filter_map(|k| k.strip_prefix("review:").map(str::to_owned))
        .collect())
}
/// @cc [owner:OctavianTocan,label:architecture] rust-llm-analysis-tracks-its-head
/// Saved LLM grouping MUST retain the head it analyzed independently of later cache
/// refreshes; imported grouping MUST use its original head, including stale shares.
/// Imports without original-head provenance MUST store explicit unknown and MUST NOT
/// infer the current cache head (Rust parity review, 16 Oct 2026;
/// rust-background-does-not-mark-viewed).
pub(crate) fn set_llm_analysis(entry: &mut Value, result: Value, head: Value) {
    if !entry["analyzedBy"].is_object() {
        entry["analyzedBy"] = json!({});
    }
    entry["analyzedBy"]["llm"] = result;
    entry["llmAnalyzedHeadSha"] = head;
}
pub(crate) fn llm_analyzed_head(entry: &Value) -> Option<&str> {
    if entry["analyzedBy"]["llm"].is_null() {
        return None;
    }
    entry
        .get("llmAnalyzedHeadSha")
        .unwrap_or(&entry["headSha"])
        .as_str()
        .filter(|head| !head.is_empty())
}
pub(crate) fn next_diff(
    key: &str,
    diff: &Value,
    current: Option<Value>,
    reviewed: &HashSet<String>,
    viewed: bool,
) -> Value {
    let mut next = current.unwrap_or_else(|| json!({"analyzedBy":{},"lastViewedAt":0}));
    // Materialize legacy identity before replacing the cached head. Even an
    // unknown old head must stay unknown rather than inheriting the new head.
    if !next["analyzedBy"]["llm"].is_null() && next.get("llmAnalyzedHeadSha").is_none() {
        next["llmAnalyzedHeadSha"] = next["headSha"].clone();
    }
    let old = next["diff"]["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let flagged = next["changedSinceReviewed"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let changed: Vec<_> = diff["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default()
        .iter()
        .filter(|f| {
            flagged.contains(&f["path"])
                || old.iter().any(|p| {
                    p["path"] == f["path"]
                        && p["sha"] != f["sha"]
                        && reviewed.contains(text(&p["sha"]))
                })
        })
        .map(|f| f["path"].clone())
        .collect();
    next["key"] = json!(key);
    next["headSha"] = json!(text(&diff["head"]["sha"]));
    next["diff"] = diff.clone();
    next["changedSinceReviewed"] = json!(changed);
    if viewed {
        next["lastViewedAt"] = json!(now());
    }
    next["sizeBytes"] = json!(serde_json::to_vec(&next).map(|b| b.len()).unwrap_or(0));
    next
}
pub(crate) fn finish_warming(state: &crate::AppState, key: &str) -> Result<Option<DiffCapacity>> {
    let mut cache = state.diff_cache.lock().unwrap();
    finish_warming_locked(state, key, &mut cache)
}
fn finish_warming_locked(
    state: &crate::AppState,
    key: &str,
    cache: &mut DiffCache,
) -> Result<Option<DiffCapacity>> {
    let storage_key = format!("diff:{key}").replace(['/', '\\'], ":");
    if !diff_snapshot_locked(state, cache)?
        .entries
        .contains_key(&storage_key)
    {
        // A concurrent removal is not evidence of a capacity rejection.
        return Ok(None);
    }
    enforce_diff_budget_locked(state, cache)?;
    if !diff_snapshot_locked(state, cache)?
        .entries
        .contains_key(&storage_key)
    {
        // The complete snapshot (including overview growth) could not survive
        // this budget. Remember the settled capacity before releasing the lock.
        Ok(Some(diff_capacity_locked(state, cache)?))
    } else {
        Ok(None)
    }
}
fn enforce_diff_budget_locked(state: &crate::AppState, cache: &mut DiffCache) -> Result<()> {
    let limits = diff_limits(cache);
    let mut entries: Vec<_> = diff_snapshot_locked(state, cache)?
        .entries
        .iter()
        .map(|(key, meta)| (key.clone(), meta.clone()))
        .collect();
    // Pins are temporary exceptions to both budgets, not a replacement for one of
    // the user's viewed entries. Pin registration and eviction share this lock.
    entries.retain(|(key, _)| {
        !key.starts_with("diff:posted:")
            && !cache.pins.contains_key(key.strip_prefix("diff:").unwrap())
    });
    entries.sort_by_key(|(_, meta)| std::cmp::Reverse(meta.last_viewed_at));
    let mut bytes = 0u64;
    for (index, (key, meta)) in entries.into_iter().enumerate() {
        bytes = bytes.saturating_add(meta.size_bytes);
        if index < limits.slots && bytes <= limits.bytes {
            continue;
        }
        let Some(e) = state
            .host
            .storage
            .diff_entry_if_unchanged(&key, meta.revision)?
        else {
            // A touch, refetch or AI write since metadata selection keeps it safe.
            continue;
        };
        let scope = key.strip_prefix("diff:").unwrap();
        let meta_key = format!("pr-meta:{scope}");
        let body_key = format!("pr-body:{scope}");
        let meta = state.host.storage.get(&meta_key)?;
        let body = state.host.storage.get(&body_key)?;
        // Full-file scopes retain canonical repository slashes, unlike Store keys.
        let removed_scope = crate::source::serialize_ref(&e["diff"]["ref"])
            .ok()
            .or_else(|| {
                e["key"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
            })
            .unwrap_or_else(|| scope.to_owned());
        // An intervening touch, refetch or AI write makes this CAS fail; keep it.
        let removed = state.host.storage.compare_exchange(&[
            (key, Some(e), None),
            (meta_key, meta, None),
            (body_key, body, None),
        ]);
        if removed.is_ok() {
            crate::source::remove_file_scope(state, &removed_scope)?;
        }
    }
    Ok(())
}
pub fn put_diff(state: &crate::AppState, key: &str, diff: &Value) -> Result<Value> {
    valid_diff(diff)?;
    validate_posted_diff_key(key, diff)?;
    let mut cache = state.diff_cache.lock().unwrap();
    let marks = marked(state)?;
    let value = state.host.storage.update(&format!("diff:{key}"), |old| {
        if let Some(saved) = &old {
            validate_posted_provenance(key, saved)?;
        }
        Ok(next_diff(key, diff, old, &marks, true))
    })?;
    enforce_diff_budget_locked(state, &mut cache)?;
    Ok(value)
}
/// @cc [owner:OctavianTocan,label:architecture] rust-diff-captured-head-guard
/// Captured reads MUST NOT overwrite a newer saved head (port of server-store-answers-first, H14).
pub fn put_diff_if_unchanged(
    state: &crate::AppState,
    key: &str,
    diff: &Value,
    expected: Option<&Value>,
    viewed: bool,
) -> Result<Value> {
    let mut cache = state.diff_cache.lock().unwrap();
    let value = put_diff_if_unchanged_locked(state, key, diff, expected, viewed)?;
    enforce_diff_budget_locked(state, &mut cache)?;
    Ok(value)
}
fn put_diff_if_unchanged_locked(
    state: &crate::AppState,
    key: &str,
    diff: &Value,
    expected: Option<&Value>,
    viewed: bool,
) -> Result<Value> {
    valid_diff(diff)?;
    validate_posted_diff_key(key, diff)?;
    let marks = marked(state)?;
    let value = state.host.storage.update(&format!("diff:{key}"), |old| {
        if let Some(saved) = &old {
            validate_posted_provenance(key, saved)?;
        }
        if old.as_ref().map(|v| &v["diff"]) != expected.map(|v| &v["diff"]) {
            return old.context("The cached diff changed. Reload before saving.");
        }
        Ok(next_diff(key, diff, old, &marks, viewed))
    })?;
    Ok(value)
}
/// Cold background entries may use only spare capacity. Existing entries still
/// refresh, and all writes retain the captured-snapshot compare-and-swap guard.
pub(crate) fn put_warmed_diff_if_unchanged(
    state: &crate::AppState,
    key: &str,
    diff: &Value,
    expected: Option<&Value>,
) -> Result<WarmAdmission> {
    valid_diff(diff)?;
    validate_posted_diff_key(key, diff)?;
    let mut cache = state.diff_cache.lock().unwrap();
    let current = state.host.storage.get(&format!("diff:{key}"))?;
    if let Some(saved) = &current {
        validate_posted_provenance(key, saved)?;
    }
    if current.as_ref().map(|v| &v["diff"]) != expected.map(|v| &v["diff"]) {
        return current
            .map(WarmAdmission::Stored)
            .context("The cached diff changed. Reload before saving.");
    }
    if current.is_none() {
        let next = next_diff(key, diff, None, &marked(state)?, false);
        let capacity = diff_capacity_locked(state, &mut cache)?;
        if !capacity.fits(next["sizeBytes"].as_u64().unwrap_or(0)) {
            // Return the capacity that caused this decision while still locked.
            // A later pin release must make this marker stale, not rewrite it.
            return Ok(WarmAdmission::Rejected(capacity));
        }
    }
    put_diff_if_unchanged_locked(state, key, diff, expected, false)?;
    if let Some(capacity) = finish_warming_locked(state, key, &mut cache)? {
        return Ok(WarmAdmission::Rejected(capacity));
    }
    state
        .host
        .storage
        .get(&format!("diff:{key}"))?
        .map(WarmAdmission::Stored)
        .context("The cached diff changed. Reload before saving.")
}
pub fn message_text(content: &Value) -> String {
    if let Some(s) = content.as_str() {
        return s.into();
    }
    array(content)
        .iter()
        .map(|p| p.as_str().or_else(|| p["text"].as_str()).unwrap_or(""))
        .collect::<Vec<_>>()
        .join("")
}
fn archive_session(mut session: Value) -> Value {
    let messages = array(&session["messages"]);
    let split = (session["chatStartIndex"].as_u64().unwrap_or(0) as usize).min(messages.len());
    let chat = messages[split..].to_vec();
    session["messages"] = json!(&messages[..split]);
    let suggestions = session
        .as_object_mut()
        .and_then(|v| v.remove("suggestions"));
    if chat.iter().any(|m| m["role"] == "user") {
        let mut archived =
            json!({"id":uuid::Uuid::new_v4().to_string(),"archivedAt":now(),"messages":chat});
        if let Some(s) = suggestions {
            archived["suggestions"] = s;
        }
        let mut archive = array(&session["archive"]);
        archive.insert(0, archived);
        archive.sort_by_key(|v| std::cmp::Reverse(v["archivedAt"].as_u64().unwrap_or(0)));
        archive.truncate(30);
        session["archive"] = json!(archive);
    }
    session
}
pub fn change_chat_saved(state: &crate::AppState, key: &str, id: Option<&str>) -> Result<Value> {
    if state.host.storage.get(&format!("diff:{key}"))?.is_none() {
        if id.is_some() {
            bail!("That chat is not in this pull request's history.");
        }
        return Ok(json!({"cleared":0}));
    }
    let mut result = json!({"cleared":0});
    state.host.storage.update(&format!("diff:{key}"), |old| {
        let mut e = old.context("Open the diff first.")?;
        validate_posted_provenance(key, &e)?;
        let session = e.get("llmSession").cloned();
        if let Some(mut session) = session {
            let count = array(&session["messages"])
                .len()
                .saturating_sub(session["chatStartIndex"].as_u64().unwrap_or(0) as usize);
            if let Some(id) = id {
                let mut archive = array(&session["archive"]);
                let index = archive
                    .iter()
                    .position(|v| v["id"] == id)
                    .context("That chat is not in this pull request's history.")?;
                let chosen = archive.remove(index);
                session["archive"] = json!(archive);
                session = archive_session(session);
                let mut messages = array(&session["messages"]);
                messages.extend(array(&chosen["messages"]));
                session["messages"] = json!(messages);
                if let Some(s) = chosen.get("suggestions") {
                    session["suggestions"] = s.clone();
                }
                if session.get("archive").is_none() {
                    session["archive"] = json!([]);
                }
                result = json!({"restored":true});
            } else {
                session = archive_session(session);
                result = json!({"cleared":count});
            }
            e["llmSession"] = session;
        } else if id.is_some() {
            bail!("That chat is not in this pull request's history.");
        }
        Ok(e)
    })?;
    Ok(result)
}
fn history(session: &Value) -> Value {
    let mut archive = array(&session["archive"]);
    archive.sort_by_key(|v| std::cmp::Reverse(v["archivedAt"].as_u64().unwrap_or(0)));
    json!({"chats":archive.iter().map(|c|{let messages=array(&c["messages"]);let first=messages.iter().filter(|m|m["role"]=="user").map(|m|message_text(&m["content"])).find_map(|s|s.lines().map(str::trim).find(|l|!l.is_empty()).map(str::to_owned)).unwrap_or_default();let title=if first.chars().count()>80{format!("{}…",first.chars().take(79).collect::<String>().trim_end())}else{first};let stamp=messages.iter().rev().find_map(|m|m["timestamp"].as_u64()).unwrap_or(c["archivedAt"].as_u64().unwrap_or(0));json!({"id":c["id"],"title":title,"at":iso(stamp),"turns":messages.iter().filter(|m|m["role"]=="user").count()})}).collect::<Vec<_>>()})
}
fn latest(
    state: &crate::AppState,
    prefix: &str,
    scope: &str,
    head: &Value,
    strict: bool,
) -> Result<Option<Value>> {
    let saved = state
        .host
        .storage
        .get(&format!("{prefix}:{scope}"))?
        .unwrap_or(Value::Null);
    let entries = array(&saved["entries"]);
    Ok(entries
        .iter()
        .find(|v| {
            let r = if prefix == "critique" {
                &v["result"]
            } else {
                v
            };
            r["headSha"] == *head
        })
        .or_else(|| if strict { None } else { entries.first() })
        .cloned())
}
fn review_state(state: &Arc<crate::AppState>, args: &Value) -> Result<Value> {
    let key = key(args)?;
    let saved = state
        .host
        .storage
        .get(&format!("diff:{key}"))?
        .unwrap_or(Value::Null);
    validate_posted_provenance(key, &saved)?;
    let session = &saved["llmSession"];
    let chat: Vec<_> = array(&session["messages"])
        .into_iter()
        .skip(session["chatStartIndex"].as_u64().unwrap_or(0) as usize)
        .filter_map(|m| {
            let text = message_text(&m["content"]);
            if text.is_empty() {
                None
            } else {
                Some(json!({"role":m["role"],"text":text}))
            }
        })
        .collect();
    let threads = crate::ai::saved_threads(state, key)?;
    let llm = &saved["analyzedBy"]["llm"];
    let mut out = json!({"threads":threads,"chat":chat,"aiGroups":array(&llm["groups"]).into_iter().map(|mut g|{if g.get("category").is_none(){g["category"]=json!("other");}let category=g["category"].clone();g["children"]=json!(array(&g["children"]).into_iter().map(|mut c|{if c.get("category").is_none(){c["category"]=category.clone();}c}).collect::<Vec<_>>());g}).collect::<Vec<_>>(),"suggestions":if chat.last().map(|m|m["role"]=="assistant").unwrap_or(false){array(&session["suggestions"])}else{vec![]}});
    if llm.is_object() {
        let mut analysis = json!({"files":array(&llm["files"])});
        for f in ["overallSummary", "model", "generatedAt", "sharedBy"] {
            if let Some(v) = llm.get(f) {
                analysis[f] = v.clone();
            }
        }
        out["analysis"] = analysis;
    }
    if let Some(v) = saved.get("sharedComment") {
        out["sharedComment"] = v.clone();
    }
    if saved["diff"].is_object() {
        let scope = crate::source::serialize_ref(&saved["diff"]["ref"])?;
        let head = &saved["diff"]["head"]["sha"];
        if let Some(c) = latest(state, "critique", &scope, head, true)? {
            let mut r = c["result"].clone();
            let revision = hex::encode(Sha256::digest(serde_json::to_vec(&r)?));
            r["variant"] = c["variant"].clone();
            r["revision"] = json!(revision);
            out["critique"] = r;
        }
        if let Some(t) = latest(state, "tour", &scope, head, false)? {
            out["tour"] = t;
        }
        if let Some(b) = latest(state, "brief", &scope, head, false)? {
            if let Ok(tree) = crate::brief::parse(text(&b["lang"])) {
                let mut native = json!({"tree":tree});
                for field in [
                    "headSha",
                    "engine",
                    "model",
                    "effort",
                    "locale",
                    "generatedAt",
                ] {
                    if let Some(value) = b.get(field) {
                        native[field] = value.clone();
                    }
                }
                out["brief"] = native;
            }
        }
    }
    Ok(out)
}
fn valid_analysis(v: &Value) -> bool {
    fn group(g: &Value, children: bool) -> bool {
        g["key"].is_string()
            && g["label"].is_string()
            && g["filePaths"]
                .as_array()
                .map(|a| a.iter().all(Value::is_string))
                .unwrap_or(false)
            && g.get("summary").map(Value::is_string).unwrap_or(true)
            && g.get("children")
                .map(|c| {
                    children
                        && c.as_array()
                            .map(|a| a.iter().all(|g| group(g, false)))
                            .unwrap_or(false)
                })
                .unwrap_or(true)
    }
    v["schemaVersion"].is_number()
        && v["generatedAt"].is_string()
        && ["none", "rule-based", "llm", "web-llm"].contains(&text(&v["source"]))
        && v["groups"]
            .as_array()
            .map(|a| a.iter().all(|g| group(g, true)))
            .unwrap_or(false)
}
/// @cc [owner:OctavianTocan,label:architecture] rust-browser-cache-mutations-account-bytes
/// Browser body mutations MUST update serialized byte accounting before publishing
/// cache metadata and enforce the shared budget under its coordinator, preserving
/// posted exclusions and active pins (R13 finding 3, 16 Oct 2026).
/**
 * @cc [owner:OctavianTocan,label:architecture;security] rust-browser-cache-validates-saved-entries
 * Browser cache reads and mutation replies MUST validate canonical entry shapes.
 * Invalid saved entries MUST behave as absent for reads and replacement revisions,
 * but posted provenance MUST be checked before treating an entry as invalid or absent
 * (R14 finding 4, 16 Oct 2026).
 */
fn cache_diff(state: &crate::AppState, args: &Value) -> Result<Value> {
    let key = key(args)?;
    let method = text(&args["method"]);
    let values = array(&args["values"]);
    let v = |i: usize| values.get(i).cloned().unwrap_or(Value::Null);
    match method {
        "get" | "getMeta" => {
            let saved = state.host.storage.get(&format!("diff:{key}"))?;
            let Some(saved) = saved else {
                return Ok(Value::Null);
            };
            validate_posted_provenance(key, &saved)?;
            let Ok(mut parsed) = crate::rpc_validation::cache_entry(&saved) else {
                return Ok(Value::Null);
            };
            let revision = json!(diff_revision(&saved));
            if method == "getMeta" {
                let mut meta = metadata(&parsed);
                meta["revision"] = revision;
                return crate::rpc_validation::cache_meta(&meta);
            }
            parsed["revision"] = revision;
            return Ok(parsed);
        }
        "putDiff" => {
            let diff = v(0);
            valid_diff(&diff)?;
            let diff = crate::rpc_validation::result("source-fetch", diff)?;
            anyhow::ensure!(
                diff["ref"]["kind"] != "posted",
                "Posted diffs open only in the Pully app."
            );
            let canonical = crate::source::serialize_ref(&diff["ref"])?;
            anyhow::ensure!(
                key.replace(['/', '\\'], ":") == canonical.replace(['/', '\\'], ":"),
                "The diff reference must match its cache key."
            );
            let head = v(1);
            if !head.is_string() || !(v(2).is_null() || v(2).is_string()) {
                bail!("Invalid diff write.");
            }
            let mut cache = state.diff_cache.lock().unwrap();
            let marks = marked(state)?;
            let result = state.host.storage.update(&format!("diff:{key}"), |old| {
                if let Some(saved) = &old {
                    validate_posted_provenance(key, saved)?;
                }
                // A corrupt/previous-shape body was a miss for the browser. Its
                // refill must observe the same absence at the atomic CAS boundary.
                let old = old.filter(|saved| crate::rpc_validation::cache_entry(saved).is_ok());
                let current = old
                    .as_ref()
                    .map(|e| json!(diff_revision(e)))
                    .unwrap_or(Value::Null);
                if current != v(2) {
                    return old.context("The cached diff changed. Reload before saving.");
                }
                let mut next = next_diff(key, &diff, old, &marks, v(3)["viewed"] != false);
                next["headSha"] = head;
                update_entry_size(&mut next)?;
                crate::rpc_validation::cache_entry(&next)?;
                Ok(next)
            })?;
            enforce_diff_budget_locked(state, &mut cache)?;
            return crate::rpc_validation::cache_entry(&with_revision(result));
        }
        _ => {}
    }
    let mut cache = state.diff_cache.lock().unwrap();
    let Some(saved) = state.host.storage.get(&format!("diff:{key}"))? else {
        return Ok(Value::Null);
    };
    validate_posted_provenance(key, &saved)?;
    if crate::rpc_validation::cache_entry(&saved).is_err() {
        return Ok(Value::Null);
    }
    state.host.storage.update(&format!("diff:{key}"), |old| {
        let mut e = old.context("Open the diff first.")?;
        validate_posted_provenance(key, &e)?;
        match method {
            "touch" => e["lastViewedAt"] = json!(now()),
            "setChangedSinceReviewed" => {
                if !v(0)
                    .as_array()
                    .map(|a| a.iter().all(Value::is_string))
                    .unwrap_or(false)
                {
                    bail!("Invalid reviewed paths.");
                }
                e["changedSinceReviewed"] = v(0);
            }
            "setAnalyzedResult" => {
                let source = v(0);
                if !["llm", "web-llm"].contains(&text(&source)) || !valid_analysis(&v(1)) {
                    bail!("Invalid analysis result.");
                }
                if source == "llm" {
                    let result = v(1);
                    // Legacy browser imports discard the shared comment's head.
                    // Unknown provenance must not mark the current head analyzed.
                    let head = if result["sharedBy"].is_string() {
                        Value::Null
                    } else {
                        e["headSha"].clone()
                    };
                    set_llm_analysis(&mut e, result, head);
                } else {
                    if !e["analyzedBy"].is_object() {
                        e["analyzedBy"] = json!({});
                    }
                    e["analyzedBy"][text(&source)] = v(1);
                }
            }
            "setLlmSession" => {
                let expected = v(1)
                    .as_str()
                    .and_then(|raw| serde_json::from_str::<Value>(raw).ok());
                let actual = crate::rpc_validation::cache_session(
                    e.get("llmSession").unwrap_or(&Value::Null),
                )?;
                // Reads project the canonical schema. Compare those values rather
                // than property order or discarded legacy fields in stored JSON.
                if expected.as_ref() != Some(&actual) {
                    bail!("The chat changed on the server. Reload before saving.");
                }
                let mut session = v(0);
                if session.is_null() {
                    e.as_object_mut().unwrap().remove("llmSession");
                } else {
                    if !session["messages"].is_array() || !session["chatStartIndex"].is_u64() {
                        bail!("Invalid chat session.");
                    }
                    if session.get("archive").is_none() {
                        if let Some(archive) = e["llmSession"].get("archive") {
                            session["archive"] = archive.clone();
                        }
                    }
                    e["llmSession"] = session;
                }
            }
            "setSharedComment" => {
                let shared = v(0);
                if !shared.is_null() && (!shared["id"].is_number() || !shared["url"].is_string()) {
                    bail!("Invalid shared comment.");
                }
                if shared.is_null() {
                    e.as_object_mut().unwrap().remove("sharedComment");
                } else {
                    e["sharedComment"] = shared;
                }
            }
            "setReviewData" => {
                if !v(0)["threads"].is_array() || !v(0)["summaries"].is_array() {
                    bail!("Invalid review data.");
                }
                e["reviews"] = v(0);
            }
            _ => bail!("Unknown diff cache operation."),
        }
        update_entry_size(&mut e)?;
        crate::rpc_validation::cache_entry(&e)?;
        Ok(e)
    })?;
    enforce_diff_budget_locked(state, &mut cache)?;
    Ok(Value::Null)
}
async fn file_content(state: &Arc<crate::AppState>, args: &Value) -> Result<Value> {
    let key = key(args)?;
    let path = text(&args["path"]);
    if path.is_empty() {
        bail!("Loading a full file needs the diff key and a path.");
    }
    let owned = state.clone();
    let owned_key = key.to_owned();
    let e = crate::store::blocking(move || entry(&owned, &owned_key)).await?;
    let diff = &e["diff"];
    if diff["ref"]["kind"] == "posted" || diff["ref"]["kind"] == "paste" {
        bail!("This diff has no full files, only its patch.");
    }
    let file = diff["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default()
        .iter()
        .find(|f| f["path"] == path);
    let Some(file) = file else {
        if !diff["head"]["sha"].is_string() || path.split('/').any(|p| p == "..") {
            bail!("This diff has no full file for that path.");
        }
        let content = crate::source::load_file(
            state,
            &json!({"ref":diff["ref"],"path":path,"sha":diff["head"]["sha"]}),
        )
        .await?;
        if content.is_null() {
            bail!("That file is not in the repository at this commit.");
        }
        return Ok(json!({"old":null,"new":content}));
    };
    if !diff["base"]["sha"].is_string() || !diff["head"]["sha"].is_string() {
        bail!("This diff has no full file for that path.");
    }
    let old = if file["status"] == "added" {
        Value::Null
    } else {
        crate::source::load_file(state,&json!({"ref":diff["ref"],"path":file.get("previousPath").unwrap_or(&file["path"]),"sha":diff["base"]["sha"]})).await?
    };
    let new = if file["status"] == "removed" {
        Value::Null
    } else {
        crate::source::load_file(
            state,
            &json!({"ref":diff["ref"],"path":path,"sha":diff["head"]["sha"]}),
        )
        .await?
    };
    Ok(json!({"old":old,"new":new}))
}
/// @cc [owner:OctavianTocan,label:security] rust-patches-match-requested-head
/// Patch reads naming a head MUST reject a different stored head rather than mixing
/// snapshots (Rust integration review, 15 October 2026; server-store-answers-first).
fn diff_patches(state: &crate::AppState, args: &Value) -> Result<Value> {
    let key = key(args)?;
    let paths = args["paths"]
        .as_array()
        .context("Loading patches needs the diff key and the paths.")?;
    if paths.len() > 100 {
        bail!("Ask for at most 100 paths at a time.");
    }
    let entry = entry(state, key)?;
    if let Some(expected) = args.get("headSha") {
        let expected = expected
            .as_str()
            .context("A patch snapshot needs a head sha.")?;
        if expected != text(&entry["diff"]["head"]["sha"]) {
            bail!("This diff changed. Reopen it before loading patches.");
        }
    }
    let files = entry["diff"]["files"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let mut found = serde_json::Map::new();
    for path in paths.iter().filter_map(Value::as_str) {
        if let Some(file) = files.iter().find(|file| file["path"] == path) {
            found.insert(
                path.into(),
                json!({"hunks":file["hunks"],"sha":file["sha"]}),
            );
        }
    }
    Ok(json!({"files":found}))
}
pub async fn handle(
    state: &Arc<crate::AppState>,
    name: &str,
    args: Value,
) -> Option<Result<Value>> {
    if let Err(error) = state.identity.ensure_current() {
        return Some(Err(error));
    }
    handle_inner(state, name, args).await.map(|result| {
        state.identity.ensure_current()?;
        result
    })
}

async fn handle_inner(
    state: &Arc<crate::AppState>,
    name: &str,
    args: Value,
) -> Option<Result<Value>> {
    if let Some(result) = github::handle(state, name, &args).await {
        return Some(result);
    }
    if ![
        "cache-diff",
        "reviewed-get",
        "reviewed-set",
        "diff-patches",
        "diff-file",
        "file-content",
        "review-state",
        "chat-clear",
        "chat-restore",
        "chat-history",
        "jev-files",
        "recent-diffs",
        "repo-viewed",
    ]
    .contains(&name)
    {
        return None;
    }
    let result = match name {
        "file-content" | "diff-file" => file_content(state, &args).await,
        "chat-clear" => async { crate::ai::change_chat(state, key(&args)?, None).await }.await,
        "chat-restore" => {
            async {
                let id = text(&args["id"]);
                if id.is_empty() {
                    bail!("That needs the diff key and the chat.");
                }
                crate::ai::change_chat(state, key(&args)?, Some(id)).await
            }
            .await
        }
        _ => {
            let state = state.clone();
            let name = name.to_owned();
            crate::store::blocking(move || handle_storage(&state, &name, args)).await
        }
    };
    Some(result)
}
fn handle_storage(state: &Arc<crate::AppState>, name: &str, args: Value) -> Result<Value> {
    match name {
        "cache-diff" => cache_diff(state, &args),
        "review-state" => review_state(state, &args),
        "chat-history" => {
            let e = state
                .host
                .storage
                .get(&format!("diff:{}", key(&args)?))?
                .unwrap_or(Value::Null);
            validate_posted_provenance(key(&args)?, &e)?;
            Ok(history(&e["llmSession"]))
        }
        "reviewed-get" => {
            let key = text(&args["key"]);
            let e = if key.is_empty() {
                Value::Null
            } else {
                state
                    .host
                    .storage
                    .get(&format!("diff:{key}"))?
                    .unwrap_or(Value::Null)
            };
            validate_posted_provenance(key, &e)?;
            let shas = if args["shas"].is_array() {
                array(&args["shas"])
            } else {
                e["diff"]["files"]
                    .as_array()
                    .map(Vec::as_slice)
                    .unwrap_or_default()
                    .iter()
                    .map(|f| f["sha"].clone())
                    .collect()
            };
            let mut reviewed = Vec::new();
            for sha in shas {
                if let Some(sha) = sha.as_str() {
                    if let Some(mark) = state.host.storage.get(&format!("review:{sha}"))? {
                        if mark["sha"] == sha && mark["reviewedAt"].is_number() {
                            if !reviewed.contains(&sha.to_string()) {
                                reviewed.push(sha.to_string());
                            }
                        }
                    }
                }
            }
            Ok(
                json!({"reviewed":reviewed,"changedSinceReviewed":array(&e["changedSinceReviewed"])}),
            )
        }
        "reviewed-set" => {
            let key = text(&args["key"]);
            if let Some(saved) = state.host.storage.get(&format!("diff:{key}"))? {
                validate_posted_provenance(key, &saved)?;
            }
            let shas: HashSet<_> = array(&args["shas"])
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect();
            if shas.is_empty() {
                bail!("Name at least one file sha.");
            }
            for sha in &shas {
                if args["reviewed"] == true {
                    state.host.storage.set(
                        &format!("review:{sha}"),
                        &json!({"sha":sha,"reviewedAt":now()}),
                    )?;
                } else {
                    state.host.storage.remove(&format!("review:{sha}"))?;
                }
            }
            let key = text(&args["key"]);
            if !key.is_empty() && state.host.storage.get(&format!("diff:{key}"))?.is_some() {
                state.host.storage.update(&format!("diff:{key}"), |old| {
                    let mut e = old.context("Open the diff first.")?;
                    validate_posted_provenance(key, &e)?;
                    let touched: HashSet<_> = e["diff"]["files"]
                        .as_array()
                        .map(Vec::as_slice)
                        .unwrap_or_default()
                        .iter()
                        .filter(|f| shas.contains(text(&f["sha"])))
                        .map(|f| text(&f["path"]).to_owned())
                        .collect();
                    e["changedSinceReviewed"] = json!(array(&e["changedSinceReviewed"])
                        .into_iter()
                        .filter(|p| !touched.contains(text(p)))
                        .collect::<Vec<_>>());
                    Ok(e)
                })?;
            }
            Ok(json!(true))
        }
        "diff-patches" => diff_patches(state, &args),
        "jev-files" => {
            let e = entry(state, key(&args)?)?;
            Ok(crate::jev::known_files(state, &e["diff"]))
        }
        "recent-diffs" | "repo-viewed" => {
            let mut selected = {
                let mut cache = state.diff_cache.lock().unwrap_or_else(|e| e.into_inner());
                diff_snapshot_locked(state, &mut cache)?
                    .entries
                    .iter()
                    .filter(|(_, meta)| {
                        meta.last_viewed_at > 0
                            && (name == "recent-diffs"
                                || meta.repository.as_ref().is_some_and(|(owner, repo)| {
                                    owner == text(&args["owner"]) && repo == text(&args["repo"])
                                }))
                    })
                    .map(|(key, meta)| (key.clone(), meta.last_viewed_at))
                    .collect::<Vec<_>>()
            };
            selected.sort_by_key(|(_, at)| std::cmp::Reverse(*at));
            if name == "recent-diffs" {
                selected.truncate(12);
            }
            let mut entries = Vec::with_capacity(selected.len());
            for (key, _) in selected {
                if let Some(entry) = state.host.storage.get(&key)? {
                    if validate_posted_provenance(key.strip_prefix("diff:").unwrap_or(&key), &entry)
                        .is_ok()
                    {
                        entries.push(entry);
                    }
                }
            }
            if name == "recent-diffs" {
                entries.truncate(12);
                Ok(json!(entries.iter().map(|e|{let m=metadata(e);json!({"key":m["key"],"ref":m["ref"],"title":m["title"],"label":m["label"],"url":m["url"],"state":m["pullRequestState"],"additions":m["additions"],"deletions":m["deletions"],"lastViewedAt":m["lastViewedAt"]})}).collect::<Vec<_>>()))
            } else {
                if !args["owner"].is_string() || !args["repo"].is_string() {
                    bail!("That needs an owner and a repo.");
                }
                Ok(json!(entries.iter().filter(|e|e["diff"]["ref"]["kind"]=="github-pr"&&e["diff"]["ref"]["owner"]==args["owner"]&&e["diff"]["ref"]["repo"]==args["repo"]).map(|e|{let m=metadata(e);let ai=e["analyzedBy"].get("llm").or_else(||e["analyzedBy"].get("web-llm"));json!({"number":text(&e["diff"]["ref"]["number"]).parse::<u64>().unwrap_or(0),"additions":m["additions"],"deletions":m["deletions"],"files":e["diff"]["files"].as_array().map_or(0, Vec::len),"groups":ai.map(|a|array(&a["groups"]).len()).unwrap_or_else(||array(&crate::source::groups(&e["diff"])).len()),"hasAiResult":ai.is_some()})}).collect::<Vec<_>>()))
            }
        }
        _ => unreachable!(),
    }
}
#[cfg(test)]
mod tests;
