//! Claude and Codex stream decoding for sandboxed CLI jobs.

use super::EventSink;
use crate::ai::safe_preview;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::collections::HashMap;

struct Block {
    id: String,
    kind: String,
    text: String,
}
pub(super) struct CliParser {
    binary: String,
    structured: bool,
    sink: EventSink,
    blocks: HashMap<u64, Block>,
    message: usize,
    answer: Option<String>,
    completed: bool,
    failed: bool,
    usage: Value,
    started: u64,
}
fn tool_preview(content: &Value) -> Option<String> {
    let text = content.as_str().map(str::to_owned).unwrap_or_else(|| {
        content
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|part| part["text"].as_str())
            .collect::<Vec<_>>()
            .join("\n")
    });
    let text = crate::ai::sanitize(text.trim());
    if text.is_empty() {
        None
    } else {
        Some(text.chars().take(400).collect())
    }
}
impl CliParser {
    pub(super) fn new(
        binary: &str,
        structured: bool,
        model: Option<String>,
        sink: EventSink,
    ) -> Self {
        let mut usage = json!({});
        if let Some(model) = model {
            usage["model"] = json!(model);
        }
        Self {
            binary: binary.into(),
            structured,
            sink,
            blocks: HashMap::new(),
            message: 0,
            answer: None,
            completed: false,
            failed: false,
            usage,
            started: crate::now_ms(),
        }
    }
    fn put(&self, mut patch: Value) {
        for field in ["text", "title", "tool", "id"] {
            if let Some(text) = patch[field].as_str() {
                patch[field] = json!(if field == "text"
                    && patch["status"] != "done"
                    && patch["status"] != "failed"
                {
                    crate::ai::safe_stream_text(text)
                } else {
                    crate::ai::sanitize(text)
                });
            }
        }
        (self.sink)(patch)
    }
    pub(super) fn feed(&mut self, line: &str) {
        let Ok(event) = serde_json::from_str::<Value>(line) else {
            return;
        };
        if event["type"] == "pulls_auth_warning" {
            self.put(json!({"id":"auth-refresh","kind":"status","title":"The refreshed login could not be saved. You may need to sign the CLI in again.","status":"done"}));
        } else if self.binary == "claude" {
            self.claude(&event);
        } else {
            self.codex(&event);
        }
    }
    fn claude(&mut self, event: &Value) {
        match event["type"].as_str() {
            Some("system") if event["subtype"] == "init" => { if event["model"].is_string() { self.usage["model"] = event["model"].clone(); } }
            Some("system") if event["subtype"] == "api_retry" => self.put(json!({"id":format!("retry:{}",event["attempt"]),"kind":"status","title":"Provider request failed; retrying","status":"done"})),
            Some("stream_event") => {
                let stream = &event["event"]; let index = stream["index"].as_u64().unwrap_or(0);
                match stream["type"].as_str() {
                    Some("message_start") => { self.message += 1; self.blocks.clear(); }
                    Some("content_block_start") => {
                        let content = &stream["content_block"];
                        if content["type"] == "tool_use" {
                            let id = format!("tool:{}", content["id"].as_str().unwrap_or("unknown"));
                            let tool = content["name"].as_str().unwrap_or("tool").strip_prefix("mcp__pr__").unwrap_or(content["name"].as_str().unwrap_or("tool"));
                            self.put(json!({"id":id,"kind":"tool","tool":tool,"title":format!("Using {tool}"),"status":"running"}));
                            self.blocks.insert(index, Block { id, kind:"tool".into(), text:String::new() });
                        } else if let Some(kind) = match content["type"].as_str() { Some("thinking"|"redacted_thinking") => Some("thinking"), Some("text") => Some("text"), _ => None } {
                            let id = format!("{kind}:{}:{index}", self.message);
                            let text = content[if kind == "thinking" { "thinking" } else { "text" }].as_str().unwrap_or("").to_owned();
                            self.put(json!({"id":id,"kind":kind,"title":if kind=="thinking"{"Thinking"}else{"Writing"},"status":"running","text":safe_preview(&text, false)}));
                            self.blocks.insert(index, Block { id, kind:kind.into(), text });
                        }
                    }
                    Some("content_block_delta") => {
                        if let Some(block) = self.blocks.get_mut(&index) {
                            let delta = &stream["delta"];
                            if let Some(text) = delta["text"].as_str().or(delta["thinking"].as_str()) {
                                block.text.push_str(text);
                                let patch = json!({"id":block.id,"text":safe_preview(&block.text, false)}); self.put(patch);
                            }
                        }
                    }
                    Some("content_block_stop") => {
                        if let Some(block) = self.blocks.get(&index).filter(|b| b.kind != "tool") {
                            self.put(json!({"id":block.id,"title":if block.kind=="thinking"{"Thought"}else{"Wrote"},"text":safe_preview(block.text.trim(), true),"status":"done"}));
                        }
                    }
                    _ => {}
                }
            }
            Some("assistant") => {
                if event["error"].is_string() { self.failed = true; }
                for content in event["message"]["content"].as_array().into_iter().flatten() {
                    if content["type"] == "tool_use" {
                        let tool = content["name"].as_str().unwrap_or("tool");
                        self.put(json!({"id":format!("tool:{}",content["id"].as_str().unwrap_or("unknown")),"kind":"tool","tool":tool.strip_prefix("mcp__pr__").unwrap_or(tool),"title":format!("Using {}",tool.strip_prefix("mcp__pr__").unwrap_or(tool)),"status":"running"}));
                    }
                }
            }
            Some("user") => {
                for content in event["message"]["content"].as_array().into_iter().flatten() {
                    if content["type"] == "tool_result" {
                        let mut patch = json!({"id":format!("tool:{}",content["tool_use_id"].as_str().unwrap_or("unknown")),"title":"Tool finished","status":if content["is_error"]==true{"failed"}else{"done"}});
                        if let Some(preview) = tool_preview(&content["content"]) { patch["text"] = json!(preview); }
                        self.put(patch);
                    }
                }
            }
            Some("rate_limit_event") if event["rate_limit_info"]["status"] == "rejected" => self.put(json!({"id":"rate-limit","kind":"status","title":"Provider rate limit reached","status":"done"})),
            Some("result") => {
                self.completed = event["subtype"] == "success" && event["is_error"] != true;
                self.failed |= !self.completed;
                self.answer = event.get("structured_output").filter(|v| !v.is_null()).map(Value::to_string).or_else(|| event["result"].as_str().map(str::to_owned));
                self.claude_usage(event);
            }
            _ => {}
        }
    }
    fn claude_usage(&mut self, event: &Value) {
        let models: Vec<(&String, &Value)> = event["modelUsage"]
            .as_object()
            .into_iter()
            .flatten()
            .collect();
        for (to, per_model, aggregate) in [
            ("inputTokens", "inputTokens", "input_tokens"),
            ("outputTokens", "outputTokens", "output_tokens"),
            (
                "cacheReadTokens",
                "cacheReadInputTokens",
                "cache_read_input_tokens",
            ),
            (
                "cacheWriteTokens",
                "cacheCreationInputTokens",
                "cache_creation_input_tokens",
            ),
            ("reasoningTokens", "thinkingTokens", "thinking_tokens"),
        ] {
            let counts: Vec<u64> = models
                .iter()
                .filter_map(|(_, v)| v[per_model].as_u64())
                .collect();
            if !counts.is_empty() {
                self.usage[to] = json!(counts.iter().fold(0_u64, |sum, n| sum.saturating_add(*n)));
            } else if let Some(n) = event["usage"][aggregate].as_u64().or_else(|| {
                if to == "reasoningTokens" {
                    event["usage"]["output_tokens_details"]["thinking_tokens"].as_u64()
                } else {
                    None
                }
            }) {
                self.usage[to] = json!(n);
            }
        }
        if let Some((model, _)) = models.iter().max_by(|(_, a), (_, b)| {
            a["costUSD"]
                .as_f64()
                .unwrap_or(0.)
                .total_cmp(&b["costUSD"].as_f64().unwrap_or(0.))
        }) {
            self.usage["model"] = json!(model);
        }
        for (to, from) in [("costUsd", "total_cost_usd"), ("durationMs", "duration_ms")] {
            if event[from].is_number() {
                self.usage[to] = event[from].clone();
            }
        }
    }
    fn codex(&mut self, event: &Value) {
        match event["type"].as_str() {
            Some("item.started" | "item.updated" | "item.completed") => {
                let item = &event["item"];
                let done = event["type"] == "item.completed";
                let id = item["id"].as_str().unwrap_or("unknown");
                let text = item["text"].as_str().unwrap_or("");
                let status = if item["status"] == "failed" || !item["error"].is_null() {
                    "failed"
                } else if done {
                    "done"
                } else {
                    "running"
                };
                match item["type"].as_str() {
                    Some("agent_message"|"reasoning") => {
                        let thinking = item["type"] == "reasoning";
                        if !thinking && done { self.answer = Some(text.to_owned()); }
                        let kind = if thinking {"thinking"} else {"text"};
                        let mut patch = json!({"id":format!("{kind}:{id}"),"kind":kind,"title":match (thinking,done) {(true,true)=>"Thought",(true,false)=>"Thinking",(false,true)=>"Wrote",(false,false)=>"Writing"},"status":status});
                        if !(self.structured && !thinking && serde_json::from_str::<Value>(text).is_ok()) {
                            patch["text"] = json!(safe_preview(text, status != "running"));
                        }
                        self.put(patch);
                    }
                    Some("mcp_tool_call") => {
                        let tool=item["tool"].as_str().unwrap_or("tool");
                        let mut patch=json!({"id":format!("tool:{id}"),"kind":"tool","tool":tool,"title":format!("{} {tool}",if done{"Used"}else{"Using"}),"status":status});
                        if let Some(preview)=tool_preview(&item["result"]["content"]) { patch["text"]=json!(preview); }
                        self.put(patch);
                    }
                    Some("error") => self.put(json!({"id":format!("status:{id}"),"kind":"status","title":"The CLI reported a provider error","status":"done"})),
                    _ => {}
                }
            }
            Some("turn.completed") => {
                self.completed = true;
                let usage = &event["usage"];
                let cached = usage["cached_input_tokens"].as_u64().unwrap_or(0);
                for (to, from) in [
                    ("inputTokens", "input_tokens"),
                    ("outputTokens", "output_tokens"),
                    ("cacheReadTokens", "cached_input_tokens"),
                    ("cacheWriteTokens", "cache_write_input_tokens"),
                    ("reasoningTokens", "reasoning_output_tokens"),
                ] {
                    if let Some(n) = usage[from].as_u64() {
                        let n = if to == "inputTokens" {
                            n.saturating_sub(cached)
                        } else {
                            n
                        };
                        self.usage[to] =
                            json!(self.usage[to].as_u64().unwrap_or(0).saturating_add(n));
                    }
                }
            }
            Some("turn.failed") => self.failed = true,
            _ => {}
        }
    }
    pub(super) fn finish(mut self) -> Result<(String, Value)> {
        if self.failed || !self.completed {
            bail!("The CLI did not report a successful completed turn. Check provider authentication, model availability, and CLI version.");
        }
        let answer = self
            .answer
            .filter(|s| !s.trim().is_empty())
            .context("The CLI completed without an answer")?;
        if self.structured {
            serde_json::from_str::<Value>(&answer)
                .context("The CLI did not return valid structured JSON")?;
        }
        if self.usage["durationMs"].is_null() {
            self.usage["durationMs"] = json!(crate::now_ms().saturating_sub(self.started));
        }
        let usage = serde_json::from_str(&crate::ai::sanitize(&self.usage.to_string()))?;
        Ok((answer, usage))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn parse(binary: &str, lines: &str, structured: bool) -> Result<(String, Value)> {
        let mut parser = CliParser::new(binary, structured, None, Arc::new(|_| {}));
        for line in lines.lines() {
            parser.feed(line);
        }
        parser.finish()
    }
    #[test]
    fn cli_requires_completed_success_and_full_answer() {
        assert_eq!(
            parse(
                "claude",
                r#"{"type":"result","subtype":"success","structured_output":{"answer":"ok"}}"#,
                true
            )
            .unwrap()
            .0,
            r#"{"answer":"ok"}"#
        );
        assert!(parse(
            "claude",
            r#"{"type":"result","subtype":"error","result":"secret"}"#,
            false
        )
        .is_err());
        assert!(parse(
            "claude",
            r#"{"type":"result","subtype":"success","result":"not json"}"#,
            true
        )
        .is_err());
        assert_eq!(parse("codex","{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"whole answer\"}}\n{\"type\":\"turn.completed\"}",false).unwrap().0,"whole answer");
        assert!(parse("codex", r#"{"type":"turn.completed"}"#, false).is_err());
    }
    #[test]
    fn streams_before_result_and_preserves_usage() {
        let events = Arc::new(std::sync::Mutex::new(vec![]));
        let copy = events.clone();
        let mut parser = CliParser::new(
            "claude",
            false,
            None,
            Arc::new(move |v| copy.lock().unwrap().push(v)),
        );
        parser.feed(r#"{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text"}}}"#);
        parser.feed(r#"{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}}"#);
        assert_eq!(events.lock().unwrap().last().unwrap()["text"], "Hello");
        parser.feed(r#"{"type":"result","subtype":"success","result":"Hello","usage":{"input_tokens":4,"output_tokens":2},"total_cost_usd":0.01}"#);
        let (_, usage) = parser.finish().unwrap();
        assert_eq!(usage["inputTokens"], 4);
        assert_eq!(usage["costUsd"], 0.01);
    }
    #[test]
    fn long_cli_stream_previews_keep_the_complete_final_answer() {
        for binary in ["claude", "codex"] {
            let events = Arc::new(std::sync::Mutex::new(vec![]));
            let copy = events.clone();
            let mut parser = CliParser::new(
                binary,
                false,
                None,
                Arc::new(move |v| copy.lock().unwrap().push(v)),
            );
            let answer = "🦀123456789".repeat(1000);
            if binary == "claude" {
                parser.feed(&json!({"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text"}}}).to_string());
                for chunk in answer.as_bytes().chunks(130) {
                    parser.feed(&json!({"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"text":std::str::from_utf8(chunk).unwrap()}}}).to_string());
                }
                assert_eq!(parser.blocks[&0].text, answer);
                parser.feed(
                    r#"{"type":"stream_event","event":{"type":"content_block_stop","index":0}}"#,
                );
                parser.feed(
                    &json!({"type":"result","subtype":"success","result":answer}).to_string(),
                );
            } else {
                parser.feed(&json!({"type":"item.completed","item":{"id":"full","type":"agent_message","text":answer}}).to_string());
                parser.feed(r#"{"type":"turn.completed"}"#);
            }
            for patch in events.lock().unwrap().iter() {
                if let Some(text) = patch["text"].as_str() {
                    assert!(text.chars().count() <= 8001);
                }
            }
            assert_eq!(parser.finish().unwrap().0, answer);
        }
    }
    #[test]
    fn codex_terminal_text_preserves_suffixes_and_redacts_secrets() {
        const CHILD: &str = "PULLS_REVIEW_TEST_CODEX_ACTIVITY";
        if crate::rt::env::var_os(CHILD).is_none() {
            let test_name = format!(
                "{}::codex_terminal_text_preserves_suffixes_and_redacts_secrets",
                module_path!().split_once("::").unwrap().1
            );
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", &test_name, "--nocapture"])
                .env_clear()
                .env(CHILD, "1")
                .env("OPENAI_API_KEY", "sk-test")
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "isolated Codex activity regression failed:\n{}\n{}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            assert!(String::from_utf8_lossy(&output.stdout).contains("1 passed"));
            return;
        }
        assert_eq!(crate::rt::env::var("OPENAI_API_KEY").unwrap(), "sk-test");
        for item_type in ["agent_message", "reasoning"] {
            let events = Arc::new(std::sync::Mutex::new(vec![]));
            let copy = events.clone();
            let mut parser = CliParser::new(
                "codex",
                false,
                None,
                Arc::new(move |v| copy.lock().unwrap().push(v)),
            );
            let mut emit = |event_type: &str, id: &str, status: &str, text: &str| {
                parser.feed(
                    &json!({"type":event_type,"item":{"id":id,"type":item_type,"status":status,"text":text}}).to_string(),
                );
                events.lock().unwrap().last().unwrap().clone()
            };
            assert_eq!(
                emit("item.started", "ordinary", "in_progress", "Read this")["text"],
                "Read thi"
            );
            assert_eq!(
                emit("item.completed", "ordinary", "completed", "Read this")["text"],
                "Read this"
            );
            assert_eq!(
                emit("item.updated", "secret", "in_progress", "Read this sk-")["text"],
                "Read this "
            );
            let completed = emit("item.completed", "secret", "completed", "Read this sk-test");
            assert_eq!(completed["text"], "Read this [redacted]");
            assert_eq!(completed["status"], "done");
            let failed = emit("item.updated", "failed", "failed", "Read this");
            assert_eq!(failed["text"], "Read this");
            assert_eq!(failed["status"], "failed");
            assert_eq!(
                emit(
                    "item.completed",
                    "failed-secret",
                    "failed",
                    "Read this sk-test"
                )["text"],
                "Read this [redacted]"
            );
            assert!(!serde_json::to_string(&*events.lock().unwrap())
                .unwrap()
                .contains("sk-test"));
        }
    }
}
