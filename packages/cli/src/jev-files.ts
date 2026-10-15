import type { DiffsPayload, FileChange } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import type { JevChoiceAnswer, JevChoiceQuestion, JevClient } from './jev'
import { createHash } from 'node:crypto'
import { sharedJev } from './jev'

const STORAGE_PREFIX = 'jev-file:'
/** Files of one diff Jev looks at, at most; GitHub lists no more than 300 either. */
export const MAX_RATED_FILES = 300
/** About 1,500 tokens of patch: enough to see what kind of change it is. */
const PATCH_CHARS = 6_000
/** Below this Jev is unsure, and an unsure file gets a human. */
const MIN_CONFIDENCE = 0.5
/** A file Jev could not classify is tried again after this long, not on every poll. */
const RETRY_FAILED_MS = 10 * 60_000

export const FILE_PRIORITIES = ['p0', 'p1', 'p2'] as const
export type FilePriority = typeof FILE_PRIORITIES[number]

export const FILE_QUESTIONS = {
  attention: {
    type: 'choice',
    instructions: 'How much attention does a reviewer need to give this changed file? Judge the change itself, not how large it is.',
    criteria: {
      p0: 'Needs human judgment: logic, security, data handling, API contracts, concurrency or money. A mistake here could break behaviour or leak data.',
      p1: 'Worth a look: ordinary code changes that follow what the code already does.',
      p2: 'Low attention: generated files, lockfiles, formatting, renames, snapshots or trivial docs.',
    },
  } satisfies JevChoiceQuestion,
}

export interface FileAttention {
  priority: FilePriority
  /** Jev's confidence in its choice, 0 to 1; under 0.5 the file counts as `p0`. */
  confidence: number
}

/** The `jev-files` answer. */
export interface JevFilesResult {
  enabled: boolean
  /** By path, the files classified so far. */
  files: Record<string, FileAttention>
  /** Files still being classified; ask again for them. */
  pending: number
}

export const JEV_FILES_OFF: JevFilesResult = { enabled: false, files: {}, pending: 0 }

/**
 * @param file A changed file.
 * @returns What Jev is asked about: its path, status, size and the start of its patch.
 */
export function fileState(file: FileChange) {
  const patch = file.hunks.map(hunk => `${hunk.header}\n${hunk.patch}`).join('\n')
  return { path: file.path, status: file.status, additions: file.additions, deletions: file.deletions, patch: patch.slice(0, PATCH_CHARS) }
}

/**
 * @param answer Jev's answer to `attention`.
 * @returns The priority to show; an unsure answer, or an option Jev should not have chosen, is `p0`.
 */
export function priorityOf(answer: JevChoiceAnswer): FileAttention {
  const known = (FILE_PRIORITIES as readonly string[]).includes(answer.choice)
  return { priority: known && answer.confidence >= MIN_CONFIDENCE ? answer.choice as FilePriority : 'p0', confidence: answer.confidence }
}

/**
 * One change to a file: what Jev is shown of it, and the blob it ends at. The same blob reached from another
 * base (another pull request, a commit range) is another change and is asked about on its own.
 */
function fileKey(file: FileChange): string {
  return createHash('sha256').update(JSON.stringify([file.sha, fileState(file)])).digest('hex')
}

function isAttention(value: unknown): value is FileAttention {
  const saved = value as FileAttention | null | undefined
  return (FILE_PRIORITIES as readonly unknown[]).includes(saved?.priority) && typeof saved?.confidence === 'number'
}

export interface FileAttentionService {
  /**
   * @param diff The cached diff.
   * @returns What is rated for it so far, and how many of its files are being rated now. Never asks Jev.
   */
  known: (diff: DiffsPayload) => Promise<JevFilesResult>
  /**
   * Rates the files of `diff` that have no rating yet. Jev is asked only here, so it runs when a review asks for it
   * rather than for every diff that opens.
   *
   * @param diff The cached diff.
   * @param options How long to wait.
   * @param options.waitMs How long to wait for the answers; the rest carry on in the background.
   * @returns What is rated once every file is, or once `waitMs` passes.
   */
  rate: (diff: DiffsPayload, options?: { waitMs?: number }) => Promise<JevFilesResult>
  /**
   * @param ref The diff an AI job reads: its repository and the commits it spans.
   * @returns Its files' ratings by path, as `known` or `rate` last read them; `undefined` when there are none.
   */
  forDiff: (ref: DiffIdentity) => Record<string, FileAttention> | undefined
}

/** A diff as an AI job knows it (`AiPrContext`): a repository and the commits it spans. */
export interface DiffIdentity {
  owner: string
  repo: string
  baseSha?: string
  headSha: string
}

export interface FileAttentionOptions {
  jev: JevClient
  /** Where results outlive a restart (`jev-file:<fileKey>`). */
  storage?: Storage
  now?: () => number
  sleep?: (ms: number) => Promise<void>
}

/** Diffs whose ratings `forDiff` remembers; the oldest is forgotten past this. */
const REMEMBERED_DIFFS = 50
/** How long `rate` waits by default: an analysis starts with what is rated by then. */
const RATE_WAIT_MS = 20_000

function identityKey(ref: DiffIdentity): string {
  return `${ref.owner}/${ref.repo}@${ref.baseSha ?? ''}..${ref.headSha}`
}

function identityOf(diff: DiffsPayload): string | undefined {
  const ref = diff.ref as { owner?: string, repo?: string }
  return ref.owner && ref.repo && diff.head ? identityKey({ owner: ref.owner, repo: ref.repo, baseSha: diff.base?.sha, headSha: diff.head.sha }) : undefined
}

/**
 * Which files of a diff need a human, by Jev. Each change to a file is classified once:
 * results are kept by {@link fileKey}, in memory and in `storage`.
 *
 * @param options The Jev client and where to keep results.
 * @returns The service behind the phone's `jev-files` route and the AI review's `triage_files` tool.
 */
export function createFileAttention(options: FileAttentionOptions): FileAttentionService {
  const { jev, storage, now = Date.now, sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) } = options
  const done = new Map<string, FileAttention>()
  const looked = new Set<string>()
  const running = new Map<string, Promise<void>>()
  const failedAt = new Map<string, number>()
  const byDiff = new Map<string, Record<string, FileAttention>>()

  async function classify(file: FileChange, key: string): Promise<void> {
    try {
      const answer = await jev.ask(fileState(file), FILE_QUESTIONS)
      if (answer === 'off')
        return
      const attention = priorityOf(answer.answers.attention)
      done.set(key, attention)
      failedAt.delete(key)
      await storage?.setItem(`${STORAGE_PREFIX}${key}`, attention).catch(() => {})
    }
    catch {
      failedAt.set(key, now())
    }
    finally {
      running.delete(key)
    }
  }

  /** The files Jev looks at, each with its key, and what an earlier run saved for them. */
  async function candidatesOf(diff: DiffsPayload) {
    const candidates = diff.files.filter(file => !file.isBinary).slice(0, MAX_RATED_FILES).map(file => ({ file, key: fileKey(file) }))
    await Promise.all(candidates.map(async ({ key }) => {
      if (!storage || done.has(key) || looked.has(key))
        return
      const value = await storage.getItem(`${STORAGE_PREFIX}${key}`).catch(() => undefined)
      looked.add(key)
      if (isAttention(value))
        done.set(key, value)
    }))
    return candidates
  }

  function remember(diff: DiffsPayload, files: Record<string, FileAttention>): void {
    const id = identityOf(diff)
    if (!id)
      return
    byDiff.delete(id)
    byDiff.set(id, files)
    if (byDiff.size > REMEMBERED_DIFFS)
      byDiff.delete(byDiff.keys().next().value!)
  }

  async function known(diff: DiffsPayload): Promise<JevFilesResult> {
    if (!jev.enabled())
      return JEV_FILES_OFF
    const files: Record<string, FileAttention> = {}
    let pending = 0
    for (const { file, key } of await candidatesOf(diff)) {
      const attention = done.get(key)
      if (attention)
        files[file.path] = attention
      else if (running.has(key))
        pending++
    }
    remember(diff, files)
    // A refused key during this call turns everything off at once rather than leaving files pending forever.
    return jev.enabled() ? { enabled: true, files, pending } : JEV_FILES_OFF
  }

  return {
    known,
    async rate(diff, { waitMs = RATE_WAIT_MS } = {}) {
      if (!jev.enabled())
        return JEV_FILES_OFF
      const asked: Promise<void>[] = []
      for (const { file, key } of await candidatesOf(diff)) {
        if (done.has(key))
          continue
        const failed = failedAt.get(key)
        if (failed !== undefined && now() - failed < RETRY_FAILED_MS)
          continue
        let work = running.get(key)
        if (!work) {
          // The client holds requests to its own concurrency limit, so every file can be handed over at once.
          work = classify(file, key)
          running.set(key, work)
        }
        asked.push(work)
      }
      if (asked.length)
        await Promise.race([Promise.all(asked), sleep(waitMs)])
      return known(diff)
    },
    forDiff(ref) {
      const files = byDiff.get(identityKey(ref))
      return files && Object.keys(files).length ? files : undefined
    },
  }
}

let shared: FileAttentionService | undefined

/**
 * The process's one service, so the phone's route and the AI tasks share what is rated and in flight.
 *
 * @param storage Where results outlive a restart; the first caller's is kept.
 * @returns The service.
 */
export function sharedFileAttention(storage?: Storage): FileAttentionService {
  return shared ??= createFileAttention({ jev: sharedJev(), storage })
}

/**
 * @param ref The diff an AI job reads.
 * @returns Its files' ratings when this process rated or read them, without starting the service.
 */
export function fileAttentionFor(ref: DiffIdentity): Record<string, FileAttention> | undefined {
  return shared?.forDiff(ref)
}
