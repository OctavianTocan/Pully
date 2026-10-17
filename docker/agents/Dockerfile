# The Claude Code and Codex CLIs that pulls.review runs its AI work with, in a container whose only
# skills are the curated ones in ./skills. Build it with `docker/agents/setup.sh build`.
ARG NODE_IMAGE=node:22-bookworm-slim
FROM ${NODE_IMAGE}

ARG CLAUDE_CODE_VERSION=latest
ARG CODEX_VERSION=latest
# The agent user takes the server owner's ids (setup.sh passes them), so it can write the job folders
# the server creates with mode 0700 and mounts into the container.
ARG AGENT_UID=1000
ARG AGENT_GID=1000

# git and ripgrep support the provider CLIs; flock (util-linux) protects shared login updates.
# PR tools and GitHub credentials stay on the native server, outside the model container.
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates git ripgrep util-linux \
  && rm -rf /var/lib/apt/lists/*

RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
  && npm cache clean --force

RUN userdel -r node 2>/dev/null || true \
  && groupadd -o -g "${AGENT_GID}" agent \
  && useradd -o -m -d /home/agent -s /bin/bash -u "${AGENT_UID}" -g agent agent

COPY skills /opt/pulls-review/skills
COPY entrypoint.sh /usr/local/bin/pulls-review-agent-entrypoint
RUN chmod 0755 /usr/local/bin/pulls-review-agent-entrypoint && chmod -R a+rX /opt/pulls-review

# A global npm install is root's to update; the image is rebuilt instead.
ENV DISABLE_AUTOUPDATER=1
USER agent
WORKDIR /home/agent
ENTRYPOINT ["pulls-review-agent-entrypoint"]
CMD ["bash"]
