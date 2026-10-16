use anyhow::{bail, Context, Result};
use clap::Parser;
use pulls_review_server::{
    ai,
    transport::{self, HttpState},
    AppState,
};
use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};

#[derive(Parser)]
#[command(
    name = "pulls-review-server",
    about = "Native Rust backend for pulls.review; serves the existing web and Android clients"
)]
struct Args {
    /// Local branch, commit, or A..B / A...B range to open.
    target: Option<String>,
    #[arg(long)]
    worktree: bool,
    #[arg(long)]
    no_open: bool,
    /// CLI jobs remain isolated; host execution is intentionally unavailable.
    #[arg(long, value_parser=["docker"])]
    agents: Option<String>,
    /// Run without a local checkout (requires --data-dir).
    #[arg(long, env = "PULLS_REVIEW_SERVER")]
    server: bool,
    #[arg(long, env = "PULLS_REVIEW_HOST", default_value = "127.0.0.1")]
    host: String,
    #[arg(long, env = "PULLS_REVIEW_PORT", default_value_t = 7390)]
    port: u16,
    /// Git repository to review. No branch switching or shell is used.
    #[arg(long, default_value = ".")]
    cwd: PathBuf,
    /// Persistent cache (default: <git-common-dir>/pulls-review-rust).
    #[arg(long, env = "PULLS_REVIEW_DATA_DIR")]
    data_dir: Option<PathBuf>,
    /// Existing PR_LOCAL web build output, built using pnpm build:local.
    #[arg(long, env = "PULLS_REVIEW_WEB_DIR")]
    assets: Option<PathBuf>,
    #[arg(long, env = "PULLS_REVIEW_ANDROID_DIR")]
    android_dir: Option<PathBuf>,
    /// Disable only the browser terminal-code gate when a reverse proxy authenticates it.
    #[arg(long)]
    no_auth: bool,
    #[arg(long)]
    allow_origin: Vec<String>,
    /// Disable periodic GitHub cache warming.
    #[arg(long)]
    no_warm: bool,
    /// Group review-requested pull requests every ten minutes (may use paid providers).
    #[arg(long)]
    preanalyze: bool,
    #[arg(long, default_value = "claude-code", value_parser=["claude-code","codex","pi"])]
    preanalyze_engine: String,
    #[arg(long)]
    preanalyze_model: Option<String>,
    /// Internal: stdio MCP adapter restricted to this preselected PR payload.
    #[arg(long, hide = true)]
    mcp_context: Option<PathBuf>,
}
#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        eprintln!("{}", transport::safe_error(&e));
        std::process::exit(1)
    }
}
async fn run() -> Result<()> {
    let args = Args::parse();
    if let Some(path) = args.mcp_context {
        return mcp_stdio(path).await;
    }
    if args.server && (args.target.is_some() || args.worktree) {
        bail!(pulls_review_server::host::NO_CHECKOUT);
    }
    let cwd = if args.server {
        PathBuf::new()
    } else {
        tokio::fs::canonicalize(&args.cwd)
            .await
            .context("Repository path does not exist")?
    };
    let data = if let Some(path) = args.data_dir {
        path
    } else {
        if args.server {
            bail!("--server requires --data-dir or PULLS_REVIEW_DATA_DIR");
        }
        let common = pulls_review_server::source::git_common_dir(&cwd)
            .await
            .context(
                "--cwd must name a Git repository (or pass --data-dir for a GitHub-only server)",
            )?;
        cwd.join(common.trim()).join("pulls-review-rust")
    };
    let app = AppState::from_host(pulls_review_server::host::ServerHost::new(
        pulls_review_server::store::Store::new(data)?,
        Some(cwd),
        args.android_dir,
    ))?;
    if let Some(runtime) = &args.agents {
        std::env::set_var("PULLS_REVIEW_AGENT_RUNTIME", runtime);
    }
    let page = if args.server {
        "/".into()
    } else {
        start_page(&app, args.target.as_deref(), args.worktree).await?
    };
    let state = HttpState::new(
        app.clone(),
        &args.host,
        args.port,
        args.allow_origin,
        args.no_auth,
        args.assets,
    )?;
    let listener = tokio::net::TcpListener::bind((args.host.as_str(), args.port))
        .await
        .context("Unable to bind server address")?;
    eprintln!(
        "pulls.review Rust backend: http://{}:{}",
        args.host,
        listener.local_addr()?.port()
    );
    if args.host != "127.0.0.1" && args.host != "localhost" && args.host != "::1" {
        eprintln!("Keep the phone API behind an authenticated reverse proxy. Account methods additionally require a Pully session; /api/v1 requires an agent key.");
    }
    state.print_code();
    if !args.no_open {
        open_browser(&format!(
            "http://{}:{}{}",
            if args.host == "0.0.0.0" {
                "127.0.0.1"
            } else {
                &args.host
            },
            listener.local_addr()?.port(),
            page
        ))
        .await;
    }
    let bridge = transport::start_agent_bridge(app.clone()).await?;
    let warmer = if args.no_warm {
        None
    } else {
        Some(tokio::spawn(pulls_review_server::scheduler::warm(
            app.clone(),
        )))
    };
    let preanalyzer = args.preanalyze.then(|| {
        tokio::spawn(pulls_review_server::scheduler::preanalyze(
            app.clone(),
            args.preanalyze_engine,
            args.preanalyze_model,
        ))
    });
    let shutdown_app = app.clone();
    let result = axum::serve(listener, transport::router(state))
        .with_graceful_shutdown(async move {
            shutdown().await;
            ai::begin_shutdown(&shutdown_app);
        })
        .await;
    if let Some(warmer) = warmer {
        warmer.abort();
    }
    if let Some(task) = preanalyzer {
        task.abort();
    }
    bridge.abort();
    ai::shutdown(&app).await;
    result.context("HTTP server failed")
}
async fn shutdown() {
    #[cfg(unix)]
    {
        if let Ok(mut term) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            tokio::select! { _=tokio::signal::ctrl_c()=>{}, _=term.recv()=>{} }
            return;
        }
    }
    let _ = tokio::signal::ctrl_c().await;
}
async fn mcp_stdio(path: PathBuf) -> Result<()> {
    // No AppState, environment credentials, cache, or repository is initialized here.
    let bytes = tokio::fs::read(path)
        .await
        .context("MCP context is unavailable")?;
    if bytes.len() > 16 * 1024 * 1024 {
        bail!("MCP context is too large")
    }
    let context: serde_json::Value =
        serde_json::from_slice(&bytes).context("Invalid MCP context")?;
    let mut input = tokio::io::BufReader::new(tokio::io::stdin());
    let mut output = tokio::io::stdout();
    loop {
        let mut line = Vec::new();
        let read = tokio::io::AsyncReadExt::take(&mut input, 1024 * 1024 + 1)
            .read_until(b'\n', &mut line)
            .await?;
        if read == 0 {
            break;
        }
        if line.len() > 1024 * 1024 {
            bail!("MCP request is too large")
        }
        let request = match serde_json::from_slice::<serde_json::Value>(&line) {
            Ok(v) => v,
            Err(_) => {
                output.write_all(b"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}\n").await?;
                continue;
            }
        };
        let notification = request.get("id").is_none();
        let response = ai::mcp_context(&context, request).await;
        if !notification {
            output
                .write_all(serde_json::to_string(&response)?.as_bytes())
                .await?;
            output.write_all(b"\n").await?;
            output.flush().await?;
        }
    }
    Ok(())
}

fn validate_target(target: &str) -> Result<()> {
    let trimmed = target.trim();
    let parts = trimmed
        .split_once("...")
        .or_else(|| trimmed.split_once(".."));
    let invalid_option = parts.map_or_else(
        || trimmed.starts_with('-'),
        |(base, head)| base.starts_with('-') || head.starts_with('-'),
    );
    if target.is_empty() || invalid_option || target.chars().any(char::is_control) {
        bail!("Invalid local review target");
    }
    Ok(())
}

async fn start_page(
    app: &std::sync::Arc<AppState>,
    target: Option<&str>,
    worktree: bool,
) -> Result<String> {
    if worktree {
        return Ok("/worktree".into());
    }
    let info = pulls_review_server::source::handle(app, "repo-info", serde_json::json!({}))
        .await
        .and_then(Result::ok)
        .unwrap_or_default();
    if let Some(target) = target {
        validate_target(target)?;
        let kind = if target.contains("..") {
            "compare"
        } else if info["branches"]
            .as_array()
            .is_some_and(|a| a.iter().any(|v| v == target))
        {
            "branch"
        } else {
            "commit"
        };
        return Ok(format!(
            "/{kind}/{}",
            pulls_review_server::source::enc(target)
        ));
    }
    let branch = pulls_review_server::text(&info, "currentBranch");
    Ok(
        if !branch.is_empty()
            && info["defaultBranch"]["name"].is_string()
            && info["defaultBranch"]["name"] != branch
        {
            format!("/branch/{}", pulls_review_server::source::enc(branch))
        } else {
            "/".into()
        },
    )
}
async fn open_browser(url: &str) {
    #[cfg(target_os = "macos")]
    let mut command = tokio::process::Command::new("open");
    #[cfg(target_os = "windows")]
    let mut command = tokio::process::Command::new("explorer");
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    let mut command = tokio::process::Command::new("xdg-open");
    let _ = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        command
            .arg(url)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true)
            .status(),
    )
    .await;
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn targets_reject_options_in_either_range_side() {
        for target in ["--all", "main..--all", "HEAD...-x", "  -x  "] {
            assert!(validate_target(target).is_err());
        }
        for target in ["main...topic", "..HEAD", "HEAD..", "topic", "HEAD"] {
            assert!(validate_target(target).is_ok());
        }
    }
}
