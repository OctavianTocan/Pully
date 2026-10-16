/**
 * Usage analytics kept on our own server. Every client (the phone, the web app the server
 * serves) and the server itself write the same event shape; the server appends them to one
 * NDJSON file per UTC day and reads them back for the Stats page. Nothing goes to a third
 * party, and no analytics package is used.
 *
 * What may be recorded is the root `CONTRACTS` file's `analytics-*` rules.
 */

/** Where an event came from. */
export const ANALYTICS_APPS = ['android', 'web', 'server'] as const
export type AnalyticsApp = typeof ANALYTICS_APPS[number]

export type AnalyticsValue = string | number | boolean

/** One thing that happened, as clients send it and as the day files keep it. */
export interface AnalyticsEvent {
  /** `area.action`, such as `pr.open` or `review.end`. */
  name: string
  /** When it happened, by the sender's clock (ISO 8601). */
  at: string
  app: AnalyticsApp
  /** The app's version, such as `0.37.0`. */
  version?: string
  /** A random id that changes when the app comes back after 30 minutes away. */
  session?: string
  /** A random id made on first launch and kept on the device. */
  device?: string
  props: Record<string, AnalyticsValue>
}

/** An event as stored: the server's receive time is added, so a wrong phone clock still sorts. */
export interface StoredAnalyticsEvent extends AnalyticsEvent {
  /** When the server received it (ISO 8601); its UTC day names the file. */
  rx: string
}

export const ANALYTICS_LIMITS = {
  /** Events in one `POST /api/events`. */
  batch: 200,
  /** Serialized events one batch carries, below the 64 KiB browsers allow a beacon or keepalive request. */
  batchBytes: 48 * 1024,
  /** One event, serialized. */
  eventBytes: 8 * 1024,
  /** Characters kept of a string value. */
  stringChars: 200,
  /** Characters kept of a `stack` value. */
  stackChars: 2000,
  /** Props kept on one event. */
  props: 40,
} as const

/** How long a session lasts without the app in front, in milliseconds. */
export const ANALYTICS_SESSION_GAP_MS = 30 * 60 * 1000

const EVENT_NAME = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/
const ID = /^[\w-]{1,64}$/
const PROP_KEY = /^[a-z]\w{0,39}$/i
/** Keys whose values are content, not usage. */
const BANNED_KEY = /token|secret|password|passwd|api_?key|authorization|cookie|credential|prompt|body|content|diff|patch|query|path|filename|text|answer|comment/i
/** Values shaped like a GitHub token, a provider key, a Slack token or a JWT. */
const KEY_SHAPED = /\b(?:gh[pousr]_\w{20,}|github_pat_\w{20,}|sk-[\w-]{16,}|xox[abprs]-[\w-]{10,}|eyJ[\w-]{10,}\.[\w-]{10,}(?:\.[\w-]+)?)/g
/** An absolute path on a computer: `/home/…`, `~/…`, `C:\…`. URLs keep theirs. */
const LOCAL_PATH = /(^|[\s'"(=])(?:~\/|\/(?:home|Users|root|tmp|var|etc|mnt|opt|private|data)\/|[A-Z]:\\)\S*/g

/** A `file://` URL, which names a place on a computer. */
const FILE_URL = /file:\/\/\S*/g
/** A relative file path such as `src/a.ts`: folders, then a name with an extension. URLs keep theirs. */
const RELATIVE_PATH = /(^|[\s'"(=:`])(?:[\w.-]+\/)+[\w.-]+\.[a-z\d]{1,8}\b/gi
/** Quoted text; when it has a space in it, it is what someone typed or read rather than a name. */
const QUOTED = /(['"`])([^'"`\n]*)\1/g
const EMAIL = /[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g

/** Removes keys, paths, e-mail addresses and quoted text from a string, then cuts it to `limit` characters. */
export function scrubText(value: string, limit: number = ANALYTICS_LIMITS.stringChars): string {
  return value
    .replace(KEY_SHAPED, '[redacted]')
    .replace(FILE_URL, '[path]')
    .replace(LOCAL_PATH, '$1[path]')
    .replace(RELATIVE_PATH, '$1[path]')
    .replace(EMAIL, '[email]')
    .replace(QUOTED, (quoted, mark: string, inside: string) => /\s/.test(inside) ? `${mark}…${mark}` : quoted)
    .slice(0, limit)
}

function cleanProps(raw: unknown): Record<string, AnalyticsValue> {
  const props: Record<string, AnalyticsValue> = {}
  if (!raw || typeof raw !== 'object' || Array.isArray(raw))
    return props
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (Object.keys(props).length >= ANALYTICS_LIMITS.props)
      break
    if (!PROP_KEY.test(key) || BANNED_KEY.test(key))
      continue
    if (typeof value === 'boolean')
      props[key] = value
    else if (typeof value === 'number' && Number.isFinite(value))
      props[key] = value
    else if (typeof value === 'string')
      props[key] = scrubText(value, key === 'stack' ? ANALYTICS_LIMITS.stackChars : ANALYTICS_LIMITS.stringChars)
  }
  return props
}

function isoOr(value: unknown, fallback: string): string {
  if (typeof value !== 'string')
    return fallback
  const time = Date.parse(value)
  return Number.isFinite(time) ? new Date(time).toISOString() : fallback
}

/**
 * An event as the day files may keep it, or `undefined` when it can't be kept: a name
 * that isn't `area.action`, an unknown app, or more than 8 KB once cleaned. Banned keys are
 * dropped and key- or path-shaped text is redacted rather than the event refused.
 * @param raw - What a client sent.
 * @param rx - The server's receive time (ISO 8601).
 */
export function cleanEvent(raw: unknown, rx: string): StoredAnalyticsEvent | undefined {
  if (!raw || typeof raw !== 'object')
    return undefined
  const input = raw as Record<string, unknown>
  const name = typeof input.name === 'string' ? input.name : ''
  const app = input.app as AnalyticsApp
  if (name.length > 64 || !EVENT_NAME.test(name) || !ANALYTICS_APPS.includes(app))
    return undefined
  const event: StoredAnalyticsEvent = {
    name,
    at: isoOr(input.at, rx),
    rx,
    app,
    ...(typeof input.version === 'string' && input.version ? { version: input.version.slice(0, 32) } : {}),
    ...(typeof input.session === 'string' && ID.test(input.session) ? { session: input.session } : {}),
    ...(typeof input.device === 'string' && ID.test(input.device) ? { device: input.device } : {}),
    props: cleanProps(input.props),
  }
  return JSON.stringify(event).length > ANALYTICS_LIMITS.eventBytes ? undefined : event
}

/**
 * The events of one `POST /api/events` body worth keeping, at most `ANALYTICS_LIMITS.batch`.
 * @param body - `{ events: [...] }` as the client sent it.
 * @param rx - The server's receive time (ISO 8601).
 */
export function cleanBatch(body: unknown, rx: string): StoredAnalyticsEvent[] {
  const events = (body as { events?: unknown } | undefined)?.events
  if (!Array.isArray(events))
    return []
  return events.slice(0, ANALYTICS_LIMITS.batch).map(event => cleanEvent(event, rx)).filter(event => event !== undefined)
}

/** What the Stats page shows for a date range, built by the server from the day files. */
export interface AnalyticsSummary {
  /** First and last UTC day included (`YYYY-MM-DD`). */
  from: string
  to: string
  totals: {
    events: number
    sessions: number
    devices: number
    prsOpened: number
    filesReviewed: number
    /** Claude Code, Codex and Pi runs of every kind. */
    aiRuns: number
    aiFailures: number
    costUsd: number
    tokensIn: number
    tokensOut: number
    crashes: number
    errors: number
  }
  /** One row per day in the range, oldest first, empty days included. */
  days: { day: string, sessions: number, prsOpened: number, filesReviewed: number, aiRuns: number, costUsd: number }[]
  /** Screens by time spent, most first. */
  screens: { screen: string, views: number, seconds: number }[]
  /** AI runs by engine and model, most runs first. */
  engines: { engine: string, model: string, runs: number, failures: number, costUsd: number, tokensIn: number, tokensOut: number, cacheRead: number, priced: number, medianMs: number }[]
  /**
   * AI runs by what they were for (analyze, review, ask, chat, tour, brief), most first, with the tokens they spent.
   * `priced` counts the runs whose engine reported a cost, so a cost of nothing reads as not reported, not as free (O9).
   */
  tasks: { task: string, runs: number, failures: number, costUsd: number, tokensIn: number, tokensOut: number, cacheRead: number, priced: number }[]
  /** Server calls and phone requests, slowest median first. */
  calls: { name: string, calls: number, failures: number, medianMs: number, p95Ms: number }[]
  /** Start and load times in milliseconds by app. */
  speed: { metric: string, app: string, count: number, medianMs: number, p95Ms: number }[]
  github: { calls: number, failures: number, lowestRateLeft?: number }
  cache: { hit: number, refetch: number, stale: number }
  /** Errors and crashes grouped by kind and message, most first. */
  errors: { name: string, app: string, kind: string, message: string, count: number, lastAt: string }[]
  /** Every event name and how often it was seen, most first. */
  names: { name: string, count: number }[]
}

/** Where a tracker sends its events. */
export interface AnalyticsTransport {
  /** Sends a batch and resolves `true` once the server took it. */
  send: (events: AnalyticsEvent[]) => Promise<boolean>
  /** Hands a batch to the browser while the page goes away; `true` when it was queued. */
  beacon?: (events: AnalyticsEvent[]) => boolean
}

export interface AnalyticsTrackerOptions {
  app: AnalyticsApp
  version: string
  device: string
  transport: AnalyticsTransport
  /** The clock, for tests. */
  now?: () => number
  /** A new random id, for sessions. */
  randomId?: () => string
  /** Events held while sending fails; the oldest go first past this. */
  maxQueue?: number
}

export interface AnalyticsTracker {
  /** Records an event; it goes up with the next flush. Does nothing while turned off. */
  track: (name: string, props?: Record<string, AnalyticsValue | undefined>) => void
  /** Sends what is queued. */
  flush: () => Promise<void>
  /** Sends what is queued with the transport's beacon, for a page that is going away. */
  flushOnExit: () => void
  /** Notes the app came to the front; a new session starts after 30 minutes away. */
  foreground: () => void
  /** Notes the app went to the back. */
  background: () => void
  setEnabled: (enabled: boolean) => void
  readonly enabled: boolean
  readonly session: string
  /** Events waiting to be sent, for tests. */
  readonly queued: number
}

/** A random id of 16 hex characters. */
export function analyticsId(): string {
  const bytes = new Uint8Array(8)
  globalThis.crypto.getRandomValues(bytes)
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * The web's tracker; the phone has the same rules in Kotlin (`data/Analytics.kt`). Events
 * wait in memory and go up when `flush` is called (every 30 seconds and when the page hides).
 */
export function createAnalyticsTracker(options: AnalyticsTrackerOptions): AnalyticsTracker {
  const now = options.now ?? Date.now
  const randomId = options.randomId ?? analyticsId
  const maxQueue = options.maxQueue ?? 2000
  let queue: AnalyticsEvent[] = []
  let enabled = true
  let session = randomId()
  let backgroundAt: number | undefined
  let sending: Promise<void> | undefined
  /** Goes up each time analytics is turned off, so a batch already on its way is never put back. */
  let generation = 0

  /** The front of the queue, up to `batch` events and `batchBytes`; always at least one event. */
  const take = () => {
    let bytes = 0
    let count = 0
    while (count < queue.length && count < ANALYTICS_LIMITS.batch) {
      bytes += JSON.stringify(queue[count]).length + 1
      if (count && bytes > ANALYTICS_LIMITS.batchBytes)
        break
      count++
    }
    const batch = queue.slice(0, count)
    queue = queue.slice(count)
    return batch
  }

  async function sendAll(): Promise<void> {
    const started = generation
    // Turning analytics off empties the queue, which ends the loop.
    while (queue.length) {
      const batch = take()
      const ok = await options.transport.send(batch).catch(() => false)
      if (!ok) {
        // Back to the front, to go with the next flush, unless analytics was turned off meanwhile.
        if (generation === started)
          queue = [...batch, ...queue].slice(-maxQueue)
        return
      }
    }
  }

  return {
    track(name, props = {}) {
      if (!enabled)
        return
      const clean: Record<string, AnalyticsValue> = {}
      for (const [key, value] of Object.entries(props)) {
        if (value !== undefined)
          clean[key] = value
      }
      queue.push({ name, at: new Date(now()).toISOString(), app: options.app, version: options.version, session, device: options.device, props: clean })
      if (queue.length > maxQueue)
        queue = queue.slice(-maxQueue)
    },
    flush() {
      return sending ??= sendAll().finally(() => {
        sending = undefined
      })
    },
    flushOnExit() {
      if (!options.transport.beacon || !enabled)
        return
      while (queue.length) {
        const batch = take()
        if (!options.transport.beacon(batch)) {
          queue = [...batch, ...queue]
          return
        }
      }
    },
    foreground() {
      if (backgroundAt !== undefined && now() - backgroundAt >= ANALYTICS_SESSION_GAP_MS)
        session = randomId()
      backgroundAt = undefined
    },
    background() {
      backgroundAt = now()
    },
    setEnabled(next) {
      enabled = next
      if (!next) {
        queue = []
        generation++
      }
    },
    get enabled() {
      return enabled
    },
    get session() {
      return session
    },
    get queued() {
      return queue.length
    },
  }
}
