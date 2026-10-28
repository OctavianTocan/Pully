//! The server inside the hosted agent container: one login's Claude Code and Codex runs, model
//! lists and sign-ins, done with a native server's own code ([`crate::process`],
//! [`crate::login`]) as plain processes, since the container is the sandbox. Only the login's own
//! Durable Object can reach it; [`crate::remote`] is its client.
use crate::{
    config::{config, Preparation},
    login::{self as agent_login, AgentLogin},
    process as agent_process,
    redact::safe_error,
    remote as agent_remote,
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

/// The largest login file kept between container starts, as the login copy scripts allow.
const LOGIN_FILE_LIMIT: usize = 512 * 1024;

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
    if !std::path::Path::new(config().container_marker).is_file() {
        bail!("The agent runner only runs in the agent container");
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
        .route(
            "/login-files",
            get(login_files_save).put(login_files_restore),
        )
        .layer(axum::extract::DefaultBodyLimit::max(4 * LOGIN_FILE_LIMIT))
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
        Err(error) => json!({"error": safe_error(&error)}),
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
        let preparation = Preparation::named(body["preparation"].as_str().unwrap_or("strict"))?;
        Ok::<_, anyhow::Error>((login, id.to_owned(), preparation))
    })();
    match started {
        Err(error) => {
            let _ = lines.send(json!({"error": safe_error(&error)}));
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
                    Err(error) => json!({"error": safe_error(&error)}),
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

/// The login files kept while the container sleeps, by their path in the CLI home: the field
/// that makes each a subscription sign-in, then the others kept with it. Nothing else of the
/// home is kept, so an API key or a setting a CLI wrote never leaves the container.
const LOGIN_FILES: [(&str, &str, &[&str]); 3] = [
    (".codex/auth.json", "tokens", &["auth_mode", "last_refresh"]),
    (".claude/.credentials.json", "claudeAiOauth", &[]),
    (".claude.json", "oauthAccount", &[]),
];

/// The kept part of the login file at `path`, or `None` when it holds no sign-in.
fn kept(path: &str, file: &Value) -> Option<Value> {
    let (_, main, others) = LOGIN_FILES.iter().find(|(name, ..)| *name == path)?;
    let mut kept = serde_json::Map::new();
    kept.insert(main.to_string(), file.get(*main)?.clone());
    for field in *others {
        if let Some(value) = file.get(*field) {
            kept.insert(field.to_string(), value.clone());
        }
    }
    Some(Value::Object(kept))
}

/// The login's sign-in files as one JSON object of path to kept content, for its Durable
/// Object to keep as text while the container sleeps; `{}` when it has none.
async fn login_files_save(State(runner): State<Arc<Runner>>) -> axum::response::Response {
    use axum::response::IntoResponse;
    let files = (|| {
        let home = runner.home()?;
        let mut files = serde_json::Map::new();
        for (path, ..) in LOGIN_FILES {
            let file = home.join(path);
            let Ok(meta) = std::fs::symlink_metadata(&file) else {
                continue;
            };
            if !meta.is_file() || meta.len() > LOGIN_FILE_LIMIT as u64 {
                continue;
            }
            let Ok(content) = serde_json::from_slice::<Value>(&std::fs::read(&file)?) else {
                continue;
            };
            if let Some(content) = kept(path, &content) {
                files.insert(path.to_owned(), content);
            }
        }
        Ok::<_, anyhow::Error>(Value::Object(files))
    })();
    match files {
        Ok(files) => Json(files).into_response(),
        Err(error) => (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            safe_error(&error),
        )
            .into_response(),
    }
}

/// Writes back the files from `login_files_save` into the login's CLI home after a start.
async fn login_files_restore(
    State(runner): State<Arc<Runner>>,
    body: axum::body::Bytes,
) -> Json<Value> {
    answer((|| {
        // Read as bytes: the object sends the saved text without a content type.
        let files: serde_json::Map<String, Value> =
            serde_json::from_slice(&body).context("Invalid login files")?;
        let home = runner.home()?;
        for (path, content) in &files {
            let content = kept(path, content).context("Invalid login file")?;
            let file = home.join(path);
            let folder = file.parent().context("Invalid login file")?;
            std::fs::create_dir_all(folder)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                for dir in [home.as_path(), folder] {
                    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
                }
            }
            let temp = file.with_extension(format!("pully-{}", uuid::Uuid::new_v4()));
            let mut options = std::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
            std::io::Write::write_all(&mut options.open(&temp)?, content.to_string().as_bytes())?;
            std::fs::rename(&temp, &file)?;
        }
        Ok(json!(true))
    })())
}
