# Claude Code and Codex in Docker

The native Rust server uses the `pulls-review-agents` image for Claude Code and
Codex jobs and model catalogs. Docker is required; there is no host execution
fallback. Each job gets an isolated home, read-only inputs and a Linux native MCP
helper, a read-only container root, dropped capabilities, and resource limits.
Neither the checkout, host home, server store, GitHub token, nor shared sign-in
volume is mounted into the model container. Scoped tools reach the Rust server
using an expiring per-job capability. Separate helpers copy selected login fields
and safely persist refreshed authentication.

Stopping an AI task stops its container too. The server offers only curated
review lenses from [`skills/`](./skills), the launcher's packaged copy, or an
explicit `PULLS_REVIEW_SKILLS_DIR`; account-wide host skills are not offered. Pi
uses a server-side OpenAI-compatible tool loop and does not need Docker.

The full locked Rust build, 16 native presentation tests, and two full-binary
offline HTTP smokes passed. These checks do not verify live Docker, MCP-helper,
authentication, or provider behavior. See the [native setup guide](../../backend/README.md)
and [verification notes](../../docs/RUST_BACKEND_MIGRATION.md).

## Prepare the shared image and sign-ins

1. Install Docker: [Docker Desktop](https://docs.docker.com/desktop/) on macOS or
   Windows (WSL), or [Docker Engine](https://docs.docker.com/engine/install/) on
   Linux. Start it and check that `docker info` answers.
2. From the repository root, run:

   ```sh
   docker/agents/setup.sh all
   ```

   It builds the image, then signs in whichever provider is not signed in yet:
   - Claude Code (`claude auth login`): open the link it prints, approve, and paste
     the code back.
   - Codex (`codex login --device-auth`): open the link and enter the code it shows.
     If ChatGPT refuses device codes, turn on device code sign-in under ChatGPT's
     Settings, Security, and run `docker/agents/setup.sh login-codex` again.

   It ends with `check`, which prints both provider versions and sign-in status.
   This checks the image and saved provider logins, not native backend or
   MCP-helper compatibility. To use just one provider, run `build` and its
   `login-claude` or `login-codex` command instead of `all`.

These are the server's own sign-ins, used by requests without a Pully account and
by the server's background work. Everyone signed in to a Pully account signs their
own Claude Code and Codex in from the phone (Settings, AI), and their AI runs only on
that sign-in: each account has its own `pulls-review-agents-home-<hash>` volume,
written only by the CLIs' own login commands (`ai-kit/src/login.rs`). There
are no API keys: `ANTHROPIC_API_KEY`, `CLAUDE_CODE_OAUTH_TOKEN` and `OPENAI_API_KEY`
are not passed to the CLIs. Node remains in the image for the provider CLIs and the
native server's login-copy helpers. The image does not build the Rust server or its
Linux MCP helper.

GitHub credentials belong to the server: it uses `PULLS_REVIEW_GITHUB_TOKEN`,
`GITHUB_TOKEN`, or `GH_TOKEN`, falling back to the server account's GitHub CLI
login. The agent image does not install the GitHub CLI or receive its sign-in.

## Start the native server

From the repository root, using Rust/Cargo 1.99.0 (the verified toolchain):

```sh
pnpm install
cargo build --locked --manifest-path backend/Cargo.toml
pnpm -F pulls.review run build
node packages/cli/bin.mjs --cwd /absolute/path/to/repository --no-open --no-warm
```

The JavaScript launcher runs only the native executable and supplies the packaged
web assets and curated lenses. It can use the repository-local build above,
`PULLS_REVIEW_SERVER_BIN`, or an installed `pulls-review-server` on `PATH`. The npm
package does not install or compile a native executable for you; see the
[launcher guide](../../packages/cli/README.md) for source installation and overrides.

To run the native executable directly after the same build:

```sh
PULLS_REVIEW_SKILLS_DIR="$(pwd)/docker/agents/skills" \
  ./backend/target/debug/pulls-review-server \
  --cwd /absolute/path/to/repository \
  --assets "$(pwd)/packages/cli/dist/client" \
  --no-open --no-warm
```

Docker is the default. `--agents docker` or
`PULLS_REVIEW_AGENT_RUNTIME=docker` is accepted explicitly; `host` is rejected.
Set `PULLS_REVIEW_AGENT_IMAGE` to select a different existing image. The server
does not pull an image automatically.

On Linux, the running native executable is the default MCP helper. On macOS or
Windows, set `PULLS_REVIEW_AGENT_MCP_BINARY` to a separately built Linux
`pulls-review-server` compatible with the image's architecture and runtime
libraries. The macOS/Windows server executable cannot run in that Linux image.
Use an absolute helper path to avoid service working-directory ambiguity.

The helper calls a separate ephemeral-port `/mcp` listener through
`host.docker.internal`. It receives access only to its selected review context;
it cannot invoke general RPC, storage, or credential methods. That listener
binds `0.0.0.0` for Docker reachability. Restrict network access to the server and
do not expose it through a public proxy. General phone RPCs have no application
login; account-owned methods additionally require a Pully session and `/api/v1`
uses agent keys. A remote deployment still needs private networking or authenticated
proxy access to every route. See the [security notes](../../backend/README.md#security-and-network-access).

## Add or remove a skill

1. Add a folder under [`skills/`](./skills), using letters, digits, `-`, or `_`
   for its name. Put a `SKILL.md` inside it beginning with:

   ```md
   ---
   name: my-lens
   title: My lens
   description: One sentence on what this review looks for.
   ---
   ```

   Follow it with instructions for the reviewer. To remove a skill, delete its
   folder.

2. Run `pnpm -F pulls.review run build` to update the launcher's bundled
   `dist/skills` copy. Rebuild with `docker/agents/setup.sh build` to update the
   image's curated skills too. Its setup entrypoint replaces the shared home's
   Claude/Codex skill folders with that set; isolated native jobs bypass this
   entrypoint.

The server reads the curated folder for its lens list, preserving front-matter
`title` and `description`. The selected lens contributes its instruction body,
without the front matter. Native jobs receive those instructions directly rather
than mounting host skill folders. The launcher defaults to its own `dist/skills`;
the direct native executable defaults to `<cwd>/docker/agents/skills`. Set
`PULLS_REVIEW_SKILLS_DIR` to this project's curated folder when running the native
executable directly against another repository.

## Other commands

| Command                               | What it does                                                   |
| ------------------------------------- | -------------------------------------------------------------- |
| `docker/agents/setup.sh build`        | Builds the image again, with the newest Claude Code and Codex. |
| `docker/agents/setup.sh login-claude` | Signs Claude Code in again.                                    |
| `docker/agents/setup.sh login-codex`  | Signs Codex in again.                                          |
| `docker/agents/setup.sh check`        | Prints versions and saved provider sign-in status.             |

Pin a CLI version with `CLAUDE_CODE_VERSION=2.1.300 docker/agents/setup.sh build`
(or `CODEX_VERSION`). Use another image name with `PULLS_REVIEW_AGENT_IMAGE` for
both `setup.sh` and the server. Removing `pulls-review-agents-home` with
`docker volume rm pulls-review-agents-home` deletes the image's saved sign-ins;
it does not remove server environment credentials or the server account's `gh`
login.
