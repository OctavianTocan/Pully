<script setup lang="ts">
import type { OverviewTab } from './context'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import FeedbackLoading from '@antfu/design/components/Feedback/FeedbackLoading.vue'
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import CheckCounts from './CheckCounts.vue'
import { selectCommit } from './commit-selection'
import { usePrOverview } from './context'
import PrChecks from './PrChecks.vue'
import PrCommitList from './PrCommitList.vue'
import PrConversation from './PrConversation.vue'
import PrMergeBox from './PrMergeBox.vue'

defineProps<{
  document?: Document | ShadowRoot
}>()

const ctx = usePrOverview()
const overview = computed(() => ctx?.store.overview)

const root = useTemplateRef<HTMLElement>('root')
watch(() => ctx?.focusRequest, () => nextTick(() => root.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })))

const tabs = computed(() => {
  const current = overview.value
  const conversation = current?.timeline.filter(item => item.kind === 'comment' || item.kind === 'review').length
  const commits = current && current.commits.length + current.commitsHidden
  return [
    { value: 'conversation', icon: 'i-ph:chat-circle-text', count: conversation },
    { value: 'commits', icon: 'i-lu-commit', count: commits },
    { value: 'checks', icon: 'i-ph:list-checks', count: undefined },
  ] satisfies { value: OverviewTab, icon: string, count?: number }[]
})

function openTab(tab: OverviewTab) {
  ctx?.setTab(tab)
  ctx?.setOpen(true)
}
</script>

<template>
  <section
    v-if="ctx"
    ref="root"
    class="scroll-mt-[calc(var(--diffs-header-height,0px)+12px)] border border-base rounded-lg"
  >
    <div class="flex items-center gap-1 px-2 py-1.5 md:flex-wrap" :class="{ 'border-b border-base': ctx.open }">
      <ActionIconButton
        compact
        :icon="ctx.open ? 'i-lu-chevd' : 'i-lu-next'"
        :label="$t(ctx.open ? 'overview.hide' : 'overview.show')"
        :tooltip="$t(ctx.open ? 'overview.hide' : 'overview.show')"
        :aria-expanded="ctx.open"
        class="op-fade hover:op-100"
        @click="ctx.setOpen(!ctx.open)"
      />
      <div role="tablist" class="min-w-0 flex snap-x snap-mandatory items-center gap-1 overflow-x-auto overscroll-x-contain md:(flex-wrap snap-none overflow-visible)">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          type="button"
          role="tab"
          :aria-selected="ctx.open && ctx.tab === tab.value"
          class="flex shrink-0 snap-start items-center gap-1.5 whitespace-nowrap rounded-md px-2.5 py-1 text-sm transition coarse:min-h-11"
          :class="ctx.open && ctx.tab === tab.value ? 'bg-active color-active' : 'op-fade hover:bg-hover hover:op-100'"
          @click="openTab(tab.value)"
        >
          <span :class="tab.icon" aria-hidden="true" />
          {{ $t(`overview.tab.${tab.value}`) }}
          <span v-if="tab.count" class="text-xs font-mono op-mute">{{ tab.count }}</span>
          <CheckCounts v-if="tab.value === 'checks' && overview?.checksSummary.total" :summary="overview.checksSummary" />
        </button>
      </div>
      <div class="flex-auto" />
      <ActionIconButton
        v-if="overview"
        compact
        icon="i-carbon-logo-github"
        :href="overview.url"
        target="_blank"
        rel="noopener"
        :label="$t('overview.viewOnGithub')"
        :tooltip="$t('overview.viewOnGithub')"
        class="op-fade hover:op-100"
      />
    </div>

    <div v-if="ctx.open" class="p-3">
      <div v-if="overview" class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_16rem]">
        <PrMergeBox
          :store="ctx.store"
          :overview="overview"
          :document="document"
          class="lg:col-start-2 lg:row-start-1"
          @show-checks="openTab('checks')"
        />
        <div class="min-w-0 lg:col-start-1 lg:row-start-1">
          <PrConversation
            v-if="ctx.tab === 'conversation'"
            :overview="overview"
            @select-commit="ctx.select(selectCommit($event))"
          />
          <PrCommitList
            v-else-if="ctx.tab === 'commits'"
            :commits="overview.commits"
            :hidden="overview.commitsHidden"
            :url="overview.url"
            :selection="ctx.selection"
            @select="ctx.select($event)"
          />
          <PrChecks v-else :checks="overview.checks" :summary="overview.checksSummary" :url="overview.url" />
        </div>
      </div>
      <div v-else-if="ctx.store.error" class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span class="i-ph:warning shrink-0 text-amber-600 dark:text-amber-400" aria-hidden="true" />
        <span>{{ $t('overview.loadFailed') }}</span>
        <span class="min-w-0 break-words op-fade">{{ ctx.store.error.message }}</span>
        <ActionButton size="sm" @click="ctx.store.refresh()">
          {{ $t('common.retry') }}
        </ActionButton>
      </div>
      <p v-else-if="ctx.store.available === false" class="text-sm op-fade">
        {{ $t('overview.tokenRequired') }}
      </p>
      <FeedbackLoading v-else :text="$t('overview.loading')" />
    </div>
  </section>
</template>
