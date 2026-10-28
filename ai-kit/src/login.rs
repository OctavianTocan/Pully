//! Each person's own Claude Code and Codex sign-in. A signed-in account has its own Docker volume
//! that only the CLIs' own login commands write; AI work for that account copies its login from
//! there and nowhere else. Requests without an account use the server's own sign-in. There are no
//! API keys on either path.
use crate::rt::process::{Child, ChildStdin, Command};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    process::Stdio,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    sync::watch,
};

const OUTPUT_LIMIT: usize = 64 * 1024;
/// Codex's device code lasts 15 minutes; nothing waits on a sign-in longer than that.
const LOGIN_LIFETIME: Duration = Duration::from_secs(15 * 60);

/// Whose subscription a CLI run or sign-in uses. Never chosen by a request body.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum AgentLogin {
    /// No account on the request: the server's own sign-in.
    Server,
    /// A signed-in account, by its account ID.
    Account(String),
}
impl AgentLogin {
    /// The Docker volume (in the hosted agent container, the folder) that holds this login's
    /// CLI homes.
    pub fn volume(&self) -> String {
        let server = crate::config().volume;
        match self {
            Self::Server => server.into(),
            Self::Account(id) => {
                let digest = Sha256::digest(id.as_bytes());
                let hex: String = digest[..16].iter().map(|b| format!("{b:02x}")).collect();
                format!("{server}-{hex}")
            }
        }
    }
    /// What to do when this login has no sign-in for `binary`.
    pub fn missing(&self, binary: &str) -> String {
        (crate::config().missing)(self, display(binary), binary)
    }
}

pub fn display(binary: &str) -> &'static str {
    if binary == "claude" {
        "Claude Code"
    } else {
        "Codex"
    }
}

struct Pending {
    container: String,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    output: Arc<Mutex<String>>,
    /// None while the CLI runs, then whether it exited successfully.
    done: watch::Receiver<Option<bool>>,
}

/// Sign-ins in progress, one per login and CLI. Dropping one stops its container.
#[derive(Default)]
pub struct Logins {
    pending: Mutex<HashMap<(String, String), Arc<Pending>>>,
}

impl Logins {
    fn take(&self, login: &AgentLogin, binary: &str) -> Option<Arc<Pending>> {
        self.pending
            .lock()
            .unwrap()
            .remove(&(login.volume(), binary.to_owned()))
    }
    fn get(&self, login: &AgentLogin, binary: &str) -> Option<Arc<Pending>> {
        self.pending
            .lock()
            .unwrap()
            .get(&(login.volume(), binary.to_owned()))
            .cloned()
    }

    /// Starts the CLI's own sign-in and answers with the link to open (and Codex's code).
    pub async fn start(&self, login: &AgentLogin, binary: &str) -> Result<Value> {
        if crate::remote::get().is_some() {
            let body = json!({"binary": binary});
            return crate::remote::call(login, "/login/start", body).await;
        }
        if let Some(old) = self.take(login, binary) {
            crate::process::remove_container(&old.container).await;
        }
        let runtime = crate::process::runtime()?;
        let container = login_name();
        let cli: &[&str] = if binary == "claude" {
            &["claude", "auth", "login", "--claudeai"]
        } else {
            &["codex", "login", "--device-auth"]
        };
        let mut stop = None;
        let mut command = match &runtime {
            crate::process::Runtime::Docker(image) => {
                let mut command = login_container(&container, &login.volume(), true);
                command.arg(image).args(cli);
                command
            }
            crate::process::Runtime::Local(_) => {
                let home = local_home(&runtime, login, true).await?;
                // The host's sign-in program can prepare the home first, as its image does in Docker.
                let mut command = match crate::config().sign_in_program {
                    Some(program) => {
                        let mut command = crate::process::local_command(program, &home);
                        command.args(cli);
                        command
                    }
                    None => {
                        let mut command = crate::process::local_command(cli[0], &home);
                        command.args(&cli[1..]);
                        command
                    }
                };
                command.env("BROWSER", "true");
                stop = Some(crate::process::local_stop(&container));
                command
            }
        };
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|_| anyhow::anyhow!("Could not start Docker on the server."))?;
        let output = Arc::new(Mutex::new(String::new()));
        let stdout = child.stdout.take().context("Sign-in output unavailable")?;
        let stderr = child.stderr.take().context("Sign-in output unavailable")?;
        crate::rt::spawn(collect(stdout, output.clone()));
        crate::rt::spawn(collect(stderr, output.clone()));
        let (sender, done) = watch::channel(None);
        let pending = Arc::new(Pending {
            container: container.clone(),
            stdin: tokio::sync::Mutex::new(child.stdin.take()),
            output,
            done,
        });
        crate::rt::spawn(supervise(child, container, stop, sender));
        // The link comes first; a CLI that ends before printing one has failed to start.
        let link = crate::rt::timeout(Duration::from_secs(45), async {
            loop {
                let found = {
                    let output = pending.output.lock().unwrap();
                    link(binary, &output)
                };
                if found.is_some() {
                    return found;
                }
                let ended = pending.done.borrow().is_some();
                if ended {
                    return None;
                }
                crate::rt::sleep(Duration::from_millis(100)).await;
            }
        })
        .await
        .ok()
        .flatten();
        let Some((url, code)) = link else {
            crate::process::remove_container(&pending.container).await;
            bail!("{} did not start its sign-in. Try again.", display(binary));
        };
        self.pending
            .lock()
            .unwrap()
            .insert((login.volume(), binary.to_owned()), pending);
        Ok(json!({"url":url,"code":code,"paste":binary == "claude"}))
    }

    /// Claude Code takes the code its sign-in page shows; Codex finishes by itself, so this waits
    /// up to 25 seconds for it and answers `pending` while the person is still approving.
    pub async fn finish(
        &self,
        login: &AgentLogin,
        binary: &str,
        code: Option<&str>,
    ) -> Result<Value> {
        if crate::remote::get().is_some() {
            let body = json!({"binary": binary, "code": code});
            return crate::remote::call(login, "/login/finish", body).await;
        }
        let pending = self
            .get(login, binary)
            .with_context(|| format!("That {} sign-in ended. Start again.", display(binary)))?;
        let wait = if binary == "claude" {
            let code = code
                .map(str::trim)
                .filter(|code| {
                    !code.is_empty()
                        && code.len() <= 4096
                        && !code.chars().any(|c| c.is_control() || c.is_whitespace())
                })
                .context("Paste the code from the sign-in page.")?;
            let mut stdin = pending.stdin.lock().await;
            let mut input = stdin
                .take()
                .context("That code was already sent. Start again.")?;
            input
                .write_all(format!("{code}\n").as_bytes())
                .await
                .context("The sign-in ended. Start again.")?;
            let _ = input.shutdown().await;
            Duration::from_secs(60)
        } else {
            Duration::from_secs(25)
        };
        let mut done = pending.done.clone();
        let finished = crate::rt::timeout(wait, async {
            done.wait_for(Option::is_some)
                .await
                .ok()
                .and_then(|value| *value)
        })
        .await;
        let outcome = match finished {
            Ok(outcome) => outcome,
            Err(_) if binary == "codex" => return Ok(json!({"signedIn":false,"pending":true})),
            Err(_) => None,
        };
        self.take(login, binary);
        crate::process::remove_container(&pending.container).await;
        if outcome != Some(true) {
            let text = pending.output.lock().unwrap().to_lowercase();
            if binary == "claude"
                && (text.contains("invalid code") || text.contains("login failed"))
            {
                bail!("That code didn't work. Start again and paste the whole code.");
            }
            if text.contains("device code") && text.contains("enable") {
                bail!(
                    "Turn on device code sign-in in ChatGPT's security settings, then start again."
                );
            }
            bail!("{} sign-in did not finish. Start again.", display(binary));
        }
        Ok(json!({"signedIn":true,"pending":false}))
    }

    pub async fn cancel(&self, login: &AgentLogin, binary: &str) {
        if crate::remote::get().is_some() {
            let body = json!({"binary": binary});
            let _ = crate::remote::call(login, "/login/cancel", body).await;
            return;
        }
        if let Some(pending) = self.take(login, binary) {
            crate::process::remove_container(&pending.container).await;
        }
    }
}

/// Both CLIs' sign-in state for a login, read without a network and without creating a volume.
pub async fn status(login: &AgentLogin) -> Result<Value> {
    if crate::remote::get().is_some() {
        return crate::remote::call(login, "/status", json!({})).await;
    }
    let signed_out = json!({"signedIn":false});
    let runtime = crate::process::runtime()?;
    if !login_exists(&runtime, login).await {
        return Ok(json!({"claude":signed_out,"codex":signed_out}));
    }
    const SCRIPT: &str =
        "claude auth status --json 2>/dev/null; echo; echo @@codex@@; codex login status 2>&1";
    let container = login_name();
    let mut command = match &runtime {
        crate::process::Runtime::Docker(image) => {
            let mut command = login_container(&container, &login.volume(), false);
            command.args(["--entrypoint", "sh", image, "-c", SCRIPT]);
            command
        }
        crate::process::Runtime::Local(_) => {
            let home = local_home(&runtime, login, false).await?;
            let mut command = crate::process::local_command("sh", &home);
            command.args(["-c", SCRIPT]);
            command
        }
    };
    let output = crate::rt::timeout(
        Duration::from_secs(30),
        command.stdin(Stdio::null()).kill_on_drop(true).output(),
    )
    .await;
    crate::process::remove_container(&container).await;
    let output = output
        .context("Checking the AI sign-ins timed out")?
        .map_err(|_| anyhow::anyhow!("Could not start Docker on the server."))?;
    Ok(parse_status(&String::from_utf8_lossy(&output.stdout)))
}

/// Signs one CLI out with its own command, which also revokes the login where the CLI can.
pub async fn sign_out(login: &AgentLogin, binary: &str) -> Result<()> {
    if crate::remote::get().is_some() {
        let body = json!({"binary": binary});
        return crate::remote::call(login, "/sign-out", body)
            .await
            .map(|_| ());
    }
    let runtime = crate::process::runtime()?;
    if !login_exists(&runtime, login).await {
        return Ok(());
    }
    let container = login_name();
    let mut command = match &runtime {
        crate::process::Runtime::Docker(image) => {
            let mut command = login_container(&container, &login.volume(), true);
            command.args(["--entrypoint", binary, image]);
            command
        }
        crate::process::Runtime::Local(_) => {
            crate::process::local_command(binary, &local_home(&runtime, login, false).await?)
        }
    };
    if binary == "claude" {
        command.args(["auth", "logout"]);
    } else {
        command.arg("logout");
    }
    let result = crate::rt::timeout(
        Duration::from_secs(45),
        command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .status(),
    )
    .await;
    crate::process::remove_container(&container).await;
    result
        .context("Signing out timed out")?
        .map_err(|_| anyhow::anyhow!("Could not start Docker on the server."))?;
    Ok(())
}

fn parse_status(text: &str) -> Value {
    let (claude, codex) = text.split_once("@@codex@@").unwrap_or((text, ""));
    let claude = claude
        .lines()
        .filter_map(|line| serde_json::from_str::<Value>(line.trim()).ok())
        .find(|v| v.is_object())
        .or_else(|| serde_json::from_str::<Value>(claude.trim()).ok())
        .unwrap_or(Value::Null);
    // Only the subscription sign-ins count: a Console key or an OpenAI API key is not one.
    let mut claude_state =
        json!({"signedIn":claude["loggedIn"] == true && claude["authMethod"] == "claude.ai"});
    for (from, to) in [("email", "email"), ("subscriptionType", "plan")] {
        if let Some(value) = claude[from]
            .as_str()
            .filter(|v| !v.is_empty() && v.len() <= 256)
        {
            claude_state[to] = json!(value);
        }
    }
    let codex = strip_ansi(codex);
    let codex_state = json!({"signedIn":codex.contains("Logged in using ChatGPT")});
    json!({"claude":claude_state,"codex":codex_state})
}

/// The link the phone may open, and Codex's one-time code. Only the providers' own sign-in
/// hosts are accepted, so the phone never opens another link a CLI printed.
fn link(binary: &str, output: &str) -> Option<(String, Option<String>)> {
    static URL: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    static CODE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let text = strip_ansi(output);
    let url = URL
        .get_or_init(|| regex::Regex::new(r"https://[^\s\x1b]+").unwrap())
        .find_iter(&text)
        .map(|m| m.as_str().trim_end_matches(['.', ',', ')']).to_owned())
        .find(|url| allowed_link(binary, url))?;
    if binary == "claude" {
        return Some((url, None));
    }
    let code = CODE
        .get_or_init(|| regex::Regex::new(r"\b[A-Z0-9]{4,}-[A-Z0-9]{4,}\b").unwrap())
        .find(&text)?
        .as_str()
        .to_owned();
    Some((url, Some(code)))
}

fn allowed_link(binary: &str, url: &str) -> bool {
    let Ok(parsed) = url::Url::parse(url) else {
        return false;
    };
    let hosts: &[&str] = if binary == "claude" {
        &[
            "claude.com",
            "claude.ai",
            "platform.claude.com",
            "console.anthropic.com",
        ]
    } else {
        &["auth.openai.com", "chatgpt.com"]
    };
    parsed.scheme() == "https"
        && parsed.port().is_none()
        && parsed.username().is_empty()
        && parsed.host_str().is_some_and(|host| hosts.contains(&host))
}

fn strip_ansi(text: &str) -> String {
    static ANSI: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    ANSI.get_or_init(|| regex::Regex::new(r"\x1b\[[0-9;?]*[A-Za-z]").unwrap())
        .replace_all(text, "")
        .into_owned()
}

/// A fresh name for a sign-in, status or sign-out container.
fn login_name() -> String {
    format!(
        "{}-login-{}",
        crate::config().container_prefix,
        uuid::Uuid::new_v4()
    )
}

/// A container with only the login volume as its home: no job inputs, tools or model prompt.
fn login_container(name: &str, volume: &str, network: bool) -> Command {
    let mut command = Command::new("docker");
    command.args([
        "run",
        "--rm",
        "--interactive",
        "--init",
        "--pull",
        "never",
        "--name",
        name,
        "--read-only",
        "--tmpfs",
        "/tmp",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--pids-limit=128",
        "--memory=1g",
        "--cpus=1",
        "--env",
        "HOME=/home/agent",
        "--env",
        "NO_COLOR=1",
        "--env",
        "DISABLE_AUTOUPDATER=1",
        "--env",
        "BROWSER=true",
        "--mount",
        &format!("type=volume,source={volume},target=/home/agent"),
    ]);
    if !network {
        command.arg("--network=none");
    }
    command
}

/// Whether `login` has a CLI home yet: a Docker volume, or a folder in the agent container.
async fn login_exists(runtime: &crate::process::Runtime, login: &AgentLogin) -> bool {
    match runtime.home(&login.volume()) {
        Some(home) => crate::rt::fs::metadata(home)
            .await
            .is_ok_and(|info| info.is_dir()),
        None => volume_exists(&login.volume()).await,
    }
}

/// The CLI home of `login` in the agent container, made private when `create` is set.
async fn local_home(
    runtime: &crate::process::Runtime,
    login: &AgentLogin,
    create: bool,
) -> Result<std::path::PathBuf> {
    let home = runtime
        .home(&login.volume())
        .context("Not a local runtime")?;
    if create {
        crate::rt::fs::create_dir_all(&home).await?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            crate::rt::fs::set_permissions(&home, std::fs::Permissions::from_mode(0o700)).await?;
        }
    }
    Ok(home)
}

async fn volume_exists(volume: &str) -> bool {
    let mut inspect = Command::new("docker");
    inspect
        .args(["volume", "inspect", "--format", "{{.Name}}", volume])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .kill_on_drop(true);
    matches!(crate::rt::timeout(Duration::from_secs(15), inspect.status()).await, Ok(Ok(status)) if status.success())
}

async fn collect<R: AsyncRead + Unpin>(mut input: R, output: Arc<Mutex<String>>) {
    let mut buf = [0_u8; 4096];
    while let Ok(size) = input.read(&mut buf).await {
        if size == 0 {
            break;
        }
        let chunk = String::from_utf8_lossy(&buf[..size]).into_owned();
        let mut text = output.lock().unwrap();
        if text.len() + chunk.len() <= OUTPUT_LIMIT {
            text.push_str(&chunk);
        }
        drop(text);
    }
}

async fn supervise(
    mut child: Child,
    container: String,
    stop: Option<tokio::sync::oneshot::Receiver<()>>,
    done: watch::Sender<Option<bool>>,
) {
    // A local sign-in has no container to remove, so it is stopped through `stop`.
    let stopped = async {
        match stop {
            Some(stop) => {
                let _ = stop.await;
            }
            None => std::future::pending().await,
        }
    };
    let status = tokio::select! {
        status = crate::rt::timeout(LOGIN_LIFETIME, child.wait()) => Some(status),
        _ = stopped => None,
    };
    let success = matches!(&status, Some(Ok(Ok(status))) if status.success());
    if !matches!(status, Some(Ok(_))) {
        let _ = child.kill().await;
    }
    crate::process::remove_container(&container).await;
    done.send_replace(Some(success));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_account_has_its_own_volume_and_the_server_keeps_its_own() {
        crate::config::for_tests();
        let a = AgentLogin::Account("account-a".into()).volume();
        let b = AgentLogin::Account("account-b".into()).volume();
        assert_ne!(a, b);
        assert_eq!(AgentLogin::Server.volume(), "pulls-review-agents-home");
        assert!(
            a.starts_with("pulls-review-agents-home-")
                && a.len() == "pulls-review-agents-home-".len() + 32
        );
        assert!(a.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-'));
    }

    #[test]
    fn links_come_only_from_the_providers_sign_in_hosts() {
        let claude = "Opening browser to sign in…\nIf the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y\nPaste code here if prompted > ";
        assert_eq!(
            link("claude", claude),
            Some((
                "https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y".into(),
                None
            ))
        );
        let codex = "\x1b[94mhttps://auth.openai.com/codex/device\x1b[0m\n\n2. Enter this one-time code \x1b[90m(expires in 15 minutes)\x1b[0m\n   \x1b[94mD4V3-3LNX6\x1b[0m\n";
        assert_eq!(
            link("codex", codex),
            Some((
                "https://auth.openai.com/codex/device".into(),
                Some("D4V3-3LNX6".into())
            ))
        );
        assert_eq!(
            link("claude", "visit https://evil.example/claude.com"),
            None
        );
        assert_eq!(
            link(
                "codex",
                "visit https://auth.openai.com.evil.example/x ABCD-EFGH"
            ),
            None
        );
        assert_eq!(
            link(
                "codex",
                "https://auth.openai.com/codex/device but no code yet"
            ),
            None
        );
    }

    #[test]
    fn status_reads_both_clis() {
        let out = "{\n  \"loggedIn\": true,\n  \"authMethod\": \"claude.ai\",\n  \"email\": \"a@example.com\",\n  \"subscriptionType\": \"max\"\n}\n\n@@codex@@\nLogged in using ChatGPT\n";
        assert_eq!(
            parse_status(out),
            json!({"claude":{"signedIn":true,"email":"a@example.com","plan":"max"},"codex":{"signedIn":true}})
        );
        let out = "{\"loggedIn\":true,\"authMethod\":\"api_key\"}\n\n@@codex@@\nLogged in using an API key - sk-***\n";
        assert_eq!(
            parse_status(out),
            json!({"claude":{"signedIn":false},"codex":{"signedIn":false}})
        );
        let out = "{\"loggedIn\":false}\n\n@@codex@@\nNot logged in\n";
        assert_eq!(
            parse_status(out),
            json!({"claude":{"signedIn":false},"codex":{"signedIn":false}})
        );
        assert_eq!(
            parse_status(""),
            json!({"claude":{"signedIn":false},"codex":{"signedIn":false}})
        );
    }
}

/// Against the real agent image and Docker: `cargo test real_docker -- --ignored --nocapture`.
/// It starts both sign-ins for a throwaway account, prints their links and removes the volume.
#[cfg(test)]
mod real_docker {
    use super::*;

    #[tokio::test]
    #[ignore]
    async fn real_docker_sign_ins_print_links() {
        crate::config::for_tests();
        let login = AgentLogin::Account(format!("real-docker-{}", uuid::Uuid::new_v4()));
        let logins = Logins::default();
        assert_eq!(status(&login).await.unwrap()["claude"]["signedIn"], false);
        for binary in ["claude", "codex"] {
            let started = logins.start(&login, binary).await.unwrap();
            println!("{binary}: {started}");
            assert!(started["url"].as_str().unwrap().starts_with("https://"));
            assert_eq!(started["code"].is_string(), binary == "codex");
        }
        let finished = logins
            .finish(&login, "claude", Some("not-a-real-code#state"))
            .await;
        println!("claude with a wrong code: {finished:?}");
        assert!(finished
            .unwrap_err()
            .to_string()
            .contains("code didn't work"));
        logins.cancel(&login, "codex").await;
        println!("status: {}", status(&login).await.unwrap());
        sign_out(&login, "claude").await.unwrap();
        let _ = Command::new("docker")
            .args(["volume", "rm", "--force", &login.volume()])
            .status()
            .await;
    }
}
