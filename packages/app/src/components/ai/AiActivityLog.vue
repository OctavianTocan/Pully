<script setup lang="ts">
import type { TrackedActivity } from './ai-activity'
import { useNow } from '@vueuse/core'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { summarizeTools } from './ai-activity'
import { formatClockDuration } from './ai-format'
import AiActivityRow from './AiActivityRow.vue'
import AiWorkedFor from './AiWorkedFor.vue'

const props = withDefaults(defineProps<{
  /** The work log, oldest first. */
  activities: readonly TrackedActivity[]
  /** Whether the work is still going; settled work folds into a "Worked for" disclosure. */
  running: boolean
  /** When the work started, ms since the epoch; defaults to when the first step started. */
  startedAt?: number
  /** When the work settled, ms since the epoch; defaults to when the last step ended. */
  endedAt?: number
  /** How the work ended, for the settled heading. */
  outcome?: 'done' | 'failed' | 'stopped'
  /** Fewer rows and no per-step timing, for narrow places like the chat. */
  compact?: boolean
}>(), { outcome: 'done' })

const { t } = useI18n()
const { now, pause, resume } = useNow({ interval: 1000, controls: true })
watch(() => props.running, running => running ? resume() : pause(), { immediate: true })

const showAll = ref(false)
const open = ref(false)

const cap = computed(() => props.compact ? 3 : 6)
const hiddenCount = computed(() => showAll.value ? 0 : Math.max(0, props.activities.length - cap.value))
const visibleRows = computed(() => props.activities.slice(hiddenCount.value))

// The server opens every job with a `start` status ("Starting Claude Code…" → "Started Claude (model)"); it is no notice.
function isWarning(activity: TrackedActivity, index: number): boolean {
  return activity.kind === 'status' && activity.status === 'done' && activity.id !== 'start' && index > 0
}

const tail = computed(() => {
  if (!props.running)
    return undefined
  const last = props.activities.at(-1)
  if (!last)
    return t('ai.loading')
  return last.status === 'running' ? undefined : t('ai.verb.thinking.now')
})

const start = computed(() => props.startedAt ?? props.activities[0]?.startedAt)
const end = computed(() => {
  if (props.endedAt !== undefined)
    return props.endedAt
  const ends = props.activities.map(activity => activity.endedAt ?? activity.seenAt)
  return ends.length ? Math.max(...ends) : undefined
})

const duration = computed(() => {
  if (start.value === undefined)
    return formatClockDuration(0)
  const until = props.running ? now.value.getTime() : end.value ?? start.value
  return formatClockDuration(until - start.value)
})

const settledLabel = computed(() => {
  const named = { duration: duration.value }
  if (props.outcome === 'failed')
    return t('ai.failedAfter', named)
  if (props.outcome === 'stopped')
    return t('ai.stoppedAfter', named)
  return t('ai.workedFor', named)
})

const summary = computed(() => summarizeTools(props.activities)
  .map(({ category, count }) => t(`ai.groups.${category}`, { n: count }, count))
  .join(', '))

const nowMs = computed(() => now.value.getTime())
</script>

<template>
  <div class="min-w-0 flex flex-col gap-2" :class="compact ? 'text-xs' : 'text-sm'">
    <template v-if="running">
      <div class="flex items-center gap-1.5 op-fade">
        <span class="i-ph:hammer shrink-0" aria-hidden="true" />
        <span class="tabular-nums">{{ $t('ai.workingFor', { duration }) }}</span>
      </div>
      <div class="border-t border-base" />
      <button
        v-if="hiddenCount || showAll"
        type="button"
        class="self-start rounded text-xs op-mute outline-none transition coarse:min-h-11 hover:op100 focus-visible:ring-2 focus-visible:ring-primary-500/40"
        @click="showAll = !showAll"
      >
        {{ showAll ? $t('ai.showLess') : $t('ai.more', { n: hiddenCount }) }}
      </button>
      <ul v-if="visibleRows.length" class="flex flex-col gap-1.5" role="log" aria-relevant="additions">
        <AiActivityRow
          v-for="(activity, index) in visibleRows"
          :key="activity.id"
          :activity="activity"
          :now="nowMs"
          :warning="isWarning(activity, index + hiddenCount)"
          :compact="compact"
        />
      </ul>
      <div v-if="tail" class="flex items-center gap-1.5 color-base" role="status">
        <span class="i-ph:brain shrink-0" aria-hidden="true" />
        <span class="ai-shimmer truncate">{{ tail }}</span>
      </div>
    </template>

    <template v-else-if="activities.length || startedAt !== undefined">
      <AiWorkedFor
        v-model:open="open"
        :label="settledLabel"
        :summary="summary"
        :failed="outcome === 'failed'"
        :expandable="activities.length > 0"
      >
        <ul class="flex flex-col gap-1.5 pl-1">
          <AiActivityRow
            v-for="(activity, index) in activities"
            :key="activity.id"
            :activity="activity"
            :now="nowMs"
            :warning="isWarning(activity, index)"
            :compact="compact"
          />
        </ul>
      </AiWorkedFor>
      <div v-if="!compact" class="border-t border-base" />
    </template>
  </div>
</template>
