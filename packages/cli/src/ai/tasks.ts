import type { ResolvedModel } from '@pulls.review/core/analyze'
import type { CacheRepositories, LlmSession } from '@pulls.review/core/cache'
import type { CliRunHooks, CliRunResult } from '@pulls.review/core/llm'
import type { AiActivity, AiAskThread, AiAskTurn, AiJobStatus, AiTaskModel, AiTaskRequest, AiTaskSelection, AiTaskSnapshot, AiUsage } from '@pulls.review/core/local-rpc'
import type { Locale } from '@pulls.review/core/locales'
import type { BriefResult, CritiqueResult, DiffsPayload, GroupedResult, TourResult } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import { randomUUID } from 'node:crypto'
import { defaultLlmSettings, resolveModel } from '@pulls.review/core/analyze'
import { DEFAULT_LOCALE, isLocale } from '@pulls.review/core/locales'
import { serializeRef } from '@pulls.review/core/types'
import { listAiJobs, runAiJob } from './jobs'

const WAIT_MS = 25_000
const KEEP_MS = 30 * 60_000
const THREADS_PREFIX = 'ask-threads:'

/** What a runner gets besides its inputs: the task's abort signal, its work-log sink and a tag naming its CLI jobs. */
export interface TaskHooks extends CliRunHooks {
  signal: AbortSignal
  /** Labels the CLI jobs the task starts, so their usage can be summed afterwards. */
  tag: string
}

/** The model work behind each task kind; injected so tests can replace the CLIs. */
export interface TaskRunners {
  analyze: (diff: DiffsPayload, resolved: ResolvedModel, locale: Locale, hooks: TaskHooks) => Promise<{ result: GroupedResult, transcript: LlmSession['messages'] }>
  critique: (diff: DiffsPayload, resolved: ResolvedModel, lens: { name: string, instructions: string } | undefined, hooks: TaskHooks) => Promise<CritiqueResult>
  ask: (diff: DiffsPayload, resolved: ResolvedModel, request: { selection: AiTaskSelection, question: string, history: { question: string, answer: string }[] }, hooks: TaskHooks) => Promise<CliRunResult>
  /** A reply in the diff's chat; `session` is `undefined` when the diff was never analyzed, and the chat starts from the pull request itself. */
  chat: (diff: DiffsPayload, resolved: ResolvedModel, locale: Locale, session: LlmSession | undefined, input: { text?: string, retry?: boolean }, hooks: TaskHooks & { onGroupingUpdate: (result: GroupedResult) => Promise<void> }) => Promise<LlmSession>
  /** A guided tour: plan, then every stop written in parallel. */
  tour: (diff: DiffsPayload, resolved: ResolvedModel, locale: string, hooks: TaskHooks) => Promise<TourResult>
  /** The page an agent designs for the diff, building on its analysis when there is one. */
  brief: (diff: DiffsPayload, resolved: ResolvedModel, locale: string, analysis: GroupedResult | undefined, hooks: TaskHooks) => Promise<BriefResult>
  /** A review lens's instructions, or `undefined` when there is no such skill. */
  lens: (name: string) => Promise<string | undefined>
  /** What the CLI jobs tagged `tag` consumed together. */
  usage: (tag: string) => AiUsage | undefined
}

interface Task {
  request: AiTaskRequest
  info: Omit<AiTaskSnapshot, 'status' | 'rev' | 'activities' | 'error' | 'usage' | 'endedAt' | 'result'>
  status: AiJobStatus
  rev: number
  entries: Map<string, AiActivity>
  waiters: Set<() => void>
  controller: AbortController
  error?: string
  usage?: AiUsage
  result?: string
  endedAt?: number
  /** `ask`: the answered turns before this question. */
  history?: { question: string, answer: string }[]
}

export interface TaskManager {
  /** Starts a task, or returns the running one doing the same work. An `ask` is saved to its thread before this resolves. */
  start: (request: AiTaskRequest) => Promise<string>
  /** The task once it moved past revision `after`, or after a while if it did not. */
  wait: (id: string, after: number) => Promise<AiTaskSnapshot>
  /** Stops a running task and its CLI. */
  cancel: (id: string) => boolean
  /** One diff's tasks, newest first, without work logs. */
  list: (key: string) => AiTaskSnapshot[]
  /** Every diff's tasks, newest first, without work logs. */
  listAll: () => AiTaskSnapshot[]
  /** One diff's saved question threads. */
  threads: (key: string) => Promise<AiAskThread[]>
  /** Drops a thread and stops the answer it waits for. */
  closeThread: (key: string, threadId: string) => Promise<void>
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function localeOf(value: string): Locale {
  return isLocale(value) ? value : DEFAULT_LOCALE
}

/**
 * The model a task runs, from what the page resolved out of its settings.
 * @param model - Engine, model id and effort.
 * @returns The resolved model; the CLI engines need no key, so it always resolves.
 */
export function resolveTaskModel(model: AiTaskModel): ResolvedModel {
  const settings = model.engine === 'codex'
    ? { ...defaultLlmSettings, provider: model.engine, codexModel: model.model || defaultLlmSettings.codexModel, codexEffort: model.effort ?? '' }
    : { ...defaultLlmSettings, provider: model.engine, claudeCodeModel: model.model || defaultLlmSettings.claudeCodeModel, claudeCodeEffort: model.effort ?? '' }
  return resolveModel(settings)!
}

function selectionLabel({ path, startLine, endLine }: AiTaskSelection): string {
  return startLine === endLine ? `${path}:L${startLine}` : `${path}:L${startLine}-L${endLine}`
}

function labelOf(request: AiTaskRequest): string {
  switch (request.kind) {
    case 'analyze': return `Analyze ${request.key}`
    case 'critique': return `Review ${request.key}${request.lens ? ` (${request.lens})` : ''}`
    case 'ask': return `Ask about ${selectionLabel(request.selection)}`
    case 'chat': return `Chat about ${request.key}`
    case 'tour': return `Tour ${request.key}`
    case 'brief': return `Brief ${request.key}`
  }
}

/** Two requests that would do the same work share one running task. */
function sameWork(a: AiTaskRequest, b: AiTaskRequest): boolean {
  if (a.kind !== b.kind || a.key !== b.key)
    return false
  if (a.kind === 'critique' && b.kind === 'critique')
    return a.variant === b.variant
  if (a.kind === 'ask' && b.kind === 'ask')
    return a.threadId === b.threadId
  return true
}

/**
 * Runs AI work on the server and saves what it produces into the cache the page reads,
 * so a reload, a closed tab or a sleeping phone loses nothing: the page lists the diff's
 * tasks when it opens, follows the running ones, and reads finished results from the cache.
 * @param options - The cache and storage the page shares, and the runners doing the model work.
 * @param options.cache - The repositories over the shared cache directory.
 * @param options.storage - The same storage, for the question threads.
 * @param options.runners - The model work; `defaultTaskRunners()` outside tests.
 * @returns The task manager the RPC functions call.
 */
export function createTaskManager({ cache, storage, runners }: { cache: CacheRepositories, storage: Storage, runners: TaskRunners }): TaskManager {
  const tasks = new Map<string, Task>()
  const threadLocks = new Map<string, Promise<unknown>>()

  function wake(task: Task) {
    for (const waiter of [...task.waiters])
      waiter()
  }

  function record(task: Task, batch: AiActivity[]) {
    if (task.status !== 'running' || !batch.length)
      return
    // A task may run several CLI jobs (an analysis retry, a chat turn); their entries merge by id under the task's own revisions.
    task.rev += 1
    for (const activity of batch)
      task.entries.set(activity.id, { ...activity, rev: task.rev })
    wake(task)
  }

  function settle(task: Task, status: Exclude<AiJobStatus, 'running'>, fields: { error?: string, usage?: AiUsage, result?: string } = {}) {
    if (task.status !== 'running')
      return
    task.rev += 1
    const now = Date.now()
    for (const [id, entry] of task.entries) {
      if (entry.status === 'running')
        task.entries.set(id, { ...entry, status: status === 'failed' ? 'failed' : 'done', endedAt: now, rev: task.rev })
    }
    Object.assign(task, { status, endedAt: now }, fields)
    wake(task)
  }

  function prune() {
    const now = Date.now()
    for (const [id, task] of tasks) {
      if (task.status !== 'running' && now - (task.endedAt ?? now) > KEEP_MS)
        tasks.delete(id)
    }
  }

  function snapshot(task: Task, after: number): AiTaskSnapshot {
    return {
      ...task.info,
      status: task.status,
      rev: task.rev,
      activities: [...task.entries.values()].filter(entry => entry.rev > after).map(entry => ({ ...entry })),
      error: task.error,
      usage: task.usage,
      result: task.result,
      endedAt: task.endedAt,
    }
  }

  // Thread writes for one diff run one at a time, so a question and an answer never drop each other.
  function withThreads<T>(key: string, change: (threads: AiAskThread[]) => T): Promise<T> {
    const run = (threadLocks.get(key) ?? Promise.resolve()).then(async () => {
      const stored = await storage.getItem<{ threads?: AiAskThread[] }>(`${THREADS_PREFIX}${key}`)
      const threads = Array.isArray(stored?.threads) ? stored.threads : []
      const result = change(threads)
      await storage.setItem(`${THREADS_PREFIX}${key}`, { threads })
      return result
    })
    threadLocks.set(key, run.catch(() => {}))
    return run
  }

  function patchTurn(key: string, threadId: string, taskId: string, patch: Partial<AiAskTurn>): Promise<void> {
    return withThreads(key, (threads) => {
      const turn = threads.find(thread => thread.id === threadId)?.turns.find(candidate => candidate.taskId === taskId)
      if (turn)
        Object.assign(turn, patch)
    })
  }

  /** Does the task's model work and saves its result; returns what the task settles with. */
  async function perform(task: Task, hooks: TaskHooks): Promise<{ usage?: AiUsage, result?: string }> {
    const { request } = task
    const entry = await cache.diffs.get(request.key)
    if (!entry)
      throw new Error('This diff is no longer cached on the server; open it again.')
    // Cancelled while the cache was read: the runner would never see the abort.
    task.controller.signal.throwIfAborted()
    const resolved = resolveTaskModel(request.model)
    const { diff } = entry
    switch (request.kind) {
      case 'analyze': {
        const { result, transcript } = await runners.analyze(diff, resolved, localeOf(request.locale), hooks)
        task.controller.signal.throwIfAborted()
        await cache.diffs.setAnalyzedResult(request.key, 'llm', result)
        await cache.diffs.setLlmSession(request.key, { messages: transcript, chatStartIndex: transcript.length })
        // The page also gets the grouping itself, so a browser write racing this one can't lose it.
        return { usage: runners.usage(hooks.tag), result: JSON.stringify(result) }
      }
      case 'critique': {
        const instructions = request.lens ? await runners.lens(request.lens) : undefined
        if (request.lens && !instructions)
          throw new Error(`The skill "${request.lens}" was not found.`)
        const result = await runners.critique(diff, resolved, request.lens ? { name: request.lens, instructions: instructions! } : undefined, hooks)
        task.controller.signal.throwIfAborted()
        await cache.critiques.set(serializeRef(diff.ref), request.variant, result)
        return { usage: result.usage, result: JSON.stringify(result) }
      }
      case 'ask': {
        const answer = await runners.ask(diff, resolved, { selection: request.selection, question: request.question, history: task.history ?? [] }, hooks)
        task.controller.signal.throwIfAborted()
        await patchTurn(request.key, request.threadId, task.info.id, { answer: answer.text, usage: answer.usage, outcome: 'done', activities: [...task.entries.values()] })
        return { usage: answer.usage, result: answer.text }
      }
      case 'tour': {
        const result = await runners.tour(diff, resolved, localeOf(request.locale), hooks)
        task.controller.signal.throwIfAborted()
        // The plan and every stop run as separate CLI jobs under this task's tag; their usage adds up.
        const tour = { ...result, usage: runners.usage(hooks.tag) }
        await cache.tours.set(serializeRef(diff.ref), tour)
        return { usage: tour.usage, result: JSON.stringify(tour) }
      }
      case 'brief': {
        const result = await runners.brief(diff, resolved, localeOf(request.locale), entry.analyzedBy.llm, hooks)
        task.controller.signal.throwIfAborted()
        const brief = { ...result, usage: runners.usage(hooks.tag) }
        await cache.briefs.set(serializeRef(diff.ref), brief)
        return { usage: brief.usage, result: JSON.stringify(brief) }
      }
      case 'chat': {
        // A diff nobody analyzed has no transcript; the chat then starts from the pull request itself.
        const next = await runners.chat(diff, resolved, localeOf(request.locale), entry.llmSession as LlmSession | undefined, { text: request.text, retry: request.retry }, {
          ...hooks,
          onGroupingUpdate: result => cache.diffs.setAnalyzedResult(request.key, 'llm', result),
        })
        task.controller.signal.throwIfAborted()
        await cache.diffs.setLlmSession(request.key, next)
        return {}
      }
    }
  }

  /** An analysis that succeeded starts what it was asked to, so the phone need not be awake to carry on. */
  async function chain(request: Extract<AiTaskRequest, { kind: 'analyze' }>) {
    for (const step of request.then ?? []) {
      try {
        if (step === 'tour' && await hasCurrentTour(request.key))
          continue
        await start({ kind: step, key: request.key, model: request.model, locale: request.locale })
      }
      catch {
        // The analysis stands; a follow-up that cannot start is the reviewer's to run by hand.
      }
    }
  }

  async function hasCurrentTour(key: string): Promise<boolean> {
    const entry = await cache.diffs.get(key)
    if (!entry)
      return false
    const headSha = entry.diff.head?.sha
    return (await cache.tours.latest(serializeRef(entry.diff.ref), headSha))?.headSha === headSha
  }

  async function execute(task: Task) {
    const { request } = task
    const hooks: TaskHooks = { signal: task.controller.signal, onActivity: batch => record(task, batch), tag: `${task.info.label} · ${task.info.id}` }
    try {
      settle(task, 'done', await perform(task, hooks))
      if (request.kind === 'analyze')
        void chain(request)
    }
    catch (error) {
      const stopped = task.controller.signal.aborted
      if (request.kind === 'ask' && !stopped)
        await patchTurn(request.key, request.threadId, task.info.id, { error: messageOf(error), outcome: 'failed', activities: [...task.entries.values()] }).catch(() => {})
      if (stopped)
        settle(task, 'cancelled', { error: 'Cancelled.' })
      else
        settle(task, 'failed', { error: messageOf(error) })
    }
  }

  async function start(request: AiTaskRequest): Promise<string> {
    prune()
    const running = [...tasks.values()].find(task => task.status === 'running' && sameWork(task.request, request))
    if (running)
      return running.info.id
    const id = randomUUID()
    const title = (await cache.diffs.get(request.key).catch(() => undefined))?.diff.title
    const task: Task = {
      request,
      info: {
        id,
        kind: request.kind,
        key: request.key,
        title,
        label: labelOf(request),
        engine: request.model.engine,
        model: request.model.model,
        effort: request.model.effort,
        startedAt: Date.now(),
        variant: request.kind === 'critique' ? request.variant : undefined,
        lens: request.kind === 'critique' ? request.lens : undefined,
        threadId: request.kind === 'ask' ? request.threadId : undefined,
        text: request.kind === 'ask' ? request.question : request.kind === 'chat' ? request.text : undefined,
      },
      status: 'running',
      rev: 0,
      entries: new Map(),
      waiters: new Set(),
      controller: new AbortController(),
    }
    if (request.kind === 'ask') {
      task.history = await withThreads(request.key, (threads) => {
        let thread = threads.find(candidate => candidate.id === request.threadId)
        if (!thread) {
          thread = { id: request.threadId, selection: request.selection, label: selectionLabel(request.selection), turns: [] }
          threads.push(thread)
        }
        const history = thread.turns.flatMap(turn => turn.answer !== undefined ? [{ question: turn.question, answer: turn.answer }] : [])
        thread.turns.push({ question: request.question, startedAt: task.info.startedAt, taskId: id })
        return history
      })
    }
    tasks.set(id, task)
    void execute(task)
    return id
  }

  async function wait(id: string, after: number): Promise<AiTaskSnapshot> {
    const task = tasks.get(id)
    if (!task)
      throw new Error('That task is gone: the server restarted or dropped it.')
    if (task.rev <= after && task.status === 'running') {
      await new Promise<void>((resolve) => {
        let timer: ReturnType<typeof setTimeout> | undefined
        const done = () => {
          clearTimeout(timer)
          task.waiters.delete(done)
          resolve()
        }
        timer = setTimeout(done, WAIT_MS)
        task.waiters.add(done)
      })
    }
    return snapshot(task, after)
  }

  function cancel(id: string): boolean {
    const task = tasks.get(id)
    if (!task || task.status !== 'running')
      return false
    task.controller.abort()
    settle(task, 'cancelled', { error: 'Cancelled.' })
    const { request } = task
    if (request.kind === 'ask')
      void patchTurn(request.key, request.threadId, id, { error: 'Stopped.', outcome: 'stopped', activities: [...task.entries.values()] }).catch(() => {})
    return true
  }

  /** Newest first; tasks started in the same millisecond keep the later one on top. */
  function newestFirst(): Task[] {
    prune()
    return [...tasks.values()].reverse().sort((a, b) => b.info.startedAt - a.info.startedAt)
  }

  function list(key: string): AiTaskSnapshot[] {
    return newestFirst()
      .filter(task => task.info.key === key)
      .map(task => snapshot(task, Number.POSITIVE_INFINITY))
  }

  function listAll(): AiTaskSnapshot[] {
    return newestFirst().map(task => snapshot(task, Number.POSITIVE_INFINITY))
  }

  async function threads(key: string): Promise<AiAskThread[]> {
    // A turn whose task this process never ran was cut off by a restart: say so instead of waiting forever.
    return withThreads(key, (saved) => {
      for (const turn of saved.flatMap(thread => thread.turns)) {
        const task = turn.taskId ? tasks.get(turn.taskId) : undefined
        if (turn.outcome === undefined && !task) {
          turn.outcome = 'failed'
          turn.error = 'The server restarted before answering.'
        }
      }
      return structuredClone(saved)
    })
  }

  async function closeThread(key: string, threadId: string): Promise<void> {
    for (const task of tasks.values()) {
      if (task.status === 'running' && task.info.key === key && task.info.threadId === threadId)
        cancel(task.info.id)
    }
    await withThreads(key, (saved) => {
      const index = saved.findIndex(thread => thread.id === threadId)
      if (index >= 0)
        saved.splice(index, 1)
    })
  }

  return { start, wait, cancel, list, listAll, threads, closeThread }
}

const USAGE_COUNTS = ['inputTokens', 'outputTokens', 'cacheReadTokens', 'cacheWriteTokens', 'reasoningTokens', 'costUsd', 'durationMs'] as const

/**
 * Sums what a set of CLI jobs reported.
 * @param usages - Each job's usage.
 * @returns The totals and the last named model; nothing when no job reported usage.
 */
export function sumUsage(usages: readonly AiUsage[]): AiUsage | undefined {
  if (!usages.length)
    return undefined
  const total: AiUsage = { model: usages.findLast(usage => usage.model)?.model }
  for (const usage of usages) {
    for (const count of USAGE_COUNTS) {
      if (usage[count] !== undefined)
        total[count] = (total[count] ?? 0) + usage[count]
    }
  }
  return total
}

/**
 * The real runners: core's analysis, review, ask and chat over the server's Claude Code or Codex CLI.
 * @param lens - Reads a review lens's instructions by name.
 * @returns Runners that load the LLM runtime on first use.
 */
export function defaultTaskRunners(lens: (name: string) => Promise<string | undefined>): TaskRunners {
  let loaded: Promise<typeof import('@pulls.review/core/llm')> | undefined
  const llm = () => loaded ??= import('@pulls.review/core/llm').then((module) => {
    module.setCliRunner(runAiJob)
    return module
  })
  return {
    async analyze(diff, resolved, locale, hooks) {
      const core = await llm()
      return core.runLlmAnalysis(diff, resolved, locale, { signal: hooks.signal, onActivity: hooks.onActivity, context: core.prContextOf(diff), label: hooks.tag })
    },
    async critique(diff, resolved, critiqueLens, hooks) {
      return (await llm()).runCritique(diff, resolved, { lens: critiqueLens, signal: hooks.signal, onActivity: hooks.onActivity })
    },
    async tour(diff, resolved, locale, hooks) {
      return (await llm()).runTour(diff, resolved, { locale, signal: hooks.signal, onActivity: hooks.onActivity, label: hooks.tag })
    },
    async brief(diff, resolved, locale, analysis, hooks) {
      return (await llm()).runBrief(diff, resolved, { locale, analysis, signal: hooks.signal, onActivity: hooks.onActivity, label: hooks.tag })
    },
    async ask(diff, resolved, request, hooks) {
      return (await llm()).askAboutSelection(diff, resolved, request, { signal: hooks.signal, onActivity: hooks.onActivity })
    },
    async chat(diff, resolved, locale, session, input, hooks) {
      const core = await llm()
      const history = session ?? core.seedChatSession(diff, locale)
      const last = history.messages.at(-1)
      const retrying = input.retry && last?.role === 'assistant' && last.stopReason === 'error'
      const agent = core.createChatSession({
        diff,
        resolved,
        locale,
        messages: retrying ? history.messages.slice(0, -1) : history.messages,
        onGroupingUpdate: hooks.onGroupingUpdate,
        onActivity: hooks.onActivity,
        context: core.prContextOf(diff),
      })
      const abort = () => agent.abort()
      hooks.signal.addEventListener('abort', abort, { once: true })
      try {
        if (retrying)
          await agent.continue()
        else if (input.text?.trim())
          await agent.prompt(input.text)
        else
          throw new Error('Nothing to send.')
      }
      finally {
        hooks.signal.removeEventListener('abort', abort)
      }
      return { messages: [...agent.state.messages], chatStartIndex: history.chatStartIndex }
    },
    lens,
    usage: tag => sumUsage(listAiJobs().filter(job => job.label === tag && job.usage).map(job => job.usage!)),
  }
}
