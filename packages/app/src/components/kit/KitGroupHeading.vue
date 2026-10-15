<!-- The phone's `GroupHeading`: a list section's name in the muted ink, its count fainter, and a chevron that folds it. -->
<script setup lang="ts">
defineProps<{
  label: string
  count: number
  folded?: boolean
  /** Where the name leads, such as the repository's own list. */
  to?: string | object
}>()

defineEmits<{ fold: [] }>()
</script>

<template>
  <div class="flex items-center gap-0.5 pt-2 -mx-2">
    <component
      :is="to ? 'RouterLink' : 'span'"
      :to="to"
      class="rounded-md px-2 pb-1.5 pt-2.5 text-sm color-muted font-medium"
      :class="to ? 'press-tint hover:bg-hover' : ''"
    >
      {{ label }}
    </component>
    <span class="flex-1 text-sm color-faint tabular-nums">{{ count }}</span>
    <button
      type="button"
      class="press-tint h-9 w-9 flex items-center justify-center rounded-full color-faint hover:bg-hover"
      :aria-expanded="!folded"
      :aria-label="label"
      @click="$emit('fold')"
    >
      <span class="i-lu-chevd text-4.5 transition-transform duration-slide ease-slide" :class="folded ? '-rotate-90' : ''" aria-hidden="true" />
    </button>
  </div>
</template>
