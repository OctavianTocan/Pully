import type { MergeMethod, PrMergeability, PrOverview, PullRequestStateChange } from '@pulls.review/core/github'
import type { Credentials } from '@pulls.review/core/types'
import { assessMergeability, changePullRequestState, createGithubClient, fetchPullRequestOverview, mergePullRequest, updatePullRequestBranch } from '@pulls.review/core/github'
import { computed, getCurrentScope, onScopeDispose, reactive, ref, shallowRef } from 'vue'

export type PrOverviewAction = 'merge' | 'update-branch' | PullRequestStateChange

export interface PrOverviewStore {
  readonly owner: string
  readonly repo: string
  readonly number: number
  /** Unset until the first load lands, unless this session already loaded it once. */
  readonly overview: PrOverview | undefined
  /** `false` when GitHub authentication is unavailable; unset until known. */
  readonly available: boolean | undefined
  /** Loading with nothing to show yet. */
  readonly isLoading: boolean
  readonly error: Error | undefined
  /** Unset until the overview is loaded. */
  readonly mergeability: PrMergeability | undefined
  /** The action in flight. */
  readonly busy: PrOverviewAction | undefined
  /** Why the last action failed; cleared when the next one starts. */
  readonly actionError: string | undefined
  load: () => Promise<void>
  refresh: () => Promise<void>
  /** Resolves `true` when GitHub accepted the merge. */
  merge: (input: { method: MergeMethod, title?: string, body?: string }) => Promise<boolean>
  /** Resolves `true` when GitHub accepted the change. */
  changeState: (change: PullRequestStateChange) => Promise<boolean>
  /** Resolves `true` when GitHub accepted the update. */
  updateBranch: (method?: 'merge' | 'rebase') => Promise<boolean>
  clearActionError: () => void
}

export interface PrOverviewStoreOptions {
  credentials: Credentials
  /** Runs after an action changed the PR on GitHub. */
  onChanged?: (action: PrOverviewAction) => void
  /** Where revisits read the last overview from; defaults to `sessionStorage`. */
  storage?: Pick<Storage, 'getItem' | 'setItem'>
}

const RECHECK_MERGEABILITY_MS = 3_000
const POLL_PENDING_CHECKS_MS = 30_000
const MAX_POLLS = 20

const memory = new Map<string, PrOverview>()

function sessionStore(): Pick<Storage, 'getItem' | 'setItem'> | undefined {
  try {
    return globalThis.sessionStorage
  }
  catch {
    return undefined
  }
}

function readCached(key: string, storage: Pick<Storage, 'getItem'> | undefined): PrOverview | undefined {
  const hit = memory.get(key)
  if (hit)
    return hit
  try {
    const raw = storage?.getItem(key)
    return raw ? JSON.parse(raw) as PrOverview : undefined
  }
  catch {
    return undefined
  }
}

function writeCached(key: string, value: PrOverview, storage: Pick<Storage, 'setItem'> | undefined) {
  memory.set(key, value)
  try {
    storage?.setItem(key, JSON.stringify(value))
  }
  catch {
    // Quota or a blocked storage: the in-memory copy still serves this tab.
  }
}

function toError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

/** How long until the overview is worth fetching again on its own, if ever. */
function revalidateDelay(overview: PrOverview): number | undefined {
  if (overview.state !== 'open' && overview.state !== 'draft')
    return undefined
  // GitHub computes mergeability lazily; the first read after a push is often UNKNOWN.
  if (overview.mergeable === 'unknown' || overview.mergeStateStatus === 'unknown')
    return RECHECK_MERGEABILITY_MS
  return overview.checksSummary.pending > 0 ? POLL_PENDING_CHECKS_MS : undefined
}

/**
 * A PR's overview (description, conversation, checks, commits, merge state) with
 * stale-while-revalidate: a revisit in the same session renders the last result
 * at once, then refetches. Also runs the PR state actions.
 * @param params The PR to load.
 * @param params.owner Repository owner.
 * @param params.repo Repository name.
 * @param params.number PR number.
 * @param opts Credentials and hooks.
 * @returns The reactive store.
 */
/**
 * @cc [owner:OctavianTocan,label:security;product] pr-overview-uses-delegated-authentication
 * Overview loading and refresh MUST accept delegated GitHub authentication without
 * requiring the server token in the page (R14 finding 2, 16 Oct 2026).
 */
export function createPrOverviewStore(params: { owner: string, repo: string, number: number | string }, opts: PrOverviewStoreOptions): PrOverviewStore {
  const { owner, repo } = params
  const number = Number(params.number)
  const client = createGithubClient(opts.credentials)
  const storage = opts.storage ?? sessionStore()
  const key = `pulls-review:overview:${owner}/${repo}#${number}`

  const overview = shallowRef<PrOverview | undefined>(readCached(key, storage))
  const available = ref<boolean>()
  const isLoading = ref(false)
  const error = ref<Error>()
  const busy = ref<PrOverviewAction>()
  const actionError = ref<string>()
  const mergeability = computed(() => overview.value && assessMergeability(overview.value))

  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let polls = 0
  let disposed = false

  function schedule(current: PrOverview) {
    clearTimeout(timer)
    const delay = revalidateDelay(current)
    if (disposed || delay === undefined || polls >= MAX_POLLS)
      return
    timer = setTimeout(() => {
      if (globalThis.document?.visibilityState === 'hidden') {
        schedule(current)
        return
      }
      polls++
      void fetchOverview()
    }, delay)
  }

  async function fetchOverview() {
    const current = ++generation
    isLoading.value = !overview.value
    try {
      const fresh = await fetchPullRequestOverview(client, owner, repo, number)
      if (current !== generation)
        return
      overview.value = fresh
      error.value = undefined
      writeCached(key, fresh, storage)
      schedule(fresh)
    }
    catch (err) {
      if (current === generation)
        error.value = toError(err)
    }
    finally {
      if (current === generation)
        isLoading.value = false
    }
  }

  async function load() {
    try {
      available.value = client.authenticated ? await client.authenticated() : !!(await client.token())
    }
    catch {
      available.value = false
    }
    if (available.value)
      await fetchOverview()
  }

  async function refresh() {
    polls = 0
    await load()
  }

  async function run(action: PrOverviewAction, task: (current: PrOverview) => Promise<void>): Promise<boolean> {
    const current = overview.value
    if (!current || busy.value)
      return false
    busy.value = action
    actionError.value = undefined
    try {
      await task(current)
    }
    catch (err) {
      actionError.value = toError(err).message
      return false
    }
    finally {
      busy.value = undefined
    }
    polls = 0
    await fetchOverview()
    opts.onChanged?.(action)
    return true
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      disposed = true
      clearTimeout(timer)
    })
  }

  return reactive({
    owner,
    repo,
    number,
    overview,
    available,
    isLoading,
    error,
    mergeability,
    busy,
    actionError,
    load,
    refresh,
    merge: ({ method, title, body }: { method: MergeMethod, title?: string, body?: string }) =>
      run('merge', current => mergePullRequest(client, { pullRequestId: current.id, method, title, body, expectedHeadOid: current.head.oid })),
    changeState: (change: PullRequestStateChange) =>
      run(change, current => changePullRequestState(client, current.id, change)),
    updateBranch: (method?: 'merge' | 'rebase') =>
      run('update-branch', current => updatePullRequestBranch(client, current.id, { method, expectedHeadOid: current.head.oid })),
    clearActionError: () => {
      actionError.value = undefined
    },
  }) as PrOverviewStore
}
