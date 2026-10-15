import type { PullRequestListPage } from '@pulls.review/core/types'
import type { SwrCache } from './swr'
import { createSwrCache } from './swr'

/** A first page younger than this is answered as it is; an older one is answered at once and fetched again behind it. */
export const REPO_PULLS_FRESH_MS = 60_000
/** The Inbox's repositories have their first page fetched ahead when the copy is older than this. */
export const REPO_PULLS_WARM_MS = 5 * 60_000
/** How many of the Inbox's repositories are fetched ahead, the busiest first. */
export const REPO_PULLS_WARM_LIMIT = 12

/** A first page and when the server fetched it, so the phone knows whether to ask again for a newer one. */
export type CachedPullsPage = PullRequestListPage & { cachedAt: number }

export interface RepoPullsCache {
  /**
   * The first page of a repository's open pull requests. One the server holds is answered without waiting for GitHub,
   * and fetched again behind the answer once it is older than {@link REPO_PULLS_FRESH_MS}; `fresh` waits for GitHub.
   */
  first: (owner: string, repo: string, fresh?: boolean) => Promise<CachedPullsPage>
  /** Fetches ahead the first page of each repository in `repos` the server holds no young copy of, one at a time. */
  warm: (repos: { owner: string, repo: string }[]) => void
}

function keyOf(owner: string, repo: string): string {
  return `${owner}/${repo}`.toLowerCase()
}

/**
 * The repository page opened slowly because every open asked GitHub (Tavi, 15 Oct: "the server should already have
 * them"). The server keeps each first page, answers from it at once, and fetches the Inbox's repositories ahead.
 *
 * @param fetchFirst Fetches a repository's first page from GitHub.
 * @param now The clock, replaceable in tests.
 * @returns An empty cache.
 */
export function createRepoPullsCache(fetchFirst: (owner: string, repo: string) => Promise<PullRequestListPage>, now: () => number = Date.now): RepoPullsCache {
  const names = new Map<string, { owner: string, repo: string }>()
  const pages: SwrCache<PullRequestListPage> = createSwrCache((key) => {
    const name = names.get(key)
    if (!name)
      return Promise.reject(new Error(`No repository for ${key}`))
    return fetchFirst(name.owner, name.repo)
  }, now)
  let warming = false

  function remember(owner: string, repo: string): string {
    const key = keyOf(owner, repo)
    names.set(key, { owner, repo })
    return key
  }

  return {
    async first(owner, repo, fresh) {
      const key = remember(owner, repo)
      const entry = fresh ? await pages.refresh(key) : pages.peekAndRevalidate(key, REPO_PULLS_FRESH_MS) ?? await pages.refresh(key)
      return { ...entry.value, cachedAt: entry.fetchedAt }
    },
    warm(repos) {
      if (warming)
        return
      const counts = new Map<string, { owner: string, repo: string, count: number }>()
      for (const { owner, repo } of repos) {
        const key = keyOf(owner, repo)
        const seen = counts.get(key)
        counts.set(key, { owner, repo, count: (seen?.count ?? 0) + 1 })
      }
      const due = [...counts.values()]
        .sort((a, b) => b.count - a.count)
        .slice(0, REPO_PULLS_WARM_LIMIT)
        .filter(({ owner, repo }) => {
          const entry = pages.peek(keyOf(owner, repo))
          return !entry || now() - entry.fetchedAt >= REPO_PULLS_WARM_MS
        })
      if (!due.length)
        return
      warming = true
      void (async () => {
        try {
          for (const { owner, repo } of due) {
            // One failure (a repository the token cannot read) only leaves that page to be fetched on open.
            await pages.refresh(remember(owner, repo)).catch(() => {})
          }
        }
        finally {
          warming = false
        }
      })()
    },
  }
}
