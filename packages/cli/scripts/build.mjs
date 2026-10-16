import { cp, mkdir, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const dist = fileURLToPath(new URL('../dist', import.meta.url))
const skills = fileURLToPath(new URL('../../../docker/agents/skills', import.meta.url))

// Remove old TypeScript server bundles before Vite writes the current SPA.
await rm(dist, { recursive: true, force: true })
await mkdir(dist, { recursive: true })
await cp(skills, `${dist}/skills`, { recursive: true })
