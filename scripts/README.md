# Pully deployment

`pulls-review-update.sh` runs from the host's existing two-minute systemd timer. Install
the reviewed script at `~/bin/pulls-review-update.sh` and set
`PULLS_REVIEW_PUBLIC_URL=https://prs.octaviantocan.com` in the unit's environment. It uses
the existing `~/apps/pulls-review` checkout, `~/.local/state/pulls-review-update` state and
`~/.local/share/pulls-review/android` update directory. The `PULLS_REVIEW_REPO`,
`PULLS_REVIEW_UPDATE_STATE` and `PULLS_REVIEW_ANDROID_DIR` variables override those paths.

The updater requires clean tracked files and a fast-forward to `origin/main`. It preserves
local work by refusing dirty checkouts. Before recovering one, pause the update timer, save
the patch and affected files outside the checkout, then restore only the reviewed edits.
Never bump release metadata directly in the deployment checkout. Keep `.env`, credentials,
runtime data and untracked operator files in place.

Server build failures leave the previous service running and record the failed commit.
After investigating and correcting a host/toolchain problem, archive the `failed` marker
to retry that commit; a newer main commit is retried automatically. APK publish failures
fail the unit and retry on the next invocation even when the server is already current.
The timer must be resumed after maintenance.

Android source, resources, build inputs, changelogs and every bundled `CONTRACTS` file
require a higher `versionCode` and changed current-release notes in the same pull request.
The always-running `CI / android-release` job checks against the PR base (or previous main
commit on push). Require the `android-release` status check on main with up-to-date branches,
so two parallel releases cannot merge with the same version code.

`versionName` may stay the same when completing an unshipped release; its newest changelog
section must describe the new build. Existing design checks also require release summaries,
bullets and pictures. The publisher refuses local tracked edits, downgrades and new bytes
under an existing code, and records `sourceCommit` in `latest.json`.

Run the release and deployment tests with:

```sh
node --test android/scripts/release.test.mjs android/scripts/publish.test.mjs scripts/pulls-review-update.test.mjs
node android/scripts/check-release.mjs --base origin/main
pnpm run contracts:check
pnpm run android:design
```

After publishing, download `/android/latest.json` and `/android/pulls.apk` from the running
server, compare SHA-256 with the manifest, inspect the APK version and pinned signing
certificate, and confirm the checkout is clean and the timer's next run succeeds. This
verifies the server's update channel; installation on a phone is a separate check.
