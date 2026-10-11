import type { AnalyzeAdapter, DiffCategory, DiffGroup, GroupedResultCore } from '../../../types/analyze'
import type { FileChange } from '../../../types/diff'
import type { RuleGroupFacts, RuleKey, RuleText } from './rules'
import { normalizeGroupedResult } from '../../../types/analyze'
import { areaOf, compareNames, comparePaths, pairTests } from './order'
import { codeRule, defaultRules, GROUP_ORDER, matchRule } from './rules'

export const RULE_BASED_SCHEMA_VERSION = 1

/** A code group is split by area only when at least two areas have this many files each. */
export const MIN_AREA_FILES = 3

/** Key of the split code group collecting the areas too small for a group of their own. */
const OTHER_AREAS_KEY = 'code:*'

interface Bucket {
  key: string
  ruleKey: RuleKey
  category: DiffCategory
  paths: string[]
  pairedTests: number
  area?: string
  otherAreas?: true
}

function factsOf(bucket: Bucket, byPath: Map<string, FileChange>): RuleGroupFacts {
  const files = bucket.paths.map(path => byPath.get(path)!)
  const facts: RuleGroupFacts = {
    files: files.length,
    additions: files.reduce((sum, file) => sum + file.additions, 0),
    deletions: files.reduce((sum, file) => sum + file.deletions, 0),
    added: files.filter(file => file.status === 'added').length,
    removed: files.filter(file => file.status === 'removed').length,
    renamed: files.filter(file => file.status === 'renamed').length,
    pairedTests: bucket.pairedTests,
  }
  if (bucket.area !== undefined)
    facts.area = bucket.area
  if (bucket.otherAreas)
    facts.otherAreas = true
  return facts
}

/**
 * Code in review order - each source file followed by the tests that cover it - split
 * into one group per area (`packages/<name>`, `src/<dir>`, ...) when the PR touches
 * at least two areas with `MIN_AREA_FILES` files each. Smaller areas share one
 * trailing group, labelled by their area when there's only one of them.
 */
function codeBuckets(sources: string[], pairs: Map<string, string[]>): Bucket[] {
  const units = [...sources].sort(comparePaths).map(source => ({ source, paths: [source, ...(pairs.get(source) ?? [])] }))
  const byArea = new Map<string, typeof units>()
  for (const unit of units) {
    const area = areaOf(unit.source)
    byArea.set(area, [...(byArea.get(area) ?? []), unit])
  }

  const size = (list: typeof units) => list.reduce((n, unit) => n + unit.paths.length, 0)
  const bucketOf = (list: typeof units, extra: Partial<Bucket>, key: string): Bucket => ({
    key,
    ruleKey: 'code',
    category: codeRule.category,
    paths: list.flatMap(unit => unit.paths),
    pairedTests: list.reduce((n, unit) => n + unit.paths.length - 1, 0),
    ...extra,
  })

  const large = [...byArea].filter(([, list]) => size(list) >= MIN_AREA_FILES)
  if (large.length < 2)
    return units.length ? [bucketOf(units, {}, 'code')] : []

  const areaKey = (area: string) => `code:${area || '.'}`
  const buckets = large
    .sort(([a], [b]) => compareNames(a, b))
    .map(([area, list]) => bucketOf(list, { area }, areaKey(area)))

  const small = [...byArea].filter(([, list]) => size(list) < MIN_AREA_FILES)
  if (small.length === 1)
    buckets.push(bucketOf(small[0]![1], { area: small[0]![0] }, areaKey(small[0]![0])))
  else if (small.length > 1)
    buckets.push(bucketOf(units.filter(unit => small.some(([, list]) => list.includes(unit))), { otherAreas: true }, OTHER_AREAS_KEY))
  return buckets
}

/**
 * The deterministic grouping: each file goes to its first matching rule (`rules.ts`),
 * tests move right after the code file they cover when both are in the diff, a large
 * code group splits by area, files sort by `comparePaths`, and groups follow
 * `GROUP_ORDER`. Depends only on the set of files, never on their order in the diff.
 *
 * `text` words each group from its rule key and the facts measured about it, so the
 * caller can translate the label and summary.
 */
export function createRuleBasedAdapter(text: (key: RuleKey, facts: RuleGroupFacts) => RuleText): AnalyzeAdapter {
  return {
    id: 'rule-based',
    available: true,
    async analyze(diff) {
      const byPath = new Map(diff.files.map(file => [file.path, file]))
      const byRule = new Map<RuleKey, string[]>()
      for (const file of byPath.values()) {
        const { key } = matchRule(file)
        byRule.set(key, [...(byRule.get(key) ?? []), file.path])
      }

      const sources = byRule.get('code') ?? []
      const pairs = pairTests(sources, byRule.get('tests') ?? [])
      const paired = new Set([...pairs.values()].flat())

      const buckets: Bucket[] = GROUP_ORDER.flatMap((key) => {
        if (key === 'code')
          return codeBuckets(sources, pairs)
        const paths = (byRule.get(key) ?? []).filter(path => !paired.has(path)).sort(comparePaths)
        const rule = defaultRules.find(candidate => candidate.key === key)!
        return paths.length ? [{ key, ruleKey: key, category: rule.category, paths, pairedTests: 0 }] : []
      })

      const groups: DiffGroup[] = buckets.map(bucket => ({
        key: bucket.key,
        ...text(bucket.ruleKey, factsOf(bucket, byPath)),
        category: bucket.category,
        filePaths: bucket.paths,
      }))

      const core: GroupedResultCore = {
        groups,
        schemaVersion: RULE_BASED_SCHEMA_VERSION,
      }
      return normalizeGroupedResult('rule-based', core)
    },
  }
}
