import type { AnalyticsSummary, AnalyticsValue, StoredAnalyticsEvent } from '@pulls.review/core/analytics'
import type { Env } from '@pulls.review/core/env'
import type { GithubCallInfo } from '@pulls.review/core/github'
import { createReadStream } from 'node:fs'
import { appendFile, mkdir, readdir, unlink } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { createInterface } from 'node:readline'
import { cleanBatch, cleanEvent } from '@pulls.review/core/analytics'
import { observeGithubCalls } from '@pulls.review/core/github'
import pkg from '../package.json' with { type: 'json' }

/** Day files older than this many days are deleted. */
export const ANALYTICS_KEEP_DAYS = 365
const DAY_MS = 24 * 60 * 60 * 1000
const DAY_FILE = /^(\d{4}-\d{2}-\d{2})\.ndjson$/

/**
 * The folder the day files live in: `PULLS_REVIEW_ANALYTICS_DIR`, or beside the Android
 * builds the server already serves. Not the repo-scoped store, which would split the numbers
 * across checkouts.
 */
export function analyticsDir(env: Env = process.env): string {
  return env.PULLS_REVIEW_ANALYTICS_DIR || join(homedir(), '.local/share/pulls-review/analytics')
}

/** `PULLS_REVIEW_ANALYTICS=off` stops the server writing anything. */
export function analyticsEnabled(env: Env = process.env): boolean {
  return !/^(?:off|0|false|no)$/i.test(env.PULLS_REVIEW_ANALYTICS ?? '')
}

function dayOf(iso: string): string {
  return iso.slice(0, 10)
}

let pending: StoredAnalyticsEvent[] = []
let writing: Promise<void> = Promise.resolve()
let timer: ReturnType<typeof setTimeout> | undefined

/** Appends what is pending to the day files, one append per day. */
export function flushAnalytics(env: Env = process.env): Promise<void> {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  const batch = pending
  pending = []
  if (!batch.length)
    return writing
  writing = writing.then(async () => {
    const dir = analyticsDir(env)
    const byDay = new Map<string, string[]>()
    for (const event of batch) {
      const day = dayOf(event.rx)
      byDay.set(day, [...byDay.get(day) ?? [], JSON.stringify(event)])
    }
    try {
      await mkdir(dir, { recursive: true })
      for (const [day, lines] of byDay)
        await appendFile(join(dir, `${day}.ndjson`), `${lines.join('\n')}\n`)
    }
    catch (error) {
      // Analytics never breaks the work it measures.
      console.error(`pulls.review: analytics could not be written (${error instanceof Error ? error.message : String(error)})`)
    }
  })
  return writing
}

function enqueue(events: StoredAnalyticsEvent[], env: Env): void {
  if (!events.length || !analyticsEnabled(env))
    return
  pending.push(...events)
  if (pending.length >= 200) {
    void flushAnalytics(env)
    return
  }
  timer ??= setTimeout(() => void flushAnalytics(env), 2000)
  timer.unref?.()
}

/**
 * Records one of the server's own events. Never throws and never waits: the event is
 * written within two seconds.
 * @param name - `area.action`, such as `ai.run`.
 * @param props - Counts, timings and enums; content is dropped by `cleanEvent`.
 */
export function track(name: string, props: Record<string, AnalyticsValue | undefined> = {}, env: Env = process.env): void {
  if (!analyticsEnabled(env))
    return
  const rx = new Date().toISOString()
  const clean: Record<string, AnalyticsValue> = {}
  for (const [key, value] of Object.entries(props)) {
    if (value !== undefined)
      clean[key] = value
  }
  const event = cleanEvent({ name, at: rx, app: 'server', version: pkg.version, props: clean }, rx)
  if (event)
    enqueue([event], env)
}

/**
 * Takes a client's `POST /api/events` body. Returns how many events were kept.
 * @param body - `{ events: [...] }`.
 */
export function ingestAnalytics(body: unknown, env: Env = process.env): number {
  if (!analyticsEnabled(env))
    return 0
  const events = cleanBatch(body, new Date().toISOString())
  enqueue(events, env)
  return events.length
}

/**
 * Deletes day files older than `ANALYTICS_KEEP_DAYS`. Returns the days removed.
 * @param env - Where the day files are.
 * @param now - The clock, for tests.
 */
export async function pruneAnalytics(env: Env = process.env, now: number = Date.now()): Promise<string[]> {
  const dir = analyticsDir(env)
  const cutoff = dayOf(new Date(now - ANALYTICS_KEEP_DAYS * DAY_MS).toISOString())
  const names = await readdir(dir).catch(() => [] as string[])
  const removed: string[] = []
  for (const name of names) {
    const day = DAY_FILE.exec(name)?.[1]
    if (day && day < cutoff) {
      await unlink(join(dir, name)).catch(() => {})
      removed.push(day)
    }
  }
  return removed
}

interface GithubMinute { calls: number, failures: number, msTotal: number, msMax: number, rateLeft?: number }
let githubCalls = new Map<string, GithubMinute>()

/**
 * Counts one GitHub request. The PR store alone makes hundreds an hour, so they are kept as
 * one `github.calls` event per route a minute rather than an event each.
 */
export function countGithubCall(call: GithubCallInfo): void {
  const row = githubCalls.get(call.route) ?? { calls: 0, failures: 0, msTotal: 0, msMax: 0 }
  row.calls++
  if (call.status === 0 || call.status >= 400)
    row.failures++
  row.msTotal += call.ms
  row.msMax = Math.max(row.msMax, call.ms)
  if (call.rateLeft !== undefined)
    row.rateLeft = Math.min(row.rateLeft ?? Infinity, call.rateLeft)
  githubCalls.set(call.route, row)
}

/** Writes the minute's `github.calls` events. */
export function flushGithubCalls(env: Env = process.env): void {
  const rows = githubCalls
  githubCalls = new Map()
  for (const [route, row] of rows)
    track('github.calls', { route, calls: row.calls, failures: row.failures, ms: Math.round(row.msTotal / row.calls), msMax: row.msMax, rateLeft: row.rateLeft }, env)
}

/** Prunes now and once a day for as long as the server runs, and counts every GitHub call. */
export function startAnalytics(env: Env = process.env): void {
  if (analyticsEnabled(env))
    observeGithubCalls(countGithubCall)
  setInterval(flushGithubCalls, 60_000, env).unref()
  void pruneAnalytics(env)
  setInterval(() => void pruneAnalytics(env), DAY_MS).unref()
  process.once('beforeExit', () => {
    flushGithubCalls(env)
    void flushAnalytics(env)
  })
}

/** The days from `from` to `to`, inclusive (`YYYY-MM-DD`). */
function daysBetween(from: string, to: string): string[] {
  const days: string[] = []
  for (let t = Date.parse(`${from}T00:00:00Z`); t <= Date.parse(`${to}T00:00:00Z`) && days.length < 400; t += DAY_MS)
    days.push(new Date(t).toISOString().slice(0, 10))
  return days
}

/** Reads the stored events of the given days, skipping lines that don't parse. */
export async function* readAnalytics(days: string[], env: Env = process.env): AsyncGenerator<StoredAnalyticsEvent> {
  const dir = analyticsDir(env)
  for (const day of days) {
    const stream = createReadStream(join(dir, `${day}.ndjson`), 'utf8')
    const opened = await new Promise<boolean>((resolve) => {
      stream.once('open', () => resolve(true))
      stream.once('error', () => resolve(false))
    })
    if (!opened)
      continue
    for await (const line of createInterface({ input: stream, crlfDelay: Infinity })) {
      if (!line)
        continue
      try {
        yield JSON.parse(line) as StoredAnalyticsEvent
      }
      catch {}
    }
  }
}

function quantile(values: number[], q: number): number {
  if (!values.length)
    return 0
  const sorted = [...values].sort((a, b) => a - b)
  return Math.round(sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))]!)
}

function num(value: AnalyticsValue | undefined): number {
  return typeof value === 'number' ? value : 0
}

function str(value: AnalyticsValue | undefined, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

/** Whether an event's `status` says it went wrong. */
function failed(props: Record<string, AnalyticsValue>): boolean {
  const status = props.status
  return status === 'error' || status === 'failed' || status === 'timeout' || (typeof status === 'number' && status >= 400) || props.ok === false
}

/** A date range of at most 366 days ending today, in UTC days. */
export function analyticsRange(input: { from?: unknown, to?: unknown, days?: unknown } | undefined, now: number = Date.now()): { from: string, to: string } {
  const valid = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : undefined
  const to = valid(input?.to) ?? dayOf(new Date(now).toISOString())
  const span = typeof input?.days === 'number' && input.days >= 1 ? Math.min(366, Math.floor(input.days)) : 30
  const from = valid(input?.from) ?? dayOf(new Date(Date.parse(`${to}T00:00:00Z`) - (span - 1) * DAY_MS).toISOString())
  return from <= to ? { from, to } : { from: to, to: from }
}

interface Bucket { ms: number[], calls: number, failures: number }

/** What each event adds to the counts; any other event is only counted by name. */
const COUNTERS: Record<string, (tally: Tally, event: StoredAnalyticsEvent) => void> = {
  'screen.view': (tally, { props }) => {
    tally.screen(str(props.screen, 'unknown')).views++
  },
  'screen.time': (tally, { props }) => {
    tally.screen(str(props.screen, 'unknown')).seconds += num(props.seconds)
  },
  'pr.open': (tally, event) => {
    tally.totals.prsOpened++
    const day = tally.perDay.get(dayOf(event.rx))
    if (day)
      day.prsOpened++
  },
  'file.reviewed': (tally, event) => {
    tally.totals.filesReviewed++
    const day = tally.perDay.get(dayOf(event.rx))
    if (day)
      day.filesReviewed++
  },
  'pr.load': (tally, event) => tally.sped('pr.load', event.app, num(event.props.ms)),
  'app.open': (tally, event) => tally.sped(`app.open ${str(event.props.start, 'cold')}`, event.app, num(event.props.ms)),
  'ai.run': (tally, event) => tally.aiRun(event),
  'rpc.call': (tally, { props }) => tally.timed(`rpc ${str(props.method, 'unknown')}`, num(props.ms), failed(props)),
  'jev.call': (tally, { props }) => {
    tally.timed('jev', num(props.ms), failed(props))
    tally.totals.tokensIn += num(props.tokensIn)
    tally.totals.tokensOut += num(props.tokensOut)
  },
  'github.calls': (tally, { props }) => tally.githubCalls(props),
  'cache.diff': (tally, { props }) => {
    const outcome = str(props.outcome)
    if (outcome === 'hit' || outcome === 'refetch' || outcome === 'stale')
      tally.cache[outcome]++
  },
  'store.refresh': (tally, event) => tally.timed(event.name, num(event.props.ms), failed(event.props)),
  'preanalyze.run': (tally, event) => tally.timed(event.name, num(event.props.ms), failed(event.props)),
  'crash': (tally, event) => tally.error(event),
  'error.client': (tally, event) => tally.error(event),
  'error.server': (tally, event) => tally.error(event),
}

/** The running counts behind one `AnalyticsSummary`, fed one stored event at a time. */
class Tally {
  readonly totals = { events: 0, sessions: 0, devices: 0, prsOpened: 0, filesReviewed: 0, aiRuns: 0, aiFailures: 0, costUsd: 0, tokensIn: 0, tokensOut: 0, crashes: 0, errors: 0 }
  readonly perDay: Map<string, { day: string, sessions: Set<string>, prsOpened: number, filesReviewed: number, aiRuns: number, costUsd: number }>
  readonly sessions = new Set<string>()
  readonly devices = new Set<string>()
  readonly names = new Map<string, number>()
  readonly screens = new Map<string, { views: number, seconds: number }>()
  readonly engines = new Map<string, { engine: string, model: string, runs: number, failures: number, costUsd: number, tokensIn: number, tokensOut: number, ms: number[] }>()
  readonly tasks = new Map<string, { runs: number, failures: number, costUsd: number }>()
  readonly calls = new Map<string, Bucket>()
  readonly speed = new Map<string, { metric: string, app: string, ms: number[] }>()
  readonly errors = new Map<string, { name: string, app: string, kind: string, message: string, count: number, lastAt: string }>()
  readonly github = { calls: 0, failures: 0, lowestRateLeft: undefined as number | undefined }
  readonly cache = { hit: 0, refetch: 0, stale: 0 }

  constructor(days: string[]) {
    this.perDay = new Map(days.map(day => [day, { day, sessions: new Set<string>(), prsOpened: 0, filesReviewed: 0, aiRuns: 0, costUsd: 0 }]))
  }

  add(event: StoredAnalyticsEvent): void {
    this.totals.events++
    this.names.set(event.name, (this.names.get(event.name) ?? 0) + 1)
    if (event.session) {
      this.sessions.add(event.session)
      this.perDay.get(dayOf(event.rx))?.sessions.add(event.session)
    }
    if (event.device)
      this.devices.add(event.device)
    COUNTERS[event.name]?.(this, event)
  }

  screen(name: string): { views: number, seconds: number } {
    const row = this.screens.get(name) ?? { views: 0, seconds: 0 }
    this.screens.set(name, row)
    return row
  }

  bucket(name: string): Bucket {
    const row = this.calls.get(name) ?? { calls: 0, failures: 0, ms: [] }
    this.calls.set(name, row)
    return row
  }

  timed(name: string, ms: number, bad: boolean): void {
    const row = this.bucket(name)
    row.calls++
    if (bad)
      row.failures++
    if (ms > 0)
      row.ms.push(ms)
  }

  sped(metric: string, app: string, ms: number): void {
    if (ms <= 0)
      return
    const key = `${metric}\u0000${app}`
    const row = this.speed.get(key) ?? { metric, app, ms: [] }
    row.ms.push(ms)
    this.speed.set(key, row)
  }

  aiRun(event: StoredAnalyticsEvent): void {
    const { props } = event
    const engine = str(props.engine, 'unknown')
    const model = str(props.model, 'default')
    const key = `${engine}\u0000${model}`
    const row = this.engines.get(key) ?? { engine, model, runs: 0, failures: 0, costUsd: 0, tokensIn: 0, tokensOut: 0, ms: [] }
    const bad = failed(props)
    const cost = num(props.costUsd)
    row.runs++
    row.failures += bad ? 1 : 0
    row.costUsd += cost
    row.tokensIn += num(props.tokensIn)
    row.tokensOut += num(props.tokensOut)
    if (num(props.ms) > 0)
      row.ms.push(num(props.ms))
    this.engines.set(key, row)
    const task = str(props.task, 'other')
    const kind = this.tasks.get(task) ?? { runs: 0, failures: 0, costUsd: 0 }
    kind.runs++
    kind.failures += bad ? 1 : 0
    kind.costUsd += cost
    this.tasks.set(task, kind)
    this.totals.aiRuns++
    this.totals.aiFailures += bad ? 1 : 0
    this.totals.costUsd += cost
    this.totals.tokensIn += num(props.tokensIn)
    this.totals.tokensOut += num(props.tokensOut)
    const day = this.perDay.get(dayOf(event.rx))
    if (day) {
      day.aiRuns++
      day.costUsd += cost
    }
  }

  githubCalls(props: Record<string, AnalyticsValue>): void {
    const count = Math.max(1, num(props.calls))
    const failures = num(props.failures)
    this.github.calls += count
    this.github.failures += failures
    if (typeof props.rateLeft === 'number')
      this.github.lowestRateLeft = Math.min(this.github.lowestRateLeft ?? Infinity, props.rateLeft)
    const row = this.bucket(`github ${str(props.route, 'other')}`)
    row.calls += count
    row.failures += failures
    // A minute's average stands for each of its calls; its slowest call keeps the tail.
    for (let i = 0; i < count - 1; i++)
      row.ms.push(num(props.ms))
    row.ms.push(Math.max(num(props.ms), num(props.msMax)))
  }

  error(event: StoredAnalyticsEvent): void {
    if (event.name === 'crash')
      this.totals.crashes++
    else
      this.totals.errors++
    const kind = str(event.props.kind, 'error')
    const message = str(event.props.message)
    const key = `${event.name}\u0000${event.app}\u0000${kind}\u0000${message}`
    const row = this.errors.get(key) ?? { name: event.name, app: event.app, kind, message, count: 0, lastAt: event.rx }
    row.count++
    if (event.rx > row.lastAt)
      row.lastAt = event.rx
    this.errors.set(key, row)
  }

  summary(range: { from: string, to: string }): AnalyticsSummary {
    const cents = (usd: number) => Math.round(usd * 10000) / 10000
    return {
      from: range.from,
      to: range.to,
      totals: { ...this.totals, sessions: this.sessions.size, devices: this.devices.size, costUsd: cents(this.totals.costUsd) },
      days: [...this.perDay.values()].map(row => ({ day: row.day, sessions: row.sessions.size, prsOpened: row.prsOpened, filesReviewed: row.filesReviewed, aiRuns: row.aiRuns, costUsd: cents(row.costUsd) })),
      screens: [...this.screens].map(([screen, row]) => ({ screen, views: row.views, seconds: Math.round(row.seconds) })).sort((a, b) => b.seconds - a.seconds || b.views - a.views),
      engines: [...this.engines.values()].map(row => ({ engine: row.engine, model: row.model, runs: row.runs, failures: row.failures, costUsd: cents(row.costUsd), tokensIn: row.tokensIn, tokensOut: row.tokensOut, medianMs: quantile(row.ms, 0.5) })).sort((a, b) => b.runs - a.runs),
      tasks: [...this.tasks].map(([task, row]) => ({ task, runs: row.runs, failures: row.failures, costUsd: cents(row.costUsd) })).sort((a, b) => b.runs - a.runs),
      calls: [...this.calls].map(([name, row]) => ({ name, calls: row.calls, failures: row.failures, medianMs: quantile(row.ms, 0.5), p95Ms: quantile(row.ms, 0.95) })).sort((a, b) => b.medianMs - a.medianMs),
      speed: [...this.speed.values()].map(row => ({ metric: row.metric, app: row.app, count: row.ms.length, medianMs: quantile(row.ms, 0.5), p95Ms: quantile(row.ms, 0.95) })),
      github: { ...this.github },
      cache: { ...this.cache },
      errors: [...this.errors.values()].sort((a, b) => b.count - a.count || b.lastAt.localeCompare(a.lastAt)).slice(0, 50),
      names: [...this.names].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count),
    }
  }
}

/**
 * The Stats page's numbers for a range of days, counted from the day files in one pass.
 * @param range - First and last UTC day, inclusive.
 * @param range.from - The first day (`YYYY-MM-DD`).
 * @param range.to - The last day.
 * @param env - Where the day files are.
 */
export async function summarizeAnalytics(range: { from: string, to: string }, env: Env = process.env): Promise<AnalyticsSummary> {
  await flushAnalytics(env)
  const days = daysBetween(range.from, range.to)
  const tally = new Tally(days)
  for await (const event of readAnalytics(days, env))
    tally.add(event)
  return tally.summary(range)
}
