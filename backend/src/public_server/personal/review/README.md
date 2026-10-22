# Personal review reads

These fixed GET routes are installed only by the explicitly configured public host.
They require the current canonical Pully session's `privateRepositories` capability;
public sessions, anonymous browsing and legacy credentials do not authorize them.
The existing public and account inventory wire is unchanged.

The base path is
`/api/personal/v1/github/account/repositories/{owner}/{repo}/pulls/{number}/review`.
Opening requires `repositoryId` from the selected verified repository response.
The response contains the existing account/GitHub/authority envelope, its account
repository metadata and this target:

```ts
interface PersonalReviewTarget {
  repositoryId: number
  pullId: number
  number: number
  baseSha: string
  headSha: string
  private: boolean
}
```

The SHAs are complete 40- or 64-digit hexadecimal object IDs, normalized to lowercase.
`baseSha` pins the PR's base ref; the canonical diff's `base.sha` remains its merge
base. Follow-up requests require `repositoryId`, `pullId`, `baseSha`, `headSha` and
`private` in the query, with the PR number still in the path. Each repeats the same
envelope/target and adds `value`:

- `/overview`: the canonical PR overview result, including actual viewer permissions.
- `/diff`: the canonical complete source-diff snapshot.
- `/files`: canonical `{ old, new }` file content; also requires relative `path`.
- `/reviews`: canonical review threads, summaries and the caller's pending review.

File paths retain exact case/Unicode and are limited to 4096 UTF-8 bytes. Leading
slashes, controls, empty components, `.` and `..` are rejected before transport.
Unknown query fields, body-bearing requests, cookies and Access/legacy headers
are rejected. These are fixed reads, never a caller-controlled RPC or GitHub proxy.

The route has a 45-second deadline and shares the existing per-binding three-read
GitHub concurrency/hourly budget. Four review operations may run at once across the
listener to bound large decoded snapshots. Each upstream body and encoded response is bounded
to 32 MiB; nested requests additionally share a 64 MiB/128-request operation budget.
The established two-MiB inventory boundary is retained. Every nested request and
final publication checks the original session, repository and exact PR/visibility
leases. Confirmed denial or replacement retires older readers for that binding.
No private AppState diff, overview or blob cache is selected through these loaders.

Errors use the existing safe code envelope. Changed PR/visibility pins return
`staleReview` with HTTP 409; lost repository access/identity returns `forbidden` with 403. No credential or raw upstream error appears in responses. A client should reopen
the selected PR after 409 and retire a denied source. Responses are `no-store` and
`no-referrer`.

Viewer flags describe GitHub permissions. This read slice implements no comment,
review or merge write route, and grants no AI authority. AI eligibility and provider
credentials remain independent of GitHub sign-in. Live OAuth registration/TLS is a
deployment prerequisite; loopback fixtures exercise the actual configured host,
exchange, claim/confirmation, session and canonical read path without provisioning.
