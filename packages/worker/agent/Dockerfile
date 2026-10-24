# The hosted agent container: one Pully login's Claude Code and Codex, run by the server's own
# agent_runner (backend/src/agent_runner.rs) as plain processes, since this container is the
# sandbox. It is the docker/agents image plus the server binary. Built from the repository root
# (wrangler.jsonc, image_build_context); Dockerfile.dockerignore keeps the context small.
ARG RUST_IMAGE=rust:1.97-bookworm
ARG NODE_IMAGE=node:22-bookworm-slim

FROM ${RUST_IMAGE} AS server
WORKDIR /src
COPY backend backend
COPY docker/agents/skills docker/agents/skills
COPY packages/core/src/types/merge-risk-rubric.json packages/core/src/types/merge-risk-rubric.json
RUN cargo build --locked --release --manifest-path backend/Cargo.toml --bin pulls-review-server

FROM ${NODE_IMAGE}
ARG CLAUDE_CODE_VERSION=latest
ARG CODEX_VERSION=latest

# git and ripgrep support the CLIs; flock (util-linux) guards login updates.
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates git ripgrep util-linux \
  && rm -rf /var/lib/apt/lists/*

RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
  && npm cache clean --force

# The runner runs as root and keeps the login homes in /homes, which only root can read; each
# CLI job runs as pully-cli in its own job folder, so a run cannot read a saved login.
RUN userdel -r node 2>/dev/null || true \
  && groupadd -g 1001 pully-cli \
  && useradd -M -d /nonexistent -s /usr/sbin/nologin -u 1001 -g pully-cli pully-cli

COPY docker/agents/skills /opt/pulls-review/skills
COPY docker/agents/entrypoint.sh /usr/local/bin/pulls-review-agent-entrypoint
COPY --from=server /src/backend/target/release/pulls-review-server /usr/local/bin/pulls-review-server
# The marker lets agent_runner start; the native server never has it. Homes are the logins' own.
RUN chmod 0755 /usr/local/bin/pulls-review-agent-entrypoint && chmod -R a+rX /opt/pulls-review \
  && touch /.pully-agent-container \
  && install -d -o root -g root -m 0700 /homes

ENV DISABLE_AUTOUPDATER=1 \
  PULLY_AGENT_RUNNER=0.0.0.0:8080 \
  PULLY_AGENT_HOMES=/homes \
  PULLY_AGENT_CLI_USER=1001:1001
WORKDIR /
EXPOSE 8080
ENTRYPOINT ["pulls-review-server"]
