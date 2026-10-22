# Native share handoff

`PullyShare.appex` is a thin system adapter. It links the UI-free `PullyLinks`
framework and the pure Foundation `PullyShareSupport` product. The main app links
`PullyShared` and re-decodes the same shared-domain codec in its own Kotlin runtime;
it does not link a second Kotlin runtime. Compose retains the existing incoming-link
inbox, account/source policy, destination mapping and Back stack.

The extension reads URL/text attachments, validates their HTTPS envelope against
GitHub or the configured server's public origin, and uses the existing shared parser
to encode the canonical target and LinkPlace. It stores no raw prose, arbitrary URL
query, account name, credential, settings or model key. Save explains that the user
must then open Pully and completes the extension; it does not foreground another app.

The optional App Group contains one latest pending link, an opaque source token,
public origin, UUID and timestamp. Payloads are bounded to 16 KiB and expire after
24 hours. Every activation, save, revoke and consume uses the same cross-process
`flock`, protected atomic files, 0600 files and a 0700 subdirectory. iOS uses Complete
file protection. Consumption removes the record under lock before returning it.
The lock is nonblocking: a suspended process cannot freeze app activation or account
cleanup. Contention returns a distinct busy/retry error before touching protected
records; a failed revocation keeps imports disabled until a later attempt retires both
durable sides. Busy imports preserve the existing token and pending record.
The existing shared source namespace stays in a separate app-private file. A new
process for the same verified source reuses the opaque token and pending record;
session renewal, verification retry and scene close do not rotate it. Actual native
endpoint, Access or account replacement revokes before new-source publication, and
a late extension save rechecks its captured token. Storage errors fail closed for
import and cannot block ordinary credential cleanup.

Unreadable or protected identity and pending files retain their authority and data
for a later retry. Malformed or oversized identity/source JSON instead requires
successful retirement of both durable sides before one attempt at a fresh token;
the old record is never imported. A malformed pending record is discarded without
rotating a valid same-source token. The extension bundles its own FileTimestamp
required-reason manifest for metadata access confined to its own or group container;
the ordinary built-resource check verifies that actual embedded resource.

## Provisioning

The default preview has no App Group entitlement or team configuration. Its extension
shows that sharing needs setup and offers Cancel without Save. The app, custom URL
delivery and ordinary CI remain usable. A build setting alone grants no container.

Enabling this handoff on a provisioned installation requires a registered App Group
for both `com.octaviantocan.pully.preview` and its `.share` extension. Configure that
same group in both targets' actual provisioning/entitlements, then set the project
`PULLY_SHARE_APP_GROUP` build setting. The entitlement example here contains a placeholder
and is deliberately not applied. Keep the main app's existing private Keychain identity;
App Group access must not add a Keychain sharing entitlement. No Apple registration,
team, provisioning profile, purchase or distribution setting is changed by this code.

The existing Pully scheme builds and embeds the extension, so the ordinary Mac host
compile checks its real Swift and `PullyLinks` header integration. Native package tests
exercise independent storage clients, same-source host recreation, stale writes,
expiry/corruption, protection attributes and an actual separate process holding the
file lock while host take/revoke and extension save return promptly. Shared tests run
the real parser/codec through typed inbox routing and
source/await/Back guards. These source tests are not proof of a provisioned container
or a device share-sheet journey; those require the registered group and real Apple
execution. Physical protected-data behavior and signed device distribution remain
separate validation.
