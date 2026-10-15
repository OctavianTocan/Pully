<script setup lang="ts">
import type { CheckBucket, PrCheck, PrChecksSummary } from '@pulls.review/core/github'
import { computed, ref } from 'vue'
import { CHECK_BUCKET_ICON } from './status-icons'

const props = defineProps<{
  /** Failing first, as the overview orders them. */
  checks: PrCheck[]
  summary: PrChecksSummary
  /** The PR's GitHub page. */
  url: string
}>()

const BUCKETS: CheckBucket[] = ['failing', 'pending', 'passing', 'skipped']

const groups = computed(() => BUCKETS
  .map(bucket => ({ bucket, checks: props.checks.filter(check => check.bucket === bucket) }))
  .filter(group => group.checks.length))

// Passing and skipped runs are noise while something still needs attention.
const collapsed = ref(new Set<CheckBucket>(
  props.summary.failing + props.summary.pending > 0 ? ['passing', 'skipped'] : ['skipped'],
))

function toggle(bucket: CheckBucket) {
  const next = new Set(collapsed.value)
  if (!next.delete(bucket))
    next.add(bucket)
  collapsed.value = next
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <p v-if="!checks.length" class="px-1 text-sm op-fade">
      {{ $t('overview.checks.none') }}
    </p>
    <section v-for="group in groups" :key="group.bucket" class="flex flex-col">
      <button
        type="button"
        class="flex items-center gap-1.5 rounded-md px-1 py-1 text-left text-sm font-medium hover:bg-hover"
        :aria-expanded="!collapsed.has(group.bucket)"
        @click="toggle(group.bucket)"
      >
        <span :class="collapsed.has(group.bucket) ? 'i-lu-next' : 'i-lu-chevd'" class="op-fade" aria-hidden="true" />
        <span :class="CHECK_BUCKET_ICON[group.bucket]" class="text-xs" aria-hidden="true" />
        {{ $t(`overview.checks.${group.bucket}`, { n: group.checks.length }) }}
      </button>
      <ul v-if="!collapsed.has(group.bucket)" class="flex flex-col">
        <li v-for="check in group.checks" :key="check.id" class="flex items-center gap-2 rounded-md py-1 pl-7 pr-1 text-sm hover:bg-hover">
          <span :class="CHECK_BUCKET_ICON[check.bucket]" class="shrink-0 text-xs" :title="check.conclusion" />
          <div class="min-w-0 flex flex-1 flex-col">
            <span class="truncate">
              <span v-if="check.group" class="op-fade">{{ check.group }} / </span>{{ check.name }}
            </span>
            <span v-if="check.summary" class="truncate text-xs op-mute">{{ check.summary }}</span>
          </div>
          <span v-if="check.required" class="shrink-0 border border-base rounded px-1 text-xs op-fade">{{ $t('overview.checks.required') }}</span>
          <a v-if="check.url" :href="check.url" target="_blank" rel="noopener" class="shrink-0 text-xs op-fade hover:underline">{{ $t('overview.checks.details') }}</a>
        </li>
      </ul>
    </section>
    <a v-if="summary.hidden" :href="`${url}/checks`" target="_blank" rel="noopener" class="px-1 text-xs op-fade hover:underline">
      {{ $t('overview.moreOnGithub', { n: summary.hidden }) }}
    </a>
  </div>
</template>
