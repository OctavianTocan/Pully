import type { CheckBucket } from '@pulls.review/core/github'
import type { ChecksStatus, ReviewDecision } from '@pulls.review/core/types'

/** @unocss-include */

/** GitHub's own glyphs and colors for a CI rollup. */
export const CHECKS_ICON: Record<ChecksStatus, string> = {
  success: 'i-lu-check text-green-600 dark:text-green-400',
  failure: 'i-lu-close text-red-600 dark:text-red-400',
  pending: 'i-ph:circle-fill text-amber-500',
}

export const CHECK_BUCKET_ICON: Record<CheckBucket, string> = {
  failing: CHECKS_ICON.failure,
  pending: CHECKS_ICON.pending,
  passing: CHECKS_ICON.success,
  skipped: 'i-ph:prohibit op-mute',
}

export const REVIEW_DECISION_ICON: Record<ReviewDecision, string> = {
  approved: 'i-lu-okc text-green-600 dark:text-green-400',
  changes_requested: 'i-lu-xc text-red-600 dark:text-red-400',
  review_required: 'i-ph:eye op-fade',
}
