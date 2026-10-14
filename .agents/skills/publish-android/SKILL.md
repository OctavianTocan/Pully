---
name: publish-android
description: Build the Android app and publish it so the installed phone app offers the update; also how to test a build on a phone. Use when asked to ship, release, publish, install or test the Android app, or how an Android update reaches a device.
---

# Publish and test the Android app

Nothing builds or publishes the APK automatically. CI (`ci.yml`) does not touch `android/`, and
`release.yml` only runs for `v*` tags and publishes the packages. Merging to `main` changes no
installed app. A person (or an agent with the toolchain) has to run the steps below.

The sandbox agents run in cannot build: `dl.google.com` and `maven.google.com` are blocked, so there is
no Android SDK or AGP. Do not claim an Android change was built or run unless it was, on a machine that
can.

## Publish an update

Needs JDK (the script uses `mise where java`), the Android SDK (`ANDROID_HOME`, default
`~/Android/Sdk`) and the server running with access to the output directory.

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts`. The app offers an update only
   when the published `versionCode` is higher than the installed one.
2. Merge to `main`, check it out on the build machine.
3. Run `android/scripts/publish-apk.sh [public-base-url] [notes]` (default base
   `https://prs.octaviantocan.com`). It runs `./gradlew :app:assembleDebug`, reads the version from the
   APK (falling back to `build.gradle.kts`), then writes `pulls.apk` and `latest.json`
   (`versionCode`, `versionName`, `sha256`, `apkUrl`, `notes`) to
   `$PULLS_REVIEW_ANDROID_DIR`, default `~/.local/share/pulls-review/android`.
4. The pulls.review server serves them at `GET /android/pulls.apk` and `GET /android/latest.json`
   (`packages/cli/src/native-api.ts`). It must run with the same `PULLS_REVIEW_ANDROID_DIR`.
5. On the phone the installed app compares its version code with `latest.json` and offers the update.
   Accept it; Android asks once for permission to install from the app.

Debug signing is pinned to `android/app/debug.keystore` (committed), so a build on any machine
produces the same signature and installed apps keep accepting updates. If it is ever regenerated or
replaced, every installed app must be reinstalled — never let Gradle fall back to an auto-generated
`~/.android/debug.keystore`.

## Test without publishing

- Phone on USB or wireless debugging: `cd android && ./gradlew :app:installDebug`.
- JVM tests: `./gradlew :app:testDebugUnitTest :core:test`.

## First build after a big UI change

Expect compile errors the first time: the code may only have been type-checked against a different
Compose version. Fix those before publishing. Then check on the phone: inbox swipe (review right,
archive left), a pull request's overview and diff, selecting a line and commenting, the More sheet
(Model, Effort, Commands), a tour, Settings → Appearance (System, Light, Dark), and a large pull
request for list performance.
