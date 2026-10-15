import type { GroupedResult } from '../../types/analyze'
import { describe, expect, it } from 'vitest'
import { githubCites, githubCitesInResult, githubCiteUrl } from './cites'

const at = { owner: 'vuejs', repo: 'core', sha: 'abc123' }
const blob = 'https://github.com/vuejs/core/blob/abc123'

describe('githubCiteUrl', () => {
  it('points a repository path at the file on GitHub, with its line or range', () => {
    expect(githubCiteUrl('src/cache/key.ts#L42', at)).toBe(`${blob}/src/cache/key.ts#L42`)
    expect(githubCiteUrl('src/cache/key.ts#L42-L50', at)).toBe(`${blob}/src/cache/key.ts#L42-L50`)
    expect(githubCiteUrl('src/cache/key.ts#L42-50', at)).toBe(`${blob}/src/cache/key.ts#L42-L50`)
    expect(githubCiteUrl('key.ts:7', at)).toBe(`${blob}/key.ts#L7`)
    expect(githubCiteUrl('./src/a.ts', at)).toBe(`${blob}/src/a.ts`)
    expect(githubCiteUrl('/src/a.ts', at)).toBe(`${blob}/src/a.ts`)
    expect(githubCiteUrl('pages/[id].vue', at)).toBe(`${blob}/pages/%5Bid%5D.vue`)
  })

  it('leaves web links, anchors and folders alone', () => {
    expect(githubCiteUrl('https://example.com/a.ts', at)).toBeUndefined()
    expect(githubCiteUrl('mailto:someone@example.com', at)).toBeUndefined()
    expect(githubCiteUrl('#overview', at)).toBeUndefined()
    expect(githubCiteUrl('//cdn.example.com/x.js', at)).toBeUndefined()
    expect(githubCiteUrl('src/', at)).toBeUndefined()
  })
})

describe('githubCites', () => {
  it('rewrites the links in prose and keeps code as it is', () => {
    const text = [
      'Moves [`cacheKey`](src/cache/key.ts#L42 "the key") into [key.ts](src/cache/key.ts); see [docs](https://vuejs.org).',
      'Code like `[a](b.ts)` stays.',
      '```md',
      '[a](b.ts)',
      '```',
      'After the fence, [b.ts](b.ts#L3) is a link again.',
    ].join('\n')
    expect(githubCites(text, at)).toBe([
      `Moves [\`cacheKey\`](${blob}/src/cache/key.ts#L42 "the key") into [key.ts](${blob}/src/cache/key.ts); see [docs](https://vuejs.org).`,
      'Code like `[a](b.ts)` stays.',
      '```md',
      '[a](b.ts)',
      '```',
      `After the fence, [b.ts](${blob}/b.ts#L3) is a link again.`,
    ].join('\n'))
  })

  it('changes nothing in text without repository links', () => {
    const text = 'Plain words, a [link](https://example.com) and `code`.'
    expect(githubCites(text, at)).toBe(text)
  })
})

describe('githubCitesInResult', () => {
  it('rewrites every summary and note and nothing else', () => {
    const result: GroupedResult = {
      source: 'llm',
      generatedAt: '2026-10-15T00:00:00.000Z',
      schemaVersion: 1,
      overallSummary: 'See [a.ts](src/a.ts#L1).',
      groups: [{ key: 'core', label: 'Core', summary: 'In [a.ts](src/a.ts).', filePaths: ['src/a.ts'], children: [{ key: 'tests', label: 'Tests', summary: '[t](test/a.ts#L2)', filePaths: ['test/a.ts'] }] }],
      files: [{ path: 'src/a.ts', summary: 'Calls [b](src/b.ts#L3).', hunks: [{ index: 0, note: '[c](src/c.ts#L4)' }] }],
    }
    expect(githubCitesInResult(result, at)).toEqual({
      ...result,
      overallSummary: `See [a.ts](${blob}/src/a.ts#L1).`,
      groups: [{ ...result.groups[0], summary: `In [a.ts](${blob}/src/a.ts).`, children: [{ ...result.groups[0]!.children![0], summary: `[t](${blob}/test/a.ts#L2)` }] }],
      files: [{ path: 'src/a.ts', summary: `Calls [b](${blob}/src/b.ts#L3).`, hunks: [{ index: 0, note: `[c](${blob}/src/c.ts#L4)` }] }],
    })
  })
})
