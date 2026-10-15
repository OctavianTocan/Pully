import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createFileContentCache } from './file-content-cache'

let cache: ReturnType<typeof createFileContentCache>

beforeEach(() => {
  cache = createFileContentCache(createStorage({ driver: memoryDriver() }))
})

describe('file content cache', () => {
  it('round-trips content for a given diff + sha + path', async () => {
    await cache.set('github:o/r#1', 'sha-a', 'src/foo.ts', 'export const x = 1')
    await expect(cache.get('github:o/r#1', 'sha-a', 'src/foo.ts')).resolves.toBe('export const x = 1')
  })

  it('misses on an unknown entry', async () => {
    await expect(cache.get('github:o/r#1', 'sha-a', 'src/foo.ts')).resolves.toBeUndefined()
  })

  it('keys by diff, sha and path', async () => {
    await cache.set('github:o/r#1', 'sha-a', 'src/foo.ts', 'old content')
    await cache.set('github:o/r#1', 'sha-b', 'src/foo.ts', 'new content')
    await cache.set('github:o/r#1', 'sha-a', 'src/bar.ts', 'bar content')
    await cache.set('local:repo', 'sha-a', 'src/foo.ts', 'local content')

    await expect(cache.get('github:o/r#1', 'sha-a', 'src/foo.ts')).resolves.toBe('old content')
    await expect(cache.get('github:o/r#1', 'sha-b', 'src/foo.ts')).resolves.toBe('new content')
    await expect(cache.get('github:o/r#1', 'sha-a', 'src/bar.ts')).resolves.toBe('bar content')
    await expect(cache.get('local:repo', 'sha-a', 'src/foo.ts')).resolves.toBe('local content')
  })
})

it('accounts for UTF-8 bytes, evicts least-recently-used contents and skips oversized files', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const limited = createFileContentCache(storage, { maxBytes: 8 })
  let now = 0
  const clock = vi.spyOn(Date, 'now').mockImplementation(() => ++now)
  try {
    await limited.set('scope', 'h', 'a', 'éé')
    await limited.set('scope', 'h', 'b', '1234')
    await limited.get('scope', 'h', 'a')
    await limited.set('scope', 'h', 'c', '5678')
    expect(await limited.get('scope', 'h', 'b')).toBeUndefined()
    expect(await limited.get('scope', 'h', 'a')).toBe('éé')
    expect(await limited.get('scope', 'h', 'c')).toBe('5678')
    await limited.set('scope', 'h', 'too-big', 'x'.repeat(9))
    expect(await limited.get('scope', 'h', 'too-big')).toBeUndefined()
    expect(await limited.get('scope', 'h', 'a')).toBe('éé')
    expect(await storage.getKeys('file-content:')).toHaveLength(2)
  }
  finally {
    clock.mockRestore()
  }
})

it('bounds empty entries and serializes concurrent writers sharing storage', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const first = createFileContentCache(storage, { maxBytes: 8, maxEntries: 2 })
  const second = createFileContentCache(storage, { maxBytes: 8, maxEntries: 2 })
  await Promise.all([
    first.set('a', 'h', 'a', '123456'),
    second.set('b', 'h', 'b', '123456'),
  ])
  expect(await storage.getKeys('file-content:')).toHaveLength(1)
  for (let index = 0; index < 5; index++)
    await first.set('empty', 'h', `${index}`, '')
  expect((await storage.getKeys('file-content:')).length).toBeLessThanOrEqual(2)
})

it('removes precisely one diff scope and discards old unbudgeted entries', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const scoped = createFileContentCache(storage)
  await storage.setItem('file-content:legacy:sha:path', 'old unaccounted text')
  await scoped.set('github:o/r#1', 'h1', 'a', 'one')
  await scoped.set('github:o/r#1', 'h2', 'a', 'two')
  await scoped.set('github:o/r#10', 'h1', 'a', 'other')
  expect(await storage.getItem('file-content:legacy:sha:path')).toBeNull()
  await scoped.removeScope('github:o/r#1')
  expect(await scoped.get('github:o/r#1', 'h1', 'a')).toBeUndefined()
  expect(await scoped.get('github:o/r#1', 'h2', 'a')).toBeUndefined()
  expect(await scoped.get('github:o/r#10', 'h1', 'a')).toBe('other')
})

it('treats quota failures as optional cache misses, but preserves unrelated failures', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const optional = createFileContentCache(storage)
  const write = vi.spyOn(storage, 'setItem').mockRejectedValueOnce(new DOMException('Full', 'QuotaExceededError'))
  await expect(optional.set('scope', 'h', 'a', 'fetched content')).resolves.toBeUndefined()
  expect(await optional.get('scope', 'h', 'a')).toBeUndefined()
  write.mockRejectedValueOnce(new Error('Storage disconnected'))
  await expect(optional.set('scope', 'h', 'a', 'content')).rejects.toThrow('Storage disconnected')
})

it('removes a body whose optional metadata write hits quota', async () => {
  const storage = createStorage({ driver: memoryDriver() })
  const optional = createFileContentCache(storage)
  const original = storage.setItem.bind(storage)
  vi.spyOn(storage, 'setItem').mockImplementation(async (key, value, options) => {
    if (key.startsWith('file-content-meta:'))
      throw new DOMException('Full', 'QuotaExceededError')
    await original(key, value, options)
  })
  await expect(optional.set('scope', 'h', 'a', 'fetched content')).resolves.toBeUndefined()
  expect(await storage.getKeys('file-content:')).toEqual([])
})
