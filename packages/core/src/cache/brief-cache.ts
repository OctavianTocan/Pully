import type { Storage } from 'unstorage'
import type { BriefResult } from '../types/brief'
import * as v from 'valibot'
import { BriefResultSchema } from '../types/brief'

const BRIEF_KEY_PREFIX = 'brief:'
/** Briefs kept per diff, newest first. */
export const BRIEFS_PER_DIFF = 4

const StoredBriefsSchema = v.object({ entries: v.array(BriefResultSchema) })

export interface BriefCache {
  /** The newest brief of `scope`, preferring one of commit `headSha`. */
  latest: (scope: string, headSha?: string) => Promise<BriefResult | undefined>
  /** Saves `brief`, replacing an older one of the same head commit and language. */
  set: (scope: string, brief: BriefResult) => Promise<void>
}

/**
 * Briefs per diff scope.
 * @param storage - The storage every cache collection shares.
 * @returns The brief repository.
 */
export function createBriefCache(storage: Storage): BriefCache {
  async function read(scope: string): Promise<BriefResult[]> {
    const parsed = v.safeParse(StoredBriefsSchema, await storage.getItem(`${BRIEF_KEY_PREFIX}${scope}`))
    return parsed.success ? parsed.output.entries : []
  }
  return {
    async latest(scope, headSha) {
      const entries = await read(scope)
      return entries.find(entry => entry.headSha === headSha) ?? entries[0]
    },
    async set(scope, brief) {
      const rest = (await read(scope)).filter(entry => !(entry.headSha === brief.headSha && entry.locale === brief.locale))
      await storage.setItem(`${BRIEF_KEY_PREFIX}${scope}`, { entries: [brief, ...rest].slice(0, BRIEFS_PER_DIFF) })
    },
  }
}
