import type { AiTaskSnapshot } from '@pulls.review/core/local-rpc'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import * as v from 'valibot'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createAccounts } from './account'
import { createPostedStore } from './posted'
import { localRpcFunctions } from './rpc'

let repo: string

function call(functions: ReturnType<typeof localRpcFunctions>, name: string, ...args: unknown[]) {
  const fn = functions.find(candidate => candidate.name === name)
  // The handlers take the validated argument shapes; the test passes them as the browser would.
  return (fn!.handler as (...input: unknown[]) => Promise<unknown>)(...args)
}

beforeEach(() => {
  repo = mkdtempSync(join(tmpdir(), 'pulls-review-rpc-'))
  const git = (...args: string[]) => execFileSync('git', args, { cwd: repo })
  git('init', '-q', '-b', 'main')
  git('config', 'user.email', 'dev@example.com')
  git('config', 'user.name', 'Dev')
  writeFileSync(join(repo, 'a.ts'), 'one\n')
  git('add', '.')
  git('commit', '-q', '-m', 'init')
  writeFileSync(join(repo, 'a.ts'), 'two\n')
})

afterEach(() => {
  rmSync(repo, { recursive: true, force: true })
})

describe('local RPC', () => {
  it('serves the diff and a browser-side cache that runs over the storage functions', async () => {
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: { GITHUB_TOKEN: 'from-env' } })

    expect(await call(functions, LOCAL_RPC.repoInfo)).toMatchObject({ currentBranch: 'main', defaultBranch: { name: 'main', ref: 'main' }, branches: ['main'], commits: [{ subject: 'init' }] })
    expect(await call(functions, LOCAL_RPC.githubToken)).toBe('from-env')
    const diff = await call(functions, LOCAL_RPC.sourceFetch, { target: '' }) as { files: { path: string }[], head: { sha: string } }
    expect(diff.files.map(file => file.path)).toEqual(['a.ts'])
    expect(await call(functions, LOCAL_RPC.sourceFingerprint, { target: '' })).toBe(diff.head.sha)
    expect(await call(functions, LOCAL_RPC.sourceLoadFile, { target: '', path: 'a.ts', sha: diff.head.sha })).toBe('two\n')

    // The browser's repositories over an unstorage driver made of these functions.
    const cache = createCacheRepositories(createStorage({
      driver: {
        getItem: key => call(functions, LOCAL_RPC.storageGetItem, { key }),
        hasItem: async key => await call(functions, LOCAL_RPC.storageGetItem, { key }) !== null,
        setItem: async (key, value) => void await call(functions, LOCAL_RPC.storageSetItem, { key, value }),
        removeItem: async key => void await call(functions, LOCAL_RPC.storageRemoveItem, { key }),
        getKeys: base => call(functions, LOCAL_RPC.storageGetKeys, { base }) as Promise<string[]>,
      },
    }))
    await cache.reviewMarks.set(['sha-a'], true)
    expect(await cache.reviewMarks.get(['sha-a', 'sha-b'])).toEqual(new Set(['sha-a']))
  })

  it('reads and writes many cache keys in one call', async () => {
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: {} })

    await call(functions, LOCAL_RPC.storageSetItems, { items: [{ key: 'a', value: '1' }, { key: 'b', value: '2' }] })
    expect(await call(functions, LOCAL_RPC.storageGetItems, { keys: ['b', 'missing', 'a'] })).toEqual(['2', null, '1'])
  })

  it('keeps the account\'s records and posted diffs out of the page\'s storage calls', async () => {
    const driver = memoryDriver()
    const env = { PULLY_ACCOUNT_EMAIL: 'tavi@example.com', PULLY_ACCOUNT_PASSWORD: 'correct horse' }
    await createAccounts(createStorage({ driver })).seed(env)
    const functions = localRpcFunctions({ cwd: repo, driver, env })
    await call(functions, LOCAL_RPC.storageSetItem, { key: 'cache:one', value: '1' })

    const keys = await call(functions, LOCAL_RPC.storageGetKeys, { base: '' }) as string[]
    expect(keys).toEqual(['cache:one'])
    const records = (await driver.getKeys('', {})).filter(key => key !== 'cache:one')
    expect(records.length).toBeGreaterThan(0)
    for (const key of records) {
      expect(await call(functions, LOCAL_RPC.storageGetItem, { key })).toBeNull()
      await expect(call(functions, LOCAL_RPC.storageSetItem, { key, value: 'x' })).rejects.toThrow('not the page')
    }
    await expect(call(functions, LOCAL_RPC.storageSetItems, { items: [{ key: 'agent-key:abc', value: '{}' }] })).rejects.toThrow('not the page')
    await expect(call(functions, LOCAL_RPC.storageRemoveItem, { key: 'account:anything' })).rejects.toThrow('not the page')
  })

  it('never opens a posted diff, nor reads, lists or runs anything on one: they open only in the app', async () => {
    const driver = memoryDriver()
    const env = { PULLY_ACCOUNT_EMAIL: 'tavi@example.com', PULLY_ACCOUNT_PASSWORD: 'correct horse' }
    const storage = createStorage({ driver })
    const account = await createAccounts(storage).seed(env)
    const { meta } = await createPostedStore(storage, createCacheRepositories(storage)).save({
      accountId: account!.id,
      keyName: 'Laptop',
      patch: 'diff --git a/a.ts b/a.ts\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-one\n+two\n',
      fields: { title: 'Two', tour: { title: 'Tour', stops: [] } },
    })
    const functions = localRpcFunctions({ cwd: repo, driver, env })
    const key = `posted:${meta.id}`

    for (const name of [LOCAL_RPC.sourceFetch, LOCAL_RPC.sourceKey, LOCAL_RPC.sourceFingerprint])
      await expect((async () => call(functions, name, { target: key }))()).rejects.toThrow('only in the Pully app')
    expect(await call(functions, LOCAL_RPC.storageGetKeys, { base: '' })).toEqual([])
    expect(await call(functions, LOCAL_RPC.storageGetItem, { key: `tour:${key}` })).toBeNull()
    expect(await call(functions, LOCAL_RPC.storageGetItems, { keys: [`pr-meta:${key}`] })).toEqual([null])
    await expect(call(functions, LOCAL_RPC.storageSetItem, { key: `tour:${key}`, value: '{}' })).rejects.toThrow('not the page')
    // The calls that take a diff's key refuse a posted one before their handler runs.
    const argsOf = (name: string): v.GenericSchema => functions.find(candidate => candidate.name === name)!.args![0]
    expect(v.safeParse(argsOf(LOCAL_RPC.aiTaskList), { key }).success).toBe(false)
    expect(v.safeParse(argsOf(LOCAL_RPC.askThreads), { key }).success).toBe(false)
    expect(v.safeParse(argsOf(LOCAL_RPC.aiTaskStart), { kind: 'tour', key, model: { engine: 'claude-code' }, locale: 'en' }).success).toBe(false)
    expect(v.safeParse(argsOf(LOCAL_RPC.aiTaskList), { key: 'github-pr:a/b/1' }).success).toBe(true)
  })

  it('leaves out of the page\'s task listings and waits the work the phone runs on a posted diff', async () => {
    type Tasks = NonNullable<Parameters<typeof localRpcFunctions>[0]['tasks']>
    const posted = { id: 't1', key: 'posted:k3T9xQ2mLp0a', title: 'Secret plans' } as AiTaskSnapshot
    const pull = { id: 't2', key: 'github-pr:a/b/1', title: 'Fix it' } as AiTaskSnapshot
    const fake: Pick<Tasks, 'listAll' | 'wait'> = { listAll: () => [posted, pull], wait: async id => id === 't1' ? posted : pull }
    const tasks = fake as Tasks
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: {}, tasks })

    expect(await call(functions, LOCAL_RPC.aiTaskListAll, {})).toEqual([pull])
    expect(await call(functions, LOCAL_RPC.aiTaskWait, { id: 't2', after: 0 })).toBe(pull)
    await expect(call(functions, LOCAL_RPC.aiTaskWait, { id: 't1', after: 0 })).rejects.toThrow('only in the Pully app')
  })
})
