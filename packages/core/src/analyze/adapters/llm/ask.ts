import type { AiActivity } from '../../../local-rpc'
import type { DiffSide } from '../../../types/comment-threads'
import type { DiffsPayload } from '../../../types/diff'
import type { CliRunResult } from './cli-stream'
import type { ResolvedModel } from './model'
import { isCliEngine, runCli } from './cli-stream'
import { fileHeading, numberHunkLines, prContextOf, prHeading, renderNumberedHunk, renderNumberedLines, WRITING_RULES } from './critique'
import { MENTION_RULE, renderFilesAsText } from './prompt'

const HUNK_CHAR_BUDGET = 24_000
/** The rest of the diff is quoted only below this; past it the engine reads files through its tools. */
const REST_CHAR_BUDGET = 60_000
const THREAD_TURNS = 4

export const ASK_SYSTEM_PROMPT = `You are helping an engineer read someone else's pull request. They have selected some lines of it and have a question about them.
The selection is where the question starts, not where the answer has to come from. The rest of the pull request, and the repository it lands in, are yours to read when you have tools for it.
Answer in under 150 words. No headings; a short code snippet is fine.
Answer the question that was asked. Reach past the selection when the answer is elsewhere, such as a caller, a definition, a test or an earlier hunk, and name where you found it.
Say what the code shows. When nothing you can read settles the question, say so in one sentence and name what would.

${WRITING_RULES.replace('No bold, no italics, no emoji, no headings.', 'No emoji, no headings.')}

${MENTION_RULE}`

/** Lines of one file the reader selected, numbered on `side`. */
export interface AskSelection {
  path: string
  side: DiffSide
  startLine: number
  endLine: number
}

export interface AskTurn {
  question: string
  answer: string
}

/** A question about a selection, with the earlier turns of the same thread. */
export interface AskRequest {
  selection: AskSelection
  question: string
  /** Earlier questions and answers of this thread, oldest first. */
  history: AskTurn[]
}

/**
 * Names a selection as `path:L10-L20`.
 * @param selection - The selected lines.
 * @returns The reference text.
 */
export function selectionLabel(selection: AskSelection): string {
  const { path, startLine, endLine } = selection
  return startLine === endLine ? `${path}:L${startLine}` : `${path}:L${startLine}-L${endLine}`
}

/**
 * The selected lines and the hunks they sit in, rendered for a prompt.
 * @param diff - The diff the selection was made in.
 * @param selection - The selected lines.
 * @returns The selected lines and the enclosing hunks, or `undefined` when the diff does not show them.
 */
export function selectionContext(diff: DiffsPayload, selection: AskSelection): { lines: string, hunks: string } | undefined {
  const file = diff.files.find(candidate => candidate.path === selection.path)
  if (!file)
    return undefined
  const low = Math.min(selection.startLine, selection.endLine)
  const high = Math.max(selection.startLine, selection.endLine)
  const selected: string[] = []
  const hunks: string[] = []
  file.hunks.forEach((hunk, index) => {
    const lines = numberHunkLines(hunk)
    const positions = lines.map(entry => selection.side === 'additions' ? entry.newLine : entry.oldLine)
    const first = positions.findIndex(line => line !== undefined && line >= low && line <= high)
    if (first === -1)
      return
    const last = positions.findLastIndex(line => line !== undefined && line >= low && line <= high)
    selected.push(renderNumberedLines(lines.slice(first, last + 1)))
    hunks.push(renderNumberedHunk(hunk, index))
  })
  if (selected.length === 0)
    return undefined
  const hunkText = hunks.join('\n')
  return {
    lines: selected.join('\n…\n'),
    hunks: `${fileHeading(file)}\n${hunkText.length > HUNK_CHAR_BUDGET ? `${hunkText.slice(0, HUNK_CHAR_BUDGET)}\n[truncated]` : hunkText}`,
  }
}

/**
 * The user message for one question about a selection.
 * @param diff - The diff the selection was made in.
 * @param request - The selection, the new question and the thread so far.
 * @returns The prompt text.
 */
export function buildAskPrompt(diff: DiffsPayload, request: AskRequest): string {
  const { selection } = request
  const context = selectionContext(diff, selection)
  const parts = [prHeading(diff), `## Selected: ${selectionLabel(selection)} (${selection.side === 'additions' ? 'new' : 'old'} file)`]
  if (context)
    parts.push(`Each line is prefixed with its number in the old file (L) and the new file (R).\n\n${context.lines}\n\n## The hunks around it\n\n${context.hunks}`)
  else
    parts.push('(The diff does not show these lines. Read the file through your tools if you have them.)')

  const rest = renderFilesAsText(diff.files.filter(file => file.path !== selection.path))
  if (rest && rest.length <= REST_CHAR_BUDGET)
    parts.push(`## The rest of the pull request\n\n${rest}`)
  else if (rest)
    parts.push(`## The rest of the pull request\n\n${diff.files.filter(file => file.path !== selection.path).map(fileHeading).join('\n')}\n\nThe diffs of these files are left out; read them through your tools when the question needs them.`)

  for (const turn of request.history.slice(-THREAD_TURNS))
    parts.push(`Earlier question: ${turn.question}\nYour answer: ${turn.answer}`)
  parts.push(`## Question\n\n${request.question}`)
  return parts.join('\n\n')
}

export interface AskRunOptions {
  signal?: AbortSignal
  /** Live work-log entries; entries repeat as they update, merge by `id`. */
  onActivity?: (activities: AiActivity[]) => void
}

/**
 * Answers one question about selected lines through Claude Code or Codex on the server.
 * @param diff - The diff the selection was made in.
 * @param resolved - The model to answer with; its provider must be a CLI engine.
 * @param request - The selection, the question and the thread so far.
 * @param options - Abort signal and live activity listener.
 * @returns The answer as Markdown prose, and the usage the CLI reported.
 * @throws When the provider is not a CLI engine or the build has no CLI runner.
 */
export async function askAboutSelection(diff: DiffsPayload, resolved: ResolvedModel, request: AskRequest, options: AskRunOptions = {}): Promise<CliRunResult> {
  const engine = resolved.model.provider
  if (!isCliEngine(engine))
    throw new Error('Asking about code needs Claude Code or Codex as the AI provider.')
  const result = await runCli({
    engine,
    model: resolved.model.id || undefined,
    effort: resolved.effort,
    system: ASK_SYSTEM_PROMPT,
    prompt: buildAskPrompt(diff, request),
    label: `Ask about ${selectionLabel(request.selection)}`,
    context: prContextOf(diff),
  }, { signal: options.signal, onActivity: options.onActivity })
  if (options.signal?.aborted)
    throw new DOMException('Aborted', 'AbortError')
  return { text: result.text.trim(), usage: result.usage }
}
