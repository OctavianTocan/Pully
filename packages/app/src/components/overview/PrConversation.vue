<script setup lang="ts">
import type { PrOverview } from '@pulls.review/core/github'
import DisplayDate from '@antfu/design/components/Display/DisplayDate.vue'
import { computed, ref } from 'vue'
import GithubAvatar from '../GithubAvatar.vue'
import PrHtml from './PrHtml.vue'
import PrTimelineEntry from './PrTimelineEntry.vue'

const props = defineProps<{
  overview: PrOverview
}>()

const emit = defineEmits<{
  selectCommit: [oid: string]
}>()

const INITIAL_ITEMS = 30

const showAll = ref(false)
const earlier = computed(() => showAll.value ? 0 : Math.max(0, props.overview.timeline.length - INITIAL_ITEMS))
const items = computed(() => props.overview.timeline.slice(earlier.value))
</script>

<template>
  <div class="min-w-0 flex flex-col gap-3">
    <article class="border border-base rounded-lg">
      <header class="flex flex-wrap items-center gap-x-1.5 gap-y-1 border-b border-base bg-raised px-3 py-1.5 text-sm">
        <GithubAvatar v-if="overview.author" :login="overview.author.login" :avatar-url="overview.author.avatarUrl" :size="18" />
        <span class="font-medium">{{ overview.author?.login ?? 'ghost' }}</span>
        <span class="op-fade">{{ $t('overview.timeline.commented') }}</span>
        <DisplayDate :date="overview.createdAt" class="text-xs op-mute" />
      </header>
      <div class="px-3 py-2">
        <PrHtml v-if="overview.bodyHTML.trim()" :html="overview.bodyHTML" />
        <p v-else class="text-sm italic op-fade">
          {{ $t('overview.noDescription') }}
        </p>
      </div>
    </article>

    <a v-if="overview.timelineHidden" :href="overview.url" target="_blank" rel="noopener" class="px-1 text-xs op-fade hover:underline">
      {{ $t('overview.moreOnGithub', { n: overview.timelineHidden }) }}
    </a>
    <button v-if="earlier" type="button" class="self-start px-1 text-xs op-fade coarse:min-h-11 hover:underline" @click="showAll = true">
      {{ $t('overview.earlier', { n: earlier }, earlier) }}
    </button>

    <ol class="flex flex-col gap-3">
      <PrTimelineEntry v-for="item in items" :key="item.id" :item="item" @select-commit="emit('selectCommit', $event)" />
    </ol>
  </div>
</template>
