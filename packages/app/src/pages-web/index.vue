<script setup lang="ts">
import { ref, watch } from 'vue'
import HomeScreen from '../components/home/HomeScreen.vue'
import { settings } from '../state/settings'

const identity = ref(0)
watch(() => settings.value.githubToken, () => { identity.value++ })
</script>

<template>
  <HomeScreen :key="identity" />
</template>
