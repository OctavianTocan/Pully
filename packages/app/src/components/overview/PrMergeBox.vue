<script setup lang="ts">
import type { PrOverview, PullRequestStateChange } from '@pulls.review/core/github'
import type { PrOverviewStore } from '../../stores/pr-overview-store'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import DisplayDate from '@antfu/design/components/Display/DisplayDate.vue'
import { computed, ref } from 'vue'
import PrStatusIcon from '../diff/PrStatusIcon.vue'
import GithubAvatar from '../GithubAvatar.vue'
import PrMergeModal from './PrMergeModal.vue'
import { CHECKS_ICON, REVIEW_DECISION_ICON } from './status-icons'

const props = defineProps<{
  store: PrOverviewStore
  overview: PrOverview
  document?: Document | ShadowRoot
}>()

const emit = defineEmits<{
  showChecks: []
}>()

const REVIEW_ICON = {
  approved: REVIEW_DECISION_ICON.approved,
  changes_requested: REVIEW_DECISION_ICON.changes_requested,
  commented: 'i-ph:chat-circle-dots-duotone op-fade',
  dismissed: 'i-ph:prohibit op-mute',
  pending: 'i-ph:hourglass-duotone op-mute',
} as const

const mergeOpen = ref(false)

const isOpen = computed(() => props.overview.state === 'open' || props.overview.state === 'draft')
const mergeability = computed(() => props.store.mergeability)

const stateActions = computed(() => {
  const { state, viewer } = props.overview
  const actions: { change: PullRequestStateChange, icon: string, danger?: boolean }[] = []
  if (state === 'draft' && viewer.canUpdate)
    actions.push({ change: 'ready', icon: 'i-ph:eye-duotone' })
  if (state === 'open' && viewer.canUpdate)
    actions.push({ change: 'draft', icon: 'i-ph:pencil-simple-duotone' })
  if (isOpen.value && viewer.canClose)
    actions.push({ change: 'close', icon: 'i-ph:x-circle-duotone', danger: true })
  if (state === 'closed' && viewer.canReopen)
    actions.push({ change: 'reopen', icon: 'i-ph:arrow-counter-clockwise-duotone' })
  return actions
})

// GitHub only grants this while the head is behind the base.
const canUpdateBranch = computed(() => isOpen.value && props.overview.viewer.canUpdateBranch)
</script>

<template>
  <aside class="flex flex-col gap-3 text-sm">
    <div v-if="overview.state === 'merged' || overview.state === 'closed'" class="flex items-center gap-1.5">
      <PrStatusIcon :state="overview.state" class="text-base!" />
      <i18n-t :keypath="`overview.state.${overview.state}`" tag="span" scope="global" class="font-medium">
        <template #time>
          <DisplayDate :date="(overview.state === 'merged' ? overview.mergedAt : overview.closedAt) ?? overview.createdAt" class="font-normal op-fade" />
        </template>
      </i18n-t>
    </div>
    <template v-else-if="mergeability?.allowed">
      <div v-if="mergeability.ready && !mergeability.blocker" class="flex items-center gap-1.5 font-medium">
        <span class="i-ph:check-circle-duotone shrink-0 text-green-600 dark:text-green-400" aria-hidden="true" />
        {{ $t('overview.merge.ready') }}
      </div>
      <div v-if="mergeability.blocker" class="flex items-start gap-1.5">
        <span
          :class="mergeability.blocker === 'checking' ? 'i-ph:circle-notch animate-spin op-fade' : 'i-ph:warning-duotone text-amber-600 dark:text-amber-400'"
          class="mt-0.5 shrink-0" aria-hidden="true"
        />
        {{ $t(`overview.merge.blocked.${mergeability.blocker}`) }}
      </div>
      <p v-if="mergeability.warning" class="text-xs op-fade">
        {{ $t(`overview.merge.${mergeability.warning}`) }}
      </p>
    </template>

    <button
      v-if="overview.checksSummary.state"
      type="button"
      class="flex items-center gap-1.5 text-left coarse:min-h-11 hover:underline"
      @click="emit('showChecks')"
    >
      <span :class="CHECKS_ICON[overview.checksSummary.state]" class="shrink-0 text-xs" aria-hidden="true" />
      <span class="op-fade">{{ $t(`pulls.checks.${overview.checksSummary.state}`) }}</span>
    </button>

    <div v-if="overview.reviewDecision || overview.latestReviews.length || overview.requestedReviewers.length" class="flex flex-col gap-1.5">
      <div v-if="overview.reviewDecision" class="flex items-center gap-1.5">
        <span :class="REVIEW_DECISION_ICON[overview.reviewDecision]" class="shrink-0" aria-hidden="true" />
        <span class="op-fade">{{ $t(`pulls.review.${overview.reviewDecision}`) }}</span>
      </div>
      <ul v-if="overview.latestReviews.length" class="flex flex-col gap-1 pl-1">
        <li v-for="review in overview.latestReviews" :key="review.author.login" class="flex items-center gap-1.5">
          <GithubAvatar :login="review.author.login" :avatar-url="review.author.avatarUrl" :size="16" />
          <span class="min-w-0 flex-1 truncate">{{ review.author.login }}</span>
          <span :class="REVIEW_ICON[review.state]" class="shrink-0" aria-hidden="true" />
        </li>
      </ul>
      <p v-if="overview.requestedReviewers.length" class="text-xs op-fade">
        {{ $t('overview.awaitingReview', { names: overview.requestedReviewers.join(', ') }) }}
      </p>
    </div>

    <div v-if="mergeability?.allowed || canUpdateBranch || stateActions.length" class="flex flex-col gap-2 border-t border-base pt-3">
      <ActionButton
        v-if="mergeability?.allowed"
        variant="primary"
        icon="i-ph:git-merge-duotone"
        :disabled="!mergeability.ready || !!store.busy"
        :loading="store.busy === 'merge'"
        @click="mergeOpen = true"
      >
        {{ $t('overview.merge.button') }}
      </ActionButton>
      <ActionButton
        v-if="canUpdateBranch"
        size="sm"
        icon="i-ph:arrows-merge-duotone"
        :disabled="!!store.busy"
        :loading="store.busy === 'update-branch'"
        @click="store.updateBranch()"
      >
        {{ $t('overview.actions.updateBranch') }}
      </ActionButton>
      <ActionButton
        v-for="action in stateActions"
        :key="action.change"
        size="sm"
        :icon="action.icon"
        :class="{ 'text-red-600 dark:text-red-400': action.danger }"
        :disabled="!!store.busy"
        :loading="store.busy === action.change"
        @click="store.changeState(action.change)"
      >
        {{ $t(`overview.actions.${action.change}`) }}
      </ActionButton>
    </div>

    <div v-if="store.actionError && !mergeOpen" role="alert" class="flex items-start gap-1 text-red-600 dark:text-red-400">
      <span class="min-w-0 flex-1">{{ $t('overview.actions.failed', { message: store.actionError }) }}</span>
      <ActionIconButton compact icon="i-ph:x" :label="$t('common.dismiss')" class="shrink-0 text-xs" @click="store.clearActionError()" />
    </div>

    <PrMergeModal v-if="mergeability?.allowed" v-model:open="mergeOpen" :store="store" :overview="overview" :document="document" />
  </aside>
</template>
