import { useLocalStorage } from '@vueuse/core'

interface TourProgress {
  reached: number
  current: number
  title: string
}
export const tourProgress = useLocalStorage<Record<string, TourProgress>>('pully:tour-progress', {})
export function tourProgressKey(key: string, generatedAt: string) {
  return `${key}@${generatedAt}`
}
export function recordTourProgress(key: string, current: number, title: string) {
  const previous = tourProgress.value[key]
  tourProgress.value = { ...tourProgress.value, [key]: { reached: Math.max(previous?.reached ?? 0, current), current, title } }
}
