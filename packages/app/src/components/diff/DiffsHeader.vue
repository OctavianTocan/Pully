<script setup lang="ts">
import type { GroupSource } from '@pulls.review/core/types'
import type { DiffsStore } from '../../stores/types'
import { ACTIONS_BOT_LOGIN } from '@pulls.review/core/github'
import { computed, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { parentForRef } from '../../source-routes'
import { showGroupSidebar } from '../../state/group-nav'
import GithubAvatar from '../GithubAvatar.vue'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'
import KitFloatButton from '../kit/KitFloatButton.vue'
import NavControls from '../NavControls.vue'
import { usePrOverview } from '../overview/context'
import PrHeaderChips from '../overview/PrHeaderChips.vue'
import DiffAnalyzeButton from './DiffAnalyzeButton.vue'
import DiffGroupNav from './DiffGroupNav.vue'
import DiffGroupNavToggle from './DiffGroupNavToggle.vue'
import DiffPrMeta from './DiffPrMeta.vue'
import DiffReviewThreadsToggle from './DiffReviewThreadsToggle.vue'
import DiffShareButton from './DiffShareButton.vue'
import PrStatusIcon from './PrStatusIcon.vue'

const props = defineProps<{
  document?: Document | ShadowRoot
  store: DiffsStore
  groupsVisable: string[]
  scrollY: number
}>()

const { t } = useI18n()

// `store.diff` is guaranteed set - `DiffsPage` only renders this component once it is.
const meta = computed(() => props.store.diff!)
const overviewCtx = usePrOverview()
// While the diff is narrowed to some commits, `meta` describes those commits; the header still names the PR.
const pr = computed(() => overviewCtx?.store.overview)
const display = computed(() => {
  const current = pr.value
  if (!current) {
    const { title, label, url, base, head, author } = meta.value
    return { title, label, url, state: meta.value.pullRequest?.state, base: base?.ref, head: head?.ref, author: author && { login: author.name, avatarUrl: author.avatarUrl } }
  }
  return { title: current.title, label: `#${current.number}`, url: current.url, state: current.state, base: current.base.ref, head: current.head.ref, author: current.author }
})
const groups = computed(() => props.store.groups)
// The embedded view keys off the compile-time `PR_EMBED` flag instead of a runtime flag
// threaded down from the store.
const isEmbedded = import.meta.env.PR_EMBED
const CritiqueButton = import.meta.env.PR_LOCAL
  ? defineAsyncComponent(() => import('../critique/CritiqueButton.vue'))
  : undefined

const aiResult = computed(() => props.store.aiResult)
const analyzeOptions = computed(() => [
  { value: 'rule-based' as GroupSource, label: t('pr.rules') },
  { value: (aiResult.value?.source ?? 'llm') as GroupSource, label: t('pr.ai') },
])
const analyzeMode = computed({
  get: () => props.store.analyzeMode,
  set: (mode: GroupSource) => props.store.setAnalyzeMode(mode),
})
// Share is for results the viewer generated - a loaded shared result is credited, not re-shared.
const canShareResult = computed(() => props.store.shared && props.store.llm && aiResult.value && !aiResult.value.sharedBy)

const parent = computed(() => parentForRef(meta.value.ref))
// Back leads where the list came from: the inbox on the local build, the repository's list on the web.
const back = computed(() => import.meta.env.PR_LOCAL ? '/' : parent.value?.route ?? '/')

const reviews = computed(() => props.store.reviews)

function refresh() {
  void props.store.refresh()
  void overviewCtx?.store.refresh()
}

function scrollToGroup(key: string) {
  (props.document ?? document).getElementById(`group-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<!--
  The phone's PR header: a back button and the page's controls floating over the paper, the title large,
  then one quiet line of where and who, the branches as soft chips, and the PR's own chips.
-->
<template>
  <header
    class="left-0 right-0 top-0 z-nav bg-base px-4 pb-3 pt-[calc(0.75rem+env(safe-area-inset-top))] transition-shadow md:sticky"
    :class="scrollY > 20 ? 'shadow-bar' : ''"
  >
    <div class="mxa max-w-500 w-full flex flex-col gap-3">
      <div class="flex items-center gap-2">
        <KitFloatButton v-if="!isEmbedded" icon="i-lu-back" :to="back" :label="$t('keys.home')" />
        <PrStatusIcon v-else-if="display.state" :state="display.state" />
        <div class="flex-1" />
        <DiffReviewThreadsToggle
          v-if="reviews"
          :show-threads="reviews.showThreads"
          @update:show-threads="reviews.setShowThreads($event)"
        />
        <KitFloatButton v-if="store.canRefresh" icon="i-lu-refresh" :label="$t('common.refresh')" @click="refresh" />
        <NavControls :document="document">
          <!-- The sidebar only exists at `lg` and up, so the choice is only offered there. -->
          <div class="hidden lg:block">
            <DiffGroupNavToggle />
          </div>
        </NavControls>
      </div>

      <h1 class="break-words text-xxl font-medium font-display">
        {{ display.title }}
      </h1>

      <div class="flex flex-col gap-2">
        <p class="flex flex-wrap items-center gap-x-1.5 text-sm color-muted">
          <RouterLink v-if="parent && !isEmbedded" :to="parent.route" class="hover:underline">
            {{ parent.label }}
          </RouterLink>
          <a v-if="display.label" :href="display.url" target="_blank" rel="noopener" class="color-base font-semibold hover:underline">{{ display.label }}</a>
          <template v-if="display.author">
            <span aria-hidden="true">·</span>
            <span class="flex items-center gap-1.5">
              <GithubAvatar :login="display.author.login" :avatar-url="display.author.avatarUrl" :size="16" />
              {{ display.author.login }}
            </span>
          </template>
        </p>
        <p v-if="display.base && display.head && !isEmbedded" class="max-w-full min-w-0 flex items-center gap-2 text-sm">
          <span class="min-w-0 truncate rounded-md bg-accent-wash px-2 py-0.5 color-accent-wash-ink" :title="display.head">{{ display.head }}</span>
          <span class="i-lu-to shrink-0 text-4 color-muted" aria-hidden="true" />
          <span class="min-w-0 truncate rounded-md bg-accent-wash px-2 py-0.5 color-accent-wash-ink" :title="display.base">{{ display.base }}</span>
        </p>
      </div>

      <div class="flex flex-wrap items-center gap-2 text-sm">
        <!-- Its own row on every width: once an AI grouping exists it is the only way between the two. -->
        <KitCapsuleTabs v-if="aiResult" v-model="analyzeMode" :options="analyzeOptions" :aria-label="$t('pr.analyzeBy')" flat class="border border-base" />
        <PrHeaderChips />
        <span v-if="aiResult?.sharedBy && store.analyzeMode !== 'rule-based'" class="h-9 flex items-center gap-1.5 border border-base rounded-full px-3.5" :title="aiResult.model">
          <template v-if="aiResult.sharedBy === ACTIONS_BOT_LOGIN">
            {{ $t('pr.sharedAnalysis') }}
          </template>
          <template v-else>
            {{ $t('pr.sharedBy') }}
            <GithubAvatar :login="aiResult.sharedBy" :size="16" />
            {{ aiResult.sharedBy }}
          </template>
        </span>
        <DiffAnalyzeButton v-if="store.llm" :store="store" :document="document" />
        <CritiqueButton v-if="CritiqueButton && store.critique?.available" :critique="store.critique" :document="document" />
        <DiffShareButton v-if="canShareResult" :store="store" :document="document" />
        <DiffPrMeta v-if="showGroupSidebar" class="ml-auto" :store="store" :document="document" />
      </div>

      <div v-if="!showGroupSidebar" class="flex flex-wrap items-center gap-2 text-sm">
        <DiffGroupNav
          class="min-w-60 flex-auto"
          :groups="groups"
          :groups-visable="groupsVisable"
          :reviewed="store.reviewed"
          @select="scrollToGroup"
        />

        <DiffPrMeta class="ml-auto self-end" :store="store" :document="document" />
      </div>
    </div>
  </header>
</template>
