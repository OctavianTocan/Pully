import type { RouterHistory } from 'vue-router'
import { createMemoryHistory } from 'vue-router'

/** A real phone viewport keeps CSS, portals, fixed controls and JS media queries in agreement. */
export function insideMobileFrame(): boolean {
  return window.frameElement?.hasAttribute('data-pully-screen') === true
}

export function mountMobileFrame(): void {
  document.documentElement.classList.add('mobile-host')
  const frame = document.createElement('iframe')
  frame.setAttribute('data-pully-screen', '')
  frame.title = 'Pully'
  frame.allow = 'autoplay; clipboard-read; clipboard-write; fullscreen'
  frame.src = window.location.href
  document.getElementById('app')!.append(frame)
  frame.addEventListener('load', () => {
    const child = frame.contentDocument
    if (!child)
      return
    const syncTitle = () => { document.title = child.title }
    syncTitle()
    new MutationObserver(syncTitle).observe(child.head, { subtree: true, childList: true, characterData: true })
  })
}

/** The address bar and browser Back belong to the host; the phone owns rendering and scrolling. */
export function mobileHistory(base = '/'): RouterHistory {
  const host = window.parent
  const memory = createMemoryHistory(base)
  const path = () => {
    const prefix = base.replace(/\/$/, '')
    const pathname = prefix && host.location.pathname.startsWith(`${prefix}/`)
      ? host.location.pathname.slice(prefix.length)
      : host.location.pathname
    return pathname + host.location.search + host.location.hash
  }
  memory.replace(path())
  let position = Number(host.history.state?.position ?? 0)
  host.history.replaceState({ ...host.history.state, position }, '')
  const cleanups = new Set<() => void>()
  let paused = false
  const syncChild = (to: string) => window.history.replaceState(null, '', memory.createHref(to))
  return {
    base: memory.base,
    get location() { return memory.location },
    get state() { return host.history.state ?? {} },
    createHref: memory.createHref,
    push(to, data) {
      const from = memory.location
      host.history.replaceState({ ...host.history.state, forward: to, scroll: { left: window.scrollX, top: window.scrollY } }, '')
      memory.push(to, data)
      host.history.pushState({ ...data, back: from, current: to, forward: null, position: ++position }, '', memory.createHref(to))
      syncChild(to)
    },
    replace(to, data) {
      memory.replace(to, data)
      host.history.replaceState({ ...host.history.state, ...data, current: to, position }, '', memory.createHref(to))
      syncChild(to)
    },
    go(delta, triggerListeners = true) { paused = !triggerListeners; host.history.go(delta) },
    listen(callback) {
      const pop = () => {
        const from = memory.location
        const to = path()
        const next = Number(host.history.state?.position ?? 0)
        const delta = next - position
        position = next
        memory.replace(to)
        syncChild(to)
        if (paused) { paused = false; return }
        callback(to, from, { delta, type: 'pop' as Parameters<Parameters<RouterHistory['listen']>[0]>[2]['type'], direction: (delta > 0 ? 'forward' : delta < 0 ? 'back' : '') as Parameters<Parameters<RouterHistory['listen']>[0]>[2]['direction'] })
      }
      host.addEventListener('popstate', pop)
      const cleanup = () => { host.removeEventListener('popstate', pop); cleanups.delete(cleanup) }
      cleanups.add(cleanup)
      return cleanup
    },
    destroy() { cleanups.forEach(cleanup => cleanup()); memory.destroy() },
  }
}
