<script setup lang="ts">
import type { Command } from '../state/commands'
import DisplayKbd from '@antfu/design/components/Display/DisplayKbd.vue'
import { computed, nextTick, ref, useId, useTemplateRef, watch } from 'vue'
import { deepActiveElement, eventChord } from '../composables/useKeyboardShortcuts'
import { t } from '../i18n'
import { rankItems } from '../state/command-rank'
import { commands, isAvailable } from '../state/commands'
import { COMMAND, keysFor } from '../state/keymap'
import { paletteOpen, paletteScope } from '../state/palette'
import AppModal from './AppModal.vue'

const props = defineProps<{
  document?: Document | ShadowRoot
}>()

const LIMIT = 200
const PAGE = 10

const id = useId()
const query = ref('')
const selected = ref(0)
const input = useTemplateRef<HTMLInputElement>('input')
const list = useTemplateRef<HTMLElement>('list')
let returnFocus: HTMLElement | undefined

const candidates = computed(() => {
  if (!paletteOpen.value)
    return []
  const prefix = paletteScope.value?.prefix
  const byId = new Map<string, Command>()
  for (const command of commands.value) {
    if (command.hidden || command.id === COMMAND.palette || (prefix && !command.id.startsWith(prefix)) || !isAvailable(command))
      continue
    byId.delete(command.id)
    byId.set(command.id, command)
  }
  return [...byId.values()]
})

const ranked = computed(() => rankItems(candidates.value, query.value, command => `${command.title} ${command.keywords ?? ''} ${command.group ?? ''}`))
const results = computed(() => ranked.value.slice(0, LIMIT))

const sections = computed(() => {
  const groups = new Map<string, { command: Command, index: number }[]>()
  results.value.forEach((command, index) => {
    const name = command.group ?? t('palette.group.general')
    let entries = groups.get(name)
    if (!entries)
      groups.set(name, entries = [])
    entries.push({ command, index })
  })
  return [...groups].map(([name, entries]) => ({ name, entries }))
})

// Rendering follows the grouped order, so selection walks it too.
const order = computed(() => sections.value.flatMap(section => section.entries.map(entry => entry.index)))

function optionId(index: number) {
  return `${id}-option-${index}`
}

function shortcutOf(command: Command) {
  return command.shortcut ?? keysFor(command.id)
}

function move(delta: number) {
  const count = order.value.length
  if (!count)
    return
  const at = order.value.indexOf(selected.value)
  const next = Math.abs(delta) === 1
    ? (at + delta + count) % count
    : Math.min(Math.max(at + delta, 0), count - 1)
  selected.value = order.value[next]!
  void nextTick(() => list.value?.querySelector(`#${CSS.escape(optionId(selected.value))}`)?.scrollIntoView({ block: 'nearest' }))
}

function close() {
  paletteOpen.value = false
}

async function run(command: Command | undefined) {
  if (!command)
    return
  close()
  await nextTick()
  void command.run()
}

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing)
    return
  switch (eventChord(event)) {
    case 'arrowdown':
    case 'tab':
      move(1)
      break
    case 'arrowup':
    case 'shift+tab':
      move(-1)
      break
    case 'pagedown':
      move(PAGE)
      break
    case 'pageup':
      move(-PAGE)
      break
    case 'enter':
      void run(results.value[selected.value])
      break
    case 'escape':
    case 'mod+k':
      close()
      break
    case 'backspace':
      if (query.value || !paletteScope.value)
        return
      paletteScope.value = undefined
      break
    default:
      return
  }
  // Handled here so the page's own shortcut listener never sees the key as well.
  event.preventDefault()
  event.stopPropagation()
}

watch(query, () => {
  selected.value = order.value[0] ?? 0
})
watch(paletteScope, () => {
  selected.value = order.value[0] ?? 0
})

watch(paletteOpen, async (isOpen) => {
  if (isOpen) {
    returnFocus = deepActiveElement(props.document ?? document)
    query.value = ''
    selected.value = order.value[0] ?? 0
    await nextTick()
    input.value?.focus()
  }
  else {
    returnFocus?.focus({ preventScroll: true })
    returnFocus = undefined
  }
}, { flush: 'post' })
</script>

<template>
  <AppModal v-model:open="paletteOpen" :document="document">
    <div class="flex flex-col -m-3">
      <div class="flex items-center gap-2 border-b border-base px-3 py-2">
        <span class="i-lu-search shrink-0 op-mute" aria-hidden="true" />
        <button
          v-if="paletteScope"
          type="button"
          class="shrink-0 border border-base rounded bg-active px-1.5 py-0.5 text-xs color-base"
          :title="t('palette.clearScope')"
          @click="paletteScope = undefined; input?.focus()"
        >
          {{ paletteScope.label }}
          <span class="i-lu-close ml-0.5 align-middle text-[0.7em] op-mute" aria-hidden="true" />
        </button>
        <input
          ref="input"
          v-model="query"
          type="text"
          role="combobox"
          autocomplete="off"
          autocapitalize="none"
          autocorrect="off"
          spellcheck="false"
          enterkeyhint="go"
          aria-autocomplete="list"
          aria-expanded="true"
          :aria-controls="`${id}-list`"
          :aria-activedescendant="results.length ? optionId(selected) : undefined"
          :aria-label="t('palette.title')"
          :placeholder="t('palette.placeholder')"
          class="min-w-0 flex-1 bg-transparent py-1 text-sm color-base outline-none"
          @keydown="onKeydown"
        >
      </div>
      <div :id="`${id}-list`" ref="list" role="listbox" class="max-h-[min(60dvh,28rem)] overflow-auto overscroll-contain py-1">
        <div v-if="!results.length" class="px-3 py-6 text-center text-sm op-fade">
          {{ t('palette.empty') }}
        </div>
        <div v-for="section in sections" :key="section.name" role="group" :aria-label="section.name">
          <div class="px-3 pb-1 pt-2 text-xs color-faint font-medium">
            {{ section.name }}
          </div>
          <div
            v-for="{ command, index } in section.entries"
            :id="optionId(index)"
            :key="command.id"
            role="option"
            :aria-selected="index === selected"
            class="mx-1 flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm coarse:min-h-11"
            :class="index === selected ? 'bg-active color-base' : 'color-base op-fade'"
            @mousemove="selected = index"
            @click="run(command)"
          >
            <span class="h-4 w-4 shrink-0" :class="command.icon ?? 'i-ph:dot-outline'" aria-hidden="true" />
            <span class="min-w-0 flex-1 truncate">{{ command.title }}</span>
            <DisplayKbd v-if="shortcutOf(command)" :keys="shortcutOf(command)" class="shrink-0" />
          </div>
        </div>
        <div v-if="ranked.length > LIMIT" class="px-3 py-2 text-xs op-mute">
          {{ t('palette.more', { n: ranked.length - LIMIT }) }}
        </div>
      </div>
    </div>
    <template #footer>
      <!-- Key hints mean nothing without a keyboard. -->
      <div class="flex flex-1 items-center gap-4 px-1 text-xs op-fade coarse:hidden">
        <span class="flex items-center gap-1"><DisplayKbd keys="up" /><DisplayKbd keys="down" />{{ t('palette.navigate') }}</span>
        <span class="flex items-center gap-1"><DisplayKbd keys="enter" />{{ t('palette.run') }}</span>
        <span class="flex items-center gap-1"><DisplayKbd keys="esc" />{{ t('palette.close') }}</span>
      </div>
    </template>
  </AppModal>
</template>
