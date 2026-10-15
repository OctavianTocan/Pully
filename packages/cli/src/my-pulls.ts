import type { Env } from '@pulls.review/core/env'
import type { MyPull, MyPullChecks, MyPullMergeable, MyPullReviewDecision, MyPullRole, MyPullsSnapshot, MyPullState } from '@pulls.review/core/local-rpc'
import process from 'node:process'
import { MY_PULL_ROLES } from '@pulls.review/core/local-rpc'
import { resolveGithubToken } from './credentials'
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
  repository { nameWithOwner }
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
  repository: { nameWithOwner: string }
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

/** A search hit with the GraphQL node id the enrichment request needs. */
export interface FoundPull {
  id: string
  pull: MyPull
}

type Enrichment = Pick<MyPull, 'reviewDecision' | 'checks' | 'mergeable' | 'additions' | 'deletions' | 'changedFiles' | 'reviewRequests'>

interface EnrichmentEntry {
  updatedAt: string
  headSha: string
  fetchedAt: number
  data: Enrichment
}

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

/** Whether a previous enrichment still describes `pull`. */
function isReusable(entry: EnrichmentEntry | undefined, pull: MyPull, now: number): entry is EnrichmentEntry {
  if (!entry || entry.updatedAt !== pull.updatedAt || entry.headSha !== pull.headSha)
    return false
  const settled = entry.data.checks !== 'pending' && entry.data.mergeable !== 'unknown'
  return now - entry.fetchedAt < (settled ? SETTLED_ENRICHMENT_TTL_MS : UNSETTLED_ENRICHMENT_TTL_MS)
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
   * @returns The last list fetched, however old, or `null` before the first one; refreshes it in the background once aged.
   */
  cached: (state: MyPullState) => MyPullsSnapshot | null
}

export interface MyPullsServiceOptions {
  graphql: GithubGraphql
  /** The clock, replaceable in tests. */
  now?: () => number
}

/**
 * @param options The GitHub client and clock to use.
 * @returns A `my-pulls` service with its own cache.
 */
export function createMyPullsService(options: MyPullsServiceOptions): MyPullsService {
  const { graphql, now = Date.now } = options
  const enrichments = new Map<string, EnrichmentEntry>()
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
    const chunks: FoundPull[][] = []
    for (let i = 0; i < stale.length; i += ENRICH_CHUNK)
      chunks.push(stale.slice(i, i + ENRICH_CHUNK))
    await runLimited(chunks.map(chunk => async () => {
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

  async function fetchList(state: MyPullState): Promise<MyPull[]> {
    const logins = await ownerLogins().catch(() => [])
    const batches = await Promise.all(searchQueries(state, logins).map(([role, query]) => search(role, query, state)))
    const merged = mergeRoleBatches(batches)
    const requested = new Set(batches.find(([role]) => role === 'review-requested')?.[1].map(hit => hit.id))
    for (const hit of merged)
      hit.pull.requestedFromMe = requested.has(hit.id)
    // Closed pull requests have no review or check state worth triaging.
    if (state === 'open')
      await enrich(merged)
    return merged.map(hit => hit.pull)
  }

  const cache = createSwrCache<MyPull[]>(state => fetchList(state as MyPullState), now)

  return {
    list: async state => (await cache.get(state, FRESH_MS)).value,
    cached(state) {
      const entry = cache.peekAndRevalidate(state, REVALIDATE_AFTER_MS)
      return entry ? { pulls: entry.value, fetchedAt: entry.fetchedAt, refreshing: cache.isRefreshing(state) } : null
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

function service(): MyPullsService {
  return shared ??= createMyPullsService({ graphql: createGithubGraphql(process.env) })
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
 * @returns The last list the server fetched without waiting, or `null` before the first.
 */
export function cachedMyPulls(state: MyPullState): MyPullsSnapshot | null {
  return service().cached(state)
}
