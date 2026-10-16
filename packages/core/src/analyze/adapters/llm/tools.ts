import type { AgentTool } from '@earendil-works/pi-agent-core'
import type { Tool } from '@earendil-works/pi-ai'
import type { DiffsPayload, FileChange } from '../../../types/diff'
import type { Analysis } from './schema'
import { toJsonSchema } from '@valibot/to-json-schema'
import * as v from 'valibot'
import { renderFilesAsText } from './prompt'
import { AnalysisSchema } from './schema'

export const READ_CALL_CHAR_CAP = 40_000

export interface AnalysisLedger {
  turn: number
  charsRead: number
  readPaths: Set<string>
  readRanges: Map<string, [number, number][]>
  submitAttempts: number
  result?: Analysis
}

export function createLedger(): AnalysisLedger {
  return { turn: 0, charsRead: 0, readPaths: new Set(), readRanges: new Map(), submitAttempts: 0 }
}

export interface CoverageIssues {
  missing: string[]
  duplicated: string[]
  unknown: string[]
}

export function findCoverageIssues(diff: DiffsPayload, analysis: Analysis): CoverageIssues {
  const knownPaths = new Set(diff.files.map(file => file.path))
  const seen = new Set<string>()
  const duplicated = new Set<string>()
  const unknown: string[] = []

  const visit = (path: string) => {
    if (!knownPaths.has(path)) {
      unknown.push(path)
      return
    }
    if (seen.has(path))
      duplicated.add(path)
    seen.add(path)
  }

  for (const group of analysis.groups) {
    group.filePaths.forEach(visit)
    for (const child of group.children ?? [])
      child.filePaths.forEach(visit)
  }

  const missing = diff.files.map(file => file.path).filter(path => !seen.has(path))
  return { missing, duplicated: [...duplicated], unknown }
}

function describeCoverageIssues(issues: CoverageIssues, toolName: string): string | undefined {
  const lines: string[] = []
  if (issues.missing.length > 0)
    lines.push(`Missing paths: ${issues.missing.join(', ')}`)
  if (issues.duplicated.length > 0)
    lines.push(`Duplicated paths: ${issues.duplicated.join(', ')}`)
  if (issues.unknown.length > 0)
    lines.push(`Unknown paths: ${issues.unknown.join(', ')}`)
  if (lines.length === 0)
    return undefined
  lines.push(`Fix these and call ${toolName} again.`)
  return lines.join('\n')
}

const ReadDiffsParamsSchema = v.object({
  paths: v.array(v.string()),
  offset: v.optional(v.pipe(v.number(), v.integer(), v.minValue(0))),
})

/** Records the union of returned character ranges, so a partial read never completes a path. */
function recordRead(ledger: AnalysisLedger, path: string, start: number, end: number, length: number): void {
  const merged: [number, number][] = []
  const ranges: [number, number][] = [...ledger.readRanges.get(path) ?? [], [start, end]]
  for (const range of ranges.sort((a, b) => a[0] - b[0])) {
    const previous = merged.at(-1)
    if (previous && range[0] <= previous[1])
      previous[1] = Math.max(previous[1], range[1])
    else
      merged.push([...range])
  }
  ledger.readRanges.set(path, merged)
  if (merged[0]?.[0] === 0 && merged[0][1] >= length)
    ledger.readPaths.add(path)
}

interface ReadSection {
  text: string
  start: number
  end: number
  length: number
  alreadyRead: boolean
  continuation?: number
}

function readSection(file: FileChange, ledger: AnalysisLedger, offset: number | undefined, available: number, pendingChars: number, canTruncate: boolean): ReadSection | undefined {
  const alreadyRead = offset === undefined && ledger.readPaths.has(file.path)
  const fullText = alreadyRead ? `### ${file.path}\n(already shown above)` : renderFilesAsText([file])
  const firstRange = ledger.readRanges.get(file.path)?.[0]
  const start = alreadyRead ? 0 : offset ?? (firstRange?.[0] === 0 ? firstRange[1] : 0)
  if (start > fullText.length)
    throw new Error(`Offset is past the end of ${file.path}.`)
  const section: ReadSection = { text: fullText.slice(start), start, end: fullText.length, length: fullText.length, alreadyRead }
  if (section.text.length + pendingChars <= available)
    return section
  if (!canTruncate)
    return undefined
  // Keep the continuation and queued-path notice inside the same call budget.
  section.end = start + Math.max(0, available - pendingChars - 160)
  section.text = `${fullText.slice(start, section.end)}\n(truncated; continue this path with offset: ${section.end})`
  section.continuation = section.end
  return section
}

export function createReadDiffsTool(diff: DiffsPayload, ledger: AnalysisLedger): AgentTool {
  const filesByPath = new Map(diff.files.map(file => [file.path, file]))

  return {
    name: 'read_diffs',
    label: 'Read diffs',
    description: 'Returns the diffs of the given manifest paths. Large files return a continuation offset: request that single path with offset to read the next chunk. Omitting offset resumes the first unread range.',
    parameters: toJsonSchema(ReadDiffsParamsSchema) as Tool['parameters'],
    execute: async (_toolCallId, rawParams) => {
      const parsed = v.safeParse(ReadDiffsParamsSchema, rawParams)
      if (!parsed.success)
        throw new Error(v.summarize(parsed.issues))
      const { paths, offset } = parsed.output
      if (offset !== undefined && paths.length !== 1)
        throw new Error('An offset requires exactly one path.')

      const unknownPaths = paths.filter(path => !filesByPath.has(path))
      if (unknownPaths.length > 0)
        throw new Error(`Unknown paths: ${unknownPaths.join(', ')}`)

      const sections: string[] = []
      const returnedPaths: string[] = []
      const continuations: { path: string, offset: number }[] = []
      let chars = 0
      const pendingNotice = (remaining: string[]) => remaining.length ? `\n\nNot returned (limit reached), request again: ${remaining.join(', ')}`.slice(0, 512) : ''

      for (let i = 0; i < paths.length; i++) {
        const path = paths[i]!
        const separator = sections.length ? 2 : 0
        const remaining = READ_CALL_CHAR_CAP - chars - separator
        const pending = pendingNotice(paths.slice(i + 1))
        const section = readSection(filesByPath.get(path)!, ledger, offset, remaining, pending.length, returnedPaths.length === 0)
        if (!section) {
          const notice = pendingNotice(paths.slice(i)).slice(0, READ_CALL_CHAR_CAP - chars)
          sections[sections.length - 1] += notice
          chars += notice.length
          break
        }

        sections.push(section.text)
        returnedPaths.push(path)
        chars += separator + section.text.length
        if (!section.alreadyRead)
          recordRead(ledger, path, section.start, section.end, section.length)
        if (section.continuation !== undefined) {
          continuations.push({ path, offset: section.continuation })
          sections[sections.length - 1] += pending
          chars += pending.length
          break
        }
      }

      ledger.charsRead += chars
      return {
        content: [{ type: 'text', text: sections.join('\n\n') }],
        details: { paths: returnedPaths, chars, continuations },
      }
    },
  }
}

function parseJsonString(value: unknown): unknown {
  if (typeof value !== 'string')
    return value
  try {
    return JSON.parse(value)
  }
  catch {
    return value
  }
}

function normalizeGroupArguments(value: unknown): unknown {
  const group = parseJsonString(value)
  if (!group || typeof group !== 'object' || Array.isArray(group))
    return group
  const record = group as Record<string, unknown>
  const normalized: Record<string, unknown> = { ...record, filePaths: parseJsonString(record.filePaths) }
  if (record.children !== undefined) {
    const children = parseJsonString(record.children)
    normalized.children = Array.isArray(children) ? children.map(normalizeGroupArguments) : children
  }
  return normalized
}

function normalizeFileNoteArguments(value: unknown): unknown {
  const note = parseJsonString(value)
  if (!note || typeof note !== 'object' || Array.isArray(note) || (note as Record<string, unknown>).hunks === undefined)
    return note
  const record = note as Record<string, unknown>
  return { ...record, hunks: parseJsonString(record.hunks) }
}

/**
 * Some models behind Anthropic-compatible endpoints (e.g. DeepSeek via the Vercel AI Gateway)
 * emit nested arrays as JSON strings; pi validates arguments before execute, so undo that first.
 */
export function prepareGroupingArguments(args: unknown): unknown {
  if (!args || typeof args !== 'object')
    return args
  const record = args as Record<string, unknown>
  const groups = parseJsonString(record.groups)
  const prepared: Record<string, unknown> = { ...record, groups: Array.isArray(groups) ? groups.map(normalizeGroupArguments) : groups }
  if (record.files !== undefined) {
    const files = parseJsonString(record.files)
    prepared.files = Array.isArray(files) ? files.map(normalizeFileNoteArguments) : files
  }
  return prepared
}

export function createSubmitGroupingTool(diff: DiffsPayload, ledger: AnalysisLedger): AgentTool {
  return {
    name: 'submit_grouping',
    label: 'Submit grouping',
    description: 'Submit the final grouping and overall summary.',
    parameters: toJsonSchema(AnalysisSchema) as Tool['parameters'],
    prepareArguments: prepareGroupingArguments,
    execute: async (_toolCallId, rawParams) => {
      ledger.submitAttempts += 1

      const parsed = v.safeParse(AnalysisSchema, rawParams)
      if (!parsed.success)
        throw new Error(v.summarize(parsed.issues))
      const analysis = parsed.output

      const message = describeCoverageIssues(findCoverageIssues(diff, analysis), 'submit_grouping')
      if (message && ledger.submitAttempts < 2)
        throw new Error(message)

      ledger.result = analysis
      return {
        content: [{ type: 'text', text: 'Grouping accepted.' }],
        details: analysis,
        terminate: true,
      }
    },
  }
}

export const UPDATE_GROUPING_DECLARATION: Tool = {
  name: 'update_grouping',
  description: 'Replace the current grouping and overall summary. Call only when the user asks to change the grouping.',
  parameters: toJsonSchema(AnalysisSchema) as Tool['parameters'],
}

export function createUpdateGroupingTool(diff: DiffsPayload, onUpdate: (analysis: Analysis) => void | Promise<void>): AgentTool {
  return {
    ...UPDATE_GROUPING_DECLARATION,
    label: 'Update grouping',
    prepareArguments: prepareGroupingArguments,
    execute: async (_toolCallId, rawParams) => {
      const parsed = v.safeParse(AnalysisSchema, rawParams)
      if (!parsed.success)
        throw new Error(v.summarize(parsed.issues))
      const analysis = parsed.output

      const message = describeCoverageIssues(findCoverageIssues(diff, analysis), 'update_grouping')
      if (message)
        throw new Error(message)

      await onUpdate(analysis)
      return {
        content: [{ type: 'text', text: `Grouping updated: ${analysis.groups.length} groups.` }],
        details: analysis,
      }
    },
  }
}
