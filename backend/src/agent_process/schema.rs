//! Codex's strict output boundary; canonical task schemas stay provider-independent.
use crate::ai::StructuredAnswerPreparation;
use crate::omit_nulls;
use anyhow::{Context, Result};
use serde_json::{json, Map, Value};

#[cfg(test)]
mod tests;

/// @cc [owner:OctavianTocan,label:architecture] rust-codex-strict-schema-matches-cli
/// Codex output schemas MUST recursively close objects, require every declared property,
/// and make previously optional properties nullable, matching CLI strictSchema. Canonical
/// schemas and other providers MUST remain unchanged (Tavi's 16 Oct 2026 parity request;
/// CE round 8 validated finding 1).
pub(super) fn for_cli(binary: &str, schema: &Value) -> Value {
    if binary == "codex" {
        strict_schema(schema)
    } else {
        schema.clone()
    }
}

fn strict_schema(node: &Value) -> Value {
    match node {
        Value::Array(items) => Value::Array(items.iter().map(strict_schema).collect()),
        Value::Object(entries) => {
            let mut schema: Map<_, _> = entries
                .iter()
                .map(|(key, value)| (key.clone(), strict_schema(value)))
                .collect();
            if schema.get("type").and_then(Value::as_str) == Some("object") {
                if let Some(properties) = schema.get("properties").and_then(Value::as_object) {
                    let required = schema.get("required").and_then(Value::as_array);
                    let strict: Map<_, _> = properties
                        .iter()
                        .map(|(key, value)| {
                            let value = if required.is_some_and(|keys| {
                                keys.iter().any(|item| item.as_str() == Some(key))
                            }) {
                                value.clone()
                            } else {
                                json!({"anyOf":[value,{"type":"null"}]})
                            };
                            (key.clone(), value)
                        })
                        .collect();
                    schema.insert("required".into(), json!(strict.keys().collect::<Vec<_>>()));
                    schema.insert("properties".into(), Value::Object(strict));
                    schema.insert("additionalProperties".into(), json!(false));
                }
            }
            Value::Object(schema)
        }
        _ => node.clone(),
    }
}

/// @cc [owner:OctavianTocan,label:architecture] rust-codex-drops-nulls-before-validation
/// Structured Codex answers MUST remove all null-valued object properties recursively
/// before canonical preparation and validation, matching CLI dropNulls. Array entries,
/// including nulls, MUST keep their positions. Other providers MUST skip this conversion
/// (Tavi's 16 Oct 2026 parity request; CE round 8 validated finding 1).
pub(super) fn prepare_answer(
    binary: &str,
    schema: &Value,
    answer: &str,
    preparation: StructuredAnswerPreparation,
) -> Result<String> {
    let mut raw: Value =
        serde_json::from_str(answer).context("The CLI did not return valid structured JSON")?;
    if binary == "codex" {
        omit_nulls(&mut raw);
    }
    let prepared = crate::ai::prepare_structured_answer(schema, &raw, preparation)?;
    Ok(serde_json::to_string(&prepared)?)
}
