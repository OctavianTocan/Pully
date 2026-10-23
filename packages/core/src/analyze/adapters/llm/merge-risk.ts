import type { MergeRisk } from '../../../types/merge-risk'
import rubric from '../../../types/merge-risk-rubric.json'

export const MERGE_RISK_PROMPT = rubric.instructions.join('\n\n')

/** Category-only model schema; the app owns the overall rating. Shared with the Rust backend. */
export const MERGE_RISK_SCHEMA = {
  type: 'object',
  properties: Object.fromEntries(Object.entries(rubric.categories).map(([key, category]) => [key, {
    type: 'object',
    properties: {
      verdict: { type: 'string', enum: Object.keys(category.verdicts) },
      reasons: { type: 'array', items: { type: 'string', minLength: 1 }, minItems: 1 },
    },
    required: ['verdict', 'reasons'],
    additionalProperties: false,
  }])),
  required: Object.keys(rubric.categories),
  additionalProperties: false,
} as const

/** The human-readable assessment for a GitHub review, without numeric scores. */
export function mergeRiskMarkdown(risk: MergeRisk): string {
  const categories = Object.entries(rubric.categories).map(([key, category]) => {
    const assessment = risk[key as keyof typeof rubric.categories]
    const verdict = assessment.verdict === 'not-applicable' ? 'N/A' : assessment.verdict
    return `**${category.label}: ${verdict}**\n${assessment.reasons.map(reason => `- ${reason}`).join('\n')}`
  })
  return [`**Merge risk (experimental): ${risk.level}**`, ...categories].join('\n\n')
}
