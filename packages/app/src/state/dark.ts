import type { InjectionKey, Ref } from 'vue'
import { useColorMode } from '@vueuse/core'
import { computed, watchEffect } from 'vue'
import { nativeTheme } from '../design/native-palettes'
import { channels } from '../design/theme'
import { settings } from './settings'

const mode = useColorMode({ emitAuto: true, onChanged: () => {} })
export const themeMode = mode.store
const requestedDark = computed(() => mode.value === 'dark' || (mode.value === 'auto' && mode.system.value === 'dark'))
const palette = computed(() => {
  const theme = nativeTheme(settings.value.colorTheme)
  return requestedDark.value ? theme.dark ?? theme.light : theme.light
})
export const isDark = computed({
  get: () => palette.value.dark,
  set: (dark: boolean) => { mode.value = dark ? 'dark' : 'light' },
})
if (!import.meta.env.PR_EMBED) watchEffect(() => {
  document.documentElement.classList.toggle('dark', palette.value.dark)
  const style = document.documentElement.style
  for (const [name, hex] of Object.entries(palette.value.colors)) {
    const value = channels(hex)
    style.setProperty(`--c-${name}`, value.rgb)
    style.setProperty(`--a-${name}`, String(value.alpha))
  }
})

/**
 * Lets a subtree override the ambient dark-mode ref that pierre-diffs theming reads
 * (see `FileDiff.vue`), without touching `document.documentElement` - the GitHub-embed
 * custom element provides its own scoped ref here instead, since `isDark` above targets
 * the host page's `<html>`, which the embed must never touch.
 */
export const isDarkKey: InjectionKey<Ref<boolean>> = Symbol('diffs-is-dark')
