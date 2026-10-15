import { defineConfig } from 'tsdown'

export default defineConfig({
  entry: {
    'index': 'src/index.ts',
    'devframe': 'src/devframe.ts',
    'pr-server': 'src/ai/pr-server.ts',
  },
  platform: 'node',
  dts: false,
  // The curated review skills, so an installed CLI offers them too (`src/lenses.ts`).
  copy: [{ from: '../../docker/agents/skills', to: 'dist' }],
})
