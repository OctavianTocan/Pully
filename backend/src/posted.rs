//! Account-owned, compressed agent diffs. Storage is compatible with the original flat-key layout.
use crate::{source::parse_patch_untruncated, store::Store};
use anyhow::{Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use regex::Regex;
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    io::{Read, Write},
    sync::{Mutex, OnceLock},
};
mod async_store;
mod fields;
pub use fields::{check_agent_fields, keep_in_diff};
pub const MAX_PATCH_BYTES: usize = 5 * 1024 * 1024;
pub const MAX_POST_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_FILES: usize = 2000;
const CHUNK_CHARS: usize = 1_500_000;
static WRITES: Mutex<()> = Mutex::new(());
#[derive(Debug)]
pub struct PostError {
    pub status: u16,
    pub message: String,
}
impl std::fmt::Display for PostError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for PostError {}
fn refuse<T>(status: u16, message: impl Into<String>) -> Result<T> {
    Err(PostError {
        status,
        message: message.into(),
    }
    .into())
}
fn text<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or("")
}
fn bounded(v: &Value, limit: usize) -> Option<String> {
    v.as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| String::from_utf16_lossy(&s.encode_utf16().take(limit).collect::<Vec<_>>()))
}
pub fn valid_id(id: &str) -> bool {
    (6..=32).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
}
/// The version stamp also changes on metadata-only updates, without inventing a Git head.
pub fn posted_revision(meta: &Value) -> String {
    let s = text(meta, "updatedAt");
    // Saved timestamps are ISO UTC; parse without adding a general-purpose date runtime.
    let ms = (|| {
        let y: i64 = s.get(..4)?.parse().ok()?;
        let m: i64 = s.get(5..7)?.parse().ok()?;
        let d: i64 = s.get(8..10)?.parse().ok()?;
        let y = y - if m <= 2 { 1 } else { 0 };
        let era = y.div_euclid(400);
        let yo = y - era * 400;
        let mp = m + if m > 2 { -3 } else { 9 };
        let days =
            era * 146097 + yo * 365 + yo / 4 - yo / 100 + (153 * mp + 2) / 5 + d - 1 - 719468;
        let h: i64 = s.get(11..13)?.parse().ok()?;
        let min: i64 = s.get(14..16)?.parse().ok()?;
        let sec: i64 = s.get(17..19)?.parse().ok()?;
        let sub: i64 = s.get(20..23)?.parse().ok()?;
        Some(days * 86400000 + h * 3600000 + min * 60000 + sec * 1000 + sub)
    })()
    .unwrap_or(0);
    format!("v{}.{ms}", meta["version"].as_u64().unwrap_or(0))
}
pub fn posted_payload(meta: &Value, patch: &str) -> Value {
    let mut out = json!({"ref":{"kind":"posted","id":meta["id"]},"title":meta["title"],"createdAt":meta["createdAt"],"updatedAt":meta["updatedAt"],"files":parse_patch_untruncated(patch)});
    if let Some(d) = meta.get("description") {
        out["description"] = d.clone();
    }
    let author = meta
        .get("author")
        .and_then(Value::as_str)
        .unwrap_or(text(meta, "keyName"));
    if !author.is_empty() {
        out["author"] = json!({"name":author});
    }
    out
}
pub fn find_secret(diff: &Value) -> Option<(String, u64, &'static str)> {
    static PATTERNS: OnceLock<Vec<(&str, Regex)>> = OnceLock::new();
    let patterns = PATTERNS.get_or_init(|| {
        [
            (
                "a GitHub token",
                r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_\w{40,})",
            ),
            ("an AWS access key", r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
            ("a Stripe key", r"\b[rs]k_live_[0-9A-Za-z]{20,}"),
            ("an Anthropic key", r"\bsk-ant-[\w-]{20,}"),
            ("an OpenAI key", r"\bsk-[\w-]{32,}"),
            ("a Slack token", r"\bxox[abprs]-[0-9A-Za-z-]{10,}"),
            ("a Google API key", r"\bAIza[\w-]{35}\b"),
            ("a private key", r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
            ("a Pully key", r"\bpully_[0-9A-Za-z]{32}\b"),
        ]
        .into_iter()
        .map(|(n, p)| (n, Regex::new(p).unwrap()))
        .collect()
    });
    for f in diff["files"].as_array().into_iter().flatten() {
        let path = text(f, "path");
        let base = path.rsplit('/').next().unwrap_or(path);
        let env = base == ".env"
            || base.strip_prefix(".env.").is_some_and(|s| {
                !s.is_empty()
                    && !["example", "sample", "template", "dist"]
                        .iter()
                        .any(|p| s.starts_with(p))
                    && s.chars()
                        .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
            });
        for h in f["hunks"].as_array().into_iter().flatten() {
            let mut line = h["newStart"].as_u64().unwrap_or(0);
            for row in text(h, "patch").split('\n') {
                if row.starts_with('+') && !row.starts_with("+++") {
                    let added = &row[1..];
                    let what = if env && !added.trim().is_empty() && !added.trim().starts_with('#')
                    {
                        Some("an environment file")
                    } else {
                        patterns
                            .iter()
                            .find(|(_, re)| re.is_match(added))
                            .map(|(n, _)| *n)
                    };
                    if let Some(what) = what {
                        return Some((path.into(), line, what));
                    }
                }
                if !row.starts_with('-') && !row.starts_with('\\') {
                    line += 1;
                }
            }
        }
    }
    None
}
#[derive(Clone)]
pub struct PostedStore {
    store: Store,
}
impl PostedStore {
    pub fn new(store: Store) -> Self {
        Self { store }
    }
    pub fn meta(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        if !valid_id(id) {
            return Ok(None);
        }
        Ok(self
            .store
            .get(&format!("posted-diff:{id}"))?
            .filter(|m| m["accountId"] == account_id))
    }
    pub fn list(&self, account_id: &str) -> Result<Vec<Value>> {
        let mut out = vec![];
        for key in self.store.keys("posted-diff:")? {
            if let Some(meta) = self
                .store
                .get(&key)?
                .filter(|m| m["accountId"] == account_id)
            {
                out.push(meta);
            }
        }
        out.sort_by(|a, b| text(b, "updatedAt").cmp(text(a, "updatedAt")));
        Ok(out)
    }
    fn read_patch(&self, meta: &Value) -> Result<String> {
        let mut packed = String::new();
        for at in 0..meta["chunks"].as_u64().unwrap_or(0) {
            let part = self
                .store
                .get(&format!(
                    "posted-patch:{}:{}:{at}",
                    text(meta, "id"),
                    meta["version"]
                ))?
                .context("Stored patch is incomplete")?;
            packed.push_str(part.as_str().context("Stored patch is invalid")?);
        }
        let bytes = STANDARD.decode(packed)?;
        let mut out = String::new();
        GzDecoder::new(bytes.as_slice())
            .take(MAX_PATCH_BYTES as u64 + 1)
            .read_to_string(&mut out)?;
        anyhow::ensure!(
            out.len() <= MAX_PATCH_BYTES,
            "Stored patch exceeds its limit"
        );
        Ok(out)
    }
    pub fn read(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let _guard = WRITES
            .lock()
            .map_err(|_| anyhow::anyhow!("Posted storage unavailable"))?;
        self.read_inner(account_id, id)
    }
    fn read_inner(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let Some(meta) = self.meta(account_id, id)? else {
            return Ok(None);
        };
        Ok(Some(
            json!({"patch":self.read_patch(&meta)?,"meta":meta,"agent":self.store.get(&format!("posted-agent:{id}"))?.unwrap_or(json!({}))}),
        ))
    }
    pub fn save(
        &self,
        account_id: &str,
        key_name: &str,
        id: Option<&str>,
        patch: Option<&str>,
        fields: &Value,
    ) -> Result<Value> {
        let _guard = WRITES
            .lock()
            .map_err(|_| anyhow::anyhow!("Posted storage unavailable"))?;
        let previous = match id {
            Some(id) => self.read_inner(account_id, id)?,
            None => None,
        };
        if id.is_some() && previous.is_none() {
            return refuse(404, "There is no posted diff with that id.");
        }
        if patch.is_none() && previous.is_none() {
            return refuse(
                400,
                "A post needs a patch: the body as text/x-diff, or `patch` in JSON.",
            );
        }
        if patch.is_some_and(|p| p.len() > MAX_PATCH_BYTES) {
            return refuse(413, "A patch can be at most 5 MB.");
        }
        let old = previous.as_ref().map(|p| &p["meta"]);
        let old_patch = previous.as_ref().map(|p| text(p, "patch"));
        let changed = patch.is_some() && patch != old_patch;
        let patch = patch.or(old_patch).unwrap();
        let now = crate::now_ms().max(old.and_then(|m| m["updatedMs"].as_u64()).unwrap_or(0) + 1);
        let stamp = crate::iso_ms(now);
        let id = match id {
            Some(id) => id.to_owned(),
            None => crate::account::random_id(12)?,
        };
        let mut meta=old.cloned().unwrap_or_else(||json!({"id":id,"accountId":account_id,"keyName":key_name,"title":"Posted diff","version":0,"createdAt":stamp,"chunks":0}));
        for (field, limit) in [("title", 200), ("description", 20000), ("author", 80)] {
            if let Some(v) = bounded(&fields[field], limit) {
                meta[field] = json!(v);
            }
        }
        meta["updatedAt"] = json!(stamp);
        meta["updatedMs"] = json!(now);
        if changed {
            meta["version"] = json!(meta["version"].as_u64().unwrap_or(0) + 1);
        }
        let diff = posted_payload(&meta, patch);
        let files = diff["files"].as_array().unwrap();
        if files.is_empty()
            || files.iter().any(|f| {
                text(f, "path").is_empty()
                    || (f["hunks"].as_array().is_none_or(Vec::is_empty)
                        && f["isBinary"] != true
                        && f["status"] == "modified"
                        && f.get("previousPath").is_none())
            })
        {
            return refuse(
                422,
                "That patch has no files in it. Send unified diff text, such as `git diff` prints.",
            );
        }
        if files.len() > MAX_FILES {
            return refuse(
                413,
                format!("A patch can change at most {MAX_FILES} files."),
            );
        }
        if changed && fields["allowSecrets"] != true {
            if let Some((path, line, what)) = find_secret(&diff) {
                return refuse(422,format!("{path} line {line} looks like {what}. Take it out, or send allowSecrets: true if it is not a secret."));
            }
        }
        let kept = previous
            .as_ref()
            .map(|p| p["agent"].clone())
            .unwrap_or(json!({}));
        let (agent, warnings) = check_agent_fields(
            fields,
            &diff,
            if changed {
                keep_in_diff(kept, &diff)
            } else {
                kept
            },
        );
        meta["files"] = json!(files.len());
        for field in ["additions", "deletions"] {
            meta[field] = json!(files.iter().filter_map(|f| f[field].as_u64()).sum::<u64>());
        }
        if changed {
            let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
            gzip.write_all(patch.as_bytes())?;
            let packed = STANDARD.encode(gzip.finish()?);
            let chunks = packed.as_bytes().chunks(CHUNK_CHARS);
            meta["chunks"] = json!(chunks.len());
            for (at, part) in chunks.enumerate() {
                self.store.set(
                    &format!("posted-patch:{id}:{}:{at}", meta["version"]),
                    &json!(std::str::from_utf8(part)?),
                )?;
            }
        }
        self.store.set(&format!("posted-agent:{id}"), &agent)?;
        self.store.set(&format!("posted-diff:{id}"), &meta)?;
        if changed {
            if let Some(old) = old {
                for at in 0..old["chunks"].as_u64().unwrap_or(0) {
                    self.store
                        .remove(&format!("posted-patch:{id}:{}:{at}", old["version"]))?;
                }
            }
        }
        self.materialize(&meta, patch, &agent, false)?;
        Ok(json!({"meta":meta,"warnings":warnings}))
    }
    fn materialize(&self, meta: &Value, patch: &str, agent: &Value, viewed: bool) -> Result<Value> {
        let diff = posted_payload(meta, patch);
        let key = format!("posted:{}", text(meta, "id"));
        let revision = posted_revision(meta);
        let by = meta.get("author").unwrap_or(&meta["keyName"]);
        let reviewed: HashSet<_> = self
            .store
            .keys("review:")?
            .into_iter()
            .filter_map(|key| key.strip_prefix("review:").map(str::to_owned))
            .collect();
        self.store.update(&format!("diff:{key}"), |old| {
            let mut e = crate::review::next_diff(&key, &diff, old, &reviewed, viewed);
            e["headSha"] = json!(revision);
            if let Some(g) = agent.get("groups") {
                let mut g = g.clone();
                g["source"] = json!("llm");
                g["generatedAt"] = meta["updatedAt"].clone();
                g["model"] = by.clone();
                g["sharedBy"] = by.clone();
                crate::review::set_llm_analysis(&mut e, g, json!(revision));
            }
            e["sizeBytes"] = json!(serde_json::to_vec(&e)?.len());
            Ok(e)
        })?;
        for (field, prefix) in [("tour", "tour"), ("brief", "brief"), ("review", "critique")] {
            if let Some(v) = agent.get(field) {
                let mut v = v.clone();
                v["engine"] = json!("agent");
                v["model"] = by.clone();
                v["generatedAt"] = meta["updatedAt"].clone();
                self.store.update(&format!("{prefix}:{key}"), |old| {
                    let mut entries = old
                        .as_ref()
                        .and_then(|o| o["entries"].as_array())
                        .cloned()
                        .unwrap_or_default();
                    if prefix == "critique" {
                        entries.retain(|e| e["variant"] != "agent");
                        entries.insert(0, json!({"variant":"agent","result":v}));
                        entries.truncate(8);
                    } else {
                        entries.retain(|e| {
                            !(e["headSha"] == v["headSha"] && e["locale"] == v["locale"])
                        });
                        entries.insert(0, v);
                        entries.truncate(4);
                    }
                    Ok(json!({"entries":entries}))
                })?;
            }
        }
        Ok(json!({"key":key,"diff":diff}))
    }
    pub fn open(&self, account_id: &str, id: &str) -> Result<Option<Value>> {
        let _guard = WRITES
            .lock()
            .map_err(|_| anyhow::anyhow!("Posted storage unavailable"))?;
        let Some(found) = self.read_inner(account_id, id)? else {
            return Ok(None);
        };
        let key = format!("posted:{id}");
        let cached = self.store.get(&format!("diff:{key}"))?;
        if cached.as_ref().is_some_and(|e| {
            e["headSha"] == posted_revision(&found["meta"]) && e["diff"].is_object()
        }) {
            let cached = self.store.update(&format!("diff:{key}"), |e| {
                let mut e = e.unwrap();
                e["lastViewedAt"] = json!(crate::now_ms());
                Ok(e)
            })?;
            return Ok(Some(json!({"key":key,"diff":cached["diff"]})));
        }
        Ok(Some(self.materialize(
            &found["meta"],
            text(&found, "patch"),
            &found["agent"],
            true,
        )?))
    }
    pub fn remove(&self, account_id: &str, id: &str) -> Result<bool> {
        let _guard = WRITES
            .lock()
            .map_err(|_| anyhow::anyhow!("Posted storage unavailable"))?;
        let Some(meta) = self.meta(account_id, id)? else {
            return Ok(false);
        };
        for at in 0..meta["chunks"].as_u64().unwrap_or(0) {
            self.store
                .remove(&format!("posted-patch:{id}:{}:{at}", meta["version"]))?;
        }
        for prefix in [
            "tour",
            "brief",
            "critique",
            "ask-threads",
            "diff",
            "pr-meta",
            "pr-body",
        ] {
            self.store.remove(&format!("{prefix}:posted:{id}"))?;
        }
        self.store.remove(&format!("posted-agent:{id}"))?;
        self.store.remove(&format!("posted-diff:{id}"))?;
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const PATCH: &str = "diff --git a/src/retry.ts b/src/retry.ts\nindex e69de29..4b825dc 100644\n--- a/src/retry.ts\n+++ b/src/retry.ts\n@@ -0,0 +1,3 @@\n+export function retry() {\n+  return 3\n+}\ndiff --git a/README.md b/README.md\nindex 1111111..2222222 100644\n--- a/README.md\n+++ b/README.md\n@@ -1 +1,2 @@\n # App\n+Retries GitHub calls.\n";
    fn store() -> PostedStore {
        PostedStore::new(
            Store::new(std::env::temp_dir().join(format!("posted-tests-{}", uuid::Uuid::new_v4())))
                .unwrap(),
        )
    }
    #[test]
    fn saves_opens_updates_and_removes_account_owned_compressed_diffs() {
        let s = store();
        let saved=s.save("owner","Laptop",None,Some(PATCH),&json!({"title":"Retry","groups":{"overallSummary":"Retries calls","groups":[{"key":"retry","label":"Retry","category":"core","filePaths":["src/retry.ts","gone.ts"]}]},"tour":{"title":"Tour","stops":[{"title":"Readme","files":["README.md"]},{"title":"Helper","files":["src/retry.ts"]}]},"brief":"root = Page([])","review":{"findings":[{"path":"README.md","line":2,"title":"Wording"},{"path":"gone.ts","line":1,"title":"Missing"}]}})).unwrap();
        assert_eq!(saved["warnings"].as_array().unwrap().len(), 2);
        let id = text(&saved["meta"], "id");
        assert!(valid_id(id));
        assert!(s.read("other", id).unwrap().is_none());
        assert!(s.open("other", id).unwrap().is_none());
        assert!(!s.remove("other", id).unwrap());
        let opened = s.open("owner", id).unwrap().unwrap();
        assert_eq!(opened["diff"]["author"]["name"], "Laptop");
        assert!(opened["diff"].get("head").is_none());
        assert_eq!(opened["diff"]["files"].as_array().unwrap().len(), 2);
        let read = s.read("owner", id).unwrap().unwrap();
        assert_eq!(read["patch"], PATCH);
        assert_eq!(read["agent"]["review"]["findings"][0]["id"], "agent-1");
        let old_revision = posted_revision(&saved["meta"]);
        let updated = s
            .save(
                "owner",
                "Other key",
                Some(id),
                Some(PATCH),
                &json!({"title":"Renamed"}),
            )
            .unwrap();
        assert_eq!(updated["meta"]["version"], 1);
        assert_ne!(posted_revision(&updated["meta"]), old_revision);
        assert_eq!(updated["meta"]["keyName"], "Laptop");
        let updated = s
            .save(
                "owner",
                "Laptop",
                Some(id),
                Some(PATCH.split("diff --git a/README.md").next().unwrap()),
                &json!({}),
            )
            .unwrap();
        assert_eq!(updated["meta"]["version"], 2);
        let read = s.read("owner", id).unwrap().unwrap();
        assert_eq!(read["agent"]["tour"]["stops"].as_array().unwrap().len(), 1);
        assert!(read["agent"]["review"]["findings"]
            .as_array()
            .unwrap()
            .is_empty());
        assert!(s.remove("owner", id).unwrap());
        assert!(s.store.keys("").unwrap().iter().all(|k| !k.contains(id)));
        let _ = std::fs::remove_dir_all(s.store.root());
    }
    #[test]
    fn refuses_secrets_without_echoing_them_and_keeps_valid_old_fields() {
        let s = store();
        let token = format!("ghp_{}", "a".repeat(36));
        let patch = PATCH.replace("return 3", &format!("return '{token}'"));
        let error = s
            .save("owner", "Agent", None, Some(&patch), &json!({}))
            .unwrap_err();
        assert_eq!(error.downcast_ref::<PostError>().unwrap().status, 422);
        assert!(error.to_string().contains("src/retry.ts line 2"));
        assert!(!error.to_string().contains(&token));
        let saved = s
            .save(
                "owner",
                "Agent",
                None,
                Some(&patch),
                &json!({"allowSecrets":true,"tour":{"title":"Kept","stops":[]}}),
            )
            .unwrap();
        let id = text(&saved["meta"], "id");
        let updated = s
            .save(
                "owner",
                "Agent",
                Some(id),
                None,
                &json!({"tour":{"title":42}}),
            )
            .unwrap();
        assert_eq!(updated["warnings"].as_array().unwrap().len(), 1);
        assert_eq!(
            s.read("owner", id).unwrap().unwrap()["agent"]["tour"]["title"],
            "Kept"
        );
        assert_eq!(
            s.save("owner", "Agent", None, Some("hello"), &json!({}))
                .unwrap_err()
                .downcast_ref::<PostError>()
                .unwrap()
                .status,
            422
        );
        let _ = std::fs::remove_dir_all(s.store.root());
    }
    #[test]
    fn revisions_and_secret_environment_exceptions_match_wire_behavior() {
        assert_eq!(
            posted_revision(&json!({"version":2,"updatedAt":"2026-10-16T15:00:00.000Z"})),
            "v2.1792162800000"
        );
        for (name, secret) in [
            (".env", true),
            (".env.local", true),
            (".env.example", false),
            (".env.sample", false),
        ] {
            let patch=format!("diff --git a/{name} b/{name}\n--- a/{name}\n+++ b/{name}\n@@ -0,0 +1 @@\n+PASSWORD=not-public\n");
            assert_eq!(
                find_secret(&posted_payload(&json!({}), &patch)).is_some(),
                secret
            );
        }
    }

    fn large_patch(path: &str, added: bool, last_line: &str) -> String {
        let mode = if added { "new file mode 100644\n" } else { "" };
        let padding = "x".repeat(512 * 1024);
        format!("diff --git a/{path} b/{path}\n{mode}--- a/{path}\n+++ b/{path}\n@@ -0,0 +1,2 @@\n+# {padding}\n+{last_line}\n")
    }

    #[test]
    fn complete_large_posted_patches_are_validated_and_scanned() {
        let s = store();
        let token = format!("ghp_{}", "a".repeat(36));
        for (path, secret) in [
            ("large.txt", token.as_str()),
            (".env.local", "PASSWORD=private"),
        ] {
            let patch = large_patch(path, true, secret);
            assert!(patch.len() < MAX_PATCH_BYTES);
            assert_eq!(crate::source::parse_patch(&patch)[0]["truncated"], true);
            let error = s
                .save("owner", "Agent", None, Some(&patch), &json!({}))
                .unwrap_err();
            assert_eq!(error.downcast_ref::<PostError>().unwrap().status, 422);
            assert!(error.to_string().contains(&format!("{path} line 2")));
            assert!(!error.to_string().contains(secret));
            assert!(s.list("owner").unwrap().is_empty());

            let saved = s
                .save(
                    "owner",
                    "Agent",
                    None,
                    Some(&patch),
                    &json!({"allowSecrets":true}),
                )
                .unwrap();
            let id = text(&saved["meta"], "id");
            let opened = s.open("owner", id).unwrap().unwrap();
            assert_eq!(
                opened["diff"]["files"][0]["hunks"]
                    .as_array()
                    .unwrap()
                    .len(),
                1
            );
            assert!(opened["diff"]["files"][0].get("truncated").is_none());
            assert_eq!(s.read("owner", id).unwrap().unwrap()["patch"], patch);
            s.remove("owner", id).unwrap();
        }
        // Presentation truncation must not turn an ordinary modified file into
        // the misleading "no files" rejection during posted validation.
        let patch = large_patch("ordinary.txt", false, "ordinary content");
        let saved = s
            .save("owner", "Agent", None, Some(&patch), &json!({}))
            .unwrap();
        assert_eq!(saved["meta"]["files"], 1);
        assert_eq!(saved["meta"]["additions"], 2);
        std::fs::remove_dir_all(s.store.root()).unwrap();
    }

    #[tokio::test]
    async fn posted_updates_reconcile_reviewed_shas_without_counting_a_view() {
        let s = store();
        let app = crate::AppState::new(s.store.root().to_path_buf(), s.store.root().to_path_buf())
            .unwrap();
        let saved = s
            .save("owner", "Agent", None, Some(PATCH), &json!({}))
            .unwrap();
        let id = text(&saved["meta"], "id");
        let key = format!("posted:{id}");
        let first = s.open("owner", id).unwrap().unwrap();
        let shas: Vec<_> = first["diff"]["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|file| file["sha"].clone())
            .collect();
        crate::review::handle(
            &app,
            "reviewed-set",
            json!({"key":key,"shas":shas,"reviewed":true}),
        )
        .await
        .unwrap()
        .unwrap();
        s.store
            .update(&format!("diff:{key}"), |entry| {
                let mut entry = entry.unwrap();
                entry["lastViewedAt"] = json!(42);
                Ok(entry)
            })
            .unwrap();
        let changed = PATCH
            .replace("e69de29..4b825dc", "4b825dc..abcdef0")
            .replace("return 3", "return 4");
        s.save_async("owner", "Agent", Some(id), Some(&changed), &json!({}))
            .await
            .unwrap();
        let status = crate::review::handle(&app, "reviewed-get", json!({"key":key}))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(status["changedSinceReviewed"], json!(["src/retry.ts"]));
        assert_eq!(status["reviewed"], json!(["2222222"]));
        assert_eq!(
            s.store.get(&format!("diff:{key}")).unwrap().unwrap()["lastViewedAt"],
            42
        );

        // Metadata-only updates preserve the change flag, and viewing the diff
        // does not clear it. Marking the new SHA reviewed does.
        s.save_async(
            "owner",
            "Agent",
            Some(id),
            None,
            &json!({"title":"New title"}),
        )
        .await
        .unwrap();
        s.open_async("owner", id).await.unwrap().unwrap();
        let status = crate::review::handle(&app, "reviewed-get", json!({"key":key}))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(status["changedSinceReviewed"], json!(["src/retry.ts"]));
        crate::review::handle(
            &app,
            "reviewed-set",
            json!({"key":key,"shas":["abcdef0"],"reviewed":true}),
        )
        .await
        .unwrap()
        .unwrap();
        let status = crate::review::handle(&app, "reviewed-get", json!({"key":key}))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(status["changedSinceReviewed"], json!([]));
        assert_eq!(status["reviewed"], json!(["abcdef0", "2222222"]));
        std::fs::remove_dir_all(s.store.root()).unwrap();
    }
}
