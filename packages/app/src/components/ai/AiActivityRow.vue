<script setup lang="ts">
import type { ToolCategory, TrackedActivity } from './ai-activity'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { activityTiming, toolCategory } from './ai-activity'
import { lastLine } from './ai-format'

const props = defineProps<{
  activity: TrackedActivity
  /** The current time, ms since the epoch; drives the timing of a running step. */
  now: number
  /** Shows the row as a notice (rate limit, retry) rather than a step of the work. */
  warning?: boolean
  /** Leaves out the timing unless the step has gone quiet. */
  compact?: boolean
}>()

const { t } = useI18n()
const expanded = ref(false)

const ICONS: Record<ToolCategory, string> = {
  command: 'i-lu-terminal',
  edit: 'i-lu-edit',
  read: 'i-ph:file-text',
  search: 'i-lu-search',
  other: 'i-ph:wrench',
}

const running = computed(() => props.activity.status === 'running')
const failed = computed(() => props.activity.status === 'failed')
const isProse = computed(() => props.activity.kind === 'thinking' || props.activity.kind === 'text')

const icon = computed(() => {
  if (failed.value)
    return 'i-lu-xc'
  if (props.warning)
    return 'i-ph:warning'
  switch (props.activity.kind) {
    case 'thinking': return 'i-ph:brain'
    case 'text': return 'i-ph:pencil-simple-line'
    case 'status': return 'i-ph:info'
    default: return ICONS[toolCategory(props.activity.tool)]
  }
})

const fullText = computed(() => props.activity.text?.trim() ?? '')
const preview = computed(() => isProse.value && !failed.value ? lastLine(fullText.value) : '')
// Reasoning and prose collapse to their newest line; the rest stays one click away.
const expandable = computed(() => isProse.value && !failed.value && fullText.value !== '' && fullText.value !== preview.value)

const timing = computed(() => activityTiming(props.activity, props.now))
const timingLabel = computed(() => {
  const value = timing.value
  if (!value || (props.compact && value.state !== 'idle'))
    return undefined
  const liveness = value.state === 'idle'
    ? t('ai.idleFor', { since: value.since })
    : value.state === 'now' ? t('ai.activeNow') : t('ai.activeAgo', { since: value.since })
  return props.compact ? liveness : `${liveness} · ${t('ai.elapsed', { elapsed: value.elapsed })}`
})

const tone = computed(() => {
  if (failed.value)
    return 'text-red-600 dark:text-red-400'
  if (props.warning)
    return 'italic text-amber-700 dark:text-amber-400'
  if (props.activity.kind === 'status')
    return 'italic op-mute'
  return running.value ? 'color-base' : 'op-fade'
})
</script>

<template>
  <li class="min-w-0 flex flex-col gap-1">
    <component
      :is="expandable ? 'button' : 'div'"
      :type="expandable ? 'button' : undefined"
      :aria-expanded="expandable ? expanded : undefined"
      class="min-w-0 flex items-center gap-1.5 rounded text-left outline-none"
      :class="[tone, expandable ? 'hover:bg-hover focus-visible:ring-2 focus-visible:ring-primary-500/40 -mx-1 px-1 coarse:min-h-11' : '']"
      @click="expandable && (expanded = !expanded)"
    >
      <span :class="icon" class="shrink-0" aria-hidden="true" />
      <span class="min-w-0 flex-1 truncate max-md:(line-clamp-2 whitespace-normal break-words)">
        <span :class="running ? 'ai-shimmer' : ''">{{ activity.title }}</span>
        <span v-if="preview" class="op-mute"> {{ preview }}</span>
      </span>
      <span
        v-if="timingLabel"
        class="shrink-0 text-xs not-italic tabular-nums"
        :class="timing?.state === 'idle' ? 'text-amber-700 dark:text-amber-400' : 'op-mute'"
      >{{ timingLabel }}</span>
      <span v-if="expandable" :class="expanded ? 'i-ph:caret-up' : 'i-lu-chevd'" class="shrink-0 text-xs op-mute" aria-hidden="true" />
    </component>
    <p v-if="failed && fullText" class="ml-5 whitespace-pre-wrap break-words text-xs text-red-600 dark:text-red-400">
      {{ fullText }}
    </p>
    <div v-if="expanded" class="ml-5 max-h-60 of-y-auto whitespace-pre-wrap break-words rounded bg-raised p-2 text-xs op-fade">
      {{ fullText }}
    </div>
  </li>
</template>
