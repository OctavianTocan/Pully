import type { DiffsPayload, FileChange } from '@pulls.review/core/types'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { handleNativeRequest } from './native-api'
import { storeOverview } from './pr-store'
import { serverSession } from './server-session'
import { FILE_PATCH_BUDGET } from './stored-diff'

const KEY = 'github:acme/app#7'
let repo: string

function file(path: string, patchChars: number): FileChange {
  return { path, status: 'modified', additions: 1, deletions: 1, isBinary: false, sha: `sha-${path}`, hunks: [{ header: '@@ -1 +1 @@', oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, patch: 'x'.repeat(patchChars) }] }
}

const DIFF: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'acme', repo: 'app', number: '7' },
  title: 'Stored',
  head: { sha: 'h1', ref: 'feature' },
  base: { sha: 'b1', ref: 'main' },
  files: [file('src/small.ts', 10), file('src/huge.ts', FILE_PATCH_BUDGET)],
}

async function call(name: string, args: unknown): Promise<{ status: number, body: any }> {
  const response = await handleNativeRequest({ url: new URL('http://localhost/api/rpc'), method: 'POST', json: async () => ({ name, args }) }, repo)
  return { status: response!.status, body: await response!.json() }
}

const github = vi.fn<typeof fetch>()

beforeAll(async () => {
  repo = mkdtempSync(join(tmpdir(), 'pulls-review-native-'))
  execFileSync('git', ['init', '-q', '-b', 'main'], { cwd: repo })
  await (await serverSession(repo)).cache.diffs.putDiff(KEY, DIFF, 'h1', { viewed: false })
})

beforeEach(() => {
  vi.stubEnv('PULLS_REVIEW_GITHUB_TOKEN', 'test-token')
  github.mockReset()
  vi.stubGlobal('fetch', github)
})

afterAll(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  rmSync(repo, { recursive: true, force: true })
})

describe('github-pr', () => {
  it('answers the stored diff after one head request, trimmed when lite', async () => {
    // Stored ahead of time, not yet opened: neither recently viewed nor viewed in its repository.
    expect((await call('recent-diffs', {})).body.result).toEqual([])
    expect((await call('repo-viewed', { owner: 'acme', repo: 'app' })).body.result).toEqual([])

    github.mockImplementation(async () => Response.json({ head: { sha: 'h1' } }))
    const { status, body } = await call('github-pr', { owner: 'acme', repo: 'app', number: 7, lite: true })

    expect(status).toBe(200)
    expect(github).toHaveBeenCalledOnce()
    expect(String(github.mock.calls[0]![0])).toBe('https://api.github.com/repos/acme/app/pulls/7')
    expect(body.result).toMatchObject({ key: KEY, omitted: 1, diff: { title: 'Stored', head: { sha: 'h1' } } })
    expect(body.result.stale).toBeUndefined()
    expect(body.result.diff.files.map((f: FileChange & { patchOmitted?: true }) => [f.path, f.hunks.length, f.patchOmitted ?? false])).toEqual([['src/small.ts', 1, false], ['src/huge.ts', 0, true]])
    expect(body.result.groups.flatMap((g: { filePaths: string[] }) => g.filePaths).sort()).toEqual(['src/huge.ts', 'src/small.ts'])
    // Opening counts as a view.
    expect((await call('recent-diffs', {})).body.result.map((d: { key: string }) => d.key)).toEqual([KEY])
    expect((await call('repo-viewed', { owner: 'acme', repo: 'app' })).body.result).toMatchObject([{ number: 7, files: 2 }])
  })

  it('answers the full stored diff without lite, and marks it stale when GitHub is unreachable', async () => {
    github.mockRejectedValue(new TypeError('fetch failed'))
    const { body } = await call('github-pr', { owner: 'acme', repo: 'app', number: '7' })

    expect(body.result).toMatchObject({ key: KEY, omitted: 0, stale: true })
    expect(body.result.diff.files.map((f: FileChange) => f.hunks.length)).toEqual([1, 1])
  })
})

describe('diff-patches', () => {
  it('answers the stored hunks of the named files, leaving out unknown paths', async () => {
    const { status, body } = await call('diff-patches', { key: KEY, paths: ['src/huge.ts', 'nope.ts'] })
    expect(status).toBe(200)
    expect(Object.keys(body.result.files)).toEqual(['src/huge.ts'])
    expect(body.result.files['src/huge.ts']).toEqual({ hunks: DIFF.files[1]!.hunks, sha: 'sha-src/huge.ts' })
  })

  it('refuses a diff that was never opened, and more than 100 paths', async () => {
    expect(await call('diff-patches', { key: 'github:acme/app#8', paths: ['a.ts'] })).toEqual({ status: 400, body: { error: 'Open the diff first.' } })
    expect((await call('diff-patches', { key: KEY, paths: Array.from({ length: 101 }, (_, i) => `f${i}.ts`) })).status).toBe(400)
  })
})

describe('pr-overview cached', () => {
  it('answers null before an overview is stored, then the stored one with when it was fetched', async () => {
    expect(await call('pr-overview', { owner: 'acme', repo: 'app', number: 9, cached: true })).toEqual({ status: 200, body: { result: null } })

    const overview = { id: 'PR_9', number: 9, title: 'T', body: '', state: 'open', mergeable: 'mergeable', mergeStateStatus: 'clean', updatedAt: 'u1', head: { oid: 'h9', ref: 'feature' }, base: { oid: 'b9', ref: 'main' }, commits: [], commitsHidden: 0, viewer: { canPush: true }, merge: { methods: ['merge'], defaultMethod: 'merge', mergeTitle: 'PR_TITLE', mergeMessage: 'PR_BODY', squashTitle: 'PR_TITLE', squashMessage: 'PR_BODY' } }
    await storeOverview((await serverSession(repo)).storage, { owner: 'acme', repo: 'app', number: 9 }, overview as never, 4242)
    const { body } = await call('pr-overview', { owner: 'acme', repo: 'app', number: 9, cached: true })
    expect(body.result).toMatchObject({ overview: { id: 'PR_9' }, mergeability: { allowed: true }, defaultCommits: { merge: {} }, storedAt: 4242 })
    expect(github).not.toHaveBeenCalled()
  })
})
