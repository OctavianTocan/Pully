import * as v from 'valibot'
import { CritiqueUsageSchema } from './analyze'

/**
 * A finished PR brief: the page an agent designed for one diff at one head commit.
 * `lang` is OpenUI Lang, the source of truth; readers parse it with the brief library
 * (`parseBrief`) instead of storing a tree, so a newer parser applies to older briefs.
 */
export const BriefResultSchema = v.object({
  lang: v.string(),
  headSha: v.optional(v.string()),
  engine: v.string(),
  model: v.optional(v.string()),
  effort: v.optional(v.string()),
  locale: v.string(),
  generatedAt: v.string(),
  usage: v.optional(CritiqueUsageSchema),
})
export type BriefResult = v.InferOutput<typeof BriefResultSchema>
