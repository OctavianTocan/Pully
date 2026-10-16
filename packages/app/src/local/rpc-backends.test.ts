import type { LocalRpc } from './connection'
import { createReviewMarks } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import { describe, expect, it, vi } from 'vitest'
import { createRpcCredentials, createRpcDriver } from './rpc-backends'

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
