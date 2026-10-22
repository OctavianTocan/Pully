use super::*;
use std::time::Duration;
use tokio::sync::Notify;

#[derive(Default)]
struct HeldAuthorizer {
    hold: AtomicBool,
    entered: Notify,
    release: Notify,
}
impl AiRequestAuthorizer for HeldAuthorizer {
    fn authorize_request(
        &self,
        request: reqwest::RequestBuilder,
    ) -> AiFuture<'_, reqwest::RequestBuilder> {
        Box::pin(async move {
            let target = request
                .try_clone()
                .ok_or_else(|| anyhow::anyhow!("Invalid request"))?
                .build()?;
            ensure!(
                target.method() == reqwest::Method::POST
                    && target.url().as_str() == "https://provider.example/responses",
                "Wrong inference target"
            );
            self.entered.notify_one();
            if self.hold.load(Ordering::SeqCst) {
                self.release.notified().await;
            }
            Ok(request.bearer_auth("fixture-owned-access"))
        })
    }
}
fn reference(revision: u64) -> AiConnectionRef {
    AiConnectionRef {
        connection_id: "fixture-connection".into(),
        revision,
        provider_kind: AiProviderKind::ChatGPTSubscription,
    }
}
fn request() -> reqwest::RequestBuilder {
    reqwest::Client::new().post("https://provider.example/responses")
}

#[tokio::test]
async fn local_owners_are_distinct_without_github_or_provider_authority() {
    let a = AiPrincipalSource::local();
    let b = AiPrincipalSource::local();
    assert_ne!(a.owner_id(), b.owner_id());
    a.ensure_current().unwrap();
    b.ensure_current().unwrap();
    a.retire();
    assert!(a.ensure_current().is_err());
    b.ensure_current().unwrap();
}

#[tokio::test]
async fn captured_connection_metadata_and_secret_authorization_stay_separate() {
    let owner = AiPrincipalSource::verified("verified-pully-owner".into()).unwrap();
    let authority =
        AiConnectionAuthority::new(owner, reference(3), Arc::new(HeldAuthorizer::default()))
            .unwrap();
    let lease = authority.capture().unwrap();
    assert_eq!(lease.connection(), &reference(3));
    assert_eq!(lease.principal().owner_id(), "verified-pully-owner");
    let authorized = lease.authorize_request(request()).await.unwrap();
    assert!(!format!("{authorized:?}").contains("fixture-owned-access"));
    let built = authorized.build().unwrap();
    assert_eq!(
        built.headers()[reqwest::header::AUTHORIZATION],
        "Bearer fixture-owned-access"
    );
    assert!(built.headers()[reqwest::header::AUTHORIZATION].is_sensitive());
    assert!(lease
        .authorize_request(reqwest::Client::new().post("https://other.example/responses"))
        .await
        .is_err());
    assert!(lease
        .authorize_request(reqwest::Client::new().get("https://provider.example/responses"))
        .await
        .is_err());
}

#[tokio::test]
async fn source_retirement_cancels_a_held_authorization_without_a_new_token() {
    let owner = AiPrincipalSource::local();
    let authorizer = Arc::new(HeldAuthorizer::default());
    authorizer.hold.store(true, Ordering::SeqCst);
    let authority =
        AiConnectionAuthority::new(owner.clone(), reference(1), authorizer.clone()).unwrap();
    let lease = authority.capture().unwrap();
    let observed = lease.clone();
    let task = tokio::spawn(async move { observed.authorize_request(request()).await });
    authorizer.entered.notified().await;
    owner.retire();
    assert!(tokio::time::timeout(Duration::from_secs(1), task)
        .await
        .unwrap()
        .unwrap()
        .is_err());
    assert!(lease.ensure_current().await.is_err());
    assert!(authority.capture().is_err());
    authorizer.release.notify_one();
}

#[tokio::test]
async fn retired_connection_cannot_adopt_its_replacement_or_revoke_another_owner() {
    let owner = AiPrincipalSource::local();
    let authorizer = Arc::new(HeldAuthorizer::default());
    let old = AiConnectionAuthority::new(owner.clone(), reference(1), authorizer.clone()).unwrap();
    let captured = old.capture().unwrap();
    old.retire();
    let replacement = AiConnectionAuthority::new(owner, reference(2), authorizer.clone()).unwrap();
    let new = replacement.capture().unwrap();
    let other_owner = AiPrincipalSource::local();
    let other = AiConnectionAuthority::new(other_owner, reference(1), authorizer).unwrap();
    assert!(captured.ensure_current().await.is_err());
    assert_eq!(captured.connection().revision, 1);
    new.ensure_current().await.unwrap();
    other.capture().unwrap().ensure_current().await.unwrap();
    drop(old);
    new.ensure_current().await.unwrap();
}

#[tokio::test]
async fn retirement_before_subscription_is_observed_synchronously() {
    let owner = AiPrincipalSource::local();
    let authority = AiConnectionAuthority::new(
        owner.clone(),
        reference(1),
        Arc::new(HeldAuthorizer::default()),
    )
    .unwrap();
    let lease = authority.capture().unwrap();
    owner.retire();
    assert!(owner.subscribe_retirement().is_err());
    tokio::time::timeout(Duration::from_secs(1), lease.retired())
        .await
        .unwrap()
        .unwrap();
}

#[tokio::test]
async fn dropping_connection_owner_retires_captured_observers() {
    let authority = AiConnectionAuthority::new(
        AiPrincipalSource::local(),
        reference(1),
        Arc::new(HeldAuthorizer::default()),
    )
    .unwrap();
    let lease = authority.capture().unwrap();
    drop(authority);
    assert!(lease.ensure_current().await.is_err());
    tokio::time::timeout(Duration::from_secs(1), lease.retired())
        .await
        .unwrap()
        .unwrap();
}

#[test]
fn invalid_owner_or_connection_metadata_cannot_mint_a_lease() {
    for invalid in ["", "../owner", "owner@host", "owner with spaces"] {
        assert!(AiPrincipalSource::verified(invalid.into()).is_err());
    }
    let invalid = AiConnectionAuthority::new(
        AiPrincipalSource::local(),
        reference(0),
        Arc::new(HeldAuthorizer::default()),
    );
    assert!(invalid.is_err());
    let mut invalid_ref = reference(1);
    invalid_ref.connection_id = "../another-owner".into();
    assert!(AiConnectionAuthority::new(
        AiPrincipalSource::local(),
        invalid_ref,
        Arc::new(HeldAuthorizer::default()),
    )
    .is_err());
}
