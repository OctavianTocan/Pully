import type { RuleGroupFacts } from '@pulls.review/core/analyze'
import { describe, expect, it } from 'vitest'
import { ruleText } from './index'

const none: RuleGroupFacts = { files: 3, additions: 10, deletions: 2, added: 0, removed: 0, renamed: 0, pairedTests: 0 }

describe('ruleText', () => {
  it('keeps the plain blurb when there is nothing to add', () => {
    expect(ruleText('docs', none)).toEqual({ label: 'Docs', summary: 'Documentation updates.' })
  })

  it('lists new, deleted, renamed and paired-test counts after the blurb, pluralized', () => {
    expect(ruleText('code', { ...none, added: 2, removed: 1, renamed: 1, pairedTests: 1 }).summary)
      .toBe('Source changes. 2 new files, 1 deleted, 1 renamed, 1 test right after its source.')
    expect(ruleText('code', { ...none, added: 1, pairedTests: 3 }).summary)
      .toBe('Source changes. 1 new file, 3 tests right after their source.')
  })

  it('names the area of a split code group', () => {
    expect(ruleText('code', { ...none, area: 'packages/app' }).label).toBe('Code · packages/app')
    expect(ruleText('code', { ...none, area: '' }).label).toBe('Code · repo root')
    expect(ruleText('code', { ...none, otherAreas: true }).label).toBe('Code · other areas')
  })

  it('has a label for every new rule', () => {
    expect(['types', 'data', 'styles', 'scripts', 'ci', 'i18n', 'assets'].map(key => ruleText(key as never, none).label))
      .toEqual(['Types', 'Data', 'Styles', 'Scripts', 'CI', 'Translations', 'Assets'])
  })
})
