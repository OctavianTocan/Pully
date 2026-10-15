<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import type { AiAnchor } from './file-annotations'
import AskThread from '../ask/AskThread.vue'
import FindingCard from './FindingCard.vue'

defineProps<{
  store: DiffsStore
  anchor: AiAnchor
}>()
</script>

<template>
  <p v-for="(note, index) in anchor.notes" :key="`note-${index}`" class="my-1 max-w-200 flex items-start gap-1.5 text-xs op-fade">
    <span class="min-w-0">{{ note }}</span>
  </p>
  <template v-if="store.critique">
    <FindingCard v-for="finding in anchor.findings" :key="finding.id" :finding="finding" :critique="store.critique" inline />
  </template>
  <template v-if="store.ask">
    <AskThread v-for="thread in anchor.threads" :key="thread.id" :thread="thread" :ask="store.ask" />
  </template>
</template>
