//! What a host names: its Docker volumes and image, its environment variables, its tool helper and
//! the sentences it shows. Set once per process with [`configure`] before any AI work.
use crate::login::AgentLogin;
use anyhow::Result;
use serde_json::Value;
use std::sync::OnceLock;

static CONFIG: OnceLock<&'static Config> = OnceLock::new();

/// A host's names for the agent layer. Changing `volume` or `lock_file` on a running server loses
/// every saved sign-in, so a host keeps them for good once people have signed in.
pub struct Config {
    /// The Docker volume of the server's own sign-in. An account's is this name, `-`, then 32 hex
    /// digits of its ID's SHA-256.
    pub volume: &'static str,
    /// Starts the names of sign-in and job containers: `{prefix}-login-…` and `{prefix}-agent-…`.
    pub container_prefix: &'static str,
    /// The lock file in a login's home that keeps two runs from saving a refreshed login at once.
    pub lock_file: &'static str,
    /// Starts the host's environment variables: `{env}_RUNTIME`, `{env}_IMAGE` and `{env}_MCP_BINARY`.
    pub env: &'static str,
    /// The agent image when `{env}_IMAGE` is unset.
    pub image: &'static str,
    /// How to build the agent image, for the error when Docker cannot start one.
    pub image_setup: &'static str,
    /// The file only the hosted agent image has; the runner refuses to start without it.
    pub container_marker: &'static str,
    /// The program a sign-in starts through in the hosted agent container, so the image can prepare
    /// the login's home first; `None` runs the CLI itself.
    pub sign_in_program: Option<&'static str>,
    /// The helper jobs reach their tools through, or `None` when this host gives jobs no tools.
    pub tools: Option<Tools>,
    /// What to tell the person when `login` has no sign-in for the CLI named `name` (`binary`).
    pub missing: fn(login: &AgentLogin, name: &str, binary: &str) -> String,
    /// Environment variables always redacted from model output, whatever their length, besides
    /// the ones [`crate::redact`] always covers.
    pub secrets: &'static [&'static str],
    /// Answer preparations this host offers besides [`Preparation::STRICT`].
    pub preparations: &'static [&'static str],
    /// Makes a structured answer ready and checks it against its schema.
    pub prepare: fn(Preparation, schema: &Value, raw: &Value) -> Result<Value>,
}

/// A native helper a job reaches its tools through, over MCP on stdio.
pub struct Tools {
    /// Where the helper is mounted in a Docker job.
    pub target: &'static str,
    /// The helper's argument before the job's context file.
    pub context_arg: &'static str,
    /// The MCP server name; the CLIs call its tools `mcp__{server}__{tool}`.
    pub server: &'static str,
}

/// How a structured answer is made ready before it is checked, chosen by the host's code and
/// never by a request. [`Preparation::STRICT`] checks the answer as it came.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Preparation(pub &'static str);

impl Preparation {
    pub const STRICT: Self = Self("strict");

    /// The preparation a wire name stands for, when this host offers it.
    pub fn named(name: &str) -> Result<Self> {
        if name == Self::STRICT.0 {
            return Ok(Self::STRICT);
        }
        config()
            .preparations
            .iter()
            .find(|offered| **offered == name)
            .map(|offered| Self(offered))
            .ok_or_else(|| anyhow::anyhow!("Invalid answer preparation"))
    }
}

/// Names this process's agent layer for the life of the process. Later calls with the same config
/// do nothing; another config is an error, so one process never mixes two hosts' volumes.
pub fn configure(config: &'static Config) -> Result<()> {
    let set = CONFIG.get_or_init(|| config);
    anyhow::ensure!(
        std::ptr::eq(*set, config),
        "The AI kit is already configured for another host"
    );
    Ok(())
}

/// The config given to [`configure`]. Panics when it has not run: a host that skipped it would
/// sign people in under names it never reads again.
pub fn config() -> &'static Config {
    CONFIG
        .get()
        .copied()
        .expect("ai_kit::configure must run before AI work")
}

/// The config, when one is set; redaction works without one.
pub(crate) fn try_config() -> Option<&'static Config> {
    CONFIG.get().copied()
}

/// The check [`Preparation::STRICT`] does: the unchanged answer, once it matches its schema.
pub fn strict(schema: &Value, raw: &Value) -> Result<Value> {
    crate::schema::validate_schema(schema, raw)?;
    Ok(raw.clone())
}

#[cfg(test)]
pub(crate) fn for_tests() {
    static TEST: Config = Config {
        volume: "pulls-review-agents-home",
        container_prefix: "pulls",
        lock_file: ".pulls-review-auth.lock",
        env: "PULLS_REVIEW_AGENT",
        image: "pulls-review-agents",
        image_setup: "docker/agents/setup.sh build",
        container_marker: "/.pully-agent-container",
        sign_in_program: Some("pulls-review-agent-entrypoint"),
        tools: Some(Tools {
            target: "/opt/pulls-review-server",
            context_arg: "--mcp-context",
            server: "pr",
        }),
        missing: |_, name, _| format!("Sign in to {name} first."),
        secrets: &[],
        preparations: &[],
        prepare: |_, schema, raw| strict(schema, raw),
    };
    let _ = configure(&TEST);
}
