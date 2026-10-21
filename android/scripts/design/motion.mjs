// Motion goes the way the person went (rule 105). The kit in ui/design/ is the one place that decides which way a
// page, tab, file or label moves: BlurSwitch, LabelSwitch and rememberPageSlide take an `order` and work the way out
// from it, and PageBack moves a page with a back swipe. So a screen may not slide anything itself, every one of those
// calls names its order, and Back goes through PageBack. A hard check with no baseline: excuse one line with
// `// motion-ok: <reason>`.
import { blank, composables, excusedLines, isDesignSource, kotlinFiles, lineIndex, matching, readAndroid, shortPath, splitTopLevel } from './kotlin.mjs'

export const NAME = 'motion'
export const RULES = [105]

const KIT = 'ui/design/'
const TAG = 'motion-ok'

/** Compose's own slides and pagers, which move a fixed way unless whoever calls them works the way out. */
const SLIDES = /\b(?:slideInHorizontally|slideOutHorizontally|slideInVertically|slideOutVertically|slideIn|slideOut|slideIntoContainer|slideOutOfContainer|HorizontalPager|VerticalPager|SlideDirection)\b/g

/** The kit's slides themselves, which must take their offset from the way. */
const KIT_SLIDE = /\b(?:slideInHorizontally|slideOutHorizontally|slideInVertically|slideOutVertically|slideIntoContainer|slideOutOfContainer)\s*\(/g

/** The kit's page changes: each is told how the pages rank, and finds the way from that. */
export const ORDERED = ['BlurSwitch', 'LabelSwitch', 'rememberPageSlide']

const ORDERED_CALL = new RegExp(`(?<!fun\\s)(?<!fun\\s<\\w+>\\s)(?<![\\w.])(?:${ORDERED.join('|')})\\s*\\(`, 'g')

const BACK = /\b(?:BackHandler|PredictiveBackHandler)\b/g

const IMPORT_LINE = /^import .*$/gm

const withoutImports = code => code.replace(IMPORT_LINE, line => ' '.repeat(line.length))

/** `[start, end)` of a call whose name starts at `at`: its arguments and a trailing lambda, if it has one. */
function callExtent(code, at) {
  const close = matching(code, code.indexOf('(', at))
  if (close === -1) {
    return null
  }
  let next = close + 1
  while (/[ \t]/.test(code[next] ?? '')) {
    next++
  }
  const end = code[next] === '{' ? matching(code, next) : close
  return { close, end: end === -1 ? close : end }
}

/** Outside the kit: Compose's own slides and pagers, and Back that moves nothing. */
function screenHits(code) {
  return [
    ...[...code.matchAll(SLIDES)].map(m => ({ at: m.index, message: `${m[0]} picks its own direction. Use BlurSwitch, LabelSwitch or rememberPageSlide with an order, so the change moves the way the person went` })),
    ...[...code.matchAll(BACK)].map(m => ({ at: m.index, message: `${m[0]} pops without moving anything. Use PageBack, so a back swipe moves the page with the finger; a Back that moves nothing says why with // ${TAG}` })),
  ]
}

/** Inside the kit: a slide whose offset does not use the way. */
function kitHits(code) {
  return [...code.matchAll(KIT_SLIDE)].filter((m) => {
    const extent = callExtent(code, m.index)
    return extent && !/\bway\b/.test(code.slice(m.index, extent.end + 1))
  }).map(m => ({ at: m.index, message: `${m[0].replace(/\s*\($/, '')} in the kit has an offset that does not use the way. Work it out with pageWay or a PageMotion` }))
}

/** Anywhere: a page change that does not name its order. */
function orderHits(code) {
  return [...code.matchAll(ORDERED_CALL)].filter((m) => {
    const extent = callExtent(code, m.index)
    return extent && !splitTopLevel(code.slice(code.indexOf('(', m.index) + 1, extent.close)).some(arg => /^order\s*=/.test(arg))
  }).map(m => ({ at: m.index, message: `${m[0].replace(/\s*\($/, '')} needs \`order =\`, how its pages rank left to right, so the next one comes from the right` }))
}

/** Problems in one file, as `file:line: message (rule 105)`, less the lines excused with `// motion-ok:`. */
function fileProblems(file, source) {
  const code = withoutImports(blank(source))
  const lineAt = lineIndex(code)
  const excused = excusedLines(source, TAG)
  const hits = [...(isDesignSource(file) ? kitHits(code) : screenHits(code)), ...orderHits(code)]
  return hits.map(hit => ({ line: lineAt(hit.at), message: hit.message }))
    .filter(hit => !excused.has(hit.line))
    .map(hit => `${file}:${hit.line}: ${hit.message} (rule 105)`)
}

/** The kit's page changes must keep `order` without a default, so a caller cannot leave the way to chance. */
export function declarationProblems(sources) {
  const problems = []
  const found = new Set()
  for (const [file, source] of Object.entries(sources).filter(([f]) => isDesignSource(f))) {
    for (const fn of composables(source, file).filter(f => ORDERED.includes(f.name) && !f.private)) {
      found.add(fn.name)
      const order = fn.params.find(p => p.name === 'order')
      if (!order) {
        problems.push(`${file}:${fn.line}: ${fn.name} takes no \`order\`. It needs one to know which way its pages go (rule 105)`)
      }
      else if (order.default !== undefined) {
        problems.push(`${file}:${fn.line}: ${fn.name}'s \`order\` has a default. Leave it required, so every caller says how its pages rank (rule 105)`)
      }
    }
  }
  return { problems, found }
}

/** Every motion problem in `sources` (short path to text). */
export function scan(sources) {
  return [
    ...Object.entries(sources).flatMap(([file, source]) => fileProblems(file, source)),
    ...declarationProblems(sources).problems,
  ]
}

export function readSources() {
  return Object.fromEntries(kotlinFiles().map(path => [shortPath(path), readAndroid(path)]))
}

export function check() {
  const sources = readSources()
  const { found } = declarationProblems(sources)
  const gone = ORDERED.filter(name => !found.has(name)).map(name => `${KIT}: ${name} is not declared any more. Update ORDERED in scripts/design/motion.mjs`)
  return { name: NAME, problems: [...scan(sources), ...gone], written: [] }
}
