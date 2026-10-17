import type { LOCAL_DEVFRAME_ID } from '@pulls.review/core/local-rpc'
import type { DevframeScopedClientRpc } from 'devframe/client'
import { connectDevframeLocal } from './devframe-connection'
import { connectRust } from './rust-connection'

export type LocalRpc = DevframeScopedClientRpc<typeof LOCAL_DEVFRAME_ID>

/** Only the transport capabilities consumed by the local application. */
export interface LocalClient {
  ensureTrusted: (timeout?: number) => Promise<boolean>
  requestAuthCode: () => Promise<unknown>
  requestTrustWithCode: (code: string) => Promise<boolean>
  events: {
    on: (event: 'connection:status', listener: (status: string) => void) => unknown
  }
}

/** The `pulls.review` server this `PR_LOCAL` build was served by (see `backend/`). */
export async function connectLocal(): Promise<{ client: LocalClient, rpc: LocalRpc }> {
  const rust = await connectRust()
  if (rust)
    return rust
  // Found relative to `document.baseURI`, the mount path the page's `<base>` names.
  return connectDevframeLocal()
}
