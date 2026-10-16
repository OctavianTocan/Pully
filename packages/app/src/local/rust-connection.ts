import type { LocalClient, LocalRpc } from './connection'

/**
 * HTTP-only adapter for the native Rust server. It preserves the existing local
 * RPC contract and terminal-code gate; no rendering or application state changes.
 */
/**
 * @cc [owner:OctavianTocan,label:product] rust-web-trust-loss-reconnects
 * A web-RPC 401 MUST notify connection listeners so an authenticated page reenters
 * the trust gate. An invalid code at the initial gate MUST NOT trigger this reload
 * path (R11 review finding 16, 16 Oct 2026).
 */
export async function connectRust(): Promise<{ client: LocalClient, rpc: LocalRpc } | undefined> {
  const endpoint = (path: string) => new URL(path, document.baseURI)
  const detected = await fetch(endpoint('api/backend'), { signal: AbortSignal.timeout(2000) })
    .then(response => response.ok ? response.json() : undefined)
    .catch(() => undefined)
  if (detected?.backend !== 'rust')
    return undefined

  const listeners = new Set<(status: string) => void>()
  async function request(path: string, body?: unknown): Promise<any> {
    let response: Response
    try {
      response = await fetch(endpoint(path), {
        method: body === undefined ? 'GET' : 'POST',
        credentials: 'same-origin',
        ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
      })
    }
    catch (error) {
      for (const listener of listeners)
        listener('disconnected')
      throw error
    }
    if (path === 'api/web-rpc' && response.status === 401) {
      for (const listener of listeners)
        listener('disconnected')
    }
    const payload = await response.json()
    if (!response.ok)
      throw new Error(payload.error ?? `Server request failed (${response.status})`)
    return payload
  }
  const rpc = {
    async call(name: string, ...args: unknown[]) {
      return (await request('api/web-rpc', { name, args: args[0] ?? null })).result
    },
  } as LocalRpc

  // Only the trust, event and scoped-RPC methods consumed by installLocal are
  // emulated; the Rust server does not pretend to be a devframe WebSocket hub.
  const client: LocalClient = {
    ensureTrusted: async () => !!(await request('api/session')).trusted,
    requestAuthCode: async () => { await request('api/auth-code', {}) },
    requestTrustWithCode: async (code: string) => {
      try {
        return !!(await request('api/session', { code })).trusted
      }
      catch { return false }
    },
    events: {
      on(_event: string, listener: (status: string) => void) {
        listeners.add(listener)
        return () => listeners.delete(listener)
      },
    },
  }
  return { client, rpc }
}
