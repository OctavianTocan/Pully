---
name: publish-android
description: Build the Android app and publish it so the installed phone app offers the update; also how to test a build on a phone. Use when asked to ship, release, publish, install or test the Android app, or how an Android update reaches a device.
---

# Publish and test the Android app

Nothing builds or publishes the APK automatically. CI (`ci.yml`) does not touch `android/`, and
`release.yml` only runs for `v*` tags and publishes the packages. Merging to `main` changes no
installed app. A person (or an agent with the toolchain) has to run the steps below.

## Building in a cloud sandbox

A cloud sandbox can compile and unit-test the app (checked 14 Oct 2026: `dl.google.com`,
`maven.google.com` and Maven Central all answer through the egress proxy). What it cannot do is run the app,
so say "compiled and unit-tested", never "tried on a phone", unless a device was used.

1. Install a JDK 17+, then the SDK: download the Linux command-line tools zip from
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

Build it before publishing (see above); code written without a compiler may not build against this Compose
version. Then check on the phone: inbox swipe (review right,
archive left), a pull request's overview and diff, selecting a line and commenting, the More sheet
(Model, Effort, Commands), a tour, Settings → Appearance (System, Light, Dark), and a large pull
request for list performance.
