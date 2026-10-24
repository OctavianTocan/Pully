//! Sandboxed CLI transport. Permission flags are defense in depth, never the sandbox.
//! Only the job folder is writable. Login state is copied in a separate, non-agent container,
//! from the login volume of whoever started the work (agent_login.rs). There are no API keys.
#[cfg(test)]
mod lifecycle_tests;
mod schema;
mod stream;

use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    path::{Path, PathBuf},
    process::Stdio,
    sync::{Arc, OnceLock},
    time::Duration,
};
use crate::agent_login::AgentLogin;
use stream::CliParser;
use crate::rt::process::Command;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    sync::watch,
};
use uuid::Uuid;

const INPUT_LIMIT: usize = 8 * 1024 * 1024;
const OUTPUT_LIMIT: usize = 16 * 1024 * 1024;
const MCP_BIN: &str = "/opt/pulls-review-server";
const PAYLOAD: &str = "/inputs/payload.json";
pub type EventSink = Arc<dyn Fn(Value) + Send + Sync>;
static CLEANUPS: std::sync::Mutex<Vec<crate::rt::JoinHandle<()>>> =
    std::sync::Mutex::new(Vec::new());

pub async fn shutdown_cleanup() {
    loop {
        let pending = std::mem::take(&mut *CLEANUPS.lock().unwrap());
        if pending.is_empty() {
            break;
        }
        for cleanup in pending {
            let _ = cleanup.await;
        }
    }
}
struct Job {
    root: PathBuf,
    name: String,
    volume: String,
    login: std::sync::Mutex<Option<(String, String)>>,
}
impl Job {
    fn new(login: &AgentLogin) -> Self {
        let name = format!("pulls-agent-{}", Uuid::new_v4());
        Self {
            root: std::env::temp_dir().join(&name),
            name,
            volume: login.volume(),
            login: std::sync::Mutex::new(None),
        }
    }
    async fn prepare(&self) -> Result<()> {
        crate::rt::fs::create_dir(&self.root)
            .await
            .context("Could not create sandbox job folder")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            crate::rt::fs::set_permissions(&self.root, std::fs::Permissions::from_mode(0o700)).await?;
        }
        for folder in [
            "inputs",
            "work",
            "work/home",
            "work/home/.claude",
            "work/home/.codex",
            "work/tmp",
            "work/state",
        ] {
            crate::rt::fs::create_dir(self.root.join(folder)).await?;
        }
        Ok(())
    }
}
impl Drop for Job {
    fn drop(&mut self) {
        let root = self.root.clone();
        let name = self.name.clone();
        let login = self
            .login
            .lock()
            .unwrap()
            .clone()
            .map(|(binary, image)| (binary, image, self.volume.clone()));
        if let Ok(runtime) = crate::rt::Handle::try_current() {
            let handle = runtime.spawn(cleanup_job(root, name, login, Duration::from_secs(30)));
            let mut handles = CLEANUPS.lock().unwrap();
            handles.retain(|h| !h.is_finished());
            handles.push(handle);
        }
    }
}
async fn cleanup_job(
    root: PathBuf,
    name: String,
    login: Option<(String, String, String)>,
    login_timeout: Duration,
) {
    // Stop model/bootstrap work before copying credentials. The final persistence
    // helper can itself finish creating after a killed client, so no helper may
    // be started after the delayed removal retries.
    remove_container(&name).await;
    remove_container(&format!("{name}-login")).await;
    if let Some((binary, image, volume)) = login {
        let _ = persist_login_with_timeout(&root, &name, &binary, &image, &volume, login_timeout)
            .await;
    }
    for wait in [0, 1, 9] {
        crate::rt::sleep(Duration::from_secs(wait)).await;
        remove_container(&name).await;
        remove_container(&format!("{name}-login")).await;
    }
    let _ = crate::rt::fs::remove_dir_all(root).await;
}
pub(crate) async fn remove_container(name: &str) {
    let mut command = Command::new("docker");
    command
        .args(["rm", "--force", name])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    let _ = crate::rt::timeout(Duration::from_secs(15), command.status()).await;
}
async fn read_bounded<R: AsyncRead + Unpin>(mut input: R, limit: usize) -> Result<Vec<u8>> {
    let mut result = Vec::new();
    let mut buf = [0_u8; 8192];
    loop {
        let size = input
            .read(&mut buf)
            .await
            .context("Could not read sandbox output")?;
        if size == 0 {
            return Ok(result);
        }
        if result.len() + size > limit {
            bail!("The sandbox process exceeded its output limit");
        }
        result.extend_from_slice(&buf[..size]);
    }
}
async fn read_stdout<R: AsyncRead + Unpin>(
    mut input: R,
    on_line: &mut (dyn FnMut(&str) -> bool + Send),
) -> Result<(Vec<u8>, bool)> {
    let mut result = Vec::new();
    let mut start = 0;
    let mut scan_from = 0;
    let mut buf = [0_u8; 8192];
    loop {
        let size = input
            .read(&mut buf)
            .await
            .context("Could not read sandbox output")?;
        if result.len() + size > OUTPUT_LIMIT {
            bail!("The sandbox process exceeded its output limit");
        }
        result.extend_from_slice(&buf[..size]);
        while let Some(at) = result[scan_from..].iter().position(|b| *b == b'\n') {
            let end = scan_from + at;
            let line = std::str::from_utf8(&result[start..end])
                .context("The CLI emitted invalid UTF-8")?;
            if on_line(line.trim_end_matches('\r')) {
                return Ok((result, true));
            }
            start = end + 1;
            scan_from = start;
        }
        scan_from = result.len();
        if size == 0 {
            if start < result.len() {
                let line = std::str::from_utf8(&result[start..])
                    .context("The CLI emitted invalid UTF-8")?;
                if on_line(line) {
                    return Ok((result, true));
                }
            }
            return Ok((result, false));
        }
    }
}
async fn process_stream(
    command: &mut Command,
    input: Vec<u8>,
    on_line: &mut (dyn FnMut(&str) -> bool + Send),
) -> Result<Vec<u8>> {
    let mut child = command.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true)
        .spawn().map_err(|_| anyhow::anyhow!("Could not start Docker. Install Docker, start its daemon, and build docker/agents/setup.sh build; host execution is disabled."))?;
    let mut stdin = child.stdin.take().context("Sandbox stdin unavailable")?;
    let stdout = child.stdout.take().context("Sandbox stdout unavailable")?;
    let stderr = child.stderr.take().context("Sandbox stderr unavailable")?;
    let control = async {
        let writer = async {
            stdin
                .write_all(&input)
                .await
                .context("Could not send sandbox input")?;
            stdin
                .shutdown()
                .await
                .context("Could not finish sandbox input")?;
            Ok::<_, anyhow::Error>(())
        };
        let (_, _, status) = tokio::try_join!(writer, read_bounded(stderr, 1024 * 1024), async {
            child
                .wait()
                .await
                .context("Could not wait for sandbox process")
        })?;
        Ok::<_, anyhow::Error>(status)
    };
    let output = read_stdout(stdout, on_line);
    tokio::pin!(control);
    tokio::pin!(output);
    let (out, status) = tokio::select! {
        result = &mut output => {
            let (out, early) = result?;
            if early { return Ok(out); }
            (out, control.await?)
        }
        result = &mut control => { let status = result?; (output.await?.0, status) }
    };
    if !status.success() {
        bail!("The sandboxed CLI failed (exit {}). Check the agent image, CLI version, and provider authentication; raw stderr is withheld to protect credentials.", status.code().map(|n| n.to_string()).unwrap_or_else(|| "signal".into()));
    }
    Ok(out)
}
async fn process(command: &mut Command, input: Vec<u8>) -> Result<Vec<u8>> {
    process_stream(command, input, &mut |_| false).await
}
fn mount(path: &Path, target: &str, readonly: bool) -> Result<String> {
    let path = path.to_str().context("Sandbox paths must be UTF-8")?;
    if path.contains([',', '"', '\n', '\r']) {
        bail!("Sandbox paths cannot contain CSV delimiters");
    }
    Ok(format!(
        "type=bind,source={path},target={target}{}",
        if readonly { ",readonly" } else { "" }
    ))
}
fn setting(request: &Value, key: &str, max: usize) -> Result<Option<String>> {
    match request.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(v)) if v.len() <= max && !v.contains('\0') => Ok(Some(v.clone())),
        _ => bail!("Invalid AI job {key}"),
    }
}
pub(crate) fn runtime_image() -> Result<String> {
    let runtime = crate::rt::env::var("PULLS_REVIEW_AGENT_RUNTIME").unwrap_or_else(|_| "docker".into());
    if runtime.trim() != "docker" {
        bail!("The Rust server requires PULLS_REVIEW_AGENT_RUNTIME=docker; unsandboxed host execution is disabled");
    }
    let image =
        crate::rt::env::var("PULLS_REVIEW_AGENT_IMAGE").unwrap_or_else(|_| "pulls-review-agents".into());
    if image.is_empty() || image.starts_with('-') || image.chars().any(char::is_whitespace) {
        bail!("Invalid PULLS_REVIEW_AGENT_IMAGE");
    }
    Ok(image)
}
fn binary(engine: &str) -> Result<&'static str> {
    match engine {
        "claude" | "claude-code" => Ok("claude"),
        "codex" => Ok("codex"),
        _ => bail!("Unsupported sandbox CLI engine"),
    }
}
fn sandbox_command(root: &Path, name: &str, entrypoint: &str, network: bool) -> Result<Command> {
    let mut command = Command::new("docker");
    command.args([
        "create",
        "--name",
        name,
        "--pull",
        "never",
        "--interactive",
        "--init",
        "--read-only",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=128",
        "--memory=2g",
        "--cpus=2",
        "--workdir=/job",
        "--entrypoint",
        entrypoint,
    ]);
    if network {
        command.args(["--add-host", "host.docker.internal:host-gateway"]);
    } else {
        command.arg("--network=none");
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let metadata = std::fs::metadata(root)?;
        command.args([
            "--user".into(),
            format!("{}:{}", metadata.uid(), metadata.gid()),
        ]);
    }
    command.args([
        "--env",
        "HOME=/job/home",
        "--env",
        "TMPDIR=/job/tmp",
        "--env",
        "CODEX_HOME=/job/home/.codex",
        "--env",
        "CLAUDE_CONFIG_DIR=/job/home/.claude",
        "--env",
        "DISABLE_AUTOUPDATER=1",
    ]);
    command.args(["--mount", &mount(&root.join("work"), "/job", false)?]);
    Ok(command)
}
async fn create_start(
    command: &mut Command,
    name: &str,
    input: Vec<u8>,
    on_line: &mut (dyn FnMut(&str) -> bool + Send),
) -> Result<Vec<u8>> {
    crate::rt::timeout(Duration::from_secs(60), process(command, vec![]))
        .await
        .context("Docker create timed out")??;
    let mut start = Command::new("docker");
    start.args(["start", "--attach", "--interactive", name]);
    process_stream(&mut start, input, on_line).await
}
// Saved login files carry the CLIs' own subscription sign-in only; no API key is ever read.
const LOGIN_BOOTSTRAP_SCRIPT: &str = r#"const fs=require('fs'),crypto=require('crypto'); const kind=process.argv[1];
function raw(p){const s=fs.lstatSync(p);if(!s.isFile()||s.isSymbolicLink()||s.size>524288||fs.realpathSync(p)!==p)throw Error('Invalid login');return fs.readFileSync(p,'utf8');}
function save(p,v){fs.writeFileSync(p,JSON.stringify(v),{mode:0o600});}
const source=kind==='claude'?'/login/.claude/.credentials.json':'/login/.codex/auth.json';const original=raw(source),a=JSON.parse(original);
fs.writeFileSync('/job/state/auth-original',crypto.createHash('sha256').update(original).digest('hex'),{mode:0o600});
if(kind==='claude'){if(!a.claudeAiOauth)throw Error('Missing login');save('/job/home/.claude/.credentials.json',{claudeAiOauth:a.claudeAiOauth});
let cfg={hasCompletedOnboarding:true};try{const c=JSON.parse(raw('/login/.claude.json'));if(c.oauthAccount)cfg.oauthAccount=c.oauthAccount;}catch{}save('/job/home/.claude.json',cfg);
}else{const out={};for(const k of ['auth_mode','tokens','last_refresh'])if(a[k]!==undefined)out[k]=a[k];if(!out.tokens)throw Error('Missing login');save('/job/home/.codex/auth.json',out);}"#;
const LOGIN_PERSIST_SCRIPT: &str = r#"const fs=require('fs'),crypto=require('crypto');const kind=process.argv[1];
const relative=kind==='claude'?'.claude/.credentials.json':'.codex/auth.json',target='/login/'+relative;
function raw(p){const s=fs.lstatSync(p);if(!s.isFile()||s.isSymbolicLink()||s.size>524288||fs.realpathSync(p)!==p)throw Error('Invalid login');return fs.readFileSync(p,'utf8');}
const hash=s=>crypto.createHash('sha256').update(s).digest('hex'),original=raw('/job/state/auth-original');
if(hash(raw(target))!==original)process.exit(0);
const updated=JSON.parse(raw('/job/home/'+relative)),out={};
if(kind==='claude'){if(!updated.claudeAiOauth)throw Error('Invalid login');out.claudeAiOauth=updated.claudeAiOauth;}
else{for(const k of ['auth_mode','tokens','last_refresh'])if(updated[k]!==undefined)out[k]=updated[k];if(!out.tokens)throw Error('Invalid login');}
const content=JSON.stringify(out),temp=target+'.pulls-'+crypto.randomUUID();
try{fs.writeFileSync(temp,content,{mode:0o600,flag:'wx'});fs.renameSync(temp,target);fs.writeFileSync('/job/state/auth-original',hash(content),{mode:0o600});}finally{try{fs.unlinkSync(temp);}catch{}}"#;
/// Only credential files are copied. The agent never mounts the login volume, settings, skills,
/// GitHub login, host home or checkout. A bootstrap never has a network or a model process.
async fn prepare_login(job: &Job, binary: &str, image: &str, login: &AgentLogin) -> Result<()> {
    let volume = &job.volume;
    let mut inspect = Command::new("docker");
    inspect.args(["volume", "inspect", "--format", "{{.Name}}", volume]);
    crate::rt::timeout(Duration::from_secs(15), process(&mut inspect, vec![]))
        .await
        .context("Docker login volume check timed out")?
        .map_err(|_| anyhow::anyhow!(login.missing(binary)))?;
    let name = format!("{}-login", job.name);
    let mut create = sandbox_command(&job.root, &name, "node", false)?;
    create.args([
        "--mount",
        &format!("type=volume,source={volume},target=/login,readonly"),
    ]);
    // Parse, bound and whitelist auth data; never copy an executable config or a symlink.
    create
        .arg(image)
        .args(["-e", LOGIN_BOOTSTRAP_SCRIPT, binary]);
    let result = crate::rt::timeout(
        Duration::from_secs(30),
        create_start(&mut create, &name, vec![], &mut |_| false),
    )
    .await;
    remove_container(&name).await;
    result
        .context("Sandbox authentication copy timed out")?
        .map_err(|_| anyhow::anyhow!(login.missing(binary)))?;
    *job.login.lock().unwrap() = Some((binary.to_owned(), image.to_owned()));
    Ok(())
}
// Refresh tokens remain useful across jobs without exposing the shared home to a model.
// The lock and original fingerprint prevent an older concurrent run replacing a newer login.
async fn persist_login(
    root: &Path,
    job_name: &str,
    binary: &str,
    image: &str,
    volume: &str,
) -> Result<()> {
    persist_login_with_timeout(root, job_name, binary, image, volume, Duration::from_secs(30)).await
}
async fn persist_login_with_timeout(
    root: &Path,
    job_name: &str,
    binary: &str,
    image: &str,
    volume: &str,
    timeout: Duration,
) -> Result<()> {
    if !root.join("work/state/auth-original").is_file() {
        return Ok(());
    }
    let name = format!("{job_name}-login");
    let mut create = sandbox_command(root, &name, "flock", false)?;
    create.args([
        "--mount",
        &format!("type=volume,source={volume},target=/login"),
    ]);
    create.arg(image).args([
        "--wait",
        "10",
        "/login/.pulls-review-auth.lock",
        "node",
        "-e",
        LOGIN_PERSIST_SCRIPT,
        binary,
    ]);
    let result = crate::rt::timeout(
        timeout,
        create_start(&mut create, &name, vec![], &mut |_| false),
    )
    .await;
    remove_container(&name).await;
    result.context("Sandbox login refresh timed out")??;
    Ok(())
}
/// Docker always needs a Linux helper, even when its server runs on macOS or Windows.
/// A configured helper must be an ELF executable; otherwise use the Linux server itself.
fn mcp_helper() -> Result<PathBuf> {
    let path = match crate::rt::env::var_os("PULLS_REVIEW_AGENT_MCP_BINARY") {
        Some(path) => PathBuf::from(path),
        None if cfg!(target_os = "linux") => std::env::current_exe()?,
        None => bail!("Set PULLS_REVIEW_AGENT_MCP_BINARY to a Linux build of pulls-review-server for the Docker image's architecture."),
    };
    let path = path
        .canonicalize()
        .context("The configured Linux MCP helper does not exist")?;
    let mut file = std::fs::File::open(&path).context("Could not open the Linux MCP helper")?;
    let mut magic = [0; 4];
    std::io::Read::read_exact(&mut file, &mut magic).context("Invalid Linux MCP helper")?;
    if &magic != b"\x7fELF" {
        bail!("The MCP helper must be a Linux ELF executable compatible with the agent image");
    }
    Ok(path)
}
async fn launch(
    job: &Job,
    login: &AgentLogin,
    binary: &str,
    args: Vec<String>,
    input: Vec<u8>,
    helper: bool,
    on_line: &mut (dyn FnMut(&str) -> bool + Send),
) -> Result<Vec<u8>> {
    let image = runtime_image()?;
    prepare_login(job, binary, &image, login).await?;
    let mut create = sandbox_command(&job.root, &job.name, binary, true)?;
    create.args([
        "--mount",
        &mount(&job.root.join("inputs"), "/inputs", true)?,
    ]);
    if helper {
        create.args(["--mount", &mount(&mcp_helper()?, MCP_BIN, true)?]);
    }
    create.arg(&image).args(args);
    let result = create_start(&mut create, &job.name, input, on_line).await;
    remove_container(&job.name).await;
    let shared_login = job.login.lock().unwrap().is_some();
    if shared_login
        && persist_login(&job.root, &job.name, binary, &image, &job.volume)
            .await
            .is_err()
    {
        on_line(r#"{"type":"pulls_auth_warning"}"#);
    }
    result
}

/// A job emits bounded activity patches as stdout arrives; final answers stay untruncated.
pub async fn run_agent(
    request: Value,
    login: &AgentLogin,
    cancel: watch::Receiver<bool>,
    sink: EventSink,
) -> Result<(String, Value)> {
    run_agent_prepared(
        request,
        login,
        cancel,
        sink,
        crate::ai::StructuredAnswerPreparation::Strict,
    )
    .await
}

pub(crate) async fn run_agent_prepared(
    request: Value,
    login: &AgentLogin,
    mut cancel: watch::Receiver<bool>,
    sink: EventSink,
    preparation: crate::ai::StructuredAnswerPreparation,
) -> Result<(String, Value)> {
    if *cancel.borrow() {
        bail!("AI task cancelled");
    }
    if serde_json::to_vec(&request)?.len() > INPUT_LIMIT {
        bail!("AI job exceeds the 8 MiB input limit");
    }
    runtime_image()?;
    let job = Job::new(login);
    let result = tokio::select! {
        result = crate::rt::timeout(Duration::from_secs(30 * 60), run_inner(&request, &job, login, sink, preparation)) => result.map_err(|_| anyhow::anyhow!("AI job exceeded the 30 minute timeout"))?,
        _ = async { loop { if *cancel.borrow_and_update() || cancel.changed().await.is_err() { break; } } } => Err(anyhow::anyhow!("AI task cancelled")),
    };
    remove_container(&job.name).await;
    remove_container(&format!("{}-login", job.name)).await;
    result.map(|(answer, usage)| (crate::ai::sanitize(&answer), usage))
}
async fn run_inner(
    request: &Value,
    job: &Job,
    login: &AgentLogin,
    sink: EventSink,
    preparation: crate::ai::StructuredAnswerPreparation,
) -> Result<(String, Value)> {
    let engine = setting(request, "engine", 32)?.context("AI engine is required")?;
    let binary = binary(&engine)?;
    let model = setting(request, "model", 256)?;
    let effort = setting(request, "effort", 32)?;
    if effort.as_ref().is_some_and(|effort| {
        !["none", "minimal", "low", "medium", "high", "xhigh", "max"].contains(&effort.as_str())
    }) {
        bail!("Unsupported reasoning effort");
    }
    let prompt = setting(request, "prompt", INPUT_LIMIT)?.context("AI prompt is required")?;
    let system = crate::ai::sanitize(&setting(request, "system", 64 * 1024)?.unwrap_or_default());
    if request
        .get("schema")
        .is_some_and(|v| v.to_string().len() > 64 * 1024)
    {
        bail!("AI answer schema exceeds the 64 KiB limit");
    }
    let structured = request.get("schema").is_some_and(|v| !v.is_null());
    let context = request.get("context").filter(|v| v.is_object());
    job.prepare().await?;
    if let Some(context) = context {
        crate::rt::fs::write(
            job.root.join("inputs/payload.json"),
            serde_json::to_vec(context)?,
        )
        .await?;
    }
    if structured {
        crate::rt::fs::write(
            job.root.join("inputs/schema.json"),
            serde_json::to_vec(&schema::for_cli(binary, &request["schema"]))?,
        )
        .await?;
    }
    let mcp_args = json!(["--mcp-context", PAYLOAD]);
    let mut args: Vec<String>;
    let input: Vec<u8>;
    if binary == "claude" {
        args = [
            "-p",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--tools",
            "",
            "--setting-sources",
            "",
            "--strict-mcp-config",
            "--disable-slash-commands",
            "--no-session-persistence",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        args.extend(["--system-prompt".into(), system]);
        if let Some(model) = &model {
            args.extend(["--model".into(), model.clone()]);
        }
        if let Some(effort) = effort {
            args.extend(["--effort".into(), effort]);
        }
        if structured {
            args.extend(["--json-schema".into(), request["schema"].to_string()]);
        }
        if context.is_some() {
            let tools = request["_tools"]
                .as_array()
                .context("Scoped tool names are missing")?
                .iter()
                .filter_map(Value::as_str)
                .map(|t| format!("mcp__pr__{t}"))
                .collect::<Vec<_>>()
                .join(",");
            args.extend([
                "--mcp-config".into(),
                json!({"mcpServers":{"pr":{"command":MCP_BIN,"args":mcp_args}}}).to_string(),
                "--allowedTools".into(),
                tools,
            ]);
        }
        input = format!("{}\n{}\n", json!({"type":"control_request","request_id":"thinking","request":{"subtype":"set_max_thinking_tokens","max_thinking_tokens":null,"thinking_display":"summarized"}}), json!({"type":"user","message":{"role":"user","content":prompt}})).into_bytes();
    } else {
        args = [
            "exec",
            "--json",
            "--ephemeral",
            "--ignore-user-config",
            "--ignore-rules",
            "--skip-git-repo-check",
            "--sandbox",
            "read-only",
            "--color",
            "never",
            "--output-last-message",
            "/job/answer.txt",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        for config in [
            "features.shell_tool=false",
            "features.unified_exec=false",
            "features.view_image=false",
            "web_search=\"disabled\"",
            "model_reasoning_summary=\"auto\"",
            "sqlite_home=\"/job/state\"",
        ] {
            args.extend(["-c".into(), config.into()]);
        }
        if let Some(model) = &model {
            args.extend(["--model".into(), model.clone()]);
        }
        if let Some(effort) = effort {
            args.extend([
                "-c".into(),
                format!("model_reasoning_effort={}", json!(effort)),
            ]);
        }
        if structured {
            args.extend(["--output-schema".into(), "/inputs/schema.json".into()]);
        }
        if context.is_some() {
            for config in [
                format!("mcp_servers.pr.command={}", json!(MCP_BIN)),
                format!("mcp_servers.pr.args={mcp_args}"),
                "mcp_servers.pr.default_tools_approval_mode=\"approve\"".into(),
            ] {
                args.extend(["-c".into(), config]);
            }
        }
        args.push("-".into());
        input = format!("{system}\n\n{prompt}").into_bytes();
    }
    let mut parser = CliParser::new(binary, structured, model, sink);
    let input = crate::ai::sanitize(&String::from_utf8(input).context("Invalid prompt encoding")?)
        .into_bytes();
    launch(job, login, binary, args, input, context.is_some(), &mut |line| {
        parser.feed(line);
        false
    })
    .await?;
    let (mut answer, usage) = parser.finish()?;
    if structured {
        answer = schema::prepare_answer(binary, &request["schema"], &answer, preparation)?;
    }
    Ok((answer, usage))
}

/// Catalog queries run in exactly the same image/authentication boundary as paid jobs, but
/// initialization, debug models and version queries never submit a prompt or start a paid turn.
pub async fn catalog(engine: &str, login: &AgentLogin) -> Result<Value> {
    static VERSION: OnceLock<regex::Regex> = OnceLock::new();
    let binary = binary(engine)?;
    runtime_image()?;
    let job = Job::new(login);
    let result = crate::rt::timeout(Duration::from_secs(45), async {
        job.prepare().await?;
        let args = if binary == "claude" {
            ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--setting-sources", "", "--strict-mcp-config", "--tools", "", "--disable-slash-commands", "--no-session-persistence"].iter().map(|s| s.to_string()).collect()
        } else { vec!["debug".into(), "models".into()] };
        let input = if binary == "claude" { format!("{}\n",json!({"type":"control_request","request_id":"init","request":{"subtype":"initialize"}})).into_bytes() } else { vec![] };
        let mut initialized = None;
        let output = launch(&job, login, binary, args, input, false, &mut |line| {
            if binary == "claude" {
                if let Ok(event) = serde_json::from_str::<Value>(line) {
                    if event["type"] == "control_response" && event["response"]["request_id"] == "init" { initialized = Some(event["response"].clone()); return true; }
                }
            }
            false
        }).await?;
        let body = if binary == "claude" {
            let response = initialized.context("Claude did not report a model catalog")?;
            if response["subtype"] == "error" { bail!("Claude could not list models. Check its sandbox login and CLI version."); }
            response["response"].clone()
        } else { serde_json::from_slice(&output).context("Codex did not report a valid model catalog")? };
        let mut result = parse_catalog(binary, &body)?;
        // A second container shares only this job's copied credentials; version failures do not
        // invalidate a catalog. The first initialized Claude process is stopped before reuse.
        remove_container(&job.name).await;
        let version = crate::rt::timeout(Duration::from_secs(10), launch(&job, login, binary, vec!["--version".into()], vec![], false, &mut |_| false)).await;
        if let Ok(Ok(bytes)) = version {
            if let Some(version) = VERSION.get_or_init(|| regex::Regex::new(r"\d+\.\d+\.\d[^\s]*").unwrap()).find(&String::from_utf8_lossy(&bytes)) { result["version"] = json!(version.as_str()); }
        }
        Ok(result)
    }).await.context("The sandbox CLI did not list models within 45 seconds")?;
    remove_container(&job.name).await;
    result
}
fn parse_catalog(binary: &str, body: &Value) -> Result<Value> {
    static NUMERIC_VERSION: OnceLock<regex::Regex> = OnceLock::new();
    static CONTEXT_SUFFIX: OnceLock<regex::Regex> = OnceLock::new();
    static DATE_SUFFIX: OnceLock<regex::Regex> = OnceLock::new();
    let mut rows: Vec<&Value> = body["models"]
        .as_array()
        .context("The CLI returned no model list")?
        .iter()
        .collect();
    if binary == "codex" {
        rows.sort_by(|a, b| {
            a["priority"]
                .as_f64()
                .unwrap_or(f64::INFINITY)
                .total_cmp(&b["priority"].as_f64().unwrap_or(f64::INFINITY))
        });
    }
    let mut models = vec![];
    let mut seen = std::collections::HashSet::new();
    for row in rows {
        let Some(id) = row[if binary == "claude" { "value" } else { "slug" }]
            .as_str()
            .filter(|s| !s.is_empty() && s.len() <= 256)
        else {
            continue;
        };
        if row["visibility"] == "hide" || !seen.insert(id) {
            continue;
        }
        let mut name = row[if binary == "claude" {
            "displayName"
        } else {
            "display_name"
        }]
        .as_str()
        .unwrap_or(id)
        .to_string();
        let mut description = row["description"].as_str().map(str::to_string);
        if binary == "claude" && id != "default" && name.chars().all(|c| c.is_ascii_alphabetic()) {
            if let Some((head, tail)) = description.as_deref().and_then(|d| d.split_once(" · ")) {
                if head.split_once(' ').is_some_and(|(family, version)| {
                    family.eq_ignore_ascii_case(&name)
                        && NUMERIC_VERSION
                            .get_or_init(|| regex::Regex::new(r"^\d+(?:\.\d+)?$").unwrap())
                            .is_match(version)
                }) {
                    name = head.to_owned();
                    description = Some(tail.to_owned());
                }
            }
        }
        let efforts: Vec<&str> = if binary == "claude" {
            row["supportedEffortLevels"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .collect()
        } else {
            row["supported_reasoning_levels"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|l| l["effort"].as_str())
                .collect()
        };
        let mut model = json!({"id":id,"name":name,"efforts":efforts});
        if let Some(description) = description {
            model["description"] = json!(description);
        }
        if binary == "claude" {
            if id == "default" {
                model["isDefault"] = json!(true);
            }
            let resolved = row["resolvedModel"].as_str().unwrap_or(id);
            let without_context = CONTEXT_SUFFIX
                .get_or_init(|| regex::Regex::new(r"\[[^\]]*\]$").unwrap())
                .replace(resolved, "")
                .to_string();
            let base = DATE_SUFFIX
                .get_or_init(|| regex::Regex::new(r"-\d{8}$").unwrap())
                .replace(&without_context, "")
                .to_string();
            let default = if ["claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"]
                .contains(&base.as_str())
            {
                "medium"
            } else if base == "claude-opus-4-7" {
                "xhigh"
            } else {
                "high"
            };
            if efforts.contains(&default) {
                model["defaultEffort"] = json!(default);
            }
        } else {
            if models.is_empty() {
                model["isDefault"] = json!(true);
            }
            if let Some(effort) = row["default_reasoning_level"].as_str() {
                model["defaultEffort"] = json!(effort);
            }
        }
        models.push(model);
    }
    if models.is_empty() {
        bail!("The CLI lists no available models");
    }
    let mut catalog =
        json!({"engine":if binary == "claude" {"claude-code"} else {"codex"},"models":models});
    if binary == "claude" {
        if let Some(account) = body["account"]["subscriptionType"].as_str() {
            catalog["account"] = json!(account);
        }
    }
    serde_json::from_str(&crate::ai::sanitize(&catalog.to_string()))
        .context("Invalid catalog metadata")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn saved_codex_login_excludes_api_keys_and_preserves_oauth_refresh() {
        // Execute the production scripts with an in-memory filesystem and synthetic values only.
        let fixture = r#"const assert=require('assert/strict'),vm=require('vm'),crypto=require('crypto');
const [bootstrap,persist]=process.argv.slice(1),source='/login/.codex/auth.json',home='/job/home/.codex/auth.json',fingerprint='/job/state/auth-original';
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
function fixture(saved){
  const files=new Map(),writes=[],renames=[];
  const set=(p,v,extra={})=>files.set(p,{data:typeof v==='string'?v:JSON.stringify(v),...extra});
  const get=p=>{assert(files.has(p),'Unexpected filesystem read: '+p);return files.get(p);};
  const read=p=>get(p).data;
  const fs={
    lstatSync(p){const f=get(p);return {isFile:()=>f.regular!==false,isSymbolicLink:()=>!!f.symlink,size:f.size??Buffer.byteLength(f.data)};},
    realpathSync:p=>get(p).realpath??p,
    readFileSync:read,
    writeFileSync(p,data,options){assert.equal(options.mode,0o600);if(p.includes('.pulls-')){assert.equal(options.flag,'wx');assert(!files.has(p));}writes.push(p);set(p,String(data));},
    renameSync(from,to){assert(from.startsWith(to+'.pulls-'));renames.push([from,to]);files.set(to,get(from));files.delete(from);},
    unlinkSync(p){assert(files.delete(p));}
  };
  const stopped=Symbol('exit');
  const run=script=>{try{vm.runInNewContext(script,{
    require:name=>{if(name==='fs')return fs;assert(['crypto','path'].includes(name));return require(name);},
    process:{argv:['node','codex'],exit:code=>{assert.equal(code,0);throw stopped;}}
  },{timeout:1000});}catch(error){if(error!==stopped)throw error;}};
  set(source,saved);return {files,writes,renames,set,read,run};
}
const login={auth_mode:'chatgpt',tokens:{access_token:'synthetic-access',refresh_token:'synthetic-refresh'},last_refresh:'before'};
let f=fixture({auth_mode:'apikey',OPENAI_API_KEY:'synthetic-key'});
assert.throws(()=>f.run(bootstrap),/Missing login/);assert(!f.files.has(home));
f=fixture({...login,OPENAI_API_KEY:'synthetic-key',settings:{unrelated:true}});
const original=f.read(source);f.run(bootstrap);
assert.deepEqual(JSON.parse(f.read(home)),login);assert.equal(f.read(fingerprint),hash(original));
const refreshed={...login,tokens:{access_token:'synthetic-new-access',refresh_token:'synthetic-new-refresh'},last_refresh:'after'};
f.set(home,{...refreshed,OPENAI_API_KEY:'synthetic-new-key',settings:{unrelated:true}});f.run(persist);
assert.deepEqual(JSON.parse(f.read(source)),refreshed);assert.equal(f.read(fingerprint),hash(f.read(source)));
assert.equal(f.renames.length,1);assert(![...f.files.keys()].some(p=>p.includes('.pulls-')));
const current={...login,last_refresh:'newer-concurrent-login'},priorHash=f.read(fingerprint),priorWrites=f.writes.length;
f.set(source,current);f.run(persist);
assert.deepEqual(JSON.parse(f.read(source)),current);assert.equal(f.read(fingerprint),priorHash);assert.equal(f.writes.length,priorWrites);
f=fixture(login);f.run(bootstrap);f.set(home,{auth_mode:'apikey',OPENAI_API_KEY:'synthetic-key'});
assert.throws(()=>f.run(persist),/Invalid login/);assert.deepEqual(JSON.parse(f.read(source)),login);assert.equal(f.renames.length,0);
for(const invalid of [{regular:false},{symlink:true},{size:524289},{realpath:'/outside/auth.json'}]){
  f=fixture(login);Object.assign(f.files.get(source),invalid);assert.throws(()=>f.run(bootstrap),/Invalid login/);
  f=fixture(login);f.run(bootstrap);Object.assign(f.files.get(home),invalid);assert.throws(()=>f.run(persist),/Invalid login/);assert.equal(f.renames.length,0);
}
"#;
        let mut node = Command::new("node");
        node.env_clear();
        if let Some(path) = crate::rt::env::var_os("PATH") {
            node.env("PATH", path);
        }
        let output = crate::rt::timeout(
            Duration::from_secs(5),
            node.args(["-e", fixture, LOGIN_BOOTSTRAP_SCRIPT, LOGIN_PERSIST_SCRIPT])
                .kill_on_drop(true)
                .output(),
        )
        .await
        .expect("Synthetic login fixtures timed out")
        .expect("Node is required to check the sandbox login scripts");
        assert!(
            output.status.success(),
            "Synthetic login fixtures failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    #[test]
    fn catalog_keeps_account_models_and_efforts() {
        let c=parse_catalog("claude",&json!({"account":{"subscriptionType":"max"},"models":[{"value":"opus","displayName":"Opus","description":"Opus 5.5 · Complex work","resolvedModel":"claude-opus-5-5-20261001[1m]","supportedEffortLevels":["low","medium","high"]}]})).unwrap();
        assert_eq!(c["models"][0]["name"], "Opus 5.5");
        assert_eq!(c["models"][0]["defaultEffort"], "medium");
        assert_eq!(c["account"], "max");
        let c=parse_catalog("codex",&json!({"models":[{"slug":"old","priority":3},{"slug":"hidden","priority":0,"visibility":"hide"},{"slug":"new","priority":1,"supported_reasoning_levels":[{"effort":"high"}],"default_reasoning_level":"high"}]})).unwrap();
        assert_eq!(c["models"][0]["id"], "new");
        assert_eq!(c["models"][0]["isDefault"], true);
        assert_eq!(c["models"].as_array().unwrap().len(), 2);
    }
    #[test]
    fn mount_options_cannot_be_injected() {
        assert!(mount(Path::new("/tmp/source,readonly=false"), "/inputs", true).is_err());
        assert_eq!(
            mount(Path::new("/tmp/safe"), "/inputs", true).unwrap(),
            "type=bind,source=/tmp/safe,target=/inputs,readonly"
        );
    }
    #[tokio::test]
    async fn stdout_streaming_handles_chunked_unicode_and_early_catalog() {
        let (mut writer, reader) = tokio::io::duplex(128);
        let task = crate::rt::spawn(async move {
            writer.write_all(b"{\"text\":\"\xe2").await.unwrap();
            writer.write_all(b"\x98\x83\"}\nlast").await.unwrap();
        });
        let mut seen = vec![];
        let (_, early) = read_stdout(reader, &mut |line| {
            seen.push(line.to_owned());
            false
        })
        .await
        .unwrap();
        task.await.unwrap();
        assert!(!early);
        assert_eq!(seen, vec!["{\"text\":\"☃\"}", "last"]);
    }

    #[tokio::test]
    async fn stdout_cursor_preserves_chunk_and_eof_boundaries() {
        let long = format!("{}😀", "x".repeat(9000));
        let input = format!("\n{long}\r\nlast\r");
        for capacity in [1, 3, 8192] {
            let bytes = input.as_bytes().to_vec();
            let (mut writer, reader) = tokio::io::duplex(capacity);
            let task = crate::rt::spawn(async move {
                writer.write_all(&bytes).await.unwrap();
            });
            let mut seen = vec![];
            let (raw, early) = read_stdout(reader, &mut |line| {
                seen.push(line.to_owned());
                false
            })
            .await
            .unwrap();
            task.await.unwrap();
            assert_eq!(raw, input.as_bytes());
            assert!(!early);
            assert_eq!(seen, ["", &long, "last\r"]);
        }
        let input = b"first\r\nsecond\n";
        let (raw, early) = read_stdout(&input[..], &mut |line| {
            assert_eq!(line, "first");
            true
        })
        .await
        .unwrap();
        assert!(early);
        assert_eq!(raw, input);
        for invalid in [&b"\xff\n"[..], &b"\xff"[..]] {
            let error = read_stdout(invalid, &mut |_| false).await.unwrap_err();
            assert_eq!(error.to_string(), "The CLI emitted invalid UTF-8");
        }
    }
}
