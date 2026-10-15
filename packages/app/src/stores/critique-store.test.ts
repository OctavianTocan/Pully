import type { CacheRepositories } from '@pulls.review/core/cache'
import type { CritiqueFinding, CritiqueResult, DiffsPayload } from '@pulls.review/core/types'
import type { LensSource } from '../local/lenses'
import type { DiffsStoreReviews } from './types'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { settings } from '../state/settings'
import { createCritiqueStore } from './critique-store'

const mocks = vi.hoisted(() => ({ runCritique: vi.fn() }))

vi.mock('@pulls.review/core/llm', async importOriginal => ({
  ...await importOriginal<typeof import('@pulls.review/core/llm')>(),
  runCritique: mocks.runCritique,
}))

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '1' },
  title: 'PR',
  head: { sha: 'h1', ref: 'feat' },
  files: [],
}

function finding(id: string, severity: CritiqueFinding['severity'], extra: Partial<CritiqueFinding> = {}): CritiqueFinding {
  return { id, path: 'a.ts', side: 'additions', line: 3, severity, title: `${id} title`, body: `${id} body`, ...extra }
}

function critique(extra: Partial<CritiqueResult> = {}): CritiqueResult {
  return {
    summary: 'Looks risky.',
    findings: [finding('f1', 'bug', { startLine: 1 }), finding('f2', 'risk'), finding('f3', 'nit')],
    dropped: 0,
    headSha: 'h1',
    engine: 'claude-code',
    generatedAt: '2026-10-05T00:00:00Z',
    ...extra,
  }
}

function fakeReviews() {
  return { canWrite: true, postReview: vi.fn<DiffsStoreReviews['postReview']>(async () => {}) }
}

function settle() {
  return new Promise(resolve => setTimeout(resolve, 0))
}

let cache: CacheRepositories
const originalLlm = settings.value.llm

beforeEach(() => {
  cache = createCacheRepositories(createStorage({ driver: memoryDriver() }))
  mocks.runCritique.mockReset()
  settings.value.llm = { ...originalLlm, provider: 'claude-code', claudeCodeModel: 'sonnet', claudeCodeEffort: '' }
})

afterEach(() => {
  settings.value.llm = originalLlm
})

describe('createCritiqueStore', () => {
  it('runs a review, picks its non-nit findings and shows it again from the cache', async () => {
    mocks.runCritique.mockResolvedValue(critique())
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined })
    await settle()
    expect(store.available).toBe(true)

    await store.run()
    expect(mocks.runCritique).toHaveBeenCalledOnce()
    expect(store.result?.summary).toBe('Looks risky.')
    expect([...store.selected]).toEqual(['f1', 'f2'])
    expect(store.isRunning).toBe(false)

    const reopened = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined })
    await vi.waitFor(() => expect(reopened.result?.summary).toBe('Looks risky.'))
  })

  it('serves a saved review for the same settings unless forced', async () => {
    mocks.runCritique.mockResolvedValue(critique())
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined })
    await store.run()
    await store.run()
    expect(mocks.runCritique).toHaveBeenCalledOnce()

    await store.run({ force: true })
    expect(mocks.runCritique).toHaveBeenCalledTimes(2)
  })

  it('appends a lens\'s instructions and fails on a lens that is gone', async () => {
    mocks.runCritique.mockResolvedValue(critique({ lens: 'security' }))
    const lenses: LensSource = {
      list: async () => [{ name: 'security', title: 'Security' }],
      get: async name => name === 'security' ? 'Look for injections.' : undefined,
    }
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined, lenses })

    await store.run({ lens: 'security' })
    expect(mocks.runCritique.mock.calls[0]![2]).toMatchObject({ lens: { name: 'security', instructions: 'Look for injections.' } })
    expect(store.lens).toBe('security')

    await store.run({ lens: 'gone' })
    expect(mocks.runCritique).toHaveBeenCalledOnce()
    expect(store.error?.message).toContain('gone')
  })

  it('posts the visible picked findings once, as review comments on their lines', async () => {
    mocks.runCritique.mockResolvedValue(critique())
    const reviews = fakeReviews()
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => reviews })
    await store.run()

    store.setSeverities(['bug'])
    expect(store.toPost.map(item => item.id)).toEqual(['f1'])
    expect(store.canPost).toBe(true)
    await store.post('Summary')

    expect(reviews.postReview).toHaveBeenCalledWith('Summary', [
      { target: { path: 'a.ts', side: 'additions', line: 3, startLine: 1, startSide: 'additions' }, body: expect.stringContaining('f1 body') },
    ])
    expect(store.posted.has('f1')).toBe(true)
    expect(store.selected.has('f1')).toBe(false)
    expect(store.canPost).toBe(false)
  })

  it('cannot post a review of an older head', async () => {
    mocks.runCritique.mockResolvedValue(critique({ headSha: 'old' }))
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => fakeReviews() })
    await store.run()
    expect(store.toPost.length).toBe(2)
    expect(store.canPost).toBe(false)
  })

  it('stops following a review without reporting an error', async () => {
    mocks.runCritique.mockImplementation((_diff, _resolved, options: { signal: AbortSignal }) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    }))
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined })
    const running = store.run()
    await vi.waitFor(() => expect(mocks.runCritique).toHaveBeenCalled())
    expect(store.isRunning).toBe(true)

    store.abort()
    await running
    expect(store.isRunning).toBe(false)
    expect(store.error).toBeUndefined()
    expect(store.result).toBeUndefined()
  })

  it('does nothing without a CLI engine', async () => {
    settings.value.llm = { ...settings.value.llm, provider: 'anthropic', anthropicApiKey: 'key' }
    const store = createCritiqueStore({ cache, diff: ref(diff), getReviews: () => undefined })
    expect(store.available).toBe(false)
    await store.run()
    expect(mocks.runCritique).not.toHaveBeenCalled()
  })
})
