import type { DiffCache } from '@pulls.review/core/cache'
import type { Env } from '@pulls.review/core/env'
import type { MergeMethod, PrMergeability, PrOverview } from '@pulls.review/core/github'
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import process from 'node:process'
import { assessMergeability, createGithubClient, createGithubSource, defaultMergeCommit, fetchPullRequestOverview } from '@pulls.review/core/github'
import { serializeRef, staticCredentials } from '@pulls.review/core/types'
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
  diffs: Pick<DiffCache, 'getMeta' | 'putDiff'>
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
 * moved, an overview only when its `updatedAt` did. The writes do not count as views, so they
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

  async function storeDiff(pull: MyPull, auth: string): Promise<boolean> {
    const key = serializeRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })
    if ((await diffs.getMeta(key))?.headSha === pull.headSha)
      return false
    const id = `diff ${key}@${pull.headSha}`
    try {
      const diff = await fetchDiff(pull, auth)
      await diffs.putDiff(key, diff, diff.head?.sha ?? pull.headSha ?? '', { viewed: false })
      logged.delete(id)
      return true
    }
    catch (error) {
      once(id, `storing the diff of ${key} failed: ${messageOf(error)}`)
      return false
    }
  }

  async function storeOverviewOf(pull: MyPull, auth: string): Promise<boolean> {
    const stored = await readStoredOverview(storage, pull)
    if (stored && stored.updatedAt === pull.updatedAt)
      return false
    const id = `overview ${overviewKey(pull)}@${pull.updatedAt}`
    try {
      await storeOverview(storage, pull, await fetchOverview(pull, auth), now())
      logged.delete(id)
      return true
    }
    catch (error) {
      once(id, `storing the overview of ${pull.owner}/${pull.repo}#${pull.number} failed: ${messageOf(error)}`)
      return false
    }
  }

  async function run() {
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
  /** The repository whose cache the server serves. */
  cwd: string
  env: Env
  log?: (line: string) => void
}

/**
 * Stores the open pull requests shortly after the server starts, then every
 * `PR_STORE_INTERVAL_MS`.
 *
 * @param options The repository, and the environment holding the GitHub token.
 * @returns Stops the schedule.
 */
export async function startPrStore(options: PrStoreOptions): Promise<() => void> {
  const { cwd, env, log = line => process.stderr.write(`pulls.review: ${line}\n`) } = options
  const session = await serverSession(cwd)
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
