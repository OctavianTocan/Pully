import { useEventListener } from '@vueuse/core'
import { ref } from 'vue'
import { openPalette } from '../state/palette'
import { settings } from '../state/settings'

/** Android's resisted pull beyond the top opens Commands; fixed bottom controls stay put. */
export function usePullToCommands() {
  const pull = ref(0)
  let start: {
    x: number
    y: number
  } | undefined
  let lastY = 0
  useEventListener(window, 'touchstart', (event: TouchEvent) => {
    const touch = event.touches[0]
    if (!touch || event.touches.length !== 1 || window.scrollY > 0 || (event.target instanceof Element && event.target.closest('input,textarea,select,[role="dialog"]')))
      return
    start = { x: touch.clientX, y: touch.clientY }
    lastY = touch.clientY
  }, { passive: true })
  useEventListener(window, 'touchmove', (event: TouchEvent) => {
    const touch = event.touches[0]
    if (!start || !touch)
      return
    if (event.touches.length !== 1 || Math.abs(touch.clientX - start.x) > Math.max(12, Math.abs(touch.clientY - start.y))) {
      start = undefined
      pull.value = 0
      return
    }
    if (touch.clientY > start.y && window.scrollY <= 0) {
      if (event.cancelable)
        event.preventDefault()
      const previous = pull.value
      const delta = touch.clientY - lastY
      pull.value = Math.max(0, Math.min(117.6, previous + delta * (delta > 0 ? Math.max(0.15, 1 - previous / 117.6) : 1)))
      if ((previous >= 84) !== (pull.value >= 84) && settings.value.haptics)
        navigator.vibrate?.(5)
    }
    lastY = touch.clientY
  }, { passive: false })
  useEventListener(window, 'touchend', () => {
    if (pull.value >= 84)
      openPalette()
    pull.value = 0
    start = undefined
  }, { passive: true })
  useEventListener(window, 'touchcancel', () => {
    pull.value = 0
    start = undefined
  }, { passive: true })
  return pull
}
