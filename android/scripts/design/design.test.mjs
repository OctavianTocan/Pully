// Tests of the design checks themselves: the Kotlin readers, then each check on small fixtures that
// break one rule at a time, then the whole suite on the real repository.
import assert from 'node:assert/strict'
import { describe, it } from 'vitest'
import * as bans from './bans.mjs'
import * as book from './book.mjs'
import { checkChangelog, compareVersions, parseChangelog, versionName } from './changelog.mjs'
import { run } from './check.mjs'
import { blank, composables, dpScale, excusedLines, matching, paletteColors, splitTopLevel, typeRoles } from './kotlin.mjs'
import * as motion from './motion.mjs'
import { compare, countsOf } from './ratchet.mjs'
import * as rawValues from './raw-values.mjs'
import { checkChecklist, checkRules } from './rules.mjs'
import { buildSpec, parseDesign, parseRules } from './spec.mjs'
import { compareColors, compareScale, compareShadows, compareTypography, neutralDarkProblems } from './tokens.mjs'
import { componentProblems, localeProblems, pruneLocaleCopies, webTokens } from './web.mjs'

const FRONT = {
  typography: { 'xs': { fontSize: '13px' }, 'xs-wide': { fontSize: '12px' }, 'sm': { fontSize: '15px' } },
}

describe('kotlin readers', () => {
  it('blanks comments and strings but keeps offsets and lines', () => {
    const source = 'val a = "Color(0xFF000000)" // Color(0xFF111111)\n/* nested /* Color(0xFF222222) */ */ val b = 1\n'
    const code = blank(source)
    assert.equal(code.length, source.length)
    assert.equal(code.split('\n').length, source.split('\n').length)
    assert.ok(!code.includes('Color'))
    assert.ok(code.includes('val b = 1'))
    assert.ok(blank(source, { keepStrings: true }).includes('"Color(0xFF000000)"'))
  })

  it('finds matching brackets and splits at top-level commas only', () => {
    const code = 'f(a, g(b, c), { d, e })'
    assert.equal(matching(code, 1), code.length - 1)
    assert.deepEqual(splitTopLevel('a: List<Pair<A, B>>, b: (Int, Int) -> Unit = {}, c: Int'), ['a: List<Pair<A, B>>', 'b: (Int, Int) -> Unit = {}', 'c: Int'])
  })

  it('excuses the line and the one after an excusing comment', () => {
    const lines = excusedLines('a\n// raw-value-ok: scrim\nb\nc // raw-value-ok: x\nd\n', 'raw-value-ok')
    assert.deepEqual([...lines].sort(), [2, 3, 4])
    assert.equal(excusedLines('// raw-value-ok:\nb\n', 'raw-value-ok').size, 0)
  })

  it('lists composables with props, defaults, visibility and docs', () => {
    const source = [
      '/** A pill. */',
      '@Composable',
      'fun Pill(label: String, modifier: Modifier = Modifier, items: List<Pair<A, B>> = emptyList(), onClick: () -> Unit = {}) {',
      '    Text(label)',
      '}',
      '',
      '@Composable private fun Hidden() {}',
      '',
      '@Composable',
      'fun Modifier.glow(shape: Shape, elevation: Dp = 8.dp): Modifier = this',
      '',
      'fun notComposable() {}',
      '',
      'fun outer() {',
      '    @Composable',
      '    fun nested() {}',
      '}',
    ].join('\n')
    const found = composables(source, 'Kit.kt')
    assert.deepEqual(found.map(c => c.name), ['Pill', 'Hidden', 'glow', 'nested'])
    const pill = found[0]
    assert.equal(pill.doc, 'A pill.')
    assert.equal(pill.top, true)
    assert.deepEqual(pill.params.map(p => p.name), ['label', 'modifier', 'items', 'onClick'])
    assert.equal(pill.params[1].default, 'Modifier')
    assert.equal(pill.params[2].type, 'List<Pair<A, B>>')
    assert.ok(pill.source.startsWith('@Composable\nfun Pill('))
    assert.equal(found[1].private, true)
    assert.equal(found[2].receiver, 'Modifier')
    assert.equal(found[3].top, false)
  })

  it('reads palettes, type roles with their wide twin, and dp scales', () => {
    const tokens = [
      'val LightPullsColors = PullsColors(dark = false, base = Color(0xFFFDFCF9), scrim = Color(0x99000000), accentTeal = Color(0xFF24929C))',
      'object PullsType {',
      '  val sm: TextStyle get() = if (wide) TextStyle(fontFamily = PullsFonts.sans, fontSize = 14.sp, lineHeight = 20.sp) else TextStyle(fontFamily = PullsFonts.sans, fontSize = 15.sp, lineHeight = 21.sp)',
      '  val xxl = TextStyle(fontFamily = PullsFonts.sans, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.26).sp)',
      '}',
    ].join('\n')
    assert.deepEqual(paletteColors(tokens, 'LightPullsColors'), { 'base': '#FDFCF9', 'scrim': '#00000099', 'accent-teal': '#24929C' })
    const roles = typeRoles(tokens)
    assert.equal(roles.sm.fontSize, '15px')
    assert.equal(roles['sm-wide'].fontSize, '14px')
    assert.equal(roles.xxl.letterSpacing, '-0.26px')
    assert.equal(roles.xxl.fontWeight, 500)
    assert.deepEqual(dpScale('object S {\n val x1 = 4.dp\n val x2: Dp = 8.dp\n}', 'S'), { x1: '4px', x2: '8px' })
    assert.equal(dpScale('object S {}', 'Missing'), null)
  })
})

describe('ratchet', () => {
  const kinds = { hex: { label: 'raw colours', rules: [47], advice: 'Use a token.' } }

  it('passes at the baseline and fails on a rise or a fall', () => {
    assert.deepEqual(compare({ hex: { 'a.kt': [1, 2] } }, { hex: { 'a.kt': 2 } }, kinds, 'update'), [])
    assert.match(compare({ hex: { 'a.kt': [1, 2, 3] } }, { hex: { 'a.kt': 2 } }, kinds, 'update')[0], /a\.kt: 3 raw colours, baseline 2/)
    assert.match(compare({ hex: { 'a.kt': [1] } }, { hex: { 'a.kt': 2 } }, kinds, 'update')[0], /fell from 2 to 1.*`update`/)
    assert.match(compare({ hex: { 'b.kt': [4] } }, {}, kinds, 'update')[0], /b\.kt: 1 raw colours, baseline 0/)
    assert.match(compare({}, { hex: { 'gone.kt': 1 } }, kinds, 'update')[0], /fell from 1 to 0/)
  })

  it('counts per kind and file and leaves zeros out', () => {
    assert.deepEqual(countsOf({ hex: { 'b.kt': [1], 'a.kt': [1, 2], 'c.kt': [] }, radius: {} }), { hex: { 'a.kt': 2, 'b.kt': 1 } })
  })
})

describe('raw-values', () => {
  const scan = source => rawValues.scan({ 'ui/x/Screen.kt': source }, FRONT)
  const lines = (hits, kind) => hits[kind]['ui/x/Screen.kt'] ?? []

  it('counts raw colours, sizes and literal radii but not tokens or radius tokens', () => {
    const hits = scan([
      'val a = Color(0xFF123456)',
      'val b = Color.White',
      'val c = c.accentTeal',
      'val d = RoundedCornerShape(12.dp)',
      'val e = RoundedCornerShape(PullsRadius.card)',
      'val f = RoundedCornerShape(r.dp)',
      'val g = RoundedCornerShape(topStart = 24.dp, topEnd = 0.dp)',
      'val h = TextStyle(fontSize = 17.sp)',
    ].join('\n'))
    assert.deepEqual(lines(hits, 'hex'), [1, 2])
    assert.deepEqual(lines(hits, 'radius'), [4, 7])
    assert.deepEqual(lines(hits, 'fontSize'), [8])
  })

  it('reads the radius from live code, not from a comment inside the call', () => {
    const hits = scan('val a = RoundedCornerShape(PullsRadius.card /* 16.dp */)\nval b = RoundedCornerShape(PullsRadius.card, // 12.dp\n)')
    assert.deepEqual(hits.radius, {})
  })

  it('counts text under 15 by role and by raw size, uppercase and tracked text', () => {
    const hits = scan([
      'Text(a, style = PullsType.xs)',
      'Text(a, style = PullsType.sm)',
      'Text(a, style = PullsType.base.copy(fontSize = 12.sp))',
      'Text(a.uppercase())',
      'Text(a, style = TextStyle(letterSpacing = 0.5.sp))',
      'Text(a, style = TextStyle(letterSpacing = (-0.26).sp))',
    ].join('\n'))
    assert.deepEqual(lines(hits, 'smallText'), [1, 3])
    assert.deepEqual(lines(hits, 'uppercase'), [4, 5])
  })

  it('allows .shadow only inside floatShadow, and honours the opt-out comment', () => {
    const hits = scan([
      '@Composable',
      'fun Modifier.floatShadow(shape: Shape, elevation: Dp = 8.dp): Modifier {',
      '    return this.shadow(elevation, shape)',
      '}',
      'val x = Modifier.shadow(2.dp)',
      '// raw-value-ok: the switch thumb is 2dp by the canvas',
      'val y = Modifier.shadow(2.dp)',
      'val z = Color(0xFF000000) // raw-value-ok: scrim',
    ].join('\n'))
    assert.deepEqual(lines(hits, 'shadow'), [5])
    assert.deepEqual(hits.hex['ui/x/Screen.kt'] ?? [], [])
  })

  it('ignores the files that define the tokens and anything inside comments and strings', () => {
    const hits = rawValues.scan({ 'ui/design/Tokens.kt': 'val a = Color(0xFF000000)', 'ui/x/A.kt': '// Color(0xFF000000)\nval s = "Color.White"' }, FRONT)
    assert.deepEqual(countsOf(hits), {})
  })
})

describe('bans', () => {
  const scan = (source, file = 'ui/x/Screen.kt') => countsOf(bans.scan({ [file]: source }))

  it('bans Material widgets the kit replaces but not Text or Icon', () => {
    assert.deepEqual(scan('import androidx.compose.material3.Switch\nimport androidx.compose.material3.Text\nimport androidx.compose.material3.Icon\nimport androidx.compose.material3.*'), { 'material-component': { 'ui/x/Screen.kt': 2 } })
  })

  it('keeps menus in the kit and sheets and pull to refresh out everywhere', () => {
    const imports = 'import androidx.compose.material3.ModalBottomSheet\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.pulltorefresh.PullToRefreshBox'
    assert.deepEqual(scan(imports), { 'material-pull-refresh': { 'ui/x/Screen.kt': 1 }, 'material-sheet': { 'ui/x/Screen.kt': 1 }, 'material-sheet-menu': { 'ui/x/Screen.kt': 1 } })
    assert.deepEqual(scan(imports.split('\n').slice(0, 2).join('\n'), 'ui/design/Kit.kt'), { 'material-sheet': { 'ui/design/Kit.kt': 1 } })
    assert.deepEqual(scan('import androidx.compose.material3.DropdownMenu', 'ui/design/Kit.kt'), {})
  })

  it('bans icons, speech, outgoing share and stat tiles', () => {
    const found = scan([
      'import androidx.compose.material.icons.filled.Share',
      'import android.speech.SpeechRecognizer',
      'startActivity(Intent.createChooser(intent, null))',
      '@Composable fun StatTile() {}',
    ].join('\n'))
    assert.deepEqual(Object.keys(found).sort(), ['material-icons', 'share-out', 'speech', 'stat-tile'])
  })

  it('bans link-out icons by glyph and by Phosphor name, but not the GitHub mark', () => {
    const found = scan('LuIcon(Lu.ext)\nPhIcon("arrow-square-out-bold")\nIcon(Icons.Filled.OpenInNew, null)\nDrawableIcon(R.drawable.ic_carbon_logo_github)\nval next = Lu.extend')
    assert.deepEqual(found['external-link-icon'], { 'ui/x/Screen.kt': 3 })
  })

  it('counts calls to frozen kits outside the file that defines them, not their declarations', () => {
    const found = scan('ActionButton("a") {}\nval x = MyBadge(1)\nPhIcon("check")\nLoading("wait")\nfun Card() {}')
    assert.deepEqual(found, { 'legacy-icons': { 'ui/x/Screen.kt': 1 }, 'legacy-kit': { 'ui/x/Screen.kt': 1 }, 'waiting': { 'ui/x/Screen.kt': 1 } })
    assert.deepEqual(scan('ActionButton("a") {}', 'ui/design/Components.kt'), {})
  })

  it('counts a call spelled out with the kit package, not a call on some other receiver', () => {
    const found = scan('com.octaviantocan.pulls.ui.design.ActionButton({}) {}\ncom.octaviantocan.pulls.ui.design.PhIcon("check")\nother.ActionButton({})\nval x = com.octaviantocan.pulls.ui.design.ButtonVariant.Primary')
    assert.deepEqual(found, { 'legacy-icons': { 'ui/x/Screen.kt': 1 }, 'legacy-kit': { 'ui/x/Screen.kt': 1 } })
  })

  it('counts Material names spelled out where they are used, once, with no import', () => {
    const found = scan('androidx.compose.material3.HorizontalDivider(color = c)\nandroidx.compose.material3.Text("ok")\nandroidx.compose.material3.ModalBottomSheet({}) {}\nval s = android.speech.RecognizerIntent.ACTION')
    assert.deepEqual(found, { 'material-component': { 'ui/x/Screen.kt': 1 }, 'material-sheet': { 'ui/x/Screen.kt': 1 }, 'speech': { 'ui/x/Screen.kt': 2 } })
    assert.deepEqual(scan('import androidx.compose.material3.Switch\nandroidx.compose.material3.Switch(true, {})'), { 'material-component': { 'ui/x/Screen.kt': 2 } })
  })

  it('counts the generic AI glyphs of rule 48, in code and in icon names but not in comments', () => {
    const found = scan([
      'LuIcon(Lu.bot)',
      'LuIcon(Lu.ask, tint = c.muted)',
      'PhIcon("sparkle-duotone")',
      'ActionButton(icon = "compass-duotone")',
      'Glyph("chat-circle-dots-duotone")',
      '// "sparkle-duotone" and Lu.bot are gone',
      'PhIcon("compass-tool-duotone")',
      'val ok = obj["chat"]',
      'val asked = Lu.asked',
    ].join('\n'))
    assert.deepEqual(found['ai-glyph'], { 'ui/x/Screen.kt': 5 })
    assert.deepEqual(scan('// ban-ok: the one AI mark\nPhIcon("sparkle-duotone")')['ai-glyph'], undefined)
  })

  it('lets a line opt out with a reason, and not without one', () => {
    assert.deepEqual(scan('// ban-ok: legacy screen, being replaced\nActionButton("a") {}'), {})
    assert.deepEqual(Object.keys(scan('// ban-ok:\nActionButton("a") {}')), ['legacy-kit'])
  })

  it('keeps Compose\'s text fields in the kit\'s one field (N6)', () => {
    const source = [
      'BasicTextField(value = v, onValueChange = {})',
      'OutlinedTextField(v, {})',
      'FieldText(v, {}, "Find")',
      'SheetSearch(v, {}, "Find a file")',
      'BasicTextField(v, {}) // ban-ok: edited where it stands',
    ].join('\n')
    assert.deepEqual(scan(source)['text-field'], { 'ui/x/Screen.kt': 2 })
    assert.deepEqual(scan('BasicTextField(value = v, onValueChange = {})', 'ui/design/Fields.kt')['text-field'], undefined)
    assert.deepEqual(scan('BasicTextField(value = v, onValueChange = {})', 'ui/design/Kit.kt')['text-field'], { 'ui/design/Kit.kt': 1 })
  })

  it('declares the rules DESIGN.md lists for it', () => {
    assert.deepEqual(bans.RULES, [1, 5, 8, 10, 11, 22, 28, 38, 48, 49, 51, 52, 73, 100])
  })
})

describe('motion', () => {
  const scan = (source, file = 'ui/x/Screen.kt') => motion.scan({ [file]: source })

  it('keeps Compose\'s own slides and pagers out of the screens, not out of the kit', () => {
    const source = [
      'import androidx.compose.animation.slideInHorizontally',
      'val enter = slideInHorizontally { it }',
      'val exit = slideOutOfContainer(SlideDirection.Left)',
      'HorizontalPager(state) { page -> Page(page) }',
      '// slideInHorizontally in a comment is fine',
    ].join('\n')
    const found = scan(source)
    assert.equal(found.length, 4)
    assert.ok(found[0].startsWith('ui/x/Screen.kt:2: slideInHorizontally'))
    assert.ok(found.every(p => p.endsWith('(rule 105)')))
    assert.deepEqual(scan('val enter = slideInHorizontally(tween(250)) { way * it }', 'ui/design/Motion.kt'), [])
  })

  it('makes the kit\'s slides take their offset from the way', () => {
    const found = scan('val a = slideInHorizontally(spec) { way * travel }\nval b = slideOutHorizontally(spec) { -travel }\nval c = slideInVertically(initialOffsetY = { -way * it })', 'ui/design/Motion.kt')
    assert.deepEqual(found.map(p => p.split(':').slice(0, 2).join(':')), ['ui/design/Motion.kt:2'])
  })

  it('asks every page change for its order by name', () => {
    const found = scan([
      'BlurSwitch(tab, Modifier.weight(1f), order = { it }) { Page(it) }',
      'BlurSwitch(tab) { Page(it) }',
      'LabelSwitch(index, order = { it }) { Text(names[it]) }',
      'val slide = rememberPageSlide(shown)',
      'val other = rememberPageSlide(shown, order = { it })',
      'Foo.BlurSwitch(tab) {}',
    ].join('\n'))
    assert.deepEqual(found.map(p => p.split(':')[1]), ['2', '4'])
  })

  it('wants order required on the kit\'s page changes', () => {
    const kit = [
      '@Composable',
      'fun <S> BlurSwitch(target: S, modifier: Modifier = Modifier, order: (S) -> Int = { 0 }, content: @Composable (S) -> Unit) {}',
      '',
      '@Composable',
      'fun <S> LabelSwitch(target: S, content: @Composable (S) -> Unit) {}',
      '',
      '@Composable',
      'fun <T> rememberPageSlide(target: T, order: (T) -> Int): PageSlide<T> = TODO()',
    ].join('\n')
    const { problems, found } = motion.declarationProblems({ 'ui/design/Motion.kt': kit })
    assert.deepEqual([...found].sort(), ['BlurSwitch', 'LabelSwitch', 'rememberPageSlide'])
    assert.equal(problems.length, 2)
    assert.ok(problems[0].includes('BlurSwitch\'s `order` has a default'))
    assert.ok(problems[1].includes('LabelSwitch takes no `order`'))
  })

  it('sends Back through PageBack, unless a line says why not', () => {
    assert.equal(scan('BackHandler { close() }\nPredictiveBackHandler { events -> }').length, 2)
    assert.deepEqual(scan('// motion-ok: puts the old text back; nothing moves\nBackHandler { editing = false }'), [])
    assert.equal(scan('// motion-ok:\nBackHandler { editing = false }').length, 1)
    assert.deepEqual(scan('PredictiveBackHandler(enabled) { events -> }', 'ui/design/Motion.kt'), [])
  })

  it('declares the rule DESIGN.md lists for it', () => {
    assert.deepEqual(motion.RULES, [105])
  })
})

describe('tokens', () => {
  it('compares colours both ways and wants a dark pair', () => {
    const front = { colors: { 'base': '#FFFFFF', 'dark-base': '#000000', 'muted': '#888888' } }
    const problems = compareColors(front, { base: '#FFFFFF', muted: '#999999' }, { base: '#000000', extra: '#111111' })
    assert.ok(problems.some(p => p.includes('colors.muted has no dark pair')))
    assert.ok(problems.some(p => p.includes('dark palette has extra')))
    assert.ok(problems.some(p => p.includes('colors.muted: DESIGN.md #888888, Tokens.kt #999999')))
    assert.deepEqual(compareColors({ colors: { 'a': '#ABCDEF', 'dark-a': '#012345' } }, { a: '#abcdef' }, { a: '#012345' }), [])
  })

  it('wants the dark surfaces to be neutral greys', () => {
    const neutral = Object.fromEntries(['base', 'text', 'muted', 'faint', 'border', 'border-mute', 'raised', 'sunken', 'code', 'secondary', 'gray', 'paper', 'paper-2', 'cta', 'on-cta', 'chip-on'].map(n => [`dark-${n}`, '#1D1D1D']))
    assert.deepEqual(neutralDarkProblems({ colors: neutral }), [])
    assert.deepEqual(neutralDarkProblems({ colors: { ...neutral, 'dark-raised': '#1D1D1F' } }), [])
    const cast = neutralDarkProblems({ colors: { ...neutral, 'dark-base': '#0C1B1F' } })
    assert.equal(cast.length, 1)
    assert.match(cast[0], /colors\.dark-base is #0C1B1F, which has a colour cast/)
    assert.match(neutralDarkProblems({ colors: {} })[0], /not a six digit hex colour/)
  })

  it('compares every field of a type role and treats missing tracking as zero', () => {
    const spec = { fontFamily: 'Geist', fontSize: '15px', fontWeight: 400, lineHeight: '21px' }
    const role = { fontFamily: 'Geist', fontSize: '15px', fontWeight: 400, lineHeight: '21px' }
    assert.deepEqual(compareTypography({ typography: { sm: spec } }, { sm: role }), [])
    assert.deepEqual(compareTypography({ typography: { sm: spec } }, { sm: { ...role, fontSize: '16px', letterSpacing: '1px' } }), [
      'typography.sm.fontSize: DESIGN.md 15px, PullsType 16px',
      'typography.sm.letterSpacing: DESIGN.md 0px, PullsType 1px',
    ])
    assert.equal(compareTypography({ typography: { sm: spec } }, {}).length, 1)
  })

  it('compares a scale with its Kotlin object, mapping x1 to 1', () => {
    assert.deepEqual(compareScale('spacing', { 1: '4px', 2: '8px' }, { x1: '4px', x2: '8px' }, 'PullsSpace', n => n.replace(/^x/, '')), [])
    assert.equal(compareScale('spacing', { 1: '4px', 2: '8px' }, { x1: '4px', x2: '9px', x3: '12px' }, 'PullsSpace', n => n.replace(/^x/, '')).length, 2)
    assert.match(compareScale('rounded', { sm: '4px' }, null, 'PullsRadius')[0], /no object PullsRadius/)
  })
})

describe('dESIGN.md', () => {
  const doc = [
    '---',
    'colors:',
    '  base: "#FFFFFF"',
    '  dark-base: "#000000"',
    'typography:',
    '  sm:',
    '    fontFamily: Geist',
    '    fontSize: 15px',
    '    fontWeight: 400',
    '    lineHeight: 21px',
    'components:',
    '  float-button:',
    '    size: 44px',
    '    rounded: "{rounded.full}"',
    'rounded:',
    '  full: 9999px',
    '---',
    '',
    '## Rules',
    '',
    '### One',
    '',
    '#### 1. First.',
    '',
    'Text one, see rule 2.',
    '',
    'Source: canvas. Checked by: bans, review.',
    '',
    '#### 2. Second.',
    '',
    'Text two.',
    '',
    'Source: walkthrough. Checked by: review.',
    '',
    '## Checklist',
    '',
    '1. One',
    '2. Two',
    '',
    '## Enforcement',
    '',
  ].join('\n')

  it('parses the front matter and the rules with their footers', () => {
    const parsed = parseDesign(doc)
    assert.equal(parsed.rules.length, 2)
    assert.deepEqual(parsed.rules[0], { n: 1, title: 'First.', source: 'canvas', checkedBy: ['bans', 'review'], text: 'Text one, see rule 2.' })
    assert.deepEqual(parseRules(parsed.body).map(r => r.n), [1, 2])
    const spec = buildSpec(parsed.front)
    assert.deepEqual(spec.colors.base, { light: '#FFFFFF', dark: '#000000' })
    assert.deepEqual(spec.components['float-button'], { size: 44, width: 44, height: 44, rounded: 9999, background: null, color: null, typography: null })
  })

  it('wants contiguous numbers, a source and checks that exist and claim the rule back', () => {
    const parsed = parseDesign(doc)
    assert.deepEqual(checkRules(parsed, { bans: [1] }), [])
    assert.ok(checkRules(parsed, { bans: [1, 2] }).some(p => p.includes('bans enforces rule 2')))
    assert.ok(checkRules(parsed, { bans: [] }).some(p => p.includes('rule 1 says bans checks it')))
    assert.ok(checkRules(parsed, { bans: [1], tokens: [3] }).some(p => p.includes('tokens enforces rule 3, which DESIGN.md does not have')))
    const broken = parseDesign(doc.replace('#### 2. Second.', '#### 3. Second.').replace('Checked by: review.', 'Checked by: lint.').replace('See rule 2', 'x'))
    const problems = checkRules(broken, { bans: [1] })
    assert.ok(problems.some(p => p.includes('rule 3 is in position 2')))
    assert.ok(problems.some(p => p.includes('"lint"')))
    assert.ok(problems.some(p => p.includes('mentions rule 2, but the rules run 1 to 2') === false))
  })

  it('wants a contiguous numbered checklist', () => {
    assert.deepEqual(checkChecklist(parseDesign(doc).body), [])
    assert.ok(checkChecklist(parseDesign(doc.replace('2. Two', '3. Two')).body)[0].includes('item 3'))
  })
})

describe('book', () => {
  const component = { composable: 'Pill', summary: 'A pill.', usage: ['Use it.'], rules: [1], variants: [{ id: 'default', title: 'Default', default: true }] }
  const good = { pages: [{ id: 'foundations/colors', title: 'Colors', group: 'Foundations' }, { id: 'components/pill', title: 'Pill', group: 'Components', component: 'pill' }], components: { pill: component } }
  const kit = '@Composable\nfun Pill(label: String) {}\n'
  const sources = {
    'ui/design/Kit.kt': kit,
    'ui/book/BookRoutes.kt': 'fun BookRoute(id: String) {\n  when (id) {\n    "foundations/colors" -> ColorsPage()\n  }\n}\n',
    'ui/book/BookPreviews.kt': 'fun bookPreviews(id: String) = when (id) {\n    "pill" -> pillVariants()\n    else -> emptyList()\n}\n\nprivate fun pillVariants() = listOf(\n    BookVariant("default", listOf(BookState("Rest") { Pill("a") })),\n)\n',
    'ui/book/BookSpecs.kt': 'private val SPECIMENS = listOf(\n    Specimen("pill") { Pill("a") },\n)\n',
  }
  const front = { components: { pill: { height: '44px' } } }
  const kotlin = (b = good, s = sources, e = {}) => book.checkBookKotlin(b, s, front, e)

  it('passes a consistent book', () => {
    assert.deepEqual(book.checkBookShape(good, new Set([1])), [])
    assert.deepEqual(kotlin(), [])
  })

  it('checks ids, defaults, rules and pages in book.json', () => {
    const bad = structuredClone(good)
    bad.components.pill.variants = [{ id: 'a', title: 'A' }, { id: 'a', title: 'B', default: true }]
    bad.components.pill.rules = [99]
    bad.pages.push({ id: 'colors', title: 'Colors', group: 'Foundations' }, { id: 'Bad Id', title: 'x', group: 'y' })
    const problems = book.checkBookShape(bad, new Set([1]))
    assert.ok(problems.some(p => p.includes('lists variant "a" twice')))
    assert.ok(problems.some(p => p.includes('cites rule 99')))
    assert.ok(problems.some(p => p.includes('"Bad Id"')))
    const none = structuredClone(good)
    none.components.pill.variants[0].default = false
    assert.ok(book.checkBookShape(none, new Set([1])).some(p => p.includes('0 default variants')))
  })

  it('fails a page with no route and a route with no page', () => {
    const noRoute = { ...sources, 'ui/book/BookRoutes.kt': 'fun BookRoute(id: String) {\n  when (id) {\n    "foundations/other" -> Other()\n  }\n}\n' }
    const problems = kotlin(good, noRoute)
    assert.ok(problems.some(p => p.includes('no route for page "foundations/colors"')))
    assert.ok(problems.some(p => p.includes('routes "foundations/other"')))
  })

  it('fails a component with no preview, a preview with the wrong variants, and an unknown composable', () => {
    const wrong = { ...sources, 'ui/book/BookPreviews.kt': sources['ui/book/BookPreviews.kt'].replace('"default"', '"other"') }
    assert.ok(kotlin(good, wrong).some(p => p.includes('no BookVariant("default")')))
    assert.ok(kotlin(good, wrong).some(p => p.includes('BookVariant("other") that book.json does not list')))
    const none = { ...sources, 'ui/book/BookPreviews.kt': 'fun bookPreviews(id: String) = emptyList<BookVariant>()\n' }
    assert.ok(kotlin(good, none).some(p => p.includes('no preview for component "pill"')))
    const ghost = structuredClone(good)
    ghost.components.pill.composable = 'Ghost'
    assert.ok(kotlin(ghost).some(p => p.includes('Ghost, which is not a public composable')))
  })

  it('wants a page or a reason for every public composable, and drops stale reasons', () => {
    const extra = { ...sources, 'ui/design/Kit.kt': `${kit}\n@Composable\nfun Extra() {}\n\n@Composable\nprivate fun Hidden() {}\n` }
    assert.ok(kotlin(good, extra).some(p => p.includes('Extra has no UI Book page')))
    assert.ok(!kotlin(good, extra).some(p => p.includes('Hidden')))
    assert.deepEqual(kotlin(good, extra, { composables: { Extra: 'not visual' } }), [])
    assert.ok(kotlin(good, sources, { composables: { Pill: 'x' } }).some(p => p.includes('has a UI Book page. Remove the exemption')))
    assert.ok(kotlin(good, sources, { composables: { Gone: 'x' } }).some(p => p.includes('not a public composable')))
  })

  it('wants a specimen for every sized component token, or a reason', () => {
    const noSpecs = { ...sources, 'ui/book/BookSpecs.kt': 'private val SPECIMENS = emptyList<Specimen>()\n' }
    assert.ok(kotlin(good, noSpecs).some(p => p.includes('components.pill has a size and no Specimen')))
    assert.deepEqual(kotlin(good, noSpecs, { components: { pill: 'a window' } }), [])
    assert.ok(kotlin(good, sources, { components: { toast: 'x' } }).some(p => p.includes('not a sized DESIGN.md component')))
  })

  it('wants every Lu glyph on the Icons page under its own name', () => {
    const withIcons = {
      ...sources,
      'ui/design/Icons.kt': 'internal object Lu {\n  val cog = LuGlyph(1f)\n  val back = LuGlyph(1f)\n}\n',
      'ui/book/BookFoundations.kt': 'private val GLYPHS = listOf(\n  "cog" to Lu.cog,\n  "bak" to Lu.back,\n)\n',
    }
    const problems = kotlin(good, withIcons, {})
    assert.ok(problems.some(p => p.includes('labels Lu.back as "bak"')))
    assert.ok(!problems.some(p => p.includes('does not list Lu.cog')))
    assert.ok(kotlin(good, { ...withIcons, 'ui/book/BookFoundations.kt': 'val GLYPHS = listOf("cog" to Lu.cog)\n' }).some(p => p.includes('does not list Lu.back')))
  })
})

describe('web', () => {
  const front = {
    colors: { 'paper': '#fdfcf9', 'dark-paper': '#161616' },
    typography: { sm: { fontFamily: 'Geist', fontSize: '15px', lineHeight: '22px', fontWeight: 400 } },
    rounded: { md: '8px' },
    elevation: { button: '10px' },
    shadow: { button: { y: '2px', alpha: 0.08 } },
    motion: { slide: '250ms cubic-bezier(0.22, 1, 0.36, 1), the page', press: 'a flat tint' },
    components: { chip: { backgroundColor: '{colors.paper}', typography: '{typography.sm}', rounded: '{rounded.md}', height: '36px', padding: '0px 14px' } },
  }

  it('pairs colours by theme and reads sizes as px', () => {
    const tokens = webTokens(front)
    assert.deepEqual(tokens.colors.paper, { light: '#FDFCF9', dark: '#161616' })
    assert.equal(tokens.colors['dark-paper'], undefined)
    assert.deepEqual(tokens.shadow.button, { y: 2, blur: 10, alpha: 0.08 })
    assert.deepEqual(tokens.motion, { slide: { ms: 250, curve: 'cubic-bezier(0.22, 1, 0.36, 1)' } })
    assert.deepEqual(tokens.components.chip, { background: 'paper', typography: 'sm', rounded: 'md', height: 36, padding: { y: 0, x: 14 } })
  })

  it('finds a component naming a token that does not exist', () => {
    const tokens = webTokens({ ...front, components: { chip: { backgroundColor: '{colors.ink}', rounded: '{rounded.xl}' } } })
    const problems = componentProblems(tokens)
    assert.equal(problems.length, 2)
    assert.match(problems[0], /colors\.ink/)
    assert.match(problems[1], /rounded\.xl/)
  })

  it('finds a phone string copy that drifted from the web', async () => {
    const { mkdtempSync, mkdirSync, writeFileSync } = await import('node:fs')
    const { tmpdir } = await import('node:os')
    const { join } = await import('node:path')
    const root = mkdtempSync(join(tmpdir(), 'locales-'))
    mkdirSync(join(root, 'web'))
    mkdirSync(join(root, 'android'))
    writeFileSync(join(root, 'web', 'en.json'), '{"a":"A"}\n')
    writeFileSync(join(root, 'web', 'de.json'), '{"a":"A"}\n')
    writeFileSync(join(root, 'android', 'en.json'), '{"a":"B"}\n')
    writeFileSync(join(root, 'android', 'fr.json'), '{"a":"A"}\n')
    const problems = localeProblems(join(root, 'web'), join(root, 'android'))
    assert.equal(problems.length, 3)
    assert.ok(problems.some(p => p.includes('de.json is missing')))
    assert.ok(problems.some(p => p.includes('en.json differs')))
    assert.ok(problems.some(p => p.includes('fr.json has no web locale')))
  })

  it('removes the phone\'s copy of a locale the web dropped', async () => {
    const { existsSync, mkdtempSync, mkdirSync, writeFileSync } = await import('node:fs')
    const { tmpdir } = await import('node:os')
    const { join } = await import('node:path')
    const root = mkdtempSync(join(tmpdir(), 'locales-'))
    mkdirSync(join(root, 'web'))
    mkdirSync(join(root, 'android'))
    writeFileSync(join(root, 'web', 'en.json'), '{}\n')
    writeFileSync(join(root, 'android', 'en.json'), '{}\n')
    writeFileSync(join(root, 'android', 'fr.json'), '{}\n')
    assert.deepEqual(pruneLocaleCopies(join(root, 'web'), join(root, 'android')), [join(root, 'android', 'fr.json')])
    assert.ok(existsSync(join(root, 'android', 'en.json')))
    assert.ok(!existsSync(join(root, 'android', 'fr.json')))
    assert.deepEqual(localeProblems(join(root, 'web'), join(root, 'android')), [])
  })

  it('compares shadows with PullsShadow both ways', () => {
    const levels = { button: { y: '2.dp', alpha: '0.08', blur: 'button' }, extra: { y: '1.dp', alpha: '0.1', blur: 'extra' } }
    const layer = { 'edge-y': '1.dp' }
    const problems = compareShadows({ 'shadow': { button: { y: '3px', alpha: 0.08 } }, 'shadow-layer': { 'edge-y': '1px' } }, levels, layer)
    assert.deepEqual(problems, ['shadow.button.y: DESIGN.md 3px, PullsShadow 2.dp', 'PullsShadow level extra is not in DESIGN.md shadow'])
  })
})

describe('the repository', () => {
  it('passes every design check', () => {
    const { results } = run()
    for (const result of results)
      assert.deepEqual(result.problems, [], `${result.name} has problems`)
  })
})

describe('changelog', () => {
  const text = [
    '# What\'s new',
    '<!--',
    '## 9.9.9 sits in a comment',
    '-->',
    '## 0.10.0',
    '',
    'Pull requests slide in,',
    'and edit in place.',
    '',
    '- Edit a title in place.',
    '- Commits are plain lists',
    '  with whole titles.',
    '',
    '## 0.9.0',
    '',
    'Sheets rest at half height.',
    '',
    '* Profile hides a repository.',
  ].join('\n')

  it('reads sections, summaries and bullets the way the app does', () => {
    const entries = parseChangelog(text)
    assert.deepEqual(entries.map(e => e.version), ['0.10.0', '0.9.0'])
    assert.equal(entries[0].summary, 'Pull requests slide in, and edit in place.')
    assert.deepEqual(entries[0].items, ['Edit a title in place.', 'Commits are plain lists with whole titles.'])
    assert.equal(compareVersions('0.10.0', '0.9.2'), 1)
    assert.equal(compareVersions('1.2', '1.2.0'), 0)
    assert.equal(versionName('versionCode = 31\n        versionName = "0.17.0"'), '0.17.0')
  })

  it('passes when the app\'s version has a full section, newest first', () => {
    assert.deepEqual(checkChangelog(text, '0.10.0'), [])
  })

  it('fails on a version with no section, an empty section, or the wrong order', () => {
    assert.match(checkChangelog(text, '0.11.0').join('\n'), /no section for 0\.11\.0/)
    assert.match(checkChangelog(text, '0.9.0').join('\n'), /0\.10\.0, above the app's 0\.9\.0/)
    assert.match(checkChangelog('## 0.2.0\n\n- A change.\n', '0.2.0').join('\n'), /needs a summary/)
    assert.match(checkChangelog('## 0.2.0\n\nA summary.\n', '0.2.0').join('\n'), /at least one bullet/)
    assert.match(checkChangelog(`## 0.2.0\n\n${'A long summary. '.repeat(5)}\n\n- a\n`, '0.2.0').join('\n'), /Keep it to 70/)
    assert.match(checkChangelog('## 0.1.0\n\nOld.\n\n- a\n\n## 0.2.0\n\nNew.\n\n- b\n', '0.2.0').join('\n'), /Newest first/)
  })
})
