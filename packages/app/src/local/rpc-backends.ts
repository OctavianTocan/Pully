import type { DiffCache, PrCacheEntry, PrCacheMeta } from '@pulls.review/core/cache'
import type { Credentials, DiffSource } from '@pulls.review/core/types'
import type { Driver } from 'unstorage'
import type { LocalRpc } from './connection'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { DiffsPayloadSchema } from '@pulls.review/core/types'
import * as v from 'valibot'

/** The server's cache directory as an unstorage driver: the browser's `CacheRepositories` run on it unchanged. */
export function createRpcDriver(rpc: LocalRpc): Driver {
  const observed = new Map<string, string | null>()
  async function read(key: string) {
    const value = v.parse(v.nullable(v.string()), await rpc.call(LOCAL_RPC.storageGetItem, { key }))
    observed.set(key, value)
    return value
  }
  return {
    name: 'pulls-review-rpc',
    hasItem: async key => await read(key) !== null,
    getItem: read,
    setItem: async (key, value) => {
      await rpc.call(LOCAL_RPC.storageSetItem, { key, value, expected: observed.get(key) ?? null })
      observed.set(key, value)
    },
    removeItem: async (key) => {
      await rpc.call(LOCAL_RPC.storageRemoveItem, { key, expected: observed.get(key) ?? null })
      observed.set(key, null)
    },
    getKeys: async base => v.parse(v.array(v.string()), await rpc.call(LOCAL_RPC.storageGetKeys, { base })),
    getItems: async (items) => {
      const values = v.parse(v.array(v.nullable(v.string())), await rpc.call(LOCAL_RPC.storageGetItems, { keys: items.map(item => item.key) }))
      return items.map((item, index) => {
        const value = values[index] ?? null
        observed.set(item.key, value)
        return { key: item.key, value }
      })
    },
    setItems: async (items) => {
      await rpc.call(LOCAL_RPC.storageSetItems, { items: items.map(({ key, value }) => ({ key, value, expected: observed.get(key) ?? null })) })
      for (const { key, value } of items)
        observed.set(key, value)
    },
  }
}

/** GitHub calls run on the server; the page never obtains its token. */
export function createRpcCredentials(rpc: LocalRpc): Credentials {
  return {
    githubToken: async () => undefined,
    githubAuthenticated: async () => v.parse(v.boolean(), await rpc.call(LOCAL_RPC.githubAuthenticated)),
    async githubRequest(path, options = {}) {
      const reply = v.parse(v.object({
        status: v.number(),
        body: v.string(),
        headers: v.record(v.string(), v.string()),
      }), await rpc.call(LOCAL_RPC.githubRequest, { path, ...options }))
      const response = new Response(reply.status === 204 || reply.status === 304 ? null : reply.body, { status: reply.status, headers: reply.headers })
      if (!response.ok) {
        const { GithubApiError } = await import('@pulls.review/core/github')
        throw new GithubApiError(response.status, `GitHub request failed (${response.status})`)
      }
      return response
    },
  }
}

/** A local diff, run by the server's `git`. Its target may still be resolving (a branch's base). */
export function createRpcSource(rpc: LocalRpc, pending: Promise<string>): DiffSource {
  return {
    key: async () => v.parse(v.string(), await rpc.call(LOCAL_RPC.sourceKey, { target: await pending })),
    fetch: async () => v.parse(DiffsPayloadSchema, await rpc.call(LOCAL_RPC.sourceFetch, { target: await pending })),
    fingerprint: async () => v.parse(v.string(), await rpc.call(LOCAL_RPC.sourceFingerprint, { target: await pending })),
    loadFile: async (path, sha) => v.parse(v.optional(v.string()), await rpc.call(LOCAL_RPC.sourceLoadFile, { target: await pending, path, sha }) ?? undefined),
  }
}

/** Mutations execute against the server's shared repository, never as split raw storage writes. */
export function createRpcDiffCache(rpc: LocalRpc, fallback: DiffCache): DiffCache {
  const heads = new Map<string, string | null>()
  const sessions = new Map<string, string>()
  const call = (method: string, key: string, ...values: unknown[]) => rpc.call(LOCAL_RPC.cacheDiff, { method, key, values })
  const cache: DiffCache = {
    ...fallback,
    async get(key) {
      const entry = await call('get', key) as PrCacheEntry | undefined
      heads.set(key, entry?.headSha ?? null)
      sessions.set(key, JSON.stringify(entry?.llmSession ?? null))
      return entry
    },
    async getMeta(key) {
      const meta = await call('getMeta', key) as PrCacheMeta | undefined
      heads.set(key, meta?.headSha ?? null)
      return meta
    },
    async putDiff(key, diff, headSha, options) {
      const entry = await call('putDiff', key, diff, headSha, heads.get(key) ?? null, options) as PrCacheEntry
      heads.set(key, entry.headSha)
      return entry
    },
    async updateDiff(key, next, options) {
      const update = next(await cache.get(key))
      return update ? cache.putDiff(key, update.diff, update.headSha, options) : undefined
    },
    async put() { throw new Error('Whole-entry replacement is not supported by the server cache.') },
    touch: async (key) => { await call('touch', key) },
    setChangedSinceReviewed: async (key, paths) => { await call('setChangedSinceReviewed', key, paths) },
    setAnalyzedResult: async (key, source, result) => { await call('setAnalyzedResult', key, source, result) },
    async setLlmSession(key, session) {
      const expected = sessions.get(key) ?? 'null'
      await call('setLlmSession', key, session, expected)
      const previous = JSON.parse(expected)
      const saved = session && session.archive === undefined && previous?.archive
        ? { ...session, archive: previous.archive }
        : session
      sessions.set(key, JSON.stringify(saved ?? null))
    },
    setSharedComment: async (key, comment) => { await call('setSharedComment', key, comment) },
    setReviewData: async (key, reviews) => { await call('setReviewData', key, reviews) },
  }
  return cache
}
