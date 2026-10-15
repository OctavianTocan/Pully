<script setup lang="ts">
import type { Command } from '../state/commands'
import FeedbackToasts from '@antfu/design/components/Feedback/FeedbackToasts.vue'
import { inject } from 'vue'
import { routerKey } from 'vue-router'
import { deepActiveElement, isTypingTarget, useKeyboardShortcuts } from '../composables/useKeyboardShortcuts'
import { t } from '../i18n'
import { useCommand } from '../state/commands'
import { isDark } from '../state/dark'
import { COMMAND } from '../state/keymap'
import { activeFile, activeRow, clearSelection } from '../state/navigation'
import { helpOpen, paletteOpen, paletteScope, toasts } from '../state/palette'
import { settingsModalOpen } from '../state/settingsModal'
import CommandPalette from './CommandPalette.vue'
import KeyboardHelpModal from './KeyboardHelpModal.vue'

const props = defineProps<{
  /** Where dialogs render and focus is read: the embed's shadow root, or the page. */
  document?: Document | ShadowRoot
  /**
   * Where keys are listened for. Omitted, the whole window; inside the GitHub embed, the
   * drawer (`null` until it mounts), so GitHub's own shortcuts keep working elsewhere.
   */
  target?: HTMLElement | null
}>()

// The embed has no router.
const router = inject(routerKey, undefined)

function root() {
  return props.document ?? document
}

function focusedField() {
  const active = deepActiveElement(root())
  return isTypingTarget(active) ? active : undefined
}

function togglePalette() {
  helpOpen.value = false
  if (!paletteOpen.value)
    paletteScope.value = undefined
  paletteOpen.value = !paletteOpen.value
}

function scrollWindow(top: number) {
  window.scrollTo({ top, behavior: 'instant' })
}

useCommand(() => {
  const group = t('palette.group.general')
  const list: Command[] = [
    { id: COMMAND.palette, title: t('keys.palette'), group, hidden: true, run: togglePalette },
    { id: COMMAND.help, title: t('keys.help'), group, icon: 'i-ph:keyboard', run: () => {
      helpOpen.value = !helpOpen.value
    } },
    { id: COMMAND.settings, title: t('keys.settings'), group, icon: 'i-lu-cog', run: () => {
      settingsModalOpen.value = true
    } },
    {
      id: COMMAND.escape,
      title: t('keys.escape'),
      group,
      hidden: true,
      // Unclaimed otherwise, so Escape still reaches whatever else listens for it.
      when: () => !!focusedField() || activeFile.value !== undefined || activeRow.value !== undefined,
      run: () => {
        const field = focusedField()
        if (field)
          field.blur()
        else
          clearSelection()
      },
    },
  ]
  if (router) {
    list.push({
      id: COMMAND.home,
      title: t('keys.home'),
      group,
      icon: 'i-ph:house',
      when: () => router.currentRoute.value.path !== '/',
      run: () => router.push('/'),
    })
  }
  if (!import.meta.env.PR_EMBED) {
    list.push(
      { id: COMMAND.theme, title: t('keys.theme'), group, icon: 'i-ph:moon-stars', run: () => {
        isDark.value = !isDark.value
      } },
      { id: COMMAND.top, title: t('keys.top'), group, hidden: true, run: () => scrollWindow(0) },
      { id: COMMAND.bottom, title: t('keys.bottom'), group, hidden: true, run: () => scrollWindow(window.document.documentElement.scrollHeight) },
    )
  }
  return list
})

useKeyboardShortcuts(() => props.target === undefined ? window : props.target, root)
</script>

<template>
  <CommandPalette :document="document" />
  <KeyboardHelpModal :document="document" />
  <FeedbackToasts :items="toasts.toasts.value" :to="target ?? undefined" @dismiss="toasts.dismiss" />
</template>
