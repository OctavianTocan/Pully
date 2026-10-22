use crate::AppState;
use anyhow::{ensure, Context, Result};
use reqwest::Method;
use serde_json::Value;
use std::{future::Future, pin::Pin};

pub(crate) type GitHubFuture<'a> =
    Pin<Box<dyn Future<Output = Result<(u16, Value, String)>> + Send + 'a>>;

pub(crate) trait GitHubRead: Send + Sync {
    fn raw<'a>(
        &'a self,
        method: Method,
        path: &'a str,
        body: Option<Value>,
        accept: &'a str,
    ) -> GitHubFuture<'a>;
}

pub(crate) struct StateGitHubRead<'a>(pub &'a AppState);

impl GitHubRead for StateGitHubRead<'_> {
    fn raw<'a>(
        &'a self,
        method: Method,
        path: &'a str,
        body: Option<Value>,
        accept: &'a str,
    ) -> GitHubFuture<'a> {
        Box::pin(super::github::raw(self.0, method, path, body, accept))
    }
}

pub(crate) async fn json(
    read: &dyn GitHubRead,
    method: Method,
    path: &str,
    body: Option<Value>,
) -> Result<Value> {
    let (code, _, text) = read
        .raw(method, path, body, "application/vnd.github+json")
        .await?;
    ensure!(
        (200..300).contains(&code),
        "GitHub request failed (HTTP {code})"
    );
    if text.is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(&text).context("GitHub returned invalid JSON")
}
