// Keeps the numbered rules in DESIGN.md honest in both directions: a rule may only name a check
// that exists, and a check may only claim a rule that names it back. `review` is a person.
import * as bans from './bans.mjs'
import * as book from './book.mjs'
import * as rawValues from './raw-values.mjs'
import { CHECKS, readDesign } from './spec.mjs'
import * as tokens from './tokens.mjs'

export const NAME = 'rules'

const SOURCES = ['canvas', 'walkthrough', 'perplexity', 'answers', 'overhaul']

/** What each check says it enforces. */
export const DECLARED = {
  'tokens': tokens.RULES,
  'raw-values': rawValues.RULES,
  'bans': bans.RULES,
  'book': book.RULES,
}

/** Rule numbers run 1, 2, 3 in order, and each has a source and checks that exist. */
function ruleProblems(rules) {
  const problems = []
  const numbers = new Set()
  rules.forEach((rule, i) => {
    if (rule.n !== i + 1) {
      problems.push(`DESIGN.md: rule ${rule.n} is in position ${i + 1}. Rules are one contiguous list from 1`)
    }
    if (numbers.has(rule.n)) {
      problems.push(`DESIGN.md: rule ${rule.n} is listed twice`)
    }
    numbers.add(rule.n)
    if (!SOURCES.includes(rule.source)) {
      problems.push(`DESIGN.md: rule ${rule.n} needs "Source: ${SOURCES.slice(0, -1).join(', ')} or ${SOURCES.at(-1)}."`)
    }
    if (rule.checkedBy.length === 0) {
      problems.push(`DESIGN.md: rule ${rule.n} needs a "Checked by:" line, \`review\` at least`)
    }
    for (const name of rule.checkedBy.filter(n => !CHECKS.includes(n))) {
      problems.push(`DESIGN.md: rule ${rule.n} is checked by "${name}", which is not one of ${CHECKS.join(', ')}`)
    }
  })
  return problems
}

/** What a check claims against what the rules say, both ways. */
function claimProblems(rules, name, claimed) {
  const problems = []
  for (const n of claimed) {
    const rule = rules.find(r => r.n === n)
    if (!rule) {
      problems.push(`${name} enforces rule ${n}, which DESIGN.md does not have`)
    }
    else if (!rule.checkedBy.includes(name)) {
      problems.push(`${name} enforces rule ${n}, but DESIGN.md rule ${n} is checked by ${rule.checkedBy.join(', ') || 'nothing'}. Add ${name} to its "Checked by"`)
    }
  }
  for (const rule of rules.filter(r => r.checkedBy.includes(name) && !claimed.includes(r.n))) {
    problems.push(`DESIGN.md rule ${rule.n} says ${name} checks it, but ${name} does not enforce it`)
  }
  return problems
}

/** "rule 47" and "rules 14 and 45" in prose point at rules that exist. */
function referenceProblems(rules, body) {
  const prose = body.replace(/^#{1,6} .*$/gm, '')
  const numbers = [...prose.matchAll(/\brules?\s+(\d+)(?:\s*(?:to|and|,)\s*(\d+))?/gi)].flatMap(m => [m[1], m[2]].filter(Boolean).map(Number))
  return numbers.filter(n => n < 1 || n > rules.length).map(n => `DESIGN.md mentions rule ${n}, but the rules run 1 to ${rules.length}`)
}

/** Problems in the rule list of a parsed DESIGN.md. `declared` maps a check to its rule numbers. */
export function checkRules({ rules, body }, declared = DECLARED) {
  return [...new Set([
    ...ruleProblems(rules),
    ...Object.entries(declared).flatMap(([name, claimed]) => claimProblems(rules, name, claimed)),
    ...referenceProblems(rules, body),
  ])]
}

/** Checklist items under `## Checklist` are one contiguous numbered list. */
export function checkChecklist(body) {
  const start = body.indexOf('\n## Checklist')
  if (start === -1) {
    return ['DESIGN.md has no ## Checklist']
  }
  const rest = body.slice(start + 1)
  const end = rest.indexOf('\n## ', 3)
  const items = [...(end === -1 ? rest : rest.slice(0, end)).matchAll(/^(\d+)\. /gm)].map(m => Number(m[1]))
  if (items.length === 0) {
    return ['DESIGN.md: the checklist has no numbered items']
  }
  return items.map((n, i) => (n === i + 1 ? null : `DESIGN.md: checklist item ${n} is in position ${i + 1}`)).filter(Boolean)
}

export function check() {
  const design = readDesign()
  return { name: NAME, problems: [...checkRules(design), ...checkChecklist(design.body)], written: [] }
}
