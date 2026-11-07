<script setup lang="ts">
import type { AiInboxChat } from '@pulls.review/core/local-rpc'
import { Markdown } from '@comark/vue'
import { resolveAskModel } from '@pulls.review/core/analyze'
import { AiInboxChatSchema, INBOX_KEY, LOCAL_RPC } from '@pulls.review/core/local-rpc'
import * as v from 'valibot'
import { computed, inject, nextTick, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { cancelAiTask, followAiTask, listAiTasks, startAiTask, taskModelOf } from '../../local/ai-tasks'
import { localRpcKey } from '../../local/local-rpc-key'
import { playCue } from '../../sound/control-sounds'
import { settings } from '../../state/settings'
import { keepRepoLinks } from '../ai/ai-format'
import AppModal from '../AppModal.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'

const props = defineProps<{ question?: string }>()
const open = defineModel<boolean>('open', { required: true })
const rpc = inject(localRpcKey, undefined)
const chat = shallowRef<AiInboxChat>({ turns: [] })
const input = ref('')
const busy = ref(false)
const error = ref<string>()
const content = ref<HTMLElement>()
const model = computed(() => taskModelOf(resolveAskModel(settings.value.llm)))
let taskId: string | undefined
let follower: AbortController | undefined
let active = true
let epoch = 0
let stopRequested = false
onScopeDispose(() => {
  active = false
  epoch++
  follower?.abort()
})
async function load() {
  if (!rpc)
    return
  const parsed = v.safeParse(AiInboxChatSchema, await rpc.call(LOCAL_RPC.inboxChat))
  if (active && parsed.success)
    chat.value = parsed.output
}
async function follow(id: string, generation: number) {
  if (!rpc)
    return
  follower?.abort()
  const controller = new AbortController()
  follower = controller
  taskId = id
  busy.value = true
  try {
    const result = await followAiTask(rpc, id, { signal: controller.signal, onActivity: () => {
      if (active && generation === epoch)
        void load().catch(() => {})
    } })
    if (!active || generation !== epoch)
      return
    await load()
    if (result.status === 'failed')
      error.value = result.error
    if (result.status === 'failed' || result.status === 'done')
      playCue(result.status === 'failed' ? 'error' : 'ready', true)
  }
  catch (failure) {
    if (active && generation === epoch && !controller.signal.aborted)
      error.value = failure instanceof Error ? failure.message : String(failure)
  }
  finally {
    if (active && generation === epoch) {
      busy.value = false
      taskId = undefined
    }
  }
}
async function send(question = input.value) {
  const text = question.trim()
  if (!rpc || !text || busy.value || !model.value)
    return
  const generation = ++epoch
  stopRequested = false
  busy.value = true
  input.value = ''
  error.value = undefined
  try {
    const id = await startAiTask(rpc, { kind: 'inbox', key: INBOX_KEY, question: text, model: model.value, locale: settings.value.locale })
    if (stopRequested)
      await cancelAiTask(rpc, id)
    if (!active || generation !== epoch)
      return
    await load()
    await follow(id, generation)
  }
  catch (failure) {
    if (active && generation === epoch) {
      busy.value = false
      error.value = failure instanceof Error ? failure.message : String(failure)
    }
  }
}
async function stop() {
  stopRequested = true
  if (rpc && taskId)
    await cancelAiTask(rpc, taskId)
}
async function clear() {
  if (!rpc || busy.value)
    return
  await rpc.call(LOCAL_RPC.inboxChatClear)
  if (active)
    chat.value = { turns: [] }
}
watch(open, async (shown) => {
  if (!shown || !rpc)
    return
  try {
    await load()
    if (!active)
      return
    const pending = (await listAiTasks(rpc, INBOX_KEY)).find(task => task.status === 'running' && task.kind === 'inbox')
    if (!active)
      return
    if (pending && !busy.value)
      void follow(pending.id, epoch)
    else if (props.question?.trim() && !busy.value)
      void send(props.question)
  }
  catch (failure) {
    if (active)
      error.value = failure instanceof Error ? failure.message : String(failure)
  }
}, { immediate: true })
watch(() => props.question, (question) => {
  if (open.value && question?.trim())
    void send(question)
})
watch(() => chat.value.turns, async () => {
  await nextTick()
  content.value?.scrollIntoView({ block: 'end', behavior: 'smooth' })
})
</script>

<template>
  <AppModal v-model:open="open" :title="$t('mobile.ask')" spacious tall>
    <div class="min-h-60 flex flex-col gap-6">
      <p v-if="!chat.turns.length" class="py-8 text-center text-sm color-muted">
        {{ $t('mobile.noChat') }}
      </p>
      <article v-for="(turn, index) in chat.turns" :key="index" class="flex flex-col gap-3">
        <p class="text-lg font-medium">
          {{ turn.question }}
        </p>
        <Suspense v-if="turn.answer">
          <Markdown :value="turn.answer" class="chat-markdown text-read!" @click="keepRepoLinks" />
        </Suspense>
        <p v-if="turn.error" class="text-sm color-error">
          {{ turn.error }}
        </p>
      </article>
      <p v-if="busy" role="status" class="ai-shimmer text-sm">
        {{ $t('ai.loading') }}
      </p>
      <p v-if="error" class="text-sm color-error">
        {{ error }}
      </p>
      <div class="flex flex-wrap gap-2">
        <button v-for="suggestion in chat.suggestions" :key="suggestion" class="btn-action text-sm" :disabled="busy" @click="send(suggestion)">
          {{ suggestion }}
        </button>
      </div>
      <div ref="content" />
    </div>
    <template #footer>
      <div class="w-full flex flex-col gap-3 px-2">
        <div class="flex items-center justify-between">
          <button class="btn-text text-sm" :disabled="busy" @click="clear">
            {{ $t('mobile.newChat') }}
          </button><button v-if="busy" class="btn-action" @click="stop">
            {{ $t('common.cancel') }}
          </button>
        </div>
        <KitSearchPill v-model="input" :placeholder="$t('mobile.ask')" :send-label="input.trim() && !busy ? $t('mobile.send') : undefined" @send="send()" />
      </div>
    </template>
  </AppModal>
</template>
