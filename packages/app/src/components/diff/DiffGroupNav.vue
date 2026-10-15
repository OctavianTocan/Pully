<script setup lang="ts">
import type { ResolvedGroupWithChildren } from './group-utils'
import { computed, ref } from 'vue'
import DiffGroupNavItem from './DiffGroupNavItem.vue'

const props = defineProps<{
  groups: ResolvedGroupWithChildren[]
  groupsVisable: string[]
  reviewed: Set<string>
}>()

const emit = defineEmits<{
  select: [key: string]
}>()

const expandedKey = ref<string>()
function toggleExpanded(key: string, expand = expandedKey.value !== key) {
  expandedKey.value = expand ? key : undefined
}

// Subgroups are rendered as their own nav items (no further nesting), so give each a
// leaf `children` array to satisfy the item component's stats helpers.
const subgroups = computed<ResolvedGroupWithChildren[]>(() => {
  const group = props.groups.find(g => g.key === expandedKey.value)
  return group ? group.children.map(child => ({ ...child, children: [] })) : []
})

function onSelectSubgroup(key: string) {
  emit('select', key)
  toggleExpanded(key, true)
}
</script>

<template>
  <div class="min-w-0 flex flex-1 flex-col gap-1.5">
    <!-- One row that scrolls sideways on a phone, as the phone's chips do; it wraps where there is room. -->
    <div class="flex items-center gap-2 max-md:([scrollbar-width:none] overflow-x-auto px-4 -mx-4) md:flex-wrap">
      <DiffGroupNavItem
        v-for="group in groups"
        :key="group.key"
        :group="group"
        :active="groupsVisable.includes(group.key)"
        :reviewed="reviewed"
        :expanded="expandedKey === group.key"
        @select="onSelectSubgroup($event)"
        @toggle="toggleExpanded(group.key)"
      />
    </div>
    <template v-if="subgroups.length">
      <div class="flex items-center gap-1">
        <div class="text-sm">
          <span class="op-fade">{{ $t('group.subgroupsOf') }} </span><span>{{ props.groups.find(g => g.key === expandedKey)?.label }}</span>
        </div>
        <div class="flex-auto border-t border-base" />
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <DiffGroupNavItem
          v-for="sub in subgroups"
          :key="sub.key"
          :group="sub"
          :active="groupsVisable.includes(sub.key)"
          :reviewed="reviewed"
          @select="$emit('select', $event)"
        />
      </div>
    </template>
  </div>
</template>
