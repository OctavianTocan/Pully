<script setup lang="ts">
import { layout } from '../../state/layout'
const kept = { mark: '', code: 'let n = 1' }
const old = { mark: '−', code: 'n = n + 1' }
const added = { mark: '+', code: 'n += 1' }
const end = { mark: '', code: 'return n' }
</script>

<template>
  <div role="radiogroup" :aria-label="$t('settings.layout')" class="grid grid-cols-2 gap-3">
    <button v-for="choice in (['unified', 'split'] as const)" :key="choice" type="button" role="radio" :aria-checked="layout === choice" class="press-tint flex flex-col items-center gap-2 rounded-xl text-left" @click="layout = choice">
      <span class="min-h-21 w-full flex items-center rounded-xl border bg-raised px-2 py-2.5" :class="layout === choice ? 'border-2 border-accent-teal' : 'border-base'" aria-hidden="true">
        <span class="w-full flex gap-1">
          <span v-for="(lines, index) in (choice === 'split' ? [[kept, old, end], [kept, added, end]] : [[kept, old, added, end]])" :key="index" class="min-w-0 flex flex-1 flex-col gap-0.5">
            <span v-for="(line, row) in lines" :key="row" class="flex overflow-hidden whitespace-nowrap rounded-xs px-0.75 font-mono" style="font-size:7.75px;line-height:12.4px" :class="line.mark === '+' ? 'bg-diff-add' : line.mark === '−' ? 'bg-diff-del' : ''"><span class="w-1.75 shrink-0" :class="line.mark === '+' ? 'color-diff-add-ink' : line.mark === '−' ? 'color-diff-del-ink' : ''">{{ line.mark }}</span><span :class="line.mark ? 'color-base' : 'color-muted'">{{ line.code }}</span></span>
          </span>
        </span>
      </span>
      <span class="text-sm" :class="layout === choice ? 'color-base font-medium' : 'color-muted'">{{ $t(`settings.${choice}`) }}</span>
    </button>
  </div>
</template>
