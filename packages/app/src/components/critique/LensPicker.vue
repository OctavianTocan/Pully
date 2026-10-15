<script setup lang="ts">
import type { ReviewLens } from '@pulls.review/core/local-rpc'
import type { DiffsStoreCritique } from '../../stores/types'
import ActionButton from '@antfu/design/components/Action/ActionButton.vue'
import FeedbackSpinner from '@antfu/design/components/Feedback/FeedbackSpinner.vue'
import FormSearchField from '@antfu/design/components/Form/FormSearchField.vue'
import { computed, nextTick, ref, useTemplateRef, watch } from 'vue'
import { FIELD, vField } from '../../directives/field'
import AppModal from '../AppModal.vue'

const props = defineProps<{
  critique: DiffsStoreCritique
  document?: Document | ShadowRoot
}>()

const emit = defineEmits<{
  /** The lens to review with; `undefined` is the general review. */
  pick: [lens: string | undefined]
}>()

const open = defineModel<boolean>('open', { required: true })

const lenses = ref<ReviewLens[]>()
const isLoading = ref(false)
const error = ref<string>()
const query = ref('')
const searchRef = useTemplateRef<HTMLElement>('search')

async function load() {
  isLoading.value = true
  error.value = undefined
  try {
    lenses.value = await props.critique.listLenses()
  }
  catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
  finally {
    isLoading.value = false
  }
}

watch(open, async (isOpen) => {
  if (!isOpen)
    return
  query.value = ''
  if (!lenses.value && !isLoading.value)
    void load()
  await nextTick()
  searchRef.value?.querySelector('input')?.focus()
})

const matches = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const all = lenses.value ?? []
  if (!needle)
    return all
  return all.filter(lens => lens.title.toLowerCase().includes(needle) || lens.name.toLowerCase().includes(needle) || lens.description?.toLowerCase().includes(needle))
})
const showGeneral = computed(() => !query.value.trim())

function pick(lens: string | undefined) {
  open.value = false
  emit('pick', lens)
}

function pickFirst() {
  if (showGeneral.value)
    pick(undefined)
  else if (matches.value[0])
    pick(matches.value[0].name)
}
</script>

<template>
  <AppModal v-model:open="open" :title="$t('lens.title')" :description="$t('lens.description')" :document="document">
    <div class="flex flex-col gap-2">
      <div ref="search" @keydown.enter.prevent="pickFirst">
        <FormSearchField v-model="query" v-field="FIELD.search" :placeholder="$t('lens.search')" size="sm" />
      </div>
      <ul class="flex flex-col gap-0.5" role="listbox" :aria-label="$t('lens.title')">
        <li v-if="showGeneral">
          <button
            type="button"
            role="option"
            :aria-selected="!critique.lens"
            class="w-full flex items-start gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-hover"
            :class="!critique.lens ? 'bg-active' : ''"
            @click="pick(undefined)"
          >
            <span class="i-lu-search mt-0.5 shrink-0 op-fade" aria-hidden="true" />
            <span class="min-w-0 flex flex-col">
              <span class="text-sm font-medium">{{ $t('lens.general') }}</span>
              <span class="text-xs op-fade">{{ $t('lens.generalDescription') }}</span>
            </span>
          </button>
        </li>
        <li v-for="lens in matches" :key="lens.name">
          <button
            type="button"
            role="option"
            :aria-selected="critique.lens === lens.name"
            class="w-full flex items-start gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-hover"
            :class="critique.lens === lens.name ? 'bg-active' : ''"
            @click="pick(lens.name)"
          >
            <span class="i-ph:lightbulb mt-0.5 shrink-0 op-fade" aria-hidden="true" />
            <span class="min-w-0 flex flex-col">
              <span class="text-sm font-medium">{{ lens.title }}</span>
              <span v-if="lens.description" class="line-clamp-2 text-xs op-fade">{{ lens.description }}</span>
            </span>
          </button>
        </li>
      </ul>
      <div v-if="isLoading" class="flex items-center gap-2 px-2 py-1.5 text-sm op-fade">
        <FeedbackSpinner />
        {{ $t('lens.loading') }}
      </div>
      <div v-else-if="error" class="flex items-center justify-between gap-2 px-2 py-1.5 text-sm text-red-600 dark:text-red-400">
        <span class="min-w-0">{{ $t('lens.loadFailed', { error }) }}</span>
        <ActionButton size="sm" @click="load">
          {{ $t('common.retry') }}
        </ActionButton>
      </div>
      <p v-else-if="lenses && !lenses.length" class="px-2 py-1.5 text-sm op-fade">
        {{ $t('lens.none') }}
      </p>
      <p v-else-if="lenses && !matches.length" class="px-2 py-1.5 text-sm op-fade">
        {{ $t('lens.noMatch') }}
      </p>
    </div>
  </AppModal>
</template>
