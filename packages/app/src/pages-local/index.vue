<script setup lang="ts">
import type { MyPull, MyPullRole, MyPullsSnapshot, MyPullState } from '@pulls.review/core/local-rpc'
import type { TriageQueue } from '../components/home/triage'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionToggleGroup from '@antfu/design/components/Action/ActionToggleGroup.vue'
import FeedbackEmptyState from '@antfu/design/components/Feedback/FeedbackEmptyState.vue'
import FeedbackLoading from '@antfu/design/components/Feedback/FeedbackLoading.vue'
import FormTextInput from '@antfu/design/components/Form/FormTextInput.vue'
import { createGithubSource } from '@pulls.review/core/github'
import { LOCAL_RPC, MY_PULL_ROLES, MY_PULL_STATES } from '@pulls.review/core/local-rpc'
import { useNow } from '@vueuse/core'
import { computed, inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppContext } from '../app-context'
import AppFooter from '../components/AppFooter.vue'
import AppHeader from '../components/AppHeader.vue'
import HomePullRow from '../components/home/HomePullRow.vue'
import HomeQueueNav from '../components/home/HomeQueueNav.vue'
import HomeSyncStatus from '../components/home/HomeSyncStatus.vue'
import { filterPulls, groupByRepo, TRIAGE_QUEUES } from '../components/home/triage'
import { deviceStorage, useMyPulls } from '../components/home/use-my-pulls'
import { useDocumentTitle } from '../composables/useDocumentTitle'
import { useHomeNavigation } from '../composables/useHomeNavigation'
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

const url = ref('')
const search = ref('')
const parsed = computed(() => routeFromGithubUrl(url.value))

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
  { value: 'open', label: t('local.home.state.open') },
  { value: 'closed', label: t('local.home.state.closed') },
])

const { pulls, fetchedAt, updating, error, reload } = useMyPulls({
  cached: async selected => await rpc.call(LOCAL_RPC.myPullsCached, { state: selected }) as MyPullsSnapshot | null,
  fresh: async selected => await rpc.call(LOCAL_RPC.myPulls, { state: selected }) as MyPull[],
}, state, { storage: deviceStorage() })

const now = useNow({ interval: 60_000 })
const filtered = computed(() => filterPulls(pulls.value ?? [], {
  queue: queue.value,
  role: role.value,
  needle: search.value.trim().toLowerCase(),
}, now.value.getTime()))
const groups = computed(() => groupByRepo(filtered.value.pulls))

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
  <div class="relative min-h-screen flex flex-col">
    <AppHeader />

    <main class="mxa max-w-5xl w-full flex flex-1 flex-col gap-6 px-4 py-8 md:px-6 md:py-10">
      <div class="flex flex-col gap-1">
        <h1 class="text-2xl font-semibold">
          {{ $t('local.home.title') }}
        </h1>
        <p class="text-sm op-fade">
          {{ $t('local.home.tagline') }}
        </p>
      </div>

      <form class="flex items-stretch gap-2" @submit.prevent="open">
        <FormTextInput v-model="url" :placeholder="$t('local.home.urlPlaceholder')" icon="i-ph:link-duotone" class="min-w-0 flex-1" />
        <ActionButton type="submit" variant="primary" icon="i-ph-arrow-right-bold" :disabled="!parsed">
          {{ $t('local.home.open') }}
        </ActionButton>
      </form>

      <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
        <FormTextInput v-model="search" :placeholder="$t('local.home.search')" icon="i-ph:magnifying-glass-duotone" class="min-w-0 flex-1 basis-60" data-nav-search />
        <ActionToggleGroup v-model="state" :options="stateOptions" />
        <HomeSyncStatus :updating="updating" :fetched-at="fetchedAt" :error="pulls ? error : undefined" class="ml-auto" @refresh="reload" />
      </div>

      <FeedbackLoading v-if="!pulls && !error" :text="$t('local.home.loading')" />

      <FeedbackEmptyState v-else-if="!pulls" icon="i-ph:warning-duotone" :title="$t('local.home.failed')">
        <template #hint>
          {{ error }}
        </template>
        <template #actions>
          <ActionButton variant="primary" :disabled="updating" @click="reload">
            {{ $t('common.retry') }}
          </ActionButton>
        </template>
      </FeedbackEmptyState>

      <div v-else class="grid gap-4 md:grid-cols-[13rem_minmax(0,1fr)] md:gap-6">
        <aside class="min-w-0 md:sticky md:top-4 md:self-start">
          <HomeQueueNav
            v-model:queue="queue"
            v-model:role="role"
            :queue-counts="filtered.queueCounts"
            :role-counts="filtered.roleCounts"
            :show-queues="state === 'open'"
          />
        </aside>

        <section class="min-w-0 flex flex-col gap-4">
          <FeedbackEmptyState v-if="!groups.length" icon="i-ph:git-pull-request-duotone" :title="queue === 'all' ? $t('local.home.empty') : $t('triage.empty')" />

          <details v-for="group in groups" :key="group.name" open class="group flex flex-col gap-2">
            <summary class="flex cursor-pointer select-none list-none items-center gap-2 text-sm font-mono coarse:min-h-11">
              <span class="i-ph:caret-right-bold text-xs op-fade transition group-open:rotate-90" aria-hidden="true" />
              <span class="i-ph:git-branch-duotone op-fade" aria-hidden="true" />
              <span class="min-w-0 truncate">{{ group.name }}</span>
              <span class="op-fade">{{ group.pulls.length }}</span>
              <RouterLink :to="repoRoute(group.owner, group.repo)" class="ml-auto shrink-0 text-xs op-fade coarse:(min-h-11 flex items-center pl-2) hover:underline" @click.stop>
                {{ $t('local.home.allInRepo') }}
              </RouterLink>
            </summary>
            <ul class="mt-2 flex flex-col border border-base rounded-lg py-1">
              <li v-for="pull in group.pulls" :key="pull.number">
                <HomePullRow
                  :pull="pull"
                  :data-nav-row="nav.rowKey(pull)"
                  :class="{ 'bg-hover ring-1 ring-inset ring-primary-500/50': nav.isActive(pull) }"
                  @intent="prefetcher.hover"
                  @leave="prefetcher.leave"
                />
              </li>
            </ul>
          </details>
        </section>
      </div>
    </main>

    <AppFooter />
  </div>
</template>
