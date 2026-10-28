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
design must pass. Mac verification remains on GitHub-hosted macOS and is optional
for merging and publishing (Octavian, 28 Oct 2026).
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
same admission. No tag or deployment is triggered by merging to main.
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

Linux runner operations are documented in [CI_RUNNERS.md](./CI_RUNNERS.md).
