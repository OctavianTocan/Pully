<script setup lang="ts">
import FormCheckbox from '@antfu/design/components/Form/FormCheckbox.vue'
import { settingsModalOpen } from '../../state/settingsModal'
import { usageStats } from '../../state/usage-stats'

// Only the build our server serves records usage; the public site has no server of ours to send to.
</script>

<template>
  <div class="flex flex-col gap-1">
    <h3 class="mb-1 text-sm color-base font-medium">
      {{ $t('stats.title') }}
    </h3>
    <FormCheckbox v-model="usageStats" :label="$t('stats.usage')" />
    <p class="text-xs color-muted">
      {{ $t('stats.usageHint') }}
    </p>
    <RouterLink to="/stats" class="btn-action-sm mt-1 self-start" @click="settingsModalOpen = false">
      {{ $t('stats.title') }}
    </RouterLink>
  </div>
</template>
