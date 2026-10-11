<script setup lang="ts">
import type { MyPull, MyPullChecks, MyPullReviewDecision } from '@pulls.review/core/local-rpc'
import type { PullRequestState } from '@pulls.review/core/types'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatTimeAgo } from '../../i18n/time-ago'
import { routeForRef } from '../../source-routes'
import DiffStats from '../diff/DiffStats.vue'
import PrStatusIcon from '../diff/PrStatusIcon.vue'

const props = defineProps<{
  pull: MyPull
}>()

const emit = defineEmits<{
  /** The pointer or focus rests on the row: the click may follow. */
  intent: [pull: MyPull]
  leave: [pull: MyPull]
}>()

const { locale } = useI18n()

// Same glyphs and colors as the repo pull request list.
const CHECKS_ICON: Record<Exclude<MyPullChecks, 'none'>, string> = {
  success: 'i-ph:check-bold text-green-600 dark:text-green-400',
  failure: 'i-ph:x-bold text-red-600 dark:text-red-400',
  pending: 'i-ph:circle-fill text-amber-500',
}

const REVIEW: Record<MyPullReviewDecision, { icon: string, label: string }> = {
  'approved': { icon: 'i-ph:check-circle-duotone text-green-600 dark:text-green-400', label: 'pulls.review.approved' },
  'changes-requested': { icon: 'i-ph:x-circle-duotone text-red-600 dark:text-red-400', label: 'pulls.review.changes_requested' },
  'review-required': { icon: 'i-ph:eye-duotone op-fade', label: 'pulls.review.review_required' },
}

const status = computed<PullRequestState>(() => {
  const { state, isDraft, merged } = props.pull
  if (state === 'open')
    return isDraft ? 'draft' : 'open'
  return merged ? 'merged' : 'closed'
})
const checks = computed(() => props.pull.checks === 'none' ? undefined : props.pull.checks)
const review = computed(() => props.pull.reviewDecision && REVIEW[props.pull.reviewDecision])
const requested = computed(() => props.pull.requestedFromMe ?? props.pull.role === 'review-requested')
const to = computed(() => routeForRef({ kind: 'github-pr', owner: props.pull.owner, repo: props.pull.repo, number: String(props.pull.number) }))
</script>

<template>
  <RouterLink
    :to="to"
    class="flex flex-wrap items-center gap-x-3 gap-y-0.5 px-3 py-2 text-sm transition coarse:min-h-12 sm:flex-nowrap hover:bg-hover"
    @pointerenter="emit('intent', pull)"
    @pointerleave="emit('leave', pull)"
    @focus="emit('intent', pull)"
    @blur="emit('leave', pull)"
  >
    <PrStatusIcon :state="status" class="text-base" />
    <span class="min-w-10 shrink-0 text-xs font-mono op-fade">#{{ pull.number }}</span>
    <span class="min-w-0 flex-1 truncate max-sm:(line-clamp-2 order-last basis-full whitespace-normal break-words)">
      <span v-if="pull.isDraft" class="mr-1 text-xs op-fade">[{{ $t('local.home.draft') }}]</span>{{ pull.title }}
    </span>
    <span
      v-if="checks"
      :class="CHECKS_ICON[checks]"
      class="shrink-0 text-xs"
      role="img"
      :aria-label="$t(`pulls.checks.${checks}`)"
      :title="$t(`pulls.checks.${checks}`)"
    />
    <span
      v-if="review"
      :class="review.icon"
      class="shrink-0"
      role="img"
      :aria-label="$t(review.label)"
      :title="$t(review.label)"
    />
    <span v-if="requested" class="shrink-0 rounded bg-hover px-1.5 py-0.5 text-xs color-accent-teal">{{ $t('triage.requested') }}</span>
    <span
      v-if="pull.mergeable === 'conflicting'"
      class="shrink-0 rounded bg-hover px-1.5 py-0.5 text-xs text-red-600 dark:text-red-400"
      :title="$t('triage.conflictHint')"
    >{{ $t('triage.conflict') }}</span>
    <span
      v-if="pull.labels.length"
      class="hidden max-w-24 shrink-0 truncate rounded bg-hover px-1.5 py-0.5 text-xs op-fade lg:inline"
      :title="pull.labels.join(', ')"
    >{{ pull.labels[0] }}<template v-if="pull.labels.length > 1"> +{{ pull.labels.length - 1 }}</template></span>
    <DiffStats
      v-if="pull.additions !== undefined"
      class="hidden w-24 shrink-0 text-right md:inline"
      :additions="pull.additions"
      :deletions="pull.deletions"
      :title="pull.changedFiles !== undefined ? $t('triage.files', pull.changedFiles) : undefined"
    />
    <span class="hidden w-24 shrink-0 truncate text-xs op-fade sm:inline">{{ pull.author }}</span>
    <time class="min-w-20 shrink-0 whitespace-nowrap text-right text-xs op-fade max-sm:ml-auto" :datetime="pull.updatedAt" :title="new Date(pull.updatedAt).toLocaleString(locale)">
      {{ formatTimeAgo(new Date(pull.updatedAt), locale) }}
    </time>
  </RouterLink>
</template>
