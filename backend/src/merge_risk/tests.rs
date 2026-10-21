use super::*;

pub(crate) fn assessment(verdicts: &[Value]) -> Value {
    let keys = [
        "alignment",
        "impact",
        "verification",
        "rollbackCost",
        "errorDetection",
    ];
    Value::Object(keys.iter().zip(verdicts).map(|(key, verdict)| {
        (key.to_string(), json!({"verdict":verdict,"reasons":["Evidence in [the change](src/main.rs#L1)."]}))
    }).collect())
}

#[test]
fn shared_weighting_and_threshold_fixtures() {
    let fixtures: Value = serde_json::from_str(include_str!(
        "../../../packages/core/src/types/merge-risk.fixtures.json"
    ))
    .unwrap();
    for fixture in fixtures.as_array().unwrap() {
        let input = assessment(fixture["verdicts"].as_array().unwrap());
        assert_eq!(
            assess(&input).unwrap()["level"],
            fixture["level"],
            "{}",
            fixture["name"]
        );
    }
}

#[test]
fn categories_and_evidence_are_required_and_supplied_levels_are_ignored() {
    let mut input = assessment(
        &json!(["poor", "large", "poor", "high", "poor"])
            .as_array()
            .unwrap(),
    );
    input["level"] = json!("low");
    assert_eq!(assess(&input).unwrap()["level"], "high");
    for invalid in [
        Value::Null,
        json!({}),
        {
            let mut missing = input.clone();
            missing.as_object_mut().unwrap().remove("impact");
            missing
        },
        {
            let mut empty = input.clone();
            empty["verification"]["reasons"] = json!([]);
            empty
        },
        {
            let mut empty = input.clone();
            empty["alignment"]["reasons"] = json!([" "]);
            empty
        },
        {
            let mut invalid = input.clone();
            invalid["alignment"]["verdict"] = json!("not-applicable");
            invalid
        },
    ] {
        assert!(assess(&invalid).is_err());
    }
}
