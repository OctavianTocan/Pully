import { createApp } from 'vue'
import { installAppContext } from './app-context'
import App from './App.vue'
import { i18n } from './i18n'
import { router } from './router'
import { isDark } from './state/dark'
import { syncThemeColor } from './state/theme-color'
import './styles'

syncThemeColor(isDark)

async function start() {
  const app = createApp(App).use(i18n)
  // The flag is a compile-time literal: the site build never bundles the devframe client.
  // Local routes are added before the router installs, so the first navigation sees them.
  if (import.meta.env.PR_LOCAL)
    await import('./local/install').then(({ installLocal }) => installLocal(app, router))
  else
    installAppContext(app)
  app.use(router).mount('#app')
}

void start()
