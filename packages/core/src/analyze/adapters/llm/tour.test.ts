import type { DiffsPayload } from '../../../types/diff'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setCliRunner } from './cli-stream'
import { MENTION_RULE } from './prompt'
import { buildTourPlanPrompt, buildTourStopPrompt, MAX_STOPS, resolveTourPlan, resolveTourStop, runTour } from './tour'

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '7' },
  title: 'Cache the parser',
  description: 'Parses once per file.',
  head: { sha: 'h1', ref: 'feat' },
  base: { sha: 'b1', ref: 'main' },
  files: [
    { path: 'src/parse.ts', status: 'modified', additions: 2, deletions: 1, sha: 's1', hunks: [{ header: '@@ -1,2 +1,3 @@', oldStart: 1, oldLines: 2, newStart: 1, newLines: 3, patch: ' a\n-b\n+c\n+d' }] },
    { path: 'src/cache.ts', status: 'added', additions: 1, deletions: 0, sha: 's2', hunks: [{ header: '@@ -0,0 +1,1 @@', oldStart: 0, oldLines: 0, newStart: 1, newLines: 1, patch: '+export const cache = new Map()' }] },
  ],
} as never

const plan = {
  title: 'Parser cache',
  overview: 'Adds a cache.',
  stops: [
    { title: 'Where parsing starts', purpose: 'The entry point.', files: ['src/parse.ts'], notes: 'parse() is called per save.' },
    { title: 'The cache', purpose: 'How entries live.', files: ['src/cache.ts'], notes: '' },
  ],
  glossary: [{ term: 'entry', meaning: 'One parsed file.' }],
}

afterEach(() => setCliRunner(undefined))

describe('tour prompts', () => {
  it('plans in the reader language with the numbered diff', () => {
    const prompt = buildTourPlanPrompt(diff, 'de')
    expect(prompt).toContain('Write in German.')
    expect(prompt).toContain('Parses once per file.')
    expect(prompt).toContain('src/cache.ts')
  })

  it('asks every prompt to write each mention of the code as a link to its place (rule 96)', () => {
    expect(buildTourPlanPrompt(diff, 'en')).toContain(MENTION_RULE)
    expect(buildTourStopPrompt(diff, plan, 0, 'en')).toContain(MENTION_RULE)
  })

  it('asks for flows as one hop per line, so the app can draw them', () => {
    expect(buildTourStopPrompt(diff, plan, 0, 'en')).toContain('`From -> To: what moves`')
  })

  it('gives one stop the outline and only its own files', () => {
    const prompt = buildTourStopPrompt(diff, plan, 1, 'en')
    expect(prompt).toContain('2. The cache: How entries live.   <- this stop')
    expect(prompt).toContain('### src/cache.ts')
    expect(prompt).not.toContain('### src/parse.ts')
  })
})

describe('resolving answers', () => {
  it('caps stops and drops untitled ones', () => {
    const stops = Array.from({ length: 12 }, (_, at) => ({ title: at === 0 ? '' : `Stop ${at}`, purpose: 'p', files: ['a'], notes: '' }))
    const resolved = resolveTourPlan({ title: 'T', overview: 'O', stops, glossary: [] })
    expect(resolved.stops).toHaveLength(MAX_STOPS)
    expect(resolved.stops[0]!.title).toBe('Stop 1')
  })

  it('rejects a plan without stops', () => {
    expect(() => resolveTourPlan({ title: 'T', stops: [] })).toThrow('no stops')
  })

  it('splits the Markdown body into prose and visuals', () => {
    const stop = resolveTourStop({
      takeaway: 'It caches.',
      body: 'Parsing is cached.\n\n```calls\nsave\n  parse\n    cache.get\n```\n\nThe flow:\n\n```mermaid\nflowchart TD\n  A --> B\n```\n\n```ts\nexport const cache = new Map()\n```\n\n```\n```\nDone.',
      sources: [{ path: 'src/cache.ts', line: 1.7, why: 'the map' }, { path: '', line: 0, why: '' }],
    })
    expect(stop.blocks).toEqual([
      { kind: 'text', text: 'Parsing is cached.' },
      { kind: 'shape', text: '', lang: 'calls', body: 'save\n  parse\n    cache.get' },
      { kind: 'text', text: 'The flow:' },
      { kind: 'diagram', text: '', body: 'flowchart TD\n  A --> B' },
      { kind: 'shape', text: 'ts', lang: 'code', body: 'export const cache = new Map()' },
      { kind: 'text', text: 'Done.' },
    ])
    expect(stop.sources).toEqual([{ path: 'src/cache.ts', line: 1, why: 'the map' }])
  })
})

describe('runTour', () => {
  const resolved = { model: { provider: 'claude-code', id: 'sonnet' }, effort: undefined } as never

  it('plans once, then writes every stop, and keeps a failed stop with its error', async () => {
    const runner = vi.fn(async (request: { system: string, prompt: string }) => {
      if (request.prompt.includes('Plan a guided tour'))
        return { text: JSON.stringify(plan), usage: { model: 'sonnet-5' } }
      if (request.prompt.includes('Write stop 2'))
        throw new Error('engine fell over')
      return { text: JSON.stringify({ takeaway: 'Entry.', body: 'Starts here.', sources: [] }) }
    })
    setCliRunner(runner as never)
    const tour = await runTour(diff, resolved, { locale: 'en' })
    expect(runner).toHaveBeenCalledTimes(3)
    expect(tour.title).toBe('Parser cache')
    expect(tour.stops[0]).toMatchObject({ takeaway: 'Entry.' })
    expect(tour.stops[1]).toMatchObject({ title: 'The cache', error: 'engine fell over' })
    expect(tour).toMatchObject({ headSha: 'h1', model: 'sonnet-5', locale: 'en', glossary: plan.glossary })
  })

  it('fails when no stop could be written', async () => {
    setCliRunner((async (request: { prompt: string }) => {
      if (request.prompt.includes('Plan a guided tour'))
        return { text: JSON.stringify(plan) }
      throw new Error('down')
    }) as never)
    await expect(runTour(diff, resolved, { locale: 'en' })).rejects.toThrow('down')
  })
})
