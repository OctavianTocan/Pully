<script setup lang="ts">
defineProps<{ href: string }>()
</script>

<template>
  <a :href="href" target="_blank" rel="noopener" class="color-active hover:underline">
    <slot />
  </a>
</template>
