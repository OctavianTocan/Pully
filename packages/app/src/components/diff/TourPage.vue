<script setup lang="ts">
import type { TourResult } from '@pulls.review/core/types'
import type { TrackedActivity } from '../ai/ai-activity'
import type { DiffsStore } from '../../stores/types'
import { resolveModel } from '@pulls.review/core/analyze'
import { TourResultSchema, serializeRef } from '@pulls.review/core/types'
import { Markdown } from '@comark/vue'
import * as v from 'valibot'
import { computed, nextTick, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { useEventListener } from '@vueuse/core'
import { useAppContext } from '../../app-context'
import { getAiRpc } from '../../local/ai-rpc'
import { cancelAiTask, followAiTask, listAiTasks, startAiTask, taskModelOf } from '../../local/ai-tasks'
import { settings } from '../../state/settings'
import { recordTourProgress, tourProgressKey } from '../../state/tour-progress'
import { settingsModalOpen } from '../../state/settingsModal'
import { playCue } from '../../sound/control-sounds'
import AppModal from '../AppModal.vue'
import AiActivityLog from '../ai/AiActivityLog.vue'
import DiagramBlock from '../ai/DiagramBlock.vue'
import { upsertActivities } from '../ai/ai-activity'
import { keepRepoLinks } from '../ai/ai-format'

const props = defineProps<{ store: DiffsStore, active: boolean }>()
const emit = defineEmits<{ source: [path: string], busy: [value: boolean], ready: [value: boolean] }>()
const { cache } = useAppContext()
const result = shallowRef<TourResult>()
const busy = ref(false)
const error = ref<string>()
const setup = ref(false)
const stop = ref(0)
const activities = shallowRef<TrackedActivity[]>([])
const key = computed(() => props.store.diff ? serializeRef(props.store.diff.ref) : undefined)
const sections = ref(false)
const moreOpen = ref(false)
const folded = ref(new Set<number>())
const cards = ref<HTMLElement>()
watch(result, value => emit('ready', !!value), { immediate: true })
watch(busy, value => emit('busy', value), { immediate: true })
useEventListener(window, 'scroll', () => {
  if (!props.active || !result.value) return
  const elements = [...(cards.value?.querySelectorAll<HTMLElement>('[data-tour-stop]') ?? [])]
  const current = elements.findLastIndex(element => element.getBoundingClientRect().top <= 160)
  if (current >= 0) stop.value = current
  if (window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 4 && elements.length) stop.value = elements.length - 1
}, { passive: true })
watch([stop, result, () => props.active], () => {
  if (props.active && result.value && key.value) recordTourProgress(tourProgressKey(key.value, result.value.generatedAt), stop.value + 1, result.value.stops[stop.value]?.title ?? '')
})
function fold(index: number) { const next = new Set(folded.value); if (next.has(index)) next.delete(index); else next.add(index); folded.value = next }
defineExpose({ more: () => { moreOpen.value = true } })
const model = computed(() => taskModelOf(resolveModel(settings.value.llm)))
let owner = 0
let controller: AbortController | undefined
let taskId: string | undefined
let stopRequested = false
onScopeDispose(() => { owner++; controller?.abort() })

async function follow(id: string, scope: string, generation: number) {
  const rpc = getAiRpc()
  if (!rpc) return
  controller?.abort()
  controller = new AbortController()
  taskId = id
  busy.value = true
  try {
    const task = await followAiTask(rpc, id, { signal: controller.signal, onActivity: incoming => { if (owner === generation) activities.value = upsertActivities(activities.value, incoming) } })
    if (owner !== generation) return
    if (task.status === 'done') {
      const saved = await cache.tours.latest(scope, props.store.diff?.head?.sha)
      if (owner !== generation) return
      const parsed = task.result ? v.safeParse(TourResultSchema, JSON.parse(task.result)) : undefined
      result.value = saved ?? (parsed?.success ? parsed.output : undefined)
      if (result.value) { setup.value = false; playCue('ready', true) }
    }
    else if (task.status === 'failed') { error.value = task.error; playCue('error', true) }
  }
  catch (failure) { if (owner === generation && !controller.signal.aborted) error.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (owner === generation) { busy.value = false; taskId = undefined } }
}

watch(key, async (scope) => {
  const generation = ++owner
  controller?.abort()
  result.value = undefined
  busy.value = false
  error.value = undefined
  stop.value = 0
  if (!scope) return
  try {
    const [saved, tasks] = await Promise.all([cache.tours.latest(scope, props.store.diff?.head?.sha), getAiRpc() ? listAiTasks(getAiRpc()!, scope) : Promise.resolve([])])
    if (owner !== generation) return
    result.value = saved
    const pending = tasks.find(task => task.kind === 'tour' && task.status === 'running')
    if (pending) void follow(pending.id, scope, generation)
  }
  catch (failure) { if (owner === generation) error.value = failure instanceof Error ? failure.message : String(failure) }
}, { immediate: true })

async function start() {
  const rpc = getAiRpc()
  const scope = key.value
  const configured = model.value
  if (busy.value || !rpc || !scope || !configured) return
  const generation = owner
  stopRequested = false
  busy.value = true
  error.value = undefined
  activities.value = []
  try {
    const id = await startAiTask(rpc, { kind: 'tour', key: scope, model: configured, locale: settings.value.locale })
    if (stopRequested) await cancelAiTask(rpc, id)
    if (owner !== generation) return
    await follow(id, scope, generation)
  }
  catch (failure) { if (owner === generation) { busy.value = false; error.value = failure instanceof Error ? failure.message : String(failure) } }
}
async function cancel() { stopRequested = true; const rpc = getAiRpc(); if (rpc && taskId) await cancelAiTask(rpc, taskId) }
async function select(index: number) { stop.value = index; sections.value = false; folded.value.delete(index); await nextTick(); cards.value?.querySelector<HTMLElement>(`[data-tour-stop="${index}"]`)?.scrollIntoView({ block: 'start', behavior: settings.value.motion ? 'smooth' : 'instant' }) }
</script>

<template>
  <div class="pb-32">
    <template v-if="result">
      <button class="sticky top-[var(--diffs-header-height)] z-tour-sections w-full flex items-center gap-3 bg-base px-4 pb-2.5 pt-5 text-left" :aria-label="$t('mobile.sections')" @click="sections = true">
        <svg viewBox="0 0 24 24" class="h-6 w-6 shrink-0 -rotate-90" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="rgb(var(--c-border))" stroke-width="2" /><circle cx="12" cy="12" r="9" fill="none" stroke="rgb(var(--c-accent-teal))" stroke-width="2" :stroke-dasharray="`${56.55 * ((stop + 1) / Math.max(1, result.stops.length))} 56.55`" /></svg>
        <span class="line-clamp-2 flex-1 text-sm font-medium">{{ result.stops[stop]?.title || result.title }}</span><span class="i-lu-chevd text-5 color-muted" />
      </button>
      <div ref="cards" class="flex flex-col gap-3.5 px-4 pt-3.5">
        <article class="rounded-[18px] bg-raised px-4 pb-5 pt-4.5 shadow-note">
          <h1 class="text-xxl font-medium">{{ result.title }}</h1>
          <Suspense><Markdown :value="result.overview" class="chat-markdown mt-3 text-read! leading-6!" @click="keepRepoLinks" /></Suspense>
          <div class="mt-2 flex justify-end"><button class="btn-icon text-muted" :disabled="busy" :aria-label="$t('mobile.rewriteTour')" @click="setup = true"><span class="i-lu-redo text-5" /></button></div>
        </article>
        <article v-for="(item, index) in result.stops" :key="index" :data-tour-stop="index" class="scroll-mt-32 rounded-[18px] bg-raised px-4 pb-5 pt-4.5 shadow-note">
          <button class="press-tint w-full flex items-start gap-3 text-left" :aria-expanded="!folded.has(index)" @click="fold(index)"><h2 class="min-w-0 flex-1 text-xxl font-medium">{{ item.title }}</h2><span class="i-lu-chevd mt-1.75 shrink-0 text-5 color-muted transition-transform duration-slide" :class="folded.has(index) ? '-rotate-90' : ''" /></button>
          <div v-show="!folded.has(index)" class="mt-3.5 flex flex-col gap-3.5">
            <p v-if="item.purpose" class="text-read">{{ item.purpose }}</p>
            <p v-if="item.error" class="text-sm color-error">{{ item.error }}</p>
            <template v-for="(block, blockIndex) in item.blocks" :key="blockIndex">
              <Suspense v-if="block.text"><Markdown :value="block.text" class="chat-markdown text-read! leading-6!" @click="keepRepoLinks" /></Suspense>
              <DiagramBlock v-if="block.kind === 'diagram'" :source="block.body" />
              <pre v-else-if="block.kind === 'shape'" class="overflow-x-auto rounded-xl bg-code p-3 font-mono" style="font-size:var(--diffs-font-size,12.5px);line-height:var(--diffs-line-height,20px)"><code>{{ block.body }}</code></pre>
            </template>
            <p v-if="item.takeaway" class="rounded-xl bg-accent-wash p-3 text-sm color-accent-wash-ink font-medium">{{ item.takeaway }}</p>
            <div v-if="item.sources.length" class="flex flex-wrap gap-2"><button v-for="source in item.sources" :key="`${source.path}:${source.line}`" class="press-tint max-w-full break-all rounded-lg bg-paper-2 px-3 py-2 text-left text-xs font-mono" @click="emit('source', source.path)">{{ source.path }}:{{ source.line }}</button></div>
          </div>
        </article>
        <article v-if="result.glossary.length" class="rounded-[18px] bg-raised px-4 pb-5 pt-4.5 shadow-note"><h2 class="text-base font-semibold">{{ $t('mobile.glossary') }}</h2><div v-for="entry in result.glossary" :key="entry.term" class="mt-2 py-0.5"><h3 class="text-sm font-medium font-mono">{{ entry.term.replaceAll('`', '') }}</h3><Suspense><Markdown :value="entry.meaning" class="chat-markdown text-sm color-muted" /></Suspense></div></article>
      </div>
    </template>
    <div v-else class="mx-4 mt-3.5 flex flex-col gap-3 rounded-[18px] bg-raised px-4 pb-5 pt-4.5 shadow-note">
      <h1 class="text-xxl font-medium">{{ $t(busy ? 'mobile.tourWriting' : 'mobile.tourInvitation') }}</h1>
      <p class="text-read color-muted">{{ error || $t('mobile.tourIntroduction') }}</p>
      <button v-if="!busy" class="btn-primary" @click="setup = true">{{ $t('mobile.writeTour') }}</button>
      <button v-else class="btn-action" @click="cancel">{{ $t('common.cancel') }}</button>
    </div>
    <AiActivityLog v-if="activities.length" :activities="activities" :running="busy" />
    <AppModal v-model:open="sections" :title="$t('mobile.sections')"><div class="overflow-hidden rounded-card bg-raised shadow-note"><button v-for="(item, index) in result?.stops" :key="index" class="press-tint min-h-13 w-full flex items-center gap-3.5 border-b border-mute px-4 py-3 text-left last:border-0" @click="select(index)"><span class="shrink-0 text-5 color-accent-teal" :class="index < stop ? 'i-lu-okc' : 'i-lu-history'" /><span class="text-row">{{ item.title }}</span></button></div></AppModal>
    <AppModal v-model:open="moreOpen" :title="$t('mobile.tour')"><div class="flex flex-col gap-3"><button class="btn-text justify-start" @click="moreOpen = false; sections = true">{{ $t('mobile.sections') }}</button><button class="btn-text justify-start" :disabled="busy" @click="moreOpen = false; setup = true">{{ $t('mobile.rewriteTour') }}</button><button v-if="busy" class="btn-text justify-start" @click="cancel">{{ $t('common.cancel') }}</button></div></AppModal>
    <AppModal v-model:open="setup" :title="$t('mobile.writeTour')">
      <div class="flex flex-col gap-4">
        <p class="text-base">{{ $t('mobile.tourDescription') }}</p>
        <button class="rounded-card bg-raised p-4 text-left text-base" @click="settingsModalOpen = true"><span class="color-muted">{{ $t('mobile.tourModel') }}</span><span class="mt-1 block">{{ model?.model || settings.llm.provider }}</span></button>
        <p v-if="error" class="text-sm color-error">{{ error }}</p>
        <button v-if="model && getAiRpc()" class="btn-primary self-end" :disabled="busy" @click="start">{{ $t('mobile.startTour') }}</button>
        <button v-else class="btn-primary self-end" @click="setup = false; settingsModalOpen = true">{{ $t('common.settings') }}</button>
      </div>
    </AppModal>
  </div>
</template>
