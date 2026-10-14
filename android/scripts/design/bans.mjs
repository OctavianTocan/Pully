// Ratchet on banned imports and calls: Material widgets the kit replaces, Material icons,
// speech input, outgoing share, stat tiles, the web-port kit and the waiting components.
// A ban with an empty baseline is a hard ban. A ban with a baseline is frozen: the departures that
// exist today are counted per file and may only fall. Lift a ban by editing the table below, or
// excuse one line with `// ban-ok: <reason>`. Nothing is skipped silently.
import { join } from 'node:path'
import { ANDROID, blank, excusedLines, kotlinFiles, lineIndex, readAndroid, shortPath } from './kotlin.mjs'
import { compare, readBaseline, writeBaseline } from './ratchet.mjs'

export const NAME = 'bans'
export const BASELINE = join(ANDROID, 'scripts', 'design', 'bans.baseline.json')
const UPDATE = 'pnpm run android:design:update'

/** Material components the kit has no use for or has replaced. Anything else under material3 is a ban. */
const MATERIAL_OK = [
  'Text',
  'Icon',
  'MaterialTheme',
  'ColorScheme',
  'Typography',
  'lightColorScheme',
  'darkColorScheme',
  'LocalContentColor',
  'LocalTextStyle',
  'ProvideTextStyle',
  'ExperimentalMaterial3Api',
  'contentColorFor',
]

/** Material's bottom sheet, which nothing may use: the kit's own `PullsSheet` takes the rule 73 timing and Material's cannot. */
const SHEETS = [
  'ModalBottomSheet',
  'ModalBottomSheetDefaults',
  'rememberModalBottomSheetState',
  'SheetState',
  'SheetValue',
  'BottomSheetDefaults',
]

/** Material pieces that only the kit's own wrappers may use. */
const KIT_ONLY = ['DropdownMenu', 'DropdownMenuItem']

/** Files that may use KIT_ONLY: the kit. */
const KIT_FILES = ['ui/design/']

/** The web-port kit in `Components.kt`: one visual dialect only (rule 49). */
const LEGACY_KIT = ['ActionButton', 'ToggleGroup', 'ReviewCheckbox', 'TextInput', 'Badge', 'EmptyState', 'Card']

/** Its icon dialect: Phosphor glyphs by name instead of the Lucide `Lu` glyphs. */
const LEGACY_ICONS = ['PhIcon', 'IconButton', 'HeaderIcon']

/** The robot head as a Lucide glyph, and the sparkle, compass and chat bubble as Phosphor names in any weight. */
const AI_GLYPH = /\bLu\.bot\b|"(?:robot|sparkles?|compass|chat-circle|chat-circle-dots|chat-dots|chat-teardrop|chat-text)(?:-(?:duotone|bold|fill|light|thin))?"/g

const WAITING = ['Loading', 'SpinIcon', 'FeedbackSpinner', 'CircularProgressIndicator', 'LinearProgressIndicator']

const MATERIAL = /^androidx\.compose\.material3\.(\w+(?:\.\w+)*)$/

/** The kit's own package: a call spelled out in full is still a call to the kit. */
const KIT_PACKAGE = 'com\\.octaviantocan\\.pulls\\.ui\\.design\\.'

const call = names => new RegExp(`(?<!fun\\s)(?<![\\w.])(?:${KIT_PACKAGE})?(?:${names.join('|')})\\s*\\(`, 'g')

/**
 * `import` bans test the imported name; `call` bans test code with strings and comments blanked,
 * unless `strings` is set, which keeps string literals for icon names.
 * `allowIn` lists path prefixes that are exempt.
 */
export const BANS = {
  'material-component': {
    label: 'Material components the kit replaces',
    rules: [1, 8, 28, 38, 49],
    advice: 'Use the kit: CtaButton, FloatButton, PullsSwitch, PullsCheck, UnderlineTabs, CapsuleTabs, SoftChip.',
    importOf(name) {
      if (name === 'androidx.compose.material3.*')
        return true
      const m = MATERIAL.exec(name)
      if (!m)
        return false
      const leaf = m[1].split('.')[0]
      return !MATERIAL_OK.includes(leaf) && !KIT_ONLY.includes(leaf) && !SHEETS.includes(leaf) && leaf !== 'pulltorefresh'
    },
  },
  'material-sheet': {
    label: 'Material bottom sheets',
    rules: [5, 49, 73],
    advice: 'Use PullsSheet. Material\'s sheet slides and closes on its own timing, not rule 73\'s.',
    importOf: name => SHEETS.some(leaf => name === `androidx.compose.material3.${leaf}`),
  },
  'material-sheet-menu': {
    label: 'Material menus outside the kit',
    rules: [5, 49],
    advice: 'Use MenuReveal through the kit\'s menu.',
    allowIn: KIT_FILES,
    importOf: name => KIT_ONLY.some(leaf => name === `androidx.compose.material3.${leaf}`),
  },
  'material-pull-refresh': {
    label: 'pull-to-refresh indicators',
    rules: [52],
    advice: 'Refresh is silent: refresh in the background and show nothing.',
    importOf: name => name.startsWith('androidx.compose.material3.pulltorefresh.'),
  },
  'material-icons': {
    label: 'Material icons',
    rules: [1, 48],
    advice: 'Ask which fact the icon carries. If one is needed, use a Lu glyph.',
    importOf: name => name.startsWith('androidx.compose.material.icons.') || name.startsWith('androidx.compose.material.Icon'),
  },
  'speech': {
    label: 'speech input',
    rules: [10, 11],
    advice: 'No voice or dictation, ever.',
    importOf: name => name.startsWith('android.speech.'),
    call: /\b(?:SpeechRecognizer|RecognizerIntent)\b/g,
  },
  'share-out': {
    label: 'outgoing share',
    rules: [11],
    advice: 'No Share on a PR or a tour.',
    call: /\bIntent\.createChooser\(|\bShareCompat\b/g,
  },
  'stat-tile': {
    label: 'stat tiles',
    rules: [8, 22],
    advice: 'Typographic hierarchy on a flat page, with aligned label and value columns.',
    call: /\bfun\s+(?:Stat|Kpi|Metric)(?:Tile|Card)\b/g,
  },
  'legacy-kit': {
    label: 'uses of the web-port kit',
    rules: [49],
    advice: 'Use the mobile kit in Kit.kt. The web-port kit is frozen.',
    allowIn: ['ui/design/Components.kt'],
    call: call(LEGACY_KIT),
  },
  'legacy-icons': {
    label: 'uses of the Phosphor icon dialect',
    rules: [48, 49],
    advice: 'Use Lu glyphs from Icons.kt, and only where the icon carries a fact.',
    allowIn: ['ui/design/Components.kt'],
    call: call(LEGACY_ICONS),
  },
  'ai-glyph': {
    label: 'generic AI glyphs (robot head, sparkle, compass, chat bubble)',
    rules: [1, 48],
    advice: 'Ask which fact the icon carries. The model is shown by its own icon. A line that has to keep one says why with // ban-ok.',
    allowIn: ['ui/design/Components.kt'],
    strings: true,
    call: AI_GLYPH,
  },
  'waiting': {
    label: 'loading screens and spinners',
    rules: [51],
    advice: 'Show a Skeleton where the content will be. AI work in progress keeps its orb.',
    allowIn: ['ui/design/Components.kt'],
    call: call(WAITING),
  },
}

export const RULES = [...new Set(Object.values(BANS).flatMap(ban => ban.rules))].sort((a, b) => a - b)

/** The kinds the ratchet reports, in the shape `compare` wants. */
export const KINDS = BANS

const allowed = (ban, file) => (ban.allowIn ?? []).some(prefix => file.startsWith(prefix))

const IMPORT_LINE = /^import .*$/gm

const IMPORT = /^import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+\w+)?\s*$/gm

/** A Material or speech name spelled out in full where it is used, which needs no import. */
const QUALIFIED = /(?<![\w.])(?:androidx\.compose\.material3|androidx\.compose\.material\.icons|androidx\.compose\.material|android\.speech)(?:\.\w+)+/g

/** Offsets in `code` where `ban` matches, by its imports, its names spelled out in full and its call pattern. */
function offsetsOf(ban, code, imports) {
  const found = ban.importOf ? imports.filter(m => ban.importOf(m[1])).map(m => m.index) : []
  if (ban.importOf) {
    const body = code.replace(IMPORT_LINE, line => ' '.repeat(line.length))
    found.push(...[...body.matchAll(QUALIFIED)].filter(m => ban.importOf(m[0])).map(m => m.index))
  }
  return ban.call ? [...found, ...[...code.matchAll(ban.call)].map(m => m.index)] : found
}

/** `hits[ban][file]`: the line of every ban hit in `sources` (short path to text) not excused with `// ban-ok:`. */
export function scan(sources) {
  const hits = Object.fromEntries(Object.keys(BANS).map(id => [id, {}]))
  for (const [file, source] of Object.entries(sources)) {
    const code = blank(source)
    const literal = blank(source, { keepStrings: true })
    const lineAt = lineIndex(code)
    const excused = excusedLines(source, 'ban-ok')
    const imports = [...code.matchAll(IMPORT)]
    for (const [id, ban] of Object.entries(BANS).filter(([, b]) => !allowed(b, file))) {
      const lines = offsetsOf(ban, ban.strings ? literal : code, imports).map(lineAt).filter(line => !excused.has(line))
      if (lines.length > 0) {
        hits[id][file] = lines
      }
    }
  }
  return hits
}

export function readSources() {
  return Object.fromEntries(kotlinFiles().map(path => [shortPath(path), readAndroid(path)]))
}

export function check({ update = false } = {}) {
  const hits = scan(readSources())
  if (update) {
    writeBaseline(BASELINE, hits)
    return { name: NAME, problems: [], written: [BASELINE] }
  }
  return { name: NAME, problems: compare(hits, readBaseline(BASELINE), KINDS, UPDATE), written: [] }
}
