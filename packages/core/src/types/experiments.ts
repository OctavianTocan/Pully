import * as v from 'valibot'

/** Explicit per-run opt-in. Omission never enables an experiment. */
export const ReviewExperimentsSchema = v.object({ mergeRisk: v.optional(v.boolean()) })
export type ReviewExperiments = v.InferOutput<typeof ReviewExperimentsSchema>
