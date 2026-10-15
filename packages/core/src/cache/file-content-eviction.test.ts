import type { PrCacheEntry } from './schema'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { expect, it } from 'vitest'
import { createCacheRepositories } from '../cache'

it('evicting a diff removes all full-file contents for its scope', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const repositories = createCacheRepositories(storage, { maxEntries: 1 })
  const entry = (key: string, lastViewedAt: number): PrCacheEntry => ({
    key,
    headSha: key,
    lastViewedAt,
    sizeBytes: 1,
    analyzedBy: {},
    diff: { ref: { kind: 'paste', hash: key }, title: key, files: [] },
  })
  await repositories.diffs.put(entry('old', 1))
  await repositories.fileContents.set('old', 'base', 'file.ts', 'old base')
  await repositories.fileContents.set('old', 'head', 'file.ts', 'old head')
  await repositories.fileContents.set('new', 'head', 'file.ts', 'new head')
  await repositories.diffs.put(entry('new', 2))
  expect(await repositories.diffs.get('old')).toBeUndefined()
  expect(await repositories.fileContents.get('old', 'base', 'file.ts')).toBeUndefined()
  expect(await repositories.fileContents.get('old', 'head', 'file.ts')).toBeUndefined()
  expect(await repositories.fileContents.get('new', 'head', 'file.ts')).toBe('new head')
})
