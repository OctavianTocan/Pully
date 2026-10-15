import type { AnalyticsTracker, AnalyticsValue } from '@pulls.review/core/analytics'
import type { DiffsPayload } from '@pulls.review/core/types'

/**
 * Usage analytics for the web app our server serves. Only the `PR_LOCAL` build installs a
 * tracker (`local/analytics.ts`); on the public site, which has no server of ours behind it,
 * every call here does nothing.
 */
let tracker: AnalyticsTracker | undefined

export function setAnalyticsTracker(next: AnalyticsTracker | undefined): void {
  tracker = next
}

/**
 * Records an event for the Stats page. Props are counts, timings and enums, never what was
 * read or typed (root `CONTRACTS`, `analytics-what-not-how-much`).
 */
export function track(name: string, props?: Record<string, AnalyticsValue | undefined>): void {
  tracker?.track(name, props)
}

/** What a pull request or a diff is, for `pr.open`: its repo and number, never a branch or a path. */
export function diffFacts(diff: DiffsPayload): Record<string, AnalyticsValue | undefined> {
  const ref = diff.ref
  return {
    kind: ref.kind,
    repo: 'owner' in ref && 'repo' in ref ? `${ref.owner}/${ref.repo}` : undefined,
    number: ref.kind === 'github-pr' ? Number(ref.number) : undefined,
    files: diff.files.length,
    additions: diff.files.reduce((sum, file) => sum + file.additions, 0),
    deletions: diff.files.reduce((sum, file) => sum + file.deletions, 0),
  }
}
