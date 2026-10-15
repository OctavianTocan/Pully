import type { AiActivity, AiPrContext, MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import type { PrTriage } from './ai/pr-tools'
import { sharedJev } from './jev'
import { fileAttentionFor, MAX_RATED_FILES, sharedFileAttention } from './jev-files'
import { classifyPull, jevOfPull } from './my-pulls'

/**
 * Jev's part in an AI review. Jev is asked only when a review of a diff starts (an analysis, a review for problems, a
 * tour or a brief), never for every diff that opens: it rates the files and sorts the pull request then, and the
 * review's model reads both through its `triage_files` tool.
 */

/** How long a review waits for Jev before it starts with what is rated by then. */
export const JEV_REVIEW_WAIT_MS = 20_000

/**
 * @param diff A loaded diff.
 * @returns The open pull request it is, as the list would hold it, or `undefined` for anything else.
 */
export function pullOfDiff(diff: DiffsPayload): MyPull | undefined {
  const { ref } = diff
  const state = diff.pullRequest?.state
  if (ref.kind !== 'github-pr' || !diff.head || state === 'closed' || state === 'merged')
    return undefined
  return {
    owner: ref.owner,
    repo: ref.repo,
    number: Number(ref.number),
    title: diff.title,
    author: diff.author?.name ?? '',
    isDraft: state === 'draft',
    updatedAt: diff.updatedAt ?? '',
    commentsCount: 0,
    labels: [],
    state: 'open',
    role: 'involved',
    headSha: diff.head.sha,
    headRef: diff.head.ref,
    baseRef: diff.base?.ref,
  }
}

export interface PrepareJevOptions {
  /** Where ratings outlive a restart. */
  storage?: Storage
  /** The review's work log: one line while Jev looks. */
  onActivity?: (activities: AiActivity[]) => void
  waitMs?: number
}

/**
 * Has Jev rate the files of `diff` and sort the pull request it belongs to, before a review reads them. Waits at most
 * `waitMs`; whatever is still out carries on in the background. Never throws, and does nothing while Jev is off.
 *
 * @param diff The diff a review is about to read.
 * @param options Where results are kept, and the review's work log.
 */
export async function prepareJev(diff: DiffsPayload, options: PrepareJevOptions = {}): Promise<void> {
  if (!sharedJev().enabled())
    return
  const { storage, onActivity, waitMs = JEV_REVIEW_WAIT_MS } = options
  const attention = sharedFileAttention(storage)
  const pull = pullOfDiff(diff)
  // A tour or brief after an analysis finds everything rated already: nothing to ask, nothing to show.
  const [rated, sortedBefore] = await Promise.all([
    attention.known(diff).then(known => Object.keys(known.files).length).catch(() => 0),
    pull ? jevOfPull(pull).catch(() => undefined) : Promise.resolve(true),
  ])
  if (rated >= Math.min(diff.files.filter(file => !file.isBinary).length, MAX_RATED_FILES) && sortedBefore)
    return
  const step: AiActivity = { id: 'jev-triage', kind: 'status', title: 'Finding the files that need a careful look', status: 'running', startedAt: Date.now(), rev: 0 }
  onActivity?.([step])
  const sorted = pull && !sortedBefore ? classifyPull(pull).catch(() => {}) : Promise.resolve()
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, waitMs)
  })
  await Promise.all([
    attention.rate(diff, { waitMs }).catch(() => {}),
    Promise.race([sorted, timeout]),
  ])
  clearTimeout(timer)
  onActivity?.([{ ...step, status: 'done', endedAt: Date.now() }])
}

/**
 * @param context The diff an AI job reads.
 * @returns What Jev said about it, for the `triage_files` tool; `undefined` when it said nothing.
 */
export async function triageFor(context: AiPrContext): Promise<PrTriage | undefined> {
  const files = fileAttentionFor(context)
  const pull = context.number === undefined
    ? undefined
    : await jevOfPull({ owner: context.owner, repo: context.repo, number: context.number, headSha: context.headSha, title: context.title ?? '' }).catch(() => undefined)
  return files || pull ? { files: files ?? {}, ...(pull ? { pull } : {}) } : undefined
}
