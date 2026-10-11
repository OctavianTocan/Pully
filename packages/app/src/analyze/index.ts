import type { RuleGroupFacts, RuleKey, RuleText } from '@pulls.review/core/analyze'
import type { AnalyzeAdapter, GroupSource } from '@pulls.review/core/types'
import { createNoneAdapter, createRuleBasedAdapter } from '@pulls.review/core/analyze'
import { t } from '../i18n'
import { llmAdapter } from './adapters/llm'
import { webLlmAdapter } from './adapters/web-llm'

/**
 * Words a rule-based group: its category label (plus the area for a split code
 * group), and a summary that adds what the group header doesn't already show - how
 * many files are new, deleted or renamed, and how many tests sit right after their source.
 */
export function ruleText(key: RuleKey, facts: RuleGroupFacts): RuleText {
  const base = t(`rules.${key}.label`)
  const area = facts.otherAreas ? t('rules.otherAreas') : facts.area === '' ? t('rules.rootArea') : facts.area
  const label = area === undefined ? base : t('rules.area', { label: base, area })

  const parts = [
    facts.added > 0 && t('rules.facts.added', { n: facts.added }, facts.added),
    facts.removed > 0 && t('rules.facts.removed', { n: facts.removed }, facts.removed),
    facts.renamed > 0 && t('rules.facts.renamed', { n: facts.renamed }, facts.renamed),
    facts.pairedTests > 0 && t('rules.facts.pairedTests', { n: facts.pairedTests }, facts.pairedTests),
  ].filter(part => typeof part === 'string')

  const blurb = t(`rules.${key}.summary`)
  const summary = parts.length > 0
    ? t('rules.facts.sentence', { blurb, list: parts.join(t('rules.facts.separator')) })
    : blurb
  return { label, summary }
}

/** Group text is looked up per run, so it follows the current UI language. */
export const ruleBasedAdapter = createRuleBasedAdapter(ruleText)
export const noneAdapter = createNoneAdapter(() => t('rules.allFiles'))

const registry: Record<GroupSource, AnalyzeAdapter> = {
  'none': noneAdapter,
  'rule-based': ruleBasedAdapter,
  'llm': llmAdapter,
  'web-llm': webLlmAdapter,
}

export function resolveAdapter(id: GroupSource): AnalyzeAdapter {
  return registry[id]
}
