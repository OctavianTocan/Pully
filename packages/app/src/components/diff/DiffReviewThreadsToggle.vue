<script setup lang="ts">
import KitFloatButton from '../kit/KitFloatButton.vue'

defineProps<{
  showThreads: boolean
}>()

defineEmits<{
  'update:showThreads': [value: boolean]
}>()
</script>

<template>
  <KitFloatButton
    icon="i-lu-reply"
    :active="showThreads"
    :label="$t(showThreads ? 'review.hideComments' : 'review.showComments')"
    @click="$emit('update:showThreads', !showThreads)"
  />
</template>
