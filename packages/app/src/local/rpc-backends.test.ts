import type { PrCacheEntry } from '@pulls.review/core/cache'
import type { LocalRpc } from './connection'
import { createCacheRepositories, createReviewMarks } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import { describe, expect, it, vi } from 'vitest'
import { createRpcCredentials, createRpcDiffCache, createRpcDriver } from './rpc-backends'

it('delegates GitHub requests without retrieving server tokens', async () => {
  const call = vi.fn(async (method: string) => method === LOCAL_RPC.githubAuthenticated
    ? true
    : {
        status: 200,
        body: '{"login":"tavi"}',
        headers: { 'x-oauth-scopes': 'repo' },
      })
  const credentials = createRpcCredentials({ call } as any)
  expect(await credentials.githubToken()).toBeUndefined()
  expect(await credentials.githubAuthenticated!()).toBe(true)
  expect(await (await credentials.githubRequest!('/user')).json()).toEqual({ login: 'tavi' })
  expect(call.mock.calls.map(([method]) => method)).toEqual([LOCAL_RPC.githubAuthenticated, LOCAL_RPC.githubRequest])
})

describe('optimistic cache writes', () => {
  it('sends the exact last observed value and updates it only after success', async () => {
    const call = vi.fn().mockResolvedValueOnce('old').mockRejectedValueOnce(new Error('changed')).mockResolvedValueOnce(undefined)
    const driver = createRpcDriver({ call } as any)
    await driver.getItem('key', {})
    await expect(driver.setItem!('key', 'new', {})).rejects.toThrow('changed')
    await driver.setItem!('key', 'retry', {})
    expect(call.mock.calls[1]).toEqual([LOCAL_RPC.storageSetItem, { key: 'key', value: 'new', expected: 'old' }])
    expect(call.mock.calls[2]).toEqual([LOCAL_RPC.storageSetItem, { key: 'key', value: 'retry', expected: 'old' }])
  })
})

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>(r => resolve = r)
  return { promise, resolve }
}

it('queues Reset behind a pending Mark all batch without losing either removal', async () => {
  const marked = JSON.stringify({ sha: 'already-reviewed', reviewedAt: 1 })
  const values = new Map([['review:already-reviewed', marked]])
  const started = deferred()
  const finish = deferred()
  const call = vi.fn(async (method: string, args: any) => {
    if (method === LOCAL_RPC.storageGetItems)
      return args.keys.map((key: string) => values.get(key) ?? null)
    if (method === LOCAL_RPC.storageSetItems) {
      started.resolve()
      await finish.promise
      // Native batches compare every expectation before changing any value.
      for (const item of args.items)
        expect(item.expected).toBe(values.get(item.key) ?? null)
      for (const item of args.items)
        values.set(item.key, item.value)
      return null
    }
    if (method === LOCAL_RPC.storageRemoveItem) {
      expect(args.expected).toBe(values.get(args.key) ?? null)
      values.delete(args.key)
      return null
    }
    throw new Error(`Unexpected RPC: ${method}`)
  })
  const marks = createReviewMarks(createStorage({ driver: createRpcDriver({ call } as Pick<LocalRpc, 'call'>) }))
  expect(await marks.get(['already-reviewed', 'unreviewed'])).toEqual(new Set(['already-reviewed']))

  const markAll = marks.set(['unreviewed'], true)
  await started.promise
  const reset = marks.set(['already-reviewed', 'unreviewed'], false)
  await Promise.resolve()
  expect(call.mock.calls.filter(([method]) => method === LOCAL_RPC.storageRemoveItem)).toHaveLength(0)
  finish.resolve()
  await Promise.all([markAll, reset])

  expect(values.size).toBe(0)
  expect(await marks.get(['already-reviewed', 'unreviewed'])).toEqual(new Set())
  expect(call.mock.calls.filter(([method]) => method === LOCAL_RPC.storageRemoveItem).map(([, args]) => args.expected))
    .toEqual([marked, expect.stringContaining('unreviewed')])
})

it('serializes single and batch writes while keeping external conflicts visible', async () => {
  const started = deferred()
  const finish = deferred()
  let stored: string | null = 'old'
  const call = vi.fn(async (method: string, args: any) => {
    if (method === LOCAL_RPC.storageGetItem)
      return stored
    if (method === LOCAL_RPC.storageSetItem) {
      started.resolve()
      await finish.promise
      expect(args.expected).toBe(stored)
      stored = args.value
      return null
    }
    if (method === LOCAL_RPC.storageSetItems) {
      if (args.items[0].expected !== stored)
        throw new Error('The cached data changed on the server. Reload before saving.')
      stored = args.items[0].value
      return null
    }
    if (method === LOCAL_RPC.storageRemoveItem) {
      expect(args.expected).toBe(stored)
      stored = null
      return null
    }
    throw new Error(`Unexpected RPC: ${method}`)
  })
  const driver = createRpcDriver({ call } as Pick<LocalRpc, 'call'>)
  await driver.getItem('key', {})
  const single = driver.setItem!('key', 'own-write', {})
  await started.promise
  const batch = driver.setItems!([{ key: 'key', value: 'own-batch' }], {})
  finish.resolve()
  await Promise.all([single, batch])
  expect(call.mock.calls[2][1].items[0].expected).toBe('own-write')

  stored = 'external-write'
  await expect(driver.setItems!([{ key: 'key', value: 'stale' }], {}))
    .rejects
    .toThrow('The cached data changed on the server. Reload before saving.')
  expect(stored).toBe('external-write')
  // A caller can explicitly reload after the conflict, and the queue remains usable.
  await driver.getItem('key', {})
  await driver.removeItem!('key', {})
  expect(stored).toBeNull()
})

it.each([
  { shas: Array.from({ length: 201 }, (_, index) => `sha-${index}`) },
  { shas: ['same-sha', 'same-sha'] },
])('marks every selected SHA with bounded unique native batches', async ({ shas }) => {
  const values = new Map<string, string>()
  const call = vi.fn(async (method: string, args: any) => {
    if (method === LOCAL_RPC.storageGetItems)
      return args.keys.map((key: string) => values.get(key) ?? null)
    if (method === LOCAL_RPC.storageSetItems) {
      expect(args.items.length).toBeLessThanOrEqual(200)
      expect(new Set(args.items.map((item: any) => item.key)).size).toBe(args.items.length)
      for (const item of args.items)
        expect(item.expected).toBe(values.get(item.key) ?? null)
      for (const item of args.items)
        values.set(item.key, item.value)
      return null
    }
    throw new Error(`Unexpected RPC: ${method}`)
  })
  const marks = createReviewMarks(createStorage({ driver: createRpcDriver({ call } as Pick<LocalRpc, 'call'>) }))
  await marks.set(shas, true)
  expect(await marks.get(shas)).toEqual(new Set(shas))
  const batches = call.mock.calls.filter(([method]) => method === LOCAL_RPC.storageSetItems)
  expect(batches.map(([, args]) => args.items.length)).toEqual(shas.length === 201 ? [200, 1] : [1])
})

it('keeps only acknowledged chunk observations after a partial bulk failure', async () => {
  let fail = true
  const values = new Map<string, string>()
  const call = vi.fn(async (_method: string, args: any) => {
    if (args.items.some((item: any) => item.key === 'key-200') && fail)
      throw new Error('Second batch failed')
    for (const item of args.items)
      expect(item.expected).toBe(values.get(item.key) ?? null)
    for (const item of args.items)
      values.set(item.key, item.value)
  })
  const driver = createRpcDriver({ call } as Pick<LocalRpc, 'call'>)
  const items = Array.from({ length: 201 }, (_, index) => ({ key: `key-${index}`, value: 'first' }))
  await expect(driver.setItems!(items, {})).rejects.toThrow('Second batch failed')
  expect(values.size).toBe(200)
  fail = false
  await driver.setItems!([...items, { key: 'key-0', value: 'last' }], {})
  expect(values.size).toBe(201)
  expect(values.get('key-0')).toBe('last')
})

it.each(['single', 'batch', 'has'] as const)('orders a deferred %s read before queued writes', async (kind) => {
  const started = deferred()
  const finish = deferred()
  let stored = 'old'
  const call = vi.fn(async (method: string, args: any) => {
    if (method === LOCAL_RPC.storageGetItem || method === LOCAL_RPC.storageGetItems) {
      const captured = stored
      started.resolve()
      await finish.promise
      return method === LOCAL_RPC.storageGetItems ? [captured] : captured
    }
    const item = method === LOCAL_RPC.storageSetItems ? args.items[0] : args
    if (item.expected !== stored)
      throw new Error('The cached data changed on the server. Reload before saving.')
    stored = item.value
    return null
  })
  const driver = createRpcDriver({ call } as Pick<LocalRpc, 'call'>)
  const reading = kind === 'batch'
    ? driver.getItems!([{ key: 'key' }], {})
    : kind === 'has' ? driver.hasItem!('key', {}) : driver.getItem('key', {})
  await started.promise
  const writing = kind === 'batch'
    ? driver.setItems!([{ key: 'key', value: 'new' }], {})
    : driver.setItem!('key', 'new', {})
  await Promise.resolve()
  expect(stored).toBe('old')
  finish.resolve()
  await Promise.all([reading, writing])
  await driver.setItem!('key', 'last', {})
  expect(stored).toBe('last')
  expect(call.mock.calls.at(-1)).toEqual([LOCAL_RPC.storageSetItem, { key: 'key', value: 'last', expected: 'new' }])
})

function cacheEntry(): PrCacheEntry {
  return {
    key: 'paste:cache',
    headSha: 'head',
    revision: 'revision',
    lastViewedAt: 1,
    sizeBytes: 100,
    diff: { ref: { kind: 'paste', hash: 'cache' }, title: 'Fresh diff', files: [] },
    analyzedBy: {},
  }
}

function rpcCache(call: ReturnType<typeof vi.fn>) {
  return createRpcDiffCache({ call } as Pick<LocalRpc, 'call'>, createCacheRepositories(createStorage()).diffs)
}

it('normalizes null and malformed native cache reads to misses', async () => {
  for (const reply of [null, { ...cacheEntry(), diff: { ...cacheEntry().diff, files: null } }, { ...cacheEntry(), llmSession: { messages: null, chatStartIndex: 0 } }]) {
    const cache = rpcCache(vi.fn().mockResolvedValue(reply))
    expect(await cache.get('paste:cache')).toBeUndefined()
    expect(await cache.getMeta('paste:cache')).toBeUndefined()
  }
})

it('parses valid native metadata and entry replies using the canonical schemas', async () => {
  const entry = cacheEntry()
  const meta = { key: entry.key, headSha: entry.headSha, revision: entry.revision, ref: entry.diff.ref, title: entry.diff.title, additions: 0, deletions: 0, fileShas: [], lastViewedAt: 1, sizeBytes: 100 }
  const call = vi.fn().mockResolvedValueOnce({ ...entry, internal: true }).mockResolvedValueOnce({ ...meta, internal: true })
  const cache = rpcCache(call)
  expect(await cache.get(entry.key)).toEqual(entry)
  expect(await cache.getMeta(entry.key)).toEqual(meta)
})

it('allows a fresh fetch to replace a malformed cache miss', async () => {
  const fresh = cacheEntry()
  const call = vi.fn()
    .mockResolvedValueOnce({ ...fresh, diff: { ...fresh.diff, files: null } })
    .mockResolvedValueOnce(fresh)
  const cache = rpcCache(call)
  const observed = await cache.get(fresh.key)
  expect(await cache.putDiff(fresh.key, fresh.diff, fresh.headSha, { expectedRevision: observed?.revision ?? null })).toEqual(fresh)
  expect(call.mock.calls[1][1].values[2]).toBeNull()
})

it('rejects malformed write replies and never installs a corrupt conflict winner', async () => {
  const fresh = cacheEntry()
  const malformed = { ...fresh, diff: { ...fresh.diff, files: null } }
  const call = vi.fn().mockResolvedValueOnce(malformed)
  const cache = rpcCache(call)
  await expect(cache.putDiff(fresh.key, fresh.diff, fresh.headSha, { expectedRevision: null })).rejects.toThrow()
  call.mockRejectedValueOnce(new Error('The cached diff changed. Reload before saving.')).mockResolvedValueOnce(malformed)
  await expect(cache.putDiff(fresh.key, fresh.diff, fresh.headSha, { expectedRevision: null })).rejects.toThrow('The cached diff changed')
})

it.each(['updated entry', 'conflict winner'])('clears the session returned by a deferred refresh %s without another read', async (replyKind) => {
  const initial = { ...cacheEntry(), llmSession: { messages: [], chatStartIndex: 0 } }
  let stored: PrCacheEntry = initial
  const started = deferred()
  const finish = deferred()
  const call = vi.fn(async (method: string, args: any) => {
    expect(method).toBe(LOCAL_RPC.cacheDiff)
    if (args.method === 'get')
      return structuredClone(stored)
    if (args.method === 'putDiff') {
      started.resolve()
      await finish.promise
      if (args.values[2] === stored.revision)
        stored = { ...stored, diff: args.values[0], headSha: args.values[1], revision: 'refreshed' }
      return structuredClone(stored)
    }
    if (args.method === 'setLlmSession') {
      if (args.values[1] !== JSON.stringify(stored.llmSession ?? null))
        throw new Error('The chat changed on the server. Reload before saving.')
      stored = { ...stored, llmSession: args.values[0] }
      return null
    }
    throw new Error(`Unexpected cache method: ${args.method}`)
  })
  const cache = rpcCache(call)
  const observed = await cache.get(initial.key)
  const refresh = cache.putDiff(initial.key, { ...initial.diff, title: 'Refreshed diff' }, initial.headSha, { expectedRevision: observed!.revision })
  await started.promise
  const completedSession = {
    messages: [
      { role: 'user', content: 'Why?', timestamp: 1 },
      { role: 'assistant', content: [{ type: 'text', text: 'Finished on the server.' }], timestamp: 2 },
    ],
    chatStartIndex: 0,
  }
  stored = {
    ...stored,
    revision: replyKind === 'conflict winner' ? 'concurrent-diff' : stored.revision,
    llmSession: completedSession,
  }
  finish.resolve()
  const displayed = await refresh
  expect(displayed.llmSession).toEqual(completedSession)
  expect(displayed.revision).toBe(replyKind === 'conflict winner' ? 'concurrent-diff' : 'refreshed')

  await cache.setLlmSession(initial.key, undefined)
  expect(stored.llmSession).toBeUndefined()
  expect(call.mock.calls.map(([, args]) => args.method)).toEqual(['get', 'putDiff', 'setLlmSession'])
  expect(call.mock.calls[2][1].values[1]).toBe(JSON.stringify(displayed.llmSession))
})
