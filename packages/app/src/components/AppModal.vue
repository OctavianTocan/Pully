<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { claimSheet } from '../state/sheets'

// A minimal in-house replacement for `@antfu/design`'s `OverlayModal`: that one's
// `reka-ui` `DialogPortal` always teleports to `document.body`, which - inside the
// GitHub-embed custom element - escapes the shadow root entirely and renders
// unstyled directly on the host page. `document` (the embed's own shadow root, see
// `EmbedApp.ce.vue`) lets this Teleport stay inside it instead; the main site (no
// `document` prop) keeps teleporting to the real `document.body` as before.
const props = defineProps<{
  open: boolean
  title?: string
  description?: string
  document?: Document | ShadowRoot
  /** Reserve a half-screen sheet for reading content rather than a compact form. */
  spacious?: boolean
  back?: boolean
  tall?: boolean
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
  'back': []
  'scroll': [event: Event]
}>()

// A `ShadowRoot` isn't a valid CSS selector, so Teleport's `to` (typed as
// `string | HTMLElement` but happy with any target used directly) needs the
// object itself, not a stringified target - the real `document` also isn't a
// selector, so that case falls back to `'body'`.
const teleportTarget = computed(() => props.document instanceof ShadowRoot ? props.document : 'body')
const panel = ref<HTMLElement>()
const scroller = ref<HTMLElement>()
defineExpose({ scroller })
let previousFocus: HTMLElement | undefined
let ownership: ReturnType<typeof claimSheet> | undefined
const layer = ref(0)
const pagePositions = new Map<string, number>()
watch(() => props.title, async (next, previous) => {
  if (!props.open || !scroller.value)
    return
  pagePositions.set(previous ?? '', scroller.value.scrollTop)
  await nextTick()
  if (scroller.value)
    scroller.value.scrollTop = pagePositions.get(next ?? '') ?? 0
})
let dragStart = 0
const drag = ref(0)
const expanded = ref(false)
const tallHeight = 'calc(100dvh - max(16px, env(safe-area-inset-top)) - 44px)'
const panelStyle = computed(() => ({
  maxHeight: expanded.value ? tallHeight : '50dvh',
  height: expanded.value ? tallHeight : props.spacious ? '50dvh' : undefined,
  transform: drag.value ? `translateY(${drag.value}px)` : undefined,
}))

function topmost() {
  return ownership?.topmost() ?? false
}

function close() {
  emit('update:open', false)
}

function onKeydown(event: KeyboardEvent) {
  if (!topmost())
    return
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
  if (event.key === 'Tab' && panel.value) {
    const fields = [...panel.value.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]')].filter(el => el.getClientRects().length)
    const active = props.document instanceof ShadowRoot ? props.document.activeElement : document.activeElement
    const first = fields[0]
    const last = fields.at(-1)
    if (!first) {
      event.preventDefault()
      panel.value.focus()
    }
    else if (event.shiftKey && (active === first || active === panel.value)) {
      event.preventDefault()
      last?.focus()
    }
    else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }
}

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    expanded.value = props.tall ?? false
    const active = props.document instanceof ShadowRoot ? props.document.activeElement : document.activeElement
    previousFocus = active instanceof HTMLElement ? active : undefined
    document.addEventListener('keydown', onKeydown)
    ownership = claimSheet(!(props.document instanceof ShadowRoot))
    layer.value = ownership.layer
    await nextTick()
    if (props.open)
      panel.value?.focus({ preventScroll: true })
  }
  else {
    document.removeEventListener('keydown', onKeydown)
    ownership?.release()
    ownership = undefined
    previousFocus?.focus({ preventScroll: true })
    drag.value = 0
  }
}, { immediate: true })

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  ownership?.release()
})

function dragDown(event: PointerEvent) {
  dragStart = event.clientY;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function dragMove(event: PointerEvent) {
  if ((event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId))
    drag.value = event.clientY - dragStart
}
function dragUp() {
  if (drag.value < -48) {
    expanded.value = true
  }
  else if (drag.value > 72) {
    if (expanded.value)
      expanded.value = false
    else close()
  }
  drag.value = 0
}
function focusField(event: FocusEvent) {
  if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)
    expanded.value = true
}
</script>

<template>
  <Teleport :to="teleportTarget">
    <Transition name="mobile-sheet">
      <!-- In the embed it must clear the drawer, which sits above GitHub's own page.
         The dialog stays a bottom sheet inside the mobile viewport, clear of safe insets. -->
      <div
        v-if="open"
        class="fixed inset-0 flex items-end justify-center pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]"
        :class="teleportTarget === 'body' ? 'z-modal' : 'z-[2147483002]'"
        :style="{ zIndex: (teleportTarget === 'body' ? 100 : 2147483002) + layer * 10 }"
      >
        <div class="fixed inset-0 z-modal-backdrop bg-scrim" data-sound="off" @click="close" />
        <div
          ref="panel"
          role="dialog"
          tabindex="-1"
          aria-modal="true"
          :aria-label="title"
          class="mobile-sheet-panel relative z-modal-content w-full flex flex-col overflow-hidden rounded-t-sheet bg-paper color-base shadow-modal outline-none"
          :style="panelStyle"
          :class="spacious ? 'max-w-2xl' : 'max-w-xl'"
          @focusin="focusField"
        >
          <div class="flex shrink-0 touch-none justify-center pb-3.5 pt-2.5" @pointerdown="dragDown" @pointermove="dragMove" @pointerup="dragUp" @pointercancel="drag = 0">
            <span class="h-1 w-9 rounded-full bg-grabber" />
          </div>
          <header
            v-if="title || description || $slots.header" class="flex shrink-0 items-center gap-3 px-4"
          >
            <button type="button" class="press-tint h-11 w-11 flex shrink-0 items-center justify-center rounded-full bg-raised shadow-button" :aria-label="$t(back ? 'mobile.back' : 'common.close')" @click="back ? emit('back') : close()">
              <span :class="back ? 'i-lu-back' : 'i-lu-close'" class="text-5" aria-hidden="true" />
            </button>
            <div class="min-w-0 flex-1 text-center">
              <h2 v-if="title" class="truncate text-lg color-base font-semibold">
                {{ title }}
              </h2>
              <p v-if="description" class="text-sm op-fade">
                {{ description }}
              </p>
              <slot name="header" />
            </div>
            <slot name="trailing">
              <span class="w-11 shrink-0" aria-hidden="true" />
            </slot>
          </header>
          <div
            ref="scroller"
            class="min-h-0 flex-auto overflow-auto overscroll-contain px-4 pt-5"
            :class="!$slots.footer ? 'pb-[calc(1.5rem+env(safe-area-inset-bottom))]' : 'pb-4'"
            @scroll="emit('scroll', $event)"
          >
            <slot />
          </div>
          <footer v-if="$slots.footer" class="flex shrink-0 flex-wrap justify-end gap-2 px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-3">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
