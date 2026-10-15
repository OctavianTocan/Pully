<script setup lang="ts">
import { useNow } from '@vueuse/core'
import { useI18n } from 'vue-i18n'
import { formatTimeAgo } from '../../i18n/time-ago'

defineProps<{
  updating: boolean
  /** When the list on screen was fetched, in epoch milliseconds. */
  fetchedAt?: number
  /** Why the last update failed. */
  error?: string
}>()

const emit = defineEmits<{
  refresh: []
}>()

const { locale } = useI18n()
const now = useNow({ interval: 30_000 })
</script>

<!-- A quiet line, as on the phone: what is on screen stays, and this only says how fresh it is. -->
<template>
  <div class="min-w-0 flex items-center gap-1 text-sm color-muted" role="status" aria-live="polite">
    <span v-if="updating">{{ $t('local.home.updating') }}</span>
    <span v-else-if="error" class="min-w-0 truncate" :title="error">{{ $t('local.home.updateFailed', { message: error }) }}</span>
    <span v-else-if="fetchedAt" :title="new Date(fetchedAt).toLocaleString(locale)">
      {{ $t('local.home.updatedAgo', { time: formatTimeAgo(new Date(fetchedAt), locale, now.getTime()) }) }}
    </span>
    <button
      type="button"
      class="press-tint h-9 w-9 flex shrink-0 items-center justify-center rounded-full hover:bg-hover disabled:op40"
      :disabled="updating"
      :aria-label="$t('local.home.refresh')"
      :title="$t('local.home.refresh')"
      @click="emit('refresh')"
    >
      <span class="i-lu-refresh text-4.5" :class="updating ? 'animate-spin' : ''" aria-hidden="true" />
    </button>
  </div>
</template>
