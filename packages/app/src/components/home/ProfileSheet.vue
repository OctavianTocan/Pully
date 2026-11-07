<script setup lang="ts">
import type { MyPull } from '@pulls.review/core/local-rpc'
import { createGithubClient } from '@pulls.review/core/github'
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { useAppContext } from '../../app-context'
import AppModal from '../AppModal.vue'
import GithubAvatar from '../GithubAvatar.vue'
import KitSheetRow from '../kit/KitSheetRow.vue'
import KitSwitch from '../kit/KitSwitch.vue'
import KitSearchPill from '../kit/KitSearchPill.vue'

const props = defineProps<{ viewer?: string, pulls: MyPull[], closed: boolean }>()
const open = defineModel<boolean>('open', { required: true })
const hidden = defineModel<string[]>('hidden', { required: true })
const emit = defineEmits<{ closed: [value: boolean] }>()
const { credentials } = useAppContext()
const client = createGithubClient(credentials)
const page = ref<'main' | 'repos'>('main')
const search = ref('')
interface Day { date: string, contributionCount: number }
interface Collection { totalCommitContributions: number, totalPullRequestContributions: number, totalPullRequestReviewContributions: number, contributionCalendar: { totalContributions: number, weeks: { contributionDays: Day[] }[] } }
const stats = shallowRef<Collection>()
const failed = ref(false)
const picked = ref<{ count: number, label: string }>()
let generation = 0
onScopeDispose(() => { generation++ })
watch([open, () => props.viewer], async ([shown, viewer]) => {
  if (!shown || !viewer) return
  const owner = ++generation
  stats.value = undefined
  failed.value = false
  try {
    const result = await client.graphql<{ user: { contributionsCollection: Collection } | null }>('query PullyProfile($login:String!) { user(login:$login) { contributionsCollection { totalCommitContributions totalPullRequestContributions totalPullRequestReviewContributions contributionCalendar { totalContributions weeks { contributionDays { date contributionCount } } } } } }', { login: viewer })
    if (owner === generation) { stats.value = result.user?.contributionsCollection; failed.value = !stats.value }
  }
  catch { if (owner === generation) failed.value = true }
})
watch(open, shown => { if (!shown) { page.value = 'main'; picked.value = undefined } })
const choices = computed(() => {
  const counts = new Map<string, number>()
  for (const pull of props.pulls) { const key = `${pull.owner}/${pull.repo}`; counts.set(key, (counts.get(key) ?? 0) + 1) }
  for (const key of hidden.value) if (!counts.has(key)) counts.set(key, 0)
  return [...counts].sort(([a], [b]) => a.localeCompare(b)).map(([key, count]) => ({ key, count, owner: key.split('/')[0]!, repo: key.split('/')[1]!, shown: !hidden.value.includes(key) }))
})
const visible = computed(() => choices.value.filter(choice => choice.key.toLowerCase().includes(search.value.trim().toLowerCase())))
const shownCount = computed(() => choices.value.filter(choice => choice.shown).length)
const days = computed(() => stats.value?.contributionCalendar.weeks.flatMap(week => week.contributionDays) ?? [])
const calendar = computed(() => days.value.slice(Math.max(0, Math.floor((days.value.length - 1) / 7) * 7 - 25 * 7)))
const busiest = computed(() => Math.max(1, ...calendar.value.map(day => day.contributionCount)))
const months = computed(() => {
  const totals = new Map<string, number>()
  for (const day of days.value) { const key = day.date.slice(0, 7); totals.set(key, (totals.get(key) ?? 0) + day.contributionCount) }
  return [...totals].slice(-12).map(([date, count]) => ({ date, count }))
})
const streak = computed(() => {
  let current = 0; let longest = 0
  for (const day of days.value) { current = day.contributionCount ? current + 1 : 0; longest = Math.max(longest, current) }
  if (!days.value.at(-1)?.contributionCount) {
    current = 0
    for (let i = days.value.length - 2; i >= 0 && days.value[i]!.contributionCount; i--) current++
  }
  return { current, longest }
})
function setShown(key: string, shown: boolean) { hidden.value = shown ? hidden.value.filter(value => value !== key) : [...new Set([...hidden.value, key])] }
function setAll(shown: boolean) { hidden.value = shown ? [] : choices.value.map(choice => choice.key) }
function pickDay(day: Day) { picked.value = { count: day.contributionCount, label: new Date(`${day.date}T12:00:00Z`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }) } }
function dragCalendar(event: PointerEvent) { if (event.buttons) { const target = document.elementFromPoint(event.clientX, event.clientY)?.closest<HTMLElement>('[data-profile-day]'); if (target) { const day = calendar.value[Number(target.dataset.profileDay)]; if (day) pickDay(day) } } }
</script>

<template>
  <AppModal v-model:open="open" :title="$t(page === 'main' ? 'mobile.profile' : 'mobile.repositories')" :back="page === 'repos'" @back="page = 'main'">
    <div v-if="page === 'main'" class="flex flex-col gap-6">
      <a :href="viewer ? `https://github.com/${viewer}` : undefined" target="_blank" rel="noopener" class="press-tint flex items-center gap-3.5 rounded-lg p-1">
        <GithubAvatar v-if="viewer" :login="viewer" :size="64" /><span v-else class="h-16 w-16 rounded-full bg-sunken" /><span class="text-xxl font-semibold">{{ viewer || 'Pully' }}</span>
      </a>
      <section v-if="viewer">
        <h3 class="text-sm color-muted">{{ $t('mobile.githubYear') }}</h3>
        <p v-if="failed" class="mt-2 text-sm color-muted">{{ $t('mobile.statsUnavailable') }}</p>
        <template v-else>
          <div class="mt-1.5 rounded-card bg-raised p-4 shadow-note">
            <div class="flex items-baseline gap-2"><span class="text-xxl font-semibold tabular-nums">{{ picked ? picked.count.toLocaleString() : stats?.contributionCalendar.totalContributions.toLocaleString() || ' ' }}</span><span class="text-sm color-muted">{{ picked?.label || $t('mobile.contributions') }}</span></div>
            <div v-if="stats" class="mt-3 grid grid-flow-col grid-rows-7 gap-0.75" style="grid-template-columns:repeat(26,minmax(0,1fr))" @pointermove="dragCalendar">
              <button v-for="(day, index) in calendar" :key="day.date" :data-profile-day="index" class="aspect-square rounded-xs" :aria-label="`${day.date}: ${day.contributionCount}`" :style="{ background: `color-mix(in srgb,rgb(var(--c-primary)) ${day.contributionCount ? Math.max(25, Math.ceil(day.contributionCount / busiest * 4) * 25) : 0}%,rgb(var(--c-paper-2)))` }" data-sound="off" @pointerdown="pickDay(day)" @click="pickDay(day)" />
            </div><div v-else class="mt-3 h-20" />
          </div>
          <div class="mt-3 rounded-card bg-raised p-4 shadow-note">
            <div class="grid grid-cols-2 gap-x-4 gap-y-3.5"><div v-for="item in [{ value: stats?.totalCommitContributions, label: $t('mobile.commits') }, { value: stats?.totalPullRequestContributions, label: $t('mobile.pullRequests') }, { value: stats?.totalPullRequestReviewContributions, label: $t('mobile.reviews') }, { value: stats ? streak.current : undefined, label: $t('mobile.dayStreak') }]" :key="item.label"><p class="text-xl font-semibold tabular-nums">{{ item.value?.toLocaleString() ?? ' ' }}</p><p class="text-xs color-muted">{{ item.label }}</p></div></div>
            <div class="mt-4.5 h-22 flex items-end gap-1"><button v-for="month in months" :key="month.date" class="min-w-0 flex flex-1 flex-col items-center justify-end gap-1" :aria-label="`${month.date}: ${month.count}`" data-sound="off" @click="picked = { count: month.count, label: new Date(`${month.date}-01T12:00:00Z`).toLocaleDateString(undefined, { month: 'long' }) }"><span class="w-full rounded-t-xs bg-primary" :style="{ height: `${Math.max(2, month.count / Math.max(1, ...months.map(item => item.count)) * 60)}px` }" /><span class="text-micro color-muted">{{ new Date(`${month.date}-01`).toLocaleDateString(undefined, { month: 'narrow' }) }}</span></button></div>
          </div>
        </template>
      </section>
      <section><h3 class="text-sm color-muted">{{ $t('mobile.inbox') }}</h3><div class="mt-1.5 overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.repositories')" :value="$t('mobile.reposShown', { shown: shownCount, total: choices.length })" first chevron @activate="page = 'repos'" /><KitSheetRow :label="$t('mobile.closed')" @activate="emit('closed', !closed)"><KitSwitch :model-value="closed" :label="$t('mobile.closed')" @update:model-value="emit('closed', $event)" /></KitSheetRow></div></section>
    </div>
    <div v-else class="flex flex-col gap-3">
      <KitSearchPill v-model="search" :placeholder="$t('mobile.findRepository')" class="rounded-card! shadow-note!" />
      <div v-if="choices.length" class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow :label="$t('mobile.allRepositories')" first @activate="setAll(shownCount !== choices.length)"><KitSwitch :model-value="shownCount === choices.length" :label="$t('mobile.allRepositories')" @update:model-value="setAll" /></KitSheetRow></div>
      <div class="overflow-hidden rounded-card bg-raised shadow-note"><KitSheetRow v-for="(choice, index) in visible" :key="choice.key" :label="choice.repo" :value="String(choice.count)" :first="index === 0" @activate="setShown(choice.key, !choice.shown)"><KitSwitch :model-value="choice.shown" :label="choice.key" @update:model-value="setShown(choice.key, $event)" /></KitSheetRow></div>
    </div>
  </AppModal>
</template>
