<!-- The phone's `CapsuleTabs`: a raised pill with the float shadow, the chosen segment a paper pill that slides between them. -->
<script setup lang="ts" generic="T extends string">
import { computed } from 'vue'

const props = defineProps<{
  options: { value: T, label: string }[]
  /** No shadow, for a capsule that sits on a raised surface already. */
  flat?: boolean
}>()

const model = defineModel<T>({ required: true })
const index = computed(() => Math.max(0, props.options.findIndex(o => o.value === model.value)))
</script>

<template>
  <div
    role="tablist"
    class="relative h-11 inline-flex shrink-0 rounded-full bg-raised p-0.5 text-sm"
    :class="flat ? '' : 'shadow-button'"
  >
    <span
      class="absolute bottom-0.5 left-0.5 top-0.5 rounded-full bg-paper-2 transition-transform duration-slide ease-slide"
      :style="{ width: `calc((100% - 4px) / ${options.length})`, transform: `translateX(${index * 100}%)` }"
      aria-hidden="true"
    />
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="tab"
      :aria-selected="option.value === model"
      class="relative min-w-24 flex-1 rounded-full px-4 transition-colors"
      :class="option.value === model ? 'color-base' : 'color-muted hover:color-base'"
      @click="model = option.value"
    >
      {{ option.label }}
    </button>
  </div>
</template>
