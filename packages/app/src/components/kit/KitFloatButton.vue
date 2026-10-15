<!-- The phone's `FloatIcon`: a 44px raised circle with the button shadow, the icon at 20px. `flat` drops the shadow and fill, for a button inside a bar. -->
<script setup lang="ts">
import { computed } from 'vue'
import { vTooltip } from 'vue-afloat'

const props = defineProps<{
  icon: string
  /** The accessible name; also the tooltip. */
  label: string
  /** A link instead of a button. */
  to?: string
  flat?: boolean
  active?: boolean
  disabled?: boolean
}>()

const tag = computed(() => props.to === undefined ? 'button' : 'RouterLink')
</script>

<template>
  <component
    :is="tag"
    v-tooltip="label"
    :to="to"
    :type="to === undefined ? 'button' : undefined"
    :disabled="to === undefined ? disabled : undefined"
    :aria-label="label"
    :aria-pressed="active || undefined"
    class="press-tint h-11 w-11 flex shrink-0 items-center justify-center rounded-full color-base transition disabled:pointer-events-none hover:bg-hover disabled:op40"
    :class="[flat ? '' : 'bg-raised shadow-button', active ? 'bg-ambient!' : '']"
  >
    <span :class="icon" class="text-5" aria-hidden="true" />
  </component>
</template>
