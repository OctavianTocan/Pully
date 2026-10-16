# Architecture & design decisions

pulls.review is a SPA (deployed at pulls.review) that renders a GitHub PR's diff at
`/gh/{owner}/{repo}/{number}` - or an arbitrary pasted/uploaded unified diff at
`/upload` - grouped and summarized for easier review, with a rule-based
fallback grouping when no LLM is configured. `/gh/{owner}/{repo}` lists the
repo's open PRs as a way into those deep-links. A user-run server also supports
local Git reviews, the Android client, persistent review state, GitHub operations,
and server-owned AI work. The public static site, self-hosted server, and CI
analysis are separate execution modes.

See `plans/` for historical phase-by-phase designs. This document describes the
current architecture; applicable `CONTRACTS` files hold its enforced boundaries.
The server is native Rust. A [locked full build, 43 targeted native fixtures, and two
offline HTTP/analytics smokes](https://github.com/OctavianTocan/pulls.review/actions/runs/38917860671)
passed at `fac9ff30b532297145b890d91cde2ec2c3af6ca6`. This verifies the final TypeScript-removal revision. Live provider, Docker/login,
GitHub-write, and Android-device compatibility remain unverified. See [backend setup](../backend/README.md)
and the [migration evidence](../docs/RUST_BACKEND_MIGRATION.md).

## Layout

Four pnpm workspace packages, plus the native backend and Android client:

- `packages/app` - the SPA and github.com embed (Vite + Vue), including the
  self-hosted `PR_LOCAL` build. Paths below written as `app/…` live in
  `packages/app/src/`. Browser rendering and UI behavior remain TypeScript/Vue.
- `packages/core` (`@pulls.review/core`) - shared browser/Action code and client
  contracts, exposed as subpaths rather than one barrel: `/types`, `/patch-parser`,
  `/github`, `/paste`, `/cache`, `/analyze`, `/llm`, `/local-rpc`, `/diagnostics`,
  `/env`, `/locales`, and `/analytics`. Built with tsdown; the app resolves these
  to source through a Vite alias. Core MUST load under plain Node: no Settings,
  vue-i18n, `localStorage`, `document`, or Vite-only syntax inside it. Callers
  inject settings, locale and wording. The Node-only `/local` entry point and
  its Git implementation are removed; shared browser runtime and RPC types stay.
- `packages/actions` (`@pulls.review/actions`) - the separate TypeScript GitHub
  Action CLI: fetch, analyze, upsert the shared-analysis comment. Root
  `action.yml` continues to run this package, not the local-server launcher.
- `packages/cli` (`pulls.review`, the unscoped name) - dependency-free JavaScript
  launcher (`bin.mjs`) and web/curated-skills packaging. It forwards arguments,
  stdio, working directory, signals and exit status to `pulls-review-server`.
  An explicit `PULLS_REVIEW_SERVER_BIN` wins, followed in a source checkout by
  `backend/target/debug`, `backend/target/release`, and absolute `PATH` entries.
  It supplies package-relative `dist/client` and `dist/skills` defaults. The
  package neither bundles/downloads/builds a native executable nor falls back to
  a TypeScript server. Its build clears old `dist` output, copies curated skills,
  and runs the app's local Vite build. Native compilation is a separate Cargo
  step, combined with assets by root `pnpm run build:server`.
- `backend` (`pulls-review-server`) - Rust/Axum HTTP/RPC server, local Git source,
  GitHub operations, persistent review state, AI orchestration, and native
  presentation adapters. It serves the existing `PR_LOCAL` build at standalone
  and local-hub paths. Targets live in page routes (`/`, `/branch/<name>`,
  `/compare/<A...B>`, `/worktree`, `/commit/<sha>`); CLI target arguments only
  choose the page to open and never switch the checkout. No Node server runs.
- `android` - Kotlin/Compose client for the user-run server, retaining the shared
  design tokens and locale sources from the web app.

## Native backend boundaries (`backend/src/`)

The migration targets client-visible backend contracts, not byte-for-byte
replacement of TypeScript libraries. Source coverage is not proof of runtime
parity; the [migration matrix](../docs/RUST_BACKEND_MIGRATION.md#coverage-matrix)
lists implemented operations and remaining verification limits.

- `transport.rs` serves the allowlisted phone RPC API, browser trust/session and
  web RPC, static web assets, and existing Android artifacts. The web adapter
  (`app/local/rust-connection.ts`) detects `/api/backend` and uses HTTP.
  `devframe.rs` additionally provides compatible app RPC/trust transport at
  `/__connection.json` and `/__ws`; it is not an arbitrary devframe plugin host.
  `rpc_validation.rs` applies declared argument defaults, trims, unknown-field
  stripping and type validation before dispatch, then validates declared result
  shapes. Semantic guards, including nonnegative integer chat-start indices,
  remain in the handlers even where shared schemas allow broader numbers.
- `source.rs` and `source/` load GitHub, local, and pasted diffs, rule grouping, inbox/repository
  lists, and full-file content. Git commands are read-only and do not switch
  branches or modify the index. `review.rs` and `review/` provide review state,
  discussion, shared analysis, and real GitHub mutations under server credentials.
- `ai.rs` and `ai/` own task revisions, cancellation, grouping, critiques, Ask/chat,
  tours, briefs, and scoped PR/inbox tools. Page disconnects do not cancel jobs;
  process restarts lose active tasks while completed results remain persisted.
  Pi uses a native OpenAI-compatible streaming tool loop, not `pi-agent-core`.
- `agent_process.rs` runs Claude Code and Codex only in Docker, with an isolated
  job home and read-only inputs/Linux MCP helper. No checkout, host home,
  settings, server store, or GitHub token is mounted. A per-run capability lets
  the helper reach scoped server tools. Selected login fields are copied by a
  separate helper; the model container never mounts the shared login volume.
  Missing Docker, credentials, image, or compatible Linux helper fails explicitly;
  there is no host execution fallback. See [Docker setup](../docker/agents/README.md).
- `store.rs` provides locked, atomic file replacement and serialized updates.
  Rust defaults to `<git-common-dir>/pulls-review-rust`, separate from the
  historical TypeScript cache. An older server checkout and Rust must never
  share a live store. Native full-file
  caching has a persistent 500-entry / 25 MiB LRU; multi-key updates are not
  crash-atomic transactions. Use the documented backup/rollback procedure.
- `scheduler.rs` implements periodic cache warming and opt-in `--preanalyze`.
  `jev.rs` integrates the external paid TypeSafe service. `analytics.rs` keeps
  sanitized first-party events in UTC-day NDJSON files with retention limits.
- `brief.rs` parses OpenUI into inert client trees without JavaScript/action
  execution. `presentation/` contains native Mermaid/SVG and syntect/two-face
  highlighting adapters. Their output can differ from Mermaid/Shiki. Both real
  renderer adapters compiled in CI and all 16 presentation fixtures passed at
  `fac9ff30`. The earlier partial harness excluded them and provides no renderer
  evidence; the final removal revision requires its own checks.

Account-specific phone methods require their own account session; other phone
API and Android download routes retain their authenticated-proxy/private-network
boundary. Browser terminal-code trust protects browser-only methods, not that
phone boundary. Agent API routes additionally require an account-scoped key.
Allowed origins are not authentication. Server-owned credentials never
reach clients. Browser-used core, browser-only providers/renderers, and the
separate GitHub Action remain TypeScript.

## AI work outlives the page (`backend/src/ai.rs`, `ai/tasks.rs`)

Every Claude Code, Codex or Pi run started through the local server is a task,
not a browser-held promise. The server loads the selected diff/context, captures
its identity, runs scoped work, and persists results through the shared review
state: analysis/session, critique, Ask/chat, tour, or brief. Client RPC shapes
remain declared in `packages/core/src/local-rpc.ts`.

- Reloading, closing a tab or a phone sleeping MUST NOT cancel a task. Client
  `abort()` requests cancellation; `detach()` stops following without stopping
  the server's work. A client lists/follows running tasks when it returns.
- Task revisions expose normalized activity, usage and progress. Equivalent work
  joins the existing current-head task instead of starting duplicate model work;
  per-diff coordination prevents conflicting mutations while different PRs can
  run concurrently. Stale results cannot replace a newly stored head.
- Requested `then` tour/brief follow-ups start on the server after successful
  analysis, even if the client has gone away. A current saved tour is skipped.
  The separate opt-in preanalysis scheduler runs analysis only.
- A chat needs no previous analysis: it can start from the selected PR itself
  with the same scoped source tools. Chat regrouping validates complete path
  coverage before changing the saved analysis.
- Running/recent task records live in memory. Page disconnects do not erase
  them; a process restart loses active jobs, while completed persisted results
  remain. Restart recovery for running jobs is not implemented.
- Browser-only providers keep their in-page path. Native HTTP polling and the
  compatible devframe transport are connection mechanisms, not alternate server
  implementations or permission to execute the deleted TypeScript backend.

## Where the CLIs run (`backend/src/agent_process.rs`)

Claude Code and Codex jobs and catalog probes run only through the native Docker
boundary. `PULLS_REVIEW_AGENT_RUNTIME`, when set, must be `docker`; `--agents`
accepts only `docker`. `PULLS_REVIEW_AGENT_IMAGE` defaults to
`pulls-review-agents`, built through `docker/agents/setup.sh build`.

- Each model container has its own writable job directory, read-only root/input
  payload/Linux MCP helper, dropped capabilities, no-new-privileges and resource
  limits. The host checkout, home, server store, GitHub token, settings and
  account skills are not mounted. Provider networking remains available.
- The native stdio MCP helper reaches scoped PR/inbox tools through a separate
  ephemeral `/mcp` listener and per-run capability. It does not expose arbitrary
  RPC, storage or credentials. Source reads can include unchanged files at the
  selected revision without mounting the checkout. The listener binds
  `0.0.0.0` for Docker reachability and must not be publicly proxied.
- Environment credentials take precedence. Otherwise a separate network-disabled
  helper copies allowlisted authentication fields from the shared
  `pulls-review-agents-home` login volume. The model container never mounts that
  volume; a locked helper persists refreshes only if the original login has not
  changed. `login-claude` and `login-codex` remain interactive operator commands.
- Cancelling/stopping a run kills and cleans up its container, not merely the
  Docker client, and invalidates its capability. Jobs have a 30-minute deadline.
  Missing Docker, credentials, image, bridge or helper fails explicitly. There
  is no unsandboxed host fallback.
- `PULLS_REVIEW_AGENT_MCP_BINARY` must be a compatible Linux ELF executable.
  Linux can use the current server binary; macOS/Windows require a separately
  built Linux helper. The image installs third-party Node-based CLIs, not the
  old TypeScript backend, and does not build this helper.
- Claude model discovery uses initialization; Codex uses `debug models`.
  Discovery makes no paid prompt, uses a ten-minute cache and supports refresh.
  Live provider versions, catalogs and shared-login flows remain untested.

`ai/lenses.rs` reads only the curated `SKILL.md` directory: packaged `dist/skills`
through the launcher, the explicit `PULLS_REVIEW_SKILLS_DIR`, or the direct
native default `<cwd>/docker/agents/skills`. It preserves frontmatter titles and
descriptions and accepts valid dotted folder names. Instruction bodies exclude
frontmatter and truncate at a valid Unicode boundary after at most 30,000 UTF-16
units, with a truncation marker. Reads/catalogs are bounded; canonical-path guards
keep file and directory symlinks inside the curated root. Account skill folders
are never searched or offered to jobs.

## Pi, the server's native tool loop (`backend/src/ai/provider.rs`)

`pi` is the third server engine alongside `claude-code` and `codex`. Rust sends
OpenAI-compatible `/models` and `/chat/completions` requests to the endpoint
selected by `PULLS_REVIEW_PI_BASE_URL`, with optional `PULLS_REVIEW_PI_API_KEY`
and `PULLS_REVIEW_PI_MODEL`. It does not use the browser's `pi-agent-core` SDK.

- Endpoint/key configuration comes only from the server environment. A client
  names an engine and model, never a provider URL or key. Missing base URL makes
  Pi jobs/catalogs fail with setup guidance.
- Pi calls the same scoped PR/inbox handlers exposed to CLI jobs through MCP.
  It gets no general shell, filesystem or web tool. Structured tasks finish
  through `submit_answer`; tool turns retain required reasoning metadata.
- Streamed text, thinking, tools and usage produce observable task revisions.
  Compatible non-streaming JSON responses are accepted too. A single session
  deadline bounds the loop; native protocol behavior is not claimed identical
  to every upstream SDK edge case.
- Pi shares task lifecycle, persistence and cancellation with the CLI engines,
  but requires neither a child process nor Docker/Linux MCP helper.

## The phone reads the native store (`backend/src/store.rs`, `source/`, `review/`)

Opening a pull request MUST NOT wait on all file pages when a valid cached
snapshot exists. Rust owns filesystem storage and enforces separate diff and
full-file budgets. The browser retains its `unstorage` RPC driver, while phone
methods expose domain operations instead of arbitrary storage access.

- Rust defaults to `<git-common-dir>/pulls-review-rust`. One process locks each
  root; file replacement is atomic and updates serialized. Multi-key updates
  are not crash-atomic. Diff eviction is 50 entries / 50 MiB of recorded diff
  sizes; full-file caching has its own persistent 500-entry / 25 MiB LRU.
- Unless `--no-warm`, a three-minute scheduler discovers up to 150 recently
  updated open inbox PRs, probes base/head identities, caches missing or changed
  diffs and refreshes changed/30-minute-stale overviews. Background writes do
  not mark a PR viewed and cannot overwrite a head stored after the read.
- `github-pr` returns the stored diff when the live identity matches and can
  mark the stored snapshot stale when GitHub is unavailable. `fresh` requests
  refetch. The store retains the full diff; `lite` trims only the response and
  `diff-patches` serves omitted hunks by path.
- `pr-overview` stores fetched overviews; `cached: true` reads that copy without
  a new GitHub request. Repository/inbox refresh coordination shares results and
  prevents queued duplicate fetches.
- `--preanalyze` opts into a separate ten-minute scheduler for review-requested
  open PRs with at most 300 changed files. Each eligible head is attempted at
  most once per server lifetime, failures included. It may incur provider costs;
  `--no-warm` does not disable an explicitly selected preanalysis worker.
- Legacy `pr-meta`/`pr-body` records can be read from a copy of a stopped old
  cache; new native records use `diff:` and synthesize browser-compatible views.
  Rollback requires a pinned older server checkout and the untouched old cache.
  There is no automatic reverse migration or current TypeScript server fallback.

## The brief: a page the agent designs (`backend/src/brief.rs`)

A native `brief` task asks the provider for OpenUI Lang using the shared product
component vocabulary. Rust parses and lowers this into the native `{type, props}`
tree, and the web/Android clients draw it. Browser-only analysis retains its
TypeScript parser in `packages/core/src/analyze/adapters/llm/brief.ts` and the
locked `@openuidev/lang-core` package.

- Only allowlisted components/argument shapes are accepted. References point to
  changed paths/lines so clients navigate to source; unknown components never
  render. Component changes require corresponding parser and client updates.
- Saved Lang is parsed on read. Bounded partial recovery can preserve useful
  saved-tree fragments; generation is stricter, allowing an initial attempt and
  two repair attempts before failing an invalid result. This differs from the
  original server's partial-generation retention behavior.
- Reactive expressions, state, query/mutation and action syntax are inert AST.
  No parser step executes JavaScript, actions, network requests or tools.
  Limits bound input, nesting, node expansion and output allocation.
- Native Mermaid rendering/sanitization and syntect/two-face highlighting retain
  the client response shapes and Pierre palettes. Their output is not byte- or
  pixel-identical to the browser's Mermaid/Shiki rendering.
- `@openuidev/lang-core` remains disallowed from running its telemetry postinstall
  (`allowBuilds` in `pnpm-workspace.yaml`); the browser parser needs no build.

## Jev sorts pull requests and files (`backend/src/jev.rs`)

With `PULLS_REVIEW_TYPESAFE_API_KEY` or `TYPESAFE_API_KEY`, Rust asks the fixed
TypeSafe `systemone` service and `jev-latest` model typed classification questions.
It does not recreate that proprietary model or send its key to clients/AI jobs.

- No key or a 401 disables classification for the process. Clients receive
  disabled state instead of a failure. Requests are paid, paced, concurrency
  bounded and retried on rate/temporary-overload responses.
- Full-review preparation (`analyze`, `critique`, `tour`, `brief`, preanalysis)
  starts eligible classification with bounded waiting. Inbox classification is
  requested explicitly through `my-pulls-cached` with `classify: true`.
  Opening/polling `jev-files` alone does not start paid work.
- `my-pulls` MUST NOT wait for Jev. Available kind/risk is merged into open PR
  rows and the cached snapshot identifies the active classifier. A result for
  an older head/title cannot replace the newer one.
- At most 300 non-binary files receive `p0`/`p1`/`p2` attention; low-confidence
  answers conservatively become `p0`. File polls report current ratings,
  pending count, and current PR kind/risk. The phone drops marks as the head moves.
- Cached triage reaches a selected AI review through `triage_files`, never via
  the service key. Persistent PR/file identities avoid charging again for the
  same classification; a changed diff/base context invalidates reuse.

## Shared browser and Action layers

The sections below describe the retained TypeScript providers, analysis adapters,
cache abstractions and view contracts. The public browser and Action use them
directly; the self-hosted app also uses shared client types/cache glue while
backend work runs in Rust. A TypeScript module path here does not imply a second
local server implementation.

## Invariants

- The public site requires no centrally hosted review backend or OAuth
  client-secret exchange. Its GitHub and model access runs in the browser using
  user-supplied credentials. The separate Actions CLI runs in the user's CI.
  The optional user-run server owns its credentials, storage, AI jobs, and RPC
  routes; server credentials MUST NOT be exposed to the web or phone client.
- The app MUST build as a static SPA and deploy as static files, on Vercel.
  `vercel.json` carries a catch-all rewrite to `/index.html` so the dynamic
  routes under `/gh/...` work on direct navigation/refresh, not
  just client-side routing after landing on `/`.
- Every composable, component and utility is imported explicitly - no
  auto-import plugin.
- Unit tests (Vitest) SHOULD be co-located with the source file they test
  (`foo.ts` + `foo.test.ts`) wherever possible. This applies to every pure
  module - `patch-parser`, analyze adapters, cache modules, provider
  normalizers - not just a "utils" subset. A top-level `tests/` directory is
  reserved for higher-level integration tests (multiple modules wired
  together, e.g. provider + adapter + cache end-to-end), not a dumping
  ground for what should be co-located unit tests.

## Data flow: DiffSource -> Analyze adapter -> Cache -> View

Two pluggable-adapter boundaries exist so that adding a data source or an
analysis strategy later never touches the view layer:

- **`DiffSource`** (`core/types/source.ts`) is one diff's origin, already bound
  to its target and `Credentials`: `key()` (the cache key, known before any
  fetch), `fetch()` (normalized into the canonical `DiffsPayload`), and optional
  capability members - `fingerprint()` for staleness, `loadFile()` for full file
  content, `viewer()` for who the credentials act as, and the framework-free
  `reviews: ReviewsApi` / `sharing: SharingApi` for review threads and shared
  analyses (the app's sub-stores only wrap them; a refused write surfaces as
  the `writeForbidden` diagnostic). `createDiffsStore(source, ...)` has one
  load path and derives what it offers from those members, never from which
  kind of source it holds. Every payload carries a structured `ref`
  (`SourceRef`, a valibot variant by `kind`); `serializeRef(ref)` is the only
  key formula, and nothing parses ids back. Views render a diff's header from
  neutral payload fields (`label`, `url`, `author`) and store flags
  (`canRefresh` from `fingerprint`, `auth` from the source), and link through
  `app/source-routes.ts` - the only module that maps refs to routes and back -
  so no component checks a ref's `kind` or spells out a `/gh/...` path. `Credentials.githubToken()` is read
  per request, so a token saved later applies without rebuilding a store.
  Implementations (`core/providers/`):
  - `github-pr` (`createGithubPullRequestSource`) - GitHub REST API (PR
    metadata + paginated file list/patches) through one `GithubClient`.
  - `github-compare` / `github-commit` (`createGithubCompareSource`,
    `createGithubCommitSource`) - `base...head` against its merge base, and one
    commit against its first parent. GitHub caps both listings at 300 files.
    A compare fingerprints by its head ref; a commit never changes, so it has
    no fingerprint. Neither has reviews or sharing. `createGithubSource(ref)`
    picks the right one for any GitHub ref, served at
    `/gh/{owner}/{repo}/compare/{base}...{head}` and
    `/gh/{owner}/{repo}/commit/{sha}` by the same page as a PR.
  - `paste` (`createPasteSource`) - accepts raw unified-diff/patch text (pasted, or an uploaded
    `.diff`/`.patch` file, e.g. GitHub's `.diff` endpoint or `git diff >
diff.patch` output) via the shared `core/patch-parser.ts`. Fed to the
    `/upload` route (no param) via a fixed `sessionStorage` key, not a URL -
    deliberately not shareable as a link, since it has no live source to
    refetch even if it were content-hash-addressed.
  - `local` - a working tree, commit or range served by Rust's
    `backend/src/source/local.rs` and native patch parser. The local browser
    adapter preserves the `DiffSource` boundary through RPC; it does not execute
    Git. The former Node-only `core/local` export is removed. Historical
    `plans/04-local-provider.md` describes the original implementation.
- **`core/patch-parser.ts`** parses unified-diff / git-extended-diff text (the
  format shared by GitHub's `.diff` endpoint, `git diff` output, and plain
  `diff -u`) into canonical `FileChange[]`. It is provider-agnostic
  infrastructure, not itself a provider: `github`'s normalizer falls back to
  it only for files whose `patch` GitHub's JSON API omitted (very large
  diffs); browser/Action paste sources use the same parser. Native local and
  pasted sources use `backend/src/source/patch.rs`, preserving shared wire shapes.
- **`AnalyzeAdapter`** (`app/analyze/`) turns a `DiffsPayload` into a
  `GroupedResult` (grouped files + optional summaries). Each
  adapter lives in its own folder (`app/analyze/adapters/{id}/index.ts`):
  - `none` - implemented. A single flat group containing every file, for
    users who just want the plain file list with no classification.
  - `rule-based` - implemented. Deterministic glob-pattern classification,
    flat (1-level) groups, no LLM, no network call
    (`core/analyze/adapters/rule-based/`). Each file goes to its first matching
    rule (code, types, data, styles, tests, scripts, ci, config, deps, i18n,
    docs, assets, generated, other; precedence is documented in `rules.ts`).
    Groups follow `GROUP_ORDER` (review relevance: code first, generated and
    unknown binaries last). Inside a group, files sort directory-aware
    (`order.ts`: a directory's own files before its subfolders, entry files
    such as `index.*` first, then natural name order). A test whose source is
    in the diff is placed right after that source in the code group; other
    tests stay in `tests`. When at least two areas (`packages/<name>`,
    `src/<dir>`, or the first directory) each have `MIN_AREA_FILES` code files,
    code splits into one flat group per area (key `code:<area>`), and smaller
    areas share one `code:*` group. The output MUST depend only on the set of
    files, not on their order in the diff. Core measures each group
    (`RuleGroupFacts`: files, +/-, new/deleted/renamed, paired tests, area) and
    the app's `text(key, facts)` words its label and summary, so they stay
    translatable.
  - `llm` - implemented for browser/Action execution. A pi-agent-core tool loop against whichever
    provider the caller resolved (`resolveModel(llm)`: AI Gateway, Anthropic,
    or OpenAI-compatible - only the selected one is ever called). Core's
    `runLlmAnalysis(diff, resolved, locale)` knows nothing of Settings; the
    app binds it to `settings.value` in `app/analyze/adapters/llm/index.ts`. The prompt
    carries a file manifest, the commit subject lines when there is more than
    one commit, and the full diffs when they are small; the model pulls
    diffs on demand with `read_diffs` and finishes with `submit_grouping`,
    whose coverage check rejects a grouping that misses or invents paths so
    the model must fix it. The prompt itself stays English whatever the
    user's language; only its closing line (`Respond and categorize in
<language>.`) names the locale, and the result is stamped with
    that `locale` so the view knows what language it is in. A read budget and a turn cap bound the run, and
    structured progress (`{ kind: 'reading', paths }`) streams to the header,
    worded by `app/i18n/core-messages.ts`. Failures MUST
    surface as `llm.error` - there is no silent `rule-based` fallback, and
    nothing but real model output is ever stored under `llm`. Everything that
    runs or chats with a model - `stores/llm-store.ts` (the `DiffsStore.llm`
    sub-store), `composables/useLlmChat.ts`, the chat components, and the pi
    runtime (`@pulls.review/core/llm`) behind them - is only
    reached via dynamic `import()` behind the compile-time
    `import.meta.env.PR_LLM` flag: the site builds with it on (lazy chunks),
    the embed with it off (the `import()`s are dead code, so its single IIFE
    never bundles any of it, nor `@ai-sdk/gateway`), and
    `vite.config.embed.ts` fails the build if an LLM SDK slips in anyway.
    `store.llm` is `undefined` iff the flag is off.
  - Follow-up chat reuses the analysis transcript: `llmSession` (`messages`
    - `chatStartIndex`) is persisted alongside the result in the cached diff
      entry, and `composables/useLlmChat.ts` builds a fresh pi `Agent` from it
      per message (`read_diffs` plus `update_grouping`, which replaces
      `analyzedBy.llm` in store and cache). Re-analyze or loading a shared
      result replaces the session; a refetch keeps it (the transcript still
      reads the live diff). Results cached without one show `Re-analyze to
enable chat`.
  - `web-llm` - TODO, stub only. Fully in-browser model inference, no network
    call at analyze time.
  - LLM-sourced groups MAY nest one level (root group -> children, e.g.
    `docs/featureA`); `rule-based` groups MUST stay flat. Depth is capped at 2
    total - enforced structurally in the schema (child groups have no further
    `children`), not by convention.
  - A `GroupedResult` references files by path and MAY predate the diff it is
    viewed with (new commits since an AI run, a shared result, a model that
    omitted files). `resolveGroups` (`app/components/diff/group-utils.ts`)
    reconciles the two at view time and MUST NOT silently drop anything: a
    path still in the diff resolves to its `FileChange` (a renamed file is
    found by its `previousPath` too), a path that left the diff is kept as
    `missing` and rendered as removed, and files no group names are collected
    into a trailing `Uncategorized` group. Adapters therefore don't need their
    own catch-all group.
- **View components** (`app/components/`) MUST stay pure and data-driven: they
  receive a single `DiffsStore` (`app/stores/types.ts`) as a `store` prop,
  threaded explicitly down the tree (no provide/inject, no global singleton
  registry), and call its methods directly (`store.setReviewed(...)`,
  `store.llm?.reanalyze()`) instead of emitting events that bubble up to
  whoever created the store. `store.llm` is `undefined` when LLM analysis
  isn't compiled into the current build (`PR_LLM` off: the GitHub-embedded view),
  which components use structurally to hide the _run_ side of AI (analyze,
  chat). The AI _result_ itself (`store.aiResult`, `analyzeMode`) lives on the
  store root, because the embed can still hold one loaded from a shared PR
  comment (below). Components MUST
  NOT know which provider or analyze adapter produced the store's data, and
  MUST NOT talk to storage/providers/adapters directly - only the store
  factories (`app/stores/diffs-store.ts`'s `createDiffsStore`,
  `app/stores/mock-diffs-store.ts`'s `createMockDiffsStore`) do that. This
  keeps every view component Storybook-friendly and isomorphic across a real
  PR, a pasted patch, and mock data - a story just builds a different store.

## Sharing an AI result through the PR (`plans/07-share-result.md`)

An `llm`/`web-llm` `GroupedResult` MAY be posted as a Conversation (issue)
comment on the GitHub PR, one comment per user, updated in place on later
shares. This is the only way the github.com embed gets an AI result at all
(GitHub's CSP blocks model providers), and lets token-less visitors on public
repos read one. Contract:

- Body: line 1 is the literal marker `<!-- pulls.review data -->`; then a link
  to `https://pulls.review/gh/{owner}/{repo}/{n}?from={login}` (hardcoded
  origin - a preview host MUST NOT leak into a public comment); then a
  `<details>` with model/time/head attribution and a fenced ` ```json `
  block `{ headSha, result }` (`SharedAnalysisSchema`). If plain JSON exceeds
  the comment budget, the writer uses a base64-compressed `lz-string` fence;
  oversized compressed comments fail explicitly. The historical template is in
  `plans/07-share-result.md`; the current formatter/parser lives in
  `core/providers/github/shared-analysis-comment.ts`. Unmarked or invalid
  comments are ignored, never an error. Only the `GroupedResult` is shared - never the chat transcript.
- Loading writes the result into the cached diff under its own `source` with
  `sharedBy: login`, so the view credits it and never offers to re-share it.
  Discovery is one best-effort `GET` of the first 100 comments, run only when
  the store has no AI result. `?from=` on the site loads that user directly
  unless it would replace a locally generated result (then it's offered).
- A shared result whose `headSha` differs from the loaded diff is offered
  labelled outdated, not hidden. Likewise one whose `locale` differs from the
  UI language is offered labelled with that language (results from before the
  field existed are treated as matching). Sharing a non-English result shows a
  banner recommending English for public projects; the confirm button reads
  `Share anyway`.
- An analysis run for the app (`mentions` on the run) writes every place it
  names as a repository-relative Markdown link (`MENTION_RULE`), which the
  phone turns into a jump to that line. Text posted on GitHub, the shared
  payload and AI review comments alike, MUST have those links rewritten to
  `github.com/{owner}/{repo}/blob/{headSha}/…` first (`githubCites`), since a
  relative link there resolves against the pull request's page.

## Canonical data structures

The shared TypeScript shapes (`DiffsPayload`, `GroupedResult`, `PrCacheEntry`,
`FileReviewState`, ...) are defined as `valibot` schemas first, with the TS
type derived via `v.InferOutput`. Rust preserves the client wire shapes through
native validation and serialization; it does not execute those schemas. Runtime validation happens at the two
boundaries that see untrusted/versioned data: normalizing a provider's raw
API response, and reading an entry back out of storage.

Every `FileChange` carries a `sha` - the provider's content-addressed hash
for that file: GitHub's blob SHA (from the JSON API, or extracted from a
parsed patch's `index <old>..<new>` line - git-generated diffs carry real
blob shas even as plain text), falling back to a computed SHA-256 hash of the
patch content only when no such line exists (e.g. a plain POSIX `diff -u`
paste). This is the key primitive that lets the app tell whether a file
actually changed between two fetches of the same PR without diffing patch
text, and is also the key for persisted review state (see below).

## Caching (`@pulls.review/core/cache`, unstorage)

Persistence is a set of repositories in core (`DiffCache`, `ReviewMarks`,
`FileContentCache`, `PullRequestListCache`, built together by
`createCacheRepositories(storage)`) over any
[`unstorage`](https://github.com/unjs/unstorage) `Storage`, so the backend can
be swapped (IndexedDB in the browser, `memory` in tests, and the existing local
server RPC driver into Rust) without touching a call site. Rust implements its
own filesystem cache and synthesizes compatible legacy views; it does not run
`unstorage` or the deleted TypeScript server. The app builds its repositories once, in
the app context (`app/app-context.ts`, IndexedDB via `app/cache/browser-cache.ts`),
installed once per app (the SPA, the embed's custom element): pages and
top-level composables inject it and pass it to store factories as `cache`;
there is no module singleton.

One `unstorage` instance, logical collections via key prefix (unstorage is
flat key-value, so there's no native "object store" split):

- `pr-meta:*` + `pr-body:*` - one cached diff, split in two documents under the
  same key. The meta half holds what eviction, the recent lists and per-view
  bookkeeping need (`headSha`, title, counts, file shas, `lastViewedAt`, size,
  shared comment, `changedSinceReviewed`), so none of them reads a whole diff;
  the body holds the raw diff, the `llm`/`web-llm` `GroupedResult`s, the review
  snapshot and the `llmSession` chat transcript (counted toward the size
  budget). Keyed by the diff's id, `github:{owner}/{repo}#{number}` for github
  or `paste:{contentHash}` for paste (content hash is an internal cache key
  only, never exposed in a URL - see the `paste` provider note above). Writes
  to one key run one at a time inside `DiffCache`, so overlapping
  read-modify-writes never drop each other's changes. Entries from before the
  split (`pr:*`) are deleted on the next eviction pass. App-managed LRU eviction
  (size/count budget), not left to browser eviction heuristics. For `github`,
  staleness is detected by comparing cached vs. live `headSha` and surfaced as
  a non-intrusive refresh banner, unless the user opted into auto-refresh
  (banner checkbox or Settings) - the app MUST NOT re-run a paid LLM analysis
  on refetch, and MUST NOT silently go stale. `paste` entries have no live
  source, so no staleness check applies; if evicted, `/upload` simply has
  nothing to show until the user pastes again.
  Only results that cost a model call are persisted: `rule-based` and `none`
  MUST be recomputed from the diff on every load, so they can never disagree
  with it. A refetch replaces the entry's diff but keeps its AI result and
  session - `resolveGroups` reconciles them against the new diff, so a paid
  analysis is never thrown away by new commits.
- `review:*` - per-file "reviewed" marks, keyed by `review:{FileChange.sha}`,
  not by path or PR. This is deliberate: if a PR gets new commits and a
  file's `sha` is unchanged, its reviewed mark MUST survive; only files whose
  `sha` changed lose their mark. Pruned opportunistically whenever the diff cache
  evicts, by walking the remaining entries' shas. Which of those files _had_
  been reviewed is remembered on the cached diff as `changedSinceReviewed`
  (paths): `putDiff` flags every path whose outgoing `sha` carried a mark and
  whose incoming `sha` differs, and the flag survives further commits until
  the user marks the file again (`DiffsStore.setReviewed`), which is how the
  view shows "reviewed, but changed since" instead of plain "unreviewed".
- `file-content:*` + `file-content-meta:*` - full-file content and its byte/access
  metadata, fetched on demand through `DiffsStore.fileContent` for sources with
  `loadFile`, including GitHub and local Git. Keys hash the tuple of diff scope,
  revision, and path; the scope isolates non-commit working-tree fingerprints.
  A separate LRU defaults to 500 entries / 25 MiB, counts UTF-8 bytes, and removes
  corrupt/unaccounted entries. Evicting a diff also drops its full-file versions.
  Storage quota failures do not fail a successful source load. Server callers
  can pass their configured content budget.
- `pulls:*` - a repo's first page of open PRs (`PullRequestListPage`), keyed
  `pulls:{owner}/{repo}`. Stale-while-revalidate: it renders instantly on
  revisit and is silently replaced by a fresh first page every time - unlike
  a cached diff, nothing paid hangs off a list, so auto-refetching costs nothing.
  Later pages chain off it via `next` and are never persisted. Search is
  client-side (fzf over the loaded rows) and drains the remaining pages so
  its results cover the whole repo; only open/draft PRs are ever listed. With
  a token the rows come from GraphQL search (review decision, CI rollup,
  linked issues); without one, from REST search, which lacks those three.

## Deferred scope and migration limits

- `web-llm` model inference remains a stub.
- The public static site has no server-rendered per-PR social/OG previews.
- Native local review, inbox/history, review submission and GitHub mutations
  have source implementations. Earlier TypeScript server plans are historical;
  the current npm launcher starts Rust only.
- The locked native build, 43 targeted native fixtures and two full-binary
  offline smokes passed on the final removal revision `fac9ff30`.
  Live providers/catalogs, Docker/shared login, TypeSafe, GitHub writes and Android
  devices have not been exercised. The existing web/Android UIs and separate
  GitHub Action are retained. See the [migration notes](../docs/RUST_BACKEND_MIGRATION.md).

## UI conventions

- The web looks like the Android app and is drawn from the same source
  (DESIGN.md rule 106). `android/DESIGN.md`'s front matter is the one token
  source: `android/scripts/design/web.mjs` writes it, the phone's `Lu` icons
  and its `PullsFonts` into `app/src/design/*.generated.*`, and
  `app/src/design/theme.ts` turns those into the UnoCSS theme, CSS variables
  (`--c-*`, `--shadow-*`), `kit-*` shortcuts and the `i-lu-*` icon set, and
  redraws `@antfu/design`'s shortcuts in them. `app/src/components/kit/` holds
  the phone's kit pieces (float button, capsule tabs, soft chip, search pill,
  group heading). The web locales are the strings' source; the APK carries
  copies. `pnpm run android:design:update` regenerates all of it, and the
  `web` check fails when any generated file or copy drifts. Prefer `i-lu-*`
  icons, then Phosphor's regular weight; no link-out arrows or decorative AI
  sparkles.
- Every user-facing string goes through vue-i18n (`app/i18n/`): `$t()` in
  templates, `useI18n()` in component scripts, `i18n.global.t` in plain
  modules. Core has no vue-i18n: the app's adapter registry
  (`app/analyze/index.ts`) supplies the rule-based group text, and
  `app/i18n/core-messages.ts` words core's progress events and translates its
  nostics diagnostics by code (`localizeError`), falling back to the English
  message. English is bundled and is the key schema (`locales/en.json`, typed
  via `DefineLocaleMessage`); other locales load on demand. One
  `settings.locale` drives both the UI and the language the LLM writes
  summaries in; it is seeded from `navigator.languages` (core's `locales.ts`)
  and switched in Settings (`LayoutSettingsSection.vue`), as on the phone. Model-facing
  text (prompts, tool errors) and diagnostics that embed URLs/status codes
  stay English.
- Settings (GitHub PAT, later model keys) and loading a pasted/uploaded diff
  are both modals/panels (`SettingsModal.vue`/`LoadDiffModal.vue` wrapping
  pure `*Panel.vue` content), triggered from `AppHeader.vue` - never routed
  pages. `AppHeader` itself only renders on the landing pages
  (`pages-web/index.vue`, and `pages-local/index.vue` in the `PR_LOCAL`
  build). The reading views stay header-free: their own sticky `DiffsHeader`
  is the only scroll nav there.
- Routed pages live in `app/pages-web/` (the site: landing, `/upload`,
  `/gh/...`) and `app/pages-local/` (only registered by the `PR_LOCAL` build:
  the ref picker and the local review page).
- A generated userscript (`scripts/build-userscript.ts`) mounts
  `<pulls-review-embed-panel>` (`app/embed/`, a Vue custom element built as
  one IIFE with `PR_EMBED` on and `PR_LLM` off) into `github.com` pull request
  pages. The PR view renders inside its shadow root with a pre-compiled
  stylesheet; `githubIntegration.ts` is the only code touching GitHub's own
  DOM - a "Review Changes" tab beside "Files changed" and the edge button,
  both dotted when a shared analysis exists for the PR; while open, the
  conversation sidebar is hidden and `<html>` shrinks to the remaining width
  so the page reflows beside the drawer; `https://pulls.review/gh/...` links
  to the current PR open in the drawer (honouring `?from=`), with an appended
  icon that still opens the site in a new tab.
- Diff layout (split/unified) is user-toggleable; both are supported by
  `@pierre/diffs`.
- The group list is user-toggleable between tabs in the sticky `DiffsHeader`
  and a sticky left sidebar tree (`DiffGroupSidebar`, subgroups nested under
  their parent). The sidebar only applies at `lg` and up; narrower viewports
  always get the tabs. Persisted like the diff layout (`state/group-nav.ts`).
- Large PRs are a first-class case, not an edge case: file lists and diff
  content MUST be virtualized (`@tanstack/vue-virtual`).
- Every component in `app/components/` gets a Storybook story, backed by a
  mix of real captured fixtures (provider + rule-based adapter output on real
  PRs) and hand-authored synthetic fixtures for edge cases.

## Latest-main rebase, 16 October 2026

The rewrite is rebased onto main `c666dbcaa734081c3b26f4fb3e259ad8ab760a9b`
(Android 0.45.0 with Google sign-in and posted-link/API-path security fixes). The newer web/mobile
interfaces remain intact. Backend additions from main now have native modules:
`account.rs` / `google_sign_in.rs` (password-test and verified Google accounts,
hashed sessions/agent keys and settings),
`posted.rs` / `agent_api.rs` (persistent posted diffs and authenticated agent API),
`host.rs` (optional checkout and served artifacts), and `github_stats.rs` (cached
profile counts). The lightweight `pr-text` route refreshes edited descriptions;
analytics include task token/cache-read totals. Posted diffs are excluded from
cache eviction and their data remains account scoped.

All TypeScript files newly introduced under `packages/cli/src` are removed too.
The browser-consumed posted source/types stay in core, as do the web/mobile UI
and separate GitHub Action. No Cloudflare deployment is part of this change.
Earlier validation below is historical; fresh compile/check and review evidence
for the rebased revision is recorded in the additional-review section of
`docs/RUST_REWRITE_REVIEW.md`. Google sign-in from the later main update is
ported independently before the additional simplification sequence.

