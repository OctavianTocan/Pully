---
name: security
title: Security
description: Places where an attacker or a bad input can read, change or run what it should not.
---

# Security review

Find the places where data from outside reaches something powerful. For every finding, name who
controls the input (a phone on the network, a pull request author, a repository's files, a model's
answer, an environment variable) and what they gain.

## How to work

1. List the inputs the change accepts: HTTP bodies and query strings, RPC arguments, file and branch
   names, the content of a diff, text written by an AI model, deep links and intents on Android.
2. Follow each one through the tools to where it ends up: a shell command, a file path, a SQL or
   GraphQL query, HTML, a URL that is fetched, a log line, a credential store.
3. Check what stands between the two: validation (valibot, zod or a hand-written guard), escaping, an
   allow list, or nothing.

## What to look for

- Command injection: `exec`, `execSync` or `spawn` with `shell: true` built from strings; arguments that
  can start with `-` and be read as a flag (pass `--` before them); `git` refs or paths taken from a
  request.
- Path traversal: a name from a request joined onto a folder without checking that the result stays
  inside it (`..`, absolute paths, symlinks, URL-encoded slashes).
- Injection into HTML: `v-html`, `innerHTML`, Markdown rendered without sanitising, SVG from an untrusted
  source, an `href` that can be `javascript:`.
- Server-side request forgery: a URL from the caller fetched by the server, or a redirect followed to an
  internal address.
- Secrets: tokens written to logs, error messages, URLs, command lines (visible in `ps`), files with
  loose permissions, a WebView, Android backups or `SharedPreferences` without encryption.
- Authorisation: an endpoint that changes state without checking who asked, a check done in the client
  only, an RPC that trusts an id the caller sends.
- Unsafe deserialisation and prototype pollution: `Object.assign` or spread of parsed JSON into an object
  that is later used as config, keys like `__proto__` or `constructor`.
- Prompt injection: text from a pull request or repository that is put into a model's prompt and can
  change what tools the model calls or what it writes back.
- Android: exported activities, receivers or providers without a permission, `PendingIntent` without
  `FLAG_IMMUTABLE`, cleartext traffic, trusting every certificate, intents built from deep-link data.
- Containers and scripts: images running as root, host folders mounted read-write when read-only would
  do, `curl | sh`, unquoted shell variables.
- Dependencies: a new package that is unmaintained, typo-squatted or pulls in a post-install script.

## Severity

- **bug**: an input you can name gets an attacker something today.
- **risk**: an exploit needs another mistake or an unusual setup, but the protection is missing.
- **nit**: hardening that costs little, such as a tighter permission or an extra `--`.

Do not report theoretical issues in code the diff does not touch.
