import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reloadAfterDisconnect } from './reconnect'
import { connectRust } from './rust-connection'

function json(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), { status })
}

const page = { baseURI: 'http://localhost/', visibilityState: 'visible', addEventListener: vi.fn() }

beforeEach(() => {
  vi.useFakeTimers()
  page.visibilityState = 'visible'
  page.addEventListener.mockClear()
  vi.stubGlobal('document', page)
  vi.stubGlobal('window', { addEventListener: vi.fn() })
  vi.stubGlobal('location', { reload: vi.fn() })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('rust browser trust lifecycle', () => {
  it('reloads into the trust gate when the first RPC after an idle restart returns 401', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ backend: 'rust' }))
      .mockResolvedValueOnce(json({ trusted: true }))
      .mockResolvedValueOnce(json({ error: 'Enter the server\'s terminal code first.' }, 401))
    vi.stubGlobal('fetch', fetch)
    const { client, rpc } = (await connectRust())!
    expect(await client.ensureTrusted()).toBe(true)
    reloadAfterDisconnect(client)

    await expect(rpc.call(LOCAL_RPC.githubAuthenticated)).rejects.toThrow('terminal code')
    expect(location.reload).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1500)
    expect(location.reload).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[2][0].pathname).toBe('/api/web-rpc')
  })

  it('keeps incorrect initial codes in the gate without scheduling a reload', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ backend: 'rust' }))
      .mockResolvedValueOnce(json({ trusted: false }))
      .mockResolvedValueOnce(json({ error: 'Invalid code.' }, 401))
      .mockResolvedValueOnce(json({ trusted: true }))
      .mockResolvedValueOnce(json({ error: 'Forbidden method.' }, 403))
    vi.stubGlobal('fetch', fetch)
    const { client, rpc } = (await connectRust())!
    reloadAfterDisconnect(client)

    expect(await client.ensureTrusted()).toBe(false)
    expect(await client.requestTrustWithCode('wrong')).toBe(false)
    expect(await client.requestTrustWithCode('correct')).toBe(true)
    await expect(rpc.call(LOCAL_RPC.githubAuthenticated)).rejects.toThrow('Forbidden method')
    await vi.advanceTimersByTimeAsync(1500)
    expect(location.reload).not.toHaveBeenCalled()
  })

  it.each(['headers', 'body'])('honors the caller trust deadline while waiting for %s', async (phase) => {
    let signal!: AbortSignal
    const blocked = () => new Promise<never>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    })
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ backend: 'rust' }))
      .mockImplementationOnce((_url, options) => {
        signal = options.signal
        return phase === 'headers' ? blocked() : Promise.resolve({ ok: true, status: 200, json: blocked })
      }))
    const { client } = (await connectRust())!
    const trusted = client.ensureTrusted(2000)
    const rejected = expect(trusted).rejects.toMatchObject({ name: 'TimeoutError' })
    await vi.advanceTimersByTimeAsync(1999)
    expect(signal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    await rejected
    expect(signal.aborted).toBe(true)
  })

  it('bounds other trust requests and leaves long-running RPCs independent', async () => {
    const signals: AbortSignal[] = []
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ backend: 'rust' }))
      .mockImplementation((_url, options) => new Promise((_resolve, reject) => {
        signals.push(options.signal)
        options.signal?.addEventListener('abort', () => reject(options.signal.reason), { once: true })
      }))
    vi.stubGlobal('fetch', fetch)
    const { client, rpc } = (await connectRust())!
    const authCode = expect(client.requestAuthCode()).rejects.toMatchObject({ name: 'TimeoutError' })
    const trustCode = client.requestTrustWithCode('code')
    void rpc.call(LOCAL_RPC.githubAuthenticated)
    await vi.advanceTimersByTimeAsync(10_000)
    await authCode
    expect(await trustCode).toBe(false)
    expect(signals.slice(0, 2).every(signal => signal.aborted)).toBe(true)
    expect(signals[2]).toBeUndefined()
  })

  it('clears a successful trust request deadline after reading its body', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({ backend: 'rust' })).mockResolvedValueOnce(json({ trusted: true }))
    vi.stubGlobal('fetch', fetch)
    const { client } = (await connectRust())!
    expect(await client.ensureTrusted(2000)).toBe(true)
    const signal = fetch.mock.calls[1][1].signal
    await vi.advanceTimersByTimeAsync(2000)
    expect(signal.aborted).toBe(false)
  })

  it('waits until a disconnected tab is visible before reloading', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ backend: 'rust' }))
      .mockResolvedValueOnce(new Response('Unauthorized', { status: 401 })))
    const { client, rpc } = (await connectRust())!
    page.visibilityState = 'hidden'
    reloadAfterDisconnect(client)

    // Trust loss must be reported even when an intermediary supplies non-JSON errors.
    await expect(rpc.call(LOCAL_RPC.githubAuthenticated)).rejects.toThrow()
    await vi.advanceTimersByTimeAsync(1500)
    expect(location.reload).not.toHaveBeenCalled()
    page.visibilityState = 'visible'
    page.addEventListener.mock.calls[0][1]()
    expect(location.reload).toHaveBeenCalledOnce()
  })
})
