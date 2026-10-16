# Rust backend migration

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

## Scope and baseline

This branch replaces the local TypeScript backend with the native `backend/`
server on top of the fixes in PR #48 (initial baseline
`45f7a7d3648a8c1ef494722db0a431aedfaa5a04`, reconciled at
`fa2c35327ae669b020317399fa8ebe04175ef479` with the 0.37 analytics changes from main).
All 75 files now present on main under `packages/cli/src` and eight Node-only `packages/core/src/local*`
files have been removed, along with the old CLI TypeScript build/test configs and
`@pulls.review/core/local` export. `packages/cli` now contains a dependency-free
JavaScript native launcher and web/curated-skills packaging. Its package has no
TypeScript server dependencies and never falls back to the removed implementation.
The 35 original server contract IDs remain enforced under `backend/CONTRACTS`;
client/shared contracts stay with their retained code.

The server implements the previously disclosed parity areas: CLI catalogs and
streamed activity, chat regrouping and source tools, TypeSafe/Jev, full-file
caching and inbox warming, inert OpenUI parsing, native Mermaid/highlighting,
compressed sharing, devframe transport, preanalysis, local CLI targets, shared
Docker login support, a configurable Linux MCP helper, server-side `gh auth`
fallback, curated-lens metadata/instructions, and declared RPC argument/result
schemas enforced natively before/after dispatch.

**Implementation coverage is not complete end-to-end verification.** The Cargo
download blocker was cleared through GitHub CI and a resolved lockfile. The
locked full build, all 43 targeted native fixtures, and two full-binary offline
HTTP/analytics smokes passed on the complete removal revision `fac9ff30b532297145b890d91cde2ec2c3af6ca6`. Local launcher/package
checks have passed; live providers, Docker/login, TypeSafe, GitHub writes, and
Android-device workflows remain untested. The [verification ledger](#verification-scope)
separates these results from the earlier renderer-excluding partial harness.

The existing Vue web and Kotlin/Compose Android interfaces have not been
rewritten. Client changes are transport/cache glue. The native server is not a
wrapper around the Node server: HTTP, GitHub requests, Git orchestration, parsing,
grouping, storage, review state, AI orchestration, provider protocol handling,
MCP, and native presentation adapters are Rust. Git remains a process dependency.
Docker jobs still run the third-party Node-based Claude Code/Codex CLIs, and the
frontend still builds with TypeScript, Vue, and Vite and runs JavaScript in the
browser. Browser-only providers, the public static website, and GitHub Actions
remain in the original TypeScript implementation.

See [backend/README.md](../backend/README.md) for commands, flags, environment
variables, authentication precedence, and network precautions.

## Coverage matrix

“Implemented” means source is present. Full compilation and bounded native
runtime evidence apply to the named revision below, not automatically to later
changes or to live providers, production deployment, Android devices or GitHub
writes. Native renderer choices intentionally differ from their TypeScript
counterparts.

| Original backend responsibility                                          | Rust implementation                                                                                                                               | Differences / limits                                                                                                                                                                                                                                                                          |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Native Android `{name,args}` RPC and error/result envelopes              | Axum `POST /api/rpc`, explicit native method allowlist                                                                                            | Network access control remains required; account, settings, keys and posted diffs additionally require a Pully session. Unknown methods are rejected; raw storage/credential proxy stay browser-only.                                                                                         |
| Local web connection and terminal-code trust                             | HTTP backend detection/session endpoints and `POST /api/web-rpc`; devframe-compatible `/__connection.json` and `/__ws`                            | WebSocket transport implements the app's registered JSON/birpc methods and trust exchange, not arbitrary devframe plugins or executable structured-clone objects.                                                                                                                             |
| Local web serving and CLI navigation                                     | Existing `PR_LOCAL` static build with HTML fallback; optional target, `--worktree`, browser launch, `--no-open`                                   | Node/pnpm builds the SPA; launcher supplies assets or direct Rust uses `--assets`. Route selection never changes checkout/index. `--agents` accepts only `docker`.                                                                                                                            |
| Android artifact downloads                                               | Allowlisted files under `--android-dir`, with containment checks                                                                                  | No APK build, signing, publication, or update scheduler.                                                                                                                                                                                                                                      |
| GitHub PR, compare, and commit sources                                   | Bounded REST, canonical diffs, paginated files/commits, missing-patch recovery                                                                    | Fixed `api.github.com`. Incomplete inventories fail; compare at 300 files and commit at 3,000 files are conservatively rejected. Unrecoverable patches retain truncation information.                                                                                                         |
| GitHub authentication                                                    | Environment credentials followed by bounded server-account `gh auth token` lookup                                                                 | No browser sign-in discovery, GitHub Enterprise configuration, or token delivery to clients. Docker login volume is separate.                                                                                                                                                                 |
| Local repository, worktree, commit, and range sources                    | Read-only Git, resolved commit refs, patch parsing, untracked-file reads, full-file lookup                                                        | Secure worktree file reads/search require Unix. Windows/non-Unix cannot snapshot untracked files or expand/search worktree file contents; these paths fail closed. Committed Git sources remain available. Worktree fingerprint is not immutable. Git is required; no branch/index switching. |
| Pasted diffs                                                             | Patch parsing and browser `paste-open`/`paste-diff`                                                                                               | No repository-backed full-file lookup. These open methods are not on the phone allowlist.                                                                                                                                                                                                     |
| Deterministic rule grouping                                              | Native patterns, categories, test/source pairing, stable ordering, large code-area subdivision                                                    | Distinct from AI intent grouping; does not reproduce every TypeScript glob/runtime behavior.                                                                                                                                                                                                  |
| Inbox and repository PR lists                                            | Search, deduplication, roles, enrichment, stored snapshots, Jev kind/risk                                                                         | Inbox retains the newest 100 results per role; broader histories are not exhaustive. Repository pagination is capped at ten pages.                                                                                                                                                            |
| Cache warming                                                            | Every three minutes, discover up to 150 recently updated open inbox PRs; cache/probe diffs in pairs and refresh changed/30-minute-stale overviews | Requires GitHub authentication; does not mark viewed. `--no-warm` disables it. Network and rate limits still apply.                                                                                                                                                                           |
| Preanalysis                                                              | Opt-in `--preanalyze`, ten-minute scheduler, engine/model selection, persisted analysis and analytics                                             | Review-requested open PRs only; maximum 300 changed files; each eligible head attempted at most once per server lifetime, including failures. May incur provider charges.                                                                                                                     |
| Persistent cache and browser driver                                      | Atomic JSON replacement, root locking, serialized updates, compare-and-exchange, legacy reads                                                     | One native writer per root; no live shared TS/Rust writers. Multi-key changes are not crash-atomic transactions.                                                                                                                                                                              |
| Full-file caching                                                        | Persistent UTF-8 byte/count LRU, scoped by review/revision/path, 500 entries / 25 MiB                                                             | Separate from diff eviction; corrupt/orphaned entries are misses. Quota failures do not fail a successful source read.                                                                                                                                                                        |
| Review state, recent/viewed PRs, analysis and chat                       | Native state/cache handlers and archived-chat restore/clear coordination                                                                          | Running task records remain in memory. Restart loses active runs, while persisted completed results remain.                                                                                                                                                                                   |
| PR overview, stack, discussion, review state                             | Native REST/GraphQL and snapshots                                                                                                                 | Shared analysis: first 100 comments. Discussion: 100-record pages, incomplete inventories rejected at 30 pages, bounded output. Live response compatibility remains unverified.                                                                                                               |
| Pending reviews, comments/replies, resolve, edit/delete, submit/discard  | Real native GitHub operations with ownership/target/head guards where implemented                                                                 | No simulated success. GitHub rules/token permissions govern writes; no live mutation verification is claimed.                                                                                                                                                                                 |
| Merge, auto-merge/cancel, update branch, edit, close/reopen, draft/ready | Native GraphQL mutations                                                                                                                          | Real repository changes. Validate deliberately against a disposable PR before production use.                                                                                                                                                                                                 |
| Posting selected critique findings                                       | Selection validation, saved-current-critique/current-head checks, finding anchors and posting                                                     | Requires selected finding IDs; live posting remains unverified.                                                                                                                                                                                                                               |
| Shared analysis comments                                                 | JSON and `lz-string` UTF-16/base64 parsing, listing, load, post/update                                                                            | Bounded decompression. New shares compress when needed; still reject bodies over the 60,000 UTF-16-unit budget after compression.                                                                                                                                                             |
| Server-owned AI tasks                                                    | Start/wait/list/cancel, revision snapshots, deduplication, per-diff serialization                                                                 | Page disconnects do not cancel jobs; no running-job restart recovery. Activity is normalized across engines, not an identical upstream event log.                                                                                                                                             |
| Analyze, critique, selected-line Ask, chat, tours, briefs, inbox chat    | Native orchestration/persistence and streamed task activity                                                                                       | Requires a working provider. Live model quality and every upstream schema/runtime edge case remain unverified.                                                                                                                                                                                |
| AI grouping and chat regrouping                                          | Grouping coverage repair/reconciliation, file/hunk notes, `update_grouping` with complete path coverage and current-head guards                   | Updates are available to active chat context; malformed/incomplete updates fail before saved analysis changes.                                                                                                                                                                                |
| Analysis follow-ups                                                      | Requested tour/brief tasks; skip current saved tour                                                                                               | Separate from the opt-in periodic preanalysis worker.                                                                                                                                                                                                                                         |
| Pi                                                                       | OpenAI-compatible catalog, streamed chat-completions/tool loop, structured `submit_answer`, thinking/text/tools/usage                             | Native protocol loop, no `pi-agent-core`. Compatible JSON responses are accepted; upstream runtime/watchdog behavior is not reproduced in every detail.                                                                                                                                       |
| Claude Code / Codex jobs                                                 | Docker-only CLI commands, incremental JSON activity/usage, native stdio MCP forwarding through per-run capability                                 | Environment or selected shared-volume login fields; isolated job home. No host execution fallback. Compatible Linux helper required; see below.                                                                                                                                               |
| Claude Code / Codex catalogs                                             | Claude initialization handshake, `codex debug models`, optional version/account/effort metadata; ten-minute cache and explicit refresh            | No paid prompt for discovery. Depends on installed CLI versions, Docker/image, and authentication; live discovery remains unverified.                                                                                                                                                         |
| Curated review lenses                                                    | Bounded `SKILL.md` reads, frontmatter title/description, dotted names, frontmatter-free UTF-16-budgeted instructions                              | Launcher supplies packaged skills; direct native execution can set `PULLS_REVIEW_SKILLS_DIR`. Canonical-path confinement; no host account skills.                                                                                                                                             |
| AI PR and inbox tools                                                    | Diff/hunks, file discovery, source reads/search, discussion, cached triage, chat grouping, selected-inbox PR/detail/diff reads                    | Same server-owned scoped context for Pi and bridged CLI tools. Bounded searches report unavailable reads/limits. No general shell/web/checkout authority.                                                                                                                                     |
| TypeSafe/Jev                                                             | Fixed external TypeSafe service, PR kind/risk and review-file attention, persistence, deduplication, pacing/retries, analytics, `triage_files`    | Requires server environment key; paid external service, no recreated proprietary model. Missing key/401 disables it. File-rating polls do not start paid work.                                                                                                                                |
| OpenUI brief parsing and generation                                      | Native Lang parser/plain-tree lowering, forward refs, objects and inert reactive AST, partial saved-tree recovery, validation/repair              | No JavaScript, reactive/action execution, or tool/URL invocation. Generation remains strict and data-only; see below.                                                                                                                                                                         |
| Native syntax highlighting                                               | Syntect/two-face grammar highlighting using the existing Pierre theme assets and native token/theme envelope                                      | Preserves text/palettes; scopes/token boundaries can differ from Shiki. Bounded input and plain-text fallback. Compiled and bounded presentation fixtures passed on the final removal revision `fac9ff30`.                                                                                    |
| Native Mermaid SVG                                                       | `mermaid-rs-renderer`, native XY charts, app palettes and inert AndroidSVG sanitization                                                           | Flow/state/sequence/class/ER/XY chart support; other diagram types fail. Layout and SVG differ from Mermaid.js. Compiled and bounded presentation fixtures passed on the final removal revision `fac9ff30`.                                                                                   |
| First-party analytics                                                    | Sanitized `/api/events`, UTC-day NDJSON/retention, Stats, RPC/AI/GitHub/Jev/refresh/preanalysis measurements                                      | Preserves no-content and server-off controls; frontend telemetry is unchanged.                                                                                                                                                                                                                |
| Browser-only providers, public static app, GitHub Actions                | Original TypeScript implementations retained                                                                                                      | Outside the native server migration; browser bundle retains its provider and renderer code.                                                                                                                                                                                                   |

## AI tools and Docker boundary

AI jobs use server-owned, scoped tools. They receive neither a writable checkout
nor a general shell. The PR tools are `read_file_diff`, `read_hunks`, `find_files`,
`read_source`, `search_diff`, and `read_discussion`; context additionally exposes
`search_source`, `triage_files` when cached triage is available, and
`update_grouping` for chat. Inbox contexts expose `read_pull_request` and
`read_pull_request_diff` only for PRs on the selected inbox list.

Pi calls the handlers directly. A CLI job's Linux stdio MCP helper forwards to a
separate server `/mcp` listener at an ephemeral port through
`host.docker.internal`. An unpredictable per-run token identifies server-retained
review context, becomes invalid when the run ends/cancels, and cannot grant general
RPC, cache access, or GitHub credentials. The listener binds `0.0.0.0` for Docker
reachability; restrict server network exposure and do not proxy it publicly.
This replaces the older changed-files-only CLI snapshot limitation: bridged CLI
jobs can use the same source/discussion/inbox handlers as Pi.

Source search operates at the selected revision, including unchanged files when
available, with eight concurrent reads, a 60-second deadline, 32 MiB source
budget, and 200-match cap. Results disclose stopped/incomplete searches. Offline
MCP payloads can still contain captured source snapshots; those explicitly report
that they cover captured context only. Working-tree reads remain live worktree
reads rather than immutable commit content. Source boundaries exclude Git
administration, server state and analytics directories. Discussion reads paginate
100-record pages per endpoint, reject incomplete inventories at the 30-page safety
limit, and cap rendered output at 120,000 bytes. Shared-analysis discovery retains
its separate first-100-comment limit.

CLI containers have an isolated writable job home, read-only root/inputs/helper,
dropped capabilities, no-new-privileges, and resource limits. Provider network
access is allowed. Neither server GitHub credentials, host home, checkout,
server store, host settings, nor host skills are mounted. The agent image still
contains Node-based provider CLIs.

Provider environment credentials win. Otherwise a separate network-disabled
bootstrap copies allowlisted auth fields from the existing
`pulls-review-agents-home` volume. The model process never mounts that shared
volume. A separate locked helper writes refreshed auth fields back only if the
original login fingerprint is unchanged, preventing an old job overwriting a
newer sign-in. Use the existing `login-claude` / `login-codex` setup commands.

`PULLS_REVIEW_AGENT_MCP_BINARY` selects a Linux ELF build of
`pulls-review-server` compatible with the Docker image's architecture and runtime
libraries. Linux defaults to the running executable; macOS/Windows require a
separately built Linux helper. The agent image build does not build that helper.
An ELF-header check does not prove architecture/library compatibility. Missing
Docker, credentials/image, bridge, or helper fails explicitly; **there is no
unsandboxed host runtime fallback**. `--agents` accepts only `docker`.

## Native OpenUI and presentation adapters

`backend/src/brief.rs` parses the locked OpenUI Lang syntax and lowers it to the
existing native `{type, props}` boundary. In addition to named assignments,
forward references, literals, arrays, and component calls, it preserves objects,
state/runtime references, operators, member/index access, conditionals,
assignments, and built-in reactive/action forms as inert AST. Query/mutation
forms never invoke tools. Parsing never executes JavaScript, actions, imports,
URLs, or provider requests.

Brief components and their argument types remain allowlisted, including `Stack`,
`Section`, `Text`, `Callout`, `Table`, `Checklist`, `FileList`, `Diagram`, `Code`,
and `BeforeAfter`. Parsing supports bounded recovery for saved cut-off programs
and prunes invalid array items. Generation-time validation rejects partial,
unresolved, orphaned, or schema-invalid programs; the generation prompt requests
data-only syntax. Generation allows an initial attempt plus two repairs, then
fails if still invalid. This strict final failure can differ from the original
TypeScript generation path's partially usable result retention.

Input is bounded to 512 KiB, nesting to 96, expansion to 20,000 nodes, and expanded
output to 8 MiB. Saved briefs retain their Lang source and parse to native trees
on read. Reactive syntax retention does not add reactive execution to Android.

Mermaid rendering uses native Rust layout for flowcharts, state, sequence, class,
ER, and XY charts. Input is limited to 8,000 UTF-16 units; graph/output bounds and
SVG allowlisting prevent active content, links, scripts, external resources, or
source-controlled configuration escaping the native image boundary. Unsupported
or invalid diagrams fail instead of producing a placeholder. Layout and SVG are
not byte-identical to Mermaid.js, and this is not every Mermaid grammar feature.

Syntax highlighting uses syntect/two-face grammars with the existing Pierre
light/dark assets, preserving the native line/token/theme response and source
text. It accepts up to 400 lines / 512 KiB; oversized individual lines, unknown
extensions, and grammar errors use plain-text tokens. Shiki's grammar scopes and
token boundaries are not identical. Both real rendering adapters compiled and
all 16 bounded presentation fixtures passed on the final removal revision `fac9ff30`. The historical partial
harness excluded these adapters and remains non-evidence for them; final-removal
revision verification is separate.

## Cache migration and rollback

Start with Rust's default **separate** cache. In an ordinary clone:

- Historical TypeScript server: `.git/pulls-review`
- Current Rust server: `.git/pulls-review-rust`

Both use the Git **common directory**, which may be outside the checkout for
linked worktrees. Resolve it with `git rev-parse --git-common-dir` from the actual
reviewed repository; `.git` need not be a directory.

To preserve existing local review history:

1. Stop TypeScript and all Rust servers using either data root. Wait for AI jobs
   if you need their results; restart does not resume active runs.
2. Make a complete backup of the stopped TypeScript cache. Keep it and the
   original unchanged.
3. Copy the cache into a **new** directory and pass it with
   `--data-dir /absolute/path/to/the-copy`. Never merge over an active Rust root.
4. Start one Rust server, initially with `--no-open --no-warm` and without
   `--preanalyze`. Inspect representative diffs, marks, chats, and analysis. Avoid
   live writes until the relevant workflows are checked.
5. Keep the original cache and a known pre-removal checkout/release. To roll
   back, stop Rust and start that older TypeScript server against its original
   cache. The current launcher cannot select a TypeScript backend.

The native store reads TypeScript's unstorage flat-key layout and joins legacy
`pr-meta:` / `pr-body:` records into a diff on read without deleting them. Rust
writes `diff:` records and synthesizes legacy metadata/body views for the web
client. No reverse migration automatically exposes new Rust review changes to
the old server. Do not copy the Rust root over the old one and assume round-trip
compatibility.

Rust locks each root and serializes mutations; TypeScript does not honor the
lock. Never run both against one live root, or multiple independent Rust writers.
Diff eviction is 50 entries / 50 MiB of recorded diff sizes; full-file content has
a separate persistent 500-entry / 25 MiB LRU. These are not whole-root quotas.
Full-file scope eviction follows review cache removal; bodies are reusable across
restarts but cannot bypass the byte/count budget through malformed metadata.

## Native launcher, installation, and rollback

The current source has no TypeScript server entry point. The npm package includes
`bin.mjs`, `dist/client`, and `dist/skills`; it does not bundle a Rust binary,
download one, or run Cargo at launch/install time. From this checkout:

```sh
cargo build --locked --manifest-path backend/Cargo.toml
pnpm -F pulls.review run build
node packages/cli/bin.mjs --cwd /absolute/path/to/repository --no-open --no-warm
# Equivalent combined build:
# pnpm run build:server
```

The package build first removes old `dist` output, then copies curated skills
and builds the existing local Vue SPA. `pnpm run build` remains a pnpm workspace
build; use `build:server` when you also need native compilation.

The launcher resolves an explicit `PULLS_REVIEW_SERVER_BIN` first. In this actual
source checkout it next checks `backend/target/debug` then `release`; otherwise
it searches absolute `PATH` entries. Empty overrides and missing executables
fail with setup instructions. It preserves arguments, working directory, stdio,
signals and exit status and supplies package-relative web/skills paths unless
explicitly overridden. A custom `CARGO_TARGET_DIR` needs an explicit binary path.
For an installed npm launcher, first install a native executable from a source
checkout with `cargo install --locked --path /path/to/pulls.review/backend` and
make Cargo's bin directory available on `PATH`.

The web adapter detects `/api/backend` and can speak the existing devframe data
transport. This is transport compatibility only; no client or launcher starts
another backend as a fallback.

For rollback or behavioral comparison, keep a separate checkout or release
pinned to a known revision from before TypeScript server removal and follow that
revision's build/run instructions. Keep its original cache unchanged. Stop the
current server before restarting the old one, use the old cache, and do not
expect newly saved Rust review changes to appear there. Never share the same
live cache or listener between old and new servers.

## Verification scope

Verification prioritizes compilation and bounded offline/read-only compatibility
checks, not exhaustive integration suites, provider bake-offs or benchmarks.
Each result below applies only to its stated source/check scope.

### Full native build and runtime evidence

- [Run 38912005160](https://github.com/OctavianTocan/pulls.review/actions/runs/38912005160)
  built the complete native backend, including real renderer dependencies, at
  `ef3483b95886c16c6023e66170dd5d105f4dc09f` using Rust/Cargo 1.99.0. This
  cleared the earlier dependency-resolution/full-build blocker and supplied the
  resolved checked-in lockfile.
- [Run 38912828958](https://github.com/OctavianTocan/pulls.review/actions/runs/38912828958)
  at `93beeb9e2080973476d7dbdc50d77b4e7590c134` passed a **locked full native
  build** (48 seconds), **16 actual native presentation tests** (1.27 seconds),
  and both **full-binary offline HTTP/auth/local-review and analytics smokes**.
  These checks exercised the actual rendering adapters, unlike the old harness.
- [Final cutover run 38917860671](https://github.com/OctavianTocan/pulls.review/actions/runs/38917860671)
  at `fac9ff30b532297145b890d91cde2ec2c3af6ca6` passed the **locked full native build**
  (1 minute 17 seconds), **43 targeted fixtures** (16 presentation, 11 lens,
  14 RPC and two numeric-cursor tests), and both **full-binary offline smokes**.
  The HTTP smoke includes declared RPC validation and no-write batch regressions.
  This revision includes the complete TypeScript backend removal.
- `.github/workflows/rust-backend-compile.yml` runs those bounded checks in one
  standard Ubuntu job with two Cargo workers and a 15-minute limit. It uses no
  secrets, deployments, matrix, benchmark, broad test suite or stored artifacts.
  Documentation-only updates do not rerun it. Green Node or Android checks alone
  do not establish native compilation.

### Local cutover checks

- All three retained TypeScript packages (app, core and Action) passed
  typechecking; the JavaScript launcher has syntax checks instead of a
  TypeScript server build. Source-wide package ESLint and contract formatting
  passed. Core and Action production builds and the real local SPA packaging
  build (3,034 modules) passed.
- All 20 Node launcher fixtures passed. An npm dry pack contained 229 files
  (about 2.4 MB), including six curated skills, with no old server bundles or
  native executable. Packaging results do not prove a clean install.
- A launcher-to-partial-harness smoke checked SPA serving and signal behavior.
  It validates launcher integration only; it is **not** additional full-binary
  native evidence.
- The native RPC schema adapter passed 14 isolated offline fixtures. It rejects
  invalid typed inputs before Git/storage side effects, applies declared
  defaults/transforms/unknown-field stripping, and validates declared return
  shapes, including reserved-key filtering matching Valibot. Numeric AI wait
  cursors retain JavaScript number ordering; two targeted fixtures accompany
  that correction; both passed in full native CI at `fac9ff30`. Existing semantic
  guards remain, including object-only requests and nonnegative integer
  `chatStartIndex` even though the shared declaration accepts numbers. The same
  RPC fixtures and expanded HTTP smoke also passed in the final full native run.
- Curated-lens parity passed 11 isolated tests and 276 bounded differential
  cases covering frontmatter, names, Unicode/truncation and confinement behavior.
  The 11 native lens fixtures also passed in the final full native run.
- A separate focused cutover review found no blocking issue in the launcher,
  packaging, deleted-code imports, contracts, Docker boundary, Android contract
  metadata or manifests. It excluded the then-in-progress RPC validator/docs
  and is not counted as one of the ten complete review rounds.
- Lockfile metadata for the removed CLI dependencies was updated without
  changing existing dependency snapshots/integrities. An offline pnpm lockfile
  regeneration could not finish because registry metadata for
  `@adobe/css-tools` was unavailable; YAML/importer consistency was checked,
  but no clean-install result is claimed.

### Historical partial evidence: review and simplification rounds

The temporary offline harness passed **202 bounded checks** (201 library and
one CLI), excluding actual Mermaid/highlighting adapters. It also passed three
bounded HTTP/auth/local-review, analytics, and Devframe discovery/auth/RPC
smokes. The checks included the pinned JavaScript Devframe error-codec fixture
and 107 OpenUI golden cases inside one test. All four then-existing package
typechecks, source-wide lint, contracts, Rust formatting and diff whitespace
checks passed. Clippy was unavailable in that environment.

The continuation review corrected 15 findings. Ten subsequent complete review
rounds corrected 34 additional validated findings, followed by five complete
simplification rounds with 23 behavior-preserving improvements. See the
[per-round record](RUST_REWRITE_REVIEW.md). The dependency blocker recorded in
those rounds was real at that time; it is no longer the global build status.
These partial results are retained as historical evidence and are not relabeled
as full-backend tests.

### Historical baseline: `84f0f481` only

The earlier migration baseline passed `cargo check`, debug builds, 53 offline
Rust tests, both bounded HTTP smoke scripts, and the existing Vue typecheck.
HTTP checks covered auth/Host/Origin boundaries, native storage rejection, local
diff/grouping/review/cache behavior, unchanged Git index, and analytics
sanitization/persistence/retention/opt-out. They apply only to that baseline.

### Remaining verification boundary

Rerun these against the final removal revision in a supported build environment:

```sh
cargo build --locked --manifest-path backend/Cargo.toml
cargo test --locked --manifest-path backend/Cargo.toml --lib presentation:: -- --test-threads=2
python3 backend/scripts/smoke.py
python3 backend/scripts/analytics_smoke.py
pnpm -F pulls.review run test
pnpm -F pulls.review run typecheck
```

The smoke fixtures use temporary repositories/data roots and strip provider
credentials; they do not call paid providers or write to GitHub. Record the
revision and actual outcomes before treating final cutover as verified.

No live AI provider/catalog, Docker/shared-login/Linux-helper execution,
TypeSafe-service interaction, GitHub mutation, Android device/UI exercise,
production deployment or proxy-security validation is claimed. Validate any
required live workflow deliberately in an appropriate environment. Native
Mermaid/Shiki-replacement output is not byte-identical to upstream JavaScript
libraries. No performance benchmark or complete end-to-end parity claim is made.
Start on localhost with a separate cache.

## Published native CI verification, 16 October 2026

[Bounded native CI](https://github.com/OctavianTocan/pulls.review/actions/runs/38954719782)
passed on `d9615aae6b4bdbf53133e3c903c7f74906c17ea7` in 2 minutes 8 seconds.
It compiled the complete locked native backend and passed presentation, lens,
RPC, native-source and synthetic Docker-cancellation fixtures, followed by all
three offline HTTP smoke scripts. This was one Ubuntu runner with two Cargo
workers and a 15-minute job limit. No live provider, real Docker or device
verification is inferred. The subsequent publication commit changes only
documentation; the tested runtime source is unchanged.

## Final operational setup review, 16 October 2026

This additional setup review compared the native implementation with main
`c666dbcaa734081c3b26f4fb3e259ad8ab760a9b`; it is separate from the previously
completed review and simplification rounds. Three independent read-only inspections
covered installation/packaging and startup, Docker/provider/GitHub/storage lifecycle,
and hosted accounts/Google/posted/browser/mobile contracts.

Validated fixes:

- **P2 startup:** `--port 0` now registers the actual bound port for browser origins.
  IPv6 startup URLs/Host allowlists use bracketed literals, and HTTP port 80 origins
  omit the default port as browsers do.
- **P2 observability:** failed native/account RPC responses again record
  `error.server`, including calls that bypass ordinary dispatch. Events use the
  existing privacy and opt-out filter; response bodies and RPC timing stay intact.
- **P3 UI parity:** with web assets configured, posted links reach the retained
  localized Vue phone-only landing screen. Assetless servers retain the plain-text
  explanation. Neither path reads posted content.
- **P3 setup documentation:** account sessions and agent keys are distinguished from
  general phone RPCs that still need network/proxy authentication.

Bounded verification for these changes: locked offline native build, one focused
origin-serialization unit fixture, all three offline HTTP smoke scripts (including
new ephemeral-port, posted-page and failed-RPC telemetry regressions), 20 launcher
fixtures, launcher syntax, contract format, Rust format and diff whitespace checks.
The build retains the existing unused `DiffSnapshot.size_bytes` warning.

The npm package still needs a separately built/installed native executable. CLI
providers intentionally require Docker; a helper must match the image's architecture
and runtime libraries (non-Linux hosts supply a Linux helper explicitly). Hosted
operators need persistent storage, their public `--allow-origin`, authenticated TLS
proxy protection, and an explicit HTTPS `PULLY_PUBLIC_URL` behind TLS termination.
Rollback requires the separately preserved original cache and server checkout.

No new setup blocker was confirmed in the remaining inspected source. This is not
production runtime parity: no clean npm install/release, live provider or Google
sign-in, real Docker/helper/shared-login execution, GitHub writes, Android device,
or deployed proxy was exercised. Native CI's approved branch-only scope is unchanged.
