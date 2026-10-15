import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
  vi.unstubAllGlobals()
  rmSync(repo, { recursive: true, force: true })
})

describe('local RPC', () => {
  it('rejects stale browser writes without replacing server changes', async () => {
    const driver = memoryDriver()
    const functions = localRpcFunctions({ cwd: repo, driver, env: {} })
    await driver.setItem!('test', 'new server state', {})
    await expect(call(functions, LOCAL_RPC.storageSetItem, { key: 'test', value: 'stale', expected: 'old' })).rejects.toThrow('changed on the server')
    expect(await driver.getItem('test', {})).toBe('new server state')
  })

  it('keeps GitHub credentials server-side and refuses foreign origins', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"login":"tavi"}', { headers: { 'x-oauth-scopes': 'repo' } }))
    vi.stubGlobal('fetch', fetch)
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: { GITHUB_TOKEN: 'server-secret' } })
    const result = await call(functions, LOCAL_RPC.githubRequest, { path: '/user' })
    expect(JSON.stringify(result)).not.toContain('server-secret')
    expect(fetch.mock.calls[0]![1]).toMatchObject({ redirect: 'error', headers: { Authorization: 'Bearer server-secret' } })
    await expect(call(functions, LOCAL_RPC.githubRequest, { path: '//attacker.example/user' })).rejects.toThrow('Unsupported')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('serves the diff and a browser-side cache that runs over the storage functions', async () => {
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: { GITHUB_TOKEN: 'from-env' } })

    expect(await call(functions, LOCAL_RPC.repoInfo)).toMatchObject({ currentBranch: 'main', defaultBranch: { name: 'main', ref: 'main' }, branches: ['main'], commits: [{ subject: 'init' }] })
    expect(functions.some(fn => String(fn.name) === 'github-token')).toBe(false)
    expect(await call(functions, LOCAL_RPC.githubAuthenticated)).toBe(true)
    const diff = await call(functions, LOCAL_RPC.sourceFetch, { target: '' }) as { files: { path: string }[], head: { sha: string } }
    expect(diff.files.map(file => file.path)).toEqual(['a.ts'])
    expect(await call(functions, LOCAL_RPC.sourceFingerprint, { target: '' })).toBe(diff.head.sha)
    expect(await call(functions, LOCAL_RPC.sourceLoadFile, { target: '', path: 'a.ts', sha: diff.head.sha })).toBe('two\n')

    // The browser's repositories over an unstorage driver made of these functions.
    const cache = createCacheRepositories(createStorage({
      driver: {
        getItem: key => call(functions, LOCAL_RPC.storageGetItem, { key }),
        hasItem: async key => await call(functions, LOCAL_RPC.storageGetItem, { key }) !== null,
        setItem: async (key, value) => void await call(functions, LOCAL_RPC.storageSetItem, { key, value, expected: null }),
        removeItem: async key => void await call(functions, LOCAL_RPC.storageRemoveItem, { key, expected: null }),
        getKeys: base => call(functions, LOCAL_RPC.storageGetKeys, { base }) as Promise<string[]>,
      },
    }))
    await cache.reviewMarks.set(['sha-a'], true)
    expect(await cache.reviewMarks.get(['sha-a', 'sha-b'])).toEqual(new Set(['sha-a']))
  })

  it('runs browser diff mutations on the shared cache and rejects raw replacements', async () => {
    const driver = memoryDriver()
    const storage = createStorage({ driver })
    const cache = createCacheRepositories(storage)
    const functions = localRpcFunctions({ cwd: repo, driver, storage, cache, env: {} })
    const diff = await call(functions, LOCAL_RPC.sourceFetch, { target: '' }) as any
    await call(functions, LOCAL_RPC.cacheDiff, { method: 'putDiff', key: 'test', values: [diff, 'h1', null] })
    await cache.diffs.setLlmSession('test', { messages: [{ role: 'user', content: 'Saved server chat', timestamp: 1 }], chatStartIndex: 0 })
    await call(functions, LOCAL_RPC.cacheDiff, { method: 'touch', key: 'test' })
    expect((await cache.diffs.get('test'))?.llmSession?.messages).toHaveLength(1)
    await expect(call(functions, LOCAL_RPC.cacheDiff, { method: 'putDiff', key: 'test', values: [diff, 'old', null] })).rejects.toThrow('changed')
    await expect(call(functions, LOCAL_RPC.storageSetItem, { key: 'pr-body:test', value: '{}', expected: null })).rejects.toThrow('server diff cache')
    expect((await cache.diffs.get('test'))?.headSha).toBe('h1')
  })

  it('reads and writes many cache keys in one call', async () => {
    const functions = localRpcFunctions({ cwd: repo, driver: memoryDriver(), env: {} })

    await call(functions, LOCAL_RPC.storageSetItems, { items: [{ key: 'a', value: '1', expected: null }, { key: 'b', value: '2', expected: null }] })
    expect(await call(functions, LOCAL_RPC.storageGetItems, { keys: ['b', 'missing', 'a'] })).toEqual(['2', null, '1'])
  })
})
