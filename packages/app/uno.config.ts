import { generateColorRamp, presetAnthonyDesign } from '@antfu/design/unocss'
import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetWind3,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'
import { antfuOverrides, antfuTheme, color, designTheme, kitShortcuts, luCollection, preflightCss, tokens } from './src/design/theme'

/** Applies `utilities` to every `selector` descendant (`_` stands for a space). */
function descendant(selector: string, utilities: string) {
  return utilities.split(' ').map(utility => `[&_${selector}]:${utility}`).join(' ')
}

const MARKDOWN_BLOCK = ':is(p,ul,ol,pre,blockquote,table,hr,h1,h2,h3,h4)'

/** Rendered chat Markdown, whose elements the template can't put classes on. */
const chatMarkdown = [
  'text-sm leading-relaxed break-words',
  descendant(`${MARKDOWN_BLOCK}+${MARKDOWN_BLOCK}`, 'mt-2.5'),
  descendant(':is(h1,h2,h3,h4)', 'font-semibold'),
  descendant(':is(h1,h2)', 'text-base'),
  descendant(':is(h3,h4)', 'text-sm'),
  descendant('*+:is(h1,h2,h3,h4)', 'mt-4'),
  descendant(':is(ul,ol)', 'pl-5'),
  descendant('ul', 'list-disc'),
  descendant('ol', 'list-decimal'),
  descendant('li+li', 'mt-1'),
  descendant(':not(pre)>code', 'text-[max(0.85em,var(--pr-code-min,0px))] px-1 py-0.5 rounded bg-code'),
  descendant('pre', 'text-xs leading-relaxed p-3 border border-base rounded-md bg-code max-w-full overflow-x-auto'),
  descendant('pre_code', 'whitespace-pre'),
  descendant('blockquote', 'pl-3 border-l-2 border-base op-fade'),
  descendant('table', 'text-xs block max-w-full overflow-x-auto border-collapse'),
  descendant(':is(th,td)', 'px-2 py-1 border border-base text-left'),
  descendant('a', 'underline underline-offset-2'),
  descendant('a:hover', 'op-80'),
  descendant('hr', 'border-base'),
].join(' ')

/** Pointers that can really hover. A touch screen fakes `:hover` on tap and keeps it until the next tap elsewhere. */
const HOVER_MEDIA = '@media (hover: hover) and (pointer: fine)'

export function createUnoConfig() {
  return defineConfig({
    // Every `:hover` rule (`hover:`, `group-hover:`, `[&_a:hover]:`, and the hover parts
    // of shortcuts, ours and `@antfu/design`'s) applies only under HOVER_MEDIA, so a tapped
    // control on a phone doesn't stay highlighted. Touch gets `:active` instead (`main.css`).
    postprocess: [
      (util) => {
        if (/(?<!\\):hover/.test(util.selector))
          util.parent = util.parent ? `${util.parent} $$ ${HOVER_MEDIA}` : HOVER_MEDIA
      },
    ],
    variants: [
      // `coarse:` - touch screens, where a target needs ~44px whatever the viewport width.
      matcher => matcher.startsWith('coarse:')
        ? { matcher: matcher.slice('coarse:'.length), parent: '@media (pointer: coarse)' }
        : matcher,
    ],
    // The phone's tokens (android/DESIGN.md, rule 106) over Wind's: colours, type roles, radii,
    // shadows and motion. See `src/design/theme.ts`.
    theme: {
      ...designTheme(),
      // Wind's own `transition` list plus `scale`, so the `:active` press scale in
      // `main.css` eases on every control that already uses `transition`.
      transitionProperty: {
        DEFAULT: 'color,background-color,border-color,text-decoration-color,fill,stroke,opacity,box-shadow,transform,filter,backdrop-filter,scale',
      },
    },
    preflights: [{ getCSS: () => preflightCss() }],
    shortcuts: [
      ['btn', 'btn-primary'],
      ['icon-btn', 'btn-icon'],
      // `kit-cta-button`, `kit-soft-chip` and the rest: DESIGN.md's component tokens, the sizes the phone measures.
      kitShortcuts(),
      { 'chat-markdown': chatMarkdown },
      // Named z-index layers used by @antfu/design's overlay components (OverlayModal, etc.)
      // The preset ships no z-index scale and blocks plain `z-<number>` on purpose.
      {
        'z-file-diff-header': 'z-[10]',
        'z-nav': 'z-[30]',
        'z-dropdown': 'z-[40]',
        'z-tooltip': 'z-[45]',
        'z-toast': 'z-[50]',

        'z-drawer-backdrop': 'z-[80]',
        'z-drawer-content': 'z-[90]',

        'z-modal': 'z-[100]',
        'z-modal-backdrop': 'z-[101]',
        'z-modal-content': 'z-[102]',
      },
    ],
    presets: [
      presetAnthonyDesign({
        // The phone's teal, with the ramp's middle and dark steps set to its two tokens.
        primary: { ...generateColorRamp(tokens.colors.primary.light), 500: color('primary'), 600: color('primary-600'), DEFAULT: color('primary') },
        darkBackground: tokens.colors.base.dark,
        theme: antfuTheme(),
        overrides: antfuOverrides(),
      }),
      presetWind3(),
      presetAttributify(),
      presetIcons({
        scale: 1.2,
        // The prototype's line icons, the same paths the phone draws (`Lu` in Icons.kt): `i-lu-back`.
        collections: { lu: luCollection() },
      }),
      presetTypography(),
    ],
    transformers: [
      transformerDirectives(),
      transformerVariantGroup(),
    ],
  })
}

export default createUnoConfig()
