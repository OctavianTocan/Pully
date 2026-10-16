import type { CacheRepositories } from '@pulls.review/core/cache'
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { SnapshotStore } from './snapshots'
import { serializeRef } from '@pulls.review/core/types'

export interface PrefetchOptions {
  snapshots: SnapshotStore
  cache: CacheRepositories
  /** Fetches a pull request's diff from GitHub. */
  fetchDiff: (pull: MyPull) => Promise<DiffsPayload>
  /** How long the pointer must rest on a row before it counts. */
  delayMs?: number
  /** Pull requests fetched at the same time. */
  concurrency?: number
  /** Larger pull requests are left for an explicit open. */
  maxFiles?: number
}

export interface Prefetcher {
  /** The pointer rests on a row: prefetch it after a short delay. */
  hover: (pull: MyPull) => void
  /** The pointer left before the delay ran out. */
  leave: (pull: MyPull) => void
  /**
   * Saves a pull request's page for an instant open, unless an up-to-date copy exists.
   *
   * @param pull The pull request.
   * @returns Once it is saved or skipped; never rejects.
   */
  prefetch: (pull: MyPull) => Promise<void>
}

/**
 * Saves the page of a hovered pull request ahead of the click, so opening it renders
 * at once. Only open pull requests whose head commit is known are prefetched.
 *
 * @param options Where pages are kept and how to fetch them.
 * @returns The prefetcher.
 */
export function createPrefetcher(options: PrefetchOptions): Prefetcher {
  const { snapshots, cache, fetchDiff, delayMs = 150, concurrency = 2, maxFiles = 300 } = options
  const timers = new Map<string, ReturnType<typeof setTimeout>>()
  const started = new Map<string, Promise<void>>()
  const queue: (() => void)[] = []
  let running = 0

  const keyOf = (pull: MyPull) => serializeRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })

  function slot<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      queue.push(() => {
        running++
        task().then(resolve, reject).finally(() => {
          running--
          queue.pop()?.()
        })
      })
      if (running < concurrency)
        queue.pop()?.()
    })
  }

  async function save(pull: MyPull, key: string, headSha: string) {
    if (await snapshots.headSha(key) === headSha)
      return
    let entry = await cache.diffs.get(key)
    if (entry?.headSha !== headSha) {
      const diff = await fetchDiff(pull)
      entry = await cache.diffs.putDiff(key, diff, diff.head?.sha ?? '', { expectedHeadSha: entry?.headSha ?? null })
    }
    const reviewed = await cache.reviewMarks.get(entry.diff.files.map(file => file.sha))
    await snapshots.put(key, entry, reviewed)
  }

  function prefetch(pull: MyPull): Promise<void> {
    const headSha = pull.headSha
    if (pull.state !== 'open' || !headSha || (pull.changedFiles ?? 0) > maxFiles)
      return Promise.resolve()
    const key = keyOf(pull)
    const id = `${key}@${headSha}`
    let job = started.get(id)
    if (!job) {
      job = slot(() => save(pull, key, headSha)).catch(() => {
        // A failed prefetch only means the click loads normally; let a later hover retry.
        started.delete(id)
      })
      started.set(id, job)
    }
    return job
  }

  return {
    hover(pull) {
      const key = keyOf(pull)
      if (timers.has(key))
        return
      timers.set(key, setTimeout(() => {
        timers.delete(key)
        void prefetch(pull)
      }, delayMs))
    },
    leave(pull) {
      const key = keyOf(pull)
      clearTimeout(timers.get(key))
      timers.delete(key)
    },
    prefetch,
  }
}
