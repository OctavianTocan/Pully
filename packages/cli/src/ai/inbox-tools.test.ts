import type { Exec, InboxPayload } from './inbox-tools'
import { describe, expect, it, vi } from 'vitest'
import { callInboxTool, describePull, inboxTools, listedPull, pickFiles } from './inbox-tools'

const payload: InboxPayload = { pulls: [{ owner: 'octa', repo: 'web', number: 5 }, { owner: 'octa', repo: 'api', number: 9 }] }

const DIFF = `diff --git a/src/a.ts b/src/a.ts
--- a/src/a.ts
+++ b/src/a.ts
@@ -1 +1 @@
-old
+new
diff --git a/src/b.ts b/src/b.ts
--- a/src/b.ts
+++ b/src/b.ts
@@ -1 +1 @@
-b
+B
`

describe('inbox tools', () => {
  it('offers reading a pull request and its diff', () => {
    expect(inboxTools().map(tool => tool.name)).toEqual(['read_pull_request', 'read_pull_request_diff'])
  })

  it('reads only pull requests the list has', () => {
    expect(listedPull(payload, 'Octa/Web#5')).toEqual({ owner: 'octa', repo: 'web', number: 5 })
    expect(() => listedPull(payload, 'octa/web#6')).toThrow('octa/web#6 is not in the list.')
    expect(() => listedPull(payload, 'web#5')).toThrow('owner/repo#number')
    expect(() => listedPull(payload, undefined)).toThrow('owner/repo#number')
  })

  it('never runs gh for a pull request outside the list, or for an unknown tool', async () => {
    const exec = vi.fn<Exec>(async () => '{}')
    await expect(callInboxTool(payload, 'read_pull_request', { pull: 'evil/repo#1' }, exec)).rejects.toThrow('not in the list')
    await expect(callInboxTool(payload, 'run_shell', { pull: 'octa/web#5' }, exec)).rejects.toThrow('unknown tool run_shell')
    expect(exec).not.toHaveBeenCalled()
  })

  it('reads a pull request through gh pr view', async () => {
    const exec = vi.fn<Exec>(async () => JSON.stringify({
      title: 'Fix the cache',
      body: 'Keys by repository.',
      author: { login: 'ana' },
      state: 'OPEN',
      isDraft: false,
      headRefName: 'fix',
      baseRefName: 'main',
      additions: 3,
      deletions: 1,
      changedFiles: 1,
      createdAt: '2026-10-10T09:00:00Z',
      updatedAt: '2026-10-14T09:00:00Z',
      statusCheckRollup: [{ name: 'lint', conclusion: 'FAILURE' }, { name: 'test', status: 'IN_PROGRESS' }, { context: 'deploy', state: 'SUCCESS' }],
      reviews: [{ author: { login: 'bo' }, state: 'CHANGES_REQUESTED', body: 'Needs a test.', submittedAt: '2026-10-12T09:00:00Z' }],
      comments: [{ author: { login: 'ana' }, body: 'Added it.', createdAt: '2026-10-13T09:00:00Z' }],
      files: [{ path: 'src/cache.ts', additions: 3, deletions: 1 }],
    }))
    const text = await callInboxTool(payload, 'read_pull_request', { pull: 'octa/web#5' }, exec)
    expect(exec).toHaveBeenCalledWith('gh', ['pr', 'view', '5', '--repo', 'octa/web', '--json', expect.stringContaining('statusCheckRollup')])
    expect(text).toContain('# octa/web#5: Fix the cache')
    expect(text).toContain('open · by ana · fix into main · +3 −1 in 1 files')
    expect(text).toContain('Checks: 1 failing (lint), 1 running, 1 passing')
    expect(text).toContain('## Description\n\nKeys by repository.')
    expect(text.indexOf('bo (changes requested, 2026-10-12): Needs a test.')).toBeLessThan(text.indexOf('ana (2026-10-13): Added it.'))
    expect(text).toContain('## Files\n\nsrc/cache.ts (+3 −1)')
  })

  it('says so when gh cannot read it', async () => {
    expect(await callInboxTool(payload, 'read_pull_request', { pull: 'octa/api#9' }, async () => null)).toBe('octa/api#9 could not be read.')
    expect(await callInboxTool(payload, 'read_pull_request_diff', { pull: 'octa/api#9' }, async () => null)).toBe('The diff of octa/api#9 could not be read.')
  })

  it('reads the diff, or only the files asked for', async () => {
    const exec = vi.fn<Exec>(async () => DIFF)
    expect(await callInboxTool(payload, 'read_pull_request_diff', { pull: 'octa/web#5' }, exec)).toBe(DIFF)
    expect(exec).toHaveBeenCalledWith('gh', ['pr', 'diff', '5', '--repo', 'octa/web'])
    const only = await callInboxTool(payload, 'read_pull_request_diff', { pull: 'octa/web#5', paths: ['src/b.ts'] }, exec)
    expect(only.startsWith('diff --git a/src/b.ts b/src/b.ts')).toBe(true)
    expect(only).not.toContain('src/a.ts')
    expect(await callInboxTool(payload, 'read_pull_request_diff', { pull: 'octa/web#5', paths: ['c.ts'] }, exec)).toBe('octa/web#5 changes none of c.ts.')
    await expect(callInboxTool(payload, 'read_pull_request_diff', { pull: 'octa/web#5', paths: 'c.ts' }, exec)).rejects.toThrow('paths must be an array')
  })

  it('keeps the whole diff when no paths are given', () => {
    expect(pickFiles(DIFF, [])).toBe(DIFF)
  })

  it('describes a merged draft-less pull request with no checks or discussion', () => {
    const text = describePull('o/r#1', { title: 'T', state: 'MERGED', mergedAt: '2026-10-14T00:00:00Z', author: { login: 'a' } })
    expect(text).toContain('merged · by a')
    expect(text).toContain('Checks: none')
    expect(text).toContain('(the author wrote none)')
    expect(text).toContain('## Reviews and comments\n\nNone yet.')
  })
})
