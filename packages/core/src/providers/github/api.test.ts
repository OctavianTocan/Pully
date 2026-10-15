import { afterEach, describe, expect, it, vi } from 'vitest'
import { staticCredentials } from '../../types/source'
import { fetchDiffText, fetchFileContentAtRef, fetchFileContentWithin } from './api'
import { createGithubClient } from './client'

describe('fetchFileContentAtRef', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('requests the raw content at the given ref and path', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('file contents', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const content = await fetchFileContentAtRef(createGithubClient(staticCredentials('my-token')), 'owner', 'repo', 'src/foo.ts', 'abc123')

    expect(content).toBe('file contents')
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('https://api.github.com/repos/owner/repo/contents/src/foo.ts?ref=abc123')
    expect((init.headers as Record<string, string>).Accept).toBe('application/vnd.github.raw')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer my-token')
  })

  it('percent-encodes each path segment but keeps slashes', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('x', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await fetchFileContentAtRef(createGithubClient(), 'owner', 'repo', 'src/a b/c#d.ts', 'main')

    const [url] = fetchMock.mock.calls[0]!
    expect(url).toBe('https://api.github.com/repos/owner/repo/contents/src/a%20b/c%23d.ts?ref=main')
  })

  it('returns undefined for a 404 (file absent at this ref) instead of throwing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not found', { status: 404 })))

    await expect(fetchFileContentAtRef(createGithubClient(), 'owner', 'repo', 'new-file.ts', 'base-sha')).resolves.toBeUndefined()
  })

  it('throws on any other non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('nope', { status: 500 })))

    await expect(fetchFileContentAtRef(createGithubClient(), 'owner', 'repo', 'src/foo.ts', 'abc123')).rejects.toThrow(/GitHub API request failed \(500\)/)
  })
})

describe('fetchFileContentWithin', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns a file within the limit', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('héllo', { status: 200 })))

    await expect(fetchFileContentWithin(createGithubClient(), 'owner', 'repo', 'a.ts', 'sha', 6)).resolves.toBe('héllo')
  })

  it('returns null for a file whose stated length is over the limit, without reading it', async () => {
    const body = new ReadableStream({ pull: vi.fn() })
    const cancel = vi.spyOn(body, 'cancel')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200, headers: { 'content-length': '100' } })))

    await expect(fetchFileContentWithin(createGithubClient(), 'owner', 'repo', 'a.ts', 'sha', 10)).resolves.toBeNull()
    expect(cancel).toHaveBeenCalled()
  })

  it('stops reading once a streamed file passes the limit', async () => {
    let pulled = 0
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulled++
        controller.enqueue(new Uint8Array(4))
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })))

    await expect(fetchFileContentWithin(createGithubClient(), 'owner', 'repo', 'a.ts', 'sha', 10)).resolves.toBeNull()
    expect(pulled).toBeLessThan(10)
  })

  it('returns undefined for a 404', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('not found', { status: 404 })))

    await expect(fetchFileContentWithin(createGithubClient(), 'owner', 'repo', 'a.ts', 'sha', 10)).resolves.toBeUndefined()
  })
})

describe('fetchDiffText', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns undefined when GitHub refuses to render the diff (406)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('too_large', { status: 406 })))

    await expect(fetchDiffText(createGithubClient(), '/repos/owner/repo/pulls/1')).resolves.toBeUndefined()
  })

  it('still throws on other failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('nope', { status: 500 })))

    await expect(fetchDiffText(createGithubClient(), '/repos/owner/repo/pulls/1')).rejects.toThrow(/\(500\)/)
  })
})
