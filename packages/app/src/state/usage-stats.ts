import { useLocalStorage } from '@vueuse/core'

/**
 * Whether this browser sends usage analytics to our server. On by default; turning it off
 * drops what is queued and sends nothing more (root `CONTRACTS`, `analytics-can-be-turned-off`).
 */
export const usageStats = useLocalStorage('diffs:usage-stats', true)
