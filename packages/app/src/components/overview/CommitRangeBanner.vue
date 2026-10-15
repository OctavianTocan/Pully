<script setup lang="ts">
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import { computed } from 'vue'
import { adjacentCommits, resolveCommitSelection } from './commit-selection'
import { usePrOverview } from './context'

const ctx = usePrOverview()
const commits = computed(() => ctx?.store.overview?.commits ?? [])
const range = computed(() => ctx?.selection && resolveCommitSelection(commits.value, ctx.selection))
const adjacent = computed(() => ctx?.selection ? adjacentCommits(commits.value, ctx.selection) : {})
// Commits GitHub did not return are the oldest ones, so they shift every position.
const offset = computed(() => ctx?.store.overview?.commitsHidden ?? 0)
const total = computed(() => commits.value.length + offset.value)
const headline = computed(() => range.value && range.value.start === range.value.end ? commits.value[range.value.start]?.headline : undefined)
</script>

<template>
  <div
    v-if="ctx?.selection"
    class="flex flex-wrap items-center gap-x-3 gap-y-2 border border-base rounded-lg bg-raised px-3 py-2 text-sm"
  >
    <span class="i-lu-commit shrink-0 op-fade" aria-hidden="true" />
    <span v-if="range && range.start === range.end" class="min-w-0 flex items-center gap-2">
      <span class="shrink-0">{{ $t('overview.selection.viewing', { index: range.start + 1 + offset, total }) }}</span>
      <span v-if="headline" class="min-w-0 truncate op-fade">{{ headline }}</span>
    </span>
    <span v-else-if="range">
      {{ $t('overview.selection.viewingRange', { start: range.start + 1 + offset, end: range.end + 1 + offset, total }) }}
    </span>
    <span v-else-if="ctx.selection.from === ctx.selection.to" class="font-mono">
      {{ $t('overview.selection.viewingSha', { sha: ctx.selection.from.slice(0, 7) }) }}
    </span>
    <span v-else class="font-mono">
      {{ $t('overview.selection.viewingShaRange', { from: ctx.selection.from.slice(0, 7), to: ctx.selection.to.slice(0, 7) }) }}
    </span>
    <div class="flex-auto" />
    <div class="flex items-center gap-1">
      <ActionIconButton
        compact
        icon="i-lu-prev"
        :label="$t('overview.selection.previous')"
        :tooltip="$t('overview.selection.previous')"
        :disabled="!adjacent.previous"
        @click="ctx.select(adjacent.previous)"
      />
      <ActionIconButton
        compact
        icon="i-lu-next"
        :label="$t('overview.selection.next')"
        :tooltip="$t('overview.selection.next')"
        :disabled="!adjacent.next"
        @click="ctx.select(adjacent.next)"
      />
    </div>
    <ActionButton size="sm" @click="ctx.select(undefined)">
      {{ $t('overview.selection.showAll') }}
    </ActionButton>
  </div>
</template>
