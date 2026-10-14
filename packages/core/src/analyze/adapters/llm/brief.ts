import type { ElementNode, ParseResult } from '@openuidev/lang-core'
import type { AiActivity } from '../../../local-rpc'
import type { GroupedResult } from '../../../types/analyze'
import type { BriefResult } from '../../../types/brief'
import type { DiffsPayload } from '../../../types/diff'
import type { ResolvedModel } from './model'
import { createLibrary, createParser, defineComponent } from '@openuidev/lang-core'
import { z } from 'zod'
import { LOCALES } from '../../../locales'
import { serializeRef } from '../../../types/source'
import { isCliEngine, runCli } from './cli-stream'
import { fencedContent, prContextOf, prHeading, renderNumberedDiff, WRITING_RULES } from './critique'
import { buildManifest } from './prompt'

/*
 * The brief: a page the agent designs for one pull request, written in OpenUI Lang
 * (https://github.com/thesysdev/openui) and parsed by OpenUI's own parser against the
 * library below. Each client draws the components natively; this file only says what may be built.
 */

const DESCRIPTION_CHAR_LIMIT = 4_000
/** The numbered diff goes into the prompt whole only up to this size; past it the manifest and the agent's own reading carry the page. */
const DIFF_CHAR_LIMIT = 120_000
/** How many times the agent is asked to fix a program that does not parse cleanly. */
export const MAX_BRIEF_REPAIRS = 2

const Text = defineComponent({
  name: 'Text',
  description: 'A short paragraph in Markdown: one to four sentences. Inline `code` and links only; no emphasis, headings, lists or tables.',
  props: z.object({ markdown: z.string() }),
  component: 'Text',
})

const Callout = defineComponent({
  name: 'Callout',
  description: 'One highlighted note. tone is "risk" for what could break, "warning" for what to double-check, "good" for what is safe, "info" for context. Use sparingly.',
  props: z.object({ tone: z.enum(['info', 'good', 'warning', 'risk']), text: z.string(), title: z.string().optional() }),
  component: 'Callout',
})

const KeyValue = defineComponent({
  name: 'KeyValue',
  description: 'One row of KeyValues: a short label and its value.',
  props: z.object({ label: z.string(), value: z.string() }),
  component: 'KeyValue',
})

const KeyValues = defineComponent({
  name: 'KeyValues',
  description: 'Facts as aligned label and value rows, such as the affected table, the flag, the version range. Two to eight rows.',
  props: z.object({ rows: z.array(KeyValue.ref) }),
  component: 'KeyValues',
})

const Table = defineComponent({
  name: 'Table',
  description: 'A comparison grid, such as what moves where. At most 6 columns and 12 rows, each cell a few words (inline Markdown). rows[i] has one cell per column.',
  props: z.object({ columns: z.array(z.string()), rows: z.array(z.array(z.string())) }),
  component: 'Table',
})

const CheckItem = defineComponent({
  name: 'CheckItem',
  description: 'One thing to verify. path and line, when given, make it open the diff at that place; the path must be one of the changed files exactly as listed.',
  props: z.object({ text: z.string(), path: z.string().optional(), line: z.number().optional() }),
  component: 'CheckItem',
})

const Checklist = defineComponent({
  name: 'Checklist',
  description: 'What the reviewer should verify, most important first. Two to six items, each a concrete check, not advice.',
  props: z.object({ items: z.array(CheckItem.ref) }),
  component: 'Checklist',
})

const Step = defineComponent({
  name: 'Step',
  description: 'One step of Steps: a short title and one or two sentences.',
  props: z.object({ title: z.string(), text: z.string() }),
  component: 'Step',
})

const Steps = defineComponent({
  name: 'Steps',
  description: 'An ordered sequence, such as how a request flows through the new code or the order a migration runs. Three to seven steps.',
  props: z.object({ items: z.array(Step.ref) }),
  component: 'Steps',
})

const FileEntry = defineComponent({
  name: 'FileEntry',
  description: 'One file of a FileList. path is a changed file exactly as listed; note says what to know about it in under 12 words; line is where to land in the new file.',
  props: z.object({ path: z.string(), note: z.string(), line: z.number().optional() }),
  component: 'FileEntry',
})

const FileList = defineComponent({
  name: 'FileList',
  description: 'The files worth reading first, in reading order, each opening the diff at that file. Two to eight entries; skip lockfiles, generated files and snapshots.',
  props: z.object({ files: z.array(FileEntry.ref) }),
  component: 'FileList',
})

const Diagram = defineComponent({
  name: 'Diagram',
  description: 'A Mermaid diagram of the code as it is after the change: flowchart TD or sequenceDiagram, at most 10 nodes, labels under 25 characters. Use only when shape is the point.',
  props: z.object({ mermaid: z.string(), caption: z.string().optional() }),
  component: 'Diagram',
})

const Code = defineComponent({
  name: 'Code',
  description: 'A short excerpt of the new code, under 15 lines, only when the exact code is the point. language is the fence name such as ts or kotlin.',
  props: z.object({ code: z.string(), language: z.string().optional(), caption: z.string().optional() }),
  component: 'Code',
})

const BeforeAfter = defineComponent({
  name: 'BeforeAfter',
  description: 'Two short snippets or values side by side, such as a config before and after, or an old and a new signature. Under 10 lines each.',
  props: z.object({ before: z.string(), after: z.string(), title: z.string().optional() }),
  component: 'BeforeAfter',
})

const Finding = defineComponent({
  name: 'Finding',
  description: 'A concrete problem you found while reading, with the input that triggers it. severity is "bug" when it breaks, "risk" when it may, "nit" for small things. path and line anchor it in the diff.',
  props: z.object({
    severity: z.enum(['bug', 'risk', 'nit']),
    title: z.string(),
    text: z.string(),
    path: z.string().optional(),
    line: z.number().optional(),
  }),
  component: 'Finding',
})

/** A prop that accepts any one of `components`, inline or by reference. */
function oneOf(components: readonly { ref: unknown }[]) {
  return z.union(components.map(component => component.ref) as never)
}

/** What a Section or the page itself may hold, apart from the containers. */
const blocks = [Text, Callout, KeyValues, Table, Checklist, Steps, FileList, Diagram, Code, BeforeAfter, Finding] as const

const Section = defineComponent({
  name: 'Section',
  description: 'A titled group of blocks the reader can fold, for the secondary parts of a large change. open is true when it should start unfolded (default true).',
  props: z.object({ title: z.string(), children: z.array(oneOf(blocks)), open: z.boolean().optional() }),
  component: 'Section',
})

const Stack = defineComponent({
  name: 'Stack',
  description: 'The page: its blocks from top to bottom. Three to twelve blocks.',
  props: z.object({ children: z.array(oneOf([Section, ...blocks])) }),
  component: 'Stack',
})

/** The components the agent may build a brief from. */
export const briefLibrary = createLibrary({
  root: 'Stack',
  id: 'pulls-review-brief',
  components: [Stack, Section, ...blocks, KeyValue, CheckItem, Step, FileEntry],
  componentGroups: [
    { name: 'Page', components: ['Stack', 'Section'], notes: ['Stack is always the root. Section only holds blocks, never another Section.'] },
    { name: 'Say', components: ['Text', 'Callout', 'Finding'], notes: ['Lead with the one thing the reviewer must not miss.'] },
    { name: 'Show', components: ['KeyValues', 'KeyValue', 'Table', 'Steps', 'Step', 'Diagram', 'Code', 'BeforeAfter'] },
    { name: 'Point', components: ['Checklist', 'CheckItem', 'FileList', 'FileEntry'], notes: ['Paths must be changed files, written exactly as the PR lists them.'] },
  ],
})

const EXAMPLES = [
  `root = Stack([lead, checks])
lead = Text("\`resolveGroups\` now keeps a path that left the diff as \`missing\` instead of dropping it, so a reopened PR no longer loses the notes of renamed files.")
checks = Checklist([CheckItem("A renamed file is still found by its previousPath", "src/group-utils.ts", 42), CheckItem("A deleted file shows as removed, not as uncategorized")])`,
  `root = Stack([risk, moves, flow, read])
risk = Callout("risk", "The new column is NOT NULL and the backfill runs in the same migration, so it locks \`orders\` while it runs on a large table.", "Locks on deploy")
moves = Table(["Column", "Before", "After"], [["status", "text", "enum"], ["total", "integer cents", "numeric(12,2)"]])
flow = Diagram("flowchart TD\\n  A[API] --> B[Validator]\\n  B --> C[(orders)]", "Write path after this change")
read = FileList([FileEntry("db/migrations/0042_orders.sql", "Adds the column and backfills", 1), FileEntry("api/orders.ts", "Writes the new column")])`,
]

export const BRIEF_SYSTEM_PROMPT = `${briefLibrary.prompt({
  preamble: `You are a staff engineer preparing the briefing page a reviewer reads on their phone before opening the diff of a pull request.
You decide what this page contains. Pick the components that make THIS change quickest to understand: a migration needs a table of what moves, a refactor a diagram of the new shape, a risky change a short checklist of what to verify, a one-line fix almost nothing. Leave out any block that carries no information; a short page beats a padded one.
Read the code first, the files around the change and the callers it affects. Every claim must come from code you read; if you are inferring, say so.
This page is the one screen that answers "what do I need to know before I review this?". It is not a summary of the description and not a tour.

${WRITING_RULES}`,
  additionalRules: [
    'Output only the OpenUI Lang program: no code fences, no commentary before or after it.',
    'Open with the single most important thing for the reviewer: a Callout or one Text. Give the page no title.',
    'Paths in CheckItem, FileEntry and Finding must be changed files, spelled exactly as the PR lists them. Never invent a path or a line.',
    'Do not repeat the title or the description. Do not list every file.',
    'Write the strings in the language named at the end of the user message. Code, paths and identifiers stay as they are.',
  ],
  examples: EXAMPLES,
  bindings: false,
  toolCalls: false,
  builtinFunctions: false,
})}`

function languageOf(locale: string): string {
  return LOCALES.find(entry => entry.code === locale)?.name ?? 'English'
}

/**
 * The user prompt: the PR, what the analysis already found out about it, and the diff.
 * @param diff - The pull request.
 * @param locale - The reader's language code.
 * @param analysis - The AI grouping of this diff, when there is one, so the page builds on it.
 * @returns The prompt text.
 */
export function buildBriefPrompt(diff: DiffsPayload, locale: string, analysis?: GroupedResult): string {
  const sections = [prHeading(diff)]
  const description = diff.description?.trim()
  if (description)
    sections.push(`## Description\n\n${description.slice(0, DESCRIPTION_CHAR_LIMIT)}`)
  if (analysis) {
    const groups = analysis.groups.map(group => `- ${group.label}: ${group.summary} (${[...group.filePaths, ...(group.children ?? []).flatMap(child => child.filePaths)].join(', ')})`)
    sections.push(`## What the analysis found\n\n${analysis.overallSummary ?? ''}\n\n${groups.join('\n')}`.trim())
  }
  sections.push(`## Changed files\n\n${buildManifest(diff.files)}`)
  const numbered = renderNumberedDiff(diff.files)
  if (numbered.length <= DIFF_CHAR_LIMIT)
    sections.push(`## What changed\n\nEach line is prefixed with its number in the old file (L) and the new file (R).\n\n${numbered}`)
  sections.push(`## Task

Design the briefing page for this pull request and write it as an OpenUI Lang program.
- Use your tools to read the files around the change and the callers it affects before you decide what the page needs.
- Choose only the components this change calls for.
- Output the program and nothing else.
- Write the strings in ${languageOf(locale)}.`)
  return sections.join('\n\n')
}

const parser = createParser(briefLibrary.toJSONSchema())

/**
 * Parses a brief with OpenUI's own parser against the brief library.
 * @param lang - OpenUI Lang source.
 * @returns The parse result: the element tree and what the parser found wrong with it.
 */
export function parseBrief(lang: string): ParseResult {
  return parser.parse(lang)
}

/**
 * What is wrong with a parsed brief, worded for the agent that wrote it.
 * @param result - The parse result.
 * @returns One line per problem; empty when the brief is sound.
 */
export function briefProblems(result: ParseResult): string[] {
  const problems = result.meta.errors.filter(error => error.severity !== 'warning').map(error => error.message)
  for (const name of result.meta.unresolved)
    problems.push(`\`${name}\` is used but never defined.`)
  for (const name of result.meta.orphaned)
    problems.push(`\`${name}\` is defined but no other statement uses it, so it does not render. Put it in its parent's list or delete it.`)
  if (result.meta.incomplete)
    problems.push('The program is cut off before its last statement ends.')
  if (!result.root)
    problems.push('There is no `root = Stack([...])` statement.')
  else if (!childrenOf(result.root).length)
    problems.push('The page has no blocks.')
  return problems
}

function childrenOf(node: ElementNode): unknown[] {
  const children = node.props.children
  return Array.isArray(children) ? children.filter(Boolean) : []
}

/** A node as the clients draw it: the component name and its props, children inline. */
export interface BriefNode {
  type: string
  props: Record<string, unknown>
}

function plain(value: unknown): unknown {
  if (Array.isArray(value))
    return value.filter(item => item !== null && item !== undefined).map(plain)
  if (value && typeof value === 'object' && (value as { type?: unknown }).type === 'element') {
    const node = value as ElementNode
    return { type: node.typeName, props: Object.fromEntries(Object.entries(node.props).map(([name, prop]) => [name, plain(prop)])) } satisfies BriefNode
  }
  return value
}

/**
 * The tree a client draws.
 * @param lang - OpenUI Lang source of a brief.
 * @returns The root node with every child resolved, or `undefined` when nothing parses to a page.
 */
export function briefTree(lang: string): BriefNode | undefined {
  const { root } = parseBrief(lang)
  return root && childrenOf(root).length ? plain(root) as BriefNode : undefined
}

/**
 * The program inside an answer that may wrap it in a code fence.
 * @param text - The engine's answer.
 * @returns The OpenUI Lang source.
 */
export function briefProgramOf(text: string): string {
  const trimmed = text.trim()
  const fenced = fencedContent(trimmed)
  if (fenced !== undefined)
    return fenced.trim()
  const start = trimmed.search(/^root\s*=/m)
  return (start > 0 ? trimmed.slice(start) : trimmed).trim()
}

/**
 * The prompt that asks for a fixed program: the original ask, the failed program, and what the parser said.
 * @param prompt - The original user prompt.
 * @param lang - The program that did not parse cleanly.
 * @param problems - What `briefProblems` found.
 * @returns The prompt text.
 */
export function buildRepairPrompt(prompt: string, lang: string, problems: string[]): string {
  return `${prompt}

## Your previous program

${lang}

## What the parser found wrong with it

${problems.map(problem => `- ${problem}`).join('\n')}

Fix exactly these problems and output the whole corrected program, and nothing else.`
}

export interface BriefRunOptions {
  locale: string
  /** The AI grouping of this diff, when there is one. */
  analysis?: GroupedResult
  signal?: AbortSignal
  onActivity?: (activities: AiActivity[]) => void
  /** Labels every CLI job of this brief alike, so their usage adds up under one task. */
  label?: string
}

function status(id: string, title: string, running: boolean): AiActivity {
  const now = Date.now()
  return { id, kind: 'status', title, status: running ? 'running' : 'done', startedAt: now, endedAt: running ? undefined : now, rev: 0 }
}

/**
 * Has the agent design the briefing page through Claude Code or Codex, then checks the program with OpenUI's parser and asks for fixes.
 * @param diff - The pull request.
 * @param resolved - The model; its provider must be a CLI engine.
 * @param options - Language, the analysis to build on, abort signal, activity listener and job label.
 * @returns The brief, as OpenUI Lang.
 * @throws When the provider is not a CLI engine or no repair attempt produced a page.
 */
export async function runBrief(diff: DiffsPayload, resolved: ResolvedModel, options: BriefRunOptions): Promise<BriefResult> {
  const engine = resolved.model.provider
  if (!isCliEngine(engine))
    throw new Error('A brief needs Claude Code or Codex as the AI provider.')
  const subject = serializeRef(diff.ref)
  const base = { engine, model: resolved.model.id || undefined, effort: resolved.effort, context: prContextOf(diff), label: options.label, system: BRIEF_SYSTEM_PROMPT }
  const hooks = { signal: options.signal, onActivity: options.onActivity }
  const prompt = buildBriefPrompt(diff, options.locale, options.analysis)

  options.onActivity?.([status('brief-design', 'Designing the brief', true)])
  let lang = ''
  let problems: string[] = []
  let tree: BriefNode | undefined
  let usage: BriefResult['usage']
  for (let attempt = 0; attempt <= MAX_BRIEF_REPAIRS; attempt++) {
    const answer = await runCli({
      ...base,
      prompt: attempt === 0 ? prompt : buildRepairPrompt(prompt, lang, problems),
      key: `brief:${subject}:${diff.head?.sha ?? ''}:${engine}:${resolved.model.id}:${options.locale}:${attempt}`,
    }, hooks)
    options.signal?.throwIfAborted()
    lang = briefProgramOf(answer.text)
    usage = answer.usage ?? usage
    const parsed = parseBrief(lang)
    problems = briefProblems(parsed)
    tree = briefTree(lang)
    if (!problems.length)
      break
    if (attempt < MAX_BRIEF_REPAIRS)
      options.onActivity?.([status('brief-fix', `Fixing ${problems.length} problem${problems.length === 1 ? '' : 's'} in the brief`, true)])
  }
  // Components with bad props are dropped by the parser, so a page that still has blocks is usable.
  if (!tree)
    throw new Error(`The brief did not parse: ${problems[0] ?? 'it has no page'}`)
  options.onActivity?.([status('brief-design', 'Designed the brief', false)])

  return {
    lang,
    headSha: diff.head?.sha,
    engine,
    model: usage?.model ?? (resolved.model.id || undefined),
    effort: resolved.effort,
    locale: options.locale,
    generatedAt: new Date().toISOString(),
  }
}
