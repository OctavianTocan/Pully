---
name: api-and-contracts
title: APIs and contracts
description: Breaking changes to types, wire formats, routes and saved data that others depend on.
---

# API and contract review

Find where this change alters something another party relies on: a caller in another package, the web
app, the Android app, an older version of either that is still installed, data already saved on disk,
or a third-party API. For each finding, name the party that breaks and how.

## How to work

1. List every boundary the diff touches: exported functions and types, RPC method names and their
   request and response JSON, HTTP routes, CLI flags and environment variables, file formats and cache
   keys, database or storage schemas, events and messages.
2. For each boundary, compare before and after: what was required, what was optional, what each field
   meant, what errors were possible. The tools can show the old file at the base commit.
3. Find the other side of each boundary in the repository (search for the method name, the field name,
   the route) and check that it was updated. A shared package such as `core`, the server and the
   Android `Repo` are typical pairs that must change together.

## What to look for

- A field renamed, removed or made required in a response or request, without the reader on the other
  side tolerating both shapes. Phone apps update later than servers, so the server must keep answering
  older clients, and the client must tolerate fields it does not know and fields that are missing.
- A type that widens (a new union member, a new enum value, a new status string) without every
  `switch`, `when` and lookup table on the reading side handling it.
- `null` versus missing versus empty: a field that was absent now comes as `null`, or `[]` now means
  something different from "not loaded".
- Validation schemas (valibot, zod, kotlinx.serialization models) that no longer match what is sent.
- Numbers, dates and ids that change unit, time zone, base (0- or 1-based) or type (number to string).
- Cache keys or storage keys whose meaning changed while old entries are still read back.
- Defaults that changed silently, such as a flag that used to be off and is now on.
- Exported names removed or renamed without the callers in other packages updated, and public behaviour
  changed under the same name.
- Error contracts: a function that used to return `undefined` now throws, or the other way round; error
  messages or codes that a caller matches on.
- Docs, help text and examples that now describe the old behaviour.

## Severity

- **bug**: a party you can name breaks with this change as it stands.
- **risk**: it breaks for an older client, old saved data or a caller outside the repository.
- **nit**: the contract still holds but is now unclear, such as an undocumented new field.

Purely internal refactors with every caller updated are not findings.
