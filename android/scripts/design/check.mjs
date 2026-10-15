// Runs the design checks in order. `--update` rewrites the generated files and the baselines
// first, then runs the checks, so a clean run after an update proves the update is consistent.
// Exit code 1 if any check has a problem.
import { relative } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import * as bans from './bans.mjs'
import * as book from './book.mjs'
import * as changelog from './changelog.mjs'
import { REPO } from './kotlin.mjs'
import * as motion from './motion.mjs'
import * as rawValues from './raw-values.mjs'
import * as rules from './rules.mjs'
import * as tokens from './tokens.mjs'
import * as web from './web.mjs'

/** In the order they run. */
export const CHECKS = [tokens, rawValues, bans, book, motion, web, rules, changelog]

export function run({ update = false } = {}) {
  const written = update ? CHECKS.flatMap(c => c.check({ update: true }).written) : []
  return { written, results: CHECKS.map(c => c.check({ update: false })) }
}

const short = path => relative(REPO, path).split('\\').join('/')

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { written, results } = run({ update: process.argv.includes('--update') })
  for (const path of written)
    console.log(`wrote ${short(path)}`)
  let failed = false
  for (const result of results) {
    if (result.problems.length === 0) {
      console.log(`${result.name} ok`)
      continue
    }
    failed = true
    console.log(`${result.name}: ${result.problems.length} problem${result.problems.length === 1 ? '' : 's'}`)
    for (const problem of result.problems)
      console.log(`  ${problem}`)
  }
  if (failed)
    process.exit(1)
}
