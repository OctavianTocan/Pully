// One look for both apps (rule 106). The web app takes what the phone is drawn with from the same
// sources and nothing of its own: the tokens from DESIGN.md's front matter, the prototype icons from
// `Lu` in Icons.kt, and the fonts from `PullsFonts` in Tokens.kt, each written into
// packages/app/src/design as generated files. Strings go the other way: the web's locale files are
// the source and the phone carries byte-for-byte copies. A generated file that differs from what
// its source gives fails, and `--update` rewrites it.
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { ASSETS, FONT_FAMILY_NAMES, fontFamilies, luGlyphs, readAndroid, REPO } from './kotlin.mjs'
import { px, readDesign, stable } from './spec.mjs'

export const NAME = 'web'
export const RULES = [106]

const KOTLIN = 'app/src/main/kotlin/com/octaviantocan/pulls/ui/design'
export const WEB_DESIGN = join(REPO, 'packages', 'app', 'src', 'design')
export const TOKENS_JSON = join(WEB_DESIGN, 'tokens.generated.json')
export const ICONS_JSON = join(WEB_DESIGN, 'icons.generated.json')
export const FONTS_CSS = join(WEB_DESIGN, 'fonts.generated.css')
export const WEB_LOCALES = join(REPO, 'packages', 'app', 'src', 'i18n', 'locales')
export const ANDROID_LOCALES = join(ASSETS, 'i18n')
const FONT_DIR = join(REPO, 'android', 'app', 'src', 'main', 'res', 'font')

const HEADER = 'Generated from android/DESIGN.md and the Android kit by android/scripts/design/web.mjs (pnpm run android:design:update). Do not edit.'

/** A `{group.name}` reference, or null. */
function ref(value) {
  const m = /^\{(\w[\w-]*)\.([\w-]+)\}$/.exec(String(value ?? ''))
  return m ? { group: m[1], name: m[2] } : null
}

/** `0px 14px` as `[0, 14]`, `2px` as `[2, 2]`: a padding's block and inline sides. */
function padding(value) {
  if (value === undefined) {
    return undefined
  }
  const parts = String(value).trim().split(/\s+/).map(px)
  return { y: parts[0], x: parts[1] ?? parts[0] }
}

/**
 * A component token as the web kit reads it: colour and type role by name, sizes in px. A radius
 * that names a token keeps the name, so the web uses the same class the theme defines for it.
 */
function webComponent(c) {
  const size = c.size === undefined ? undefined : px(c.size)
  const rounded = ref(c.rounded)
  return {
    background: ref(c.backgroundColor)?.name,
    color: ref(c.textColor)?.name,
    typography: ref(c.typography)?.name,
    rounded: rounded ? rounded.name : (c.rounded === undefined ? undefined : px(c.rounded)),
    width: c.width === undefined ? size : px(c.width),
    height: c.height === undefined ? size : px(c.height),
    padding: padding(c.padding),
  }
}

/** A motion token's duration and curve, when it has them (`press` and the like are words only). */
function motion(value) {
  const text = String(value)
  const ms = /^(\d+)ms\b/.exec(text)?.[1]
  if (!ms) {
    return undefined
  }
  const curve = /cubic-bezier\([^)]*\)|\bease(?:-in-out|-in|-out)?\b|\blinear\b/.exec(text)?.[0]
  return curve ? { ms: Number(ms), curve } : { ms: Number(ms) }
}

const prune = object => JSON.parse(JSON.stringify(object))

/** The front matter as the web reads it: plain numbers in px, colours paired by theme. */
export function webTokens(front) {
  const colors = {}
  for (const [name, value] of Object.entries(front.colors ?? {})) {
    if (!name.startsWith('dark-')) {
      colors[name] = { light: String(value).toUpperCase(), dark: String(front.colors[`dark-${name}`] ?? '').toUpperCase() }
    }
  }
  const typography = Object.fromEntries(Object.entries(front.typography ?? {}).map(([name, t]) => [name, {
    family: t.fontFamily,
    size: px(t.fontSize),
    line: px(t.lineHeight),
    weight: Number(t.fontWeight),
    tracking: px(t.letterSpacing ?? 0),
  }]))
  const numbers = group => Object.fromEntries(Object.entries(front[group] ?? {}).map(([k, v]) => [k, px(v)]))
  const shadow = Object.fromEntries(Object.entries(front.shadow ?? {}).map(([name, s]) => [name, {
    y: px(s.y),
    blur: px(front.elevation?.[name]),
    alpha: Number(s.alpha),
  }]))
  const layer = Object.fromEntries(Object.entries(front['shadow-layer'] ?? {}).map(([k, v]) => [k, typeof v === 'number' ? v : px(v)]))
  return prune({
    colors,
    typography,
    rounded: numbers('rounded'),
    spacing: numbers('spacing'),
    shadow,
    shadowLayer: layer,
    motion: Object.fromEntries(Object.entries(front.motion ?? {}).map(([k, v]) => [k, motion(v)]).filter(([, v]) => v)),
    components: Object.fromEntries(Object.entries(front.components ?? {}).map(([name, c]) => [name, webComponent(c)])),
  })
}

/** Every token a component names exists in its group. */
export function componentProblems(tokens) {
  const problems = []
  for (const [name, c] of Object.entries(tokens.components)) {
    for (const [key, group] of [['background', 'colors'], ['color', 'colors'], ['typography', 'typography']]) {
      if (c[key] !== undefined && !(c[key] in tokens[group])) {
        problems.push(`DESIGN.md components.${name} names ${group}.${c[key]}, which does not exist`)
      }
    }
    if (typeof c.rounded === 'string' && !(c.rounded in tokens.rounded)) {
      problems.push(`DESIGN.md components.${name} names rounded.${c.rounded}, which does not exist`)
    }
  }
  return problems
}

/** `@font-face` rules for every `PullsFonts` file, pointing at the same TTFs the APK ships. */
export function fontsCss(families, fromDir = WEB_DESIGN) {
  const lines = [`/* ${HEADER} */`, '']
  const problems = []
  for (const [key, fonts] of Object.entries(families ?? {})) {
    const family = FONT_FAMILY_NAMES[key] ?? key
    for (const { file, weight } of fonts) {
      const path = join(FONT_DIR, `${file}.ttf`)
      if (!existsSync(path)) {
        problems.push(`PullsFonts.${key} uses R.font.${file}, and android/app/src/main/res/font/${file}.ttf does not exist`)
        continue
      }
      lines.push(
        '@font-face {',
        `  font-family: '${family}';`,
        `  src: url('${relative(fromDir, path).split('\\').join('/')}') format('truetype');`,
        `  font-weight: ${weight};`,
        '  font-style: normal;',
        '  font-display: swap;',
        '}',
        '',
      )
    }
  }
  return { css: `${lines.join('\n').trimEnd()}\n`, problems }
}

/** The web locales and the phone's copies: names on one side only, or contents that differ. */
export function localeProblems(web = WEB_LOCALES, android = ANDROID_LOCALES) {
  const list = dir => (existsSync(dir) ? readdirSync(dir).filter(n => n.endsWith('.json')).sort() : [])
  const webNames = list(web)
  const androidNames = list(android)
  const problems = []
  for (const name of webNames) {
    if (!androidNames.includes(name)) {
      problems.push(`android/app/src/main/assets/i18n/${name} is missing. The phone carries every web locale`)
    }
    else if (readFileSync(join(web, name), 'utf8') !== readFileSync(join(android, name), 'utf8')) {
      problems.push(`android/app/src/main/assets/i18n/${name} differs from packages/app/src/i18n/locales/${name}. Strings are written in the web locales; the phone's are copies`)
    }
  }
  for (const name of androidNames) {
    if (!webNames.includes(name)) {
      problems.push(`android/app/src/main/assets/i18n/${name} has no web locale. Add packages/app/src/i18n/locales/${name}, or remove the copy`)
    }
  }
  return problems
}

function stale(path, wanted) {
  return !existsSync(path) || readFileSync(path, 'utf8') !== wanted
}

const short = path => relative(REPO, path).split('\\').join('/')

export function check({ update = false } = {}) {
  const { front } = readDesign()
  const families = fontFamilies(readAndroid(`${KOTLIN}/Tokens.kt`))
  // The CSS name of each `PullsFonts` family, so the web's `font-sans`, `font-mono` and `font-display` are the phone's.
  const tokens = { fonts: Object.fromEntries(Object.keys(families ?? {}).map(key => [key, FONT_FAMILY_NAMES[key] ?? key])), ...webTokens(front) }
  const glyphs = luGlyphs(readAndroid(`${KOTLIN}/Icons.kt`))
  const fonts = fontsCss(families)
  const problems = [
    ...componentProblems(tokens),
    ...fonts.problems,
    ...(glyphs ? [] : ['Icons.kt has no object Lu, the icons the web draws']),
  ]
  const generated = [
    [TOKENS_JSON, stable({ $comment: HEADER, ...tokens })],
    [ICONS_JSON, stable({ $comment: HEADER, icons: glyphs ?? {} })],
    [FONTS_CSS, fonts.css],
  ]
  for (const name of existsSync(WEB_LOCALES) ? readdirSync(WEB_LOCALES).filter(n => n.endsWith('.json')) : []) {
    generated.push([join(ANDROID_LOCALES, name), readFileSync(join(WEB_LOCALES, name), 'utf8')])
  }
  const written = []
  for (const [path, text] of generated) {
    if (!stale(path, text)) {
      continue
    }
    if (update) {
      mkdirSync(dirname(path), { recursive: true })
      writeFileSync(path, text)
      written.push(path)
    }
    else if (!path.startsWith(ANDROID_LOCALES)) {
      problems.push(`${short(path)} is stale. Run \`pnpm run android:design:update\`.`)
    }
  }
  if (!update) {
    problems.push(...localeProblems())
  }
  return { name: NAME, problems, written }
}
