<script setup lang="ts">
import type { MyPull } from '@pulls.review/core/local-rpc'
import { useNow } from '@vueuse/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { compactAge, compactCount } from '../../i18n/time-ago'
import { routeForRef } from '../../source-routes'

const props = defineProps<{
  pull: MyPull
}>()

const emit = defineEmits<{
  /** The pointer or focus rests on the row: the click may follow. */
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
    return { icon: 'h-2 w-2 rounded-full bg-amber', label: t('pulls.checks.pending') }
  return undefined
})
const requested = computed(() => props.pull.requestedFromMe ?? props.pull.role === 'review-requested')
const to = computed(() => routeForRef({ kind: 'github-pr', owner: props.pull.owner, repo: props.pull.repo, number: String(props.pull.number) }))
</script>

<!-- The phone's `PullRowContent`: the number in its own column, the title, then who wrote it, the checks, the size and the age. -->
<template>
  <RouterLink
    :to="to"
    class="press-tint relative flex gap-1.5 px-4 py-3.25 transition-colors hover:bg-hover"
    @pointerenter="emit('intent', pull)"
    @pointerleave="emit('leave', pull)"
    @focus="emit('intent', pull)"
    @blur="emit('leave', pull)"
  >
    <span class="w-13 shrink-0 text-num font-semibold font-display tabular-nums">#{{ pull.number }}</span>
    <span class="min-w-0 flex flex-1 flex-col gap-1.25">
      <span class="text-row">
        <span v-if="pull.isDraft" class="color-muted">[{{ $t('local.home.draft') }}] </span>{{ pull.title }}
      </span>
      <span class="flex items-center gap-1.5 text-sm color-muted">
        <span class="min-w-0 flex flex-1 items-center gap-1.5">
          <span v-if="requested" class="h-1.75 w-1.75 shrink-0 rounded-full bg-accent-teal" role="img" :aria-label="$t('triage.requested')" :title="$t('triage.requested')" />
          <span class="min-w-0 break-words">{{ pull.author }}</span>
          <span v-if="pull.stack" class="flex shrink-0 items-center gap-1 tabular-nums">
            <span class="i-lu-layers text-3.75" aria-hidden="true" />
            <template v-if="pull.stack.below">{{ $t('triage.stackedOn', { n: pull.stack.below }) }}</template>
          </span>
        </span>
        <span class="w-6 flex shrink-0 items-center">
          <span v-if="check" :class="check.icon" class="text-4.5" role="img" :aria-label="check.label" :title="check.label" />
        </span>
        <span v-if="pull.additions !== undefined" class="flex shrink-0 justify-end gap-1 text-right font-medium font-mono" :title="pull.changedFiles !== undefined ? $t('triage.files', pull.changedFiles) : undefined">
          <span class="w-12 color-success sm:w-14">+{{ compactCount(pull.additions) }}</span>
          <span class="w-9 color-error sm:w-10">−{{ compactCount(pull.deletions ?? 0) }}</span>
        </span>
        <time class="w-8 shrink-0 text-right tabular-nums" :datetime="pull.updatedAt" :title="new Date(pull.updatedAt).toLocaleString(locale)">
          {{ compactAge(new Date(pull.updatedAt), now.getTime()) }}
        </time>
      </span>
    </span>
    <span class="absolute left-4 right-4 top-0 h-px bg-border-mute" aria-hidden="true" />
  </RouterLink>
</template>
