use super::*;

fn graphql_response() -> Value {
    json!({"data":{"search":{
        "issueCount":1,
        "pageInfo":{"hasNextPage":true,"endCursor":"next:opaque/=="},
        "nodes":[{
            "number":7,"title":"Example","url":"https://github.com/o/r/pull/7",
            "isDraft":false,"author":null,"assignees":{"nodes":[]},
            "labels":{"nodes":[
                {"name":"empty","color":"ffffff","description":null,"id":"discard"},
                {"name":"described","color":"000000","description":"Useful"},
                {"name":"missing","color":"123456"},
                {"name":"invalid","color":"654321","description":42}
            ]},
            "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
            "comments":{"totalCount":0},"closingIssuesReferences":{"totalCount":0},
            "additions":1,"deletions":2,"changedFiles":1
        }]
    }}})
}

#[tokio::test]
async fn authenticated_graphql_pages_normalize_labels_and_preserve_cursors() {
    for next in [None, Some("cursor:opaque/==")] {
        let args = json!({"owner":"o","repo":"r","next":next});
        let page = fetch_repo_page_with(&args, true, |method, path, body| async move {
            assert_eq!(method, reqwest::Method::POST);
            assert_eq!(path, "/graphql");
            assert_eq!(body.unwrap()["variables"]["cursor"], json!(next));
            Ok(graphql_response())
        })
        .await
        .unwrap();
        assert_eq!(
            page,
            json!({
                "totalCount":1,"next":"next:opaque/==","items":[{
                    "number":7,"title":"Example","url":"https://github.com/o/r/pull/7",
                    "state":"open","assignees":[],
                    "labels":[
                        {"name":"empty","color":"ffffff"},
                        {"name":"described","color":"000000","description":"Useful"},
                        {"name":"missing","color":"123456"},
                        {"name":"invalid","color":"654321"}
                    ],
                    "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
                    "comments":0,"linkedIssues":0,"additions":1,"deletions":2,"changedFiles":1
                }]
            })
        );
    }
    assert!(repo_labels(&Value::Null).is_empty());
}

#[tokio::test]
async fn fresh_cached_rest_continuation_stays_rest_after_authentication() {
    let root = std::env::temp_dir().join(format!("pulls-legacy-page-{}", uuid::Uuid::new_v4()));
    let state = AppState::new(root.clone(), root.clone()).unwrap();
    let args = json!({"owner":"o","repo":"r"});
    let cached = json!({"totalCount":201,"items":[],"next":"2","cachedAt":now()});
    state.store.set(&repo_key(&args), &cached).unwrap();
    let first = repo_pulls_with(&state, &args, |_, _| async {
        panic!("a fresh persisted first page must not fetch")
    })
    .await
    .unwrap();
    assert_eq!(first, cached);
    let page = repo_pulls_with(
        &state,
        &json!({"owner":"o","repo":"r","next":first["next"]}),
        |_, args| async move {
            fetch_repo_page_with(&args, true, |method, path, body| async move {
                assert_eq!(method, reqwest::Method::GET);
                assert_eq!(path, "/search/issues?q=repo%3Ao%2Fr%20is%3Apr%20is%3Aopen&sort=updated&order=desc&per_page=100&page=2");
                assert!(body.is_none());
                Ok(json!({"total_count":201,"items":vec![json!({"number":101});100]}))
            })
            .await
        },
    )
    .await
    .unwrap();
    assert_eq!(page["next"], "3");
    assert_eq!(page["items"].as_array().unwrap().len(), 100);
    assert_eq!(state.store.get(&repo_key(&args)).unwrap(), Some(cached));
    std::fs::remove_dir_all(root).unwrap();
}

#[tokio::test]
async fn numeric_rest_continuations_keep_page_limits_and_errors() {
    for next in ["0", "11", "999999999999999999999999999999999999"] {
        let error = fetch_repo_page_with(
            &json!({"owner":"o","repo":"r","next":next}),
            true,
            |_, _, _| async { panic!("invalid REST pages must fail before requesting") },
        )
        .await
        .unwrap_err();
        assert_eq!(error.to_string(), "Invalid continuation");
    }
    let last = fetch_repo_page_with(
        &json!({"owner":"o","repo":"r","next":"10"}),
        true,
        |method, path, _| async move {
            assert_eq!(method, reqwest::Method::GET);
            assert!(path.ends_with("&page=10"));
            Ok(json!({"total_count":1001,"items":vec![json!({"number":999});100]}))
        },
    )
    .await
    .unwrap();
    assert!(last.get("next").is_none());
    let error = fetch_repo_page_with(
        &json!({"owner":"o","repo":"r","next":"2"}),
        true,
        |method, _, _| async move {
            assert_eq!(method, reqwest::Method::GET);
            Ok(json!({"incomplete_results":true,"total_count":201,"items":[]}))
        },
    )
    .await
    .unwrap_err();
    assert_eq!(
        error.to_string(),
        "GitHub search is incomplete; retry later"
    );
}
