<script setup lang="ts">
import type { DiffsStore } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'

defineProps<{
  store: DiffsStore
}>()
</script>

<template>
  <div role="status" class="flex flex-wrap items-center gap-x-3 gap-y-2 border border-base rounded-lg bg-raised px-3 py-2 text-sm">
    <span class="i-ph:cloud-slash shrink-0 op-fade" aria-hidden="true" />
    <span class="min-w-0 flex-1">{{ $t('local.snapshot.offline') }}</span>
    <ActionButton size="sm" :disabled="store.isLoading" @click="store.load()">
      {{ $t('common.retry') }}
    </ActionButton>
  </div>
</template>
