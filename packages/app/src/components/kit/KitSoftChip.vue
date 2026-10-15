<!-- The phone's `SoftChip`: a 36px pill with a hairline edge, filled when chosen, its count in the muted ink. -->
<script setup lang="ts">
defineProps<{
  label: string
  selected?: boolean
  count?: number | string
  /** A line glyph before the label, as the sort chip carries. */
  icon?: string
}>()
</script>

<template>
  <button
    type="button"
    role="tab"
    :aria-selected="selected"
    class="h-9 inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap border rounded-full px-3.5 text-sm color-base transition-colors"
    :class="selected ? 'border-transparent bg-ambient' : 'border-base hover:bg-hover'"
  >
    <span v-if="icon" :class="icon" class="text-4 color-muted" aria-hidden="true" />
    <span>{{ label }}</span>
    <span v-if="count !== undefined" class="color-muted tabular-nums">{{ count }}</span>
  </button>
</template>
