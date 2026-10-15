// Generates app/src/main/kotlin/.../ui/design/OpenCodeThemes.kt and assets/licenses/opencode-themes.txt
// from the colour themes built into OpenCode, the terminal coding agent (https://github.com/anomalyco/opencode,
// formerly sst/opencode, MIT). Each theme becomes a `ColorTheme` with a light and a dark `PullsColors`; a theme
// OpenCode draws in one look only (Aura, Ayu, and those whose two looks are the same) uses it for both. Pulls' own palette stays the default and is not here.
//
// The theme files are vendored in android/scripts/opencode-themes/ (with OpenCode's LICENSE), so this runs offline
// and the same input always gives the same Kotlin. Run from the repo root:
//   node android/scripts/gen-opencode-themes.mjs          write the Kotlin from the vendored files
//   node android/scripts/gen-opencode-themes.mjs --fetch  download the files again from OpenCode's dev branch first
//
// OpenCode's themes are its v1 JSON format: `defs` names colours, and each key of `theme` is a hex colour, a name
// from `defs` or another theme key, or `{ dark, light }` of those (packages/tui/src/theme/resolve.ts). Lucent Orng
// is left out: its backgrounds are `transparent`, drawn over the terminal's own, which a phone screen does not have.
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'
import process from 'node:process'

const root = new URL('../../', import.meta.url).pathname
const VENDOR = join(root, 'android/scripts/opencode-themes')
const OUT = join(root, 'android/app/src/main/kotlin/com/octaviantocan/pulls/ui/design/OpenCodeThemes.kt')
const LICENSE_OUT = join(root, 'android/app/src/main/assets/licenses/opencode-themes.txt')
const RAW = 'https://raw.githubusercontent.com/anomalyco/opencode/dev'
const SOURCE = `${RAW}/packages/tui/src/theme`

/** Display names, in OpenCode's own spelling where it has one. A theme missing here fails the run. */
export const NAMES = {
  'opencode': 'OpenCode',
  'aura': 'Aura',
  'ayu': 'Ayu',
  'carbonfox': 'Carbonfox',
  'catppuccin': 'Catppuccin',
  'catppuccin-frappe': 'Catppuccin Frappé',
  'catppuccin-macchiato': 'Catppuccin Macchiato',
  'cobalt2': 'Cobalt2',
  'cursor': 'Cursor',
  'dracula': 'Dracula',
  'everforest': 'Everforest',
  'flexoki': 'Flexoki',
  'github': 'GitHub',
  'gruvbox': 'Gruvbox',
  'kanagawa': 'Kanagawa',
  'material': 'Material',
  'matrix': 'Matrix',
  'mercury': 'Mercury',
  'monokai': 'Monokai',
  'nightowl': 'Night Owl',
  'nord': 'Nord',
  'one-dark': 'One Dark',
  'orng': 'Orng',
  'osaka-jade': 'Osaka Jade',
  'palenight': 'Palenight',
  'rosepine': 'Rosé Pine',
  'solarized': 'Solarized',
  'synthwave84': 'Synthwave \'84',
  'tokyonight': 'Tokyo Night',
  'vercel': 'Vercel',
  'vesper': 'Vesper',
  'zenburn': 'Zenburn',
}

/** Not generated: drawn over the terminal's background (`transparent`), so there is no screen colour to take. */
const SKIPPED = new Set(['lucent-orng'])

// --- Colour maths (sRGB, WCAG 2 relative luminance) ---

/** `#rgb`, `#rrggbb` or `#rrggbbaa` as channels 0 to 255 and an alpha 0 to 1. */
export function parseHex(hex) {
  const digits = hex.replace('#', '')
  const full = digits.length <= 4 ? digits.split('').map(ch => ch + ch).join('') : digits
  const n = Number.parseInt(full.slice(0, 6), 16)
  const a = full.length === 8 ? Number.parseInt(full.slice(6), 16) / 255 : 1
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a }
}

function channel(v) {
  const s = v / 255
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}

/** WCAG 2 relative luminance. */
export function luminance(c) {
  return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
}

/** WCAG 2 contrast ratio, 1 to 21. */
export function contrast(x, y) {
  const [hi, lo] = [luminance(x), luminance(y)].sort((p, q) => q - p)
  return (hi + 0.05) / (lo + 0.05)
}

/** [from] moved [t] of the way to [to]. */
export function mix(from, to, t) {
  const at = (p, q) => Math.round(p + (q - p) * t)
  return { r: at(from.r, to.r), g: at(from.g, to.g), b: at(from.b, to.b), a: 1 }
}

const WHITE = { r: 255, g: 255, b: 255, a: 1 }
const BLACK = { r: 0, g: 0, b: 0, a: 1 }
const withAlpha = (c, a) => ({ ...c, a })

/** [color] stepped toward [toward] until it reads against [against] at [ratio], or as far as it goes. */
function readable(color, against, ratio, toward) {
  for (let t = 0; t <= 1.0001; t += 0.02) {
    const next = mix(color, toward, Math.min(t, 1))
    if (contrast(next, against) >= ratio)
      return next
  }
  return toward
}

// --- OpenCode's v1 format ---

/** Every key of [theme] resolved to a colour for [mode], the way OpenCode's `resolveThemeColors` does. */
export function resolveTheme(json, mode) {
  const defs = json.defs ?? {}
  const resolve = (value, chain = []) => {
    if (typeof value === 'object' && value !== null)
      return resolve(value[mode], chain)
    if (typeof value === 'number')
      throw new Error(`ANSI colour ${value} is not supported`)
    if (value === 'none' || value === 'transparent')
      return null
    if (value.startsWith('#'))
      return parseHex(value)
    if (chain.includes(value))
      throw new Error(`circular colour reference ${[...chain, value].join(' -> ')}`)
    const next = defs[value] ?? json.theme[value]
    if (next === undefined)
      throw new Error(`colour reference "${value}" not found`)
    return resolve(next, [...chain, value])
  }
  return Object.fromEntries(Object.entries(json.theme).map(([key, value]) => [key, resolve(value)]))
}

/**
 * [resolved] with every see-through colour laid over the theme's background, as the terminal shows it (Cursor's and
 * Mercury's muted text, borders and diff washes carry an alpha). Pulls draws these on its own surfaces, so they are
 * flattened once here rather than left to blend with whatever sits under them.
 */
export function flatten(resolved) {
  const bg = resolved.background
  return Object.fromEntries(Object.entries(resolved).map(([key, c]) => {
    if (!c || c.a >= 1 || key === 'background')
      return [key, c]
    const at = (p, q) => Math.round(q + (p - q) * c.a)
    return [key, { r: at(c.r, bg.r), g: at(c.g, bg.g), b: at(c.b, bg.b), a: 1 }]
  }))
}

/** Whether [json] has a light and a dark look, or the same look for both (every value flat). */
export function hasModes(json) {
  return Object.values(json.theme).some(value => typeof value === 'object' && value !== null)
}

// --- OpenCode roles onto Pulls' ---

/**
 * The `PullsColors` for one look of an OpenCode theme. Screens, sheets and wells come from its three backgrounds,
 * ink from its text roles, the accents and states from its own, and the diff washes from its diff backgrounds.
 * What Pulls has and OpenCode has not is derived from those: borders are its subtle border halfway into the
 * background (Pulls' hairlines are quieter than a terminal's box lines), cards are a step above the sheet, and the
 * fill under white ink (`primary`, the send bubble and swipe) is the theme's primary darkened until white reads on it.
 */
export function toPulls(o) {
  const dark = luminance(o.background) < luminance(o.text)
  const bg = o.background
  const panel = o.backgroundPanel
  const element = o.backgroundElement
  const raised = dark ? mix(panel, o.text, 0.05) : mix(bg, WHITE, 0.6)
  const primary = readable(o.primary, WHITE, 3, BLACK)
  const accentWash = mix(bg, o.primary, dark ? 0.2 : 0.12)
  return {
    dark,
    base: bg,
    text: o.text,
    muted: o.textMuted,
    faint: mix(o.textMuted, bg, 0.3),
    border: mix(bg, o.borderSubtle, 0.5),
    borderMute: mix(bg, o.borderSubtle, 0.3),
    hover: withAlpha(o.text, dark ? 0x14 / 255 : 0x12 / 255),
    active: withAlpha(o.text, dark ? 0x1F / 255 : 0x1A / 255),
    raised,
    sunken: element,
    code: panel,
    secondary: panel,
    primary,
    primary600: mix(primary, BLACK, 0.18),
    activeText: o.primary,
    success: o.success,
    error: o.error,
    warning: o.warning,
    blue: o.info,
    purple: o.syntaxKeyword,
    gray: mix(o.textMuted, bg, 0.3),
    orange: o.warning,
    amber: o.warning,
    accentTeal: o.primary,
    accentOrange: o.accent,
    accentMagenta: o.secondary,
    paper: panel,
    paper2: element,
    cta: o.text,
    onCta: bg,
    chipOn: mix(element, o.text, 0.08),
    accentWash,
    accentWashInk: readable(o.primary, accentWash, 4.5, o.text),
    diffAdd: o.diffAddedBg,
    diffDel: o.diffRemovedBg,
    diffAddInk: o.diffAdded,
    diffDelInk: o.diffRemoved,
    diffAddMark: mix(o.diffAddedBg, o.diffAdded, 0.25),
    diffDelMark: mix(o.diffRemovedBg, o.diffRemoved, 0.25),
    shadow: dark ? BLACK : o.text,
    scrim: dark ? withAlpha(BLACK, 0x99 / 255) : withAlpha(o.text, 0x61 / 255),
    grabber: mix(panel, o.text, 0.15),
  }
}

/** The order of `PullsColors`' constructor, which the generated Kotlin follows. */
export const ROLES = ['base', 'text', 'muted', 'faint', 'border', 'borderMute', 'hover', 'active', 'raised', 'sunken', 'code', 'secondary', 'primary', 'primary600', 'activeText', 'success', 'error', 'warning', 'blue', 'purple', 'gray', 'orange', 'amber', 'accentTeal', 'accentOrange', 'accentMagenta', 'paper', 'paper2', 'cta', 'onCta', 'chipOn', 'accentWash', 'accentWashInk', 'diffAdd', 'diffDel', 'diffAddInk', 'diffDelInk', 'diffAddMark', 'diffDelMark', 'shadow', 'scrim', 'grabber']

const hex2 = n => n.toString(16).padStart(2, '0').toUpperCase()
const argb = c => `0x${hex2(Math.round((c.a ?? 1) * 255))}${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}`

export function themes(dir = VENDOR) {
  return readdirSync(dir).filter(f => f.endsWith('.json')).map(f => basename(f, '.json')).filter(id => !SKIPPED.has(id)).map((id) => {
    if (!NAMES[id])
      throw new Error(`${id}.json has no display name in NAMES`)
    const json = JSON.parse(readFileSync(join(dir, `${id}.json`), 'utf8'))
    const light = toPulls(flatten(resolveTheme(json, 'light')))
    const dark = hasModes(json) ? toPulls(flatten(resolveTheme(json, 'dark'))) : light
    // Catppuccin Frappé and Macchiato, and Night Owl, give both looks the same colours: one look, like Aura's.
    return { id, name: NAMES[id], light, dark, modes: JSON.stringify(light) !== JSON.stringify(dark) }
  }).sort((x, y) => (x.id === 'opencode' ? -1 : y.id === 'opencode' ? 1 : x.name.localeCompare(y.name)))
}

function colors(palette, indent) {
  const pad = ' '.repeat(indent)
  const lines = [`${pad}dark = ${palette.dark},`, ...ROLES.map(role => `${pad}${role} = Color(${argb(palette[role])}),`)]
  return lines.join('\n')
}

export function kotlin(list) {
  const entries = list.map(t => `    ColorTheme(
        id = "${t.id}",
        name = "${t.name.replace(/"/g, '\\"')}",
        light = PullsColors(
${colors(t.light, 12)}
        ),
        dark = ${t.modes ? `PullsColors(\n${colors(t.dark, 12)}\n        )` : 'null'},
    ),`).join('\n')
  return `// Generated by android/scripts/gen-opencode-themes.mjs from OpenCode's built-in themes. Do not edit; run
//   node android/scripts/gen-opencode-themes.mjs
// OpenCode (https://github.com/anomalyco/opencode) is MIT licensed, Copyright (c) 2025 opencode. Its license
// ships in assets/licenses/opencode-themes.txt and sits beside the theme files in android/scripts/opencode-themes.
package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.graphics.Color

/** OpenCode's colour themes as Pulls palettes: OpenCode first, then by name. A null dark has one look for both. */
internal val OpenCodeThemes: List<ColorTheme> = listOf(
${entries}
)
`
}

async function fetchVendored() {
  const v1 = await (await fetch(`${SOURCE}/v1.ts`)).text()
  const ids = [...v1.matchAll(/\.\/assets\/([\w-]+)\.json/g)].map(m => m[1])
  mkdirSync(VENDOR, { recursive: true })
  for (const id of [...ids, 'opencode']) {
    const response = await fetch(`${SOURCE}/assets/${id}.json`)
    if (!response.ok)
      throw new Error(`${id}.json: HTTP ${response.status}`)
    if (!SKIPPED.has(id))
      writeFileSync(join(VENDOR, `${id}.json`), await response.text())
  }
  writeFileSync(join(VENDOR, 'LICENSE'), await (await fetch(`${RAW}/LICENSE`)).text())
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  if (process.argv.includes('--fetch'))
    await fetchVendored()
  const list = themes()
  writeFileSync(OUT, kotlin(list))
  const license = readFileSync(join(VENDOR, 'LICENSE'), 'utf8')
  writeFileSync(LICENSE_OUT, `The colour themes under Settings > Theme (all but Pulls) are converted from OpenCode's built-in\nthemes, https://github.com/anomalyco/opencode.\n\n${license}`)
  for (const t of list) {
    const ratios = [t.light, t.dark].map(p => contrast(p.text, p.base).toFixed(1))
    console.log(`${t.id.padEnd(22)} ${t.modes ? 'light+dark' : 'one look  '} text/base ${ratios.join(' ')}`)
  }
  console.log(`wrote ${list.length} OpenCode themes`)
}
