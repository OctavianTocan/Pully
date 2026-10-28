# Shared Pully mobile code

Android and iOS build these same Kotlin source directories. Android includes the
modules from `android/settings.gradle.kts`; the standalone `mobile` root lets the
Apple host build them without configuring the Android application. `mobile/gradlew`
delegates to the existing pinned Android wrapper (Gradle 9.6.0).

`shared-domain` contains the existing core policies, wire models/parsers, triage,
stacks and portable pull targets. `shared-client` contains the existing Repo feature
methods and RPC envelopes, with injected configuration, source identity, transport,
clock and timing events. Neither module depends on Compose or native app services.

`shared-ui` retains the existing Compose-reactive AppSettings and ReviewSession,
with native preference/effect/cache adapters. Android keeps its existing preference
keys and factories. The same palettes, typography, font
preferences, Lu icons, spacing/radii and complete production pull request/stack
rows serve both hosts. Android's Inbox and repository lists call that shared row;
its Tours retain their persisted-entry adapter over the shared frame and check mark.
`android/core` remains the JVM compatibility facade and owns Android-only updater,
browser and remaining JVM-specific policies. Shared domain code has no Compose,
Android or Java dependency.

The iOS app hosts the same shared account, Home, review, AI and Settings screens on the
configured server's real data (see [the iOS host guide](../ios/README.md)); its Debug UI
tests alone select a labelled fixture with sample rows. Both hosts load the same 12 locale
files, with the existing plural/interpolation rules and English fallback. Shared status
marks honor native reduced-motion settings.

## Toolchain

Both Gradle roots pin Kotlin 2.4.21, Compose Multiplatform 1.12.2 and AGP 9.3.1.
Keep their plugin declarations aligned. AGP was adjusted from 9.4.0 because the
[Kotlin compatibility matrix](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
supports AGP through 9.3.1 for this Kotlin version; Gradle 9.6.0 and Java 21 remain.
[AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
supports API 37. The Android Compose BOM's UI/foundation/runtime version 1.12.1
matches the Android mapping of
[Compose Multiplatform 1.12.2](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).

One source build avoids cross-version Android project attributes: AGP explicitly
rejects project dependencies produced by a different AGP version, so separate
plugin versions in a composite would not solve that compatibility constraint.
Shared Android resources generate from the existing font directory; iOS bundles
those same files directly. Fonts and web tokens/icons remain single-source, and
`android:design` now checks shared common and native production sources too.

## Verify

Use Java 21 and an Android SDK containing `platforms;android-37.0` and
`build-tools;37.0.0`. Configure `ANDROID_HOME` or local SDK properties as usual.

```sh
cd android
./gradlew :shared-domain:jvmTest :shared-client:jvmTest :shared-ui:testAndroidHostTest :core:test :app:testDebugUnitTest :app:assembleDebug
cd ..
pnpm run android:design
pnpm run android:design:test
pnpm run contracts:check
node --test android/scripts/release.test.mjs
```

The moved common domain tests run under `:shared-domain:jvmTest` and the iOS
simulator; `:core:test` retains JVM-specific tests. Shared localization, settings and
review-session tests run under `:shared-ui:testAndroidHostTest` and
`:shared-ui:iosSimulatorArm64Test`. `:shared-client:jvmTest` includes real local HTTP
tests; its common protocol tests also run on Native. The separate
`:shared-client:iosSimulatorArm64IntegrationTest` executes the real NSURLSession
adapter against an explicit loopback fixture; see
[the fixture commands](../ios/scripts/README-rpc-fixture.md). Missing fixture
configuration fails that integration task instead of silently skipping it.

Review caches are app-owned, source-scoped and bounded. Native identity changes
clear old private state and cancel observation; an old response cannot restore it.
Task polling reconnects after transport errors and accepts only authoritative
terminal statuses and current revisions. Detaching a route stops observation,
while server-owned work continues until an explicit cancellation RPC.
Portable timestamp parsing retains the former Java parser's accepted offset,
fraction, leap-second and midnight forms, as well as null for invalid or overflowing
epoch milliseconds. Existing and focused common parser regressions cover that
boundary. Brief's producer golden JSON stays at
`android/core/src/test/resources/brief-fixture.json`, where the TypeScript producer
test already reads it. A Gradle task generates a common Kotlin test fixture from
those exact bytes; no second JSON or platform-specific test loader is maintained.

See [the iOS host guide](../ios/README.md) for the unsigned simulator build.
`.github/workflows/ios-verify.yml` runs native domain tests, links the actual
Compose framework, builds and launches the host in light/dark and portrait/landscape,
checks Spanish labels and English fallback, verifies font/locale bytes, and retains
XCTest evidence. Ordinary simulator CI needs no
distribution secrets or paid Apple team. Final Apple framework/app compilation
requires the supported macOS/Xcode host; Linux JVM/Android success alone does not
validate iOS compilation, signing, device behavior or App Store distribution.

Runtime changes in any shared module trigger Android verification and the
required Android release/version gate. All `CONTRACTS` files continue to enter
Android's generated contract bundle automatically, including the shared modules.
