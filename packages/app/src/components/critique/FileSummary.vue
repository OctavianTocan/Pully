<script setup lang="ts">
defineProps<{
  summary: string
}>()
</script>

<template>
  <div class="flex items-start gap-2 border-b border-base px-3 py-2 text-sm op-fade" :title="$t('critique.fileSummary')">
    <p class="min-w-0">
      {{ summary }}
    </p>
  </div>
</template>
