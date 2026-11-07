<!--
  The phone's `SearchPill`: a 52px raised pill with the search shadow. Typing filters at once; when the text
  names something to open (a link, a number), an Open pill appears in it and Enter opens it, so there is
  no second field.
-->
<script setup lang="ts">
import { useI18n } from 'vue-i18n'

withDefaults(defineProps<{
  placeholder: string
  /** Set when the text can be opened; the Open pill's accessible name. */
  openLabel?: string
  sendLabel?: string
  sendDisabled?: boolean
  searchIcon?: boolean
}>(), { searchIcon: true })

const emit = defineEmits<{ open: [], send: [] }>()
const model = defineModel<string>({ required: true })
const { t } = useI18n()
function focusField(event: MouseEvent) {
  if (event.target instanceof Element && !event.target.closest('button')) (event.currentTarget as HTMLFormElement).querySelector('input')?.focus()
}
</script>

<template>
  <form
    role="search"
    class="h-13 flex items-center gap-2.5 rounded-full bg-raised pl-4 shadow-search"
    :class="model || sendLabel ? 'pr-1.5' : 'pr-4'"
    @click="focusField"
    @submit.prevent="openLabel ? emit('open') : sendLabel && !sendDisabled ? emit('send') : undefined"
  >
    <span v-if="searchIcon !== false" class="i-lu-search shrink-0 text-5 color-muted" aria-hidden="true" />
    <input
      v-model="model"
      type="search"
      :enterkeyhint="openLabel ? 'go' : sendLabel ? 'send' : 'search'"
      :placeholder="placeholder"
      :aria-label="placeholder"
      class="min-w-0 flex-1 bg-transparent text-row color-base outline-none [&::-webkit-search-cancel-button]:hidden placeholder:color-muted"
    >
    <button v-if="openLabel" type="submit" class="press-tint h-10 shrink-0 rounded-full bg-cta px-4 text-sm color-on-cta font-medium" :aria-label="openLabel">
      {{ t('local.home.open') }}
    </button>
    <button v-if="sendLabel && !openLabel" type="submit" :disabled="sendDisabled" class="press-tint h-10 w-10 flex shrink-0 items-center justify-center rounded-full bg-primary color-white disabled:op40" :aria-label="sendLabel" data-emphasis="normal"><span class="i-lu-arrow text-5" aria-hidden="true" /></button>
    <button v-else-if="model" type="button" class="press-tint h-10 w-10 flex shrink-0 items-center justify-center rounded-full color-muted hover:bg-hover" :aria-label="t('common.clear')" @click="model = ''">
      <span class="i-lu-close text-4.5" aria-hidden="true" />
    </button>
  </form>
</template>
