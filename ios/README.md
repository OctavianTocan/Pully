# Pully iOS host

This internal preview hosts the same shared Compose code used by Android. Ordinary
launches connect the real shared account/client to the configured server, with native
Keychain storage, restored-session verification, the existing sign-in page and Server
fields. The existing Home and repository screens retain their shared route stack,
filters, scroll state, profile graphs, pull-request rows and message catalog. Shared
settings synchronization and first-party usage policies use their real native adapters.
Home now opens the existing shared ReviewScreen and Overview/Diff readers through the
same session cache and navigation stack. AI, viewer-progress writes and full settings
remain unavailable before work until their complete workflows are installed and proven.
The complete review journey is still being connected. No service credentials are bundled;
push, background execution, associated domains and distribution signing remain separate.

The deterministic, nonprivate fixture journey requires the Debug launch environment
`PULLY_UI_TEST_FIXTURES=1` and retains its visible fixture label. It is never selected
after a live server, sign-in or secure-storage failure. Existing UI smoke tests explicitly
select this fixture journey; they do not claim live sign-in coverage.

The host supplies UIKit app/scene lifecycle, clipboard, browser and leading-edge
back recognition through the required `NativeHomeActions` interface.
The scene retains `IosPullyApp(homeActions:accountStorage:)` from `PullyShared`; its
`viewController` renders the app and its `close()` retires work on scene disconnection.
Safari, suspension and ordinary route changes preserve that application owner. The
Debug fixture uses `MainViewControllerKt.MainViewController(homeActions:)`. Shared
Compose owns safe-area padding, UI, state and navigation. The Xcode project references
the original Android font files and their existing license notices as resources, so
both clients use identical font bytes.
The existing Android `i18n/` folder is a direct Xcode folder resource, preserving
`i18n/<locale>.json` in the app bundle. The resource gate compares the complete JSON
file set and bytes with the canonical web locales and the Android mirror. No separate
iOS translation source is maintained. `CFBundleLocalizations` declares that same
supported set for native language selection; the resource gate verifies it too.

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
  -derivedDataPath ios/build/DerivedData build CODE_SIGNING_ALLOWED=NO
python3 ios/scripts/verify-bundled-fonts.py \
  ios/build/DerivedData/Build/Products/Debug-iphonesimulator/Pully.app
```

Unsigned compilation does not exercise Keychain. To execute the host tests, use the
ad-hoc simulator signing, exact embedded XML/DER entitlement guard and
`test-without-building` sequence in `.github/workflows/ios-verify.yml`. It requires no
Apple Developer membership or signing secret. The app-hosted account tests and dedicated
native Keychain tests must run with that private simulator identity; an unsigned
Keychain failure is not an allowed skip.

The UI smoke test checks the shared preview survives launch, rotation and relaunch,
and keeps light/dark portrait and light landscape screenshots in the test result.
It also checks the production checks mark's Spanish catalog label, the draft state's
accessibility and the English fallback for an unsupported device language. The
interactive scenarios choose All, scroll to a known fixture, open its repository,
return through the actual native leading-edge gesture and verify the same filter
and scroll position after back and background/resume. They also exercise the
unsupported-review limitation and the existing hold-menu Copy link confirmation.
A separate Debug-only launch environment selects the native test appearance before Compose
attaches; ordinary launches use the system appearance. The `iOS verify` PR job
also runs the actual shared domain tests on JVM and the pinned iPhone simulator,
links the shared framework, verifies font/locale bytes and retains simulator evidence.
It also runs native account package tests on macOS and iOS, the actual Keychain
roundtrip and the shared client's separate NSURLSession loopback integration suite.
The artifact includes `Pully-simulator.zip`, a private ad-hoc ARM64 simulator app that can
be installed with `xcrun simctl install <simulator-UDID> Pully.app` after extraction.
Simulator signing uses only a local ad-hoc identity, with no distribution credentials.

Linux can run the shared JVM and Android checks. A supported macOS host is required
to link the Kotlin/Native Apple framework and build/run this app. Local XML/script
validation is not evidence of an iOS compile; use the macOS CI result for that claim.
No device, signed archive, TestFlight or App Store validation is implied by this host.

Native credential/configuration and Google token adapters live in
[`NativeServices`](NativeServices/README.md). They retain the shared Kotlin RPC client
and keep credentials in app-private Keychain storage. The separate fixture screen
does not expose sign-in. Native Google availability requires a real iOS client and
registered callback scheme; the adapter documents the exact missing configuration.
The app bundles its UserDefaults required-reason declaration and retains the SDK's
own privacy resource. Store privacy/signing review remains later release work.

## Darwin RPC integration

On macOS, start the nonprivate loopback fixture from the repository root:

```sh
python3 ios/scripts/rpc-fixture.py --state-dir ios/build/rpc-fixture
```

With a booted iPhone simulator, run in a second terminal:

```sh
cd android
./gradlew :shared-client:iosSimulatorArm64IntegrationTest \
  -Ppully.iosSimulatorId="$SIMULATOR_ID" \
  -Ppully.rpcFixtureUrl="$(cat ../ios/build/rpc-fixture/url)"
```

The six tests use the actual NSURLSession transport. The oversized-response fixture
advertises 64 MiB + 1, emits at most one initial body byte, then holds the rest.
This avoids relying on delivery of a headers-only response callback: the test still
requires the advertised-length error and socket closure, and verifies that the
fixture sent at most one byte. That byte cannot trigger the 64 MiB streaming guard,
so waiting for the rest or ignoring the header still fails. Stop the fixture with
Ctrl-C after the tests finish.

## Toolchain references

Checked on 21 October 2026:

- [Kotlin Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html): Kotlin 2.4.21, supported Gradle/AGP range and Xcode 26.4.
- [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html): Compose Multiplatform 1.12.2 and its Jetpack Compose mapping.
- [GitHub runner labels](https://github.com/actions/runner-images#available-images) and [macOS 26 ARM64 image](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md): ordinary `macos-26` label, installed Xcode 26.4.1 and simulator runtime.
- [Direct Xcode integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) and [UIKit integration](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html): build-phase ordering, script sandboxing and the required high-refresh-rate plist setting.

## Native control feedback

The host installs `ControlFeedbackHost` before constructing the shared Compose controller.
The actual existing Cuelume recipes, shaping, DSP, synthesis, voice mixer and browser
fidelity tests live in `shared-ui/commonMain` and `commonTest`; Android retains its
AudioTrack and haptic adapters. The iOS adapter changes output only:

- Kotlin synthesizes the same cues on a serial worker and hands 48 kHz stereo Float32
  to a serial Swift producer in bounded 480-frame buffers.
- `AVAudioEngine`/`AVAudioPlayerNode` use the ambient session category, which follows
  Ring/Silent and mixes with other audio. No background-audio mode is enabled.
- UIKit selection, impact and notification feedback adapt the existing cue roles.
  The existing `pulls-ui` sound, haptic and material preferences remain authoritative.
- Scene suspension or an interruption disables pending synthesis and discards old
  mixer/output buffers. Resuming never starts a cue from an earlier interaction.

The Xcode resource phase references the original Android `cuelume/recipes.json` and
Cuelume MIT notice directly. The built-app verification compares their bytes with
those sources. Resource installation failure is explicit before interactive controls;
unavailable audio hardware quietly leaves the action functional.

The shared Android-host sound tests and Android compilation can run on Linux. Apple
buffer conversion tests are part of the existing simulator Swift package test step;
Kotlin/Native linking, Swift compilation, simulator execution and physical Ring/Silent
or haptic behavior still require the macOS lane/device validation. Source wiring is
not evidence that those Apple checks passed.

The account rules also run as native Swift package tests. The real Keychain test
runs in a separate native app-hosted XCTest target (`PullyNativeKeychain`), ad-hoc
signed for the simulator. Its private application identity is checked from the
actual built executable before testing: the workflow verifies the code signature,
extracts its `__TEXT,__entitlements` and `__TEXT,__ents_der` simulator sections, and
requires matching private XML/DER claims. Xcode's ordinary simulator code-signature
entitlement dictionary may be empty. This local simulator signature needs no
Apple account or provisioning secrets and does not validate device/distribution
signing. The main Pully simulator artifact has its separately verified private ad-hoc identity. See
[NativeServices verification](NativeServices/README.md#verification).

## Shared Home native actions

`HomeActionsHost` supplies the required `NativeHomeActions` passed to
`MainViewController(homeActions:)`; the shared Home/Repo screen slice and this Swift
entrypoint must be integrated together. Compose retains all screen state, navigation
and RPC. The adapter writes the system clipboard, opens validated HTTPS GitHub links
directly through UIKit, and forwards actual scene events. Registration synchronously
reports foreground-inactive as started, foreground-active as started/resumed, and
background as stopped. Cancelling a registration removes only that observer; scene
disconnection stops and clears all observers. Reentrant transitions cannot resume an
obsolete foreground event.

The same adapter registers one leading-edge screen-pan recognizer on the Compose
root view. Shared layout direction selects the left or right edge. Native code
reports clamped page-width progress, commits a sufficiently long drag or inward
flick, and cancels an interrupted/reversed drag. Shared `PageMotion` renders the
transition and the existing shared stack owns the back operation. A disabled root,
scene interruption, replaced registration or cancellation cannot commit another
route. No UIKit navigation stack or native view transform is involved.

The explicit browser action validates HTTP(S) URLs without URL credentials and
presents `SFSafariViewController` from the active Compose scene. Its initial request
stays in native browser presentation, including canonical GitHub PR links, instead
of going through app/universal-link routing. Open on GitHub retains direct system
navigation. Neither native action attaches Pully session or Access headers.

The lifecycle and external-link validation cases run in the native Swift package
suite. Source/PBX checks on Linux do not prove Swift header compatibility, simulator
clipboard/browser behavior, real edge-pan delivery or Apple lifecycle execution;
the paired app must compile and run in the macOS lane. Only the explicit Debug fixture
uses sample data; ordinary launches use the configured server and verified account.

### Live account simulator identity

The shared account runtime uses the actual private Keychain adapter. The main app's
Debug simulator build therefore has an explicit private application identity and
XCTest attachment entitlement, restricted to the simulator SDK. No device signing
identity, provisioning profile, team, sharing group or Apple account is configured.
CI retains an unsigned compile, then builds the simulator app ad-hoc, verifies its
actual Mach-O XML/DER identity and signature, and runs both the existing UI suite
and app-hosted account bridge tests. The bridge tests use the production Swift host,
shared Kotlin protocol and real Security storage for invalidation ordering, restored
session confinement, source replacement and credential reset. The earlier dedicated
native Keychain suite remains mandatory. Source review and Linux parser tests do not
substitute for this new Mac compilation and execution gate.

### Incoming links

The app registers `pully://open?url=<percent-encoded HTTPS URL>`. For example,
`pully://open?url=https%3A%2F%2Fgithub.com%2Fpully%2Fmobile-fixtures%2Fpull%2F76`
opens a pull request. The native boundary accepts GitHub or the currently configured
HTTPS server origin only, preserves the full destination path, query and fragment,
and rejects credentials, control characters, oversized input and other envelopes.
Google callback URLs are offered to the SDK first. This does not bypass the registered
iOS Google client requirement or wait synchronously for a server sign-in response.

Cold scene URL contexts and browsing activities, and warm URL/activity callbacks,
feed the retained shared app's one incoming-link inbox. The same shared parser,
verified current account and Compose stack select the actual repository, PR, file,
line, conversation or check destination. Pending arrivals wait for a ready account;
an invalid arrival uses the existing one-shot shared error. No server is reconfigured
and the app does not inspect the clipboard automatically.

The public `XCUIApplication.open(_:)` test delivers the registered custom URL through
iOS to the actual Debug account root, reopens the same review at a file anchor,
checks native Back retains Repo/Inbox context, then terminates and opens a cold URL
while the genuine isolated Keychain account restores. Shared parser regressions and
native Foundation receiver tests cover huge identifiers, malformed envelopes, origin
confinement, SDK callback ordering and disconnected-scene delivery. These tests require
the existing signed Mac simulator lane; source coverage alone is not execution proof.

Universal Link callbacks are implemented, but automatic HTTPS delivery requires a
real app/team Associated Domains entitlement and a matching server-hosted AASA file.
Those external registrations are not configured by this preview. Claiming GitHub's
domain is not possible; use the custom URL or a supported share handoff instead.
The separate share-extension work must use an optional provisioned App Group for
sanitized, source-bound handoff data and keep all credentials in the app-private
Keychain. An extension cannot promise to foreground Pully; saving and then opening
the app remains an explicit user action.
