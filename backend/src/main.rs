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
    /// Serve only tokenless public browsing and explicitly configured personal GitHub reads.
    #[arg(long, env = "PULLY_PUBLIC_LISTEN")]
    public_listen: Option<std::net::SocketAddr>,
    /// Isolated public/personal storage root; never the legacy --data-dir.
    #[arg(long, env = "PULLY_PUBLIC_DATA_DIR")]
    public_data_dir: Option<PathBuf>,
    /// Owner-private JSON file for the registered Pully OAuth App.
    #[arg(long, env = "PULLY_PERSONAL_OAUTH_CONFIG_FILE")]
    personal_oauth_config: Option<PathBuf>,
    /// Owner-private JSON file holding versioned server vault keys.
    #[arg(long, env = "PULLY_PERSONAL_VAULT_KEYS_FILE")]
    personal_vault_keys: Option<PathBuf>,
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
    /// Persistent cache (default: <git-common-dir>/pulls-review-rust, first seeded from a copy of pulls-review).
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
    if let Some(address) = args.public_listen {
        if args.target.is_some()
            || args.worktree
            || args.agents.is_some()
            || args.preanalyze
            || args.mcp_context.is_some()
            || args.no_auth
            || !args.allow_origin.is_empty()
        {
            bail!("Public Pully cannot open private targets or start legacy agent work");
        }
        let personal = match (args.public_data_dir, args.personal_oauth_config, args.personal_vault_keys) {
            (None, None, None) => None,
            (Some(data_root), Some(oauth_app_file), Some(vault_keys_file)) => Some(
                pulls_review_server::public_server::PersonalAuthFiles { data_root, oauth_app_file, vault_keys_file }
            ),
            _ => bail!("Personal GitHub startup requires explicit isolated storage, OAuth App and vault-key files"),
        };
        let router = pulls_review_server::public_server::router(
            pulls_review_server::public_server::PublicServerConfig { personal },
        )
        .await?;
        let listener = tokio::net::TcpListener::bind(address)
            .await
            .context("Unable to bind public Pully address")?;
        eprintln!("Pully public API: http://{}", listener.local_addr()?);
        axum::serve(listener, router)
            .with_graceful_shutdown(shutdown())
            .await
            .context("Public Pully server failed")?;
        return Ok(());
    }
    if args.public_data_dir.is_some()
        || args.personal_oauth_config.is_some()
        || args.personal_vault_keys.is_some()
    {
        bail!("Public/personal configuration requires --public-listen or PULLY_PUBLIC_LISTEN");
    }
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
        let common = cwd.join(common.trim());
        let root = common.join("pulls-review-rust");
        if pulls_review_server::store::seed_from_legacy(&root, &common.join("pulls-review"))? {
            eprintln!(
                "Copied the TypeScript server's data into {}; the original is unchanged.",
                root.display()
            );
        }
        root
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
    let listener = tokio::net::TcpListener::bind((args.host.as_str(), args.port))
        .await
        .context("Unable to bind server address")?;
    // Port zero asks the OS to choose a port. Browser origins must use that
    // actual port, not the requested zero.
    let port = listener.local_addr()?.port();
    let mut state = HttpState::new(
        app.clone(),
        &args.host,
        port,
        args.allow_origin,
        args.no_auth,
        args.assets,
    )?;
    // This opt-in file is shared only with the host's deployment timer. The
    // ordinary phone/browser credentials cannot stop the server.
    if let Some(path) = std::env::var_os("PULLS_REVIEW_UPDATE_TOKEN_FILE") {
        state = state.with_update_token(transport::create_update_token(&PathBuf::from(path))?);
    }
    eprintln!(
        "pulls.review Rust backend: {}",
        transport::http_origin(&args.host, port)
    );
    if args.host != "127.0.0.1" && args.host != "localhost" && args.host != "::1" {
        eprintln!("Keep the phone API behind an authenticated reverse proxy. Account methods additionally require a Pully session; /api/v1 requires an agent key.");
    }
    state.print_code();
    if !args.no_open {
        open_browser(&format!(
            "{}{}",
            transport::http_origin(
                match args.host.as_str() {
                    "0.0.0.0" => "127.0.0.1",
                    "::" => "::1",
                    host => host,
                },
                port,
            ),
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
            tokio::select! {
                _ = shutdown() => {},
                _ = ai::wait_for_update_shutdown(&shutdown_app) => {},
            }
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
