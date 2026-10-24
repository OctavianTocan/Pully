use super::*;
use std::time::Duration;

#[tokio::test]
async fn ce_public_single_read_rejects_name_replacement_before_pr_inventory() {
    let fixture = Fixture::start().await;
    let client = Arc::new(fixture.client());
    let pending = Arc::new(DeferredReply::default());
    fixture.state.lock().unwrap().pending_repository = Some(pending.clone());
    let old = crate::rt::spawn({
        let client = client.clone();
        async move { client.pulls("public-owner", "public-repo", 1).await }
    });
    crate::rt::timeout(Duration::from_secs(2), pending.arrived.notified())
        .await
        .unwrap();
    {
        let mut state = fixture.state.lock().unwrap();
        state.repository_id = 99;
        state.pull_title = "Replacement repository title".into();
    }
    pending.release.notify_one();
    assert_eq!(
        old.await
            .unwrap()
            .err()
            .expect("mixed repository identity must be rejected")
            .code,
        ErrorCode::RepositoryUnavailable
    );
    let current = client
        .pulls("public-owner", "public-repo", 1)
        .await
        .unwrap();
    assert_eq!(current.repository.id, 99);
    assert_eq!(current.pulls[0].title, "Replacement repository title");
}

#[tokio::test]
async fn ce_public_single_read_reconfirms_visibility_and_id_before_cache_publication() {
    for change in ["private", "replacement"] {
        let fixture = Fixture::start().await;
        let client = Arc::new(fixture.client());
        let pending = Arc::new(DeferredReply::default());
        fixture.state.lock().unwrap().pending_pull = Some(pending.clone());
        let old = crate::rt::spawn({
            let client = client.clone();
            async move { client.pulls("public-owner", "public-repo", 1).await }
        });
        crate::rt::timeout(Duration::from_secs(2), pending.arrived.notified())
            .await
            .unwrap();
        {
            let mut state = fixture.state.lock().unwrap();
            if change == "private" {
                state.private = true;
            } else {
                state.repository_id = 99;
            }
        }
        pending.release.notify_one();
        assert_eq!(
            old.await
                .unwrap()
                .err()
                .expect("changed authority must not enter public cache")
                .code,
            ErrorCode::RepositoryUnavailable,
            "{change}"
        );
        {
            let mut state = fixture.state.lock().unwrap();
            state.private = false;
            state.repository_id = 99;
            state.pull_title = "New authorized public snapshot".into();
        }
        let current = client
            .pulls("public-owner", "public-repo", 1)
            .await
            .unwrap();
        assert_eq!(current.repository.id, 99);
        assert_eq!(current.pulls[0].title, "New authorized public snapshot");
    }
}
