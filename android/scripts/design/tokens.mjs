// Compares the front matter of DESIGN.md with the Kotlin tokens, and writes the two JSON files the
// UI Book reads from it. The Kotlin is not generated: Tokens.kt carries computed properties and
// the app is edited in parallel, so the check reads it and fails closed on any difference.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { lint } from '@google/design.md/linter'
import { ASSETS, dpScale, paletteColors, readAndroid, SHARED_DESIGN, shadowLayer, shadowLevels, typeRoles } from './kotlin.mjs'
import { buildRules, buildSpec, DESIGN_PATH, px, readDesign, stable } from './spec.mjs'

export const NAME = 'tokens'
export const RULES = [12, 45, 47, 76]
export const SPEC_JSON = join(ASSETS, 'book', 'spec.json')
export const RULES_JSON = join(ASSETS, 'book', 'rules.json')
const KOTLIN = 'app/src/main/kotlin/com/octaviantocan/pulls/ui/design'

const sameHex = (a, b) => String(a).toUpperCase() === String(b).toUpperCase()

/** Every light colour has a `dark-` twin and every twin has its light colour. */
function darkPairs(light, dark) {
  const problems = []
  for (const name of Object.keys(light)) {
    if (!(name in dark)) {
      problems.push(`DESIGN.md colors.${name} has no dark pair. Add colors.dark-${name} (rule 47)`)
    }
  }
  for (const name of Object.keys(dark)) {
    if (!(name in light)) {
      problems.push(`DESIGN.md colors.dark-${name} has no light colour ${name}`)
    }
  }
  return problems
}

/** One theme's palette in Tokens.kt against its half of the front matter. */
function paletteProblems(theme, kotlin, doc) {
  const problems = []
  for (const name of new Set([...Object.keys(kotlin), ...Object.keys(doc)])) {
    const key = theme === 'dark' ? `dark-${name}` : name
    if (!(name in kotlin)) {
      problems.push(`DESIGN.md colors.${key} is not in the ${theme} palette in Tokens.kt`)
    }
    else if (!(name in doc)) {
      problems.push(`Tokens.kt ${theme} palette has ${name} (${kotlin[name]}) and DESIGN.md colors.${key} does not`)
    }
    else if (!sameHex(kotlin[name], doc[name])) {
      problems.push(`colors.${key}: DESIGN.md ${doc[name]}, Tokens.kt ${kotlin[name]}`)
    }
  }
  return problems
}

/** Front matter `colors` compared with the two palettes in Tokens.kt, both directions. */
export function compareColors(front, light, dark) {
  const entries = Object.entries(front.colors ?? {})
  const lightDoc = Object.fromEntries(entries.filter(([k]) => !k.startsWith('dark-')))
  const darkDoc = Object.fromEntries(entries.filter(([k]) => k.startsWith('dark-')).map(([k, v]) => [k.slice(5), v]))
  return [...darkPairs(lightDoc, darkDoc), ...paletteProblems('light', light, lightDoc), ...paletteProblems('dark', dark, darkDoc)]
}

/** The dark surfaces and text, which must be a grey with no colour cast (rule 76). The accent, the diff washes and status colours are not neutral. */
export const NEUTRAL_DARK = ['base', 'text', 'muted', 'faint', 'border', 'border-mute', 'raised', 'sunken', 'code', 'secondary', 'gray', 'paper', 'paper-2', 'cta', 'on-cta', 'chip-on']

/** Dark colours that name a neutral but carry a hue: red, green and blue differ by more than 2. */
export function neutralDarkProblems(front) {
  const colors = front.colors ?? {}
  return NEUTRAL_DARK.flatMap((name) => {
    const hex = String(colors[`dark-${name}`] ?? '').replace('#', '')
    if (!/^[0-9a-f]{6}$/i.test(hex)) {
      return [`DESIGN.md colors.dark-${name} is not a six digit hex colour, so it cannot be checked for a colour cast (rule 76)`]
    }
    const [r, g, b] = [0, 2, 4].map(i => Number.parseInt(hex.slice(i, i + 2), 16))
    return Math.max(r, g, b) - Math.min(r, g, b) > 2 ? [`colors.dark-${name} is #${hex.toUpperCase()}, which has a colour cast. Dark surfaces are neutral greys: red, green and blue within 2 (rule 76)`] : []
  })
}

const TEXT_KEYS = ['fontFamily', 'fontWeight']
const NUMBER_KEYS = ['fontSize', 'lineHeight', 'letterSpacing']

/** One type role: DESIGN.md `spec` against the `kotlin` role. Tracking left out means 0. */
function roleProblems(name, kotlin, spec) {
  const want = { ...spec, letterSpacing: spec.letterSpacing ?? '0px' }
  const have = { ...kotlin, letterSpacing: kotlin.letterSpacing ?? '0px' }
  const off = [
    ...TEXT_KEYS.filter(key => String(want[key]) !== String(have[key])),
    ...NUMBER_KEYS.filter(key => px(want[key]) !== px(have[key])),
  ]
  return off.map(key => `typography.${name}.${key}: DESIGN.md ${want[key]}, PullsType ${have[key]}`)
}

/** Front matter `typography` compared with `PullsType`. */
export function compareTypography(front, roles) {
  const doc = front.typography ?? {}
  return [...new Set([...Object.keys(roles), ...Object.keys(doc)])].flatMap((name) => {
    if (!roles[name]) {
      return [`DESIGN.md typography.${name} is not a role in PullsType`]
    }
    return doc[name] ? roleProblems(name, roles[name], doc[name]) : [`PullsType.${name} is not in DESIGN.md typography`]
  })
}

/** One of `rounded`, `spacing`, `elevation` against its `object` in Scale.kt. */
export function compareScale(group, doc, kotlin, objectName, keyOf = name => name) {
  if (!kotlin) {
    return [`Scale.kt has no object ${objectName}, for DESIGN.md ${group}`]
  }
  const wanted = doc ?? {}
  const byKey = Object.fromEntries(Object.entries(kotlin).map(([name, value]) => [keyOf(name), [name, value]]))
  const problems = []
  for (const [key, value] of Object.entries(wanted)) {
    const entry = byKey[key]
    if (!entry) {
      problems.push(`DESIGN.md ${group}.${key} (${value}) is not in ${objectName}`)
    }
    else if (px(entry[1]) !== px(value)) {
      problems.push(`${group}.${key}: DESIGN.md ${value}, ${objectName}.${entry[0]} ${entry[1]}`)
    }
  }
  for (const [key, [name]] of Object.entries(byKey)) {
    if (!(key in wanted)) {
      problems.push(`${objectName}.${name} is not in DESIGN.md ${group}`)
    }
  }
  return problems
}

/**
 * Front matter `shadow` (each level's drop and strength; its blur is `elevation`) and `shadow-layer`
 * (the tight edge and the dark-mode strength) against `PullsShadow` and `floatShadow` in Kit.kt.
 */
/** One `shadow` level against its `PullsShadow` entry. */
function shadowLevelProblems(name, spec, level) {
  const problems = []
  if (px(level.y) !== px(spec.y)) {
    problems.push(`shadow.${name}.y: DESIGN.md ${spec.y}, PullsShadow ${level.y}`)
  }
  if (Number(level.alpha) !== Number(spec.alpha)) {
    problems.push(`shadow.${name}.alpha: DESIGN.md ${spec.alpha}, PullsShadow ${level.alpha}`)
  }
  if (level.blur !== name) {
    problems.push(`PullsShadow.${name} blurs by PullsElevation.${level.blur}; its blur is elevation.${name}`)
  }
  return problems
}

/** `shadow-layer` against the values `floatShadow` draws with. */
function shadowLayerProblems(wanted, layer) {
  return Object.entries(wanted ?? {}).flatMap(([key, value]) => {
    const have = layer[key]
    const same = typeof value === 'number' ? Number(have) === value : px(have) === px(value)
    return same ? [] : [`shadow-layer.${key}: DESIGN.md ${value}, Kit.kt ${have}`]
  })
}

/**
 * Front matter `shadow` (each level's drop and strength; its blur is `elevation`) and `shadow-layer`
 * (the tight edge and the dark-mode strength) against `PullsShadow` and `floatShadow` in Kit.kt.
 */
export function compareShadows(front, levels, layer) {
  if (!levels) {
    return ['Kit.kt has no enum class PullsShadow, for DESIGN.md shadow']
  }
  const wanted = front.shadow ?? {}
  const problems = []
  for (const [name, spec] of Object.entries(wanted)) {
    problems.push(...(levels[name] ? shadowLevelProblems(name, spec, levels[name]) : [`DESIGN.md shadow.${name} is not a PullsShadow level`]))
  }
  for (const name of Object.keys(levels)) {
    if (!(name in wanted)) {
      problems.push(`PullsShadow level ${name} is not in DESIGN.md shadow`)
    }
  }
  problems.push(...shadowLayerProblems(front['shadow-layer'], layer))
  return problems
}

/** `design.md lint`, from `@google/design.md`: every error finding is a problem, warnings are not. */
export function lintDesign(path = DESIGN_PATH) {
  const report = lint(readFileSync(path, 'utf8'))
  return report.findings.filter(f => f.severity === 'error').map(f => `design.md lint: ${f.path}: ${f.message} (${f.rule})`)
}

function stale(path, wanted) {
  return !existsSync(path) || readFileSync(path, 'utf8') !== wanted
}

export function check({ update = false } = {}) {
  const { front, rules } = readDesign()
  const tokens = readAndroid(`${SHARED_DESIGN}/Tokens.kt`)
  const scale = readAndroid(`${SHARED_DESIGN}/Scale.kt`)
  const kit = readAndroid(`${KOTLIN}/Kit.kt`)
  const problems = [
    ...lintDesign(),
    ...compareColors(front, paletteColors(tokens, 'LightPullsColors'), paletteColors(tokens, 'DarkPullsColors')),
    ...neutralDarkProblems(front),
    ...compareTypography(front, typeRoles(tokens)),
    ...compareScale('rounded', front.rounded, dpScale(scale, 'PullsRadius'), 'PullsRadius'),
    ...compareScale('spacing', front.spacing, dpScale(scale, 'PullsSpace'), 'PullsSpace', name => name.replace(/^x/, '')),
    ...compareScale('elevation', front.elevation, dpScale(scale, 'PullsElevation'), 'PullsElevation'),
    ...compareShadows(front, shadowLevels(kit), shadowLayer(kit)),
  ]
  const generated = [
    [SPEC_JSON, stable(buildSpec(front))],
    [RULES_JSON, stable(buildRules(rules))],
  ]
  const written = []
  for (const [path, text] of generated) {
    if (!stale(path, text))
      continue
    if (update) {
      writeFileSync(path, text)
      written.push(path)
    }
    else {
      problems.push(`${path.slice(path.indexOf('android/'))} is stale. Run \`pnpm run android:design:update\`.`)
    }
  }
  return { name: NAME, problems, written }
}
