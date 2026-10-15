import type { PullRequestListPage } from '@pulls.review/core/types'
import { describe, expect, it, vi } from 'vitest'
import { createRepoPullsCache, REPO_PULLS_FRESH_MS, REPO_PULLS_WARM_LIMIT } from './repo-pulls'

function page(title: string): PullRequestListPage {
  return { items: [], totalCount: title.length }
}

describe('createRepoPullsCache', () => {
  it('answers a held first page without waiting, and fetches it again once it is old', async () => {
    let clock = 1_000
    const fetchFirst = vi.fn(async (_owner: string, _repo: string) => page(`fetch ${fetchFirst.mock.calls.length}`))
    const cache = createRepoPullsCache(fetchFirst, () => clock)

    const first = await cache.first('acme', 'app')
    expect(first.cachedAt).toBe(1_000)
    expect(fetchFirst).toHaveBeenCalledTimes(1)

    clock += 10
    await cache.first('Acme', 'App')
    expect(fetchFirst).toHaveBeenCalledTimes(1)

    clock += REPO_PULLS_FRESH_MS
    const stale = await cache.first('acme', 'app')
    expect(stale.cachedAt).toBe(1_000)
    expect(fetchFirst).toHaveBeenCalledTimes(2)
  })

  it('waits for GitHub when asked for a fresh page', async () => {
    let clock = 0
    const fetchFirst = vi.fn(async () => page('x'))
    const cache = createRepoPullsCache(fetchFirst, () => clock)
    await cache.first('acme', 'app')
    clock = 5
    const fresh = await cache.first('acme', 'app', true)
    expect(fresh.cachedAt).toBe(5)
    expect(fetchFirst).toHaveBeenCalledTimes(2)
  })

  it('fetches the busiest of the Inbox repositories ahead, once', async () => {
    const fetchFirst = vi.fn(async () => page('x'))
    const cache = createRepoPullsCache(fetchFirst, () => 0)
    const repos = Array.from({ length: REPO_PULLS_WARM_LIMIT + 3 }, (_, i) => ({ owner: 'acme', repo: `r${i}` }))
    cache.warm([...repos, { owner: 'acme', repo: 'r14' }, { owner: 'acme', repo: 'r14' }])
    await vi.waitFor(() => expect(fetchFirst).toHaveBeenCalledTimes(REPO_PULLS_WARM_LIMIT))
    expect(fetchFirst.mock.calls[0]).toEqual(['acme', 'r14'])
    await new Promise(resolve => setTimeout(resolve, 0))
    // Only the one of the twelve busiest the first round left out (r11) is fetched; the rest are young.
    cache.warm(repos)
    await vi.waitFor(() => expect(fetchFirst).toHaveBeenCalledTimes(REPO_PULLS_WARM_LIMIT + 1))
    expect(fetchFirst.mock.calls.at(-1)).toEqual(['acme', 'r11'])
  })
})
