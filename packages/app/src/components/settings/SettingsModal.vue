<script setup lang="ts">
import { ref } from 'vue'
import { useGithubTokenMeta } from '../../composables/useGithubTokenMeta'
import { useLlmModels } from '../../composables/useLlmModels'
import { settings } from '../../state/settings'
import AppModal from '../AppModal.vue'
import SettingsPanel from './SettingsPanel.vue'

defineProps<{
  open: boolean
  document?: Document | ShadowRoot
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
}>()

const githubToken = useGithubTokenMeta()
const llmModels = useLlmModels()
const panel = ref<{ back: () => void }>()
const navigation = ref<{
  title?: string
  back: boolean
}>({ back: false })
</script>

<template>
  <AppModal
    :title="navigation.title || $t('common.settings')"
    :back="navigation.back"
    :open="open"
    :document="document"
    @update:open="emit('update:open', $event ?? false)"
    @back="panel?.back()"
  >
    <SettingsPanel
      ref="panel"
      :github-token-set="!!settings.githubToken"
      :github-token-meta="githubToken.meta.value"
      :github-token-busy="githubToken.busy.value"
      :github-token-error="githubToken.error.value"
      :llm-settings="settings.llm"
      :models="llmModels.models.value"
      :models-loading="llmModels.loading.value"
      :models-error="llmModels.error.value"
      @navigation="navigation = $event"
      @save-github-token="githubToken.saveToken($event)"
      @update:llm-settings="settings = { ...settings, llm: $event }"
    />
  </AppModal>
</template>
