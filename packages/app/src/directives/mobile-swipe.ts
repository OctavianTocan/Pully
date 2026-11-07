import type { ObjectDirective } from 'vue'
import { settings } from '../state/settings'

const cleanup = new WeakMap<HTMLElement, () => void>()

/** Rows commit after horizontal intent; vertical scrolling never activates or archives one. */
export const vMobileSwipe: ObjectDirective<HTMLElement> = {
  mounted(el) {
    let gesture: { x: number, y: number, horizontal: boolean, held: boolean } | undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    let suppressClick = false
    const reset = () => { clearTimeout(timer); el.style.transform = ''; gesture = undefined }
    const down = (event: PointerEvent) => {
      if (event.button !== 0 || (event.target instanceof Element && event.target.closest('button,input,textarea,select')))
        return
      if (el.hasAttribute('data-swipe-page') && event.target instanceof Element && event.target.closest('[data-swipe-row]'))
        return
      suppressClick = false
      gesture = { x: event.clientX, y: event.clientY, horizontal: false, held: false }
      if (el.hasAttribute('data-swipe-row')) timer = setTimeout(() => {
        if (!gesture) return
        gesture.held = true
        suppressClick = true
        if (settings.value.haptics && 'vibrate' in navigator) navigator.vibrate(12)
        el.dispatchEvent(new CustomEvent('hold'))
      }, 500)
    }
    const move = (event: PointerEvent) => {
      if (!gesture) return
      const dx = event.clientX - gesture.x
      const dy = event.clientY - gesture.y
      if (Math.abs(dx) > 8 || Math.abs(dy) > 8) clearTimeout(timer)
      if (!gesture.horizontal && Math.abs(dy) > 12) { reset(); return }
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.4) gesture.horizontal = true
      if (gesture.horizontal && !gesture.held) {
        el.setPointerCapture(event.pointerId)
        if (settings.value.motion && el.hasAttribute('data-swipe-row')) el.style.transform = `translateX(${Math.max(-96, Math.min(96, dx * 0.6))}px)`
      }
    }
    const up = (event: PointerEvent) => {
      if (!gesture) return
      const dx = event.clientX - gesture.x
      if (gesture.horizontal && Math.abs(dx) > 64 && !gesture.held) {
        suppressClick = true
        el.dispatchEvent(new CustomEvent('swipe', { detail: dx < 0 ? 'left' : 'right' }))
      }
      reset()
    }
    const click = (event: MouseEvent) => {
      if (suppressClick) { event.preventDefault(); event.stopImmediatePropagation(); suppressClick = false }
    }
    el.style.touchAction = 'pan-y pinch-zoom'
    el.addEventListener('pointerdown', down)
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
    el.addEventListener('pointercancel', reset)
    el.addEventListener('click', click, true)
    cleanup.set(el, () => {
      reset()
      el.removeEventListener('pointerdown', down)
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      el.removeEventListener('pointercancel', reset)
      el.removeEventListener('click', click, true)
    })
  },
  unmounted(el) { cleanup.get(el)?.(); cleanup.delete(el) },
}
