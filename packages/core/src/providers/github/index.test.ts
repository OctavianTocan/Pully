import { afterEach, describe, expect, it, vi } from 'vitest'
import { createGithubPullRequestSource } from './index'

const pr = { title: 'PR', body: '', user: null, base: { ref: 'main', sha: 'base' }, head: { ref: 'feat', sha: 'head' }, created_at: '', updated_at: '', html_url: 'https://github.com/o/r/pull/1', state: 'open', draft: false, merged: false }

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('github pull request source', () => {
  it('knows its cache key without a request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    expect(await createGithubPullRequestSource({ owner: 'o', repo: 'r', number: '1' }).key()).toBe('github:o/r#1')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('fetches a payload carrying its ref, and fingerprints by head sha', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/pulls/1') ? pr : url.includes('/compare/') ? { merge_base_commit: { sha: 'ancestor' } } : []))))
    const source = createGithubPullRequestSource({ owner: 'o', repo: 'r', number: '1' })

    const diff = await source.fetch()

    expect(diff.ref).toEqual({ kind: 'github-pr', owner: 'o', repo: 'r', number: '1' })
    expect(await source.fingerprint!()).toBe('head')
    expect([source.reviews, source.sharing, source.viewer]).not.toContain(undefined)
  })
})

it('uses the captured PR merge base for old-side content and the same comparison for fallback', async () => {
  const requests: { path: string, accept: string }[] = []
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    const parsed = new URL(url)
    const path = parsed.pathname
    const accept = new Headers(init.headers).get('Accept') ?? ''
    requests.push({ path, accept })
    if (path.endsWith('/pulls/1'))
      return Response.json(pr)
    if (path.endsWith('/files'))
      return Response.json([{ filename: 'a.ts', status: 'modified', additions: 1, deletions: 1, sha: 'blob' }])
    if (path.endsWith('/commits'))
      return Response.json([])
    if (path.includes('/compare/')) {
      if (accept === 'application/vnd.github.diff')
        return Response.json({ message: 'too large' }, { status: 406 })
      return Response.json({ merge_base_commit: { sha: 'ancestor' } })
    }
    if (path.includes('/contents/'))
      return new Response(parsed.searchParams.get('ref') === 'ancestor' ? 'old\n' : 'new\n')
    throw new Error(`Unexpected request ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  const source = createGithubPullRequestSource({ owner: 'o', repo: 'r', number: '1' })
  const diff = await source.fetch()
  expect(diff.base).toEqual({ sha: 'ancestor', ref: 'main' })
  expect(diff.head).toEqual({ sha: 'head', ref: 'feat' })
  expect(diff.files[0]!.hunks[0]!.patch).toBe('-old\n+new')
  expect(requests.filter(request => request.path.includes('/compare/'))).toEqual([
    { path: '/repos/o/r/compare/base...head', accept: 'application/vnd.github+json' },
    { path: '/repos/o/r/compare/base...head', accept: 'application/vnd.github.diff' },
  ])
  expect(fetchMock.mock.calls.filter(([url]) => url.includes('/contents/')).map(([url]) => new URL(url).searchParams.get('ref')).sort()).toEqual(['ancestor', 'head'])
})
