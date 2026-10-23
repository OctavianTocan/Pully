// Coverage of the UI Book, the way the web book tests its own routes, components and specs.
// `book.json` is the one typed list of pages and components. The Kotlin in `ui/book` is read, not
// run: its page routes, preview registry and spec specimens are regex-readable on purpose.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { ANDROID, ASSETS, blank, composables, isDesignSource, kotlinFiles, matching, readAndroid, shortPath } from './kotlin.mjs'
import { readDesign, stable } from './spec.mjs'

export const NAME = 'book'
export const RULES = [44, 45, 47]
export const BOOK_JSON = join(ASSETS, 'book', 'book.json')
export const SOURCES_JSON = join(ASSETS, 'book', 'sources.json')
export const EXEMPT_JSON = join(ANDROID, 'scripts', 'design', 'book.exempt.json')
const BOOK_DIR = 'ui/book'
const DESIGN_DIR = 'ui/design'

const ID = /^[a-z][a-z0-9-]*$/
const PAGE_ID = /^[a-z][a-z0-9-]*(?:\/[a-z][a-z0-9-]*)*$/

function readJson(path, fallback) {
  return existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : fallback
}

const short = path => path.slice(path.indexOf('android/'))

/** Public top-level composables in `ui/design`, by name, each with its declarations (overloads). */
export function designComposables(sources) {
  const found = {}
  for (const [file, source] of Object.entries(sources)) {
    if (!isDesignSource(file)) {
      continue
    }
    for (const c of composables(source, file).filter(d => !d.private && d.top)) {
      found[c.name] = [...(found[c.name] ?? []), c]
    }
  }
  return found
}

/** The `"id" -> expression` arms of a file. */
export function arms(source) {
  const code = blank(source, { keepStrings: true })
  return [...code.matchAll(/^[ \t]*"([\w/-]+)"[ \t]*->[ \t]*([^\n]*)/gm)].map(m => ({ id: m[1], rest: m[2].trim() }))
}

/** The body of `fun <name>(...)` in `source`, strings kept, or null. */
export function functionBody(source, name) {
  const code = blank(source, { keepStrings: true })
  const m = new RegExp(`\\bfun\\s+${name}\\s*\\(`).exec(code)
  const close = m ? matching(code, code.indexOf('(', m.index)) : -1
  if (close === -1) {
    return null
  }
  const brace = code.indexOf('{', close)
  const equals = code.indexOf('=', close)
  if (equals !== -1 && (brace === -1 || equals < brace)) {
    const stop = code.indexOf('\n\n', equals)
    return code.slice(equals, stop === -1 ? code.length : stop)
  }
  return code.slice(brace, matching(code, brace) + 1)
}

function pageProblems(page, components, seen) {
  const problems = []
  if (!PAGE_ID.test(page.id ?? '')) {
    problems.push(`book.json: page id "${page.id}" is not lower-case words joined by - and /`)
  }
  if (seen.has(page.id)) {
    problems.push(`book.json: page "${page.id}" is listed twice`)
  }
  seen.add(page.id)
  for (const key of ['title', 'group']) {
    if (!page[key]) {
      problems.push(`book.json: page "${page.id}" has no ${key}`)
    }
  }
  if (page.component && !components[page.component]) {
    problems.push(`book.json: page "${page.id}" shows component "${page.component}", which is not in components`)
  }
  return problems
}

function variantProblems(id, variants) {
  const problems = []
  const defaults = variants.filter(v => v.default)
  if (variants.length === 0) {
    problems.push(`book.json: component "${id}" has no variants`)
  }
  else if (defaults.length !== 1) {
    problems.push(`book.json: component "${id}" has ${defaults.length} default variants, wants exactly 1`)
  }
  const ids = new Set()
  for (const v of variants) {
    if (!ID.test(v.id ?? '')) {
      problems.push(`book.json: component "${id}" has a variant id "${v.id}" that is not lower-case words joined by -`)
    }
    if (ids.has(v.id)) {
      problems.push(`book.json: component "${id}" lists variant "${v.id}" twice`)
    }
    ids.add(v.id)
    if (!v.title) {
      problems.push(`book.json: variant "${id}/${v.id}" has no title`)
    }
  }
  return problems
}

function componentProblems(id, c, pages, ruleNumbers) {
  const problems = []
  if (!ID.test(id)) {
    problems.push(`book.json: component id "${id}" is not lower-case words joined by -`)
  }
  if (!pages.some(p => p.component === id)) {
    problems.push(`book.json: component "${id}" has no page`)
  }
  for (const key of ['composable', 'summary']) {
    if (!c[key]) {
      problems.push(`book.json: component "${id}" has no ${key}`)
    }
  }
  if (!Array.isArray(c.usage) || c.usage.length === 0) {
    problems.push(`book.json: component "${id}" has no usage notes`)
  }
  for (const n of (c.rules ?? []).filter(rule => !ruleNumbers.has(rule))) {
    problems.push(`book.json: component "${id}" cites rule ${n}, which DESIGN.md does not have`)
  }
  return [...problems, ...variantProblems(id, c.variants ?? [])]
}

/** `book.json` against itself: ids, one default variant per component, rule numbers that exist. */
export function checkBookShape(book, ruleNumbers) {
  const pages = book.pages ?? []
  const components = book.components ?? {}
  const seen = new Set()
  return [
    ...pages.flatMap(page => pageProblems(page, components, seen)),
    ...Object.entries(components).flatMap(([id, c]) => componentProblems(id, c, pages, ruleNumbers)),
  ]
}

/** Every page without a component has a route arm in `BookRoutes.kt`, and every arm has a page. */
function routeProblems(pages, routes) {
  if (routes === undefined) {
    return [`${BOOK_DIR}/BookRoutes.kt is missing`]
  }
  const routed = new Set(arms(routes).map(a => a.id))
  const missing = pages.filter(p => !p.component && !routed.has(p.id))
  const extra = [...routed].filter((id) => {
    const page = pages.find(p => p.id === id)
    return !page || page.component
  })
  return [
    ...missing.map(p => `${BOOK_DIR}/BookRoutes.kt has no route for page "${p.id}"`),
    ...extra.map(id => `${BOOK_DIR}/BookRoutes.kt routes "${id}", which book.json does not list as a page of its own`),
  ]
}

/** One component's preview arm: it calls a function in the file, and its variant ids are the listed ones. */
function previewProblems(id, c, previews, registry) {
  const arm = registry.find(a => a.id === id)
  if (!arm) {
    return [`${BOOK_DIR}/BookPreviews.kt has no preview for component "${id}"`]
  }
  const fn = /^(\w+)\(\)/.exec(arm.rest)?.[1]
  const body = fn ? functionBody(previews, fn) : null
  if (!body) {
    return [`${BOOK_DIR}/BookPreviews.kt: the preview for "${id}" must call a function declared in the file, as \`"${id}" -> name()\``]
  }
  const coded = [...body.matchAll(/\bBookVariant\(\s*"([\w-]+)"/g)].map(m => m[1])
  const listed = (c.variants ?? []).map(v => v.id)
  return [
    ...listed.filter(v => !coded.includes(v)).map(v => `${BOOK_DIR}/BookPreviews.kt: ${fn}() has no BookVariant("${v}") for "${id}"`),
    ...coded.filter(v => !listed.includes(v)).map(v => `${BOOK_DIR}/BookPreviews.kt: ${fn}() has BookVariant("${v}") that book.json does not list for "${id}"`),
  ]
}

function registryProblems(components, previews) {
  if (previews === undefined) {
    return [`${BOOK_DIR}/BookPreviews.kt is missing`]
  }
  const registry = arms(previews)
  return [
    ...Object.entries(components).flatMap(([id, c]) => previewProblems(id, c, previews, registry)),
    ...registry.filter(a => !components[a.id]).map(a => `${BOOK_DIR}/BookPreviews.kt previews "${a.id}", which is not a component in book.json`),
  ]
}

/** Every glyph in `object Lu` is drawn on the Icons page, under its own name. */
function iconProblems(icons, foundations) {
  if (icons === undefined || foundations === undefined) {
    return []
  }
  const code = blank(icons)
  const open = code.indexOf('{', code.indexOf('object Lu'))
  const body = open === -1 ? '' : code.slice(open, matching(code, open))
  const glyphs = [...body.matchAll(/\bval\s+(\w+)\s*=\s*LuGlyph\(/g)].map(m => m[1])
  const listed = [...foundations.matchAll(/"(\w+)"\s+to\s+Lu\.(\w+)/g)]
  return [
    ...glyphs.filter(name => !listed.some(m => m[2] === name)).map(name => `${BOOK_DIR}/BookFoundations.kt: the Icons page does not list Lu.${name}`),
    ...listed.filter(([, label, name]) => label !== name).map(([, label, name]) => `${BOOK_DIR}/BookFoundations.kt: the Icons page labels Lu.${name} as "${label}"`),
    ...listed.filter(([, , name]) => !glyphs.includes(name)).map(([, , name]) => `${BOOK_DIR}/BookFoundations.kt: the Icons page lists Lu.${name}, which Icons.kt does not have`),
  ]
}

/** Reasons in `book.exempt.json` that are empty, or for a composable that is gone or has a page. */
function exemptionProblems(exempt, declared, named) {
  const problems = []
  for (const [name, reason] of Object.entries(exempt.composables ?? {})) {
    if (!reason) {
      problems.push(`book.exempt.json: ${name} is exempt with no reason`)
    }
    if (!declared[name]) {
      problems.push(`book.exempt.json: ${name} is not a public composable in ${DESIGN_DIR} any more. Remove it`)
    }
    else if (named.has(name)) {
      problems.push(`book.exempt.json: ${name} has a UI Book page. Remove the exemption`)
    }
  }
  return problems
}

/** Every named composable exists, and every public one in `ui/design` has a page or a reason. */
function composableProblems(components, declared, exempt) {
  const named = new Set()
  const problems = []
  for (const [id, c] of Object.entries(components)) {
    for (const name of [c.composable, ...(c.also ?? [])].filter(Boolean)) {
      named.add(name)
      if (!declared[name]) {
        problems.push(`book.json: component "${id}" names ${name}, which is not a public composable in ${DESIGN_DIR}`)
      }
    }
  }
  for (const name of Object.keys(declared).sort().filter(n => !named.has(n) && !exempt.composables?.[n])) {
    const [first] = declared[name]
    problems.push(`${first.file}:${first.line}: ${name} has no UI Book page. Add it to book.json or give a reason in book.exempt.json`)
  }
  return [...problems, ...exemptionProblems(exempt, declared, named)]
}

/** Every sized component token is measured by a specimen, or exempt with a reason. */
function specimenProblems(front, specs, exempt) {
  if (specs === undefined) {
    return [`${BOOK_DIR}/BookSpecs.kt is missing`]
  }
  const tokens = Object.entries(front.components ?? {})
  const sized = tokens.filter(([, c]) => ['size', 'width', 'height'].some(k => c[k] !== undefined)).map(([name]) => name)
  const specimens = [...blank(specs, { keepStrings: true }).matchAll(/\bSpecimen\(\s*"([\w-]+)"/g)].map(m => m[1])
  const problems = []
  for (const name of sized.filter(n => !specimens.includes(n) && !exempt.components?.[n])) {
    problems.push(`DESIGN.md components.${name} has a size and no Specimen in ${BOOK_DIR}/BookSpecs.kt. Add one or give a reason in book.exempt.json (rule 47)`)
  }
  for (const id of specimens.filter(n => !front.components?.[n])) {
    problems.push(`${BOOK_DIR}/BookSpecs.kt measures "${id}", which is not in DESIGN.md components`)
  }
  for (const [name, reason] of Object.entries(exempt.components ?? {})) {
    if (!reason) {
      problems.push(`book.exempt.json: component ${name} is exempt with no reason`)
    }
    if (!sized.includes(name)) {
      problems.push(`book.exempt.json: component ${name} is not a sized DESIGN.md component. Remove it`)
    }
  }
  return problems
}

/** Page routes, preview registry, icons, composables and specimens in the Kotlin, against `book.json`. */
export function checkBookKotlin(book, sources, front, exempt) {
  const components = book.components ?? {}
  const file = name => sources[`${BOOK_DIR}/${name}`]
  return [
    ...routeProblems(book.pages ?? [], file('BookRoutes.kt')),
    ...registryProblems(components, file('BookPreviews.kt')),
    ...iconProblems(sources[`${DESIGN_DIR}/Icons.kt`], file('BookFoundations.kt')),
    ...composableProblems(components, designComposables(sources), exempt),
    ...specimenProblems(front, file('BookSpecs.kt'), exempt),
  ]
}

/** What the Book shows as the props and the source of each component. */
export function buildSources(book, sources) {
  const declared = designComposables(sources)
  const out = {}
  for (const [id, c] of Object.entries(book.components ?? {}).sort(([a], [b]) => a.localeCompare(b))) {
    const names = [c.composable, ...(c.also ?? [])]
    out[id] = {
      composable: c.composable,
      declarations: names.flatMap(name => declared[name] ?? []).map((d) => {
        const entry = { file: d.file, line: d.line, doc: d.doc }
        // JSON null would read back as the text "null" through Android's org.json, so leave it out.
        if (d.receiver) {
          entry.receiver = d.receiver
        }
        return { ...entry, params: d.params, source: d.source }
      }),
    }
  }
  return out
}

export function readSources() {
  return Object.fromEntries(kotlinFiles().map(path => [shortPath(path), readAndroid(path)]))
}

export function check({ update = false } = {}) {
  if (!existsSync(BOOK_JSON)) {
    return { name: NAME, problems: [`${short(BOOK_JSON)} is missing`], written: [] }
  }
  const { front, rules } = readDesign()
  const book = readJson(BOOK_JSON, {})
  const sources = readSources()
  const problems = [
    ...checkBookShape(book, new Set(rules.map(r => r.n))),
    ...checkBookKotlin(book, sources, front, readJson(EXEMPT_JSON, {})),
  ]
  const wanted = stable(buildSources(book, sources))
  const written = []
  if (!existsSync(SOURCES_JSON) || readFileSync(SOURCES_JSON, 'utf8') !== wanted) {
    if (update) {
      writeFileSync(SOURCES_JSON, wanted)
      written.push(SOURCES_JSON)
    }
    else {
      problems.push(`${short(SOURCES_JSON)} is stale. Run \`pnpm run android:design:update\`.`)
    }
  }
  return { name: NAME, problems, written }
}
