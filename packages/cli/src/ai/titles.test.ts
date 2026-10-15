import { describe, expect, it } from 'vitest'
import { describeCommand, describeTool } from './titles'

describe('work-log titles', () => {
  it.each([
    ['/bin/bash -lc \'sed -n 1,80p src/queue.ts\'', 'Reading src/queue.ts', 'Read src/queue.ts'],
    ['head -n 20 a.ts b.ts', 'Reading a.ts and 1 more file', 'Read a.ts and 1 more file'],
    ['rg -n "useQueue" packages/app', 'Searching for `useQueue` in packages/app', 'Searched for `useQueue` in packages/app'],
    ['git status --short', 'Checking `git status`', 'Checked `git status`'],
    ['find . -name "*.ts"', 'Finding files', 'Found files'],
    ['ls src', 'Listing src', 'Listed src'],
    ['pnpm test && pnpm lint', 'Running `pnpm test && pnpm lint`', 'Ran `pnpm test && pnpm lint`'],
  ])('describes `%s`', (command, running, done) => {
    expect(describeCommand(command)).toMatchObject({ running, done })
  })

  it('describes PR tools by what they look at', () => {
    expect(describeTool('mcp__pr__read_file_diff', { paths: ['a.ts', 'b.ts', 'c.ts'] })).toEqual({ running: 'Reading the diff of a.ts and 2 more files', done: 'Read the diff of a.ts and 2 more files' })
    expect(describeTool('find_files', {}).running).toBe('Listing the repository\'s files')
    expect(describeTool('mcp__pr__search_source', { pattern: 'useQueue', path: 'src/**' }).done).toBe('Searched the code for `useQueue` in src/**')
    expect(describeTool('mcp__other__thing').running).toBe('Running thing')
  })

  it('describes inbox tools by the pull request they read', () => {
    expect(describeTool('mcp__inbox__read_pull_request', { pull: 'o/r#5' })).toEqual({ running: 'Reading o/r#5', done: 'Read o/r#5' })
    expect(describeTool('mcp__inbox__read_pull_request_diff', { pull: 'o/r#5', paths: ['a.ts'] }).done).toBe('Read the diff of o/r#5 (a.ts)')
    expect(describeTool('mcp__inbox__read_pull_request_diff', {}).running).toBe('Reading the diff of a pull request')
  })
})
