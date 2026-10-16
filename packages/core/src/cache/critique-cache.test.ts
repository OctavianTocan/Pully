import type { CritiqueResult } from '../types/analyze'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { beforeEach, describe, expect, it } from 'vitest'
import { createCritiqueCache, CRITIQUES_PER_DIFF } from './critique-cache'

const result = (summary: string, headSha = 'h1'): CritiqueResult => ({ summary, findings: [], dropped: 0, headSha, engine: 'codex', generatedAt: '2026-01-01T00:00:00Z' })

let storage: ReturnType<typeof createStorage>
let cache: ReturnType<typeof createCritiqueCache>

beforeEach(() => {
  storage = createStorage({ driver: memoryDriver() })
  cache = createCritiqueCache(storage)
})

describe('critique cache', () => {
  it('round-trips per variant and replaces a variant on save', async () => {
    await cache.set('pr', 'a', result('one'))
    await cache.set('pr', 'b', result('two'))
    await cache.set('pr', 'a', result('three'))
    await expect(cache.get('pr', 'a')).resolves.toMatchObject({ summary: 'three' })
    await expect(cache.get('pr', 'b')).resolves.toMatchObject({ summary: 'two' })
    await expect(cache.get('other', 'a')).resolves.toBeUndefined()
  })

  it('returns the newest critique, optionally of one commit', async () => {
    await cache.set('pr', 'a', result('old commit', 'h0'))
    await cache.set('pr', 'b', result('new commit', 'h1'))
    await expect(cache.latest('pr')).resolves.toMatchObject({ summary: 'new commit' })
    await expect(cache.latest('pr', 'h0')).resolves.toMatchObject({ summary: 'old commit' })
    await expect(cache.latest('pr', 'h2')).resolves.toBeUndefined()
  })

  it('keeps a bounded number of critiques and treats a corrupt entry as a miss', async () => {
    for (let index = 0; index <= CRITIQUES_PER_DIFF; index++)
      await cache.set('pr', `v${index}`, result(`${index}`))
    await expect(cache.get('pr', 'v0')).resolves.toBeUndefined()
    await expect(cache.get('pr', `v${CRITIQUES_PER_DIFF}`)).resolves.toBeDefined()

    await storage.setItem('critique:pr', { entries: 'nope' })
    await expect(cache.latest('pr')).resolves.toBeUndefined()
  })
})

it('serializes concurrent variants across repositories over the same storage', async () => {
  const other = createCritiqueCache(storage)
  await Promise.all([
    cache.set('pr', 'security', result('security review')),
    other.set('pr', 'correctness', result('correctness review')),
  ])
  await expect(cache.get('pr', 'security')).resolves.toMatchObject({ summary: 'security review' })
  await expect(other.get('pr', 'correctness')).resolves.toMatchObject({ summary: 'correctness review' })
  await expect(cache.latestEntry('pr', 'h1')).resolves.toMatchObject({ variant: 'correctness', result: { summary: 'correctness review' } })
  await expect(cache.latestEntry('pr', 'missing')).resolves.toBeUndefined()
})
