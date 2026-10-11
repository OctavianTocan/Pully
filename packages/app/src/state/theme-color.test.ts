import { afterEach, describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'
import { syncThemeColor, THEME_COLOR } from './theme-color'

function metas() {
  return [...document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')].map(meta => meta.content)
}

describe('syncThemeColor', () => {
  afterEach(() => {
    document.head.innerHTML = ''
  })

  it('points both per-scheme metas at the current mode', async () => {
    document.head.innerHTML = `
      <meta name="theme-color" media="(prefers-color-scheme: light)" content="${THEME_COLOR.light}">
      <meta name="theme-color" media="(prefers-color-scheme: dark)" content="${THEME_COLOR.dark}">
    `
    const isDark = ref(true)
    const stop = syncThemeColor(isDark)

    expect(metas()).toEqual([THEME_COLOR.dark, THEME_COLOR.dark])

    isDark.value = false
    await nextTick()
    expect(metas()).toEqual([THEME_COLOR.light, THEME_COLOR.light])
    stop()
  })
})
