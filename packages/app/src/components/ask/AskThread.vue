<script setup lang="ts">
import type { AskThread, DiffsStoreAsk } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import FormTextarea from '@antfu/design/components/Form/FormTextarea.vue'
import { Markdown } from '@comark/vue'
import { onMounted, ref, useTemplateRef } from 'vue'
import { FIELD, vField } from '../../directives/field'
import AiActivityLog from '../ai/AiActivityLog.vue'
import AiUsageLine from '../ai/AiUsageLine.vue'

const props = defineProps<{
  thread: AskThread
  ask: DiffsStoreAsk
}>()

const question = ref('')
const inputRef = useTemplateRef<{ $el: HTMLTextAreaElement }>('input')

function submit() {
  const text = question.value.trim()
  if (!text || props.thread.isAsking)
    return
  question.value = ''
  void props.ask.ask(props.thread.id, text)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing)
    return
  event.preventDefault()
  submit()
}

onMounted(() => {
  if (!props.thread.turns.length)
    inputRef.value?.$el.focus({ preventScroll: true })
})
</script>

<template>
  <div class="my-1 max-w-200 overflow-hidden border border-base rounded-lg bg-base" :data-ask-thread="thread.id">
    <div class="flex items-center gap-2 border-b border-base py-1 pl-3 pr-1 text-xs">
      <span class="i-ph:sparkle-duotone shrink-0 op-fade" aria-hidden="true" />
      <span class="min-w-0 flex-1 truncate font-mono op-fade">{{ thread.label }}</span>
      <ActionIconButton compact icon="i-ph:x" :label="$t('ask.close')" :tooltip="$t('ask.close')" @click="ask.close(thread.id)" />
    </div>
    <div class="flex flex-col gap-3 p-2">
      <div v-for="(turn, index) in thread.turns" :key="index" class="flex flex-col gap-2">
        <div class="max-w-[85%] self-end whitespace-pre-wrap break-words rounded-lg bg-active px-3 py-2 text-sm">
          {{ turn.question }}
        </div>
        <div v-if="turn.startedAt !== undefined" class="flex items-start gap-2 px-1">
          <AiActivityLog
            compact
            class="flex-1"
            :activities="turn.activities ?? []"
            :running="turn.outcome === undefined"
            :started-at="turn.startedAt"
            :outcome="turn.outcome"
          />
          <ActionButton v-if="turn.outcome === undefined" size="sm" variant="text" icon="i-ph:stop-duotone" @click="ask.stop(thread.id)">
            {{ $t('common.stop') }}
          </ActionButton>
        </div>
        <Suspense v-if="turn.answer !== undefined">
          <Markdown :value="turn.answer" class="chat-markdown min-w-0 px-1 text-sm" />
        </Suspense>
        <p v-else-if="turn.error" class="px-1 text-xs text-red-600 dark:text-red-400">
          {{ turn.error }}
        </p>
        <AiUsageLine v-if="turn.usage" class="px-1" :usage="turn.usage" />
      </div>
      <div v-if="!thread.isAsking" class="flex flex-col gap-2">
        <FormTextarea
          ref="input"
          v-model="question"
          v-field="FIELD.message"
          :rows="2"
          :resize="false"
          :placeholder="thread.turns.length ? $t('ask.followUpPlaceholder') : $t('ask.placeholder')"
          @keydown="onKeydown"
        />
        <div class="flex items-center justify-between gap-2">
          <span class="text-xs op-mute">{{ $t('ask.hint') }}</span>
          <ActionButton size="sm" variant="primary" :disabled="!question.trim()" @click="submit">
            {{ $t('common.send') }}
          </ActionButton>
        </div>
      </div>
    </div>
  </div>
</template>
