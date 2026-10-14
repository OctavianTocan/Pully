import type { PrOverview } from '@pulls.review/core/github'
import { staticCredentials } from '@pulls.review/core/types'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { createPrOverviewStore } from './pr-overview-store'

const mocks = vi.hoisted(() => ({
  fetchPullRequestOverview: vi.fn(),
  mergePullRequest: vi.fn(),
  changePullRequestState: vi.fn(),
  updatePullRequestBranch: vi.fn(),
}))

vi.mock('@pulls.review/core/github', async importOriginal => ({
  ...await importOriginal<typeof import('@pulls.review/core/github')>(),
  ...mocks,
}))

let counter = 0

function overview(patch: Partial<PrOverview> = {}): PrOverview {
  return {
    id: 'PR_1',
    number: 7,
    title: 'Add things',
    url: 'https://github.com/o/r/pull/7',
    body: '',
    bodyHTML: '',
    createdAt: '2026-01-01T00:00:00Z',
    state: 'open',
    mergeable: 'mergeable',
    mergeStateStatus: 'clean',
    head: { oid: 'head', ref: 'feature' },
    base: { oid: 'base', ref: 'main' },
    requestedReviewers: [],
    latestReviews: [],
    checks: [],
    checksSummary: { failing: 0, pending: 0, passing: 0, skipped: 0, total: 0, hidden: 0 },
    commits: [],
    commitsHidden: 0,
    timeline: [],
    timelineHidden: 0,
    viewer: { canPush: true, canMergeAsAdmin: false, canClose: true, canReopen: true, canUpdate: true, canUpdateBranch: false, canEnableAutoMerge: false, canDisableAutoMerge: false },
    merge: { methods: ['merge', 'squash'], defaultMethod: 'squash', squashTitle: 'PR_TITLE', squashMessage: 'COMMIT_MESSAGES', mergeTitle: 'MERGE_MESSAGE', mergeMessage: 'PR_TITLE' },
    ...patch,
  }
}

function memoryStorage() {
  const items = new Map<string, string>()
  return {
    items,
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => void items.set(key, value),
  }
}

// The store also keeps a module-level memory cache, so each test uses its own PR number.
function params() {
  return { owner: 'o', repo: 'r', number: 1000 + ++counter }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.resetAllMocks()
})

describe('createPrOverviewStore', () => {
  it('loads the overview and caches it for the session', async () => {
    mocks.fetchPullRequestOverview.mockResolvedValue(overview())
    const storage = memoryStorage()
    const pr = params()
    const store = createPrOverviewStore(pr, { credentials: staticCredentials('token'), storage })

    await store.load()

    expect(store.available).toBe(true)
    expect(store.overview?.title).toBe('Add things')
    expect(store.mergeability?.ready).toBe(true)
    expect(mocks.fetchPullRequestOverview).toHaveBeenCalledWith(expect.anything(), 'o', 'r', pr.number)
    expect(storage.items.get(`pulls-review:overview:o/r#${pr.number}`)).toContain('Add things')
  })

  it('renders a cached overview at once, then revalidates', async () => {
    const storage = memoryStorage()
    const pr = params()
    storage.setItem(`pulls-review:overview:o/r#${pr.number}`, JSON.stringify(overview({ title: 'Cached' })))
    let resolve!: (value: PrOverview) => void
    mocks.fetchPullRequestOverview.mockReturnValue(new Promise((r) => {
      resolve = r
    }))

    const store = createPrOverviewStore(pr, { credentials: staticCredentials('token'), storage })
    expect(store.overview?.title).toBe('Cached')

    const loading = store.load()
    await vi.waitFor(() => expect(mocks.fetchPullRequestOverview).toHaveBeenCalled())
    expect(store.isLoading).toBe(false)
    resolve(overview({ title: 'Fresh' }))
    await loading

    expect(store.overview?.title).toBe('Fresh')
  })

  it('is unavailable without a token and never fetches', async () => {
    const store = createPrOverviewStore(params(), { credentials: staticCredentials(), storage: memoryStorage() })

    await store.load()

    expect(store.available).toBe(false)
    expect(mocks.fetchPullRequestOverview).not.toHaveBeenCalled()
  })

  it('keeps a load failure as the error', async () => {
    mocks.fetchPullRequestOverview.mockRejectedValue(new Error('boom'))
    const store = createPrOverviewStore(params(), { credentials: staticCredentials('token'), storage: memoryStorage() })

    await store.load()

    expect(store.error?.message).toBe('boom')
    expect(store.isLoading).toBe(false)
  })

  it('surfaces a failed action and does not report a change', async () => {
    mocks.fetchPullRequestOverview.mockResolvedValue(overview())
    mocks.mergePullRequest.mockRejectedValue(new Error('Pull Request is not mergeable'))
    const onChanged = vi.fn()
    const store = createPrOverviewStore(params(), { credentials: staticCredentials('token'), storage: memoryStorage(), onChanged })
    await store.load()

    const ok = await store.merge({ method: 'squash' })

    expect(ok).toBe(false)
    expect(store.actionError).toBe('Pull Request is not mergeable')
    expect(store.busy).toBeUndefined()
    expect(onChanged).not.toHaveBeenCalled()
    store.clearActionError()
    expect(store.actionError).toBeUndefined()
  })

  it('refetches and reports the change after a successful action', async () => {
    mocks.fetchPullRequestOverview.mockResolvedValueOnce(overview()).mockResolvedValueOnce(overview({ state: 'closed' }))
    mocks.changePullRequestState.mockResolvedValue(undefined)
    const onChanged = vi.fn()
    const store = createPrOverviewStore(params(), { credentials: staticCredentials('token'), storage: memoryStorage(), onChanged })
    await store.load()

    const ok = await store.changeState('close')

    expect(ok).toBe(true)
    expect(mocks.changePullRequestState).toHaveBeenCalledWith(expect.anything(), 'PR_1', 'close')
    expect(store.overview?.state).toBe('closed')
    expect(onChanged).toHaveBeenCalledWith('close')
  })

  it('passes the head it saw to merges, so a newer push is not merged unseen', async () => {
    mocks.fetchPullRequestOverview.mockResolvedValue(overview())
    mocks.mergePullRequest.mockResolvedValue(undefined)
    const store = createPrOverviewStore(params(), { credentials: staticCredentials('token'), storage: memoryStorage() })
    await store.load()

    await store.merge({ method: 'squash', title: 'T' })

    expect(mocks.mergePullRequest).toHaveBeenCalledWith(expect.anything(), { pullRequestId: 'PR_1', method: 'squash', title: 'T', body: undefined, expectedHeadOid: 'head' })
  })

  it('rechecks while GitHub is still computing mergeability, and stops once disposed', async () => {
    mocks.fetchPullRequestOverview.mockResolvedValue(overview({ mergeable: 'unknown', mergeStateStatus: 'unknown' }))
    const scope = effectScope()
    const store = scope.run(() => createPrOverviewStore(params(), { credentials: staticCredentials('token'), storage: memoryStorage() }))!
    await store.load()
    expect(mocks.fetchPullRequestOverview).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(3_000)
    expect(mocks.fetchPullRequestOverview).toHaveBeenCalledTimes(2)

    scope.stop()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.fetchPullRequestOverview).toHaveBeenCalledTimes(2)
  })
})
