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
  /** A desktop sidebar's lists instead of the phone's chip rows. */
  vertical?: boolean
}>()

const queue = defineModel<TriageQueue>('queue', { required: true })
const role = defineModel<MyPullRole | 'all'>('role', { required: true })

const ROLES: (MyPullRole | 'all')[] = ['all', ...ROLE_ORDER]
// The phone's `.chips`: one row of soft chips that scrolls sideways, each with the size of the list it would show.
const ROW = 'flex gap-2 overflow-x-auto overscroll-x-contain px-4 [scrollbar-width:none]'
// The same choice as a sidebar list: a full-width pill per item, filled like a chosen chip.
const ITEM = 'press-tint h-10 w-full flex items-center justify-between gap-3 rounded-full px-4 text-left text-sm color-base transition-colors'
</script>

<template>
  <nav v-if="vertical" class="flex flex-col gap-5">
    <div v-if="showQueues" role="tablist" :aria-label="$t('local.home.queues')" class="flex flex-col gap-0.5">
      <h2 class="px-4 pb-1 text-sm color-muted font-medium">
        {{ $t('local.home.queues') }}
      </h2>
      <button
        v-for="item in TRIAGE_QUEUES"
        :key="item"
        type="button"
        role="tab"
        :aria-selected="queue === item"
        :class="[ITEM, queue === item ? 'bg-ambient' : 'hover:bg-hover']"
        :title="$t(`triage.hint.${item}`, { n: STALE_AFTER_DAYS })"
        @click="queue = item"
      >
        <span class="truncate">{{ $t(`triage.queue.${item}`) }}</span>
        <span class="color-muted tabular-nums">{{ queueCounts[item] }}</span>
      </button>
    </div>
    <div role="tablist" :aria-label="$t('local.home.roles')" class="flex flex-col gap-0.5">
      <h2 class="px-4 pb-1 text-sm color-muted font-medium">
        {{ $t('local.home.roles') }}
      </h2>
      <button
        v-for="item in ROLES"
        :key="item"
        type="button"
        role="tab"
        :aria-selected="role === item"
        :class="[ITEM, role === item ? 'bg-ambient' : 'hover:bg-hover']"
        @click="role = item"
      >
        <span class="truncate">{{ item === 'all' ? $t('local.home.all') : $t(`local.home.role.${item}`) }}</span>
        <span class="color-muted tabular-nums">{{ roleCounts[item] }}</span>
      </button>
    </div>
  </nav>

  <nav v-else class="min-w-0 flex flex-col gap-2">
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
