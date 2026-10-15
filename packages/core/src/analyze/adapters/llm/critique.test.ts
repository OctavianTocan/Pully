import type { DiffsPayload, FileChange } from '../../../types/diff'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setCliRunner } from './cli-stream'
import { buildCritiquePrompt, citesOnGithub, CRITIQUE_SCHEMA, CRITIQUE_SYSTEM_PROMPT, critiqueReviewBody, findingCommentBody, hunkIndexOf, MAX_FINDINGS, numberHunkLines, prContextOf, resolveFindings, runCritique } from './critique'
import { resolveModel } from './model'
import { MENTION_RULE } from './prompt'
import { defaultLlmSettings } from './settings'

// Old lines 10-13, new lines 10-14: line 11 is replaced by two lines, line 12 stays.
const patch = [' keep()', '-old()', '+first()', '+second()', ' stay()', ' end()'].join('\n')

function file(path: string, overrides: Partial<FileChange> = {}): FileChange {
  return {
    path,
    status: 'modified',
    additions: 2,
    deletions: 1,
    isBinary: false,
    sha: path,
    hunks: [{ header: '@@ -10,4 +10,5 @@ function f', oldStart: 10, oldLines: 4, newStart: 10, newLines: 5, patch }],
    ...overrides,
  }
}

function diffWith(...files: FileChange[]): DiffsPayload {
  return {
    ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '7' },
    title: 'Fix things',
    description: 'Body',
    base: { sha: 'base1', ref: 'main' },
    head: { sha: 'head1', ref: 'fix' },
    files,
  }
}

const raw = (overrides: Record<string, unknown> = {}) => ({ path: 'a.ts', side: 'RIGHT', line: 11, severity: 'bug', title: 'Breaks', body: 'It breaks.', ...overrides })

afterEach(() => setCliRunner(undefined))

describe('numberHunkLines', () => {
  it('numbers context on both sides, additions on the new side and deletions on the old side', () => {
    expect(numberHunkLines(file('a.ts').hunks[0]!)).toEqual([
      { kind: ' ', text: 'keep()', oldLine: 10, newLine: 10 },
      { kind: '-', text: 'old()', oldLine: 11 },
      { kind: '+', text: 'first()', newLine: 11 },
      { kind: '+', text: 'second()', newLine: 12 },
      { kind: ' ', text: 'stay()', oldLine: 12, newLine: 13 },
      { kind: ' ', text: 'end()', oldLine: 13, newLine: 14 },
    ])
  })

  it('finds the hunk showing a line on a side', () => {
    expect(hunkIndexOf(file('a.ts'), 'additions', 14)).toBe(0)
    expect(hunkIndexOf(file('a.ts'), 'deletions', 14)).toBe(-1)
    expect(hunkIndexOf(file('a.ts'), 'additions', 9)).toBe(-1)
  })
})

describe('resolveFindings', () => {
  it('maps sides and keeps a range inside one hunk', () => {
    const { findings, dropped } = resolveFindings(diffWith(file('a.ts')), [raw({ line: 11, endLine: 12 }), raw({ side: 'LEFT', line: 11, severity: 'whatever' })])
    expect(dropped).toBe(0)
    expect(findings).toEqual([
      { id: 'a.ts:deletions:11-11', path: 'a.ts', side: 'deletions', line: 11, severity: 'risk', title: 'Breaks', body: 'It breaks.' },
      { id: 'a.ts:additions:11-12', path: 'a.ts', side: 'additions', line: 12, startLine: 11, severity: 'bug', title: 'Breaks', body: 'It breaks.' },
    ])
  })

  it('drops unknown paths, lines the diff does not show, empty bodies and duplicates', () => {
    const { findings, dropped } = resolveFindings(diffWith(file('a.ts')), [
      raw({ path: 'nope.ts' }),
      raw({ line: 40 }),
      raw({ side: 'LEFT', line: 14 }),
      raw({ body: '  ' }),
      raw({ line: 'x' }),
      raw(),
      raw({ title: 'Same spot' }),
    ])
    expect(findings.map(finding => finding.id)).toEqual(['a.ts:additions:11-11'])
    expect(dropped).toBe(6)
  })

  it('strips diff path prefixes, falls back to the body for a title, and keeps suggestions on the new side only', () => {
    const { findings } = resolveFindings(diffWith(file('a.ts')), [
      raw({ path: 'b/a.ts', title: '', body: 'First line.\nMore.', suggestion: '```ts\nfixed()\n```' }),
      raw({ side: 'LEFT', suggestion: 'ignored()' }),
    ])
    expect(findings.find(finding => finding.side === 'additions')).toMatchObject({ title: 'First line.', suggestion: 'fixed()' })
    expect(findings.find(finding => finding.side === 'deletions')).not.toHaveProperty('suggestion')
  })

  it('sorts by file then line, counting duplicates as dropped', () => {
    const many = Array.from({ length: 30 }, (_, index) => raw({ path: index % 2 ? 'a.ts' : 'b.ts', line: 10 + (index % 5), body: `Body ${index}` }))
    const { findings, dropped } = resolveFindings(diffWith(file('a.ts'), file('b.ts')), many)
    expect(findings.map(finding => `${finding.path}:${finding.line}`)).toEqual([
      'a.ts:10',
      'a.ts:11',
      'a.ts:12',
      'a.ts:13',
      'a.ts:14',
      'b.ts:10',
      'b.ts:11',
      'b.ts:12',
      'b.ts:13',
      'b.ts:14',
    ])
    expect(dropped).toBe(20)
  })

  it('keeps the first MAX_FINDINGS in file order', () => {
    const files = Array.from({ length: MAX_FINDINGS + 5 }, (_, index) => file(`f${String(index).padStart(2, '0')}.ts`))
    const { findings, dropped } = resolveFindings(diffWith(...files), [...files].reverse().map(each => raw({ path: each.path })))
    expect(findings).toHaveLength(MAX_FINDINGS)
    expect(findings[0]!.path).toBe('f00.ts')
    expect(dropped).toBe(5)
  })
})

describe('comment bodies', () => {
  it('appends a suggestion block, fenced past any backticks in the code', () => {
    const body = findingCommentBody({ id: 'x', path: 'a.ts', side: 'additions', line: 1, severity: 'bug', title: 'T', body: 'B', suggestion: 'a ``` b' })
    expect(body).toBe('**T**\n\nB\n\n````suggestion\na ``` b\n````')
  })

  it('links what a finding mentions, and points the links at the head commit on GitHub (rule 96)', () => {
    expect(CRITIQUE_SYSTEM_PROMPT).toContain(MENTION_RULE)
    const onGithub = citesOnGithub(diffWith(file('a.ts')))
    expect(onGithub('[`first`](a.ts#L11) calls [b.ts](src/b.ts) and [docs](https://example.com).'))
      .toBe('[`first`](https://github.com/o/r/blob/head1/a.ts#L11) calls [b.ts](https://github.com/o/r/blob/head1/src/b.ts) and [docs](https://example.com).')
    expect(citesOnGithub({ ...diffWith(file('a.ts')), head: undefined })('[a](a.ts#L1)')).toBe('[a](a.ts#L1)')
  })

  it('names the engine, model and lens under the summary', () => {
    expect(critiqueReviewBody({ summary: 'Sound.', findings: [], dropped: 0, engine: 'codex', model: 'gpt-5', lens: 'security', generatedAt: '' }))
      .toBe('Sound.\n\n<sub>Reviewed with codex/gpt-5, through the security lens on pulls.review.</sub>')
  })
})

describe('buildCritiquePrompt', () => {
  it('numbers every line and appends the lens', () => {
    const prompt = buildCritiquePrompt(diffWith(file('a.ts'), file('pnpm-lock.yaml')), { name: 'security', instructions: 'Look for injection.' })
    expect(prompt).toContain('# o/r#7: Fix things')
    expect(prompt).toContain('L11           -old()')
    expect(prompt).toContain('       R12    +second()')
    expect(prompt).toContain('### pnpm-lock.yaml [modified, +2/-1]\n(generated file, not yours to review)')
    expect(prompt).toMatch(/## Review lens: security\n\n[\s\S]*Look for injection\.$/)
  })
})

describe('prContextOf', () => {
  it('describes GitHub diffs only', () => {
    expect(prContextOf(diffWith(file('a.ts')))).toMatchObject({ owner: 'o', repo: 'r', number: 7, headSha: 'head1', baseSha: 'base1', files: [{ path: 'a.ts', status: 'modified', patch: `@@ -10,4 +10,5 @@ function f\n${patch}` }] })
    expect(prContextOf({ ...diffWith(), ref: { kind: 'paste', hash: 'h' } })).toBeUndefined()
  })
})

describe('runCritique', () => {
  const resolved = resolveModel({ ...defaultLlmSettings, provider: 'claude-code', claudeCodeModel: 'opus', claudeCodeEffort: 'high' })!

  it('runs the CLI with the schema and context, and anchors the answer', async () => {
    const runner = vi.fn(async () => ({ text: JSON.stringify({ summary: ' One bug. ', findings: [raw(), raw({ path: 'gone.ts' })] }), usage: { costUsd: 0.5, model: 'claude-opus' } }))
    setCliRunner(runner)
    const result = await runCritique(diffWith(file('a.ts')), resolved)
    expect(runner).toHaveBeenCalledWith(expect.objectContaining({ engine: 'claude-code', model: 'opus', effort: 'high', schema: CRITIQUE_SCHEMA, context: expect.objectContaining({ headSha: 'head1' }) }), expect.anything())
    expect(result).toMatchObject({ summary: 'One bug.', dropped: 1, headSha: 'head1', engine: 'claude-code', model: 'claude-opus', usage: { costUsd: 0.5 } })
    expect(result.findings).toHaveLength(1)
  })

  it('accepts a fenced JSON answer', async () => {
    setCliRunner(async () => ({ text: '```json\n{"summary":"Nothing to flag.","findings":[]}\n```' }))
    await expect(runCritique(diffWith(file('a.ts')), resolved)).resolves.toMatchObject({ summary: 'Nothing to flag.', findings: [] })
  })

  it('drops the object\'s closing quote and brace left inside the summary', async () => {
    setCliRunner(async () => ({ text: JSON.stringify({ summary: 'Nothing to flag."}', findings: [] }) }))
    await expect(runCritique(diffWith(file('a.ts')), resolved)).resolves.toMatchObject({ summary: 'Nothing to flag.' })
  })

  it('refuses API providers', async () => {
    await expect(runCritique(diffWith(), resolveModel({ ...defaultLlmSettings, provider: 'anthropic', anthropicApiKey: 'k' })!)).rejects.toThrow('Claude Code or Codex')
  })
})
