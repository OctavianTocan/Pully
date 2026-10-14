import type { Locale } from '../../../locales'
import type { DiffsPayload } from '../../../types/diff'
import type { CliEngine } from './cli-stream'
import { promptLanguageName } from '../../../locales'
import { runCli } from './cli-stream'
import { fencedContent, prHeading } from './critique'

/*
 * The questions under an answer in Ask (rule 99): two or three a reviewer might ask next,
 * written after every turn by a small, quick model rather than the one that answered.
 */

/** A small, quick model per engine: Haiku for Claude Code, Codex's own default at low effort. */
export const SUGGESTION_MODELS: Record<CliEngine, { model?: string, effort?: string }> = {
  'claude-code': { model: 'haiku' },
  'codex': { effort: 'low' },
}

export const MAX_SUGGESTIONS = 3
const SUGGESTION_CHAR_LIMIT = 120
const ANSWER_CHAR_LIMIT = 6_000

export const SUGGESTIONS_SYSTEM_PROMPT = `You suggest what a code reviewer might ask next about a pull request, after reading the last question and its answer.
- Two or three questions, each under 80 characters, each answerable from this pull request.
- Follow what the answer opened up: a name it used, a case it did not cover, where to look next. Never ask again what was just asked.
- Plain questions in the reader's own words, no Markdown, each ending with a question mark.`

export const SUGGESTIONS_SCHEMA = {
  type: 'object',
  properties: {
    questions: { type: 'array', items: { type: 'string' }, description: 'Two or three short follow-up questions.' },
  },
  required: ['questions'],
  additionalProperties: false,
} as const

/**
 * The prompt for the suggestions: the pull request's heading, the question and the answer.
 * @param diff - The pull request.
 * @param question - What the reviewer asked.
 * @param answer - The reply, as Markdown.
 * @param locale - The reader's language.
 * @returns The prompt text.
 */
export function buildSuggestionsPrompt(diff: DiffsPayload, question: string, answer: string, locale: Locale): string {
  return [
    prHeading(diff),
    `## The question\n\n${question.trim()}`,
    `## The answer\n\n${answer.trim().slice(0, ANSWER_CHAR_LIMIT)}`,
    `Write the questions in the language of the question above (in ${promptLanguageName(locale)} when you cannot tell), as the answer does.`,
  ].join('\n\n')
}

/**
 * The usable questions of an answer: trimmed, short, ending in a question mark, none the
 * reviewer just asked and no two alike, at most {@link MAX_SUGGESTIONS}.
 * @param raw - The parsed answer.
 * @param asked - The question that was just answered.
 * @returns The questions, possibly none.
 */
export function resolveSuggestions(raw: unknown, asked: string): string[] {
  const list = (raw as { questions?: unknown } | undefined)?.questions
  if (!Array.isArray(list))
    return []
  const key = (text: string) => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
  const seen = new Set([key(asked)])
  const out: string[] = []
  for (const item of list) {
    if (typeof item !== 'string')
      continue
    const text = item.replace(/\s+/g, ' ').trim()
    if (!text || text.length > SUGGESTION_CHAR_LIMIT || !/[?？]$/.test(text) || seen.has(key(text)))
      continue
    seen.add(key(text))
    out.push(text)
    if (out.length === MAX_SUGGESTIONS)
      break
  }
  return out
}

/**
 * Asks the engine's small model for the questions to offer after an answer. Never throws: an
 * answer stands without suggestions.
 * @param diff - The pull request.
 * @param engine - The engine that answered; its small model writes the questions.
 * @param question - What the reviewer asked.
 * @param answer - The reply.
 * @param locale - The reader's language.
 * @param signal - Stops the request with the task.
 * @returns Up to three questions.
 */
export async function suggestQuestions(diff: DiffsPayload, engine: CliEngine, question: string, answer: string, locale: Locale, signal?: AbortSignal): Promise<string[]> {
  if (!question.trim() || !answer.trim())
    return []
  try {
    const result = await runCli({
      engine,
      ...SUGGESTION_MODELS[engine],
      system: SUGGESTIONS_SYSTEM_PROMPT,
      prompt: buildSuggestionsPrompt(diff, question, answer, locale),
      schema: SUGGESTIONS_SCHEMA,
      label: 'Suggest follow-up questions',
    }, { signal })
    return resolveSuggestions(JSON.parse(fencedContent(result.text) ?? result.text.trim()), question)
  }
  catch {
    return []
  }
}
