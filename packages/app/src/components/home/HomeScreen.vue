<script setup lang="ts">
import type { MyPull, MyPullsSnapshot, MyPullState } from '@pulls.review/core/local-rpc'
import type { PrCacheMeta } from '@pulls.review/core/cache'
import type { TourResult } from '@pulls.review/core/types'
import { createGithubClient, createGithubSource } from '@pulls.review/core/github'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { useLocalStorage } from '@vueuse/core'
import { computed, inject, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppContext } from '../../app-context'
import { useDocumentTitle } from '../../composables/useDocumentTitle'
import { useHomeNavigation } from '../../composables/useHomeNavigation'
import { vMobileSwipe } from '../../directives/mobile-swipe'
import { localRpcKey } from '../../local/local-rpc-key'
import { repoRoute, routeForRef, routeFromGithubUrl } from '../../source-routes'
import { settingsModalOpen } from '../../state/settingsModal'
import { settings } from '../../state/settings'
import { createPrefetcher } from '../../state/prefetch'
import { prSnapshots } from '../../state/snapshots'
import { usePullToCommands } from '../../composables/usePullToCommands'
import AppModal from '../AppModal.vue'
import GithubAvatar from '../GithubAvatar.vue'
import SettingsModal from '../settings/SettingsModal.vue'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'
import KitFloatButton from '../kit/KitFloatButton.vue'
import KitGroupHeading from '../kit/KitGroupHeading.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'
import KitSoftChip from '../kit/KitSoftChip.vue'
import ProfileSheet from './ProfileSheet.vue'
import KitSheetRow from '../kit/KitSheetRow.vue'
import HomePullRow from './HomePullRow.vue'
import HomeTourRow from './HomeTourRow.vue'
import InboxChatSheet from './InboxChatSheet.vue'
import { browserInbox } from './browser-inbox'
import { groupByRepo, matchesSearch } from './triage'
import { inboxStacks } from './inbox-stacks'
import { deviceStorage, useMyPulls } from './use-my-pulls'

const commandsPull = usePullToCommands()
const rpc = inject(localRpcKey, undefined)
const { cache, credentials } = useAppContext()
const client = createGithubClient(credentials)
const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const tab = computed({
  get: () => route.query.tab === 'tours' ? 'tours' : 'inbox',
  set: value => { void router.replace({ query: { ...route.query, tab: value === 'tours' ? value : undefined } }) },
})
const filter = useLocalStorage<'waiting' | 'authored' | 'all'>('pully:inbox-filter', 'waiting')
const sort = useLocalStorage<string>('pully:inbox-sort', 'updated')
const grouping = useLocalStorage<'repo' | 'kind'>('pully:inbox-group', 'repo')
const hiddenRepos = useLocalStorage<string[]>('pully:hidden-repositories', [])
const archived = useLocalStorage<string[]>('pully:inbox-archived', [])
const folded = useLocalStorage<string[]>('pully:inbox-folded', [])
const search = ref('')
const focused = ref(false)
const profile = ref(false)
const sortOpen = ref(false)
const held = ref<MyPull>()
const stack = ref<MyPull[]>()
const viewer = ref<string>()
const state = ref<MyPullState>('open')
const tours = shallowRef<{ meta: PrCacheMeta, tour: TourResult }[]>([])
const toursError = ref<string>()
const undo = ref<{ previous: string[] }>()
const chatOpen = ref(false)
const question = ref('')
const tabPositions = new Map<string, number>()
watch(tab, async (next, previous) => {
  tabPositions.set(previous, window.scrollY)
  await nextTick()
  window.scrollTo({ top: tabPositions.get(next) ?? 0, behavior: 'instant' })
})
watch(chatOpen, shown => { if (!shown) question.value = '' })
watch(settingsModalOpen, shown => { if (shown) { chatOpen.value = false; profile.value = false; sortOpen.value = false; held.value = undefined } })
const key = (pull: MyPull) => `${pull.owner}/${pull.repo}#${pull.number}`
const parsed = computed(() => routeFromGithubUrl(search.value.trim()))
const needle = computed(() => parsed.value ? '' : search.value.trim().toLowerCase())

async function identity() {
  if (viewer.value) return viewer.value
  const user = await (await client.request('/user')).json() as { login: string }
  viewer.value = user.login
  return user.login
}
async function recentPulls(): Promise<MyPull[]> {
  return (await cache.diffs.listRecent(100)).flatMap((meta) => {
    const ref = meta.ref
    if (ref.kind !== 'github-pr') return []
    return [{ owner: ref.owner, repo: ref.repo, number: Number(ref.number), title: meta.title, author: '', isDraft: meta.pullRequestState === 'draft', updatedAt: new Date(meta.lastViewedAt).toISOString(), commentsCount: 0, labels: [], state: meta.pullRequestState === 'closed' || meta.pullRequestState === 'merged' ? 'closed' as const : 'open' as const, role: 'involved' as const, additions: meta.additions, deletions: meta.deletions }]
  })
}
const { pulls, error, reload } = useMyPulls({
  cached: async selected => rpc ? await rpc.call(LOCAL_RPC.myPullsCached, { state: selected }) as MyPullsSnapshot | null : null,
  fresh: async selected => rpc
    ? await rpc.call(LOCAL_RPC.myPulls, { state: selected }) as MyPull[]
    : settings.value.githubToken ? browserInbox(client, await identity(), selected) : (await recentPulls()).filter(pull => pull.state === selected),
}, state, { storage: rpc ? deviceStorage() : undefined })
const searched = computed(() => (pulls.value ?? []).filter(pull => !hiddenRepos.value.includes(`${pull.owner}/${pull.repo}`) && matchesSearch(pull, needle.value)))
const hasKinds = computed(() => (pulls.value ?? []).some(pull => !!pull.jev))
function inFilter(pull: MyPull, choice: string) {
  if (choice === 'all') return true
  if (archived.value.includes(key(pull)) || pull.state !== 'open') return false
  return choice === 'authored' ? pull.role === 'authored' : !pull.isDraft || pull.role === 'authored'
}
const filters = computed(() => (['waiting', 'authored', 'all'] as const).map(value => ({ value, label: t(`mobile.filter.${value}`), count: searched.value.filter(pull => inFilter(pull, value)).length })))
const sorted = computed(() => searched.value.filter(pull => inFilter(pull, filter.value)).sort((a, b) => {
  if (sort.value === 'least-updated') return Date.parse(a.updatedAt) - Date.parse(b.updatedAt)
  if (sort.value === 'number') return b.number - a.number
  if (sort.value === 'size') return (b.additions ?? 0) + (b.deletions ?? 0) - (a.additions ?? 0) - (a.deletions ?? 0)
  if (sort.value === 'risk' && hasKinds.value) return Number(!a.jev) - Number(!b.jev) || (b.jev?.risk ?? 0) - (a.jev?.risk ?? 0)
  return Date.parse(b.updatedAt) - Date.parse(a.updatedAt)
}))
const groups = computed(() => {
  if (grouping.value !== 'kind' || !hasKinds.value) return groupByRepo(sorted.value)
  const kinds = ['fix', 'feature', 'refactor', 'performance', 'tests', 'docs', 'dependencies', 'infra', 'other']
  return kinds.map(kind => ({ name: `kind:${kind}`, owner: '', repo: t(`mobile.kinds.${kind}`), pulls: sorted.value.filter(pull => (pull.jev?.kind ?? 'other') === kind) })).filter(group => group.pulls.length)
})
const tourGroups = computed(() => {
  const groups = new Map<string, typeof tours.value>()
  for (const entry of tours.value) {
    const ref = entry.meta.ref
    if (ref.kind !== 'github-pr' || hiddenRepos.value.includes(`${ref.owner}/${ref.repo}`) || !`${ref.owner}/${ref.repo} ${entry.meta.title} #${ref.number}`.toLowerCase().includes(needle.value)) continue
    const name = `${ref.owner}/${ref.repo}`
    groups.set(name, [...(groups.get(name) ?? []), entry])
  }
  return [...groups].map(([name, entries]) => ({ name, entries }))
})
const tabs = computed(() => [{ value: 'inbox', label: t('mobile.inbox') }, { value: 'tours', label: t('mobile.tours') }])
const sorts = computed(() => ['updated', 'least-updated', 'number', 'size', ...(hasKinds.value ? ['risk'] : [])].map(value => ({ value, label: t(`mobile.sort.${value}`) })))
async function loadTours() {
  try {
    const metas = (await cache.diffs.listRecent(400)).filter(meta => meta.ref.kind === 'github-pr')
    const found = await Promise.all(metas.map(async meta => ({ meta, tour: await cache.tours.latest(meta.key, meta.headSha) })))
    tours.value = found.filter((entry): entry is { meta: PrCacheMeta, tour: TourResult } => !!entry.tour).sort((a, b) => Date.parse(b.tour.generatedAt) - Date.parse(a.tour.generatedAt))
  }
  catch (failure) { toursError.value = failure instanceof Error ? failure.message : String(failure) }
}
onMounted(() => { void loadTours(); if (rpc || settings.value.githubToken) void identity().catch(() => {}) })
const prefetcher = createPrefetcher({ snapshots: prSnapshots(), cache, fetchDiff: pull => createGithubSource({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) }, credentials).fetch() })
const nav = useHomeNavigation({ rows: () => groups.value.flatMap(group => inboxStacks(group.pulls).map(entry => entry.pull)), pulls: () => pulls.value, refresh: reload })
useDocumentTitle(() => t('mobile.inbox'))
function fold(name: string) { folded.value = folded.value.includes(name) ? folded.value.filter(value => value !== name) : [...folded.value, name] }
function openPull(pull: MyPull) { void router.push(routeForRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) })) }
function swipeRow(event: Event, pull: MyPull, layers?: MyPull[]) {
  if ((event as CustomEvent<string>).detail === 'right') { if (layers) stack.value = layers; else openPull(pull) }
  else archivePull(pull, layers)
}
function archivePull(pull: MyPull, layers = [pull]) {
  const previous = [...archived.value]
  const ids = layers.map(key)
  archived.value = ids.every(id => previous.includes(id)) ? previous.filter(id => !ids.includes(id)) : [...new Set([...previous, ...ids])]
  undo.value = { previous }
  held.value = undefined
}
function undoArchive() { if (undo.value) archived.value = undo.value.previous; undo.value = undefined }
function openSearch() { if (parsed.value) void router.push(parsed.value) }
function pageSwipe(event: Event) { tab.value = (event as CustomEvent<string>).detail === 'left' ? 'tours' : 'inbox' }
function blurSearch() { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); focused.value = false }
function askInbox() {
  if (!rpc || !search.value.trim()) return
  question.value = search.value.trim()
  search.value = ''
  blurSearch()
  chatOpen.value = true
}
</script>

<template>
  <div v-mobile-swipe data-swipe-page class="relative min-h-dvh bg-base pb-28" @swipe="pageSwipe">
    <div v-if="commandsPull > 4" class="pointer-events-none absolute inset-x-0 top-0 text-center text-sm color-muted" :style="{ opacity: Math.min(1, commandsPull / 84), transform: `translateY(${commandsPull * 0.3 - 12}px)` }">{{ $t(commandsPull >= 84 ? 'mobile.releaseCommands' : 'mobile.pullCommands') }}</div>
    <div :style="commandsPull ? { transform: `translateY(${commandsPull * 0.6}px)` } : undefined">
    <header class="sticky top-0 z-nav flex items-center gap-3 bg-base px-4 pt-[calc(0.75rem+env(safe-area-inset-top))]">
      <KitFloatButton icon="i-lu-cog" :label="$t('common.settings')" @click="settingsModalOpen = true" />
      <KitCapsuleTabs v-model="tab" :options="tabs" class="min-w-0 flex-1" />
      <button type="button" class="press-tint h-11 w-11 flex shrink-0 items-center justify-center overflow-hidden rounded-full" :aria-label="$t('mobile.profile')" @click="profile = true">
        <GithubAvatar v-if="viewer" :login="viewer" :size="40" />
        <svg v-else class="h-10 w-10 rounded-full" viewBox="0 0 40 40" aria-hidden="true"><defs><linearGradient id="profile-dot" x2="1" y2="1"><stop stop-color="#D9B38C" /><stop offset="1" stop-color="#8C5A3C" /></linearGradient></defs><circle cx="20" cy="20" r="20" fill="url(#profile-dot)" /></svg>
      </button>
    </header>
    <main v-if="tab === 'inbox'">
      <nav role="tablist" :aria-label="$t('local.home.queues')" class="sticky top-[calc(56px+env(safe-area-inset-top))] z-nav flex gap-2 overflow-x-auto bg-base px-4 pt-3 [scrollbar-width:none]">
        <KitSoftChip v-for="item in filters" :key="item.value" :label="item.label" :count="item.count" :selected="filter === item.value" @click="filter = item.value" />
      </nav>
      <div v-if="!pulls && !error" aria-busy="true" :aria-label="$t('local.home.loading')" class="pt-6">
        <div v-for="n in 7" :key="n" class="flex gap-3 px-4 py-4"><span class="kit-skeleton w-11" /><span class="flex flex-1 flex-col gap-3"><span class="kit-skeleton" :style="{ width: `${95 - n * 6}%` }" /><span class="kit-skeleton w-1/2" /></span></div>
      </div>
      <div v-else-if="!pulls" class="flex flex-col items-center gap-2 px-8 py-12 text-center">
        <p class="text-row font-medium">{{ $t('local.home.failed') }}</p><p class="text-sm color-muted">{{ error }}</p>
        <button class="btn-action" @click="reload">{{ $t('common.retry') }}</button>
      </div>
      <div v-else-if="!groups.length" class="flex flex-col items-center gap-2 px-8 py-12 text-center">
        <p class="text-row font-medium">{{ needle ? $t('local.home.noMatches') : $t('local.home.empty') }}</p>
        <p v-if="!rpc && !settings.githubToken" class="text-sm color-muted">{{ $t('mobile.guestHint') }}</p>
      </div>
      <section v-for="group in groups" :key="group.name" class="px-4">
        <KitGroupHeading class="sticky top-[calc(104px+env(safe-area-inset-top))] z-inbox-group bg-base" :label="group.repo" :count="group.pulls.length" :to="group.owner ? repoRoute(group.owner, group.repo) : undefined" :folded="folded.includes(group.name) && !needle" @fold="fold(group.name)" />
        <ul v-if="!folded.includes(group.name) || needle" class="-mx-4">
          <li v-for="entry in inboxStacks(group.pulls)" :key="key(entry.pull)" class="overflow-hidden">
            <HomePullRow v-mobile-swipe data-swipe-row :pull="entry.pull" :stack-layers="entry.layers" :risk="sort === 'risk' || grouping === 'kind'" :repo-label="group.owner ? undefined : entry.pull.repo" :data-nav-row="nav.rowKey(entry.pull)" :class="{ 'bg-hover': nav.isActive(entry.pull) }" @swipe="swipeRow($event, entry.pull, entry.layers)" @hold="entry.layers ? stack = entry.layers : held = entry.pull" @contextmenu.prevent="entry.layers ? stack = entry.layers : held = entry.pull" @stack="stack = $event" @intent="prefetcher.hover" @leave="prefetcher.leave" />
          </li>
        </ul>
      </section>
    </main>
    <main v-else>
      <div v-if="!tourGroups.length" class="flex flex-col items-center gap-1.5 px-8 py-12 text-center">
        <p class="text-lg font-medium">{{ $t('mobile.noTours') }}</p><p class="text-sm color-muted">{{ toursError || $t('mobile.tourHint') }}</p>
      </div>
      <section v-for="group in tourGroups" :key="group.name" class="px-4">
        <KitGroupHeading :label="group.name.split('/')[1]!" :count="group.entries.length" :folded="folded.includes(group.name)" @fold="fold(group.name)" />
        <ul v-if="!folded.includes(group.name)" class="-mx-4">
          <li v-for="entry in group.entries" :key="entry.meta.key">
            <HomeTourRow :meta="entry.meta" :tour="entry.tour" :pull="pulls?.find(pull => entry.meta.ref.kind === 'github-pr' && pull.owner === entry.meta.ref.owner && pull.repo === entry.meta.ref.repo && String(pull.number) === entry.meta.ref.number)" />
          </li>
        </ul>
      </section>
    </main>
    </div>
    <div v-if="focused && !search" class="fixed inset-0 z-search-dim bg-scrim/50" @pointerdown.prevent="blurSearch" />
    <div class="pointer-events-none fixed inset-x-0 bottom-0 z-foot-bar flex items-center gap-2.5 bg-gradient-to-t from-base via-base/90 to-transparent px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-5">
      <KitSearchPill v-model="search" class="pointer-events-auto min-w-0 flex-1" :placeholder="$t(tab === 'tours' ? 'mobile.searchTours' : 'mobile.search')" :open-label="parsed ? $t('local.home.open') : undefined" :send-label="rpc && tab === 'inbox' && search.trim().endsWith('?') ? $t('mobile.send') : undefined" data-nav-search @focusin="focused = true" @focusout="focused = false" @open="openSearch" @send="askInbox" />
      <KitFloatButton v-if="tab === 'inbox'" icon="i-lu-sort" :label="$t('mobile.sort.label')" class="pointer-events-auto h-13! w-13!" @click="sortOpen = true" />
    </div>
    <div v-if="undo" role="status" class="fixed bottom-25 left-4 right-4 z-toast flex items-center justify-between rounded-full bg-cta px-5 py-3 text-sm color-on-cta shadow-button">
      <span>{{ $t('mobile.archived') }}</span><button class="px-2 font-medium" @click="undoArchive">{{ $t('mobile.undo') }}</button>
    </div>
    <SettingsModal v-model:open="settingsModalOpen" />
    <ProfileSheet v-model:open="profile" v-model:hidden="hiddenRepos" :viewer="viewer" :pulls="pulls ?? []" :closed="state === 'closed'" @closed="state = $event ? 'closed' : 'open'; filter = 'all'" />
    <InboxChatSheet v-if="rpc" v-model:open="chatOpen" :question="question" />
    <AppModal :open="!!stack" :title="$t('mobile.stack')" @update:open="stack = undefined"><div class="-mx-4"><HomePullRow v-for="layer in [...(stack ?? [])].reverse()" :key="key(layer)" :pull="layer" @click="stack = undefined" /></div></AppModal>
    <AppModal :open="!!held" :title="held?.title" @update:open="held = undefined">
      <div v-if="held" class="flex flex-col gap-2">
        <button class="btn-text justify-start" @click="openPull(held); held = undefined">{{ $t('local.home.open') }}</button>
        <button class="btn-text justify-start" @click="archivePull(held)">{{ $t(archived.includes(key(held)) ? 'mobile.unarchive' : 'mobile.archive') }}</button>
      </div>
    </AppModal>
    <AppModal v-model:open="sortOpen" :title="$t('mobile.sort.title')">
      <div class="overflow-hidden rounded-card bg-raised shadow-note">
        <KitSheetRow v-for="(item, index) in sorts" :key="item.value" :label="item.label" :first="index === 0" @activate="sort = item.value; sortOpen = false"><span v-if="sort === item.value" class="i-lu-check color-accent-teal" /></KitSheetRow>
      </div>
      <template v-if="hasKinds"><h3 class="mt-5 px-4 text-sm color-muted font-medium">{{ $t('mobile.groupBy') }}</h3><div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow v-for="(value, index) in (['repo', 'kind'] as const)" :key="value" :label="$t(`mobile.group.${value}`)" :first="index === 0" @activate="grouping = value; sortOpen = false"><span v-if="grouping === value" class="i-lu-check color-accent-teal" /></KitSheetRow></div></template>
    </AppModal>
  </div>
</template>
