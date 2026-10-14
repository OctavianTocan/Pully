// Generates app/src/main/assets/cuelume/recipes.json and assets/licenses/cuelume.txt from the
// Cuelume package (https://github.com/danielwh2/cuelume, MIT): every theme's fourteen cues as
// the layers the package itself plays, with its `knock` / `struck` / `click` builders already
// expanded. `sound/Palette.kt` reads the file, so the phone plays the same recipes the web does.
// Run from the repo root:
//   node android/scripts/gen-cuelume.mjs [path-to-unpacked-cuelume-package]
// Without a path it downloads the pinned version with `npm pack`.
import { execFileSync } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const VERSION = '0.2.8'
const root = new URL('../../', import.meta.url).pathname

function packageDir() {
  if (process.argv[2])
    return resolve(process.argv[2])
  const dir = mkdtempSync(join(tmpdir(), 'cuelume-'))
  execFileSync('npm', ['pack', `cuelume@${VERSION}`, '--silent'], { cwd: dir })
  execFileSync('tar', ['xzf', `cuelume-${VERSION}.tgz`], { cwd: dir })
  return join(dir, 'package')
}

const pkg = packageDir()
const { THEMES } = await import(pathToFileURL(join(pkg, 'dist/sounds/themes.js')).href)

const assets = join(root, 'android/app/src/main/assets')
mkdirSync(join(assets, 'cuelume'), { recursive: true })

// One layer per line, so a change to a recipe reads as a change to a line.
const pad = (depth, text) => `${'  '.repeat(depth)}${text}`
const themes = Object.entries(THEMES).map(([theme, cues]) => {
  const body = Object.entries(cues).map(([cue, { layers, release, ...rest }]) => {
    const parts = [pad(4, `"masterGain": ${rest.masterGain}`)]
    for (const key of ['room', 'walk', 'vary']) {
      if (rest[key] !== undefined)
        parts.push(pad(4, `"${key}": ${JSON.stringify(rest[key])}`))
    }
    const list = (name, items) => [pad(4, `"${name}": [`), items.map(l => pad(5, JSON.stringify(l))).join(',\n'), pad(4, ']')].join('\n')
    parts.push(list('layers', layers))
    if (release)
      parts.push(list('release', release))
    return `${pad(3, `"${cue}": {`)}\n${parts.join(',\n')}\n${pad(3, '}')}`
  })
  return `${pad(2, `"${theme}": {`)}\n${body.join(',\n')}\n${pad(2, '}')}`
})
writeFileSync(join(assets, 'cuelume/recipes.json'), `{\n  "version": "${VERSION}",\n  "themes": {\n${themes.join(',\n')}\n  }\n}\n`)

mkdirSync(join(assets, 'licenses'), { recursive: true })
copyFileSync(join(pkg, 'LICENSE'), join(assets, 'licenses/cuelume.txt'))
console.log(`Wrote recipes.json for cuelume ${VERSION}: ${Object.keys(THEMES).join(', ')}`)
