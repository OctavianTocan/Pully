// Small Kotlin readers for the design checks. Not a parser: they blank comments and string
// literals so a regex cannot match inside either, find matching brackets, and pull out the
// composable declarations and the token objects the checks compare with DESIGN.md.
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

export const ANDROID = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
export const REPO = join(ANDROID, '..')
export const KOTLIN_ROOT = join(ANDROID, 'app', 'src', 'main', 'kotlin', 'com', 'octaviantocan', 'pulls')
export const ASSETS = join(ANDROID, 'app', 'src', 'main', 'assets')

/** Every `.kt` file under `dir`, as paths relative to the Android project, sorted. */
export function kotlinFiles(dir = KOTLIN_ROOT) {
  const found = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      found.push(...kotlinFiles(path))
    }
    else if (entry.name.endsWith('.kt')) {
      found.push(relative(ANDROID, path).split('\\').join('/'))
    }
  }
  return found.sort()
}

/** `ui/home/Chrome.kt` from an Android-relative path: the key the baselines use. */
export function shortPath(path) {
  return path.replace(/^app\/src\/main\/kotlin\/com\/octaviantocan\/pulls\//, '')
}

export function readAndroid(path) {
  return readFileSync(join(ANDROID, path), 'utf8')
}

/** The line each offset falls on, for many lookups into one source. */
export function lineIndex(source) {
  const starts = [0]
  for (let i = 0; i < source.length; i++) {
    if (source[i] === '\n') {
      starts.push(i + 1)
    }
  }
  return (offset) => {
    let lo = 0
    let hi = starts.length - 1
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1
      if ((starts[mid] ?? 0) <= offset) {
        lo = mid
      }
      else {
        hi = mid - 1
      }
    }
    return lo + 1
  }
}

/** 1-based line of an offset. */
export function lineOf(source, offset) {
  return lineIndex(source)(offset)
}

const OPEN = { '(': ')', '{': '}', '[': ']' }

/** Offset of the bracket that closes the one at `open` (in blanked source), or -1. */
export function matching(code, open) {
  const stack = []
  for (let i = open; i < code.length; i++) {
    const c = code[i]
    if (OPEN[c]) {
      stack.push(OPEN[c])
    }
    else if (c === ')' || c === '}' || c === ']') {
      if (stack.pop() !== c) {
        return -1
      }
      if (stack.length === 0) {
        return i
      }
    }
  }
  return -1
}

/** `[start, end)` of the body of each function called `name`, in blanked source. */
export function functionBodies(code, name) {
  const ranges = []
  const pattern = new RegExp(`\\bfun\\s+(?:[\\w.<>?]+\\.)?${name}\\s*\\(`, 'g')
  for (const m of code.matchAll(pattern)) {
    const close = matching(code, code.indexOf('(', m.index))
    if (close === -1) {
      continue
    }
    const brace = code.indexOf('{', close)
    const equals = code.indexOf('=', close)
    const block = brace !== -1 && (equals === -1 || brace < equals)
    const end = block ? matching(code, brace) : code.indexOf('\n\n', close)
    ranges.push([m.index, end === -1 ? code.length : end])
  }
  return ranges
}

function lineCommentEnd(source, from) {
  const newline = source.indexOf('\n', from)
  return newline === -1 ? source.length : newline
}

function blockCommentEnd(source, from) {
  let depth = 1
  let i = from + 2
  while (i < source.length && depth > 0) {
    const pair = source.slice(i, i + 2)
    if (pair === '/*') {
      depth++
      i += 2
    }
    else if (pair === '*/') {
      depth--
      i += 2
    }
    else {
      i++
    }
  }
  return i
}

function quoteEnd(source, from, quote) {
  let i = from + 1
  while (i < source.length && source[i] !== quote && source[i] !== '\n') {
    i += source[i] === '\\' ? 2 : 1
  }
  return i
}

/** The comment or literal that starts at `i`: where it ends and which part to wipe, or null. */
function constructAt(source, i) {
  const pair = source.slice(i, i + 2)
  if (pair === '//' || pair === '/*') {
    const end = pair === '//' ? lineCommentEnd(source, i) : blockCommentEnd(source, i)
    return { end, from: i, to: end, string: false }
  }
  if (source.startsWith('"""', i)) {
    const close = source.indexOf('"""', i + 3)
    const end = close === -1 ? source.length : close + 3
    return { end, from: i + 3, to: Math.max(i + 3, end - 3), string: true }
  }
  const c = source[i]
  const isChar = c === '\'' && (source[i + 2] === '\'' || source[i + 1] === '\\')
  if (c === '"' || isChar) {
    const to = quoteEnd(source, i, c)
    return { end: to + 1, from: i + 1, to, string: true }
  }
  return null
}

/**
 * Blanks what regexes must not see, keeping every offset and newline so line numbers still
 * match the source. Comments always go. String and character literal contents go too unless
 * `keepStrings` is set, which is for reading default values.
 */
export function blank(source, { keepStrings = false } = {}) {
  const out = Array.from(source)
  let i = 0
  while (i < source.length) {
    const found = constructAt(source, i)
    if (!found) {
      i++
      continue
    }
    if (!found.string || !keepStrings) {
      for (let k = found.from; k < found.to; k++) {
        out[k] = out[k] === '\n' ? '\n' : ' '
      }
    }
    i = found.end
  }
  return out.join('')
}

/** How much the bracket depth changes at `text[i]`: generics count, the `>` of `->` does not. */
function depthDelta(text, i) {
  const c = text[i]
  if ('({[<'.includes(c)) {
    return 1
  }
  if (')}]'.includes(c) || (c === '>' && text[i - 1] !== '-')) {
    return -1
  }
  return 0
}

/** Splits `text` at commas that sit outside every bracket pair, including generics. */
export function splitTopLevel(text) {
  const parts = []
  let depth = 0
  let start = 0
  for (let i = 0; i < text.length; i++) {
    depth += depthDelta(text, i)
    if (text[i] === ',' && depth === 0) {
      parts.push(text.slice(start, i))
      start = i + 1
    }
  }
  parts.push(text.slice(start))
  return parts.map(p => p.trim()).filter(Boolean)
}

/** Comment lines that excuse a line from a check: `// <tag>: <reason>` on it or right above it. */
export function excusedLines(source, tag) {
  const pattern = new RegExp(`(?://|/\\*)\\s*${tag}:\\s*\\S`)
  const excused = new Set()
  source.split('\n').forEach((line, i) => {
    if (!pattern.test(line)) {
      return
    }
    excused.add(i + 1)
    if (/^\s*(?:\/\/|\/\*)/.test(line)) {
      excused.add(i + 2)
    }
  })
  return excused
}

const COMPOSABLE = /@Composable\b/
const ANNOTATION_LINE = /^\s*@\w[\w.]*(?:\(.*\))?\s*$/
const FUN = /(?:^|\n)([ \t]*)((?:@\w[\w.]*(?:\([^)\n]*\))?[ \t]+)*)((?:(?:public|internal|private|inline|operator|override|suspend)[ \t]+)*)fun[ \t]+(?:<[^>\n]*>[ \t]+)?(?:([\w.<>?]+)\.)?(\w+)[ \t]*\(/g

/** Start of the declaration including annotation lines above it, and whether `@Composable` is one. */
function annotationStart(code, lineStart, funAt) {
  let top = lineStart
  let annotated = COMPOSABLE.test(code.slice(lineStart, funAt))
  const above = code.slice(0, lineStart).split('\n')
  above.pop()
  while (above.length > 0 && ANNOTATION_LINE.test(above.at(-1))) {
    const line = above.pop()
    annotated ||= COMPOSABLE.test(line)
    top -= line.length + 1
  }
  return { top, annotated }
}

/** End of a function whose parameter list closes at `close`: a block, or an expression body. */
function declarationEnd(code, close) {
  let cursor = close + 1
  while (cursor < code.length && /[\s\w:<>?.,]/.test(code[cursor]) && code[cursor] !== '{' && code[cursor] !== '=') {
    cursor++
  }
  if (code[cursor] === '{') {
    return matching(code, cursor) + 1
  }
  // An expression body runs to the next line that starts at the margin.
  const lines = code.slice(cursor).split('\n')
  let offset = cursor + lines[0].length
  for (const line of lines.slice(1)) {
    if (line.trim() !== '' && !/^\s/.test(line) && !/^[)}\]]/.test(line)) {
      return offset
    }
    offset += line.length + 1
  }
  return code.length
}

/** The doc comment right above `top`: a block comment or a run of line comments. */
function docAbove(source, top) {
  const above = source.slice(0, top).trimEnd()
  if (above.endsWith('*/')) {
    const start = above.lastIndexOf('/**')
    if (start === -1 || above.slice(start).includes('\n\n')) {
      return ''
    }
    const body = above.slice(start).replace(/^\/\*\*|\*\/$/g, '')
    return body.split('\n').map(l => l.replace(/^\s*\*? ?/, '')).join('\n').trim()
  }
  const lines = above.split('\n')
  const doc = []
  while (lines.length > 0 && /^\s*\/\/\/?/.test(lines.at(-1))) {
    doc.unshift(lines.pop().replace(/^\s*\/\/\/? ?/, ''))
  }
  return doc.join('\n').trim()
}

/** The text of a declaration with the margin of a nested one removed. */
function dedent(text, indent) {
  return indent ? text.split('\n').map(l => l.replace(new RegExp(`^${indent}`), '')).join('\n') : text
}

function declarationOf(source, code, plain, m, file) {
  const [whole, indent, , modifiers, receiver, name] = m
  const lead = whole.startsWith('\n') ? 1 : 0
  const funAt = m.index + whole.indexOf('fun', lead)
  const open = m.index + whole.length - 1
  const { top, annotated } = annotationStart(code, m.index + lead, funAt)
  const close = matching(code, open)
  if (!annotated || close === -1) {
    return null
  }
  const end = declarationEnd(code, close)
  return {
    name,
    receiver: receiver ?? null,
    top: indent === '',
    private: /\bprivate\b/.test(modifiers),
    internal: /\binternal\b/.test(modifiers),
    file,
    line: lineOf(source, top),
    doc: docAbove(source, top),
    params: parameters(plain.slice(open + 1, close)),
    source: dedent(source.slice(top, end), indent).trimEnd(),
  }
}

/**
 * Every composable function declared in `source`: name, receiver, visibility, doc comment,
 * parameters and the declaration's own text. Private ones are included and flagged.
 */
export function composables(source, file = '') {
  const code = blank(source)
  const plain = blank(source, { keepStrings: true })
  return [...code.matchAll(FUN)].map(m => declarationOf(source, code, plain, m, file)).filter(Boolean)
}

/** Offset of the `=` that starts a default value in `rest`, outside every bracket, or -1. */
function defaultAt(rest) {
  let depth = 0
  for (let i = 0; i < rest.length; i++) {
    depth += depthDelta(rest, i)
    if (rest[i] === '=' && depth === 0 && rest[i - 1] !== '=' && rest[i + 1] !== '=') {
      return i
    }
  }
  return -1
}

/** `name: Type = default` entries from a parameter list (comments already blanked). */
function parameters(list) {
  return splitTopLevel(list).map((part) => {
    const text = part.replace(/^\s*(?:vararg|noinline|crossinline)\s+/, '').replace(/\s+/g, ' ').trim()
    const colon = text.indexOf(':')
    const rest = text.slice(colon + 1).trim()
    const eq = defaultAt(rest)
    const entry = { name: text.slice(0, colon).trim(), type: (eq === -1 ? rest : rest.slice(0, eq)).trim() }
    if (eq !== -1) {
      entry.default = rest.slice(eq + 1).trim()
    }
    return entry
  }).filter(p => p.name)
}

export function kebab(name) {
  return name.replace(/([a-z])([A-Z])/g, '$1-$2').replace(/([a-z])(\d)/g, '$1-$2').toLowerCase()
}

/** Offsets of the body of `object <name> { ... }` in blanked source, or null. */
function objectBody(code, name) {
  const at = code.indexOf(`object ${name}`)
  if (at === -1) {
    return null
  }
  const open = code.indexOf('{', at)
  return code.slice(open + 1, matching(code, open))
}

/** `Color(0xAARRGGBB)` properties of one `PullsColors(...)` value, as CSS `#RRGGBB[AA]`. */
export function paletteColors(source, valueName) {
  const code = blank(source)
  const at = code.indexOf(`val ${valueName} = PullsColors(`)
  if (at === -1) {
    throw new Error(`Tokens.kt has no ${valueName}`)
  }
  const open = code.indexOf('(', at)
  const body = code.slice(open + 1, matching(code, open))
  const colors = {}
  for (const [, name, argb] of body.matchAll(/\b(\w+)\s*=\s*Color\(0x([0-9a-f]{8})\)/gi)) {
    const alpha = argb.slice(0, 2).toUpperCase()
    colors[kebab(name)] = `#${argb.slice(2).toUpperCase()}${alpha === 'FF' ? '' : alpha}`
  }
  return colors
}

const WEIGHTS = { Normal: 400, Medium: 500, SemiBold: 600, Bold: 700 }
const FAMILIES = { sans: 'Geist', mono: 'Geist Mono', display: 'Mona Sans' }

/** `key = value` pairs of a call's arguments. */
function namedArguments(args) {
  const named = {}
  for (const part of splitTopLevel(args)) {
    const eq = part.indexOf('=')
    if (eq > 0) {
      named[part.slice(0, eq).trim()] = part.slice(eq + 1).trim()
    }
  }
  return named
}

function readStyle(args) {
  const named = namedArguments(args)
  const family = /PullsFonts\.(\w+)/.exec(named.fontFamily ?? '')?.[1] ?? 'sans'
  const weight = /FontWeight\.(\w+)/.exec(named.fontWeight ?? '')?.[1] ?? 'Normal'
  const style = {
    fontFamily: FAMILIES[family] ?? family,
    fontSize: `${Number.parseFloat(named.fontSize)}px`,
    fontWeight: WEIGHTS[weight] ?? weight,
    lineHeight: `${Number.parseFloat(named.lineHeight)}px`,
  }
  const spacing = /\(?\s*(-?[\d.]+)\)?\.sp/.exec(named.letterSpacing ?? '')?.[1]
  if (spacing !== undefined) {
    style.letterSpacing = `${Number.parseFloat(spacing)}px`
  }
  return style
}

/** Every `TextStyle(...)` call in `expr`, read. */
function textStyles(expr) {
  const styles = []
  let from = 0
  for (;;) {
    const start = expr.indexOf('TextStyle(', from)
    if (start === -1) {
      return styles
    }
    const open = start + 'TextStyle'.length
    const close = matching(expr, open)
    styles.push(readStyle(expr.slice(open + 1, close)))
    from = close + 1
  }
}

/**
 * The roles of `PullsType`. A role written `if (wide) A else B` gives two entries, `<name>`
 * for B (the phone) and `<name>-wide` for A. Only `TextStyle(...)` values count.
 */
export function typeRoles(source) {
  const body = objectBody(blank(source), 'PullsType') ?? ''
  const roles = {}
  for (const [, name, expr] of body.matchAll(/\bval\s+(\w+)\s*(?::\s*\w+\s*)?(?:get\(\)\s*)?=\s*(\S[^\n]*)/g)) {
    const styles = textStyles(expr)
    if (styles.length === 2) {
      roles[`${name}-wide`] = styles[0]
      roles[name] = styles[1]
    }
    else if (styles.length === 1) {
      roles[name] = styles[0]
    }
  }
  return roles
}

/** `val name = 4.dp` entries of `object <objectName>`, as `{ name: '4px' }`. */
export function dpScale(source, objectName) {
  const body = objectBody(blank(source), objectName)
  if (body === null) {
    return null
  }
  const scale = {}
  for (const m of body.matchAll(/\bval\s+(\w+)\s*(?::\s*Dp\s*)?=\s*([\d.]+)\.dp/g)) {
    scale[m[1]] = `${Number.parseFloat(m[2])}px`
  }
  return scale
}
