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

# git and gh serve the pull request tools (read_source, find_files, read_discussion); ripgrep is what
# the CLIs search with. gh comes from GitHub's own repository, since Debian's is years behind.
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep \
  && install -d -m 0755 /etc/apt/keyrings \
  && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg -o /etc/apt/keyrings/githubcli-archive-keyring.gpg \
  && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
  && apt-get update \
  && apt-get install -y --no-install-recommends gh \
  && apt-get purge -y curl \
  && apt-get autoremove -y \
  && rm -rf /var/lib/apt/lists/*

RUN npm install -g "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" "@openai/codex@${CODEX_VERSION}" \
  && npm cache clean --force

# The checkout the server runs in is mounted read-only and belongs to the host user, which git would
# otherwise refuse as "dubious ownership".
RUN git config --system --add safe.directory '*'

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
