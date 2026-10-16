# Rust backend rewrite review

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

## Current status

Ten complete sequential review rounds and five complete simplification rounds
are finished: 34 validated review findings fixed and 23 simplifications applied,
in addition to the earlier reviews below. The TypeScript server and Node-only
local Git provider have now been removed; `packages/cli` packages the existing
web UI/curated lenses and launches a separately supplied native executable.
The browser-used TypeScript core, Vue UI, Android UI and separate Action remain.

The earlier dependency blocker is cleared. A [locked full native CI run](https://github.com/OctavianTocan/pulls.review/actions/runs/38917860671)
at `fac9ff30b532297145b890d91cde2ec2c3af6ca6` passed a complete build,
43 targeted native fixtures (16 presentation, 11 lens, 14 RPC and two cursor
tests), and two full-binary offline HTTP/auth/review and analytics smokes on the
complete removal revision. Local cutover builds, typechecks, lint/contracts, packaging inspection and
20 launcher fixtures have passed; the launcher integration smoke used the
partial harness and is not a full-native runtime result.

Historical per-round results below remain limited to their named baseline or
partial harness. Their HTTP 403/build limitations describe the environment at
that time, not today's overall build status. Source reviews and bounded checks
do not prove live provider, Docker/login, TypeSafe, GitHub-write or Android-device
parity. See the [current verification ledger](RUST_BACKEND_MIGRATION.md#verification-scope).

## Historical baseline review: 15 October 2026

Scope: the Rust rewrite and its local-client transport adapter, compared with the reconciled review-fixes branch. This review does not re-review the inherited fixes in PR #48.

All retained findings were checked against corrected source at `7501cb47`. That baseline review found no remaining actionable issues within its scope; it does not cover the subsequent parity changes.

### Corrections

The review identified 20 distinct issues: four high-priority and sixteen medium-priority. Corrections cover:

- CLI usage records no longer persist a null model that violates client schemas.
- The documented web root and SPA routes serve the supplied frontend assets.
- Shutdown cancels jobs, drains worker and container cleanup tasks, then flushes analytics.
- Local source reads exclude canonical Git administration, review storage and analytics directories, including symlink aliases and separate Git directories.
- AI analytics retain the actual engine/model, and CLI follow-up suggestions receive explicit scoped context.
- Every brief-generation repair is strictly validated before saving.
- Analytics waits for append completion, preserves retention after opt-out, and has a real offline HTTP persistence fixture.
- Startup Git discovery uses the same bounded process helper as other Git operations.
- Snapshot replacement compares the complete expected diff, including same-head base changes.
- Cached PR opens update recency; untracked binary identities include their bytes.
- Unavailable repository-info fields are omitted rather than sent as null.
- Raw storage keys are normalized before protected-collection checks.
- Browser and native GitHub requests share credentials, bounded transport and telemetry.
- Background warming probes upstream base/head identity before fetching full diffs and skips closed cached PRs.
- Optional GraphQL enrichment cannot discard a completed REST inbox; inbox refreshes are serialized.

The local-client adapter also now declares the exact trust/event capabilities it uses, without asserting that it implements the entire devframe client.

### Verification and limits

Compilation, 53 bounded Rust unit fixtures, two offline HTTP smoke scripts, Vue typechecking, targeted lint and contract-format checks passed. The smoke fixtures use temporary repositories/storage and credential-free child environments; they cover HTTP/auth/static routing, local source and review data, Git-directory aliases, analytics sanitization/persistence, limits and opt-out retention.

No live AI calls, GitHub writes, Docker/provider execution, Android device exercise, deployment or performance benchmark was used to establish these results. The source review of shutdown and sandbox lifecycle is not an end-to-end Docker execution claim.

This baseline was an opt-in backend migration, not a complete feature-parity
guarantee. See the [current migration coverage](RUST_BACKEND_MIGRATION.md) for
subsequent implementation, verification and rollback changes.

## Parity continuation review: 16 October 2026

The continuation was reviewed against baseline `84f0f481` using correctness,
project standards, test quality, maintainability, agent-native behavior, security,
performance, API contracts, reliability and adversarial perspectives. Fifteen
distinct findings were validated. Corrections address:

- Expanded structured-clone byte budgets before allocation and interoperable
  Devframe error records, with auth expiry/throttle/revocation fixtures.
- Pi reasoning metadata replay across tool turns and a single session deadline.
- Deduplicated, bounded source selections and CI-first, patch-free inbox context.
- Cache warming admission and rejected-snapshot suppression, analysis residency
  pins, captured-head validation and head-aware job deduplication.
- Shared repository refresh results, including failures, without queued retries.
- Rejection of wholly failed tours while retaining useful partial results.
- Private parser/grouping/inbox module extraction to keep implementation files
  within the repository's size limit without changing public contracts.

### Verification status

The corrected source passed 202 bounded offline checks (201 library, one CLI)
in a partial harness, including a pinned Devframe JavaScript codec fixture.
That harness also passed HTTP/auth/local-review, analytics and Devframe smokes.
All four then-existing package typechecks, source-wide lint, Rust formatting,
contract formatting and diff whitespace checks passed. At this stage, full
compilation and dependency resolution were blocked by HTTP 403 responses from
the environment's proxy for newly required rendering crates. The partial
harness excluded Mermaid/highlighting and could not establish a complete build.
The later full native CI results at the top of this document cleared that
build blocker; they do not change the scope of these historical results.

No live AI, TypeSafe, GitHub mutation, Docker execution, Android device test,
merge or deployment is claimed. See [migration verification scope](RUST_BACKEND_MIGRATION.md#verification-scope)
for the current evidence and remaining validation boundary.

## Repeated review request

The requested sequence is ten additional complete code-review rounds, with
validated findings fixed after each round, then five simplification rounds.
Reviewer perspectives are not counted as separate rounds.

### Round 1: complete

Ten independently validated findings were fixed: brief retained-key/AST allocation
budgets, namespaced streamed activity IDs, a non-vacuous reasoning-privacy fixture,
grouping normalization before schema checks, canonical full-file eviction scopes,
reopened-PR refreshes, per-engine model discovery coordination, bounded batched
local search, stable inbox base/head identities, and mutation-versioned cache
metadata. Private tests were split out to preserve module-size limits.

The partial harness passed 135 library tests (including pinned Devframe codec
interop), one CLI test, and three bounded loopback smoke scripts. Contract and
Rust formatting checks passed. Mermaid/highlighting remain excluded; the full
build blocker is unchanged.

### Round 2: complete

Five validated findings were fixed: failed-tour completion events, plaintext
fallback after syntax-parser state loss, unnecessary grouping diff clones,
persisted analyzed-head identity, and blocking full-file-cache transactions.
The partial harness passed 141 library tests, one CLI test and three loopback
smokes. Formatting and contract checks passed. The real syntax-highlighting
regression remains unexecuted because its dependency is unavailable.

### Round 3: complete

Five validated findings were fixed: saved API-key exclusion while preserving
shared OAuth login and environment keys, complete sanitized terminal Codex
activity text, aggregate search budgets across binary files, asynchronous
GitHub credential lookup, and live identity probes for reopened cached PRs.
A malformed test fixture found during verification was repaired before the
full bounded suite was rerun. The partial harness passed 146 library tests,
one CLI test and three loopback smokes; formatting and contracts passed.

### Round 4: complete

One validated defect was fixed: repeated Jev cache reads and hashing every
25 milliseconds while waiting for classifications. Waiting now stays in memory
and hydration happens once. An unsupported test-only candidate was rejected.
The partial harness passed 147 library tests, one CLI test and three smokes;
formatting and contracts passed.

### Round 5: complete

The full review and independent validation found no new actionable defect.
A replay-coverage candidate was rejected after tracing the production path.
No artificial source change was made. All 148 partial tests and formatting /
contract checks passed again; the unchanged round 4 smoke results still apply.

### Round 6: complete

Three validated defects were fixed: preserve unknown analyzed-head provenance
for legacy shared imports, include PR descriptions in live bridged discussion
results, and move remaining TypeSafe storage transactions off async workers.
Private discussion and Jev test modules preserve repository size limits.
The partial harness passed 154 library tests, one CLI test and three smokes;
formatting and contracts passed.

### Round 7: complete

Two validated defects were fixed: retain undefined optional-field semantics
across the Devframe codec and move overview cache transactions off async workers.
The Devframe regression uses the pinned JavaScript codec and real client cache
schemas. A hypothetical future OpenUI fixture-drift claim was rejected.
The partial harness passed 160 library tests, one CLI test and three smokes;
formatting and contracts passed.

### Round 8: complete

Six validated findings were fixed: Codex strict-schema parity, bounded diff-search
rendering, asynchronous diff-pin cleanup, nullable repository labels, legacy
pagination continuations, and the documented discussion-output limit. A new
admission regression exposed an oversized nested AI future; boxing that future
fixed the stack overflow without increasing stack limits. The unchanged
regression and complete bounded suite then passed: 175 library tests, one CLI
test, three smokes, formatting and contracts. Live Codex behavior is unverified.

### Round 9: complete

One validated streaming-performance defect was fixed: preview updates now use a
bounded window rather than repeatedly processing the entire accumulated answer.
Final answers and replay fields remain complete. Secret redaction across chunk
and clipping boundaries received an additional synthetic security audit.
The partial harness passed 183 library tests, one CLI test and three smokes;
formatting and contracts passed.

### Round 10: complete

One validated defect was fixed: preanalysis eligibility cache reads now run off
async workers. A held-lock regression covers responsiveness. The final partial
harness passed 184 library tests, one CLI test and three smokes, with formatting
and contracts passing.

All ten requested complete sequential review rounds are finished, with all 34
validated findings corrected and five unsupported candidates rejected. The requested
subsequent simplification sequence is recorded below. At the end of those rounds,
production compilation and real-renderer verification were still blocked; later
CI results cleared the build gate. These rounds are not a parity completion claim.

## Native cutover after the review sequence

The requested backend removal is implemented in the same PR: 65 original CLI
source/test files and eight Node-only core local-provider files are deleted,
with the old CLI TypeScript configs/dependencies and core `/local` export.
The 35 original server contract IDs are retained under `backend/CONTRACTS`, and
Android contract labeling recognizes the native backend. Historical plans remain
marked as history rather than instructions for the current server.

The `pulls.review` package has a dependency-free JavaScript launcher, Node fixture
tests, and packaging scripts for the existing SPA and six curated skills. It
requires `PULLS_REVIEW_SERVER_BIN`, a checkout build, or a server on absolute
`PATH`; it never builds/downloads a binary or starts TypeScript. Old `dist`
bundles are removed during packaging. Root `build:server` combines the locked
Cargo build and launcher-assets build. Rollback requires a pinned pre-removal
checkout/release and the untouched original cache, not the current launcher.

After the ten reviews/five simplifications, two additional parity corrections
were made during cutover. These are separate from the 34 findings and 23
simplifications:

- Native lens metadata/instructions preserve frontmatter titles/descriptions,
  dotted folder names, and bounded UTF-16 truncation with canonical curated-path
  confinement. Eleven isolated tests and 276 differential cases passed.
- Native declared RPC argument/result schemas reject invalid typed inputs before
  Git/storage side effects, apply defaults/transforms/field stripping, and
  validate declared return shapes. Fourteen isolated offline fixtures passed.
  Existing nonnegative integer `chatStartIndex` guards remain even though the
  shared schema permits numbers; valid clients supply indices.

The complete removal revision passed the locked native build and targeted
runtime checks at `fac9ff30`, as recorded above.

The final local cutover checks passed all three retained TypeScript package
typechecks, source-wide package lint, contract formatting, actual core/Action
builds, local SPA packaging (3,034 modules), and 20 launcher fixtures. An npm dry
pack found 229 files/about 2.4 MB with six skills and no old server/native binary.
A launcher SPA/signal smoke used the partial harness; it is explicitly excluded
from full-native evidence. No clean install or live provider/Docker/GitHub/device
result is claimed. See the migration ledger for the remaining pnpm offline
metadata limit and exact native CI revisions.

A separate focused cutover review found no blocking findings in launcher and
packaging behavior, deleted-code imports, contracts, Docker integration, Android
contract metadata and manifests. It excluded the then-in-progress RPC validator
and documentation. This bounded review is additional evidence, not another
count toward the ten complete code-review rounds.

The focused RPC follow-up review found reserved object keys that Valibot strips;
`__proto__`, `constructor` and `prototype` are now filtered at the same loose-object
and record boundaries. Numeric AI wait cursors preserve fractional/negative number
ordering instead of silently coercing to zero; two targeted cursor fixtures were
added. Both pure and async fixtures passed in the full native CI run at `fac9ff30`.
Object-only requests and nonnegative integer chat indices remain semantic guards.

## Simplification rounds

Each run uses the complete reuse, quality and efficiency reviews, then applies
only changes shown to preserve behavior. Safety checks and client contracts
remain in place. Counts classify each applied change once.

### Round 1: complete

Seven changes applied (reuse 1, quality 2, efficiency 4); two skipped.
Improvements include shared null-field cleanup, simpler completion-state storage,
reduced cloning/regex work, and cohesive private GitHub modules. Checks passed:
189 library tests, one CLI test, three smokes, all four package typechecks,
source-wide lint, contracts, Rust formatting and diff whitespace.

### Round 2: complete

Five changes applied (reuse 2, quality 0, efficiency 3); three skipped.
Shared timestamp helpers matched the previous implementations on 267 bounded
comparison cases. Polling reads, borrowed diff traversal and incremental SSE
newline scanning reduce unnecessary work without changing output or order.
Checks passed: 193 library tests, one CLI test, three smokes, all four package
typechecks, source-wide lint, contracts, Rust formatting and diff whitespace.

### Round 3: complete

Six changes applied (reuse 2, quality 1, efficiency 3); six skipped.
Improvements include shared MCP/PR-state formatting, a simpler shared-comment
return type, bounded Jev task input, incremental CLI line scanning and fixed-regex
caches. Checks passed: 197 library tests, one CLI test, three smokes, all four
package typechecks, source-wide lint, contracts, Rust formatting and diff whitespace.

### Round 4: complete

Four changes applied (reuse 0, quality 1, efficiency 3); twelve reuse candidates
were rejected. Removed an unused embedded-script import and avoided copying
complete histories/diffs where immutable borrows preserve behavior. Checks
passed: 200 library tests, one CLI test, three smokes, all four package typechecks,
source-wide lint, contracts, Rust formatting and diff whitespace.

### Round 5: complete

One quality change applied: share the identical secret-name constant while
preserving the distinct redaction algorithms. Reuse and efficiency found no
warranted further changes. Checks passed: 201 library tests, one CLI test,
three smokes, all four package typechecks, source-wide lint, contracts, Rust
formatting and diff whitespace.

All five full simplification rounds are complete, with 23 applied improvements.
Those tests used the partial harness excluding Mermaid/highlighting. At the
end of the simplification sequence, missing rendering dependencies still blocked
full production compilation and Clippy was not installed. Neither was counted
as a pass. The later locked native build/presentation/smoke results are recorded
separately above; no live integration result is inferred from them.
