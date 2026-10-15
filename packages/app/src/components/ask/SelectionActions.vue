<script setup lang="ts">
import type { FileChange, ReviewDraftTarget } from '@pulls.review/core/types'
import type { DiffsStore } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import { useClipboard } from '@vueuse/core'
import { computed, ref } from 'vue'
import { askSelectionOf, githubBlobUrl, lineRangeLabel } from './selection'

const props = defineProps<{
  store: DiffsStore
  file: FileChange
  target: ReviewDraftTarget
  /** Shown without a comment composer below it, so it carries its own close button. */
  standalone?: boolean
}>()

const emit = defineEmits<{
  /** The selection is handled, e.g. a question thread was opened on it. */
  done: []
}>()

const selection = computed(() => askSelectionOf(props.target))
const label = computed(() => lineRangeLabel(selection.value))
const link = computed(() => props.store.diff && githubBlobUrl(props.store.diff, props.file, selection.value))

const { copy, copied } = useClipboard({ legacy: true, copiedDuring: 1500 })
const lastCopied = ref<'label' | 'link'>()

function copyText(kind: 'label' | 'link', text: string) {
  lastCopied.value = kind
  void copy(text)
}

function askAi() {
  props.store.ask?.open(selection.value)
  emit('done')
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-1 py-1 pl-3 pr-1 text-xs" :class="standalone ? '' : 'border-b border-base'">
    <span class="mr-auto min-w-0 truncate font-mono op-fade">{{ label }}</span>
    <ActionButton v-if="store.ask?.available" size="sm" variant="text" @click="askAi">
      {{ $t('ask.action') }}
    </ActionButton>
    <ActionIconButton
      compact
      :icon="copied && lastCopied === 'label' ? 'i-lu-check' : 'i-lu-copy'"
      :label="$t('ask.copyLocation')"
      :tooltip="copied && lastCopied === 'label' ? $t('ask.copied') : $t('ask.copyLocation')"
      @click="copyText('label', label)"
    />
    <ActionIconButton
      v-if="link"
      compact
      :icon="copied && lastCopied === 'link' ? 'i-lu-check' : 'i-ph:link'"
      :label="$t('ask.copyLink')"
      :tooltip="copied && lastCopied === 'link' ? $t('ask.copied') : $t('ask.copyLink')"
      @click="copyText('link', link)"
    />
    <ActionIconButton v-if="standalone" compact icon="i-lu-close" :label="$t('common.close')" @click="emit('done')" />
  </div>
</template>
