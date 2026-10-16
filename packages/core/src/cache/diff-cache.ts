import type { Storage } from 'unstorage'
import type { GroupedResult } from '../types/analyze'
import type { ReviewData } from '../types/comment-threads'
import type { DiffsPayload } from '../types/diff'
import type { ReviewMarks } from './review-marks'
import type { LlmSession, PersistedGroupSource, PrCacheBody, PrCacheEntry, PrCacheMeta } from './schema'
import * as v from 'valibot'
import { createFileContentCache } from './file-content-cache'
import { PrCacheBodySchema, PrCacheMetaSchema } from './schema'
import { storageTransaction } from './transaction'

const META_PREFIX = 'pr-meta:'
const BODY_PREFIX = 'pr-body:'
/** Entries from before the meta/body split: never readable again, so eviction drops them. */
const LEGACY_PREFIX = 'pr:'
export const DEFAULT_MAX_BUDGET_BYTES = 50 * 1024 * 1024
export const DEFAULT_MAX_ENTRY_COUNT = 50

export interface CacheBudget {
  maxBytes?: number
  maxEntries?: number
  /** Keys the budget never evicts nor counts, such as the diffs an account owns (`posted:*`), which last until deleted. */
  keep?: (key: string) => boolean
  /** Separate bounded budget for full source-file bodies. */
  fileContentMaxBytes?: number
}

export interface PutDiffOptions {
  /**
   * `false` for a write nobody is looking at (a server keeping open pull requests fresh):
   * the entry keeps its `lastViewedAt`, or gets `0` when new, so it neither moves up the
   * LRU nor shows as recently viewed. Defaults to `true`.
   */
  viewed?: boolean
}

/** What `updateDiff` stores. */
export interface DiffUpdate {
  diff: DiffsPayload
  headSha: string
  /** Optional analysis and transcript saved atomically with the selected diff revision. */
  analyzedBy?: PrCacheBody['analyzedBy']
  llmSession?: LlmSession
}

export interface DiffCache {
  get: (key: string) => Promise<PrCacheEntry | undefined>
  /** One entry's metadata (`headSha`, counts, `lastViewedAt`) without reading its diff. */
  getMeta: (key: string) => Promise<PrCacheMeta | undefined>
  /** Writes a whole entry as given, replacing what was there. */
  put: (entry: PrCacheEntry) => Promise<void>
  /**
   * Stores a freshly fetched diff under `key`, keeping whatever the existing entry
   * already holds (AI analyses, chat session, review data, shared comment): those
   * were paid for and stay useful against a newer diff via `resolveGroups`.
   */
  putDiff: (key: string, diff: DiffsPayload, headSha: string, options?: PutDiffOptions) => Promise<PrCacheEntry>
  /**
   * `putDiff` with what to store decided by `next` from the entry as stored when this write's turn comes, so a diff
   * stored in between is never replaced by one decided against an older entry. `next` returning `undefined` writes nothing.
   * @returns The entry written, or `undefined` when nothing was.
   */
  updateDiff: (key: string, next: (existing: PrCacheEntry | undefined) => DiffUpdate | undefined, options?: PutDiffOptions) => Promise<PrCacheEntry | undefined>
  touch: (key: string) => Promise<void>
  setChangedSinceReviewed: (key: string, paths: string[]) => Promise<void>
  setAnalyzedResult: (key: string, source: PersistedGroupSource, result: GroupedResult) => Promise<void>
  /** Writes the chat transcript; omitted archives are preserved. A browser may supply its observed serialized session for conflict detection. */
  setLlmSession: (key: string, session: LlmSession | undefined, expectedSession?: string) => Promise<void>
  setSharedComment: (key: string, sharedComment: PrCacheEntry['sharedComment']) => Promise<void>
  setReviewData: (key: string, reviews: ReviewData) => Promise<void>
  /** Drops one entry, both halves. Its review marks stay until the next eviction prunes them. */
  remove: (key: string) => Promise<void>
  /** Most-recently-viewed first, leaving out entries never viewed (`lastViewedAt` 0). Reads metadata only. */
  listRecent: (limit: number) => Promise<PrCacheMeta[]>
  /** Whole entries whose metadata matches; only those bodies are read. */
  listMatching: (match: (meta: PrCacheMeta) => boolean) => Promise<PrCacheEntry[]>
}

/** Rough approximation of an entry's on-disk footprint, used for LRU budget accounting. */
export function computeEntrySizeBytes(diff: DiffsPayload, analyzedBy: PrCacheBody['analyzedBy'], llmSession?: PrCacheBody['llmSession'] | LlmSession): number {
  return new TextEncoder().encode(JSON.stringify({ diff, analyzedBy, llmSession })).length
}

function split(entry: PrCacheEntry): { meta: PrCacheMeta, body: PrCacheBody } {
  const { diff, analyzedBy, reviews, llmSession, ...rest } = entry
  const meta: PrCacheMeta = {
    ...rest,
    ref: diff.ref,
    title: diff.title,
    label: diff.label,
    url: diff.url,
    pullRequestState: diff.pullRequest?.state,
    additions: diff.files.reduce((sum, file) => sum + file.additions, 0),
    deletions: diff.files.reduce((sum, file) => sum + file.deletions, 0),
    fileShas: diff.files.map(file => file.sha),
  }
  return { meta, body: { diff, analyzedBy, reviews, llmSession } }
}

function join(meta: PrCacheMeta, body: PrCacheBody): PrCacheEntry {
  const { key, headSha, lastViewedAt, sizeBytes, sharedComment, changedSinceReviewed } = meta
  return { key, headSha, lastViewedAt, sizeBytes, sharedComment, changedSinceReviewed, ...body }
}

/**
 * Cached diffs, split into a `pr-meta:*` and a `pr-body:*` document per key so
 * eviction and listing never load whole diffs. Writes to one key run one at a
 * time, so overlapping read-modify-writes (a review refetch next to a shared-result
 * load) can't drop each other's changes.
 */
/**
 * @cc [owner:OctavianTocan,label:architecture] diff-cache-shares-transaction-locks
 * Repositories over the same storage MUST serialize read-modify-write operations for
 * each diff key, including coordinated browser writes (review finding 6, 15 Oct 2026).
 */
export function createDiffCache(storage: Storage, reviewMarks: ReviewMarks, budget: CacheBudget = {}): DiffCache {
  const maxBytes = budget.maxBytes ?? DEFAULT_MAX_BUDGET_BYTES
  const maxEntries = budget.maxEntries ?? DEFAULT_MAX_ENTRY_COUNT
  const keep = budget.keep ?? (() => false)
  const fileContents = createFileContentCache(storage, { maxBytes: budget.fileContentMaxBytes })

  function exclusive<T>(key: string, task: () => Promise<T>): Promise<T> {
    return storageTransaction(storage, `diff:${key}`, task)
  }

  async function getMeta(key: string): Promise<PrCacheMeta | undefined> {
    const result = v.safeParse(PrCacheMetaSchema, await storage.getItem(`${META_PREFIX}${key}`))
    return result.success ? result.output : undefined
  }

  async function getBody(key: string): Promise<PrCacheBody | undefined> {
    const result = v.safeParse(PrCacheBodySchema, await storage.getItem(`${BODY_PREFIX}${key}`))
    return result.success ? result.output : undefined
  }

  async function get(key: string): Promise<PrCacheEntry | undefined> {
    const [meta, body] = await Promise.all([getMeta(key), getBody(key)])
    // A missing half, or a corrupt / previous-shape one, is a cache miss rather than a crash.
    return meta && body ? join(meta, body) : undefined
  }

  async function write(entry: PrCacheEntry): Promise<void> {
    const { meta, body } = split(entry)
    await Promise.all([
      storage.setItem(`${META_PREFIX}${entry.key}`, meta),
      storage.setItem(`${BODY_PREFIX}${entry.key}`, body),
    ])
  }

  function updateMeta(key: string, patch: (meta: PrCacheMeta) => PrCacheMeta): Promise<void> {
    return exclusive(key, async () => {
      const meta = await getMeta(key)
      if (meta)
        await storage.setItem(`${META_PREFIX}${key}`, patch(meta))
    })
  }

  function updateBody(key: string, patch: (body: PrCacheBody) => PrCacheBody): Promise<void> {
    return exclusive(key, async () => {
      const body = await getBody(key)
      if (body)
        await storage.setItem(`${BODY_PREFIX}${key}`, patch(body))
    })
  }

  async function listMetas(): Promise<PrCacheMeta[]> {
    const keys = await storage.getKeys(META_PREFIX)
    const metas: PrCacheMeta[] = []
    for (const { value } of await storage.getItems(keys)) {
      const result = v.safeParse(PrCacheMetaSchema, value)
      if (result.success)
        metas.push(result.output)
    }
    return metas
  }

  function enforceBudget(): Promise<void> {
    return storageTransaction(storage, 'diff-budget', async () => {
      const legacy = await storage.getKeys(LEGACY_PREFIX)
      await Promise.all(legacy.map(key => storage.removeItem(key)))

      const metas = await listMetas()
      const kept: PrCacheMeta[] = metas.filter(meta => keep(meta.key))
      const sorted = metas.filter(meta => !keep(meta.key)).sort((a, b) => b.lastViewedAt - a.lastViewedAt)
      let totalBytes = 0
      const evicted: PrCacheMeta[] = []
      for (const [index, meta] of sorted.entries()) {
        totalBytes += meta.sizeBytes
        if (index < maxEntries && totalBytes <= maxBytes)
          kept.push(meta)
        else
          evicted.push(meta)
      }
      if (evicted.length === 0)
        return

      await Promise.all(evicted.map(meta => exclusive(meta.key, async () => {
        const current = await getMeta(meta.key)
        // A read or write since budget accounting may have made this entry current again.
        if (current && (current.headSha !== meta.headSha || current.lastViewedAt !== meta.lastViewedAt || current.sizeBytes !== meta.sizeBytes)) {
          kept.push(current)
          return
        }
        await Promise.all([
          storage.removeItem(`${META_PREFIX}${meta.key}`),
          storage.removeItem(`${BODY_PREFIX}${meta.key}`),
          fileContents.removeScope(meta.key),
        ])
      })))
      await reviewMarks.prune(new Set(kept.flatMap(meta => meta.fileShas)))
    })
  }

  /**
   * Paths of `diff` whose previous `sha` was reviewed but has since changed, plus the
   * still-present paths already flagged on `existing` - a flag only clears when the
   * user marks the file again (`setChangedSinceReviewed`), not by further commits.
   */
  async function changedSinceReviewed(existing: PrCacheEntry, diff: DiffsPayload): Promise<string[]> {
    const previousSha = new Map(existing.diff.files.map(file => [file.path, file.sha]))
    const reviewed = await reviewMarks.get([...previousSha.values()])
    const flagged = new Set(existing.changedSinceReviewed)
    return diff.files
      .filter((file) => {
        const previous = previousSha.get(file.path)
        return flagged.has(file.path) || (previous !== undefined && previous !== file.sha && reviewed.has(previous))
      })
      .map(file => file.path)
  }

  /** Writes `diff` over `existing`, keeping what `putDiff` keeps. Runs inside the key's `exclusive`. */
  async function writeDiff(key: string, existing: PrCacheEntry | undefined, { diff, headSha, analyzedBy: analysis, llmSession: session }: DiffUpdate, viewed: boolean): Promise<PrCacheEntry> {
    const analyzedBy = analysis ?? existing?.analyzedBy ?? {}
    const llmSession = session === undefined ? existing?.llmSession : { ...session, archive: session.archive ?? existing?.llmSession?.archive } as PrCacheBody['llmSession']
    const next: PrCacheEntry = {
      ...existing,
      key,
      diff,
      headSha,
      analyzedBy,
      llmSession,
      lastViewedAt: viewed ? Date.now() : existing?.lastViewedAt ?? 0,
      sizeBytes: computeEntrySizeBytes(diff, analyzedBy, llmSession),
      changedSinceReviewed: existing ? await changedSinceReviewed(existing, diff) : [],
    }
    await write(next)
    return next
  }

  return {
    get: key => exclusive(key, () => get(key)),
    getMeta: key => exclusive(key, () => getMeta(key)),
    async put(entry) {
      await exclusive(entry.key, () => write(entry))
      await enforceBudget()
    },
    async putDiff(key, diff, headSha, { viewed = true } = {}) {
      const entry = await exclusive(key, async () => writeDiff(key, await get(key), { diff, headSha }, viewed))
      await enforceBudget()
      return entry
    },
    async updateDiff(key, next, { viewed = true } = {}) {
      const entry = await exclusive(key, async () => {
        const existing = await get(key)
        const update = next(existing)
        return update && writeDiff(key, existing, update, viewed)
      })
      if (entry)
        await enforceBudget()
      return entry
    },
    touch: key => updateMeta(key, meta => ({ ...meta, lastViewedAt: Date.now() })),
    setChangedSinceReviewed: (key, paths) => updateMeta(key, meta => ({ ...meta, changedSinceReviewed: paths })),
    setSharedComment: (key, sharedComment) => updateMeta(key, meta => ({ ...meta, sharedComment })),
    setAnalyzedResult: (key, source, result) => updateBody(key, body => ({ ...body, analyzedBy: { ...body.analyzedBy, [source]: result } })),
    setReviewData: (key, reviews) => updateBody(key, body => ({ ...body, reviews })),
    async setLlmSession(key, session, expectedSession) {
      await exclusive(key, async () => {
        const entry = await get(key)
        if (expectedSession !== undefined && expectedSession !== JSON.stringify(entry?.llmSession ?? null))
          throw new Error('The chat changed on the server. Reload before saving.')
        if (!entry)
          return
        // A session written without its archive (a chat turn, a new analysis, the page) keeps the stored one.
        const archive = session && session.archive === undefined ? entry.llmSession?.archive : undefined
        // The persisted session keeps only the fields the schema checks; `AgentMessage` is wider.
        const llmSession = (archive ? { ...session, archive } : session) as PrCacheBody['llmSession']
        await write({ ...entry, llmSession, sizeBytes: computeEntrySizeBytes(entry.diff, entry.analyzedBy, llmSession) })
      })
      await enforceBudget()
    },
    remove(key) {
      return exclusive(key, async () => {
        await Promise.all([storage.removeItem(`${META_PREFIX}${key}`), storage.removeItem(`${BODY_PREFIX}${key}`), fileContents.removeScope(key)])
      })
    },
    async listRecent(limit) {
      return (await listMetas()).filter(meta => meta.lastViewedAt !== 0).sort((a, b) => b.lastViewedAt - a.lastViewedAt).slice(0, limit)
    },
    async listMatching(match) {
      const metas = (await listMetas()).filter(match)
      const entries = await Promise.all(metas.map(meta => exclusive(meta.key, async () => {
        const current = await getMeta(meta.key)
        const body = current && match(current) ? await getBody(meta.key) : undefined
        return current && body ? join(current, body) : undefined
      })))
      return entries.filter(entry => entry !== undefined)
    },
  }
}
