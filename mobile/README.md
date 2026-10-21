# Shared Pully mobile foundation

Android and iOS build these same Kotlin source directories. Android includes the
modules from `android/settings.gradle.kts`; the standalone `mobile` root lets the
Apple host build them without configuring the Android application. `mobile/gradlew`
delegates to the existing pinned Android wrapper (Gradle 9.6.0).

This first slice shares ten existing domain units, the existing palettes, type and
font preferences, Lu icons, spacing/radii, and the production pull request row
layout. Android's Inbox, Tours and repository rows call that shared layout.
`android/core` remains the JVM compatibility facade and owns Android-only updater,
browser and remaining JVM-specific policies. Shared domain code has no Compose,
Android or Java dependency.

The iOS app is an **internal preview with fixture rows**, using the same shared
components. It does not yet connect to a server or support account, review, diff,
AI, persistence or notifications. It is not a usable full Pully client. The next
slices share existing screens and their client/session dependencies in the order
specified by the accepted iOS implementation plan; they must keep Android working.

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
./gradlew :shared-domain:jvmTest :core:test :app:testDebugUnitTest :app:assembleDebug
cd ..
pnpm run android:design
pnpm run android:design:test
pnpm run contracts:check
node --test android/scripts/release.test.mjs
```

The moved common tests run under `:shared-domain:jvmTest` and the iOS simulator;
`:core:test` retains the JVM-specific tests. Brief's producer golden JSON stays at
`android/core/src/test/resources/brief-fixture.json`, where the TypeScript producer
test already reads it. A Gradle task generates a common Kotlin test fixture from
those exact bytes; no second JSON or platform-specific test loader is maintained.

See [the iOS host guide](../ios/README.md) for the unsigned simulator build.
`.github/workflows/ios-verify.yml` runs native domain tests, links the actual
Compose framework, builds and launches the host in light/dark and portrait/landscape,
checks font bytes, and retains XCTest evidence. Ordinary simulator CI needs no
distribution secrets or paid Apple team. Final Apple framework/app compilation
requires the supported macOS/Xcode host; Linux JVM/Android success alone does not
validate iOS compilation, signing, device behavior or App Store distribution.

Runtime changes in either shared module trigger Android verification and the
required Android release/version gate. All `CONTRACTS` files continue to enter
Android's generated contract bundle automatically, including the shared modules.
