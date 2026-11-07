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
  <div role="button" tabindex="0" :aria-expanded="!folded" class="press-tint min-h-11 flex items-center pl-2 pr-4 pt-1.5 -mx-4" @click="$emit('fold')" @keydown.enter.self.prevent="$emit('fold')" @keydown.space.self.prevent="$emit('fold')">
    <component
      :is="to ? 'RouterLink' : 'span'"
      :to="to"
      class="min-w-0 truncate rounded-md pb-1.5 pl-2 pr-1 pt-2.5 text-sm color-muted font-medium"
      :class="to ? 'press-tint hover:bg-hover' : ''"
      @click="to && $event.stopPropagation()"
    >
      {{ label }}
    </component>
    <span class="shrink-0 pt-1 text-sm color-muted font-medium tabular-nums">({{ count }})</span>
    <span class="i-lu-chevd ml-1 mt-1 shrink-0 text-4.5 color-faint transition-transform duration-slide ease-slide" :class="folded ? '-rotate-90' : ''" aria-hidden="true" />
  </div>
</template>
