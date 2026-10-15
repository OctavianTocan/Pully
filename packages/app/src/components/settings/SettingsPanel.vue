<script setup lang="ts">
import type { LlmSettings } from '@pulls.review/core/analyze'
import type { ModelOption } from '@pulls.review/core/llm'
import type { StoredGithubTokenMeta } from '../../composables/useGithubTokenMeta'
import AutoRefreshSettingsSection from './AutoRefreshSettingsSection.vue'
import GithubTokenSettings from './GithubTokenSettings.vue'
import LayoutSettingsSection from './LayoutSettingsSection.vue'
import LlmSettingsSection from './LlmSettingsSection.vue'
import UsageStatsSettingsSection from './UsageStatsSettingsSection.vue'

defineProps<{
  githubTokenSet: boolean
  githubTokenMeta: StoredGithubTokenMeta | null
  githubTokenBusy?: boolean
  githubTokenError?: string
  llmSettings: LlmSettings
  models: ModelOption[] | null
  modelsLoading?: boolean
  modelsError?: string
}>()

defineEmits<{
  /** Save a new GitHub token (validated by the container); `''` removes it. */
  'saveGithubToken': [token: string]
  'update:llmSettings': [value: LlmSettings]
}>()

// The server's `gh` login supplies GitHub access, so the local build has no token to enter.
const isLocal = import.meta.env.PR_LOCAL
</script>

<template>
  <div class="flex flex-col gap-4 p2">
    <LayoutSettingsSection />

    <div class="border-t border-base" />

    <AutoRefreshSettingsSection />

    <div class="border-t border-base" />

    <template v-if="!isLocal">
      <GithubTokenSettings
        :token-set="githubTokenSet"
        :meta="githubTokenMeta"
        :busy="githubTokenBusy"
        :error="githubTokenError"
        @save="$emit('saveGithubToken', $event)"
      />

      <div class="border-t border-base" />
    </template>

    <LlmSettingsSection
      :llm-settings="llmSettings"
      :models="models"
      :models-loading="modelsLoading"
      :models-error="modelsError"
      @update:llm-settings="$emit('update:llmSettings', $event)"
    />

    <template v-if="isLocal">
      <div class="border-t border-base" />

      <UsageStatsSettingsSection />
    </template>
  </div>
</template>
