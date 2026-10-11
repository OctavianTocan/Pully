import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createStorage } from 'unstorage'
import fsDriver from 'unstorage/drivers/fs'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { flatKeys, repoCacheDriverAt } from './storage'

let dir: string

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'pulls-review-cache-'))
})

afterEach(() => {
  rmSync(dir, { recursive: true, force: true })
})

describe('flatKeys', () => {
  it('stores keys that would need one path as both a file and a directory', async () => {
    const storage = createStorage({ driver: flatKeys(fsDriver({ base: dir })) })
    await storage.setItem('pr-meta:github:o/r@main...feat', 'a')
    await storage.setItem('pr-meta:github:o/r@main...feat/x', 'b')
    await storage.setItem('review:sha', 'c')

    expect(await storage.getItem('pr-meta:github:o/r@main...feat')).toBe('a')
    expect(await storage.getItem('pr-meta:github:o/r@main...feat/x')).toBe('b')
    expect((await storage.getKeys('pr-meta:')).sort()).toEqual(['pr-meta:github:o:r@main...feat', 'pr-meta:github:o:r@main...feat:x'])
    await storage.removeItem('review:sha')
    expect(await storage.getKeys('review:')).toEqual([])
  })
})

describe('createRepoCacheDriver', () => {
  it('lists keys when the cache lives under .git', async () => {
    // The real cache is `<git-common-dir>/pulls-review`; the fs driver's default ignore
    // (`**/.git/**`) matched that absolute path and hid every key.
    const base = join(dir, '.git', 'pulls-review')
    const storage = createStorage({ driver: repoCacheDriverAt(base) })
    await storage.setItem('pr-meta:github:o/r#1', 'a')
    expect(await storage.getKeys('pr-meta:')).toEqual(['pr-meta:github:o:r#1'])
  })
})
