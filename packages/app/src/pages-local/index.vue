<script setup lang="ts">
import type { MyPull, MyPullRole, MyPullsSnapshot, MyPullState } from '@pulls.review/core/local-rpc'
import type { TriageQueue } from '../components/home/triage'
import { createGithubSource } from '@pulls.review/core/github'
import { LOCAL_RPC, MY_PULL_ROLES, MY_PULL_STATES } from '@pulls.review/core/local-rpc'
import { useNow } from '@vueuse/core'
import { computed, inject, ref, shallowReactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppContext } from '../app-context'
import AppHeader from '../components/AppHeader.vue'
import HomePullRow from '../components/home/HomePullRow.vue'
import HomeQueueNav from '../components/home/HomeQueueNav.vue'
import HomeSyncStatus from '../components/home/HomeSyncStatus.vue'
import { filterPulls, groupByRepo, TRIAGE_QUEUES } from '../components/home/triage'
import { deviceStorage, useMyPulls } from '../components/home/use-my-pulls'
import KitCapsuleTabs from '../components/kit/KitCapsuleTabs.vue'
import KitGroupHeading from '../components/kit/KitGroupHeading.vue'
import KitSearchPill from '../components/kit/KitSearchPill.vue'
import { useDocumentTitle } from '../composables/useDocumentTitle'
import { useHomeNavigation } from '../composables/useHomeNavigation'
import { FIELD, vField } from '../directives/field'
import { localRpcKey } from '../local/local-rpc-key'
import { repoRoute, routeFromGithubUrl } from '../source-routes'
import { createPrefetcher } from '../state/prefetch'
import { prSnapshots } from '../state/snapshots'

// Only `installLocal` registers this page, after providing the RPC client.
const rpc = inject(localRpcKey)!
const { cache, credentials } = useAppContext()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()

// One field, as on the phone: typing filters the list, and a pasted link gets an Open button instead.
const search = ref('')
const parsed = computed(() => routeFromGithubUrl(search.value.trim()))

function fromQuery<T extends string>(name: string, allowed: readonly T[], fallback: T): T {
  const value = route.query[name]
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? value as T : fallback
}

/** Keeps the filters in the URL, leaving defaults out. */
function setQuery(patch: Record<string, string | undefined>) {
  const query = { ...route.query, ...patch }
  for (const [name, value] of Object.entries(query)) {
    if (value === undefined)
      delete query[name]
  }
  void router.replace({ query })
}

const state = computed<MyPullState>({
  get: () => fromQuery('state', MY_PULL_STATES, 'open'),
  set: value => setQuery({ state: value === 'open' ? undefined : value, queue: undefined }),
})
const queue = computed<TriageQueue>({
  get: () => state.value === 'open' ? fromQuery('queue', TRIAGE_QUEUES, 'all') : 'all',
  set: value => setQuery({ queue: value === 'all' ? undefined : value }),
})
const role = computed<MyPullRole | 'all'>({
  get: () => fromQuery('role', ['all', ...MY_PULL_ROLES], 'all'),
  set: value => setQuery({ role: value === 'all' ? undefined : value }),
})

const stateOptions = computed(() => [
  { value: 'open' as const, label: t('local.home.state.open') },
  { value: 'closed' as const, label: t('local.home.state.closed') },
])

const { pulls, fetchedAt, updating, error, reload } = useMyPulls({
  cached: async selected => await rpc.call(LOCAL_RPC.myPullsCached, { state: selected }) as MyPullsSnapshot | null,
  fresh: async selected => await rpc.call(LOCAL_RPC.myPulls, { state: selected }) as MyPull[],
}, state, { storage: deviceStorage() })

const now = useNow({ interval: 60_000 })
const filtered = computed(() => filterPulls(pulls.value ?? [], {
  queue: queue.value,
  role: role.value,
  needle: parsed.value ? '' : search.value.trim().toLowerCase(),
}, now.value.getTime()))
const groups = computed(() => groupByRepo(filtered.value.pulls))
const folded = shallowReactive(new Set<string>())
function fold(name: string) {
  if (!folded.delete(name))
    folded.add(name)
}

const prefetcher = createPrefetcher({
  snapshots: prSnapshots(),
  cache,
  fetchDiff: pull => createGithubSource({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) }, credentials).fetch(),
})

function open() {
  if (parsed.value)
    router.push(parsed.value)
}

useDocumentTitle(() => t('local.home.title'))
const nav = useHomeNavigation({ rows: () => groups.value.flatMap(group => group.pulls), pulls: () => pulls.value, refresh: reload })
</script>

<template>
  <div class="relative flex flex-col min-h-dvh">
    <AppHeader>
      <KitCapsuleTabs v-model="state" :options="stateOptions" class="max-w-60 w-full" />
    </AppHeader>

    <main class="mxa max-w-3xl w-full flex flex-1 flex-col pb-32">
      <h1 class="sr-only">
        {{ $t('local.home.title') }}
      </h1>

      <HomeQueueNav
        v-model:queue="queue"
        v-model:role="role"
        class="pb-2 pt-3"
        :queue-counts="filtered.queueCounts"
        :role-counts="filtered.roleCounts"
        :show-queues="state === 'open'"
      >
        <HomeSyncStatus :updating="updating" :fetched-at="fetchedAt" :error="pulls ? error : undefined" class="shrink-0 pl-2" @refresh="reload" />
      </HomeQueueNav>

      <!-- The shape of the list while the first one loads, never a loading screen. -->
      <div v-if="!pulls && !error" class="flex flex-col" aria-busy="true" :aria-label="$t('local.home.loading')">
        <div v-for="n in 6" :key="n" class="flex gap-1.5 px-4 py-3.25">
          <span class="kit-skeleton w-11 shrink-0" />
          <span class="flex flex-1 flex-col gap-2">
            <span class="kit-skeleton" :style="{ width: `${90 - n * 7}%` }" />
            <span class="kit-skeleton w-1/3" />
          </span>
        </div>
      </div>

      <div v-else-if="!pulls" class="flex flex-col items-center gap-1.5 px-8 py-12 text-center">
        <p class="text-row font-medium">
          {{ $t('local.home.failed') }}
        </p>
        <p class="text-sm color-muted">
          {{ error }}
        </p>
        <button type="button" class="btn-action mt-1" :disabled="updating" @click="reload">
          <span class="i-lu-redo" aria-hidden="true" />
          {{ $t('common.retry') }}
        </button>
      </div>

      <p v-else-if="!groups.length" class="px-8 py-12 text-center text-row font-medium">
        {{ queue === 'all' ? $t('local.home.empty') : $t('triage.empty') }}
      </p>

      <section v-for="group in groups" v-else :key="group.name" class="flex flex-col px-4">
        <KitGroupHeading
          :label="group.repo"
          :title="group.name"
          :count="group.pulls.length"
          :folded="folded.has(group.name) && !search"
          :to="repoRoute(group.owner, group.repo)"
          @fold="fold(group.name)"
        />
        <ul v-if="!folded.has(group.name) || search" class="flex flex-col -mx-4">
          <li v-for="pull in group.pulls" :key="pull.number">
            <HomePullRow
              :pull="pull"
              :data-nav-row="nav.rowKey(pull)"
              :class="{ 'bg-hover': nav.isActive(pull) }"
              @intent="prefetcher.hover"
              @leave="prefetcher.leave"
            />
          </li>
        </ul>
      </section>
    </main>

    <div class="pointer-events-none fixed inset-x-0 bottom-0 z-nav px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))]">
      <KitSearchPill
        v-model="search"
        v-field="FIELD.search"
        class="pointer-events-auto mxa max-w-3xl"
        :placeholder="$t('local.home.search')"
        :open-label="parsed ? $t('local.home.open') : undefined"
        data-nav-search
        @open="open"
      />
    </div>
  </div>
</template>
