import type { Storage } from 'unstorage'
import * as v from 'valibot'
import { computeContentHash } from '../patch-parser'
import { storageTransaction } from './transaction'

const FILE_CONTENT_KEY_PREFIX = 'file-content:'
const FILE_CONTENT_META_PREFIX = 'file-content-meta:'
const TRANSACTION_KEY = 'file-content-budget'
/** Full-file text has its own explicit budget, in addition to the diff cache's budget. */
export const DEFAULT_FILE_CONTENT_MAX_BYTES = 25 * 1024 * 1024
const DEFAULT_FILE_CONTENT_MAX_ENTRIES = 500

const ContentBodySchema = v.object({ content: v.string() })
const ContentMetaSchema = v.object({
  scope: v.string(),
  sizeBytes: v.pipe(v.number(), v.minValue(0)),
  lastAccessedAt: v.number(),
})
type ContentMeta = v.InferOutput<typeof ContentMetaSchema> & { key: string }

export interface FileContentBudget {
  maxBytes?: number
  maxEntries?: number
}

export interface FileContentCache {
  /** `scope` is the diff's cache key, so a sha that isn't a commit (a local working tree) can't collide across sources. */
  get: (scope: string, sha: string, path: string) => Promise<string | undefined>
  /** Optional storage: exceeding the content budget or storage quota does not fail a successful source load. */
  set: (scope: string, sha: string, path: string, content: string) => Promise<void>
  /** Drops every full-file version belonging to an evicted diff. */
  removeScope: (scope: string) => Promise<void>
}

/** Same commit + path always yields the same bytes; scope isolates working-tree fingerprints. */
function fileContentKey(scope: string, sha: string, path: string): Promise<string> {
  // A tuple hash avoids key normalization collisions and filesystem filename-length limits.
  return computeContentHash(JSON.stringify([scope, sha, path]))
}

function isQuotaError(error: unknown): boolean {
  const visited = new Set<unknown>()
  while (error && typeof error === 'object' && !visited.has(error)) {
    visited.add(error)
    const { name, code, cause } = error as { name?: string, code?: string, cause?: unknown }
    if (name === 'QuotaExceededError' || name === 'NS_ERROR_DOM_QUOTA_REACHED' || code === 'ENOSPC')
      return true
    error = cause
  }
  return false
}

export function createFileContentCache(storage: Storage, budget: FileContentBudget = {}): FileContentCache {
  const maxBytes = budget.maxBytes ?? DEFAULT_FILE_CONTENT_MAX_BYTES
  const maxEntries = budget.maxEntries ?? DEFAULT_FILE_CONTENT_MAX_ENTRIES

  async function remove(key: string): Promise<void> {
    await Promise.all([
      storage.removeItem(`${FILE_CONTENT_KEY_PREFIX}${key}`),
      storage.removeItem(`${FILE_CONTENT_META_PREFIX}${key}`),
    ])
  }

  /** Reads only metadata; old unbudgeted contents and corrupt metadata are disposable cache misses. */
  async function list(): Promise<ContentMeta[]> {
    const metas: ContentMeta[] = []
    const keys = await storage.getKeys(FILE_CONTENT_META_PREFIX)
    for (const { key, value } of await storage.getItems(keys)) {
      const parsed = v.safeParse(ContentMetaSchema, value)
      const suffix = key.slice(FILE_CONTENT_META_PREFIX.length)
      if (parsed.success)
        metas.push({ key: suffix, ...parsed.output })
      else
        await remove(suffix)
    }
    const known = new Set(metas.map(meta => `${FILE_CONTENT_KEY_PREFIX}${meta.key}`))
    for (const key of await storage.getKeys(FILE_CONTENT_KEY_PREFIX)) {
      if (!known.has(key))
        await storage.removeItem(key)
    }
    return metas
  }

  return {
    get(scope, sha, path) {
      return storageTransaction(storage, TRANSACTION_KEY, async () => {
        const key = await fileContentKey(scope, sha, path)
        const parsed = v.safeParse(ContentMetaSchema, await storage.getItem(`${FILE_CONTENT_META_PREFIX}${key}`))
        if (!parsed.success) {
          await remove(key)
          return undefined
        }
        const body = v.safeParse(ContentBodySchema, await storage.getItem(`${FILE_CONTENT_KEY_PREFIX}${key}`))
        if (!body.success) {
          await remove(key)
          return undefined
        }
        try {
          await storage.setItem(`${FILE_CONTENT_META_PREFIX}${key}`, { ...parsed.output, lastAccessedAt: Date.now() })
        }
        catch (error) {
          if (!isQuotaError(error))
            throw error
        }
        return body.output.content
      })
    },
    async set(scope, sha, path, content) {
      try {
        await storageTransaction(storage, TRANSACTION_KEY, async () => {
          const key = await fileContentKey(scope, sha, path)
          const sizeBytes = new TextEncoder().encode(content).byteLength
          const metas = (await list()).filter(meta => meta.key !== key).sort((a, b) => b.lastAccessedAt - a.lastAccessedAt)
          if (sizeBytes > maxBytes || maxEntries < 1) {
            await remove(key)
            return
          }
          // Make room before the write, so optional content does not needlessly exhaust storage quota.
          let totalBytes = sizeBytes
          let count = 1
          for (const meta of metas) {
            if (totalBytes + meta.sizeBytes <= maxBytes && count < maxEntries) {
              totalBytes += meta.sizeBytes
              count++
            }
            else {
              await remove(meta.key)
            }
          }
          try {
            await storage.setItem(`${FILE_CONTENT_KEY_PREFIX}${key}`, { content })
            await storage.setItem(`${FILE_CONTENT_META_PREFIX}${key}`, { scope, sizeBytes, lastAccessedAt: Date.now() })
          }
          catch (error) {
            // A failed metadata write must not leave an unaccounted full-file body behind.
            await remove(key).catch(() => {})
            throw error
          }
        })
      }
      catch (error) {
        if (!isQuotaError(error))
          throw error
      }
    },
    removeScope(scope) {
      return storageTransaction(storage, TRANSACTION_KEY, async () => {
        for (const meta of await list()) {
          if (meta.scope === scope)
            await remove(meta.key)
        }
      })
    },
  }
}
