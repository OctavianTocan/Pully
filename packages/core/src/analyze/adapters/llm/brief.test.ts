import type { GroupedResult } from '../../../types/analyze'
import type { DiffsPayload } from '../../../types/diff'
import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { normalizeGroupedResult } from '../../../types/analyze'
import { BRIEF_SYSTEM_PROMPT, briefLibrary, briefProblems, briefProgramOf, briefTree, buildBriefPrompt, buildRepairPrompt, MAX_BRIEF_REPAIRS, parseBrief, runBrief } from './brief'
import { setCliRunner } from './cli-stream'
import { MENTION_RULE } from './prompt'

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '7' },
  title: 'Cache the parser',
  description: 'Parses once per file.',
  head: { sha: 'h1', ref: 'feat' },
  base: { sha: 'b1', ref: 'main' },
  files: [
    { path: 'src/parse.ts', status: 'modified', additions: 2, deletions: 1, sha: 's1', hunks: [{ header: '@@ -1,2 +1,3 @@', oldStart: 1, oldLines: 2, newStart: 1, newLines: 3, patch: ' a\n-b\n+c\n+d' }] },
    { path: 'src/cache.ts', status: 'added', additions: 1, deletions: 0, sha: 's2', hunks: [{ header: '@@ -0,0 +1,1 @@', oldStart: 0, oldLines: 0, newStart: 1, newLines: 1, patch: '+export const cache = new Map()' }] },
  ],
} as never

const good = `root = Stack([lead, check])
lead = Callout("risk", "The cache never evicts.", "Unbounded")
check = Checklist([CheckItem("Entries are keyed by file sha", "src/cache.ts", 1), CheckItem("A changed file misses the cache")])`

afterEach(() => setCliRunner(undefined))

describe('the tree the Android app reads', () => {
  const fixture = (name: string) => readFileSync(new URL(`../../../../../../android/core/src/test/resources/${name}`, import.meta.url), 'utf8')

  it('is exactly what the Kotlin parser is tested against', () => {
    const lang = fixture('brief-fixture.lang')
    expect(briefProblems(parseBrief(lang))).toEqual([])
    expect(briefTree(lang)).toEqual(JSON.parse(fixture('brief-fixture.json')))
  })
})

describe('brief library', () => {
  it('parses the examples the prompt teaches without a single problem', () => {
    const examples = BRIEF_SYSTEM_PROMPT.split(/^root = /m).slice(1).map(body => `root = ${body.split('\n\n')[0]}`)
    expect(examples.length).toBeGreaterThanOrEqual(2)
    for (const example of examples) {
      const result = parseBrief(example)
      expect(briefProblems(result), example).toEqual([])
      expect(briefTree(example)).toBeDefined()
    }
  })

  it('teaches the components by their signatures', () => {
    expect(BRIEF_SYSTEM_PROMPT).toContain('Stack(children:')
    expect(BRIEF_SYSTEM_PROMPT).toContain('FileEntry(path: string, note: string, line?: number)')
    expect(Object.keys(briefLibrary.components)).toEqual(expect.arrayContaining(['Stack', 'Section', 'Text', 'Callout', 'Table', 'Checklist', 'FileList', 'Diagram', 'Finding']))
  })

  it('hands clients a plain tree of components with their children inline', () => {
    expect(briefTree(good)).toEqual({
      type: 'Stack',
      props: {
        children: [
          { type: 'Callout', props: { tone: 'risk', text: 'The cache never evicts.', title: 'Unbounded' } },
          { type: 'Checklist', props: { items: [
            { type: 'CheckItem', props: { text: 'Entries are keyed by file sha', path: 'src/cache.ts', line: 1 } },
            { type: 'CheckItem', props: { text: 'A changed file misses the cache' } },
          ] } },
        ],
      },
    })
  })

  it('keeps a section\'s blocks inside it and unfolds by default', () => {
    const tree = briefTree('root = Stack([s])\ns = Section("Details", [Text("a"), Text("b")])')
    expect(tree?.props.children).toEqual([{ type: 'Section', props: { title: 'Details', children: [{ type: 'Text', props: { markdown: 'a' } }, { type: 'Text', props: { markdown: 'b' } }] } }])
  })

  it('names what is wrong in words the writer can act on', () => {
    const problems = briefProblems(parseBrief('root = Stack([a, c])\na = Text(1)\nb = Text("never used")\nc = Nope("x")'))
    expect(problems.some(problem => problem.includes('expects string'))).toBe(true)
    expect(problems.some(problem => problem.includes('`b` is defined but no other statement uses it'))).toBe(true)
    expect(problems.some(problem => problem.includes('Unknown component "Nope"'))).toBe(true)
  })

  it('flags a program with no root or no blocks', () => {
    expect(briefProblems(parseBrief('a = Text("x")'))).toEqual(expect.arrayContaining([expect.stringContaining('no `root')]))
    expect(briefProblems(parseBrief('root = Stack([])'))).toEqual(['The page has no blocks.'])
    expect(briefTree('root = Stack([])')).toBeUndefined()
  })

  it('drops a block whose props are wrong instead of drawing it half-built', () => {
    const tree = briefTree('root = Stack([a, b])\na = Callout("loud", "x")\nb = Text("fine")')
    expect(tree?.props.children).toEqual([{ type: 'Text', props: { markdown: 'fine' } }])
  })
})

describe('reading an answer', () => {
  it('unwraps a code fence', () => {
    expect(briefProgramOf(`\`\`\`openui\n${good}\n\`\`\``)).toBe(good)
  })

  it('skips chatter before the program', () => {
    expect(briefProgramOf(`Here is the page:\n\n${good}`)).toBe(good)
  })

  it('leaves a bare program alone', () => {
    expect(briefProgramOf(`${good}\n`)).toBe(good)
  })
})

describe('prompts', () => {
  const analysis: GroupedResult = normalizeGroupedResult('llm', {
    overallSummary: 'Adds a parse cache.',
    groups: [{ key: 'cache', label: 'Parse cache', summary: 'A map keyed by sha.', category: 'core', filePaths: ['src/cache.ts'], children: [{ key: 'use', label: 'Call site', summary: 'x', category: 'core', filePaths: ['src/parse.ts'] }] }],
    schemaVersion: 1,
  })

  it('carries the PR, the analysis, the manifest and the language', () => {
    const prompt = buildBriefPrompt(diff, 'de', analysis)
    expect(prompt).toContain('Parses once per file.')
    expect(prompt).toContain('Adds a parse cache.')
    expect(prompt).toContain('- Parse cache: A map keyed by sha. (src/cache.ts, src/parse.ts)')
    expect(prompt).toContain('## Changed files')
    expect(prompt).toContain('Write the strings in German.')
    expect(prompt).toContain(MENTION_RULE)
  })

  it('works without an analysis', () => {
    expect(buildBriefPrompt(diff, 'en')).not.toContain('What the analysis found')
  })

  it('asks for a fix by quoting the program and each problem', () => {
    const repair = buildRepairPrompt('ORIGINAL', 'root = Nope()', ['Unknown component "Nope"'])
    expect(repair).toContain('ORIGINAL')
    expect(repair).toContain('root = Nope()')
    expect(repair).toContain('- Unknown component "Nope"')
  })
})

describe('runBrief', () => {
  const resolved = { model: { provider: 'claude-code', id: 'sonnet' }, effort: 'high' } as never

  it('returns the program the agent wrote, with the head commit and model', async () => {
    const runner = vi.fn(async (_request: object) => ({ text: good, usage: { model: 'sonnet-5', costUsd: 0.1 } }))
    setCliRunner(runner as never)
    const brief = await runBrief(diff, resolved, { locale: 'en' })
    expect(runner).toHaveBeenCalledTimes(1)
    expect(brief).toMatchObject({ lang: good, headSha: 'h1', engine: 'claude-code', model: 'sonnet-5', effort: 'high', locale: 'en' })
    expect(runner.mock.calls[0]![0]).toMatchObject({ engine: 'claude-code', system: BRIEF_SYSTEM_PROMPT, context: expect.objectContaining({ owner: 'o', repo: 'r', number: 7 }) })
  })

  it('asks for a fix when the program does not parse cleanly and keeps the fixed one', async () => {
    const runner = vi.fn(async (request: { prompt: string }) => ({ text: request.prompt.includes('What the parser found wrong') ? good : 'root = Stack([a])\na = Text(1)' }))
    setCliRunner(runner as never)
    const progress: string[] = []
    const brief = await runBrief(diff, resolved, { locale: 'en', onActivity: batch => progress.push(...batch.map(entry => entry.title)) })
    expect(runner).toHaveBeenCalledTimes(2)
    expect(brief.lang).toBe(good)
    expect(progress).toEqual(['Designing the brief', 'Fixing 2 problems in the brief', 'Designed the brief'])
  })

  it('accepts a page that still has blocks after the repairs run out', async () => {
    const flawed = 'root = Stack([a, b])\na = Text("ok")\nb = Text(2)'
    const runner = vi.fn(async () => ({ text: flawed }))
    setCliRunner(runner as never)
    const brief = await runBrief(diff, resolved, { locale: 'en' })
    expect(runner).toHaveBeenCalledTimes(MAX_BRIEF_REPAIRS + 1)
    expect(brief.lang).toBe(flawed)
  })

  it('fails when nothing parses to a page', async () => {
    setCliRunner((async () => ({ text: 'I could not do that.' })) as never)
    await expect(runBrief(diff, resolved, { locale: 'en' })).rejects.toThrow('The brief did not parse')
  })

  it('needs a CLI engine', async () => {
    await expect(runBrief(diff, { model: { provider: 'anthropic', id: 'x' } } as never, { locale: 'en' })).rejects.toThrow('needs Claude Code or Codex')
  })
})
