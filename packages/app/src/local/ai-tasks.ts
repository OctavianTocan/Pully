import type { ResolvedModel } from '@pulls.review/core/analyze'
import type { AiActivity, AiAskThread, AiTaskModel, AiTaskRequest, AiTaskSnapshot, LlmEngine } from '@pulls.review/core/local-rpc'
import type { LocalRpc } from './connection'
import { LLM_ENGINES, LOCAL_RPC } from '@pulls.review/core/local-rpc'

export interface FollowTaskOptions {
  /** Aborting stops following; the task keeps running on the server. */
  signal?: AbortSignal
  /** Receives entries changed since the last poll. */
  onActivity?: (activities: AiActivity[]) => void
}

function isConnectionLoss(error: unknown): boolean {
  return (error as { name?: unknown })?.name === 'DevframeConnectionError' && (error as { kind?: unknown }).kind === 'connection'
}

/**
 * Waits until `signal` aborts, never resolving otherwise.
 * @param signal - The follower's signal.
 * @returns A promise that rejects with an `AbortError` once `signal` aborts.
 */
function untilAborted(signal: AbortSignal | undefined): Promise<never> {
  return new Promise((_resolve, reject) => {
    signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  })
}

/**
 * Follows a server task until it settles. A dropped connection is not a failure: the
 * task keeps running on the server and the page reconnects by reloading (see `install.ts`),
 * so following just pauses until then.
 * @param rpc - The local server connection.
 * @param id - The task.
 * @param options - Abort signal and work-log listener.
 * @returns The settled task.
 */
export async function followAiTask(rpc: LocalRpc, id: string, options: FollowTaskOptions = {}): Promise<AiTaskSnapshot> {
  let after = 0
  for (;;) {
    options.signal?.throwIfAborted()
    let snapshot: AiTaskSnapshot
    try {
      snapshot = await rpc.call(LOCAL_RPC.aiTaskWait, { id, after }) as AiTaskSnapshot
    }
    catch (error) {
      if (isConnectionLoss(error))
        return untilAborted(options.signal)
      throw error
    }
    options.signal?.throwIfAborted()
    after = snapshot.rev
    if (snapshot.activities.length)
      options.onActivity?.(snapshot.activities)
    if (snapshot.status !== 'running')
      return snapshot
  }
}

/**
 * Starts a task on the server.
 * @param rpc - The local server connection.
 * @param request - What to run and where to save it.
 * @returns The task's id; a running task doing the same work is reused.
 */
export async function startAiTask(rpc: LocalRpc, request: AiTaskRequest): Promise<string> {
  return String(await rpc.call(LOCAL_RPC.aiTaskStart, request))
}

/**
 * Stops a running task and its CLI.
 * @param rpc - The local server connection.
 * @param id - The task.
 */
export async function cancelAiTask(rpc: LocalRpc, id: string): Promise<void> {
  await rpc.call(LOCAL_RPC.aiTaskCancel, { id }).catch(() => {})
}

/**
 * One diff's tasks.
 * @param rpc - The local server connection.
 * @param key - The diff's cache key.
 * @returns Running and recently finished tasks, newest first, without work logs.
 */
export async function listAiTasks(rpc: LocalRpc, key: string): Promise<AiTaskSnapshot[]> {
  return await rpc.call(LOCAL_RPC.aiTaskList, { key }) as AiTaskSnapshot[]
}

/**
 * One diff's saved question threads.
 * @param rpc - The local server connection.
 * @param key - The diff's cache key.
 * @returns Oldest first.
 */
export async function listAskThreads(rpc: LocalRpc, key: string): Promise<AiAskThread[]> {
  return await rpc.call(LOCAL_RPC.askThreads, { key }) as AiAskThread[]
}

/**
 * Drops a question thread on the server and stops its running answer.
 * @param rpc - The local server connection.
 * @param key - The diff's cache key.
 * @param threadId - The thread.
 */
export async function closeAskThread(rpc: LocalRpc, key: string, threadId: string): Promise<void> {
  await rpc.call(LOCAL_RPC.askThreadClose, { key, threadId })
}

/**
 * The model part of a task request.
 * @param resolved - The model the settings resolved to.
 * @returns Engine, model and effort; `undefined` when the provider is not a server CLI.
 */
export function taskModelOf(resolved: ResolvedModel | undefined): AiTaskModel | undefined {
  const engine = resolved?.model.provider
  if (!resolved || !(LLM_ENGINES as readonly string[]).includes(engine ?? ''))
    return undefined
  return { engine: engine as LlmEngine, model: resolved.model.id || undefined, effort: resolved.effort }
}
