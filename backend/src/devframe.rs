//! Devframe 1.2's birpc transport. Only the application's registered JSON data
//! methods are exposed; the native/browser security boundary is unchanged.
use super::*;
use axum::extract::{
    ws::{Message, WebSocket, WebSocketUpgrade},
    Query,
};
use futures_util::{SinkExt, StreamExt};
use std::collections::HashMap;

const MAX_RPC_BYTES: usize = MAX_CACHE_RPC_BYTES;
const MAX_AUTH_BYTES: usize = 4 * 1024 * 1024;

pub(super) async fn metadata() -> Json<Value> {
    Json(json!({"backend":"websocket","websocket":{"path":"__ws"},"jsonSerializableMethods":[]}))
}
// Non-JSON objects, cycles and excessive depth are invalid RPC data, never
// executable constructors.
/// @cc [owner:OctavianTocan,label:architecture] devframe-optional-json-fields
/// Decoding MUST omit undefined object members, retain explicit null members, and
/// use null placeholders for undefined array items (Tavi's 16 Oct parity request;
/// devframe 1.2.0 / structured-clone-es 2.0.1 JSON data contract).
#[cfg(test)]
fn decode(raw: &str) -> Result<Value> {
    decode_with_budget(raw, MAX_RPC_BYTES)
}
fn decode_with_budget(raw: &str, budget: usize) -> Result<Value> {
    anyhow::ensure!(raw.len() <= budget, "RPC data is too large");
    let Some(encoded) = raw.strip_prefix("s:") else {
        return Ok(serde_json::from_str(raw)?);
    };
    let graph: Vec<Value> = serde_json::from_str(encoded)?;
    fn charge(bytes: &mut usize, count: usize, size: usize) -> Result<()> {
        *bytes = count
            .checked_mul(size)
            .and_then(|needed| bytes.checked_sub(needed))
            .context("RPC data is too large")?;
        Ok(())
    }
    fn visit(
        graph: &[Value],
        i: usize,
        depth: usize,
        nodes: &mut usize,
        bytes: &mut usize,
    ) -> Result<Value> {
        anyhow::ensure!(depth < 64 && *nodes > 0, "RPC data is too complex");
        *nodes -= 1;
        // Charge every expanded occurrence, even when it references an earlier node.
        // Container storage is charged separately, before it can reserve or grow.
        charge(bytes, 1, std::mem::size_of::<Value>())?;
        let node = graph.get(i).context("Invalid RPC graph")?;
        let tag = node[0]
            .as_i64()
            .context("Only JSON RPC values are accepted")?;
        let v = &node[1];
        Ok(match tag {
            -1 => Value::Null,
            0 => {
                anyhow::ensure!(!v.is_object() && !v.is_array(), "Invalid RPC primitive");
                if let Some(value) = v.as_str() {
                    charge(bytes, value.len(), 1)?;
                }
                v.clone()
            }
            1 => {
                let references = v.as_array().context("Invalid RPC array")?;
                charge(bytes, references.len(), std::mem::size_of::<Value>())?;
                let mut out = Vec::with_capacity(references.len());
                for reference in references {
                    out.push(visit(
                        graph,
                        reference.as_u64().context("Invalid RPC index")? as usize,
                        depth + 1,
                        nodes,
                        bytes,
                    )?);
                }
                Value::Array(out)
            }
            2 => {
                let mut out = serde_json::Map::new();
                for pair in v.as_array().context("Invalid RPC object")? {
                    // Include entry/index storage and spare capacity for map growth.
                    charge(
                        bytes,
                        2,
                        std::mem::size_of::<(String, Value)>() + 3 * std::mem::size_of::<usize>(),
                    )?;
                    let key = visit(
                        graph,
                        pair[0].as_u64().context("Invalid RPC index")? as usize,
                        depth + 1,
                        nodes,
                        bytes,
                    )?;
                    let Value::String(key) = key else {
                        bail!("Invalid RPC key");
                    };
                    anyhow::ensure!(
                        !["__proto__", "constructor", "prototype"].contains(&key.as_str()),
                        "Invalid RPC key"
                    );
                    let index = pair[1].as_u64().context("Invalid RPC index")? as usize;
                    let value = visit(graph, index, depth + 1, nodes, bytes)?;
                    // Match JSON.stringify for optional fields. Visit first so even
                    // omitted members retain all graph, depth, node and byte guards.
                    if graph[index][0].as_i64() != Some(-1) {
                        out.insert(key, value);
                    }
                }
                Value::Object(out)
            }
            _ => bail!("Only JSON RPC values are accepted"),
        })
    }
    let mut bytes = budget;
    visit(&graph, 0, 0, &mut 100_000, &mut bytes)
}
fn valid_token(auth: &Mutex<Auth>, token: &str) -> bool {
    !token.is_empty()
        && auth.lock().is_ok_and(|auth| {
            same(token, &auth.token) || auth.dev_tokens.iter().any(|saved| same(token, saved))
        })
}
pub(super) async fn connect(
    State(state): State<HttpState>,
    headers: HeaderMap,
    Query(query): Query<HashMap<String, String>>,
    ws: WebSocketUpgrade,
) -> Response {
    let token = if trusted(&state, &headers) {
        state.auth.lock().ok().map(|auth| auth.token.clone())
    } else {
        query
            .get("devframe_auth_token")
            .filter(|token| valid_token(&state.auth, token))
            .cloned()
    };
    // Axum fixes decoder limits at upgrade time. Code exchange may authorize
    // this socket, but the browser reconnects with its issued token before large
    // RPCs. Both frame and aggregate limits must bound unfinished fragments.
    let budget = if state.no_auth || token.is_some() {
        MAX_RPC_BYTES
    } else {
        MAX_AUTH_BYTES
    };
    ws.max_message_size(budget)
        .max_frame_size(budget)
        .on_upgrade(move |socket| serve(socket, state, token))
}
fn authentication(
    auth: &Mutex<Auth>,
    no_auth: bool,
    token: &mut Option<String>,
    name: &str,
    args: &Value,
) -> Option<Result<Value>> {
    match name {
        "anonymous:devframe:auth" => {
            if !token
                .as_deref()
                .is_some_and(|token| valid_token(auth, token))
                && valid_token(auth, text(args, "authToken"))
            {
                *token = Some(text(args, "authToken").to_owned());
            }
            Some(Ok(
                json!({"isTrusted": no_auth || token.as_deref().is_some_and(|token| valid_token(auth, token))}),
            ))
        }
        "anonymous:devframe:auth:exchange" => Some((|| {
            let mut auth = auth
                .lock()
                .map_err(|_| anyhow::anyhow!("Authentication is unavailable"))?;
            if auth.attempt_window.elapsed() > Duration::from_secs(60) {
                auth.attempts = 0;
                auth.attempt_window = Instant::now();
            }
            auth.attempts = auth.attempts.saturating_add(1);
            let valid = auth.attempts <= 10
                && auth.issued.elapsed() <= Duration::from_secs(600)
                && same(text(args, "code"), &auth.code);
            if !valid {
                return Ok(json!({"authToken": Value::Null}));
            }
            anyhow::ensure!(
                auth.dev_tokens.len() < 1024,
                "Too many trusted browser sessions"
            );
            let issued = format!(
                "{}{}",
                uuid::Uuid::new_v4().simple(),
                uuid::Uuid::new_v4().simple()
            );
            auth.dev_tokens.insert(issued.clone());
            *token = Some(issued.clone());
            Ok(json!({"authToken": issued}))
        })()),
        "anonymous:devframe:auth:request-code" => Some((|| {
            let mut auth = auth
                .lock()
                .map_err(|_| anyhow::anyhow!("Authentication is unavailable"))?;
            if args["reissue"] == true || auth.issued.elapsed() > Duration::from_secs(600) {
                auth.code = Auth::default().code;
                auth.issued = Instant::now();
            }
            eprintln!("Browser trust code: {}", auth.code);
            Ok(Value::Null)
        })()),
        _ => None,
    }
}
async fn serve(socket: WebSocket, state: HttpState, mut token: Option<String>) {
    let (mut sender, mut receiver) = socket.split();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(32);
    let mut tasks = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            frame = receiver.next() => {
                let raw = match frame {
                    Some(Ok(Message::Text(raw))) => raw,
                    Some(Ok(Message::Ping(data))) => {
                        if sender.send(Message::Pong(data)).await.is_err() { break; }
                        continue;
                    }
                    Some(Ok(Message::Pong(_))) => continue,
                    _ => break,
                };
                let authorized = state.no_auth || token.as_deref().is_some_and(|token| valid_token(&state.auth, token));
                let budget = if authorized { MAX_RPC_BYTES } else { MAX_AUTH_BYTES };
                let Ok(q) = decode_with_budget(&raw, budget) else { break; };
                if q["t"] != "q" { continue; }
                let id = q["i"].clone();
                let name = text(&q, "m");
                let args = q["a"].get(0).cloned().unwrap_or(json!({}));
                let authorized = state.no_auth || token.as_deref().is_some_and(|token| valid_token(&state.auth, token));
                let answer = if let Some(result) = authentication(&state.auth, state.no_auth, &mut token, name, &args) {
                    Some(result)
                } else if !authorized {
                    Some(Err(anyhow::anyhow!("Enter the server's terminal code first.")))
                } else if name == "devframe:auth:revoke" {
                    if let Some(token) = token.take() {
                        if let Ok(mut auth) = state.auth.lock() { auth.dev_tokens.remove(&token); }
                    }
                    Some(Ok(Value::Null))
                } else if matches!(name, "devframe:rpc:server-state:subscribe" | "devframe:rpc:server-state:get") {
                    Some(Ok(json!({})))
                } else if let Some(name) = name.strip_prefix("pulls.review:") {
                    if tasks.len() >= 32 {
                        Some(Err(anyhow::anyhow!("Too many concurrent RPC requests")))
                    } else {
                        let name = name.to_owned();
                        let state = state.clone();
                        let tx = tx.clone();
                        tasks.spawn(async move {
                            let result = web_dispatch(&state.app, &name, args).await;
                            if !id.is_null() { let _ = tx.send(envelope(id, result)).await; }
                        });
                        None
                    }
                } else {
                    Some(Err(anyhow::anyhow!("That method is not available to the app.")))
                };
                if let Some(result) = answer {
                    if !q["i"].is_null() && sender.send(Message::Text(envelope(q["i"].clone(), result).into())).await.is_err() { break; }
                }
            },
            Some(reply) = rx.recv() => {
                if sender.send(Message::Text(reply.into())).await.is_err() { break; }
            },
            Some(_) = tasks.join_next(), if !tasks.is_empty() => {}
        }
    }
    // Persistent AI jobs belong to Jobs; only this connection's requests stop.
    tasks.abort_all();
}
// Outbound errors use structured-clone-es 2.0.1's Error record, as pinned by
// devframe 1.2.0. This encoder does not expand the inbound tag allowlist.
fn envelope(id: Value, result: Result<Value>) -> String {
    fn encode_data(value: Value, graph: &mut Vec<Value>) -> usize {
        let index = graph.len();
        graph.push(Value::Null);
        let encoded = match value {
            Value::Array(values) => json!([
                1,
                values
                    .into_iter()
                    .map(|value| encode_data(value, graph))
                    .collect::<Vec<_>>()
            ]),
            Value::Object(values) => json!([
                2,
                values
                    .into_iter()
                    .map(|(key, value)| [
                        encode_data(Value::String(key), graph),
                        encode_data(value, graph)
                    ])
                    .collect::<Vec<_>>()
            ]),
            primitive => json!([0, primitive]),
        };
        graph[index] = encoded;
        index
    }
    match result {
        Ok(value) => json!({"t":"s","i":id,"r":value}).to_string(),
        Err(error) => {
            let mut graph = vec![Value::Null];
            let t_key = encode_data(json!("t"), &mut graph);
            let t_value = encode_data(json!("s"), &mut graph);
            let i_key = encode_data(json!("i"), &mut graph);
            let i_value = encode_data(id, &mut graph);
            let e_key = encode_data(json!("e"), &mut graph);
            let e_value = graph.len();
            graph.push(json!([7, {"name":"Error","message":safe_error(&error)}]));
            graph[0] = json!([2, [[t_key, t_value], [i_key, i_value], [e_key, e_value]]]);
            format!("s:{}", json!(graph))
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    // Captured from devframe 1.2.0 / structured-clone-es 2.0.1. The ignored
    // interop check below also regenerates it with the executable pinned codec.
    const CRITIQUE_REQUEST: &str = r#"s:[[2,[[1,2],[3,4],[5,6],[7,8]]],[0,"t"],[0,"q"],[0,"i"],[0,"critique-1"],[0,"m"],[0,"pulls.review:ai-task-start"],[0,"a"],[1,[9]],[2,[[10,11],[12,13],[14,15],[21,22],[23,20]]],[0,"kind"],[0,"critique"],[0,"key"],[0,"repo:1"],[0,"model"],[2,[[16,17],[14,18],[19,20]]],[0,"engine"],[0,"pi"],[0,"some-model"],[0,"effort"],[-1],[0,"variant"],[0,"default"],[0,"lens"]]"#;

    #[test]
    fn undefined_object_members_are_omitted_without_dropping_nulls() {
        let frame = json!([
            [2, [[1, 2], [3, 4], [5, 6], [7, 8]]],
            [0, "omitted"],
            [-1],
            [0, "explicit"],
            [0, null],
            [0, "items"],
            [1, [2, 4, 8]],
            [0, "nested"],
            [2, [[1, 2], [3, 4]]]
        ]);
        assert_eq!(
            decode(&format!("s:{frame}")).unwrap(),
            json!({"explicit":null,"items":[null,null,{"explicit":null}],"nested":{"explicit":null}})
        );
    }

    #[test]
    fn omitted_members_still_validate_keys_and_charge_expansion() {
        for key in ["__proto__", "constructor", "prototype"] {
            let frame = json!([[2, [[1, 2]]], [0, key], [-1]]);
            assert!(decode(&format!("s:{frame}")).is_err());
        }
        let key = "k".repeat(256 * 1024);
        let frame = format!(
            "s:{}",
            json!([[1, vec![1; 32]], [2, [[2, 3]]], [0, key], [-1]])
        );
        assert!(frame.len() < MAX_RPC_BYTES);
        assert_eq!(
            decode_with_budget(&frame, 4 * 1024 * 1024)
                .unwrap_err()
                .to_string(),
            "RPC data is too large"
        );
    }

    #[test]
    fn critique_request_keeps_optional_strings_absent() {
        assert_eq!(
            decode(CRITIQUE_REQUEST).unwrap(),
            json!({"t":"q","i":"critique-1","m":"pulls.review:ai-task-start","a":[{
                "kind":"critique","key":"repo:1","model":{"engine":"pi","model":"some-model"},
                "variant":"default"
            }]})
        );
    }

    #[test]
    #[ignore = "offline interop: set PULLS_DEVFRAME_CODEC and PULLS_DEVFRAME_CLIENT_ROOT; Node.js must resolve jiti and valibot"]
    fn pinned_devframe_optional_fields_pass_existing_client_caches() {
        fn node(script: &str, args: &[&str]) -> String {
            let output = std::process::Command::new("node")
                .args(["--input-type=module", "-e", script])
                .args(args)
                .output()
                .expect("Run the pinned codec and client schemas with Node.js");
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            String::from_utf8(output.stdout).unwrap()
        }
        let codec = std::env::var("PULLS_DEVFRAME_CODEC").expect("Set PULLS_DEVFRAME_CODEC");
        let root =
            std::env::var("PULLS_DEVFRAME_CLIENT_ROOT").expect("Set PULLS_DEVFRAME_CLIENT_ROOT");
        let wires = node(
            r#"import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { n: stringify, t: parse } = await import(pathToFileURL(process.argv[1]).href);
const wires = ['some-model', undefined].map(model => {
  const request = { t: 'q', i: 'critique-1', m: 'pulls.review:ai-task-start', a: [{
    kind: 'critique', key: 'repo:1', model: { engine: 'pi', model, effort: undefined },
    variant: 'default', lens: undefined,
  }] };
  const wire = stringify(request);
  assert.deepEqual(parse(wire), request);
  return `s:${wire}`;
});
console.log(JSON.stringify(wires));"#,
            &[&codec],
        );
        let wires: Vec<String> = serde_json::from_str(&wires).unwrap();
        assert_eq!(wires[0], CRITIQUE_REQUEST);
        let requests: Vec<Value> = wires.iter().map(|wire| decode(wire).unwrap()).collect();
        for (index, request) in requests.iter().enumerate() {
            let args = &request["a"][0];
            assert!(args.get("lens").is_none());
            assert!(args["model"].get("effort").is_none());
            assert_eq!(args["model"].get("model").is_some(), index == 0);
        }
        node(
            r#"import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
const root = process.argv[1];
const require = createRequire(resolve(root, 'package.json'));
const { createJiti } = require('jiti');
const v = require('valibot');
const jiti = createJiti(resolve(root, 'package.json'), { moduleCache: false, fsCache: false });
const load = path => jiti.import(resolve(root, `packages/core/src/${path}.ts`));
const { CritiqueResultSchema } = await load('types/analyze');
const { TourResultSchema } = await load('types/tour');
const { BriefResultSchema } = await load('types/brief');
const { createCritiqueCache } = await load('cache/critique-cache');
const { createTourCache } = await load('cache/tour-cache');
const { createBriefCache } = await load('cache/brief-cache');
for (const request of JSON.parse(process.argv[2])) {
  const args = request.a[0];
  for (const [kind, schema, shape, createCache] of [
    ['critique', CritiqueResultSchema, { summary: 'ok', findings: [], dropped: 0 }, createCritiqueCache],
    ['tour', TourResultSchema, { title: 'tour', overview: 'ok', stops: [], glossary: [] }, createTourCache],
    ['brief', BriefResultSchema, { lang: 'root = Text("ok")' }, createBriefCache],
  ]) {
    // Persistence-shape mirror of ai/tasks.rs::stamp and its critique lens copy.
    // The exact decoded request is checked in Rust above; no provider is invoked.
    const result = { ...shape, headSha: 'head', locale: 'en', generatedAt: '2026-10-16T00:00:00Z', usage: {} };
    for (const field of ['engine', 'model', 'effort']) {
      if (Object.hasOwn(args.model, field)) result[field] = args.model[field];
    }
    if (kind === 'critique' && Object.hasOwn(args, 'lens')) result.lens = args.lens;
    const saved = JSON.parse(JSON.stringify(result));
    assert.equal(v.safeParse(schema, saved).success, true, kind);
    for (const field of kind === 'critique' ? ['model', 'effort', 'lens'] : ['model', 'effort']) {
      assert.equal(v.safeParse(schema, { ...saved, [field]: null }).success, false, `${kind}.${field}`);
    }
    const entries = kind === 'critique' ? [{ variant: args.variant, result: saved }] : [saved];
    const storage = { getItem: async key => key === `${kind}:${args.key}` ? { entries } : null };
    const cache = createCache(storage);
    const reloaded = kind === 'critique' ? await cache.get(args.key, args.variant) : await cache.latest(args.key);
    assert.deepEqual(reloaded, v.parse(schema, saved), `${kind} survives cache reload`);
  }
}"#,
            &[&root, &serde_json::to_string(&requests).unwrap()],
        );
    }

    #[test]
    fn supported_cache_payload_exceeds_four_mib() {
        let payload = "x".repeat(5 * 1024 * 1024);
        let frame = format!("s:{}", json!([[0, payload]]));
        assert_eq!(
            decode(&frame).unwrap().as_str().unwrap().len(),
            payload.len()
        );
        assert!(decode_with_budget(&frame, 4 * 1024 * 1024).is_err());
    }

    #[test]
    fn graph_bounds() {
        assert_eq!(
            decode(r#"s:[[2,[[1,2]]],[0,"t"],[0,"q"]]"#).unwrap(),
            json!({"t":"q"})
        );
        assert!(decode("s:[[1,[0]]]").is_err());
        assert!(decode("s:[[7,{}]]").is_err());
        assert!(decode(r#"s:[["Function","return 1"]]"#).is_err());
        assert!(decode(r#"s:[[2,[[1,2]]],[0,"__proto__"],[0,null]]"#).is_err());
    }

    #[test]
    fn repeated_strings_are_charged_before_cloning() {
        // Only 256 KiB on the wire and 33 visits, but 8 MiB if fully expanded.
        // Keep the regression itself bounded even if the limit is removed.
        let value = "x".repeat(256 * 1024);
        let frame = format!("s:{}", json!([[1, vec![1; 32]], [0, value]]));
        assert!(frame.len() < MAX_RPC_BYTES);
        assert_eq!(
            decode_with_budget(&frame, 4 * 1024 * 1024)
                .unwrap_err()
                .to_string(),
            "RPC data is too large"
        );

        let accepted = format!("s:{}", json!([[1, [1, 1]], [0, value]]));
        let decoded = decode(&accepted).unwrap();
        assert_eq!(decoded, json!([value, value]));
    }

    #[test]
    fn repeated_object_keys_are_charged_before_cloning() {
        let key = "k".repeat(256 * 1024);
        let frame = format!(
            "s:{}",
            json!([[1, vec![1; 32]], [2, [[2, 3]]], [0, key], [0, null]])
        );
        assert!(frame.len() < MAX_RPC_BYTES);
        assert_eq!(
            decode_with_budget(&frame, 4 * 1024 * 1024)
                .unwrap_err()
                .to_string(),
            "RPC data is too large"
        );
    }

    #[test]
    fn repeated_containers_are_charged_before_growing() {
        // No string payloads: the expanded Value slots alone exceed the cap.
        let references = (4 * 1024 * 1024) / (1_000 * std::mem::size_of::<Value>()) + 1;
        let frame = format!(
            "s:{}",
            json!([[1, vec![1; references]], [1, vec![2; 1_000]], [0, null]])
        );
        assert!(frame.len() < 10_000);
        assert_eq!(
            decode_with_budget(&frame, 4 * 1024 * 1024)
                .unwrap_err()
                .to_string(),
            "RPC data is too large"
        );
    }

    #[test]
    fn errors_use_structured_clone_error_records() {
        let wire = envelope(
            json!("request-7"),
            Err(anyhow::anyhow!("Open the diff first.")),
        );
        let graph: Value = serde_json::from_str(wire.strip_prefix("s:").unwrap()).unwrap();
        // Golden record layout from devframe 1.2.0 / structured-clone-es 2.0.1.
        assert_eq!(
            graph,
            json!([
                [2, [[1, 2], [3, 4], [5, 6]]],
                [0, "t"], [0, "s"], [0, "i"], [0, "request-7"], [0, "e"],
                [7, {"name":"Error", "message":"Open the diff first."}]
            ])
        );
        assert!(decode(&wire).is_err(), "Error tags remain outbound-only");
        assert_eq!(
            serde_json::from_str::<Value>(&envelope(json!("request-8"), Ok(json!({"ok":true}))))
                .unwrap(),
            json!({"t":"s", "i":"request-8", "r":{"ok":true}})
        );
    }

    #[test]
    fn error_encoder_preserves_json_ids_and_sanitizes_messages() {
        let id = json!([7, {"nested":[true, null, "id"]}]);
        let wire = envelope(id.clone(), Err(anyhow::anyhow!("{}", "x".repeat(1_500))));
        let mut graph: Vec<Value> = serde_json::from_str(wire.strip_prefix("s:").unwrap()).unwrap();
        let error_index = graph[0][1][2][1].as_u64().unwrap() as usize;
        assert_eq!(graph[error_index][0], 7);
        assert_eq!(
            graph[error_index][1]["message"].as_str().unwrap().len(),
            1_200
        );
        // Replace only the outbound Error to inspect the rest through our JSON decoder.
        graph[error_index] = json!([0, null]);
        assert_eq!(decode(&format!("s:{}", json!(graph))).unwrap()["i"], id);
    }

    #[test]
    #[ignore = "offline interop: set PULLS_DEVFRAME_CODEC to devframe 1.2.0's structured-clone-CbAV5rFI.mjs and provide Node.js"]
    fn pinned_devframe_codec_revives_actual_errors() {
        let codec = std::env::var("PULLS_DEVFRAME_CODEC").expect("Set PULLS_DEVFRAME_CODEC");
        let wire = envelope(
            json!("request-7"),
            Err(anyhow::anyhow!("Open the diff first.")),
        );
        let output = std::process::Command::new("node")
            .args([
                "--input-type=module",
                "-e",
                r#"import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { t: parse } = await import(pathToFileURL(process.argv[1]).href);
const reply = parse(process.argv[2].slice(2));
assert.equal(reply.t, 's');
assert.equal(reply.i, 'request-7');
assert(reply.e instanceof Error);
assert.equal(reply.e.message, 'Open the diff first.');"#,
                &codec,
                &wire,
            ])
            .output()
            .expect("Run the pinned codec with Node.js");
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }

    // Raw frames exercise Axum/Tungstenite's buffering before a completed
    // Message reaches serve(); a decoder-only test cannot catch this regression.
    #[tokio::test]
    async fn upgrade_limits_bound_unfinished_auth_messages_and_allow_trusted_frames() {
        use tokio::{
            io::{AsyncReadExt, AsyncWriteExt},
            net::TcpStream,
        };
        async fn connect(port: u16, query: &str) -> TcpStream {
            let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
            stream.write_all(format!("GET /__ws{query} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").as_bytes()).await.unwrap();
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") {
                assert!(headers.len() < 8192);
                headers.push(stream.read_u8().await.unwrap());
            }
            assert!(
                headers.starts_with(b"HTTP/1.1 101"),
                "{}",
                String::from_utf8_lossy(&headers)
            );
            stream
        }
        async fn frame_header(stream: &mut TcpStream, opcode: u8, length: usize) {
            let mut header = vec![opcode];
            if length < 126 {
                header.push(0x80 | length as u8);
            } else if length <= u16::MAX as usize {
                header.push(0x80 | 126);
                header.extend_from_slice(&(length as u16).to_be_bytes());
            } else {
                header.push(0x80 | 127);
                header.extend_from_slice(&(length as u64).to_be_bytes());
            }
            header.extend_from_slice(&[0; 4]); // A valid all-zero client mask.
            stream.write_all(&header).await.unwrap();
        }
        async fn send(stream: &mut TcpStream, opcode: u8, payload: &[u8]) {
            frame_header(stream, opcode, payload.len()).await;
            stream.write_all(payload).await.unwrap();
        }
        async fn receive(stream: &mut TcpStream) -> (u8, Vec<u8>) {
            let opcode = stream.read_u8().await.unwrap();
            let size = stream.read_u8().await.unwrap();
            assert_eq!(size & 0x80, 0);
            let length = match size {
                126 => stream.read_u16().await.unwrap() as usize,
                127 => stream.read_u64().await.unwrap() as usize,
                n => n as usize,
            };
            assert!(length < 8192, "fixture responses remain small");
            let mut payload = vec![0; length];
            stream.read_exact(&mut payload).await.unwrap();
            (opcode, payload)
        }
        async fn closed_before_final_frame(stream: &mut TcpStream) {
            let outcome = crate::rt::timeout(Duration::from_secs(2), stream.read_u8())
                .await
                .expect("oversized unfinished input must be rejected without a final fragment");
            match outcome {
                Ok(opcode) => {
                    assert_eq!(opcode & 0xf, 8, "only a close frame may follow rejection")
                }
                Err(error) => assert!(matches!(
                    error.kind(),
                    std::io::ErrorKind::UnexpectedEof | std::io::ErrorKind::ConnectionReset
                )),
            }
        }
        let root = std::env::temp_dir().join(format!("pulls-ws-budget-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(PathBuf::new(), root.clone()).unwrap();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = HttpState::new(app, "127.0.0.1", port, vec![], false, None).unwrap();
        state.auth.lock().unwrap().code = "123456".into();
        let server =
            crate::rt::spawn(async move { axum::serve(listener, router(state)).await.unwrap() });
        let result = crate::rt::timeout(Duration::from_secs(10), async {
            let mut unauthenticated = connect(port, "").await;
            let chunk = vec![b'x'; MAX_AUTH_BYTES / 4];
            for index in 0..4 {
                send(&mut unauthenticated, if index == 0 { 1 } else { 0 }, &chunk).await;
                send(&mut unauthenticated, 0x89, b"alive").await;
                assert_eq!(receive(&mut unauthenticated).await, (0x8a, b"alive".to_vec()));
            }
            send(&mut unauthenticated, 0, b"x").await;
            closed_before_final_frame(&mut unauthenticated).await;
            let mut oversized_frame = connect(port, "").await;
            frame_header(&mut oversized_frame, 1, MAX_AUTH_BYTES + 1).await;
            closed_before_final_frame(&mut oversized_frame).await;

            let mut exchange = connect(port, "").await;
            let request = json!({"t":"q","i":1,"m":"anonymous:devframe:auth:exchange","a":[{"code":"123456"}]}).to_string();
            send(&mut exchange, 0x81, request.as_bytes()).await;
            let (_, answer) = receive(&mut exchange).await;
            let answer: Value = serde_json::from_slice(&answer).unwrap();
            let token = answer["r"]["authToken"].as_str().unwrap();
            let mut trusted = connect(port, &format!("?devframe_auth_token={token}")).await;
            // Above Tungstenite's default 16 MiB frame limit, but still bounded.
            let request = json!({"t":"q","i":2,"m":"devframe:rpc:server-state:get","a":[],"padding":"x".repeat(17 * 1024 * 1024)}).to_string();
            send(&mut trusted, 0x81, request.as_bytes()).await;
            let (opcode, answer) = receive(&mut trusted).await;
            assert_eq!(opcode, 0x81);
            assert_eq!(serde_json::from_slice::<Value>(&answer).unwrap(), json!({"t":"s","i":2,"r":{}}));
        }).await;
        server.abort();
        let _ = server.await;
        std::fs::remove_dir_all(root).unwrap();
        result.unwrap();
    }

    fn auth_fixture() -> Mutex<Auth> {
        Mutex::new(Auth {
            code: "123456".into(),
            ..Auth::default()
        })
    }

    fn exchange(auth: &Mutex<Auth>, token: &mut Option<String>, code: &str) -> Value {
        authentication(
            auth,
            false,
            token,
            "anonymous:devframe:auth:exchange",
            &json!({"code":code}),
        )
        .unwrap()
        .unwrap()
    }

    fn assert_rejected(auth: &Mutex<Auth>, code: &str) {
        let mut token = None;
        assert_eq!(exchange(auth, &mut token, code), json!({"authToken":null}));
        assert!(token.is_none());
        assert!(auth.lock().unwrap().dev_tokens.is_empty());
    }

    #[test]
    fn authentication_rejects_wrong_code() {
        let auth = auth_fixture();
        assert_rejected(&auth, "654321");
        assert_eq!(auth.lock().unwrap().attempts, 1);
    }

    #[test]
    fn authentication_rejects_expired_code() {
        let auth = auth_fixture();
        auth.lock().unwrap().issued = Instant::now() - Duration::from_secs(601);
        assert_rejected(&auth, "123456");
    }

    #[test]
    fn authentication_throttles_correct_code() {
        let auth = auth_fixture();
        auth.lock().unwrap().attempts = 10;
        assert_rejected(&auth, "123456");
        assert_eq!(auth.lock().unwrap().attempts, 11);
    }

    #[test]
    fn authentication_resets_expired_attempt_window() {
        let auth = auth_fixture();
        {
            let mut auth = auth.lock().unwrap();
            auth.attempts = u32::MAX;
            auth.attempt_window = Instant::now() - Duration::from_secs(61);
        }
        let mut token = None;
        let reply = exchange(&auth, &mut token, "123456");
        let token = token.expect("A valid code after reset must authenticate");
        assert_eq!(reply, json!({"authToken":token}));
        let auth = auth.lock().unwrap();
        assert_eq!(auth.attempts, 1);
        assert!(auth.dev_tokens.contains(&token));
        assert_eq!(auth.dev_tokens.len(), 1);
    }

    #[test]
    fn revoked_tokens_cannot_restore_trust() {
        let auth = auth_fixture();
        let mut token = None;
        exchange(&auth, &mut token, "123456");
        let issued = token.clone().unwrap();
        assert!(valid_token(&auth, &issued));
        auth.lock().unwrap().dev_tokens.remove(&issued);
        assert!(!valid_token(&auth, &issued));
        // Both a stale connection token and a new connection's offered token fail.
        let mut new_connection = None;
        for token in [&mut token, &mut new_connection] {
            let reply = authentication(
                &auth,
                false,
                token,
                "anonymous:devframe:auth",
                &json!({"authToken":issued}),
            )
            .unwrap()
            .unwrap();
            assert_eq!(reply, json!({"isTrusted":false}));
        }
        assert!(new_connection.is_none());
        assert!(auth.lock().unwrap().dev_tokens.is_empty());
    }

    #[test]
    fn no_auth_does_not_require_or_issue_browser_tokens() {
        let auth = auth_fixture();
        let mut token = None;
        let reply = authentication(
            &auth,
            true,
            &mut token,
            "anonymous:devframe:auth",
            &json!({}),
        )
        .unwrap()
        .unwrap();
        assert_eq!(reply, json!({"isTrusted":true}));
        assert!(token.is_none());
        assert!(auth.lock().unwrap().dev_tokens.is_empty());
        assert!(authentication(&auth, true, &mut token, "storage:get", &json!({})).is_none());
    }
}
