// Ratchet on raw values: colours, font sizes and radii written in a screen instead of read from
// a token, text under 15, uppercase and tracked text, and shadows that are not `floatShadow`.
// The Kotlin is behind DESIGN.md today, so the counts are frozen in a baseline that only falls.
import { join } from 'node:path'
import { ANDROID, blank, excusedLines, functionBodies, kotlinFiles, lineIndex, matching, readAndroid, shortPath } from './kotlin.mjs'
import { compare, readBaseline, writeBaseline } from './ratchet.mjs'
import { readDesign } from './spec.mjs'

export const NAME = 'raw-values'
export const RULES = [14, 45, 47]
export const BASELINE = join(ANDROID, 'scripts', 'design', 'raw-values.baseline.json')
const UPDATE = 'pnpm run android:design:update'

/** Files that define the tokens: raw values are their job. */
const DEFINES_TOKENS = new Set(['ui/design/Tokens.kt', 'ui/design/Scale.kt'])

/** Functions that are allowed to call `.shadow(` because every other shadow goes through them. */
const SHADOW_HELPERS = ['floatShadow']

export const KINDS = {
  hex: { label: 'raw colours', rules: [47], advice: 'Read a colour from LocalPullsColors, or add a token to DESIGN.md and Tokens.kt.' },
  fontSize: { label: 'raw font sizes', rules: [47], advice: 'Use a PullsType role.' },
  radius: { label: 'raw corner radii', rules: [47], advice: 'Use PullsRadius.' },
  smallText: { label: 'text set below 15', rules: [14], advice: 'Body-size ink or nothing.' },
  uppercase: { label: 'uppercase or tracked text', rules: [14], advice: 'A section label is one word in sentence case.' },
  shadow: { label: 'raw shadows', rules: [45], advice: 'Use floatShadow, the canvas shadow.' },
}

const NAMED_COLOR = /\bColor\.(?:White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b/g
const HEX_COLOR = /\bColor\(\s*0x[0-9a-f]+/gi
const FONT_SIZE = /\bfontSize\s*=\s*(-?\d+(?:\.\d+)?)\.sp\b/g
const TRACKING = /\bletterSpacing\s*=\s*(?:\(\s*)?(\d[\d.]*)(?:\.sp|\.em|f)/g
const UPPERCASE = /\.(?:uppercase|toUpperCase)\(/g
const SHADOW = /\.(?:shadow|dropShadow)\(/g
const LITERAL_RADIUS = /\d(?:\.\d+)?\.dp|^\s*\d+\s*$|\bpercent\s*=/

/** The `PullsType` roles set below 15 on a phone, from the front matter. */
export function smallRoles(front) {
  return Object.entries(front.typography ?? {})
    .filter(([name, t]) => !name.endsWith('-wide') && Number.parseFloat(t.fontSize) < 15)
    .map(([name]) => name)
}

const offsets = (code, pattern) => [...code.matchAll(pattern)].map(m => m.index)

/** Constructors given a literal size or a percent instead of a radius token. */
function radiusOffsets(code, source) {
  return [...code.matchAll(/\b(?:RoundedCornerShape|CornerRadius)\(/g)].filter((m) => {
    const open = m.index + m[0].length - 1
    const close = matching(code, open)
    return close !== -1 && LITERAL_RADIUS.test(source.slice(open + 1, close))
  }).map(m => m.index)
}

function shadowOffsets(code) {
  const helpers = SHADOW_HELPERS.flatMap(name => functionBodies(code, name))
  return offsets(code, SHADOW).filter(at => !helpers.some(([from, to]) => at >= from && at < to))
}

function trackedOffsets(code) {
  return [...code.matchAll(TRACKING)].filter(m => Number.parseFloat(m[1]) > 0).map(m => m.index)
}

/** Offsets of every raw value in one file, by kind. */
function offsetsByKind(code, source, small) {
  const sizes = [...code.matchAll(FONT_SIZE)]
  return {
    hex: [...offsets(code, HEX_COLOR), ...offsets(code, NAMED_COLOR)],
    fontSize: sizes.map(m => m.index),
    radius: radiusOffsets(code, source),
    smallText: [...sizes.filter(m => Number.parseFloat(m[1]) < 15).map(m => m.index), ...offsets(code, small)],
    uppercase: [...offsets(code, UPPERCASE), ...trackedOffsets(code)],
    shadow: shadowOffsets(code),
  }
}

/**
 * `hits[kind][file]`: the line numbers in `sources` (a map of short path to text) that break a
 * rule and are not excused with `// raw-value-ok: <reason>`.
 */
export function scan(sources, front) {
  const hits = Object.fromEntries(Object.keys(KINDS).map(kind => [kind, {}]))
  const small = new RegExp(`\\bPullsType\\.(?:${smallRoles(front).join('|')})\\b`, 'g')
  for (const [file, source] of Object.entries(sources).filter(([name]) => !DEFINES_TOKENS.has(name))) {
    const code = blank(source)
    const lineAt = lineIndex(code)
    const excused = excusedLines(source, 'raw-value-ok')
    for (const [kind, found] of Object.entries(offsetsByKind(code, source, small))) {
      const lines = found.map(lineAt).filter(line => !excused.has(line)).sort((x, y) => x - y)
      if (lines.length > 0) {
        hits[kind][file] = lines
      }
    }
  }
  return hits
}

export function readSources() {
  return Object.fromEntries(kotlinFiles().map(path => [shortPath(path), readAndroid(path)]))
}

export function check({ update = false } = {}) {
  const hits = scan(readSources(), readDesign().front)
  if (update) {
    writeBaseline(BASELINE, hits)
    return { name: NAME, problems: [], written: [BASELINE] }
  }
  return { name: NAME, problems: compare(hits, readBaseline(BASELINE), KINDS, UPDATE), written: [] }
}
