<script setup lang="ts">
import type { PrEventKind, PrReviewState, PrTimelineItem } from '@pulls.review/core/github'
import DisplayDate from '@antfu/design/components/Display/DisplayDate.vue'
import { labelStyle } from '@antfu/design/utils/color'
import { ref } from 'vue'
import { isDark } from '../../state/dark'
import GithubAvatar from '../GithubAvatar.vue'
import PrHtml from './PrHtml.vue'

defineProps<{
  item: PrTimelineItem
}>()

const emit = defineEmits<{
  selectCommit: [oid: string]
}>()

const showHidden = ref(false)
const showInline = ref(false)

const REVIEW_DISPLAY: Record<PrReviewState, { icon: string, key: string }> = {
  approved: { icon: 'i-ph:check-circle-duotone text-green-600 dark:text-green-400', key: 'overview.timeline.approved' },
  changes_requested: { icon: 'i-ph:x-circle-duotone text-red-600 dark:text-red-400', key: 'overview.timeline.changesRequested' },
  commented: { icon: 'i-ph:chat-circle-dots-duotone op-fade', key: 'overview.timeline.reviewed' },
  dismissed: { icon: 'i-ph:prohibit op-mute', key: 'overview.timeline.reviewed' },
  pending: { icon: 'i-ph:hourglass-duotone op-mute', key: 'overview.timeline.reviewed' },
}

const EVENT_ICON: Record<PrEventKind, string> = {
  labeled: 'i-ph:tag-duotone',
  unlabeled: 'i-ph:tag-duotone',
  assigned: 'i-ph:user-duotone',
  unassigned: 'i-ph:user-duotone',
  ready_for_review: 'i-ph:eye-duotone',
  convert_to_draft: 'i-ph:pencil-simple-duotone',
  closed: 'i-ph:x-circle-duotone text-red-600 dark:text-red-400',
  reopened: 'i-ph:arrow-counter-clockwise-duotone text-green-600 dark:text-green-400',
  merged: 'i-ph:git-merge-duotone color-purple-500',
  force_pushed: 'i-ph:git-commit-duotone',
  review_requested: 'i-ph:eye-duotone',
  review_dismissed: 'i-ph:prohibit',
  renamed: 'i-ph:pencil-simple-line-duotone',
}

function labelChipStyle(color: string) {
  const { color: text, background, borderColor } = labelStyle(`#${color}`, isDark.value)
  return { color: text, background, borderColor }
}
</script>

<template>
  <li v-if="item.kind === 'event'" class="flex flex-wrap items-center gap-x-1.5 gap-y-1 px-1 text-sm">
    <span :class="EVENT_ICON[item.event]" class="shrink-0 op-fade" aria-hidden="true" />
    <GithubAvatar v-if="item.actor" :login="item.actor.login" :avatar-url="item.actor.avatarUrl" :size="16" />
    <span class="font-medium">{{ item.actor?.login ?? 'ghost' }}</span>
    <i18n-t :keypath="`overview.event.${item.event}`" tag="span" scope="global" class="op-fade">
      <template #label>
        <span
          v-if="item.label"
          class="inline-flex items-center border rounded-full px-2 py-0.5 text-xs font-medium leading-none"
          :style="labelChipStyle(item.label.color)"
        >{{ item.label.name }}</span>
      </template>
      <template #subject>
        <span class="color-base font-medium" :class="{ 'font-mono': item.event === 'merged' }">{{ item.subject }}</span>
      </template>
      <template #from>
        <span class="color-base" :class="item.event === 'renamed' ? 'font-medium' : 'font-mono'">{{ item.from }}</span>
      </template>
      <template #to>
        <span class="color-base" :class="item.event === 'renamed' ? 'font-medium' : 'font-mono'">{{ item.to }}</span>
      </template>
    </i18n-t>
    <DisplayDate :date="item.createdAt" class="text-xs op-mute" />
    <p v-if="item.message" class="w-full pl-6 text-xs op-fade">
      {{ item.message }}
    </p>
  </li>

  <li v-else-if="item.kind === 'commits'" class="flex flex-col gap-1 px-1 text-sm">
    <div class="flex items-center gap-1.5">
      <span class="i-ph:git-commit-duotone shrink-0 op-fade" aria-hidden="true" />
      <GithubAvatar v-if="item.actor" :login="item.actor.login" :avatar-url="item.actor.avatarUrl" :size="16" />
      <span class="font-medium">{{ item.actor?.login ?? 'ghost' }}</span>
      <span class="op-fade">{{ $t('overview.timeline.pushed', { n: item.commits.length }, item.commits.length) }}</span>
      <DisplayDate :date="item.createdAt" class="text-xs op-mute" />
    </div>
    <ul class="flex flex-col pl-6">
      <li v-for="commit in item.commits" :key="commit.oid" class="flex items-center gap-2 text-xs">
        <button
          type="button"
          class="min-w-0 truncate text-left op-fade coarse:py-2.5 hover:underline hover:op-100"
          :title="$t('overview.commitList.view')"
          @click="emit('selectCommit', commit.oid)"
        >
          {{ commit.headline }}
        </button>
        <a :href="commit.url" target="_blank" rel="noopener" class="ml-auto shrink-0 font-mono op-mute coarse:(py-2.5 pl-2) hover:underline">{{ commit.abbreviatedOid }}</a>
      </li>
    </ul>
  </li>

  <li v-else class="border border-base rounded-lg">
    <div class="flex flex-wrap items-center gap-x-1.5 gap-y-1 border-b border-base bg-raised px-3 py-1.5 text-sm" :class="{ 'border-b-0 rounded-lg': item.kind === 'comment' && item.minimizedReason && !showHidden }">
      <span v-if="item.kind === 'review'" :class="REVIEW_DISPLAY[item.state].icon" class="shrink-0" aria-hidden="true" />
      <GithubAvatar v-if="item.actor" :login="item.actor.login" :avatar-url="item.actor.avatarUrl" :size="18" />
      <span class="font-medium">{{ item.actor?.login ?? 'ghost' }}</span>
      <span class="op-fade">{{ item.kind === 'review' ? $t(REVIEW_DISPLAY[item.state].key) : $t('overview.timeline.commented') }}</span>
      <a :href="item.url" target="_blank" rel="noopener" class="text-xs op-mute hover:underline">
        <DisplayDate :date="item.createdAt" />
      </a>
      <button
        v-if="item.kind === 'comment' && item.minimizedReason"
        type="button"
        class="ml-auto text-xs op-fade hover:underline"
        :aria-expanded="showHidden"
        @click="showHidden = !showHidden"
      >
        {{ $t('overview.timeline.hidden', { reason: item.minimizedReason }) }}
      </button>
    </div>

    <div v-if="item.bodyHTML.trim() && (item.kind !== 'comment' || !item.minimizedReason || showHidden)" class="px-3 py-2">
      <PrHtml :html="item.bodyHTML" />
    </div>

    <div v-if="item.kind === 'review' && item.commentCount" class="border-t border-base px-3 py-1.5 text-sm">
      <button type="button" class="flex items-center gap-1 op-fade coarse:min-h-11 hover:op-100" :aria-expanded="showInline" @click="showInline = !showInline">
        <span :class="showInline ? 'i-ph:caret-down' : 'i-ph:caret-right'" aria-hidden="true" />
        {{ $t('overview.timeline.inlineComments', { n: item.commentCount }, item.commentCount) }}
      </button>
      <ul v-if="showInline" class="mt-1.5 flex flex-col gap-2">
        <li v-for="comment in item.comments" :key="comment.id" class="flex flex-col gap-1 border-l-2 border-base pl-3">
          <div class="flex flex-wrap items-center gap-1.5 text-xs">
            <a :href="comment.url" target="_blank" rel="noopener" class="font-mono op-fade hover:underline">{{ comment.path }}<template v-if="comment.line">:{{ comment.line }}</template></a>
            <span v-if="comment.outdated" class="border border-base rounded px-1 op-mute">{{ $t('overview.timeline.outdated') }}</span>
          </div>
          <PrHtml :html="comment.bodyHTML" class="text-xs!" />
        </li>
        <li v-if="item.commentCount > item.comments.length">
          <a :href="item.url" target="_blank" rel="noopener" class="text-xs op-fade hover:underline">
            {{ $t('overview.moreOnGithub', { n: item.commentCount - item.comments.length }) }}
          </a>
        </li>
      </ul>
    </div>
  </li>
</template>
