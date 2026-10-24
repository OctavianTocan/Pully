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

What does not yet: AI work, which needs Claude Code and Codex in a container of their own.
Those calls fail closed: the server's `rt::fs` and `rt::process` refuse every call in the
Worker.

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

## Deploy (after Tavi's go-ahead)

1. Copy today's server data in (a one-off import, still to write), and create the bucket with
   `wrangler r2 bucket create pully-android`, then run `scripts/upload-android.sh <android-dir>`.
   Each Android publish then needs the same upload until `android/scripts/publish-apk.sh` does it.
2. Secrets: `wrangler secret put PULLS_REVIEW_GITHUB_TOKEN`, and the others in
   `SERVER_ENVIRONMENT` (`src/lib.rs`) that today's server has.
3. Add the hostname to `wrangler.jsonc` (`routes` or a custom domain) and an Access
   application for it with the same policy as today's server.
4. `pnpm -F @pulls.review/worker run deploy`.
