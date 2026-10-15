<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import { useNow } from '@vueuse/core'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { settingsModalOpen } from '../../state/settingsModal'
import { formatClockDuration } from '../ai/ai-format'
import AnalyzeStatusModal from './AnalyzeStatusModal.vue'

const props = defineProps<{
  store: DiffsStore
  document?: Document | ShadowRoot
}>()

const { t } = useI18n()

// Only rendered when `store.llm` is set - `DiffsHeader` gates on it.
const llm = computed(() => props.store.llm!)
const hasAiResult = computed(() => props.store.aiResult !== undefined)
// Running or failed: the button opens the status dialog instead of starting a run.
const hasStatus = computed(() => llm.value.isAnalyzing || llm.value.error !== undefined)
const statusOpen = ref(false)

const { now, pause, resume } = useNow({ interval: 1000, controls: true })
watch(() => llm.value.isAnalyzing, analyzing => analyzing ? resume() : pause(), { immediate: true })
const elapsed = computed(() => llm.value.startedAt === undefined ? undefined : formatClockDuration(now.value.getTime() - llm.value.startedAt))

const icon = computed(() => {
  if (llm.value.isAnalyzing)
    return 'i-lu-redo animate-spin'
  if (llm.value.error)
    return `i-ph:warning-circle ${hasAiResult.value ? 'text-red-500' : 'text-red-100'}`
  return undefined
})

const label = computed(() => {
  if (llm.value.isAnalyzing)
    return elapsed.value ? t('analyze.analyzingFor', { duration: elapsed.value }) : t('analyze.analyzing')
  if (llm.value.error)
    return t('analyze.failed')
  return hasAiResult.value ? t('analyze.reanalyze') : t('analyze.withAi')
})

const title = computed(() => {
  if (llm.value.isAnalyzing)
    return llm.value.activities.at(-1)?.title ?? llm.value.progress?.message ?? t('analyze.analyzing')
  if (llm.value.error)
    return t('analyze.failedTitle', { message: llm.value.error.message })
  return undefined
})

function onClick() {
  if (hasStatus.value)
    statusOpen.value = true
  else
    llm.value.reanalyze()
}
</script>

<template>
  <ActionButton
    v-if="!llm.isSetup"
    class="shrink-0 text-xs shadow"
    size="sm"
    variant="primary"
    :title="$t('analyze.setupTitle')"
    @click="settingsModalOpen = true"
  >
    {{ $t('analyze.setup') }}
  </ActionButton>
  <ActionButton
    v-else-if="!hasAiResult || hasStatus || store.analyzeMode !== 'rule-based'"
    class="shrink-0 text-xs tabular-nums"
    :variant="hasAiResult ? 'text' : 'primary'"
    :icon="icon"
    :title="title"
    @click="onClick"
  >
    {{ label }}
  </ActionButton>

  <AnalyzeStatusModal :open="statusOpen" :store="store" :document="document" @update:open="statusOpen = $event" />
</template>
