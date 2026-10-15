import type { AnalyticsEvent } from './analytics'
import { describe, expect, it } from 'vitest'
import { ANALYTICS_LIMITS, ANALYTICS_SESSION_GAP_MS, cleanBatch, cleanEvent, createAnalyticsTracker, scrubText } from './analytics'

const RX = '2026-10-15T21:00:00.000Z'

describe('cleanEvent', () => {
  it('keeps a well-formed event and stamps the receive time', () => {
    expect(cleanEvent({ name: 'pr.open', at: '2026-10-15T20:59:59Z', app: 'android', version: '0.37.0', session: 's1', device: 'd1', props: { repo: 'acme/app', number: 7, lite: true } }, RX)).toEqual({
      name: 'pr.open',
      at: '2026-10-15T20:59:59.000Z',
      rx: RX,
      app: 'android',
      version: '0.37.0',
      session: 's1',
      device: 'd1',
      props: { repo: 'acme/app', number: 7, lite: true },
    })
  })

  it('refuses names that are not area.action and unknown apps', () => {
    expect(cleanEvent({ name: 'open', app: 'web', props: {} }, RX)).toBeUndefined()
    expect(cleanEvent({ name: 'PR.Open', app: 'web', props: {} }, RX)).toBeUndefined()
    expect(cleanEvent({ name: 'pr.open', app: 'ios', props: {} }, RX)).toBeUndefined()
  })

  it('drops content keys, arrays and non-finite numbers', () => {
    const event = cleanEvent({ name: 'search.run', app: 'web', props: { query: 'secret plan', path: 'src/a.ts', prompt: 'x', githubToken: 'y', diff: 'z', results: 3, list: [1], bad: Number.NaN } }, RX)
    expect(event?.props).toEqual({ results: 3 })
  })

  it('redacts keys and local paths inside text', () => {
    expect(scrubText('bad token ghp_abcdefghijklmnopqrstuvwxyz0123 here')).toBe('bad token [redacted] here')
    expect(scrubText('ENOENT: open \'/home/tavi/repo/a.ts\'')).toBe('ENOENT: open \'[path]')
    expect(scrubText('at https://prs.example.com/assets/index.js:1:2')).toBe('at https://prs.example.com/assets/index.js:1:2')
    expect(scrubText('at file:///srv/app/x.js:3:4')).toBe('at [path]')
  })

  it('redacts relative paths, e-mail addresses and quoted text, but keeps names', () => {
    expect(scrubText('could not load src/private/plan.ts')).toBe('could not load [path]')
    expect(scrubText('no access for tavi@example.com')).toBe('no access for [email]')
    expect(scrubText('"ship the secret plan" is not valid JSON')).toBe('"…" is not valid JSON')
    expect(scrubText('Cannot read properties of undefined (reading \'files\')')).toBe('Cannot read properties of undefined (reading \'files\')')
    expect(scrubText('at com.octaviantocan.pulls.Repo.load(Repo.kt:12)')).toBe('at com.octaviantocan.pulls.Repo.load(Repo.kt:12)')
  })

  it('cuts strings, keeps longer stacks, and refuses an event over 8 KB', () => {
    const event = cleanEvent({ name: 'error.client', app: 'web', props: { message: 'm'.repeat(500), stack: 's'.repeat(5000) } }, RX)
    expect((event?.props.message as string).length).toBe(ANALYTICS_LIMITS.stringChars)
    expect((event?.props.stack as string).length).toBe(ANALYTICS_LIMITS.stackChars)
    const props = Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`k${i}`, 'v'.repeat(200)]))
    expect(cleanEvent({ name: 'ui.tap', app: 'web', props }, RX)).toBeUndefined()
  })

  it('keeps at most one batch of events', () => {
    const events = Array.from({ length: 250 }, () => ({ name: 'ui.tap', app: 'web', props: {} }))
    expect(cleanBatch({ events }, RX)).toHaveLength(ANALYTICS_LIMITS.batch)
    expect(cleanBatch('nope', RX)).toEqual([])
  })
})

describe('createAnalyticsTracker', () => {
  function setup(ok = true) {
    let time = Date.parse(RX)
    let n = 0
    const sent: AnalyticsEvent[][] = []
    const tracker = createAnalyticsTracker({
      app: 'web',
      version: '1.0.0',
      device: 'dev',
      now: () => time,
      randomId: () => `s${++n}`,
      transport: { send: async (events) => {
        sent.push(events)
        return ok
      } },
    })
    return { tracker, sent, advance: (ms: number) => (time += ms) }
  }

  it('sends what is queued and leaves out undefined props', async () => {
    const { tracker, sent } = setup()
    tracker.track('pr.open', { number: 7, from: undefined })
    await tracker.flush()
    expect(sent).toEqual([[{ name: 'pr.open', at: RX, app: 'web', version: '1.0.0', session: 's1', device: 'dev', props: { number: 7 } }]])
    expect(tracker.queued).toBe(0)
  })

  it('keeps events when sending fails', async () => {
    const { tracker } = setup(false)
    tracker.track('ui.tap', { id: 'refresh' })
    await tracker.flush()
    expect(tracker.queued).toBe(1)
  })

  it('starts a new session only after 30 minutes away', () => {
    const { tracker, advance } = setup()
    tracker.background()
    advance(ANALYTICS_SESSION_GAP_MS - 1)
    tracker.foreground()
    expect(tracker.session).toBe('s1')
    tracker.background()
    advance(ANALYTICS_SESSION_GAP_MS)
    tracker.foreground()
    expect(tracker.session).toBe('s2')
  })

  it('does not put a failed batch back after analytics was turned off', async () => {
    let fail!: () => void
    const tracker = createAnalyticsTracker({
      app: 'web',
      version: '1.0.0',
      device: 'dev',
      transport: { send: () => new Promise<boolean>((resolve) => {
        fail = () => resolve(false)
      }) },
    })
    tracker.track('ui.tap')
    const flushing = tracker.flush()
    tracker.setEnabled(false)
    tracker.setEnabled(true)
    fail()
    await flushing
    expect(tracker.queued).toBe(0)
  })

  it('splits batches to stay under the beacon size', async () => {
    const { tracker, sent } = setup()
    for (let i = 0; i < 40; i++)
      tracker.track('error.client', { stack: 's'.repeat(2000) })
    await tracker.flush()
    expect(sent.length).toBeGreaterThan(1)
    for (const batch of sent)
      expect(JSON.stringify(batch).length).toBeLessThan(ANALYTICS_LIMITS.batchBytes + 2100)
    expect(sent.flat()).toHaveLength(40)
  })

  it('drops the queue and records nothing while turned off', () => {
    const { tracker } = setup()
    tracker.track('ui.tap')
    tracker.setEnabled(false)
    tracker.track('ui.tap')
    expect(tracker.queued).toBe(0)
  })
})
