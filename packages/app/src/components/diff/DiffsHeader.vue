<script setup lang="ts">
import type { GroupSource } from '@pulls.review/core/types'
import type { DiffsStore } from '../../stores/types'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import ActionToggleGroup from '@antfu/design/components/Action/ActionToggleGroup.vue'
import { ACTIONS_BOT_LOGIN } from '@pulls.review/core/github'
import { computed, defineAsyncComponent } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { parentForRef } from '../../source-routes'
import { showGroupSidebar } from '../../state/group-nav'
import GithubAvatar from '../GithubAvatar.vue'
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
  { value: 'rule-based', label: t('pr.rules') },
  { value: aiResult.value?.source ?? 'llm', label: t('pr.ai'), icon: 'i-ph-sparkle-duotone' },
])
// Share is for results the viewer generated - a loaded shared result is credited, not re-shared.
const canShareResult = computed(() => props.store.shared && props.store.llm && aiResult.value && !aiResult.value.sharedBy)

const parent = computed(() => parentForRef(meta.value.ref))

const reviews = computed(() => props.store.reviews)

function refresh() {
  void props.store.refresh()
  void overviewCtx?.store.refresh()
}

function scrollToGroup(key: string) {
  (props.document ?? document).getElementById(`group-${key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <header
    class="left-0 right-0 top-0 z-nav flex flex-col gap-2 border-b bg-base px4 pb2 pt-[calc(0.5rem+env(safe-area-inset-top))] transition-all md:sticky"
    :class="scrollY > 20 ? 'border-base shadow-sm' : 'border-transparent' "
  >
    <div class="mxa max-w-500 w-full">
      <div class="flex flex-wrap items-center gap-2">
        <component :is="isEmbedded ? 'div' : RouterLink" to="/" class="flex coarse:(min-h-11 min-w-11 items-center justify-center -ml-3)">
          <PrStatusIcon v-if="display.state" :state="display.state" />
          <div v-else class="i-ph-house-line-duotone" />
        </component>
        <!-- Phones: the icon and the controls share the first row, so the title gets a full row of its own. -->
        <div class="flex-auto md:hidden" />
        <h1 class="flex flex-auto items-center gap-2 break-words text-lg font-semibold max-md:(order-1 basis-full)">
          {{ display.title }}
          <a v-if="display.label" :href="display.url" target="_blank" rel="noopener" class="text-base font-normal op-fade coarse:(min-h-11 inline-flex items-center) hover:underline">{{ display.label }}</a>
          <ActionIconButton
            v-if="store.canRefresh"
            icon="i-ph:arrows-clockwise-duotone"
            :label="$t('common.refresh')" :tooltip="$t('common.refresh')"
            class="shrink-0 text-sm" @click="refresh"
          />
        </h1>

        <div
          v-if="aiResult"
          class="flex shrink-0 items-center gap-1.5 text-sm max-md:order-2"
        >
          <span class="op-fade">{{ $t('pr.analyzeBy') }}</span>
          <ActionToggleGroup
            :model-value="store.analyzeMode"
            :options="analyzeOptions"
            @update:model-value="store.setAnalyzeMode($event as GroupSource)"
          />
        </div>

        <DiffReviewThreadsToggle
          v-if="reviews"
          :show-threads="reviews.showThreads"
          @update:show-threads="reviews.setShowThreads($event)"
        />
        <div class="shrink-0">
          <NavControls :document="document">
            <!-- The sidebar only exists at `lg` and up, so the choice is only offered there. -->
            <div class="hidden lg:block">
              <DiffGroupNavToggle />
            </div>
          </NavControls>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-sm">
        <RouterLink v-if="parent && !isEmbedded" :to="parent.route" class="flex items-center gap-1.5 op-fade coarse:min-h-11 hover:underline">
          <span>{{ parent.label }}</span>
        </RouterLink>
        <span v-if="display.author" class="flex items-center gap-1.5">
          <span class="op-fade">{{ $t('pr.by') }}</span>
          <GithubAvatar :login="display.author.login" :avatar-url="display.author.avatarUrl" :size="16" />
          <span class="op-fade">{{ display.author.login }}</span>
        </span>
        <span v-if="display.base && display.head && !isEmbedded" class="max-w-full min-w-0 flex items-center gap-1 font-mono">
          <span class="min-w-0 truncate border border-base rounded bg-code px-2 py-0.5 text-xs font-mono" :title="display.base">{{ display.base }}</span>
          ←
          <span class="min-w-0 truncate border border-base rounded bg-code px-2 py-0.5 text-xs font-mono" :title="display.head">{{ display.head }}</span>
        </span>
        <PrHeaderChips />
        <span v-if="aiResult?.sharedBy && store.analyzeMode !== 'rule-based'" class="flex items-center gap-1.5 border border-base rounded px2" :title="aiResult.model">
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

      <div v-if="!showGroupSidebar" class="flex flex-wrap items-center gap-2 pt-2 text-sm">
        <DiffGroupNav
          class="min-w-60 flex-auto"
          :groups="groups"
          :groups-visable="groupsVisable"
          :reviewed="store.reviewed"
          @select="scrollToGroup"
        />

        <DiffPrMeta class="ml-auto self-end pt-2" :store="store" :document="document" />
      </div>
    </div>
  </header>
</template>
