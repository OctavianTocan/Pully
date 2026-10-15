<script setup lang="ts">
import type { ResolvedGroupWithChildren } from './group-utils'
import DisplayDonut from '@antfu/design/components/Display/DisplayDonut.vue'
import { computed } from 'vue'
import DiffStats from './DiffStats.vue'
import { countGroupFiles, countGroupStats, groupProgress } from './group-utils'

const props = defineProps<{
  group: ResolvedGroupWithChildren
  active: boolean
  reviewed: Set<string>
  expanded?: boolean
}>()

defineEmits<{
  select: [key: string]
  toggle: []
}>()

const stats = computed(() => countGroupStats(props.group))
const fileCount = computed(() => countGroupFiles(props.group))
const subgroupCount = computed(() => props.group.children.length)
const progress = computed(() => groupProgress(props.group, props.reviewed))
</script>

<!-- A group as the phone's soft chip: its name, then its size in the muted ink; filled while it is on screen. -->
<template>
  <div
    class="h-11 flex items-center border rounded-full pl-4 pr-1.5 transition-colors"
    :class="active ? 'border-transparent bg-ambient' : 'border-base'"
  >
    <button
      type="button"
      class="flex items-center gap-2 whitespace-nowrap text-left text-sm color-base"
      :aria-current="active || undefined"
      @click="$emit('select', group.key)"
    >
      <span>{{ group.label }}</span>
      <DiffStats :additions="stats.added" :deletions="stats.deleted" class="text-xs" />
      <span class="text-xs color-muted">{{ $t('common.files', { n: fileCount }, fileCount) }}</span>
      <span v-if="subgroupCount" class="text-xs color-muted">· {{ $t('common.subgroups', { n: subgroupCount }, subgroupCount) }}</span>
      <DisplayDonut v-if="progress !== 0" :value="progress" :size="16" :thickness="2" class="color-accent-teal" />
    </button>
    <button
      v-if="subgroupCount"
      type="button"
      class="press-tint h-8 w-8 flex items-center justify-center rounded-full color-muted hover:bg-hover"
      :aria-expanded="expanded"
      :aria-label="$t(expanded ? 'group.hideSubgroups' : 'group.showSubgroups')"
      @click.stop="$emit('toggle')"
    >
      <span class="i-lu-chevd text-4 transition-transform duration-slide ease-slide" :class="expanded ? 'rotate-180' : ''" aria-hidden="true" />
    </button>
    <span v-else class="w-2.5" aria-hidden="true" />
  </div>
</template>
