# Plan 14: a sync engine like Zero

Status: **built** (Android 0.71.0). Asked by Tavi on 28 Oct 2026 after a call with Omar, whose
app is fast because of [Zero](https://zero.rocicorp.dev) (Fireflies, 26 Oct 2026 19:26 call,
1:01:29: "I'm using Zero... It's a sync engine... It's not what Linear uses"). Tavi chose
"Build Zero's design ourselves" and said "Go" on the plan in thread "Build a sync engine like
Wholana's".

## Why not Zero itself

Zero's client is TypeScript only (its docs list native mobile apps under "when not to use
Zero") and its server, `zero-cache`, is a long-running Node container replicating Postgres.
Pully has native Kotlin apps and a Rust server whose store is a key/value `store::Records`
(files natively, Durable Object SQLite on Cloudflare), with no Postgres. PowerSync has a
Kotlin SDK but also needs Postgres and its own service.

## What Zero does, and where Pully does it

- **Reads come from a copy on the device.** Already so in Pully: the Inbox shows the device
  copy first, a review opens from the kept diff (`shared-home-cache-and-policies-stay-compatible`,
  `shared-review-opens-from-bounded-cache`).
- **The server pokes clients when their data changes.** `sync-wait` (`backend/src/sync.rs`):
  one change feed per account store, topics `inbox`, `marks`, `review:<key>`.
- **Clients read only what changed.** `followChanges` (`mobile/shared-client/.../SyncFeed.kt`):
  the Inbox reads the server's copy on `inbox`, a PR page its overview and saved work on its
  topic and its marks on `marks`.
- **Writes show first and are confirmed later.** `Outbox` (`.../Outbox.kt`) in
  `ReviewSessionCache`: marks go out in order, retried until the server has them, laid over
  server reads.
- **zero-cache keeps itself current from Postgres.** While a phone watches the Inbox,
  `sync-wait` refreshes a kept Inbox older than a minute from GitHub; the server's own
  3-minute warm round still runs.

## How it works

- `Store::write` and `Store::erase` call `Changes::note(key)` under the store lock. The
  feed is shared by every Store over the same records (the account's workspace stores too),
  keeps the newest version of up to 512 topics, and wakes waiters through a `watch` channel.
- `sync-wait { epoch, version, waitMs, inbox? }` answers `{ epoch, version, topics, reset }`
  once a topic changes after `version`, or after at most 25 s with no topics. A new server
  or Durable Object has a new epoch; its clients get `reset` and read everything once.
- It goes through `/api/rpc` and `/workspace/api/rpc` like every phone method, so the same
  allowlist, schemas, workspace checks and identity retirement apply. It is a long poll, not
  a WebSocket, so no new route or credential path was added. It is left out of call timings
  on both sides.
- The phone follows only while the screen is in front (Home's read observations, the review
  page's observation scope), stops at once on a server without the feed (404), and gives up
  after five failed waits until the screen next comes to the front.

## Measured

Local native server, debug build: a mark written by one client woke a waiting client in
4 ms median (20 marks, worst 164 ms). Before, an open Inbox or PR page never took a change
made elsewhere until it was reloaded or the app came back to the front.

## Left out, on purpose

- No device SQLite: the existing device copies are already source-scoped and bounded by
  contracts, and adding a database would not make reads faster than they are.
- No offline queue across app restarts: like Zero, writes need a connection to land; the
  outbox lives as long as the app.
- Image placeholders (Omar's image server) are not part of this: Pully's pictures are
  GitHub avatars and Markdown images served by GitHub.
- GitHub webhooks would make GitHub changes near-instant instead of within a minute; they
  need a webhook secret and are a separate ask.

Contracts: `server-sync-feed-names-only-own-changes`, `server-inbox-fresh-while-watched`
(backend), `sync-reads-what-changed`, `writes-show-first` (shared-client),
`home-inbox-follows-the-server`, `review-page-follows-the-server` (shared-ui).

## Pages read before the tap (0.73.0)

Asked by Tavi on 28 Oct 2026 at 15:36: "even the things that the user hasn't actually opened
yet load immediately", with everything from his account and repos already on the phone.

Measured first. The phone's own `pr.load` timings (125 opens, 19 to 21 Oct) took a median of
0.7 s when the server had the diff and 2.9 s when it read GitHub (90th percentile 5.4 s,
worst 9.8 s), and the overview, reviews and stack were read from GitHub on every open (1.1 to
2.2 s on the server, `pr-overview`, `review-data` and `pr-stack`).

- **Server** (`server-keeps-pull-request-pages`): `review-data` and `pr-stack` keep their last
  answer beside `pr-overview`'s (`backend/src/review/github/kept.rs`) and answer `cached: true`
  from it. The warm round reads them again only when a pull request's `updatedAt` moved. A
  diff the server does not have reads the comparison, files and commits together: 2.4 s
  became 1.4 s for the same reads in a test against GitHub.
- **Phone** (`pages-are-read-before-the-tap`): once the Inbox shows, the first 150 rows'
  pages (`PullPage`: diff, overview, reviews, stack) are read two at a time and kept;
  Android keeps them in files of the account's own (`FilePageCopies`, 400 pages, 200 MB;
  forty rows, sixty pages and 48 MB until Tavi asked for more on 28 Oct, 17:30).
  A page opens from its copy in the first frame: reading and drawing the largest one seen,
  450 KB with 86 files, took 14 ms (median, laptop JVM). Only rows whose head or `updatedAt`
  moved are read again.
- iOS keeps its earlier warm of the top four rows in memory: it has no file store yet.
