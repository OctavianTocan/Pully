<script setup lang="ts">
defineProps<{
  /** e.g. `Worked for 2m 3s`. */
  label: string
  /** e.g. `Ran 3 commands, Read 4 files`. */
  summary?: string
  /** Shows the label in red, for work that failed. */
  failed?: boolean
  /** Whether there is a work log to unfold; without one the label is plain text. */
  expandable?: boolean
}>()

const open = defineModel<boolean>('open', { default: false })
</script>

<template>
  <div class="min-w-0 flex flex-col gap-2">
    <component
      :is="expandable ? 'button' : 'div'"
      :type="expandable ? 'button' : undefined"
      :aria-expanded="expandable ? open : undefined"
      class="min-w-0 flex items-center self-start gap-1.5 rounded text-left outline-none coarse:min-h-11"
      :class="[
        failed ? 'text-red-600 dark:text-red-400' : 'op-fade',
        expandable ? 'transition hover:op100 focus-visible:ring-2 focus-visible:ring-primary-500/40' : '',
      ]"
      @click="expandable && (open = !open)"
    >
      <span class="i-ph:clock shrink-0" aria-hidden="true" />
      <span class="shrink-0 tabular-nums">{{ label }}</span>
      <span v-if="summary" class="min-w-0 truncate op-mute">· {{ summary }}</span>
      <span v-if="expandable" :class="open ? 'i-ph:caret-up' : 'i-ph:caret-down'" class="shrink-0 text-xs" aria-hidden="true" />
    </component>
    <slot v-if="expandable && open" />
  </div>
</template>
