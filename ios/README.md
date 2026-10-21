# Pully iOS host

This internal preview hosts the same shared Compose code used by Android. Its current
scope is deterministic public-safe fixtures; server setup, sign-in and the complete
review journey are subsequent slices. It does not contain service credentials or
configure push, background execution, associated domains or distribution signing.

The host uses UIKit only for app/scene lifecycle and the root `UIWindow`.
`MainViewControllerKt.MainViewController()` from `PullyShared` renders the app; shared
Compose owns safe-area padding, UI, state and navigation. The Xcode project references
the original Android font files and their existing license notices as resources, so
both clients use identical font bytes.

## Build and run on a Mac

Use an Apple ARM64 Mac with Java 21, Xcode 26.4.1 and an iOS 26.4.1 simulator. The
deployment floor is iOS 17; minimum-OS and physical-device verification remain separate
release work. The mobile Gradle build pins its Kotlin/Compose/AGP toolchain and also
declares an Android target, which requires Android SDK platform/build-tools 37.

Open `ios/Pully.xcodeproj`, choose the shared `Pully` scheme and an iPhone simulator,
then Run. The first build downloads Gradle/Kotlin/Native dependencies. Xcode runs
`scripts/build-shared.sh` before compiling Swift; that invokes
`:shared-ui:embedAndSignAppleFrameworkForXcode` from `mobile/`. The project disables
Xcode user-script sandboxing as required by that supported direct integration.

From the repository root, after creating an iPhone simulator:

```sh
export DEVELOPER_DIR=/Applications/Xcode_26.4.1.app/Contents/Developer
xcodebuild -project ios/Pully.xcodeproj -scheme Pully -configuration Debug \
  -destination 'platform=iOS Simulator,id=<simulator-UDID>' \
  -derivedDataPath ios/build/DerivedData -resultBundlePath ios/build/Pully.xcresult \
  -parallel-testing-enabled NO test CODE_SIGNING_ALLOWED=NO
python3 ios/scripts/verify-bundled-fonts.py \
  ios/build/DerivedData/Build/Products/Debug-iphonesimulator/Pully.app
```

The UI smoke test checks the shared preview survives launch, rotation and relaunch,
and keeps light/dark portrait and light landscape screenshots in the test result.
A Debug-only launch environment selects the native test appearance before Compose
attaches; ordinary launches use the system appearance. The `iOS verify` PR job
also runs the actual shared domain tests on JVM and the pinned iPhone simulator,
links the shared framework, verifies font bytes and retains simulator evidence.
The artifact includes `Pully-simulator.zip`, an unsigned ARM64 simulator app that can
be installed with `xcrun simctl install <simulator-UDID> Pully.app` after extraction.
An unsigned simulator run needs no Apple Developer membership or signing secrets.

Linux can run the shared JVM and Android checks. A supported macOS host is required
to link the Kotlin/Native Apple framework and build/run this app. Local XML/script
validation is not evidence of an iOS compile; use the macOS CI result for that claim.
No device, signed archive, TestFlight or App Store validation is implied by this host.

## Toolchain references

Checked on 21 October 2026:

- [Kotlin Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html): Kotlin 2.4.21, supported Gradle/AGP range and Xcode 26.4.
- [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html): Compose Multiplatform 1.12.2 and its Jetpack Compose mapping.
- [GitHub runner labels](https://github.com/actions/runner-images#available-images) and [macOS 26 ARM64 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md): ordinary `macos-26` label, installed Xcode 26.4.1 and simulator runtime.
- [Direct Xcode integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) and [UIKit integration](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html): build-phase ordering, script sandboxing and the required high-refresh-rate plist setting.
