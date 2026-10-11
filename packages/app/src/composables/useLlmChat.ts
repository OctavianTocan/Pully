import type { Agent, AgentMessage } from '@earendil-works/pi-agent-core'
import type { AssistantMessage } from '@earendil-works/pi-ai'
import type { LlmSession } from '@pulls.review/core/cache'
import type { AiTaskModel, AiTaskSnapshot } from '@pulls.review/core/local-rpc'
import type { DiffsPayload, GroupedResult } from '@pulls.review/core/types'
import type { Ref } from 'vue'
import type { TrackedActivity } from '../components/ai/ai-activity'
import type { LocalRpc } from '../local/connection'
import { resolveAskModel } from '@pulls.review/core/analyze'
import { diagnostics } from '@pulls.review/core/diagnostics'
import { computed, getCurrentScope, onScopeDispose, ref, shallowRef, toRaw } from 'vue'
import { upsertActivities } from '../components/ai/ai-activity'
import { t } from '../i18n'
import { localizeError } from '../i18n/core-messages'
import { cancelAiTask, followAiTask, startAiTask } from '../local/ai-tasks'
import { settings } from '../state/settings'
import { buildPrContext } from '../stores/ai-job'
import { isAbortError } from '../stores/run-errors'

export interface LlmChatOptions {
  diff: Ref<DiffsPayload | undefined>
  session: Ref<LlmSession | undefined>
  onSessionChange: (session: LlmSession) => void | Promise<void>
  onGroupingUpdate: (result: GroupedResult) => void | Promise<void>
  /**
   * Replies through server tasks that outlive the page, when the local server can run them.
   * `target` names where; `reload` rereads the session and grouping the task saved.
   */
  server?: {
    target: () => { rpc: LocalRpc, key: string, model: AiTaskModel } | undefined
    reload: () => Promise<void>
  }
}

function isErrorReply(message: AgentMessage | undefined): message is AssistantMessage {
  return message?.role === 'assistant' && message.stopReason === 'error'
}

export function useLlmChat({ diff, session, onSessionChange, onGroupingUpdate, server }: LlmChatOptions) {
  const isStreaming = ref(false)
  const error = ref<Error>()
  const live = shallowRef<{ session: LlmSession, messages: AgentMessage[] }>()
  const activities = shallowRef<TrackedActivity[]>([])
  const startedAt = ref<number>()
  const endedAt = ref<number>()
  let liveAgent: Agent | undefined
  let stopRequested = false
  /** The server reply this page follows; aborting it stops following, not the reply. */
  let following: AbortController | undefined
  let serverTask: { rpc: LocalRpc, id: string } | undefined

  const available = computed(() => session.value !== undefined)
  const messages = computed(() => {
    const liveMessages = live.value?.session === session.value ? live.value?.messages : undefined
    return (liveMessages ?? session.value?.messages ?? []).slice(session.value?.chatStartIndex ?? 0)
  })

  async function run(start: (agent: Agent) => Promise<void>, history: AgentMessage[]) {
    const currentDiff = toRaw(diff.value)
    const current = session.value
    if (!currentDiff || !current || isStreaming.value)
      return
    const resolved = resolveAskModel(settings.value.llm)
    if (!resolved) {
      error.value = localizeError(diagnostics.llmNotConfigured())
      return
    }

    // A clear, re-analysis or refresh during the run replaces the session; the run must not write over it.
    const isStale = () => session.value !== current || toRaw(diff.value) !== currentDiff

    error.value = undefined
    activities.value = []
    startedAt.value = Date.now()
    endedAt.value = undefined
    isStreaming.value = true
    stopRequested = false
    try {
      const { createChatSession } = await import('@pulls.review/core/llm')
      if (stopRequested || isStale())
        return
      const agent = createChatSession({
        diff: currentDiff,
        resolved,
        locale: settings.value.locale,
        messages: history,
        onGroupingUpdate: result => isStale() ? undefined : onGroupingUpdate(result),
        onActivity: (next) => {
          if (!isStale())
            activities.value = upsertActivities(activities.value, next)
        },
        context: buildPrContext(currentDiff),
      })
      liveAgent = agent
      const sync = () => {
        const { streamingMessage } = agent.state
        live.value = { session: current, messages: streamingMessage ? [...agent.state.messages, streamingMessage] : [...agent.state.messages] }
      }
      sync()
      const unsubscribe = agent.subscribe(sync)
      try {
        await start(agent)
      }
      finally {
        unsubscribe()
      }

      if (isStale())
        return
      const next = { messages: [...agent.state.messages], chatStartIndex: current.chatStartIndex }
      await onSessionChange(next)
      const last = next.messages.at(-1)
      if (isErrorReply(last))
        error.value = new Error(last.errorMessage ?? t('analyze.chatFailed'))
    }
    catch (err) {
      if (!isStale())
        error.value = localizeError(err)
    }
    finally {
      liveAgent = undefined
      live.value = undefined
      endedAt.value = Date.now()
      isStreaming.value = false
    }
  }

  /** Follows a server reply until it settles, then rereads what it saved. */
  async function follow(rpc: LocalRpc, task: Pick<AiTaskSnapshot, 'id' | 'startedAt' | 'text'>) {
    following?.abort()
    const controller = new AbortController()
    following = controller
    serverTask = { rpc, id: task.id }
    const isCurrent = () => following === controller
    const current = session.value
    error.value = undefined
    activities.value = []
    startedAt.value = task.startedAt
    endedAt.value = undefined
    isStreaming.value = true
    // Shows the question right away; the saved session replaces this once the reply is in.
    if (current && task.text)
      live.value = { session: current, messages: [...toRaw(current.messages), { role: 'user', content: task.text, timestamp: task.startedAt }] }
    try {
      const done = await followAiTask(rpc, task.id, {
        signal: controller.signal,
        onActivity: (next) => {
          if (isCurrent())
            activities.value = upsertActivities(activities.value, next)
        },
      })
      if (!isCurrent())
        return
      await server?.reload()
      const last = session.value?.messages.at(-1)
      if (done.status === 'failed')
        error.value = new Error(done.error ?? t('analyze.chatFailed'))
      else if (isErrorReply(last))
        error.value = new Error(last.errorMessage ?? t('analyze.chatFailed'))
    }
    catch (err) {
      if (isCurrent() && !isAbortError(err))
        error.value = localizeError(err)
    }
    finally {
      if (isCurrent()) {
        following = undefined
        serverTask = undefined
        live.value = undefined
        endedAt.value = Date.now()
        isStreaming.value = false
      }
    }
  }

  async function sendOnServer(target: NonNullable<ReturnType<NonNullable<LlmChatOptions['server']>['target']>>, input: { text?: string, retry?: boolean }) {
    if (isStreaming.value)
      return
    try {
      const id = await startAiTask(target.rpc, { kind: 'chat', key: target.key, model: target.model, locale: settings.value.locale, ...input })
      await follow(target.rpc, { id, startedAt: Date.now(), text: input.text })
    }
    catch (err) {
      error.value = localizeError(err)
    }
  }

  async function send(text: string) {
    if (!text.trim() || !session.value)
      return
    const target = server?.target()
    if (target)
      return sendOnServer(target, { text })
    await run(agent => agent.prompt(text), toRaw(session.value.messages))
  }

  async function retry() {
    const current = toRaw(session.value?.messages)
    if (!current || !isErrorReply(current.at(-1)))
      return
    const target = server?.target()
    if (target)
      return sendOnServer(target, { retry: true })
    await run(agent => agent.continue(), current.slice(0, -1))
  }

  /** Picks up a reply the server is still writing, e.g. after a reload. */
  function resume(task: Pick<AiTaskSnapshot, 'id' | 'startedAt' | 'text'>) {
    const target = server?.target()
    if (target && !isStreaming.value)
      void follow(target.rpc, task)
  }

  /** Stops the reply, on the server too. */
  function stop() {
    stopRequested = true
    liveAgent?.abort()
    if (serverTask)
      void cancelAiTask(serverTask.rpc, serverTask.id)
  }

  /** Stops watching without stopping a server reply, which still finishes and saves. */
  function detach() {
    stopRequested = true
    liveAgent?.abort()
    following?.abort()
    following = undefined
    serverTask = undefined
    live.value = undefined
    isStreaming.value = false
  }

  if (getCurrentScope())
    onScopeDispose(detach)

  async function clear() {
    const current = session.value
    if (!current)
      return
    stop()
    error.value = undefined
    activities.value = []
    await onSessionChange({ messages: toRaw(current.messages).slice(0, current.chatStartIndex), chatStartIndex: current.chatStartIndex })
  }

  return { available, messages, isStreaming, error, activities, startedAt, endedAt, send, retry, stop, detach, resume, clear }
}
