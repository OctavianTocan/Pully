# Goal

pulls.review is a better way to review a GitHub pull request's
diff: grouped, summarized, and fast, instead of GitHub's flat file-by-file
list. It renders any PR at `/gh/{owner}/{repo}/{number}` (and any compare or
commit at `/gh/{owner}/{repo}/compare/{base}...{head}` and `.../commit/{sha}`), lists a repo's open
PRs at `/gh/{owner}/{repo}`, and renders any raw `.diff`/`.patch` text pasted
or uploaded at `/upload`. The public site is a static browser application;
users can also run their own server for local Git reviews, the Android client,
persistent review state, and server-owned AI work. The [Rust backend](../backend/README.md)
is the server implementation. The `pulls.review` npm package launches a separately
built native executable and packages the existing web UI and curated lenses;
there is no TypeScript backend fallback. A locked full build, 43 targeted native
fixtures, and two offline smokes passed on the final removal revision `fac9ff30`.
Live integrations remain unverified.
See the [migration status](../docs/RUST_BACKEND_MIGRATION.md).

## Why

GitHub's own PR view doesn't scale well to large or sprawling changes: every
file gets equal visual weight regardless of whether it's the actual change,
a generated lockfile, or a docs update, and there's no narrative tying
related files together. [Linear's PR review
guides](https://linear.app/docs/diffs#guides) show what's possible instead -
breaking a large PR into explained, grouped sections before a reviewer goes
file-by-file. pulls.review aims at that same experience, but as a standalone
tool usable on public or private PRs, with a browser-only mode and an optional
user-run backend, not tied to a project management product.

## Core value proposition

1. **Grouping** - files are grouped (code / tests / docs / deps / config /
   generated / other, or LLM-defined groups) instead of presented as
   one flat list, so a reviewer can skip straight to what matters.
2. **Summarization** - when an LLM is configured, each group (and the PR as
   a whole) gets a short written summary. Without one, a fast deterministic
   rule-based grouping still gives structure for free.
3. **Performance** - large PRs (hundreds of files, huge individual files)
   should stay fast via virtualization, not degrade the way GitHub's own UI
   does.
4. **User-controlled execution** - the public site fetches, caches, and analyzes
   in the visitor's browser. The separate `@pulls.review/actions` CLI can analyze
   in the user's CI; the `pulls.review` server runs local and phone workflows
   on the user's machine through the native Rust executable.
   Credentials stay in the selected execution environment, and server-owned
   credentials never reach its clients. No centrally hosted review backend is
   required.

## Longer-term direction (see `01-architecture.md` for what's deferred vs. built)

- LLM-powered analysis (built) as an opt-in alongside the always-available
  rule-based fallback. Fully in-browser model inference (`web-llm`) requiring no
  API key remains deferred.
- Analyzing every PR once from a workflow (built): the `@pulls.review/actions` CLI and
  its GitHub Action post the same shared-analysis comment a reviewer can, so
  the site and the embed show the result without anyone spending a key.
- Reviewing PRs interactively from within pulls.review (built): inline review
  comment threads, replies, and GitHub-style review submission
  (approve/request changes/comment), degrading to read-only when the token
  can't write.
- Sources beyond GitHub: pasted/uploaded raw patches (built early, since
  it's essentially free once a patch parser exists), GitHub compare ranges and
  single commits (built), and a `local` source for a git working tree, commit
  or range (built; `plans/04-local-provider.md`).
- A userscript (built) that embeds pulls.review as a sidepanel directly inside GitHub's own
  PR page, next to the real comment thread - reviewing with pulls.review's grouping
  without leaving github.com.
- A `pulls.review` launcher (built) that starts the native Rust server to review
  local Git changes (the working tree, any ref range, one commit) in the browser.
  The existing local UI uses native HTTP and the server also exposes a compatible
  devframe data transport. Page routes select targets without switching branches.
  Historical plans also discuss static snapshots and the original TypeScript
  implementation; those plans are not a current feature inventory.
- The native Rust backend preserves existing web and Android client interfaces.
  Its HTTP/RPC handlers, local Git source, AI orchestration, storage, and native
  presentation adapters replace the removed TypeScript server. Browser-used core,
  Vue, Kotlin/Compose, and the GitHub Action remain in their original languages.
  Full native build and bounded presentation/HTTP checks have passed on the
  pre-removal revision. Live providers, Docker/login, GitHub writes, and Android
  devices still require deliberate validation before claiming end-to-end parity.

## Explicit non-goals

- Hosting repositories or replacing GitHub's CI and issue tracker. The
  self-hosted review workflow can display GitHub checks and operate on PRs,
  including merging; GitHub remains the source of truth. The separate CI action
  posts shared analysis and does not gate merging.
- A centrally hosted account service. The product has an inbox, PR lists, and
  review history, with state in the browser or the user's own server. The phone
  has no application login; network access or an authenticated reverse proxy
  controls who can reach its server.
