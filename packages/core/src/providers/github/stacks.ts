import type { GithubClient } from './client'
import { GithubApiError } from './client'
import { rollupStatus } from './overview'

/**
 * Pull request stacks: a chain (or tree) of pull requests where each one's base branch is the
 * head branch of the one below it. GitHub's own stacks (`PullRequest.stack`, generally available
 * since October 2026) are read first; a host whose schema lacks those fields, or a pull request
 * stacked by hand (Graphite, spr, plain branches), is inferred from branch names instead.
 */

export type StackEntryState = 'open' | 'draft' | 'merged' | 'closed'
/** `none`: the head commit reports no checks at all. */
export type StackEntryChecks = 'success' | 'failure' | 'pending' | 'none'

/** One layer of a stack. */
export interface StackEntry {
  number: number
  title: string
  state: StackEntryState
  /** 1 sits on the base branch; each layer above counts up. Siblings of a fork share a position. */
  position: number
  /** The pull request the stack was asked for. */
  current: boolean
  headRef: string
  baseRef: string
  checks?: StackEntryChecks
  additions?: number
  deletions?: number
}

/** The stack a pull request belongs to, bottom layer first. */
export interface PullRequestStack {
  /** GitHub's own stack, rather than one inferred from branch names. */
  native: boolean
  /** GitHub's stack number; native stacks only. */
  number?: number
  /** The trunk the bottom layer merges into. */
  base: string
  entries: StackEntry[]
}

/** How far the inference walks down and up from the pull request, in requests each way. */
const MAX_STEPS = 10
/** How many layers one fork may hold when walking up. */
const UP_FANOUT = 5
/** Native stacks list at most this many entries; larger ones are cut, as GitHub's own map pages them. */
const NATIVE_ENTRIES = 50

const STACK_PULL_FIELDS = `number title state isDraft merged headRefName baseRefName isCrossRepository additions deletions
      commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }`

/**
 * GitHub's own stack for one pull request. Kept apart from every other query: on a host without
 * the stack fields it fails as a whole, which must only mean "no native stack".
 */
export const PULL_REQUEST_STACK_QUERY = `
query PullRequestStack($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    defaultBranchRef { name }
    pullRequest(number: $number) {
      ${STACK_PULL_FIELDS}
      stack {
        number
        baseRefName
        entries(first: ${NATIVE_ENTRIES}) { nodes { position pullRequest { ${STACK_PULL_FIELDS} } } }
      }
    }
  }
}`

/** The same pull request without the stack fields, for inferring a stack when the query above fails. */
export const STACK_PULL_QUERY = `
query StackPull($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    defaultBranchRef { name }
    pullRequest(number: $number) { ${STACK_PULL_FIELDS} }
  }
}`

/** Open pull requests whose head branch is `$ref`: what the pull request with base `$ref` sits on. */
export const STACK_BELOW_QUERY = `
query StackBelow($owner: String!, $repo: String!, $ref: String!) {
  repository(owner: $owner, name: $repo) {
    pullRequests(headRefName: $ref, states: OPEN, first: ${UP_FANOUT}) { nodes { ${STACK_PULL_FIELDS} } }
  }
}`

/** Open pull requests whose base branch is `$ref`: the layers on top of the pull request with head `$ref`. */
export const STACK_ABOVE_QUERY = `
query StackAbove($owner: String!, $repo: String!, $ref: String!) {
  repository(owner: $owner, name: $repo) {
    pullRequests(baseRefName: $ref, states: OPEN, first: ${UP_FANOUT}) { nodes { ${STACK_PULL_FIELDS} } }
  }
}`

export interface RawStackPull {
  number: number
  title: string
  state: 'OPEN' | 'CLOSED' | 'MERGED'
  isDraft: boolean
  merged: boolean
  headRefName: string
  baseRefName: string
  /** The head branch lives in a fork, so no branch of this repository is its head. */
  isCrossRepository?: boolean
  additions?: number
  deletions?: number
  commits?: { nodes: ({ commit: { statusCheckRollup: { state: string } | null } } | null)[] } | null
}

export interface RawNativeStack {
  number: number
  baseRefName: string
  entries: { nodes: ({ position: number, pullRequest: RawStackPull | null } | null)[] }
}

export interface RawStackPullData {
  repository: {
    defaultBranchRef: { name: string } | null
    pullRequest: (RawStackPull & { stack?: RawNativeStack | null }) | null
  } | null
}

export interface RawStackListData {
  repository: { pullRequests: { nodes: (RawStackPull | null)[] } } | null
}

/** Only the GraphQL call the stack needs, so a test can stand in for GitHub. */
export type StackClient = Pick<GithubClient, 'graphql'>

/**
 * @param raw One pull request as the stack queries return it.
 * @param position Its layer, 1 on the base branch.
 * @param current Whether it is the pull request the stack was asked for.
 * @returns The layer.
 */
export function toStackEntry(raw: RawStackPull, position: number, current: boolean): StackEntry {
  const commits = raw.commits?.nodes
  return {
    number: raw.number,
    title: raw.title,
    state: raw.merged || raw.state === 'MERGED' ? 'merged' : raw.state === 'CLOSED' ? 'closed' : raw.isDraft ? 'draft' : 'open',
    position,
    current,
    headRef: raw.headRefName,
    baseRef: raw.baseRefName,
    // No commit node means the field was not asked for; a commit without a rollup has no checks.
    checks: commits ? rollupStatus(commits[0]?.commit.statusCheckRollup?.state) ?? 'none' : undefined,
    additions: raw.additions,
    deletions: raw.deletions,
  }
}

/** Bottom first; siblings of a fork by number, so the order never depends on GitHub's. */
function ordered(entries: StackEntry[]): StackEntry[] {
  return entries.sort((a, b) => a.position - b.position || a.number - b.number)
}

/**
 * Normalizes GitHub's own stack.
 * @param stack `PullRequest.stack`, `null` when the pull request is not stacked.
 * @param number The pull request the stack was asked for.
 * @returns The stack bottom first, or `null` when there is none or it holds this pull request alone.
 */
export function parseNativeStack(stack: RawNativeStack | null | undefined, number: number): PullRequestStack | null {
  if (!stack)
    return null
  const entries = stack.entries.nodes.flatMap(node => node?.pullRequest ? [toStackEntry(node.pullRequest, node.position, node.pullRequest.number === number)] : [])
  if (entries.length < 2)
    return null
  return { native: true, number: stack.number, base: stack.baseRefName, entries: ordered(entries) }
}

async function openPulls(client: StackClient, query: string, owner: string, repo: string, ref: string): Promise<RawStackPull[]> {
  const data = await client.graphql<RawStackListData>(query, { owner, repo, ref })
  // A fork's branch is not a branch of this repository, so nothing here can be stacked on it.
  return (data.repository?.pullRequests.nodes ?? []).filter((node): node is RawStackPull => !!node && !node.isCrossRepository)
}

/**
 * Infers a stack from branch names: down through the open pull request whose head is this one's base,
 * up through every open pull request whose base is this one's head, at most {@link MAX_STEPS} requests
 * each way. Going up, a fork is followed on every side and its layers share a position.
 * @param client Runs the GraphQL requests.
 * @param owner Repository owner.
 * @param repo Repository name.
 * @param current The pull request to start from.
 * @param defaultBranch The repository's default branch: a pull request based on it is the bottom.
 * @returns The stack bottom first, or `null` when no other pull request was found.
 */
export async function inferPullRequestStack(client: StackClient, owner: string, repo: string, current: RawStackPull, defaultBranch?: string): Promise<PullRequestStack | null> {
  const seen = new Set([current.number])
  const below: RawStackPull[] = []
  let cursor = current
  for (let step = 0; step < MAX_STEPS && cursor.baseRefName && cursor.baseRefName !== defaultBranch; step++) {
    const next = (await openPulls(client, STACK_BELOW_QUERY, owner, repo, cursor.baseRefName)).find(pull => !seen.has(pull.number))
    if (!next)
      break
    seen.add(next.number)
    below.unshift(next)
    cursor = next
  }

  const own = below.length + 1
  const above: [RawStackPull, number][] = []
  let level = current.isCrossRepository ? [] : [current]
  for (let depth = 1; depth <= MAX_STEPS && level.length > 0; depth++) {
    const found = (await Promise.all(level.map(pull => openPulls(client, STACK_ABOVE_QUERY, owner, repo, pull.headRefName)))).flat()
    level = found.filter((pull) => {
      if (seen.has(pull.number))
        return false
      seen.add(pull.number)
      return true
    })
    above.push(...level.map((pull): [RawStackPull, number] => [pull, own + depth]))
  }

  if (below.length === 0 && above.length === 0)
    return null
  return {
    native: false,
    base: (below[0] ?? current).baseRefName,
    entries: ordered([
      ...below.map((pull, index) => toStackEntry(pull, index + 1, false)),
      toStackEntry(current, own, true),
      ...above.map(([pull, position]) => toStackEntry(pull, position, false)),
    ]),
  }
}

/**
 * The stack a pull request sits in: GitHub's own when it has one, otherwise inferred from branch names.
 * @param client Runs the GraphQL requests; needs a token.
 * @param owner Repository owner.
 * @param repo Repository name.
 * @param number Pull request number.
 * @returns The stack bottom first, or `null` when the pull request is not stacked.
 * @throws GithubApiError 404 when the pull request does not exist.
 */
export async function fetchPullRequestStack(client: StackClient, owner: string, repo: string, number: string | number): Promise<PullRequestStack | null> {
  const variables = { owner, repo, number: Number(number) }
  // The stack fields may not exist on this host; then the query fails and the stack is inferred.
  const native = await client.graphql<RawStackPullData>(PULL_REQUEST_STACK_QUERY, variables).catch(() => undefined)
  const stack = parseNativeStack(native?.repository?.pullRequest?.stack, variables.number)
  if (stack)
    return stack
  const data = native ?? await client.graphql<RawStackPullData>(STACK_PULL_QUERY, variables)
  const pull = data.repository?.pullRequest
  if (!pull)
    throw new GithubApiError(404, `Pull request ${owner}/${repo}#${number} not found`)
  return inferPullRequestStack(client, owner, repo, pull, data.repository?.defaultBranchRef?.name)
}
