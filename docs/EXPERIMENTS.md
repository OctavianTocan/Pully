# Experimental features

The mobile app has an Experimental section directly in Settings, using its existing
grouped rows and switches. Both Merge risk and Browser floaty start off,
including on upgrade, and require an explicit local choice. Experiment preferences
are separate from ordinary settings and never sync from another device or account.
The mobile UI and exported assessments label experimental output as Experimental.
The desktop app has no experimental settings or assessment UI; shared review code
keeps the optional extension boundary for mobile requests (Tavi's 21 Oct 2026
mobile-only correction).

## Boundaries

| Responsibility | Implementation |
| --- | --- |
| Native opt-ins | `mobile/shared-ui/src/commonMain/kotlin/com/octaviantocan/pulls/settings/ExperimentalFeatures.kt` |
| Android settings | `android/app/src/main/kotlin/com/octaviantocan/pulls/ui/settings/ExperimentalSettings.kt` |
| Review extension registration in core | `packages/core/src/analyze/adapters/llm/review-experiments.ts` |
| Review extension registration on the server | `backend/src/review_experiments.rs` |
| Merge-risk implementation | `merge-risk.ts`, `types/merge-risk*`, `backend/src/merge_risk.rs` |
| Optional mobile review UI | `ExperimentalMergeRisk.kt` |
| Browser runtime gate | `BrowserHelperSettings.allowed` |

Review requests opt in with `experiments: { mergeRisk: true }`. Omission and false
keep the original review prompt, schema and result. The core and Rust host call
the extension boundary; rubric instructions, schemas and calculation stay outside
ordinary review. Experimental runs use a distinct cache variant, and the server
also separates task deduplication by opt-in. Enabling a setting never starts paid
work or regenerates an existing review.

Disabling merge-risk hides kept assessments and excludes them from generated review
posting text. An already-started opted-in job may finish and be saved, but cannot
make its assessment visible while the setting is off. Ordinary findings remain
usable. Old data never acts as consent.

Browser floaty additionally requires the existing accessibility disclosure and
Android grant. The Browser floaty row leads to that disclosure; a settings switch
never grants accessibility permission. Old disclosure consent and an existing
Android grant cannot enable the new experiment flag. Disabling withdraws consent
and asks the live service to remove its overlay immediately. All address reads and
tap-time checks use the same gate.

## Usage analytics

The existing first-party tracker records `experiment.toggle` with `experiment` and
`enabled`, and `experiment.use` with `experiment` and `action`. The only IDs are
`merge-risk` and `browser-helper`; the only use actions are `review`, `view`, and
`open`. Review counts a requested experimental review, view counts an assessment
display, and browser open counts a successful explicit tap. Cached review reads
do not count as another review request.

Usage stats off suppresses these events through the existing tracker and queue.
No separate analytics service is introduced. No assessment, verdict, score, finding,
URL, repository, browser observation or permission state is attached to experiment events. The
accessibility service never reports observations or impressions.

## Removing an experiment

For merge-risk, remove its registration from the two review-extension modules,
then remove its setting and optional renderer. The normal review schema and prompt
remain intact. The optional saved-wire field may remain readable for historical
data, or be removed with a cache migration; it must never become a required field.
Remove the rubric, scorer and fixtures only once those optional readers no longer
import them. The existing baseline and opt-in tests define that boundary.

For Browser floaty, remove its experimental row, flag, browser module, accessibility
service manifest declaration and accessibility resources. Remove its disable callback
from the Android `AppSettings` adapter as well. No review or provider path depends on it.

Keep the event names for existing day-file history even after deleting a feature.
Feature removal must not change the ordinary review cache or analytics opt-out.
