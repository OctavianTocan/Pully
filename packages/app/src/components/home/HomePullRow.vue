<script setup lang="ts">
import type { MyPull } from '@pulls.review/core/local-rpc'
import { useNow } from '@vueuse/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { compactAge, compactCount } from '../../i18n/time-ago'
import { routeForRef } from '../../source-routes'

const props = defineProps<{
  pull: MyPull
  risk?: boolean
  repoLabel?: string
  stackLayers?: MyPull[]
}>()

const emit = defineEmits<{
  /** The pointer or focus rests on the row: the click may follow. */
  stack: [layers: MyPull[]]
  intent: [pull: MyPull]
  leave: [pull: MyPull]
}>()

const { locale, t } = useI18n()
const now = useNow({ interval: 60_000 })

/**
 * The phone's `CheckMark`: a red cross when checks fail or the branch conflicts, a green tick when
 * they pass, an amber dot while they run.
 */
const check = computed(() => {
  const { checks, mergeable } = props.pull
  if (checks === 'failure' || mergeable === 'conflicting')
    return { icon: 'i-lu-xc color-error', label: t(checks === 'failure' ? 'pulls.checks.failure' : 'triage.conflict') }
  if (checks === 'success')
    return { icon: 'i-lu-okc color-success', label: t('pulls.checks.success') }
  if (checks === 'pending')
    return { icon: 'h-2 w-2 rounded-full bg-checks-pending', label: t('pulls.checks.pending') }
  return undefined
})
const requested = computed(() => props.pull.requestedFromMe ?? props.pull.role === 'review-requested')
const state = computed(() => props.pull.merged ? 'merged' : props.pull.state === 'closed' ? 'closed' : props.pull.isDraft ? 'draft' : 'open')
const tile = computed(() => ({
  open: { icon: 'i-lu-pr-open', color: 'success' },
  draft: { icon: 'i-lu-pr-draft', color: 'muted' },
  merged: { icon: 'i-lu-pr-merged', color: 'purple' },
  closed: { icon: 'i-lu-pr-closed', color: 'error' },
})[state.value])
const to = computed(() => routeForRef({ kind: 'github-pr', owner: props.pull.owner, repo: props.pull.repo, number: String(props.pull.number) }))
</script>

<!--
  The phone's `PullRowContent`: the number in its own column, the title, then who wrote it, the checks, the size and the age.
  A collapsed stack uses the same frame and facts as a pull request.
-->
<template>
  <RouterLink
    :to="to"
    class="press-tint relative flex items-center gap-3 px-4 py-3 transition-colors hover:bg-hover"
    @click="stackLayers ? ($event.preventDefault(), emit('stack', stackLayers)) : undefined"
    @pointerenter="emit('intent', pull)"
    @pointerleave="emit('leave', pull)"
    @focus="emit('intent', pull)"
    @blur="emit('leave', pull)"
  >
    <span class="[--tile-alpha:0.12] h-10.5 w-10.5 flex shrink-0 items-center justify-center rounded-lg dark:[--tile-alpha:0.18]" :style="{ color: `rgb(var(--c-${tile.color}))`, backgroundColor: stackLayers ? 'transparent' : `rgb(var(--c-${tile.color}) / var(--tile-alpha))`, boxShadow: stackLayers ? `inset 0 0 0 1px rgb(var(--c-${tile.color}) / 0.7)` : undefined }" role="img" :aria-label="$t(`mobile.state.${state}`)"><span :class="stackLayers ? 'i-lu-layers' : tile.icon" class="text-6" aria-hidden="true" /></span>
    <span class="min-w-0 flex flex-1 flex-col gap-1">
      <span class="line-clamp-2 text-base">{{ pull.title }}</span>
      <span class="flex items-center gap-1.5 text-sm color-muted">
        <span class="min-w-10 shrink-0 color-base font-medium font-display tabular-nums">#{{ pull.number }}</span>
        <span class="min-w-0 flex flex-1 items-center gap-1.5">
          <span v-if="stackLayers" class="flex shrink-0 items-center gap-0.75"><span class="i-lu-layers text-3.5" />{{ stackLayers.length }}</span>
          <span v-else-if="pull.stack" class="flex shrink-0 items-center gap-1 tabular-nums" :class="risk && (pull.jev?.risk ?? 0) >= 2.5 ? 'color-amber' : ''">
            <span class="i-lu-layers text-3.5" aria-hidden="true" />
            <template v-if="pull.stack.size > 1">{{ pull.stack.position }}/{{ pull.stack.size }}</template>
          </span>
          <span v-else-if="risk && pull.jev" class="shrink-0" :class="pull.jev.risk >= 2.5 ? 'color-amber' : ''">{{ $t(`mobile.riskWords.${Math.max(0, Math.min(3, Math.round(pull.jev.risk)))}`) }}</span>
          <span v-if="requested" class="h-1.75 w-1.75 shrink-0 rounded-full bg-accent-teal" role="img" :aria-label="$t('triage.requested')" :title="$t('triage.requested')" />
          <span class="min-w-0 truncate">{{ repoLabel ? `${repoLabel} · ${pull.author}` : pull.author }}</span>
        </span>
        <span class="w-5.5 flex shrink-0 items-center">
          <span v-if="check" :class="check.icon" class="text-4.5" role="img" :aria-label="check.label" :title="check.label" />
        </span>
        <span v-if="pull.additions !== undefined" class="flex shrink-0 justify-end gap-1 text-right font-medium font-mono" :title="pull.changedFiles !== undefined ? $t('triage.files', pull.changedFiles) : undefined">
          <span class="w-12 color-success">+{{ compactCount(pull.additions) }}</span>
          <span class="w-12 color-error">−{{ compactCount(pull.deletions ?? 0) }}</span>
        </span>
        <time class="w-8.5 shrink-0 text-right tabular-nums" :datetime="pull.updatedAt" :title="new Date(pull.updatedAt).toLocaleString(locale)">
          {{ compactAge(new Date(pull.updatedAt), now.getTime()) }}
        </time>
      </span>
    </span>
    <span class="absolute left-4 right-4 top-0 h-px bg-border-mute" aria-hidden="true" />
  </RouterLink>
</template>
