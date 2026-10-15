import type { PrPayload } from './pr-tools'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { callPrTool, parseHunks, prTools } from './pr-tools'

const QUEUE_PATCH = [
  '@@ -1,3 +1,4 @@',
  ' import { run } from \'./run\'',
  '-export const limit = 2',
  '+export const limit = 4',
  '+export const retries = 3',
  ' export default run',
  '@@ -20,2 +21,2 @@ function drain()',
  '-  queue.clear()',
  '+  queue.drain()',
  '   return queue',
].join('\n')

const BASE: PrPayload = {
  owner: 'acme',
  repo: 'widgets',
  number: 7,
  headSha: '0000000',
  files: [
    { path: 'src/queue.ts', status: 'modified', patch: QUEUE_PATCH },
    { path: 'pnpm-lock.yaml', status: 'modified', patch: '@@ -1 +1 @@\n-a\n+b' },
    { path: 'logo.png', status: 'added' },
  ],
}

describe('pr tools on the diff', () => {
  it('offers Jev\'s triage first, only when Jev gave one', async () => {
    expect(prTools(BASE).map(tool => tool.name)).not.toContain('triage_files')
    const triaged: PrPayload = {
      ...BASE,
      triage: {
        pull: { kind: 'fix', kindConfidence: 0.82, risk: 2.6 },
        files: { 'src/queue.ts': { priority: 'p0', confidence: 0.9 }, 'pnpm-lock.yaml': { priority: 'p2', confidence: 0.97 } },
      },
    }
    expect(prTools(triaged)[0]!.name).toBe('triage_files')
    const text = await callPrTool(triaged, 'triage_files', {})
    expect(text).toContain('Pull request: fix (82% sure), high risk (2.6 on a scale of 0 trivial to 3 high).')
    expect(text).toContain('p0, needs human judgment')
    expect(text).toContain('- src/queue.ts (90% sure)')
    expect(text).toContain('- pnpm-lock.yaml (97% sure)')
    expect(text).not.toContain('p1, worth a look')
    expect(text).toContain('Not rated: 1 file')
  })

  it('splits a patch into numbered hunks', () => {
    const hunks = parseHunks('src/queue.ts', QUEUE_PATCH)
    expect(hunks.map(hunk => [hunk.id, hunk.oldStart, hunk.newStart, hunk.lines.length])).toEqual([
      ['src/queue.ts#0', 1, 1, 5],
      ['src/queue.ts#1', 20, 21, 3],
    ])
  })

  it('numbers diff lines by the side they live on', async () => {
    const text = await callPrTool(BASE, 'read_file_diff', { paths: ['src/queue.ts'] })
    expect(text).toContain('### src/queue.ts [modified]')
    expect(text).toContain('[src/queue.ts#1] @@ -20,2 +21,2 @@ function drain()')
    expect(text.split('\n').filter(line => /^[-+ ]\s+\d+ │/.test(line))).toEqual([
      '     1 │ import { run } from \'./run\'',
      '-    2 │ export const limit = 2',
      '+    2 │ export const limit = 4',
      '+    3 │ export const retries = 3',
      '     4 │ export default run',
      '-   20 │   queue.clear()',
      '+   21 │   queue.drain()',
      '    22 │   return queue',
    ])
  })

  it('leaves lockfiles out and says why a file has no diff', async () => {
    const text = await callPrTool(BASE, 'read_file_diff', { paths: ['pnpm-lock.yaml', 'logo.png', 'nope.ts'] })
    expect(text).toContain('### pnpm-lock.yaml [modified]\nLockfile or generated output')
    expect(text).toContain('### logo.png [added]\nNo textual diff')
    expect(text).toContain('nope.ts: not changed here.')
  })

  it('finds changed lines with their file, line and hunk', async () => {
    expect(await callPrTool(BASE, 'search_diff', { pattern: 'queue\\.' })).toBe([
      'src/queue.ts:L20 [src/queue.ts#1] -  queue.clear()',
      'src/queue.ts:L21 [src/queue.ts#1] +  queue.drain()',
    ].join('\n'))
    expect(await callPrTool(BASE, 'search_diff', { pattern: 'queue\\.', removed: false })).toBe('src/queue.ts:L21 [src/queue.ts#1] +  queue.drain()')
    expect(await callPrTool(BASE, 'search_diff', { pattern: '^b$' })).toBe('No changed line matches ^b$.')
  })

  it('reads hunks by id', async () => {
    const text = await callPrTool(BASE, 'read_hunks', { hunkIds: ['src/queue.ts#1', 'src/queue.ts#9'] })
    expect(text).toContain('[src/queue.ts#1] @@ -20,2')
    expect(text).toContain('[src/queue.ts#9] no such hunk.')
  })

  it('rejects what makes no sense', async () => {
    await expect(callPrTool(BASE, 'search_diff', { pattern: '(' })).rejects.toThrow('( is not a valid regular expression')
    await expect(callPrTool(BASE, 'read_file_diff', { paths: [1] })).rejects.toThrow('paths must be an array of strings')
    await expect(callPrTool(BASE, 'rm_rf', {})).rejects.toThrow('unknown tool rm_rf')
  })
})

describe('pr tools on a checkout', () => {
  let checkout: string
  let payload: PrPayload

  beforeAll(() => {
    checkout = mkdtempSync(join(tmpdir(), 'pr-tools-'))
    const git = (...args: string[]) => execFileSync('git', ['-C', checkout, ...args], { encoding: 'utf8' }).trim()
    git('init', '-q')
    mkdirSync(join(checkout, 'src'))
    writeFileSync(join(checkout, 'src/queue.ts'), `${Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join('\n')}\n`)
    writeFileSync(join(checkout, 'src/worker.ts'), 'import { useQueue } from \'./queue\'\nuseQueue()\n')
    git('add', '.')
    git('-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'init')
    payload = { ...BASE, checkout, headSha: git('rev-parse', 'HEAD') }
  })

  afterAll(() => rmSync(checkout, { recursive: true, force: true }))

  it('offers a code search only when there is a checkout', () => {
    expect(prTools(BASE).map(tool => tool.name)).not.toContain('search_source')
    expect(prTools(payload).map(tool => tool.name)).toContain('search_source')
  })

  it('reads a slice of a file at the head commit', async () => {
    const text = await callPrTool(payload, 'read_source', { path: 'src/queue.ts', startLine: 28 })
    expect(text).toBe(`src/queue.ts at ${payload.headSha.slice(0, 7)}, lines 28–30 of 30:\n   28  line 28\n   29  line 29\n   30  line 30`)
    expect(await callPrTool(payload, 'read_source', { path: 'missing.ts' })).toBe(`missing.ts: could not be read at ${payload.headSha.slice(0, 7)}.`)
  })

  it('lists and searches the repository', async () => {
    expect(await callPrTool(payload, 'find_files', { pattern: 'worker' })).toContain('1 files in acme/widgets')
    expect(await callPrTool(payload, 'search_source', { pattern: 'usequeue', path: 'src/*' })).toBe('src/worker.ts:1:import { useQueue } from \'./queue\'\nsrc/worker.ts:2:useQueue()')
  })
})
