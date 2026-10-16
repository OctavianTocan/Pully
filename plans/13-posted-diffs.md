# Plan: posted diffs (an agent posts a diff, gets a Pully link)

Status: **built** on branch `claude/project-thread-xfrvu7` (Android 0.43.0). Started 16 Oct 2026 from Tavi's
message (12:24): "an endpoint where agents can post a diff, and they get back a link ... like a PR showcase using our
stuff, but the diff is just what they gave you ... we hide elements like the branches and so on that are not actually
present." Revised the same day with his decisions (12:40 and 12:47), which are quoted under Decisions.

What the build leaves out of this plan, for later: the agent's read-only line `notes` (its `review` findings take
that place), a key that may start AI runs (`then`), and `GET /api/v1/skill` (the skill is
`skills/post-diff-to-pully/SKILL.md` in the repo). Posted diffs do not open on the web at all (Tavi, 16 Oct 14:17): `/-/<id>` in a browser
only says to open it in the Pully app.

## What it is

Tavi signs in to Pully and makes an API key. He gives the key to an agent (Claude Code,
Codex, a CI job). The agent has a skill that teaches it the API. When it has a change
ready, it posts the patch, plus anything it knows about the change, and gets back a link:

```sh
git diff main... | curl -sS "$PULLY_URL/api/v1/diffs" \
  -H "Authorization: Bearer $PULLY_API_KEY" \
  -H "Content-Type: text/x-diff" \
  -H "X-Pully-Title: Retry GitHub calls on 502" \
  --data-binary @-
# {"id":"k3T9xQ2mLp0a","url":"https://prs.octaviantocan.com/-/k3T9xQ2mLp0a"}
```

The link opens the Pully pull request page for that diff in the phone app, signed in to the
account that owns it; on the web it only says to open it in the app. The diff also shows up in that account's list of pull
requests, marked as posted. It stays until someone deletes it.

## Decisions (Tavi, 16 Oct 12:40)

- **Accounts.** Pully gets accounts, kept as simple as possible: "we make an account for
  me ... that doesn't mean that we connect GitHub". There is one account, seeded from the
  environment. There is no sign-up.
- **Sign-in.** "a really simple login with email and password". Links are "not publicly
  available to anyone ... you have to log in into your account".
- **API keys.** "I want is to be able to create an API key ... give the API key to your
  agent, and it can use our API." Plus "a skill that teaches agents how to use said API".
- **The list.** Posted diffs "show up later in the PR list ... and just mark them somehow
  for now".
- **The domain.** prs.octaviantocan.com, but it comes from an environment variable.
- **Lifetime.** "Link is actually forever", because the diff belongs to the account.
- **What an agent may send.** Its own grouping and tour, "and whatever else", as long as
  the skill teaches it.
- **Storage.** Tavi asked: "do we need to use R2 for this? ... could we just store this in
  the database and just store what the agent gave us as opposed to the final result and
  then ... recalculate it?" The answer is yes. See Storage.

And at 12:47, answering the first draft:

- **The phone signs in.** "It actually should get a sign in screen, but once you sign in
  it's just signed in ... later on we will add Google sign in". This changes the root
  contract `phone-api-no-login`, by his decision.
- **Keys from the phone.** "make sure this is possible from the phone ... most of what I
  care about is actually the phone app."
- **Settings sync.** "we can also start saving settings, both on the mobile app and also
  sync it with the web app. Though I don't really care as much about the web app."
- **No chips.** "we are definitely not adding any more chips ... You just group them,
  maybe somewhere specific, and you can maybe color the group".
- **Where it runs.** On the same server as the rest of Pully, wherever that is.
- **Access.** "the main app is still behind Cloudflare access. Nothing changes about that
  ... when the agents need to do something, they post to an endpoint that is not ...
  hidden by Cloudflare access. But that endpoint doesn't really allow them to do anything
  unless they pass a valid API key."

## Accounts and sign-in

- **One account, seeded.** On start the server reads `PULLY_ACCOUNT_EMAIL` and
  `PULLY_ACCOUNT_PASSWORD`. If no account exists for that email, it creates one. If the
  password in the environment changed, it updates the stored hash. There is no sign-up
  route, and there is no way to create a second account except through the environment.
  These values are server secrets, like the TypeSafe key, so they live in a private
  systemd drop-in on the server's machine and as Worker secrets on Cloudflare
  (`secrets-come-from-the-environment`).
- **The password is stored only as a hash**: PBKDF2-SHA-256 through WebCrypto, with a
  random salt, so the same code runs in Node and in a Worker. `host.ts` stays free of
  Node builtins.
- **Signing in.** A sign-in page (email, password, one button) is drawn with the phone's
  kit, like the rest of the web. It is served by the server's own web build (the `PR_LOCAL`
  page devframe already serves), so the public pulls.review site stays static
  (`web-is-static` unchanged).
- **The session.** A random token goes in an `HttpOnly`, `Secure`, `SameSite=Lax` cookie.
  The server stores only its hash. A session lasts 30 days and each visit renews it.
  Signing out deletes it.
- **Guessing.** After 5 wrong passwords in 15 minutes, for that email or that IP, sign-in
  waits 15 minutes. A wrong email and a wrong password get the same answer.
- **The phone signs in once.** With no session saved, Pully opens on a sign-in page
  (email, password). After that it stays signed in until Sign out in Settings. The session
  token rides on every phone API call as `X-Pully-Session`, next to the Access headers.
  The methods that belong to the account (keys, settings, posted diffs) need it; the rest
  of the phone API is unchanged and stays behind Access. The sign-in page is shaped so
  Google sign-in can join it later as a second button.

## API keys

- **Making one.** In Settings on the phone, under Agent keys,
  Tavi names a key ("Claude Code on my laptop") and gets `pully_` followed by 32 random
  characters. It is shown once. The server keeps only its SHA-256 hash, its name, when it
  was made and when it was last used.
- **Revoking.** A key can be revoked. Diffs it posted stay, because they belong to the
  account, not to the key.
- **Using one.** Every `/api/v1/*` call needs `Authorization: Bearer <key>`. A missing,
  unknown or revoked key gets `401` with one plain sentence. A key acts as its account and
  can do nothing else: no phone API, no GitHub token, no AI unless allowed (see AI).
- **Limits per key** stop a runaway agent loop: 120 posts an hour, and a patch of 5 MB at
  most. Going over gets `429` with when to retry.

## The API (`/api/v1`)

| Call                        | What it does                                                                                                                                                         |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /api/v1/diffs`        | Body is the patch (`text/x-diff`), or JSON `{ patch, title?, description?, author?, groups?, tour?, brief?, review?, notes? }`. Answers `201 { id, url, warnings }`. |
| `PUT /api/v1/diffs/<id>`    | Posts a new version of the same link, like a push to a pull request. Same body.                                                                                      |
| `GET /api/v1/diffs/<id>`    | What was posted, so an agent can read back its own post.                                                                                                             |
| `GET /api/v1/diffs`         | The account's posted diffs, newest first.                                                                                                                            |
| `DELETE /api/v1/diffs/<id>` | Removes it.                                                                                                                                                          |

- `url` is `PULLY_PUBLIC_URL` (prs.octaviantocan.com today) plus `/-/<id>`. A GitHub login
  can't be `-`, so the path never collides with the web's `/:owner/:repo` routes.
- The id is 12 random base62 characters, not a hash of the patch, so two posts of the same
  patch are two diffs and an id can't be guessed from a known patch.
- A patch that doesn't parse into at least one file is refused with `422`. Other limits:
  2,000 files, a title of 200 characters, a description of 20,000.
- Secrets: agents will post `.env` changes by accident. A post is checked for the common
  token shapes (GitHub, AWS, Stripe, OpenAI, Anthropic, private key blocks, `.env` files).
  A match is refused with `422`, naming the file and line but never the match, unless the
  post says `allowSecrets: true`. The links are private now, but this check still matters,
  because a diff is copied wherever an AI job reads it.

## What the agent may send ("whatever else")

Everything the agent sends is checked against the schema Pully already has for that thing.
Anything that fails the check is dropped and listed in `warnings`; the diff still posts.
Pully credits what the agent sent to the poster, the way it credits a shared analysis today
(plan 07).

| Field                  | Shape                                                                                                  | Shows as                                                                   |
| ---------------------- | ------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| `title`, `description` | text, Markdown                                                                                         | the PR title and description                                               |
| `author`               | a display name                                                                                         | the author line, with no avatar                                            |
| `groups`               | `GroupedResult` (`core/types/analyze.ts`), checked by `resolveGroups` so it can't invent or drop paths | the AI grouping and its summaries                                          |
| `tour`                 | `TourResult` (`core/types/tour.ts`); stops must name files in the diff                                 | the tour                                                                   |
| `brief`                | OpenUI Lang, run through the brief parser (`brief.ts`)                                                 | the brief                                                                  |
| `review`               | AI review findings, in the critique shape                                                              | the review findings, each linked to a line                                 |
| `notes`                | `{ path, line, body }[]`                                                                               | the agent's own comments on lines, drawn like review threads but read-only |

Links written in any of these must be `https` or relative to the diff
(`server-ai-writes-links`). Any of these can be sent again with a `PUT` to update it.

## What the page shows and hides

Most of the hiding is already in place. A `DiffsPayload` with no `base`, `head`, `commits`
or `pullRequest` is already a valid payload (a paste is one). The web header draws branch
chips only when both exist (`DiffsHeader.vue:116`). The phone loads the overview, review
threads and stack only for a `Pull` target (`ReviewSession.kt:222`).

| Part of the page                                              | Posted diff                                                                                  |
| ------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Title, description, author                                    | From the post                                                                                |
| `#123` label                                                  | Nothing; the author line names the key                                                       |
| Branches, checks, commits sheet, commit picker, stack         | Hidden                                                                                       |
| Conversation, GitHub threads, approve, request changes, merge | Hidden. The agent's `notes` show where the threads would be.                                 |
| New-commits banner                                            | "A newer version was posted", when a `PUT` arrived since the page opened                     |
| Open on GitHub, Load full file                                | Hidden. The first is already gated on `refKind`, and `file-content` already refuses a paste. |
| File list, groups, diff, stats, reviewed marks, Jev           | Work as they do today                                                                        |
| Tour, brief, review, Ask                                      | What the agent sent, or what Pully ran                                                       |

## The list

- Posted diffs appear in the Inbox as a group of their own, headed "Posted by agents", above
  the repositories, with its heading in its own colour. No chip and no filter is added.
  Each row reads like a pull request row: the title, and the key's name where a pull
  request shows its author.
- They come from a new method, `posted-list`, which both clients merge into the list. They
  don't come from `my-pulls`, because that answer stays an array of GitHub pull requests
  (architecture, Jev).
- Delete is in the PR sheet's actions.

## Storage: no R2

Pully stores what the agent gave, and works out the rest each time the diff opens:

- **Stored:** the raw patch text (gzip-compressed), the metadata (title, description,
  author, key, version, created) and the agent's fields as sent. These go in the server's
  own store, the same store that holds pull requests and reviews today. On the computer it runs on today that
  is files under `<git-common-dir>/pulls-review`. On Cloudflare it becomes the Durable
  Object's SQLite database (plan 12, step 4).
- **Worked out on every open:** the parsed files (`parsePatch`, the same parser paste and
  local use) and the rule-based groups. Today's rule already says rule-based groups are
  recomputed and never stored. Both are cheap.
- **Also stored:** anything Pully pays for itself, such as a tour or analysis Tavi starts
  on a posted diff, the Ask threads and the reviewed marks. Recomputing those would mean
  paying for them again.
- **Why R2 isn't needed:** gzip shrinks a patch 5 to 10 times, so even the 5 MB limit
  stores in about 1 MB. The only ceiling is on Cloudflare, where one SQLite value holds at
  most 2 MB, so a patch that is still larger after gzip is split into numbered chunks.
  R2 only earns its place if posts grow to tens of megabytes, which the 5 MB limit rules
  out.
- **Kept out of the PR cache's budget.** Posted diffs live under their own `posted:`
  prefix, outside `SERVER_CACHE_BUDGET`, which evicts past 400 entries. A link is meant to
  last forever, and a burst of posts must not push pull requests out.

## The data model

- `SourceRef` gains `{ kind: 'posted', id }`, with the key `posted:<id>`. `paste` isn't
  reused, because a paste is keyed by its content hash and deliberately has no route,
  while a posted diff has an owner, a route and versions.
- `createPostedSource(id)` reads the stored patch and parses it. Its `fingerprint()` is
  the version number, so the existing staleness path shows "a newer version was posted".
  Reviewed marks are keyed by file `sha`, so they survive on files that didn't change.

## AI that Pully runs

A tour, analysis or Ask that Pully runs on a posted diff uses the server's engines and
Tavi's subscriptions:

- **Who may start one.** Only a signed-in session or the phone. An API key can't, unless
  Tavi turns on "may start AI" for that key. Then the post may say `then: ["tour"]`, the
  way an analysis already chains its tour and brief.
- **Which tools it gets.** There is no repository, so the job gets the diff tools only:
  `read_file_diff`, `read_hunks`, `find_files` over the diff's paths, `search_diff`, and
  `triage_files` when Jev ran. `read_source`, `search_source` and `read_discussion` are
  left out of the list, and the instructions say there is no repository to read.
  `agents-act-through-pr-tools` holds as it is.

## Where it runs

- **On the same server as the rest of Pully.** Everything goes through `ServerHost` (plan
  12, step 1), so it runs wherever the server runs and moves with it.
- **Cloudflare Access.** Only the agent API (`/api/v1/*`) is outside Access, and it answers
  nothing without a valid key. The phone API, the sign-in page, the link pages and the
  rest of the web page stay behind Access as today.
- **Environment.** `PULLY_PUBLIC_URL`, `PULLY_ACCOUNT_EMAIL` and `PULLY_ACCOUNT_PASSWORD`.
  With no account set, the agent API and the sign-in page answer `404`, and nothing else
  changes.

## The phone

- **Links open in Pully.** Add `/-/.*` on the `PULLY_PUBLIC_URL` host to the VIEW filter
  in `AndroidManifest.xml`. prs.octaviantocan.com is already listed there, and the comment
  there explains the one-`.*`-per-segment trap. Also add `OpenTarget.Posted(id)` in
  `PrLinks.kt`, its parse in `PrLinks.parse` and `HomeLinks`, and cases in `PrLinksTest`
  and `LinkFilterTest`.
- **Loading.** `Repo.open` calls a new native call, `posted-diff`, next to `local-diff`.
  It answers `{ key, diff, groups }` the way `openLocal` does, plus the agent's fields.
- **The Inbox** gets the Posted by agents group. Settings gets Account (sign out) and Agent keys.
- **Settings sync.** Every setting in `AppSettings` (theme, colours, language, fonts, sounds,
  layout and the rest) is saved to the account with the time it changed. Signing in, or
  opening the app, takes the newer value of each one; a change on the phone is sent at
  once. The server address and Access keys stay on the phone, since they are needed to
  reach the server at all.

## The skill

`post-diff-to-pully`, kept in the repo with the other skills, and also served as plain
text at `GET /api/v1/skill`, so an agent can fetch it with the same key. It covers:

- when to post: a change is ready for a human to look at;
- the `curl` line and the environment it expects (`PULLY_URL`, `PULLY_API_KEY`);
- each field from "What the agent may send", with a small valid example of each;
- that every place it names must be a link relative to the diff;
- to `PUT` again after more work, rather than post a new diff;
- to give the user the `url`.

`pulls.review post [git target]` in the CLI does the same from a shell. It runs the pinned
`git diff` the local source uses and prints the link.

## Steps

Each step is one pull request.

1. **Account, sign-in, keys.** The account seed, password hash, session cookie, the
   sign-in page, Agent keys in web Settings, the guessing limit, and contracts for each.
   Tested over an in-memory host, as `native-api.test.ts` does.
2. **The agent API and storage.** The `posted` ref, `createPostedSource`, `/api/v1/diffs`,
   the limits, the secret check, and the agent's fields checked and stored.
3. **The web page.** `/-/<id>` opens the posted diff (the web matters less to Tavi).
4. **The phone.** Sign-in, Agent keys, settings sync, the link filter, `OpenTarget.Posted`,
   `posted-diff`, the Posted group in the Inbox. Android version bump with a changelog
   entry and pictures.
5. **The skill and `pulls.review post`.**
6. **Pully's AI on posted diffs.** The diff-only tool set, and "may start AI" per key.

Steps 1 to 3 together are the first thing an agent can use end to end. The Access bypass
is needed before an agent outside Tavi's network can post.
