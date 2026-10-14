// Writes the launcher icon: the adaptive icon's three layers as vector drawables, the two
// mipmap entries that stitch them together, and a flat SVG of the whole icon. The icon is a
// lowercase p: a branch line (the stem) and a commit ring (the bowl) with one orange commit
// inside it, on the deep teal of the app. Edit the shapes here, never the XML. Run from the
// repo root:
//   node android/scripts/gen-icon.mjs
//
// Everything is drawn on the 108 unit canvas Android gives an adaptive icon. A launcher masks
// the canvas to a circle, a squircle or a rounded square and shows only the middle 72 units, so
// the glyph stays inside the 66 unit circle that is safe on every mask (SAFE_RADIUS below).
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

export const CANVAS = 108
export const SAFE_RADIUS = 33

const PAPER = '#FDFCF9'

// Gradients, in canvas units. `radial` takes cx, cy, r; `linear` takes x1, y1, x2, y2.
const gradients = {
  ground: { type: 'radial', cx: 30, cy: 22, r: 96, stops: [[0, '#1E6A75'], [0.5, '#0E333A'], [1, '#091A1E']] },
  stem: { type: 'linear', x1: 40, y1: 30, x2: 40, y2: 80, stops: [[0, PAPER], [1, '#CFE6E3']] },
  ring: { type: 'linear', x1: 40, y1: 30, x2: 70, y2: 62, stops: [[0, '#FFFFFF'], [1, '#D6E9E7']] },
  commit: { type: 'linear', x1: 50, y1: 41, x2: 58, y2: 50, stops: [[0, '#FF9266'], [1, '#EF6A3A']] },
}

// The glyph. The stem is a capsule 10 wide; the ring is 10 wide around a centre line of radius
// 14.25, tangent to the stem's centre line. Outer shapes wind clockwise and the hole
// counter-clockwise, so one path can be their union with the hole cut out.
const stem = 'M34.75,31.75A5,5 0 0 1 44.75,31.75V77.75A5,5 0 0 1 34.75,77.75Z'
const ringOuter = 'M34.75,45.5A19.25,19.25 0 1 1 73.25,45.5A19.25,19.25 0 1 1 34.75,45.5Z'
const ringHole = 'M44.75,45.5A9.25,9.25 0 1 0 63.25,45.5A9.25,9.25 0 1 0 44.75,45.5Z'
const commit = 'M49,45.5A5,5 0 1 1 59,45.5A5,5 0 1 1 49,45.5Z'

export const layers = {
  background: [
    { d: 'M0,0H108V108H0Z', fill: 'ground' },
  ],
  foreground: [
    // A soft lift under the letter: one path, so the overlap of stem and ring does not darken twice.
    { d: `${stem}${ringOuter}${ringHole}`, fill: '#000000', alpha: 0.28, dy: 2.4 },
    { d: stem, fill: 'stem' },
    { d: `${ringOuter}${ringHole}`, fill: 'ring' },
    { d: commit, fill: 'commit' },
  ],
  // The themed icon (Android 13+) paints this shape in the wallpaper's colour. Any opaque fill works.
  monochrome: [
    { d: `${stem}${ringOuter}${ringHole}${commit}`, fill: '#000000' },
  ],
}

const num = n => String(+n.toFixed(2))

function svgGradient(id, g) {
  const stops = g.stops.map(([o, c]) => `<stop offset="${o}" stop-color="${c}"/>`).join('')
  return g.type === 'radial'
    ? `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${g.cx}" cy="${g.cy}" r="${g.r}">${stops}</radialGradient>`
    : `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${g.x1}" y1="${g.y1}" x2="${g.x2}" y2="${g.y2}">${stops}</linearGradient>`
}

/** One layer as SVG elements on the 108 canvas. `prefix` keeps gradient ids apart when several icons share a page. */
export function layerSvg(name, prefix = 'pulls') {
  const used = new Set()
  const body = layers[name].map((shape) => {
    const isGradient = shape.fill in gradients
    if (isGradient)
      used.add(shape.fill)
    const fill = isGradient ? `url(#${prefix}-${shape.fill})` : shape.fill
    const alpha = shape.alpha ? ` fill-opacity="${shape.alpha}"` : ''
    const move = shape.dy ? ` transform="translate(0 ${shape.dy})"` : ''
    return `<path d="${shape.d}" fill="${fill}"${alpha}${move}/>`
  }).join('')
  const defs = [...used].map(id => svgGradient(`${prefix}-${id}`, gradients[id])).join('')
  return (defs ? `<defs>${defs}</defs>` : '') + body
}

export function iconSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}">${layerSvg('background')}${layerSvg('foreground')}</svg>\n`
}

function xmlGradient(g, pad) {
  const stops = g.stops.map(([o, c]) => `${pad}            <item android:offset="${o}" android:color="${c}" />`).join('\n')
  const head = g.type === 'radial'
    ? `android:type="radial" android:centerX="${g.cx}" android:centerY="${g.cy}" android:gradientRadius="${g.r}"`
    : `android:type="linear" android:startX="${g.x1}" android:startY="${g.y1}" android:endX="${g.x2}" android:endY="${g.y2}"`
  return `${pad}    <aapt:attr name="android:fillColor">\n${pad}        <gradient ${head}>\n${stops}\n${pad}        </gradient>\n${pad}    </aapt:attr>`
}

function xmlPath(shape, pad) {
  const alpha = shape.alpha ? `\n${pad}    android:fillAlpha="${shape.alpha}"` : ''
  const open = `${pad}<path\n${pad}    android:pathData="${shape.d}"${alpha}`
  if (shape.fill in gradients)
    return `${open}>\n${xmlGradient(gradients[shape.fill], pad)}\n${pad}</path>`
  return `${open}\n${pad}    android:fillColor="${shape.fill}" />`
}

export function layerXml(name) {
  const paths = layers[name].map((shape) => {
    if (!shape.dy)
      return xmlPath(shape, '    ')
    return `    <group android:translateY="${num(shape.dy)}">\n${xmlPath(shape, '        ')}\n    </group>`
  }).join('\n')
  return `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by android/scripts/gen-icon.mjs. Edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${CANVAS}dp"
    android:height="${CANVAS}dp"
    android:viewportWidth="${CANVAS}"
    android:viewportHeight="${CANVAS}">
${paths}
</vector>
`
}

export const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by android/scripts/gen-icon.mjs. Edit the script, not this file. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
`

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  const res = join(root, 'android/app/src/main/res')
  mkdirSync(join(res, 'drawable'), { recursive: true })
  mkdirSync(join(res, 'mipmap-anydpi-v26'), { recursive: true })
  mkdirSync(join(root, 'android/icon'), { recursive: true })
  for (const name of Object.keys(layers))
    writeFileSync(join(res, 'drawable', `ic_launcher_${name}.xml`), layerXml(name))
  // minSdk is 26, so every phone that installs the app reads the adaptive icon and no PNG fallbacks are needed.
  writeFileSync(join(res, 'mipmap-anydpi-v26/ic_launcher.xml'), adaptiveXml)
  writeFileSync(join(res, 'mipmap-anydpi-v26/ic_launcher_round.xml'), adaptiveXml)
  writeFileSync(join(root, 'android/icon/pulls.svg'), iconSvg())
  console.log('wrote the launcher icon')
}
