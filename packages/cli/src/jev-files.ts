import type { DiffsPayload, FileChange } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import type { JevChoiceAnswer, JevChoiceQuestion, JevClient } from './jev'
import { createHash } from 'node:crypto'

const STORAGE_PREFIX = 'jev-file:'
/** Files of one diff Jev looks at, at most; GitHub lists no more than 300 either. */
const MAX_FILES = 300
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
   * @returns The files classified so far; starts the rest in the background.
   */
  files: (diff: DiffsPayload) => Promise<JevFilesResult>
}

export interface FileAttentionOptions {
  jev: JevClient
  /** Where results outlive a restart (`jev-file:<fileKey>`). */
  storage?: Storage
  now?: () => number
}

/**
 * Which files of a diff need a human, by Jev. Each change to a file is classified once:
 * results are kept by {@link fileKey}, in memory and in `storage`.
 *
 * @param options The Jev client and where to keep results.
 * @returns The service behind the phone's `jev-files` route.
 */
export function createFileAttention(options: FileAttentionOptions): FileAttentionService {
  const { jev, storage, now = Date.now } = options
  const done = new Map<string, FileAttention>()
  const looked = new Set<string>()
  const running = new Set<string>()
  const failedAt = new Map<string, number>()

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

  return {
    async files(diff) {
      if (!jev.enabled())
        return JEV_FILES_OFF
      const candidates = diff.files.filter(file => !file.isBinary).slice(0, MAX_FILES).map(file => ({ file, key: fileKey(file) }))
      await Promise.all(candidates.map(async ({ key }) => {
        if (!storage || done.has(key) || looked.has(key))
          return
        const value = await storage.getItem(`${STORAGE_PREFIX}${key}`).catch(() => undefined)
        looked.add(key)
        if (isAttention(value))
          done.set(key, value)
      }))
      const files: Record<string, FileAttention> = {}
      let pending = 0
      for (const { file, key } of candidates) {
        const attention = done.get(key)
        if (attention) {
          files[file.path] = attention
          continue
        }
        const failed = failedAt.get(key)
        if (failed !== undefined && now() - failed < RETRY_FAILED_MS)
          continue
        pending++
        if (!running.has(key)) {
          running.add(key)
          // The client holds requests to its own concurrency limit; the phone asks again for the rest.
          void classify(file, key)
        }
      }
      // A refused key during this call turns everything off at once rather than leaving files pending forever.
      return jev.enabled() ? { enabled: true, files, pending } : JEV_FILES_OFF
    },
  }
}
