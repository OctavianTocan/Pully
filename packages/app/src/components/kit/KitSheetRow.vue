<script setup lang="ts">
defineProps<{
  label: string
  value?: string
  first?: boolean
  chevron?: boolean
  icon?: string
}>()
defineEmits<{ activate: [] }>()
</script>

<template>
  <div role="button" tabindex="0" class="press-tint relative min-h-13 flex items-center gap-3.5 px-4 text-row color-base" @click="$emit('activate')" @keydown.enter.self.prevent="$emit('activate')" @keydown.space.self.prevent="$emit('activate')">
    <span v-if="!first" class="absolute right-0 top-0 h-px bg-border-mute" :class="icon ? 'left-13' : 'left-4'" aria-hidden="true" />
    <span v-if="icon" :class="icon" class="shrink-0 text-5.5" aria-hidden="true" />
    <span class="min-w-0 flex-1 py-2">{{ label }}</span>
    <span v-if="value || $slots.default || chevron" class="max-w-1/2 min-w-0 flex items-center gap-1.5 text-sm color-muted">
      <span v-if="value" class="truncate">{{ value }}</span>
      <span v-if="$slots.default" class="flex shrink-0 items-center" @click.stop><slot /></span>
      <span v-if="chevron" class="i-lu-chev shrink-0 text-4.5 color-faint" aria-hidden="true" />
    </span>
  </div>
</template>
