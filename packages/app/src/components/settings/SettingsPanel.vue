<script setup lang="ts">
import type { LlmSettings } from '@pulls.review/core/analyze'
import type { ModelOption } from '@pulls.review/core/llm'
import type { StoredGithubTokenMeta } from '../../composables/useGithubTokenMeta'
import { resolveModel } from '@pulls.review/core/analyze'
import { LOCALES } from '@pulls.review/core/locales'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { nativeTheme, nativeThemes } from '../../design/native-palettes'
import { playCue } from '../../sound/control-sounds'
import { autoRefresh } from '../../state/auto-refresh'
import { isDark, themeMode } from '../../state/dark'
import { appFonts, codeFonts } from '../../state/phone-type'
import { settings } from '../../state/settings'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'
import KitSheetRow from '../kit/KitSheetRow.vue'
import KitSwitch from '../kit/KitSwitch.vue'
import CodeSample from './CodeSample.vue'
import GithubTokenSettings from './GithubTokenSettings.vue'
import LayoutChoices from './LayoutChoices.vue'
import LlmSettingsSection from './LlmSettingsSection.vue'
import UsageStatsSettingsSection from './UsageStatsSettingsSection.vue'

const props = defineProps<{
  githubTokenSet: boolean
  githubTokenMeta: StoredGithubTokenMeta | null
  githubTokenBusy?: boolean
  githubTokenError?: string
  llmSettings: LlmSettings
  models: ModelOption[] | null
  modelsLoading?: boolean
  modelsError?: string
}>()

const emit = defineEmits<{
  /** Save a new GitHub token (validated by the container); `''` removes it. */
  'saveGithubToken': [token: string]
  'update:llmSettings': [value: LlmSettings]
  'navigation': [value: {
    title: string
    back: boolean
  }]
}>()

// The server's `gh` login supplies GitHub access, so the local build has no token to enter.
const isLocal = import.meta.env.PR_LOCAL
const isEmbedded = import.meta.env.PR_EMBED
const query = ref('')
const themes = computed(() => nativeThemes.filter(theme => theme.name.toLowerCase().includes(query.value.trim().toLowerCase())))
const languages = computed(() => LOCALES.filter(locale => `${locale.native} ${locale.code}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const page = ref<'main' | 'theme' | 'language' | 'app-font' | 'code-font' | 'feedback' | 'account' | 'ai' | 'advanced'>('main')
const { t } = useI18n()
const titles = computed(() => ({ 'main': t('common.settings'), 'theme': t('mobile.colorTheme'), 'language': t('settings.language'), 'app-font': t('mobile.appFont'), 'code-font': t('mobile.codeFont'), 'feedback': t('mobile.sound'), 'account': t('mobile.account'), 'ai': t('mobile.tourModel'), 'advanced': t('mobile.advanced') }))
watch([page, titles], () => emit('navigation', { title: titles.value[page.value], back: page.value !== 'main' }), { immediate: true })
const themeOptions = computed(() => [{ value: 'auto', label: t('mobile.system') }, { value: 'light', label: t('settings.light') }, { value: 'dark', label: t('settings.dark') }])
const zoom = computed({ get: () => String(settings.value.codeZoom), set: (value) => {
  settings.value.codeZoom = Number(value)
} })
const zoomOptions = computed(() => [{ value: '0.88', label: t('mobile.smaller') }, { value: '1', label: t('mobile.defaultSize') }, { value: '1.16', label: t('mobile.larger') }])
const currentModel = computed(() => {
  const resolved = resolveModel(props.llmSettings)
  return resolved?.model.name || resolved?.model.id || props.llmSettings.provider
})
const languageName = computed(() => LOCALES.find(locale => locale.code === settings.value.locale)?.native ?? settings.value.locale)
watch(page, () => {
  query.value = ''
})
function back() {
  page.value = 'main'
}
defineExpose({ back })
function material(value: typeof settings.value.soundMaterial) {
  settings.value.soundMaterial = value
  playCue('tap', true)
}
</script>

<template>
  <div v-if="page === 'main'" class="flex flex-col gap-6">
    <section v-if="!isLocal">
      <h3 class="text-sm color-muted">
        {{ $t('mobile.account') }}
      </h3>
      <div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow :label="$t('mobile.account')" :value="githubTokenMeta?.login" first chevron @activate="page = 'account'" />
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('mobile.appearance') }}
      </h3>
      <KitCapsuleTabs v-if="!isEmbedded" v-model="themeMode" :options="themeOptions" class="mt-2.5 w-full" />
      <div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow v-if="!isEmbedded" :label="$t('mobile.colorTheme')" :value="nativeTheme(settings.colorTheme).name" first chevron @activate="page = 'theme'" />
        <KitSheetRow :label="$t('settings.language')" :value="languageName" chevron @activate="page = 'language'" />
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('settings.layout') }}
      </h3><LayoutChoices class="mt-2.5" />
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('mobile.fonts') }}
      </h3>
      <div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow :label="$t('mobile.appFont')" :value="appFonts.find(font => font.id === settings.appFont)?.label" first chevron @activate="page = 'app-font'" />
        <KitSheetRow :label="$t('mobile.codeFont')" :value="codeFonts.find(font => font.id === settings.codeFont)?.label" chevron @activate="page = 'code-font'" />
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('mobile.code') }}
      </h3>
      <div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <div class="flex flex-col gap-3 p-3.5">
          <KitCapsuleTabs v-model="zoom" :options="zoomOptions" class="w-full" /><CodeSample />
        </div>
        <KitSheetRow :label="$t('mobile.wrapCode')" @activate="settings.codeWrap = !settings.codeWrap">
          <KitSwitch v-model="settings.codeWrap" :label="$t('mobile.wrapCode')" />
        </KitSheetRow>
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('mobile.sounds') }}
      </h3>
      <div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow :label="$t('mobile.buttonSounds')" first @activate="settings.sounds = !settings.sounds">
          <KitSwitch v-model="settings.sounds" :label="$t('mobile.buttonSounds')" />
        </KitSheetRow>
        <KitSheetRow v-if="settings.sounds" :label="$t('mobile.sound')" :value="$t(`mobile.material.${settings.soundMaterial}`)" chevron @activate="page = 'feedback'" />
        <KitSheetRow :label="$t('mobile.vibration')" @activate="settings.haptics = !settings.haptics">
          <KitSwitch v-model="settings.haptics" :label="$t('mobile.vibration')" />
        </KitSheetRow>
      </div>
    </section>
    <section v-if="isLocal">
      <h3 class="text-sm color-muted">
        {{ $t('stats.title') }}
      </h3><div class="mt-3 rounded-card bg-raised p-4 shadow-note">
        <UsageStatsSettingsSection />
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('settings.cached') }}
      </h3><div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow :label="$t('settings.autoRefresh')" first @activate="autoRefresh = !autoRefresh">
          <KitSwitch v-model="autoRefresh" :label="$t('settings.autoRefresh')" />
        </KitSheetRow>
      </div>
    </section>
    <section>
      <h3 class="text-sm color-muted">
        {{ $t('settings.llm.title') }}
      </h3><div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow :label="$t('mobile.tourModel')" :value="currentModel" first chevron @activate="page = 'ai'" />
      </div>
    </section>
    <div class="mt-6 overflow-hidden rounded-card bg-raised shadow-note">
      <KitSheetRow :label="$t('mobile.advanced')" first chevron @activate="page = 'advanced'" />
    </div>
  </div>
  <div v-else class="flex flex-col gap-4">
    <div v-if="page === 'theme'" class="flex flex-col gap-3">
      <KitSearchPill v-model="query" :placeholder="$t('mobile.findTheme')" class="rounded-card! shadow-note!" />
      <div class="overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow v-for="(theme, index) in themes" :key="theme.id" :label="theme.name" :first="index === 0" @activate="settings.colorTheme = theme.id">
          <span class="flex items-center gap-1"><span v-for="(color, dot) in ['base', 'accent-teal', 'success', 'error']" :key="color" class="h-3.5 w-3.5 rounded-full" :class="dot === 0 ? 'border border-base' : ''" :style="{ background: (isDark ? theme.dark ?? theme.light : theme.light).colors[color] }" /></span><span class="ml-3 h-5 w-5 flex items-center justify-center"><span v-if="settings.colorTheme === theme.id" class="i-lu-check text-5 color-accent-teal" /></span>
        </KitSheetRow>
      </div>
    </div>
    <div v-else-if="page === 'language'" class="flex flex-col gap-3">
      <KitSearchPill v-model="query" :placeholder="$t('mobile.findLanguage')" class="rounded-card! shadow-note!" /><div class="overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow v-for="(locale, index) in languages" :key="locale.code" :label="locale.native" :first="index === 0" @activate="settings.locale = locale.code; back()">
          <span v-if="settings.locale === locale.code" class="i-lu-check color-primary" />
        </KitSheetRow>
      </div>
    </div>
    <div v-else-if="page === 'app-font'" class="overflow-hidden rounded-card bg-raised shadow-note">
      <KitSheetRow v-for="(font, index) in appFonts" :key="font.id" :label="font.label" :first="index === 0" :style="{ fontFamily: font.family }" @activate="settings.appFont = font.id">
        <span v-if="settings.appFont === font.id" class="i-lu-check color-primary" />
      </KitSheetRow>
    </div>
    <div v-else-if="page === 'code-font'" class="overflow-hidden rounded-card bg-raised shadow-note">
      <KitSheetRow v-for="(font, index) in codeFonts" :key="font.id" :label="font.label" :first="index === 0" :style="{ fontFamily: font.family }" @activate="settings.codeFont = font.id">
        <span v-if="settings.codeFont === font.id" class="i-lu-check color-primary" />
      </KitSheetRow>
    </div>
    <div v-else-if="page === 'feedback'" class="overflow-hidden rounded-card bg-raised shadow-note" data-sound="off">
      <KitSheetRow v-for="(value, index) in (['default', 'mech', 'bubble', 'press'] as const)" :key="value" :label="$t(`mobile.material.${value}`)" :first="index === 0" @activate="material(value)">
        <span v-if="settings.soundMaterial === value" class="i-lu-check color-primary" />
      </KitSheetRow>
    </div>
    <template v-else-if="page === 'account' && !isLocal">
      <GithubTokenSettings :token-set="githubTokenSet" :meta="githubTokenMeta" :busy="githubTokenBusy" :error="githubTokenError" @save="emit('saveGithubToken', $event)" />
    </template>
    <LlmSettingsSection v-else-if="page === 'ai'" :llm-settings="llmSettings" :models="models" :models-loading="modelsLoading" :models-error="modelsError" @update:llm-settings="emit('update:llmSettings', $event)" />
    <div v-else-if="page === 'advanced'" class="rounded-card bg-raised shadow-note">
      <KitSheetRow :label="$t('mobile.motion')" first @activate="settings.motion = !settings.motion">
        <KitSwitch v-model="settings.motion" :label="$t('mobile.motion')" />
      </KitSheetRow>
    </div>
  </div>
</template>
