<script setup lang="ts">
import NavControls from './NavControls.vue'

/**
 * The phone's header: no bar, only floating controls over the paper. The wordmark leads home; the
 * default slot takes the middle, as the Inbox's capsule tabs do on the phone.
 */
</script>

<template>
  <header class="sticky top-0 z-nav flex items-center gap-3 bg-base px-4 pb-2 pt-[calc(0.75rem+env(safe-area-inset-top))]">
    <!-- On a phone the middle (the Inbox's tabs) takes the wordmark's place, as the phone app has none. -->
    <RouterLink to="/" class="press-tint h-11 flex shrink-0 items-center rounded-full px-1 text-xl font-medium font-display" :class="$slots.default ? 'max-sm:hidden' : ''">
      Pully
    </RouterLink>
    <div class="min-w-0 flex flex-1 sm:justify-center">
      <slot />
    </div>
    <NavControls />
  </header>
</template>
