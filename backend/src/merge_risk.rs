//! Built-in merge assessment. The browser and server use the same versioned rubric.
use anyhow::{bail, Result};
use serde_json::{json, Map, Value};
use std::sync::OnceLock;

#[cfg(test)]
pub(crate) mod tests;

fn rubric() -> &'static Value {
    static RUBRIC: OnceLock<Value> = OnceLock::new();
    RUBRIC.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../packages/core/src/types/merge-risk-rubric.json"
        ))
        .expect("the bundled merge-risk rubric must be valid JSON")
    })
}

pub(crate) fn prompt() -> String {
    rubric()["instructions"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(Value::as_str)
        .collect::<Vec<_>>()
        .join("\n\n")
}

pub(crate) fn schema() -> Value {
    let properties: Map<String, Value> = rubric()["categories"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(key, category)| {
            let verdicts: Vec<_> = category["verdicts"].as_object().unwrap().keys().collect();
            (key.clone(), json!({
                "type":"object", "properties":{
                    "verdict":{"type":"string","enum":verdicts},
                    "reasons":{"type":"array","items":{"type":"string","minLength":1},"minItems":1}
                }, "required":["verdict","reasons"], "additionalProperties":false
            }))
        })
        .collect();
    let required: Vec<_> = properties.keys().cloned().collect();
    json!({"type":"object","properties":properties,"required":required,"additionalProperties":false})
}

/// @cc [owner:OctavianTocan,label:product] rust-merge-risk-is-computed-from-evidence
/// A merge assessment MUST contain every category with a valid verdict and nonempty
/// reasons. Its overall level MUST be computed from the shared Warp rubric, ignoring
/// any supplied level. Scores MUST stay internal. Source: Tavi, 21 Oct 2026, request
/// to implement warpdotdev/common-skills merge-risk as part of Pully's PR assessment.
pub(crate) fn assess(raw: &Value) -> Result<Value> {
    let mut assessment = raw.clone();
    if let Some(object) = assessment.as_object_mut() {
        object.remove("level");
    }
    crate::ai::validate_schema(&schema(), &assessment)?;
    let mut weighted = 0.0;
    let mut weights = 0.0;
    for (key, category) in rubric()["categories"].as_object().unwrap() {
        let reasons = assessment[key]["reasons"].as_array().unwrap();
        if reasons.is_empty()
            || reasons
                .iter()
                .any(|reason| reason.as_str().unwrap().trim().is_empty())
        {
            bail!("Merge risk category {key} needs evidence or an explicit gap");
        }
        let verdict = assessment[key]["verdict"].as_str().unwrap();
        let weight = category["weight"].as_f64().unwrap();
        weighted += category["verdicts"][verdict].as_f64().unwrap() * weight;
        weights += weight;
    }
    let score = weighted / weights;
    assessment["level"] = json!(if score < rubric()["thresholds"]["low"].as_f64().unwrap() {
        "low"
    } else if score < rubric()["thresholds"]["medium"].as_f64().unwrap() {
        "medium"
    } else {
        "high"
    });
    Ok(assessment)
}
