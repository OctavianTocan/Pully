import type { CacheRepositories } from '@pulls.review/core/cache'
import process from 'node:process'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import { localRpcFunctions } from './rpc'
import { createRepoCacheDriver } from './storage'

export interface ServerSession {
  functions: ReturnType<typeof localRpcFunctions>
  cache: CacheRepositories
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
    return {
      functions: localRpcFunctions({ cwd, driver, env: process.env }),
      cache: createCacheRepositories(storage),
    }
  })
}

/** The RPC functions, for the browser socket. */
export async function serverFunctions(cwd: string) {
  return (await serverSession(cwd)).functions
}
