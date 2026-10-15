import { useToast } from '@antfu/design/composables/toast'
import { ref } from 'vue'

/** Narrows the palette to commands whose id starts with `prefix`, shown as a chip labelled `label`. */
export interface PaletteScope {
  prefix: string
  label: string
}

export const paletteOpen = ref(false)
export const paletteScope = ref<PaletteScope>()
export const helpOpen = ref(false)

/**
 * Opens the command palette, optionally narrowed to a subset of commands.
 *
 * @param scope - Limits the list until the user clears it; omit for every command.
 */
export function openPalette(scope?: PaletteScope): void {
  paletteScope.value = scope
  paletteOpen.value = true
}

/** The app-wide toast queue the keyboard layer renders. */
export const toasts = useToast()

/**
 * Shows a short-lived confirmation, e.g. after copying something.
 *
 * @param message - The text to show, already translated.
 * @param type - `error` for a failure.
 */
export function notify(message: string, type: 'success' | 'error' = 'success'): void {
  toasts.add(message, { type, duration: 2500, icon: type === 'success' ? 'i-lu-okc' : 'i-ph:warning-circle' })
}
