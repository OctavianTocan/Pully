import type { LocalClient } from './connection'

/**
 * Reloading a visible tab picks up server tasks and reenters the terminal-code gate
 * when a server restart invalidated its old trust cookie.
 */
export function reloadAfterDisconnect(client: LocalClient): void {
  let lost = false
  const reloadWhenVisible = () => {
    if (lost && document.visibilityState === 'visible')
      location.reload()
  }
  client.events.on('connection:status', (status) => {
    if (status !== 'disconnected' && status !== 'error')
      return
    lost = true
    // A short pause rides out a server restart instead of reloading into a refused connection.
    setTimeout(reloadWhenVisible, 1500)
  })
  document.addEventListener('visibilitychange', reloadWhenVisible)
  window.addEventListener('online', reloadWhenVisible)
}
