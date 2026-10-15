<script setup lang="ts">
import type { CritiqueSeverity } from '@pulls.review/core/types'
import type { DiffsStore, DiffsStoreCritique } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionToggleGroup from '@antfu/design/components/Action/ActionToggleGroup.vue'
import FormCheckbox from '@antfu/design/components/Form/FormCheckbox.vue'
import FormTextarea from '@antfu/design/components/Form/FormTextarea.vue'
import { Markdown } from '@comark/vue'
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { keepRepoLinks } from '../ai/ai-format'
import AiActivityLog from '../ai/AiActivityLog.vue'
import AiUsageLine from '../ai/AiUsageLine.vue'
import FindingCard from './FindingCard.vue'

const props = defineProps<{
  store: DiffsStore
  critique: DiffsStoreCritique
}>()

const { t } = useI18n()
const isOpen = ref(true)
const body = ref('')

const result = computed(() => props.critique.result)
const reviews = computed(() => props.store.reviews)

const ENGINE_NAMES: Record<string, string> = { 'claude-code': 'Claude Code', 'codex': 'Codex', 'pi': 'Pi' }
// The usage line names the model the CLI reported, so it is only repeated here when the CLI named none.
const engine = computed(() => {
  const r = result.value
  return r ? [ENGINE_NAMES[r.engine] ?? r.engine, r.usage?.model ? undefined : r.model, r.effort].filter(Boolean).join(' · ') : ''
})
const isOutdated = computed(() => !!result.value?.headSha && result.value.headSha !== props.store.diff?.head?.sha)

const severityOptions = computed(() => (['bug', 'risk', 'nit'] as const).map((severity) => {
  const count = result.value?.findings.filter(finding => finding.severity === severity).length ?? 0
  return { value: severity, label: `${t(`critique.severity.${severity}`)} ${count}`, disabled: count === 0 }
}))

const selectable = computed(() => props.critique.visibleFindings.filter(finding => !props.critique.posted.has(finding.id)))
const allSelected = computed(() => selectable.value.length > 0 && selectable.value.every(finding => props.critique.selected.has(finding.id)))

watch(() => props.critique.defaultBody, (value) => {
  body.value = value
}, { immediate: true })

// The panel sits under the pull request's description, so a review started from the toolbar is otherwise off screen.
const root = useTemplateRef<HTMLElement>('root')
watch(() => props.critique.isRunning, (running) => {
  if (running)
    root.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}, { immediate: true, flush: 'post' })
</script>

<template>
  <section ref="root" data-critique-panel class="flex flex-col gap-3 border border-base rounded-lg bg-raised px-3 py-2">
    <header class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <button type="button" class="flex items-center gap-1.5 text-sm font-medium coarse:min-h-11" :aria-expanded="isOpen" @click="isOpen = !isOpen">
        <span :class="isOpen ? 'i-lu-chevd' : 'i-lu-next'" aria-hidden="true" />
        <span class="i-lu-search op-fade" aria-hidden="true" />
        {{ $t('critique.title') }}
      </button>
      <span v-if="critique.lens" class="rounded bg-active px-1.5 py-0.5 text-xs font-mono" :title="$t('critique.withLens', { lens: critique.lens })">
        {{ critique.lens }}
      </span>
      <span v-if="result" class="text-xs op-fade">
        {{ $t('critique.findingCount', { n: result.findings.length }, result.findings.length) }}
      </span>
      <span v-if="engine" class="text-xs op-mute">{{ engine }}</span>
      <AiUsageLine v-if="result && !critique.isRunning" class="ml-auto" :usage="result.usage" :title="$t('critique.usage')" />
    </header>

    <div v-if="critique.isRunning || critique.activities.length" class="flex items-start gap-3">
      <AiActivityLog
        class="min-w-0 flex-1"
        :activities="critique.activities"
        :running="critique.isRunning"
        :started-at="critique.startedAt"
        :outcome="critique.outcome"
      />
      <ActionButton v-if="critique.isRunning" size="sm" icon="i-lu-stop" @click="critique.abort()">
        {{ $t('common.stop') }}
      </ActionButton>
    </div>
    <div v-if="critique.error" class="flex items-center gap-2 text-sm text-red-600 dark:text-red-400">
      <span class="i-ph:warning-circle shrink-0" aria-hidden="true" />
      <span class="min-w-0 flex-1 break-words">{{ critique.error.message }}</span>
      <ActionButton size="sm" @click="critique.run({ lens: critique.lens, force: true })">
        {{ $t('common.retry') }}
      </ActionButton>
    </div>

    <template v-if="isOpen && result">
      <p v-if="isOutdated" class="text-xs text-amber-700 dark:text-amber-400">
        {{ $t('critique.outdated') }}
      </p>
      <Suspense v-if="result.summary">
        <Markdown :value="result.summary" class="chat-markdown min-w-0 text-sm" @click="keepRepoLinks" />
      </Suspense>

      <p v-if="!result.findings.length" class="text-sm op-fade">
        {{ $t('critique.noFindings') }}
      </p>
      <template v-else>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <ActionToggleGroup
            multiple
            :options="severityOptions"
            :aria-label="$t('critique.filter')"
            :model-value="critique.severities"
            @update:model-value="critique.setSeverities(($event ?? []) as CritiqueSeverity[])"
          />
          <FormCheckbox
            v-if="selectable.length"
            :label="$t('critique.selectAll')"
            :model-value="allSelected"
            @update:model-value="critique.selectAll($event)"
          />
        </div>
        <div class="flex flex-col gap-2">
          <FindingCard v-for="finding in critique.visibleFindings" :key="finding.id" :finding="finding" :critique="critique" />
        </div>
        <p v-if="!critique.visibleFindings.length" class="text-sm op-fade">
          {{ $t('critique.allFiltered') }}
        </p>
      </template>
      <p v-if="result.dropped" class="text-xs op-mute">
        {{ $t('critique.dropped', { n: result.dropped }, result.dropped) }}
      </p>

      <div v-if="result.findings.length && reviews" class="flex flex-col gap-2 border-t border-base pt-3">
        <template v-if="reviews.canWrite">
          <FormTextarea v-model="body" :rows="3" :placeholder="$t('critique.bodyPlaceholder')" :disabled="critique.isPosting" />
          <p v-if="reviews.pendingReview" class="text-xs op-fade">
            {{ $t('critique.joinsPending', { n: reviews.pendingCommentCount }, reviews.pendingCommentCount) }}
          </p>
          <p v-if="critique.postError" class="text-xs text-red-600 dark:text-red-400">
            {{ critique.postError.message }}
          </p>
          <!-- On phones the floating chat button sits over the right edge; keep the post button clear of it. -->
          <div class="flex flex-wrap items-center justify-end gap-2 pr-16 md:pr-0">
            <span v-if="critique.posted.size" class="mr-auto flex items-center gap-1 text-xs op-fade">
              <span class="i-lu-okc text-green-600 dark:text-green-400" aria-hidden="true" />
              {{ $t('critique.postedCount', { n: critique.posted.size }, critique.posted.size) }}
            </span>
            <ActionButton
              size="sm"
              variant="primary"
              icon="i-ph:paper-plane-tilt"
              :loading="critique.isPosting"
              :disabled="!critique.canPost"
              @click="critique.post(body)"
            >
              {{ $t('critique.post', { n: critique.toPost.length }, critique.toPost.length) }}
            </ActionButton>
          </div>
        </template>
        <p v-else-if="reviews.writeBlockedReason" class="text-xs op-fade">
          {{ reviews.writeBlockedReason }}
        </p>
      </div>
    </template>
  </section>
</template>
