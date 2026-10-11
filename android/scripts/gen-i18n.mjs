// Copies the web app's locale files into the APK assets so the phone uses the same
// strings and the same 12 languages. Run from the repo root:
//   node android/scripts/gen-i18n.mjs
import { copyFileSync, mkdirSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('../../', import.meta.url).pathname
const from = join(root, 'packages/app/src/i18n/locales')
const to = join(root, 'android/app/src/main/assets/i18n')
mkdirSync(to, { recursive: true })
let count = 0
for (const name of readdirSync(from)) {
  if (!name.endsWith('.json'))
    continue
  copyFileSync(join(from, name), join(to, name))
  count++
}
console.log(`copied ${count} locales`)
