<script setup lang="ts">
import type { Locale } from '@pulls.review/core/locales'
import { LOCALES } from '@pulls.review/core/locales'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { isDark } from '../../state/dark'
import { layout } from '../../state/layout'
import { settings } from '../../state/settings'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'

// App-wide preferences, written straight to their singletons. As on the phone, the look and the
// language live here rather than as buttons in every header.
const { t } = useI18n()
const layoutOptions = computed(() => [
  { value: 'unified' as const, label: t('settings.unified') },
  { value: 'split' as const, label: t('settings.split') },
])
const themeOptions = computed(() => [
  { value: 'light' as const, label: t('settings.light') },
  { value: 'dark' as const, label: t('settings.dark') },
])
const theme = computed({
  get: () => isDark.value ? 'dark' as const : 'light' as const,
  set: (value) => { isDark.value = value === 'dark' },
})
const locale = computed({
  get: () => settings.value.locale,
  set: (value: Locale) => { settings.value = { ...settings.value, locale: value } },
})
// The GitHub embed follows GitHub's own theme, so it offers no choice.
const isEmbedded = import.meta.env.PR_EMBED
</script>

<template>
  <div class="flex flex-col gap-4">
    <div v-if="!isEmbedded" class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="text-sm color-base font-medium">
        {{ $t('settings.theme') }}
      </h3>
      <KitCapsuleTabs v-model="theme" :options="themeOptions" flat class="bg-paper-2! [&>span]:bg-raised!" />
    </div>
    <label class="flex flex-wrap items-center justify-between gap-2">
      <span class="text-sm color-base font-medium">{{ $t('settings.language') }}</span>
      <select v-model="locale" class="h-11 rounded-full bg-paper-2 px-4 text-sm color-base outline-none focus-visible:ring-2 focus-visible:ring-accent-teal/45">
        <option v-for="option in LOCALES" :key="option.code" :value="option.code">{{ option.native }}</option>
      </select>
    </label>
    <div class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="text-sm color-base font-medium">
        {{ $t('settings.layout') }}
      </h3>
      <KitCapsuleTabs v-model="layout" :options="layoutOptions" flat class="bg-paper-2! [&>span]:bg-raised!" />
    </div>
  </div>
</template>
