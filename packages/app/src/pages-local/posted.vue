<script setup lang="ts">
import FeedbackEmptyState from '@antfu/design/components/Feedback/FeedbackEmptyState.vue'
import { useDocumentTitle } from '../composables/useDocumentTitle'

// A diff an agent posted (plan 13) opens only in the phone app; the web never reads it (Tavi, 16 Oct 14:17). The words
// stay out of the locales, which the phone copies, since only this one web page says them.
const title = 'This diff opens in the Pully app'
useDocumentTitle(() => title)
</script>

<template>
  <main>
    <FeedbackEmptyState icon="i-lu-terminal" :title="title">
      <template #hint>
        Diffs that agents post open only on your phone, signed in to the account.
      </template>
    </FeedbackEmptyState>
  </main>
</template>
