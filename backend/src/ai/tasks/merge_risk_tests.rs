use super::*;

fn provider<'a>(
    _state: &'a Arc<AppState>,
    run: &'a Arc<Run>,
    job: &'a Value,
    _preparation: StructuredAnswerPreparation,
) -> futures_util::future::BoxFuture<'a, Result<(String, Value)>> {
    Box::pin(async move {
        let enabled = crate::review_experiments::merge_risk_enabled(&run.request);
        assert_eq!(
            text(job, "prompt").contains(&crate::merge_risk::prompt()),
            enabled
        );
        assert_eq!(
            job["schema"]["properties"].get("mergeRisk").is_some(),
            enabled
        );
        let mut assessment = crate::merge_risk::tests::assessment(
            json!(["good", "large", "poor", "high", "poor"])
                .as_array()
                .unwrap(),
        );
        if run.request["incomplete"] == true {
            assessment.as_object_mut().unwrap().remove("verification");
        }
        Ok((
            json!({"summary":"Nothing to flag inline.","findings":[],"mergeRisk":assessment})
                .to_string(),
            json!({}),
        ))
    })
}

#[tokio::test]
async fn critique_merge_risk_is_stored_and_reloaded_without_inline_findings() {
    let root = std::env::temp_dir().join(format!("pulls-merge-risk-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.join("data")).unwrap();
    state.host.storage.set("diff:test", &json!({"headSha":"head","diff":{
        "ref":{"kind":"posted","id":"fixture"},"head":{"sha":"head"},"title":"A change","files":[]
    }})).unwrap();
    let request = json!({"kind":"critique","key":"test","model":{"engine":"fixture"},"variant":"review:experimental-merge-risk","experiments":{"mergeRisk":true},"expectedHeadSha":"head"});
    let run = Arc::new(super::tests::preparation_run(request.clone()));
    let (answer, _) = perform_with_provider(&state, &run, provider).await.unwrap();
    let result: Value = serde_json::from_str(&answer).unwrap();
    assert_eq!(result["mergeRisk"]["level"], "high");
    assert_eq!(result["findings"], json!([]));
    assert_eq!(result["headSha"], "head");
    let stored = state.host.storage.get("critique:test").unwrap().unwrap();
    assert_eq!(stored["entries"][0]["result"], result);
    let mut ordinary = request.clone();
    ordinary.as_object_mut().unwrap().remove("experiments");
    ordinary["variant"] = json!("review");
    let ordinary_run = Arc::new(super::tests::preparation_run(ordinary));
    let (ordinary_answer, _) = perform_with_provider(&state, &ordinary_run, provider)
        .await
        .unwrap();
    let ordinary_result: Value = serde_json::from_str(&ordinary_answer).unwrap();
    assert!(ordinary_result.get("mergeRisk").is_none());
    assert_eq!(ordinary_result["findings"], json!([]));
    let stored = state.host.storage.get("critique:test").unwrap().unwrap();
    assert_eq!(stored["entries"].as_array().unwrap().len(), 2);
    let mut invalid_request = request;
    invalid_request["incomplete"] = json!(true);
    let invalid_run = Arc::new(super::tests::preparation_run(invalid_request));
    assert!(perform_with_provider(&state, &invalid_run, provider)
        .await
        .is_err());
    assert_eq!(
        state.host.storage.get("critique:test").unwrap().unwrap(),
        stored
    );
    shutdown(&state).await;
    std::fs::remove_dir_all(root).unwrap();
}
