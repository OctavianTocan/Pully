<script setup lang="ts">
import type { PrCacheMeta } from '@pulls.review/core/cache'
import type { MyPull } from '@pulls.review/core/local-rpc'
import type { TourResult } from '@pulls.review/core/types'
import { useNow } from '@vueuse/core'
import { computed } from 'vue'
import { compactAge, compactCount } from '../../i18n/time-ago'
import { routeForRef } from '../../source-routes'
import { tourProgress, tourProgressKey } from '../../state/tour-progress'

const props = defineProps<{ meta: PrCacheMeta, tour: TourResult, pull?: MyPull }>()
const now = useNow({ interval: 60_000 })
const progress = computed(() => tourProgress.value[tourProgressKey(props.meta.key, props.tour.generatedAt)])
const completed = computed(() => !!props.tour.stops.length && (progress.value?.current ?? 0) >= props.tour.stops.length)
const fraction = computed(() => Math.min(1, (progress.value?.reached ?? 0) / Math.max(1, props.tour.stops.length)))
const check = computed(() => props.pull?.checks === 'failure' || props.pull?.mergeable === 'conflicting' ? 'i-lu-xc color-error' : props.pull?.checks === 'success' ? 'i-lu-okc color-success' : props.pull?.checks === 'pending' ? 'h-2 w-2 rounded-full bg-checks-pending' : '')
</script>

<template>
  <RouterLink :to="{ path: routeForRef(meta.ref), query: { view: 'tour' } }" class="press-tint relative flex items-center gap-3 px-4 py-3">
    <span class="h-10.5 w-10.5 flex shrink-0 items-center justify-center rounded-lg bg-sunken">
      <span v-if="completed" class="h-6 w-6 flex items-center justify-center rounded-full bg-success color-on-success"><span class="i-lu-tick text-4" /></span>
      <svg v-else viewBox="0 0 24 24" class="h-6 w-6 -rotate-90" aria-hidden="true"><circle cx="12" cy="12" r="9" fill="none" stroke="rgb(var(--c-border))" stroke-width="2" /><circle cx="12" cy="12" r="9" fill="none" stroke="rgb(var(--c-accent-teal))" stroke-width="2" :stroke-dasharray="`${56.55 * fraction} 56.55`" /></svg>
    </span>
    <span class="min-w-0 flex flex-1 flex-col gap-1">
      <span class="line-clamp-2 text-base">{{ meta.title }}</span>
      <span class="flex items-center gap-1.5 text-sm color-muted">
        <span class="min-w-10 shrink-0 color-base font-medium font-display tabular-nums">{{ meta.label || (meta.ref.kind === 'github-pr' ? `#${meta.ref.number}` : '') }}</span>
        <span class="min-w-0 flex-1 truncate">{{ pull?.author || (completed ? '' : progress?.title || $t(progress?.reached ? 'mobile.reading' : 'mobile.notStarted')) }}</span>
        <span v-if="pull" class="w-5.5 flex shrink-0 items-center"><span :class="check" class="text-4.5" /></span>
        <span v-if="pull?.additions !== undefined" class="flex shrink-0 gap-1 text-right font-medium font-mono"><span class="w-12 color-success">+{{ compactCount(pull.additions) }}</span><span class="w-12 color-error">−{{ compactCount(pull.deletions ?? 0) }}</span></span>
        <time class="w-8.5 shrink-0 text-right tabular-nums">{{ compactAge(new Date(pull?.updatedAt ?? tour.generatedAt), now.getTime()) }}</time>
      </span>
    </span>
    <span class="absolute left-4 right-4 top-0 h-px bg-border-mute" aria-hidden="true" />
  </RouterLink>
</template>
