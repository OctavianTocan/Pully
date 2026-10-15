import * as v from 'valibot'
import { CritiqueUsageSchema } from './analyze'

/** A piece of a tour stop: prose, a monospace sketch (call tree, file tree, pseudocode, excerpt), or Mermaid source. */
export const TourBlockSchema = v.variant('kind', [
  v.object({ kind: v.literal('text'), text: v.string() }),
  v.object({ kind: v.literal('shape'), text: v.string(), lang: v.string(), body: v.string() }),
  v.object({ kind: v.literal('diagram'), text: v.string(), body: v.string() }),
])
export type TourBlock = v.InferOutput<typeof TourBlockSchema>

export const TourStopSchema = v.object({
  title: v.string(),
  purpose: v.string(),
  files: v.array(v.string()),
  takeaway: v.string(),
  blocks: v.array(TourBlockSchema),
  sources: v.array(v.object({ path: v.string(), line: v.number(), why: v.string() })),
  /** Set when this stop could not be written; the rest of the tour stands. */
  error: v.optional(v.string()),
})
export type TourStop = v.InferOutput<typeof TourStopSchema>

/** A finished tour of one diff at one head commit. */
export const TourResultSchema = v.object({
  title: v.string(),
  overview: v.string(),
  stops: v.array(TourStopSchema),
  glossary: v.array(v.object({ term: v.string(), meaning: v.string() })),
  headSha: v.optional(v.string()),
  engine: v.string(),
  model: v.optional(v.string()),
  effort: v.optional(v.string()),
  locale: v.string(),
  generatedAt: v.string(),
  usage: v.optional(CritiqueUsageSchema),
})
export type TourResult = v.InferOutput<typeof TourResultSchema>

/** The planner's answer, before any stop is written. */
export interface TourPlan {
  title: string
  overview: string
  /** `form` is the shape the planner picked for the stop (a key of `TOUR_FORMS`), so stops written in parallel still vary. */
  stops: { title: string, purpose: string, files: string[], notes: string, form?: string }[]
  glossary: { term: string, meaning: string }[]
}
