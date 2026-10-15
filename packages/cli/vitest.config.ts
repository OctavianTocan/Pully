import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  resolve: {
    alias: [{ find: /^@pulls\.review\/core\/(.+)$/, replacement: `${fileURLToPath(new URL('../core/src/', import.meta.url))}$1.ts` }],
  },
  test: {
    name: 'cli',
    environment: 'node',
    // Tests never write to the real analytics folder; analytics.test.ts passes its own env.
    env: { PULLS_REVIEW_ANALYTICS: 'off' },
  },
})
