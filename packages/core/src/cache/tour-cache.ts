import type { Storage } from 'unstorage'
import type { TourResult } from '../types/tour'
import * as v from 'valibot'
import { TourResultSchema } from '../types/tour'

const TOUR_KEY_PREFIX = 'tour:'
/** Tours kept per diff, newest first. */
export const TOURS_PER_DIFF = 4

const StoredToursSchema = v.object({ entries: v.array(TourResultSchema) })

export interface TourCache {
  /** The newest tour of `scope`, preferring one of commit `headSha`. */
  latest: (scope: string, headSha?: string) => Promise<TourResult | undefined>
  /** Saves `tour`, replacing an older one of the same head commit and language. */
  set: (scope: string, tour: TourResult) => Promise<void>
}

/**
 * Tours per diff scope.
 * @param storage - The storage every cache collection shares.
 * @returns The tour repository.
 */
export function createTourCache(storage: Storage): TourCache {
  async function read(scope: string): Promise<TourResult[]> {
    const parsed = v.safeParse(StoredToursSchema, await storage.getItem(`${TOUR_KEY_PREFIX}${scope}`))
    return parsed.success ? parsed.output.entries : []
  }
  return {
    async latest(scope, headSha) {
      const entries = await read(scope)
      return entries.find(entry => entry.headSha === headSha) ?? entries[0]
    },
    async set(scope, tour) {
      const rest = (await read(scope)).filter(entry => !(entry.headSha === tour.headSha && entry.locale === tour.locale))
      await storage.setItem(`${TOUR_KEY_PREFIX}${scope}`, { entries: [tour, ...rest].slice(0, TOURS_PER_DIFF) })
    },
  }
}
