import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    projects: ['packages/app', 'packages/core', 'packages/actions', 'packages/worker', 'android/scripts/design'],
  },
})
