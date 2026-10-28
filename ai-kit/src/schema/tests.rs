use super::*;

const STRICT: Preparation = Preparation::STRICT;

#[test]
fn strict_schema_matches_the_cli_nested_optional_fixture() {
    // the former TypeScript schema.test.ts, including the exact property/required order.
    let canonical = json!({"type":"object","properties":{"label":{"type":"string"},"children":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}}},"required":["label"]});
    let expected = json!({"type":"object","properties":{"label":{"type":"string"},"children":{"anyOf":[{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"}},"required":["key"],"additionalProperties":false}},{"type":"null"}]}},"required":["label","children"],"additionalProperties":false});
    assert_eq!(for_cli("codex", &canonical), expected);
    assert_eq!(canonical["required"], json!(["label"]));
    assert!(canonical.get("additionalProperties").is_none());
}

#[test]
fn strict_schema_matches_cli_for_definitions_unions_and_empty_objects() {
    let canonical = json!({
        "$defs":{"leaf":{"type":"object","properties":{"requiredNull":{"type":"null"},"optional":{"type":"string"}},"required":["requiredNull"],"additionalProperties":{"type":"string"}}},
        "anyOf":[{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"anyOf":[{"type":"number"},{"type":"null"}]}},"required":["key"]}},{"type":"null"}],
        "metadata":[true,{"type":"object","properties":{}},{"type":"object"},{"type":["object","null"],"properties":{"x":{"type":"string"}}}]
    });
    // Evaluated against the former TypeScript strictSchema, without a provider.
    let expected = json!({
        "$defs":{"leaf":{"type":"object","properties":{"requiredNull":{"type":"null"},"optional":{"anyOf":[{"type":"string"},{"type":"null"}]}},"required":["requiredNull","optional"],"additionalProperties":false}},
        "anyOf":[{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"value":{"anyOf":[{"anyOf":[{"type":"number"},{"type":"null"}]},{"type":"null"}]}},"required":["key","value"],"additionalProperties":false}},{"type":"null"}],
        "metadata":[true,{"type":"object","properties":{},"required":[],"additionalProperties":false},{"type":"object"},{"type":["object","null"],"properties":{"x":{"type":"string"}}}]
    });
    assert_eq!(for_cli("codex", &canonical), expected);
    assert_eq!(for_cli("codex", &Value::Null), Value::Null);
    assert_eq!(for_cli("codex", &json!(false)), json!(false));
}

#[test]
fn drop_nulls_matches_cli_without_changing_array_positions_or_scalars() {
    let mut answer = json!({"label":"a","children":null,"nested":[null,{"x":null,"y":1,"items":[null,false,0,""]}],"requiredNull":null,"empty":{}});
    omit_nulls(&mut answer);
    assert_eq!(
        answer,
        json!({"label":"a","nested":[null,{"y":1,"items":[null,false,0,""]}],"empty":{}})
    );
    let mut null = Value::Null;
    omit_nulls(&mut null);
    assert_eq!(null, Value::Null);
}

// Exact task-schema fixtures from Pully's backend/src/ai/tasks/grouping.rs and ai/tasks.rs.
fn string() -> Value {
    json!({"type":"string"})
}
fn strings() -> Value {
    json!({"type":"array","items":string()})
}
fn obj(properties: Value, required: &[&str]) -> Value {
    json!({"type":"object","properties":properties,"required":required})
}
fn grouping_schema() -> Value {
    let leaf = obj(
        json!({"key":string(),"label":string(),"summary":string(),"category":{"type":"string","enum":["ui","api","core","data","cli","security","tests","docs","examples","deps","build","scripts","config","i18n","assets","other"]},"filePaths":strings()}),
        &["key", "label", "category", "filePaths"],
    );
    let mut group = leaf.clone();
    group["properties"]["children"] = json!({"type":"array","items":leaf});
    obj(
        json!({"overallSummary":string(),"groups":{"type":"array","items":group},"files":{"type":"array","items":obj(json!({"path":string(),"summary":string(),"hunks":{"type":"array","items":obj(json!({"index":{"type":"number"},"note":string()}),&["index","note"])}}),&["path","summary"])}}),
        &["overallSummary", "groups"],
    )
}
fn critique_schema() -> Value {
    obj(
        json!({"summary":string(),"findings":{"type":"array","items":obj(json!({"id":string(),"path":string(),"side":{"type":"string","enum":["additions","deletions"]},"line":{"type":"integer"},"startLine":{"type":"integer"},"severity":{"type":"string","enum":["bug","risk","nit"]},"title":string(),"body":string(),"suggestion":string()}),&["id","path","side","line","severity","title","body"])}}),
        &["summary", "findings"],
    )
}
fn tour_plan_schema() -> Value {
    obj(
        json!({"title":string(),"overview":string(),"stops":{"type":"array","items":obj(json!({"title":string(),"purpose":string(),"files":strings(),"notes":string()}),&["title","purpose","files","notes"])},"glossary":{"type":"array","items":obj(json!({"term":string(),"meaning":string()}),&["term","meaning"])}}),
        &["title", "overview", "stops", "glossary"],
    )
}
fn stop_schema() -> Value {
    obj(
        json!({"title":string(),"purpose":string(),"files":strings(),"takeaway":string(),"blocks":{"type":"array","items":{"anyOf":[obj(json!({"kind":{"enum":["text"]},"text":string()}),&["kind","text"]),obj(json!({"kind":{"enum":["shape"]},"text":string(),"lang":string(),"body":string()}),&["kind","text","lang","body"]),obj(json!({"kind":{"enum":["diagram"]},"text":string(),"body":string()}),&["kind","text","body"])]}},"sources":{"type":"array","items":obj(json!({"path":string(),"line":{"type":"integer"},"why":string()}),&["path","line","why"])}}),
        &["title", "purpose", "files", "takeaway", "blocks", "sources"],
    )
}

fn assert_strict_objects(node: &Value) {
    match node {
        Value::Array(items) => items.iter().for_each(assert_strict_objects),
        Value::Object(entries) => {
            if entries.get("type").and_then(Value::as_str) == Some("object") {
                if let Some(properties) = entries.get("properties").and_then(Value::as_object) {
                    assert_eq!(entries["additionalProperties"], false);
                    assert_eq!(
                        entries["required"],
                        json!(properties.keys().collect::<Vec<_>>())
                    );
                }
            }
            entries.values().for_each(assert_strict_objects);
        }
        _ => {}
    }
}

#[test]
fn canonical_task_schemas_are_strict_only_at_the_codex_boundary() {
    for schema in [
        grouping_schema(),
        critique_schema(),
        tour_plan_schema(),
        stop_schema(),
    ] {
        let original = schema.clone();
        let strict = for_cli("codex", &schema);
        assert_strict_objects(&strict);
        for binary in ["claude", "pi"] {
            assert_eq!(for_cli(binary, &schema), original);
        }
        assert_eq!(schema, original);
    }
    let grouping = for_cli("codex", &grouping_schema());
    assert_eq!(
        grouping["properties"]["files"]["anyOf"][1],
        json!({"type":"null"})
    );
    let group = &grouping["properties"]["groups"]["items"];
    assert_eq!(
        group["properties"]["summary"]["anyOf"][1],
        json!({"type":"null"})
    );
    let leaf = &group["properties"]["children"]["anyOf"][0]["items"];
    assert_eq!(
        leaf["properties"]["summary"]["anyOf"][1],
        json!({"type":"null"})
    );
    let critique = for_cli("codex", &critique_schema());
    let finding = &critique["properties"]["findings"]["items"]["properties"];
    assert_eq!(finding["startLine"]["anyOf"][1], json!({"type":"null"}));
    assert_eq!(finding["suggestion"]["anyOf"][1], json!({"type":"null"}));
}

fn prepared(binary: &str, schema: &Value, raw: &Value, preparation: Preparation) -> Result<Value> {
    crate::config::for_tests();
    Ok(serde_json::from_str(&prepare_answer(
        binary,
        schema,
        &raw.to_string(),
        preparation,
    )?)?)
}

#[test]
fn codex_critique_and_tour_round_trips_keep_canonical_validation() {
    let critique = critique_schema();
    let finding = json!({"id":"f","path":"a.rs","side":"additions","line":1,"startLine":null,"severity":"bug","title":"Bug","body":"Breaks.","suggestion":null});
    let raw = json!({"summary":"Review.","findings":[finding]});
    let answer = prepared("codex", &critique, &raw, STRICT).unwrap();
    assert!(answer["findings"][0].get("startLine").is_none());
    assert!(answer["findings"][0].get("suggestion").is_none());
    assert_eq!(answer["findings"][0]["line"], 1);
    for binary in ["claude", "pi"] {
        assert!(prepared(binary, &critique, &raw, STRICT).is_err());
    }
    let plan = json!({"title":"Tour","overview":"Flow.","stops":[{"title":"Entry","purpose":"Explain.","files":["a.rs"],"notes":"Read."}],"glossary":[{"term":"Entry","meaning":"Start."}]});
    let stop = json!({"title":"Entry","purpose":"Explain.","files":["a.rs"],"takeaway":"Keep.","blocks":[{"kind":"text","text":"Prose."},{"kind":"shape","text":"Flow.","lang":"calls","body":"entry"},{"kind":"diagram","text":"Actors.","body":"graph TD"}],"sources":[{"path":"a.rs","line":1,"why":"Entry."}]});
    for (schema, answer) in [(tour_plan_schema(), plan), (stop_schema(), stop)] {
        assert_strict_objects(&for_cli("codex", &schema));
        for binary in ["codex", "claude", "pi"] {
            assert_eq!(prepared(binary, &schema, &answer, STRICT).unwrap(), answer);
        }
        let mut invalid = answer.clone();
        invalid["title"] = Value::Null;
        assert!(prepared("codex", &schema, &invalid, STRICT).is_err());
    }
}

#[test]
fn codex_cleanup_never_repairs_wrong_types_or_required_nulls() {
    let schema = json!({"type":"object","properties":{"required":{"type":"null"},"optional":{"type":"string"},"items":{"type":"array","items":{"type":"string"}}},"required":["required","items"]});
    let null_required = json!({"required":null,"optional":null,"items":[]});
    // CLI dropNulls removes required null properties too; canonical validation must reject.
    assert!(prepared("codex", &schema, &null_required, STRICT).is_err());
    assert_eq!(
        prepared(
            "claude",
            &schema,
            &json!({"required":null,"items":[]}),
            STRICT
        )
        .unwrap(),
        json!({"required":null,"items":[]})
    );
    let grouping = grouping_schema();
    for invalid in [
        json!({"overallSummary":null,"groups":[]}),
        json!({"overallSummary":"Bad.","groups":[null]}),
        json!({"overallSummary":"Bad.","groups":"wrong"}),
        json!({"overallSummary":"Bad.","groups":[{"key":"a","label":"A","category":"invalid","filePaths":[],"summary":null,"children":null}]}),
    ] {
        assert!(prepared("codex", &grouping, &invalid, STRICT).is_err());
    }
    assert!(prepare_answer("codex", &grouping, "not JSON", STRICT).is_err());
}
