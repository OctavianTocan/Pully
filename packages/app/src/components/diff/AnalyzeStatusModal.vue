<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import { useResizeObserver } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import AiActivityLog from '../ai/AiActivityLog.vue'
import AiUsageLine from '../ai/AiUsageLine.vue'
import AppModal from '../AppModal.vue'

const props = defineProps<{
  open: boolean
  store: DiffsStore
  document?: Document | ShadowRoot
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
}>()

// Only opened when `store.llm` is set - `DiffAnalyzeButton` gates on it.
const llm = computed(() => props.store.llm!)

const outcome = computed(() => llm.value.error ? 'failed' : llm.value.cancelled ? 'stopped' : 'done')
const groupCount = computed(() => props.store.aiResult?.groups.length)
const wallTime = computed(() => {
  const { startedAt, endedAt } = llm.value
  return startedAt !== undefined && endedAt !== undefined ? endedAt - startedAt : undefined
})

const listEl = useTemplateRef<HTMLDivElement>('listEl')
const contentEl = useTemplateRef<HTMLDivElement>('contentEl')
const stuckToBottom = ref(true)

function onScroll() {
  const el = listEl.value
  if (el)
    stuckToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

useResizeObserver(contentEl, () => {
  if (stuckToBottom.value && listEl.value)
    listEl.value.scrollTop = listEl.value.scrollHeight
})

function rerun() {
  stuckToBottom.value = true
  llm.value.reanalyze()
}
</script>

<template>
  <AppModal
    :title="$t('analyze.statusTitle')"
    :open="open"
    :document="document"
    @update:open="emit('update:open', $event)"
  >
    <div class="max-h-[60dvh] min-h-40 flex flex-col">
      <div ref="listEl" class="min-w-0 flex-auto overflow-y-auto overscroll-contain" @scroll="onScroll">
        <div ref="contentEl" class="flex flex-col gap-3 text-sm">
          <p v-if="llm.resumed" class="flex items-center gap-1.5 text-xs op-mute">
            <span class="i-ph:plugs-connected shrink-0" aria-hidden="true" />
            {{ $t('analyze.resumed') }}
          </p>

          <AiActivityLog
            :activities="llm.activities"
            :running="llm.isAnalyzing"
            :started-at="llm.startedAt"
            :ended-at="llm.endedAt"
            :outcome="outcome"
          />

          <template v-if="!llm.isAnalyzing">
            <div
              v-if="llm.error"
              role="alert"
              class="flex flex-col gap-1 border border-red-500/30 rounded bg-red-500/5 p-3 text-red-600 dark:text-red-400"
            >
              <div class="flex items-center gap-1.5 font-medium">
                <span class="i-lu-xc shrink-0" aria-hidden="true" />
                {{ $t('analyze.failed') }}
              </div>
              <p class="whitespace-pre-wrap break-words">
                {{ llm.error.message }}
              </p>
            </div>
            <p v-else-if="llm.cancelled" class="flex items-center gap-1.5 op-fade">
              <span class="i-ph:stop-circle shrink-0" aria-hidden="true" />
              {{ $t('ai.stopped') }}
            </p>
            <p v-else-if="groupCount !== undefined" class="flex items-center gap-1.5 color-base">
              <span class="i-lu-okc shrink-0 text-green-600 dark:text-green-400" aria-hidden="true" />
              {{ $t('analyze.done', { n: groupCount }, groupCount) }}
            </p>

            <AiUsageLine :usage="llm.usage" :duration-ms="wallTime" />
          </template>
        </div>
      </div>
    </div>

    <template #footer>
      <ActionButton v-if="llm.isAnalyzing" size="sm" icon="i-lu-stop" @click="llm.abort()">
        {{ $t('common.stop') }}
      </ActionButton>
      <ActionButton v-else size="sm" variant="primary" icon="i-lu-redo" @click="rerun">
        {{ $t('common.rerun') }}
      </ActionButton>
    </template>
  </AppModal>
</template>
