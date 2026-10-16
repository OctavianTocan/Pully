import type { App } from 'vue'
import type { Router } from 'vue-router'
import { createCacheRepositories, SERVER_CACHE_BUDGET } from '@pulls.review/core/cache'
import { setCliRunner } from '@pulls.review/core/llm'
import { createStorage } from 'unstorage'
import { createApp } from 'vue'
import { installAppContext } from '../app-context'
import { i18n } from '../i18n'
import { createJobRunner } from './ai-jobs'
import { setAiRpc } from './ai-rpc'
import { installAnalytics } from './analytics'
import { connectLocal } from './connection'
import { createRpcLenses, setLensSource } from './lenses'
import { localRpcKey } from './local-rpc-key'
import LocalAuthGate from './LocalAuthGate.vue'
import { localRoutes } from './pages'
import { reloadAfterDisconnect } from './reconnect'
import { createRpcCredentials, createRpcDiffCache, createRpcDriver, createRpcFileContentCache } from './rpc-backends'

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
 * Wires a `PR_LOCAL` build to the `pulls.review` server: the cache lives in the repo
 * (over RPC), GitHub operations stay on the server, `/` picks refs and
 * `/compare`, `/branch`, `/worktree` and `/commit` review them.
 */
export async function installLocal(app: App, router: Router): Promise<void> {
  const { client, rpc } = await connectLocal()
  await ensureTrusted(client)
  reloadAfterDisconnect(client)

  const cache = createCacheRepositories(createStorage({ driver: createRpcDriver(rpc) }), SERVER_CACHE_BUDGET)
  cache.diffs = createRpcDiffCache(rpc, cache.diffs)
  cache.fileContents = createRpcFileContentCache(rpc)
  installAppContext(app, {
    cache,
    credentials: createRpcCredentials(rpc),
  })
  app.provide(localRpcKey, rpc)
  setCliRunner(createJobRunner(rpc))
  setAiRpc(rpc)
  setLensSource(createRpcLenses(rpc))

  for (const route of localRoutes(() => import('../pages-local/diff.vue'), () => import('../pages-local/posted.vue')))
    router.addRoute(route)
  // Your open pull requests, grouped by repository, replace the site's landing page;
  // the checkout's own ref picker moves to `/checkout`.
  router.removeRoute('home')
  router.addRoute({ path: '/', component: () => import('../pages-local/index.vue') })
  router.addRoute({ path: '/checkout', component: () => import('../pages-local/checkout.vue') })
  router.addRoute({ path: '/stats', component: () => import('../pages-local/stats.vue') })
  installAnalytics(app, router)
}
