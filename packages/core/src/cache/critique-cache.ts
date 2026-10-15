import type { Storage } from 'unstorage'
import type { CritiqueResult } from '../types/analyze'
import * as v from 'valibot'
import { CritiqueResultSchema } from '../types/analyze'
import { storageTransaction } from './transaction'

const CRITIQUE_KEY_PREFIX = 'critique:'
/** Critiques kept per diff; older ones are dropped as new ones are saved. */
export const CRITIQUES_PER_DIFF = 8

const StoredCritiquesSchema = v.object({
  entries: v.array(v.object({ variant: v.string(), result: CritiqueResultSchema })),
})
type StoredCritiques = v.InferOutput<typeof StoredCritiquesSchema>

export interface CritiqueCache {
  /** The critique saved under `variant` for the diff `scope`, if any. */
  get: (scope: string, variant: string) => Promise<CritiqueResult | undefined>
  /** The newest critique saved for `scope`, optionally only among those of commit `headSha`. */
  latest: (scope: string, headSha?: string) => Promise<CritiqueResult | undefined>
  /** The newest saved variant and its result, optionally limited to one head commit. */
  latestEntry: (scope: string, headSha?: string) => Promise<{ variant: string, result: CritiqueResult } | undefined>
  /** Saves `result` under `variant`, replacing what that variant held. */
  set: (scope: string, variant: string, result: CritiqueResult) => Promise<void>
}

function critiqueKey(scope: string): string {
  return `${CRITIQUE_KEY_PREFIX}${scope}`
}

/**
 * Critiques per diff, keyed by a caller-chosen variant (head commit, engine, model, effort, lens).
 * @param storage - The storage every cache collection shares.
 * @returns The critique repository.
 */
export function createCritiqueCache(storage: Storage): CritiqueCache {
  async function read(scope: string): Promise<StoredCritiques['entries']> {
    const parsed = v.safeParse(StoredCritiquesSchema, await storage.getItem(critiqueKey(scope)))
    // A corrupt or previous-shape entry is a cache miss rather than an error.
    return parsed.success ? parsed.output.entries : []
  }

  return {
    async get(scope, variant) {
      return (await read(scope)).find(entry => entry.variant === variant)?.result
    },
    async latest(scope, headSha) {
      return (await read(scope)).find(entry => headSha === undefined || entry.result.headSha === headSha)?.result
    },
    async latestEntry(scope, headSha) {
      return (await read(scope)).find(entry => headSha === undefined || entry.result.headSha === headSha)
    },
    set(scope, variant, result) {
      return storageTransaction(storage, critiqueKey(scope), async () => {
        const entries = (await read(scope)).filter(entry => entry.variant !== variant)
        const stored: StoredCritiques = { entries: [{ variant, result }, ...entries].slice(0, CRITIQUES_PER_DIFF) }
        await storage.setItem(critiqueKey(scope), stored)
      })
    },
  }
}
