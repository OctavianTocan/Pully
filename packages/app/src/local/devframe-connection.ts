import type { DevframeRpcClient } from 'devframe/client'
import type { LocalClient, LocalRpc } from './connection'
import { LOCAL_DEVFRAME_ID } from '@pulls.review/core/local-rpc'
import { connectDevframe, consumeOtpFromUrl } from 'devframe/client'

/**
 * @cc [owner:OctavianTocan,label:security;architecture] web-devframe-trust-upgrades-socket
 * A WebSocket trusted after connection MUST reconnect with its issued token before
 * reporting successful trust or sending application RPCs. Scoped RPC references
 * and disconnect listeners MUST follow the replacement connection without treating
 * the intentional close as lost trust (R14 security finding 3, 16 Oct 2026).
 */
export async function connectDevframeLocal(): Promise<{ client: LocalClient, rpc: LocalRpc }> {
  // Own OTP exchange so its promise includes the new authenticated upgrade.
  const options = { simpleAuth: false, otpParam: false } as const
  let active = await connectDevframe(options)
  let upgradeToken = active.connection.authToken
  let upgrading: Promise<void> | undefined
  const listeners = new Set<(status: string) => void>()
  function listen(source: DevframeRpcClient) {
    return source.events.on('connection:status', (status) => {
      if (source === active) {
        for (const listener of listeners)
          listener(status)
      }
    })
  }
  let unlisten = listen(active)
  function upgrade(timeout = 10_000): Promise<void> {
    if (upgrading)
      return upgrading
    const token = active.connection.authToken
    if (active.transport !== 'websocket' || !token || token === upgradeToken)
      return Promise.resolve()
    const previous = active
    upgrading = (async () => {
      const next = await connectDevframe({ ...options, connection: previous.connection, authToken: token })
      try {
        if (!await next.ensureTrusted(timeout))
          throw new Error('The replacement browser connection was not trusted.')
      }
      catch (error) {
        next.close?.()
        throw error
      }
      unlisten()
      active = next
      upgradeToken = token
      unlisten = listen(next)
      previous.close?.()
    })().finally(() => { upgrading = undefined })
    return upgrading
  }
  let startup: Promise<unknown> = Promise.resolve()
  const client: LocalClient = {
    async ensureTrusted(timeout = 2000) {
      const ready = (async () => {
        await startup
        if (!await active.ensureTrusted(timeout))
          return false
        await upgrade(timeout)
        return true
      })()
      if (timeout <= 0)
        return ready
      let timer: ReturnType<typeof setTimeout> | undefined
      try {
        return await Promise.race([
          ready,
          new Promise<never>((_resolve, reject) => {
            timer = setTimeout(() => reject(new Error('[devframe] Timeout waiting for rpc to be trusted')), timeout)
          }),
        ])
      }
      finally {
        clearTimeout(timer)
      }
    },
    requestAuthCode: () => active.requestAuthCode(),
    async requestTrustWithCode(code) {
      if (!await active.requestTrustWithCode(code))
        return false
      await upgrade()
      // A successful explicit retry supersedes a still-pending URL-code exchange.
      startup = Promise.resolve()
      return true
    },
    events: {
      on(_event, listener) {
        listeners.add(listener)
        return () => listeners.delete(listener)
      },
    },
  }
  const code = consumeOtpFromUrl()
  if (code)
    startup = client.requestTrustWithCode(code).catch(() => false)
  // Like the HTTP adapter, expose only the application-used RPC call capability.
  // Resolve the scope per call so already captured references follow an upgrade.
  const rpc = {
    async call(name: string, ...args: unknown[]) {
      await startup
      await upgrade()
      return active.scope(LOCAL_DEVFRAME_ID).rpc.call(name, ...args)
    },
  } as LocalRpc
  return { client, rpc }
}
