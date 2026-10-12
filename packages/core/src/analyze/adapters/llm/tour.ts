import type { AiActivity } from '../../../local-rpc'
import type { DiffsPayload } from '../../../types/diff'
import type { TourBlock, TourPlan, TourResult, TourStop } from '../../../types/tour'
import type { ResolvedModel } from './model'
import { LOCALES } from '../../../locales'
import { serializeRef } from '../../../types/source'
import { isCliEngine, runCli } from './cli-stream'
import { fencedContent, prContextOf, prHeading, renderNumberedDiff, WRITING_RULES } from './critique'

/*
 * A guided tour of a pull request, with no diffs. After DeepWiki: one run plans the stops,
 * then one run per stop writes it, in parallel. Each stop explains with the smallest visual
 * that makes its point, as the show-me skill does.
 */

export const MIN_STOPS = 3
export const MAX_STOPS = 8
const DESCRIPTION_CHAR_LIMIT = 4_000

export const TOUR_PLAN_SYSTEM_PROMPT = `You are a staff engineer giving a teammate a guided tour of a pull request before they review it.
You are planning the tour, not writing it. The plan decides everything: which systems the change touches, what matters, what can be skipped, and in what order a newcomer should meet it.
Read the code. The diff shows what changed; read the files around it and the callers it does not touch, so the stops follow how the code actually works, not how the files happen to be listed.
A good tour is short. Cover what the reader must understand to review or own this change, not every file.

${WRITING_RULES}`

export const TOUR_STOP_SYSTEM_PROMPT = `You are a staff engineer writing one stop of a guided tour of a pull request.
The reader will not see the diff. Explain the code as it stands after the change: what it does, how the pieces connect, and why it was built this way. Mention what it replaced only when that explains the new shape.
Show, then tell. Pick the smallest visual that makes the point and put one or two sentences beside it. Never paste a whole file.
Every claim must come from code you read. If you are inferring, say so.

${WRITING_RULES}`

/** The plan: the tour's table of contents and glossary. */
export const TOUR_PLAN_SCHEMA = {
  type: 'object',
  properties: {
    title: { type: 'string', description: 'The tour title, under 70 characters: what this pull request is really about.' },
    overview: { type: 'string', description: 'Three to five sentences: what the change does, the one or two things a reviewer must understand, and what is safe to skim. Markdown allowed, no headings.' },
    stops: {
      type: 'array',
      description: `Between ${MIN_STOPS} and ${MAX_STOPS} stops, in the order a newcomer should meet them: entry point or user-facing behaviour first, internals after.`,
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'The stop title, under 50 characters. Name the system or idea, not a file.' },
          purpose: { type: 'string', description: 'One or two sentences: what the reader will understand after this stop and why it matters for the change.' },
          files: { type: 'array', items: { type: 'string' }, description: 'Paths this stop is about, changed or not. At most 8.' },
          notes: { type: 'string', description: 'What you found while reading that the writer of this stop must know: names of functions, the flow, the gotcha. Plain text.' },
        },
        required: ['title', 'purpose', 'files', 'notes'],
        additionalProperties: false,
      },
    },
    glossary: {
      type: 'array',
      description: 'Terms from this code a newcomer would not know: domain words, type names, flags. Up to 10. Empty when there are none.',
      items: {
        type: 'object',
        properties: { term: { type: 'string' }, meaning: { type: 'string', description: 'One sentence.' } },
        required: ['term', 'meaning'],
        additionalProperties: false,
      },
    },
  },
  required: ['title', 'overview', 'stops', 'glossary'],
  additionalProperties: false,
} as const

/**
 * One written stop. The body is Markdown with fenced visuals, the shape models already write
 * well (a nested block schema made Claude retry its answer again and again).
 */
export const TOUR_STOP_SCHEMA = {
  type: 'object',
  properties: {
    takeaway: { type: 'string', description: 'One sentence the reader should remember from this stop.' },
    body: { type: 'string', description: 'The stop in Markdown: short paragraphs alternating with fenced visuals. No headings.' },
    sources: {
      type: 'array',
      description: 'Where in the code the reader can check this stop: up to 6 places you read.',
      items: {
        type: 'object',
        properties: {
          path: { type: 'string' },
          line: { type: 'number', description: 'Line in the new version of the file; 0 for the whole file.' },
          why: { type: 'string', description: 'What is there, under 60 characters.' },
        },
        required: ['path', 'line', 'why'],
        additionalProperties: false,
      },
    },
  },
  required: ['takeaway', 'body', 'sources'],
  additionalProperties: false,
} as const

/** Fence languages that name a sketch kind; any other language is a code excerpt. */
const SHAPES = new Set(['calls', 'tree', 'pseudo', 'flow', 'code'])

/**
 * Splits a stop's Markdown into prose and visuals: a `mermaid` fence is a diagram, a fence
 * of a sketch kind is that sketch, any other fence is a code excerpt.
 * @param markdown - The stop body.
 * @returns The blocks in reading order.
 */
export function splitTourBody(markdown: string): TourBlock[] {
  const blocks: TourBlock[] = []
  let prose: string[] = []
  let fence: OpenFence | undefined
  const flushProse = () => {
    const text = prose.join('\n').trim()
    if (text)
      blocks.push({ kind: 'text', text })
    prose = []
  }
  for (const line of markdown.split('\n')) {
    if (fence) {
      if (closesFence(line, fence.marker)) {
        const block = fenceBlock(fence)
        if (block)
          blocks.push(block)
        fence = undefined
      }
      else {
        fence.lines.push(line)
      }
      continue
    }
    const opened = openFence(line)
    if (opened) {
      flushProse()
      fence = opened
    }
    else {
      prose.push(line)
    }
  }
  // An unclosed fence keeps its text as prose rather than losing it.
  if (fence)
    prose.push(`${fence.marker}${fence.lang}`, ...fence.lines)
  flushProse()
  return blocks
}

interface OpenFence { marker: string, lang: string, lines: string[] }

function openFence(line: string): OpenFence | undefined {
  const trimmed = line.trim()
  const marker = trimmed.startsWith('```') ? '```' : trimmed.startsWith('~~~') ? '~~~' : undefined
  if (!marker)
    return undefined
  const info = trimmed.slice(marker.length).replace(/^[`~]*/, '').trim()
  return { marker, lang: (info.split(/\s/)[0] ?? '').toLowerCase(), lines: [] }
}

/** A closing fence is the opening marker (or longer) and nothing else. */
function closesFence(line: string, marker: string): boolean {
  const trimmed = line.trim()
  return trimmed.startsWith(marker) && trimmed.replaceAll(marker[0]!, '') === ''
}

function fenceBlock(fence: OpenFence): TourBlock | undefined {
  const body = fence.lines.join('\n').trimEnd()
  if (!body)
    return undefined
  if (fence.lang === 'mermaid')
    return { kind: 'diagram', text: '', body }
  const sketch = SHAPES.has(fence.lang)
  return { kind: 'shape', text: sketch ? '' : fence.lang, lang: sketch ? fence.lang : 'code', body }
}

function languageOf(locale: string): string {
  return LOCALES.find(entry => entry.code === locale)?.name ?? 'English'
}

function str(value: unknown): string {
  return typeof value === 'string' ? value.trim().replace(/"\s*\}+$/, '').trimEnd() : ''
}

function parseJson(text: string): Record<string, unknown> {
  const parsed = JSON.parse(fencedContent(text) ?? text.trim()) as unknown
  if (!parsed || typeof parsed !== 'object')
    throw new Error('The tour engine returned no object.')
  return parsed as Record<string, unknown>
}

/**
 * The planning prompt: the PR, its description, and the numbered diff.
 * @param diff - The pull request.
 * @param locale - The reader's language code.
 * @returns The prompt text.
 */
export function buildTourPlanPrompt(diff: DiffsPayload, locale: string): string {
  const sections = [prHeading(diff)]
  const description = diff.description?.trim()
  if (description)
    sections.push(`## Description\n\n${description.slice(0, DESCRIPTION_CHAR_LIMIT)}`)
  sections.push(`## What changed\n\nEach line is prefixed with its number in the old file (L) and the new file (R).\n\n${renderNumberedDiff(diff.files)}`)
  sections.push(`## Task

Plan a guided tour of this pull request for a teammate who has never seen this code.
- Use your tools to read the files around the change and the callers it affects before you decide the stops.
- ${MIN_STOPS} to ${MAX_STOPS} stops. Fewer is better when the change is small. Each stop is one idea a reviewer must hold in their head.
- Order the stops so each builds on the last.
- Skip generated files, lockfiles, snapshots and pure renames unless they are the point.
- Write in ${languageOf(locale)}.`)
  return sections.join('\n\n')
}

/**
 * The prompt for one stop: the whole plan for context, then this stop's files.
 * @param diff - The pull request.
 * @param plan - The finished plan.
 * @param index - Which stop to write.
 * @param locale - The reader's language code.
 * @returns The prompt text.
 */
export function buildTourStopPrompt(diff: DiffsPayload, plan: TourPlan, index: number, locale: string): string {
  const stop = plan.stops[index]!
  const outline = plan.stops.map((entry, at) => `${at + 1}. ${entry.title}: ${entry.purpose}${at === index ? '   <- this stop' : ''}`).join('\n')
  const wanted = new Set(stop.files)
  const files = diff.files.filter(file => wanted.has(file.path) || (file.previousPath && wanted.has(file.previousPath)))
  const sections = [
    prHeading(diff),
    `## The tour\n\n${plan.title}\n\n${plan.overview}\n\nStops:\n${outline}`,
    `## This stop: ${stop.title}\n\n${stop.purpose}\n\nFiles: ${stop.files.join(', ') || '(none listed)'}\n\nNotes from the planner:\n${stop.notes || '(none)'}`,
  ]
  if (files.length)
    sections.push(`## What changed in these files\n\nFor your reading only. The reader never sees this diff.\n\n${renderNumberedDiff(files)}`)
  sections.push(`## Task

Write stop ${index + 1} of ${plan.stops.length}.
- Read the files with your tools first: the new version, and the callers when the stop is about a flow.
- Explain the code as it is after this pull request. Do not show diffs, do not use +/- lines, do not narrate the change line by line.
- Write the body as Markdown: one to four sentences, then a visual, then the next point. No headings.
- Visuals are fenced blocks, each under 25 lines of 60 characters:
  - \`\`\`calls for a call tree of the runtime flow (indent two spaces per level),
  - \`\`\`tree for a file tree with a short comment per entry,
  - \`\`\`pseudo for the logic as pseudocode,
  - \`\`\`flow for data moving between parts, as text arrows,
  - \`\`\`mermaid for a small flowchart TD or sequenceDiagram (at most 10 nodes, labels under 25 characters),
  - a fence in the source language (\`\`\`ts, \`\`\`rust...) for a short excerpt of the new code, only when the exact code is the point.
- Use two to four visuals. Pick the one that makes each point fastest.
- Do not repeat what earlier stops cover; refer to them by name when needed.
- Write in ${languageOf(locale)}. Code, paths and identifiers stay as they are.`)
  return sections.join('\n\n')
}

/**
 * Validates the plan answer.
 * @param raw - The parsed answer.
 * @returns The plan, with stops capped at {@link MAX_STOPS}.
 * @throws When there are no usable stops.
 */
export function resolveTourPlan(raw: Record<string, unknown>): TourPlan {
  const stops = (Array.isArray(raw.stops) ? raw.stops : []).flatMap((entry) => {
    const item = (entry ?? {}) as Record<string, unknown>
    const title = str(item.title)
    if (!title)
      return []
    return [{
      title,
      purpose: str(item.purpose),
      files: (Array.isArray(item.files) ? item.files : []).map(str).filter(Boolean).slice(0, 8),
      notes: str(item.notes),
    }]
  }).slice(0, MAX_STOPS)
  if (!stops.length)
    throw new Error('The tour plan had no stops.')
  const glossary = (Array.isArray(raw.glossary) ? raw.glossary : []).flatMap((entry) => {
    const item = (entry ?? {}) as Record<string, unknown>
    const term = str(item.term)
    return term ? [{ term, meaning: str(item.meaning) }] : []
  }).slice(0, 12)
  return { title: str(raw.title) || 'Tour', overview: str(raw.overview), stops, glossary }
}

/**
 * Validates one written stop.
 * @param raw - The parsed answer.
 * @returns The stop body.
 */
export function resolveTourStop(raw: Record<string, unknown>): Pick<TourStop, 'takeaway' | 'blocks' | 'sources'> {
  const body = typeof raw.body === 'string' ? raw.body : ''
  const sources = (Array.isArray(raw.sources) ? raw.sources : []).flatMap((entry) => {
    const item = (entry ?? {}) as Record<string, unknown>
    const path = str(item.path)
    const line = typeof item.line === 'number' && Number.isFinite(item.line) ? Math.max(0, Math.floor(item.line)) : 0
    return path ? [{ path, line, why: str(item.why) }] : []
  }).slice(0, 6)
  return { takeaway: str(raw.takeaway), blocks: splitTourBody(body), sources }
}

export interface TourRunOptions {
  locale: string
  signal?: AbortSignal
  onActivity?: (activities: AiActivity[]) => void
  /** Labels every CLI job of this tour alike, so their usage adds up under one task. */
  label?: string
}

function status(id: string, title: string, running: boolean): AiActivity {
  const now = Date.now()
  return { id, kind: 'status', title, status: running ? 'running' : 'done', startedAt: now, endedAt: running ? undefined : now, rev: 0 }
}

/**
 * Plans the tour, then writes every stop in parallel through Claude Code or Codex.
 * @param diff - The pull request.
 * @param resolved - The model; its provider must be a CLI engine.
 * @param options - Language, abort signal, activity listener and job label.
 * @returns The tour; a stop that failed to write keeps its plan and carries `error`.
 * @throws When the provider is not a CLI engine or the plan fails.
 */
export async function runTour(diff: DiffsPayload, resolved: ResolvedModel, options: TourRunOptions): Promise<TourResult> {
  const engine = resolved.model.provider
  if (!isCliEngine(engine))
    throw new Error('A tour needs Claude Code or Codex as the AI provider.')
  const subject = serializeRef(diff.ref)
  const context = prContextOf(diff)
  const base = { engine, model: resolved.model.id || undefined, effort: resolved.effort, context, label: options.label }
  const hooks = { signal: options.signal, onActivity: options.onActivity }

  options.onActivity?.([status('tour-plan', 'Planning the tour', true)])
  const planned = await runCli({
    ...base,
    system: TOUR_PLAN_SYSTEM_PROMPT,
    prompt: buildTourPlanPrompt(diff, options.locale),
    schema: TOUR_PLAN_SCHEMA,
    key: `tour-plan:${subject}:${diff.head?.sha ?? ''}:${engine}:${resolved.model.id}:${options.locale}`,
  }, hooks)
  options.signal?.throwIfAborted()
  const plan = resolveTourPlan(parseJson(planned.text))
  options.onActivity?.([status('tour-plan', `Planned ${plan.stops.length} stops`, false)])

  let written = 0
  const stops = await Promise.all(plan.stops.map(async (stop, index): Promise<TourStop> => {
    try {
      const answer = await runCli({
        ...base,
        system: TOUR_STOP_SYSTEM_PROMPT,
        prompt: buildTourStopPrompt(diff, plan, index, options.locale),
        schema: TOUR_STOP_SCHEMA,
        key: `tour-stop:${subject}:${diff.head?.sha ?? ''}:${engine}:${resolved.model.id}:${options.locale}:${index}:${stop.title}`,
      }, hooks)
      options.signal?.throwIfAborted()
      return { title: stop.title, purpose: stop.purpose, files: stop.files, ...resolveTourStop(parseJson(answer.text)) }
    }
    catch (error) {
      if (options.signal?.aborted)
        throw error
      return { title: stop.title, purpose: stop.purpose, files: stop.files, takeaway: '', blocks: [], sources: [], error: error instanceof Error ? error.message : String(error) }
    }
    finally {
      written++
      options.onActivity?.([status('tour-write', `Wrote ${written} of ${plan.stops.length} stops`, written < plan.stops.length)])
    }
  }))
  if (stops.every(stop => stop.error))
    throw new Error(stops[0]?.error ?? 'No stop of the tour could be written.')

  return {
    title: plan.title,
    overview: plan.overview,
    stops,
    glossary: plan.glossary,
    headSha: diff.head?.sha,
    engine,
    model: planned.usage?.model ?? (resolved.model.id || undefined),
    effort: resolved.effort,
    locale: options.locale,
    generatedAt: new Date().toISOString(),
  }
}
