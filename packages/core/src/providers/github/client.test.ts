import { afterEach, describe, expect, it, vi } from 'vitest'
import { staticCredentials } from '../../types/source'
import { createGithubClient, GithubApiError, githubRoute, observeGithubCalls } from './client'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('createGithubClient', () => {
  it('sends the token only when there is one', async () => {
    const fetchMock = vi.fn(async (_url: string, _init: RequestInit) => json({}))
    vi.stubGlobal('fetch', fetchMock)

    await createGithubClient(staticCredentials('tok')).request('/user')
    await createGithubClient().request('/user')

    const [withToken, anonymous] = fetchMock.mock.calls.map(([, init]) => new Headers(init.headers))
    expect(withToken!.get('Authorization')).toBe('Bearer tok')
    expect(anonymous!.has('Authorization')).toBe(false)
  })

  it('follows pages until a short one, appending to an existing query', async () => {
    const fetchMock = vi.fn(async (url: string) => json(url.endsWith('&page=1') ? Array.from({ length: 100 }, (_, i) => i) : [100]))
    vi.stubGlobal('fetch', fetchMock)

    const items = await createGithubClient().paginate<number>('/repos/o/r/pulls/1/files?x=1')

    expect(items).toHaveLength(101)
    expect(fetchMock.mock.calls.map(call => call[0])).toEqual([
      'https://api.github.com/repos/o/r/pulls/1/files?x=1&per_page=100&page=1',
      'https://api.github.com/repos/o/r/pulls/1/files?x=1&per_page=100&page=2',
    ])
  })

  it('fetches the remaining pages together once the Link header names the last one', async () => {
    const link = '<https://api.github.com/repositories/1/pulls/1/files?per_page=100&page=2>; rel="next", <https://api.github.com/repositories/1/pulls/1/files?per_page=100&page=3>; rel="last"'
    let inFlight = 0
    let maxInFlight = 0
    const fetchMock = vi.fn(async (url: string) => {
      const page = Number(url.match(/page=(\d+)$/)![1])
      if (page === 1)
        return new Response(JSON.stringify(Array.from({ length: 100 }, (_, i) => i)), { headers: { Link: link } })
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise(resolve => setTimeout(resolve, page === 2 ? 20 : 5))
      inFlight--
      return json(page === 2 ? Array.from({ length: 100 }, (_, i) => 100 + i) : [200])
    })
    vi.stubGlobal('fetch', fetchMock)

    const items = await createGithubClient().paginate<number>('/repos/o/r/pulls/1/files')

    expect(items).toEqual(Array.from({ length: 201 }, (_, i) => i))
    expect(maxInFlight).toBe(2)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('surfaces GitHub\'s own error message with the status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: 'Resource not accessible' }, 403)))

    const failure = await createGithubClient(staticCredentials('tok')).request('/repos/o/r').catch((err: unknown) => err)

    expect(failure).toBeInstanceOf(GithubApiError)
    expect(failure).toMatchObject({ status: 403, message: 'Resource not accessible' })
  })
})

describe('observeGithubCalls', () => {
  afterEach(() => observeGithubCalls(undefined))

  it('reports the route shape, status and rate limit, never the repo', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 404, headers: { 'x-ratelimit-remaining': '4321' } })))
    const calls: unknown[] = []
    observeGithubCalls(call => calls.push(call))
    await createGithubClient().request('/repos/acme/app/pulls/7/files?page=2').catch(() => {})
    expect(calls).toEqual([{ route: 'pulls/:n/files', method: 'GET', status: 404, ms: expect.any(Number), rateLeft: 4321 }])
  })

  it('names routes without owners, numbers or SHAs', () => {
    expect(githubRoute('/repos/acme/app/commits/0123abc4567')).toBe('commits/:n')
    expect(githubRoute('/repos/acme/app')).toBe('repo')
    expect(githubRoute('/graphql')).toBe('graphql')
    expect(githubRoute('/search/issues?q=x')).toBe('search/issues')
    expect(githubRoute('/repos/acme/app/contents/src/secret.ts?ref=main')).toBe('contents/:n/:n')
    expect(githubRoute('/users/someone/repos')).toBe('users/:n/:n')
  })
})
