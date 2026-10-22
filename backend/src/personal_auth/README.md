# Personal GitHub authorization

This module supplies a Pully-owned GitHub OAuth App flow, encrypted credential
vault, hashed Pully sessions and explicit HTTP router factories. Nothing in the
module registers a production listener, changes Cloudflare Access or exposes the
legacy RPC dispatch. The unconfigured factory serves only disabled capability
discovery and needs no account, provider or store.

## Configuration and activation boundary

A reviewed host can construct `OAuthConfig` from its own registered Pully OAuth
App ID, server secret and an exact HTTPS callback ending in
`/api/personal/v1/auth/github/callback`. It supplies the server-owned Store and
`VaultKeys` (a positive active version and one to eight versioned 32-byte keys),
then explicitly installs `configured_router`. No credential is read from `gh`,
another application's client ID, a shared GitHub token or an account seed.

Real registration, callback domains/TLS, secure key distribution and public ingress
limits are external deployment prerequisites. They are not provisioned here. The
host must redact callback query strings in any external access log. The module's
callback body is static, has no referrer disclosure and never prints code, state,
proof or credentials. Retain prior vault keys while old envelopes still use them;
new writes always use the active key version.

Only the auth/session surface exists here. There is no authenticated repository,
review, AI, settings, storage or agent ingress. Installing these factories does not
authorize the legacy API or make operator caches personal. A future repository
boundary must use the explicit application authority and current tenant/repository
permission checks, regardless of a token's inherited upstream scope.

## Native protocol

All paths below are fixed under a trusted HTTPS Pully auth origin, separate from
the user's legacy private-server configuration. Native requests carry no Access
headers, legacy `X-Pully-Session`, cookies or user-selected redirect/configuration.

- `GET /api/personal/v1/capabilities` returns `{github:{enabled:Boolean}}`.
- `POST /api/personal/v1/auth/github/start` accepts only `nativeChallenge` and
  returns `attemptId`, `authorizeUrl`, `expiresAt` and `pollAfterMs`.
- The native client makes 32 CSPRNG bytes, encodes them as 43-character base64url
  without padding (`nativeProof`), and sends
  `BASE64URL_NO_PAD(SHA256(ASCII(nativeProof)))` as its challenge.
- `POST .../status`, `.../claim` and `.../cancel` require `attemptId` and
  `nativeProof` in JSON bodies. They accept no query parameters. State and GitHub
  PKCE use separate server-generated randomness.
- The system browser uses the fixed GitHub authorize origin. A fixed callback
  consumes state once, exchanges the code with S256 PKCE and verifies `GET /user`.
  The native app resumes polling with the same in-memory proof after browser return.
  Ordinary background pauses polling without cancellation; explicit cancel,
  source/entry retirement, expiry and process death retire native ownership.
- `status` returns `pending`, `ready`, `failed`, `cancelled` or a typed expired
  failure. A successful one-use `claim` returns only the newly issued Pully
  `session` and redacted `status`. GitHub tokens never reach native clients.
- The claim's session is provisional: its `sessionExpiresAt` is the original
  attempt deadline, at most ten minutes after start. Secure-store the bearer, then
  use bearer-authenticated `GET /api/personal/v1/session` before that deadline to
  confirm delivery. The confirmed response returns the ordinary thirty-day session
  deadline. Do not publish the new source before confirmation. An expired or lost
  claim requires fresh sign-in; unconfirmed sessions expire and are reclaimed before
  a later sign-in's capacity check, without replaying a claim or retaining its bearer.
- The client must consume its pending attempt before publishing the accepted session
  to its new source. After a claim is consumed, source retirement or failed storage/
  confirmation must sign out the captured session. Remove its local credential only
  after revocation succeeds; an uncertain failure must retain a cleanup-only secure
  record that cannot restore a principal.
  Retiring an obsolete pending attempt uses best-effort cancel against its captured
  origin; explicit cancel after a claim revokes that exact session.

`*At` timestamps are Unix epoch seconds. `pollAfterMs` is milliseconds (2000).
Public sign-in always has `publicRead` application authority, even when GitHub
returns an inherited `repo` grant. The wire also names `privateRepositories` for
the separately reviewed explicit capability-upgrade boundary; this public factory
does not expose a private-upgrade route. GitHub's `repo` grant is broad read/write
access and cannot bypass organization approval or SSO.

`GET /api/personal/v1/session`, `POST /api/personal/v1/session/sign-out` and
`POST /api/personal/v1/github/disconnect` accept only a personal Pully bearer in
`Authorization`. Session discovery may serialize a necessary refresh. Sign-out
retires its session; disconnect retires all sessions for that stable account.
Failures have fixed `{error:{kind,message}}` bodies, retain no reflected request
or provider text, and every response is `no-store`.

## Durable identity and token lifecycle

The account mapping uses `github.com` plus immutable numeric GitHub ID. Mutable
login metadata never chooses an account. `start_legacy_link` is a private-host
entry requiring an actual current legacy account session, rechecked on install;
there is no public link route and email matching never links accounts. Linking
does not copy legacy API keys, caches or other data.

AES-256-GCM envelopes authenticate account ID, GitHub subject, credential revision,
record purpose, provider, format and key version. Session claims separately
authenticate their hashed bearer, account, subject, generation, expiry and provisional
confirmation deadline. No key,
OAuth configuration, GitHub token or raw session wrapper implements Debug.

Refresh serializes per stable account, retires the old captured binding, and saves
the rotated pair encrypted before retryable identity verification. Unverified
rotated credentials cannot create authority. A temporary `/user` outage therefore
retries verification without reusing the consumed refresh token. A mismatched
subject, terminal refresh rejection or unlink cannot resurrect prior sessions.
An old rejection cannot revoke a newer credential revision.

Active bindings are scoped to a session and credential revision for independent
session revocation. Durable vault identity remains the stable account ID. The
current tenant foundation separately namespaces transient state by binding UUID;
cross-device durable repository/history continuity is not implemented by this
auth module and must not be inferred from stable vault identity.

## Verification and provider basis

The targeted Rust tests exercise the actual service, canonical identity parser,
AEAD, Store transactions and HTTP router on loopback with a fake GitHub provider.
They do not authenticate a real GitHub account or verify deployed callback links.

Current GitHub documentation used for the protocol:

- [OAuth Apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps):
  S256 PKCE, state, fixed token exchange, `offline_access`, expiring access/refresh
  pairs, refresh rotation and revalidating the authenticated user.
- [Scopes](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps):
  public read without regular scopes, inherited grants and the breadth of `repo`.
- [Authenticated user](https://docs.github.com/en/rest/users/users#get-the-authenticated-user)
  and [organization SSO](https://docs.github.com/en/enterprise-cloud@latest/authentication/authenticating-with-single-sign-on/authorizing-an-app-for-single-sign-on).
