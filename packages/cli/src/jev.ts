import type { Env } from '@pulls.review/core/env'
import process from 'node:process'

/**
 * Jev, TypeSafe AI's classification model. It never writes text: it answers typed
 * questions (a choice, a score, a yes/no) about a `state`, cheaply and fast, so the
 * server can sort pull requests and files with it as soon as they arrive.
 * https://docs.typesafe.ai/api
 */
export const JEV_ENDPOINT = 'https://api.typesafe.ai/v1/systemone'
export const JEV_MODEL = 'jev-latest'

const CONCURRENCY = 8
const MAX_ATTEMPTS = 3
const TIMEOUT_MS = 30_000
/** The first retry waits this long, each later one twice as long as the one before. */
const BACKOFF_MS = 1_000
/** A `retry-after` beyond this is cut short: the list or the file page is waiting on fresher answers. */
const MAX_RETRY_AFTER_MS = 20_000
/** Kept a little under TypeSafe's limits of 80 requests and 100k input tokens a second. */
const REQUESTS_PER_SECOND = 75
const INPUT_TOKENS_PER_SECOND = 90_000

export type JevState = string | Record<string, unknown> | unknown[]

export interface JevChoiceQuestion {
  type: 'choice'
  instructions: string
  /** Each option (up to 255) and what it means. */
  criteria: Readonly<Record<string, string>>
}

export interface JevScoreQuestion {
  type: 'score'
  instructions: string
  /** 2 to 10 levels, lowest first. */
  criteria: readonly string[]
}

export interface JevNoulQuestion {
  type: 'noul'
  instructions: string
  criteria?: { readonly true: string, readonly false: string }
}

export type JevQuestion = JevChoiceQuestion | JevScoreQuestion | JevNoulQuestion

export interface JevChoiceAnswer {
  type: 'choice'
  choice: string
  probabilities: Record<string, number>
  confidence: number
}

export interface JevScoreAnswer {
  type: 'score'
  /** Probability-weighted, 0-based: it can sit between two levels. */
  score: number
  legend: Record<string, string>
  probabilities: Record<string, number>
  confidence: number
}

export interface JevNoulAnswer {
  type: 'noul'
  /** 0 to 1. */
  noul: number
}

type AnswerTo<Q extends JevQuestion> = Q extends JevChoiceQuestion ? JevChoiceAnswer : Q extends JevScoreQuestion ? JevScoreAnswer : JevNoulAnswer

export interface JevResponse<Q extends Record<string, JevQuestion>> {
  /** The exact model that answered, such as `jev-1.13.0`. */
  model: string
  answers: { [K in keyof Q]: AnswerTo<Q[K]> }
  usage: { input_tokens: number, output_tokens: number }
}

/** A request Jev did not answer: refused input (400, 413, 415, 422), overload after the retries, or a timeout. */
export class JevError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
    this.name = 'JevError'
  }
}

export interface JevClient {
  /** A key is configured and TypeSafe has not refused it during this run. */
  enabled: () => boolean
  /**
   * @param state What the questions are about.
   * @param questions The questions, by the id each answer comes back under.
   * @returns The answers, or `'off'` when there is no key or TypeSafe refused it (401) during this run.
   * @throws {JevError} When Jev does not answer this request.
   */
  ask: <Q extends Record<string, JevQuestion>>(state: JevState, questions: Q) => Promise<JevResponse<Q> | 'off'>
}

export interface JevClientOptions {
  /** No key means Jev is off: every call answers `'off'`. */
  apiKey?: string
  fetch?: typeof globalThis.fetch
  concurrency?: number
  timeoutMs?: number
  /** Where the one-line notices go; never given the key. */
  log?: (line: string) => void
  /** The clock and the wait between retries, replaceable in tests. */
  now?: () => number
  sleep?: (ms: number) => Promise<void>
}

/**
 * @param env The process environment.
 * @returns The TypeSafe key: `PULLS_REVIEW_TYPESAFE_API_KEY`, else `TYPESAFE_API_KEY`.
 */
export function jevKeyFromEnv(env: Env): string | undefined {
  return [env.PULLS_REVIEW_TYPESAFE_API_KEY, env.TYPESAFE_API_KEY].map(value => value?.trim()).find(value => value)
}

/** Milliseconds a `retry-after` header asks for, in seconds or as an HTTP date. */
function retryAfterMs(header: string | null, now: number): number | undefined {
  if (!header)
    return undefined
  const seconds = Number(header)
  if (Number.isFinite(seconds))
    return Math.max(0, seconds * 1000)
  const at = Date.parse(header)
  return Number.isNaN(at) ? undefined : Math.max(0, at - now)
}

/**
 * @param payload A successful response's body.
 * @param questions What was asked.
 * @returns What is wrong with the answers, or `undefined` when every question came back answered in its own type.
 */
function answerProblem(payload: unknown, questions: Record<string, JevQuestion>): string | undefined {
  const answers = (payload as { answers?: Record<string, { type?: unknown } | undefined> } | undefined)?.answers
  if (!answers || typeof answers !== 'object')
    return 'Jev answered without answers.'
  const missing = Object.entries(questions).find(([id, question]) => answers[id]?.type !== question.type)
  return missing ? `Jev did not answer "${missing[0]}".` : undefined
}

function isAnswered<Q extends Record<string, JevQuestion>>(payload: unknown, questions: Q): payload is JevResponse<Q> {
  return answerProblem(payload, questions) === undefined
}

/** The message a refused request's body gives, as `error` or `error.message`. */
function errorDetail(payload: unknown): string | undefined {
  const error = (payload as { error?: unknown } | undefined)?.error
  const detail = typeof error === 'string' ? error : (error as { message?: unknown } | undefined)?.message
  return typeof detail === 'string' ? detail.slice(0, 300) : undefined
}

/**
 * A Jev client with its own limits: at most `concurrency` requests in flight, paced under
 * TypeSafe's rate limits, 429 and 529 retried with backoff (three attempts in all), each
 * attempt cut off after `timeoutMs`. A refused key turns it off for the rest of the run.
 *
 * @param options The key and the parts tests replace.
 * @returns The client.
 */
export function createJevClient(options: JevClientOptions = {}): JevClient {
  const {
    apiKey,
    fetch = globalThis.fetch,
    concurrency = CONCURRENCY,
    timeoutMs = TIMEOUT_MS,
    log = line => process.stderr.write(`pulls.review: ${line}\n`),
    now = Date.now,
    sleep = ms => new Promise(resolve => setTimeout(resolve, ms)),
  } = options
  let refused = false
  let active = 0
  const waiting: (() => void)[] = []
  let nextStart = 0
  const reported = new Set<number | undefined>()

  async function acquire(): Promise<void> {
    if (active < concurrency) {
      active++
      return
    }
    // `release` hands its slot straight to the next waiter, so `active` stays as it is.
    await new Promise<void>(resolve => waiting.push(resolve))
  }

  function release(): void {
    const next = waiting.shift()
    if (next)
      next()
    else
      active--
  }

  /** Spaces request starts so neither the request rate nor the input-token rate passes its limit. */
  async function pace(body: string): Promise<void> {
    const tokens = Math.ceil(body.length / 4)
    const cost = Math.max(1000 / REQUESTS_PER_SECOND, tokens / INPUT_TOKENS_PER_SECOND * 1000)
    const start = Math.max(now(), nextStart)
    nextStart = start + cost
    const wait = start - now()
    if (wait > 0)
      await sleep(wait)
  }

  function fail(message: string, status?: number): never {
    // One line per kind of failure is enough to see what is wrong; the rest would only repeat it.
    if (!reported.has(status)) {
      reported.add(status)
      log(`Jev: ${message}`)
    }
    throw new JevError(message, status)
  }

  async function attempt(body: string): Promise<Response | 'off'> {
    await acquire()
    try {
      if (refused)
        return 'off'
      await pace(body)
      return await fetch(JEV_ENDPOINT, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body,
        signal: AbortSignal.timeout(timeoutMs),
      })
    }
    catch (error) {
      const timedOut = error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')
      fail(timedOut ? `no answer within ${timeoutMs / 1000}s.` : `the request failed: ${error instanceof Error ? error.message : String(error)}`)
    }
    finally {
      release()
    }
  }

  /** Sends `body`, retrying 429 and 529; `'off'` once the key is refused. */
  async function send(body: string): Promise<Response | 'off'> {
    for (let tries = 1; ; tries++) {
      const res = await attempt(body)
      if (res === 'off')
        return 'off'
      if (res.status === 401) {
        if (!refused)
          log('TypeSafe refused the API key (401), so Jev is off until the server restarts.')
        refused = true
        return 'off'
      }
      if ((res.status !== 429 && res.status !== 529) || tries >= MAX_ATTEMPTS)
        return res
      await res.body?.cancel().catch(() => {})
      await sleep(Math.min(retryAfterMs(res.headers.get('retry-after'), now()) ?? BACKOFF_MS * 2 ** (tries - 1), MAX_RETRY_AFTER_MS))
    }
  }

  return {
    enabled: () => !!apiKey && !refused,
    async ask(state, questions) {
      if (!apiKey || refused)
        return 'off'
      const res = await send(JSON.stringify({ state, model: JEV_MODEL, questions }))
      if (res === 'off')
        return 'off'
      const payload: unknown = await res.json().catch(() => undefined)
      if (!res.ok) {
        const detail = errorDetail(payload)
        fail(`request refused (${res.status})${detail ? `: ${detail}` : '.'}`, res.status)
      }
      if (!isAnswered(payload, questions))
        fail(answerProblem(payload, questions)!, res.status)
      return payload
    },
  }
}

let shared: JevClient | undefined

/** The process's one client, so every caller shares its concurrency and rate limits. */
export function sharedJev(): JevClient {
  return shared ??= createJevClient({ apiKey: jevKeyFromEnv(process.env) })
}
