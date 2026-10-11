import type { DiffsPayload, FileChange } from '../../../types/diff'
import type { RuleGroupFacts, RuleKey } from './rules'
import { describe, expect, it } from 'vitest'
import { createRuleBasedAdapter } from './index'

const seen: Array<{ key: RuleKey, facts: RuleGroupFacts }> = []
const ruleBasedAdapter = createRuleBasedAdapter((key, facts) => {
  seen.push({ key, facts })
  const label = key[0]!.toUpperCase() + key.slice(1)
  return {
    label: facts.otherAreas ? `${label} · *` : facts.area !== undefined ? `${label} · ${facts.area || '/'}` : label,
    summary: `${facts.files} files, +${facts.additions} -${facts.deletions}`,
  }
})

function file(path: string, overrides: Partial<FileChange> = {}): FileChange {
  return { path, status: 'modified', additions: 1, deletions: 0, isBinary: false, sha: path, hunks: [], ...overrides }
}

function diffOf(files: FileChange[]): DiffsPayload {
  return { ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '1' }, title: 't', description: '', files }
}

async function groupsOf(files: FileChange[]) {
  const result = await ruleBasedAdapter.analyze(diffOf(files))
  return result.groups
}

async function byKey(files: FileChange[]) {
  return Object.fromEntries((await groupsOf(files)).map(g => [g.key, g.filePaths]))
}

describe('ruleBasedAdapter', () => {
  it('groups files across multiple categories, flat (no nesting)', async () => {
    const result = await ruleBasedAdapter.analyze(diffOf([file('src/index.ts'), file('src/other.test.ts'), file('README.md'), file('package.json')]))

    expect(result.source).toBe('rule-based')
    expect(result.overallSummary).toBeUndefined()
    for (const group of result.groups)
      expect(group.children).toBeUndefined()

    expect(Object.fromEntries(result.groups.map(g => [g.key, g.filePaths]))).toEqual({
      code: ['src/index.ts'],
      tests: ['src/other.test.ts'],
      deps: ['package.json'],
      docs: ['README.md'],
    })
  })

  it('falls back to the code group for anything unmatched', async () => {
    const groups = await groupsOf([file('src/weird-file.xyz')])
    expect(groups).toHaveLength(1)
    expect(groups[0]?.key).toBe('code')
  })

  it('files binary images as assets and other binaries as other, regardless of path', async () => {
    expect(await byKey([file('docs/logo.png', { isBinary: true }), file('docs/data.zip', { isBinary: true }), file('src/index.ts')])).toEqual({
      code: ['src/index.ts'],
      assets: ['docs/logo.png'],
      other: ['docs/data.zip'],
    })
  })

  it('is always available and needs no network', () => {
    expect(ruleBasedAdapter.available).toBe(true)
  })

  it('labels every group with the supplied text, even without an LLM', async () => {
    const groups = await groupsOf([file('src/index.ts')])
    expect(groups[0]?.label).toBe('Code')
    expect(groups[0]?.summary).toBeTruthy()
  })

  it('tags every group with a category, mapping code to core and generated to other', async () => {
    const groups = await groupsOf([
      file('src/index.ts'),
      file('pnpm-lock.yaml'),
      file('README.md'),
      file('.github/workflows/ci.yml'),
      file('src/style.css'),
      file('src/env.d.ts'),
      file('db/migrations/1.sql'),
      file('locales/de.json'),
      file('scripts/release.ts'),
    ])
    expect(Object.fromEntries(groups.map(g => [g.key, g.category]))).toEqual({
      code: 'core',
      types: 'api',
      data: 'data',
      styles: 'ui',
      scripts: 'scripts',
      ci: 'build',
      i18n: 'i18n',
      docs: 'docs',
      generated: 'other',
    })
  })

  it('orders groups by review relevance, not by first appearance in the diff', async () => {
    const groups = await groupsOf([
      file('pnpm-lock.yaml'),
      file('README.md'),
      file('tsconfig.json'),
      file('src/a.test.ts'),
      file('src/b.ts'),
    ])
    expect(groups.map(g => g.key)).toEqual(['code', 'tests', 'config', 'docs', 'generated'])
  })

  it('places each test right after the source file it covers, leaving unmatched tests in the tests group', async () => {
    expect(await byKey([
      file('src/z.ts'),
      file('src/z.test.ts'),
      file('src/a.test.ts'),
      file('src/a.ts'),
      file('src/index.ts'),
      file('src/orphan.test.ts'),
      file('packages/reactivity/__tests__/computed.spec.ts'),
      file('packages/reactivity/src/computed.ts'),
    ])).toEqual({
      code: [
        'packages/reactivity/src/computed.ts',
        'packages/reactivity/__tests__/computed.spec.ts',
        'src/index.ts',
        'src/a.ts',
        'src/a.test.ts',
        'src/z.ts',
        'src/z.test.ts',
      ],
      tests: ['src/orphan.test.ts'],
    })
  })

  it('splits code by area when at least two areas have enough files, collecting the small ones', async () => {
    const files = [
      file('packages/app/src/a.ts'),
      file('packages/app/src/b.ts'),
      file('packages/app/src/c.ts'),
      file('packages/core/src/x.ts'),
      file('packages/core/src/x.test.ts'),
      file('packages/core/src/y.ts'),
      file('packages/cli/src/run.ts'),
      file('scripts/build.ts'),
      file('index.ts'),
    ]
    const groups = await groupsOf(files)
    expect(groups.map(g => [g.key, g.label, g.filePaths])).toEqual([
      ['code:packages/app', 'Code · packages/app', ['packages/app/src/a.ts', 'packages/app/src/b.ts', 'packages/app/src/c.ts']],
      ['code:packages/core', 'Code · packages/core', ['packages/core/src/x.ts', 'packages/core/src/x.test.ts', 'packages/core/src/y.ts']],
      ['code:*', 'Code · *', ['index.ts', 'packages/cli/src/run.ts']],
      ['scripts', 'Scripts', ['scripts/build.ts']],
    ])
    for (const group of groups)
      expect(group.category).toBe(group.key === 'scripts' ? 'scripts' : 'core')
  })

  it('labels a lone small area by name instead of as "other areas"', async () => {
    const groups = await groupsOf([
      file('src/a/1.ts'),
      file('src/a/2.ts'),
      file('src/a/3.ts'),
      file('src/b/1.ts'),
      file('src/b/2.ts'),
      file('src/b/3.ts'),
      file('index.ts'),
    ])
    expect(groups.map(g => g.label)).toEqual(['Code · src/a', 'Code · src/b', 'Code · /'])
  })

  it('keeps one code group when only one area is large enough', async () => {
    const groups = await groupsOf([
      file('packages/app/a.ts'),
      file('packages/app/b.ts'),
      file('packages/app/c.ts'),
      file('packages/core/x.ts'),
      file('packages/core/y.ts'),
    ])
    expect(groups.map(g => g.key)).toEqual(['code'])
    expect(groups[0]!.label).toBe('Code')
  })

  it('hands the text callback the group\'s measured facts', async () => {
    seen.length = 0
    await groupsOf([
      file('src/a.ts', { status: 'added', additions: 10, deletions: 0 }),
      file('src/a.test.ts', { status: 'added', additions: 5, deletions: 0 }),
      file('src/b.ts', { status: 'removed', additions: 0, deletions: 7 }),
      file('src/c.ts', { status: 'renamed', previousPath: 'src/old-c.ts', additions: 2, deletions: 2 }),
      file('README.md'),
    ])
    expect(seen).toEqual([
      { key: 'code', facts: { files: 4, additions: 17, deletions: 9, added: 2, removed: 1, renamed: 1, pairedTests: 1 } },
      { key: 'docs', facts: { files: 1, additions: 1, deletions: 0, added: 0, removed: 0, renamed: 0, pairedTests: 0 } },
    ])
  })

  it('names every file exactly once', async () => {
    const files = [
      'packages/app/src/a.ts',
      'packages/app/src/a.test.ts',
      'packages/app/src/b.ts',
      'packages/app/src/c.ts',
      'packages/core/src/x.ts',
      'packages/core/src/y.ts',
      'packages/core/src/z.ts',
      'packages/core/test/fixtures.json',
      'README.md',
      'pnpm-lock.yaml',
      '.github/workflows/ci.yml',
    ].map(path => file(path))
    const paths = (await groupsOf(files)).flatMap(g => g.filePaths)
    expect([...paths].sort()).toEqual(files.map(f => f.path).sort())
  })

  it('is stable: the same files in any order give the same groups', async () => {
    const files = [
      'packages/app/src/index.ts',
      'packages/app/src/components/Button.vue',
      'packages/app/src/components/Button.test.ts',
      'packages/app/src/style.css',
      'packages/core/src/a.ts',
      'packages/core/src/b.ts',
      'packages/core/src/index.ts',
      'packages/core/src/b.test.ts',
      'packages/cli/src/main.ts',
      'packages/app/src/i18n/locales/en.json',
      'packages/app/package.json',
      'pnpm-lock.yaml',
      'README.md',
      '.github/workflows/ci.yml',
      'tsconfig.json',
    ].map(path => file(path))

    const strip = (groups: Awaited<ReturnType<typeof groupsOf>>) => groups.map(({ key, label, summary, category, filePaths }) => ({ key, label, summary, category, filePaths }))
    const expected = strip(await groupsOf(files))
    expect(strip(await groupsOf([...files].reverse()))).toEqual(expected)
    // A fixed shuffle (no Math.random, so the test itself is deterministic).
    const shuffled = files.map((f, i) => ({ f, k: (i * 7) % files.length })).sort((a, b) => a.k - b.k).map(({ f }) => f)
    expect(strip(await groupsOf(shuffled))).toEqual(expected)
  })
})
