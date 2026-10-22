use super::*;

#[tokio::test]
async fn ce_private_later_denial_or_replacement_retires_an_older_final_200() {
    for route in ["selected", "inbox"] {
        for change in ["denial", "replacement"] {
            let fixture = Fixture::new().await;
            let (a, _) = fixture.sign_in("a").await;
            let (a, _) = fixture.upgrade(&a, "a-upgrade").await;
            let (b, _) = fixture.sign_in("b").await;
            let (b, _) = fixture.upgrade(&b, "b-upgrade").await;
            fixture.github.private.store(true, Ordering::SeqCst);
            fixture.github.hold_repo_at.store(2, Ordering::SeqCst);
            let selected = "/api/personal/v1/github/account/repositories/reader-a/project/pulls";
            let path = if route == "selected" {
                selected
            } else {
                "/api/personal/v1/github/account/pulls"
            };
            let request = fixture
                .server
                .client
                .get(format!("{}{path}", fixture.server.origin))
                .bearer_auth(&a);
            let older = tokio::spawn(async move { request.send().await.unwrap() });
            tokio::time::timeout(
                Duration::from_secs(2),
                fixture.github.repo_entered.notified(),
            )
            .await
            .unwrap();
            match change {
                "denial" => fixture.github.deny_a_only.store(true, Ordering::SeqCst),
                _ => fixture.github.replacement.store(true, Ordering::SeqCst),
            }
            let newer = fixture.server.get(selected, Some(&a)).await;
            assert!(newer.status().is_client_error());
            fixture.github.repo_release.notify_one();
            let response = tokio::time::timeout(Duration::from_secs(2), older)
                .await
                .unwrap()
                .unwrap();
            assert!(
                response.status().is_client_error(),
                "older final200 published after {change} in {route}"
            );
            assert!(!response
                .text()
                .await
                .unwrap()
                .contains("current public title"));
            // The same URL/connection ID is never a global tenant fence.
            fixture.github.replacement.store(false, Ordering::SeqCst);
            let other = fixture
                .server
                .get(
                    "/api/personal/v1/github/account/repositories/reader-b/project/pulls",
                    Some(&b),
                )
                .await;
            assert_eq!(other.status(), StatusCode::OK);
            assert_eq!(
                other.json::<Value>().await.unwrap()["repository"]["id"],
                702
            );
        }
    }
}

#[tokio::test]
async fn ce_inbox_uses_fresh_draft_after_search_index_lags_ready_transition() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    fixture
        .github
        .stale_search_draft
        .store(true, Ordering::SeqCst);
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.json::<Value>().await.unwrap()["pulls"][0]["draft"],
        false
    );
}

#[tokio::test]
async fn ce_inbox_accepts_search_rows_without_the_optional_draft_field() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    fixture
        .github
        .omit_search_draft
        .store(true, Ordering::SeqCst);
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.json::<Value>().await.unwrap()["pulls"][0]["draft"],
        false
    );
}

#[tokio::test]
async fn ce_inbox_gets_authoritative_pr_draft_when_fresh_issue_omits_it() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    fixture
        .github
        .omit_search_draft
        .store(true, Ordering::SeqCst);
    fixture
        .github
        .omit_issue_draft
        .store(true, Ordering::SeqCst);
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.json::<Value>().await.unwrap()["pulls"][0]["draft"],
        true
    );
    assert!(fixture
        .github
        .requests
        .lock()
        .unwrap()
        .iter()
        .any(|(path, _)| path.ends_with("/pulls/7")));
}

#[tokio::test]
async fn ce_inbox_fallback_pr_cannot_change_the_captured_repository_identity() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    fixture
        .github
        .omit_search_draft
        .store(true, Ordering::SeqCst);
    fixture
        .github
        .omit_issue_draft
        .store(true, Ordering::SeqCst);
    fixture.github.wrong_pull_base.store(true, Ordering::SeqCst);
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::FORBIDDEN);
    assert!(!response
        .text()
        .await
        .unwrap()
        .contains("current public title"));
}

#[tokio::test]
async fn ce_cached_session_uses_its_current_immutable_subject_after_login_reassignment() {
    let fixture = Fixture::new().await;
    let (session, status) = fixture.sign_in("a").await;
    *fixture.github.current_a_login.lock().unwrap() = Some("renamed-a".into());
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert_eq!(response.status(), StatusCode::OK);
    let page: Value = response.json().await.unwrap();
    assert_eq!(page["githubId"], 42);
    assert_eq!(page["accountId"], status["accountId"]);
    assert_eq!(page["pulls"][0]["repository"]["id"], 701);
    assert_eq!(
        page["pulls"][0]["repository"]["fullName"],
        "renamed-a/project"
    );
    assert!(fixture
        .github
        .queries
        .lock()
        .unwrap()
        .iter()
        .any(|query| query.contains("review-requested:renamed-a")));
    assert!(!fixture
        .github
        .queries
        .lock()
        .unwrap()
        .iter()
        .any(|query| query.contains("review-requested:reader-a")));
}

#[tokio::test]
async fn ce_login_change_during_search_cannot_publish_the_old_relation() {
    let fixture = Fixture::new().await;
    let (session, _) = fixture.sign_in("a").await;
    fixture
        .github
        .rename_during_search
        .store(true, Ordering::SeqCst);
    let response = fixture.server.get(PULLS_PATH, Some(&session)).await;
    assert!(response.status().is_client_error());
    assert!(!response
        .text()
        .await
        .unwrap()
        .contains("current public title"));
}
