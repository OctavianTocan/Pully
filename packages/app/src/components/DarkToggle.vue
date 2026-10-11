<script setup lang="ts">
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import { isDark } from '../state/dark'

// The browser-chrome colour follows `isDark` from `main.ts` (`state/theme-color.ts`), on
// every page, not only the ones that show this toggle.

function toggleDark() {
  isDark.value = !isDark.value
}
</script>

<template>
  <ActionIconButton
    icon="i-ph-sun-duotone dark:i-ph-moon-duotone"
    @click="toggleDark"
  />
</template>
