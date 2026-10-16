import type { Driver } from 'unstorage'

/** A file the host serves as it is, such as the Android updater's APK. */
export interface HostFile {
  body: ReadableStream<Uint8Array>
  size: number
}

/**
 * What the server needs from the machine it runs on (`plans/12-hosted-server.md`). The phone's
 * API and the server session reach storage, the checkout and served files only through this, so
 * the same handlers run in the CLI on a computer (`nodeHost`) and, later, on a hosted server.
 * This module MUST NOT import a Node builtin.
 */
export interface ServerHost {
  /** The server's store: the fs driver under the checkout's git folder on a computer. */
  storage: Driver
  /**
   * The checkout local diffs come from. A server without one (a hosted one) opens only GitHub
   * sources, and every local route answers `NO_CHECKOUT`.
   */
  cwd?: string
  /**
   * The Android updater's files by name (`latest.json`, `pulls.apk`, `past/<version>.apk`,
   * `past/index.json`). Undefined when the file is missing; without it every one is missing.
   */
  androidFile?: (name: string) => Promise<HostFile | undefined>
}

export const NO_CHECKOUT = 'This server has no checkout, so it opens GitHub pull requests only.'

/** The host's checkout, or `NO_CHECKOUT` thrown. */
export function checkoutOf(host: Pick<ServerHost, 'cwd'>): string {
  if (!host.cwd)
    throw new Error(NO_CHECKOUT)
  return host.cwd
}
