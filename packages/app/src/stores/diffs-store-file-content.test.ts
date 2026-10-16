import type { DiffsPayload } from '@pulls.review/core/types'
import type { LocalRpc } from '../local/connection'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { computeContentHash } from '@pulls.review/core/patch-parser'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { expect, it, vi } from 'vitest'
import { createRpcDiffCache, createRpcDriver, createRpcFileContentCache, createRpcSource } from '../local/rpc-backends'
import { createDiffsStore } from './diffs-store'

it('expands a cold native file and reads it again without raw CAS writes or losing metadata', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const server = createCacheRepositories(storage)
  const scope = 'local:repo@HEAD'
  const path = 'src/example.ts'
  const diff: DiffsPayload = {
    ref: { kind: 'local', repo: 'repo', target: 'HEAD' },
    title: 'Local changes',
    base: { sha: 'base', ref: 'base' },
    head: { sha: 'head', ref: 'HEAD' },
    files: [{ path, sha: 'blob', status: 'modified', additions: 1, deletions: 1, isBinary: false, hunks: [] }],
  }
  await server.diffs.putDiff(scope, diff, 'head')
  const content = new Map([['base', 'before\n'], ['head', 'after\n']])
  // These methods mirror the native transaction boundary. source-load-file saves
  // both body and metadata before replying, including on the first browser miss.
  const call = vi.fn(async (name: string, args: any) => {
    if (name === LOCAL_RPC.sourceKey)
      return scope
    if (name === LOCAL_RPC.sourceFingerprint)
      return 'head'
    if (name === LOCAL_RPC.storageGetItems)
      return args.keys.map(() => null)
    if (name === LOCAL_RPC.cacheDiff) {
      if (args.method === 'get')
        return server.diffs.get(args.key)
      if (args.method === 'touch')
        return server.diffs.touch(args.key)
    }
    if (name === LOCAL_RPC.cacheFileContent) {
      switch (args.method) {
        case 'get':
          return await server.fileContents.get(args.scope, args.sha, args.path) ?? null
        case 'set':
          await server.fileContents.set(args.scope, args.sha, args.path, args.content)
          return null
        case 'removeScope':
          await server.fileContents.removeScope(args.scope)
          return null
      }
    }
    if (name === LOCAL_RPC.sourceLoadFile) {
      const loaded = content.get(args.sha)!
      await server.fileContents.set(scope, args.sha, args.path, loaded)
      return loaded
    }
    throw new Error(`Unexpected RPC: ${name}`)
  })
  const rpc = { call } as Pick<LocalRpc, 'call'>
  const cache = createCacheRepositories(createStorage({ driver: createRpcDriver(rpc) }))
  cache.diffs = createRpcDiffCache(rpc, cache.diffs)
  cache.fileContents = createRpcFileContentCache(rpc)
  const store = createDiffsStore(createRpcSource(rpc, Promise.resolve('HEAD')), { cache })
  await store.load()
  expect(store.error).toBeUndefined()

  await expect(store.fileContent!.load(diff.files[0])).resolves.toEqual({ old: 'before\n', new: 'after\n' })
  for (const [sha, text] of content) {
    const key = await computeContentHash(JSON.stringify([scope, sha, path]))
    expect(await storage.getItem(`file-content:${key}`)).toEqual({ content: text })
    expect(await storage.getItem(`file-content-meta:${key}`)).toMatchObject({ scope, sizeBytes: text.length })
  }
  await expect(store.fileContent!.load(diff.files[0])).resolves.toEqual({ old: 'before\n', new: 'after\n' })
  expect(call.mock.calls.filter(([name]) => name === LOCAL_RPC.sourceLoadFile)).toHaveLength(2)
  expect(call.mock.calls.filter(([name]) => [LOCAL_RPC.storageSetItem, LOCAL_RPC.storageSetItems, LOCAL_RPC.storageRemoveItem].includes(name as any))).toHaveLength(0)

  await cache.fileContents.set('other-scope', 'head', path, '')
  await cache.fileContents.removeScope(scope)
  expect(await cache.fileContents.get(scope, 'head', path)).toBeUndefined()
  expect(await cache.fileContents.get('other-scope', 'head', path)).toBe('')
  expect(await storage.getKeys('file-content:')).toHaveLength(1)
  expect(await storage.getKeys('file-content-meta:')).toHaveLength(1)
})

it('validates full-file RPC results and preserves server errors', async () => {
  const call = vi.fn().mockResolvedValueOnce(12).mockRejectedValueOnce(new Error('Storage unavailable'))
  const cache = createRpcFileContentCache({ call })
  await expect(cache.get('scope', 'head', 'a')).rejects.toThrow()
  await expect(cache.set('scope', 'head', 'a', 'text')).rejects.toThrow('Storage unavailable')
})
