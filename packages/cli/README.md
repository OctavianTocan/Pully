# pulls.review

This npm package launches the native Rust server and includes its web UI and curated
review skills. Node.js 20 or newer and a separately built compatible
`pulls-review-server` executable are required. The package does not contain a native
binary, download one, or compile one during installation or launch.

## Build from source

From this repository's root, with Rust/Cargo and the workspace's pnpm version installed:

```sh
pnpm install --frozen-lockfile
pnpm run build:server
node packages/cli/bin.mjs --cwd /path/to/repository
```

`build:server` runs `cargo build --locked --manifest-path backend/Cargo.toml`, then
packages the existing local web UI and curated skills. The launcher finds that
checkout's `backend/target/debug/pulls-review-server` (or `.exe` on Windows), then
its release build if there is no executable debug build. It resolves the checkout
from the launcher's own location, never from the repository being reviewed.

`pnpm -F pulls.review run build` only prepares the npm assets. It removes the old
`dist` output, copies `docker/agents/skills` into `dist/skills`, and runs the existing
app `build:local` into `dist/client`. It does not compile Rust.

## Use the npm launcher

These instructions describe this branch's package. This PR does not publish an npm
release: until a release containing this launcher is published, use the source
commands above. Bare `npx pulls.review` may still resolve the older TypeScript
package from npm.

First install the native server from a compatible source checkout:

```sh
cargo install --locked --path /path/to/pulls.review/backend
```

Put Cargo's bin directory on `PATH`, or set `PULLS_REVIEW_SERVER_BIN` to the full
executable path. Then run inside a Git repository:

```sh
npx pulls.review               # current branch against the default branch, or ref picker
npx pulls.review main...feat   # what feat adds since it forked from main
npx pulls.review v1..v2        # tree diff between two revisions
npx pulls.review feat          # a branch against the default branch
npx pulls.review HEAD          # one commit against its parent
npx pulls.review --worktree    # uncommitted changes, including untracked files
```

`pulls-review` is an equivalent executable alias. Native executable resolution is:

1. `PULLS_REVIEW_SERVER_BIN`, when set. An invalid or empty value fails without falling back.
2. This launcher's source checkout debug build, then release build, when running from `packages/cli` in the checkout.
3. `pulls-review-server` on an absolute `PATH` entry. Relative and empty `PATH` entries are ignored.

For a custom Cargo target directory, use `PULLS_REVIEW_SERVER_BIN`. The wrapper
passes arguments literally, preserves the reviewed working directory and standard
streams, and forwards termination signals and the native process's exit status.
There is no TypeScript backend fallback. The former `pulls.review/devframe` export
has been removed.

## Assets, settings and cache

The launcher sets absolute defaults for `PULLS_REVIEW_WEB_DIR` (`dist/client` beside
the launcher) and `PULLS_REVIEW_SKILLS_DIR` (`dist/skills`). Explicit values are
preserved. Native `--assets` overrides the web-directory environment value.
Native options such as `--cwd`, `--data-dir`, `--android-dir`, `--host`, `--port`,
`--no-open` and `--no-warm` are passed through unchanged. Use `--help` for the
native options. `PULLS_REVIEW_AGENT_MCP_BINARY` remains available for the required
Linux Docker helper when the server runs on another platform.

By default the server listens at `127.0.0.1:7390` and opens the review page. Browser
access uses the terminal code. The native phone API intentionally has no login;
restrict network access or use an authenticated reverse proxy before exposing it.

The native cache defaults to `<git-common-dir>/pulls-review-rust`. It does not use
or migrate the former `.git/pulls-review` cache. Use a separate `--data-dir` when
migrating or running a GitHub-only server, and never share a live cache with an old
backend process. See the [native server guide](../../backend/README.md) and
[migration guide](../../docs/RUST_BACKEND_MIGRATION.md) for runtime setup and limits.

To analyze a GitHub pull request in CI, use [`@pulls.review/actions`](../actions).
