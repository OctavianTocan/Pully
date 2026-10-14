import type { AiTaskSnapshot } from '@pulls.review/core/local-rpc'
import type { TaskRunners } from './tasks'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { createPasteSource } from '@pulls.review/core/paste'
import { normalizeGroupedResult, serializeRef } from '@pulls.review/core/types'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createTaskManager, resolveTaskModel, sumUsage } from './tasks'

const PATCH = `diff --git a/a.ts b/a.ts\nindex 000..111 100644\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n`
const diff = { ...await createPasteSource(PATCH).fetch(), head: { sha: 'h1', ref: 'feat' } }
const KEY = serializeRef(diff.ref)
const model = { engine: 'claude-code' as const, model: 'opus', effort: 'high' }
const grouping = normalizeGroupedResult('llm', { overallSummary: 'Summary', groups: [], schemaVersion: 1 }, 'claude-code/opus')

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function setup(runners: Partial<TaskRunners> = {}) {
  const storage = createStorage({ driver: memoryDriver() })
  const cache = createCacheRepositories(storage)
  await cache.diffs.putDiff(KEY, diff, 'h1')
  const all: TaskRunners = {
    analyze: vi.fn<TaskRunners['analyze']>(async () => ({ result: grouping, transcript: [{ role: 'user', content: 'Group this', timestamp: 0 }] })),
    critique: vi.fn<TaskRunners['critique']>(async () => ({ summary: 'ok', findings: [], dropped: 0, headSha: 'h1', engine: 'claude-code', generatedAt: '2026-10-11T00:00:00Z' })),
    ask: vi.fn<TaskRunners['ask']>(async () => ({ text: 'It parses.', usage: { costUsd: 0.01 } })),
    chat: vi.fn<TaskRunners['chat']>(async (_diff, _resolved, _locale, session) => ({ chatStartIndex: session?.chatStartIndex ?? 0, messages: [...(session?.messages ?? []), { role: 'user', content: 'Why?', timestamp: 1 }] })),
    tour: vi.fn<TaskRunners['tour']>(async () => ({ title: 'Tour', overview: 'o', stops: [], glossary: [], headSha: 'h1', engine: 'claude-code', locale: 'en', generatedAt: '2026-10-12T00:00:00Z' })),
    brief: vi.fn<TaskRunners['brief']>(async () => ({ lang: 'root = Stack([])', headSha: 'h1', engine: 'claude-code', locale: 'en', generatedAt: '2026-10-14T00:00:00Z' })),
    lens: vi.fn<TaskRunners['lens']>(async () => undefined),
    usage: vi.fn<TaskRunners['usage']>(() => ({ costUsd: 0.5 })),
    ...runners,
  }
  return { storage, cache, runners: all, tasks: createTaskManager({ cache, storage, runners: all }) }
}

async function settled(wait: (id: string, after: number) => Promise<AiTaskSnapshot>, id: string): Promise<AiTaskSnapshot> {
  let after = 0
  for (;;) {
    const snapshot = await wait(id, after)
    after = snapshot.rev
    if (snapshot.status !== 'running')
      return snapshot
  }
}

describe('ai tasks', () => {
  it('saves an analysis and its chat session into the diff entry', async () => {
    const { tasks, cache } = await setup()
    const id = await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' })
    expect(await settled(tasks.wait, id)).toMatchObject({ status: 'done', kind: 'analyze', key: KEY, usage: { costUsd: 0.5 } })
    const entry = await cache.diffs.get(KEY)
    expect(entry?.analyzedBy.llm).toMatchObject({ overallSummary: 'Summary' })
    expect(entry?.llmSession).toMatchObject({ chatStartIndex: 1 })
    expect(tasks.list(KEY).map(task => task.id)).toEqual([id])
    expect(tasks.list('other')).toEqual([])
  })

  it('keeps running when nobody follows it, and joins a running task doing the same work', async () => {
    const gate = deferred<Awaited<ReturnType<TaskRunners['analyze']>>>()
    const { tasks, cache } = await setup({ analyze: vi.fn<TaskRunners['analyze']>(() => gate.promise) })
    const id = await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' })
    expect(await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' })).toBe(id)
    // The page is gone: nothing waits on the task. It still finishes and saves.
    gate.resolve({ result: grouping, transcript: [] })
    await vi.waitFor(async () => expect((await cache.diffs.get(KEY))?.analyzedBy.llm).toBeDefined())
    expect(tasks.list(KEY)[0]).toMatchObject({ status: 'done' })
  })

  it('streams work-log entries to a long-poller', async () => {
    const gate = deferred<Awaited<ReturnType<TaskRunners['analyze']>>>()
    const { tasks } = await setup({
      analyze: vi.fn<TaskRunners['analyze']>(async (_d, _r, _l, hooks) => {
        hooks.onActivity?.([{ id: 'read', kind: 'tool', title: 'Read a.ts', status: 'running', startedAt: 0, rev: 7 }])
        return gate.promise
      }),
    })
    const id = await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' })
    await vi.waitFor(async () => expect((await tasks.wait(id, 0)).activities).toHaveLength(1))
    const first = await tasks.wait(id, 0)
    expect(first.activities[0]).toMatchObject({ id: 'read', title: 'Read a.ts', rev: first.rev })
    gate.resolve({ result: grouping, transcript: [] })
    const last = await settled(tasks.wait, id)
    expect(last.activities).toEqual([expect.objectContaining({ id: 'read', status: 'done' })])
  })

  it('saves a critique under its variant', async () => {
    const { tasks, cache, runners } = await setup({ lens: vi.fn(async () => 'Look for injections.') })
    const id = await tasks.start({ kind: 'critique', key: KEY, model, variant: 'h1:claude-code:opus:high:security', lens: 'security' })
    expect(await settled(tasks.wait, id)).toMatchObject({ status: 'done', variant: 'h1:claude-code:opus:high:security', lens: 'security' })
    expect(runners.critique).toHaveBeenCalledWith(diff, expect.anything(), { name: 'security', instructions: 'Look for injections.' }, expect.anything())
    await expect(cache.critiques.get(KEY, 'h1:claude-code:opus:high:security')).resolves.toMatchObject({ summary: 'ok' })
  })

  it('saves questions and answers to the diff\'s threads, with earlier turns as history', async () => {
    const { tasks, runners } = await setup()
    const selection = { path: 'a.ts', side: 'additions' as const, startLine: 1, endLine: 1 }
    const first = await tasks.start({ kind: 'ask', key: KEY, model, threadId: 't1', selection, question: 'What?' })
    await settled(tasks.wait, first)
    const second = await tasks.start({ kind: 'ask', key: KEY, model, threadId: 't1', selection, question: 'Safe?' })
    expect((await tasks.threads(KEY))[0]!.turns[1]).toMatchObject({ question: 'Safe?', taskId: second })
    await settled(tasks.wait, second)

    expect(vi.mocked(runners.ask).mock.calls[1]![2]).toMatchObject({ question: 'Safe?', history: [{ question: 'What?', answer: 'It parses.' }] })
    const [thread] = await tasks.threads(KEY)
    expect(thread).toMatchObject({ id: 't1', label: 'a.ts:L1', turns: [{ question: 'What?', answer: 'It parses.', outcome: 'done' }, { question: 'Safe?', outcome: 'done' }] })

    await tasks.closeThread(KEY, 't1')
    expect(await tasks.threads(KEY)).toEqual([])
  })

  it('marks a stopped question and a failed one in its thread', async () => {
    const { tasks } = await setup({
      ask: vi.fn<TaskRunners['ask']>((_d, _r, request, hooks) => request.question === 'boom'
        ? Promise.reject(new Error('CLI broke'))
        : new Promise((_resolve, reject) => hooks.signal.addEventListener('abort', () => reject(new Error('aborted'))))),
    })
    const selection = { path: 'a.ts', side: 'additions' as const, startLine: 1, endLine: 1 }
    const slow = await tasks.start({ kind: 'ask', key: KEY, model, threadId: 't1', selection, question: 'slow' })
    expect(tasks.cancel(slow)).toBe(true)
    expect(await settled(tasks.wait, slow)).toMatchObject({ status: 'cancelled' })
    const failed = await tasks.start({ kind: 'ask', key: KEY, model, threadId: 't2', selection, question: 'boom' })
    expect(await settled(tasks.wait, failed)).toMatchObject({ status: 'failed', error: 'CLI broke' })
    await vi.waitFor(async () => expect((await tasks.threads(KEY)).map(thread => thread.turns[0]!.outcome)).toEqual(['stopped', 'failed']))
  })

  it('says a question was cut off when its task is unknown after a restart', async () => {
    const { storage, cache, runners } = await setup()
    await storage.setItem(`ask-threads:${KEY}`, { threads: [{ id: 't1', selection: { path: 'a.ts', side: 'additions', startLine: 1, endLine: 1 }, label: 'a.ts:L1', turns: [{ question: 'Q', taskId: 'gone' }] }] })
    const restarted = createTaskManager({ cache, storage, runners })
    expect((await restarted.threads(KEY))[0]!.turns[0]).toMatchObject({ outcome: 'failed', error: expect.stringContaining('restarted') })
  })

  it('continues the saved chat session and stores the reply', async () => {
    const { tasks, cache } = await setup()
    await cache.diffs.setLlmSession(KEY, { messages: [{ role: 'user', content: 'Group this', timestamp: 0 }], chatStartIndex: 1 })
    const id = await tasks.start({ kind: 'chat', key: KEY, model, locale: 'en', text: 'Why?' })
    expect(await settled(tasks.wait, id)).toMatchObject({ status: 'done', text: 'Why?' })
    expect((await cache.diffs.get(KEY))?.llmSession?.messages).toHaveLength(2)
  })

  it('starts a chat from the pull request when nobody analyzed it', async () => {
    const { tasks, cache, runners } = await setup()
    const id = await tasks.start({ kind: 'chat', key: KEY, model, locale: 'en', text: 'Why?' })
    expect(await settled(tasks.wait, id)).toMatchObject({ status: 'done', text: 'Why?' })
    expect(vi.mocked(runners.chat).mock.calls[0]![3]).toBeUndefined()
    expect((await cache.diffs.get(KEY))?.llmSession?.messages).toHaveLength(1)
  })

  it('saves a brief next to the diff and builds it on the analysis', async () => {
    const { tasks, cache, runners } = await setup()
    await settled(tasks.wait, await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' }))
    const id = await tasks.start({ kind: 'brief', key: KEY, model, locale: 'en' })
    expect(await settled(tasks.wait, id)).toMatchObject({ status: 'done', kind: 'brief', usage: { costUsd: 0.5 } })
    expect(vi.mocked(runners.brief).mock.calls[0]![3]).toMatchObject({ overallSummary: 'Summary' })
    await expect(cache.briefs.latest(serializeRef(diff.ref), 'h1')).resolves.toMatchObject({ lang: 'root = Stack([])', headSha: 'h1' })
  })

  it('writes a brief without an analysis too', async () => {
    const { tasks, runners } = await setup()
    await settled(tasks.wait, await tasks.start({ kind: 'brief', key: KEY, model, locale: 'en' }))
    expect(vi.mocked(runners.brief).mock.calls[0]![3]).toBeUndefined()
  })

  it('carries on with the tour and the brief after an analysis, without the phone', async () => {
    const { tasks, cache, runners } = await setup()
    await settled(tasks.wait, await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en', then: ['tour', 'brief'] }))
    await vi.waitFor(async () => {
      expect(runners.tour).toHaveBeenCalledOnce()
      expect(runners.brief).toHaveBeenCalledOnce()
      expect(await cache.tours.latest(serializeRef(diff.ref), 'h1')).toBeDefined()
      expect(await cache.briefs.latest(serializeRef(diff.ref), 'h1')).toBeDefined()
    })
    expect(tasks.list(KEY).map(task => task.kind).sort()).toEqual(['analyze', 'brief', 'tour'])
  })

  it('does not chain after a failed or stopped analysis, and skips a tour that is current', async () => {
    const failing = await setup({ analyze: vi.fn<TaskRunners['analyze']>(() => Promise.reject(new Error('CLI broke'))) })
    expect(await settled(failing.tasks.wait, await failing.tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en', then: ['tour', 'brief'] }))).toMatchObject({ status: 'failed' })
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(failing.runners.tour).not.toHaveBeenCalled()
    expect(failing.runners.brief).not.toHaveBeenCalled()

    const { tasks, cache, runners } = await setup()
    await cache.tours.set(serializeRef(diff.ref), { title: 'Tour', overview: 'o', stops: [], glossary: [], headSha: 'h1', engine: 'claude-code', locale: 'en', generatedAt: '2026-10-12T00:00:00Z' })
    await settled(tasks.wait, await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en', then: ['tour', 'brief'] }))
    await vi.waitFor(() => expect(runners.brief).toHaveBeenCalledOnce())
    expect(runners.tour).not.toHaveBeenCalled()
  })

  it('lists the tasks of every diff, newest first, with the pull request\'s title', async () => {
    const { tasks, cache } = await setup()
    const other = { ...diff, ref: { kind: 'github-pr' as const, owner: 'o', repo: 'r', number: '99' }, title: 'Other change' }
    const otherKey = serializeRef(other.ref)
    await cache.diffs.putDiff(otherKey, other, 'h1')
    const first = await tasks.start({ kind: 'analyze', key: KEY, model, locale: 'en' })
    await settled(tasks.wait, first)
    const second = await tasks.start({ kind: 'analyze', key: otherKey, model, locale: 'en' })
    await settled(tasks.wait, second)
    const all = tasks.listAll()
    expect(all.map(task => task.id)).toEqual([second, first])
    expect(all[0]).toMatchObject({ key: otherKey, title: 'Other change' })
    expect(all.every(task => task.activities.length === 0)).toBe(true)
  })

  it('fails clearly without a cached diff', async () => {
    const { tasks } = await setup()
    expect(await settled(tasks.wait, await tasks.start({ kind: 'analyze', key: 'missing', model, locale: 'en' }))).toMatchObject({ status: 'failed', error: expect.stringContaining('no longer cached') })
    await expect(tasks.wait('nope', 0)).rejects.toThrow('That task is gone')
  })
})

describe('task helpers', () => {
  it('resolves the page\'s model for either CLI', () => {
    expect(resolveTaskModel({ engine: 'codex', model: 'gpt-x', effort: 'low' })).toMatchObject({ model: { provider: 'codex', id: 'gpt-x' }, effort: 'low' })
    expect(resolveTaskModel({ engine: 'claude-code' }).model.provider).toBe('claude-code')
  })

  it('sums usage across jobs', () => {
    expect(sumUsage([])).toBeUndefined()
    expect(sumUsage([{ costUsd: 0.1, inputTokens: 5, model: 'a' }, { costUsd: 0.2, model: 'b' }])).toEqual({ costUsd: expect.closeTo(0.3), inputTokens: 5, model: 'b' })
  })
})
