import type { DiffCache, FileContentCache, PrCacheEntry, PrCacheMeta } from '@pulls.review/core/cache'
import type { Credentials, DiffSource } from '@pulls.review/core/types'
import type { Driver } from 'unstorage'
import type { LocalRpc } from './connection'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { DiffsPayloadSchema } from '@pulls.review/core/types'
import * as v from 'valibot'

/** The server's cache directory for collections without a dedicated native repository. */
/**
 * @cc [owner:OctavianTocan,label:architecture] rpc-storage-mutations-use-acknowledged-values
 * Mutations from one driver MUST serialize before capturing their expected values,
 * so overlapping single and batch writes observe successful earlier writes. A failed
 * mutation MUST NOT advance the observation or prevent later writes, and external
 * conflicts MUST still reject. Repeated keys MUST coalesce to their final value and
 * bulk writes MUST use at most 200 items per acknowledged RPC batch
 * (R12 finding 4 and R13 findings 4/5, 16 Oct 2026).
 */
export function createRpcDriver(rpc: Pick<LocalRpc, 'call'>): Driver {
  const observed = new Map<string, string | null>()
  let mutations = Promise.resolve()
  function mutate(change: () => Promise<void>): Promise<void> {
    const result = mutations.then(change)
    mutations = result.catch(() => {})
    return result
  }
  async function read(key: string) {
    const value = v.parse(v.nullable(v.string()), await rpc.call(LOCAL_RPC.storageGetItem, { key }))
    observed.set(key, value)
    return value
  }
  return {
    name: 'pulls-review-rpc',
    hasItem: async key => await read(key) !== null,
    getItem: read,
    setItem: (key, value) => mutate(async () => {
      await rpc.call(LOCAL_RPC.storageSetItem, { key, value, expected: observed.get(key) ?? null })
      observed.set(key, value)
    }),
    removeItem: key => mutate(async () => {
      await rpc.call(LOCAL_RPC.storageRemoveItem, { key, expected: observed.get(key) ?? null })
      observed.set(key, null)
    }),
    getKeys: async base => v.parse(v.array(v.string()), await rpc.call(LOCAL_RPC.storageGetKeys, { base })),
    getItems: async (items) => {
      const values = v.parse(v.array(v.nullable(v.string())), await rpc.call(LOCAL_RPC.storageGetItems, { keys: items.map(item => item.key) }))
      return items.map((item, index) => {
        const value = values[index] ?? null
        observed.set(item.key, value)
        return { key: item.key, value }
      })
    },
    setItems: items => mutate(async () => {
      // Repeated file SHAs deliberately share one review mark. Keep the final
      // value per key, then respect the native endpoint's bounded batch size.
      const unique = [...new Map(items.map(item => [item.key, item])).values()]
      for (let offset = 0; offset < unique.length; offset += 200) {
        const chunk = unique.slice(offset, offset + 200)
        await rpc.call(LOCAL_RPC.storageSetItems, { items: chunk.map(({ key, value }) => ({ key, value, expected: observed.get(key) ?? null })) })
        for (const { key, value } of chunk)
          observed.set(key, value)
      }
    }),
  }
}

/**
 * @cc [owner:OctavianTocan,label:architecture] rpc-full-file-cache-uses-native-transactions
 * Local full-file cache reads, writes and scope removals MUST use the same native
 * transaction boundary as source-load-file. A cold file expansion MUST preserve the
 * native body and metadata and permit repeated reads without raw-storage CAS conflicts
 * (R12 review finding 2, 16 Oct 2026).
 */
export function createRpcFileContentCache(rpc: Pick<LocalRpc, 'call'>): FileContentCache {
  return {
    async get(scope, sha, path) {
      return v.parse(v.optional(v.string()), await rpc.call(LOCAL_RPC.cacheFileContent, { method: 'get', scope, sha, path }) ?? undefined)
    },
    async set(scope, sha, path, content) {
      await rpc.call(LOCAL_RPC.cacheFileContent, { method: 'set', scope, sha, path, content })
    },
    async removeScope(scope) {
      await rpc.call(LOCAL_RPC.cacheFileContent, { method: 'removeScope', scope })
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
export function createRpcSource(rpc: Pick<LocalRpc, 'call'>, pending: Promise<string>): DiffSource {
  return {
    key: async () => v.parse(v.string(), await rpc.call(LOCAL_RPC.sourceKey, { target: await pending })),
    fetch: async () => v.parse(DiffsPayloadSchema, await rpc.call(LOCAL_RPC.sourceFetch, { target: await pending })),
    fingerprint: async () => v.parse(v.string(), await rpc.call(LOCAL_RPC.sourceFingerprint, { target: await pending })),
    loadFile: async (path, sha) => v.parse(v.optional(v.string()), await rpc.call(LOCAL_RPC.sourceLoadFile, { target: await pending, path, sha }) ?? undefined),
  }
}

/** Mutations execute against the server's shared repository, never as split raw storage writes. */
/**
 * @cc [owner:OctavianTocan,label:architecture] rpc-diff-writes-use-operation-heads
 * A diff write MUST compare the complete revision observed by its own fetch operation,
 * never a later unrelated cache read, including same-head base or metadata changes.
 * A conflict MUST return the winning stored entry
 * without retrying the stale write (R11 finding 15 and R13 finding 8, 16 Oct 2026).
 */
export function createRpcDiffCache(rpc: Pick<LocalRpc, 'call'>, fallback: DiffCache): DiffCache {
  const sessions = new Map<string, string>()
  const call = (method: string, key: string, ...values: unknown[]) => rpc.call(LOCAL_RPC.cacheDiff, { method, key, values })
  const cache: DiffCache = {
    ...fallback,
    async get(key) {
      const entry = await call('get', key) as PrCacheEntry | undefined
      sessions.set(key, JSON.stringify(entry?.llmSession ?? null))
      return entry
    },
    async getMeta(key) {
      return await call('getMeta', key) as PrCacheMeta | undefined
    },
    async putDiff(key, diff, headSha, options) {
      if (options?.expectedRevision === undefined)
        throw new Error('A diff write needs the revision observed before fetching.')
      try {
        return await call('putDiff', key, diff, headSha, options.expectedRevision, options) as PrCacheEntry
      }
      catch (error) {
        if (error instanceof Error && error.message === 'The cached diff changed. Reload before saving.') {
          const winner = await cache.get(key)
          if (winner)
            return winner
        }
        throw error
      }
    },
    async updateDiff(key, next, options) {
      const existing = await cache.get(key)
      const update = next(existing)
      return update ? cache.putDiff(key, update.diff, update.headSha, { ...options, expectedRevision: existing?.revision ?? null }) : undefined
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
