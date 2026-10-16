import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
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

  it('opens a posted diff for the page at /-/<id>, and only one the account has', async () => {
    const driver = memoryDriver()
    const env = { PULLY_ACCOUNT_EMAIL: 'tavi@example.com', PULLY_ACCOUNT_PASSWORD: 'correct horse' }
    const storage = createStorage({ driver })
    const account = await createAccounts(storage).seed(env)
    const { meta } = await createPostedStore(storage, createCacheRepositories(storage)).save({
      accountId: account!.id,
      keyName: 'Laptop',
      patch: 'diff --git a/a.ts b/a.ts\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-one\n+two\n',
      fields: { title: 'Two' },
    })
    const functions = localRpcFunctions({ cwd: repo, driver, env })

    const diff = await call(functions, LOCAL_RPC.sourceFetch, { target: `posted:${meta.id}` }) as { title: string, head?: unknown, files: { path: string }[] }
    expect(diff).toMatchObject({ title: 'Two', files: [{ path: 'a.ts' }] })
    expect(diff.head).toBeUndefined()
    expect(await call(functions, LOCAL_RPC.sourceKey, { target: `posted:${meta.id}` })).toBe(`posted:${meta.id}`)
    expect(await call(functions, LOCAL_RPC.sourceFingerprint, { target: `posted:${meta.id}` })).toBe('v1')
    await expect(call(functions, LOCAL_RPC.sourceFetch, { target: 'posted:nothinghere' })).rejects.toThrow('no posted diff')
  })
})
