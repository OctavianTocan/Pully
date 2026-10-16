---
name: post-diff-to-pully
description: Post a diff to Pully and hand the person a link that shows it like a pull request, with your own grouping, tour and review. Use when someone wants to look over changes you made, or asks for a Pully link.
---

# Post a diff to Pully

Pully shows a diff the way it shows a pull request: files grouped by what they do, a guided
tour, review findings on the lines, and Ask. Pages that are not real pull requests leave out
what they do not have (branches, checks, commits). You post the diff, Pully answers a link,
and you give the person that link. Only the account's owner can open it; they sign in.

## What you need

- `PULLY_API_KEY`: a key that starts with `pully_`. The person makes one in the Pully app,
  Settings, Agent keys, and gives it to you. Never print it, commit it or put it in a file.
- `PULLY_URL`: the server, such as `https://prs.octaviantocan.com`. Ask for it if you do not
  have it.

Every call sends `Authorization: Bearer $PULLY_API_KEY`. Without a valid key every call
answers 401.

Check the key first:

```sh
curl -sS "$PULLY_URL/api/v1/me" -H "Authorization: Bearer $PULLY_API_KEY"
# {"account":"tavi@example.com","key":"Claude Code"}
```

## Post a diff

The quickest post sends the patch itself as the body, with the title in a header:

```sh
git diff main...HEAD | curl -sS "$PULLY_URL/api/v1/diffs" \
  -H "Authorization: Bearer $PULLY_API_KEY" \
  -H "Content-Type: text/x-diff" \
  -H "X-Pully-Title: Retry failed GitHub calls" \
  --data-binary @-
```

`X-Pully-Title` and `X-Pully-Author` are URI-encoded, so encode anything that is not plain
ASCII. Use `--data-binary`, never `-d`, which strips the newlines a patch needs.

It answers `201`:

```json
{ "id": "a1B2c3D4e5F6", "url": "https://prs.octaviantocan.com/-/a1B2c3D4e5F6", "version": 1, "files": 3, "warnings": [] }
```

Give the person `url`. Read `warnings`: they say what was left out and why.

The patch is a unified diff with `diff --git` headers, as `git diff` writes it. Untracked
files are not in `git diff`; add them with `git add -N <path>` first, or use
`git diff --cached` after staging.

## Post with your own grouping, tour and review

Send JSON instead, with the patch in `patch`. Everything but `patch` is optional, and you
can send any of these on their own. Build the body with a tool such as `jq`, so the patch is
escaped correctly:

```sh
git diff main...HEAD > /tmp/change.patch
jq -n --rawfile patch /tmp/change.patch '{
  patch: $patch,
  title: "Retry failed GitHub calls",
  description: "Calls to GitHub now retry three times with backoff.\n\nMarkdown is fine here.",
  author: "Claude Code",
  groups: {
    overallSummary: "Adds a retry helper and uses it for every GitHub call.",
    groups: [
      { key: "retry", label: "Retry helper", category: "core", summary: "Why it backs off.", filePaths: ["src/retry.ts"] },
      { key: "docs", label: "Docs", category: "docs", filePaths: ["README.md"] }
    ],
    files: [{ path: "src/retry.ts", summary: "Wraps a call and retries it on 5xx.", hunks: [{ index: 0, note: "The backoff doubles each try." }] }]
  },
  tour: {
    title: "How the retry works",
    overview: "Two stops: the helper, then where it is used.",
    stops: [
      { title: "The helper", purpose: "What retries and when.", files: ["src/retry.ts"], takeaway: "Only 5xx retries.",
        sources: [{ path: "src/retry.ts", line: 4, why: "The backoff." }] }
    ]
  },
  review: {
    summary: "One thing to look at.",
    findings: [
      { path: "src/retry.ts", line: 12, severity: "risk", title: "No cap on the wait", body: "A long outage waits forever.", suggestion: "  return Math.min(wait, 30_000)" }
    ]
  }
}' | curl -sS "$PULLY_URL/api/v1/diffs" \
  -H "Authorization: Bearer $PULLY_API_KEY" \
  -H "Content-Type: application/json" \
  --data-binary @-
```

What each field is:

- `title` (up to 200 characters), `description` (Markdown, up to 20,000), `author` (who
  posted it, up to 80; the key's name when left out).
- `groups`: how the files are grouped on the page. Each group has a `key`, a `label`, a
  `category` (one of `ui`, `api`, `core`, `data`, `cli`, `security`, `tests`, `docs`,
  `examples`, `deps`, `build`, `scripts`, `config`, `i18n`, `assets` or `other`) and its
  `filePaths`; it MAY have a Markdown `summary` and one level of `children`. Put every file in
  exactly one group. `files` holds a note on what each file does, with optional `hunks`
  notes by the hunk's 0-based `index` in that file. Without `groups`, Pully groups the files
  itself.
- `tour`: a walk through the change, stop by stop. Each stop has a `title` and MAY have a
  `purpose`, `files`, a `takeaway` and `sources` (`path`, `line`, `why`) it points at.
- `review`: findings shown on their lines. Each has a `path`, a `line` (in the new file;
  `side: "deletions"` for a removed line), a `title`, and MAY have a `body`, a `severity`
  (`bug`, `risk` or `nit`), a `startLine` for a range and a `suggestion` (the replacement
  lines).

Paths are the ones in the patch, exactly. A path the diff does not have is dropped with a
warning; a part that is not the right shape is left out with a warning; the post still goes
through.

## Update a post

`PUT` to the same id sends a new version. Send only what changed: fields you leave out stay
as they were. The link stays the same, and the version goes up only when the patch changes.

```sh
git diff main...HEAD | curl -sS -X PUT "$PULLY_URL/api/v1/diffs/a1B2c3D4e5F6" \
  -H "Authorization: Bearer $PULLY_API_KEY" -H "Content-Type: text/x-diff" --data-binary @-
```

## The other calls

- `GET /api/v1/diffs`: your account's posts, newest first.
- `GET /api/v1/diffs/<id>`: one post, with its patch and fields.
- `DELETE /api/v1/diffs/<id>`: removes it for good; its link stops working. Only when asked.

## When it says no

Every refusal is JSON `{ "error": "..." }` that says what to do.

- `401`: no key, or the key was revoked. Ask for a new one.
- `400`: no patch, or a JSON body that is not an object.
- `413`: the patch is over 5 MB, the request over 8 MB, or it changes more than 2,000 files.
  Post less: one area at a time.
- `422`: not a diff, no changed files, or an added line that looks
  like a secret (a token, a private key, an `.env` file). The error names the file and line.
  Take the secret out and post again. Only if you are sure it is not a real secret, send
  `"allowSecrets": true` (or the header `X-Pully-Allow-Secrets: true`).
- `429`: more than 120 posts from this key in an hour. Wait the seconds in `Retry-After`.

## Good posts

- One post per change the person should look at; `PUT` new versions as you revise it,
  rather than posting again.
- Write the title as the change does it ("Retry failed GitHub calls"), not as a task.
- Group by what the change does, not by folder, and keep tours to the stops that matter.
- After posting, give the person the `url` and nothing else from the answer.
