// Generates app/src/test/resources/fixtures/cuelume-reference.json: what the real Cuelume sounds
// like, so `CuelumeFidelityTest` can hold the Kotlin port to it. Each case is rendered by Cuelume's
// own `renderRecipe` and output stage in a spec-compliant Web Audio implementation
// (node-web-audio-api, offline), with `Math.random` pinned to 0.5 so a play is exactly repeatable,
// and reduced to its loudness in 20 ms windows. Run from the repo root:
//   node android/scripts/gen-cuelume-reference.mjs
// It downloads cuelume and node-web-audio-api into a temporary folder; nothing is added to the repo's
// dependencies.
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const CUELUME = '0.2.8'
const WEB_AUDIO = '2.2.0'
const RATE = 48000
const WINDOW = 0.02
const root = new URL('../../', import.meta.url).pathname

const work = mkdtempSync(join(tmpdir(), 'cuelume-reference-'))
const run = (cmd, args) => execFileSync(cmd, args, { cwd: work, stdio: 'pipe' })
run('npm', ['pack', `cuelume@${CUELUME}`, '--silent'])
run('tar', ['xzf', `cuelume-${CUELUME}.tgz`])
run('npm', ['init', '-y'])
run('npm', ['install', `node-web-audio-api@${WEB_AUDIO}`, '--silent'])

const load = async path => import(pathToFileURL(join(work, path)).href)
const { OfflineAudioContext } = await load('node_modules/node-web-audio-api/index.js')
const { THEMES } = await load('package/dist/sounds/themes.js')
const { contextFrom, resolveEmphasis, shapeFor } = await load('package/dist/sounds/context.js')
const { createOutput } = await load('package/dist/audio/output.js')
const { renderRecipe } = await load('package/dist/audio/render.js')

Math.random = () => 0.5

const cues = ['tap', 'type', 'select', 'toggle', 'open', 'close', 'success', 'error', 'navigate', 'warning', 'loading', 'ready', 'attention', 'count']
const cases = []
const add = c => cases.push({ emphasis: 'normal', options: {}, since: null, step: 0, part: 'both', volume: 1, ...c })
for (const theme of Object.keys(THEMES)) {
  for (const cue of cues)
    add({ id: `${theme}-${cue}`, theme, cue })
}
for (const cue of cues) {
  for (const emphasis of ['subtle', 'strong'])
    add({ id: `default-${cue}-${emphasis}`, theme: 'default', cue, emphasis })
}
add({ id: 'select-forward', theme: 'default', cue: 'select', options: { direction: 'forward' } })
add({ id: 'select-back', theme: 'default', cue: 'select', options: { direction: 'back' } })
add({ id: 'toggle-back', theme: 'default', cue: 'toggle', options: { direction: 'back' } })
add({ id: 'navigate-back', theme: 'default', cue: 'navigate', options: { direction: 'back' } })
add({ id: 'count-1500', theme: 'default', cue: 'count', options: { duration: 1500 } })
add({ id: 'count-back', theme: 'default', cue: 'count', options: { direction: 'back', duration: 600 } })
add({ id: 'tap-touch', theme: 'default', cue: 'tap', options: { input: 'touch' }, emphasis: 'subtle' })
add({ id: 'tap-pen', theme: 'default', cue: 'tap', options: { input: 'pen' } })
add({ id: 'type-delete', theme: 'default', cue: 'type', options: { key: 'delete' } })
add({ id: 'type-enter', theme: 'default', cue: 'type', options: { key: 'enter' } })
add({ id: 'tap-fast', theme: 'default', cue: 'tap', since: 100 })
add({ id: 'select-fast', theme: 'mech', cue: 'select', since: 40 })
add({ id: 'press-tap-step1', theme: 'press', cue: 'tap', since: 300, step: 1 })
add({ id: 'press-tap-step2', theme: 'press', cue: 'tap', since: 300, step: 2 })
add({ id: 'press-tap-press', theme: 'press', cue: 'tap', part: 'press' })
add({ id: 'press-tap-release', theme: 'press', cue: 'tap', part: 'release' })
add({ id: 'press-select-both', theme: 'press', cue: 'select' })
add({ id: 'tap-quiet', theme: 'default', cue: 'tap', volume: 0.4 })
add({ id: 'bubble-success-strong', theme: 'bubble', cue: 'success', emphasis: 'strong' })

const dbs = []
for (const c of cases) {
  const seconds = c.theme === 'press' || ['attention', 'success'].includes(c.cue) ? 3.2 : 2.4
  const context = new OfflineAudioContext(2, Math.ceil(seconds * RATE), RATE)
  let bus = null
  const recipe = THEMES[c.theme][c.cue]
  const emphasis = resolveEmphasis(c.emphasis)
  const played = { recipe, volume: c.volume, emphasis, shape: shapeFor(c.cue, contextFrom(c.options), emphasis, c.since ?? Infinity), step: c.step }
  renderRecipe(context, () => (bus ??= createOutput(context)), new Map(), c.cue, played, c.part)
  const buffer = await context.startRendering()
  const left = buffer.getChannelData(0)
  const right = buffer.getChannelData(1)
  const size = Math.round(WINDOW * RATE)
  const windows = []
  let peak = 0
  for (let at = 0; at + size <= left.length; at += size) {
    let sum = 0
    for (let i = at; i < at + size; i++) {
      sum += left[i] * left[i] + right[i] * right[i]
      peak = Math.max(peak, Math.abs(left[i]), Math.abs(right[i]))
    }
    windows.push(Math.max(-90, Math.round(10 * 20 * Math.log10(Math.sqrt(sum / (2 * size)) || 1e-9)) / 10))
  }
  while (windows.length > 1 && windows.at(-1) <= -80)
    windows.pop()
  dbs.push({ ...c, peak: Number(peak.toFixed(4)), frames: left.length, windowsDb: windows })
}

const lines = dbs.map(c => `  ${JSON.stringify(c)}`)
writeFileSync(
  join(root, 'android/app/src/test/resources/fixtures/cuelume-reference.json'),
  `{\n"source": "cuelume ${CUELUME} rendered by node-web-audio-api ${WEB_AUDIO}, Math.random pinned to 0.5, ${WINDOW * 1000} ms RMS windows in dB",\n"cases": [\n${lines.join(',\n')}\n]\n}\n`,
)
console.log(`Wrote ${dbs.length} cases`)
