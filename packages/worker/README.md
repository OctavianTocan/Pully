# @pulls.review/worker

The hosted Pully server on Cloudflare. The Worker sends every request to one `PullyServer`
Durable Object, which runs the Rust server (`docker/server/Dockerfile`) in a Cloudflare
Container and keeps it awake. The server's data folder lives in the R2 bucket
`pully-server-data` under `data/`: `pully-container` restores it before the server starts
and saves changes about two seconds after they happen, through the `data.pully` outbound
handler in `src/data.ts`. See `plans/12-hosted-server.md` (Update, 24 October 2026).

Nothing is deployed yet. The config has no route on purpose: the hostname goes in together
with its Cloudflare Access policy.

## Try it locally

Needs Docker. Wrangler builds the image from the repository root on the first `dev`:

```sh
pnpm -F @pulls.review/worker dev
curl http://localhost:8787/health
```

Behind a TLS-inspecting proxy, build the image first with
`docker build --secret id=build-ca,src=<ca file> -f docker/server/Dockerfile -t pully-server:dev .`
and point a local copy of `wrangler.jsonc` at a Dockerfile that only says `FROM pully-server:dev`.

## Deploy (after Tavi's go-ahead)

1. `wrangler r2 bucket create pully-server-data`, then copy today's server data in under
   `data/`: the `--data-dir` folder to `data/store/`, the analytics folder to
   `data/analytics/` and the Android folder to `data/android/` (any S3 tool with an R2 API
   token, for example `aws s3 sync <folder> s3://pully-server-data/data/store/`).
2. Secrets: `wrangler secret put PULLS_REVIEW_GITHUB_TOKEN`, and the others in
   `SERVER_ENVIRONMENT` (`src/index.ts`) that today's server has.
3. Add the hostname to `wrangler.jsonc` (`routes` or a custom domain) and an Access
   application for it with the same policy as today's server.
4. `pnpm -F @pulls.review/worker run deploy`.
