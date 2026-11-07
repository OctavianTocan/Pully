<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import { useResizeObserver } from '@vueuse/core'
import { computed, nextTick, ref, useTemplateRef } from 'vue'
import { t } from '../../i18n'
import { useCommand } from '../../state/commands'
import { COMMAND } from '../../state/keymap'
import AiActivityLog from '../ai/AiActivityLog.vue'
import ChatMessage from './ChatMessage.vue'
import AppModal from '../AppModal.vue'

const props = defineProps<{
  store: DiffsStore
  document?: Document | ShadowRoot
}>()

const chat = computed(() => props.store.llm!.chat)
const open = defineModel<boolean>('open', { default: false })
const input = ref('')
const busy = computed(() => chat.value.isStreaming || props.store.llm!.isAnalyzing)
const lastIndex = computed(() => chat.value.messages.length - 1)
const lastIsError = computed(() => {
  const last = chat.value.messages.at(-1)
  return last?.role === 'assistant' && last.stopReason === 'error'
})

const showThinking = computed(() => {
  if (!chat.value.isStreaming || chat.value.activities.length)
    return false
  const last = chat.value.messages.at(-1)
  const lastPart = last?.role === 'assistant' ? last.content.at(-1) : undefined
  return !(lastPart?.type === 'text' && lastPart.text.trim())
})

// The last reply's work log folds in under the question that asked for it.
const lastQuestionIndex = computed(() => chat.value.messages.findLastIndex(message => message.role === 'user'))
const replyOutcome = computed(() => {
  const last = chat.value.messages.at(-1)
  if (last?.role !== 'assistant')
    return 'done'
  return last.stopReason === 'error' ? 'failed' : last.stopReason === 'aborted' ? 'stopped' : 'done'
})

const sheet = useTemplateRef<InstanceType<typeof AppModal>>('sheet')
const listEl = computed(() => sheet.value?.scroller)
const contentEl = useTemplateRef<HTMLDivElement>('contentEl')
const stuckToBottom = ref(true)

function onScroll() {
  const el = listEl.value
  if (el)
    stuckToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

// Markdown renders async (Suspense), so follow the content's real size rather than message changes.
useResizeObserver(contentEl, () => {
  if (stuckToBottom.value && listEl.value)
    listEl.value.scrollTop = listEl.value.scrollHeight
})

async function submit() {
  const text = input.value
  if (!text.trim() || busy.value)
    return
  input.value = ''
  stuckToBottom.value = true
  await chat.value.send(text)
}

const field = useTemplateRef<HTMLTextAreaElement>('field')
useCommand(() => ({
  id: COMMAND.ask,
  title: t('keys.ask'),
  group: t('palette.group.pr'),
  icon: 'i-ph:chat-circle-dots',
  run: async () => {
    open.value = true
    await nextTick()
    field.value?.focus()
  },
}))

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing)
    return
  event.preventDefault()
  submit()
}
</script>

<template>
  <button v-if="!open" class="press-tint fixed bottom-6 right-4 z-dropdown h-11 w-11 flex items-center justify-center rounded-full bg-raised shadow-button" :aria-label="$t('chat.title')" @click="open = true"><span class="i-lu-reply text-5" /></button>
  <AppModal ref="sheet" :document="document" v-model:open="open" :title="$t('chat.title')" spacious tall @scroll="onScroll">
    <template #trailing><button class="press-tint h-11 w-11 flex items-center justify-center rounded-full bg-raised shadow-button disabled:op40" :disabled="busy" :aria-label="$t('mobile.newChat')" @click="chat.clear()"><span class="i-lu-new-chat text-5" /></button></template>
    <div v-if="!chat.available" class="py-8 text-center text-sm color-muted">{{ $t('chat.reanalyzeToEnable') }}</div>
    <div v-else ref="contentEl" class="flex flex-col gap-5">
      <template v-for="(message, index) in chat.messages" :key="index">
        <ChatMessage :message="message" :retryable="index === lastIndex && !chat.isStreaming" phone @retry="chat.retry()" />
        <AiActivityLog v-if="index === lastQuestionIndex && !chat.isStreaming && chat.activities.length" :activities="chat.activities" :running="false" :started-at="chat.startedAt" :ended-at="chat.endedAt" :outcome="replyOutcome" compact />
      </template>
      <AiActivityLog v-if="chat.isStreaming && chat.activities.length" :activities="chat.activities" running :started-at="chat.startedAt" compact />
      <p v-if="showThinking" class="ai-shimmer text-sm" role="status">{{ $t('chat.thinking') }}</p>
      <p v-if="chat.error && !lastIsError" class="break-words text-sm color-error">{{ chat.error.message }}</p>
    </div>
    <template #footer>
      <form class="min-h-13 w-full flex items-center gap-2 rounded-full bg-raised pl-4 pr-1.5 shadow-search" @submit.prevent="submit()">
        <textarea ref="field" v-model="input" rows="1" enterkeyhint="send" :placeholder="$t('chat.placeholder')" :aria-label="$t('chat.title')" :disabled="busy" class="min-w-0 flex-1 resize-none bg-transparent py-3 text-row color-base outline-none placeholder:color-muted" @keydown="onKeydown" />
        <button v-if="chat.isStreaming" type="button" class="press-tint h-10 w-10 flex shrink-0 items-center justify-center rounded-full bg-paper-2" :aria-label="$t('common.stop')" @click="chat.stop()"><span class="i-lu-stop text-5" /></button>
        <button v-else type="submit" :disabled="busy || !input.trim()" class="press-tint h-10 w-10 flex shrink-0 items-center justify-center rounded-full bg-primary color-white disabled:op40" :aria-label="$t('common.send')" data-emphasis="normal"><span class="i-lu-arrow text-5" /></button>
      </form>
    </template>
  </AppModal>
</template>
