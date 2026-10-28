# @pulls.review/worker

The hosted Pully server on Cloudflare: the Rust server in `backend/`, built for Workers with
[workers-rs](https://github.com/cloudflare/workers-rs). The Worker sends every request to one
`PullyServer` Durable Object, which answers with the server's own router and keeps the
server's data in its SQLite storage. See `plans/12-hosted-server.md` (Update, 24 October 2026).

What runs here today:

- sign-in, agent keys, settings, posted diffs, and every phone method that needs only GitHub
  and storage;
- the 3-minute refresh of open pull requests, on the Durable Object's alarm (the first request
  sets it, and each run sets the next);
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

Nothing is deployed. The config has no route on purpose: the hostname goes in together with
its Cloudflare Access policy.

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
(`src/personal.rs`), the Worker's form of the native server's `--public-listen` listener.

`scripts/staging-config.mjs` adds the host as the Worker's custom domain for that deploy only;
`scripts/publish-staging-android.sh` builds the app and puts it under `staging/` in the bucket.

## Deploy (after Tavi's go-ahead)

1. Copy today's server data in (a one-off import, still to write), and create the bucket with
   `wrangler r2 bucket create pully-android`, then run `scripts/upload-android.sh <android-dir>`.
   Each Android publish then needs the same upload until `android/scripts/publish-apk.sh` does it.
2. Secrets: `wrangler secret put PULLS_REVIEW_GITHUB_TOKEN`, and the others in
   `SERVER_ENVIRONMENT` (`src/lib.rs`) that today's server has.
3. Add the hostname to `wrangler.jsonc` (`routes` or a custom domain) and an Access
   application for it with the same policy as today's server.
4. `pnpm -F @pulls.review/worker run deploy`.
