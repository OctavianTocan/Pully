use super::*;
use crate::agent_login::AgentLogin;

fn session(headers: &HeaderMap) -> Option<&str> {
    headers.get("x-pully-session").and_then(|v| v.to_str().ok())
}
pub(super) async fn call(
    app: &Arc<AppState>,
    headers: &HeaderMap,
    name: &str,
    args: &Value,
) -> Option<Response> {
    if !matches!(
        name,
        "account-status"
            | "account-sign-in"
            | "account-google-sign-in"
            | "account-sign-out"
            | "account-me"
            | "agent-keys"
            | "agent-key-create"
            | "agent-key-revoke"
            | "account-settings"
            | "account-settings-merge"
            | "posted-list"
            | "posted-diff"
            | "posted-delete"
            | "feedback-send"
            | "feedback-list"
    ) {
        return None;
    }
    let has_account = match app.ensure_account().await {
        Ok(v) => v,
        Err(e) => return Some(reply(Err(e))),
    };
    let google_client_id = crate::google_sign_in::client_id();
    if name == "account-status" {
        return Some(reply(Ok(account_status(
            has_account,
            google_client_id.as_deref(),
        ))));
    }
    if name == "account-google-sign-in" {
        return Some(google_sign_in(app, args, google_client_id.as_deref()).await);
    }
    if name == "account-sign-in" {
        if !has_account {
            return Some(error(StatusCode::UNAUTHORIZED, "Email and password is only for the test account, and this server has none. Sign in with Google."));
        }
        return Some(
            match app
                .accounts
                .sign_in(text(args, "email"), text(args, "password"))
                .await
            {
                Ok(crate::account::SignIn::Signed { session, account }) => {
                    reply(Ok(json!({"session":session,"email":account.email})))
                }
                Ok(crate::account::SignIn::Error { error: "wait" }) => error(
                    StatusCode::TOO_MANY_REQUESTS,
                    "Too many wrong passwords. Wait 15 minutes, then try again.",
                ),
                Ok(_) => error(
                    StatusCode::UNAUTHORIZED,
                    "That email and password don't match.",
                ),
                Err(e) => reply(Err(e)),
            },
        );
    }
    let user = match app.accounts.by_session_async(session(headers)).await {
        Ok(Some(user)) => user,
        Ok(None) => return Some(error(StatusCode::UNAUTHORIZED, "Sign in first.")),
        Err(e) => return Some(reply(Err(e))),
    };
    let result = if name == "account-sign-out" {
        app.accounts.sign_out_async(session(headers).unwrap_or("")).await.map(|_| json!(true))
    } else {
        owned_value(app, &user, name, args).await
    };
    if name == "posted-diff" && matches!(&result, Ok(Value::Null)) {
        return Some(error(
            StatusCode::NOT_FOUND,
            "There is no posted diff with that id.",
        ));
    }
    Some(reply(result))
}

/// Account operations shared by native sessions and canonical personal workspace sessions.
/// The caller supplies a server-resolved account; request arguments cannot select it.
pub(crate) async fn owned_value(app: &Arc<AppState>, user: &crate::account::Account, name: &str, args: &Value) -> Result<Value> {
    match name {
        "account-me" => Ok(json!(user)),
        "agent-keys" => Ok(json!(app.accounts.keys_async(&user.id).await?)),
        "agent-key-create" => Ok(json!(app.accounts.create_key_async(&user.id,text(args,"name")).await?)),
        "agent-key-revoke" => Ok(json!(app.accounts.revoke_key_async(&user.id,text(args,"id")).await?)),
        "account-settings" => Ok(json!({"values":app.accounts.settings_async(&user.id).await?})),
        "account-settings-merge" => {
            let values = crate::account::clean_settings(&args["values"]).context("Settings need `values`: { name: { value, at } }.")?;
            Ok(json!({"values":app.accounts.merge_settings_async(&user.id, values).await?}))
        },
        "posted-list" => Ok(json!(app.posted.list_async(&user.id).await?.into_iter().map(|meta| json!({"id":meta["id"],"title":meta["title"],"author":meta.get("author").unwrap_or(&meta["keyName"]),"key":meta["keyName"],"version":meta["version"],"createdAt":meta["createdAt"],"updatedAt":meta["updatedAt"],"files":meta["files"],"additions":meta["additions"],"deletions":meta["deletions"]})).collect::<Vec<_>>())),
        "posted-diff" => {
            let Some(mut opened) = app.posted.open_async(&user.id,text(args,"id")).await? else { return Ok(Value::Null); };
            opened["groups"] = crate::source::groups(&opened["diff"]); Ok(opened)
        },
        "posted-delete" => Ok(json!(app.posted.remove_async(&user.id,text(args,"id")).await?)),
        "feedback-send" => app.feedback.save_async(&user.id, args).await,
        "feedback-list" => Ok(json!({"items":app.feedback.list_async(&user.id, None).await?.into_iter().take(100).collect::<Vec<_>>()})),
        _ => unreachable!(),
    }
}

fn account_status(has_account: bool, google_client_id: Option<&str>) -> Value {
    let mut result = json!({"hasAccount":has_account || google_client_id.is_some()});
    if let Some(id) = google_client_id {
        result["googleClientId"] = json!(id);
    }
    result
}
async fn google_sign_in(app: &Arc<AppState>, args: &Value, client_id: Option<&str>) -> Response {
    let Some(client_id) = client_id else {
        return error(
            StatusCode::UNAUTHORIZED,
            "This server has no Google sign-in. Set PULLY_GOOGLE_CLIENT_ID on it.",
        );
    };
    let Some(email) = app
        .google_sign_in
        .email(text(args, "idToken"), client_id)
        .await
    else {
        return error(
            StatusCode::UNAUTHORIZED,
            "Google could not confirm that sign-in. Try again.",
        );
    };
    if !crate::google_sign_in::allowed(&email) {
        return error(
            StatusCode::FORBIDDEN,
            format!("{email} can't sign in to Pully. Pick another Google account."),
        );
    }
    match app.accounts.sign_in_with_google(&email).await {
        Ok(crate::account::SignIn::Signed { session, account }) => {
            reply(Ok(json!({"session":session,"email":account.email})))
        }
        Ok(_) => error(
            StatusCode::UNAUTHORIZED,
            "Google could not confirm that sign-in. Try again.",
        ),
        Err(e) => reply(Err(e)),
    }
}

/// Whose Claude Code and Codex sign-ins this request's AI work uses. A session header that no
/// longer names an account is refused, never answered with the server's own sign-in.
pub(super) async fn agent_login(
    app: &Arc<AppState>,
    headers: &HeaderMap,
) -> std::result::Result<AgentLogin, Response> {
    let Some(token) = session(headers).filter(|token| !token.is_empty()) else {
        return Ok(AgentLogin::Server);
    };
    app.ensure_account().await.map_err(|e| reply(Err(e)))?;
    match app.accounts.by_session_async(Some(token)).await {
        Ok(Some(user)) => Ok(AgentLogin::Account(user.id)),
        Ok(None) => Err(error(
            StatusCode::UNAUTHORIZED,
            "Your Pully sign-in ended. Sign in again.",
        )),
        Err(e) => Err(reply(Err(e))),
    }
}

/// Signing Claude Code and Codex in and out with each CLI's own sign-in. Only a signed-in Pully
/// account may change a sign-in; the server's own one is set up on the server with setup.sh.
pub(super) async fn agent_login_call(
    app: &Arc<AppState>,
    headers: &HeaderMap,
    name: &str,
    args: &Value,
) -> Option<Response> {
    if !matches!(
        name,
        "ai-login-status"
            | "ai-login-start"
            | "ai-login-finish"
            | "ai-login-cancel"
            | "ai-login-sign-out"
    ) {
        return None;
    }
    let login = match agent_login(app, headers).await {
        Ok(login) => login,
        Err(response) => return Some(response),
    };
    Some(agent_login_value(app, &login, name, args).await)
}

pub(crate) async fn agent_login_value(app: &Arc<AppState>, login: &AgentLogin, name: &str, args: &Value) -> Response {
    if name == "ai-login-status" {
        let result = crate::agent_login::status(&login).await.map(|mut value| {
            value["account"] = json!(matches!(login, AgentLogin::Account(_)));
            value
        });
        return reply(result);
    }
    if login == &AgentLogin::Server {
        return error(
            StatusCode::UNAUTHORIZED,
            "Sign in to your Pully account first, so the AI sign-in is yours.",
        );
    }
    let binary = match args.get("engine").and_then(Value::as_str) {
        Some("claude") => "claude",
        Some("codex") => "codex",
        _ => return reply(Err(anyhow::anyhow!("That method received invalid arguments."))),
    };
    let logins = &app.agent_logins;
    let result = match name {
        "ai-login-start" => logins.start(&login, binary).await,
        "ai-login-finish" => {
            let code = args.get("code").and_then(Value::as_str);
            logins.finish(&login, binary, code).await
        }
        "ai-login-cancel" => {
            logins.cancel(&login, binary).await;
            Ok(json!(true))
        }
        _ => {
            logins.cancel(&login, binary).await;
            crate::agent_login::sign_out(&login, binary).await.map(|_| json!(true))
        }
    };
    if name != "ai-login-start" {
        app.jobs.forget_catalogs(&login).await;
    }
    reply(result)
}

pub(super) async fn authorize_posted(
    app: &Arc<AppState>,
    headers: &HeaderMap,
    name: &str,
    args: &Value,
) -> std::result::Result<(), Response> {
    let key = requested_key(app, name, args)
        .await
        .map_err(|e| reply(Err(e)))?;
    if !is_posted(&key) {
        return Ok(());
    }
    app.ensure_account().await.map_err(|e| reply(Err(e)))?;
    let user = app
        .accounts
        .by_session_async(session(headers))
        .await
        .map_err(|e| reply(Err(e)))?
        .ok_or_else(|| error(StatusCode::UNAUTHORIZED, "Sign in first."))?;
    // A valid session alone cannot authorize another account's diff.
    let id = key.split_once(':').map(|(_, id)| id).unwrap_or("");
    if app
        .posted
        .meta_async(&user.id, id)
        .await
        .map_err(|e| reply(Err(e)))?
        .is_none()
    {
        return Err(error(
            StatusCode::NOT_FOUND,
            "There is no posted diff with that id.",
        ));
    }
    Ok(())
}

// Unscoped lists must never expose another account's posted diff or task.
pub(super) async fn filter_owned(
    app: &AppState,
    headers: &HeaderMap,
    value: Value,
) -> Result<Value> {
    let Value::Array(items) = value else {
        return Ok(value);
    };
    let accounts = app.accounts.clone();
    let posted = app.posted.clone();
    let token = session(headers).map(str::to_owned);
    crate::rt::spawn_blocking(move || {
        let account = accounts.by_session(token.as_deref())?;
        let mut visible = Vec::with_capacity(items.len());
        for item in items {
            let key = posted_reference(&item)
                .unwrap_or_else(|| text(&item, "key").replace(['/', '\\'], ":"));
            if !is_posted(&key)
                || match &account {
                    Some(account) => posted
                        .meta(
                            &account.id,
                            key.split_once(':').map(|(_, id)| id).unwrap_or(""),
                        )?
                        .is_some(),
                    None => false,
                }
            {
                visible.push(item);
            }
        }
        Ok(Value::Array(visible))
    })
    .await
    .context("Posted visibility task failed")?
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::test_support::HeldLock;

    #[tokio::test]
    async fn google_sign_in_route_preserves_status_errors_and_session_scope() {
        use crate::google_sign_in::tests::{claims, install_keys, token};
        let root = std::env::temp_dir().join(format!("google-route-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        install_keys(&app.google_sign_in).await;
        assert_eq!(account_status(false, None), json!({"hasAccount":false}));
        assert_eq!(account_status(true, None), json!({"hasAccount":true}));
        assert_eq!(
            account_status(false, Some("test-client")),
            json!({"hasAccount":true,"googleClientId":"test-client"})
        );
        for (args, client, expected, message) in [
            (
                json!({}),
                None,
                StatusCode::UNAUTHORIZED,
                "This server has no Google sign-in. Set PULLY_GOOGLE_CLIENT_ID on it.",
            ),
            (
                json!({"idToken":"bad"}),
                Some("test-client"),
                StatusCode::UNAUTHORIZED,
                "Google could not confirm that sign-in. Try again.",
            ),
            (
                json!({"idToken":token(&claims("other@example.com"))}),
                Some("test-client"),
                StatusCode::FORBIDDEN,
                "other@example.com can't sign in to Pully. Pick another Google account.",
            ),
        ] {
            let response = google_sign_in(&app, &args, client).await;
            assert_eq!(response.status(), expected);
            let value: Value = serde_json::from_slice(
                &axum::body::to_bytes(response.into_body(), 8192)
                    .await
                    .unwrap(),
            )
            .unwrap();
            assert_eq!(value, json!({"error":message}));
        }
        assert!(app.host.storage.keys("account:").unwrap().is_empty());
        let response = google_sign_in(
            &app,
            &json!({"idToken":token(&claims("tocanoctavian@gmail.com"))}),
            Some("test-client"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let value: Value = serde_json::from_slice(
            &axum::body::to_bytes(response.into_body(), 8192)
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(value["result"]["email"], "tocanoctavian@gmail.com");
        let session = value["result"]["session"].as_str().unwrap();
        assert!(app.accounts.by_session(Some(session)).unwrap().is_some());
        assert!(
            super::super::web_dispatch(&app, "account-google-sign-in", json!({}))
                .await
                .is_err()
        );
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test(flavor = "current_thread")]
    async fn account_http_operations_yield_under_storage_contention() {
        let root =
            std::env::temp_dir().join(format!("account-http-contention-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        app.account_seed.set(true).unwrap();
        let (user, session, created) = app.accounts.fixture_for_test();
        let mut headers = HeaderMap::new();
        headers.insert("x-pully-session", session.parse().unwrap());
        for (name, args) in [
            ("account-me", json!({})),
            ("agent-keys", json!({})),
            ("agent-key-create", json!({"name":"Second"})),
            ("account-settings", json!({})),
            (
                "account-settings-merge",
                json!({"values":{"theme":{"value":"dark","at":1}}}),
            ),
            ("agent-key-revoke", json!({"id":created.item.id})),
            (
                "feedback-send",
                json!({"text":"The chip jumps","screen":"home"}),
            ),
            ("feedback-list", json!({})),
        ] {
            let response = HeldLock::storage(&app.host.storage)
                .assert_yields(call(&app, &headers, name, &args))
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::OK, "{name}");
        }
        assert_eq!(
            app.accounts.settings(&user.id).unwrap()["theme"]["value"],
            "dark"
        );
        assert_eq!(
            app.feedback.list(&user.id, None).unwrap()[0]["text"],
            "The chip jumps"
        );
        let response = call(
            &app,
            &HeaderMap::new(),
            "feedback-send",
            &json!({"text":"x"}),
        )
        .await
        .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::UNAUTHORIZED,
            "feedback needs the session"
        );
        // Authentication itself needs only the Store lock; creation must also move
        // its subsequent account mutex acquisition off the single runtime thread.
        let response = app
            .accounts
            .held_for_test()
            .assert_yields(call(
                &app,
                &headers,
                "agent-key-create",
                &json!({"name":"Third"}),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let response = HeldLock::storage(&app.host.storage)
            .assert_yields(call(&app, &headers, "account-sign-out", &json!({})))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert!(app
            .accounts
            .by_session_async(Some(&session))
            .await
            .unwrap()
            .is_none());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn ai_sign_ins_belong_to_the_signed_in_account_only() {
        let root = std::env::temp_dir().join(format!("agent-login-{}", uuid::Uuid::new_v4()));
        let app = AppState::new(root.clone(), root.clone()).unwrap();
        app.host
            .storage
            .set(
                "account:person",
                &json!({"id":"person","email":"person@example.invalid","createdAt":"2026-10-23T00:00:00Z",
                    "password":{"salt":"","hash":"","iterations":1}}),
            )
            .unwrap();
        app.host
            .storage
            .set(
                &format!("account-session:{}", crate::account::sha256("person-session")),
                &json!({"accountId":"person"}),
            )
            .unwrap();
        let mut signed_in = HeaderMap::new();
        signed_in.insert("x-pully-session", "person-session".parse().unwrap());
        let mut ended = HeaderMap::new();
        ended.insert("x-pully-session", "ended-session".parse().unwrap());
        assert_eq!(agent_login(&app, &HeaderMap::new()).await.unwrap(), AgentLogin::Server);
        assert_eq!(
            agent_login(&app, &signed_in).await.unwrap(),
            AgentLogin::Account("person".into())
        );
        // An ended session never quietly falls back to the server's own sign-in.
        assert_eq!(
            agent_login(&app, &ended).await.unwrap_err().status(),
            StatusCode::UNAUTHORIZED
        );
        // Without a Pully account nothing can change a sign-in, and an unknown CLI is refused.
        let args = json!({"engine":"claude"});
        let refused = agent_login_call(&app, &HeaderMap::new(), "ai-login-start", &args).await;
        assert_eq!(refused.unwrap().status(), StatusCode::UNAUTHORIZED);
        let unknown = json!({"engine":"pi"});
        let refused = agent_login_call(&app, &signed_in, "ai-login-start", &unknown).await;
        assert_eq!(refused.unwrap().status(), StatusCode::BAD_REQUEST);
        assert!(agent_login_call(&app, &signed_in, "account-me", &args).await.is_none());
        std::fs::remove_dir_all(root).unwrap();
    }
}
