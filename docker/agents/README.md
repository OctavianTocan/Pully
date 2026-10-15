# Claude Code and Codex in Docker

pulls.review runs its AI work (analysis, reviews, questions, the inbox chat) through the Claude Code
and Codex CLIs. With `PULLS_REVIEW_AGENT_RUNTIME=docker` it runs them in the `pulls-review-agents`
image instead of on the server itself, so they only see:

- the review skills in [`skills/`](./skills), and no other skill: on every start, the image replaces
  `~/.claude/skills` and `~/.codex/skills` with exactly that folder;
- the job's own temporary folder, the server's built code (read-only, for the pull request tools)
  and the repository the server runs in (read-only, when a review reads it);
- a Docker volume, `pulls-review-agents-home`, that keeps their sign-ins between runs.

Stop on the phone stops the container too. The phone's Review sheet lists the same skills, by the
`title` in each `SKILL.md`.

In the container, Codex runs without a shell: its read-only sandbox needs Linux namespaces that a
default container does not have, so it reads the change through the pull request tools, as Claude does.

## Set it up

1. Install Docker: [Docker Desktop](https://docs.docker.com/desktop/) on macOS or Windows (WSL), or
   Docker Engine on Linux (`curl -fsSL https://get.docker.com | sh`, then log out and in after
   `sudo usermod -aG docker $USER`). Start it, and check that `docker info` answers.
2. From the repository root, run:

   ```sh
   docker/agents/setup.sh all
   ```

   It builds the image, then signs in whatever is not signed in yet:
   - Claude Code (`claude auth login`): open the link it prints, approve, and paste the code back.
   - Codex (`codex login --device-auth`): open the link and enter the code it shows. If ChatGPT
     refuses device codes, turn on device code sign-in under ChatGPT's Settings, Security, and run
     `docker/agents/setup.sh login-codex` again.
   - The GitHub CLI, which the pull request tools read files and discussions with: it copies the
     sign-in of `gh` on this machine when there is one, or asks you to sign in.

   It ends with `check`, which prints both versions and what is signed in.

3. Build the server, then start it the way you always do, with the variable set:

   ```sh
   pnpm install
   pnpm -F pulls.review run build
   PULLS_REVIEW_AGENT_RUNTIME=docker node packages/cli/bin.mjs   # plus your usual flags
   ```

   (`--agents docker` does the same as the variable.) The server prints
   `Claude Code and Codex run in the Docker image pulls-review-agents.` when it is on. The server
   must run from this checkout: the containers mount its `packages/cli/dist` folder, and the phone's
   list of skills is read from `docker/agents/skills`.

Leave the variable out to run the CLIs on the server again; nothing else changes.

## Add or remove a skill

1. Add a folder under [`skills/`](./skills) whose name is the skill's name (letters, digits, `-`,
   `_` and `.`), holding a `SKILL.md` that starts with:

   ```md
   ---
   name: my-lens
   title: My lens
   description: One sentence on what this review looks for.
   ---
   ```

   Then the instructions for the reviewer. To remove a skill, delete its folder.

2. Run `docker/agents/setup.sh build`. The next run in the container has exactly the new set.

The server reads the same folder for the phone's list straight away; restart it only if you also
changed its code. To offer skills from another folder, set `PULLS_REVIEW_SKILLS_DIR` to it.

## Other commands

| Command                               | What it does                                                   |
| ------------------------------------- | -------------------------------------------------------------- |
| `docker/agents/setup.sh build`        | Builds the image again, with the newest Claude Code and Codex. |
| `docker/agents/setup.sh login-claude` | Signs Claude Code in again.                                    |
| `docker/agents/setup.sh login-codex`  | Signs Codex in again.                                          |
| `docker/agents/setup.sh login-github` | Signs the GitHub CLI in again.                                 |
| `docker/agents/setup.sh check`        | Prints both versions and what is signed in.                    |

Pin a CLI version with `CLAUDE_CODE_VERSION=2.1.300 docker/agents/setup.sh build` (or `CODEX_VERSION`).
Use another image name with `PULLS_REVIEW_AGENT_IMAGE`, for both `setup.sh` and the server.
`docker volume rm pulls-review-agents-home` signs everything out.
