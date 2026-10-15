import type { AnalyticsEvent } from '@pulls.review/core/analytics'
import type { App } from 'vue'
import type { RouteLocationNormalized, Router } from 'vue-router'
import { analyticsId, createAnalyticsTracker } from '@pulls.review/core/analytics'
import { watch } from 'vue'
import pkg from '../../package.json' with { type: 'json' }
import { setAnalyticsTracker, track } from '../analytics'
import { autoRefresh } from '../state/auto-refresh'
import { isDark } from '../state/dark'
import { layout } from '../state/layout'
import { settings } from '../state/settings'
import { usageStats } from '../state/usage-stats'

const DEVICE_KEY = 'pulls:analytics-device'
const FLUSH_MS = 30_000

/** Where the server takes events: `POST /api/events` on the origin that served this page. */
function endpoint(): string {
  return new URL('/api/events', location.origin).href
}

function deviceId(): string {
  try {
    const stored = localStorage.getItem(DEVICE_KEY)
    if (stored && /^[\w-]{1,64}$/.test(stored))
      return stored
    const made = analyticsId()
    localStorage.setItem(DEVICE_KEY, made)
    return made
  }
  catch {
    return analyticsId()
  }
}

/**
 * A page's name for `screen.view`, from the route it matched, never its owner, repo, branch
 * or number: `/:owner/:repo/:number` is `review`.
 * @param route - The route the router resolved.
 */
export function screenOf(route: Pick<RouteLocationNormalized, 'matched' | 'name'>): string {
  const path = route.matched.at(-1)?.path ?? ''
  if (route.name === 'github-pr' || route.name === 'github-compare' || route.name === 'github-commit')
    return 'review'
  if (path === '/')
    return 'home'
  if (path === '/:owner/:repo')
    return 'repo'
  const word = /^\/([a-z]+)/.exec(path)?.[1]
  return word ?? 'other'
}

function send(events: AnalyticsEvent[]): Promise<boolean> {
  return fetch(endpoint(), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ events }), keepalive: true })
    .then(res => res.ok, () => false)
}

function beacon(events: AnalyticsEvent[]): boolean {
  return typeof navigator.sendBeacon === 'function' && navigator.sendBeacon(endpoint(), new Blob([JSON.stringify({ events })], { type: 'application/json' }))
}

/** The top of a stack, without the page's origin. */
function stackOf(error: unknown): string | undefined {
  return error instanceof Error ? error.stack?.split('\n').slice(0, 10).join('\n').replaceAll(location.origin, '') : undefined
}

/**
 * Starts usage analytics for the web app our server serves: page views and time on each
 * page, start time, errors, and whatever the app records with `track()`. Events go to
 * `POST /api/events` every 30 seconds and when the tab is hidden.
 */
export function installAnalytics(app: App, router: Router): void {
  const tracker = createAnalyticsTracker({ app: 'web', version: pkg.version, device: deviceId(), transport: { send, beacon } })
  tracker.setEnabled(usageStats.value)
  watch(usageStats, (on) => {
    tracker.setEnabled(on)
    if (on)
      track('settings.change', { key: 'usageStats', value: true })
  })
  setAnalyticsTracker(tracker)
  // Settings that change how the app looks or behaves, with their new value: enums and on or off only.
  watch(layout, value => track('settings.change', { key: 'layout', value: String(value) }))
  watch(autoRefresh, value => track('settings.change', { key: 'autoRefresh', value }))
  watch(isDark, value => track('settings.change', { key: 'theme', value: value ? 'dark' : 'light' }))
  watch(() => settings.value.locale, value => track('settings.change', { key: 'locale', value }))

  let screen: string | undefined
  let shownAt = performance.now()
  const leaveScreen = () => {
    if (screen)
      track('screen.time', { screen, seconds: Math.round((performance.now() - shownAt) / 1000) })
  }
  let started = false
  router.afterEach((to, from, failure) => {
    if (failure)
      return
    const next = screenOf(to)
    if (!started) {
      started = true
      track('app.open', { start: 'cold', ms: Math.round(performance.now()), screen: next })
    }
    if (next === screen && to.path === from.path)
      return
    leaveScreen()
    track('screen.view', { screen: next, from: screen })
    screen = next
    shownAt = performance.now()
  })

  let visibleSince = 0
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      leaveScreen()
      track('app.background', { seconds: Math.round((performance.now() - visibleSince) / 1000) })
      tracker.background()
      tracker.flushOnExit()
      return
    }
    tracker.foreground()
    visibleSince = performance.now()
    shownAt = visibleSince
  })
  // A closed tab may skip `visibilitychange`; `pagehide` still fires.
  window.addEventListener('pagehide', () => tracker.flushOnExit())
  setInterval(() => void tracker.flush(), FLUSH_MS)

  const previous = app.config.errorHandler
  app.config.errorHandler = (error, instance, info) => {
    track('error.client', { kind: error instanceof Error ? error.name : 'error', message: error instanceof Error ? error.message : String(error), stack: stackOf(error), screen, where: info })
    if (previous)
      previous(error, instance, info)
    else
      console.error(error)
  }
  window.addEventListener('error', (event) => {
    track('error.client', { kind: event.error instanceof Error ? event.error.name : 'error', message: event.message, stack: stackOf(event.error), screen })
  })
  window.addEventListener('unhandledrejection', (event) => {
    const reason: unknown = event.reason
    track('error.client', { kind: reason instanceof Error ? reason.name : 'rejection', message: reason instanceof Error ? reason.message : String(reason), stack: stackOf(reason), screen })
  })
}
