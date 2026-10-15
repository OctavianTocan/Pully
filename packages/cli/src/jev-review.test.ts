import type { DiffsPayload } from '@pulls.review/core/types'
import { describe, expect, it } from 'vitest'
import { pullOfDiff, triageFor } from './jev-review'

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'acme', repo: 'app', number: '7' },
  title: 'Fix the crash',
  author: { name: 'octo' },
  base: { sha: 'base', ref: 'main' },
  head: { sha: 'head', ref: 'fix/crash' },
  files: [],
}

describe('pullOfDiff', () => {
  it('describes an open pull request as the list would hold it', () => {
    expect(pullOfDiff(diff)).toMatchObject({ owner: 'acme', repo: 'app', number: 7, title: 'Fix the crash', author: 'octo', state: 'open', headSha: 'head', headRef: 'fix/crash', baseRef: 'main' })
  })

  it('leaves out anything Jev does not sort: a closed pull request, a commit, a diff without a head', () => {
    expect(pullOfDiff({ ...diff, pullRequest: { state: 'merged' } })).toBeUndefined()
    expect(pullOfDiff({ ...diff, pullRequest: { state: 'draft' } })).toMatchObject({ state: 'open', isDraft: true })
    expect(pullOfDiff({ ...diff, ref: { kind: 'github-commit', owner: 'acme', repo: 'app', sha: 'head' } } as DiffsPayload)).toBeUndefined()
    expect(pullOfDiff({ ...diff, head: undefined })).toBeUndefined()
  })
})

describe('triageFor', () => {
  it('has nothing to say about a diff Jev never read', async () => {
    expect(await triageFor({ owner: 'acme', repo: 'app', headSha: 'head', files: [] })).toBeUndefined()
  })
})
