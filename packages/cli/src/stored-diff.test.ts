import type { DiffsPayload, FileChange } from '@pulls.review/core/types'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createPatchReader, FILE_PATCH_BUDGET, openStoredDiff, PATCH_BUDGET, trimPatches } from './stored-diff'

const KEY = 'github:acme/app#7'

function file(path: string, patchChars: number, extra: Partial<FileChange> = {}): FileChange {
  const hunks = patchChars ? [{ header: '@@ -1 +1 @@', oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, patch: 'x'.repeat(patchChars) }] : []
  return { path, status: 'modified', additions: 1, deletions: 1, isBinary: false, sha: `sha-${path}`, hunks, ...extra }
}

function diff(head: string, files: FileChange[] = [file('a.ts', 10)]): DiffsPayload {
  return {
    ref: { kind: 'github-pr', owner: 'acme', repo: 'app', number: '7' },
    title: `At ${head}`,
    description: 'Body',
    base: { sha: 'base', ref: 'main' },
    head: { sha: head, ref: 'feature' },
    pullRequest: { state: 'open' },
    commits: [{ sha: head, message: 'Change' }],
    files,
  }
}

function cache() {
  return createCacheRepositories(createStorage({ driver: memoryDriver() })).diffs
}

describe('trimPatches', () => {
  it('keeps hunks in diff order while the total stays under the budget, and skips files over their own budget', () => {
    const big = FILE_PATCH_BUDGET
    const files = [
      file('first.ts', 100),
      file('huge.ts', big),
      file('fill-1.ts', big - 1),
      file('fill-2.ts', big - 1),
      file('fill-3.ts', big - 1),
      file('fill-4.ts', big - 1),
      file('small.ts', 50),
      file('image.png', 0, { isBinary: true }),
    ]
    const { diff: lite, omitted } = trimPatches(diff('h1', files))

    expect(lite.files.map(f => [f.path, f.patchOmitted ?? false])).toEqual([
      ['first.ts', false],
      ['huge.ts', true],
      ['fill-1.ts', false],
      ['fill-2.ts', false],
      ['fill-3.ts', false],
      // 100 + 4 * (150_000 - 1) passes 600_000, so this one waits for `diff-patches`.
      ['fill-4.ts', true],
      ['small.ts', false],
      ['image.png', false],
    ])
    expect(omitted).toBe(2)
    expect(lite.files.filter(f => !f.patchOmitted).reduce((sum, f) => sum + f.hunks.reduce((n, h) => n + h.patch.length, 0), 0)).toBeLessThan(PATCH_BUDGET)
  })

  it('leaves every other field of the diff and its files as they were', () => {
    const full = diff('h1', [file('huge.ts', FILE_PATCH_BUDGET, { status: 'renamed', previousPath: 'old.ts', truncated: true })])
    const { diff: lite } = trimPatches(full)
    const { files: _files, ...rest } = full
    expect(lite).toMatchObject(rest)
    expect(lite.files[0]).toEqual({ ...full.files[0], hunks: [], patchOmitted: true })
    expect(full.files[0]!.hunks).toHaveLength(1)
  })

  it('omits nothing from a small diff', () => {
    expect(trimPatches(diff('h1')).omitted).toBe(0)
  })
})

describe('openStoredDiff', () => {
  function source(head: string | Error, fetched = diff(head instanceof Error ? 'h-fetched' : head)) {
    return {
      fetch: vi.fn(async () => fetched),
      fingerprint: vi.fn(async () => {
        if (head instanceof Error)
          throw head
        return head
      }),
    }
  }

  it('answers the stored copy when its head is current, without fetching, and counts it as a view', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1', { viewed: false })
    const src = source('h1')

    const opened = await openStoredDiff({ key: KEY, source: src, diffs })

    expect(opened).toEqual({ diff: diff('h1') })
    expect(src.fingerprint).toHaveBeenCalledOnce()
    expect(src.fetch).not.toHaveBeenCalled()
    expect((await diffs.getMeta(KEY))!.lastViewedAt).toBeGreaterThan(0)
  })

  it('fetches and stores when the head moved', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const src = source('h2')

    expect(await openStoredDiff({ key: KEY, source: src, diffs })).toEqual({ diff: diff('h2') })
    expect(src.fetch).toHaveBeenCalledOnce()
    expect((await diffs.get(KEY))!.headSha).toBe('h2')
  })

  it('fetches without asking for the head when nothing is stored', async () => {
    const diffs = cache()
    const src = source('h1')

    expect(await openStoredDiff({ key: KEY, source: src, diffs })).toEqual({ diff: diff('h1') })
    expect(src.fingerprint).not.toHaveBeenCalled()
    expect((await diffs.getMeta(KEY))!.headSha).toBe('h1')
  })

  it('answers the stored copy as stale when GitHub cannot be asked', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const src = source(new Error('offline'))

    expect(await openStoredDiff({ key: KEY, source: src, diffs })).toEqual({ diff: diff('h1'), stale: true })
    expect(src.fetch).not.toHaveBeenCalled()
  })

  it('answers the stored copy as stale when the head does not come in time', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const src = { fetch: vi.fn(async () => diff('h2')), fingerprint: () => new Promise<string>(() => {}) }

    expect(await openStoredDiff({ key: KEY, source: src, diffs, headTimeoutMs: 10 })).toEqual({ diff: diff('h1'), stale: true })
  })

  it('answers the stored copy as stale when the head moved but the new diff fails to load', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const src = { ...source('h2'), fetch: vi.fn(async (): Promise<DiffsPayload> => {
      throw new Error('rate limited')
    }) }

    expect(await openStoredDiff({ key: KEY, source: src, diffs })).toEqual({ diff: diff('h1'), stale: true })
  })

  it('with fresh, fetches and stores even when the stored copy is current', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const refetched = { ...diff('h1'), title: 'Renamed' }
    const src = source('h1', refetched)

    expect(await openStoredDiff({ key: KEY, source: src, diffs, fresh: true })).toEqual({ diff: refetched })
    expect(src.fingerprint).not.toHaveBeenCalled()
    expect((await diffs.get(KEY))!.diff.title).toBe('Renamed')
  })

  it.each(['cold', 'fresh', 'changed'] as const)('keeps the winning head when a %s fetch finishes late', async (mode) => {
    const diffs = cache()
    if (mode !== 'cold')
      await diffs.putDiff(KEY, diff('h1'), 'h1')
    let complete!: (value: DiffsPayload) => void
    const fetch = vi.fn(() => new Promise<DiffsPayload>(resolve => complete = resolve))
    const opened = openStoredDiff({ key: KEY, source: { fetch, fingerprint: async () => 'h2' }, diffs, fresh: mode === 'fresh' })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledOnce())
    await diffs.putDiff(KEY, diff('h3'), 'h3', { viewed: false })
    complete(diff('h2'))
    expect(await opened).toEqual({ diff: diff('h3') })
    expect((await diffs.get(KEY))?.headSha).toBe('h3')
    expect((await diffs.getMeta(KEY))?.lastViewedAt).toBeGreaterThan(0)
  })

  it('with fresh, fails when GitHub does', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1'), 'h1')
    const src = { ...source('h1'), fetch: vi.fn(async (): Promise<DiffsPayload> => {
      throw new Error('rate limited')
    }) }

    await expect(openStoredDiff({ key: KEY, source: src, diffs, fresh: true })).rejects.toThrow('rate limited')
  })
})

describe('createPatchReader', () => {
  it('reads the stored hunks of the named paths and leaves out paths not in the diff', async () => {
    const diffs = cache()
    const full = diff('h1', [file('a.ts', 10), file('b.ts', 20)])
    await diffs.putDiff(KEY, full, 'h1')

    expect(await createPatchReader(diffs).read(KEY, ['b.ts', 'missing.ts'])).toEqual({
      'b.ts': { hunks: full.files[1]!.hunks, sha: 'sha-b.ts' },
    })
  })

  it('refuses a key with nothing stored', async () => {
    await expect(createPatchReader(cache()).read(KEY, ['a.ts'])).rejects.toThrow('Open the diff first.')
  })

  it('serves a remembered diff without reading the body, and the stored one once the head moves', async () => {
    const diffs = cache()
    await diffs.putDiff(KEY, diff('h1', [file('a.ts', 10)]), 'h1')
    const reader = createPatchReader(diffs)
    const get = vi.spyOn(diffs, 'get')
    reader.remember(KEY, 'h1', diff('h1', [file('a.ts', 10)]))

    expect((await reader.read(KEY, ['a.ts']))['a.ts']!.hunks[0]!.patch).toHaveLength(10)
    expect(get).not.toHaveBeenCalled()

    await diffs.putDiff(KEY, diff('h2', [file('a.ts', 30)]), 'h2', { viewed: false })
    expect((await reader.read(KEY, ['a.ts']))['a.ts']!.hunks[0]!.patch).toHaveLength(30)
    expect(get).toHaveBeenCalledOnce()
  })
})
