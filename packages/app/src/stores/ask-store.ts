import type { AskSelection, AskTurn } from '@pulls.review/core/llm'
import type { AiAskThread } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { Ref } from 'vue'
import type { LocalRpc } from '../local/connection'
import type { AskThread, DiffsStoreAsk } from './types'
import { resolveAskModel } from '@pulls.review/core/analyze'
import { askAboutSelection, isCliEngine, selectionLabel } from '@pulls.review/core/llm'
import { serializeRef } from '@pulls.review/core/types'
import { computed, reactive, watch } from 'vue'
import { upsertActivities } from '../components/ai/ai-activity'
import { t } from '../i18n'
import { getAiRpc } from '../local/ai-rpc'
import { cancelAiTask, closeAskThread, followAiTask, listAskThreads, startAiTask, taskModelOf } from '../local/ai-tasks'
import { settings } from '../state/settings'
import { isAbortError, toError } from './run-errors'

export interface AskStoreOptions {
  /** The diff questions are about; threads are kept per diff. */
  diff: Ref<DiffsPayload | undefined>
}

// Module-level so a diff's threads survive leaving and reopening it within the page session.
const threadsByDiff = reactive(new Map<string, AskThread[]>())
const controllers = new Map<string, AbortController>()
/** Server tasks answering a thread, by thread id. */
const serverTasks = new Map<string, { rpc: LocalRpc, id: string }>()
/** Diffs whose saved threads this page has already read from the server. */
const loadedScopes = new Set<string>()
let nextThreadId = 0

function sameLines(a: AskSelection, b: AskSelection): boolean {
  return a.path === b.path && a.side === b.side && a.startLine === b.startLine && a.endLine === b.endLine
}

/** Unique across reloads, since the server keeps threads by id. */
function newThreadId(): string {
  return `ask-${Date.now().toString(36)}-${(++nextThreadId).toString(36)}`
}

/** A saved server thread as the view shows it; its last turn is still being answered when it has no outcome. */
function fromServer(saved: AiAskThread): AskThread {
  return {
    id: saved.id,
    selection: saved.selection,
    label: saved.label,
    turns: saved.turns.map(turn => ({
      question: turn.question,
      answer: turn.answer,
      error: turn.error,
      usage: turn.usage,
      activities: turn.activities ? upsertActivities([], turn.activities) : [],
      startedAt: turn.startedAt,
      outcome: turn.outcome,
    })),
    isAsking: saved.turns.length > 0 && saved.turns.at(-1)!.outcome === undefined,
  }
}

/**
 * Questions about selected lines of one diff, answered by the CLI engine the ask settings name.
 * On the local server each question is a task that outlives the page, and the threads are saved
 * there per diff, so a reload shows them again and picks up an answer still being written.
 * @param opts - The diff questions are about.
 * @returns The reactive ask store.
 */
export function createAskStore(opts: AskStoreOptions): DiffsStoreAsk {
  const { diff } = opts
  const scope = computed(() => diff.value && serializeRef(diff.value.ref))
  const threads = computed(() => (scope.value && threadsByDiff.get(scope.value)) || [])
  const available = computed(() => isCliEngine(resolveAskModel(settings.value.llm)?.model.provider ?? ''))

  function find(id: string): AskThread | undefined {
    return threads.value.find(thread => thread.id === id)
  }

  /** Follows the server task answering the last turn of `thread` until it settles. */
  async function followTurn(rpc: LocalRpc, thread: AskThread, taskId: string) {
    const turn = thread.turns.at(-1)!
    controllers.get(thread.id)?.abort()
    const controller = new AbortController()
    controllers.set(thread.id, controller)
    serverTasks.set(thread.id, { rpc, id: taskId })
    thread.isAsking = true
    try {
      const done = await followAiTask(rpc, taskId, {
        signal: controller.signal,
        onActivity: (incoming) => {
          turn.activities = upsertActivities(turn.activities ?? [], incoming)
        },
      })
      if (done.status === 'done') {
        turn.answer = done.result ?? ''
        turn.usage = done.usage
        turn.outcome = 'done'
      }
      else {
        const stopped = done.status === 'cancelled'
        turn.error = stopped ? t('ask.stopped') : done.error ?? t('ask.unavailable')
        turn.outcome = stopped ? 'stopped' : 'failed'
      }
    }
    catch (err) {
      if (!isAbortError(err)) {
        turn.error = toError(err).message
        turn.outcome = 'failed'
      }
    }
    finally {
      if (controllers.get(thread.id) === controller) {
        controllers.delete(thread.id)
        serverTasks.delete(thread.id)
        thread.isAsking = false
      }
    }
  }

  // Opening a diff reads its saved threads from the server and follows answers still being written.
  watch(scope, async (key) => {
    const rpc = getAiRpc()
    if (!key || !rpc || loadedScopes.has(key))
      return
    loadedScopes.add(key)
    const saved = await listAskThreads(rpc, key).catch(() => undefined)
    if (!saved)
      return
    const local = threadsByDiff.get(key) ?? []
    // Threads opened here but not asked in yet exist only on this page.
    const merged = [...saved.map(fromServer), ...local.filter(thread => !saved.some(candidate => candidate.id === thread.id))]
    threadsByDiff.set(key, merged)
    const current = threadsByDiff.get(key)!
    for (const savedThread of saved) {
      const last = savedThread.turns.at(-1)
      const thread = current.find(candidate => candidate.id === savedThread.id)
      if (thread && last?.taskId && last.outcome === undefined && !controllers.has(thread.id))
        void followTurn(rpc, thread, last.taskId)
    }
  }, { immediate: true })

  function open(selection: AskSelection): string {
    const key = scope.value
    if (!key)
      return ''
    const existing = threads.value.find(thread => sameLines(thread.selection, selection))
    if (existing)
      return existing.id
    const thread: AskThread = { id: newThreadId(), selection: { ...selection }, label: selectionLabel(selection), turns: [], isAsking: false }
    threadsByDiff.set(key, [...threads.value, thread])
    return thread.id
  }

  async function ask(id: string, question: string) {
    const thread = find(id)
    const loaded = diff.value
    const key = scope.value
    const text = question.trim()
    if (!thread || !loaded || !key || thread.isAsking || !text)
      return
    const history: AskTurn[] = thread.turns.flatMap(turn => turn.answer !== undefined ? [{ question: turn.question, answer: turn.answer }] : [])
    thread.turns.push({ question: text, activities: [], startedAt: Date.now() })
    const turn = thread.turns.at(-1)!
    const resolved = resolveAskModel(settings.value.llm)
    if (!resolved || !isCliEngine(resolved.model.provider)) {
      turn.error = t('ask.unavailable')
      turn.outcome = 'failed'
      return
    }

    // The local server answers as a task that outlives this page and saves the thread.
    const rpc = getAiRpc()
    const model = taskModelOf(resolved)
    if (rpc && model) {
      thread.isAsking = true
      let taskId: string
      try {
        taskId = await startAiTask(rpc, { kind: 'ask', key, model, threadId: thread.id, selection: thread.selection, question: text })
      }
      catch (err) {
        turn.error = toError(err).message
        turn.outcome = 'failed'
        thread.isAsking = false
        return
      }
      await followTurn(rpc, thread, taskId)
      return
    }

    const controller = new AbortController()
    controllers.set(id, controller)
    thread.isAsking = true
    try {
      const answer = await askAboutSelection(loaded, resolved, { selection: thread.selection, question: text, history }, {
        signal: controller.signal,
        onActivity: (incoming) => {
          turn.activities = upsertActivities(turn.activities ?? [], incoming)
        },
      })
      turn.answer = answer.text
      turn.usage = answer.usage
      turn.outcome = 'done'
    }
    catch (err) {
      const stopped = isAbortError(err)
      turn.error = stopped ? t('ask.stopped') : toError(err).message
      turn.outcome = stopped ? 'stopped' : 'failed'
    }
    finally {
      thread.isAsking = false
      controllers.delete(id)
    }
  }

  function stop(id: string) {
    const task = serverTasks.get(id)
    // Cancelling settles the task, which ends the follower with a `stopped` turn.
    if (task)
      void cancelAiTask(task.rpc, task.id)
    else
      controllers.get(id)?.abort()
  }

  function close(id: string) {
    const key = scope.value
    const rpc = getAiRpc()
    controllers.get(id)?.abort()
    controllers.delete(id)
    serverTasks.delete(id)
    if (key && rpc)
      void closeAskThread(rpc, key, id).catch(() => {})
    if (key)
      threadsByDiff.set(key, threads.value.filter(thread => thread.id !== id))
  }

  return reactive({ available, threads, open, ask, stop, close }) as DiffsStoreAsk
}
