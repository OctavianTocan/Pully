# Integration and release

`main` accepts reviewed work after fast policy, lint/contracts, web type and Android
design checks. Features do not need their own Android version, release notes or
illustration. Every ready PR still receives Compound simplification and code review,
and affected tests. The required `android-release` name is retained for the existing
branch rule: it now reports fast integration and publication safeguards, not a build.

The `release` branch is the source for server deployment, APKs, historical APKs and
package tags. Its initial commit is `3540fa0aea002689d2b09677d7f1b02b16833363`, the
prior stable baseline. Creating that branch does not deploy anything or certify a
new release. The baseline is not exempt from the new publication checks.

Promote a reviewed main commit through a PR into `release`. Add one coherent Android
version higher than the actual release base, current notes and a truthful illustration
when APK inputs changed; preserve earlier notes and the signing key. Full Android
tests/APK identity and signature, backend tests/smokes, lint/contracts, types and
design must pass. The Mac account/Keychain/Darwin/Native/UIKit/resource checks run
on GitHub-hosted macOS but are optional for merge and publication (Octavian, 28 Oct 2026).
There are no path exclusions. Manual full-suite runs help diagnose candidates but
cannot replace the release-push version comparison. The duplicate main Mac diagnostic
has been removed.

After promotion, the exact release push must finish successfully before any consumer
can publish or restart. `scripts/release-track.mjs` checks a clean release or detached
checkout against a fresh remote `release` ref and the genuine GitHub Actions checks
listed in [the policy](../.github/release-policy.json). A missing ref, different origin,
newer failed/incomplete run, skipped check or unavailable evidence refuses publication.
The version comparison must come from this repository's successful release-push CI
run. Historical builds use only that admitted release's first-parent history and
exclude unshipped cached artifacts without deleting them. Tag publication needs the
same admission. No tag or stable deployment is triggered by merging to main.

## Staging

Staging is the one thing main deploys (Tavi, 24 Oct 2026). After every merge,
`.github/workflows/staging.yml` deploys the Cloudflare server (`packages/worker`) to the
`PULLY_STAGING_HOST` domain and publishes Pully Staging, a separate app
(`com.octaviantocan.pulls.staging`) that installs beside Pully, starts on that server and
updates from its `/download/staging/` channel. It never touches the stable server, the stable
APK or `latest.json` (android/CONTRACTS, `android-staging-channel`). Until the operator sets
`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` and `PULLY_STAGING_HOST`, each run skips. A
stable server that sets `PULLS_REVIEW_STAGING_URL` links to the staging download page.

The staging app uses one GitHub login for the full Inbox/Tours, Profile, Settings and
review workspace (Tavi, 25 Oct 2026). Its confirmed personal session authorizes the
account-owned `/workspace/api/rpc` and `/workspace/api/events`; it does not create a
second legacy Pully session or adopt the operator's data. Claude Code and Codex setup
connect each person's own provider account in Settings, AI.

External agents posting diffs with Agent keys remain available through the existing
server connection. The GitHub workspace explains that limitation in Settings and
refuses key creation; its phone RPC does not expose a new key-authenticated agent API.

CI also needs `PULLY_STAGING_ACCESS_CLIENT_ID` and
`PULLY_STAGING_ACCESS_CLIENT_SECRET`. These are a dedicated Access service token for
the staging workspace and `/download/staging/*` paths, supplied to the Android build
only through its environment. Tavi explicitly accepts that the staging APK contains
this credential so the phone needs no Access browser login. It grants no workspace
account authority and has no policy for production or legacy RPC. Never use the
Cloudflare API token or a production/shared Access credential here. Missing build
credentials fail publication; stable APKs contain empty fields. Staging history uses
`/download/staging/past/` and reports no archived versions until any are published.

Historical publication refuses shallow or unavailable version history before rewriting
the existing index; fetch complete release history first. Its immutable source remains
available until the background history worker finishes.

Desired protection is four required main checks (`android-release`, `lint`,
`typecheck`, `android-design`) and all six `requiredReleaseChecks` on `release`,
with force pushes and deletions disabled. The live main branch currently requires
only the retained `android-release` context. The initial release branch is currently
unprotected: the available integration can read ordinary branch metadata, but its
administrative protection endpoint returns 403. Workflow/source enforcement and
branch protection are separate; do not describe the desired settings as installed.

Linux CI runner operations are documented in [CI_RUNNERS.md](./CI_RUNNERS.md).

## Existing deployment migration

The documented deployment installs a **copy** of `scripts/pulls-review-update.sh` in
`~/bin`, run by a timer. Editing this repository does not replace that copy, switch
its working checkout or change the timer. No live host was inspected or migrated
for this policy change.

The captured updater from stable `3540fa0` tracks main, requires
`PULLS_REVIEW_PUBLIC_URL`, and runs the newly checked-out `contracts:check` before
Cargo or a restart. That command now runs a deployment-only release guard. A local
regression executes the captured script against unverified main and confirms that
the prior server, deployed marker and served APK remain. The old updater can record
its ordinary failed-revision marker. This proves that captured revision's fence;
the actual installed host revision is unknown.

An operator must inspect the installed script/timer, preserve deployment-local work,
install the reviewed updater copy and move its deployment checkout onto `release`.
The new updater refuses another branch and uses fresh release admission before a
new build or publication. Run the existing idle-safe update process only after the
release push is green. This repository change performs none of those host actions.
