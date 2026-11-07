import source from '../../../../mobile/shared-ui/src/commonMain/kotlin/com/octaviantocan/pulls/ui/design/OpenCodeThemes.kt?raw'
import tokens from './tokens.generated.json'

interface Palette {
  dark: boolean
  colors: Record<string, string>
}
export interface NativeTheme {
  id: string
  name: string
  light: Palette
  dark?: Palette
}

function palette(body: string): Palette {
  const colors: Record<string, string> = {}
  for (const match of body.matchAll(/(\w+) = Color\(0x([A-Fa-f0-9]{8})\)/g)) {
    const name = match[1]!.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`).replace(/^(paper|primary)(\d+)$/, '$1-$2')
    const argb = match[2]!
    colors[name] = `#${argb.slice(2)}${argb.startsWith('FF') ? '' : argb.slice(0, 2)}`
  }
  return { dark: /\bdark = true/.test(body), colors }
}

// Read the native generated palettes directly so web previews and native theme
// selection cannot drift to a separately maintained set of colors.
const converted: NativeTheme[] = []
for (const block of source.split(/\bColorTheme\(\s*\n/).slice(1)) {
  const id = /id = "([^"]+)"/.exec(block)?.[1]
  const name = /name = "([^"]+)"/.exec(block)?.[1]
  const light = /light = PullsColors\(([\s\S]*?)\n {8}\),/.exec(block)?.[1]
  const dark = /dark = PullsColors\(([\s\S]*?)\n {8}\),/.exec(block)?.[1]
  if (id && name && light)
    converted.push({ id, name, light: palette(light), ...(dark ? { dark: palette(dark) } : {}) })
}

export const nativeThemes: NativeTheme[] = [{
  id: 'pulls',
  name: 'Pully',
  light: { dark: false, colors: Object.fromEntries(Object.entries(tokens.colors).map(([name, value]) => [name, value.light])) },
  dark: { dark: true, colors: Object.fromEntries(Object.entries(tokens.colors).map(([name, value]) => [name, value.dark])) },
}, ...converted]

export function nativeTheme(id: string): NativeTheme {
  return nativeThemes.find(theme => theme.id === id) ?? nativeThemes[0]!
}
