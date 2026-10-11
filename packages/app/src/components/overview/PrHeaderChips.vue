<script setup lang="ts">
import { computed } from 'vue'
import CheckCounts from './CheckCounts.vue'
import CommitPicker from './CommitPicker.vue'
import { usePrOverview } from './context'
import { REVIEW_DECISION_ICON } from './status-icons'

const ctx = usePrOverview()
const overview = computed(() => ctx?.store.overview)
const comments = computed(() => overview.value?.timeline.filter(item => item.kind === 'comment' || item.kind === 'review').length ?? 0)
</script>

<template>
  <template v-if="ctx && overview">
    <button
      v-if="overview.checksSummary.total"
      type="button"
      class="flex items-center gap-1.5 border border-base rounded px-2 py-0.5 transition coarse:min-h-11 hover:bg-hover"
      :title="overview.checksSummary.state && $t(`pulls.checks.${overview.checksSummary.state}`)"
      @click="ctx.show('checks')"
    >
      <span class="op-fade">{{ $t('overview.tab.checks') }}</span>
      <CheckCounts :summary="overview.checksSummary" />
    </button>
    <button
      type="button"
      class="flex items-center gap-1.5 border border-base rounded px-2 py-0.5 transition coarse:min-h-11 hover:bg-hover"
      :aria-label="$t('overview.tab.conversation')"
      @click="ctx.show('conversation')"
    >
      <span v-if="overview.reviewDecision" :class="REVIEW_DECISION_ICON[overview.reviewDecision]" aria-hidden="true" />
      <span v-if="overview.reviewDecision" class="op-fade">{{ $t(`pulls.review.${overview.reviewDecision}`) }}</span>
      <span class="i-ph:chat-circle-text-duotone op-fade" aria-hidden="true" />
      <span class="text-xs font-mono op-fade">{{ comments }}</span>
    </button>
    <CommitPicker :overview="overview" :selection="ctx.selection" @select="ctx.select($event)" />
  </template>
</template>
