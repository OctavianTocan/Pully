<script setup lang="ts">
import type { CliModel } from '@pulls.review/core/local-rpc'
import FormTextInput from '@antfu/design/components/Form/FormTextInput.vue'
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'

// In-flow like `ModelPicker`: a teleported popper would escape the embed's shadow root unstyled.
const props = defineProps<{
  /** The models the CLI reported; unset until they load. */
  models?: readonly CliModel[]
  loading?: boolean
  /** Why the models couldn't be loaded; the picker then takes a typed model id. */
  error?: string
}>()

const modelId = defineModel<string>({ required: true })

const open = ref(false)
const query = ref('')
const listEl = useTemplateRef<HTMLDivElement>('listEl')

watch(open, async (isOpen) => {
  query.value = ''
  if (isOpen) {
    await nextTick()
    listEl.value?.scrollIntoView({ block: 'nearest' })
  }
})

const current = computed(() => props.models?.find(model => model.id === modelId.value))

const filtered = computed(() => {
  const all = props.models ?? []
  const q = query.value.trim().toLowerCase()
  if (!q)
    return all
  return all.filter(model => [model.name, model.id, model.description ?? ''].some(field => field.toLowerCase().includes(q)))
})

const customCandidate = computed(() => {
  const q = query.value.trim()
  return q && !props.models?.some(model => model.id === q) ? q : undefined
})

function pick(id: string) {
  modelId.value = id
  open.value = false
}
</script>

<template>
  <div v-if="error" class="flex flex-col gap-1">
    <FormTextInput v-model="modelId" :placeholder="$t('settings.models.placeholder')" />
    <p class="flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-400">
      <span class="i-ph:warning mt-0.5 shrink-0" aria-hidden="true" />
      <span class="break-words">{{ $t('settings.llm.catalogFailed', { message: error }) }}</span>
    </p>
  </div>

  <div v-else class="flex flex-col border border-base rounded bg-raised">
    <button
      type="button"
      class="h-9 inline-flex items-center justify-between gap-2 px-2.5 text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-primary-500/40"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span v-if="loading && !models" class="flex items-center gap-2 op-fade">
        <span class="i-ph:circle-notch animate-spin" aria-hidden="true" />
        {{ $t('settings.models.loading') }}
      </span>
      <span v-else class="min-w-0 flex items-center gap-2 color-base">
        <span class="truncate">{{ current?.name ?? modelId }}</span>
        <span v-if="current?.isDefault" class="shrink-0 border border-base rounded px-1 text-xs op-mute">{{ $t('settings.models.default') }}</span>
        <span v-if="current?.description" class="min-w-0 truncate text-xs op-mute">{{ current.description }}</span>
      </span>
      <span :class="open ? 'i-ph:caret-up' : 'i-ph:caret-down'" class="shrink-0 op-fade" aria-hidden="true" />
    </button>

    <div v-if="open" ref="listEl" class="flex flex-col border-t border-base">
      <div class="p-1.5">
        <FormTextInput
          v-model="query"
          size="sm"
          icon="i-ph:magnifying-glass"
          :placeholder="$t('settings.models.search')"
          clearable
          class="w-full"
        />
      </div>
      <div class="max-h-64 of-y-auto overscroll-contain pb-1" role="listbox">
        <div v-if="loading && !models" class="px-3 py-4 text-center text-sm op-mute">
          {{ $t('settings.models.loading') }}
        </div>
        <div v-else-if="!filtered.length && !customCandidate" class="px-3 py-4 text-center text-sm op-mute">
          {{ $t('settings.models.noMatch') }}
        </div>
        <button
          v-for="model in filtered"
          :key="model.id"
          type="button"
          role="option"
          :aria-selected="model.id === modelId"
          class="w-full flex items-start gap-2 px-3 py-1.5 text-left text-sm outline-none transition focus-visible:bg-hover"
          :class="model.id === modelId ? 'color-active bg-active' : 'hover:bg-hover'"
          @click="pick(model.id)"
        >
          <span class="min-w-0 flex flex-1 flex-col">
            <span class="flex items-center gap-2">
              <span class="truncate" :class="model.id === modelId ? '' : 'color-base'">{{ model.name }}</span>
              <span v-if="model.isDefault" class="shrink-0 border border-base rounded px-1 text-xs op-mute">{{ $t('settings.models.default') }}</span>
              <span v-if="model.name !== model.id" class="min-w-0 truncate text-xs font-mono op-mute">{{ model.id }}</span>
            </span>
            <span v-if="model.description" class="text-xs op-mute">{{ model.description }}</span>
          </span>
          <span v-if="model.id === modelId" class="i-ph:check mt-1 shrink-0 text-xs" aria-hidden="true" />
        </button>
        <button
          v-if="customCandidate"
          type="button"
          class="w-full flex items-center gap-2 px-3 py-1.5 text-left text-sm op-fade outline-none transition focus-visible:bg-hover hover:bg-hover hover:op100"
          @click="pick(customCandidate)"
        >
          <span class="i-ph:plus shrink-0 text-xs" aria-hidden="true" />
          <span class="min-w-0 flex-1 truncate">{{ $t('settings.models.useCustom', { id: customCandidate }) }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
