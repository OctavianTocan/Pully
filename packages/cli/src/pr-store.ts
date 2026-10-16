import type { DiffCache } from '@pulls.review/core/cache'
import type { Env } from '@pulls.review/core/env'
import type { MergeMethod, PrMergeability, PrOverview } from '@pulls.review/core/github'
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import type { ServerHost } from './host'
import process from 'node:process'
import { assessMergeability, createGithubClient, createGithubSource, defaultMergeCommit, fetchPullRequestOverview } from '@pulls.review/core/github'
import { serializeRef, staticCredentials } from '@pulls.review/core/types'
import { track } from './analytics'
import { resolveGithubToken } from './credentials'
import { listMyPulls } from './my-pulls'
import { serverSession } from './server-session'

/** How often the open pull requests are checked for new commits and activity. */
export const PR_STORE_INTERVAL_MS = 3 * 60_000
/** The first round waits this long, so it does not compete with the server starting up. */
export const PR_STORE_FIRST_DELAY_MS = 5_000
/** Pull requests one round looks at, newest activity first. */
export const PR_STORE_MAX_PULLS = 150
/** Pull requests fetched at the same time. */
export const PR_STORE_CONCURRENCY = 2
/** A stored overview this old is fetched again although `updatedAt` has not moved: checks and mergeability change without it. */
export const OVERVIEW_MAX_AGE_MS = 30 * 60_000

const MERGE_METHODS: MergeMethod[] = ['merge', 'squash', 'rebase']

/** What the `pr-overview` route answers. */
export interface PrOverviewValue {
  overview: PrOverview
  mergeability: PrMergeability
  defaultCommits: Record<MergeMethod, { title: string, body: string }>
}

/** A stored overview, under `overviewKey`. */
export interface StoredOverview {
  /** When it was fetched, epoch milliseconds. */
  at: number
  /** The pull request's `updatedAt` then; a list showing another one means it is out of date. */
  updatedAt: string
  value: PrOverviewValue
}

type PullId = Pick<MyPull, 'owner' | 'repo'> & { number: number | string }

/** Storage key of a pull request's stored overview: `pr-overview:<owner>/<repo>#<number>`. */
export function overviewKey(pull: PullId): string {
  return `pr-overview:${pull.owner}/${pull.repo}#${pull.number}`
}

/** The overview with whether and how it can merge, and each method's default commit text. */
export function overviewValue(overview: PrOverview): PrOverviewValue {
  const defaultCommits = Object.fromEntries(MERGE_METHODS.map(method => [method, defaultMergeCommit(overview, method)])) as PrOverviewValue['defaultCommits']
  return { overview, mergeability: assessMergeability(overview), defaultCommits }
}

/** @returns The stored overview, or `undefined` when none (or an unreadable one) is stored. */
export async function readStoredOverview(storage: Storage, pull: PullId): Promise<StoredOverview | undefined> {
  const stored = await storage.getItem<StoredOverview>(overviewKey(pull)).catch(() => null)
  return stored && typeof stored === 'object' && typeof stored.at === 'number' && stored.value?.overview ? stored : undefined
}

/**
 * Stores a freshly fetched overview for the phone to open at once next time.
 * @returns What the `pr-overview` route answers.
 */
export async function storeOverview(storage: Storage, pull: PullId, overview: PrOverview, at = Date.now()): Promise<PrOverviewValue> {
  const value = overviewValue(overview)
  const stored: StoredOverview = { at, updatedAt: overview.updatedAt ?? '', value }
  await storage.setItem(overviewKey(pull), stored)
  return value
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export interface PrStoreDeps {
  /** The signed-in user's open pull requests. */
  listPulls: () => Promise<MyPull[]>
  /** The GitHub token, or `undefined` when the server has none. */
  token: () => Promise<string | undefined>
  /** The cache the phone and the page read diffs from. */
  diffs: Pick<DiffCache, 'getMeta' | 'updateDiff'>
  /** Where overviews are stored. */
  storage: Storage
  fetchDiff: (pull: MyPull, token: string) => Promise<DiffsPayload>
  fetchOverview: (pull: MyPull, token: string) => Promise<PrOverview>
  log: (line: string) => void
  now?: () => number
}

export interface PrStore {
  /** Stores what changed in the open pull requests; joins a round already running. */
  round: () => Promise<void>
  /** Later rounds do nothing, and a running one stops before its next pull request. */
  stop: () => void
}

/** Runs `tasks` with at most `limit` in flight. */
async function runLimited(tasks: (() => Promise<void>)[], limit: number): Promise<void> {
  let next = 0
  async function worker() {
    while (next < tasks.length)
      await tasks[next++]!()
  }
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker))
}

/**
 * Keeps the open pull requests' diffs and overviews stored, so opening one reads the store
 * instead of waiting on GitHub. A diff is fetched again only when the pull request's head
 * moved, an overview when its `updatedAt` did or it is `OVERVIEW_MAX_AGE_MS` old, and a new
 * overview brings an edited title, description or state into the stored diff. The writes do not count as views, so they
 * neither show as recently viewed nor push opened diffs out of the cache. A failure is logged
 * once and tried again next round.
 *
 * @param deps Where pull requests, diffs and overviews come from, and where they are stored.
 * @returns A store whose rounds the caller schedules.
 */
export function createPrStore(deps: PrStoreDeps): PrStore {
  const { listPulls, token, diffs, storage, fetchDiff, fetchOverview, log, now = Date.now } = deps
  const logged = new Set<string>()
  let stopped = false
  let running: Promise<void> | undefined

  /** Logs `line` under `id` unless it was logged since the last success of `id`. */
  function once(id: string, line: string) {
    if (!logged.has(id))
      log(line)
    logged.add(id)
  }

  function diffKey(pull: MyPull): string {
    return serializeRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })
  }

  async function storeDiff(pull: MyPull, auth: string): Promise<boolean> {
    const key = diffKey(pull)
    const before = (await diffs.getMeta(key))?.headSha
    if (before === pull.headSha)
      return false
    const id = `diff ${key}@${pull.headSha}`
    try {
      const diff = await fetchDiff(pull, auth)
      // A phone may have stored a newer head while this one was on its way; an older fetch never replaces it. With no
      // readable entry there is nothing to lose (an unreadable body after an upgrade gets repaired).
      const stored = await diffs.updateDiff(key, existing => !existing || existing.headSha === before ? { diff, headSha: diff.head?.sha ?? pull.headSha ?? '' } : undefined, { viewed: false })
      logged.delete(id)
      return !!stored
    }
    catch (error) {
      once(id, `storing the diff of ${key} failed: ${messageOf(error)}`)
      return false
    }
  }

  /**
   * A new title, description or state leaves the head where it was, so the stored diff would keep the old ones:
   * the overview just fetched brings them in without fetching the files again.
   */
  async function freshenDiff(pull: MyPull, overview: PrOverview): Promise<void> {
    const state = overview.state
    await diffs.updateDiff(diffKey(pull), (entry) => {
      // Decided on the entry as stored when the write's turn comes: a head the phone stored meanwhile stays.
      if (!entry || entry.headSha !== overview.head?.oid)
        return undefined
      const { diff } = entry
      if (diff.title === overview.title && diff.description === overview.body && diff.pullRequest?.state === state)
        return undefined
      return { diff: { ...diff, title: overview.title, description: overview.body, pullRequest: { ...diff.pullRequest, state }, updatedAt: overview.updatedAt ?? diff.updatedAt }, headSha: entry.headSha }
    }, { viewed: false })
  }

  async function storeOverviewOf(pull: MyPull, auth: string): Promise<boolean> {
    const stored = await readStoredOverview(storage, pull)
    if (stored && stored.updatedAt === pull.updatedAt && now() - stored.at < OVERVIEW_MAX_AGE_MS)
      return false
    const id = `overview ${overviewKey(pull)}@${pull.updatedAt}`
    try {
      const overview = await fetchOverview(pull, auth)
      await storeOverview(storage, pull, overview, now())
      logged.delete(id)
      await freshenDiff(pull, overview).catch(() => {})
      return true
    }
    catch (error) {
      once(id, `storing the overview of ${pull.owner}/${pull.repo}#${pull.number} failed: ${messageOf(error)}`)
      return false
    }
  }

  async function run() {
    const started = now()
    const auth = await token().catch(() => undefined)
    if (!auth) {
      once('token', 'no GitHub token, so open pull requests are not stored ahead of time')
      return
    }
    let pulls: MyPull[]
    try {
      pulls = await listPulls()
      logged.delete('list')
    }
    catch (error) {
      once('list', `storing open pull requests skipped: ${messageOf(error)}`)
      track('store.refresh', { status: 'error', ms: now() - started, message: messageOf(error) })
      return
    }
    const open = pulls
      .filter(pull => pull.state === 'open' && pull.headSha)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .slice(0, PR_STORE_MAX_PULLS)
    let storedDiffs = 0
    let storedOverviews = 0
    await runLimited(open.map(pull => async () => {
      if (stopped)
        return
      if (await storeDiff(pull, auth))
        storedDiffs++
      if (await storeOverviewOf(pull, auth))
        storedOverviews++
    }), PR_STORE_CONCURRENCY)
    track('store.refresh', { pulls: open.length, diffs: storedDiffs, overviews: storedOverviews, ms: now() - started })
    if (storedDiffs || storedOverviews)
      log(`stored ${storedDiffs} diff${storedDiffs === 1 ? '' : 's'} and ${storedOverviews} overview${storedOverviews === 1 ? '' : 's'} of open pull requests`)
  }

  return {
    round() {
      if (stopped)
        return Promise.resolve()
      running ??= run().finally(() => {
        running = undefined
      })
      return running
    },
    stop() {
      stopped = true
    },
  }
}

export interface PrStoreOptions {
  /** Where the server runs; the pull requests go into its session's store. */
  host: ServerHost
  env: Env
  log?: (line: string) => void
}

/**
 * Stores the open pull requests shortly after the server starts, then every
 * `PR_STORE_INTERVAL_MS`.
 *
 * @param options The host, and the environment holding the GitHub token.
 * @returns Stops the schedule.
 */
export async function startPrStore(options: PrStoreOptions): Promise<() => void> {
  const { host, env, log = line => process.stderr.write(`pulls.review: ${line}\n`) } = options
  const session = await serverSession(host)
  const store = createPrStore({
    listPulls: () => listMyPulls('open'),
    token: () => resolveGithubToken(env),
    diffs: session.cache.diffs,
    storage: session.storage,
    fetchDiff: (pull, auth) => createGithubSource({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) }, staticCredentials(auth)).fetch(),
    fetchOverview: (pull, auth) => fetchPullRequestOverview(createGithubClient(staticCredentials(auth)), pull.owner, pull.repo, pull.number),
    log,
  })
  const first = setTimeout(() => void store.round(), PR_STORE_FIRST_DELAY_MS)
  const timer = setInterval(() => void store.round(), PR_STORE_INTERVAL_MS)
  first.unref()
  timer.unref()
  return () => {
    clearTimeout(first)
    clearInterval(timer)
    store.stop()
  }
}
