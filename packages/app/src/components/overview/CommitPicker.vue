<script setup lang="ts">
import type { PrOverview } from '@pulls.review/core/github'
import type { CommitSelection } from './commit-selection'
import { onClickOutside } from '@vueuse/core'
import { computed, ref, useTemplateRef } from 'vue'
import { resolveCommitSelection } from './commit-selection'
import PrCommitList from './PrCommitList.vue'

const props = defineProps<{
  overview: PrOverview
  selection?: CommitSelection
}>()

const emit = defineEmits<{
  select: [selection: CommitSelection | undefined]
}>()

// In-flow rather than teleported, so it stays styled inside the embed's shadow root.
const open = ref(false)
const root = useTemplateRef<HTMLDivElement>('root')
onClickOutside(root, () => open.value = false)

const count = computed(() => {
  const range = props.selection && resolveCommitSelection(props.overview.commits, props.selection)
  return range ? range.end - range.start + 1 : undefined
})

function pick(selection: CommitSelection | undefined, extend = false) {
  emit('select', selection)
  if (!extend)
    open.value = false
}
</script>

<template>
  <div ref="root" class="relative">
    <button
      type="button"
      class="flex items-center gap-1.5 border border-base rounded px-2 py-0.5 transition coarse:min-h-11 hover:bg-hover"
      :class="selection ? 'color-active' : 'op-fade hover:op-100'"
      :aria-label="$t('overview.selection.picker')"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span class="i-ph:git-commit-duotone" aria-hidden="true" />
      <template v-if="!selection">
        {{ $t('overview.selection.all') }}
      </template>
      <template v-else-if="selection.from === selection.to">
        {{ $t('overview.selection.one', { sha: selection.from.slice(0, 7) }) }}
      </template>
      <template v-else-if="count">
        {{ $t('overview.selection.range', { n: count }, count) }}
      </template>
      <span v-else class="font-mono">{{ selection.from.slice(0, 7) }}..{{ selection.to.slice(0, 7) }}</span>
      <span class="i-ph:caret-down text-xs" aria-hidden="true" />
    </button>
    <div
      v-if="open"
      class="absolute left-0 top-full z-dropdown mt-1 max-h-[60vh] w-[min(32rem,calc(100vw-2rem))] flex flex-col overflow-auto border border-base rounded-lg bg-base p-1 shadow-lg"
    >
      <button
        type="button"
        class="press-tint flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm transition coarse:min-h-11 hover:bg-hover"
        :class="selection ? 'color-base' : 'color-active'"
        @click="pick(undefined)"
      >
        <span class="min-w-0 flex-1">{{ $t('overview.selection.all') }}</span>
        <span class="text-xs font-mono op-mute">{{ overview.commits.length + overview.commitsHidden }}</span>
        <span v-if="!selection" class="i-ph:check shrink-0 text-xs" aria-hidden="true" />
      </button>
      <div class="my-1 border-t border-base" />
      <PrCommitList
        compact
        :commits="overview.commits"
        :hidden="overview.commitsHidden"
        :url="overview.url"
        :selection="selection"
        @select="pick"
      />
      <p v-if="overview.commits.length > 1" class="px-2 pb-1 pt-2 text-xs op-mute">
        {{ $t('overview.commitList.hint') }}
      </p>
    </div>
  </div>
</template>
