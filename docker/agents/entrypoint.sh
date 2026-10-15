#!/bin/sh
# Runs before every command in the pulls-review-agents image. The home folder is a volume that keeps the
# Claude Code and Codex logins between runs; its skill folders are replaced with exactly the curated set
# baked into the image, so no other skill can be there, and a rebuilt image brings its skills with it.
set -eu

skills=/opt/pulls-review/skills

# Several runs can start at once on the same volume, so one replaces the folders at a time.
exec 9>"$HOME/.pulls-review-skills.lock"
flock 9
for dir in "$HOME/.claude/skills" "$HOME/.codex/skills"; do
  if ! rm -rf "$dir" 2>/dev/null; then
    # Only happens when the image was rebuilt for another user than the one that filled the volume.
    echo "$dir belongs to another user. Run \`docker volume rm pulls-review-agents-home\`, then \`docker/agents/setup.sh all\` to sign in again." >&2
    exit 1
  fi
  mkdir -p "$dir"
  cp -R "$skills/." "$dir/"
done
# Newer CLIs also read skills shared between agents from here; nothing is shared into this home.
rm -rf "$HOME/.agents/skills"
flock -u 9
exec 9>&-

exec "$@"
