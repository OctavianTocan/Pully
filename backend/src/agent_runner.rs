//! The server inside the hosted agent container (`packages/worker/agent`): one Pully login's
//! Claude Code and Codex runs, model lists and sign-ins, done with the native server's own code
//! (`agent_process`, `agent_login`) as plain processes, since the container is the sandbox. Only
//! the login's `AgentHome` Durable Object can reach it; `agent_remote` is its client.
use crate::{
    agent_login::{self, AgentLogin},
    agent_process, agent_remote,
};
use anyhow::{bail, Context, Result};
use axum::{
    body::Body,
    extract::State,
    routing::{get, post},
    Json, Router,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tokio::sync::{mpsc, watch};

/// Baked into the agent image only, so the runner refuses to start anywhere else.
pub const CONTAINER_MARKER: &str = "/.pully-agent-container";
/// The largest CLI home kept between container starts.
const HOME_LIMIT: usize = 32 * 1024 * 1024;

struct Runner {
    /// The one login this container serves, by its volume name.
    volume: String,
    logins: agent_login::Logins,
    runs: Mutex<HashMap<String, watch::Sender<bool>>>,
}

/// Serves the runner on `address` for the login whose volume name is `volume`, keeping CLI
/// homes under `homes`, readable only by the runner's user, and running CLI jobs as the
/// `cli` (user, group), which must be another user.
pub async fn serve(
    address: std::net::SocketAddr,
    homes: PathBuf,
    volume: String,
    cli: (u32, u32),
) -> Result<()> {
    if !std::path::Path::new(CONTAINER_MARKER).is_file() {
        bail!("The agent runner only runs in the Pully agent container");
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if std::fs::metadata("/proc/self").map(|m| m.uid()).ok() == Some(cli.0) {
            bail!("CLI jobs must run as another user than the agent runner");
        }
    }
    agent_process::use_local_runtime(homes, Some(cli))?;
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .context("Unable to bind the agent runner address")?;
    axum::serve(listener, router(volume))
        .await
        .context("The agent runner failed")
}

/// The runner's routes, for `serve` and tests.
pub fn router(volume: String) -> Router {
    let runner = Arc::new(Runner {
        volume,
        logins: Default::default(),
        runs: Default::default(),
    });
    Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/run", post(run))
        .route("/cancel", post(cancel))
        .route("/catalog", post(catalog))
        .route("/status", post(status))
        .route("/sign-out", post(sign_out))
        .route("/login/start", post(login_start))
        .route("/login/finish", post(login_finish))
        .route("/login/cancel", post(login_cancel))
        .route("/home", get(home_save).put(home_restore))
        .layer(axum::extract::DefaultBodyLimit::max(HOME_LIMIT))
        .with_state(runner)
}

impl Runner {
    /// The login a call names, refused unless it is this container's own.
    fn login(&self, body: &Value) -> Result<AgentLogin> {
        let login = agent_remote::login_from(body)?;
        if login.volume() != self.volume {
            bail!("This agent container belongs to another login");
        }
        Ok(login)
    }
}

fn binary(body: &Value) -> Result<&'static str> {
    match body["binary"].as_str() {
        Some("claude") => Ok("claude"),
        Some("codex") => Ok("codex"),
        _ => bail!("Unsupported sandbox CLI"),
    }
}

fn answer(result: Result<Value>) -> Json<Value> {
    Json(match result {
        Ok(value) => json!({"ok": value}),
        Err(error) => json!({"error": crate::transport::safe_error(&error)}),
    })
}

async fn run(
    State(runner): State<Arc<Runner>>,
    Json(body): Json<Value>,
) -> axum::response::Response {
    let (lines, receiver) = mpsc::unbounded_channel::<Value>();
    let started = (|| {
        let login = runner.login(&body)?;
        let id = body["id"]
            .as_str()
            .filter(|id| id.len() <= 64)
            .context("Invalid run")?;
        let preparation =
            agent_remote::preparation_from(body["preparation"].as_str().unwrap_or("strict"))?;
        Ok::<_, anyhow::Error>((login, id.to_owned(), preparation))
    })();
    match started {
        Err(error) => {
            let _ = lines.send(json!({"error": crate::transport::safe_error(&error)}));
        }
        Ok((login, id, preparation)) => {
            let (stop, cancelled) = watch::channel(false);
            runner.runs.lock().unwrap().insert(id.clone(), stop);
            // A caller that stops listening has gone away, so its run stops too.
            let (gone, watcher, run_id) = (lines.clone(), runner.clone(), id.clone());
            crate::rt::spawn(async move {
                gone.closed().await;
                if let Some(stop) = watcher.runs.lock().unwrap().remove(&run_id) {
                    let _ = stop.send(true);
                }
            });
            let events = lines.clone();
            let request = body["request"].clone();
            let runner = runner.clone();
            crate::rt::spawn(async move {
                let sink: agent_process::EventSink = Arc::new(move |event| {
                    let _ = events.send(json!({"event": event}));
                });
                let result = agent_process::run_agent_prepared(
                    request,
                    &login,
                    cancelled,
                    sink,
                    preparation,
                )
                .await;
                runner.runs.lock().unwrap().remove(&id);
                let _ = lines.send(match result {
                    Ok((answer, usage)) => json!({"answer": answer, "usage": usage}),
                    Err(error) => json!({"error": crate::transport::safe_error(&error)}),
                });
            });
        }
    }
    let stream = futures_util::stream::unfold(receiver, |mut receiver| async move {
        let value = receiver.recv().await?;
        let mut line = value.to_string().into_bytes();
        line.push(b'\n');
        Some((Ok::<_, std::io::Error>(line), receiver))
    });
    let mut response = axum::response::Response::new(Body::from_stream(stream));
    response.headers_mut().insert(
        axum::http::header::CONTENT_TYPE,
        axum::http::HeaderValue::from_static("application/x-ndjson"),
    );
    response
}

async fn cancel(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer((|| {
        runner.login(&body)?;
        if let Some(stop) = body["id"]
            .as_str()
            .and_then(|id| runner.runs.lock().unwrap().remove(id))
        {
            let _ = stop.send(true);
        }
        Ok(json!(true))
    })())
}

async fn catalog(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(
        async {
            let login = runner.login(&body)?;
            let engine = body["engine"].as_str().context("Invalid engine")?;
            agent_process::catalog(engine, &login).await
        }
        .await,
    )
}

async fn status(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(async { agent_login::status(&runner.login(&body)?).await }.await)
}

async fn sign_out(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(
        async {
            let login = runner.login(&body)?;
            agent_login::sign_out(&login, binary(&body)?).await?;
            Ok(json!(true))
        }
        .await,
    )
}

async fn login_start(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(
        async {
            let login = runner.login(&body)?;
            runner.logins.start(&login, binary(&body)?).await
        }
        .await,
    )
}

async fn login_finish(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(
        async {
            let login = runner.login(&body)?;
            runner
                .logins
                .finish(&login, binary(&body)?, body["code"].as_str())
                .await
        }
        .await,
    )
}

async fn login_cancel(State(runner): State<Arc<Runner>>, Json(body): Json<Value>) -> Json<Value> {
    answer(
        async {
            let login = runner.login(&body)?;
            runner.logins.cancel(&login, binary(&body)?).await;
            Ok(json!(true))
        }
        .await,
    )
}

impl Runner {
    fn home(&self) -> Result<PathBuf> {
        agent_process::runtime()?
            .home(&self.volume)
            .context("Not a local runtime")
    }
}

/// The login's CLI home as a tar archive, for its Durable Object to keep while the container
/// sleeps. The runner never opens the files; it only archives the folder.
async fn home_save(State(runner): State<Arc<Runner>>) -> axum::response::Response {
    use axum::response::IntoResponse;
    let archive = async {
        let home = runner.home()?;
        if !home.is_dir() {
            return Ok(None);
        }
        let mut tar = agent_process::local_command("tar", &home);
        tar.arg("-C").arg(&home).args(["-cf", "-", "."]);
        let output = tar.kill_on_drop(true).output().await?;
        if !output.status.success() || output.stdout.len() > HOME_LIMIT {
            bail!("The CLI home could not be archived");
        }
        Ok::<_, anyhow::Error>(Some(output.stdout))
    }
    .await;
    match archive {
        Ok(Some(bytes)) => bytes.into_response(),
        Ok(None) => axum::http::StatusCode::NO_CONTENT.into_response(),
        Err(error) => (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            crate::transport::safe_error(&error),
        )
            .into_response(),
    }
}

/// Puts back an archive from `home_save` into the login's empty CLI home after a start.
async fn home_restore(State(runner): State<Arc<Runner>>, body: axum::body::Bytes) -> Json<Value> {
    answer(
        async {
            let home = runner.home()?;
            crate::rt::fs::create_dir_all(&home).await?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                crate::rt::fs::set_permissions(&home, std::fs::Permissions::from_mode(0o700))
                    .await?;
            }
            let mut tar = agent_process::local_command("tar", &home);
            tar.arg("-C")
                .arg(&home)
                .args(["-xf", "-", "--no-same-owner", "--no-overwrite-dir"])
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .kill_on_drop(true);
            let mut child = tar.spawn()?;
            let mut stdin = child.stdin.take().context("tar stdin unavailable")?;
            tokio::io::AsyncWriteExt::write_all(&mut stdin, &body).await?;
            drop(stdin);
            if !child.wait().await?.success() {
                bail!("The CLI home could not be restored");
            }
            Ok(json!(true))
        }
        .await,
    )
}
