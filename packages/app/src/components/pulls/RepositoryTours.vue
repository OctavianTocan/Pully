<script setup lang="ts">
import type { PrCacheMeta } from '@pulls.review/core/cache'
import type { TourResult } from '@pulls.review/core/types'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useAppContext } from '../../app-context'
import HomeTourRow from '../home/HomeTourRow.vue'

const props = defineProps<{ owner: string, repo: string, query: string }>()
const { cache } = useAppContext()
const entries = shallowRef<{ meta: PrCacheMeta, tour: TourResult }[]>([])
const error = ref<string>()
const visible = computed(() => entries.value.filter(entry => `${entry.meta.title} ${entry.meta.label}`.toLowerCase().includes(props.query.trim().toLowerCase())))
onMounted(async () => {
  try {
    const metas = (await cache.diffs.listRecent(400)).filter(meta => meta.ref.kind === 'github-pr' && meta.ref.owner === props.owner && meta.ref.repo === props.repo)
    const found = await Promise.all(metas.map(async meta => ({ meta, tour: await cache.tours.latest(meta.key, meta.headSha) })))
    entries.value = found.filter((entry): entry is { meta: PrCacheMeta, tour: TourResult } => !!entry.tour)
  }
  catch (failure) { error.value = failure instanceof Error ? failure.message : String(failure) }
})
</script>

<template>
  <ul v-if="visible.length" class="pt-3">
    <li v-for="entry in visible" :key="entry.meta.key">
      <HomeTourRow :meta="entry.meta" :tour="entry.tour" />
    </li>
  </ul>
  <div v-else class="px-8 py-12 text-center"><p class="text-lg font-medium">{{ $t('mobile.noTours') }}</p><p class="mt-1.5 text-sm color-muted">{{ error || $t('mobile.tourHint') }}</p></div>
</template>
