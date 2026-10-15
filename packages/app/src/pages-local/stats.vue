<script setup lang="ts">
import type { AnalyticsSummary } from '@pulls.review/core/analytics'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { computed, inject, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppHeader from '../components/AppHeader.vue'
import KitCapsuleTabs from '../components/kit/KitCapsuleTabs.vue'
import { useDocumentTitle } from '../composables/useDocumentTitle'
import { localRpcKey } from '../local/local-rpc-key'
import { usageStats } from '../state/usage-stats'

// Only `installLocal` registers this page, after providing the RPC client.
const rpc = inject(localRpcKey)!
const { t, n } = useI18n()

type Range = '7' | '30' | '90'
const range = ref<Range>('30')
const rangeOptions = computed(() => (['7', '30', '90'] as const).map(value => ({ value, label: t('stats.days', { n: value }) })))

const summary = shallowRef<AnalyticsSummary>()
const failed = ref(false)
let asked = 0

async function load() {
  const ask = ++asked
  failed.value = false
  try {
    const next = await rpc.call(LOCAL_RPC.analyticsSummary, { days: Number(range.value) }) as AnalyticsSummary
    if (ask === asked)
      summary.value = next
  }
  catch {
    // The numbers on screen belong to another range now, so they go and the retry shows.
    if (ask === asked) {
      summary.value = undefined
      failed.value = true
    }
  }
}
watch(range, load, { immediate: true })

const usd = (value: number) => n(value, { style: 'currency', currency: 'USD', maximumFractionDigits: value < 1 ? 2 : 0 })
const count = (value: number) => n(value)

/** A duration in the largest unit that keeps it short: 850 ms, 4.2 s, 3 min, 2 h. */
function duration(ms: number): string {
  if (ms < 1000)
    return `${Math.round(ms)} ms`
  if (ms < 60_000)
    return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`
  if (ms < 3_600_000)
    return `${Math.round(ms / 60_000)} min`
  return `${(ms / 3_600_000).toFixed(1)} h`
}

const headline = computed(() => {
  const totals = summary.value?.totals
  if (!totals)
    return []
  return [
    { label: t('stats.sessions'), value: count(totals.sessions) },
    { label: t('stats.prsOpened'), value: count(totals.prsOpened) },
    { label: t('stats.filesReviewed'), value: count(totals.filesReviewed) },
    { label: t('stats.aiRuns'), value: count(totals.aiRuns) },
    { label: t('stats.aiSpend'), value: usd(totals.costUsd) },
    { label: t('stats.crashes'), value: count(totals.crashes) },
    { label: t('stats.errors'), value: count(totals.errors) },
  ]
})

/** The per-day bars: pull requests opened, each day's bar as tall as its share of the busiest day. */
const bars = computed(() => {
  const days = summary.value?.days ?? []
  const top = Math.max(1, ...days.map(day => day.prsOpened))
  const width = 100 / Math.max(1, days.length)
  return days.map((day, index) => ({ ...day, x: index * width, width, height: day.prsOpened / top * 100 }))
})

const empty = computed(() => summary.value && summary.value.totals.events === 0)

useDocumentTitle(() => t('stats.title'))
</script>

<!-- Counts from the server's analytics day files: a row of headline numbers, the days as bars, then the lists. -->
<template>
  <div class="relative flex flex-col min-h-dvh">
    <AppHeader />

    <main class="mxa max-w-3xl w-full flex flex-col gap-6 px-4 pb-16 pt-3">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h1 class="text-xl font-medium font-display">
          {{ $t('stats.title') }}
        </h1>
        <KitCapsuleTabs v-model="range" :options="rangeOptions" :aria-label="$t('stats.range')" />
      </div>

      <p v-if="!usageStats" class="text-sm color-muted">
        {{ $t('stats.off') }}
      </p>

      <!-- The shape of the page while it loads, never a loading screen. -->
      <div v-if="!summary && !failed" class="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-busy="true">
        <span v-for="i in 8" :key="i" class="h-16 kit-skeleton rounded-2xl" />
      </div>

      <div v-else-if="!summary" class="flex flex-col items-center gap-1.5 py-12 text-center">
        <p class="text-row font-medium">
          {{ $t('stats.loadFailed') }}
        </p>
        <button type="button" class="btn-action mt-1" @click="load">
          <span class="i-lu-redo" aria-hidden="true" />
          {{ $t('common.retry') }}
        </button>
      </div>

      <p v-else-if="empty" class="py-12 text-center text-row font-medium">
        {{ $t('stats.empty') }}
      </p>

      <template v-else>
        <dl class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div v-for="item in headline" :key="item.label" class="flex flex-col gap-1 rounded-2xl bg-raised px-4 py-3">
            <dt class="text-sm color-muted">
              {{ item.label }}
            </dt>
            <dd class="text-xl font-medium tabular-nums">
              {{ item.value }}
            </dd>
          </div>
        </dl>

        <section class="flex flex-col gap-2">
          <h2 class="text-sm color-muted font-medium">
            {{ $t('stats.perDay') }} · {{ $t('stats.prsOpened') }}
          </h2>
          <svg viewBox="0 0 100 40" preserveAspectRatio="none" class="h-32 w-full color-primary" role="img" :aria-label="$t('stats.perDay')">
            <rect
              v-for="bar in bars"
              :key="bar.day"
              :x="bar.x + bar.width * 0.15"
              :y="40 - Math.max(bar.height * 0.4, bar.prsOpened ? 1 : 0.4)"
              :width="bar.width * 0.7"
              :height="Math.max(bar.height * 0.4, bar.prsOpened ? 1 : 0.4)"
              rx="0.6"
              fill="currentColor"
              :fill-opacity="bar.prsOpened ? 1 : 0.2"
            >
              <title>{{ bar.day }}: {{ count(bar.prsOpened) }}</title>
            </rect>
          </svg>
          <div class="flex justify-between text-xs color-faint tabular-nums">
            <span>{{ summary.from }}</span>
            <span>{{ summary.to }}</span>
          </div>
        </section>

        <section v-if="summary.screens.length" class="flex flex-col">
          <div class="flex gap-3 pb-1 text-sm">
            <h2 class="flex-1 color-muted font-medium">
              {{ $t('stats.screens') }}
            </h2>
            <span class="w-16 text-right text-xs color-faint">{{ $t('stats.views') }}</span>
            <span class="w-20 text-right text-xs color-faint">{{ $t('stats.time') }}</span>
          </div>
          <div v-for="row in summary.screens" :key="row.screen" class="flex items-center gap-3 border-b border-base py-2 text-sm">
            <span class="flex-1 truncate">{{ row.screen }}</span>
            <span class="w-16 text-right color-muted tabular-nums">{{ count(row.views) }}</span>
            <span class="w-20 text-right tabular-nums">{{ duration(row.seconds * 1000) }}</span>
          </div>
        </section>

        <section v-if="summary.tasks.length" class="flex flex-col">
          <div class="flex gap-3 pb-1 text-sm">
            <h2 class="flex-1 color-muted font-medium">
              {{ $t('stats.tasks') }}
            </h2>
            <span class="w-16 text-right text-xs color-faint">{{ $t('stats.runs') }}</span>
            <span class="w-16 text-right text-xs color-faint">{{ $t('stats.failed') }}</span>
            <span class="w-20 text-right text-xs color-faint">{{ $t('stats.cost') }}</span>
          </div>
          <div v-for="row in summary.tasks" :key="row.task" class="flex items-center gap-3 border-b border-base py-2 text-sm">
            <span class="flex-1 truncate">{{ row.task }}</span>
            <span class="w-16 text-right tabular-nums">{{ count(row.runs) }}</span>
            <span class="w-16 text-right color-muted tabular-nums">{{ count(row.failures) }}</span>
            <span class="w-20 text-right tabular-nums">{{ usd(row.costUsd) }}</span>
          </div>
        </section>

        <section v-if="summary.engines.length" class="flex flex-col">
          <div class="flex gap-3 pb-1 text-sm">
            <h2 class="flex-1 color-muted font-medium">
              {{ $t('stats.engines') }}
            </h2>
            <span class="w-16 text-right text-xs color-faint">{{ $t('stats.runs') }}</span>
            <span class="w-16 text-right text-xs color-faint">{{ $t('stats.median') }}</span>
            <span class="w-20 text-right text-xs color-faint">{{ $t('stats.cost') }}</span>
          </div>
          <div v-for="row in summary.engines" :key="`${row.engine} ${row.model}`" class="flex items-center gap-3 border-b border-base py-2 text-sm">
            <span class="min-w-0 flex-1 truncate">{{ row.engine }} · {{ row.model }}</span>
            <span class="w-16 text-right tabular-nums">{{ count(row.runs) }}</span>
            <span class="w-16 text-right color-muted tabular-nums">{{ duration(row.medianMs) }}</span>
            <span class="w-20 text-right tabular-nums">{{ usd(row.costUsd) }}</span>
          </div>
        </section>

        <section v-if="summary.calls.length" class="flex flex-col">
          <div class="flex gap-3 pb-1 text-xs color-faint">
            <h2 class="flex-1 text-sm color-muted font-medium">
              {{ $t('stats.slowest') }}
            </h2>
            <span class="w-16 text-right">{{ $t('stats.calls') }}</span>
            <span class="w-16 text-right">{{ $t('stats.median') }}</span>
            <span class="w-20 text-right">{{ $t('stats.p95') }}</span>
          </div>
          <div v-for="row in summary.calls.slice(0, 12)" :key="row.name" class="flex items-center gap-3 border-b border-base py-2 text-sm">
            <span class="min-w-0 flex-1 truncate text-xs font-mono">{{ row.name }}</span>
            <span class="w-16 text-right color-muted tabular-nums">{{ count(row.calls) }}</span>
            <span class="w-16 text-right tabular-nums">{{ duration(row.medianMs) }}</span>
            <span class="w-20 text-right color-muted tabular-nums">{{ duration(row.p95Ms) }}</span>
          </div>
        </section>

        <section v-if="summary.speed.length" class="flex flex-col">
          <div class="flex gap-3 pb-1 text-xs color-faint">
            <h2 class="flex-1 text-sm color-muted font-medium">
              {{ $t('stats.speed') }}
            </h2>
            <span class="w-16 text-right">{{ $t('stats.median') }}</span>
            <span class="w-20 text-right">{{ $t('stats.p95') }}</span>
          </div>
          <div v-for="row in summary.speed" :key="`${row.metric} ${row.app}`" class="flex items-center gap-3 border-b border-base py-2 text-sm">
            <span class="min-w-0 flex-1 truncate">{{ row.metric }} · {{ row.app }}</span>
            <span class="w-16 text-right tabular-nums">{{ duration(row.medianMs) }}</span>
            <span class="w-20 text-right color-muted tabular-nums">{{ duration(row.p95Ms) }}</span>
          </div>
        </section>

        <section class="grid gap-3 sm:grid-cols-2">
          <div class="flex flex-col gap-1 rounded-2xl bg-raised px-4 py-3 text-sm">
            <h2 class="color-muted font-medium">
              {{ $t('stats.github') }}
            </h2>
            <p class="tabular-nums">
              {{ $t('stats.calls') }} {{ count(summary.github.calls) }} · {{ $t('stats.failed') }} {{ count(summary.github.failures) }}
            </p>
            <p v-if="summary.github.lowestRateLeft !== undefined" class="color-muted tabular-nums">
              {{ $t('stats.rateLeft') }}: {{ count(summary.github.lowestRateLeft) }}
            </p>
          </div>
          <div class="flex flex-col gap-1 rounded-2xl bg-raised px-4 py-3 text-sm">
            <h2 class="color-muted font-medium">
              {{ $t('stats.storedDiffs') }}
            </h2>
            <p class="tabular-nums">
              {{ $t('stats.hit') }} {{ count(summary.cache.hit) }} · {{ $t('stats.refetch') }} {{ count(summary.cache.refetch) }} · {{ $t('stats.stale') }} {{ count(summary.cache.stale) }}
            </p>
          </div>
        </section>

        <section v-if="summary.errors.length" class="flex flex-col">
          <h2 class="pb-1 text-sm color-muted font-medium">
            {{ $t('stats.problems') }}
          </h2>
          <div v-for="row in summary.errors" :key="`${row.name} ${row.app} ${row.kind} ${row.message}`" class="flex flex-col gap-0.5 border-b border-base py-2 text-sm">
            <span class="flex items-center gap-2">
              <span class="min-w-0 flex-1 truncate font-medium">{{ row.kind }}</span>
              <span class="badge-muted">{{ row.app }}</span>
              <span class="w-10 text-right tabular-nums">{{ count(row.count) }}</span>
            </span>
            <span v-if="row.message" class="line-clamp-2 color-muted">{{ row.message }}</span>
          </div>
        </section>
      </template>
    </main>
  </div>
</template>
