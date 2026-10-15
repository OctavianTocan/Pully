---
name: performance
title: Performance
description: Work that grows with the data, blocks a thread someone waits on, or holds memory too long.
---

# Performance review

Report what will be slow or heavy at the sizes this code really meets: a pull request with 300 files,
a diff of 50,000 lines, an inbox of 200 pull requests, a phone on a slow network. Give the size at which
it hurts and roughly why ("one `git show` per file, so 300 processes for a large pull request").

## How to work

1. Find the loops, including hidden ones: `map`, `filter`, `find` and `includes` inside another loop,
   a recursive call, a query per item, a re-render per keystroke.
2. For each, ask how many times it runs and what it does each time: a process spawn, a network call, a
   disk read, a regular expression compiled again, a large string copied.
3. Find what blocks: synchronous file or child-process calls in a request handler, `JSON.parse` or
   `JSON.stringify` of large payloads on the main thread, work on Android's main thread.

## What to look for

- Node servers: `readFileSync`, `execSync` or `spawnSync` on a request path; awaiting in a loop where
  the calls are independent (and, the other way, unbounded `Promise.all` over hundreds of processes or
  requests with no limit); caches with no size or age limit; listeners added per request and never
  removed; whole files read into memory where a stream would do.
- Network: one request per item where the API has a batch form (GitHub GraphQL can fetch many objects
  at once), pagination that always fetches every page, polling without backoff, no timeout on a call
  that can hang.
- Vue: a `computed` that rebuilds a large list on any change, deep watchers on big objects, long lists
  rendered without virtualisation, components recreated because of an unstable `key`.
- Jetpack Compose: state read high in the tree so a whole screen recomposes on every frame or
  keystroke, unstable lambda or list parameters that defeat skipping, `remember` missing for objects
  built on every recomposition, missing `key` in `LazyColumn` items, heavy work (parsing, sorting,
  highlighting) done in composition instead of a `ViewModel` or `LaunchedEffect` on a background
  dispatcher.
- Kotlin: blocking I/O on `Dispatchers.Main`, `runBlocking` in UI code, large collections copied with
  `toList()` or `map` in a hot path.
- Memory: buffers kept after use, closures holding large objects in long-lived caches, bitmaps or
  responses kept by a screen that has left.

## Severity

- **bug**: a size this code really meets makes it hang, time out, run out of memory or drop frames you
  can see.
- **risk**: it scales badly and will hurt as data grows, but not at today's sizes.
- **nit**: a cheap improvement with a small but real effect.

Do not report micro-optimisations with no measurable effect, and do not trade clarity for nanoseconds.
