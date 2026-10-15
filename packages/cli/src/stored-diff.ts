import type { DiffCache } from '@pulls.review/core/cache'
import type { DiffHunk, DiffSource, DiffsPayload, FileChange } from '@pulls.review/core/types'
import { track } from './analytics'

/** Patch characters a lite answer carries in all; the files past it go out without hunks. */
export const PATCH_BUDGET = 600_000
/** A file whose own patch is this long always goes out without hunks in a lite answer. */
export const FILE_PATCH_BUDGET = 150_000
/** How long opening waits for GitHub's head commit before answering with the stored copy. */
export const HEAD_TIMEOUT_MS = 5_000
/** Paths one `diff-patches` request may name. */
export const MAX_PATCH_PATHS = 100

/** A file of a lite answer: `patchOmitted` when its hunks were left out and `diff-patches` has them. */
export type LiteFileChange = FileChange & { patchOmitted?: true }
export type LiteDiff = Omit<DiffsPayload, 'files'> & { files: LiteFileChange[] }

function patchLength(file: FileChange): number {
  return file.hunks.reduce((sum, hunk) => sum + hunk.patch.length, 0)
}

/**
 * The diff a phone can take in one answer: files keep their hunks, in diff order, while the
 * running patch total stays under `PATCH_BUDGET` and their own patch under `FILE_PATCH_BUDGET`.
 * A file that does not fit is skipped, not the end of the list, so smaller files after it still
 * come with hunks. Every other field is left as it is.
 *
 * @param diff The full diff.
 * @returns The trimmed diff and how many files lost their hunks.
 */
export function trimPatches(diff: DiffsPayload): { diff: LiteDiff, omitted: number } {
  let total = 0
  let omitted = 0
  const files = diff.files.map((file): LiteFileChange => {
    const size = patchLength(file)
    // A file without patch text (binary, truncated, a pure rename) has nothing to load later.
    if (size === 0 || (size < FILE_PATCH_BUDGET && total + size < PATCH_BUDGET)) {
      total += size
      return file
    }
    omitted++
    return { ...file, hunks: [], patchOmitted: true }
  })
  return { diff: { ...diff, files }, omitted }
}

export interface OpenStoredOptions {
  /** The diff's cache key, `serializeRef` of its ref. */
  key: string
  source: Pick<DiffSource, 'fetch' | 'fingerprint'>
  diffs: Pick<DiffCache, 'get' | 'getMeta' | 'putDiff' | 'touch'>
  /** Fetch from the source even when the stored copy is current. */
  fresh?: boolean
  headTimeoutMs?: number
}

export interface OpenedDiff {
  diff: DiffsPayload
  /** The source could not be asked, so this is the stored copy and may be behind it. */
  stale?: true
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error('The source did not answer in time.')), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * Opens a diff from the server's store when it is current, so a phone opening even a huge pull
 * request waits on one small GitHub request rather than every page of files. Opening counts as
 * a view. Without `fresh`, a source that can't be reached falls back to the stored copy, marked
 * `stale`.
 *
 * @param options The key, the source, the cache, and whether to skip the stored copy.
 * @returns The diff to show and whether it may be out of date.
 */
export async function openStoredDiff(options: OpenStoredOptions): Promise<OpenedDiff> {
  const { key, source, diffs, fresh = false, headTimeoutMs = HEAD_TIMEOUT_MS } = options
  async function fetchAndStore(): Promise<DiffsPayload> {
    const diff = await source.fetch()
    await diffs.putDiff(key, diff, diff.head?.sha ?? '')
    return diff
  }
  const started = Date.now()
  const note = (outcome: 'fetch' | 'hit' | 'refetch' | 'stale') => track('cache.diff', { outcome, fresh, ms: Date.now() - started })
  if (fresh || !source.fingerprint || !await diffs.getMeta(key)) {
    const diff = await fetchAndStore()
    note('fetch')
    return { diff }
  }

  // The head request and the disk read run together; neither waits on the other.
  const [stored, head] = await Promise.all([
    diffs.get(key),
    withTimeout(source.fingerprint(), headTimeoutMs).then(sha => ({ sha }), () => undefined),
  ])
  if (!stored) {
    const diff = await fetchAndStore()
    note('fetch')
    return { diff }
  }
  if (head?.sha === stored.headSha) {
    await diffs.touch(key)
    note('hit')
    return { diff: stored.diff }
  }
  if (head) {
    try {
      const diff = await fetchAndStore()
      note('refetch')
      return { diff }
    }
    catch {
      // GitHub answered the head but not the diff: the older copy beats an error page.
    }
  }
  await diffs.touch(key)
  note('stale')
  return { diff: stored.diff, stale: true }
}

/** One file's hunks as `diff-patches` answers them, with its blob sha to match against the diff the client holds. */
export interface StoredPatch {
  hunks: DiffHunk[]
  sha: string
}

export interface PatchReader {
  /** Keeps a diff just served, so the patch requests that follow opening it skip the disk. */
  remember: (key: string, headSha: string, diff: DiffsPayload) => void
  /**
   * @param key The stored diff's key.
   * @param paths Paths of its files; a path not in the diff is left out.
   * @returns Each found file's hunks, by path.
   * @throws When nothing is stored under `key`.
   */
  read: (key: string, paths: string[]) => Promise<Record<string, StoredPatch>>
}

/**
 * Reads hunks of stored diffs. A phone asks for a big diff's patches in many small requests,
 * so the last few diffs stay in memory for as long as the stored head is the same.
 *
 * @param diffs The cache the diffs are stored in.
 * @param keep How many diffs to keep in memory.
 * @returns A reader over `diffs`.
 */
export function createPatchReader(diffs: Pick<DiffCache, 'get' | 'getMeta'>, keep = 2): PatchReader {
  const recent = new Map<string, { headSha: string, files: Map<string, FileChange> }>()

  function remember(key: string, headSha: string, diff: DiffsPayload) {
    recent.delete(key)
    recent.set(key, { headSha, files: new Map(diff.files.map(file => [file.path, file])) })
    for (const old of recent.keys()) {
      if (recent.size <= keep)
        break
      recent.delete(old)
    }
  }

  async function filesOf(key: string): Promise<Map<string, FileChange>> {
    const meta = await diffs.getMeta(key)
    if (!meta)
      throw new Error('Open the diff first.')
    const held = recent.get(key)
    if (held?.headSha === meta.headSha)
      return held.files
    const entry = await diffs.get(key)
    if (!entry)
      throw new Error('Open the diff first.')
    remember(key, entry.headSha, entry.diff)
    return recent.get(key)!.files
  }

  return {
    remember,
    async read(key, paths) {
      const files = await filesOf(key)
      const found: Record<string, StoredPatch> = {}
      for (const path of paths) {
        const file = files.get(path)
        if (file)
          found[path] = { hunks: file.hunks, sha: file.sha }
      }
      return found
    },
  }
}
