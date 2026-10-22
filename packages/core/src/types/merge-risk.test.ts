import * as v from 'valibot'
import { describe, expect, it } from 'vitest'
import { CritiqueResultSchema } from './analyze'
import { assessMergeRisk, MERGE_RISK_CATEGORIES, MergeRiskSchema } from './merge-risk'
import fixtures from './merge-risk.fixtures.json'

function assessment(verdicts = fixtures[0]!.verdicts) {
  return Object.fromEntries(MERGE_RISK_CATEGORIES.map((key, index) => [key, { verdict: verdicts[index], reasons: ['Evidence in [the change](src/main.ts#L1).'] }]))
}

describe('merge risk', () => {
  it.each(fixtures)('rates $name using the Warp weights and thresholds', ({ verdicts, level }) => {
    expect(assessMergeRisk(assessment(verdicts)).level).toBe(level)
  })

  it('computes a model answer without trusting its supplied level, while cache reads stay inert', () => {
    const risky = assessment(fixtures[5]!.verdicts)
    expect(assessMergeRisk({ ...risky, level: 'low' }).level).toBe('high')
    expect(v.parse(MergeRiskSchema, { ...risky, level: 'low' }).level).toBe('low')
  })

  it('requires all categories and actual evidence or explicit gaps', () => {
    const valid = assessment()
    const { alignment: _, ...missing } = valid
    for (const invalid of [null, {}, missing, { ...valid, alignment: { verdict: 'not-applicable', reasons: ['N/A'] } }, { ...valid, impact: { verdict: 'small', reasons: [] } }, { ...valid, verification: { verdict: 'good', reasons: ['  '] } }])
      expect(() => assessMergeRisk(invalid)).toThrow()
  })

  it('loads legacy saved critiques without inventing an assessment', () => {
    const old = v.parse(CritiqueResultSchema, { summary: 'Nothing to flag.', findings: [], dropped: 0, engine: 'codex', generatedAt: '' })
    expect(old.mergeRisk).toBeUndefined()
  })
})
