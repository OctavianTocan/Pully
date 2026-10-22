import type { CritiqueResult } from '../../../types/analyze'
import type { ReviewExperiments } from '../../../types/experiments'
import { assessMergeRisk } from '../../../types/merge-risk'
import { MERGE_RISK_PROMPT, MERGE_RISK_SCHEMA, mergeRiskMarkdown } from './merge-risk'

/**
 * @cc [owner:OctavianTocan,label:product;architecture] review-experiments-are-explicit-extensions
 * Without an explicit true opt-in, ordinary review MUST NOT request, compute or export
 * experimental assessments. Experiment prompt, schema and result changes MUST live
 * behind this boundary. Source: Tavi, 21 Oct 2026, experimental opt-in/removability request.
 */
export function reviewExperiment(experiments?: ReviewExperiments) {
  if (experiments?.mergeRisk !== true)
    return undefined
  return {
    prompt: MERGE_RISK_PROMPT,
    properties: { mergeRisk: MERGE_RISK_SCHEMA },
    required: ['mergeRisk'],
    suffix: ':experimental-merge-risk',
    result: (answer: { mergeRisk?: unknown }) => ({ mergeRisk: assessMergeRisk(answer.mergeRisk) }),
    body: (result: CritiqueResult) => result.mergeRisk ? mergeRiskMarkdown(result.mergeRisk) : '',
  }
}
