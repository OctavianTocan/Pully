/** Persistence as repositories over any unstorage `Storage` (IndexedDB, memory, fs, ...). */
import type { Storage } from 'unstorage'
import type { BriefCache } from './cache/brief-cache'
import type { CritiqueCache } from './cache/critique-cache'
import type { CacheBudget, DiffCache } from './cache/diff-cache'
import type { FileContentCache } from './cache/file-content-cache'
import type { PullRequestListCache } from './cache/pull-request-list-cache'
import type { ReviewMarks } from './cache/review-marks'
import type { TourCache } from './cache/tour-cache'
import { createBriefCache } from './cache/brief-cache'
import { createCritiqueCache } from './cache/critique-cache'
import { createDiffCache } from './cache/diff-cache'
import { createFileContentCache } from './cache/file-content-cache'
import { createPullRequestListCache } from './cache/pull-request-list-cache'
import { createReviewMarks } from './cache/review-marks'
import { createTourCache } from './cache/tour-cache'

export * from './cache/brief-cache'
export * from './cache/critique-cache'
export * from './cache/diff-cache'
export * from './cache/file-content-cache'
export * from './cache/pull-request-list-cache'
export * from './cache/review-marks'
export * from './cache/schema'
export * from './cache/tour-cache'

export interface CacheRepositories {
  diffs: DiffCache
  reviewMarks: ReviewMarks
  fileContents: FileContentCache
  pullRequestLists: PullRequestListCache
  critiques: CritiqueCache
  tours: TourCache
  briefs: BriefCache
}

/**
 * The budget for the server's on-disk cache (`pulls.review` keeps every open pull request
 * stored, plus what was opened). Every repositories object over that storage MUST use it:
 * each one enforces its own budget, so one built with the browser-sized default would evict
 * the server's entries and prune their review marks.
 */
export const SERVER_CACHE_BUDGET: CacheBudget = { maxBytes: 1024 * 1024 * 1024, maxEntries: 400 }

/** Every collection shares one storage by key prefix (`pr-meta:*`, `review:*`, ...). */
export function createCacheRepositories(storage: Storage, budget?: CacheBudget): CacheRepositories {
  const reviewMarks = createReviewMarks(storage)
  return {
    diffs: createDiffCache(storage, reviewMarks, budget),
    reviewMarks,
    fileContents: createFileContentCache(storage),
    pullRequestLists: createPullRequestListCache(storage),
    critiques: createCritiqueCache(storage),
    tours: createTourCache(storage),
    briefs: createBriefCache(storage),
  }
}
