<script setup lang="ts">
import type { PullRequestListStore } from '../../stores/pull-request-list-store'
import type { PullRequestSort } from './pull-request-list-query'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '../AppModal.vue'
import GithubAvatar from '../GithubAvatar.vue'
import KitCapsuleTabs from '../kit/KitCapsuleTabs.vue'
import KitFloatButton from '../kit/KitFloatButton.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'
import { PULL_REQUEST_SORTS } from './pull-request-list-query'

defineProps<{
  store: PullRequestListStore
  scrollY: number
}>()
const query = defineModel<string>('query', { required: true })
const sort = defineModel<PullRequestSort>('sort', { required: true })
const tab = defineModel<string>('tab', { default: 'pulls' })
const sortOpen = ref(false)
const { t } = useI18n()
const tabs = computed(() => [{ value: 'pulls', label: t('mobile.pullRequests') }, { value: 'tours', label: t('mobile.tours') }])
</script>

<template>
  <header class="bg-base px-4 pt-[calc(0.75rem+env(safe-area-inset-top))]">
    <div class="flex items-center justify-between">
      <KitFloatButton icon="i-lu-back" to="/" :label="$t('keys.home')" />
      <a :href="`https://github.com/${store.owner}/${store.repo}/pulls`" target="_blank" rel="noopener" class="press-tint h-11 w-11 flex items-center justify-center rounded-full bg-raised shadow-button" :aria-label="$t('overview.viewOnGithub')"><span class="i-carbon-logo-github text-5" aria-hidden="true" /></a>
    </div>
    <div class="mt-3.5 flex items-center gap-1.5 text-sm color-muted">
      <GithubAvatar :login="store.owner" :size="18" /><span>{{ store.owner }}</span><span v-if="store.totalCount !== undefined">· {{ $t('pulls.open', { n: store.totalCount }) }}</span>
    </div>
    <h1 class="mt-0.5 text-xxl font-medium">
      {{ store.repo }}
    </h1>
    <KitCapsuleTabs v-model="tab" :options="tabs" class="mt-3.5 w-full" />
  </header>
  <div class="pointer-events-none fixed inset-x-0 bottom-0 z-nav flex items-center gap-2.5 from-base via-base/90 to-transparent bg-gradient-to-t px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-5">
    <KitSearchPill v-model="query" class="pointer-events-auto min-w-0 flex-1" :placeholder="$t(tab === 'tours' ? 'mobile.searchTours' : 'pulls.search')" />
    <KitFloatButton v-if="tab === 'pulls'" icon="i-lu-sort" :label="$t('pulls.sort.label')" class="pointer-events-auto h-13! w-13!" @click="sortOpen = true" />
  </div>
  <AppModal v-model:open="sortOpen" :title="$t('pulls.sort.label')">
    <div class="overflow-hidden rounded-card bg-raised">
      <button v-for="option in PULL_REQUEST_SORTS" :key="option" role="option" :aria-selected="sort === option" class="press-tint min-h-13 w-full flex items-center justify-between border-b border-mute px-4 text-left text-base last:border-0" @click="sort = option; sortOpen = false">
        {{ $t(`pulls.sort.${option}`) }}<span v-if="sort === option" class="i-lu-check color-primary" />
      </button>
      <button class="press-tint min-h-13 w-full flex items-center gap-3 px-4 text-base" :disabled="store.isRefreshing" @click="store.refresh()">
        <span class="i-lu-refresh" :class="store.isRefreshing ? 'animate-spin' : ''" />{{ $t('common.refresh') }}
      </button>
    </div>
  </AppModal>
</template>
