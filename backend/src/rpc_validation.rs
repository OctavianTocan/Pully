//! The declared browser/native RPC schemas, translated from the shared client
//! contract. Parse before dispatch: defaults, trims and unknown-field stripping
//! are part of the contract, not just type checks.
use anyhow::{bail, Result};
use serde_json::{json, Map, Value};

#[derive(Clone)]
enum Schema {
    Any,
    Str,
    Number,
    Bool,
    Null,
    True,
    Pick(&'static [&'static str]),
    Pattern(&'static str),
    Nonempty,
    Question(Option<usize>),
    Array(Box<Schema>),
    Object(Vec<(&'static str, Schema)>, bool),
    Record(Box<Schema>),
    Optional(Box<Schema>),
    Nullable(Box<Schema>),
    Default(Box<Schema>, Value),
    Variant(&'static str, Vec<(&'static str, Schema)>),
}
use Schema::*;
fn obj(fields: Vec<(&'static str, Schema)>) -> Schema {
    Object(fields, false)
}
fn arr(schema: Schema) -> Schema {
    Array(Box::new(schema))
}
fn opt(schema: Schema) -> Schema {
    Optional(Box::new(schema))
}
fn nullable(schema: Schema) -> Schema {
    Nullable(Box::new(schema))
}
fn strings() -> Schema {
    arr(Str)
}
fn engine() -> Schema {
    Pick(&["claude-code", "codex", "pi"])
}
fn side() -> Schema {
    Pick(&["additions", "deletions"])
}
fn lens() -> Schema {
    Pattern(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
}
fn valid_object_key(key: &str) -> bool {
    !matches!(key, "__proto__" | "prototype" | "constructor")
}
fn js_space(c: char) -> bool {
    matches!(c, '\u{0009}'..='\u{000d}' | '\u{0020}' | '\u{00a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}' | '\u{feff}')
}
impl Schema {
    fn parse(&self, input: &Value) -> std::result::Result<Value, ()> {
        let valid = match self {
            Any => true,
            Str => input.is_string(),
            Number => input.is_number(),
            Bool => input.is_boolean(),
            Null => input.is_null(),
            True => input == true,
            Pick(choices) => input.as_str().is_some_and(|s| choices.contains(&s)),
            Pattern(pattern) => input.as_str().is_some_and(|s| {
                regex::Regex::new(pattern)
                    .expect("static RPC pattern")
                    .is_match(s)
            }),
            Nonempty => input.as_str().is_some_and(|s| !s.is_empty()),
            Question(max) => {
                let text = input.as_str().ok_or(())?.trim_matches(js_space);
                if text.is_empty() || max.is_some_and(|n| text.encode_utf16().count() > n) {
                    return Err(());
                }
                return Ok(json!(text));
            }
            Array(item) => {
                return input
                    .as_array()
                    .ok_or(())?
                    .iter()
                    .map(|v| item.parse(v))
                    .collect::<std::result::Result<Vec<_>, _>>()
                    .map(Value::Array);
            }
            Object(fields, loose) => {
                let source = input.as_object().ok_or(())?;
                let mut result = if *loose {
                    source
                        .iter()
                        .filter(|(key, _)| valid_object_key(key))
                        .map(|(key, value)| (key.clone(), value.clone()))
                        .collect()
                } else {
                    Map::new()
                };
                for (key, schema) in fields {
                    let value = match source.get(*key) {
                        Some(value) => schema.parse(value)?,
                        None => match schema {
                            Optional(_) => continue,
                            Default(schema, value) => schema.parse(value)?,
                            _ => return Err(()),
                        },
                    };
                    result.insert((*key).into(), value);
                }
                return Ok(Value::Object(result));
            }
            Record(schema) => {
                return input
                    .as_object()
                    .ok_or(())?
                    .iter()
                    .filter(|(key, _)| valid_object_key(key))
                    .map(|(key, value)| Ok((key.clone(), schema.parse(value)?)))
                    .collect::<std::result::Result<Map<_, _>, _>>()
                    .map(Value::Object);
            }
            Optional(schema) | Default(schema, _) => return schema.parse(input),
            Nullable(schema) => {
                return if input.is_null() {
                    Ok(Value::Null)
                } else {
                    schema.parse(input)
                }
            }
            Variant(key, variants) => {
                let tag = input.get(*key).and_then(Value::as_str).ok_or(())?;
                return variants
                    .iter()
                    .find(|(name, _)| *name == tag)
                    .ok_or(())?
                    .1
                    .parse(input);
            }
        };
        if valid {
            Ok(input.clone())
        } else {
            Err(())
        }
    }
}

fn model() -> Schema {
    obj(vec![
        ("engine", engine()),
        ("model", opt(Str)),
        ("effort", opt(Str)),
    ])
}
fn job(extended: bool) -> Schema {
    let mut fields = vec![
        ("engine", engine()),
        ("model", opt(Str)),
        ("effort", opt(Str)),
        ("system", Str),
        ("prompt", Str),
        ("schema", opt(Any)),
    ];
    if extended {
        fields.extend([
            ("label", opt(Str)),
            ("key", opt(Str)),
            (
                "context",
                opt(obj(vec![
                    ("owner", Pattern(r"^[A-Za-z0-9_.-]+$")),
                    ("repo", Pattern(r"^[A-Za-z0-9_.-]+$")),
                    ("number", opt(Number)),
                    ("title", opt(Str)),
                    ("headSha", Pattern(r"^[0-9a-fA-F]{7,64}$")),
                    ("baseSha", opt(Str)),
                    (
                        "files",
                        arr(obj(vec![
                            ("path", Str),
                            ("previousPath", opt(Str)),
                            ("status", opt(Str)),
                            ("patch", opt(Str)),
                        ])),
                    ),
                ])),
            ),
        ]);
    }
    obj(fields)
}
fn task() -> Schema {
    let mut variants = Vec::new();
    for kind in [
        "analyze", "critique", "ask", "chat", "tour", "brief", "inbox",
    ] {
        let mut fields = vec![
            ("kind", Str),
            (
                "key",
                if kind == "inbox" {
                    Pick(&["inbox"])
                } else {
                    Nonempty
                },
            ),
            ("model", model()),
        ];
        match kind {
            "analyze" => fields.extend([
                ("locale", Str),
                ("then", opt(arr(Pick(&["tour", "brief"])))),
            ]),
            "critique" => fields.extend([("variant", Str), ("lens", opt(lens()))]),
            "ask" => fields.extend([
                ("threadId", Pattern(r"^[A-Za-z0-9_-]{1,80}$")),
                (
                    "selection",
                    obj(vec![
                        ("path", Str),
                        ("side", side()),
                        ("startLine", Number),
                        ("endLine", Number),
                    ]),
                ),
                ("question", Question(None)),
            ]),
            "chat" => fields.extend([("locale", Str), ("text", opt(Str)), ("retry", opt(Bool))]),
            "inbox" => fields.extend([
                ("locale", Str),
                ("question", Question(Some(4000))),
                ("retry", opt(Bool)),
                ("skip", opt(strings())),
            ]),
            _ => fields.push(("locale", Str)),
        }
        variants.push((kind, obj(fields)));
    }
    Variant("kind", variants)
}
fn storage_item(remove: bool) -> Schema {
    let mut fields = vec![("key", Str), ("expected", nullable(Str))];
    if !remove {
        fields.push(("value", Str));
    }
    obj(fields)
}
fn input_schema(name: &str) -> Option<Schema> {
    Some(match name {
        "source-key" | "source-fetch" | "source-fingerprint" => obj(vec![("target", Str)]),
        "source-load-file" => obj(vec![("target", Str), ("path", Str), ("sha", Str)]),
        "storage-get-item" | "ai-task-list" | "ask-threads" => obj(vec![("key", Str)]),
        "storage-set-item" => storage_item(false),
        "storage-remove-item" => storage_item(true),
        "storage-get-keys" => obj(vec![("base", Str)]),
        "storage-get-items" => obj(vec![("keys", strings())]),
        "storage-set-items" => obj(vec![("items", arr(storage_item(false)))]),
        "github-request" => obj(vec![
            (
                "path",
                Pattern(r"^/(?:repos/|user(?:[/?]|$)|search/|graphql$)"),
            ),
            (
                "method",
                opt(Pick(&["GET", "POST", "PUT", "PATCH", "DELETE"])),
            ),
            ("body", opt(Record(Box::new(Any)))),
            (
                "accept",
                opt(Pick(&[
                    "application/vnd.github+json",
                    "application/vnd.github.raw",
                    "application/vnd.github.diff",
                    "application/vnd.github.sha",
                ])),
            ),
        ]),
        "my-pulls" => obj(vec![("state", Pick(&["open", "closed"]))]),
        "my-pulls-cached" => obj(vec![
            ("state", Pick(&["open", "closed"])),
            ("revalidate", opt(Bool)),
            ("classify", opt(Bool)),
        ]),
        "lens-get" => obj(vec![("name", lens())]),
        "llm-run" => job(false),
        "ai-job-start" => job(true),
        "ai-models" => obj(vec![("engine", engine()), ("refresh", opt(Bool))]),
        "ai-job-wait" | "ai-task-wait" => obj(vec![("id", Str), ("after", Number)]),
        "ai-job-cancel" | "ai-task-cancel" => obj(vec![("id", Str)]),
        "ai-task-start" => task(),
        "ai-task-list-all" | "inbox-chat" | "inbox-chat-clear" => obj(vec![]),
        "analytics-summary" => obj(vec![
            ("from", opt(Str)),
            ("to", opt(Str)),
            ("days", opt(Number)),
        ]),
        "ask-thread-close" => obj(vec![("key", Str), ("threadId", Str)]),
        "cache-diff" => obj(vec![
            (
                "method",
                Pick(&[
                    "get",
                    "getMeta",
                    "putDiff",
                    "touch",
                    "setChangedSinceReviewed",
                    "setAnalyzedResult",
                    "setLlmSession",
                    "setSharedComment",
                    "setReviewData",
                ]),
            ),
            ("key", Str),
            ("values", Default(Box::new(arr(Any)), json!([]))),
        ]),
        _ => return None,
    })
}

/// Enforce the original declared schemas and pass only the parsed value onward.
/// Native-only operations keep their own domain validation. No-schema queries
/// still accept the null used for an omitted argument by the HTTP adapter.
pub(crate) fn arguments(name: &str, args: Value) -> Result<Value> {
    if let Some(schema) = input_schema(name) {
        let mut parsed = schema
            .parse(&args)
            .map_err(|_| anyhow::anyhow!("That method received invalid arguments."))?;
        if name == "cache-diff" {
            cache_values(&mut parsed)
                .map_err(|_| anyhow::anyhow!("That method received invalid arguments."))?;
        }
        return Ok(parsed);
    }
    if !args.is_object() && !args.is_null() {
        bail!("RPC arguments must be an object.");
    }
    Ok(args)
}

pub(crate) fn result(name: &str, value: Value) -> Result<Value> {
    let schema = match name {
        "source-key" | "source-fingerprint" | "llm-run" | "ai-job-start" | "ai-task-start" => Str,
        "source-fetch" => diff(),
        "source-load-file" | "lens-get" | "storage-get-item" => nullable(Str),
        "storage-get-keys" => strings(),
        "storage-get-items" => arr(nullable(Str)),
        "storage-set-item"
        | "storage-remove-item"
        | "storage-set-items"
        | "inbox-chat-clear"
        | "ask-thread-close" => Null,
        "github-request" => obj(vec![
            ("status", Number),
            ("headers", Record(Box::new(Str))),
            ("body", Str),
        ]),
        "ai-job-cancel" | "ai-task-cancel" => Bool,
        // trusted<T>(), unknown(), and undeclared returns deliberately do not
        // impose a stricter shape than the original boundary.
        _ => return Ok(value),
    };
    schema
        .parse(&value)
        .map_err(|_| anyhow::anyhow!("That method returned an invalid result."))
}

fn source_ref() -> Schema {
    Variant(
        "kind",
        vec![
            (
                "github-pr",
                obj(vec![
                    ("kind", Str),
                    ("owner", Str),
                    ("repo", Str),
                    ("number", Str),
                ]),
            ),
            (
                "github-compare",
                obj(vec![
                    ("kind", Str),
                    ("owner", Str),
                    ("repo", Str),
                    ("base", Str),
                    ("head", Str),
                ]),
            ),
            (
                "github-commit",
                obj(vec![
                    ("kind", Str),
                    ("owner", Str),
                    ("repo", Str),
                    ("sha", Str),
                ]),
            ),
            ("paste", obj(vec![("kind", Str), ("hash", Str)])),
            ("posted", obj(vec![("kind", Str), ("id", Str)])),
            (
                "local",
                obj(vec![("kind", Str), ("repo", Str), ("target", Str)]),
            ),
        ],
    )
}
fn diff() -> Schema {
    let revision = || obj(vec![("sha", Str), ("ref", Str)]);
    obj(vec![
        ("ref", source_ref()),
        ("title", Str),
        ("label", opt(Str)),
        (
            "author",
            opt(obj(vec![("name", Str), ("avatarUrl", opt(Str))])),
        ),
        ("description", opt(Str)),
        ("url", opt(Str)),
        ("base", opt(revision())),
        ("head", opt(revision())),
        ("createdAt", opt(Str)),
        ("updatedAt", opt(Str)),
        (
            "pullRequest",
            opt(obj(vec![(
                "state",
                opt(Pick(&["open", "closed", "merged", "draft"])),
            )])),
        ),
        (
            "commits",
            opt(arr(obj(vec![("sha", Str), ("message", Str)]))),
        ),
        (
            "files",
            arr(obj(vec![
                ("path", Str),
                ("previousPath", opt(Str)),
                (
                    "status",
                    Pick(&["added", "removed", "modified", "renamed", "copied"]),
                ),
                ("additions", Number),
                ("deletions", Number),
                ("isBinary", Bool),
                ("sha", Str),
                (
                    "hunks",
                    arr(obj(vec![
                        ("header", Str),
                        ("oldStart", Number),
                        ("oldLines", Number),
                        ("newStart", Number),
                        ("newLines", Number),
                        ("patch", Str),
                    ])),
                ),
                ("truncated", opt(True)),
            ])),
        ),
    ])
}
fn grouped() -> Schema {
    let leaf = || {
        vec![
            ("key", Str),
            ("label", Str),
            ("summary", opt(Str)),
            (
                "category",
                opt(Pick(&[
                    "ui", "api", "core", "data", "cli", "security", "tests", "docs", "examples",
                    "deps", "build", "scripts", "config", "i18n", "assets", "other",
                ])),
            ),
            ("filePaths", strings()),
        ]
    };
    let mut group = leaf();
    group.push(("children", opt(arr(obj(leaf())))));
    obj(vec![
        ("overallSummary", opt(Str)),
        ("groups", arr(obj(group))),
        (
            "files",
            opt(arr(obj(vec![
                ("path", Str),
                ("summary", Str),
                (
                    "hunks",
                    opt(arr(obj(vec![("index", Number), ("note", Str)]))),
                ),
            ]))),
        ),
        ("schemaVersion", Number),
        ("source", Pick(&["none", "rule-based", "llm", "web-llm"])),
        ("generatedAt", Str),
        ("model", opt(Str)),
        ("sharedBy", opt(Str)),
        ("locale", opt(Str)),
    ])
}
fn session() -> Schema {
    let messages = || arr(Object(vec![("role", Str)], true));
    obj(vec![
        ("messages", messages()),
        ("chatStartIndex", Number),
        ("suggestions", opt(strings())),
        (
            "archive",
            opt(arr(obj(vec![
                ("id", Str),
                ("archivedAt", Number),
                ("messages", messages()),
                ("suggestions", opt(strings())),
            ]))),
        ),
    ])
}
fn review_data() -> Schema {
    let author = || opt(obj(vec![("login", Str), ("avatarUrl", opt(Str))]));
    obj(vec![
        (
            "threads",
            arr(obj(vec![
                ("rootId", Number),
                ("path", Str),
                ("side", side()),
                ("line", opt(Number)),
                ("startLine", opt(Number)),
                ("startSide", opt(side())),
                ("outdated", Bool),
                ("resolved", opt(Bool)),
                ("threadId", opt(Str)),
                ("pending", Bool),
                (
                    "comments",
                    arr(obj(vec![
                        ("id", Number),
                        ("author", author()),
                        ("body", Str),
                        ("createdAt", Str),
                        ("url", opt(Str)),
                        ("pending", Bool),
                    ])),
                ),
            ])),
        ),
        (
            "summaries",
            arr(obj(vec![
                ("id", Number),
                ("author", author()),
                (
                    "state",
                    Pick(&["approved", "changes_requested", "commented", "dismissed"]),
                ),
                ("body", Str),
                ("submittedAt", opt(Str)),
                ("url", opt(Str)),
            ])),
        ),
        (
            "pendingReview",
            opt(obj(vec![("id", Number), ("nodeId", Str), ("body", Str)])),
        ),
    ])
}
fn cache_values(args: &mut Value) -> std::result::Result<(), ()> {
    let method = args["method"].as_str().ok_or(())?.to_owned();
    let values = args["values"].as_array_mut().ok_or(())?;
    // JSON encodes undefined array entries as null. These three optional tuple
    // positions are the existing HTTP representation of clearing/omitting them.
    let schemas = match method.as_str() {
        "putDiff" => vec![
            diff(),
            Str,
            nullable(Str),
            opt(nullable(obj(vec![("viewed", opt(Bool))]))),
        ],
        "setChangedSinceReviewed" => vec![strings()],
        "setAnalyzedResult" => vec![Pick(&["llm", "web-llm"]), grouped()],
        "setLlmSession" => vec![nullable(session()), Str],
        "setSharedComment" => vec![opt(nullable(obj(vec![("id", Number), ("url", Str)])))],
        "setReviewData" => vec![review_data()],
        _ => return Ok(()),
    };
    for (i, schema) in schemas.iter().enumerate() {
        match values.get_mut(i) {
            Some(value) => *value = schema.parse(value)?,
            None if matches!(schema, Optional(_)) => {}
            None => return Err(()),
        }
    }
    Ok(())
}

#[cfg(test)]
#[path = "rpc_validation/tests.rs"]
mod tests;
