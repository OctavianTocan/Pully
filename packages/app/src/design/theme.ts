/**
 * The web's half of one look for both apps (android/DESIGN.md, rule 106). Everything here is
 * computed from the generated files beside it, never written by hand: the tokens come from
 * DESIGN.md's front matter, the icons from the Android kit's `Lu`, the fonts from `PullsFonts`.
 * `uno.config.ts` reads it for the theme, the CSS variables, the `@antfu/design` overrides and the
 * `kit-*` classes, so a colour, size, radius or shadow exists on the web only if the phone has it.
 */
import icons from './icons.generated.json'
import tokens from './tokens.generated.json'

export type ColorName = keyof typeof tokens.colors
export type TypeRole = keyof typeof tokens.typography
export type ShadowLevel = keyof typeof tokens.shadow
export type ComponentName = keyof typeof tokens.components

interface Channels { rgb: string, alpha: number }

/** `#RRGGBB[AA]` as space-separated channels and an alpha. */
export function channels(hex: string): Channels {
  const value = hex.replace('#', '')
  const [r, g, b] = [0, 2, 4].map(i => Number.parseInt(value.slice(i, i + 2), 16))
  const alpha = value.length === 8 ? Number.parseInt(value.slice(6, 8), 16) / 255 : 1
  return { rgb: `${r} ${g} ${b}`, alpha: Math.round(alpha * 1000) / 1000 }
}

const colorNames = Object.keys(tokens.colors) as ColorName[]

/** Colours that are see-through in either theme carry their alpha in a variable of their own. */
const translucent = new Set(colorNames.filter(name => channels(tokens.colors[name].light).alpha < 1 || channels(tokens.colors[name].dark).alpha < 1))

/** A token as a CSS colour that follows the theme: `rgb(var(--c-paper))`. */
export function color(name: ColorName): string {
  return translucent.has(name) ? `rgb(var(--c-${name}) / var(--a-${name}))` : `rgb(var(--c-${name}))`
}

/** The same colour inside a UnoCSS arbitrary value, where a space is written `_`. */
export function arbitrary(name: ColorName): string {
  return `[${color(name).replaceAll(' ', '_')}]`
}

/** `0 dy blur` in the ink, over a tight one-pixel edge at a share of its strength (DESIGN.md, Elevation & Depth). */
export function shadowValue(level: ShadowLevel, theme: 'light' | 'dark'): string {
  const { y, blur, alpha } = tokens.shadow[level]
  const layer = tokens.shadowLayer
  const strength = theme === 'dark' ? Math.min(alpha * layer['dark-strength'], layer['dark-max']) : alpha
  const round = (n: number) => Math.round(n * 1000) / 1000
  return `0 ${layer['edge-y']}px ${layer['edge-radius']}px rgb(var(--c-shadow) / ${round(strength * layer['edge-share'])}), 0 ${y}px ${blur}px rgb(var(--c-shadow) / ${round(strength)})`
}

/** `xs` and `sm` step down a notch from 768px up, as `PullsType` does from 768dp. */
const WIDE_FROM = 768
const stepped = (Object.keys(tokens.typography) as TypeRole[]).filter(role => `${role}-wide` in tokens.typography)

/** The CSS variables of both themes. Light sits on `:root` and the embed's `:host`; `.dark` swaps every colour and shadow. */
export function preflightCss(): string {
  const block = (theme: 'light' | 'dark') => {
    const lines: string[] = []
    for (const name of colorNames) {
      const { rgb, alpha } = channels(tokens.colors[name][theme])
      lines.push(`--c-${name}:${rgb};`)
      if (translucent.has(name))
        lines.push(`--a-${name}:${alpha};`)
    }
    for (const level of Object.keys(tokens.shadow) as ShadowLevel[])
      lines.push(`--shadow-${level}:${shadowValue(level, theme)};`)
    return lines.join('')
  }
  const type = (wide: boolean) => stepped.map((role) => {
    const t = tokens.typography[(wide ? `${role}-wide` : role) as TypeRole]
    return `--fs-${role}:${t.size}px;--lh-${role}:${t.line}px;`
  }).join('')
  const motion = Object.entries(tokens.motion).map(([name, m]) => `--motion-${name}:${m.ms}ms;`).join('')
  const { fontFamily } = designTheme()
  return [
    `:root,:host{${block('light')}${type(false)}${motion}${diffVariables()}}`,
    `html,:host{font-family:${fontFamily.sans}}`,
    `code,kbd,samp,pre{font-family:${fontFamily.mono}}`,
    `@media (min-width:${WIDE_FROM}px){:root,:host{${type(true)}}}`,
    `html.dark,.dark{color-scheme:dark;${block('dark')}}`,
  ].join('\n')
}

/**
 * The diff (`@pierre/diffs`, drawn in a shadow root) reads these through inheritance: the phone's
 * washes for added and removed rows, their marks for the changed words, their inks for the gutter,
 * paper for the hunk bars, and Geist Mono for the code.
 */
function diffVariables(): string {
  const v = (name: ColorName) => color(name)
  const vars: Record<string, string> = {
    '--diffs-light-bg': v('base'),
    '--diffs-dark-bg': v('base'),
    '--diffs-light': v('text'),
    '--diffs-dark': v('text'),
    '--diffs-addition-color-override': v('diff-add-ink'),
    '--diffs-deletion-color-override': v('diff-del-ink'),
    '--diffs-modified-color-override': v('accent-teal'),
    '--diffs-bg-addition-override': v('diff-add'),
    '--diffs-bg-deletion-override': v('diff-del'),
    '--diffs-bg-addition-number-override': v('diff-add'),
    '--diffs-bg-deletion-number-override': v('diff-del'),
    '--diffs-bg-addition-emphasis-override': v('diff-add-mark'),
    '--diffs-bg-deletion-emphasis-override': v('diff-del-mark'),
    '--diffs-fg-number-addition-override': v('diff-add-ink'),
    '--diffs-fg-number-deletion-override': v('diff-del-ink'),
    '--diffs-fg-number-override': v('faint'),
    '--diffs-bg-separator-override': v('paper'),
    '--diffs-bg-buffer-override': v('paper'),
    '--diffs-bg-hover-override': v('hover'),
    '--diffs-font-family': designTheme().fontFamily.mono,
    '--diffs-header-font-family': designTheme().fontFamily.sans,
  }
  return Object.entries(vars).map(([key, value]) => `${key}:${value};`).join('')
}

/** Wind's own palette names, which keep their ramps: the token becomes the bare `DEFAULT` (`color-blue`, still `color-blue-500`). */
const RAMPED = new Set(['blue', 'purple', 'gray', 'orange', 'amber'])

const family = (name: string, fallback: string) => `'${name}',${fallback}`
const SANS_FALLBACK = 'ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif'
const MONO_FALLBACK = 'ui-monospace,SFMono-Regular,Menlo,Consolas,monospace'

/** `@antfu/design` owns these ramps and sets them after the app's theme, so the tokens reach it through its own `theme` option. */
export const ANTFU_RAMPS = ['success', 'error', 'warning'] as const

/** The status ramps with each token as its bare `DEFAULT`: `color-success` is the phone's, `color-success-600` stays the ramp's. */
export function antfuTheme() {
  return { colors: Object.fromEntries(ANTFU_RAMPS.map(name => [name, { DEFAULT: color(name) }])) }
}

function themeColors() {
  const colors: Record<string, any> = {}
  for (const name of colorNames) {
    if (!(ANTFU_RAMPS as readonly string[]).includes(name) && name !== 'primary' && name !== 'primary-600')
      colors[name] = RAMPED.has(name) ? { DEFAULT: color(name) } : color(name)
  }
  // `accent-teal` and its two siblings sit in the web's older `accent.<hue>` ramp.
  for (const hue of ['teal', 'orange', 'magenta'] as const)
    colors.accent = { ...colors.accent, [hue]: { DEFAULT: color(`accent-${hue}`) } }
  return colors
}

function themeFontSize() {
  const fontSize: Record<string, [string, Record<string, string>]> = {}
  for (const [role, t] of Object.entries(tokens.typography)) {
    if (role.endsWith('-wide'))
      continue
    const base = stepped.includes(role as TypeRole)
      ? [`var(--fs-${role})`, `var(--lh-${role})`] as const
      : [`${t.size}px`, `${t.line}px`] as const
    fontSize[role === 'xxl' ? '2xl' : role] = [base[0], { 'line-height': base[1], ...(t.tracking ? { 'letter-spacing': `${t.tracking}px` } : {}) }]
  }
  fontSize.xxl = fontSize['2xl']!
  return fontSize
}

function themeMotion() {
  const duration: Record<string, string> = {}
  const easing: Record<string, string> = {}
  for (const [name, m] of Object.entries(tokens.motion) as [string, { ms: number, curve?: string }][]) {
    duration[name] = `${m.ms}ms`
    if (m.curve?.startsWith('cubic-bezier'))
      easing[name] = m.curve
  }
  return { duration, easing }
}

/** The theme UnoCSS deep-merges over Wind's. */
export function designTheme() {
  const borderRadius: Record<string, string> = { DEFAULT: `${tokens.rounded.sm}px` }
  for (const [name, value] of Object.entries(tokens.rounded))
    borderRadius[name] = name === 'full' ? '9999px' : `${value}px`

  const boxShadow = Object.fromEntries(Object.keys(tokens.shadow).map(level => [level, `var(--shadow-${level})`]))

  return {
    colors: themeColors(),
    fontFamily: {
      sans: family(tokens.fonts.sans, SANS_FALLBACK),
      mono: family(tokens.fonts.mono, MONO_FALLBACK),
      display: family(tokens.fonts.display, family(tokens.fonts.sans, SANS_FALLBACK)),
    },
    fontSize: themeFontSize(),
    borderRadius,
    boxShadow,
    ...themeMotion(),
  }
}

/** A type role as classes: its size and line height, plus its family and weight when they are not the body's. */
export function typeClasses(role: TypeRole): string {
  const t = tokens.typography[role]
  const classes = [`text-${role}`]
  if (t.family !== tokens.fonts.sans)
    classes.push('font-display')
  if (t.weight !== 400)
    classes.push(`font-${t.weight}`)
  return classes.join(' ')
}

const sizeOf = (n: number | undefined) => (n === undefined ? undefined : `${n}px`)

/** One `components` token as the classes of its `kit-<name>` shortcut. */
export function componentClasses(name: ComponentName): string {
  const c = tokens.components[name] as {
    background?: ColorName
    color?: ColorName
    typography?: TypeRole
    rounded?: string | number
    width?: number
    height?: number
    padding?: { y: number, x: number }
  }
  const classes: string[] = []
  if (c.background)
    classes.push(`bg-${arbitrary(c.background)}`)
  if (c.color)
    classes.push(`color-${arbitrary(c.color)}`)
  if (c.typography)
    classes.push(typeClasses(c.typography))
  if (typeof c.rounded === 'string')
    classes.push(`rounded-${c.rounded}`)
  else if (typeof c.rounded === 'number')
    classes.push(`rounded-[${c.rounded}px]`)
  if (c.width !== undefined)
    classes.push(`w-[${sizeOf(c.width)}]`)
  if (c.height !== undefined)
    classes.push(`h-[${sizeOf(c.height)}]`)
  if (c.padding)
    classes.push(c.padding.x === c.padding.y ? `p-[${c.padding.x}px]` : `px-[${c.padding.x}px] py-[${c.padding.y}px]`)
  return classes.join(' ')
}

/** `kit-float-button`, `kit-cta-button` and the rest: every component token, as DESIGN.md declares it. */
export function kitShortcuts(): Record<string, string> {
  return Object.fromEntries((Object.keys(tokens.components) as ComponentName[]).map(name => [`kit-${name}`, componentClasses(name)]))
}

const FOCUS = 'outline-none focus-visible:ring-2 focus-visible:ring-[rgb(var(--c-accent-teal)/0.45)]'

/**
 * `@antfu/design`'s vocabulary, redrawn in the phone's tokens. The components it ships (and every
 * screen written against `bg-base`, `color-muted`, `btn-primary`…) take the phone's look from here.
 */
export function antfuOverrides(): Record<string, string> {
  const c = arbitrary
  return {
    // Text: ink, and its two quieter steps. Text never fades further (rule 14).
    'color-base': `color-${c('text')}`,
    'color-base-light': `color-${c('text')}`,
    'color-base-dark': `color-${c('text')}`,
    'color-muted': `color-${c('muted')}`,
    'color-faint': `color-${c('faint')}`,
    'color-active': `color-${c('active-text')}`,

    // Surfaces.
    'bg-base': `bg-${c('base')}`,
    'bg-secondary': `bg-${c('secondary')}`,
    'bg-raised': `bg-${c('raised')}`,
    'bg-sunken': `bg-${c('sunken')}`,
    'bg-active': `bg-${c('active')}`,
    'bg-ambient': `bg-${c('chip-on')}`,
    'bg-hover': `bg-${c('hover')}`,
    'bg-code': `bg-${c('code')}`,
    'bg-tooltip': `bg-${c('cta')} color-${c('on-cta')}`,
    'bg-gradient-more': `bg-gradient-to-t from-${c('base')} to-transparent`,

    // Borders.
    'border-base': `border-${c('border')}`,
    'border-mute': `border-${c('border-mute')}`,
    'border-active': `border-${c('primary')}`,
    'ring-base': `ring-${c('border')}`,

    // Opacity steps that land on `muted` and `faint` over the paper.
    'op-fade': 'op60',
    'op-mute': 'op45',

    // Buttons, as the phone's: the teal pill for the main action (`primary`, as Review and Analyze),
    // a quiet paper pill beside it, a bare one for the least. No borders; pressing tints (rule 82).
    'btn-primary': `inline-flex items-center justify-center gap-2 h-9 px-4 rounded-full border-none bg-${c('primary')} color-white font-medium transition hover:bg-${c('primary-600')} disabled:op50 disabled:pointer-events-none ${FOCUS}`,
    'btn-action': `inline-flex items-center justify-center gap-2 h-9 px-4 rounded-full border-none bg-${c('paper-2')} color-${c('text')} font-medium transition hover:bg-${c('chip-on')} disabled:pointer-events-none disabled:op40! ${FOCUS}`,
    'btn-action-sm': 'btn-action h-8 px-3 text-sm',
    'btn-action-active': `bg-${c('chip-on')}! color-${c('text')}`,
    'btn-text': `inline-flex items-center gap-2 h-9 px-3 rounded-full border-none color-${c('text')} transition hover:bg-${c('hover')} disabled:pointer-events-none disabled:op40! ${FOCUS}`,
    'btn-icon': `w-9 h-9 rounded-full color-${c('text')} transition flex items-center justify-center hover:bg-${c('hover')} disabled:pointer-events-none disabled:op40 ${FOCUS}`,
    'btn-icon-compact': `w-7 h-7 rounded-full color-${c('muted')} transition flex items-center justify-center hover:color-${c('text')} hover:bg-${c('hover')} disabled:pointer-events-none disabled:op40 ${FOCUS}`,
    'btn-icon-square': `kit-float-button shadow-button flex items-center justify-center transition disabled:pointer-events-none disabled:op40 ${FOCUS}`,

    // Badges: soft pills, never boxed.
    'badge': 'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium leading-none',
    'badge-active': `badge bg-${c('accent-wash')} color-${c('accent-wash-ink')}`,
    'badge-muted': `badge bg-${c('chip-on')} color-${c('muted')}`,

    // The small type steps, at the phone's sizes.
    ...Object.fromEntries((['micro', 'mini', 'compact'] as const).map((role) => {
      const t = tokens.typography[role]
      return [`text-${role}`, `text-[${t.size}px] leading-[${t.line}px]`]
    })),
  }
}

/** The prototype's icons as an Iconify collection: `i-lu-back`, drawn from the same paths as `Lu.back`. */
export function luCollection(): Record<string, string> {
  const svg: Record<string, string> = {}
  for (const [name, glyph] of Object.entries(icons.icons)) {
    const paths = glyph.paths.map(p => p.fill
      ? `<path d="${p.d}" fill="currentColor" stroke="none"/>`
      : `<path d="${p.d}"/>`).join('')
    svg[name] = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${glyph.stroke}" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`
  }
  return svg
}

export { tokens }
