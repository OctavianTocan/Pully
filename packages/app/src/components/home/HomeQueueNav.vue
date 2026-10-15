<script setup lang="ts">
import type { MyPullRole } from '@pulls.review/core/local-rpc'
import type { TriageQueue } from './triage'
import KitSoftChip from '../kit/KitSoftChip.vue'
import { ROLE_ORDER, STALE_AFTER_DAYS, TRIAGE_QUEUES } from './triage'

defineProps<{
  queueCounts: Record<TriageQueue, number>
  roleCounts: Record<MyPullRole | 'all', number>
  /** Closed pull requests have nothing to triage, so only roles show for them. */
  showQueues: boolean
}>()

const queue = defineModel<TriageQueue>('queue', { required: true })
const role = defineModel<MyPullRole | 'all'>('role', { required: true })

const ROLES: (MyPullRole | 'all')[] = ['all', ...ROLE_ORDER]
// The phone's `.chips`: one row of soft chips that scrolls sideways, each with the size of the list it would show.
const ROW = 'flex gap-2 overflow-x-auto overscroll-x-contain px-4 [scrollbar-width:none]'
</script>

<template>
  <nav class="min-w-0 flex flex-col gap-2">
    <div v-if="showQueues" role="tablist" :aria-label="$t('local.home.queues')" :class="ROW">
      <KitSoftChip
        v-for="item in TRIAGE_QUEUES"
        :key="item"
        :label="$t(`triage.queue.${item}`)"
        :count="queueCounts[item]"
        :selected="queue === item"
        :title="$t(`triage.hint.${item}`, { n: STALE_AFTER_DAYS })"
        @click="queue = item"
      />
    </div>
    <div role="tablist" :aria-label="$t('local.home.roles')" :class="ROW">
      <KitSoftChip
        v-for="item in ROLES"
        :key="item"
        :label="item === 'all' ? $t('local.home.all') : $t(`local.home.role.${item}`)"
        :count="roleCounts[item]"
        :selected="role === item"
        @click="role = item"
      />
      <slot />
    </div>
  </nav>
</template>
