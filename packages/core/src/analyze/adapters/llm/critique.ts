import type { AiActivity, AiPrContext } from '../../../local-rpc'
import type { CritiqueFinding, CritiqueResult, CritiqueSeverity } from '../../../types/analyze'
import type { DiffSide } from '../../../types/comment-threads'
import type { DiffHunk, DiffsPayload, FileChange } from '../../../types/diff'
import type { ResolvedModel } from './model'
import picomatch from 'picomatch'
import { serializeRef } from '../../../types/source'
import { GENERATED_PATTERNS } from '../rule-based/rules'
import { isCliEngine, runCli } from './cli-stream'

/** Past this the review stops being a review and becomes a wall of comments nobody reads. */
export const MAX_FINDINGS = 25

const DESCRIPTION_CHAR_LIMIT = 2_000
const DIFF_CHAR_BUDGET = 200_000

const isGeneratedPath = picomatch(GENERATED_PATTERNS)

export const WRITING_RULES = `How to write:
- Plain English. Short sentences, active voice, one idea per sentence.
- Lead with what the change does. Detail comes after that, or not at all.
- Name the actual function, type, file, flag, or number. Never a paraphrase of it.
- Cut every word that carries no meaning. No leverage, robust, comprehensive, seamless, streamline, enhance, facilitate, utilize, various, significant, key, powerful, simply, essentially.
- No throat-clearing, no restating the question, no summary of what you are about to say.
- No hedging you cannot back up. If the code shows it, say it. If it does not, say that instead.
- No bold, no italics, no emoji, no headings. Sentence case.`

export const CRITIQUE_SYSTEM_PROMPT = `You are a senior engineer reviewing someone else's pull request, looking for what is wrong with it and where.
Every finding you report is posted as a comment on a line of the diff, under the reader's name. Report nothing you would not defend in person.
A finding names a concrete failure: the input that breaks it, the case it misses, the caller it leaves broken, the state it corrupts. Say what happens, not that something might.
These are not findings: asking for tests in general, asking for a comment, renaming, extracting a helper, formatting, a preference about style, or praise.
You never invent code you did not read. If you cannot point at the line where it goes wrong, you do not have a finding.
Finding nothing is a real answer. A pull request with no defects gets an empty list, not a filled quota.

${WRITING_RULES}`

/** JSON Schema of the critique answer; optional fields may come back as `null` from engines that require every key. */
export const CRITIQUE_SCHEMA = {
  type: 'object',
  properties: {
    summary: {
      type: 'string',
      description: 'The review body, two or three sentences: what this pull request does, and what the findings add up to. Say \'nothing to flag\' plainly when the list is empty.',
    },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          path: { type: 'string', description: 'The file holding the problem, verbatim from a `###` heading of the diff.' },
          side: { type: 'string', enum: ['RIGHT', 'LEFT'], description: 'RIGHT for a line the change adds or leaves in place, LEFT for one it removes.' },
          line: { type: 'number', description: 'First line of the comment: the R number for RIGHT, the L number for LEFT, as printed before the line.' },
          endLine: { type: 'number', description: 'Last line of a multi-line comment, numbered the same way and in the same hunk. Omit for a comment on one line.' },
          severity: { type: 'string', enum: ['bug', 'risk', 'nit'], description: 'bug: it is wrong as written. risk: it breaks under a case the author probably has not hit. nit: worth saying, nothing more.' },
          title: { type: 'string', description: 'What is wrong, under 60 characters. No file names, no line numbers.' },
          body: { type: 'string', description: 'The comment as it will appear on GitHub. Two to four sentences: what breaks, the case that breaks it, and what to do instead. No greeting, no sign-off.' },
          suggestion: { type: 'string', description: 'Only for a RIGHT finding with a mechanical fix: the exact code that replaces lines line..endLine, without fences or line numbers. Omit otherwise.' },
        },
        required: ['path', 'side', 'line', 'severity', 'title', 'body'],
        additionalProperties: false,
      },
    },
  },
  required: ['summary', 'findings'],
  additionalProperties: false,
} as const

/** A line of a hunk with its position on each side it exists on. */
export interface NumberedLine {
  kind: ' ' | '+' | '-'
  text: string
  oldLine?: number
  newLine?: number
}

/**
 * Splits a hunk's patch into its lines, numbered on the sides they exist on.
 * @param hunk - One hunk of a file's diff.
 * @returns The hunk's lines in order; `\ No newline` markers are left out.
 */
export function numberHunkLines(hunk: DiffHunk): NumberedLine[] {
  const lines: NumberedLine[] = []
  let oldLine = hunk.oldStart
  let newLine = hunk.newStart
  for (const raw of hunk.patch.split('\n')) {
    const marker = raw[0]
    if (marker === '+')
      lines.push({ kind: '+', text: raw.slice(1), newLine: newLine++ })
    else if (marker === '-')
      lines.push({ kind: '-', text: raw.slice(1), oldLine: oldLine++ })
    else if (marker === ' ')
      lines.push({ kind: ' ', text: raw.slice(1), oldLine: oldLine++, newLine: newLine++ })
  }
  return lines
}

/**
 * Where a line number on one side of a file's diff falls.
 * @param file - The changed file.
 * @param side - `additions` numbers lines in the new file, `deletions` in the old one.
 * @param line - The line number on that side.
 * @returns The index of the hunk showing that line, or -1 when no hunk shows it.
 */
export function hunkIndexOf(file: FileChange, side: DiffSide, line: number): number {
  return file.hunks.findIndex(hunk => numberHunkLines(hunk).some(entry => (side === 'additions' ? entry.newLine : entry.oldLine) === line))
}

/**
 * Renders hunk lines for a prompt, each prefixed with its old (L) and new (R) line number.
 * @param lines - Lines from `numberHunkLines`.
 * @returns One prompt line per entry.
 */
export function renderNumberedLines(lines: NumberedLine[]): string {
  return lines.map((entry) => {
    const left = entry.oldLine === undefined ? '' : `L${entry.oldLine}`
    const right = entry.newLine === undefined ? '' : `R${entry.newLine}`
    return `${left.padEnd(7)}${right.padEnd(7)}${entry.kind}${entry.text}`
  }).join('\n')
}

/**
 * Renders one hunk for a prompt: its index and header, then its numbered lines.
 * @param hunk - The hunk.
 * @param index - Its 0-based position in the file's diff.
 * @returns The prompt text.
 */
export function renderNumberedHunk(hunk: DiffHunk, index: number): string {
  return `[hunk ${index}] ${hunk.header}\n${renderNumberedLines(numberHunkLines(hunk))}`
}

/**
 * The `###` heading a file gets in a prompt.
 * @param file - The changed file.
 * @returns Path, rename origin, status and line counts on one line.
 */
export function fileHeading(file: FileChange): string {
  const rename = file.previousPath ? ` (renamed from ${file.previousPath})` : ''
  return `### ${file.path}${rename} [${file.status}, +${file.additions}/-${file.deletions}]`
}

/** The diff with every line prefixed by its old (L) and new (R) number; files past the budget are only listed. */
export function renderNumberedDiff(files: FileChange[]): string {
  let spent = 0
  return files.map((file) => {
    const heading = fileHeading(file)
    if (file.isBinary)
      return `${heading}\n(binary file, no diff)`
    if (file.truncated)
      return `${heading}\n(diff too large, omitted; read the file through your tools if it matters)`
    if (isGeneratedPath(file.path))
      return `${heading}\n(generated file, not yours to review)`
    const body = file.hunks.map(renderNumberedHunk).join('\n')
    if (spent + body.length > DIFF_CHAR_BUDGET)
      return `${heading}\n(diff omitted to fit the prompt; read it through your tools before judging it)`
    spent += body.length
    return `${heading}\n${body}`
  }).join('\n\n')
}

/**
 * The two header lines that open a prompt about a diff: title, then author, branches and size.
 * @param diff - The diff the prompt is about.
 * @returns The header text.
 */
export function prHeading(diff: DiffsPayload): string {
  const { ref } = diff
  const slug = ref.kind === 'github-pr'
    ? `${ref.owner}/${ref.repo}#${ref.number}`
    : ref.kind === 'github-compare' || ref.kind === 'github-commit'
      ? `${ref.owner}/${ref.repo}${diff.label ? ` ${diff.label}` : ''}`
      : diff.label ?? ''
  const additions = diff.files.reduce((sum, file) => sum + file.additions, 0)
  const deletions = diff.files.reduce((sum, file) => sum + file.deletions, 0)
  const branches = diff.base && diff.head ? ` · ${diff.head.ref} → ${diff.base.ref}` : ''
  return `# ${slug ? `${slug}: ` : ''}${diff.title}
${diff.author ? `author ${diff.author.name}` : 'unknown author'}${branches} · +${additions} −${deletions} across ${diff.files.length} files`
}

/** A review lens: a skill whose instructions narrow what the critique looks for. */
export interface CritiqueLens {
  name: string
  instructions: string
}

/**
 * The user message for a critique run.
 * @param diff - The diff under review.
 * @param lens - Extra review instructions to follow on top of the general ones.
 * @returns The prompt text.
 */
export function buildCritiquePrompt(diff: DiffsPayload, lens?: CritiqueLens): string {
  const sections = [prHeading(diff)]
  const description = diff.description?.trim()
  if (description)
    sections.push(`## Description\n\n${description.slice(0, DESCRIPTION_CHAR_LIMIT)}`)
  sections.push(`## What changed\n\nEach line is prefixed with its number in the old file (L) and the new file (R), then its +/-/space marker.\n\n${renderNumberedDiff(diff.files)}`)
  sections.push(`## Reading the code

When you have tools to read the pull request and its repository, use them: read the code around a hunk, and the callers the diff never touches, before you judge it. A defect you report without having read the code around it is a defect you invented.`)
  sections.push(`## Task

Find what is wrong with this pull request, and say where.

Look for:
- Logic that is wrong for an input the code will see: an off-by-one, an inverted condition, a case that falls through.
- State the change leaves inconsistent: something set and never cleared, a lock never released, a cache never invalidated.
- Errors swallowed, ignored, or turned into a value the caller reads as success.
- Callers, tests, or migrations the change breaks and does not update.
- Concurrency the change assumes away, and resources it never closes.
- Anything a user can pass that reaches a query, a path, a command, or a template unchecked.

Rules:
- Anchor every finding to a file of this diff and to lines a hunk of it shows, using the L/R numbers printed before them.
- One finding per problem. Do not report the same problem on three lines of the same function.
- Report what this diff introduces. A defect the change happens to sit next to is not this author's to fix.
- Severity is bug, risk, or nit. Do not spend a bug on a preference.
- Say nothing about formatting, naming, or file layout.
- Report at most ${MAX_FINDINGS} findings, worst first.
- If the pull request is sound, return an empty findings list and say so in the summary.`)
  if (lens?.instructions.trim())
    sections.push(`## Review lens: ${lens.name}\n\nReview through this lens as well. Its instructions narrow what to look for; the rules above still decide what counts as a finding and how to anchor it.\n\n${lens.instructions.trim()}`)
  return sections.join('\n\n')
}

/** A finding as the engine returned it, before it is matched against the diff. */
interface RawFinding {
  path?: unknown
  side?: unknown
  line?: unknown
  endLine?: unknown
  severity?: unknown
  title?: unknown
  body?: unknown
  suggestion?: unknown
}

function stringOf(value: unknown): string {
  // Claude sometimes leaves the answer object's closing `"}` inside its last string.
  return typeof value === 'string' ? value.trim().replace(/"\s*\}+$/, '').trimEnd() : ''
}

function lineOf(value: unknown): number | undefined {
  const line = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : Number.NaN
  return Number.isFinite(line) && line > 0 ? Math.round(line) : undefined
}

function severityOf(value: unknown): CritiqueSeverity {
  return value === 'bug' || value === 'nit' ? value : 'risk'
}

function normalizePath(value: unknown): string {
  return stringOf(value).replace(/^(?:[ab]\/|\.\/)/, '')
}

/** The inside of `text` when it is one fenced code block, else `undefined`. */
export function fencedContent(text: string): string | undefined {
  const trimmed = text.trim()
  const fence = /^(?:`{3,}|~{3,})/.exec(trimmed)?.[0]
  const firstBreak = trimmed.indexOf('\n')
  if (!fence || firstBreak === -1 || !trimmed.endsWith(fence) || trimmed.length - fence.length <= firstBreak)
    return undefined
  return trimmed.slice(firstBreak + 1, trimmed.length - fence.length).replace(/\n$/, '')
}

function stripFences(code: string): string {
  return fencedContent(code) ?? code.replace(/\n$/, '')
}

function anchorOf(file: FileChange, item: RawFinding, first: number): { side: DiffSide, line: number, startLine?: number } | undefined {
  const side: DiffSide = item.side === 'LEFT' || item.side === 'deletions' ? 'deletions' : 'additions'
  const last = lineOf(item.endLine)
  const end = last !== undefined && last > first ? last : first
  const hunk = hunkIndexOf(file, side, end)
  if (hunk === -1)
    return undefined
  const ranged = end > first && hunkIndexOf(file, side, first) === hunk
  return { side, line: end, ...(ranged ? { startLine: first } : {}) }
}

/**
 * Matches what the engine reported against the diff it reviewed.
 * @param diff - The reviewed diff.
 * @param raw - Findings as the engine returned them.
 * @returns The findings anchored to lines the diff shows, in file order then line order and capped at `MAX_FINDINGS`, plus how many were discarded.
 */
export function resolveFindings(diff: DiffsPayload, raw: unknown[]): { findings: CritiqueFinding[], dropped: number } {
  const order = new Map(diff.files.map((file, index) => [file.path, index]))
  const filesByPath = new Map(diff.files.map(file => [file.path, file]))
  const seen = new Set<string>()
  const found: CritiqueFinding[] = []
  let dropped = 0

  for (const item of raw as RawFinding[]) {
    const body = stringOf(item?.body)
    const file = filesByPath.get(normalizePath(item?.path))
    const first = lineOf(item?.line)
    const anchor = file && body && first !== undefined ? anchorOf(file, item, first) : undefined
    const id = anchor && `${file!.path}:${anchor.side}:${anchor.startLine ?? anchor.line}-${anchor.line}`
    if (!anchor || !id || seen.has(id)) {
      dropped++
      continue
    }
    seen.add(id)
    const suggestion = anchor.side === 'additions' && typeof item.suggestion === 'string' && item.suggestion.trim() ? stripFences(item.suggestion) : undefined
    found.push({
      id,
      path: file!.path,
      ...anchor,
      severity: severityOf(item.severity),
      title: stringOf(item.title).slice(0, 120) || body.split('\n')[0]!.slice(0, 60),
      body,
      ...(suggestion !== undefined ? { suggestion } : {}),
    })
  }

  const findings = found
    .sort((a, b) => order.get(a.path)! - order.get(b.path)! || a.line - b.line)
    .slice(0, MAX_FINDINGS)
  return { findings, dropped: dropped + Math.max(found.length - MAX_FINDINGS, 0) }
}

/**
 * The GitHub comment a finding is posted as, with a suggested change when it has one.
 * @param finding - The finding to post.
 * @returns GitHub-flavored Markdown.
 */
export function findingCommentBody(finding: CritiqueFinding): string {
  const parts = [`**${finding.title}**`, finding.body]
  if (finding.suggestion !== undefined) {
    const longestRun = Math.max(2, ...[...finding.suggestion.matchAll(/`+/g)].map(match => match[0].length))
    const fence = '`'.repeat(longestRun + 1)
    parts.push(`${fence}suggestion\n${finding.suggestion}\n${fence}`)
  }
  return parts.join('\n\n')
}

/**
 * The review body posted above a critique's inline comments.
 * @param result - The critique being posted.
 * @returns GitHub-flavored Markdown.
 */
export function critiqueReviewBody(result: CritiqueResult): string {
  const by = [result.engine, result.model].filter(Boolean).join('/')
  const lens = result.lens ? `, through the ${result.lens} lens` : ''
  return `${result.summary}\n\n<sub>Reviewed with ${by}${lens} on pulls.review.</sub>`
}

function patchOf(file: FileChange): string | undefined {
  if (file.isBinary || file.truncated || file.hunks.length === 0)
    return undefined
  return file.hunks.map(hunk => `${hunk.header}\n${hunk.patch}`).join('\n')
}

/**
 * What a CLI engine needs to read a GitHub diff through tools.
 * @param diff - The loaded diff.
 * @returns The context, or `undefined` for diffs that are not on GitHub or have no head commit.
 */
export function prContextOf(diff: DiffsPayload): AiPrContext | undefined {
  const { ref } = diff
  if ((ref.kind !== 'github-pr' && ref.kind !== 'github-compare' && ref.kind !== 'github-commit') || !diff.head)
    return undefined
  return {
    owner: ref.owner,
    repo: ref.repo,
    number: ref.kind === 'github-pr' ? Number(ref.number) : undefined,
    title: diff.title,
    headSha: diff.head.sha,
    baseSha: diff.base?.sha,
    files: diff.files.map(file => ({ path: file.path, previousPath: file.previousPath, status: file.status, patch: patchOf(file) })),
  }
}

function parseAnswer(text: string): { summary?: unknown, findings?: unknown } {
  const parsed = JSON.parse(fencedContent(text) ?? text.trim()) as unknown
  if (!parsed || typeof parsed !== 'object')
    throw new Error('The review engine returned no findings object.')
  return parsed as { summary?: unknown, findings?: unknown }
}

export interface CritiqueRunOptions {
  lens?: CritiqueLens
  signal?: AbortSignal
  /** Live work-log entries; entries repeat as they update, merge by `id`. */
  onActivity?: (activities: AiActivity[]) => void
}

/**
 * Reviews a diff for defects through Claude Code or Codex on the server.
 * @param diff - The diff to review.
 * @param resolved - The model to review with; its provider must be a CLI engine.
 * @param options - Review lens, abort signal and live activity listener.
 * @returns The review summary and the findings anchored to lines of `diff`.
 * @throws When the provider is not a CLI engine, the build has no CLI runner, or the engine's answer is not a findings object.
 */
export async function runCritique(diff: DiffsPayload, resolved: ResolvedModel, options: CritiqueRunOptions = {}): Promise<CritiqueResult> {
  const engine = resolved.model.provider
  if (!isCliEngine(engine))
    throw new Error('Reviewing with AI needs Claude Code or Codex as the AI provider.')
  const { lens } = options
  const subject = serializeRef(diff.ref)
  const { text, usage } = await runCli({
    engine,
    model: resolved.model.id || undefined,
    effort: resolved.effort,
    system: CRITIQUE_SYSTEM_PROMPT,
    prompt: buildCritiquePrompt(diff, lens),
    schema: CRITIQUE_SCHEMA,
    label: `Review ${subject}${lens ? ` (${lens.name})` : ''}`,
    key: `critique:${subject}:${diff.head?.sha ?? ''}:${engine}:${resolved.model.id}:${resolved.effort ?? ''}:${lens?.name ?? ''}`,
    context: prContextOf(diff),
  }, { signal: options.signal, onActivity: options.onActivity })
  if (options.signal?.aborted)
    throw new DOMException('Aborted', 'AbortError')

  const answer = parseAnswer(text)
  const { findings, dropped } = resolveFindings(diff, Array.isArray(answer.findings) ? answer.findings : [])
  return {
    summary: stringOf(answer.summary),
    findings,
    dropped,
    headSha: diff.head?.sha,
    engine,
    model: usage?.model ?? (resolved.model.id || undefined),
    effort: resolved.effort,
    lens: lens?.name,
    generatedAt: new Date().toISOString(),
    usage,
  }
}
