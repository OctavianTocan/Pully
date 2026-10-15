import type { AiActivity, MyPull } from '../../../local-rpc'
import type { Locale } from '../../../locales'
import type { CliRunResult } from './cli-stream'
import type { ResolvedModel } from './model'
import { promptLanguageName } from '../../../locales'
import { isCliEngine, runCli } from './cli-stream'

/*
 * Ask from Home: a question about the reader's pull requests as a whole, not about one diff. The list their
 * inbox shows goes in the prompt; the model reads any one of those pull requests further through the inbox tools.
 */

const THREAD_TURNS = 4
/** Past these the list is cut, newest first: the prompt stays a list, not a dump. */
const OPEN_LIMIT = 200
const CLOSED_LIMIT = 50

export const INBOX_SYSTEM_PROMPT = `You are helping an engineer keep up with the pull requests on their plate: the ones they are asked to review, the ones they wrote, and the ones in their repositories. They ask from the inbox of a phone app.
The list below is what their inbox holds right now. Answer from it, and read a pull request through your tools when the question needs its description, its discussion or its code.
Answer in under 150 words. No headings. A short list is fine when the answer is several pull requests, one line each.
Name a pull request as owner/repo#number, exactly as the list does, so the app can turn it into a link. Never name one that is not in the list.
When the list and the tools cannot settle the question, say so in one sentence and name what would.

How to write:
- Plain English. Short sentences, active voice, one idea per sentence.
- Lead with the answer. Detail comes after that, or not at all.
- Name the actual pull request, person, check or number. Never a paraphrase of it.
- No throat-clearing, no restating the question, no summary of what you are about to say.
- No bold, no italics, no emoji, no headings. Sentence case.`

/** What an inbox question reads: the open pull requests, and the ones closed lately. */
export interface InboxPulls {
  open: MyPull[]
  closed: MyPull[]
}

export interface InboxTurn {
  question: string
  answer: string
}

/** A question about the inbox, with the earlier turns of the conversation. */
export interface InboxRequest {
  question: string
  /** Earlier questions and answers, oldest first. */
  history: InboxTurn[]
}

/**
 * How long ago `iso` was, the way a person says it: `just now`, `5m ago`, `3h ago`, `2d ago`.
 * @param iso - An ISO timestamp.
 * @param now - Epoch milliseconds to measure from.
 * @returns The age, or `undefined` when the timestamp does not parse.
 */
export function ageOf(iso: string | null | undefined, now: number): string | undefined {
  const at = iso ? Date.parse(iso) : Number.NaN
  if (Number.isNaN(at))
    return undefined
  const minutes = Math.max(0, Math.round((now - at) / 60_000))
  if (minutes < 1)
    return 'just now'
  if (minutes < 60)
    return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 48)
    return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

function roleOf(pull: MyPull): string | undefined {
  if (pull.requestedFromMe || pull.role === 'review-requested')
    return 'your review is requested'
  switch (pull.role) {
    case 'authored': return 'you wrote it'
    case 'owned': return 'in your repository'
    case 'involved': return 'you are involved'
    default: return undefined
  }
}

const CHECKS: Record<string, string> = { success: 'checks pass', failure: 'checks failing', pending: 'checks running', none: 'no checks' }
const DECISIONS: Record<string, string> = { 'approved': 'approved', 'changes-requested': 'changes requested', 'review-required': 'needs a review' }

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`
}

/** Where an open pull request stands: its checks, its review, conflicts and who it waits on. */
function standing(pull: MyPull): (string | undefined)[] {
  if (pull.state !== 'open')
    return []
  return [
    pull.checks ? CHECKS[pull.checks] ?? pull.checks : undefined,
    pull.reviewDecision ? DECISIONS[pull.reviewDecision] ?? pull.reviewDecision : undefined,
    pull.mergeable === 'conflicting' ? 'has conflicts' : undefined,
    pull.reviewRequests?.length ? `waiting on ${pull.reviewRequests.join(', ')}` : undefined,
  ]
}

function sizeOf(pull: MyPull): string | undefined {
  if (pull.additions === undefined || pull.deletions === undefined)
    return undefined
  return `+${pull.additions} −${pull.deletions}${pull.changedFiles === undefined ? '' : ` in ${plural(pull.changedFiles, 'file')}`}`
}

function whenOf(pull: MyPull, now: number): string | undefined {
  const age = ageOf(pull.updatedAt, now)
  if (pull.state === 'closed')
    return `${pull.merged ? 'merged' : 'closed'}${age ? ` ${age}` : ''}`
  return age ? `updated ${age}` : undefined
}

/**
 * One pull request as one line of the list.
 * @param pull - The pull request.
 * @param now - Epoch milliseconds, for its age.
 * @returns `owner/repo#12 "Title" · by alice · …`.
 */
export function inboxLine(pull: MyPull, now: number): string {
  const closed = pull.state === 'closed'
  const parts = [
    `${pull.owner}/${pull.repo}#${pull.number} "${pull.title.replace(/\s+/g, ' ').trim()}"`,
    `by ${pull.author || 'someone'}`,
    closed ? whenOf(pull, now) : undefined,
    roleOf(pull),
    pull.isDraft ? 'draft' : undefined,
    ...standing(pull),
    sizeOf(pull),
    pull.commentsCount ? plural(pull.commentsCount, 'comment') : undefined,
    pull.labels.length ? `labels ${pull.labels.join(', ')}` : undefined,
    closed ? undefined : whenOf(pull, now),
  ]
  return `- ${parts.filter(Boolean).join(' · ')}`
}

function newestFirst(pulls: MyPull[]): MyPull[] {
  return [...pulls].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

/**
 * The inbox as the prompt shows it: open pull requests grouped by repository, then the ones closed lately.
 * @param pulls - Open and recently closed pull requests.
 * @param now - Epoch milliseconds, for ages.
 * @returns The list as Markdown.
 */
export function renderInbox(pulls: InboxPulls, now: number): string {
  const open = newestFirst(pulls.open).slice(0, OPEN_LIMIT)
  const byRepo = new Map<string, MyPull[]>()
  for (const pull of open)
    byRepo.set(`${pull.owner}/${pull.repo}`, [...byRepo.get(`${pull.owner}/${pull.repo}`) ?? [], pull])
  const parts = [`## Open (${pulls.open.length})`]
  if (!open.length)
    parts.push('None.')
  for (const [repo, list] of [...byRepo].sort(([a], [b]) => a.localeCompare(b)))
    parts.push(`### ${repo}\n${list.map(pull => inboxLine(pull, now)).join('\n')}`)
  if (pulls.open.length > open.length)
    parts.push(`(${pulls.open.length - open.length} older open pull requests are left out.)`)
  const closed = newestFirst(pulls.closed).slice(0, CLOSED_LIMIT)
  if (closed.length)
    parts.push(`## Closed lately\n${closed.map(pull => inboxLine(pull, now)).join('\n')}`)
  return parts.join('\n\n')
}

/**
 * The user message for one question about the inbox.
 * @param pulls - What the inbox holds.
 * @param request - The question and the conversation so far.
 * @param now - Epoch milliseconds, for ages and today's date.
 * @param locale - The reader's language, for when the question does not show it.
 * @returns The prompt text.
 */
export function buildInboxPrompt(pulls: InboxPulls, request: InboxRequest, now: number, locale: Locale): string {
  const parts = [`# The reader's pull requests, as of ${new Date(now).toISOString().slice(0, 16).replace('T', ' ')} UTC`, renderInbox(pulls, now)]
  for (const turn of request.history.slice(-THREAD_TURNS))
    parts.push(`Earlier question: ${turn.question}\nYour answer: ${turn.answer}`)
  parts.push(`## Question\n\n${request.question.trim()}`)
  parts.push(`Answer in the language of the question (in ${promptLanguageName(locale)} when you cannot tell).`)
  return parts.join('\n\n')
}

export interface InboxRunOptions {
  signal?: AbortSignal
  /** Live work-log entries; entries repeat as they update, merge by `id`. */
  onActivity?: (activities: AiActivity[]) => void
  /** Epoch milliseconds to date the list from; now by default. */
  now?: number
}

/**
 * Answers one question about the reader's pull requests through Claude Code or Codex on the server.
 * @param pulls - What the inbox holds.
 * @param resolved - The model to answer with; its provider must be a CLI engine.
 * @param locale - The reader's language.
 * @param request - The question and the conversation so far.
 * @param options - Abort signal, live activity listener and the clock.
 * @returns The answer as Markdown prose, and the usage the CLI reported.
 * @throws When the provider is not a CLI engine or the build has no CLI runner.
 */
export async function askAboutInbox(pulls: InboxPulls, resolved: ResolvedModel, locale: Locale, request: InboxRequest, options: InboxRunOptions = {}): Promise<CliRunResult> {
  const engine = resolved.model.provider
  if (!isCliEngine(engine))
    throw new Error('Asking about your pull requests needs Claude Code or Codex as the AI provider.')
  const listed = [...pulls.open, ...pulls.closed].map(({ owner, repo, number }) => ({ owner, repo, number }))
  const result = await runCli({
    engine,
    model: resolved.model.id || undefined,
    effort: resolved.effort,
    system: INBOX_SYSTEM_PROMPT,
    prompt: buildInboxPrompt(pulls, request, options.now ?? Date.now(), locale),
    label: 'Ask about your pull requests',
    inbox: listed.length ? { pulls: listed } : undefined,
  }, { signal: options.signal, onActivity: options.onActivity })
  if (options.signal?.aborted)
    throw new DOMException('Aborted', 'AbortError')
  return { text: result.text.trim(), usage: result.usage }
}
