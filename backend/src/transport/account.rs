use super::*;

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
    ) {
        return None;
    }
    let has_account = match app.ensure_account().await {
        Ok(v) => v,
        Err(e) => return Some(reply(Err(e))),
    };
    if name == "account-status" {
        return Some(reply(Ok(json!({"hasAccount":has_account}))));
    }
    if name == "account-sign-in" {
        if !has_account {
            return Some(error(StatusCode::UNAUTHORIZED, "This server has no account yet. Set PULLY_ACCOUNT_EMAIL and PULLY_ACCOUNT_PASSWORD on it."));
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
    let result: Result<Value> = async {
        match name {
            "account-sign-out" => { app.accounts.sign_out_async(session(headers).unwrap_or("")).await?; Ok(json!(true)) },
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
            _ => unreachable!(),
        }
    }.await;
    if name == "posted-diff" && matches!(&result, Ok(Value::Null)) {
        return Some(error(
            StatusCode::NOT_FOUND,
            "There is no posted diff with that id.",
        ));
    }
    Some(reply(result))
}

pub(super) async fn authorize_posted(
    app: &Arc<AppState>,
    headers: &HeaderMap,
    name: &str,
    args: &Value,
) -> std::result::Result<(), Response> {
    let key = requested_key(app, name, args).map_err(|e| reply(Err(e)))?;
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
    tokio::task::spawn_blocking(move || {
        let account = accounts.by_session(token.as_deref())?;
        let mut visible = Vec::with_capacity(items.len());
        for item in items {
            let key = text(&item, "key").replace(['/', '\\'], ":");
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
}
