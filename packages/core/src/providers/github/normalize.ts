import type { DiffHunk, DiffsPayload, FileChange, FileChangeStatus, PullRequestState } from '../../types/diff'
import type { GithubPullRequestCommitJson, GithubPullRequestFileJson, GithubPullRequestJson } from './api'
import { createTwoFilesPatch } from 'diff'
import { parseHunks, parsePatch } from '../../patch-parser'

const STATUS_MAP: Record<string, FileChangeStatus> = {
  added: 'added',
  removed: 'removed',
  modified: 'modified',
  renamed: 'renamed',
  copied: 'copied',
  changed: 'modified',
  unchanged: 'modified',
}

/** How to recover a patch GitHub left out of the files listing. */
export interface PatchFallbacks {
  /** The whole diff as `.diff` text; `undefined` when GitHub refuses to render it (too many files / too large). */
  loadDiffText: () => Promise<string | undefined>
  /** A file's content at a commit; `undefined` when the file doesn't exist there. */
  loadFileContent: (path: string, ref: string) => Promise<string | undefined>
}

/** The two commits a files listing compares; `base` is absent for a root commit. */
export interface ComparedShas {
  base?: string
  head: string
}

/**
 * Past these we don't diff a file ourselves. A line diff costs about lines × changed lines:
 * a generated 10 MB file with 90k changed lines held the server for over ten minutes, and even
 * 40k lines with 2k changes takes seconds. Such a file shows as too large, with its full file a tap away.
 */
export const MAX_SELF_DIFF_CHANGES = 5_000
export const MAX_SELF_DIFF_CHARS = 2 * 1024 * 1024
export const MAX_SELF_DIFF_COST = 100_000_000
/** Gives up on a diff still running after this long, should the counts GitHub gave undersell it. */
const SELF_DIFF_TIMEOUT_MS = 10_000

function lineCount(text: string): number {
  let count = 1
  for (let at = text.indexOf('\n'); at !== -1; at = text.indexOf('\n', at + 1))
    count++
  return count
}

/** Line-diffs two texts a step per timer tick, so other requests keep being served; `undefined` when it gives up. */
function diffTexts(oldPath: string, newPath: string, oldContent: string, newContent: string): Promise<string | undefined> {
  return new Promise((resolve) => {
    createTwoFilesPatch(oldPath, newPath, oldContent, newContent, undefined, undefined, {
      maxEditLength: MAX_SELF_DIFF_CHANGES,
      timeout: SELF_DIFF_TIMEOUT_MS,
      callback: resolve,
    })
  })
}

/** Last resort for diffs too large for GitHub to render: diff the two file versions ourselves; `undefined` when too large for that too. */
async function diffFileContents(file: GithubPullRequestFileJson, shas: ComparedShas, loadFileContent: PatchFallbacks['loadFileContent']): Promise<DiffHunk[] | undefined> {
  const changes = file.additions + file.deletions
  if (changes > MAX_SELF_DIFF_CHANGES)
    return undefined
  const oldPath = file.previous_filename ?? file.filename
  const [oldContent = '', newContent = ''] = await Promise.all([
    file.status === 'added' || !shas.base ? undefined : loadFileContent(oldPath, shas.base),
    file.status === 'removed' ? undefined : loadFileContent(file.filename, shas.head),
  ])
  if (oldContent.length > MAX_SELF_DIFF_CHARS || newContent.length > MAX_SELF_DIFF_CHARS)
    return undefined
  if ((lineCount(oldContent) + lineCount(newContent)) * changes > MAX_SELF_DIFF_COST)
    return undefined
  const patch = await diffTexts(oldPath, file.filename, oldContent, newContent)
  return patch === undefined ? undefined : parseHunks(patch.split('\n'))
}

async function normalizeFile(file: GithubPullRequestFileJson, shas: ComparedShas, fallbackDiffText: () => Promise<string | undefined>, loadFileContent: PatchFallbacks['loadFileContent']): Promise<FileChange> {
  const status = STATUS_MAP[file.status] ?? 'modified'

  if (file.patch !== undefined) {
    const lines = file.patch.split('\n')
    const hunks = parseHunks(lines)
    const { additions, deletions } = { additions: file.additions, deletions: file.deletions }
    return {
      path: file.filename,
      previousPath: file.previous_filename,
      status,
      additions,
      deletions,
      isBinary: false,
      sha: file.sha,
      hunks,
    }
  }

  // GitHub omits `patch` for very large diffs (and gives no direct signal for binary
  // files), fall back to the raw `.diff` text, which is authoritative for both.
  const fullText = await fallbackDiffText()
  const parsed = fullText === undefined ? [] : await parsePatch(fullText)
  const match = parsed.find(f => f.path === file.filename || (!!file.previous_filename && f.previousPath === file.previous_filename))
  if (match) {
    // The JSON API stays the source of truth for blob sha/status/counts whenever it has them.
    return {
      ...match,
      status,
      previousPath: file.previous_filename,
      sha: file.sha,
      additions: file.additions,
      deletions: file.deletions,
    }
  }

  // Binary files report no changed lines; a text file with changes but no patch is just too large.
  if (fullText === undefined && file.additions + file.deletions > 0) {
    const hunks = await diffFileContents(file, shas, loadFileContent)
    return {
      path: file.filename,
      previousPath: file.previous_filename,
      status,
      additions: file.additions,
      deletions: file.deletions,
      isBinary: false,
      sha: file.sha,
      ...(hunks ? { hunks } : { hunks: [], truncated: true as const }),
    }
  }

  return {
    path: file.filename,
    previousPath: file.previous_filename,
    status,
    additions: file.additions,
    deletions: file.deletions,
    isBinary: true,
    sha: file.sha,
    hunks: [],
  }
}

/** A GitHub files listing (PR, compare or commit) as canonical `FileChange`s. */
export async function normalizeFiles(files: GithubPullRequestFileJson[], shas: ComparedShas, { loadDiffText, loadFileContent }: PatchFallbacks): Promise<FileChange[]> {
  let diffTextPromise: Promise<string | undefined> | undefined
  const fallbackDiffText = () => diffTextPromise ??= loadDiffText()
  return Promise.all(files.map(file => normalizeFile(file, shas, fallbackDiffText, loadFileContent)))
}

function resolveState(pr: GithubPullRequestJson): PullRequestState {
  if (pr.merged)
    return 'merged'
  if (pr.state === 'closed')
    return 'closed'
  if (pr.draft)
    return 'draft'
  return 'open'
}

export async function normalizePullRequest(
  owner: string,
  repo: string,
  number: string,
  pr: GithubPullRequestJson,
  files: GithubPullRequestFileJson[],
  commits: GithubPullRequestCommitJson[],
  fallbacks: PatchFallbacks,
): Promise<DiffsPayload> {
  return {
    ref: { kind: 'github-pr', owner, repo, number },
    title: pr.title,
    label: `#${number}`,
    author: pr.user ? { name: pr.user.login, avatarUrl: pr.user.avatar_url } : undefined,
    description: pr.body ?? '',
    url: pr.html_url,
    base: { sha: pr.base.sha, ref: pr.base.ref },
    head: { sha: pr.head.sha, ref: pr.head.ref },
    createdAt: pr.created_at,
    updatedAt: pr.updated_at,
    pullRequest: {
      state: resolveState(pr),
    },
    commits: commits.map(({ sha, commit }) => ({ sha, message: commit.message })),
    files: await normalizeFiles(files, { base: pr.base.sha, head: pr.head.sha }, fallbacks),
  }
}
