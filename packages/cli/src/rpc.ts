import type { AnalyticsSummary } from '@pulls.review/core/analytics'
import type { Env } from '@pulls.review/core/env'
import type { AiAskThread, AiInboxChat, AiJobSnapshot, AiTaskRequest, AiTaskSnapshot, CliModelCatalog, MyPull } from '@pulls.review/core/local-rpc'
import type { DiffSource } from '@pulls.review/core/types'
import type { Driver } from 'unstorage'
import type { TaskManager } from './ai/tasks'
import { createCacheRepositories, SERVER_CACHE_BUDGET } from '@pulls.review/core/cache'
import { createLocalSource, readRepoInfo } from '@pulls.review/core/local'
import { AI_FOLLOW_UPS, INBOX_KEY, LLM_ENGINES, LOCAL_RPC, MY_PULL_STATES, REVIEW_LENS_NAME } from '@pulls.review/core/local-rpc'
import { DiffsPayloadSchema } from '@pulls.review/core/types'
import { defineRpcFunction } from 'devframe'
import { createStorage } from 'unstorage'
import * as v from 'valibot'
import { listCliModels } from './ai/catalog'
import { cancelAiJob, listAiJobs, runAiJob, startAiJob, waitAiJob } from './ai/jobs'
import { createTaskManager, defaultTaskRunners } from './ai/tasks'
import { analyticsRange, summarizeAnalytics } from './analytics'
import { resolveGithubToken } from './credentials'
import { checkoutOf } from './host'
import { getLens, listLenses } from './lenses'
import { cachedMyPulls, classifyMyPulls, listMyPulls } from './my-pulls'
import { warmRepoPulls } from './native-review'

export interface LocalRpcOptions {
  /** Any directory inside the repository under review; without one, every local route answers `NO_CHECKOUT`. */
  cwd?: string
  /** Where the browser's cache lives (see `createRepoCacheDriver`). */
  driver: Driver
  env: Env
  /** Runs AI tasks that outlive the page; built over `driver` when left out. */
  tasks?: TaskManager
}

const target = v.object({ target: v.string() })

/**
 * The account's records and what agents posted (plan 13) live in the same store as the page's cache, but the page's
 * storage calls never read, list, write or remove them: they are reached only through the phone's account and posted
 * calls. That covers a posted diff's own cache entries too (`pr-meta:posted:<id>`, `tour:posted:<id>`, …).
 */
const PRIVATE_KEY = /^(?:account[:-]|agent-key:|posted-(?:diff|agent|patch):)|(?:^|:)posted:/i

/** A posted diff's key or target; the web page reaches none of them (Tavi, 16 Oct 14:17: "just block access for everyone"). */
const POSTED_KEY = /^posted:/i
const ONLY_IN_THE_APP = 'Posted diffs open only in the Pully app.'

/** [item] when it is not about a posted diff; one the phone started on a posted diff refuses. */
function notPosted<T extends { key?: string }>(item: T): T {
  if (item.key && POSTED_KEY.test(item.key))
    throw new Error(ONLY_IN_THE_APP)
  return item
}

/** A diff key the page may name: any but a posted diff's. */
const pageKey = v.pipe(v.string(), v.check(key => !POSTED_KEY.test(key), ONLY_IN_THE_APP))

/** [key] when the page may write it; a private record refuses. */
function shared(key: string): string {
  if (PRIVATE_KEY.test(key))
    throw new Error('That key is not the page\'s to change.')
  return key
}

/** What a page's diff is served by: a git target's source, which reads files and fingerprints. */
type ServedSource = DiffSource & Required<Pick<DiffSource, 'fingerprint' | 'loadFile'>>

/** A return schema for values the server builds itself, so only their type needs declaring. */
function trusted<T>() {
  return v.custom<T>(() => true)
}

const aiJobRequest = v.object({
  engine: v.picklist(LLM_ENGINES),
  model: v.optional(v.string()),
  effort: v.optional(v.string()),
  system: v.string(),
  prompt: v.string(),
  schema: v.optional(v.unknown()),
  label: v.optional(v.string()),
  key: v.optional(pageKey),
  context: v.optional(v.object({
    owner: v.pipe(v.string(), v.regex(/^[\w.-]+$/)),
    repo: v.pipe(v.string(), v.regex(/^[\w.-]+$/)),
    number: v.optional(v.number()),
    title: v.optional(v.string()),
    headSha: v.pipe(v.string(), v.regex(/^[0-9a-f]{7,64}$/i)),
    baseSha: v.optional(v.string()),
    files: v.array(v.object({
      path: v.string(),
      previousPath: v.optional(v.string()),
      status: v.optional(v.string()),
      patch: v.optional(v.string()),
    })),
  })),
})

const taskModel = v.object({ engine: v.picklist(LLM_ENGINES), model: v.optional(v.string()), effort: v.optional(v.string()) })
const taskSelection = v.object({ path: v.string(), side: v.picklist(['additions', 'deletions']), startLine: v.number(), endLine: v.number() })
const taskBase = { key: v.pipe(pageKey, v.minLength(1)), model: taskModel }
const aiTaskRequest = v.variant('kind', [
  v.object({ kind: v.literal('analyze'), ...taskBase, locale: v.string(), then: v.optional(v.array(v.picklist(AI_FOLLOW_UPS))) }),
  v.object({ kind: v.literal('critique'), ...taskBase, variant: v.string(), lens: v.optional(v.pipe(v.string(), v.regex(REVIEW_LENS_NAME))) }),
  v.object({ kind: v.literal('ask'), ...taskBase, threadId: v.pipe(v.string(), v.regex(/^[\w-]{1,80}$/)), selection: taskSelection, question: v.pipe(v.string(), v.trim(), v.minLength(1)) }),
  v.object({ kind: v.literal('chat'), ...taskBase, locale: v.string(), text: v.optional(v.string()), retry: v.optional(v.boolean()) }),
  v.object({ kind: v.literal('tour'), ...taskBase, locale: v.string() }),
  v.object({ kind: v.literal('brief'), ...taskBase, locale: v.string() }),
  v.object({
    kind: v.literal('inbox'),
    key: v.literal(INBOX_KEY),
    model: taskModel,
    locale: v.string(),
    question: v.pipe(v.string(), v.trim(), v.minLength(1), v.maxLength(4000)),
    retry: v.optional(v.boolean()),
    skip: v.optional(v.array(v.string())),
  }),
])
const diffKey = v.object({ key: pageKey })

/**
 * Every function the `PR_LOCAL` SPA calls. Only a client devframe has trusted
 * reaches them; inputs are still validated, since they decide what `git` runs.
 */
export function localRpcFunctions({ cwd, driver, env, tasks: givenTasks }: LocalRpcOptions) {
  const storage = createStorage({ driver })
  const cache = createCacheRepositories(storage, SERVER_CACHE_BUDGET)
  const tasks = givenTasks ?? createTaskManager({ cache, storage, runners: defaultTaskRunners(getLens) })
  const source = (text: string): ServedSource => {
    if (POSTED_KEY.test(text))
      throw new Error(ONLY_IN_THE_APP)
    return createLocalSource({ cwd: checkoutOf({ cwd }), target: text })
  }
  return [
    defineRpcFunction({ name: LOCAL_RPC.repoInfo, type: 'query', handler: () => readRepoInfo(checkoutOf({ cwd })) }),
    defineRpcFunction({ name: LOCAL_RPC.sourceKey, type: 'query', args: [target], returns: v.string(), handler: ({ target }) => source(target).key() }),
    defineRpcFunction({ name: LOCAL_RPC.sourceFetch, type: 'query', args: [target], returns: DiffsPayloadSchema, handler: ({ target }) => source(target).fetch() }),
    defineRpcFunction({ name: LOCAL_RPC.sourceFingerprint, type: 'query', args: [target], returns: v.string(), handler: ({ target }) => source(target).fingerprint() }),
    defineRpcFunction({
      name: LOCAL_RPC.sourceLoadFile,
      type: 'query',
      args: [v.object({ target: v.string(), path: v.string(), sha: v.string() })],
      returns: v.optional(v.string()),
      handler: ({ target, path, sha }) => source(target).loadFile(path, sha),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageGetItem,
      type: 'query',
      args: [v.object({ key: v.string() })],
      returns: v.nullable(v.string()),
      handler: async ({ key }) => {
        const value = PRIVATE_KEY.test(key) ? null : await driver.getItem(key, {})
        return typeof value === 'string' ? value : null
      },
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageSetItem,
      type: 'action',
      args: [v.object({ key: v.string(), value: v.string() })],
      returns: v.void(),
      handler: async ({ key, value }) => {
        await driver.setItem?.(shared(key), value, {})
      },
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageRemoveItem,
      type: 'action',
      args: [v.object({ key: v.string() })],
      returns: v.void(),
      handler: async ({ key }) => {
        await driver.removeItem?.(shared(key), {})
      },
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageGetKeys,
      type: 'query',
      args: [v.object({ base: v.string() })],
      returns: v.array(v.string()),
      handler: async ({ base }) => (await driver.getKeys(base, {})).filter(key => !PRIVATE_KEY.test(key)),
    }),
    defineRpcFunction({ name: LOCAL_RPC.githubToken, type: 'query', handler: () => resolveGithubToken(env) }),
    defineRpcFunction({ name: LOCAL_RPC.myPulls, type: 'query', args: [v.object({ state: v.picklist(MY_PULL_STATES) })], returns: trusted<MyPull[]>(), handler: ({ state }) => listMyPulls(state) }),
    defineRpcFunction({ name: LOCAL_RPC.lensList, type: 'query', handler: () => listLenses() }),
    defineRpcFunction({
      name: LOCAL_RPC.lensGet,
      type: 'query',
      args: [v.object({ name: v.pipe(v.string(), v.regex(REVIEW_LENS_NAME)) })],
      returns: v.optional(v.string()),
      handler: ({ name }) => getLens(name),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.llmRun,
      type: 'action',
      args: [v.object({
        engine: v.picklist(LLM_ENGINES),
        model: v.optional(v.string()),
        effort: v.optional(v.string()),
        system: v.string(),
        prompt: v.string(),
        schema: v.optional(v.unknown()),
      })],
      returns: v.string(),
      handler: async request => (await runAiJob(request)).text,
    }),
    defineRpcFunction({
      name: LOCAL_RPC.aiModels,
      type: 'query',
      args: [v.object({ engine: v.picklist(LLM_ENGINES), refresh: v.optional(v.boolean()) })],
      returns: trusted<CliModelCatalog>(),
      handler: ({ engine, refresh }) => listCliModels(engine, refresh),
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiJobStart, type: 'action', args: [aiJobRequest], returns: v.string(), handler: request => startAiJob(request) }),
    defineRpcFunction({
      name: LOCAL_RPC.aiJobWait,
      type: 'query',
      args: [v.object({ id: v.string(), after: v.number() })],
      returns: trusted<AiJobSnapshot>(),
      handler: async ({ id, after }) => notPosted(await waitAiJob(id, after)),
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiJobCancel, type: 'action', args: [v.object({ id: v.string() })], returns: v.boolean(), handler: ({ id }) => cancelAiJob(id) }),
    defineRpcFunction({ name: LOCAL_RPC.aiJobList, type: 'query', handler: () => listAiJobs().filter(job => !POSTED_KEY.test(job.key ?? '')) }),
    defineRpcFunction({
      name: LOCAL_RPC.myPullsCached,
      type: 'query',
      args: [v.object({ state: v.picklist(MY_PULL_STATES), revalidate: v.optional(v.boolean()), classify: v.optional(v.boolean()) })],
      returns: v.unknown(),
      handler: async ({ state, revalidate, classify }) => {
        const snapshot = cachedMyPulls(state, revalidate)
        // Jev sorts open pull requests only when a client asks: the phone does while its Inbox is sorted by risk or
        // grouped by kind, which need every one of them.
        if (classify && state === 'open')
          classifyMyPulls()
        // The repositories in the Inbox are the ones the phone opens next, so their lists are fetched ahead (H14). A read
        // that must not reach GitHub (`revalidate: false`) does not start that either.
        if (snapshot && state === 'open' && revalidate !== false)
          warmRepoPulls(snapshot.pulls)
        return snapshot
      },
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskStart, type: 'action', args: [aiTaskRequest], returns: v.string(), handler: request => tasks.start(request as AiTaskRequest) }),
    defineRpcFunction({
      name: LOCAL_RPC.aiTaskWait,
      type: 'query',
      args: [v.object({ id: v.string(), after: v.number() })],
      returns: trusted<AiTaskSnapshot>(),
      handler: async ({ id, after }) => notPosted(await tasks.wait(id, after)),
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskCancel, type: 'action', args: [v.object({ id: v.string() })], returns: v.boolean(), handler: ({ id }) => tasks.cancel(id) }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskList, type: 'query', args: [diffKey], returns: trusted<AiTaskSnapshot[]>(), handler: ({ key }) => tasks.list(key) }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskListAll, type: 'query', args: [v.object({})], returns: trusted<AiTaskSnapshot[]>(), handler: () => tasks.listAll().filter(task => !POSTED_KEY.test(task.key)) }),
    defineRpcFunction({ name: LOCAL_RPC.askThreads, type: 'query', args: [diffKey], returns: trusted<AiAskThread[]>(), handler: ({ key }) => tasks.threads(key) }),
    defineRpcFunction({ name: LOCAL_RPC.inboxChat, type: 'query', args: [v.object({})], returns: trusted<AiInboxChat>(), handler: () => tasks.inboxChat() }),
    defineRpcFunction({ name: LOCAL_RPC.inboxChatClear, type: 'action', args: [v.object({})], returns: v.void(), handler: () => tasks.clearInboxChat() }),
    defineRpcFunction({ name: LOCAL_RPC.analyticsSummary, type: 'query', args: [v.object({ from: v.optional(v.string()), to: v.optional(v.string()), days: v.optional(v.number()) })], returns: trusted<AnalyticsSummary>(), handler: input => summarizeAnalytics(analyticsRange(input), env) }),
    defineRpcFunction({
      name: LOCAL_RPC.askThreadClose,
      type: 'action',
      args: [v.object({ key: pageKey, threadId: v.string() })],
      returns: v.void(),
      handler: ({ key, threadId }) => tasks.closeThread(key, threadId),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageGetItems,
      type: 'query',
      args: [v.object({ keys: v.array(v.string()) })],
      returns: v.array(v.nullable(v.string())),
      handler: ({ keys }) => Promise.all(keys.map(async (key) => {
        const value = PRIVATE_KEY.test(key) ? null : await driver.getItem(key, {})
        return typeof value === 'string' ? value : null
      })),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageSetItems,
      type: 'action',
      args: [v.object({ items: v.array(v.object({ key: v.string(), value: v.string() })) })],
      returns: v.void(),
      handler: async ({ items }) => {
        await Promise.all(items.map(({ key, value }) => driver.setItem?.(shared(key), value, {})))
      },
    }),
  ]
}
