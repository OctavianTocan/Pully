import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { analyticsRange, countGithubCall, flushAnalytics, flushGithubCalls, ingestAnalytics, pruneAnalytics, summarizeAnalytics, track } from './analytics'
import { EVENTS_BODY_LIMIT, handleNativeRequest } from './native-api'

let dir: string
let env: Record<string, string>

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'pulls-review-analytics-'))
  env = { PULLS_REVIEW_ANALYTICS_DIR: dir }
})

afterEach(() => {
  rmSync(dir, { recursive: true, force: true })
})

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function lines(day = today()): any[] {
  return readFileSync(join(dir, `${day}.ndjson`), 'utf8').trim().split('\n').map(line => JSON.parse(line))
}

describe('analytics day files', () => {
  it('appends clients\' and the server\'s own events to today\'s file', async () => {
    expect(ingestAnalytics({ events: [{ name: 'pr.open', app: 'android', session: 's1', device: 'd1', props: { number: 7, path: 'src/a.ts' } }, { name: 'nope', app: 'web' }] }, env)).toBe(1)
    track('ai.run', { engine: 'claude-code', status: 'done', costUsd: 0.12 }, env)
    await flushAnalytics(env)
    const stored = lines()
    expect(stored.map(event => event.name)).toEqual(['pr.open', 'ai.run'])
    expect(stored[0].props).toEqual({ number: 7 })
    expect(stored[1].app).toBe('server')
  })

  it('writes nothing when turned off', async () => {
    const off = { ...env, PULLS_REVIEW_ANALYTICS: 'off' }
    expect(ingestAnalytics({ events: [{ name: 'pr.open', app: 'web' }] }, off)).toBe(0)
    track('ai.run', {}, off)
    await flushAnalytics(off)
    expect(() => lines()).toThrow()
  })

  it('counts GitHub calls per route a minute', async () => {
    countGithubCall({ route: 'pulls/:n', method: 'GET', status: 200, ms: 100, rateLeft: 10 })
    countGithubCall({ route: 'pulls/:n', method: 'GET', status: 502, ms: 300, rateLeft: 9 })
    flushGithubCalls(env)
    await flushAnalytics(env)
    expect(lines().map(event => event.props)).toEqual([{ route: 'pulls/:n', calls: 2, failures: 1, ms: 200, msMax: 300, rateLeft: 9 }])
  })

  it('deletes day files older than a year', async () => {
    writeFileSync(join(dir, '2020-01-01.ndjson'), '{}\n')
    writeFileSync(join(dir, `${today()}.ndjson`), '{}\n')
    expect(await pruneAnalytics(env)).toEqual(['2020-01-01'])
  })

  it('summarizes a range', async () => {
    const day = '2026-10-15'
    const event = (name: string, props: object, extra: object = {}) => JSON.stringify({ name, at: `${day}T10:00:00.000Z`, rx: `${day}T10:00:00.000Z`, app: 'android', session: 's1', device: 'd1', props, ...extra })
    writeFileSync(join(dir, `${day}.ndjson`), [
      event('screen.view', { screen: 'home' }),
      event('screen.time', { screen: 'home', seconds: 42 }),
      event('pr.open', { number: 1 }),
      event('pr.open', { kind: 'branch' }),
      event('file.reviewed', { files: 3 }),
      event('ai.run', { task: 'review', engine: 'codex', model: 'gpt', status: 'done', costUsd: 0.5, tokensIn: 100, tokensOut: 10, ms: 2000 }, { app: 'server', session: undefined }),
      event('ai.run', { task: 'review', engine: 'codex', model: 'gpt', status: 'failed', ms: 1000 }, { app: 'server' }),
      event('github.calls', { route: 'pulls', calls: 2, failures: 1, ms: 100, msMax: 120, rateLeft: 3999 }, { app: 'server' }),
      event('cache.diff', { outcome: 'hit' }, { app: 'server' }),
      event('crash', { kind: 'IllegalStateException', message: 'boom' }),
      'not json',
      '',
    ].join('\n'))
    const summary = await summarizeAnalytics({ from: '2026-10-14', to: day }, env)
    expect(summary.totals).toMatchObject({ sessions: 1, devices: 1, prsOpened: 1, filesReviewed: 3, aiRuns: 2, aiFailures: 1, costUsd: 0.5, crashes: 1 })
    expect(summary.days).toEqual([
      { day: '2026-10-14', sessions: 0, prsOpened: 0, filesReviewed: 0, aiRuns: 0, costUsd: 0 },
      { day, sessions: 1, prsOpened: 1, filesReviewed: 3, aiRuns: 2, costUsd: 0.5 },
    ])
    expect(summary.screens).toEqual([{ screen: 'home', views: 1, seconds: 42 }])
    expect(summary.engines).toEqual([{ engine: 'codex', model: 'gpt', runs: 2, failures: 1, costUsd: 0.5, tokensIn: 100, tokensOut: 10, medianMs: 2000 }])
    expect(summary.tasks).toEqual([{ task: 'review', runs: 2, failures: 1, costUsd: 0.5 }])
    expect(summary.github).toEqual({ calls: 2, failures: 1, lowestRateLeft: 3999 })
    expect(summary.cache).toEqual({ hit: 1, refetch: 0, stale: 0 })
    expect(summary.errors[0]).toMatchObject({ name: 'crash', kind: 'IllegalStateException', message: 'boom', count: 1 })
  })

  it('reads a range from days or dates', () => {
    const now = Date.parse('2026-10-15T12:00:00Z')
    expect(analyticsRange(undefined, now)).toEqual({ from: '2026-09-16', to: '2026-10-15' })
    expect(analyticsRange({ days: 7 }, now)).toEqual({ from: '2026-10-09', to: '2026-10-15' })
    expect(analyticsRange({ from: '2026-10-20', to: '2026-10-01' }, now)).toEqual({ from: '2026-10-01', to: '2026-10-20' })
    expect(analyticsRange({ from: '1990-01-01', to: '2026-10-15' }, now)).toEqual({ from: '2025-10-15', to: '2026-10-15' })
  })

  it('keeps events it could not write for the next flush', async () => {
    const blocked = { PULLS_REVIEW_ANALYTICS_DIR: join(dir, 'blocked') }
    writeFileSync(join(dir, 'blocked'), '')
    ingestAnalytics({ events: [{ name: 'pr.open', app: 'web' }] }, blocked)
    await flushAnalytics(blocked)
    rmSync(join(dir, 'blocked'))
    await flushAnalytics(blocked)
    expect(readFileSync(join(dir, 'blocked', `${today()}.ndjson`), 'utf8')).toContain('"pr.open"')
  })
})

describe('pOST /api/events', () => {
  it('answers 204', async () => {
    const response = await handleNativeRequest({ url: new URL('http://localhost/api/events'), method: 'POST', json: async () => ({ events: [] }) }, dir)
    expect(response?.status).toBe(204)
  })

  it('stops reading a body past the limit', async () => {
    const big = new TextEncoder().encode(`{"events":[],"pad":"${'x'.repeat(EVENTS_BODY_LIMIT)}"}`)
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (let at = 0; at < big.length; at += 64 * 1024)
          controller.enqueue(big.subarray(at, at + 64 * 1024))
        controller.close()
      },
    })
    const response = await handleNativeRequest({ url: new URL('http://localhost/api/events'), method: 'POST', json: async () => ({}), body }, dir)
    expect(response?.status).toBe(413)
  })
})
