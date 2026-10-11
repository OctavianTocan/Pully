<script setup lang="ts">
import type { PrCommit } from '@pulls.review/core/github'
import type { CommitSelection } from './commit-selection'
import DisplayDate from '@antfu/design/components/Display/DisplayDate.vue'
import { computed, ref } from 'vue'
import GithubAvatar from '../GithubAvatar.vue'
import { resolveCommitSelection, selectCommitRange } from './commit-selection'
import { CHECKS_ICON } from './status-icons'

const props = defineProps<{
  /** Oldest first. */
  commits: PrCommit[]
  /** Older commits GitHub has but the list does not show. */
  hidden: number
  /** The PR's GitHub page. */
  url: string
  selection?: CommitSelection
  /** Headline and sha only, for the header picker. */
  compact?: boolean
}>()

const emit = defineEmits<{
  /** `extend` is set when the click grew a range rather than picking one commit. */
  select: [selection: CommitSelection | undefined, extend: boolean]
}>()

const range = computed(() => props.selection && resolveCommitSelection(props.commits, props.selection))
const anchor = ref<number>()

function pick(index: number, event: MouseEvent) {
  const current = range.value
  if (event.shiftKey) {
    emit('select', selectCommitRange(props.commits, anchor.value ?? current?.start ?? index, index), true)
    return
  }
  anchor.value = index
  const alreadyOnlyThis = current?.start === index && current.end === index
  emit('select', alreadyOnlyThis ? undefined : selectCommitRange(props.commits, index), false)
}

function isSelected(index: number) {
  return !!range.value && index >= range.value.start && index <= range.value.end
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <a v-if="hidden" :href="`${url}/commits`" target="_blank" rel="noopener" class="px-2 py-1 text-xs op-fade hover:underline">
      {{ $t('overview.moreOnGithub', { n: hidden }) }}
    </a>
    <ol class="flex flex-col">
      <li
        v-for="(commit, index) in commits"
        :key="commit.oid"
        class="flex items-center gap-2 rounded-md transition"
        :class="isSelected(index) ? 'bg-active color-active' : 'hover:bg-hover'"
      >
        <button
          type="button"
          class="press-tint min-w-0 flex flex-1 select-none items-center gap-2 py-1.5 pl-2 text-left text-sm outline-none coarse:py-3 focus-visible:ring-2 focus-visible:ring-primary-500/40"
          :aria-pressed="isSelected(index)"
          :title="$t('overview.commitList.view')"
          @click="pick(index, $event)"
        >
          <span
            :class="commit.checks ? CHECKS_ICON[commit.checks] : 'i-ph:git-commit op-mute'"
            class="shrink-0 text-xs"
            :aria-label="commit.checks ? $t(`pulls.checks.${commit.checks}`) : undefined"
          />
          <span class="min-w-0 flex-1 truncate">{{ commit.headline }}</span>
          <span v-if="!compact" class="hidden shrink-0 items-center gap-1.5 text-xs op-fade sm:flex">
            <GithubAvatar v-if="commit.author.login" :login="commit.author.login" :avatar-url="commit.author.avatarUrl" :size="16" />
            {{ commit.author.login ?? commit.author.name }}
          </span>
          <DisplayDate v-if="!compact" :date="commit.committedDate" class="shrink-0 text-xs op-mute" />
        </button>
        <a :href="commit.url" target="_blank" rel="noopener" class="shrink-0 py-1.5 pr-2 text-xs font-mono op-mute coarse:(py-3 pl-2) hover:underline">{{ commit.abbreviatedOid }}</a>
      </li>
    </ol>
    <p v-if="!compact && commits.length > 1" class="px-2 text-xs op-mute">
      {{ $t('overview.commitList.hint') }}
    </p>
  </div>
</template>
