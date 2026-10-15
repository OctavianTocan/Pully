import type { GithubPullRequestFileJson, GithubPullRequestJson } from './api'
import { describe, expect, it, vi } from 'vitest'
import { MAX_SELF_DIFF_BYTES, MAX_SELF_DIFF_COST, normalizePullRequest } from './normalize'

const noFallbacks = { loadDiffText: async () => '', loadFileContent: async () => undefined }

const PR_JSON: GithubPullRequestJson = {
  title: 'Add feature',
  body: 'Some description',
  user: { login: 'antfu' },
  base: { ref: 'main', sha: 'base123' },
  head: { ref: 'feature', sha: 'head456' },
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-02T00:00:00Z',
  html_url: 'https://github.com/owner/repo/pull/1',
  state: 'open',
  draft: false,
  merged: false,
}

describe('normalizePullRequest', () => {
  it('normalizes metadata and a file with an inline patch', async () => {
    const files: GithubPullRequestFileJson[] = [
      {
        filename: 'src/foo.ts',
        status: 'modified',
        additions: 1,
        deletions: 1,
        sha: 'abc123',
        patch: '@@ -1,2 +1,2 @@\n context\n-old\n+new',
      },
    ]
    const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], noFallbacks)

    expect(diff).toMatchObject({
      ref: { kind: 'github-pr', owner: 'owner', repo: 'repo', number: '1' },
      title: 'Add feature',
      label: '#1',
      author: { name: 'antfu' },
      base: { ref: 'main' },
      head: { ref: 'feature' },
    })
    expect(diff.files).toHaveLength(1)
    expect(diff.files[0]).toMatchObject({ path: 'src/foo.ts', status: 'modified', sha: 'abc123', isBinary: false })
    expect(diff.files[0]!.hunks).toHaveLength(1)
  })

  it('keeps each commit\'s sha and full message', async () => {
    const commits = [{ sha: 'c1', commit: { message: 'feat: add x\n\nbody' } }]
    const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, [], commits, noFallbacks)
    expect(diff.commits).toEqual([{ sha: 'c1', message: 'feat: add x\n\nbody' }])
  })

  it('marks a renamed file with previousPath', async () => {
    const files: GithubPullRequestFileJson[] = [
      {
        filename: 'src/new-name.ts',
        previous_filename: 'src/old-name.ts',
        status: 'renamed',
        additions: 0,
        deletions: 0,
        sha: 'def456',
        patch: undefined,
      },
    ]
    const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], noFallbacks)
    expect(diff.files[0]).toMatchObject({ status: 'renamed', previousPath: 'src/old-name.ts', path: 'src/new-name.ts' })
  })

  it('falls back to the raw diff text when `patch` is omitted (large diff)', async () => {
    const files: GithubPullRequestFileJson[] = [
      {
        filename: 'src/huge.ts',
        status: 'modified',
        additions: 500,
        deletions: 10,
        sha: 'ghi789',
      },
    ]
    const fallbackText = 'diff --git a/src/huge.ts b/src/huge.ts\n'
      + 'index 0000000..ghi789 100644\n'
      + '--- a/src/huge.ts\n'
      + '+++ b/src/huge.ts\n'
      + '@@ -1,1 +1,1 @@\n'
      + '-old\n'
      + '+new\n'
    const loadFallback = vi.fn(async () => fallbackText)
    const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...noFallbacks, loadDiffText: loadFallback })

    expect(loadFallback).toHaveBeenCalledOnce()
    expect(diff.files[0]).toMatchObject({ path: 'src/huge.ts', sha: 'ghi789', isBinary: false, additions: 500, deletions: 10 })
    expect(diff.files[0]!.hunks).toHaveLength(1)
  })

  it('detects a binary file via the fallback diff text', async () => {
    const files: GithubPullRequestFileJson[] = [
      {
        filename: 'assets/logo.png',
        status: 'modified',
        additions: 0,
        deletions: 0,
        sha: 'jkl012',
      },
    ]
    const fallbackText = 'diff --git a/assets/logo.png b/assets/logo.png\n'
      + 'index aaa..jkl012 100644\n'
      + 'Binary files a/assets/logo.png and b/assets/logo.png differ\n'
    const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...noFallbacks, loadDiffText: async () => fallbackText })

    expect(diff.files[0]).toMatchObject({ path: 'assets/logo.png', isBinary: true })
  })

  describe('when GitHub refuses to render the whole-PR diff', () => {
    const tooLarge = { loadDiffText: async () => undefined }

    it('diffs the base and head file contents itself', async () => {
      const files: GithubPullRequestFileJson[] = [
        { filename: 'src/huge.ts', status: 'modified', additions: 1, deletions: 1, sha: 'ghi789' },
      ]
      const loadFileContent = vi.fn(async (_path: string, ref: string) => ref === 'base123' ? 'a\nold\nc\n' : 'a\nnew\nc\n')
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(diff.files[0]).toMatchObject({ path: 'src/huge.ts', isBinary: false, additions: 1, deletions: 1 })
      expect(diff.files[0]!.hunks).toHaveLength(1)
      expect(diff.files[0]!.hunks[0]!.patch).toContain('-old\n+new')
    })

    it('reads a renamed file\'s old side from its previous path and treats an added file as empty before', async () => {
      const files: GithubPullRequestFileJson[] = [
        { filename: 'new.ts', previous_filename: 'old.ts', status: 'renamed', additions: 1, deletions: 1, sha: 'r1' },
        { filename: 'added.ts', status: 'added', additions: 2, deletions: 0, sha: 'a1' },
      ]
      const loadFileContent = vi.fn(async (path: string) => path === 'old.ts' ? 'x\n' : path === 'new.ts' ? 'y\n' : 'l1\nl2\n')
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(loadFileContent).toHaveBeenCalledWith('old.ts', 'base123', MAX_SELF_DIFF_BYTES)
      expect(loadFileContent).not.toHaveBeenCalledWith('added.ts', 'base123', MAX_SELF_DIFF_BYTES)
      expect(diff.files[1]!.hunks[0]).toMatchObject({ oldStart: 0, oldLines: 0, newStart: 1, newLines: 2 })
    })

    it('keeps a file with no changed lines as binary without fetching content', async () => {
      const files: GithubPullRequestFileJson[] = [
        { filename: 'logo.png', status: 'modified', additions: 0, deletions: 0, sha: 'p1' },
      ]
      const loadFileContent = vi.fn(async () => 'binary')
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(diff.files[0]).toMatchObject({ isBinary: true, hunks: [] })
      expect(loadFileContent).not.toHaveBeenCalled()
    })

    it('marks a file with too many changed lines as too large without fetching content', async () => {
      // The generated 10 MB file that froze the server: ~92k changed lines.
      const files: GithubPullRequestFileJson[] = [
        { filename: 'gen.dylib.yml', status: 'modified', additions: 49_000, deletions: 42_000, sha: 'g1' },
      ]
      const loadFileContent = vi.fn(async () => 'x\n')
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(diff.files[0]).toEqual(expect.objectContaining({ isBinary: false, additions: 49_000, deletions: 42_000, hunks: [], truncated: true }))
      expect(loadFileContent).not.toHaveBeenCalled()
    })

    it('marks a file as too large when either version is past the size limit, asking for no more than it', async () => {
      const files: GithubPullRequestFileJson[] = [
        { filename: 'big.json', status: 'modified', additions: 1, deletions: 1, sha: 'b1' },
      ]
      const loadFileContent = vi.fn(async (_path: string, ref: string) => ref === 'base123' ? 'a\n' : null)
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(loadFileContent).toHaveBeenCalledWith('big.json', 'head456', MAX_SELF_DIFF_BYTES)
      expect(diff.files[0]).toMatchObject({ hunks: [], truncated: true })
    })

    it('diffs only a few files at a time', async () => {
      const files: GithubPullRequestFileJson[] = Array.from({ length: 12 }, (_, i) => ({ filename: `f${i}.ts`, status: 'modified', additions: 1, deletions: 1, sha: `s${i}` }))
      let inFlight = 0
      let most = 0
      const loadFileContent = async (_path: string, ref: string) => {
        most = Math.max(most, ++inFlight)
        await new Promise(resolve => setTimeout(resolve, 5))
        inFlight--
        return ref === 'base123' ? 'old\n' : 'new\n'
      }
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(diff.files.every(f => f.hunks.length === 1)).toBe(true)
      // Two loads (base and head) per file in flight.
      expect(most).toBeLessThanOrEqual(8)
    })

    it('marks a file as too large when its lines times its changed lines would take too long to diff', async () => {
      const files: GithubPullRequestFileJson[] = [
        { filename: 'long.txt', status: 'modified', additions: 1_000, deletions: 1_000, sha: 'l1' },
      ]
      const lines = MAX_SELF_DIFF_COST / 2_000 / 2 + 1
      const loadFileContent = vi.fn(async () => 'x\n'.repeat(lines))
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })

      expect(diff.files[0]).toMatchObject({ hunks: [], truncated: true })
    })

    it('diffs a large file without blocking the event loop', async () => {
      const lines = Array.from({ length: 5_000 }, (_, i) => `line ${i}`)
      const changed = lines.map((line, i) => i % 50 === 0 ? `changed ${i}` : line)
      const files: GithubPullRequestFileJson[] = [
        { filename: 'big.txt', status: 'modified', additions: 100, deletions: 100, sha: 'k1' },
      ]
      const loadFileContent = async (_path: string, ref: string) => (ref === 'base123' ? lines : changed).join('\n')
      let ticks = 0
      const timer = setInterval(() => ticks++, 0)
      const diff = await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...tooLarge, loadFileContent })
      clearInterval(timer)

      expect(diff.files[0]!.truncated).toBeUndefined()
      expect(diff.files[0]!.hunks.reduce((n, h) => n + h.patch.split('\n').filter(l => l.startsWith('+')).length, 0)).toBe(100)
      expect(ticks).toBeGreaterThan(0)
    })
  })

  it.each([
    [{ state: 'open' as const, draft: false, merged: false }, 'open'],
    [{ state: 'open' as const, draft: true, merged: false }, 'draft'],
    [{ state: 'closed' as const, draft: false, merged: false }, 'closed'],
    [{ state: 'closed' as const, draft: false, merged: true }, 'merged'],
  ])('resolves pullRequest.state from %o to %s', async (overrides, expected) => {
    const diff = await normalizePullRequest('owner', 'repo', '1', { ...PR_JSON, ...overrides }, [], [], noFallbacks)
    expect(diff.pullRequest?.state).toBe(expected)
  })

  it('only fetches the fallback diff text once even with multiple omitted-patch files', async () => {
    const files: GithubPullRequestFileJson[] = [
      { filename: 'a.ts', status: 'modified', additions: 1, deletions: 0, sha: 'a1' },
      { filename: 'b.ts', status: 'modified', additions: 1, deletions: 0, sha: 'b1' },
    ]
    const fallbackText = [
      'diff --git a/a.ts b/a.ts\nindex 0..a1 100644\n--- a/a.ts\n+++ b/a.ts\n@@ -0,0 +1,1 @@\n+a\n',
      'diff --git a/b.ts b/b.ts\nindex 0..b1 100644\n--- a/b.ts\n+++ b/b.ts\n@@ -0,0 +1,1 @@\n+b\n',
    ].join('')
    const loadFallback = vi.fn(async () => fallbackText)
    await normalizePullRequest('owner', 'repo', '1', PR_JSON, files, [], { ...noFallbacks, loadDiffText: loadFallback })
    expect(loadFallback).toHaveBeenCalledOnce()
  })
})
