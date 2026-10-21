# Native account services

`PullyNative` contains platform adapters, not Pully screens or an HTTP/RPC client.
The shared Kotlin `Repo` remains the implementation of `account-status`, `account-me`,
`account-google-sign-in`, password-test-account sign-in and sign-out.

`NativeAccountStore` supplies native-friendly configuration fields to the shared
iOS host adapter. The host's synchronous Repo provider reads
`store.configurationSource.read()`, which atomically copies an immutable snapshot
and is safe from any Kotlin coroutine dispatcher. It must not call MainActor store
methods from a background callback. The host maps `server`, `headers`, `engine`, `model` and
`generation` to the existing shared `RepoConfiguration`; AI effort comes from the
shared reactive settings. It must pass a real `invalidated` callback that clears
the shared review cache, cancels old source observers and resets the active route's
private state. Every server/account/Access change invokes that callback on the main
actor before another call can use the new source. During synchronous cleanup, both
relays retain their prior complete snapshots; the new generation has already
invalidated late native auth answers. Only after cleanup returns are the new relay
snapshots published. The callback clears/cancels old ownership and must not start
new sessions. Reentrant cleanup cannot publish an intermediate source. No credential
is an observer key.

Only nonsecret server/model choices and local reset markers go into app-only
`UserDefaults`. Sessions, the account selector/email and Access credentials are
JSON records in this app's Keychain, using `WhenUnlockedThisDeviceOnly`, no iCloud
sync and no shared access group. Record keys hash the normalized server address and
account; separate path-prefixed servers on one origin do not share sessions. Native
server configuration accepts HTTPS only and leaves ATS unchanged. The shared
transport rejects redirects, and no image/link/Google host receives Pully headers.

On startup a restored session is `verificationRequired`, with no active account.
Ordinary configuration excludes its session header. The host uses the separate
`store.verificationSource.read()` only for the shared `Repo.accountEmail()` call, then
passes the answer to `verifiedRestoredSession(email:expectedGeneration:)`. A network
failure leaves verification pending for retry; an explicit rejected/different account
clears the saved session. Both verification and sign-in reject answers captured
before a source change. After a successful shared sign-in RPC, install the returned
Pully session with `signedIn(session:email:expectedGeneration:)`.

Sign-out must attempt the shared server operation and then call local `signedOut()`
even if the network fails. A failed Keychain deletion still clears memory,
invalidates observers and persists a restore-block marker. `resetCredentials()`
additionally removes Access credentials and all inactive credential records. If a
corrupt/locked Keychain prevents constructing the store, the host can offer explicit
reset using the same `KeychainSecretStore.removeAll()` and preferences reset markers;
it must not silently continue with an apparently signed-in account. Never print
snapshot headers or provider errors; app-visible adapter errors are fixed messages.

## Google configuration

The package pins the official GoogleSignIn iOS SDK **10.0.0**, source tag commit
`7823e518f5d4db765ccac3e140dd3265ef0a1cf1`, inspected on 21 October 2026.
Only the native SDK product is linked; no SwiftUI sign-in screen is used.

The repository currently contains **no registered iOS OAuth client ID or callback
scheme**, so `NativeGoogleSignIn.isAvailable` is false and the shared sign-in UI must
hide that action. A server Web client ID alone cannot configure native sign-in.
The real external prerequisites are:

1. A Google Cloud **iOS** OAuth client registered for the actual bundle identifier
   `com.octaviantocan.pully.preview`, in the project that owns the server Web client.
2. Its public iOS client ID in the built app's `GIDClientID` Info.plist key, and its
   dot-reversed ID in `CFBundleURLTypes` → `CFBundleURLSchemes`.
3. The server's existing `account-status.googleClientId` Web client ID. The wrapper
   supplies it as `GIDConfiguration.serverClientID`, so the ID token's audience is
   the value the current Rust validator already checks.

No client secret belongs in the app. No Google Cloud settings or backend audience
rules are changed here. Device sign-in also needs ordinary Apple app signing;
unsigned simulator compilation and the fixture UI require no distribution account.

After explicit user action, pass the visible Compose host controller and the store's
captured/current generation to `NativeGoogleSignIn.shared.idToken`. A dismissed sheet
returns nil; task cancellation returns promptly and discards its eventual answer.
The SDK owns system authentication-sheet dismissal. An overlapping attempt is refused
until the current SDK flow completes. Scene URL callbacks are forwarded only for the
registered reversed scheme; the SDK validates auth state. The wrapper clears the SDK's
own saved Google sign-in after extracting the ID token, and the shared Repo exchanges
that token for the existing Pully session. It never requests GitHub OAuth, extra Google
scopes or a server-side refresh-token exchange. The Rust allowlist remains authoritative.

The app's `PrivacyInfo.xcprivacy` declares only its app-owned UserDefaults required
reason, `CA92.1`. It makes no collection/tracking assertion. GoogleSignIn ships its own
resource privacy manifest, including its authentication profile data and SDK diagnostic
declarations; do not replace or strip that manifest or interpret this app's narrow
required-reason entry as a complete App Store privacy review. Pully usage analytics
remain the existing first-party service; no Pully events are sent to Google's SDK.

## Verification

`swift test --package-path ios/NativeServices` runs the source-change, restore,
failure/retry, defaults confinement and OAuth configuration cases on macOS. The same
package's iOS simulator suite runs the account/Google SDK cases. The identical real
Keychain read/write/replace/delete/reset test source is compiled by the separate
`PullyNativeKeychain` Xcode scheme as an app-hosted unit test. A standalone unsigned
Swift package XCTest runner lacks the application identity required by Security
and returns `errSecMissingEntitlement`; this is a test-host requirement, not a
reason to skip the test or widen the store's access groups.

The native-only test host is ad-hoc signed for the simulator, with its own exact
application identifier and debugger attachment entitlement. It has no shared
Keychain/app groups and needs no Apple account, development/distribution identity
or provisioning secrets. The workflow verifies the built code signature and
extracts/checks its actual entitlements before executing the real Security APIs.
The preview Pully app remains separately built unsigned. The workflow runs these
native suites before building that host,
and the host resource verifier checks the app-owned privacy declaration is bundled.
These commands are wired for macOS CI; Linux has no Swift/Xcode toolchain here and
source/plist inspection is not evidence that the Swift tests or Google flow passed.
No real Google account, signed-device or App Store validation is claimed by this code.

Sources:

- [Google native client and callback setup](https://developers.google.com/identity/sign-in/ios/start-integrating)
- [Google backend ID-token authentication](https://developers.google.com/identity/sign-in/ios/backend-auth)
- [Google server-client audience](https://developers.google.com/identity/sign-in/ios/reference/Classes/GIDConfiguration)
- [Pinned SDK source and release](https://github.com/google/GoogleSignIn-iOS/releases/tag/10.0.0)
- [Apple UserDefaults required reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)
- [Apple privacy-manifest structure](https://developer.apple.com/documentation/technotes/tn3183-adding-required-reason-api-entries-to-your-privacy-manifest)

Keychain test-host references:

- [Apple DTS: host entitlement-dependent unit tests in an app](https://developer.apple.com/forums/thread/773772)
- [Apple errSecMissingEntitlement and application identity](https://developer.apple.com/documentation/security/errsecmissingentitlement)
