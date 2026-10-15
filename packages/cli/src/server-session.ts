import type { CacheRepositories } from '@pulls.review/core/cache'
import type { Storage } from 'unstorage'
import type { TaskManager } from './ai/tasks'
import process from 'node:process'
import { createCacheRepositories, SERVER_CACHE_BUDGET } from '@pulls.review/core/cache'
import { createStorage } from 'unstorage'
import { createTaskManager, defaultTaskRunners } from './ai/tasks'
import { getLens } from './lenses'
import { setMyPullsStore } from './my-pulls'
import { localRpcFunctions } from './rpc'
import { createRepoCacheDriver } from './storage'

export interface ServerSession {
  functions: ReturnType<typeof localRpcFunctions>
  cache: CacheRepositories
  tasks: TaskManager
  /** The storage under `cache`, for collections of the server's own (`jev-pr:`, `jev-file:`, `pr-overview:`). */
  storage: Storage
}

let session: Promise<ServerSession> | undefined

/**
 * One session for this process. The browser socket and the phone API share the task
 * manager and the cache, so a run started on the phone is the run the page follows.
 * @param cwd - The repository the server was started in.
 */
/**
 * @cc [owner:OctavianTocan,label:architecture] server-session-shares-state
 * RPCs, native requests, tasks and preanalysis MUST share this session's storage and
 * cache coordinator (server-ai-work-outlives-the-page; review finding 6, 15 Oct 2026).
 */
export function serverSession(cwd: string): Promise<ServerSession> {
  return session ??= createRepoCacheDriver(cwd).then((driver) => {
    const storage = createStorage({ driver })
    const cache = createCacheRepositories(storage, SERVER_CACHE_BUDGET)
    const tasks = createTaskManager({ cache, storage, runners: defaultTaskRunners(getLens) })
    setMyPullsStore({ storage, diffs: cache.diffs })
    return {
      functions: localRpcFunctions({ cwd, driver, storage, cache, tasks, env: process.env }),
      cache,
      tasks,
      storage,
    }
  })
}

/** The RPC functions, for the browser socket. */
export async function serverFunctions(cwd: string) {
  return (await serverSession(cwd)).functions
}
