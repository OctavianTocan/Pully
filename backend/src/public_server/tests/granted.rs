use super::*;
use crate::personal_auth::{account_key, GrantedSessions, PersonalAuth, SessionAuthority};
use futures_util::future::BoxFuture;

const RPC: &str = "/workspace/api/rpc";

// The sign-in object's side, called directly as the Worker calls it through its object.
struct Direct(PersonalAuth);
impl SessionAuthority for Direct {
    fn sign_out<'a>(&'a self, bearer: &'a str) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move { Ok(self.0.sign_out(bearer).await?) })
    }
    fn account<'a>(&'a self, account_id: &'a str) -> BoxFuture<'a, anyhow::Result<Option<String>>> {
        Box::pin(async move {
            Ok(match self.0.account_grant(account_id).await? {
                Some((_, grant)) => Some(grant.header()?),
                None => None,
            })
        })
    }
}

struct Account {
    auth: PersonalAuth,
    granted: Arc<GrantedSessions>,
    server: Server,
}

impl Fixture {
    // One account's own object over the fixture's vault, as the Worker builds it.
    async fn account_object(&self, account_id: &str) -> Account {
        let origin = url::Url::parse(&format!("{}/", self._upstream.origin)).unwrap();
        let configured = config::load(configuration(&self._root))
            .await
            .unwrap()
            .unwrap();
        let (_, auth) = PersonalAuthHost::fixture(configured.auth, origin.clone())
            .await
            .unwrap()
            .into_parts();
        let auth = auth.unwrap();
        let granted = Arc::new(GrantedSessions::new(
            Arc::new(Direct(auth.clone())),
            account_key(account_id),
        ));
        let root = configured
            .tenant_root
            .join(format!("account-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let tenants = crate::identity::TenantStates::new(root, 256).unwrap();
        let router = personal::fixture_account_router(granted.clone(), tenants, origin).unwrap();
        Account {
            auth,
            granted,
            server: Server::new(router).await,
        }
    }
}

impl Account {
    async fn grant(&self, session: &str, workspace: bool) -> String {
        self.auth
            .grant_session(session, workspace)
            .await
            .unwrap()
            .1
            .header()
            .unwrap()
    }
    async fn rpc(&self, session: &str, grant: Option<&str>) -> reqwest::Response {
        let mut request = self
            .server
            .client
            .post(format!("{}{RPC}", self.server.origin))
            .bearer_auth(session)
            .json(&json!({"name":"account-me","args":{}}));
        if let Some(grant) = grant {
            request = request.header(crate::personal_auth::GRANT_HEADER, grant);
        }
        request.send().await.unwrap()
    }
}

#[tokio::test]
async fn account_object_acts_only_on_the_callers_own_forwarded_session() {
    let fixture = Fixture::new().await;
    let (public, status) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let account_id = status["accountId"].as_str().unwrap().to_owned();
    let account = fixture.account_object(&account_id).await;

    // Without the sign-in object's grant, the bearer alone is refused.
    assert_eq!(
        account.rpc(&session, None).await.status(),
        StatusCode::UNAUTHORIZED
    );
    let grant = account.grant(&session, true).await;
    let me = account.rpc(&session, Some(&grant)).await;
    assert_eq!(me.status(), StatusCode::OK);
    assert!(!me.text().await.unwrap().contains("fixture-access"));
    assert_eq!(
        account.granted.account_id().as_deref(),
        Some(account_id.as_str())
    );

    // Another bearer cannot borrow the grant, and an unconfirmed-style grant (resolved
    // without the workspace check) does not open the workspace.
    assert_eq!(
        account.rpc(&public, Some(&grant)).await.status(),
        StatusCode::UNAUTHORIZED
    );
    let read_grant = account.grant(&session, false).await;
    assert_eq!(
        account.rpc(&session, Some(&read_grant)).await.status(),
        StatusCode::UNAUTHORIZED
    );
    let capabilities = account
        .server
        .client
        .get(format!(
            "{}{ACCOUNT_CAPABILITIES_PATH}",
            account.server.origin
        ))
        .bearer_auth(&session)
        .header(crate::personal_auth::GRANT_HEADER, &read_grant)
        .send()
        .await
        .unwrap();
    assert_eq!(capabilities.status(), StatusCode::OK);

    // Another account's object refuses the grant.
    let other = fixture.account_object("another-account").await;
    assert_ne!(
        other.rpc(&session, Some(&grant)).await.status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn account_object_sessions_end_with_the_sign_in_objects() {
    let fixture = Fixture::new().await;
    let (public, status) = fixture.sign_in("a").await;
    let (session, _) = fixture.upgrade(&public, "a-upgrade").await;
    let account_id = status["accountId"].as_str().unwrap().to_owned();
    let account = fixture.account_object(&account_id).await;
    let (current, grant) = account.auth.grant_session(&session, true).await.unwrap();
    let header = grant.header().unwrap();
    let mut headers = HeaderMap::new();
    headers.insert(crate::personal_auth::GRANT_HEADER, header.parse().unwrap());
    let held = account
        .granted
        .authenticate(&session, &headers, true)
        .unwrap();
    assert!(held.identity().is_ok());

    // A notice about an older credential leaves the current one; one about it ends it.
    account
        .granted
        .retire(current.hash(), Some(&uuid::Uuid::new_v4().to_string()));
    assert!(held.identity().is_ok());
    account
        .granted
        .retire(current.hash(), Some(grant.credential()));
    assert!(held.identity().is_err());

    // Background work gets the account's confirmed private session from the sign-in object.
    let background = account.granted.background(&account_id).await.unwrap();
    assert!(background.identity().is_ok());
    assert!(account
        .granted
        .background("another-account")
        .await
        .is_none());

    // Signing out through the account object signs out at the sign-in object and ends what
    // the account object holds.
    let held = account
        .granted
        .authenticate(&session, &headers, true)
        .unwrap();
    let signed_out = account
        .server
        .client
        .post(format!("{}{RPC}", account.server.origin))
        .bearer_auth(&session)
        .header(crate::personal_auth::GRANT_HEADER, &header)
        .json(&json!({"name":"account-sign-out","args":{}}))
        .send()
        .await
        .unwrap();
    assert_eq!(signed_out.status(), StatusCode::OK);
    assert!(held.identity().is_err());
    assert!(account.auth.grant_session(&session, true).await.is_err());
    assert!(current.identity().is_err());
    assert!(account.granted.background(&account_id).await.is_none());
}
