import type { CacheRepositories } from '@pulls.review/core/cache'
import type { Storage } from 'unstorage'
import type { ServerHost } from './host'
import process from 'node:process'
import { createCacheRepositories, SERVER_CACHE_BUDGET } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import { setMyPullsStore } from './my-pulls'
import { localRpcFunctions } from './rpc'

export interface ServerSession {
  functions: ReturnType<typeof localRpcFunctions>
  cache: CacheRepositories
  /** The storage under `cache`, for collections of the server's own (`jev-pr:`, `jev-file:`, `pr-overview:`). */
  storage: Storage
}

const sessions = new WeakMap<ServerHost, Promise<ServerSession>>()

/**
 * One session per host. The browser socket and the phone API share the task manager and the
 * cache, so a run started on the phone is the run the page follows.
 * @param host - Where the server runs (`nodeHost` in the CLI).
 */
export function serverSession(host: ServerHost): Promise<ServerSession> {
  let session = sessions.get(host)
  if (!session) {
    const storage = createStorage({ driver: host.storage })
    const cache = createCacheRepositories(storage, SERVER_CACHE_BUDGET)
    setMyPullsStore({ storage, diffs: cache.diffs })
    session = Promise.resolve({
      functions: localRpcFunctions({ cwd: host.cwd, driver: host.storage, env: process.env }),
      cache,
      storage,
    })
    sessions.set(host, session)
  }
  return session
}

/** The RPC functions, for the browser socket. */
export async function serverFunctions(host: ServerHost) {
  return (await serverSession(host)).functions
}
