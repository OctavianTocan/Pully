import type { PrCacheEntry } from '@pulls.review/core/cache'
import type { DiffSource, DiffsPayload } from '@pulls.review/core/types'
import type { LocalRpc } from '../local/connection'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createRpcDiffCache } from '../local/rpc-backends'
import { createDiffsStore } from './diffs-store'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => resolve = r)
  return { promise, resolve }
}

function diff(sha: string): DiffsPayload {
  return { ref: { kind: 'paste', hash: 'race' }, title: sha, head: { sha, ref: 'main' }, files: [] }
}

describe.each(['local', 'rpc'] as const)('%s diff fetch observations', (backend) => {
  it.each(['cold load', 'refresh'] as const)('keeps the newer stored head after a slow %s and an unrelated read', async (operation) => {
    const server = createCacheRepositories(createStorage({ driver: memoryDriver() }))
    // The native endpoint rejects a mismatched expected revision atomically. This fixture
    // mirrors that wire contract; no real network or timing-dependent sleep is used.
    const call = vi.fn(async (_name: string, args: { method: string, key: string, values: unknown[] }) => {
      const { method, key, values } = args
      if (method === 'get')
        return server.diffs.get(key)
      if (method === 'getMeta')
        return server.diffs.getMeta(key)
      if (method === 'touch')
        return server.diffs.touch(key)
      if (method === 'putDiff') {
        const current = await server.diffs.get(key)
        if ((current?.revision ?? null) !== values[2])
          throw new Error('The cached diff changed. Reload before saving.')
        return server.diffs.putDiff(key, values[0] as DiffsPayload, values[1] as string)
      }
      throw new Error(`Unexpected cache method: ${method}`)
    })
    const cache = backend === 'rpc'
      ? { ...server, diffs: createRpcDiffCache({ call } as Pick<LocalRpc, 'call'>, server.diffs) }
      : server
    const fetching = deferred<void>()
    const fetched = deferred<DiffsPayload>()
    const source: DiffSource = {
      key: async () => 'race',
      fetch: () => {
        fetching.resolve()
        return fetched.promise
      },
    }
    const snapshots = { get: async () => undefined, headSha: async () => undefined, put: vi.fn(async () => {}) }
    const store = createDiffsStore(source, { cache, snapshots })
    let observedRevision: string | null = null
    if (operation === 'refresh') {
      await server.diffs.putDiff('race', diff('old'), 'old')
      await store.load()
      observedRevision = (await server.diffs.getMeta('race'))!.revision!
    }
    const running = operation === 'refresh' ? store.refresh() : store.load()
    await fetching.promise

    await server.diffs.putDiff('race', diff('winner'), 'winner')
    await server.diffs.setAnalyzedResult('race', 'llm', { source: 'llm', schemaVersion: 1, generatedAt: 'now', groups: [] })
    await server.diffs.setLlmSession('race', { messages: [{ role: 'user', content: 'keep this chat', timestamp: 1 }], chatStartIndex: 1 })
    const winner = (await server.diffs.get('race'))!
    // A finishing AI task's reloadSaved and a metadata reader must not advance
    // the pending fetch's expectation to the winning revision.
    await cache.diffs.get('race')
    await cache.diffs.getMeta('race')
    fetched.resolve(diff('old'))
    await running

    expect(store.error).toBeUndefined()
    expect(store.diff?.head?.sha).toBe('winner')
    expect(store.aiResult).toEqual(winner.analyzedBy.llm)
    expect(await server.diffs.get('race')).toEqual(winner)
    expect(snapshots.put).toHaveBeenLastCalledWith('race', winner, new Set())
    if (backend === 'rpc') {
      const writes = call.mock.calls.filter(([, args]) => args.method === 'putDiff')
      expect(writes).toHaveLength(1)
      expect(writes[0][0]).toBe(LOCAL_RPC.cacheDiff)
      expect(writes[0][1].values[2]).toBe(observedRevision)
    }
  })
})

it('propagates unrelated RPC failures and never retries a stale write when the winner was removed', async () => {
  const fallback = createCacheRepositories(createStorage({ driver: memoryDriver() })).diffs
  const call = vi.fn().mockRejectedValueOnce(new Error('Storage unavailable'))
  const cache = createRpcDiffCache({ call }, fallback)
  await expect(cache.putDiff('race', diff('old'), 'old', { expectedRevision: null })).rejects.toThrow('Storage unavailable')
  expect(call).toHaveBeenCalledOnce()

  call.mockReset().mockRejectedValueOnce(new Error('The cached diff changed. Reload before saving.')).mockResolvedValueOnce(undefined)
  await expect(cache.putDiff('race', diff('old'), 'old', { expectedRevision: 'old' })).rejects.toThrow('cached diff changed')
  expect(call.mock.calls.map(([, args]) => args.method)).toEqual(['putDiff', 'get'])
})

it('requires an operation snapshot for RPC writes and captures updateDiff locally', async () => {
  const fallback = createCacheRepositories(createStorage({ driver: memoryDriver() })).diffs
  const observed: PrCacheEntry = { key: 'race', diff: diff('old'), headSha: 'old', revision: 'old-revision', analyzedBy: {}, lastViewedAt: 0, sizeBytes: 0 }
  const call = vi.fn().mockResolvedValueOnce(observed).mockResolvedValueOnce({ ...observed, headSha: 'new' })
  const cache = createRpcDiffCache({ call }, fallback)
  await expect(cache.putDiff('race', diff('old'), 'old')).rejects.toThrow('revision observed before fetching')
  expect(call).not.toHaveBeenCalled()
  await cache.updateDiff('race', existing => ({ diff: existing!.diff, headSha: 'new' }))
  expect(call.mock.calls[1][1].values[2]).toBe('old-revision')
})

describe.each(['local', 'rpc'] as const)('%s full diff revisions', (backend) => {
  it.each(['base', 'metadata'] as const)('preserves a newer same-head %s snapshot after a deferred refresh', async (change) => {
    const server = createCacheRepositories(createStorage({ driver: memoryDriver() }))
    const call = vi.fn(async (_name: string, args: { method: string, key: string, values: unknown[] }) => {
      const { method, key, values } = args
      if (method === 'get')
        return server.diffs.get(key)
      if (method === 'getMeta')
        return server.diffs.getMeta(key)
      if (method === 'touch')
        return server.diffs.touch(key)
      if (method === 'putDiff')
        return server.diffs.putDiff(key, values[0] as DiffsPayload, values[1] as string, { expectedRevision: values[2] as string | null })
      throw new Error(`Unexpected cache method: ${method}`)
    })
    const cache = backend === 'rpc'
      ? { ...server, diffs: createRpcDiffCache({ call } as Pick<LocalRpc, 'call'>, server.diffs) }
      : server
    const original = { ...diff('head'), base: { sha: 'base-1', ref: 'main' } }
    await server.diffs.putDiff('race', original, 'head')
    const observed = await cache.diffs.getMeta('race')
    expect(observed?.revision).toBe((await cache.diffs.get('race'))?.revision)
    const fetching = deferred<void>()
    const fetched = deferred<DiffsPayload>()
    const store = createDiffsStore({
      key: async () => 'race',
      fetch: () => {
        fetching.resolve()
        return fetched.promise
      },
    }, { cache })
    const running = store.refresh()
    await fetching.promise
    const fresh = change === 'base'
      ? { ...original, base: { sha: 'base-2', ref: 'release' } }
      : { ...original, title: 'New title', description: 'New description' }
    const winner = await server.diffs.putDiff('race', fresh, 'head')
    expect(winner.revision).not.toBe(observed?.revision)
    await cache.diffs.get('race')
    await cache.diffs.getMeta('race')
    fetched.resolve(original)
    await running
    expect(store.error).toBeUndefined()
    expect(store.diff).toEqual(fresh)
    expect(await server.diffs.get('race')).toEqual(winner)
    if (backend === 'rpc')
      expect(call.mock.calls.find(([, args]) => args.method === 'putDiff')?.[1].values[2]).toBe(observed?.revision)
  })
})
