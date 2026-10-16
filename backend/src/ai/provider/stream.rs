use super::super::{check, safe_preview};
use super::{bounded_json, Activity};
use crate::text;
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

#[derive(Default)]
struct CompletionStream {
    pending: Vec<u8>,
    scan_from: usize,
    data: Vec<String>,
    bytes: usize,
    done: bool,
    finished: bool,
    text: String,
    thinking: String,
    reasoning_fields: std::collections::BTreeMap<&'static str, String>,
    reasoning_details: Vec<Value>,
    calls: std::collections::BTreeMap<usize, Value>,
    usage: Value,
    model: Option<String>,
}
impl CompletionStream {
    fn push(&mut self, chunk: &[u8]) -> Result<()> {
        self.bytes += chunk.len();
        if self.bytes > 16 * 1024 * 1024 {
            bail!("Pi response exceeded its size limit");
        }
        self.pending.extend_from_slice(chunk);
        while let Some(offset) = self.pending[self.scan_from..]
            .iter()
            .position(|b| *b == b'\n')
        {
            let at = self.scan_from + offset;
            let line: Vec<u8> = self.pending.drain(..=at).collect();
            self.scan_from = 0;
            let line = std::str::from_utf8(&line)
                .map_err(|_| anyhow::anyhow!("Pi returned invalid stream encoding"))?
                .trim_end_matches(['\r', '\n']);
            if line.is_empty() {
                self.dispatch()?;
            } else if let Some(data) = line.strip_prefix("data:") {
                self.data
                    .push(data.strip_prefix(' ').unwrap_or(data).to_owned());
            }
        }
        self.scan_from = self.pending.len();
        Ok(())
    }
    fn dispatch(&mut self) -> Result<()> {
        if self.data.is_empty() {
            return Ok(());
        }
        let data = std::mem::take(&mut self.data).join("\n");
        if data.trim() == "[DONE]" {
            self.done = true;
            return Ok(());
        }
        if self.done {
            bail!("Pi sent data after the stream completed");
        }
        let event: Value = serde_json::from_str(&data)
            .map_err(|_| anyhow::anyhow!("Pi returned invalid streaming JSON"))?;
        if event.get("error").is_some_and(|e| !e.is_null()) {
            bail!(
                "The Pi provider reported an error; check provider configuration and availability"
            );
        }
        if event["usage"].is_object() {
            self.usage = event["usage"].clone();
        }
        if let Some(model) = event["model"].as_str() {
            self.model = Some(model.to_owned());
        }
        let choice = event["choices"].as_array().and_then(|choices| {
            choices
                .iter()
                .find(|choice| choice["index"].as_u64().unwrap_or(0) == 0)
        });
        let Some(choice) = choice else {
            return Ok(());
        };
        if let Some(reason) = choice["finish_reason"].as_str() {
            if !["stop", "tool_calls", "function_call"].contains(&reason) {
                bail!("Pi could not complete the answer (output limit or provider filtering)");
            }
            self.finished = true;
        }
        let delta = &choice["delta"];
        if let Some(text) = delta["content"].as_str() {
            self.text.push_str(text);
        }
        let mut recorded_thinking = false;
        for field in ["reasoning_content", "reasoning", "reasoning_text"] {
            if let Some(value) = delta[field].as_str() {
                self.reasoning_fields
                    .entry(field)
                    .or_default()
                    .push_str(value);
                // Aliased fields can carry identical text. Show each delta only once.
                if !recorded_thinking && !value.is_empty() {
                    self.thinking.push_str(value);
                    recorded_thinking = true;
                }
            }
        }
        for detail in delta["reasoning_details"].as_array().into_iter().flatten() {
            // Replay metadata is never published to the activity stream.
            append_reasoning_detail(&mut self.reasoning_details, detail);
        }
        if delta["refusal"].as_str().is_some_and(|s| !s.is_empty()) {
            bail!("The model declined this request");
        }
        for fragment in delta["tool_calls"].as_array().into_iter().flatten() {
            let index = fragment["index"]
                .as_u64()
                .context("Pi returned a tool call without an index")?
                as usize;
            if index >= 128 {
                bail!("Pi returned too many tool calls");
            }
            let call = self.calls.entry(index).or_insert_with(
                || json!({"id":"","type":"function","function":{"name":"","arguments":""}}),
            );
            if let Some(id) = fragment["id"].as_str() {
                let old = text(call, "id");
                if old.is_empty() {
                    call["id"] = json!(id);
                } else if old != id {
                    bail!("Pi changed a streamed tool call identity");
                }
            }
            for key in ["name", "arguments"] {
                if let Some(fragment) = fragment["function"][key].as_str() {
                    match &mut call["function"][key] {
                        Value::String(value) => value.push_str(fragment),
                        value => *value = json!(fragment),
                    }
                }
            }
        }
        Ok(())
    }
    fn finish(mut self) -> Result<Value> {
        if !self.pending.is_empty() {
            let pending = std::mem::take(&mut self.pending);
            let line = std::str::from_utf8(&pending)
                .map_err(|_| anyhow::anyhow!("Pi returned invalid stream encoding"))?;
            if let Some(data) = line.strip_prefix("data:") {
                self.data.push(data.trim_start().to_owned());
            }
        }
        self.dispatch()?;
        if !self.finished {
            bail!("Pi stream ended before completing an assistant turn");
        }
        let calls: Vec<Value> = self.calls.into_values().collect();
        let mut ids = std::collections::HashSet::new();
        for call in &calls {
            if text(call, "id").is_empty()
                || text(&call["function"], "name").is_empty()
                || !ids.insert(text(call, "id"))
            {
                bail!("Pi returned an invalid tool call");
            }
        }
        let mut message = json!({"role":"assistant","content":self.text});
        for (field, value) in self.reasoning_fields {
            message[field] = json!(value);
        }
        if !self.reasoning_details.is_empty() {
            message["reasoning_details"] = json!(self.reasoning_details);
        }
        if !calls.is_empty() {
            message["tool_calls"] = json!(calls);
        }
        Ok(json!({"choices":[{"message":message}],"usage":self.usage,"model":self.model}))
    }
    fn record(&self, activity: &Activity, turn: usize, done: bool) {
        for (kind, value) in [
            ("text", self.text.as_str()),
            ("thinking", self.thinking.as_str()),
        ] {
            if !value.is_empty() {
                let safe = safe_preview(value, done);
                activity.record(
                    &format!("{kind}:{turn}"),
                    kind,
                    match (kind, done) {
                        ("thinking", false) => "Thinking",
                        ("thinking", true) => "Thought",
                        (_, false) => "Writing",
                        (_, true) => "Wrote",
                    },
                    Some(&safe),
                    if done { "done" } else { "running" },
                );
            }
        }
    }
}

fn append_reasoning_detail(details: &mut Vec<Value>, detail: &Value) {
    let kind = text(detail, "type");
    let field = match kind {
        "reasoning.text" => "text",
        "reasoning.summary" => "summary",
        "reasoning.encrypted" => "data",
        _ => return,
    };
    if !detail[field].is_string()
        || (!detail["id"].is_null() && !detail["id"].is_string())
        || detail.get("format").is_some_and(|v| !v.is_string())
        || detail.get("index").is_some_and(|v| !v.is_number())
        || (kind == "reasoning.text"
            && !detail["signature"].is_null()
            && !detail["signature"].is_string())
    {
        return;
    }
    // Match Pi's OpenAI adapter: merge adjacent text/summary deltas, while signed
    // encrypted entries stay opaque and discrete (including their unknown fields).
    if let Some(last) = details
        .last_mut()
        .filter(|last| kind != "reasoning.encrypted" && text(last, "type") == kind)
    {
        if let Value::String(value) = &mut last[field] {
            value.push_str(text(detail, field));
        }
        for key in ["id", "index", "format", "signature"] {
            if key == "signature" && kind != "reasoning.text" {
                continue;
            }
            let missing =
                last[key].is_null() || (["format", "signature"].contains(&key) && last[key] == "");
            if missing && detail.get(key).is_some() {
                last[key] = detail[key].clone();
            }
        }
    } else {
        details.push(detail.clone());
    }
}

pub(super) async fn completion(
    mut response: reqwest::Response,
    activity: &Activity,
    turn: usize,
) -> Result<Value> {
    let streaming = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|h| h.to_str().ok())
        .is_some_and(|s| s.to_ascii_lowercase().contains("text/event-stream"));
    if !streaming {
        // A few OpenAI-compatible proxies answer JSON even with stream=true. Preserve
        // compatibility without silently retrying a potentially billable request.
        let result = bounded_json(response, 16 * 1024 * 1024).await?;
        if result.get("error").is_some_and(|v| !v.is_null()) {
            bail!("The Pi provider reported an error");
        }
        let reason = result["choices"][0]["finish_reason"]
            .as_str()
            .unwrap_or("stop");
        if !["stop", "tool_calls", "function_call"].contains(&reason) {
            bail!("Pi could not complete the answer");
        }
        return Ok(result);
    }
    let mut stream = CompletionStream::default();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| anyhow::anyhow!("Pi response could not be read"))?
    {
        check(&activity.run)?;
        stream.push(&chunk)?;
        stream.record(activity, turn, false);
        if stream.done {
            break;
        }
    }
    stream.record(activity, turn, true);
    stream.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn streamed_turn(deltas: &[Value], finish: &str) -> CompletionStream {
        let mut stream = CompletionStream::default();
        for delta in deltas {
            let frame = format!(
                "data: {}\n\n",
                json!({"choices":[{"index":0,"delta":delta}]})
            );
            for chunk in frame.as_bytes().chunks(3) {
                stream.push(chunk).unwrap();
            }
        }
        let frame = format!(
            "data: {}\n\ndata: [DONE]\n\n",
            json!({"choices":[{"finish_reason":finish}]})
        );
        stream.push(frame.as_bytes()).unwrap();
        stream
    }

    fn second_turn_request(stream: CompletionStream) -> Value {
        let first = stream.finish().unwrap();
        // Reconstruct and serialize the next request exactly as the tool loop does.
        let request = json!({"messages":[
            {"role":"user","content":"Review a.rs"},
            first["choices"][0]["message"],
            {"role":"tool","tool_call_id":"c1","content":"source contents"}
        ]});
        let request: Value = serde_json::from_str(&request.to_string()).unwrap();
        assert_eq!(request["messages"][1]["tool_calls"][0]["id"], "c1");
        assert_eq!(request["messages"][2]["tool_call_id"], "c1");
        let second = streamed_turn(&[json!({"content":"Reviewed."})], "stop")
            .finish()
            .unwrap();
        assert_eq!(second["choices"][0]["message"]["content"], "Reviewed.");
        request
    }

    fn tool_delta() -> Value {
        json!({"tool_calls":[{"index":0,"id":"c1","function":{"name":"read_source","arguments":"{\"path\":\"a.rs\"}"}}]})
    }

    #[test]
    fn pi_two_turn_replay_preserves_each_reasoning_field() {
        for field in ["reasoning_content", "reasoning", "reasoning_text"] {
            let mut first = json!({});
            first[field] = json!("Inspect ");
            let mut last = tool_delta();
            last[field] = json!("source.");
            let stream = streamed_turn(&[first, last], "tool_calls");
            assert_eq!(stream.thinking, "Inspect source.");
            let request = second_turn_request(stream);
            assert_eq!(request["messages"][1][field], "Inspect source.");
        }
        let stream = streamed_turn(
            &[
                json!({"reasoning_content":"", "reasoning":"once", "reasoning_text":"once"}),
                tool_delta(),
            ],
            "tool_calls",
        );
        assert_eq!(stream.thinking, "once");
        let request = second_turn_request(stream);
        assert_eq!(request["messages"][1]["reasoning_content"], "");
        assert_eq!(request["messages"][1]["reasoning"], "once");
        assert_eq!(request["messages"][1]["reasoning_text"], "once");
    }

    #[test]
    fn pi_two_turn_replay_merges_details_without_exposing_metadata() {
        let encrypted = json!({"type":"reasoning.encrypted","data":"opaque-first","id":"e1","format":"provider-v1","index":2,"extra":"preserve"});
        let second_encrypted =
            json!({"type":"reasoning.encrypted","data":"opaque-second","id":"e2","index":3});
        let stream = streamed_turn(
            &[
                json!({"content":"Visible progress"}),
                json!({"reasoning_details":[{"type":"reasoning.text","text":"Inspect ","signature":null,"id":null,"format":""}]}),
                json!({"reasoning_details":[{"type":"reasoning.text","text":"source.","signature":"opaque-signature","id":"t1","format":"provider-v1","index":0}]}),
                json!({"reasoning_details":[{"type":"reasoning.summary","summary":"Read ","index":1}]}),
                json!({"reasoning_details":[{"type":"reasoning.summary","summary":"the file.","id":"s1"},encrypted,second_encrypted]}),
                json!({"reasoning_details":[{"type":"reasoning.text","text":42},{"type":"unknown","data":"ignored"}]}),
                tool_delta(),
            ],
            "tool_calls",
        );
        let run = std::sync::Arc::new(super::super::Run {
            snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0}))
                .into(),
            publication: Default::default(),
            request: json!({}),
            task: false,
            notify: tokio::sync::Notify::new().into(),
            cancel: tokio::sync::watch::channel(false).0,
        });
        stream.record(&Activity::new(&run), 0, true);
        let published = run.snapshot.lock().unwrap()["activities"].to_string();
        assert!(published.contains("Visible progress"));
        for secret in ["opaque-signature", "opaque-first", "opaque-second"] {
            assert!(!published.contains(secret));
        }
        assert!(stream.thinking.is_empty());
        let request = second_turn_request(stream);
        assert_eq!(
            request["messages"][1]["reasoning_details"],
            json!([
                {"type":"reasoning.text","text":"Inspect source.","signature":"opaque-signature","id":"t1","format":"provider-v1","index":0},
                {"type":"reasoning.summary","summary":"Read the file.","index":1,"id":"s1"},
                encrypted, second_encrypted,
            ])
        );
    }

    #[test]
    fn pi_stream_reassembles_utf8_tools_and_usage() {
        let data=concat!("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi ☃\",\"reasoning_content\":\"Think\"}}]}\r\n\r\n", "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"read_source\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}\n\n", "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.rs\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n", "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n", "data: [DONE]\n\n");
        let mut stream = CompletionStream::default();
        for chunk in data.as_bytes().chunks(3) {
            stream.push(chunk).unwrap();
        }
        assert_eq!(stream.text, "Hi ☃");
        assert_eq!(stream.thinking, "Think");
        let result = stream.finish().unwrap();
        assert_eq!(
            result["choices"][0]["message"]["tool_calls"][0]["function"]["arguments"],
            "{\"path\":\"a.rs\"}"
        );
        assert_eq!(result["usage"]["prompt_tokens"], 5);
    }
    #[test]
    fn pi_preview_clipping_preserves_complete_answer_and_reasoning_replay() {
        let part = "🦀123456789".repeat(10);
        let deltas: Vec<_> = (0..100)
            .map(|_| json!({"content":part,"reasoning_content":part}))
            .collect();
        let stream = streamed_turn(&deltas, "stop");
        let run = std::sync::Arc::new(super::super::Run {
            snapshot: std::sync::Mutex::new(json!({"status":"running","activities":[],"rev":0}))
                .into(),
            publication: Default::default(),
            request: json!({}),
            task: false,
            notify: tokio::sync::Notify::new().into(),
            cancel: tokio::sync::watch::channel(false).0,
        });
        let activity = Activity::new(&run);
        stream.record(&activity, 0, false);
        stream.record(&activity, 0, true);
        for entry in run.snapshot.lock().unwrap()["activities"]
            .as_array()
            .unwrap()
        {
            assert!(entry["text"].as_str().unwrap().chars().count() <= 8001);
        }
        let result = stream.finish().unwrap();
        let message = &result["choices"][0]["message"];
        assert_eq!(message["content"], part.repeat(100));
        assert_eq!(message["reasoning_content"], part.repeat(100));
    }
    #[test]
    fn tool_fragments_match_single_frame_for_all_accepted_finishes() {
        let name = "read_source";
        let arguments = json!({"path":"α/β.rs","note":"unicode 😀"}).to_string();
        let whole = json!({"tool_calls":[{"index":0,"id":"c1","function":{"name":name,"arguments":arguments}}]});
        let mut pieces = vec![];
        for (key, value) in [("name", name), ("arguments", arguments.as_str())] {
            for ch in value.chars() {
                let mut function = json!({});
                function[key] = json!(ch.to_string());
                pieces.push(json!({"tool_calls":[{"index":0,"id":"c1","function":function}]}));
            }
        }
        for finish in ["stop", "tool_calls", "function_call"] {
            assert_eq!(
                streamed_turn(&pieces, finish).finish().unwrap(),
                streamed_turn(&[whole.clone()], finish).finish().unwrap()
            );
        }
    }

    #[test]
    fn scan_cursor_preserves_chunk_partition_results_and_errors() {
        fn parse(input: &[u8], width: usize) -> std::result::Result<String, String> {
            let mut stream = CompletionStream::default();
            for chunk in input.chunks(width) {
                stream.push(chunk).map_err(|e| e.to_string())?;
            }
            stream
                .finish()
                .map(|v| v.to_string())
                .map_err(|e| e.to_string())
        }
        let event = json!({"choices":[{"index":0,"delta":{"content":"α😀".repeat(256)},"finish_reason":"stop"}]});
        let valid = format!("data: {event}\r\n\r\ndata: [DONE]\n\n");
        let unfinished = format!("data: {event}");
        let fixtures = [
            valid.as_bytes(),
            unfinished.as_bytes(),
            b"data: invalid\n\n",
            b"data: \xff\n\n",
            b"data: [DONE]\n\ndata: {}\n\n",
        ];
        for input in fixtures {
            let expected = parse(input, input.len());
            for width in [1, 2, 3, 7, 64, 1024] {
                assert_eq!(parse(input, width), expected, "chunk width {width}");
            }
        }
    }

    #[test]
    fn pi_stream_fails_closed_on_truncation_or_provider_errors() {
        let mut stream = CompletionStream::default();
        stream
            .push(b"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
            .unwrap();
        assert!(stream.finish().is_err());
        let mut stream = CompletionStream::default();
        assert!(stream
            .push(b"data: {\"error\":{\"message\":\"secret\"}}\n\n")
            .is_err());
        let mut stream = CompletionStream::default();
        assert!(stream
            .push(b"data: {\"choices\":[{\"finish_reason\":\"length\"}]}\n\n")
            .is_err());
    }
}
