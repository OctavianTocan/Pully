<script setup lang="ts">
import type { MyPullRole } from '@pulls.review/core/local-rpc'
import type { TriageQueue } from './triage'
import { ROLE_ORDER, STALE_AFTER_DAYS, TRIAGE_QUEUES } from './triage'

defineProps<{
  queueCounts: Record<TriageQueue, number>
  roleCounts: Record<MyPullRole | 'all', number>
  /** Closed pull requests have nothing to triage, so only roles show for them. */
  showQueues: boolean
}>()

const queue = defineModel<TriageQueue>('queue', { required: true })
const role = defineModel<MyPullRole | 'all'>('role', { required: true })

const QUEUE_ICON: Record<TriageQueue, string> = {
  'needs-review': 'i-ph:eye-duotone',
  'ci-failing': 'i-ph:x-circle-duotone',
  'changes-requested': 'i-ph:note-pencil-duotone',
  'ready-to-merge': 'i-ph:git-merge-duotone',
  'stale': 'i-ph:hourglass-medium-duotone',
  'all': 'i-ph:stack-duotone',
}

const ROLES: (MyPullRole | 'all')[] = ['all', ...ROLE_ORDER]

const ITEM = 'flex shrink-0 snap-start items-center gap-2 whitespace-nowrap border border-base rounded-full px-3 py-1 text-sm transition coarse:min-h-11 hover:bg-hover md:border-none md:rounded-md md:px-2 md:py-1.5 md:text-left'
</script>

<template>
  <nav class="min-w-0 flex flex-col gap-3 md:gap-5">
    <div v-if="showQueues" class="flex flex-col gap-1">
      <h2 class="hidden px-2 text-xs font-medium op-fade md:block">
        {{ $t('local.home.queues') }}
      </h2>
      <div class="flex snap-x snap-mandatory gap-1.5 overflow-x-auto overscroll-x-contain pb-1 md:(flex-col snap-none gap-0.5 overflow-visible pb-0)">
        <button
          v-for="item in TRIAGE_QUEUES"
          :key="item"
          type="button"
          :class="[ITEM, queue === item ? 'bg-hover font-medium' : 'op-fade']"
          :aria-pressed="queue === item"
          :title="$t(`triage.hint.${item}`, { n: STALE_AFTER_DAYS })"
          @click="queue = item"
        >
          <span :class="[QUEUE_ICON[item], item === 'ci-failing' && queueCounts[item] ? 'text-red-600 dark:text-red-400' : '']" class="hidden shrink-0 md:inline" aria-hidden="true" />
          <span class="md:flex-1">{{ $t(`triage.queue.${item}`) }}</span>
          <span class="text-xs font-mono tabular-nums op-fade">{{ queueCounts[item] }}</span>
        </button>
      </div>
    </div>

    <div class="flex flex-col gap-1">
      <h2 class="hidden px-2 text-xs font-medium op-fade md:block">
        {{ $t('local.home.roles') }}
      </h2>
      <div class="flex snap-x snap-mandatory gap-1.5 overflow-x-auto overscroll-x-contain pb-1 md:(flex-col snap-none gap-0.5 overflow-visible pb-0)">
        <button
          v-for="item in ROLES"
          :key="item"
          type="button"
          :class="[ITEM, role === item ? 'bg-hover font-medium' : 'op-fade']"
          :aria-pressed="role === item"
          @click="role = item"
        >
          <span class="md:flex-1">{{ item === 'all' ? $t('local.home.all') : $t(`local.home.role.${item}`) }}</span>
          <span class="text-xs font-mono tabular-nums op-fade">{{ roleCounts[item] }}</span>
        </button>
      </div>
    </div>
  </nav>
</template>
