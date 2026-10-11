import type { ResolvedModel } from '@pulls.review/core/analyze'
import type { CacheRepositories } from '@pulls.review/core/cache'
import type { ReviewLens } from '@pulls.review/core/local-rpc'
import type { CritiqueFinding, CritiqueResult, CritiqueSeverity, DiffsPayload, ReviewDraftTarget } from '@pulls.review/core/types'
import type { Ref } from 'vue'
import type { TrackedActivity } from '../components/ai/ai-activity'
import type { LensSource } from '../local/lenses'
import type { AiRunOutcome, CritiqueFocus, DiffsStoreCritique, DiffsStoreReviews } from './types'
import { resolveModel } from '@pulls.review/core/analyze'
import { critiqueReviewBody, findingCommentBody, isCliEngine, runCritique } from '@pulls.review/core/llm'
import { serializeRef } from '@pulls.review/core/types'
import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { upsertActivities } from '../components/ai/ai-activity'
import { t } from '../i18n'
import { getAiRpc } from '../local/ai-rpc'
import { cancelAiTask, followAiTask, listAiTasks, startAiTask, taskModelOf } from '../local/ai-tasks'
import { settings } from '../state/settings'
import { isAbortError, toError } from './run-errors'

export interface CritiqueStoreOptions {
  cache: CacheRepositories
  /** The diff being reviewed; a new head or ref clears what was shown for the old one. */
  diff: Ref<DiffsPayload | undefined>
  /** Where findings are posted; `undefined` when the source takes no reviews. */
  getReviews: () => Pick<DiffsStoreReviews, 'canWrite' | 'postReview'> | undefined
  /** Where lenses come from; `undefined` offers none. */
  lenses?: LensSource
}

const SEVERITIES: CritiqueSeverity[] = ['bug', 'risk', 'nit']

/**
 * The cache variant a critique is saved under.
 * @param headSha - The reviewed commit.
 * @param resolved - The engine, model and effort it ran with.
 * @param lens - The lens it ran through; `undefined` for the general review.
 * @returns The variant key.
 */
export function critiqueVariant(headSha: string | undefined, resolved: ResolvedModel, lens: string | undefined): string {
  return [headSha ?? '', resolved.model.provider, resolved.model.id, resolved.effort ?? '', lens ?? ''].join(':')
}

function targetOf(finding: CritiqueFinding): ReviewDraftTarget {
  const { path, side, line, startLine } = finding
  return { path, side, line, ...(startLine !== undefined ? { startLine, startSide: side } : {}) }
}

function defaultSelection(result: CritiqueResult | undefined): Set<string> {
  return new Set(result?.findings.filter(finding => finding.severity !== 'nit').map(finding => finding.id))
}

/**
 * The AI review of one diff, run through the CLI engine the settings name.
 * @param opts - The cache, the diff and where to post and find lenses.
 * @returns The reactive critique store; `abort` stops following a running review.
 */
export function createCritiqueStore(opts: CritiqueStoreOptions): DiffsStoreCritique {
  const { cache, diff, getReviews, lenses } = opts
  const result = shallowRef<CritiqueResult>()
  const isRunning = ref(false)
  const error = ref<Error>()
  const activities = shallowRef<TrackedActivity[]>([])
  const outcome = ref<AiRunOutcome>()
  const lens = ref<string>()
  const severities = ref<CritiqueSeverity[]>([...SEVERITIES])
  const selected = ref(new Set<string>())
  const posted = ref(new Set<string>())
  const isPosting = ref(false)
  const postError = ref<Error>()
  const focused = ref<CritiqueFocus>()
  const startedAt = ref<number>()
  const now = ref(0)
  let controller: AbortController | undefined
  /** The server task this page follows; following stops on `detach`, the task only on `abort`. */
  let serverTask: { rpc: NonNullable<ReturnType<typeof getAiRpc>>, id: string } | undefined
  let timer: ReturnType<typeof setInterval> | undefined
  let focusNonce = 0

  const available = computed(() => isCliEngine(resolveModel(settings.value.llm)?.model.provider ?? ''))
  const visibleFindings = computed(() => result.value?.findings.filter(finding => severities.value.includes(finding.severity)) ?? [])
  const toPost = computed(() => visibleFindings.value.filter(finding => selected.value.has(finding.id) && !posted.value.has(finding.id)))
  const canPost = computed(() => {
    const reviews = getReviews()
    return !!reviews?.canWrite && toPost.value.length > 0 && !isPosting.value && result.value?.headSha === diff.value?.head?.sha
  })
  const elapsed = computed(() => isRunning.value && startedAt.value !== undefined ? Math.max(0, Math.floor((now.value - startedAt.value) / 1000)) : 0)
  const defaultBody = computed(() => result.value ? critiqueReviewBody(result.value) : '')

  function show(next: CritiqueResult | undefined) {
    result.value = next
    lens.value = next?.lens
    selected.value = defaultSelection(next)
    posted.value = new Set()
    postError.value = undefined
  }

  function stopTimer() {
    clearInterval(timer)
    timer = undefined
  }

  /** Stops watching the review; a server task still finishes and saves it. */
  function detach() {
    controller?.abort()
    controller = undefined
    serverTask = undefined
    isRunning.value = false
    stopTimer()
  }

  /** Stops the review, on the server too. */
  function abort() {
    if (controller)
      outcome.value = 'stopped'
    if (serverTask)
      void cancelAiTask(serverTask.rpc, serverTask.id)
    detach()
  }

  // A different diff or head makes the shown findings point at lines that may have moved.
  watch(() => diff.value && `${serializeRef(diff.value.ref)}@${diff.value.head?.sha ?? ''}`, async (subject) => {
    detach()
    error.value = undefined
    activities.value = []
    startedAt.value = outcome.value = undefined
    show(undefined)
    const loaded = diff.value
    if (!subject || !loaded)
      return
    const saved = await cache.critiques.latest(serializeRef(loaded.ref), loaded.head?.sha).catch(() => undefined)
    if (diff.value === loaded && !isRunning.value && !result.value)
      show(saved)
    // A review still running on the server for this commit, e.g. started before a reload.
    const rpc = getAiRpc()
    if (!rpc || diff.value !== loaded)
      return
    const tasks = await listAiTasks(rpc, serializeRef(loaded.ref)).catch(() => [])
    const running = tasks.find(task => task.kind === 'critique' && task.status === 'running' && task.variant?.startsWith(`${loaded.head?.sha ?? ''}:`))
    if (running && diff.value === loaded && !isRunning.value)
      void followServer(rpc, loaded, running.id, running.lens, running.startedAt)
  }, { immediate: true })

  async function followServer(rpc: NonNullable<ReturnType<typeof getAiRpc>>, loaded: DiffsPayload, id: string, lensName: string | undefined, since: number) {
    const current = new AbortController()
    startRunning(current)
    startedAt.value = since
    lens.value = lensName
    serverTask = { rpc, id }
    try {
      const done = await followAiTask(rpc, id, {
        signal: current.signal,
        onActivity: (incoming) => {
          if (controller === current)
            activities.value = upsertActivities(activities.value, incoming)
        },
      })
      if (controller !== current)
        return
      if (done.status === 'done' && done.result) {
        if (diff.value === loaded)
          show(JSON.parse(done.result) as CritiqueResult)
      }
      else if (done.status === 'cancelled') {
        outcome.value = 'stopped'
      }
      else {
        error.value = new Error(done.error ?? t('analyze.failed'))
      }
    }
    catch (err) {
      if (controller === current && !isAbortError(err))
        error.value = toError(err)
    }
    finally {
      if (controller === current)
        serverTask = undefined
      stopRunning(current)
    }
  }

  async function lensOf(name: string | undefined): Promise<{ name: string, instructions: string } | undefined> {
    if (!name)
      return undefined
    const instructions = await lenses?.get(name)
    if (!instructions)
      throw new Error(t('critique.lensMissing', { name }))
    return { name, instructions }
  }

  function startRunning(current: AbortController) {
    controller = current
    isRunning.value = true
    startedAt.value = now.value = Date.now()
    timer = setInterval(() => {
      now.value = Date.now()
    }, 1000)
    activities.value = []
    outcome.value = undefined
  }

  function stopRunning(current: AbortController) {
    if (controller !== current)
      return
    controller = undefined
    isRunning.value = false
    outcome.value = error.value ? 'failed' : outcome.value === 'stopped' ? 'stopped' : 'done'
    stopTimer()
  }

  async function run(options: { lens?: string, force?: boolean } = {}) {
    const loaded = diff.value
    const resolved = resolveModel(settings.value.llm)
    if (!loaded || !resolved || !isCliEngine(resolved.model.provider))
      return
    abort()
    error.value = undefined
    lens.value = options.lens
    const scope = serializeRef(loaded.ref)
    const variant = critiqueVariant(loaded.head?.sha, resolved, options.lens)
    const saved = options.force ? undefined : await cache.critiques.get(scope, variant).catch(() => undefined)
    if (saved) {
      if (diff.value === loaded)
        show(saved)
      return
    }

    // The local server runs the review as a task that outlives this page and saves it to the cache.
    const rpc = getAiRpc()
    const model = taskModelOf(resolved)
    if (rpc && model) {
      let id: string
      try {
        id = await startAiTask(rpc, { kind: 'critique', key: scope, model, variant, lens: options.lens })
      }
      catch (err) {
        error.value = toError(err)
        outcome.value = 'failed'
        return
      }
      await followServer(rpc, loaded, id, options.lens, Date.now())
      return
    }

    const current = new AbortController()
    startRunning(current)
    try {
      const next = await runCritique(loaded, resolved, {
        lens: await lensOf(options.lens),
        signal: current.signal,
        onActivity: (incoming) => {
          if (controller === current)
            activities.value = upsertActivities(activities.value, incoming)
        },
      })
      // Saved even when the view moved on, so the paid review is there on return.
      await cache.critiques.set(scope, variant, next).catch(() => {})
      if (controller === current && diff.value === loaded)
        show(next)
    }
    catch (err) {
      if (controller === current && !isAbortError(err))
        error.value = toError(err)
    }
    finally {
      stopRunning(current)
    }
  }

  async function post(body: string) {
    const reviews = getReviews()
    const findings = toPost.value
    if (!reviews || findings.length === 0 || isPosting.value)
      return
    isPosting.value = true
    postError.value = undefined
    try {
      await reviews.postReview(body, findings.map(finding => ({ target: targetOf(finding), body: findingCommentBody(finding) })))
      const ids = findings.map(finding => finding.id)
      posted.value = new Set([...posted.value, ...ids])
      const remaining = new Set(selected.value)
      ids.forEach(id => remaining.delete(id))
      selected.value = remaining
    }
    catch (err) {
      postError.value = toError(err)
    }
    finally {
      isPosting.value = false
    }
  }

  function setSelected(id: string, value: boolean) {
    const next = new Set(selected.value)
    value ? next.add(id) : next.delete(id)
    selected.value = next
  }

  function selectAll(value: boolean) {
    const next = new Set(selected.value)
    for (const finding of visibleFindings.value) {
      if (!posted.value.has(finding.id))
        value ? next.add(finding.id) : next.delete(finding.id)
    }
    selected.value = next
  }

  return reactive({
    available,
    hasLenses: !!lenses,
    result,
    isRunning,
    error,
    activities,
    startedAt,
    outcome,
    elapsed,
    lens,
    severities,
    visibleFindings,
    selected,
    posted,
    toPost,
    canPost,
    isPosting,
    postError,
    defaultBody,
    focused,
    listLenses: async (): Promise<ReviewLens[]> => lenses ? await lenses.list() : [],
    run,
    abort,
    detach,
    setSeverities: (next: CritiqueSeverity[]) => { severities.value = SEVERITIES.filter(severity => next.includes(severity)) },
    setSelected,
    selectAll,
    focus: (finding: CritiqueFinding) => { focused.value = { id: finding.id, path: finding.path, nonce: ++focusNonce } },
    post,
  }) as DiffsStoreCritique
}
