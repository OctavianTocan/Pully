<script setup lang="ts">
import type { CritiqueFinding } from '@pulls.review/core/types'
import type { DiffsStoreCritique } from '../../stores/types'
import FormCheckbox from '@antfu/design/components/Form/FormCheckbox.vue'
import { Markdown } from '@comark/vue'
import { computed } from 'vue'
import { keepRepoLinks } from '../ai/ai-format'
import { lineRangeLabel } from '../ask/selection'
import SeverityBadge from './SeverityBadge.vue'

const props = defineProps<{
  finding: CritiqueFinding
  critique: DiffsStoreCritique
  /** Shown inside the diff, at the line it is about. */
  inline?: boolean
}>()

const isPosted = computed(() => props.critique.posted.has(props.finding.id))
const location = computed(() => lineRangeLabel({
  path: props.finding.path,
  side: props.finding.side,
  startLine: props.finding.startLine ?? props.finding.line,
  endLine: props.finding.line,
}))
</script>

<template>
  <article
    :data-finding-id="finding.id"
    class="flex gap-2.5 border border-base rounded-lg bg-base px-3 py-2"
    :class="[inline ? 'my-1 max-w-200' : '', isPosted ? 'op-fade' : '']"
  >
    <span
      v-if="isPosted"
      class="i-lu-okc mt-0.5 shrink-0 text-green-600 dark:text-green-400"
      :title="$t('critique.postedFinding')"
      :aria-label="$t('critique.postedFinding')"
    />
    <FormCheckbox
      v-else
      class="mt-0.5 shrink-0"
      :aria-label="$t('critique.selectFinding')"
      :model-value="critique.selected.has(finding.id)"
      @update:model-value="critique.setSelected(finding.id, $event)"
    />
    <div class="min-w-0 flex flex-1 flex-col gap-1">
      <div class="flex flex-wrap items-center gap-2">
        <SeverityBadge :severity="finding.severity" />
        <span class="min-w-0 text-sm font-medium">{{ finding.title }}</span>
        <button
          v-if="!inline"
          type="button"
          class="ml-auto min-w-0 truncate text-xs font-mono op-fade coarse:(py-3 -my-3) hover:underline hover:op-100"
          :title="$t('critique.jumpToLine')"
          @click="critique.focus(finding)"
        >
          {{ location }}
        </button>
      </div>
      <Suspense>
        <Markdown :value="finding.body" class="chat-markdown min-w-0 text-sm" @click="keepRepoLinks" />
      </Suspense>
      <div v-if="finding.suggestion !== undefined" class="flex flex-col gap-1">
        <span class="text-xs op-mute">{{ $t('critique.suggestion') }}</span>
        <pre class="overflow-x-auto rounded bg-raised px-2 py-1.5 text-xs font-mono">{{ finding.suggestion }}</pre>
      </div>
    </div>
  </article>
</template>
