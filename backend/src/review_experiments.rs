//! Optional review extensions. Removing this registration leaves ordinary review intact.
use anyhow::Result;
use serde_json::{json, Value};

pub(crate) fn merge_risk_enabled(request: &Value) -> bool {
    request["experiments"]["mergeRisk"] == true
}

/// @cc [owner:OctavianTocan,label:product;architecture] rust-review-experiments-default-off
/// Only an explicit true request flag MAY extend the review prompt, schema or result.
/// Missing and false flags MUST leave ordinary review independent of merge-risk.
/// Source: Tavi, 21 Oct 2026, opt-in experimental features and easy removal request.
pub(crate) fn schema(mut base: Value, request: &Value) -> Value {
    if merge_risk_enabled(request) {
        base["properties"]["mergeRisk"] = crate::merge_risk::schema();
        base["required"]
            .as_array_mut()
            .unwrap()
            .push(json!("mergeRisk"));
    }
    base
}

pub(crate) fn instructions(request: &Value) -> String {
    if merge_risk_enabled(request) {
        format!(
            "\nExperimental merge-risk assessment:\n{}",
            crate::merge_risk::prompt()
        )
    } else {
        String::new()
    }
}

pub(crate) fn prepare(result: &mut Value, request: &Value) -> Result<()> {
    if merge_risk_enabled(request) {
        result["mergeRisk"] = crate::merge_risk::assess(&result["mergeRisk"])?;
    } else if let Some(object) = result.as_object_mut() {
        object.remove("mergeRisk");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn experiments_leave_normal_reviews_unchanged_without_explicit_boolean_consent() {
        let base = json!({"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]});
        for request in [
            json!({}),
            json!({"experiments":{"mergeRisk":false}}),
            json!({"experiments":{"mergeRisk":"true"}}),
        ] {
            assert_eq!(schema(base.clone(), &request), base);
            assert!(instructions(&request).is_empty());
            let mut result =
                json!({"summary":"Still usable.","findings":[],"mergeRisk":{"level":"high"}});
            prepare(&mut result, &request).unwrap();
            assert_eq!(result, json!({"summary":"Still usable.","findings":[]}));
        }
        let enabled = json!({"experiments":{"mergeRisk":true}});
        assert!(schema(base, &enabled)["properties"]
            .get("mergeRisk")
            .is_some());
        assert!(!instructions(&enabled).is_empty());
    }
}
