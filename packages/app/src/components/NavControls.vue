<script setup lang="ts">
import { openPalette } from '../state/palette'
import { settingsModalOpen } from '../state/settingsModal'
import KitFloatButton from './kit/KitFloatButton.vue'
import SettingsModal from './settings/SettingsModal.vue'

defineProps<{
  document?: Document | ShadowRoot
}>()

/**
 * The GitHub-embedded build skips mounting its own `SettingsModal`, since `EmbedApp.ce.vue`
 * already mounts one at the embed's root. The theme and language are chosen in Settings, as on
 * the phone, so the header carries only search and the gear.
 */
const isEmbedded = import.meta.env.PR_EMBED
</script>

<template>
  <div class="flex shrink-0 items-center gap-2">
    <slot />
    <KitFloatButton icon="i-lu-search" :label="$t('palette.title')" class="max-sm:hidden" @click="openPalette()" />
    <KitFloatButton icon="i-lu-cog" :label="$t('common.settings')" @click="settingsModalOpen = true" />
  </div>
  <SettingsModal v-if="!isEmbedded" v-model:open="settingsModalOpen" :document="document" />
</template>
