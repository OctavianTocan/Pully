import type { CacheRepositories, LlmSession } from '@pulls.review/core/cache'
import type { Env } from '@pulls.review/core/env'
import type { AiAskThread, AiInboxChat, AiJobSnapshot, AiTaskRequest, AiTaskSnapshot, CliModelCatalog, MyPull } from '@pulls.review/core/local-rpc'
import type { Driver, Storage } from 'unstorage'
import type { TaskManager } from './ai/tasks'
import { createCacheRepositories, LlmSessionSchema, SERVER_CACHE_BUDGET, storageTransaction } from '@pulls.review/core/cache'
import { createLocalSource, readRepoInfo } from '@pulls.review/core/local'
import { AI_FOLLOW_UPS, INBOX_KEY, LLM_ENGINES, LOCAL_RPC, MY_PULL_STATES, REVIEW_LENS_NAME } from '@pulls.review/core/local-rpc'
import { DiffsPayloadSchema, GroupedResultSchema, ReviewDataSchema } from '@pulls.review/core/types'
import { defineRpcFunction } from 'devframe'
import { createStorage } from 'unstorage'
import * as v from 'valibot'
import { listCliModels } from './ai/catalog'
import { cancelAiJob, listAiJobs, runAiJob, startAiJob, waitAiJob } from './ai/jobs'
import { createTaskManager, defaultTaskRunners } from './ai/tasks'
import { resolveGithubToken } from './credentials'
import { getLens, listLenses } from './lenses'
import { cachedMyPulls, classifyMyPulls, listMyPulls } from './my-pulls'
import { warmRepoPulls } from './native-review'

export interface LocalRpcOptions {
  /** Any directory inside the repository under review. */
  cwd: string
  /** Where the browser's cache lives (see `createRepoCacheDriver`). */
  driver: Driver
  env: Env
  /** Runs AI tasks that outlive the page; built over `driver` when left out. */
  tasks?: TaskManager
  storage?: Storage
  cache?: CacheRepositories
}

const target = v.object({ target: v.string() })

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
  key: v.optional(v.string()),
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
const taskBase = { key: v.pipe(v.string(), v.minLength(1)), model: taskModel }
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
const diffKey = v.object({ key: v.string() })

/**
 * Every function the `PR_LOCAL` SPA calls. Only a client devframe has trusted
 * reaches them; inputs are still validated, since they decide what `git` runs.
 */
export function localRpcFunctions({ cwd, driver, env, tasks: givenTasks, storage: givenStorage, cache: givenCache }: LocalRpcOptions) {
  const source = (text: string) => createLocalSource({ cwd, target: text })
  const storage = givenStorage ?? createStorage({ driver })
  const cache = givenCache ?? createCacheRepositories(storage, SERVER_CACHE_BUDGET)
  const tasks = givenTasks ?? createTaskManager({ cache, storage, runners: defaultTaskRunners(getLens) })
  // Browser cache writes are optimistic: a stale tab must never replace a server task's newer state.
  async function mutate(items: { key: string, value?: string, expected: string | null }[]) {
    const lockKeys = [...new Set(items.map(({ key }) => /^(?:pr-meta|pr-body):/.test(key)
      ? `diff:${key.replace(/^[^:]+:/, '')}`
      : `storage:${key}`))].sort()
    async function locked(index: number): Promise<void> {
      if (index < lockKeys.length)
        return storageTransaction(storage, lockKeys[index]!, () => locked(index + 1))
      for (const item of items) {
        if (/^(?:pr-meta|pr-body):/.test(item.key))
          throw new Error('Use the server diff cache operations to change review data.')
        const current = await driver.getItem(item.key, {})
        if ((typeof current === 'string' ? current : null) !== item.expected)
          throw new Error('The cached data changed on the server. Reload before saving.')
      }
      for (const { key, value } of items) {
        if (value === undefined)
          await driver.removeItem?.(key, {})
        else
          await driver.setItem?.(key, value, {})
      }
    }
    await locked(0)
  }
  return [
    defineRpcFunction({
      name: LOCAL_RPC.cacheDiff,
      type: 'action',
      args: [v.object({
        method: v.picklist(['get', 'getMeta', 'putDiff', 'touch', 'setChangedSinceReviewed', 'setAnalyzedResult', 'setLlmSession', 'setSharedComment', 'setReviewData']),
        key: v.string(),
        values: v.optional(v.array(v.unknown()), []),
      })],
      returns: v.unknown(),
      handler: async ({ method, key, values = [] }) => {
        switch (method) {
          case 'get': return cache.diffs.get(key)
          case 'getMeta': return cache.diffs.getMeta(key)
          case 'touch': return cache.diffs.touch(key)
          case 'putDiff': {
            const diff = v.parse(DiffsPayloadSchema, values[0])
            const headSha = v.parse(v.string(), values[1])
            const expected = v.parse(v.nullable(v.string()), values[2])
            const options = v.parse(v.optional(v.object({ viewed: v.optional(v.boolean()) })), values[3])
            const stored = await cache.diffs.updateDiff(key, current => (current?.headSha ?? null) === expected ? { diff, headSha } : undefined, options)
            if (!stored)
              throw new Error('The cached diff changed. Reload before saving.')
            return stored
          }
          case 'setChangedSinceReviewed': return cache.diffs.setChangedSinceReviewed(key, v.parse(v.array(v.string()), values[0]))
          case 'setAnalyzedResult': return cache.diffs.setAnalyzedResult(key, v.parse(v.picklist(['llm', 'web-llm']), values[0]), v.parse(GroupedResultSchema, values[1]))
          case 'setLlmSession': {
            await cache.diffs.setLlmSession(key, v.parse(v.optional(LlmSessionSchema), values[0]) as LlmSession | undefined, v.parse(v.string(), values[1]))
            return undefined
          }
          case 'setSharedComment': return cache.diffs.setSharedComment(key, v.parse(v.optional(v.object({ id: v.number(), url: v.string() })), values[0]))
          case 'setReviewData': return cache.diffs.setReviewData(key, v.parse(ReviewDataSchema, values[0]))
        }
      },
    }),
    defineRpcFunction({ name: LOCAL_RPC.repoInfo, type: 'query', handler: () => readRepoInfo(cwd) }),
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
        const value = await driver.getItem(key, {})
        return typeof value === 'string' ? value : null
      },
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageSetItem,
      type: 'action',
      args: [v.object({ key: v.string(), value: v.string(), expected: v.nullable(v.string()) })],
      returns: v.void(),
      handler: item => mutate([item]),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageRemoveItem,
      type: 'action',
      args: [v.object({ key: v.string(), expected: v.nullable(v.string()) })],
      returns: v.void(),
      handler: item => mutate([item]),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageGetKeys,
      type: 'query',
      args: [v.object({ base: v.string() })],
      returns: v.array(v.string()),
      handler: ({ base }) => driver.getKeys(base, {}),
    }),
    defineRpcFunction({ name: LOCAL_RPC.githubAuthenticated, type: 'query', handler: async () => !!await resolveGithubToken(env) }),
    defineRpcFunction({
      name: LOCAL_RPC.githubRequest,
      type: 'action',
      args: [v.object({
        path: v.pipe(v.string(), v.regex(/^\/(?:repos\/|user(?:[/?]|$)|search\/|graphql$)/)),
        method: v.optional(v.picklist(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'])),
        body: v.optional(v.record(v.string(), v.unknown())),
        accept: v.optional(v.picklist(['application/vnd.github+json', 'application/vnd.github.raw', 'application/vnd.github.diff', 'application/vnd.github.sha'])),
      })],
      returns: v.object({ status: v.number(), headers: v.record(v.string(), v.string()), body: v.string() }),
      handler: async ({ path, method = 'GET', body, accept }) => {
        // Never permit a caller-controlled origin or follow redirects with server credentials.
        const url = new URL(path, 'https://api.github.com')
        if (url.origin !== 'https://api.github.com' || !/^\/(?:repos\/|user(?:[/?]|$)|search\/|graphql$)/.test(path))
          throw new Error('Unsupported GitHub API path')
        const token = await resolveGithubToken(env)
        const response = await fetch(url, {
          method,
          redirect: 'error',
          headers: {
            'Accept': accept ?? 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
            ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          },
          body: body === undefined ? undefined : JSON.stringify(body),
        }).catch(() => { throw new Error('GitHub request could not be completed') })
        const headers: Record<string, string> = {}
        for (const name of ['content-type', 'link', 'x-oauth-scopes']) {
          const value = response.headers.get(name)
          if (value)
            headers[name] = value
        }
        return { status: response.status, headers, body: await response.text() }
      },
    }),
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
      handler: ({ id, after }) => waitAiJob(id, after),
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiJobCancel, type: 'action', args: [v.object({ id: v.string() })], returns: v.boolean(), handler: ({ id }) => cancelAiJob(id) }),
    defineRpcFunction({ name: LOCAL_RPC.aiJobList, type: 'query', handler: () => listAiJobs() }),
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
      handler: ({ id, after }) => tasks.wait(id, after),
    }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskCancel, type: 'action', args: [v.object({ id: v.string() })], returns: v.boolean(), handler: ({ id }) => tasks.cancel(id) }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskList, type: 'query', args: [diffKey], returns: trusted<AiTaskSnapshot[]>(), handler: ({ key }) => tasks.list(key) }),
    defineRpcFunction({ name: LOCAL_RPC.aiTaskListAll, type: 'query', args: [v.object({})], returns: trusted<AiTaskSnapshot[]>(), handler: () => tasks.listAll() }),
    defineRpcFunction({ name: LOCAL_RPC.askThreads, type: 'query', args: [diffKey], returns: trusted<AiAskThread[]>(), handler: ({ key }) => tasks.threads(key) }),
    defineRpcFunction({ name: LOCAL_RPC.inboxChat, type: 'query', args: [v.object({})], returns: trusted<AiInboxChat>(), handler: () => tasks.inboxChat() }),
    defineRpcFunction({ name: LOCAL_RPC.inboxChatClear, type: 'action', args: [v.object({})], returns: v.void(), handler: () => tasks.clearInboxChat() }),
    defineRpcFunction({
      name: LOCAL_RPC.askThreadClose,
      type: 'action',
      args: [v.object({ key: v.string(), threadId: v.string() })],
      returns: v.void(),
      handler: ({ key, threadId }) => tasks.closeThread(key, threadId),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageGetItems,
      type: 'query',
      args: [v.object({ keys: v.array(v.string()) })],
      returns: v.array(v.nullable(v.string())),
      handler: ({ keys }) => Promise.all(keys.map(async (key) => {
        const value = await driver.getItem(key, {})
        return typeof value === 'string' ? value : null
      })),
    }),
    defineRpcFunction({
      name: LOCAL_RPC.storageSetItems,
      type: 'action',
      args: [v.object({ items: v.array(v.object({ key: v.string(), value: v.string(), expected: v.nullable(v.string()) })) })],
      returns: v.void(),
      handler: ({ items }) => mutate(items),
    }),
  ]
}
