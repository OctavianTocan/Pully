<script setup lang="ts">
defineProps<{
  label: string
  disabled?: boolean
}>()
const model = defineModel<boolean>({ required: true })
</script>

<template>
  <button type="button" role="switch" :aria-label="label" :aria-checked="model" :disabled="disabled" class="relative h-8 w-13 shrink-0 rounded-full transition-colors duration-200 disabled:op50" :class="model ? 'bg-primary' : 'bg-border'" @click="model = !model">
    <span class="absolute left-0.75 top-0.75 h-6.5 w-6.5 rounded-full bg-white shadow-thumb transition-transform duration-200" :class="model ? 'translate-x-5' : ''" />
  </button>
</template>
