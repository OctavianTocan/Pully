import type { Ref } from 'vue'
import { watchEffect } from 'vue'

/**
 * The browser-chrome colour on phones, matched to the sticky headers' `bg-base`
 * (white, and `@antfu/design`'s `#111` in dark). `index.html` carries the same pair as
 * per-scheme metas for the first paint.
 */
export const THEME_COLOR = { light: '#ffffff', dark: '#111111' } as const

/**
 * Keeps every `theme-color` meta on the app's actual mode. Dark is a class the user can
 * flip against the OS setting, so the `prefers-color-scheme` metas alone would colour the
 * chrome for the wrong scheme. Both get the current colour: whichever one's media matches,
 * it's right. Never called by the GitHub embed, which must not touch the host's `<head>`.
 */
export function syncThemeColor(isDark: Ref<boolean>, doc: Document = document) {
  return watchEffect(() => {
    const color = isDark.value ? THEME_COLOR.dark : THEME_COLOR.light
    for (const meta of doc.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]'))
      meta.content = color
  })
}
