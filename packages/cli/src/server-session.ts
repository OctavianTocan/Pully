import type { CacheRepositories } from '@pulls.review/core/cache'
import type { Storage } from 'unstorage'
import process from 'node:process'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import { setMyPullsStore } from './my-pulls'
import { localRpcFunctions } from './rpc'
import { createRepoCacheDriver } from './storage'

export interface ServerSession {
  functions: ReturnType<typeof localRpcFunctions>
  cache: CacheRepositories
  /** The storage under `cache`, for collections of the server's own (`jev-pr:`, `jev-file:`). */
  storage: Storage
}

let session: Promise<ServerSession> | undefined

/**
 * One session for this process. The browser socket and the phone API share the task
 * manager and the cache, so a run started on the phone is the run the page follows.
 * @param cwd - The repository the server was started in.
 */
export function serverSession(cwd: string): Promise<ServerSession> {
  return session ??= createRepoCacheDriver(cwd).then((driver) => {
    const storage = createStorage({ driver })
    const cache = createCacheRepositories(storage)
    setMyPullsStore({ storage, diffs: cache.diffs })
    return {
      functions: localRpcFunctions({ cwd, driver, env: process.env }),
      cache,
      storage,
    }
  })
}

/** The RPC functions, for the browser socket. */
export async function serverFunctions(cwd: string) {
  return (await serverSession(cwd)).functions
}
