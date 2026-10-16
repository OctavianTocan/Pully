// A ratchet counts violations per kind and per file and compares them with a committed baseline.
// A count may only fall: a rise fails because something new broke the rule, and a fall fails
// until the baseline is lowered, so a gain is locked in instead of silently available to spend.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { stable } from './spec.mjs'

export function readBaseline(path) {
  return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {}
}

/** `hits[kind][file]` is a list of line numbers. Returns the counts that go in the baseline. */
export function countsOf(hits) {
  const counts = {}
  for (const kind of Object.keys(hits).sort()) {
    const files = {}
    for (const file of Object.keys(hits[kind]).sort()) {
      if (hits[kind][file].length > 0)
        files[file] = hits[kind][file].length
    }
    if (Object.keys(files).length > 0)
      counts[kind] = files
  }
  return counts
}

/**
 * Compares `hits` with `baseline`. `kinds` maps a kind to `{ label, rules }` for the messages.
 * Returns problems as strings; none means the code is exactly at its baseline.
 */
export function compare(hits, baseline, kinds, script) {
  const problems = []
  const names = new Set([...Object.keys(hits), ...Object.keys(baseline)])
  for (const kind of [...names].sort()) {
    const info = kinds[kind]
    if (!info) {
      problems.push(`baseline names an unknown kind "${kind}"`)
      continue
    }
    const files = new Set([...Object.keys(hits[kind] ?? {}), ...Object.keys(baseline[kind] ?? {})])
    for (const file of [...files].sort()) {
      const lines = hits[kind]?.[file] ?? []
      const base = baseline[kind]?.[file] ?? 0
      if (lines.length > base) {
        problems.push(`${file}: ${lines.length} ${info.label}, baseline ${base}. Lines ${[...new Set(lines)].join(', ')}. ${info.advice}${info.rules.length > 0 ? ` (rule ${info.rules.join(', ')})` : ''}`)
      }
      else if (lines.length < base) {
        problems.push(`${file}: ${info.label} fell from ${base} to ${lines.length}. Run \`${script}\` to lock the gain in.`)
      }
    }
  }
  return problems
}

export function writeBaseline(path, hits) {
  writeFileSync(path, stable(countsOf(hits)))
}
