// Reads android/DESIGN.md: the front matter tokens, the numbered rules, and the two JSON files
// the UI Book ships (spec.json from the tokens, rules.json from the rules).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from 'yaml'
import { ANDROID } from './kotlin.mjs'

export const DESIGN_PATH = join(ANDROID, 'DESIGN.md')

/** The names `Checked by` may use. `review` is a person; the others are modules that declare their rules. */
export const CHECKS = ['tokens', 'raw-values', 'bans', 'book', 'motion', 'review']

const FRONT = /^---\n([\s\S]*?)\n---\n/
const FOOTER = /^Source: (\w+)\.(?: Checked by: (.+))?$/

export function readDesign(path = DESIGN_PATH) {
  return parseDesign(readFileSync(path, 'utf8'))
}

export function parseDesign(text) {
  const match = FRONT.exec(text)
  if (!match) {
    throw new Error('DESIGN.md has no front matter')
  }
  const body = text.slice(match[0].length)
  return { front: parse(match[1]), body, rules: parseRules(body) }
}

/** One `#### N. Title` block: its footer line gives the source and the checks. */
function parseRule(part) {
  const head = /^#### (\d+)\. (.+)\n/.exec(part)
  if (!head) {
    return null
  }
  // A rule ends at the next `###` group heading, which is not part of its text.
  const lines = part.slice(head[0].length).split(/\n### /)[0].trim().split('\n')
  const footer = FOOTER.exec(lines.at(-1))
  const checkedBy = footer?.[2]?.replace(/\.$/, '').split(',').map(s => s.trim()).filter(Boolean)
  return {
    n: Number(head[1]),
    title: head[2].trim(),
    source: footer?.[1] ?? null,
    checkedBy: checkedBy ?? [],
    text: (footer ? lines.slice(0, -1) : lines).join('\n').trim(),
  }
}

/** The rules under `## Rules`, up to the next `## ` heading. */
export function parseRules(body) {
  const start = body.indexOf('\n## Rules')
  if (start === -1) {
    return []
  }
  const rest = body.slice(start + 1)
  const end = rest.indexOf('\n## ', 3)
  const section = end === -1 ? rest : rest.slice(0, end)
  return section.split(/\n(?=#### )/).slice(1).map(parseRule).filter(Boolean)
}

export function px(value) {
  if (typeof value === 'number') {
    return value
  }
  const m = /^(-?[\d.]+)(?:px|dp|sp)?$/.exec(String(value).trim())
  return m ? Number.parseFloat(m[1]) : null
}

function refName(value) {
  const m = /^\{(\w+)\.([\w-]+)\}$/.exec(String(value))
  return m ? { group: m[1], name: m[2] } : null
}

function resolveRounded(component, front) {
  if (component.rounded === undefined) {
    return null
  }
  const ref = refName(component.rounded)
  return ref ? px(front.rounded?.[ref.name]) : px(component.rounded)
}

/** The token a `{group.name}` reference names, or null when there is none. */
const tokenOf = value => (value ? refName(value)?.name ?? null : null)

/** Light and dark colours paired by name, with the `dark-` twins folded in. */
function buildColors(colors) {
  const out = {}
  for (const [name, value] of Object.entries(colors ?? {}).filter(([key]) => !key.startsWith('dark-'))) {
    out[name] = { light: String(value), dark: String(colors[`dark-${name}`] ?? '') }
  }
  return out
}

function buildTypography(typography) {
  return Object.fromEntries(Object.entries(typography ?? {}).map(([name, t]) => [name, {
    fontFamily: t.fontFamily,
    fontSize: px(t.fontSize),
    fontWeight: Number(t.fontWeight),
    lineHeight: px(t.lineHeight),
    letterSpacing: px(t.letterSpacing ?? 0),
  }]))
}

/** A component token as sizes in dp and the names of the tokens it uses. A square one gives `size` once. */
function buildComponent(c, front) {
  const side = key => (c[key] === undefined ? null : px(c[key]))
  const size = side('size')
  return {
    size,
    width: side('width') ?? size,
    height: side('height') ?? size,
    rounded: resolveRounded(c, front),
    background: tokenOf(c.backgroundColor),
    color: tokenOf(c.textColor),
    typography: tokenOf(c.typography),
  }
}

/** What the UI Book reads at run time: tokens as numbers, light and dark colours paired. */
export function buildSpec(front) {
  const scale = group => Object.fromEntries(Object.entries(front[group] ?? {}).map(([k, v]) => [k, px(v)]))
  return {
    colors: buildColors(front.colors),
    typography: buildTypography(front.typography),
    rounded: scale('rounded'),
    spacing: scale('spacing'),
    elevation: scale('elevation'),
    motion: Object.fromEntries(Object.entries(front.motion ?? {}).map(([k, v]) => [k, String(v)])),
    components: Object.fromEntries(Object.entries(front.components ?? {}).map(([name, c]) => [name, buildComponent(c, front)])),
  }
}

/** What the Rules page reads: number, title, source, who checks it, text. */
export function buildRules(rules) {
  return rules.map(r => ({ n: r.n, title: r.title, source: r.source, checkedBy: r.checkedBy, text: r.text }))
}

export function stable(value) {
  return `${JSON.stringify(value, null, 2)}\n`
}
