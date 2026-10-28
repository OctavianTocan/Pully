# @pulls.review/worker

The hosted Pully server on Cloudflare: the Rust server in `backend/`, built for Workers with
[workers-rs](https://github.com/cloudflare/workers-rs). The Worker sends every request to one
`PullyServer` Durable Object, which answers with the server's own router and keeps the
server's data in its SQLite storage. See `plans/12-hosted-server.md` (Update, 24 October 2026).

What runs here today:

- sign-in, agent keys, settings, posted diffs, and every phone method that needs only GitHub
  and storage;
- the 3-minute refresh of open pull requests, on the Durable Object's alarm (the first request
  sets it, and each run sets the next), checking up to 400 of them fifty to a GraphQL query;
- usage analytics, one SQLite row per event in the same Durable Object, written as each event
  arrives so no timer keeps the object awake;
- the download page, the update manifest and the APKs, read from the `pully-android` R2
  bucket under the same names as the native server's Android directory.

- AI work: every Claude Code and Codex run, model list and sign-in goes to the login's own
  `AgentHome` Durable Object and its container (`agent/Dockerfile`), where the AI kit's
  runner (`ai-kit/src/runner.rs`) runs the CLIs. The object keeps the login's sign-in files as text in its own
  storage while the container sleeps.

The Worker itself still runs no programs and reads no files: the server's `rt::fs` and
`rt::process` refuse every call there.

The config has no route on purpose: the staging and stable workflows add their host, which
goes in together with its Cloudflare Access policy.

## Try it locally

Needs Rust with the `wasm32-unknown-unknown` target and `cargo install worker-build`.

```sh
printf 'PULLY_ACCOUNT_EMAIL=test@example.com\nPULLY_ACCOUNT_PASSWORD=<a password>\n' > packages/worker/.dev.vars
pnpm -F @pulls.review/worker dev
curl http://localhost:8787/health
```

`wrangler dev` rebuilds when this crate changes, not when `backend/` does; restart it after
editing the server. Local data stays in `.wrangler/state`.

To try downloads, fill the local bucket from an Android directory:
`scripts/upload-android.sh ~/.local/share/pulls-review/android --local`.

`wrangler dev` builds the agent container with the local Docker, so AI work needs Docker
running; without it, AI calls answer that the container is unavailable.

## Staging

`.github/workflows/staging.yml` deploys this Worker to the staging host and publishes Pully
Staging after every merge to main (`docs/RELEASE_TRACK.md`, Staging). It needs, once:

- a Cloudflare Access application for the staging host, with the same policy as today's server;
- the GitHub secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`, and the variable
  `PULLY_STAGING_HOST` (for example `pully-staging.octaviantocan.com`);
- the `pully-android` bucket, and the Worker secrets below;
- for Connect GitHub, the secret `PULLY_PERSONAL_VAULT_KEYS` (the vault keys file's text,
  `{"activeVersion":1,"keys":{"1":"<43 base64url characters>"}}`; keep old keys while sign-ins
  made with them are in use) and an Access bypass for `/api/public/` and `/api/personal/`
  only, as on production.

Public browsing and personal GitHub sign-in go to their own `PersonalServer` object
(`src/personal.rs`), the Worker's form of the native server's `--public-listen` listener. It
keeps the sign-in vault and nothing else: each account's GitHub reads and workspace go on to
that account's own `AccountServer` object (`src/account.rs`), with the session the sign-in
object resolved for the request. The account object keeps the account's saved data in its own
SQLite, refreshes the account's open pull requests every three minutes while someone is signed
in to it, and the first time it starts copies what the account had saved in `PersonalServer`
before accounts had their own objects (that copy is left in place).

`scripts/staging-config.mjs` adds the host as the Worker's custom domain for that deploy only;
`scripts/publish-staging-android.sh` builds the app and puts it under `staging/` in the bucket.

## Stable (after Tavi's go-ahead)

`.github/workflows/stable.yml` deploys this Worker as its own Worker, `pully-stable`, with its
own objects and data, after each release push whose CI passed and that `scripts/release-track.mjs`
admits. It skips until the variable `PULLY_STABLE_HOST` is set, and setting it moves that host
to Cloudflare, so the steps below wait for Tavi:

1. Create the bucket (`wrangler r2 bucket create pully-android-stable`) and fill it from the
   server's Android directory: `PULLY_ANDROID_BUCKET=pully-android-stable
scripts/upload-android.sh <android-dir>`.
2. Worker secrets for `pully-stable`: the ones in `SERVER_ENVIRONMENT` (`src/lib.rs`) that
   today's server has, and `PULLY_PERSONAL_VAULT_KEYS` set to the VPS's own vault keys file, so
   the copied sign-ins still open.
3. Deploy once with `workflow_dispatch` on a host nobody uses yet, then copy the VPS data before
   anyone signs in: set the secret `PULLY_IMPORT_TOKEN` (32 or more random characters), run
   `node scripts/import-vps.mjs --url https://<host> --server-data <PULLS_REVIEW_DATA_DIR>
--public-data <PULLY_PUBLIC_DATA_DIR>` on the VPS to see the counts, again with `--send`,
   then `wrangler secret delete PULLY_IMPORT_TOKEN`, which turns the import route off and
   restarts every object. The import never replaces a record the Worker already has, except the
   sign-in store. Claude Code and Codex logins are not copied; each person signs in again in
   Settings, AI.
4. The Access application for the host with today's policy and the same bypass for
   `/api/public/` and `/api/personal/`, then `PULLY_STABLE_HOST`.
