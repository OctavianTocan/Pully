# Plan 12: the server off the desk, on Cloudflare

Status: **step 1 built** (the host seam). Nothing is deployed; each step that creates
or changes anything on Cloudflare waits for Tavi's go-ahead.

## Why

The phone's server is the `pulls.review` CLI on Tavi's machine (mini-001): systemd,
port 8792, reached through a Cloudflare tunnel behind Cloudflare Access. Every request
from the phone goes edge, tunnel, home connection, machine, and from there to GitHub.
It is only up while the machine is, and every GitHub call pays the home connection's
round trip. Tavi asked (16 Oct 08:39) where to host it "to make sure that it's actually
faster", naming Cloudflare Workers and Vercel as the ones his subscriptions cover, and
Railway.

## The choice: Cloudflare

| Need of today's server                                                | Cloudflare                                                           | Vercel                                                          | Railway                          |
| --------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------- | -------------------------------- |
| The phone's request reaches it fast                                   | Nearest edge, no tunnel; Smart Placement can run near GitHub instead | Functions in one chosen region                                  | One region                       |
| A store that outlives requests (`pr-meta`, diffs, reviews, Jev)       | A Durable Object's SQLite, next to the code; R2 for large bodies     | A Marketplace store (Upstash, Neon, Blob), one more network hop | A volume                         |
| Work that outlives the page (AI tasks, the 3-minute PR store refresh) | The same Durable Object holds tasks in memory and wakes on its alarm | Request-scoped; needs Cron plus Queues or Workflow              | A long-running process, as today |
| Claude Code and Codex in our Docker image                             | Containers (Sandbox SDK), started from the Durable Object            | Vercel Sandbox, a different image format                        | No Docker inside a service       |
| Who may reach it (contract `phone-api-no-login`)                      | Cloudflare Access, already set up; the phone keeps its headers       | Vercel's own protection; new headers on the phone               | Its own; new headers             |
| Tavi's plans                                                          | Covered                                                              | Covered                                                         | Not covered                      |

Vercel stays where it is good at: the public static site (`vercel.json`). Railway would be
the smallest move, but it cannot start the review containers and is not on his plans.

## Target shape

- **One server, two hosts.** The phone API (`handleNativeRequest`), the server session,
  the PR store and the review routes run over a `ServerHost` (`packages/cli/src/host.ts`):
  the store, an optional checkout, the Android files, and later the GitHub token, the
  analytics sink, the clock and the agent runner. The CLI's host (`node-host.ts`) is today's
  behaviour. The Cloudflare host is a Worker and one `PullsServer` Durable Object.
- **A platform-free server package.** Everything a host runs moves to `packages/server`,
  which imports no Node builtin, as `packages/core` already must not. The CLI keeps the
  devframe page, local git reviews, `gh`, Docker and systemd concerns.
- **Local reviews stay local.** A hosted server has no checkout: `local-diff`, `repo-info`
  and the `source-*` methods answer `NO_CHECKOUT`, and the CLI on a computer keeps them.
- **Keys stay in the environment** (`secrets-come-from-the-environment`,
  `github-token-stays-on-server`): on Cloudflare they are Worker secrets. `process.env` is
  filled from them under `nodejs_compat`, so code that reads it keeps working; a client
  still names an engine and a model, never a URL or a key.

## Steps

Each step is one pull request and keeps the CLI's behaviour unless it says otherwise.

### 1. The host seam (built)

- `ServerHost { storage, cwd?, androidFile? }`; `checkoutHost(cwd)` and the per-process
  `nodeHost(cwd)` build the CLI's. `serverSession`, `handleNativeRequest`, the review
  routes, `sessionPatches` and `startPrStore` take a host instead of a directory.
- Without a checkout, local routes answer `NO_CHECKOUT` and the Android routes 404.
- **Done when:** the phone API opens a stored pull request and keeps reviewed marks on a
  host that is only an in-memory store (`native-api.test.ts`, "a host with no checkout").

### 2. Measure first

- The phone's `pr.open` and the server's `cache.diff` carry how long the open took, so the
  Stats page shows today's numbers on mini-001 before anything moves. Without a baseline
  "faster" is a guess.

### 3. `packages/server`, free of Node

- Move the session, phone API, review routes, stored diffs, PR store, my-pulls and Jev
  there. An ESLint `no-restricted-imports` rule forbids `node:*` in it.
- What still needs Node becomes a host member: the GitHub token (the `gh` fallback stays
  in the CLI), analytics (append and read a day), the curated lenses (bundled as text at
  build), highlighting (Shiki's JavaScript regex engine instead of `createRequire`),
  `setInterval` (a host schedule), `Buffer` and `readline` (Web streams).
- Process-wide services become per host: the my-pulls store (`setMyPullsStore`), Jev's
  classifier and its file ratings (`sharedFileAttention`). Until then a process serves one
  host.

### 4. The Worker and the Durable Object

- `packages/worker` with its `wrangler` config. The Worker passes `/api/rpc`,
  `/api/events` and `/android/*` to one `PullsServer` Durable Object.
- Store: an unstorage driver over the object's SQLite for metadata and small values, R2
  for values past its row limit (diff bodies). Analytics day files and the APKs go to R2;
  `android/scripts/publish-apk.sh` gains an upload.
- The PR store refresh runs on the object's alarm. Until step 5 it runs no AI engine, so
  it is a second server the phone can be pointed at for testing, and mini-001 stays the
  default. Forwarding AI to mini-001 was rejected: its results would land in the other
  store.
- Deployed beside the current server under its own hostname behind Access; the phone's
  server address in Settings picks it. **Needs Tavi:** Workers Paid, a GitHub token for the
  server, the hostname, and the go-ahead to deploy.

### 5. AI in Containers

- The `docker/agents` image runs as a Cloudflare Container started by the object per job,
  through `startAgent`, so `server-every-cli-through-start-agent` holds. With no checkout,
  PR tools read files through GitHub (`loadFile`), as a job without one already does.
- Sign-ins become secrets: Claude Code's long-lived token (`claude setup-token`), and
  Codex's. Pi runs in the object itself; its endpoint (CLIProxyAPI) must be reachable from
  Cloudflare, through the tunnel with an Access service token. **Needs Tavi:** the tokens.

### 6. The web page and the cut-over

- The hosted web build talks to the same `/api/rpc` instead of devframe's socket.
- Compare step 2's numbers on both servers, then make the hosted one the phone's default.
  mini-001 keeps the CLI for local reviews.
