<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import peleUrl from '../../../../../mobile/shared-ui/pele/pele.global.js?url'
import { isDark } from '../../state/dark'

interface Pele { render: (source: string, options: {
  maxWidth: number
  links: boolean
  images: boolean
  idPrefix: string
}) => { svg: string } }
const props = defineProps<{ source: string }>()
const host = ref<HTMLElement>()
const failed = ref(false)
let active = true
let generation = 0
let observer: ResizeObserver | undefined
onScopeDispose(() => {
  active = false
  generation++
  observer?.disconnect()
})

function engine(): Promise<Pele> {
  const global = window as unknown as { pele?: Pele }
  if (global.pele)
    return Promise.resolve(global.pele)
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>('script[data-pully-pele]')
    const script = existing ?? document.createElement('script')
    script.addEventListener('load', () => global.pele ? resolve(global.pele) : reject(new Error('Diagram renderer unavailable')), { once: true })
    script.addEventListener('error', reject, { once: true })
    if (!existing) {
      script.src = peleUrl
      script.dataset.pullyPele = ''
      document.head.append(script)
    }
  })
}

// The same bounded, vector-only output as the native Pele host. No links, pictures,
// executable elements, event handlers, remote styles or remote paint servers survive.
function inert(svg: string): SVGElement {
  if (svg.length > 4 * 1024 * 1024)
    throw new Error('Diagram too large')
  const parsed = new DOMParser().parseFromString(svg, 'image/svg+xml')
  if (parsed.querySelector('parsererror') || parsed.documentElement.localName !== 'svg')
    throw new Error('Invalid diagram')
  const elements = new Set(['svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'polygon', 'polyline', 'line', 'text', 'tspan', 'style', 'title', 'desc'])
  const attributes = new Set(['fill', 'stroke', 'fill-opacity', 'stroke-opacity', 'stroke-width', 'stroke-dasharray', 'stroke-linecap', 'stroke-linejoin', 'stroke-miterlimit', 'fill-rule', 'font-family', 'font-size', 'font-weight', 'font-style', 'text-anchor', 'text-decoration', 'width', 'height', 'viewBox', 'x', 'y', 'x1', 'x2', 'y1', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'dx', 'dy', 'd', 'points', 'transform', 'opacity', 'class', 'style', 'xmlns'])
  const root = parsed.documentElement
  for (const element of [root, ...root.querySelectorAll('*')]) {
    if (!elements.has(element.localName)) {
      element.remove()
      continue
    }
    if (element.localName === 'style' && /@|url\s*\(|expression\s*\(/i.test(element.textContent ?? '')) {
      element.remove()
      continue
    }
    for (const attr of [...element.attributes]) {
      if (!attributes.has(attr.name) || /url\s*\(|expression\s*\(|javascript:|@import/i.test(attr.value))
        element.removeAttribute(attr.name)
    }
  }
  root.setAttribute('width', '100%')
  root.removeAttribute('height')
  ;
  (root as unknown as SVGElement).style.height = 'auto'
  ;
  (root as unknown as SVGElement).style.display = 'block'
  return document.importNode(root, true) as unknown as SVGElement
}

const palette = computed(() => ({
  '--pele-bg': 'rgb(var(--c-base))',
  '--pele-fg': 'rgb(var(--c-text))',
  '--pele-muted': 'rgb(var(--c-muted))',
  '--pele-line': 'rgb(var(--c-muted))',
  '--pele-surface': 'rgb(var(--c-paper))',
  '--pele-surface-alt': 'rgb(var(--c-paper-2))',
  '--pele-border': 'rgb(var(--c-border))',
  '--pele-accent': 'rgb(var(--c-primary))',
  '--pele-critical': 'rgb(var(--c-error))',
  '--pele-series-1': 'rgb(var(--c-accent-teal))',
  '--pele-series-2': 'rgb(var(--c-accent-orange))',
  '--pele-series-3': 'rgb(var(--c-accent-magenta))',
  '--pele-series-4': 'rgb(var(--c-blue))',
  '--pele-series-5': 'rgb(var(--c-purple))',
  '--pele-series-6': 'rgb(var(--c-warning))',
  '--pele-series-7': 'rgb(var(--c-success))',
  '--pele-series-8': 'rgb(var(--c-muted))',
}))

async function render() {
  const current = ++generation
  if (!host.value)
    return
  try {
    if (props.source.length > 50_000)
      throw new Error('Diagram source too large')
    const pele = await engine()
    if (!active || generation !== current || !host.value)
      return
    const rendered = pele.render(props.source, { maxWidth: Math.max(240, host.value.clientWidth), links: false, images: false, idPrefix: 'pully' })
    const svg = inert(rendered.svg)
    for (const [key, value] of Object.entries(palette.value)) svg.style.setProperty(key, value)
    const shadow = host.value.shadowRoot ?? host.value.attachShadow({ mode: 'open' })
    shadow.replaceChildren(svg)
    failed.value = false
  }
  catch {
    if (active && generation === current)
      failed.value = true
  }
}
onMounted(() => {
  observer = new ResizeObserver(() => {
    void render()
  })
  if (host.value)
    observer.observe(host.value)
  void render()
})
watch(() => [props.source, isDark.value], () => {
  void render()
})
</script>

<template>
  <div ref="host" class="min-w-0 w-full overflow-x-auto" :style="palette" role="img" :aria-label="$t('mobile.diagram')" />
  <pre v-if="failed" class="overflow-x-auto rounded-card bg-code p-4 text-xs leading-relaxed"><code>{{ source }}</code></pre>
</template>
