import * as v from 'valibot'
import rubric from './merge-risk-rubric.json'

export const MERGE_RISK_CATEGORIES = ['alignment', 'impact', 'verification', 'rollbackCost', 'errorDetection'] as const
export const MergeRiskLevelSchema = v.picklist(['low', 'medium', 'high'])
const reasons = v.pipe(v.array(v.pipe(v.string(), v.trim(), v.nonEmpty())), v.minLength(1))
const quality = v.picklist(['poor', 'decent', 'good'])

export const MergeRiskAssessmentSchema = v.object({
  alignment: v.object({ verdict: quality, reasons }),
  impact: v.object({ verdict: v.picklist(['large', 'medium', 'small']), reasons }),
  verification: v.object({ verdict: quality, reasons }),
  rollbackCost: v.object({ verdict: v.picklist(['high', 'medium', 'low']), reasons }),
  errorDetection: v.object({ verdict: v.picklist(['poor', 'decent', 'good', 'not-applicable']), reasons }),
})
export type MergeRiskAssessment = v.InferOutput<typeof MergeRiskAssessmentSchema>

/**
 * @cc [owner:OctavianTocan,label:product] merge-risk-weighted-verdicts
 * The overall level MUST use Warp merge-risk's category weights and thresholds from
 * merge-risk-rubric.json, including N/A as score 1. Numerical scores MUST stay internal.
 * Source: Tavi's 21 Oct 2026 request to build warpdotdev/common-skills merge-risk into Pully.
 */
export function mergeRiskLevel(assessment: MergeRiskAssessment): v.InferOutput<typeof MergeRiskLevelSchema> {
  let weighted = 0
  let weights = 0
  for (const key of MERGE_RISK_CATEGORIES) {
    const category = rubric.categories[key]
    const scores: Record<string, number> = category.verdicts
    weighted += scores[assessment[key].verdict]! * category.weight
    weights += category.weight
  }
  const score = weighted / weights
  return score < rubric.thresholds.low ? 'low' : score < rubric.thresholds.medium ? 'medium' : 'high'
}

/** Saved assessments are data. Reading a cache must not activate experimental computation. */
export const MergeRiskSchema = v.object({ ...MergeRiskAssessmentSchema.entries, level: MergeRiskLevelSchema })
export type MergeRisk = v.InferOutput<typeof MergeRiskSchema>

/** Validates a model's category verdicts before calculating the app-owned overall rating. */
export function assessMergeRisk(value: unknown): MergeRisk {
  const assessment = v.parse(MergeRiskAssessmentSchema, value)
  return { ...assessment, level: mergeRiskLevel(assessment) }
}
