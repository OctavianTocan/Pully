//! Structured answers: the JSON schema subset answers are checked against, and Codex's strict
//! output boundary. Canonical task schemas stay provider-independent.
use crate::config::{config, Preparation};
use anyhow::{bail, Context, Result};
use serde_json::{json, Map, Value};

#[cfg(test)]
mod tests;

/// @cc [owner:OctavianTocan,label:architecture] rust-codex-strict-schema-matches-cli
/// Codex output schemas MUST recursively close objects, require every declared property,
/// and make previously optional properties nullable, matching CLI strictSchema. Canonical
/// schemas and other providers MUST remain unchanged (Tavi's 16 Oct 2026 parity request;
/// CE round 8 validated finding 1).
pub fn for_cli(binary: &str, schema: &Value) -> Value {
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
pub fn prepare_answer(
    binary: &str,
    schema: &Value,
    answer: &str,
    preparation: Preparation,
) -> Result<String> {
    let mut raw: Value =
        serde_json::from_str(answer).context("The CLI did not return valid structured JSON")?;
    if binary == "codex" {
        omit_nulls(&mut raw);
    }
    let prepared = (config().prepare)(preparation, schema, &raw)?;
    Ok(serde_json::to_string(&prepared)?)
}

/// Removes every null-valued object property, at any depth; array entries keep their places.
pub fn omit_nulls(value: &mut Value) {
    match value {
        Value::Object(map) => {
            map.retain(|_, v| !v.is_null());
            for v in map.values_mut() {
                omit_nulls(v);
            }
        }
        Value::Array(array) => {
            for v in array {
                omit_nulls(v);
            }
        }
        _ => {}
    }
}

/// Checks `value` against the JSON schema subset task schemas use: `anyOf`, `enum`, `type`,
/// `required`, `properties`, `additionalProperties: false` and `items`. A null schema admits
/// anything.
pub fn validate_schema(schema: &Value, value: &Value) -> Result<()> {
    if schema.is_null() {
        return Ok(());
    }
    if let Some(variants) = schema["anyOf"].as_array() {
        if !variants.iter().any(|s| validate_schema(s, value).is_ok()) {
            bail!("Model answer did not match schema")
        };
        return Ok(());
    }
    if let Some(e) = schema["enum"].as_array() {
        if !e.contains(value) {
            bail!("Model answer has an invalid enum value")
        }
    }
    let valid = match schema["type"].as_str().unwrap_or("") {
        "object" => value.is_object(),
        "array" => value.is_array(),
        "string" => value.is_string(),
        "number" => value.is_number(),
        "integer" => value.is_i64() || value.is_u64(),
        "boolean" => value.is_boolean(),
        "null" => value.is_null(),
        _ => true,
    };
    if !valid {
        bail!("Model answer has an invalid value type")
    };
    if let Some(object) = value.as_object() {
        for k in schema["required"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
        {
            if !object.contains_key(k) {
                bail!("Model answer is missing required field {k}")
            }
        }
        if let Some(props) = schema["properties"].as_object() {
            for (k, v) in object {
                if let Some(s) = props.get(k) {
                    validate_schema(s, v)?
                } else if schema["additionalProperties"] == false {
                    bail!("Model answer contains unknown field {k}")
                }
            }
        }
    }
    if let Some(items) = value.as_array() {
        for item in items {
            validate_schema(&schema["items"], item)?
        }
    }
    Ok(())
}
