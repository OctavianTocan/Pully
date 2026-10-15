import type { DiffsPayload, FileChange } from '@pulls.review/core/types'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createJevClient } from './jev'
import { createFileAttention, FILE_QUESTIONS, fileState, JEV_FILES_OFF, priorityOf } from './jev-files'

function file(path: string, overrides: Partial<FileChange> = {}): FileChange {
  return {
    path,
    status: 'modified',
    additions: 3,
    deletions: 1,
    isBinary: false,
    sha: `blob-${path}`,
    hunks: [{ header: '@@ -1,2 +1,4 @@', oldStart: 1, oldLines: 2, newStart: 1, newLines: 4, patch: ' a\n-b\n+c\n+d\n+e' }],
    ...overrides,
  }
}

function diff(files: FileChange[]): DiffsPayload {
  return { ref: { kind: 'github-pr', owner: 'acme', repo: 'app', number: '1' }, title: 'Fix the crash', head: { sha: 'head', ref: 'fix' }, files }
}

function attention(choice: string, confidence: number) {
  return Response.json({
    model: 'jev-1.13.0',
    answers: { attention: { type: 'choice', choice, probabilities: { [choice]: confidence }, confidence } },
    usage: { input_tokens: 300, output_tokens: 0 },
  })
}

/** A TypeSafe stand-in that answers by path: `lock` files are p2, `auth` files p0 but unsure, the rest p1. */
function typesafe(options: { status?: number } = {}) {
  const paths: string[] = []
  const fetch = (async (_url: string, init: RequestInit) => {
    const { state } = JSON.parse(init.body as string) as { state: { path: string } }
    paths.push(state.path)
    if (options.status)
      return Response.json({ error: 'nope' }, { status: options.status })
    if (state.path.includes('lock'))
      return attention('p2', 0.95)
    if (state.path.includes('auth'))
      return attention('p2', 0.3)
    return attention('p1', 0.8)
  }) as typeof globalThis.fetch
  return { fetch, paths }
}

describe('fileState', () => {
  it('sends the path, status, counts and the start of the patch', () => {
    const long = file('src/a.ts', { hunks: [{ header: '@@ -1 +1 @@', oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, patch: `+${'x'.repeat(10_000)}` }] })
    const state = fileState(long)
    expect(state).toMatchObject({ path: 'src/a.ts', status: 'modified', additions: 3, deletions: 1 })
    expect(state.patch).toHaveLength(6_000)
    expect(state.patch.startsWith('@@ -1 +1 @@\n+xxx')).toBe(true)
  })

  it('asks one choice of three priorities', () => {
    expect(Object.keys(FILE_QUESTIONS.attention.criteria)).toEqual(['p0', 'p1', 'p2'])
  })
})

describe('priorityOf', () => {
  it('counts an unsure answer as p0, so it gets a human', () => {
    expect(priorityOf({ type: 'choice', choice: 'p2', probabilities: {}, confidence: 0.49 })).toEqual({ priority: 'p0', confidence: 0.49 })
    expect(priorityOf({ type: 'choice', choice: 'p2', probabilities: {}, confidence: 0.5 })).toEqual({ priority: 'p2', confidence: 0.5 })
    expect(priorityOf({ type: 'choice', choice: 'p9', probabilities: {}, confidence: 0.9 })).toEqual({ priority: 'p0', confidence: 0.9 })
  })
})

describe('createFileAttention', () => {
  const files = [file('src/auth.ts'), file('pnpm-lock.yaml'), file('src/app.ts'), file('logo.png', { isBinary: true, hunks: [] })]

  it('starts in the background, then answers with every file but the binary one', async () => {
    const { fetch, paths } = typesafe()
    const service = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }), storage: createStorage({ driver: memoryDriver() }) })

    expect(await service.files(diff(files))).toEqual({ enabled: true, files: {}, pending: 3 })
    await vi.waitFor(async () => expect((await service.files(diff(files))).pending).toBe(0))
    expect(await service.files(diff(files))).toEqual({
      enabled: true,
      files: {
        'src/auth.ts': { priority: 'p0', confidence: 0.3 },
        'pnpm-lock.yaml': { priority: 'p2', confidence: 0.95 },
        'src/app.ts': { priority: 'p1', confidence: 0.8 },
      },
      pending: 0,
    })
    expect(paths.sort()).toEqual(['pnpm-lock.yaml', 'src/app.ts', 'src/auth.ts'])
  })

  it('classifies a file version once, across calls and restarts', async () => {
    const { fetch, paths } = typesafe()
    const storage = createStorage({ driver: memoryDriver() })
    const first = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }), storage })
    await first.files(diff(files))
    await vi.waitFor(async () => expect((await first.files(diff(files))).pending).toBe(0))
    expect(paths).toHaveLength(3)

    const restarted = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }), storage })
    expect(Object.keys((await restarted.files(diff(files))).files)).toHaveLength(3)
    expect(paths).toHaveLength(3)

    // A new blob is a new version.
    await restarted.files(diff([file('src/app.ts', { sha: 'blob-2' })]))
    await vi.waitFor(() => expect(paths).toHaveLength(4))
  })

  it('asks again about a file that ends at the same blob by another change', async () => {
    const { fetch, paths } = typesafe()
    const service = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }) })
    const whole = file('src/app.ts')
    // The same final blob from another base: one commit of the pull request, say, with a smaller patch.
    const part = file('src/app.ts', { additions: 1, deletions: 0, hunks: [{ header: '@@ -3 +3,2 @@', oldStart: 3, oldLines: 1, newStart: 3, newLines: 2, patch: ' b\n+e' }] })
    await service.files(diff([whole]))
    await vi.waitFor(async () => expect((await service.files(diff([whole]))).pending).toBe(0))
    expect((await service.files(diff([part]))).pending).toBe(1)
    await vi.waitFor(() => expect(paths).toHaveLength(2))
  })

  it('looks at no more than 300 files of a diff', async () => {
    const { fetch, paths } = typesafe()
    const service = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {}, sleep: async () => {} }) })
    const many = Array.from({ length: 320 }, (_, i) => file(`src/${i}.ts`))
    expect((await service.files(diff(many))).pending).toBe(300)
    await vi.waitFor(() => expect(paths).toHaveLength(300), { timeout: 5_000 })
  })

  it('is off without a key', async () => {
    const { fetch, paths } = typesafe()
    const service = createFileAttention({ jev: createJevClient({ fetch }) })
    expect(await service.files(diff(files))).toEqual(JEV_FILES_OFF)
    expect(paths).toHaveLength(0)
  })

  it('turns off when TypeSafe refuses the key', async () => {
    const { fetch } = typesafe({ status: 401 })
    const service = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }) })
    await service.files(diff(files))
    await vi.waitFor(async () => expect(await service.files(diff(files))).toEqual(JEV_FILES_OFF))
  })

  it('stops counting a file Jev could not classify as pending', async () => {
    const { fetch, paths } = typesafe({ status: 422 })
    const service = createFileAttention({ jev: createJevClient({ apiKey: 'key', fetch, log: () => {} }) })
    await service.files(diff(files))
    await vi.waitFor(async () => expect(await service.files(diff(files))).toEqual({ enabled: true, files: {}, pending: 0 }))
    expect(paths).toHaveLength(3)
  })
})

describe('the jev-files route', () => {
  let repo: string | undefined

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.resetModules()
    if (repo)
      rmSync(repo, { recursive: true, force: true })
    repo = undefined
  })

  async function call(cwd: string, args: unknown) {
    const { handleNativeRequest } = await import('./native-api')
    const res = await handleNativeRequest({ url: new URL('http://localhost/api/rpc'), method: 'POST', json: async () => ({ name: 'jev-files', args }) }, cwd)
    return res!.json() as Promise<{ result?: unknown, error?: string }>
  }

  it('answers that Jev is off when no key is set, whatever it is asked', async () => {
    vi.resetModules()
    vi.stubEnv('PULLS_REVIEW_TYPESAFE_API_KEY', '')
    vi.stubEnv('TYPESAFE_API_KEY', '')
    expect(await call('/nowhere', {})).toEqual({ result: { enabled: false, files: {}, pending: 0 } })
  })

  it('classifies the files of a diff the phone opened', async () => {
    repo = mkdtempSync(join(tmpdir(), 'pulls-review-jev-'))
    execFileSync('git', ['init', '-q'], { cwd: repo })
    vi.resetModules()
    vi.stubEnv('PULLS_REVIEW_TYPESAFE_API_KEY', 'key')
    const { fetch } = typesafe()
    vi.stubGlobal('fetch', fetch)
    const { serverSession } = await import('./server-session')
    const opened = diff([file('src/app.ts'), file('pnpm-lock.yaml')])
    await (await serverSession(repo)).cache.diffs.putDiff('github:acme/app#1', opened, 'head')

    expect(await call(repo, { key: 'github:acme/app#1' })).toEqual({ result: { enabled: true, files: {}, pending: 2 } })
    await vi.waitFor(async () => expect(await call(repo!, { key: 'github:acme/app#1' })).toEqual({
      result: {
        enabled: true,
        files: { 'src/app.ts': { priority: 'p1', confidence: 0.8 }, 'pnpm-lock.yaml': { priority: 'p2', confidence: 0.95 } },
        pending: 0,
      },
    }))
    expect(await call(repo, { key: 'github:acme/app#2' })).toEqual({ error: 'Open the diff first.' })
  })
})
