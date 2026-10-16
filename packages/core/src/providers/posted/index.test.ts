import { describe, expect, it } from 'vitest'
import { createPostedSource } from './index'

const PATCH = `diff --git a/src/a.ts b/src/a.ts
index e69de29..4b825dc 100644
--- a/src/a.ts
+++ b/src/a.ts
@@ -0,0 +1,1 @@
+export const a = 1
`

describe('posted source', () => {
  const input = { id: 'k3T9xQ2mLp0a', patch: PATCH, version: 2, title: 'Add a', author: 'Claude Code', createdAt: '2026-10-16T12:00:00Z', updatedAt: '2026-10-16T13:00:00Z' }

  it('parses the patch into a payload with no branches, commits or pull request', async () => {
    const diff = await createPostedSource(input).fetch()
    expect(diff.ref).toEqual({ kind: 'posted', id: 'k3T9xQ2mLp0a' })
    expect(diff).toMatchObject({ title: 'Add a', author: { name: 'Claude Code' } })
    expect(diff.files.map(file => file.path)).toEqual(['src/a.ts'])
    expect(diff.base ?? diff.head ?? diff.commits ?? diff.pullRequest).toBeUndefined()
  })

  it('is keyed by its id and fingerprinted by its version', async () => {
    const source = createPostedSource(input)
    expect(await source.key()).toBe('posted:k3T9xQ2mLp0a')
    expect(await source.fingerprint!()).toBe('v2')
  })
})
