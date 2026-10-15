import type { CacheRepositories, LlmSession, PrCacheEntry } from '@pulls.review/core/cache'
import type { Env } from '@pulls.review/core/env'
import type { AiPrContext, MyPull } from '@pulls.review/core/local-rpc'
import type { DiffsPayload, GroupedResult } from '@pulls.review/core/types'
import process from 'node:process'
import { defaultLlmSettings, ENGINE_SETTINGS, resolveModel } from '@pulls.review/core/analyze'
import { archiveChat } from '@pulls.review/core/cache'
import { createGithubPullRequestSource } from '@pulls.review/core/github'
import { DEFAULT_LOCALE } from '@pulls.review/core/locales'
import { serializeRef, staticCredentials } from '@pulls.review/core/types'
import { runAiJob } from './ai/jobs'
import { track } from './analytics'
import { resolveGithubToken } from './credentials'
import { prepareJev } from './jev-review'
import { listMyPulls } from './my-pulls'
import { serverSession } from './server-session'

export const PREANALYZE_ENGINES = ['claude-code', 'codex', 'pi'] as const
export type PreanalyzeEngine = typeof PREANALYZE_ENGINES[number]

/**
 * @param value A `--preanalyze-engine` argument.
 * @returns Whether it names an engine pre-analysis can run.
 */
export function isPreanalyzeEngine(value: string | undefined): value is PreanalyzeEngine {
  return (PREANALYZE_ENGINES as readonly (string | undefined)[]).includes(value)
}

/** How often the review queue is checked for pull requests to analyze. */
export const PREANALYZE_INTERVAL_MS = 10 * 60_000
/** Larger pull requests are left for the reviewer to analyze by hand. */
export const PREANALYZE_MAX_FILES = 300

/** What the cache holds for a pull request; `undefined` when it holds nothing. */
export interface CachedAnalysisState {
  /** The head commit of the cached diff. */
  headSha: string
  /** The entry carries an AI grouping. */
  hasLlm: boolean
}

/**
 * @param pull A pull request from the signed-in user's open list.
 * @param cached The cache's copy of it.
 * @returns Whether it waits on the user's review and has no AI grouping for its head commit.
 */
export function needsPreanalysis(pull: MyPull, cached: CachedAnalysisState | undefined): boolean {
  const requested = pull.requestedFromMe ?? pull.role === 'review-requested'
  if (pull.state !== 'open' || !requested || !pull.headSha || (pull.changedFiles ?? 0) > PREANALYZE_MAX_FILES)
    return false
  return !cached?.hasLlm || cached.headSha !== pull.headSha
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export interface PreanalyzerDeps {
  /** The signed-in user's open pull requests. */
  listPulls: () => Promise<MyPull[]>
  /** The cache the browser reads. */
  cache: CacheRepositories
  fetchDiff: (pull: MyPull) => Promise<DiffsPayload>
  analyze: (diff: DiffsPayload, pull: MyPull, signal: AbortSignal) => Promise<{ result: GroupedResult, transcript: LlmSession['messages'] }>
  log: (line: string) => void
}

export interface Preanalyzer {
  /** Analyzes every pull request in the review queue that needs it, one at a time; resolves when done or stopped. */
  round: () => Promise<void>
  /** Cancels the analysis in flight; later rounds do nothing. */
  stop: () => void
}

/**
 * Each pull request is tried once per head commit: a failure waits for a new push or a
 * server restart rather than paying for the same run every round.
 *
 * @param deps Where pull requests, diffs and analyses come from, and the cache to fill.
 * @returns A pre-analyzer whose rounds the caller schedules.
 */
/**
 * @cc [owner:OctavianTocan,label:architecture] preanalysis-commits-observed-head
 * Preanalysis MUST save its diff, analysis and transcript only when the stored head has
 * not changed since the run began. Its writes MUST NOT count as views
 * (server-store-answers-first; review finding 4, 15 Oct 2026).
 */
export function createPreanalyzer(deps: PreanalyzerDeps): Preanalyzer {
  const { listPulls, cache, fetchDiff, analyze, log } = deps
  const controller = new AbortController()
  const attempted = new Set<string>()
  let running: Promise<void> | undefined

  async function analyzeOne(pull: MyPull, key: string, cached: PrCacheEntry | undefined) {
    const reused = cached && cached.headSha === pull.headSha ? cached.diff : undefined
    const diff = reused ?? await fetchDiff(pull)
    const { result, transcript } = await analyze(diff, pull, controller.signal)
    if (controller.signal.aborted)
      return
    // Commit the diff, analysis and transcript together against the head observed before the run.
    // A later head keeps its own analysis and conversation; stale output is never attached to it.
    const saved = await cache.diffs.updateDiff(key, (existing) => {
      if (existing && existing.headSha !== cached?.headSha)
        return undefined
      const previous = existing?.llmSession as LlmSession | undefined
      return {
        diff,
        headSha: diff.head?.sha ?? pull.headSha ?? '',
        analyzedBy: { ...existing?.analyzedBy, llm: result },
        llmSession: { messages: transcript, chatStartIndex: transcript.length, archive: previous && archiveChat(previous).archive },
      }
    }, { viewed: false })
    if (!saved)
      return
    log(`pre-analyzed ${key}`)
  }

  async function visit(pull: MyPull) {
    const key = serializeRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })
    const id = `${key}@${pull.headSha}`
    if (attempted.has(id))
      return
    const cached = await cache.diffs.get(key)
    if (!needsPreanalysis(pull, cached && { headSha: cached.headSha, hasLlm: !!cached.analyzedBy.llm }))
      return
    attempted.add(id)
    const started = Date.now()
    try {
      await analyzeOne(pull, key, cached)
      track('preanalyze.run', { status: 'done', ms: Date.now() - started })
    }
    catch (error) {
      if (!controller.signal.aborted) {
        log(`pre-analysis of ${key} failed: ${messageOf(error)}`)
        track('preanalyze.run', { status: 'failed', ms: Date.now() - started, message: messageOf(error) })
      }
    }
  }

  async function run() {
    let pulls: MyPull[]
    try {
      pulls = await listPulls()
    }
    catch (error) {
      log(`pre-analysis skipped: ${messageOf(error)}`)
      return
    }
    for (const pull of pulls) {
      if (controller.signal.aborted)
        return
      await visit(pull)
    }
  }

  return {
    round() {
      running ??= run().finally(() => {
        running = undefined
      })
      return running
    },
    stop: () => controller.abort(),
  }
}

export interface PreanalyzeOptions {
  /** The repository whose cache directory the browser reads. */
  cwd: string
  env: Env
  engine: PreanalyzeEngine
  /** The engine's model; its default when unset. */
  model?: string
  log?: (line: string) => void
}

function prContext(pull: MyPull, diff: DiffsPayload): AiPrContext {
  return {
    owner: pull.owner,
    repo: pull.repo,
    number: pull.number,
    title: diff.title,
    headSha: diff.head?.sha ?? pull.headSha ?? '',
    baseSha: diff.base?.sha,
    files: diff.files.map(file => ({ path: file.path, previousPath: file.previousPath, status: file.status })),
  }
}

/**
 * Analyzes the pull requests waiting on the user's review now and every
 * `PREANALYZE_INTERVAL_MS`, so their pages open with an AI grouping already there.
 *
 * @param options The repository, the environment holding the GitHub token, and the engine to run.
 * @returns Stops the schedule and any analysis in flight.
 */
export async function startPreanalyze(options: PreanalyzeOptions): Promise<() => void> {
  const { cwd, env, engine, model, log = line => process.stderr.write(`pulls.review: ${line}\n`) } = options
  const { runLlmAnalysis, setCliRunner } = await import('@pulls.review/core/llm')
  setCliRunner(runAiJob)
  const settings = { ...defaultLlmSettings, provider: engine }
  if (model)
    Object.assign(settings, { [ENGINE_SETTINGS[engine].model]: model })
  // The CLI engines need no API key, so a model always resolves.
  const resolved = resolveModel(settings)!

  const { storage, cache } = await serverSession(cwd)
  const preanalyzer = createPreanalyzer({
    listPulls: () => listMyPulls('open'),
    cache,
    fetchDiff: async pull => createGithubPullRequestSource(
      { owner: pull.owner, repo: pull.repo, number: String(pull.number) },
      staticCredentials(await resolveGithubToken(env)),
    ).fetch(),
    analyze: async (diff, pull, signal) => {
      // An analysis is when Jev reads a pull request: its files rated and its kind and risk sorted, for the model's triage.
      await prepareJev(diff, { storage })
      return runLlmAnalysis(diff, resolved, DEFAULT_LOCALE, { signal, context: prContext(pull, diff), mentions: true })
    },
    log,
  })

  log(`pre-analyzing pull requests that request your review with ${engine} (${resolved.model.id}) every ${PREANALYZE_INTERVAL_MS / 60_000} minutes`)
  void preanalyzer.round()
  const timer = setInterval(() => void preanalyzer.round(), PREANALYZE_INTERVAL_MS)
  timer.unref()
  return () => {
    clearInterval(timer)
    preanalyzer.stop()
  }
}
