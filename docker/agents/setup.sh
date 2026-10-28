#!/usr/bin/env bash
# Builds the Docker image pulls.review runs Claude Code and Codex in, and signs them in, once, into the
# server's own login volume, used without a Pully account. People with a Pully account sign their own
# in from the phone instead (ai-kit/src/login.rs). Native jobs copy only selected login fields.
# Every command can be run again: a build reuses what has not changed,
# and a sign-in replaces the one before it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
image="${PULLS_REVIEW_AGENT_IMAGE:-pulls-review-agents}"
volume="pulls-review-agents-home"

usage() {
  cat <<EOF
Usage: docker/agents/setup.sh <command>

  build          build the $image image; again after changing skills/
  login-claude   sign Claude Code in, inside the container
  login-codex    sign Codex in, inside the container
  check          print both CLI versions and what is signed in
  all            build, sign in whatever is not signed in yet, then check
EOF
}

need_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed. See docker/agents/README.md." >&2
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "Docker is not running. Start Docker Desktop (or the docker service) and try again." >&2
    exit 1
  fi
}

need_terminal() {
  if [ ! -t 0 ] || [ ! -t 1 ]; then
    echo "$1 asks you to sign in, so run it yourself in a terminal." >&2
    exit 1
  fi
}

# Runs setup commands in the image on the shared login volume. Model jobs never mount this volume.
# Docker options come first, then the image and the command.
in_container() {
  docker run --rm --init --pull never --mount "type=volume,source=$volume,target=/home/agent" "$@"
}

# The newest published version, so a rebuild installs a new CLI instead of reusing the cached layer.
latest() {
  npm view "$1" version 2>/dev/null || echo latest
}

build() {
  need_docker
  local uid gid
  uid="$(id -u)"
  gid="$(id -g)"
  # The agent user takes your ids so it can write the job folders the server makes; never root's.
  if [ "$uid" = 0 ]; then
    uid=1000
    gid=1000
  fi
  docker build \
    --build-arg "AGENT_UID=$uid" \
    --build-arg "AGENT_GID=$gid" \
    --build-arg "CLAUDE_CODE_VERSION=${CLAUDE_CODE_VERSION:-$(latest @anthropic-ai/claude-code)}" \
    --build-arg "CODEX_VERSION=${CODEX_VERSION:-$(latest @openai/codex)}" \
    --tag "$image" \
    "$here"
}

login_claude() {
  need_docker
  need_terminal login-claude
  echo "Signing Claude Code in: open the link it prints, approve, then paste the code here."
  in_container -it "$image" claude auth login
}

login_codex() {
  need_docker
  need_terminal login-codex
  echo "Signing Codex in: open the link it prints and enter the code it shows."
  in_container -it "$image" codex login --device-auth
}

signed_in() {
  case "$1" in
    claude) in_container "$image" claude auth status ;;
    codex) in_container "$image" codex login status ;;
  esac >/dev/null 2>&1
}

check() {
  need_docker
  in_container "$image" sh -c 'echo "Claude Code $(claude --version)"; echo "Codex $(codex --version)"; echo "Skills: $(ls "$HOME/.claude/skills" | tr "\n" " ")"'
  local missing=0 name
  for name in claude codex; do
    if signed_in "$name"; then
      echo "$name: signed in"
    else
      echo "$name: not signed in; run docker/agents/setup.sh login-$name"
      missing=1
    fi
  done
  return "$missing"
}

all() {
  build
  signed_in claude || login_claude
  signed_in codex || login_codex
  check
}

case "${1:-}" in
  build) build ;;
  login-claude) login_claude ;;
  login-codex) login_codex ;;
  check) check ;;
  all) all ;;
  help | -h | --help | "") usage ;;
  *)
    usage >&2
    exit 2
    ;;
esac
