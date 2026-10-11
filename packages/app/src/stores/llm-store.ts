import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { CacheRepositories, LlmSession } from '@pulls.review/core/cache'
import type { AiActivity, AiJobSnapshot, AiTaskSnapshot, AiUsage } from '@pulls.review/core/local-rpc'
import type { DiffsPayload, GroupedResult } from '@pulls.review/core/types'
import type { Ref } from 'vue'
import type { TrackedActivity } from '../components/ai/ai-activity'
import type { DiffsStoreLlm, LlmProgress } from './types'
import { resolveAskModel, resolveModel } from '@pulls.review/core/analyze'
import { LLM_ENGINES } from '@pulls.review/core/local-rpc'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { llmAdapter, runLlmAnalysis } from '../analyze/adapters/llm'
import { activitiesFromTranscript, upsertActivities, usageFromTranscript } from '../components/ai/ai-activity'
import { useLlmChat } from '../composables/useLlmChat'
import { t } from '../i18n'
import { describeProgress, localizeError } from '../i18n/core-messages'
import { listAiJobs } from '../local/ai-jobs'
import { getAiRpc } from '../local/ai-rpc'
import { cancelAiTask, followAiTask, listAiTasks, startAiTask, taskModelOf } from '../local/ai-tasks'
import { settings } from '../state/settings'
import { analysisJobKey, analysisJobLabel, buildPrContext, runUsage } from './ai-job'
import { isAbortError } from './run-errors'

export interface LlmStoreOptions {
  cache: CacheRepositories
  diff: Ref<DiffsPayload | undefined>
  /** The analysis transcript the chat continues from; `createDiffsStore` owns it because it is loaded from and reset with the PR cache entry. */
  session: Ref<LlmSession | undefined>
  /** PR cache key of the loaded diff; unset until the diff loads. */
  getCacheKey: () => string | undefined
  /** Publishes a fresh AI result as `analyzedBy.llm`. */
  setResult: (result: GroupedResult) => void
  /** Switches the view onto the `llm` grouping once a run has finished. */
  showLlmResult: () => Promise<void>
}

function isCliEngine(engine: string | undefined): boolean {
  return (LLM_ENGINES as readonly string[]).includes(engine ?? '')
}

/**
 * The LLM sub-store behind `DiffsStore.llm` - analysis runs, their progress/error, and
 * follow-up chat. Only ever loaded by `createDiffsStore` via `import()` behind
 * `import.meta.env.PR_LLM`, so a build with the flag off ships none of it.
 */
export function createLlmStore(opts: LlmStoreOptions): DiffsStoreLlm {
  const isAnalyzing = ref(false)
  const progress = ref<LlmProgress>()
  const transcript = shallowRef<AgentMessage[]>([])
  const transcriptSeenAt = ref(0)
  const cliActivities = shallowRef<TrackedActivity[]>([])
  const error = ref<Error>()
  const engine = ref<string>()
  const startedAt = ref<number>()
  const endedAt = ref<number>()
  const jobUsage = shallowRef<AiUsage>()
  const cancelled = ref(false)
  const resumed = ref(false)
  const isSetup = computed(() => llmAdapter.available)
  let abortController: AbortController | undefined

  const activities = computed(() => isCliEngine(engine.value)
    ? cliActivities.value
    : activitiesFromTranscript(transcript.value, isAnalyzing.value, t, transcriptSeenAt.value))
  const usage = computed(() => jobUsage.value ?? usageFromTranscript(transcript.value))

  async function setSession(session: LlmSession) {
    opts.session.value = session
    const key = opts.getCacheKey()
    if (key)
      await opts.cache.diffs.setLlmSession(key, session)
  }

  async function setResult(result: GroupedResult) {
    opts.setResult(result)
    const key = opts.getCacheKey()
    if (key)
      await opts.cache.diffs.setAnalyzedResult(key, 'llm', result)
  }

  /** Rereads what a server task saved: the chat session and the AI grouping. */
  async function reloadSaved(key: string) {
    const entry = await opts.cache.diffs.get(key).catch(() => undefined)
    if (opts.getCacheKey() !== key || !entry)
      return
    if (entry.llmSession)
      opts.session.value = entry.llmSession as LlmSession
    if (entry.analyzedBy.llm)
      opts.setResult(entry.analyzedBy.llm)
  }

  const chat = useLlmChat({
    diff: opts.diff,
    session: opts.session,
    onSessionChange: setSession,
    onGroupingUpdate: setResult,
    server: {
      target: () => {
        const rpc = getAiRpc()
        const key = opts.getCacheKey()
        const model = taskModelOf(resolveAskModel(settings.value.llm))
        return rpc && key && model ? { rpc, key, model } : undefined
      },
      reload: () => {
        const key = opts.getCacheKey()
        return key ? reloadSaved(key) : Promise.resolve()
      },
    },
  })

  // Server tasks keep running when the page goes away; `following` is only this page watching one.
  let following: AbortController | undefined
  let serverTaskId: string | undefined

  function serverTarget() {
    const rpc = getAiRpc()
    const key = opts.getCacheKey()
    const model = taskModelOf(resolveModel(settings.value.llm))
    return rpc && key && model ? { rpc, key, model } : undefined
  }

  async function followTask(rpc: NonNullable<ReturnType<typeof getAiRpc>>, key: string, task: Pick<AiTaskSnapshot, 'id' | 'startedAt' | 'engine'>, isResume: boolean) {
    following?.abort()
    const controller = new AbortController()
    following = controller
    serverTaskId = task.id
    const isCurrent = () => following === controller
    error.value = undefined
    transcript.value = []
    cliActivities.value = []
    jobUsage.value = undefined
    engine.value = task.engine
    startedAt.value = task.startedAt
    endedAt.value = undefined
    cancelled.value = false
    resumed.value = isResume
    isAnalyzing.value = true
    let finished = false
    try {
      const done = await followAiTask(rpc, task.id, {
        signal: controller.signal,
        onActivity: (next) => {
          if (isCurrent())
            cliActivities.value = upsertActivities(cliActivities.value, next)
        },
      })
      if (!isCurrent())
        return
      endedAt.value = done.endedAt ?? Date.now()
      jobUsage.value = done.usage
      if (done.status === 'done') {
        if (done.result)
          opts.setResult(JSON.parse(done.result) as GroupedResult)
        await reloadSaved(key)
        finished = true
      }
      else if (done.status === 'cancelled') {
        cancelled.value = true
      }
      else {
        error.value = new Error(done.error ?? t('analyze.failed'))
      }
    }
    catch (err) {
      if (isCurrent() && !isAbortError(err))
        error.value = localizeError(err)
    }
    finally {
      if (isCurrent()) {
        following = undefined
        serverTaskId = undefined
        endedAt.value ??= Date.now()
        progress.value = undefined
        isAnalyzing.value = false
      }
    }
    if (finished)
      await opts.showLlmResult()
  }

  async function runOnServer(target: NonNullable<ReturnType<typeof serverTarget>>) {
    chat.stop()
    error.value = undefined
    let id: string
    try {
      id = await startAiTask(target.rpc, { kind: 'analyze', key: target.key, model: target.model, locale: settings.value.locale })
    }
    catch (err) {
      error.value = localizeError(err)
      return
    }
    await followTask(target.rpc, target.key, { id, startedAt: Date.now(), engine: target.model.engine }, false)
  }

  // `cli-stream` hands over what the CLI reported minus reasoning tokens and wall time; the job list has all of it.
  async function loadJobUsage(match: (job: AiJobSnapshot) => boolean): Promise<AiUsage | undefined> {
    const rpc = getAiRpc()
    return rpc ? runUsage(await listAiJobs(rpc).catch(() => []), match) : undefined
  }

  async function run(resumeFrom?: AiJobSnapshot) {
    const currentDiff = opts.diff.value
    if (!currentDiff)
      return
    // The local server runs CLI analyses as tasks that outlive this page.
    const target = resumeFrom ? undefined : serverTarget()
    if (target)
      return runOnServer(target)
    chat.stop()
    const resolved = resolveModel(settings.value.llm)
    const key = resolved && analysisJobKey(currentDiff, resolved)
    const label = analysisJobLabel(currentDiff)
    const runStartedAt = resumeFrom?.startedAt ?? Date.now()
    error.value = undefined
    transcript.value = []
    cliActivities.value = []
    jobUsage.value = undefined
    engine.value = resolved?.model.provider
    startedAt.value = runStartedAt
    endedAt.value = undefined
    cancelled.value = false
    resumed.value = resumeFrom !== undefined
    isAnalyzing.value = true
    const controller = new AbortController()
    abortController = controller
    const isCurrent = () => abortController === controller
    try {
      const { result, transcript: messages } = await runLlmAnalysis(currentDiff, {
        onProgress: next => progress.value = { step: next.step, message: describeProgress(next) },
        onTranscript: (next) => {
          transcript.value = next
          transcriptSeenAt.value = Date.now()
        },
        onActivity: (next: AiActivity[]) => {
          if (isCurrent())
            cliActivities.value = upsertActivities(cliActivities.value, next)
        },
        signal: controller.signal,
        context: buildPrContext(currentDiff),
        label,
        key,
      })
      if (controller.signal.aborted)
        return
      await setResult(result)
      await setSession({ messages, chatStartIndex: messages.length })
    }
    catch (err) {
      if (!controller.signal.aborted)
        error.value = localizeError(err)
    }
    finally {
      if (isCurrent()) {
        abortController = undefined
        cancelled.value = controller.signal.aborted
        endedAt.value = Date.now()
        if (isCliEngine(engine.value)) {
          void loadJobUsage(job => job.status !== 'running' && (job.key ? job.key === key : job.label === label) && job.startedAt >= runStartedAt - 1000)
            .then((found) => {
              if (startedAt.value === runStartedAt && !isAnalyzing.value)
                jobUsage.value = found
            })
        }
      }
      progress.value = undefined
      isAnalyzing.value = false
    }
    await opts.showLlmResult()
  }

  async function reanalyze() {
    await run()
  }

  // Opening (or reloading) a page picks up the server tasks still working on its diff.
  let checkedDiff: DiffsPayload | undefined
  async function resumeServerTasks(diff: DiffsPayload) {
    const rpc = getAiRpc()
    const key = opts.getCacheKey()
    if (!rpc || !key || checkedDiff === diff)
      return
    checkedDiff = diff
    const tasks = await listAiTasks(rpc, key).catch(() => [])
    if (opts.diff.value !== diff || opts.getCacheKey() !== key)
      return
    const analysis = tasks.find(task => task.kind === 'analyze' && task.status === 'running')
    if (analysis && !isAnalyzing.value)
      void followTask(rpc, key, analysis, true)
    const reply = tasks.find(task => task.kind === 'chat' && task.status === 'running')
    if (reply)
      chat.resume(reply)
  }

  watch(opts.diff, (diff) => {
    if (diff)
      void resumeServerTasks(diff)
  }, { immediate: true })

  /** Stops the run: a server task is cancelled, not just left. */
  function abort() {
    chat.stop()
    const rpc = getAiRpc()
    if (rpc && serverTaskId)
      void cancelAiTask(rpc, serverTaskId)
    abortController?.abort()
  }

  /** Stops watching without stopping: a server task finishes and saves on its own. */
  function detach() {
    chat.detach()
    abortController?.abort()
    if (following) {
      following.abort()
      following = undefined
      serverTaskId = undefined
      isAnalyzing.value = false
      progress.value = undefined
    }
    checkedDiff = undefined
  }

  return reactive({
    isSetup,
    isAnalyzing,
    progress,
    transcript,
    error,
    activities,
    engine,
    startedAt,
    endedAt,
    usage,
    cancelled,
    resumed,
    reanalyze,
    chat: reactive(chat),
    abort,
    detach,
  }) as DiffsStoreLlm
}
