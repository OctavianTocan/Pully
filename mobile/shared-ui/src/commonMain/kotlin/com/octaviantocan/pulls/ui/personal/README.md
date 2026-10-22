# Native personal GitHub connection

The shared sheet connects a personal GitHub identity to Pully while keeping the
workspace on public Explore. It grants no legacy server, private repository or AI
authority. AI setup is a separate capability and must remain usable without GitHub.

Native hosts provide a trusted, app-configured HTTPS account origin, an actual
secure blob store and their system browser/foreground events. Android uses the
`pully.personalAuthOrigin` Gradle property, empty by default. No origin is inferred
from legacy Settings, Cloudflare Access or a saved account.

## Host ports

`PersonalSessionBlobStorage.read(): ByteArray?` and `write(ByteArray)` represent an
atomically replaced encrypted blob. Android's adapter uses a non-exportable
Keystore AES-GCM key and `AtomicFile` in `noBackupFilesDir`. An iOS adapter must
use a separate app-private Keychain item, a device-only accessibility class and no
synchronization, UserDefaults, plaintext or legacy-account fallback. Keep one
`PersonalSessionVault` per native application/store on its UI dispatcher so active
confirmation ownership is shared across windows.

`PersonalAccountRuntime(origin, vault, parentScope, cleanupScope, nowSeconds,
isCurrent, onIdentityChanged)` owns one public entry lifetime. The optional
transport argument defaults to the production personal transport. Its `view`
contains safe account metadata, checking and recovery state; never a bearer.
`restore()` verifies an accepted record before showing its profile. Native foreground
events MUST synchronously call `foreground(active)` before scheduling UI updates. `openSignIn()`
returns an owner with the shared controller; closing that owner releases only its
own stage. `signOut()` durably retires the accepted record before revocation.
`close()` retires callbacks and pending operations. All methods and the secure
store run on the UI dispatcher; cleanupScope outlives the sheet/entry and uses
that same dispatcher.

Render `PersonalAccountSheet(runtime, onOpenBrowser, onClose)`. Its bound dismissal
retires sign-in synchronously before forwarding the host's close callback; disposal
is fallback cleanup. The native foreground port includes external-browser pause and
must not be deferred through a Compose state or effect.
`GuestPages` accepts the optional verified `githubAccount` login and the existing
`onGitHubSignIn` callback. Keep the callback unavailable when the trusted origin
or actual secure storage is unavailable. An absent configured backend capability
still produces fixed recovery and never opens a fake sign-in.

The nonthrowing `onIdentityChanged` host hook must synchronously call the public
entry's `renewExplore()`, retire its old workspace and reset the same navigation
to `Screen.Explore`. It must rebuild only the public workspace, preserving the
separately saved legacy account and leaving private observers unconstructed.

## Claim recovery

The one-use claim carries a provisional session bounded by the original attempt
deadline. The controller writes a separate secure pending record before bearer
`GET /api/personal/v1/session` confirms delivery. Only a matching confirmed
account can be promoted. It consumes the attempt before the accepted source is
published, so retiring that source does not cancel the accepted claim.

Unpromoted records are cleanup-only on restart. An in-process staging owner keeps
its active confirmation out of another window's cleanup. Closing the owner
releases that record for recovery. Server sign-out must succeed before a pending
record is deleted; an uncertain network outcome retains its opaque bearer for a
later bounded retry. Queue overflow or secure-store failure refuses the new
stage rather than deleting another account or recovery record.

Actual OAuth application registration, a deployed configured personal listener
and real Apple/device execution remain separate activation/release prerequisites.
