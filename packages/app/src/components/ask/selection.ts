import type { AskSelection } from '@pulls.review/core/llm'
import type { DiffsPayload, FileChange, ReviewDraftTarget } from '@pulls.review/core/types'

/**
 * The lines a gutter selection covers, as something to ask about.
 * @param target - The selection, as a review comment would anchor on it.
 * @returns The selection on its end side.
 */
export function askSelectionOf(target: ReviewDraftTarget): AskSelection {
  const { path, side, line } = target
  // A range across both sides starts in the other side's numbering; within a hunk the two stay close.
  const startLine = target.startLine !== undefined ? Math.min(target.startLine, line) : line
  return { path, side, startLine, endLine: line }
}

/**
 * A selection as `path:L10` or `path:L10-L20`.
 * @param selection - The selected lines.
 * @returns The label.
 */
export function lineRangeLabel(selection: AskSelection): string {
  return `${selection.path}:${lineAnchor(selection)}`
}

function lineAnchor(selection: AskSelection): string {
  return selection.startLine === selection.endLine ? `L${selection.endLine}` : `L${selection.startLine}-L${selection.endLine}`
}

/**
 * A GitHub link to the selected lines at the commit they were selected in: the head for
 * added and context lines, the base for removed ones.
 * @param diff - The diff the selection is in.
 * @param file - The file the selection is in.
 * @param selection - The selected lines.
 * @returns The link, or `undefined` when the diff is not from GitHub or lacks that commit.
 */
export function githubBlobUrl(diff: DiffsPayload, file: FileChange, selection: AskSelection): string | undefined {
  const { ref } = diff
  if (ref.kind === 'paste' || ref.kind === 'local' || ref.kind === 'posted')
    return
  const isOld = selection.side === 'deletions'
  const sha = isOld ? diff.base?.sha : diff.head?.sha
  if (!sha)
    return
  const path = (isOld ? file.previousPath ?? file.path : file.path).split('/').map(encodeURIComponent).join('/')
  return `https://github.com/${ref.owner}/${ref.repo}/blob/${sha}/${path}#${lineAnchor(selection)}`
}
