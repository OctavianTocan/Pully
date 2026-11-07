import { watch } from 'vue'
import palette from '../../../../android/app/src/main/assets/cuelume/recipes.json'
import { settings } from '../state/settings'

type Cue = 'tap' | 'select' | 'toggle' | 'success' | 'error' | 'ready' | 'warning' | 'loading' | 'attention'
type Material = typeof settings.value.soundMaterial
interface Layer {
  kind: string
  from?: string
  frequency?: number
  filterFrequency?: number
  filterType?: BiquadFilterType
  filterQ?: number
  glideTo?: number
  glideTime?: number
  offset?: number
  attack: number
  decay: number
  peak: number
  note?: boolean
  stretch?: boolean
}
interface Recipe {
  masterGain: number
  layers: Layer[]
  release?: Layer[]
  room?: number
  vary?: { pitch: number, level: number }
  walk?: number[]
}

let context: AudioContext | undefined
let output: GainNode | undefined
let room: ConvolverNode | undefined
let noise: AudioBuffer | undefined
const last = new Map<Cue, number>()
const steps = new Map<Cue, number>()
const voices = new Map<Cue, GainNode>()
const allVoices = new Set<GainNode>()

function random(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6D2B79F5) | 0
    let mixed = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    mixed = (mixed + Math.imul(mixed ^ (mixed >>> 7), 61 | mixed)) ^ mixed
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296
  }
}

function audio(): AudioContext | undefined {
  if (context)
    return context
  try {
    context = new AudioContext({ latencyHint: 'interactive', sampleRate: 48000 })
    output = context.createGain()
    output.gain.value = 5.8
    const highpass = context.createBiquadFilter()
    highpass.type = 'highpass'
    highpass.frequency.value = 40
    highpass.Q.value = -3
    const clip = context.createWaveShaper()
    const curve = new Float32Array(4097)
    for (let i = 0; i < curve.length; i++) {
      const x = i * 2 / (curve.length - 1) - 1
      const over = Math.abs(x) - 0.7
      curve[i] = over <= 0 ? x : Math.sign(x) * (0.7 + over - over * over / 0.6)
    }
    clip.curve = curve
    output.connect(highpass).connect(clip).connect(context.destination)
    noise = context.createBuffer(1, context.sampleRate * 2, context.sampleRate)
    const dice = random(1)
    const samples = noise.getChannelData(0)
    for (let i = 0; i < samples.length; i++) samples[i] = dice() * 2 - 1
    const impulse = context.createBuffer(2, context.sampleRate * 0.25, context.sampleRate)
    for (let channel = 0; channel < 2; channel++) {
      const dice = random(channel === 0 ? 3110 : 5639)
      const samples = impulse.getChannelData(channel)
      let energy = 0
      for (let i = 0; i < samples.length; i++) {
        const time = i / context.sampleRate
        samples[i] = time < 0.008 ? 0 : (dice() * 2 - 1) * Math.exp(-Math.log(1000) * time / 0.25)
        energy += samples[i]! ** 2
      }
      const scale = 1 / Math.sqrt(energy)
      for (let i = 0; i < samples.length; i++) samples[i] = samples[i]! * scale
    }
    room = context.createConvolver()
    room.normalize = false
    room.buffer = impulse
    room.connect(output)
    return context
  }
  catch {
    context = undefined
    return undefined
  }
}

/** The Android recipes and shaping, rendered by the Web Audio graph the native port mirrors. */
export function playCue(cue: Cue, normal = false, direction = 0, material?: Material): void {
  if (!settings.value.sounds || document.hidden)
    return
  const ctx = audio()
  if (!ctx || !output || !room || !noise)
    return
  if (ctx.state === 'suspended') void ctx.resume().catch(() => {})
  const recipe = palette.themes[material ?? settings.value.soundMaterial]?.[cue] as Recipe | undefined
  if (!recipe)
    return
  const now = performance.now()
  const since = now - (last.get(cue) ?? -Infinity)
  last.set(cue, now)
  const speed = Math.max(0, Math.min(1, (220 - since) / 150))
  const control = cue === 'tap' || cue === 'select' || cue === 'toggle'
  const level = (normal ? 1 : 0.8) * (control ? 1 - 0.22 * speed : 1) * (cue === 'tap' ? 0.92 : 1)
  const length = (normal ? 1 : 0.85) * (control ? 1 - 0.25 * speed : 1)
  const bright = (normal ? 1 : 0.75) * (control ? 1 - 0.2 * speed : 1) * (cue === 'tap' ? 0.45 : 1)
  const tail = control ? 1 - 0.8 * speed : 1
  const pitch = (cue === 'tap' ? 0.97 : 1) * (cue === 'select' ? 1 + 0.05 * direction : 1)
  const nudge = (amount: number) => 1 + (Math.random() * 2 - 1) * amount
  const strikePitch = recipe.vary ? nudge(recipe.vary.pitch) : 1
  const strikeForce = recipe.vary ? nudge(recipe.vary.level) : 1
  const step = recipe.walk?.length && since < 1000 ? ((steps.get(cue) ?? -1) + 1) % recipe.walk.length : 0
  steps.set(cue, step)
  const walk = 2 ** ((recipe.walk?.[step] ?? 0) / 12)
  const start = ctx.currentTime
  const previous = voices.get(cue)
  if (previous) {
    previous.gain.cancelAndHoldAtTime(start)
    previous.gain.linearRampToValueAtTime(0, start + (recipe.layers.some(layer => layer.note) ? 0.4 : 0.08))
  }
  const master = ctx.createGain()
  master.gain.value = recipe.masterGain
  master.connect(output)
  voices.set(cue, master)
  allVoices.add(master)
  const walls = ctx.createBiquadFilter()
  walls.type = 'lowpass'
  walls.frequency.value = 3500
  walls.Q.value = -3
  const send = ctx.createGain()
  send.gain.value = 0.08 * (recipe.room ?? 1)
  master.connect(walls).connect(send).connect(room)
  let end = start
  const layers = [...recipe.layers, ...(recipe.release ?? []).map(layer => ({ ...layer, offset: (layer.offset ?? 0) + 0.09 }))]
  for (const layer of layers) {
    if (layer.from === 'strong' || (!normal && layer.from === 'normal'))
      continue
    const tone = layer.kind === 'tone'
    const frequency = tone ? layer.frequency! : layer.filterFrequency!
    const gain = Math.max(0, Math.min(1.5, level * (frequency >= 2500 ? bright : 1) * ((layer.offset ?? 0) > 0 ? tail : 1)))
    const tune = Math.max(0.75, Math.min(1.25, pitch)) * strikePitch * (tone ? recipe.vary ? nudge(0.005) : 1 : strikeForce) * (layer.note ? walk : 1)
    const reverse = cue === 'toggle' && direction < 0 && layer.glideTo !== undefined
    const from = (reverse ? layer.glideTo! : frequency) * tune
    const to = (reverse ? frequency : layer.glideTo) ?? frequency
    const attack = layer.attack
    const decay = layer.decay * Math.max(0.6, Math.min(1.6, length)) * (recipe.vary ? nudge(0.1) : 1)
    const at = start + (layer.offset ?? 0)
    const stop = at + attack + decay + 0.05
    const envelope = ctx.createGain()
    envelope.gain.setValueAtTime(0, at)
    envelope.gain.linearRampToValueAtTime(layer.peak * gain * strikeForce, at + attack)
    envelope.gain.setTargetAtTime(0, at + attack, decay / Math.log(1000))
    envelope.connect(master)
    const source = tone ? ctx.createOscillator() : ctx.createBufferSource()
    if (source instanceof OscillatorNode) {
      source.type = 'sine'
      source.frequency.setValueAtTime(from, at)
      if (layer.glideTo !== undefined)
        source.frequency.exponentialRampToValueAtTime(to * tune, at + (layer.glideTime ?? attack + decay))
      source.connect(envelope)
      source.start(at)
    }
    else {
      source.buffer = noise
      source.loop = true
      const filter = ctx.createBiquadFilter()
      filter.type = layer.filterType ?? 'bandpass'
      filter.Q.value = layer.filterQ ?? 1
      filter.frequency.setValueAtTime(from, at)
      if (layer.glideTo !== undefined)
        filter.frequency.exponentialRampToValueAtTime(to * tune, at + (layer.glideTime ?? attack + decay))
      source.connect(filter).connect(envelope)
      source.start(at, Math.random() * 2)
    }
    source.stop(stop)
    source.onended = () => { source.disconnect(); envelope.disconnect() }
    end = Math.max(end, stop)
  }
  window.setTimeout(() => {
    master.disconnect(); walls.disconnect(); send.disconnect()
    allVoices.delete(master)
    if (voices.get(cue) === master) voices.delete(cue)
  }, (end - start + 0.3) * 1000)
}

export function installControlSounds(): void {
  let pointer: { x: number, y: number, moved: boolean, at: number } | undefined
  document.addEventListener('pointerdown', (event) => { pointer = { x: event.clientX, y: event.clientY, moved: false, at: performance.now() } }, { capture: true, passive: true })
  document.addEventListener('pointermove', (event) => {
    if (pointer && (Math.abs(event.clientX - pointer.x) > 12 || Math.abs(event.clientY - pointer.y) > 12)) pointer.moved = true
  }, { capture: true, passive: true })
  document.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target.closest<HTMLElement>('button,a[href],[role="button"],[role="tab"],[role="option"],[role="switch"],[role="checkbox"],[role="radio"],summary') : null
    if (!target || target.matches(':disabled,[aria-disabled="true"]') || target.closest('[data-sound="off"]'))
      return
    if (event.detail > 0 && pointer && (pointer.moved || (target.hasAttribute('data-swipe-row') && performance.now() - pointer.at >= 500))) return
    const role = target.getAttribute('role')
    if ((role === 'tab' || role === 'radio' || role === 'option') && (target.getAttribute('aria-selected') === 'true' || target.getAttribute('aria-checked') === 'true'))
      return
    const toggle = role === 'switch' || role === 'checkbox'
    const select = role === 'tab' || role === 'option' || role === 'radio'
    const siblings = select ? [...(target.parentElement?.querySelectorAll('[role="tab"],[role="option"],[role="radio"]') ?? [])] : []
    const selected = siblings.findIndex(item => item.getAttribute('aria-selected') === 'true')
    const direction = toggle ? target.getAttribute('aria-checked') === 'true' ? -1 : 1 : selected >= 0 ? Math.sign(siblings.indexOf(target) - selected) : 0
    playCue(toggle ? 'toggle' : select ? 'select' : 'tap', target.classList.contains('btn-primary') || target.dataset.emphasis === 'normal', direction)
    if (settings.value.haptics && 'vibrate' in navigator) navigator.vibrate(5)
  }, { capture: true })
  document.addEventListener('change', (event) => {
    if (event.target instanceof Element && event.target.closest('[data-sound="off"]')) return
    if (event.target instanceof HTMLInputElement && ['checkbox', 'radio'].includes(event.target.type))
      playCue(event.target.type === 'checkbox' ? 'toggle' : 'select', false, event.target.checked ? 1 : -1)
    else if (event.target instanceof HTMLSelectElement) playCue('select')
  }, { capture: true })
  watch(() => settings.value.sounds, (enabled) => {
    if (!enabled && context) {
      for (const voice of allVoices) {
        voice.gain.cancelAndHoldAtTime(context.currentTime)
        voice.gain.linearRampToValueAtTime(0, context.currentTime + 0.03)
      }
    }
  })
  watch(() => settings.value.motion, (enabled) => document.documentElement.classList.toggle('reduce-motion', !enabled), { immediate: true })
}
