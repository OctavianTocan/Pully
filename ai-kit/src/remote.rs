//! AI work sent to another process. A hosted server in a Worker cannot run programs, so it sends
//! each CLI run, model list, sign-in and sign-out to the agent container of the login it is for,
//! where [`crate::runner`] does them with the same code a native server uses. A native server
//! never sets a remote and runs the CLIs itself.
use crate::config::Preparation;
use crate::login::AgentLogin;
use anyhow::{bail, Context, Result};
use futures_util::{future::BoxFuture, stream::BoxStream, StreamExt};
use serde_json::{json, Value};
use std::sync::{Arc, OnceLock};

/// A response body, read as it arrives.
pub type Body = BoxStream<'static, std::io::Result<Vec<u8>>>;

/// Reaches the agent container of a login: a Worker sends each call to that login's own Durable
/// Object, which owns the container.
pub trait AgentRemote: Send + Sync {
    /// Posts the JSON `body` to `path` on the agent container of `login`, and returns the
    /// response body once its headers arrive.
    fn post(
        &self,
        login: &AgentLogin,
        path: &'static str,
        body: Vec<u8>,
    ) -> BoxFuture<'static, Result<Body>>;
}

static REMOTE: OnceLock<Arc<dyn AgentRemote>> = OnceLock::new();

/// Sends all AI work in this process to `remote`. A Worker calls it once at start.
pub fn set(remote: Arc<dyn AgentRemote>) -> Result<()> {
    REMOTE
        .set(remote)
        .map_err(|_| anyhow::anyhow!("The agent remote is already set"))
}

/// The remote AI work goes to, when this process has one.
pub fn get() -> Option<&'static Arc<dyn AgentRemote>> {
    REMOTE.get()
}

/// How a login is named on the wire: its account ID, or `null` for the server's own.
pub fn login_json(login: &AgentLogin) -> Value {
    match login {
        AgentLogin::Server => json!({"account": null}),
        AgentLogin::Account(id) => json!({"account": id}),
    }
}

/// The login a wire body names.
pub fn login_from(value: &Value) -> Result<AgentLogin> {
    match &value["login"]["account"] {
        Value::Null => Ok(AgentLogin::Server),
        Value::String(id) if !id.is_empty() && id.len() <= 256 => {
            Ok(AgentLogin::Account(id.clone()))
        }
        _ => bail!("Invalid agent login"),
    }
}

/// Reads `body` as lines, each passed to `on_line`; a final line without a newline counts.
async fn lines(mut body: Body, mut on_line: impl FnMut(&str) -> Result<bool>) -> Result<()> {
    const LIMIT: usize = 16 * 1024 * 1024;
    let mut pending = Vec::new();
    while let Some(chunk) = body.next().await {
        pending.extend_from_slice(&chunk.context("The agent container stopped answering")?);
        while let Some(at) = pending.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = pending.drain(..=at).collect();
            let line = std::str::from_utf8(&line[..at]).context("Invalid agent output")?;
            if !line.is_empty() && on_line(line)? {
                return Ok(());
            }
        }
        if pending.len() > LIMIT {
            bail!("The agent container's answer is too large");
        }
    }
    let line = std::str::from_utf8(&pending).context("Invalid agent output")?;
    if !line.is_empty() {
        on_line(line)?;
    }
    Ok(())
}

/// Calls `path` with `body` and returns its `ok` value, or its `error` as the error.
pub async fn call(login: &AgentLogin, path: &'static str, mut body: Value) -> Result<Value> {
    let remote = get().context("No agent container is configured")?;
    body["login"] = login_json(login);
    let response = remote.post(login, path, serde_json::to_vec(&body)?).await?;
    let mut answer = None;
    lines(response, |line| {
        answer = Some(serde_json::from_str::<Value>(line).context("Invalid agent answer")?);
        Ok(true)
    })
    .await?;
    let answer = answer.context("The agent container gave no answer")?;
    if let Some(error) = answer["error"].as_str() {
        bail!("{error}");
    }
    answer.get("ok").cloned().context("Invalid agent answer")
}

/// `run_agent_prepared` in the login's agent container: its activity reaches `sink` as it comes,
/// and cancelling stops the run there.
pub(crate) async fn run(
    request: Value,
    login: &AgentLogin,
    mut cancel: tokio::sync::watch::Receiver<bool>,
    sink: crate::process::EventSink,
    preparation: Preparation,
) -> Result<(String, Value)> {
    let remote = get().context("No agent container is configured")?;
    let id = uuid::Uuid::new_v4().to_string();
    let body = json!({
        "id": id, "request": request, "login": login_json(login),
        "preparation": preparation.0,
    });
    let follow = async {
        let response = remote
            .post(login, "/run", serde_json::to_vec(&body)?)
            .await?;
        let mut outcome = None;
        lines(response, |line| {
            let value: Value = serde_json::from_str(line).context("Invalid agent output")?;
            if let Some(event) = value.get("event") {
                sink(event.clone());
                return Ok(false);
            }
            outcome = Some(value);
            Ok(true)
        })
        .await?;
        let outcome = outcome.context("The agent container stopped before the run ended")?;
        if let Some(error) = outcome["error"].as_str() {
            bail!("{error}");
        }
        let answer = outcome["answer"].as_str().context("Invalid agent answer")?;
        Ok((answer.to_owned(), outcome["usage"].clone()))
    };
    tokio::select! {
        result = follow => result,
        _ = async { loop { if *cancel.borrow_and_update() || cancel.changed().await.is_err() { break; } } } => {
            let _ = call(login, "/cancel", json!({"id": id})).await;
            bail!("AI task cancelled")
        }
    }
}
