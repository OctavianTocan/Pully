<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import type { CommitSelection } from '../overview/commit-selection'
import type { ReviewVerdict } from '@pulls.review/core/types'
import { resolveModel } from '@pulls.review/core/analyze'
import { Markdown } from '@comark/vue'
import { Virtualizer } from '@pierre/diffs'
import { useNow } from '@vueuse/core'
import { computed, defineAsyncComponent, inject, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch } from 'vue'
import { routerKey } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { vMobileSwipe } from '../../directives/mobile-swipe'
import { parentForRef } from '../../source-routes'
import { compactAge, compactCount } from '../../i18n/time-ago'
import { settings } from '../../state/settings'
import { settingsModalOpen } from '../../state/settingsModal'
import { useLlmModels } from '../../composables/useLlmModels'
import { codeFonts } from '../../state/phone-type'
import { playCue } from '../../sound/control-sounds'
import AppModal from '../AppModal.vue'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'
import KitFloatButton from '../kit/KitFloatButton.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'
import SettingsModal from '../settings/SettingsModal.vue'
import CodeSample from '../settings/CodeSample.vue'
import LayoutChoices from '../settings/LayoutChoices.vue'
import LlmSettingsSection from '../settings/LlmSettingsSection.vue'
import KitSheetRow from '../kit/KitSheetRow.vue'
import KitSwitch from '../kit/KitSwitch.vue'
import { keepRepoLinks } from '../ai/ai-format'
import { usePrOverview } from '../overview/context'
import { selectCommit } from '../overview/commit-selection'
import PrChecks from '../overview/PrChecks.vue'
import PrCommitList from '../overview/PrCommitList.vue'
import PrConversation from '../overview/PrConversation.vue'
import PrHtml from '../overview/PrHtml.vue'
import PrMergeBox from '../overview/PrMergeBox.vue'
import CommitRangeBanner from '../overview/CommitRangeBanner.vue'
import { diffVirtualizerKey } from './diff-virtualizer'
import DiffAnalyzeButton from './DiffAnalyzeButton.vue'
import DiffShareButton from './DiffShareButton.vue'
import FileDiff from './FileDiff.vue'
import ReviewCheckbox from './ReviewCheckbox.vue'
import { reviewStatus } from './review-status'
import ReviewSubmitModal from './ReviewSubmitModal.vue'
import OfflineBanner from './OfflineBanner.vue'
import SharedAnalysisBanner from './SharedAnalysisBanner.vue'
import TourPage from './TourPage.vue'

const props = defineProps<{ store: DiffsStore, document?: Document | ShadowRoot }>()
const router = inject(routerKey, undefined)
const context = usePrOverview()
const { t } = useI18n()
const copyNotice = ref('')
const pr = computed(() => context?.store.overview)
const diff = computed(() => props.store.diff)
type View = 'overview' | 'changes' | 'tour'
const view = ref<View>(router?.currentRoute.value.query.view === 'tour' ? 'tour' : router?.currentRoute.value.query.view === 'changes' ? 'changes' : context ? 'overview' : 'changes')
const openedOnTour = view.value === 'tour'
const activeFile = ref(0)
const fileSearch = ref('')
const visibleFiles = computed(() => diff.value?.files.map((file, index) => ({ file, index })).filter(entry => entry.file.path.toLowerCase().includes(fileSearch.value.trim().toLowerCase())) ?? [])
const fileView = ref<InstanceType<typeof FileDiff>>()
const tourPage = ref<InstanceType<typeof TourPage>>()
const tourBusy = ref(false)
const tourReady = ref(false)
const askExpanded = ref(false)
const askField = ref<InstanceType<typeof KitSearchPill>>()
async function expandAsk() { askExpanded.value = true; await nextTick(); askField.value?.$el.querySelector('input')?.focus() }
const file = computed(() => diff.value?.files[activeFile.value])
const pane = ref('summary')
type Sheet = 'actions' | 'tools' | 'commits' | 'checks' | 'files' | 'decide' | 'analyze' | 'model' | 'tools-font'
const sheet = ref<Sheet>()
const sheetBack = computed<Sheet | undefined>(() => sheet.value === 'decide' || sheet.value === 'analyze' ? 'actions' : sheet.value === 'model' ? 'analyze' : sheet.value === 'tools-font' ? 'tools' : undefined)
const reviewOpen = ref(false)
const verdict = ref<ReviewVerdict>('COMMENT')
const models = useLlmModels()
const analysisModel = computed(() => resolveModel(settings.value.llm)?.model.name || resolveModel(settings.value.llm)?.model.id || settings.value.llm.provider)
const codeZoom = computed({ get: () => String(settings.value.codeZoom), set: value => { settings.value.codeZoom = Number(value) } })
const zoomOptions = computed(() => [{ value: '0.88', label: t('mobile.smaller') }, { value: '1', label: t('mobile.defaultSize') }, { value: '1.16', label: t('mobile.larger') }])
const askDrafts = reactive<Record<View, string>>({ overview: '', changes: '', tour: '' })
const ask = computed({ get: () => askDrafts[view.value], set: value => { askDrafts[view.value] = value } })
const chatOpen = ref(false)
const askFocused = ref(false)
const critique = import.meta.env.PR_LOCAL ? defineAsyncComponent(() => import('../critique/CritiqueButton.vue')) : undefined
const CritiquePanel = import.meta.env.PR_LOCAL ? defineAsyncComponent(() => import('../critique/CritiquePanel.vue')) : undefined
const ChatWidget = import.meta.env.PR_LLM ? defineAsyncComponent(() => import('../chat/ChatWidget.vue')) : undefined
const back = computed(() => import.meta.env.PR_LOCAL ? '/' : diff.value ? parentForRef(diff.value.ref)?.route ?? '/' : '/')
const title = computed(() => pr.value?.title ?? diff.value?.title)
const author = computed(() => pr.value?.author?.login ?? diff.value?.author?.name)
const repository = computed(() => diff.value && 'repo' in diff.value.ref ? diff.value.ref.repo : '')
const now = useNow({ interval: 60_000 })
const age = computed(() => {
  const date = pr.value?.createdAt ?? diff.value?.createdAt
  return date ? compactAge(new Date(date), now.value.getTime()) : undefined
})
const lifecycle = computed(() => pr.value?.state ?? diff.value?.pullRequest?.state ?? 'open')
const threads = computed(() => props.store.reviews?.threads.filter(thread => thread.path === file.value?.path && !thread.outdated).length ?? 0)
const filename = computed(() => file.value?.path.split('/').at(-1) ?? '')
const folder = computed(() => file.value?.path.includes('/') ? file.value.path.slice(0, file.value.path.lastIndexOf('/')) : '')
const blob = computed(() => {
  const ref = diff.value?.ref
  const sha = file.value?.status === 'removed' ? diff.value?.base?.sha : diff.value?.head?.sha
  return ref && 'owner' in ref && 'repo' in ref && sha && file.value
    ? `https://github.com/${ref.owner}/${ref.repo}/blob/${sha}/${file.value.path.split('/').map(encodeURIComponent).join('/')}` : undefined
})
const additions = computed(() => diff.value?.files.reduce((sum, file) => sum + file.additions, 0) ?? 0)
const deletions = computed(() => diff.value?.files.reduce((sum, file) => sum + file.deletions, 0) ?? 0)
const virtualizer = new Virtualizer()
provide(diffVirtualizerKey, virtualizer)
onMounted(() => virtualizer.setup(document))
onBeforeUnmount(() => virtualizer.cleanUp())
const positions = new Map<View, number>()
watch(settingsModalOpen, shown => { if (shown) sheet.value = undefined })
watch(view, async (next, previous) => {
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
  askFocused.value = false
  askExpanded.value = false
  positions.set(previous, window.scrollY)
  if (router) void router.replace({ query: { ...router.currentRoute.value.query, view: next === 'overview' ? undefined : next } })
  await nextTick()
  window.scrollTo({ top: positions.get(next) ?? 0, behavior: 'instant' })
  window.dispatchEvent(new Event('resize'))
})
watch(() => router?.currentRoute.value.query.view, (next) => {
  const target = next === 'tour' ? 'tour' : next === 'changes' ? 'changes' : context ? 'overview' : 'changes'
  if (target !== view.value) view.value = target
})
watch(() => context?.focusRequest, () => { if (context?.tab === 'commits' || context?.tab === 'checks') sheet.value = context.tab; else { view.value = 'overview'; pane.value = 'conversation' } })
watch(() => context?.selection, () => { view.value = 'changes' })
watch(() => props.store.llm?.isAnalyzing, (busy, previous) => { if (previous && !busy) playCue(props.store.llm?.error ? 'error' : 'ready', true) })
watch(() => props.store.critique?.isRunning, (busy, previous) => { if (previous && !busy) playCue(props.store.critique?.error ? 'error' : 'ready', true) })

async function chooseFile(index: number) {
  activeFile.value = index
  view.value = 'changes'
  sheet.value = undefined
  await nextTick()
  fileView.value?.expand()
  window.scrollTo({ top: 0, behavior: 'instant' })
}
function source(path: string) { const index = diff.value?.files.findIndex(file => file.path === path) ?? -1; if (index >= 0) void chooseFile(index) }
watch(() => props.store.critique?.focused?.path, path => { if (path) source(path) })
function goBack() {
  if (view.value !== 'overview' && context && !openedOnTour) view.value = 'overview'
  else if (router && window.parent.history.state?.back) router.back()
  else void router?.push(back.value)
}
function pickCommit(selection: CommitSelection | undefined) { context?.select(selection); sheet.value = undefined; view.value = 'changes' }
async function send() {
  const question = ask.value.trim()
  if (!question || !props.store.llm || props.store.llm.chat.isStreaming) return
  ask.value = ''
  askExpanded.value = false
  blurAsk()
  chatOpen.value = true
  await props.store.llm.chat.send(question)
}
function changePage(event: Event) {
  const direction = (event as CustomEvent<string>).detail === 'left' ? 1 : -1
  const pages: View[] = ['overview', 'changes', 'tour']
  const next = pages[pages.indexOf(view.value) + direction]
  if (next) view.value = next
}
function blurAsk() { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); askFocused.value = false; if (!ask.value) askExpanded.value = false }
async function copyBranch(branch: string, message = t('mobile.branchCopied')) {
  try {
    await navigator.clipboard.writeText(branch)
    copyNotice.value = message
    playCue('success', true)
    window.setTimeout(() => { copyNotice.value = '' }, 2000)
  }
  catch { playCue('error', true) }
}
async function copyLink() {
  const url = pr.value?.url ?? diff.value?.url
  if (!url) return
  await copyBranch(url, t('mobile.linkCopied')); sheet.value = undefined
}
function review(value: ReviewVerdict) { verdict.value = value; reviewOpen.value = true }
</script>

<template>
  <div class="diffs-page min-h-dvh bg-base color-base" style="--diffs-header-height: calc(56px + env(safe-area-inset-top, 0px))">
    <header class="sticky top-0 z-nav flex items-center justify-between bg-base px-4 pt-[calc(0.75rem+env(safe-area-inset-top))]">
      <KitFloatButton icon="i-lu-back" :label="$t('keys.home')" @click="goBack" />
      <div class="relative h-11 w-53 flex items-center rounded-full bg-raised p-0.5 shadow-button">
        <span class="absolute bottom-0.5 left-0.5 top-0.5 w-[50.75px] rounded-full bg-paper-2 transition-transform duration-slide ease-slide" :style="{ transform: `translateX(${(['overview', 'changes', 'tour'].indexOf(view)) * 50.75}px)` }" />
        <div role="tablist" class="relative flex flex-1">
          <button v-for="item in (['overview', 'changes', 'tour'] as const)" :key="item" role="tab" :aria-selected="view === item" :aria-label="$t(`mobile.${item}`)" class="press-tint relative h-10 w-[50.75px] flex items-center justify-center rounded-full" @click="view = item">
            <span class="text-5" :class="item === 'overview' ? 'i-lu-list' : item === 'changes' ? 'i-lu-diff' : 'i-lu-tour'" aria-hidden="true" />
            <span v-if="item === 'tour' && tourBusy" class="absolute right-2.5 top-1.75 h-2 w-2 rounded-full bg-accent-teal animate-pulse" />
          </button>
        </div>
        <span class="mx-0.5 h-6 w-px bg-border" />
        <button class="press-tint h-10 w-[50.75px] flex items-center justify-center rounded-full text-sm font-medium" :aria-label="$t(view === 'changes' ? 'mobile.textTools' : view === 'tour' ? 'mobile.tour' : 'mobile.actions')" @click="view === 'tour' ? tourPage?.more() : sheet = view === 'changes' ? 'tools' : 'actions'">
          <span v-if="view === 'changes'">Aa</span><span v-else class="i-lu-more text-5" aria-hidden="true" />
        </button>
      </div>
    </header>
    <main v-if="!diff && !store.error" class="px-4 pt-5" aria-busy="true">
      <div class="kit-skeleton mb-3 h-8! w-4/5" /><div class="kit-skeleton mb-6 w-3/5" />
      <div v-for="n in 8" :key="n" class="kit-skeleton my-5" :style="{ width: `${95 - n * 4}%` }" />
    </main>
    <main v-else-if="store.error && !diff" class="flex flex-col items-center gap-3 px-8 py-12 text-center">
      <p class="text-row font-medium">{{ $t('pr.somethingWrong') }}</p><p class="text-sm color-muted">{{ store.error.message }}</p>
      <button class="btn-action" @click="store.load()">{{ $t('common.retry') }}</button>
    </main>
    <template v-else-if="diff">
      <OfflineBanner v-if="store.isOffline" :store="store" />
      <main v-show="view === 'overview'" v-mobile-swipe class="px-4 pb-32 pt-3" @swipe="changePage">
        <h1 class="text-xxl font-medium">{{ title }}</h1>
        <div class="mt-2 flex items-center gap-1.25 overflow-hidden whitespace-nowrap text-sm color-muted">
          <RouterLink v-if="repository" :to="parentForRef(diff.ref)?.route ?? '/'" class="min-w-0 truncate">{{ repository }}</RouterLink>
          <a :href="pr?.url ?? diff.url" target="_blank" rel="noopener" class="shrink-0 color-base font-semibold font-display">{{ pr ? `#${pr.number}` : diff.label }}</a><span v-if="author">·</span><span class="min-w-0 truncate">{{ author }}</span>
          <template v-if="age"><span>·</span><span class="shrink-0">{{ $t('mobile.ageAgo', { age }) }}</span></template>
        </div>
        <p v-if="lifecycle !== 'open'" class="mt-2 flex items-center gap-1.5 text-sm font-medium" :class="lifecycle === 'merged' ? 'color-purple' : lifecycle === 'closed' ? 'color-error' : 'color-muted'"><span :class="lifecycle === 'merged' ? 'i-lu-pr-merged' : lifecycle === 'closed' ? 'i-lu-pr-closed' : 'i-lu-pr-draft'" />{{ $t(`mobile.state.${lifecycle}`) }}</p>
        <div v-if="diff.head?.ref && diff.base?.ref" class="mt-2.5 flex items-center gap-1.5 overflow-x-auto text-sm color-muted [scrollbar-width:none]">
          <button class="shrink-0 rounded-md bg-accent-wash px-2 py-0.75 color-accent-wash-ink font-medium font-display" @click="copyBranch(diff.head.ref)">{{ diff.head.ref }}</button><span class="i-lu-to shrink-0 text-3.5" /><button class="shrink-0 rounded-md bg-accent-wash px-2 py-0.75 color-accent-wash-ink font-medium font-display" @click="copyBranch(diff.base.ref)">{{ diff.base.ref }}</button>
        </div>
        <div class="mt-3 grid grid-cols-[104px_76px_52px_minmax(0,1fr)] gap-2 border-t border-mute py-2.25 text-sm">
          <button class="text-left" @click="view = 'changes'"><span class="block color-muted">{{ $t('mobile.changes') }}</span><span class="inline-flex gap-1 font-medium"><span class="color-success">+{{ compactCount(additions) }}</span><span class="color-error">−{{ compactCount(deletions) }}</span></span></button>
          <button class="text-left" @click="sheet = 'commits'"><span class="block color-muted">{{ $t('overview.tab.commits') }}</span><span class="block font-medium font-display">{{ pr ? pr.commits.length + pr.commitsHidden : diff.commits?.length ?? '–' }}</span></button>
          <button class="text-left" @click="sheet = 'files'"><span class="block color-muted">{{ $t('mobile.files') }}</span><span class="block font-medium font-display">{{ diff.files.length }}</span></button>
          <button class="min-w-0 text-left" @click="sheet = 'checks'"><span class="block color-muted">{{ $t('overview.tab.checks') }}</span><span class="flex items-center gap-1 whitespace-nowrap" :class="pr?.checksSummary.failing ? 'color-error' : pr?.checksSummary.total ? 'color-success' : 'color-muted'"><span v-if="pr?.checksSummary.total" :class="pr.checksSummary.failing ? 'i-lu-xc' : pr.checksSummary.pending ? 'i-lu-history' : 'i-lu-okc'" />{{ $t(!pr?.checksSummary.total ? 'mobile.noChecks' : pr.checksSummary.failing ? 'mobile.checksFailing' : pr.checksSummary.pending ? 'mobile.checksPending' : 'mobile.checksPassing') }}</span></button>
        </div>
        <nav role="tablist" class="mb-5 mt-0.5 flex gap-6 border-b border-mute">
          <button v-for="item in ['summary', 'conversation']" :key="item" role="tab" :aria-selected="pane === item" class="press-tint relative h-12 text-base font-medium" :class="pane === item ? 'color-base' : 'color-muted'" @click="pane = item">{{ $t(item === 'summary' ? 'mobile.summary' : 'overview.tab.conversation') }}<span v-if="pane === item" class="absolute inset-x-0 bottom-0 h-0.5 bg-accent-teal" /></button>
        </nav>
        <div v-if="pane === 'summary'" class="flex flex-col gap-5">
          <SharedAnalysisBanner v-if="store.shared" :store="store" />
          <Suspense v-if="store.grouped?.overallSummary"><Markdown :value="store.grouped.overallSummary" class="chat-markdown text-read! leading-6!" @click="keepRepoLinks" /></Suspense>
          <PrHtml v-else-if="pr?.bodyHTML.trim()" :html="pr.bodyHTML" class="text-read! leading-6!" />
          <p v-else-if="pr" class="text-sm color-muted">{{ $t('overview.noDescription') }}</p>
          <div class="border-t border-mute pt-4">
            <h2 class="mb-2 text-xl font-medium">{{ $t('mobile.files') }}</h2>
            <button v-for="(item, index) in diff.files" :key="item.sha" class="press-tint min-h-17 w-full flex items-center gap-3 border-b border-mute py-3 text-left" @click="chooseFile(index)">
              <span class="min-w-0 flex-1"><span class="block break-words text-row font-semibold">{{ item.path.split('/').at(-1) }}</span><span v-if="item.path.includes('/')" class="mt-1 block truncate text-sm color-muted font-mono">{{ item.path.slice(0, item.path.lastIndexOf('/')) }}</span></span><span class="flex shrink-0 gap-1 text-right text-sm font-medium font-mono"><span class="w-12 color-success">+{{ compactCount(item.additions) }}</span><span class="w-12 color-error">−{{ compactCount(item.deletions) }}</span></span><span v-if="store.reviewed.has(item.sha)" class="i-lu-check color-success" />
            </button>
          </div>
          <CritiquePanel v-if="CritiquePanel && store.critique && (store.critique.result || store.critique.isRunning || store.critique.error)" :store="store" :critique="store.critique" />
        </div>
        <PrConversation v-else-if="pr" :overview="pr" @select-commit="context?.select(selectCommit($event))" />
      </main>
      <main v-show="view === 'changes'" class="pb-24 pt-2">
        <CommitRangeBanner />
        <div v-if="file" class="sticky top-[var(--diffs-header-height)] z-diff-counts h-11 flex items-center gap-3.5 bg-base px-4 text-sm">
          <span class="flex gap-1.5 font-medium font-mono"><span class="color-success">+{{ compactCount(file.additions) }}</span><span class="color-error">−{{ compactCount(file.deletions) }}</span></span>
          <button class="min-w-0 flex-1 text-left color-muted" @click="store.reviews?.setShowThreads(!store.reviews.showThreads)">{{ threads ? $t('mobile.threadCount', { n: threads }) : $t('mobile.noThreads') }}</button>
          <ReviewCheckbox :status="reviewStatus(store, file)" :aria-label="$t('file.markReviewed')" class="h-5.5! w-5.5! rounded-[7px]!" @update="store.setReviewed([file.sha], $event)" />
        </div>
        <div class="h-6 border-b border-mute" aria-hidden="true" />
        <FileDiff v-if="file" :key="file.sha" ref="fileView" :store="store" :file="file" mobile />
        <div v-if="file" class="fixed inset-x-0 bottom-0 z-nav flex items-center gap-2.5 bg-gradient-to-t from-base via-base/90 to-transparent px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-6">
          <button class="press-tint h-12 w-12 flex shrink-0 items-center justify-center rounded-full bg-raised shadow-button disabled:color-faint" :disabled="activeFile === 0" :aria-label="$t('mobile.previousFile')" @click="chooseFile(activeFile - 1)"><span class="i-lu-prev text-5.5" /></button>
          <button class="press-tint min-h-13 min-w-0 flex flex-1 flex-col items-center justify-center rounded-full bg-raised px-3.5 py-1.75 text-center shadow-bar" :aria-label="$t('mobile.chooseFile')" @click="sheet = 'files'"><span class="line-clamp-3 text-sm font-semibold">{{ filename }}</span><span v-if="folder || diff.files.length > 1" class="max-w-full truncate text-sm color-muted font-mono">{{ folder }}{{ folder && diff.files.length > 1 ? ' · ' : '' }}{{ diff.files.length > 1 ? `${activeFile + 1}/${diff.files.length}` : '' }}</span></button>
          <button class="press-tint h-12 w-12 flex shrink-0 items-center justify-center rounded-full bg-primary color-white shadow-button disabled:op40" :disabled="activeFile >= diff.files.length - 1" :aria-label="$t('mobile.nextFile')" @click="chooseFile(activeFile + 1)"><span class="i-lu-next text-5.5" /></button>
        </div>
      </main>
      <TourPage v-show="view === 'tour'" ref="tourPage" :store="store" :active="view === 'tour'" @source="source" @busy="tourBusy = $event" @ready="tourReady = $event" />
      <div v-if="askFocused && view !== 'changes'" class="fixed inset-0 z-search-dim bg-scrim/50" @pointerdown.prevent="blurAsk" />
      <div v-if="view !== 'changes' && store.llm?.isSetup && (view !== 'tour' || tourReady)" class="pointer-events-none fixed inset-x-0 bottom-0 z-foot-bar bg-gradient-to-t from-base via-base/90 to-transparent px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-6">
        <button v-if="!askExpanded && !ask" class="press-tint pointer-events-auto mx-auto h-13 w-26 flex items-center justify-center rounded-full bg-raised text-base font-medium shadow-search" @click="expandAsk">{{ $t('mobile.askShort') }}</button>
        <KitSearchPill v-else ref="askField" v-model="ask" class="pointer-events-auto" :placeholder="$t(view === 'tour' ? 'mobile.askTour' : 'mobile.askPull')" :search-icon="false" :send-label="$t('mobile.send')" :send-disabled="!ask.trim()" @send="send" @focusin="askFocused = true" @focusout="askFocused = false; if (!ask) askExpanded = false" />
      </div>
      <ChatWidget v-if="ChatWidget && store.llm && chatOpen" v-model:open="chatOpen" :store="store" />
    </template>
    <div v-if="copyNotice" role="status" class="fixed bottom-26 left-4 right-4 z-toast rounded-full bg-cta px-5 py-3 text-center text-sm color-on-cta shadow-button">{{ copyNotice }}</div>
    <SettingsModal v-model:open="settingsModalOpen" :document="document" />
    <ReviewSubmitModal v-if="store.reviews" v-model:open="reviewOpen" :reviews="store.reviews" :initial-verdict="verdict" fixed-verdict :document="document" />
    <AppModal :open="!!sheet" :document="document" :back="!!sheetBack" :title="sheet === 'checks' ? $t('overview.tab.checks') : sheet === 'commits' ? $t('overview.tab.commits') : sheet === 'files' ? $t('mobile.files') : sheet === 'tools' ? $t('mobile.textTools') : sheet === 'tools-font' ? $t('mobile.codeFont') : sheet === 'decide' ? $t('mobile.decisions') : sheet === 'analyze' ? $t('mobile.analyze') : sheet === 'model' ? $t('mobile.tourModel') : `${repository} ${diff?.label ?? ''}`" @back="sheet = sheetBack" @update:open="sheet = undefined">
      <div v-if="sheet === 'actions'" class="flex flex-col gap-3">
        <div class="grid gap-2" :class="store.llm ? 'grid-cols-3' : 'grid-cols-2'">
          <a :href="pr?.url ?? diff?.url" target="_blank" rel="noopener" class="press-tint min-h-18 flex flex-col items-center justify-center gap-2 rounded-card bg-raised p-3 text-sm shadow-note"><span class="i-carbon-logo-github text-5.5" />GitHub</a>
          <button class="press-tint min-h-18 flex flex-col items-center justify-center gap-2 rounded-card bg-raised p-3 text-sm shadow-note" @click="copyLink"><span class="i-lu-copy text-5.5" />{{ $t('mobile.copyLink') }}</button>
          <button v-if="store.llm" class="press-tint min-h-18 flex flex-col items-center justify-center gap-2 rounded-card bg-raised p-3 text-sm shadow-note" @click="sheet = 'analyze'"><span class="i-lu-list text-5.5" />{{ $t('mobile.analyze') }}</button>
        </div>
        <div v-if="store.reviews?.canWrite" class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t(store.reviews.pendingCommentCount ? 'mobile.submitComments' : 'mobile.leaveComment')" first chevron @activate="review('COMMENT')" /></div>
        <div v-if="store.reviews?.canWrite || pr?.viewer.canUpdate || pr?.viewer.canClose" class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.decisions')" first chevron @activate="sheet = 'decide'" /></div>
        <div v-if="critique && store.critique?.available" class="rounded-card bg-raised px-4 py-2 shadow-note"><component :is="critique" :critique="store.critique" :document="document" /></div>
        <DiffShareButton v-if="store.shared && store.aiResult && !store.aiResult.sharedBy" :store="store" :document="document" />
      </div>
      <div v-else-if="sheet === 'tools'" class="flex flex-col gap-6">
        <section><h3 class="text-sm color-muted">{{ $t('settings.layout') }}</h3><LayoutChoices class="mt-2.5" /></section>
        <section><h3 class="text-sm color-muted">{{ $t('mobile.code') }}</h3><div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note">
          <div class="flex flex-col gap-3 p-3.5"><KitCapsuleTabs v-model="codeZoom" :options="zoomOptions" class="w-full" /><CodeSample /></div>
          <KitSheetRow :label="$t('mobile.codeFont')" :value="codeFonts.find(font => font.id === settings.codeFont)?.label" chevron @activate="sheet = 'tools-font'" />
          <KitSheetRow :label="$t('mobile.wrapCode')" @activate="settings.codeWrap = !settings.codeWrap"><KitSwitch v-model="settings.codeWrap" :label="$t('mobile.wrapCode')" /></KitSheetRow>
          <KitSheetRow v-if="store.reviews" :label="$t('mobile.showComments')" @activate="store.reviews.setShowThreads(!store.reviews.showThreads)"><KitSwitch :model-value="store.reviews.showThreads" :label="$t('mobile.showComments')" @update:model-value="store.reviews.setShowThreads($event)" /></KitSheetRow>
        </div></section>
        <section v-if="file"><h3 class="text-sm color-muted">{{ filename }}</h3><div class="mt-3 overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.copyPath')" icon="i-lu-copy" first @activate="copyBranch(file.path, t('mobile.pathCopied')); sheet = undefined" /><a v-if="blob" :href="blob" target="_blank" rel="noopener"><KitSheetRow :label="$t('overview.viewOnGithub')" icon="i-carbon-logo-github" /></a><KitSheetRow v-if="store.fileContent && !file.isBinary" :label="$t('file.loadFull')" icon="i-lu-exp" @activate="fileView?.loadFullFile(); sheet = undefined" /></div></section>
        <div class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.actions')" icon="i-lu-more" first chevron @activate="sheet = 'actions'" /></div>
      </div>
      <div v-else-if="sheet === 'tools-font'" class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow v-for="(font, index) in codeFonts" :key="font.id" :label="font.label" :first="index === 0" :style="{ fontFamily: font.family }" @activate="settings.codeFont = font.id"><span v-if="settings.codeFont === font.id" class="i-lu-check color-primary" /></KitSheetRow></div>
      <div v-else-if="sheet === 'decide'" class="flex flex-col gap-3"><div v-if="store.reviews?.canWrite" class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('review.approve')" first chevron @activate="review('APPROVE')" /><KitSheetRow :label="$t('review.requestChanges')" chevron @activate="review('REQUEST_CHANGES')" /></div><PrMergeBox v-if="pr && context" :store="context.store" :overview="pr" :document="document" @show-checks="sheet = 'checks'" /></div>
      <div v-else-if="sheet === 'analyze'" class="flex flex-col gap-4"><p class="text-read">{{ $t('mobile.analyzeDescription') }}</p><div class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.tourModel')" :value="analysisModel" first chevron @activate="sheet = 'model'" /></div><DiffAnalyzeButton v-if="store.llm" :store="store" :document="document" /></div>
      <LlmSettingsSection v-else-if="sheet === 'model'" :llm-settings="settings.llm" :models="models.models.value" :models-loading="models.loading.value" :models-error="models.error.value" @update:llm-settings="settings.llm = $event" />
      <PrChecks v-else-if="sheet === 'checks' && pr" :checks="pr.checks" :summary="pr.checksSummary" :url="pr.url" />
      <PrCommitList v-else-if="sheet === 'commits' && pr" :commits="pr.commits" :hidden="pr.commitsHidden" :url="pr.url" :selection="context?.selection" @select="pickCommit" />
      <div v-else-if="sheet === 'files'" class="flex flex-col gap-1.5">
        <KitSearchPill v-if="(diff?.files.length ?? 0) > 1" v-model="fileSearch" :placeholder="$t('mobile.findFile')" class="rounded-card! shadow-note!" />
        <div class="overflow-hidden rounded-card bg-raised shadow-note">
          <div v-for="({ file: item, index }, row) in visibleFiles" :key="item.sha" role="button" tabindex="0" class="press-tint relative min-h-17 flex items-center gap-3 px-4 py-3" :class="activeFile === index ? 'bg-active' : ''" @click="chooseFile(index)" @keydown.enter.self.prevent="chooseFile(index)">
            <span v-if="row" class="absolute left-4 right-0 top-0 h-px bg-border-mute" />
            <ReviewCheckbox :status="reviewStatus(store, item)" :aria-label="$t('file.markReviewed')" class="h-5.5! w-5.5! rounded-[7px]!" @click.stop @update="store.setReviewed([item.sha], $event)" />
            <span class="min-w-0 flex-1"><span class="block text-row" :class="store.reviewed.has(item.sha) ? 'line-through color-muted' : ''">{{ item.path.split('/').at(-1) }}</span><span v-if="item.path.includes('/')" class="mt-0.75 block truncate text-left text-sm color-muted font-mono [direction:rtl]">{{ item.path.slice(0, item.path.lastIndexOf('/')) }}</span></span>
            <span class="flex shrink-0 gap-1 text-sm text-right font-medium font-mono"><span class="w-12 color-success">+{{ compactCount(item.additions) }}</span><span class="w-12 color-error">−{{ compactCount(item.deletions) }}</span></span>
          </div>
        </div>
      </div>
    </AppModal>
  </div>
</template>
