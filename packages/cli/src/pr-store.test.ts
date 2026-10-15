import type { PrOverview } from '@pulls.review/core/github'
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { PrStoreDeps } from './pr-store'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createPrStore, OVERVIEW_MAX_AGE_MS, overviewKey, PR_STORE_MAX_PULLS, readStoredOverview, storeOverview } from './pr-store'

function pull(number: number, overrides: Partial<MyPull> = {}): MyPull {
  return { owner: 'acme', repo: 'app', number, title: `PR ${number}`, author: 'octo', isDraft: false, updatedAt: `2026-10-01T00:00:${String(number % 60).padStart(2, '0')}Z`, commentsCount: 0, labels: [], state: 'open', role: 'authored', headSha: `h${number}`, ...overrides }
}

function diffOf(p: MyPull): DiffsPayload {
  return {
    ref: { kind: 'github-pr', owner: p.owner, repo: p.repo, number: String(p.number) },
    title: p.title,
    head: { sha: p.headSha!, ref: 'feature' },
    files: [{ path: 'a.ts', status: 'modified', additions: 1, deletions: 0, isBinary: false, sha: `f-${p.headSha}`, hunks: [] }],
  }
}

function overviewOf(p: MyPull): PrOverview {
  return {
    id: `PR_${p.number}`,
    number: p.number,
    title: p.title,
    url: `https://github.com/acme/app/pull/${p.number}`,
    body: '',
    bodyHTML: '',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: p.updatedAt,
    state: 'open',
    mergeable: 'mergeable',
    mergeStateStatus: 'clean',
    head: { oid: p.headSha!, ref: 'feature' },
    base: { oid: 'base', ref: 'main' },
    requestedReviewers: [],
    latestReviews: [],
    checks: [],
    checksSummary: { failing: 0, pending: 0, passing: 0, skipped: 0, total: 0, hidden: 0 },
    commits: [],
    commitsHidden: 0,
    timeline: [],
    timelineHidden: 0,
    viewer: { canPush: true, canMergeAsAdmin: false, canClose: true, canReopen: false, canUpdate: true, canUpdateBranch: false, canEnableAutoMerge: false, canDisableAutoMerge: false },
    merge: { methods: ['merge', 'squash'], defaultMethod: 'squash', squashTitle: 'PR_TITLE', squashMessage: 'PR_BODY', mergeTitle: 'MERGE_MESSAGE', mergeMessage: 'PR_TITLE' },
  } as PrOverview
}

function setup(pulls: MyPull[], overrides: Partial<PrStoreDeps> = {}) {
  const storage = createStorage({ driver: memoryDriver() })
  const cache = createCacheRepositories(storage)
  const fakes = {
    listPulls: vi.fn(async () => pulls),
    token: vi.fn(async (): Promise<string | undefined> => 'token'),
    fetchDiff: vi.fn(async (p: MyPull) => diffOf(p)),
    fetchOverview: vi.fn(async (p: MyPull) => overviewOf(p)),
    log: vi.fn<(line: string) => void>(),
  }
  const store = createPrStore({ ...fakes, diffs: cache.diffs, storage, now: () => 1234, ...overrides })
  return { ...fakes, storage, cache, store }
}

describe('createPrStore', () => {
  it('stores each open pull request\'s diff without counting it as viewed, and its overview', async () => {
    const { cache, storage, store, log } = setup([pull(1), pull(2)])
    await store.round()

    const meta = await cache.diffs.getMeta('github:acme/app#1')
    expect(meta).toMatchObject({ headSha: 'h1', lastViewedAt: 0 })
    expect(await cache.diffs.listRecent(10)).toEqual([])
    const overview = await readStoredOverview(storage, pull(2))
    expect(overview).toMatchObject({ at: 1234, updatedAt: pull(2).updatedAt, value: { overview: { id: 'PR_2' }, mergeability: { allowed: true }, defaultCommits: { squash: { title: 'PR 2 (#2)' } } } })
    expect(log).toHaveBeenCalledWith('stored 2 diffs and 2 overviews of open pull requests')
  })

  it('fetches again only what changed: a moved head, or a new updatedAt', async () => {
    const { store, fetchDiff, fetchOverview, listPulls, cache } = setup([pull(1), pull(2)])
    await store.round()
    fetchDiff.mockClear()
    fetchOverview.mockClear()

    listPulls.mockResolvedValue([pull(1, { headSha: 'h1b', updatedAt: '2026-10-02T00:00:00Z' }), pull(2, { updatedAt: '2026-10-03T00:00:00Z' })])
    await store.round()

    expect(fetchDiff.mock.calls.map(([p]) => p.number)).toEqual([1])
    expect(fetchOverview.mock.calls.map(([p]) => p.number).sort()).toEqual([1, 2])
    expect((await cache.diffs.getMeta('github:acme/app#1'))!.headSha).toBe('h1b')

    fetchDiff.mockClear()
    fetchOverview.mockClear()
    await store.round()
    expect(fetchDiff).not.toHaveBeenCalled()
    expect(fetchOverview).not.toHaveBeenCalled()
  })

  it('never replaces a head stored while its own fetch was on the way', async () => {
    let cache!: ReturnType<typeof setup>['cache']
    const fetchDiff = vi.fn(async (p: MyPull) => {
      // The phone opens the pull request after a newer push while the round fetches the older head.
      await cache.diffs.putDiff('github:acme/app#1', diffOf(pull(1, { headSha: 'h1-newer' })), 'h1-newer')
      return diffOf(p)
    })
    const ctx = setup([pull(1)], { fetchDiff })
    cache = ctx.cache
    await ctx.store.round()

    expect((await cache.diffs.getMeta('github:acme/app#1'))!.headSha).toBe('h1-newer')
  })

  it('fetches an overview again once it is old, and brings an edited title into the stored diff', async () => {
    let time = 1000
    const { store, fetchOverview, cache } = setup([pull(1)], { now: () => time })
    await store.round()
    fetchOverview.mockClear()

    time += OVERVIEW_MAX_AGE_MS - 1
    await store.round()
    expect(fetchOverview).not.toHaveBeenCalled()

    // Renamed without a push: the head stays, the list's updatedAt has not been seen yet, and the overview is old.
    time += 2
    fetchOverview.mockImplementation(async (p: MyPull) => ({ ...overviewOf(p), title: 'Ready', body: 'Now with a description', state: 'merged' }))
    await store.round()
    expect(fetchOverview).toHaveBeenCalledTimes(1)
    const stored = await cache.diffs.get('github:acme/app#1')
    expect(stored!.diff).toMatchObject({ title: 'Ready', description: 'Now with a description', pullRequest: { state: 'merged' } })
    expect(stored!.lastViewedAt).toBe(0)
  })

  it('keeps when a stored diff was last viewed', async () => {
    const { store, cache, listPulls } = setup([pull(1)])
    await cache.diffs.putDiff('github:acme/app#1', diffOf(pull(1, { headSha: 'old' })), 'old')
    const viewedAt = (await cache.diffs.getMeta('github:acme/app#1'))!.lastViewedAt
    listPulls.mockResolvedValue([pull(1)])
    await store.round()

    expect(await cache.diffs.getMeta('github:acme/app#1')).toMatchObject({ headSha: 'h1', lastViewedAt: viewedAt })
  })

  it('takes the newest activity first, two at a time, and at most the round limit', async () => {
    const pulls = Array.from({ length: PR_STORE_MAX_PULLS + 5 }, (_, i) => pull(i + 1, { updatedAt: new Date(Date.UTC(2026, 0, 1, 0, 0, i)).toISOString() }))
    let inFlight = 0
    let most = 0
    const fetchDiff = vi.fn(async (p: MyPull) => {
      inFlight++
      most = Math.max(most, inFlight)
      await new Promise(resolve => setTimeout(resolve, 0))
      inFlight--
      return diffOf(p)
    })
    const { store } = setup(pulls, { fetchDiff })
    await store.round()

    expect(fetchDiff).toHaveBeenCalledTimes(PR_STORE_MAX_PULLS)
    expect(fetchDiff.mock.calls[0]![0].number).toBe(PR_STORE_MAX_PULLS + 5)
    expect(fetchDiff.mock.calls.map(([p]) => p.number)).not.toContain(1)
    expect(most).toBe(2)
  })

  it('skips pull requests that are not open or have no head', async () => {
    const { store, fetchDiff } = setup([pull(1, { state: 'closed' }), pull(2, { headSha: undefined }), pull(3)])
    await store.round()
    expect(fetchDiff.mock.calls.map(([p]) => p.number)).toEqual([3])
  })

  it('logs a failure once and tries it again next round', async () => {
    const fetchDiff = vi.fn(async (): Promise<DiffsPayload> => {
      throw new Error('rate limited')
    })
    const { store, log, cache } = setup([pull(1)], { fetchDiff })
    await store.round()
    await store.round()

    expect(fetchDiff).toHaveBeenCalledTimes(2)
    expect(log.mock.calls.filter(([line]) => line.includes('rate limited'))).toEqual([['storing the diff of github:acme/app#1 failed: rate limited']])

    fetchDiff.mockImplementation(async () => diffOf(pull(1)))
    await store.round()
    expect((await cache.diffs.getMeta('github:acme/app#1'))!.headSha).toBe('h1')
  })

  it('without a token, logs once and stores nothing', async () => {
    const { store, log, listPulls, fetchDiff } = setup([pull(1)], { token: vi.fn(async () => undefined) })
    await store.round()
    await store.round()

    expect(listPulls).not.toHaveBeenCalled()
    expect(fetchDiff).not.toHaveBeenCalled()
    expect(log).toHaveBeenCalledOnce()
  })

  it('logs a list failure once', async () => {
    const { store, log } = setup([], { listPulls: vi.fn(async () => {
      throw new Error('GitHub is down')
    }) })
    await store.round()
    await store.round()
    expect(log).toHaveBeenCalledOnce()
    expect(log.mock.calls[0]![0]).toContain('GitHub is down')
  })

  it('does nothing once stopped', async () => {
    const { store, listPulls } = setup([pull(1)])
    store.stop()
    await store.round()
    expect(listPulls).not.toHaveBeenCalled()
  })
})

describe('stored overviews', () => {
  it('are keyed by pull request and read back as written', async () => {
    const storage = createStorage({ driver: memoryDriver() })
    expect(overviewKey({ owner: 'acme', repo: 'app', number: 7 })).toBe('pr-overview:acme/app#7')
    expect(await readStoredOverview(storage, { owner: 'acme', repo: 'app', number: '7' })).toBeUndefined()

    const value = await storeOverview(storage, { owner: 'acme', repo: 'app', number: '7' }, overviewOf(pull(7)), 99)
    expect(await readStoredOverview(storage, { owner: 'acme', repo: 'app', number: 7 })).toEqual({ at: 99, updatedAt: pull(7).updatedAt, value })
  })

  it('ignores something unreadable under the key', async () => {
    const storage = createStorage({ driver: memoryDriver() })
    await storage.setItem('pr-overview:acme/app#7', { nope: true })
    expect(await readStoredOverview(storage, { owner: 'acme', repo: 'app', number: 7 })).toBeUndefined()
  })
})
