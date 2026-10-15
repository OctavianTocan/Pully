<script setup lang="ts">
import type { MergeMethod, PrOverview } from '@pulls.review/core/github'
import type { PrOverviewStore } from '../../stores/pr-overview-store'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import ActionToggleGroup from '@antfu/design/components/Action/ActionToggleGroup.vue'
import FormTextarea from '@antfu/design/components/Form/FormTextarea.vue'
import FormTextInput from '@antfu/design/components/Form/FormTextInput.vue'
import { defaultMergeCommit } from '@pulls.review/core/github'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import AppModal from '../AppModal.vue'

const props = defineProps<{
  open: boolean
  store: PrOverviewStore
  overview: PrOverview
  document?: Document | ShadowRoot
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
}>()

const { t } = useI18n()

const method = ref<MergeMethod>(props.overview.merge.defaultMethod)
const title = ref('')
const body = ref('')
const defaults = computed(() => defaultMergeCommit(props.overview, method.value))

const methodOptions = computed(() => props.overview.merge.methods.map(value => ({ value, label: t(`overview.merge.method.${value}`) })))

watch([() => props.open, method], ([open]) => {
  if (!open)
    return
  title.value = defaults.value.title
  body.value = defaults.value.body
  props.store.clearActionError()
}, { immediate: true })

const busy = computed(() => props.store.busy === 'merge')

async function confirm() {
  // Untouched fields stay unset so GitHub applies the repository's own defaults.
  const ok = await props.store.merge({
    method: method.value,
    title: title.value !== defaults.value.title ? title.value : undefined,
    body: body.value !== defaults.value.body ? body.value : undefined,
  })
  if (ok)
    emit('update:open', false)
}
</script>

<template>
  <AppModal
    :title="$t('overview.merge.title', { n: overview.number })"
    :description="$t('overview.merge.into', { head: overview.head.ref, base: overview.base.ref })"
    :open="open"
    :document="document"
    @update:open="emit('update:open', $event)"
  >
    <div class="w-full flex flex-col gap-3 p-3">
      <ActionToggleGroup
        v-if="methodOptions.length > 1"
        :model-value="method"
        :options="methodOptions"
        @update:model-value="method = $event as MergeMethod"
      />
      <template v-if="method !== 'rebase'">
        <label class="flex flex-col gap-1 text-sm">
          <span class="op-fade">{{ $t('overview.merge.commitTitle') }}</span>
          <FormTextInput v-model="title" :disabled="busy" />
        </label>
        <label class="flex flex-col gap-1 text-sm">
          <span class="op-fade">{{ $t('overview.merge.commitMessage') }}</span>
          <FormTextarea v-model="body" :rows="6" :disabled="busy" />
        </label>
      </template>
      <p v-else class="text-sm op-fade">
        {{ $t('overview.merge.rebaseHint', { base: overview.base.ref }) }}
      </p>
      <p v-if="store.mergeability?.warning" class="text-sm text-amber-700 dark:text-amber-400">
        {{ $t(`overview.merge.${store.mergeability.warning}`) }}
      </p>
      <p v-if="store.actionError" class="text-sm text-red-600 dark:text-red-400">
        {{ $t('overview.actions.failed', { message: store.actionError }) }}
      </p>
      <div class="flex justify-end gap-2">
        <ActionButton variant="text" :disabled="busy" @click="emit('update:open', false)">
          {{ $t('common.cancel') }}
        </ActionButton>
        <ActionButton
          variant="primary"
          class="px3"
          icon="i-lu-merge"
          :loading="busy"
          :disabled="!store.mergeability?.ready || (method !== 'rebase' && !title.trim())"
          @click="confirm"
        >
          {{ $t('overview.merge.confirm') }}
        </ActionButton>
      </div>
    </div>
  </AppModal>
</template>
