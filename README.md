<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./packages/app/public/og-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="./packages/app/public/og.png">
  <img alt="pulls.review Open Graph image" src="./packages/app/public/og.png">
</picture>

# [pulls.review](https://pulls.review)

> [!WARNING]
> Heavily work in progress.

Pull request review made simple. Groups changed files, summarizes what matters, and reviews them one scope at a time.

## Use on Website

To use pulls.review on the website, simply visit [pulls.review](https://pulls.review) and follow the instructions to connect your GitHub account and start reviewing pull requests directly in your browser.

## Use in GitHub

You can optionally install userscript to enhance the GitHub interface with pulls.review features.

Go to [pulls.review](https://pulls.review/) and scroll down to the GitHub section to install the userscript.

## Analyze pull requests from CI (Experimental)

Instead of each reviewer spending their own model key, let a workflow analyze every pull request once and post the result as a PR comment. pulls.review picks the comment up automatically for everyone who opens the PR there. Re-runs on an unchanged PR skip the model call.

```yaml
# .github/workflows/pulls-review.yml
name: pulls.review
on:
  # The analysis only reads the diff through the API and never checks out PR code,
  # so `pull_request_target` is safe here and lets fork PRs get a comment too.
  pull_request_target:
    types: [opened, synchronize, reopened]

permissions:
  pull-requests: write

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: antfu/pulls.review@main
        with:
          api-key: ${{ secrets.VERCEL_AI_GATEWAY_API_KEY }}
```

`provider` is inferred from the key you pass (`gateway` for a Vercel AI Gateway token, `anthropic`, or `openai-compatible` with `base-url`); set it explicitly, along with `model` and `locale`, when you want to.

The action is a thin wrapper around the `@pulls.review/actions` CLI, which works anywhere a GitHub token and a model key are in the environment:

```sh
GITHUB_TOKEN=... ANTHROPIC_API_KEY=... npx @pulls.review/actions owner/repo#123
```

Every variable also has a `PULLS_REVIEW_*` form that takes precedence (`PULLS_REVIEW_GITHUB_TOKEN`, `PULLS_REVIEW_PROVIDER`, `PULLS_REVIEW_API_KEY`, `PULLS_REVIEW_MODEL`, `PULLS_REVIEW_BASE_URL`, `PULLS_REVIEW_LOCALE`); `npx @pulls.review/actions --help` lists everything.

## Run your own review server

[`pulls-review-server`](./backend/README.md) is the native Rust server for local
Git reviews, the existing Android client, server-owned AI tasks, GitHub
operations, and persistent review state. The [`pulls.review` npm package](./packages/cli/README.md)
is a small JavaScript launcher plus the web UI and curated review lenses. It
requires a separately built or installed native executable; it does not bundle,
download, or build one, and has no TypeScript server fallback.

From a source checkout, with Rust, Git, Node.js compatible with the workspace dependencies (checks used 24.19.0) and the repository's pnpm
version installed:

```sh
pnpm install
cargo build --locked --manifest-path backend/Cargo.toml
pnpm -F pulls.review run build
node packages/cli/bin.mjs --cwd /absolute/path/to/repository --worktree
# Or build the native server and packaged web assets together:
# pnpm run build:server
```

For an installed npm launcher, install the server from a source checkout with
`cargo install --locked --path /path/to/pulls.review/backend`, then put Cargo's
bin directory on `PATH` or set `PULLS_REVIEW_SERVER_BIN` to its executable.
The launcher uses this checkout's `backend/target/debug` or `release` build when
run from source, and supplies the packaged web/skills directories automatically.
Direct native execution is also supported; see the [setup guide](./backend/README.md).
This PR does not publish an npm release; use the source checkout commands until
a release includes the native launcher.

A [full native CI run](https://github.com/OctavianTocan/pulls.review/actions/runs/38917860671)
at `fac9ff30b532297145b890d91cde2ec2c3af6ca6` passed a locked full build, all 43 targeted native fixtures (16 presentation,
11 lens, 14 RPC and two cursor tests), and both bounded offline HTTP/auth/review
and analytics smokes after the TypeScript backend removal. Live providers, Docker/shared login, GitHub writes, and
Android-device workflows remain untested; these bounded results do not establish
complete end-to-end parity. The Vue/Android UIs, browser-used TypeScript core,
and separate GitHub Action remain. See the [migration and rollback notes](./docs/RUST_BACKEND_MIGRATION.md).
Keep remote access on a trusted network or behind an authenticated proxy: the
general phone RPCs have no application login. Account-owned methods additionally
require a Pully session, and `/api/v1` uses agent keys.

## Packages

| Package                                       | What it is                                                                                      |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| [`packages/app`](./packages/app)              | The Vue website, github.com embed, and self-hosted web UI.                                      |
| [`@pulls.review/core`](./packages/core)       | Browser/Action diff parsing, providers, analysis, caching, and shared client contracts.         |
| [`@pulls.review/actions`](./packages/actions) | The separate TypeScript CLI used by the GitHub Action.                                          |
| [`pulls.review`](./packages/cli)              | Dependency-free JavaScript launcher, packaged web UI, and curated lenses; requires Rust server. |
| [`pulls-review-server`](./backend)            | The native Rust HTTP/RPC server, local Git provider, AI orchestration, and persistent storage.  |
| [`android`](./android)                        | The Kotlin/Compose Android client for the self-hosted server.                                   |

## Credits

Inspired heavily by [Linear's PR review guides](https://linear.app/docs/diffs#guides), thanks for the inspiration.

## Sponsors

<p align="center">
  <a href="https://cdn.jsdelivr.net/gh/antfu/static/sponsors.svg">
    <img src="https://cdn.jsdelivr.net/gh/antfu/static/sponsors.svg" alt="Sponsors"/>
  </a>
</p>

## License

MIT License
