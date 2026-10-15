<!--
  The phone's `SearchPill`: a 54px raised pill with the search shadow. Typing filters at once; when the text
  names something to open (a link, a number), an Open pill appears in it and Enter opens it, so there is
  no second field.
-->
<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{
  placeholder: string
  /** Set when the text can be opened; the Open pill's accessible name. */
  openLabel?: string
}>()

const emit = defineEmits<{ open: [] }>()
const model = defineModel<string>({ required: true })
const { t } = useI18n()
</script>

<template>
  <form
    role="search"
    class="h-13.5 flex items-center gap-3 rounded-full bg-raised pl-5 shadow-search"
    :class="model ? 'pr-2' : 'pr-5'"
    @submit.prevent="openLabel && emit('open')"
  >
    <span class="i-lu-search shrink-0 text-5 color-muted" aria-hidden="true" />
    <input
      v-model="model"
      type="search"
      :placeholder="placeholder"
      :aria-label="placeholder"
      class="min-w-0 flex-1 bg-transparent text-lg color-base outline-none [&::-webkit-search-cancel-button]:hidden placeholder:color-muted"
    >
    <button v-if="openLabel" type="submit" class="press-tint h-10 shrink-0 rounded-full bg-cta px-4 text-sm color-on-cta font-medium" :aria-label="openLabel">
      {{ t('local.home.open') }}
    </button>
    <button v-if="model" type="button" class="press-tint h-10 w-10 flex shrink-0 items-center justify-center rounded-full color-muted hover:bg-hover" :aria-label="t('common.clear')" @click="model = ''">
      <span class="i-lu-close text-4.5" aria-hidden="true" />
    </button>
  </form>
</template>
