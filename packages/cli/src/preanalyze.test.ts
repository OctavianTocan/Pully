import type { MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { PreanalyzerDeps } from './preanalyze'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createPasteSource } from '@pulls.review/core/paste'
import { normalizeGroupedResult } from '@pulls.review/core/types'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createPreanalyzer, isPreanalyzeEngine, needsPreanalysis, PREANALYZE_MAX_FILES } from './preanalyze'

const KEY = 'github:acme/app#7'

function pull(overrides: Partial<MyPull> = {}): MyPull {
  return { owner: 'acme', repo: 'app', number: 7, title: 'PR', author: 'octo', isDraft: false, updatedAt: '2026-10-01T00:00:00Z', commentsCount: 0, labels: [], state: 'open', role: 'review-requested', requestedFromMe: true, headSha: 'h2', changedFiles: 1, ...overrides }
}

const PATCH = `diff --git a/a.ts b/a.ts\nindex 000..111 100644\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n`
const base = await createPasteSource(PATCH).fetch()

function diff(sha: string): DiffsPayload {
  return { ...base, head: { sha, ref: 'feature' }, files: base.files.map(file => ({ ...file, sha: `f-${sha}` })) }
}

const result = normalizeGroupedResult('llm', { overallSummary: 'Summary', groups: [], schemaVersion: 1 }, 'claude-code/test')

function setup(pulls: MyPull[], overrides: Partial<PreanalyzerDeps> = {}) {
  const cache = createCacheRepositories(createStorage({ driver: memoryDriver() }))
  const deps = {
    listPulls: vi.fn(async () => pulls),
    cache,
    fetchDiff: vi.fn(async (p: MyPull) => diff(p.headSha!)),
    analyze: vi.fn<PreanalyzerDeps['analyze']>(async () => ({ result, transcript: [{ role: 'user', content: 'Group this', timestamp: 0 }] })),
    log: vi.fn(),
    ...overrides,
  }
  return { ...deps, preanalyzer: createPreanalyzer(deps) }
}

describe('needsPreanalysis', () => {
  it('picks open pull requests that request a review and have no AI grouping for their head', () => {
    expect(needsPreanalysis(pull(), undefined)).toBe(true)
    expect(needsPreanalysis(pull(), { headSha: 'h2', hasLlm: false })).toBe(true)
    expect(needsPreanalysis(pull(), { headSha: 'h1', hasLlm: true })).toBe(true)
    expect(needsPreanalysis(pull(), { headSha: 'h2', hasLlm: true })).toBe(false)
  })

  it('leaves out pull requests outside the review queue, without a head, or too large', () => {
    expect(needsPreanalysis(pull({ requestedFromMe: false, role: 'authored' }), undefined)).toBe(false)
    expect(needsPreanalysis(pull({ requestedFromMe: undefined, role: 'review-requested' }), undefined)).toBe(true)
    expect(needsPreanalysis(pull({ state: 'closed' }), undefined)).toBe(false)
    expect(needsPreanalysis(pull({ headSha: undefined }), undefined)).toBe(false)
    expect(needsPreanalysis(pull({ changedFiles: PREANALYZE_MAX_FILES + 1 }), undefined)).toBe(false)
  })
})

it('accepts only the CLI engines', () => {
  expect(isPreanalyzeEngine('codex')).toBe(true)
  expect(isPreanalyzeEngine('gateway')).toBe(false)
  expect(isPreanalyzeEngine(undefined)).toBe(false)
})

describe('createPreanalyzer', () => {
  it('fetches, analyzes and caches a requested pull request where the browser reads it', async () => {
    const { cache, fetchDiff, analyze, preanalyzer } = setup([pull()])
    await preanalyzer.round()
    expect(fetchDiff).toHaveBeenCalledOnce()
    expect(analyze).toHaveBeenCalledOnce()
    const entry = await cache.diffs.get(KEY)
    expect(entry?.headSha).toBe('h2')
    expect(entry?.analyzedBy.llm?.overallSummary).toBe('Summary')
    expect(entry?.llmSession?.chatStartIndex).toBe(1)
  })

  it('reuses a cached diff at the head commit and skips one already analyzed', async () => {
    const { cache, fetchDiff, analyze, preanalyzer } = setup([pull(), pull({ number: 8 })])
    await cache.diffs.putDiff(KEY, diff('h2'), 'h2')
    await cache.diffs.putDiff('github:acme/app#8', diff('h2'), 'h2')
    await cache.diffs.setAnalyzedResult('github:acme/app#8', 'llm', result)
    await preanalyzer.round()
    expect(fetchDiff).not.toHaveBeenCalled()
    expect(analyze).toHaveBeenCalledOnce()
    expect((await cache.diffs.get(KEY))?.analyzedBy.llm).toBeDefined()
  })

  it.each([false, true])('does not replace a newer head or its chat when preanalysis finishes late (cached: %s)', async (alreadyCached) => {
    let complete!: () => void
    const analyze = vi.fn<PreanalyzerDeps['analyze']>(() => new Promise(resolve => complete = () => resolve({ result, transcript: [{ role: 'user', content: 'Old analysis', timestamp: 0 }] })))
    const { cache, preanalyzer } = setup([pull()], { analyze })
    if (alreadyCached)
      await cache.diffs.putDiff(KEY, diff('h2'), 'h2', { viewed: false })
    const round = preanalyzer.round()
    await vi.waitFor(() => expect(analyze).toHaveBeenCalledOnce())
    await cache.diffs.putDiff(KEY, diff('h3'), 'h3', { viewed: false })
    const session = { messages: [{ role: 'user' as const, content: 'New head question', timestamp: 3 }], chatStartIndex: 0 }
    await cache.diffs.setLlmSession(KEY, session)
    complete()
    await round
    const entry = await cache.diffs.get(KEY)
    expect(entry?.headSha).toBe('h3')
    expect(entry?.analyzedBy.llm).toBeUndefined()
    expect(entry?.llmSession).toEqual(session)
    expect(entry?.lastViewedAt).toBe(0)
  })

  it('keeps background preanalysis out of recent views and archives the current chat atomically', async () => {
    const { cache, preanalyzer } = setup([pull()])
    await cache.diffs.putDiff(KEY, diff('h1'), 'h1', { viewed: false })
    await cache.diffs.setLlmSession(KEY, { messages: [{ role: 'user', content: 'Prior question', timestamp: 1 }], chatStartIndex: 0 })
    await preanalyzer.round()
    const entry = await cache.diffs.get(KEY)
    expect(entry?.lastViewedAt).toBe(0)
    expect(entry?.llmSession?.archive?.[0]?.messages).toEqual([{ role: 'user', content: 'Prior question', timestamp: 1 }])
    expect(await cache.diffs.listRecent(10)).toEqual([])
  })

  it('tries a failing head commit once, then again after a new push', async () => {
    const analyze = vi.fn<PreanalyzerDeps['analyze']>(async () => {
      throw new Error('AI jobs are not available on this server.')
    })
    const pulls = [pull()]
    const { cache, log, preanalyzer } = setup(pulls, { analyze, listPulls: async () => pulls })
    await preanalyzer.round()
    await preanalyzer.round()
    expect(analyze).toHaveBeenCalledOnce()
    expect(await cache.diffs.get(KEY)).toBeUndefined()
    expect(log).toHaveBeenCalledWith(`pre-analysis of ${KEY} failed: AI jobs are not available on this server.`)
    pulls[0] = pull({ headSha: 'h3' })
    await preanalyzer.round()
    expect(analyze).toHaveBeenCalledTimes(2)
  })

  it('logs and waits for the next round when the list cannot be fetched', async () => {
    const { log, analyze, preanalyzer } = setup([], { listPulls: async () => {
      throw new Error('no GitHub token')
    } })
    await preanalyzer.round()
    expect(analyze).not.toHaveBeenCalled()
    expect(log).toHaveBeenCalledWith('pre-analysis skipped: no GitHub token')
  })

  it('drops the result of a run stopped mid-way', async () => {
    let finish!: () => void
    const analyze = vi.fn<PreanalyzerDeps['analyze']>(() => new Promise(resolve => finish = () => resolve({ result, transcript: [] })))
    const { cache, preanalyzer } = setup([pull()], { analyze })
    const round = preanalyzer.round()
    await vi.waitFor(() => expect(analyze).toHaveBeenCalled())
    preanalyzer.stop()
    finish()
    await round
    expect((await cache.diffs.get(KEY))?.analyzedBy.llm).toBeUndefined()
  })
})
