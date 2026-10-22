import type { DiffsPayload } from './diff'
import * as v from 'valibot'
import { DiffSideSchema } from './comment-threads'
import { MergeRiskSchema } from './merge-risk'

export const GroupSourceSchema = v.picklist([
  'none',
  'rule-based',
  'llm',
  'web-llm',
])
export type GroupSource = v.InferOutput<typeof GroupSourceSchema>

/**
 * Which part of the system a group touches - one axis, language-agnostic, so the view
 * can give every group a recognizable icon and color. Deliberately not the nature of
 * the change (feature/fix/refactor): groups already partition the PR by intent.
 */
export const DiffCategorySchema = v.picklist([
  'ui',
  'api',
  'core',
  'data',
  'cli',
  'security',
  'tests',
  'docs',
  'examples',
  'deps',
  'build',
  'scripts',
  'config',
  'i18n',
  'assets',
  'other',
])
export type DiffCategory = v.InferOutput<typeof DiffCategorySchema>

const CATEGORY_GUIDE = [
  'ui: components, views, styles, layout.',
  'api: endpoints, handlers, contracts between services or packages, third-party integrations.',
  'core: domain/business logic and internal modules; use only when ui/api/data/cli don\'t fit.',
  'data: schemas, migrations, models, queries, storage.',
  'cli: command-line entry points, arg parsing, terminal output.',
  'security: auth, permissions, secrets handling, input validation.',
  'tests: tests, fixtures, snapshots, stories, benchmarks.',
  'docs: README, guides, changelog, comment-only edits.',
  'examples: examples, playgrounds, demos.',
  'deps: dependency bumps, lockfiles.',
  'build: bundler/compiler/toolchain config, package manifests.',
  'scripts: dev and one-off scripts, automation under bin/ or tools/.',
  'config: runtime config, env, feature flags, linter config, CI/CD, deployment, containers, IaC.',
  'i18n: translations, locales.',
  'assets: images, fonts, static files.',
  'other: generated/vendored code or anything that fits nothing above.',
].join(' ')

/**
 * Leaf group shape (no further nesting), reused for both root groups and their children,
 * which structurally enforces the "max depth 2" decision rather than relying on convention.
 *
 * Field `description`s double as the llm adapter's field-level instructions - the model
 * sees them directly in the `submit_grouping` tool's parameter schema, so `prompt.ts` only
 * needs high-level framing, not a restatement of these per-field rules.
 */
export const SubmittedGroupLeafSchema = v.object({
  key: v.pipe(v.string(), v.description('Stable, short, kebab-case-ish id, e.g. "docs" or "feature-a".')),
  label: v.pipe(v.string(), v.description('Short, human-readable display name for this group.')),
  summary: v.optional(v.pipe(v.string(), v.description('Concise explanation of the intention of this group (why over what). Rendered as Markdown.'))), // populated only when an llm/web-llm adapter has run
  category: v.pipe(DiffCategorySchema, v.description(`Which part of the system this group touches. ${CATEGORY_GUIDE}`)),
  filePaths: v.pipe(v.array(v.string()), v.description('File paths belonging directly to this group (not to a child). Every file path given to you MUST end up in exactly one group or child - never both, never omitted.')), // references into DiffsPayload.files by path
})

/**
 * The stored/shared shape: results cached or shared before `category` existed lack it,
 * so only the llm adapter's tool schema (`SubmittedGroupLeafSchema`) requires it. The
 * view falls back to 'other'.
 */
export const DiffGroupLeafSchema = v.object({
  ...SubmittedGroupLeafSchema.entries,
  category: v.optional(SubmittedGroupLeafSchema.entries.category),
})
export type DiffGroupLeaf = v.InferOutput<typeof DiffGroupLeafSchema>

/** Adds the single allowed nesting level to a leaf shape (depth capped at 2 total: root -> children). */
export function withChildren<const TEntries extends v.ObjectEntries>(leaf: v.ObjectSchema<TEntries, undefined>) {
  return v.object({
    ...leaf.entries,
    children: v.optional(v.pipe(v.array(leaf), v.description('One extra level of nesting, e.g. splitting a large group by sub-area. Children cannot have children of their own.'))),
  })
}

export const DiffGroupSchema = withChildren(DiffGroupLeafSchema)
export type DiffGroup = v.InferOutput<typeof DiffGroupSchema>

export const HunkNoteSchema = v.object({
  index: v.pipe(v.number(), v.description('0-based position of the hunk in this file\'s diff: the number in the `[hunk N]` label before its `@@` header.')),
  note: v.pipe(v.string(), v.description('One or two sentences on what this hunk does, naming the function, type, flag or value it touches and what it changes for callers. Never a description of the act of changing, never a restatement of the added lines.')),
})
export type HunkNote = v.InferOutput<typeof HunkNoteSchema>

/** What one changed file does, with optional notes on its hunks; written by the llm adapter. */
export const FileNoteSchema = v.object({
  path: v.pipe(v.string(), v.description('The file path, verbatim from the manifest.')),
  summary: v.pipe(v.string(), v.description('Two or three sentences on what this file\'s changes do and why, naming the actual functions, types and flags. Plain text, no Markdown headings.')),
  hunks: v.optional(v.pipe(v.array(HunkNoteSchema), v.description('Notes for the hunks a reviewer would otherwise have to puzzle out. Skip hunks the file summary already explains.'))),
})
export type FileNote = v.InferOutput<typeof FileNoteSchema>

/**
 * What an adapter itself decides by analyzing the diff. `source` (which adapter ran)
 * and `generatedAt` (when) are invocation metadata the caller already knows - not
 * something the adapter decides - so they're stamped on separately to normalize this
 * into the full `GroupedResultSchema` below, instead of every adapter repeating them.
 */
export const GroupedResultCoreSchema = v.object({
  overallSummary: v.optional(v.pipe(v.string(), v.description('Short paragraph summarizing the whole PR for a reviewer, rendered as Markdown.'))), // only when an 'llm' or 'web-llm' adapter has run
  groups: v.array(DiffGroupSchema),
  /** Per-file summaries and hunk notes; absent on rule-based results and on llm results from before they existed. */
  files: v.optional(v.array(FileNoteSchema)),
  schemaVersion: v.number(), // bump on breaking shape changes, used for cache invalidation
})
export type GroupedResultCore = v.InferOutput<typeof GroupedResultCoreSchema>

export const GroupedResultSchema = v.object({
  ...GroupedResultCoreSchema.entries,
  source: GroupSourceSchema,
  generatedAt: v.string(),
  /** `provider/model-id` that produced an llm/web-llm result. */
  model: v.optional(v.string()),
  /** Login of the user whose shared PR comment this result was loaded from (see plans/07). */
  sharedBy: v.optional(v.string()),
  /** BCP 47 tag of the language the summaries were written in; absent on rule-based results and on llm results from before the setting existed. */
  locale: v.optional(v.string()),
})
export type GroupedResult = v.InferOutput<typeof GroupedResultSchema>

/** Stamps the invocation metadata an adapter doesn't decide onto its analysis. */
export function normalizeGroupedResult(source: GroupSource, core: GroupedResultCore, model?: string): GroupedResult {
  return { ...core, source, generatedAt: new Date().toISOString(), model }
}

export const CritiqueSeveritySchema = v.picklist(['bug', 'risk', 'nit'])
export type CritiqueSeverity = v.InferOutput<typeof CritiqueSeveritySchema>

/** One problem an AI critique found, anchored to a line (or range) that exists in the diff. */
export const CritiqueFindingSchema = v.object({
  id: v.string(),
  path: v.string(),
  side: DiffSideSchema,
  line: v.number(),
  /** First line of a multi-line range on the same side; absent for a single line. */
  startLine: v.optional(v.number()),
  severity: CritiqueSeveritySchema,
  title: v.string(),
  /** GitHub-flavored Markdown. */
  body: v.string(),
  /** Replacement for lines `startLine..line` on the additions side, without fences. */
  suggestion: v.optional(v.string()),
})
export type CritiqueFinding = v.InferOutput<typeof CritiqueFindingSchema>

export const CritiqueUsageSchema = v.object({
  inputTokens: v.optional(v.number()),
  outputTokens: v.optional(v.number()),
  cacheReadTokens: v.optional(v.number()),
  cacheWriteTokens: v.optional(v.number()),
  reasoningTokens: v.optional(v.number()),
  costUsd: v.optional(v.number()),
  durationMs: v.optional(v.number()),
  model: v.optional(v.string()),
})

/** A finished AI critique of one diff at one head commit. */
export const CritiqueResultSchema = v.object({
  summary: v.string(),
  /** Whole-change merge assessment; absent on reviews generated before merge-risk support. */
  mergeRisk: v.optional(MergeRiskSchema),
  findings: v.array(CritiqueFindingSchema),
  /** Findings the model returned that were discarded: off-diff anchors, duplicates, or over the cap. */
  dropped: v.number(),
  headSha: v.optional(v.string()),
  engine: v.string(),
  model: v.optional(v.string()),
  effort: v.optional(v.string()),
  /** Name of the review lens (skill) whose instructions steered the critique. */
  lens: v.optional(v.string()),
  generatedAt: v.string(),
  usage: v.optional(CritiqueUsageSchema),
})
export type CritiqueResult = v.InferOutput<typeof CritiqueResultSchema>

/** What the llm adapter is doing, by agent turn; the caller renders it in its own words. */
export type AnalyzeProgress = { step: number } & (
  | { kind: 'thinking' }
  | { kind: 'reading', paths: string[] }
  | { kind: 'organizing' }
)
export interface AnalyzeOptions { onProgress?: (progress: AnalyzeProgress) => void, signal?: AbortSignal }

export interface AnalyzeAdapter {
  readonly id: GroupSource // 'none' | 'rule-based' | 'llm' | 'web-llm'
  readonly available: boolean // none/rule-based: always true; llm: true once a key is configured; web-llm: true once a local model is loaded
  analyze: (diff: DiffsPayload, options?: AnalyzeOptions) => Promise<GroupedResult>
}
