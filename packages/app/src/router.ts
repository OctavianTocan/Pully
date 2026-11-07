import { createRouter, createWebHistory } from 'vue-router'
import { insideMobileFrame, mobileHistory } from './mobile-frame'
import { repoRoutes, routes } from './source-routes'

export const router = createRouter({
  scrollBehavior: (to, from, saved) => saved ?? (to.path === from.path ? false : { top: 0 }),
  // The `PR_LOCAL` build names its mount path in a `<base>` (see `vite.config.local.ts`).
  history: insideMobileFrame()
    ? mobileHistory(import.meta.env.PR_LOCAL ? new URL(document.baseURI).pathname : '/')
    : createWebHistory(import.meta.env.PR_LOCAL ? new URL(document.baseURI).pathname : undefined),
  routes: [
    { name: 'home', path: '/', component: () => import('./pages-web/index.vue') },
    { path: '/upload', component: () => import('./pages-web/upload.vue') },
    ...repoRoutes(() => import('./pages-web/gh/[owner]/[repo]/index.vue')),
    ...routes(() => import('./pages-web/gh/[owner]/[repo]/diff.vue')),
  ],
})
