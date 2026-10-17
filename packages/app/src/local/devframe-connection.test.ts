import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectDevframeLocal } from './devframe-connection'

const mocks = vi.hoisted(() => ({ connect: vi.fn(), otp: vi.fn() }))
vi.mock('devframe/client', () => ({ connectDevframe: mocks.connect, consumeOtpFromUrl: mocks.otp }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function connection(token?: string) {
  const listeners = new Set<(status: string) => void>()
  const emit = (status: string) => listeners.forEach(listener => listener(status))
  const call = vi.fn().mockResolvedValue('result')
  const value = {
    transport: 'websocket',
    connection: { authToken: token, metaBaseUrl: 'http://localhost/__connection.json', connectionMeta: { backend: 'websocket' } },
    ensureTrusted: vi.fn().mockResolvedValue(true),
    requestAuthCode: vi.fn().mockResolvedValue(undefined),
    requestTrustWithCode: vi.fn().mockImplementation(async () => {
      value.connection.authToken = 'issued-token'
      return true
    }),
    events: {
      on: vi.fn((_event, listener) => {
        listeners.add(listener)
        return () => listeners.delete(listener)
      }),
    },
    scope: vi.fn(() => ({ rpc: { call } })),
    close: vi.fn(() => emit('disconnected')),
  }
  return { value, call, emit }
}
afterEach(() => {
  vi.resetAllMocks()
  vi.useRealTimers()
})

describe('devframe authenticated upgrade', () => {
  it('waits for a token-bearing replacement and redirects previously captured RPC references', async () => {
    const first = connection()
    const next = connection('issued-token')
    const trusted = deferred<boolean>()
    next.value.ensureTrusted.mockReturnValue(trusted.promise)
    mocks.connect.mockResolvedValueOnce(first.value).mockResolvedValueOnce(next.value)
    const { client, rpc } = await connectDevframeLocal()
    const status = vi.fn()
    client.events.on('connection:status', status)
    let settled = false
    const exchanged = client.requestTrustWithCode('123456').then((value) => {
      settled = true
      return value
    })
    await vi.waitFor(() => expect(next.value.ensureTrusted).toHaveBeenCalled())
    expect(settled).toBe(false)
    const pendingCall = rpc.call('cache-diff', { op: 'putDiff' })
    expect(first.call).not.toHaveBeenCalled()
    trusted.resolve(true)
    expect(await exchanged).toBe(true)
    expect(await pendingCall).toBe('result')
    expect(mocks.connect.mock.calls[1][0]).toMatchObject({ authToken: 'issued-token', connection: first.value.connection })
    expect(first.value.close).toHaveBeenCalledOnce()
    expect(status).not.toHaveBeenCalled()
    expect(next.call).toHaveBeenCalledWith('cache-diff', { op: 'putDiff' })
    next.emit('disconnected')
    expect(status).toHaveBeenCalledWith('disconnected')
  })

  it('keeps returning trusted sockets and invalid codes on their original connection', async () => {
    const first = connection('stored-token')
    first.value.requestTrustWithCode.mockResolvedValue(false)
    mocks.connect.mockResolvedValue(first.value)
    const { client, rpc } = await connectDevframeLocal()
    expect(await client.ensureTrusted(2000)).toBe(true)
    expect(await client.requestTrustWithCode('wrong')).toBe(false)
    expect(await rpc.call('github-authenticated')).toBe('result')
    expect(mocks.connect).toHaveBeenCalledOnce()
    expect(first.value.close).not.toHaveBeenCalled()
  })

  it('includes fragment-code bootstrap in the authenticated upgrade', async () => {
    const first = connection()
    const next = connection('issued-token')
    mocks.otp.mockReturnValue('123456')
    mocks.connect.mockResolvedValueOnce(first.value).mockResolvedValueOnce(next.value)
    const { client, rpc } = await connectDevframeLocal()
    expect(await client.ensureTrusted()).toBe(true)
    await rpc.call('cache-diff', { op: 'get' })
    expect(first.value.requestTrustWithCode).toHaveBeenCalledWith('123456')
    expect(mocks.connect.mock.calls[0][0]).toEqual({ simpleAuth: false, otpParam: false })
    expect(next.call).toHaveBeenCalledOnce()
    expect(first.call).not.toHaveBeenCalled()
  })

  it('reconnects a token received after initial connection before application calls', async () => {
    const first = connection()
    const next = connection('broadcast-token')
    mocks.connect.mockResolvedValueOnce(first.value).mockResolvedValueOnce(next.value)
    const { client, rpc } = await connectDevframeLocal()
    first.value.connection.authToken = 'broadcast-token'
    expect(await client.ensureTrusted()).toBe(true)
    await rpc.call('cache-diff', { op: 'get' })
    expect(mocks.connect.mock.calls[1][0].authToken).toBe('broadcast-token')
    expect(next.call).toHaveBeenCalledOnce()
  })

  it('does not report trust or use the small socket if replacement authentication fails', async () => {
    const first = connection()
    const next = connection('issued-token')
    next.value.ensureTrusted.mockRejectedValue(new Error('replacement timed out'))
    mocks.connect.mockResolvedValueOnce(first.value).mockResolvedValueOnce(next.value)
    const { client } = await connectDevframeLocal()
    await expect(client.requestTrustWithCode('123456')).rejects.toThrow('replacement timed out')
    expect(next.value.close).toHaveBeenCalledOnce()
    expect(first.value.close).not.toHaveBeenCalled()
    expect(first.call).not.toHaveBeenCalled()
  })
})

it('bounds a stalled URL-code bootstrap and lets a successful manual retry continue', async () => {
  vi.useFakeTimers()
  const first = connection()
  const next = connection('issued-token')
  const stalled = deferred<boolean>()
  first.value.requestTrustWithCode.mockReturnValueOnce(stalled.promise)
  mocks.otp.mockReturnValue('stalled-code')
  mocks.connect.mockResolvedValueOnce(first.value).mockResolvedValueOnce(next.value)
  const { client, rpc } = await connectDevframeLocal()
  const trusted = client.ensureTrusted(2000)
  const rejected = expect(trusted).rejects.toThrow('Timeout')
  await vi.advanceTimersByTimeAsync(2000)
  await rejected
  expect(await client.requestTrustWithCode('123456')).toBe(true)
  expect(await rpc.call('cache-diff', { op: 'get' })).toBe('result')
  expect(next.call).toHaveBeenCalledOnce()
})
