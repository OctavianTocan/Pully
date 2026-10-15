import type { DiffsPayload } from '../../../types/diff'
import type { CliRunRequest } from './cli-stream'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ASK_SYSTEM_PROMPT, askAboutSelection, buildAskPrompt, selectionContext, selectionLabel } from './ask'
import { setCliRunner } from './cli-stream'
import { resolveAskModel } from './model'
import { MENTION_RULE } from './prompt'
import { defaultLlmSettings } from './settings'

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '3' },
  title: 'Retry',
  head: { sha: 'h', ref: 'retry' },
  base: { sha: 'b', ref: 'main' },
  files: [
    {
      path: 'a.ts',
      status: 'modified',
      additions: 2,
      deletions: 1,
      isBinary: false,
      sha: '1',
      hunks: [{ header: '@@ -1,3 +1,4 @@', oldStart: 1, oldLines: 3, newStart: 1, newLines: 4, patch: ' one\n-two\n+TWO\n+2b\n three' }],
    },
    {
      path: 'b.ts',
      status: 'added',
      additions: 1,
      deletions: 0,
      isBinary: false,
      sha: '2',
      hunks: [{ header: '@@ -0,0 +1,1 @@', oldStart: 0, oldLines: 0, newStart: 1, newLines: 1, patch: '+other' }],
    },
  ],
}

afterEach(() => setCliRunner(undefined))

describe('selection', () => {
  it('labels single lines and ranges', () => {
    expect(selectionLabel({ path: 'a.ts', side: 'additions', startLine: 2, endLine: 2 })).toBe('a.ts:L2')
    expect(selectionLabel({ path: 'a.ts', side: 'additions', startLine: 2, endLine: 3 })).toBe('a.ts:L2-L3')
  })

  it('quotes the selected lines with the removed lines between them, and the enclosing hunk', () => {
    const context = selectionContext(diff, { path: 'a.ts', side: 'additions', startLine: 1, endLine: 2 })!
    expect(context.lines.split('\n')).toEqual([
      'L1     R1      one',
      'L2            -two',
      '       R2     +TWO',
    ])
    expect(context.hunks).toContain('### a.ts [modified, +2/-1]\n[hunk 0] @@ -1,3 +1,4 @@')
    expect(selectionContext(diff, { path: 'a.ts', side: 'deletions', startLine: 9, endLine: 9 })).toBeUndefined()
  })
})

describe('buildAskPrompt', () => {
  it('includes the rest of the diff, the last turns and the question', () => {
    const history = Array.from({ length: 6 }, (_, index) => ({ question: `q${index}`, answer: `a${index}` }))
    const prompt = buildAskPrompt(diff, { selection: { path: 'a.ts', side: 'additions', startLine: 2, endLine: 3 }, question: 'Why 2b?', history })
    expect(prompt).toContain('## Selected: a.ts:L2-L3 (new file)')
    expect(prompt).toContain('### b.ts [added, +1/-0]\n[hunk 0] @@ -0,0 +1,1 @@\n+other')
    expect(prompt).not.toContain('q1')
    expect(prompt).toContain('Earlier question: q2\nYour answer: a2')
    expect(prompt.endsWith('## Question\n\nWhy 2b?')).toBe(true)
  })
})

describe('askAboutSelection', () => {
  it('runs the ask model without a schema and returns its prose', async () => {
    const runner = vi.fn(async (_request: CliRunRequest) => ({ text: ' Because. \n' }))
    setCliRunner(runner)
    const resolved = resolveAskModel({ ...defaultLlmSettings, provider: 'claude-code', askProvider: 'codex', askModel: 'gpt-5' })!
    const result = await askAboutSelection(diff, resolved, { selection: { path: 'a.ts', side: 'additions', startLine: 2, endLine: 2 }, question: 'Why?', history: [] })
    expect(result.text).toBe('Because.')
    expect(runner).toHaveBeenCalledWith(expect.objectContaining({ engine: 'codex', model: 'gpt-5', label: 'Ask about a.ts:L2', context: expect.objectContaining({ number: 3 }) }), expect.anything())
    expect(runner.mock.calls[0]![0]).not.toHaveProperty('schema')
    // An answer links what it mentions, so the phone scrolls there (rule 96).
    expect(runner.mock.calls[0]![0].system).toBe(ASK_SYSTEM_PROMPT)
    expect(ASK_SYSTEM_PROMPT).toContain(MENTION_RULE)
  })
})
