<script setup lang="ts">
import { computed } from 'vue'
import { groupNav } from '../../state/group-nav'
import KitFloatButton from '../kit/KitFloatButton.vue'

// The icon and label name the mode a click switches to.
const sidebar = computed(() => groupNav.value === 'sidebar')
</script>

<template>
  <KitFloatButton
    :icon="sidebar ? 'i-lu-layers' : 'i-lu-list'"
    :label="$t(sidebar ? 'group.navTabs' : 'group.navSidebar')"
    @click="groupNav = sidebar ? 'tabs' : 'sidebar'"
  />
</template>
