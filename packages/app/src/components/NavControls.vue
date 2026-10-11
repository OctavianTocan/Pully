<script setup lang="ts">
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import DisplayKbd from '@antfu/design/components/Display/DisplayKbd.vue'
import { openPalette } from '../state/palette'
import { settingsModalOpen } from '../state/settingsModal'
import DarkToggle from './DarkToggle.vue'
import LanguageMenu from './LanguageMenu.vue'
import SettingsModal from './settings/SettingsModal.vue'

defineProps<{
  document?: Document | ShadowRoot
}>()

/**
 * The GitHub-embedded build hides the dark-mode toggle (embed styling already follows
 * GitHub's own theme, via `embed/dark.ts`'s scoped `isDark` - `DarkToggle` only ever
 * touches the global, page-wide one) and skips mounting its own `SettingsModal`, since
 * `EmbedApp.ce.vue` already mounts one at the embed's root.
 */
const isEmbedded = import.meta.env.PR_EMBED
</script>

<template>
  <div class="flex shrink-0 items-center gap-1 coarse:gap-0">
    <slot />
    <button
      type="button"
      class="hidden items-center gap-1.5 border border-base rounded-md px-1.5 py-1 op-fade transition sm:flex hover:bg-hover hover:op-100"
      :aria-label="$t('palette.title')"
      :title="$t('palette.title')"
      @click="openPalette()"
    >
      <span class="i-ph:magnifying-glass-duotone text-sm" aria-hidden="true" />
      <DisplayKbd keys="mod+k" />
    </button>
    <LanguageMenu />
    <ActionIconButton icon="i-ph:gear-duotone" :label="$t('common.settings')" :tooltip="$t('common.settings')" @click="settingsModalOpen = true" />
    <DarkToggle v-if="!isEmbedded" />
  </div>
  <SettingsModal v-if="!isEmbedded" v-model:open="settingsModalOpen" :document="document" />
</template>
