import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { PrSnapshot, SnapshotStore } from './snapshots'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createPasteSource } from '@pulls.review/core/paste'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPrefetcher } from './prefetch'

const KEY = 'github:acme/app#7'

function pull(overrides: Partial<MyPull> = {}): MyPull {
  return { owner: 'acme', repo: 'app', number: 7, title: 'PR', author: 'octo', isDraft: false, updatedAt: '2026-10-01T00:00:00Z', commentsCount: 0, labels: [], state: 'open', role: 'authored', headSha: 'h2', changedFiles: 1, ...overrides }
}

const PATCH = `diff --git a/a.ts b/a.ts\nindex 000..111 100644\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n`
const base = await createPasteSource(PATCH).fetch()

function diff(sha: string): DiffsPayload {
  return { ...base, head: { sha, ref: 'feature' }, files: base.files.map(file => ({ ...file, sha: `f-${sha}` })) }
}

function setup(fetchDiff = vi.fn(async (p: MyPull) => diff(p.headSha!))) {
  const cache = createCacheRepositories(createStorage({ driver: memoryDriver() }))
  const saved = new Map<string, PrSnapshot>()
  const snapshots: SnapshotStore = {
    get: async key => saved.get(key),
    headSha: async key => saved.get(key)?.entry.headSha,
    put: async (key, entry, reviewed) => void saved.set(key, { key, entry, reviewed: [...reviewed], savedAt: 0 }),
  }
  return { cache, saved, fetchDiff, prefetcher: createPrefetcher({ cache, snapshots, fetchDiff }) }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('createPrefetcher', () => {
  it('fetches a pull request missing from the cache and saves its page', async () => {
    const { cache, saved, fetchDiff, prefetcher } = setup()
    await prefetcher.prefetch(pull())
    expect(fetchDiff).toHaveBeenCalledOnce()
    expect((await cache.diffs.get(KEY))?.headSha).toBe('h2')
    expect(saved.get(KEY)?.entry.headSha).toBe('h2')
  })

  it('reuses a cache entry already at the head commit, with its review marks', async () => {
    const { cache, saved, fetchDiff, prefetcher } = setup()
    await cache.diffs.putDiff(KEY, diff('h2'), 'h2')
    await cache.reviewMarks.set(['f-h2'], true)
    await prefetcher.prefetch(pull())
    expect(fetchDiff).not.toHaveBeenCalled()
    expect(saved.get(KEY)?.reviewed).toEqual(['f-h2'])
  })

  it('refetches when the cache holds an older commit, and skips once the page is current', async () => {
    const { cache, fetchDiff, prefetcher } = setup()
    await cache.diffs.putDiff(KEY, diff('h1'), 'h1')
    await prefetcher.prefetch(pull())
    await prefetcher.prefetch(pull())
    expect(fetchDiff).toHaveBeenCalledOnce()
  })

  it('leaves closed, large or unknown-head pull requests alone', async () => {
    const { fetchDiff, prefetcher } = setup()
    await prefetcher.prefetch(pull({ state: 'closed' }))
    await prefetcher.prefetch(pull({ changedFiles: 301 }))
    await prefetcher.prefetch(pull({ headSha: undefined }))
    expect(fetchDiff).not.toHaveBeenCalled()
  })

  it('snapshots the stored winner when a newer head arrives during its fetch', async () => {
    let resolve!: (value: DiffsPayload) => void
    let started!: () => void
    const fetching = new Promise<void>(r => started = r)
    const pending = new Promise<DiffsPayload>(r => resolve = r)
    const fetchDiff = vi.fn(async (_pull: MyPull) => {
      started()
      return pending
    })
    const { cache, saved, prefetcher } = setup(fetchDiff)
    await cache.diffs.putDiff(KEY, diff('h1'), 'h1')
    const running = prefetcher.prefetch(pull())
    await fetching
    await cache.diffs.putDiff(KEY, diff('h3'), 'h3')
    await cache.diffs.get(KEY)
    resolve(diff('h2'))
    await running
    expect((await cache.diffs.get(KEY))?.headSha).toBe('h3')
    expect(saved.get(KEY)?.entry.headSha).toBe('h3')
  })

  it('only prefetches a row the pointer rests on', async () => {
    vi.useFakeTimers()
    const { fetchDiff, prefetcher } = setup()
    prefetcher.hover(pull())
    prefetcher.leave(pull())
    await vi.advanceTimersByTimeAsync(500)
    expect(fetchDiff).not.toHaveBeenCalled()

    prefetcher.hover(pull())
    await vi.advanceTimersByTimeAsync(500)
    expect(fetchDiff).toHaveBeenCalledOnce()
  })

  it('runs at most two fetches at a time and retries after a failure', async () => {
    let active = 0
    let peak = 0
    let failed = false
    const fetchDiff = vi.fn(async (p: MyPull) => {
      peak = Math.max(peak, ++active)
      await new Promise(resolve => setTimeout(resolve, 5))
      active--
      if (p.number === 1 && !failed) {
        failed = true
        throw new Error('boom')
      }
      return diff(p.headSha!)
    })
    const { prefetcher } = setup(fetchDiff)
    await Promise.all([1, 2, 3, 4].map(number => prefetcher.prefetch(pull({ number }))))
    expect(peak).toBe(2)
    expect(fetchDiff).toHaveBeenCalledTimes(4)

    await prefetcher.prefetch(pull({ number: 1 }))
    expect(fetchDiff.mock.calls.filter(([p]) => p.number === 1)).toHaveLength(2)
  })
})
