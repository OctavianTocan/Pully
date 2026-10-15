import type { Env } from '@pulls.review/core/env'
import type { MyPull, MyPullChecks, MyPullMergeable, MyPullReviewDecision, MyPullRole, MyPullsSnapshot, MyPullStack, MyPullState } from '@pulls.review/core/local-rpc'
import type { PullClassifier, PullClassifierStore } from './jev-pulls'
import process from 'node:process'
import { MY_PULL_ROLES } from '@pulls.review/core/local-rpc'
import { resolveGithubToken } from './credentials'
import { sharedJev } from './jev'
import { createPullClassifier } from './jev-pulls'
import { createSwrCache } from './swr'

const SEARCH_LIMIT = 100
// GitHub's GraphQL cost grows with every PR a request resolves these fields for, so
// many small requests in parallel finish far sooner than one big one (8 per request: ~2.5s for 200 PRs).
const ENRICH_CHUNK = 8
const ENRICH_CONCURRENCY = 16
const OWNERS_TTL_MS = 60 * 60_000
const TOKEN_TTL_MS = 10 * 60_000
/** `my-pulls` answers from the cache while it is this young. */
const FRESH_MS = 5_000
/** `my-pulls-cached` starts a background refresh once its copy is this old. */
const REVALIDATE_AFTER_MS = 30_000
/** Checks and review state can change without the PR's `updatedAt` moving, so reuse is time-boxed. */
const SETTLED_ENRICHMENT_TTL_MS = 10 * 60_000
const UNSETTLED_ENRICHMENT_TTL_MS = 60_000

/** Runs one GitHub GraphQL request; partial data is returned, a response without data throws. */
export type GithubGraphql = <T>(query: string, variables: Record<string, unknown>) => Promise<T>

const LIGHT_FIELDS = `
  id number title url state isDraft updatedAt mergedAt closedAt headRefOid
  baseRefName headRefName isCrossRepository
  repository { nameWithOwner defaultBranchRef { name } }
  author { login }
  comments { totalCount }
  labels(first: 10) { nodes { name } }`

const SEARCH_QUERY = `query($q: String!, $first: Int!) {
  search(type: ISSUE, query: $q, first: $first) { nodes { ... on PullRequest { ${LIGHT_FIELDS} } } }
}`

const ENRICH_QUERY = `query($ids: [ID!]!) {
  nodes(ids: $ids) { ... on PullRequest {
    id reviewDecision mergeable additions deletions changedFiles
    commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }
    reviewRequests(first: 10) { nodes { requestedReviewer { ... on User { login } ... on Team { combinedSlug } } } }
  } }
}`

/**
 * GitHub's own stacks (generally available since October 2026). A request of its own, never folded into
 * the ones above: a host whose schema lacks these fields fails it, and that only means "no native stacks".
 */
const NATIVE_STACK_QUERY = `query($ids: [ID!]!) {
  nodes(ids: $ids) { ... on PullRequest { id stack { number size baseRefName } stackEntry { position } } }
}`

const OWNERS_QUERY = `query { viewer { login organizations(first: 100) { nodes { login } } } }`

export interface SearchNode {
  id: string
  number: number
  title: string
  url: string
  state: 'OPEN' | 'CLOSED' | 'MERGED'
  isDraft: boolean
  updatedAt: string
  mergedAt: string | null
  closedAt?: string | null
  headRefOid: string
  baseRefName: string
  headRefName: string
  /** The head branch lives in a fork. */
  isCrossRepository?: boolean
  repository: { nameWithOwner: string, defaultBranchRef?: { name: string } | null }
  author: { login: string } | null
  comments: { totalCount: number }
  labels: { nodes: { name: string }[] } | null
}

export interface EnrichNode {
  id: string
  reviewDecision: 'APPROVED' | 'CHANGES_REQUESTED' | 'REVIEW_REQUIRED' | null
  mergeable: 'MERGEABLE' | 'CONFLICTING' | 'UNKNOWN'
  additions: number
  deletions: number
  changedFiles: number
  commits: { nodes: { commit: { statusCheckRollup: { state: string } | null } }[] }
  reviewRequests: { nodes: { requestedReviewer: { login?: string, combinedSlug?: string } | null }[] } | null
}

export interface NativeStackNode {
  id: string
  stack: { number: number, size: number, baseRefName: string } | null
  stackEntry: { position: number } | null
}

/** A search hit with the GraphQL node id the enrichment request needs. */
export interface FoundPull {
  id: string
  pull: MyPull
  /** Its head branch lives in a fork, so no pull request of the repository can sit on it. */
  crossRepository?: boolean
  /** The repository's default branch: a pull request based on it is the bottom of whatever it is in. */
  defaultBranch?: string
}

type Enrichment = Pick<MyPull, 'reviewDecision' | 'checks' | 'mergeable' | 'additions' | 'deletions' | 'changedFiles' | 'reviewRequests'>

interface EnrichmentEntry<T = Enrichment> {
  updatedAt: string
  headSha: string
  fetchedAt: number
  data: T
}

/** GitHub's own stack of one pull request, `null` when it is in none. */
export type NativeStack = { number: number, size: number, position: number } | null

/**
 * The GitHub search behind each role.
 *
 * @param state Open, or closed including merged.
 * @param owners Logins whose repositories count as owned; the owned search is skipped without any.
 * @returns Role and search query pairs, most specific role first.
 */
export function searchQueries(state: MyPullState, owners: string[]): [MyPullRole, string][] {
  const base = `is:pr is:${state} sort:updated-desc`
  const queries: [MyPullRole, string][] = [
    ['review-requested', `${base} review-requested:@me`],
    ['authored', `${base} author:@me`],
    ['involved', `${base} involves:@me`],
  ]
  if (owners.length)
    queries.push(['owned', `${base} ${owners.map(owner => `user:${owner}`).join(' ')}`])
  return queries
}

/**
 * @param node A pull request from the search.
 * @param role The search that found it.
 * @param state The state that was searched for.
 * @returns The list entry, without the fields only enrichment provides.
 */
export function toFoundPull(node: SearchNode, role: MyPullRole, state: MyPullState): FoundPull {
  const [owner = '', repo = ''] = node.repository.nameWithOwner.split('/')
  return {
    id: node.id,
    crossRepository: node.isCrossRepository,
    defaultBranch: node.repository.defaultBranchRef?.name,
    pull: {
      owner,
      repo,
      number: node.number,
      title: node.title,
      author: node.author?.login ?? 'ghost',
      isDraft: node.isDraft,
      updatedAt: node.updatedAt,
      commentsCount: node.comments.totalCount,
      labels: node.labels?.nodes.map(label => label.name) ?? [],
      state,
      role,
      url: node.url,
      merged: node.mergedAt !== null,
      closedAt: state === 'closed' ? node.mergedAt ?? node.closedAt ?? undefined : undefined,
      headSha: node.headRefOid,
      requestedFromMe: role === 'review-requested',
      baseRef: node.baseRefName,
      headRef: node.headRefName,
    },
  }
}

/**
 * Deduplicates the role searches' hits.
 *
 * @param batches Each role's hits.
 * @returns One entry per pull request tagged with its most specific role, newest activity first.
 */
export function mergeRoleBatches(batches: [MyPullRole, FoundPull[]][]): FoundPull[] {
  const rank = (role: MyPullRole) => MY_PULL_ROLES.indexOf(role)
  const ordered = [...batches].sort(([a], [b]) => rank(a) - rank(b))
  const seen = new Map<string, FoundPull>()
  for (const [, hits] of ordered) {
    for (const hit of hits) {
      const key = `${hit.pull.owner}/${hit.pull.repo}#${hit.pull.number}`
      if (!seen.has(key))
        seen.set(key, hit)
    }
  }
  return [...seen.values()].sort((a, b) => b.pull.updatedAt.localeCompare(a.pull.updatedAt))
}

/**
 * @param state The head commit's `statusCheckRollup.state`, or `null` when it reports no checks.
 * @returns The coarse check status the home page shows.
 */
export function checksFromRollup(state: string | null | undefined): MyPullChecks {
  switch (state) {
    case 'SUCCESS':
      return 'success'
    case 'FAILURE':
    case 'ERROR':
      return 'failure'
    case 'PENDING':
    case 'EXPECTED':
      return 'pending'
    default:
      return 'none'
  }
}

const REVIEW_DECISIONS: Record<NonNullable<EnrichNode['reviewDecision']>, MyPullReviewDecision> = {
  APPROVED: 'approved',
  CHANGES_REQUESTED: 'changes-requested',
  REVIEW_REQUIRED: 'review-required',
}

const MERGEABLE: Record<EnrichNode['mergeable'], MyPullMergeable> = {
  MERGEABLE: 'mergeable',
  CONFLICTING: 'conflicting',
  UNKNOWN: 'unknown',
}

/**
 * @param node The enrichment request's answer for one pull request.
 * @returns The review, check and size fields it carries.
 */
export function toEnrichment(node: EnrichNode): Enrichment {
  return {
    reviewDecision: node.reviewDecision ? REVIEW_DECISIONS[node.reviewDecision] : undefined,
    checks: checksFromRollup(node.commits.nodes[0]?.commit.statusCheckRollup?.state),
    mergeable: MERGEABLE[node.mergeable] ?? 'unknown',
    additions: node.additions,
    deletions: node.deletions,
    changedFiles: node.changedFiles,
    reviewRequests: node.reviewRequests?.nodes
      .map(request => request.requestedReviewer?.login ?? request.requestedReviewer?.combinedSlug)
      .filter((name): name is string => !!name) ?? [],
  }
}

/**
 * @param node The native stack request's answer for one pull request.
 * @returns Its place in GitHub's own stack, or `null` when it is in none.
 */
export function toNativeStack(node: NativeStackNode): NativeStack {
  return node.stack && node.stackEntry && node.stack.size > 1
    ? { number: node.stack.number, size: node.stack.size, position: node.stackEntry.position }
    : null
}

/** What stack inference reads of a listed pull request. */
export interface StackCandidate {
  owner: string
  repo: string
  number: number
  baseRef?: string
  headRef?: string
  /** Its head branch lives in a fork, so nothing can sit on it. */
  crossRepository?: boolean
  /** A pull request based on the repository's default branch is a bottom, whatever else is open. */
  defaultBranch?: string
}

const pullKey = (pull: { owner: string, repo: string, number: number }) => `${pull.owner}/${pull.repo}#${pull.number}`

/**
 * Stacks among listed open pull requests, inferred from branch names the way Graphite, spr and plain
 * branches stack: within one repository, A sits on B when A's base branch is B's head branch. Two or more
 * pull requests joined that way, as a chain or a tree, are one stack.
 *
 * @param pulls The listed open pull requests.
 * @returns Each stacked pull request's place keyed `owner/repo#number`; one in no stack is left out.
 */
export function inferStacks(pulls: StackCandidate[]): Map<string, MyPullStack> {
  const repoOf = (pull: StackCandidate) => `${pull.owner}/${pull.repo}`
  const byHead = new Map<string, StackCandidate[]>()
  for (const pull of pulls) {
    if (!pull.headRef || pull.crossRepository)
      continue
    const key = `${repoOf(pull)}:${pull.headRef}`
    byHead.set(key, [...byHead.get(key) ?? [], pull])
  }
  // What each one sits on. Two open pull requests can share a head branch (into different bases); the lower number wins.
  const below = new Map<StackCandidate, StackCandidate>()
  for (const pull of pulls) {
    if (!pull.baseRef || pull.baseRef === pull.defaultBranch)
      continue
    const parent = byHead.get(`${repoOf(pull)}:${pull.baseRef}`)?.filter(other => other !== pull).sort((a, b) => a.number - b.number)[0]
    if (parent)
      below.set(pull, parent)
  }
  // Each one's bottom and depth; branches that loop (A on B on A) have no bottom and make no stack.
  const placed = pulls.flatMap((pull) => {
    const seen = new Set([pull])
    let bottom = pull
    for (let parent = below.get(bottom); parent; parent = below.get(bottom)) {
      if (seen.has(parent))
        return []
      seen.add(parent)
      bottom = parent
    }
    return [{ pull, bottom, position: seen.size }]
  })
  const sizes = new Map<StackCandidate, number>()
  for (const { bottom } of placed)
    sizes.set(bottom, (sizes.get(bottom) ?? 0) + 1)
  const stacks = new Map<string, MyPullStack>()
  for (const { pull, bottom, position } of placed) {
    const size = sizes.get(bottom)!
    if (size > 1)
      stacks.set(pullKey(pull), { id: `inferred:${repoOf(bottom)}:${bottom.headRef}`, position, size, native: false, below: below.get(pull)?.number })
  }
  return stacks
}

/**
 * Gives each listed open pull request its stack: GitHub's own first, then one inferred from branch names
 * for those GitHub has none for.
 *
 * @param found The listed open pull requests; their `pull.stack` is set in place.
 * @param nativeOf GitHub's own stack of one, by node id and as listed; `undefined` when unknown.
 */
export function applyStacks(found: FoundPull[], nativeOf: (id: string, pull: MyPull) => NativeStack | undefined): void {
  const rest: FoundPull[] = []
  for (const hit of found) {
    const native = nativeOf(hit.id, hit.pull)
    if (native)
      hit.pull.stack = { id: `native:${hit.pull.owner}/${hit.pull.repo}:${native.number}`, position: native.position, size: native.size, native: true }
    else
      rest.push(hit)
  }
  for (const hit of found) {
    const stack = hit.pull.stack
    if (!stack)
      continue
    const layers = found.filter(other => other !== hit && other.pull.stack?.id === stack.id)
    const below = layers.find(other => !other.crossRepository && other.pull.headRef === hit.pull.baseRef)
      ?? layers.find(other => other.pull.stack!.position === stack.position - 1)
    if (below)
      stack.below = below.pull.number
  }
  // Native layers take part too, so a pull request based on one's branch still shows what it sits on; only the rest take the answer.
  const inferred = inferStacks(found.map(({ pull, crossRepository, defaultBranch }) => ({ ...pull, crossRepository, defaultBranch })))
  for (const hit of rest) {
    const stack = inferred.get(pullKey(hit.pull))
    if (stack)
      hit.pull.stack = stack
  }
}

/** Whether a previous enrichment still describes `pull`. */
function isReusable(entry: EnrichmentEntry | undefined, pull: MyPull, now: number): entry is EnrichmentEntry {
  if (!entry || entry.updatedAt !== pull.updatedAt || entry.headSha !== pull.headSha)
    return false
  const settled = entry.data.checks !== 'pending' && entry.data.mergeable !== 'unknown'
  return now - entry.fetchedAt < (settled ? SETTLED_ENRICHMENT_TTL_MS : UNSETTLED_ENRICHMENT_TTL_MS)
}

/** Whether a previous native stack answer still describes `pull`; joining or leaving a stack may not move `updatedAt`, so it ages out. */
function isStackReusable(entry: EnrichmentEntry<NativeStack> | undefined, pull: MyPull, now: number): entry is EnrichmentEntry<NativeStack> {
  return !!entry && entry.updatedAt === pull.updatedAt && entry.headSha === pull.headSha && now - entry.fetchedAt < SETTLED_ENRICHMENT_TTL_MS
}

/** Splits `items` into requests of {@link ENRICH_CHUNK}. */
function chunked<T>(items: T[]): T[][] {
  const chunks: T[][] = []
  for (let i = 0; i < items.length; i += ENRICH_CHUNK)
    chunks.push(items.slice(i, i + ENRICH_CHUNK))
  return chunks
}

/** Runs `tasks` with at most `limit` in flight. */
async function runLimited<T>(tasks: (() => Promise<T>)[], limit: number): Promise<T[]> {
  const results: T[] = Array.from({ length: tasks.length })
  let next = 0
  async function worker() {
    while (next < tasks.length) {
      const index = next++
      results[index] = await tasks[index]()
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker))
  return results
}

export interface MyPullsService {
  /**
   * @param state Open, or closed including merged.
   * @returns The list, reused when the server fetched it seconds ago.
   * @throws When GitHub can't be reached or no token is available.
   */
  list: (state: MyPullState) => Promise<MyPull[]>
  /**
   * @param state Open, or closed including merged.
   * @param revalidate `false` only reads the copy, as a client waiting for classifications does, and never asks GitHub.
   * @returns The last list fetched, however old, or `null` before the first one; refreshes it in the background once aged.
   */
  cached: (state: MyPullState, revalidate?: boolean) => MyPullsSnapshot | null
}

export interface MyPullsServiceOptions {
  graphql: GithubGraphql
  /** The clock, replaceable in tests. */
  now?: () => number
  /** Sorts open pull requests by kind and risk in the background; the list never waits for it. */
  classifier?: PullClassifier
}

/**
 * @param options The GitHub client and clock to use.
 * @returns A `my-pulls` service with its own cache.
 */
export function createMyPullsService(options: MyPullsServiceOptions): MyPullsService {
  const { graphql, now = Date.now, classifier } = options
  const enrichments = new Map<string, EnrichmentEntry>()
  const nativeStacks = new Map<string, EnrichmentEntry<NativeStack>>()
  let nativeUnsupportedUntil = 0
  let owners: { logins: string[], fetchedAt: number } | undefined

  async function ownerLogins(): Promise<string[]> {
    if (owners && now() - owners.fetchedAt < OWNERS_TTL_MS)
      return owners.logins
    const data = await graphql<{ viewer: { login: string, organizations: { nodes: ({ login: string } | null)[] } } }>(OWNERS_QUERY, {})
    const logins = [data.viewer.login, ...data.viewer.organizations.nodes.map(org => org?.login)].filter((login): login is string => !!login)
    owners = { logins, fetchedAt: now() }
    return logins
  }

  async function search(role: MyPullRole, query: string, state: MyPullState): Promise<[MyPullRole, FoundPull[]]> {
    const run = () => graphql<{ search: { nodes: (SearchNode | Record<string, never> | null)[] } }>(SEARCH_QUERY, { q: query, first: SEARCH_LIMIT })
    // GitHub's search occasionally answers 502 under load; one retry absorbs it.
    const data = await run().catch(run)
    const nodes = data.search.nodes.filter((node): node is SearchNode => !!node && 'id' in node)
    return [role, nodes.map(node => toFoundPull(node, role, state))]
  }

  async function enrich(found: FoundPull[]): Promise<void> {
    const stale = found.filter(({ id, pull }) => !isReusable(enrichments.get(id), pull, now()))
    await runLimited(chunked(stale).map(chunk => async () => {
      const startedAt = now()
      try {
        const data = await graphql<{ nodes: (EnrichNode | null)[] }>(ENRICH_QUERY, { ids: chunk.map(hit => hit.id) })
        for (const node of data.nodes) {
          const hit = node && chunk.find(candidate => candidate.id === node.id)
          if (node && hit)
            enrichments.set(node.id, { updatedAt: hit.pull.updatedAt, headSha: hit.pull.headSha ?? '', fetchedAt: startedAt, data: toEnrichment(node) })
        }
      }
      catch {
        // Enrichment only adds badges; the list still shows without them.
      }
    }), ENRICH_CONCURRENCY)
    for (const hit of found) {
      const entry = enrichments.get(hit.id)
      if (entry)
        Object.assign(hit.pull, entry.data)
    }
  }

  /** Reads GitHub's own stacks into `nativeStacks`, like `enrich`; a failure only leaves them unknown. */
  async function readNativeStacks(found: FoundPull[]): Promise<void> {
    if (now() < nativeUnsupportedUntil)
      return
    const stale = found.filter(({ id, pull }) => !isStackReusable(nativeStacks.get(id), pull, now()))
    await runLimited(chunked(stale).map(chunk => async () => {
      const startedAt = now()
      try {
        const data = await graphql<{ nodes: (NativeStackNode | null)[] }>(NATIVE_STACK_QUERY, { ids: chunk.map(hit => hit.id) })
        for (const node of data.nodes) {
          const hit = node && chunk.find(candidate => candidate.id === node.id)
          if (node && hit)
            nativeStacks.set(node.id, { updatedAt: hit.pull.updatedAt, headSha: hit.pull.headSha ?? '', fetchedAt: startedAt, data: toNativeStack(node) })
        }
      }
      catch (error) {
        // A host without the stack fields refuses every such request alike, so stop asking it for a while.
        if (/doesn't exist on type/i.test(error instanceof Error ? error.message : String(error)))
          nativeUnsupportedUntil = now() + OWNERS_TTL_MS
      }
    }), ENRICH_CONCURRENCY)
  }

  async function fetchList(state: MyPullState): Promise<MyPull[]> {
    const logins = await ownerLogins().catch(() => [])
    const batches = await Promise.all(searchQueries(state, logins).map(([role, query]) => search(role, query, state)))
    const merged = mergeRoleBatches(batches)
    const requested = new Set(batches.find(([role]) => role === 'review-requested')?.[1].map(hit => hit.id))
    for (const hit of merged)
      hit.pull.requestedFromMe = requested.has(hit.id)
    // Closed pull requests have no review, check or stack state worth triaging.
    if (state === 'open') {
      await Promise.all([enrich(merged), readNativeStacks(merged)])
      // A failed request leaves an older answer behind for the next try; one that no longer describes the pull request is unknown.
      applyStacks(merged, (id, pull) => {
        const entry = nativeStacks.get(id)
        return isStackReusable(entry, pull, now()) ? entry.data : undefined
      })
    }
    const pulls = merged.map(hit => hit.pull)
    if (classifier) {
      // Saved classifications are read from local storage; Jev itself runs after the list is answered.
      await classifier.load(pulls).catch(() => {})
      if (state === 'open')
        void classifier.classify(pulls).catch(() => {})
    }
    return pulls
  }

  const cache = createSwrCache<MyPull[]>(state => fetchList(state as MyPullState), now)
  // Applied on every answer rather than once per fetch, so a cached list picks up classifications that finished since.
  const classified = (pulls: MyPull[]) => classifier ? classifier.apply(pulls) : pulls

  return {
    list: async state => classified((await cache.get(state, FRESH_MS)).value),
    cached(state, revalidate = true) {
      const entry = revalidate ? cache.peekAndRevalidate(state, REVALIDATE_AFTER_MS) : cache.peek(state)
      if (!entry)
        return null
      return {
        pulls: classified(entry.value),
        fetchedAt: entry.fetchedAt,
        refreshing: cache.isRefreshing(state),
        ...(classifier?.enabled() ? { classifier: 'jev' as const } : {}),
      }
    },
  }
}

/**
 * A GraphQL client on the server's GitHub token, re-read every few minutes.
 *
 * @param env Where to look for a token before asking `gh`.
 * @returns A client for `createMyPullsService`.
 */
export function createGithubGraphql(env: Env): GithubGraphql {
  let token: { value: Promise<string | undefined>, at: number } | undefined
  return async (query, variables) => {
    if (!token || Date.now() - token.at > TOKEN_TTL_MS)
      token = { value: resolveGithubToken(env), at: Date.now() }
    const value = await token.value
    if (!value) {
      token = undefined
      throw new Error('No GitHub token: set GITHUB_TOKEN or sign in with `gh auth login`.')
    }
    const res = await fetch('https://api.github.com/graphql', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${value}`, 'Content-Type': 'application/json', 'User-Agent': 'pulls.review' },
      body: JSON.stringify({ query, variables }),
    })
    const payload = await res.json().catch(() => undefined) as { data?: unknown, errors?: { message: string }[] } | undefined
    if (!res.ok || !payload?.data)
      throw new Error(`GitHub GraphQL request failed (${res.status}): ${payload?.errors?.map(error => error.message).join('; ') ?? res.statusText}`)
    return payload.data as never
  }
}

let shared: MyPullsService | undefined
let store: PullClassifierStore | undefined

function service(): MyPullsService {
  return shared ??= createMyPullsService({
    graphql: createGithubGraphql(process.env),
    classifier: createPullClassifier({ jev: sharedJev(), store: () => store }),
  })
}

/**
 * Gives the shared service the server's storage, so Jev's classifications survive a restart
 * and use the changed paths of diffs the server already holds. Until it is called they
 * live in memory only.
 *
 * @param value The server session's storage and diff cache.
 */
export function setMyPullsStore(value: PullClassifierStore): void {
  store = value
}

/**
 * Pull requests across every repository the signed-in user touches: review requests,
 * their own, ones they take part in, and anything opened in repos they or their orgs own.
 *
 * @param state Whether to list open or closed (including merged) pull requests.
 * @returns Deduplicated matches, each tagged with its most specific role, newest activity first.
 * @throws When no GitHub token is available or GitHub can't be reached.
 */
export function listMyPulls(state: MyPullState): Promise<MyPull[]> {
  return service().list(state)
}

/**
 * @param state Whether to look at open or closed (including merged) pull requests.
 * @param revalidate `false` never starts a refresh from GitHub, however old the list is.
 * @returns The last list the server fetched without waiting, or `null` before the first.
 */
export function cachedMyPulls(state: MyPullState, revalidate?: boolean): MyPullsSnapshot | null {
  return service().cached(state, revalidate)
}
