# Pully deployment

`pulls-review-update.sh` runs from the host's existing two-minute systemd timer. Install
the reviewed script at `~/bin/pulls-review-update.sh` and set
`PULLS_REVIEW_PUBLIC_URL=https://prs.octaviantocan.com` in the unit's environment. It uses
the existing `~/apps/pulls-review` checkout, `~/.local/state/pulls-review-update` state and
`~/.local/share/pulls-review/android` update directory. The `PULLS_REVIEW_REPO`,
`PULLS_REVIEW_UPDATE_STATE` and `PULLS_REVIEW_ANDROID_DIR` variables override those paths.

The updater requires clean tracked files and a fast-forward to `origin/release`. It admits
only the exact current remote release commit with successful genuine release checks,
and repeats admission after the build before requesting idle shutdown. It preserves
local work by refusing dirty checkouts. Before recovering one, pause the update timer, save
the patch and affected files outside the checkout, then restore only the reviewed edits.
Never bump release metadata directly in the deployment checkout. Keep `.env`, credentials,
runtime data and untracked operator files in place.

Automatic server updates preserve active AI work. Configure both the server service and
the timer with `PULLS_REVIEW_UPDATE_TOKEN_FILE=%h/.local/state/pulls-review-update/token`
(systemd expands `%h`; in a shell use the absolute home path). The backend writes and
rotates this private, mode-0600 token when it starts. The timer reads it directly and
requests an atomic idle shutdown at `http://127.0.0.1:7390/api/update/restart`; set
`PULLS_REVIEW_PORT` or a loopback-only `PULLS_REVIEW_UPDATE_URL` for another local port.
The token is separate from phone/browser credentials and never belongs in Git or logs.

Busy AI work, pending admissions, unavailable idle support and authentication failures
defer deployment until the next timer run. Once idle, the backend fences new jobs before
graceful shutdown. The updater waits for the old systemd invocation to finish, then uses
`systemctl --user start`, which leaves any supervisor-started replacement running.
It does not restart that replacement or cancel its newly accepted work.

For the initial upgrade from a backend without this endpoint, pause the timer, configure
the token-file environment on both units, wait for all active AI work to finish, then do
one controlled service restart with the reviewed new binary. Verify that the token file
exists and resume the timer. The new updater deliberately cannot automate this first
transition safely: a missing endpoint never permits a blind restart.

Server build failures leave the previous service running and record the failed commit.
After investigating and correcting a host/toolchain problem, archive the `failed` marker
to retry that commit; a newer release commit is retried automatically. APK publish failures
fail the unit and retry on the next invocation even when the server is already current.
The timer must be resumed after maintenance.

Main receives fast policy, lint/contracts, types and design checks; a feature does not
need its own Android version. Promotion to `release` compares APK inputs against the
actual release base and requires a higher `versionCode` and changed current notes.
Complete Android/native and the existing backend fixture/smoke jobs run on every
release PR/push, without path exclusions. The retained `android-release` name is a fast
integration compatibility context, not full release certification. See
[the release policy and installed-copy migration](../docs/RELEASE_TRACK.md). The initial
release branch is not yet protected; no host/timer migration is claimed.

`versionName` may stay the same when completing an unshipped release; its newest changelog
section must describe the new build. Existing design checks also require release summaries,
bullets and pictures. The publisher refuses local tracked edits, downgrades and new bytes
under an existing code, and records `sourceCommit` in `latest.json`.
It holds `.publish.lock` across build, version validation and publication; daemon and
historical-build children do not inherit that lock. Historical index writers use a
separate short `.index.lock` and unique temporary files before atomic replacement.

Run the release and deployment tests with:

```sh
node scripts/release-track.test.mjs
node scripts/release-policy.test.mjs
node android/scripts/release.test.mjs
node android/scripts/publish.test.mjs
node scripts/pulls-review-update.test.mjs
node android/scripts/check-release.mjs --base origin/release
pnpm run contracts:check
pnpm run android:design
```

After publishing, download `/android/latest.json` and `/android/pulls.apk` from the running
server, compare SHA-256 with the manifest, inspect the APK version and pinned signing
certificate, and confirm the checkout is clean and the timer's next run succeeds. This
verifies the server's update channel; installation on a phone is a separate check.
