<script setup lang="ts">
import FeedbackEmptyState from '@antfu/design/components/Feedback/FeedbackEmptyState.vue'
import { useI18n } from 'vue-i18n'
import { useDocumentTitle } from '../composables/useDocumentTitle'

// A diff an agent posted (plan 13) opens only in the phone app; the web never reads it (Tavi, 16 Oct 14:17).
const { t } = useI18n()
useDocumentTitle(() => t('local.posted.title'))
</script>

<template>
  <main>
    <FeedbackEmptyState icon="i-lu-terminal" :title="$t('local.posted.title')">
      <template #hint>
        {{ $t('local.posted.hint') }}
      </template>
    </FeedbackEmptyState>
  </main>
</template>
