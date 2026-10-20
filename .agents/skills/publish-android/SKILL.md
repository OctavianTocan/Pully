---
name: publish-android
description: Build the Android app and publish it so the installed phone app offers the update; also how to test a build on a phone. Use when asked to ship, release, publish, install or test the Android app, or how an Android update reaches a device.
---

# Publish and test the Android app

Android pull requests are compiled, JVM-tested and assembled by `android-verify.yml`; `ci.yml`
also checks the Android design. These checks do not publish an installed-app update. `release.yml`
only runs for `v*` tags and publishes the packages. Merging to `main` changes no installed app.
A person (or an agent with the toolchain and the server's output directory) has to run the steps below.

## Building in a cloud sandbox

A cloud sandbox can compile and unit-test the app (checked 14 Oct 2026: `dl.google.com`,
`maven.google.com` and Maven Central all answer through the egress proxy). What it cannot do is run the app,
so say "compiled and unit-tested", never "tried on a phone", unless a device was used.

1. Install a JDK 21 (the project toolchain), then the SDK: download the Linux command-line tools zip from
   `https://dl.google.com/android/repository/`, unpack it to `$ANDROID_HOME/cmdline-tools/latest`, run
   `sdkmanager --licenses`, then `sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"`.
   Gradle installs the build-tools version AGP asks for by itself.
2. `export ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK_ROOT=$ANDROID_HOME`, then from `android/` run
   `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`.
3. The first build downloads a few hundred artifacts and Maven Central often answers `429 Too Many
   Requests` through the proxy. Gradle keeps everything it did download, so run the same command again until
   it passes (a loop with a 45 second pause works). A real compile error shows as `e: file:///...`.
4. Run it in the background and read the log; a foreground `sleep` is blocked in the sandbox.

## Publish an update

Needs JDK 21 (the script uses `mise where java` unless `JAVA_HOME` is set), the Android SDK (`ANDROID_HOME`, default
`~/Android/Sdk`) and the server running with access to the output directory.

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`. The app offers an update only
   when the published `versionCode` is higher than the installed one. Add the version's section at the top of
   `android/CHANGELOG.md`: one summary sentence, then a bullet per change, in plain words for the person using
   the app (`pnpm run android:design` fails without it). The app shows it after the update.
2. Merge to `main`, check it out on the build machine.
3. Run `android/scripts/publish-apk.sh <public-base-url> [notes]` with the explicit publishing
   server URL (for this channel, `https://prs.octaviantocan.com`). It runs `./gradlew :app:assembleDebug`, reads the version from the
   APK (falling back to `build.gradle.kts`), then writes `pulls.apk` and `latest.json`
   (`versionCode`, `versionName`, `sha256`, `apkUrl`, `notes`) to
   `$PULLS_REVIEW_ANDROID_DIR`, default `~/.local/share/pulls-review/android`. `notes` is this version's
   summary from `android/CHANGELOG.md` (the notes argument replaces it) and `changelog` is the whole file, so
   the phone can list every version it skipped.
4. The pulls.review server serves them at `GET /android/pulls.apk` and `GET /android/latest.json`
   (`backend/src/transport.rs`). It must run with the same `PULLS_REVIEW_ANDROID_DIR`.
5. On the phone the installed app compares its version code with `latest.json` and offers the update: a card
   above the Home search while the app is open, and from 0.17.0 a notification within about 15 minutes when it
   is not (a WorkManager check, no push service). Accept it; Android asks once for permission to install from
   the app. After the install, What's new opens once with the changelog.

Debug signing is pinned to `android/app/debug.keystore` (committed), so a build on any machine
produces the same signature and installed apps keep accepting updates. If it is ever regenerated or
replaced, every installed app must be reinstalled — never let Gradle fall back to an auto-generated
`~/.android/debug.keystore`.

## Past versions

Settings, Advanced, Past versions (0.36.0) has a dev-only slider over every version the app has had. Each past
version is the app rebuilt from the last commit that carried its `versionName`, as its own package
(`com.octaviantocan.pulls.v0_20_0`, label "Pully 0.20.0", versionCode offset by 1000000, no launcher icon or link
handling). The phone downloads one when it is picked, installs it beside the app and opens it with its own server
and Access token.

- `python3 android/scripts/build-past-ui.py` builds every version not built yet into
  `$PULLS_REVIEW_ANDROID_DIR/past/` (`<version>.apk` and `index.json`), which the server serves at
  `/android/past/`. The first run builds about forty versions, one Gradle build each; `--list` shows what it
  would build from which commit, and naming versions rebuilds just those.
- `publish-apk.sh` writes `past/index.json` and starts it in the background after every publish (one run at a time, a later publish queued behind it,
  `flock` on `past/.lock`, log in `past/build.log`), so the slider fills in newest first without holding up the
  publish. While nothing is built the server answers an empty list. A checkout gets `android/local.properties`
  copied in, so the builds find the SDK without `ANDROID_HOME`.
- A version that does not build is reported and skipped; the others still go out.

## Test without publishing

- Phone on USB or wireless debugging: `cd android && ./gradlew :app:installDebug`.
- JVM tests: `./gradlew :app:testDebugUnitTest :core:test`.

## First build after a big UI change

Build it before publishing (see above); code written without a compiler may not build against this Compose
version. Then check on the phone: inbox swipe (review right,
archive left), a pull request's overview and diff, selecting a line and commenting, the More sheet
(Model, Effort, Commands), a tour, Settings → Appearance (System, Light, Dark), and a large pull
request for list performance.
