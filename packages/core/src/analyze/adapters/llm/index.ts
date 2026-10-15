import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { Locale } from '../../../locales'
import type { DiffGroup, FileNote, GroupedResult, GroupedResultCore, HunkNote } from '../../../types/analyze'
import type { DiffsPayload } from '../../../types/diff'
import type { LlmAnalyzeOptions } from './agent'
import type { ResolvedModel } from './model'
import type { Analysis } from './schema'
import { normalizeGroupedResult } from '../../../types/analyze'
import { runAgent } from './agent'

export * from './ask'
export * from './critique'
export * from './inbox'

export const LLM_SCHEMA_VERSION = 1

/**
 * Drops any file path the model hallucinated (not in the diff) and any it duplicated
 * across groups (first group wins). Files the model left out are not re-attached
 * here: `resolveGroups` surfaces them as "Uncategorized" at view time, the same way
 * it handles files added by later commits.
 */
function reconcile(diff: DiffsPayload, analysis: Analysis): DiffGroup[] {
  const validPaths = new Set(diff.files.map(file => file.path))
  const seen = new Set<string>()
  const groups: DiffGroup[] = []

  for (const group of analysis.groups) {
    const filePaths = group.filePaths.filter(path => validPaths.has(path) && !seen.has(path))
    filePaths.forEach(path => seen.add(path))

    const children = group.children
      ?.map(child => ({
        ...child,
        filePaths: child.filePaths.filter((path) => {
          if (!validPaths.has(path) || seen.has(path))
            return false
          seen.add(path)
          return true
        }),
      }))
      .filter(child => child.filePaths.length > 0)

    if (filePaths.length === 0 && !children?.length)
      continue
    groups.push({ ...group, filePaths, children: children?.length ? children : undefined })
  }

  return groups
}

/** Keeps file notes for paths in the diff (first wins) with non-empty text, and hunk notes whose index names a hunk of that file. */
function reconcileFiles(diff: DiffsPayload, notes: FileNote[] | undefined): FileNote[] {
  const hunkCounts = new Map(diff.files.map(file => [file.path, file.hunks.length]))
  const seen = new Set<string>()
  const files: FileNote[] = []
  for (const note of notes ?? []) {
    const hunkCount = hunkCounts.get(note.path)
    const summary = note.summary.trim()
    if (hunkCount === undefined || seen.has(note.path) || !summary)
      continue
    seen.add(note.path)
    const indices = new Set<number>()
    const hunks: HunkNote[] = []
    for (const hunk of note.hunks ?? []) {
      const text = hunk.note.trim()
      if (!Number.isInteger(hunk.index) || hunk.index < 0 || hunk.index >= hunkCount || indices.has(hunk.index) || !text)
        continue
      indices.add(hunk.index)
      hunks.push({ index: hunk.index, note: text })
    }
    hunks.sort((a, b) => a.index - b.index)
    files.push({ path: note.path, summary, hunks: hunks.length ? hunks : undefined })
  }
  return files
}

export function toGroupedResult(diff: DiffsPayload, analysis: Analysis, resolved: ResolvedModel, locale: Locale): GroupedResult {
  const files = reconcileFiles(diff, analysis.files)
  const core: GroupedResultCore = {
    overallSummary: analysis.overallSummary,
    groups: reconcile(diff, analysis),
    ...(files.length ? { files } : {}),
    schemaVersion: LLM_SCHEMA_VERSION,
  }
  return { ...normalizeGroupedResult('llm', core, `${resolved.model.provider}/${resolved.model.id}`), locale }
}

/** `locale` is both the language the summaries are written in and the stamp on the result. */
export async function runLlmAnalysis(diff: DiffsPayload, resolved: ResolvedModel, locale: Locale, options?: LlmAnalyzeOptions): Promise<{ result: GroupedResult, transcript: AgentMessage[] }> {
  const { analysis, transcript } = await runAgent(diff, resolved, locale, options)
  return { result: toGroupedResult(diff, analysis, resolved, locale), transcript }
}
