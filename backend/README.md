# Native Rust backend

## Latest-main rebase, 16 October 2026

The rewrite is rebased onto main `e23230a688a3723ac8c2ebbb125fd37a0686c3f7`
(Android 0.44.0 plus posted-link/API-path security fixes). The newer web/mobile
interfaces remain intact. Backend additions from main now have native modules:
`account.rs` (environment-seeded account, hashed sessions/agent keys and settings),
`posted.rs` / `agent_api.rs` (persistent posted diffs and authenticated agent API),
`host.rs` (optional checkout and served artifacts), and `github_stats.rs` (cached
profile counts). The lightweight `pr-text` route refreshes edited descriptions;
analytics include task token/cache-read totals. Posted diffs are excluded from
cache eviction and their data remains account scoped.

All TypeScript files newly introduced under `packages/cli/src` are removed too.
The browser-consumed posted source/types stay in core, as do the web/mobile UI
and separate GitHub Action. No Cloudflare deployment is part of this change.
Earlier validation below is historical; fresh compile/check and review evidence
for the rebased revision is recorded separately when completed.

`pulls-review-server` is the local/self-hosted server implementation. It serves
the existing Vue web build and Android RPC interface. The local web connection
adapter detects Rust and uses HTTP; a native devframe-compatible WebSocket
transport also supports existing local clients. The UIs have not been rewritten.

HTTP/RPC, source loading, grouping, persistent review state and full-file caching,
GitHub operations, streamed AI tasks/catalogs, TypeSafe/Jev, cache warming,
preanalysis, OpenUI parsing, Mermaid rendering, and syntax highlighting run in
Rust. The TypeScript server and Node-only local provider have been removed.
`packages/cli` is now a dependency-free JavaScript launcher plus frontend/skill
packaging. It never starts a TypeScript server or builds/downloads Rust for you.

A [full native CI run](https://github.com/OctavianTocan/pulls.review/actions/runs/38917860671)
at `fac9ff30b532297145b890d91cde2ec2c3af6ca6` passed `cargo build --locked`,
43 targeted native fixtures (16 presentation, 11 lens, 14 RPC and two cursor
tests), and both bounded offline HTTP/auth/review and analytics smokes on the
complete removal revision. Ten complete code-review rounds fixed 34 validated findings, followed
by five simplification rounds with 23 improvements. Their historical 202 partial
checks and three smokes excluded real renderers; they are separate evidence.
See the [review record](../docs/RUST_REWRITE_REVIEW.md) and
[coverage/verification limits](../docs/RUST_BACKEND_MIGRATION.md).

## Build and run

Run from the repository root. Install a current stable Rust toolchain, a native
linker, Git, Node.js compatible with the workspace dependencies (checks used 24.19.0), the repository's pnpm version, and workspace dependencies
first. The resolved `backend/Cargo.lock` is checked in. CI used Rust/Cargo 1.99.0.

```sh
pnpm install
cargo build --locked --manifest-path backend/Cargo.toml

# Clear obsolete server bundles, copy curated skills, and build the PR_LOCAL SPA.
pnpm -F pulls.review run build

# Start Rust with the packaged web/skills paths supplied by the launcher.
node packages/cli/bin.mjs \
  --cwd /absolute/path/to/repository --worktree --no-open --no-warm

# Root convenience script builds Rust and the launcher assets in that order.
# pnpm run build:server
```

This PR does not publish an npm release; use the source commands above until a
release includes this launcher. The npm package does not contain a native executable. `bin.mjs` selects
`PULLS_REVIEW_SERVER_BIN` when explicitly set; otherwise, in this source checkout,
it tries `backend/target/debug` then `backend/target/release`, then searches
absolute directories on `PATH` for `pulls-review-server` (`.exe` on Windows).
Missing executables fail with setup instructions, without falling back to Node.
An explicit empty override is an error. If using a custom `CARGO_TARGET_DIR`,
select its executable with `PULLS_REVIEW_SERVER_BIN`.

To install a native executable for use with an installed npm launcher:

```sh
cargo install --locked --path /absolute/path/to/pulls.review/backend
# Put Cargo's bin directory on PATH, or set PULLS_REVIEW_SERVER_BIN explicitly.
```

You can also run the native binary directly, without the JavaScript launcher:

```sh
PULLS_REVIEW_SKILLS_DIR="$PWD/packages/cli/dist/skills" \
  ./backend/target/debug/pulls-review-server \
  --cwd /absolute/path/to/repository \
  --assets "$PWD/packages/cli/dist/client" --no-open --no-warm
```

The launcher preserves arguments, working directory, stdio, signals and exit
status. Unless overridden, it supplies `PULLS_REVIEW_WEB_DIR=dist/client` and
`PULLS_REVIEW_SKILLS_DIR=dist/skills` as absolute package-relative paths. This
prevents a different reviewed repository from selecting its own web assets or
skills. Building only the SPA with `pnpm -F @pulls.review/app run build:local`
does not copy skills or clean obsolete server bundles; use the CLI package build
for the complete launcher assets.

Open `http://127.0.0.1:7390` and enter the browser trust code printed in the
server's terminal. Without `--no-open`, the server attempts to open the selected
review page in the system browser. Paths passed to `--assets`, an explicit
`--data-dir`, and `PULLS_REVIEW_AGENT_MCP_BINARY` are relative to the process's
working directory, not `--cwd`; absolute paths avoid service ambiguity.

`--assets` is optional for an API-only direct native invocation. With an existing
`--cwd` directory and an explicit `--data-dir`, a GitHub-only server can start
without a local Git repository; local review endpoints still require one.

For a small, read-only connectivity check after a successful build:

```sh
curl --fail http://127.0.0.1:7390/health
curl --fail http://127.0.0.1:7390/api/backend
curl --fail -H 'Content-Type: application/json' \
  --data '{"name":"repo-info","args":{}}' \
  http://127.0.0.1:7390/api/rpc
```

The bounded fixtures create temporary repositories/caches and strip provider
credentials from the child server environment:

```sh
cargo test --locked --manifest-path backend/Cargo.toml --lib presentation:: -- --test-threads=2
python3 backend/scripts/smoke.py
python3 backend/scripts/analytics_smoke.py
pnpm -F pulls.review run test
pnpm -F pulls.review run typecheck
```

These are rerunnable checks, not evidence for an untested revision. They do not
verify live providers, Docker/shared login, TypeSafe, GitHub writes, Android
devices, or deployment security.

## Command-line options

| Option                         | Default / behavior                                                                           |
| ------------------------------ | -------------------------------------------------------------------------------------------- |
| `[TARGET]`                     | Optional local branch, commit, or `A..B` / `A...B` range to open                             |
| `--worktree`                   | Open the working-tree review page; takes precedence over a target                            |
| `--no-open`                    | Suppress the system-browser launch attempt                                                   |
| `--agents docker`              | Select the isolated CLI runtime; `docker` is the only accepted value                         |
| `--host <HOST>`                | `127.0.0.1`                                                                                  |
| `--port <PORT>`                | `7390`                                                                                       |
| `--cwd <PATH>`                 | `.`; repository used by local source endpoints                                               |
| `--data-dir <PATH>`            | `<git-common-dir>/pulls-review-rust`                                                         |
| `--assets <PATH>`              | Direct native: no web assets unless supplied; launcher sets `PULLS_REVIEW_WEB_DIR`           |
| `--android-dir <PATH>`         | `$HOME/.local/share/pulls-review/android`; also `PULLS_REVIEW_ANDROID_DIR`                   |
| `--allow-origin <ORIGIN>`      | Repeatable HTTP(S) origins, for example `https://review.example.com`                         |
| `--no-auth`                    | Disable the browser terminal-code gate; use only behind a proxy authenticating every request |
| `--no-warm`                    | Disable the three-minute inbox diff/overview cache warmer                                    |
| `--preanalyze`                 | Opt in to ten-minute review-requested PR analysis; may incur provider charges                |
| `--preanalyze-engine <ENGINE>` | `claude-code`; accepts `claude-code`, `codex`, or `pi`                                       |
| `--preanalyze-model <MODEL>`   | Optional model override for preanalysis                                                      |
| `--help`                       | Print the authoritative option list                                                          |

With no explicit target, the browser opens the current branch when it differs
from the known default branch, otherwise `/`. Targets choose a route; they do not
switch branches or modify the index. The internal `--mcp-context` mode is the
sandbox's stdio tool adapter, not a normal server option.

The warmer discovers up to 150 recently updated open inbox PRs, caches missing
diffs, probes base/head changes, and refreshes stale overviews without marking
PRs viewed. Preanalysis attempts each eligible requested PR head at most once per
server lifetime, including failed attempts, and skips PRs over 300 changed files.
`--no-warm` does not disable an explicitly selected `--preanalyze` worker.

## Security and network access

- The native `POST /api/rpc` API and Android download routes have **no application
  login**, matching the existing product contract. Anyone who can reach them can
  invoke allowed methods, including GitHub writes using the server account. The
  browser trust code does not protect the native API.
- Keep the localhost default unless behind a private network or authenticated
  reverse proxy. For remote access, authenticate **all routes** at the proxy,
  use HTTPS, and add the public origin with `--allow-origin`. Existing Android
  Cloudflare Access settings supply proxy authentication. An allowed origin is
  not an authentication mechanism.
- `POST /api/web-rpc` and the devframe WebSocket app methods require browser
  terminal-code trust. Raw storage and the GitHub credential proxy are
  browser-only; they are not native API methods. Devframe metadata is at
  `/__connection.json` and the WebSocket endpoint is `/__ws`.
- `--no-auth` disables only the browser gate. It does not configure a reverse
  proxy. Do not use it on an unprotected listener.
- The server holds GitHub and provider credentials. Clients select an engine and
  model; they cannot supply provider URLs or keys through task requests. Neither
  client receives a GitHub token.
- CLI tools also use a separate `0.0.0.0` ephemeral-port `/mcp` listener reachable
  from Docker through `host.docker.internal`. This exposes no general RPC or
  storage methods. Each unpredictable capability is bound to one running job's
  selected review context and expires when that run ends. Restrict network access
  to the server; do not expose this listener through a public proxy.

## GitHub

Credential precedence is `PULLS_REVIEW_GITHUB_TOKEN`, `GITHUB_TOKEN`, then
`GH_TOKEN`. When none is set, Rust tries the server account's existing
`gh auth token --hostname github.com` login with a bounded, noninteractive lookup.
Successful lookups are cached for ten minutes; misses for thirty seconds. Tokens
and CLI output are never returned to clients. Public read-only requests can work
without a token, subject to GitHub's unauthenticated limits.

Use only the repository permissions you need. The GitHub API origin is fixed to
`https://api.github.com`; GitHub Enterprise endpoints are not configurable. The
server's GitHub CLI login is separate from the Docker agent sign-in volume.

## AI configuration

### Pi: server-side OpenAI-compatible tool loop

| Environment variable         | Meaning                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------ |
| `PULLS_REVIEW_PI_BASE_URL`   | Required API base including its version path, for example `http://127.0.0.1:8317/v1` |
| `PULLS_REVIEW_PI_API_KEY`    | Optional bearer token when the endpoint requires authentication                      |
| `PULLS_REVIEW_PI_MODEL`      | Optional default model; otherwise selected from `/models`                            |
| `PULLS_REVIEW_SUGGEST_MODEL` | Optional Pi model for short chat/inbox follow-up suggestions                         |

The base receives `/models` and `/chat/completions` requests and must support the
OpenAI-compatible tool-calling protocol, including `submit_answer` for structured
tasks. Rust requests streamed responses and records text, thinking, tools, and
usage in observable task revisions; JSON responses from compatible non-streaming
proxies are also accepted. This is a native loop, not `pi-agent-core`. Use a
trusted endpoint because it receives the selected review context.

### Claude Code and Codex: Docker-only jobs

A running Docker daemon and an existing agent image are required:

```sh
docker/agents/setup.sh build
# Optional interactive sign-ins instead of server environment credentials:
docker/agents/setup.sh login-claude
docker/agents/setup.sh login-codex
```

| Environment variable                             | Meaning                                                                                                                 |
| ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `PULLS_REVIEW_AGENT_RUNTIME`                     | Must be `docker` when set; defaults to `docker`                                                                         |
| `PULLS_REVIEW_AGENT_IMAGE`                       | `pulls-review-agents`; images are not pulled automatically                                                              |
| `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN` | Server-side credentials for `claude-code` (`claude` alias)                                                              |
| `OPENAI_API_KEY`                                 | Server-side credential for `codex`                                                                                      |
| `PULLS_REVIEW_AGENT_MCP_BINARY`                  | Linux ELF helper compatible with the Docker image; defaults to the running executable only on Linux                     |
| `PULLS_REVIEW_SKILLS_DIR`                        | Curated lens directory; direct native default is `<cwd>/docker/agents/skills`, launcher supplies packaged `dist/skills` |

Curated lenses preserve `SKILL.md` frontmatter titles/descriptions and dotted
folder names. Instruction bodies omit frontmatter and are truncated after at
most 30,000 UTF-16 units with a marker, preserving valid Unicode. Reads and
catalog output are bounded and remain within the canonical curated directory;
account skills are never searched.

Environment credentials take precedence. Otherwise a separate, network-disabled
bootstrap copies allowlisted authentication fields from the existing
`pulls-review-agents-home` Docker volume into the job's isolated home. A separate
locked helper persists refreshed auth fields only when the original login has
not changed. Model containers never mount the shared volume, host login folders,
checkout, GitHub token, server store, or host skills/settings. When reviewing
another repository, set `PULLS_REVIEW_SKILLS_DIR` to this project's curated lenses.

Each job has a dedicated writable directory, read-only input payload, and
read-only Linux MCP helper. Containers have a read-only root, dropped
capabilities, no-new-privileges, and resource limits. Scoped tools call the Rust
server for source/discussion/inbox data; the model gets neither a checkout nor a
general shell. CLI jobs time out after 30 minutes. Missing Docker, authentication,
image, or compatible helper is an error. There is **no unsandboxed host fallback**.

On macOS or Windows, explicitly provide `PULLS_REVIEW_AGENT_MCP_BINARY` pointing to
a separately built Linux `pulls-review-server` for the image's architecture and
runtime libraries. The native macOS/Windows binary cannot run inside Linux. The
image's build script installs the third-party Node-based CLIs; it does not build
the Rust helper. Pi does not need this helper or Docker.

Claude catalogs use the CLI initialization handshake; Codex catalogs use
`codex debug models`. Neither submits a paid prompt. Catalogs are cached for ten
minutes with explicit refresh support and include available account/model/effort
metadata. CLI activity and usage are parsed as the job runs. Live CLI version,
authentication, catalog, and provider behavior remain to be verified.

### TypeSafe/Jev

Set `PULLS_REVIEW_TYPESAFE_API_KEY` or `TYPESAFE_API_KEY` on the server to enable
classification through the fixed `https://api.typesafe.ai/v1/systemone` service
and `jev-latest` model. This uses the external paid service; Rust does not recreate
its proprietary model. Inbox PR kind/risk and review file attention are persisted
and reused. Opening/polling file ratings alone does not start paid file requests;
AI review preparation does. Missing keys or an HTTP 401 disable classification.
The client never receives the key. Restart after changing TypeSafe configuration.

## Storage, rendering, and rollback

Rust uses a separate data directory by default. In an ordinary clone this is
`.git/pulls-review-rust`; linked worktrees share the Git common directory. One
native process owns a data root via an exclusive lock. Never run a historical TypeScript
server and Rust against the same live cache: the old server does not honor this lock.

Legacy `pr-meta` / `pr-body` entries can be read from a **copy** of a stopped
TypeScript cache. New review records use `diff:` entries. Full-file bodies have a
separate persistent 500-entry / 25 MiB LRU. Shared analysis comments support JSON
and bounded `lz-string` compression. Follow the [backup and rollback steps](../docs/RUST_BACKEND_MIGRATION.md#cache-migration-and-rollback).

OpenUI parsing preserves inert reactive AST in the native brief tree; it never
executes actions or JavaScript. Mermaid uses a native renderer and sanitized SVG;
highlighting uses syntect/two-face grammars and the existing Pierre palettes.
These preserve client response shapes, not byte-for-byte Mermaid/Shiki output.
The real rendering dependencies compiled and all 16 presentation fixtures passed
at `fac9ff30`. The earlier partial harness excluded these adapters. Neither
result claims pixel-identical output or every upstream grammar feature.

`--android-dir` serves existing `latest.json`, `pulls.apk`, `past/index.json`, and
versioned `past/<version>.apk` artifacts. It does not build, sign, publish, or update
the Android application. Rollback to the original server requires a separately
pinned pre-removal checkout or release and the untouched original cache; the
current launcher cannot select the removed TypeScript backend.

## Usage analytics

The 0.37 client Stats interface is supported by native `POST /api/events` ingestion
and `analytics-summary`. Data stays on this server in UTC-day NDJSON files under
`PULLS_REVIEW_ANALYTICS_DIR` (default `$HOME/.local/share/pulls-review/analytics`).
`PULLS_REVIEW_ANALYTICS=off` disables writes. Client switches are preserved; event
validation strips content, paths and credentials before storage. Files older than
365 days are pruned even after opt-out; opt-out stops writes, not retention
deletion. RPC, AI, GitHub, Jev, cache refresh, and preanalysis measurements use the
same privacy filter.

## Hosted accounts and posted diffs

Use `--server --data-dir /persistent/path` for a server without a local checkout
(or `PULLS_REVIEW_SERVER=true` and `PULLS_REVIEW_DATA_DIR`). Local-source routes
fail explicitly in this mode. Keep the server behind authenticated TLS/network
protection; account sign-in does not replace the existing network boundary for
other phone RPCs.

Set `PULLY_ACCOUNT_EMAIL` and `PULLY_ACCOUNT_PASSWORD` in the server environment
to seed the account. There is no registration endpoint. Account RPCs use the
`X-Pully-Session` returned by `account-sign-in`; the agent API under `/api/v1`
requires a separately created bearer key. Agent keys are shown once, hashed at
rest, individually revocable, and account scoped. `PULLY_PUBLIC_URL` controls
posted links (`/-/<id>`), which open only in the phone app. Never put these
credentials in URLs or public configuration.

Posted patches are retained until explicitly deleted, reject detected secrets
unless the API caller explicitly opts in, and validate optional analysis fields.
Settings synchronize with per-setting timestamps, newer values winning. Android
changelog images and missing-past-version empty indexes are served natively.
