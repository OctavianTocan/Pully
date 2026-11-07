import { watch } from 'vue'
import { settings } from './settings'

/** AppFonts.kt and CodeFonts.kt: the same bundled faces and persisted choices. */
export const appFonts = [
  { id: 'geist', label: 'Geist', family: '\'Geist\',sans-serif' },
  { id: 'mona-sans', label: 'Mona Sans', family: '\'Mona Sans\',sans-serif' },
  { id: 'system', label: 'System sans', family: 'sans-serif' },
  { id: 'serif', label: 'System serif', family: 'serif' },
] as const
export const codeFonts = [
  { id: 'geist-mono', label: 'Geist Mono', family: '\'Geist Mono\',monospace' },
  { id: 'jetbrains-mono', label: 'JetBrains Mono', family: '\'JetBrains Mono\',monospace' },
  { id: 'fira-code', label: 'Fira Code', family: '\'Fira Code\',monospace' },
  { id: 'source-code-pro', label: 'Source Code Pro', family: '\'Source Code Pro\',monospace' },
  { id: 'ibm-plex-mono', label: 'IBM Plex Mono', family: '\'IBM Plex Mono\',monospace' },
  { id: 'roboto-mono', label: 'Roboto Mono', family: '\'Roboto Mono\',monospace' },
  { id: 'system', label: 'System monospace', family: 'monospace' },
] as const

export function installPhoneType() {
  watch(() => [settings.value.appFont, settings.value.codeFont, settings.value.codeZoom] as const, () => {
    const style = document.documentElement.style
    const app = appFonts.find(font => font.id === settings.value.appFont) ?? appFonts[0]
    const code = codeFonts.find(font => font.id === settings.value.codeFont) ?? codeFonts[0]
    const zoom = [0.88, 1, 1.16].includes(settings.value.codeZoom) ? settings.value.codeZoom : 1
    style.setProperty('--pr-app-font', app.family)
    style.setProperty('--pr-code-font', code.family)
    style.setProperty('--diffs-font-size', `${12.5 * zoom}px`)
    style.setProperty('--diffs-line-height', `${20 * zoom}px`)
  }, { immediate: true })
}
