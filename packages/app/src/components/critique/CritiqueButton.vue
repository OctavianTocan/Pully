<script setup lang="ts">
import type { DiffsStoreCritique } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionIconButton from '@antfu/design/components/Action/ActionIconButton.vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatClockDuration } from '../ai/ai-format'
import LensPicker from './LensPicker.vue'

const props = defineProps<{
  critique: DiffsStoreCritique
  document?: Document | ShadowRoot
}>()

const { t } = useI18n()
const pickerOpen = ref(false)

const label = computed(() => {
  if (props.critique.isRunning)
    return t('critique.running', { time: formatClockDuration(props.critique.elapsed * 1000) })
  return props.critique.result ? t('critique.rerun') : t('critique.run')
})
const title = computed(() => props.critique.lens ? t('critique.withLens', { lens: props.critique.lens }) : t('critique.runTitle'))

function revealPanel() {
  (props.document ?? document).querySelector('[data-critique-panel]')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onClick() {
  if (props.critique.isRunning)
    revealPanel()
  else
    void props.critique.run({ lens: props.critique.lens, force: !!props.critique.result })
}

function onPick(lens: string | undefined) {
  void props.critique.run({ lens })
}
</script>

<template>
  <span class="flex shrink-0 items-center">
    <ActionButton
      class="shrink-0 text-xs"
      :variant="critique.result || critique.isRunning ? 'text' : 'action'"
      :icon="critique.isRunning ? 'i-lu-redo animate-spin' : 'i-lu-search'"
      :title="title"
      @click="onClick"
    >
      <span class="tabular-nums">{{ label }}</span>
    </ActionButton>
    <ActionIconButton
      v-if="critique.hasLenses"
      compact
      icon="i-lu-chevd"
      :disabled="critique.isRunning"
      :label="$t('lens.pick')"
      :tooltip="$t('lens.pick')"
      @click="pickerOpen = true"
    />
    <LensPicker v-if="critique.hasLenses" v-model:open="pickerOpen" :critique="critique" :document="document" @pick="onPick" />
  </span>
</template>
