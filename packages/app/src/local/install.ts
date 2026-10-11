import type { App } from 'vue'
import type { Router } from 'vue-router'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { setCliRunner } from '@pulls.review/core/llm'
import { createStorage } from 'unstorage'
import { createApp } from 'vue'
import { installAppContext, settingsCredentials } from '../app-context'
import { i18n } from '../i18n'
import { createJobRunner } from './ai-jobs'
import { setAiRpc } from './ai-rpc'
import { connectLocal } from './connection'
import { createRpcLenses, setLensSource } from './lenses'
import { localRpcKey } from './local-rpc-key'
import LocalAuthGate from './LocalAuthGate.vue'
import { localRoutes } from './pages'
import { createRpcCredentials, createRpcDriver } from './rpc-backends'

/** Until the server trusts this tab, every RPC call fails: ask for the terminal's code first. */
async function ensureTrusted(client: Awaited<ReturnType<typeof connectLocal>>['client']): Promise<void> {
  // `--open` lands the tab with the code in the URL, which `connectDevframe` exchanges
  // itself; a stored token from an earlier visit also settles this quickly. It rejects on timeout.
  if (await client.ensureTrusted(2000).catch(() => false))
    return
  const host = document.createElement('div')
  document.body.append(host)
  await new Promise<void>((resolve) => {
    const gate = createApp(LocalAuthGate, { client, onTrusted: resolve }).use(i18n)
    gate.mount(host)
  })
  host.remove()
}

/**
 * devframe's socket never reconnects, and a phone drops it whenever the tab sleeps. Every AI
 * task runs and saves on the server, so reloading once the tab is visible again loses nothing
 * and picks the running ones back up.
 */
function reloadAfterDisconnect(client: Awaited<ReturnType<typeof connectLocal>>['client']): void {
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

/**
 * Wires a `PR_LOCAL` build to the `pulls.review` server: the cache lives in the repo
 * (over RPC), the GitHub token comes from the server's environment, `/` picks refs and
 * `/compare`, `/branch`, `/worktree` and `/commit` review them.
 */
export async function installLocal(app: App, router: Router): Promise<void> {
  const { client, rpc } = await connectLocal()
  await ensureTrusted(client)
  reloadAfterDisconnect(client)

  installAppContext(app, {
    cache: createCacheRepositories(createStorage({ driver: createRpcDriver(rpc) })),
    credentials: createRpcCredentials(rpc, settingsCredentials),
  })
  app.provide(localRpcKey, rpc)
  setCliRunner(createJobRunner(rpc))
  setAiRpc(rpc)
  setLensSource(createRpcLenses(rpc))

  for (const route of localRoutes(() => import('../pages-local/diff.vue')))
    router.addRoute(route)
  // Your open pull requests, grouped by repository, replace the site's landing page;
  // the checkout's own ref picker moves to `/checkout`.
  router.removeRoute('home')
  router.addRoute({ path: '/', component: () => import('../pages-local/index.vue') })
  router.addRoute({ path: '/checkout', component: () => import('../pages-local/checkout.vue') })
}
