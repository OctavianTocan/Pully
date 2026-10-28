//! Pully's names for the shared Claude Code and Codex layer (`ai-kit`): its Docker volumes and
//! image, the PR tools jobs reach through this server's own binary, and its sign-in hints. Every
//! entry point configures them before AI work, since a volume name read by nobody would lose
//! people's sign-ins.
use crate::agent_login::AgentLogin;
use crate::ai::StructuredAnswerPreparation;
use ai_kit::{Config, Preparation, Tools};

/// Pully's agent names. `volume` and `lock_file` are where existing sign-ins live; they MUST
/// stay as they are.
static PULLY: Config = Config {
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
    missing,
    secrets: &["PULLS_REVIEW_PI_API_KEY"],
    preparations: &["grouping"],
    prepare,
};

/// Configures the shared agent layer with Pully's names. Safe to call more than once.
pub fn configure_agents() {
    ai_kit::configure(&PULLY).expect("Pully is the only host of its AI kit");
}

fn missing(login: &AgentLogin, name: &str, binary: &str) -> String {
    match login {
        AgentLogin::Server => format!("{name} is not signed in on the server. Sign in to your Pully account and connect {name} in Settings, or run docker/agents/setup.sh login-{binary} on the server."),
        AgentLogin::Account(_) => format!("Sign in to {name} first: Settings, AI, {name}."),
    }
}

fn prepare(
    preparation: Preparation,
    schema: &serde_json::Value,
    raw: &serde_json::Value,
) -> anyhow::Result<serde_json::Value> {
    let preparation = match preparation.0 {
        "grouping" => StructuredAnswerPreparation::Grouping,
        _ => StructuredAnswerPreparation::Strict,
    };
    crate::ai::prepare_structured_answer(schema, raw, preparation)
}

impl From<StructuredAnswerPreparation> for Preparation {
    fn from(preparation: StructuredAnswerPreparation) -> Self {
        match preparation {
            StructuredAnswerPreparation::Strict => Preparation::STRICT,
            StructuredAnswerPreparation::Grouping => Preparation("grouping"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pully_keeps_its_volumes_hints_and_secret_names() {
        configure_agents();
        assert_eq!(ai_kit::config().secrets, &["PULLS_REVIEW_PI_API_KEY"]);
        assert_eq!(AgentLogin::Server.volume(), "pulls-review-agents-home");
        assert!(AgentLogin::Account("a".into())
            .volume()
            .starts_with("pulls-review-agents-home-"));
        assert_eq!(
            Preparation::named("grouping").unwrap(),
            Preparation("grouping")
        );
        assert!(Preparation::named("anything").is_err());
        assert_eq!(
            AgentLogin::Account("a".into()).missing("codex"),
            "Sign in to Codex first: Settings, AI, Codex."
        );
    }
}
