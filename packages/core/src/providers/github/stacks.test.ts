import type { RawNativeStack, RawStackPull, StackClient } from './stacks'
import { describe, expect, it } from 'vitest'
import { fetchPullRequestStack, inferPullRequestStack, parseNativeStack, toStackEntry } from './stacks'

function pull(number: number, head: string, base: string, overrides: Partial<RawStackPull> = {}): RawStackPull {
  return {
    number,
    title: `PR ${number}`,
    state: 'OPEN',
    isDraft: false,
    merged: false,
    headRefName: head,
    baseRefName: base,
    isCrossRepository: false,
    additions: number,
    deletions: 1,
    commits: { nodes: [{ commit: { statusCheckRollup: { state: 'SUCCESS' } } }] },
    ...overrides,
  }
}

interface FakeRepo {
  pulls: RawStackPull[]
  defaultBranch?: string
  /** What `PullRequest.stack` answers; `'unsupported'` makes the stack query fail as on a host without the field. */
  native?: RawNativeStack | null | 'unsupported'
}

/** A GitHub stand-in over a list of open pull requests, recording which queries ran. */
function fakeClient(repo: FakeRepo) {
  const calls: { name: string, ref?: string }[] = []
  const client: StackClient = {
    async graphql<T>(query: string, variables: Record<string, string | number | null>) {
      const name = /query (\w+)/.exec(query)![1]!
      calls.push({ name, ref: variables.ref as string | undefined })
      const byNumber = repo.pulls.find(candidate => candidate.number === variables.number) ?? null
      const repository = (data: object) => ({ repository: { defaultBranchRef: repo.defaultBranch ? { name: repo.defaultBranch } : null, ...data } }) as T
      switch (name) {
        case 'PullRequestStack':
          if (repo.native === 'unsupported')
            throw new Error('Field \'stack\' doesn\'t exist on type \'PullRequest\'')
          return repository({ pullRequest: byNumber && { ...byNumber, stack: repo.native ?? null } })
        case 'StackPull':
          return repository({ pullRequest: byNumber })
        case 'StackBelow':
          return repository({ pullRequests: { nodes: repo.pulls.filter(candidate => candidate.headRefName === variables.ref) } })
        case 'StackAbove':
          return repository({ pullRequests: { nodes: repo.pulls.filter(candidate => candidate.baseRefName === variables.ref) } })
        default:
          throw new Error(`unexpected query ${name}`)
      }
    },
  }
  return { client, calls }
}

const layers = (stack: Awaited<ReturnType<typeof fetchPullRequestStack>>) => stack?.entries.map(entry => [entry.number, entry.position, entry.current])

describe('toStackEntry', () => {
  it('maps state, checks and size', () => {
    expect(toStackEntry(pull(7, 'b', 'a'), 2, true)).toEqual({
      number: 7,
      title: 'PR 7',
      state: 'open',
      position: 2,
      current: true,
      headRef: 'b',
      baseRef: 'a',
      checks: 'success',
      additions: 7,
      deletions: 1,
    })
    expect(toStackEntry(pull(1, 'b', 'a', { isDraft: true }), 1, false).state).toBe('draft')
    expect(toStackEntry(pull(1, 'b', 'a', { state: 'MERGED', merged: true }), 1, false).state).toBe('merged')
    expect(toStackEntry(pull(1, 'b', 'a', { state: 'CLOSED', isDraft: true }), 1, false).state).toBe('closed')
    expect(toStackEntry(pull(1, 'b', 'a', { commits: { nodes: [{ commit: { statusCheckRollup: null } }] } }), 1, false).checks).toBe('none')
    expect(toStackEntry(pull(1, 'b', 'a', { commits: { nodes: [{ commit: { statusCheckRollup: { state: 'ERROR' } } }] } }), 1, false).checks).toBe('failure')
  })
})

describe('parseNativeStack', () => {
  it('orders the entries bottom first and marks the asked-for one', () => {
    const stack = parseNativeStack({
      number: 4,
      baseRefName: 'main',
      entries: { nodes: [
        { position: 3, pullRequest: pull(12, 'c', 'b') },
        { position: 1, pullRequest: pull(10, 'a', 'main', { state: 'MERGED', merged: true }) },
        null,
        { position: 2, pullRequest: pull(11, 'b', 'a') },
        { position: 4, pullRequest: null },
      ] },
    }, 11)
    expect(stack).toMatchObject({ native: true, number: 4, base: 'main' })
    expect(stack?.entries.map(entry => [entry.number, entry.position, entry.current, entry.state])).toEqual([
      [10, 1, false, 'merged'],
      [11, 2, true, 'open'],
      [12, 3, false, 'open'],
    ])
  })

  it('has no stack without one, or with only this pull request in it', () => {
    expect(parseNativeStack(null, 1)).toBeNull()
    expect(parseNativeStack({ number: 1, baseRefName: 'main', entries: { nodes: [{ position: 1, pullRequest: pull(1, 'a', 'main') }] } }, 1)).toBeNull()
  })
})

describe('inferPullRequestStack', () => {
  it('walks a chain down to the trunk and up to the top', async () => {
    const pulls = [pull(1, 'a', 'main'), pull(2, 'b', 'a'), pull(3, 'c', 'b'), pull(4, 'd', 'c')]
    const { client } = fakeClient({ pulls, defaultBranch: 'main' })
    const stack = await inferPullRequestStack(client, 'o', 'r', pulls[1]!, 'main')
    expect(stack).toMatchObject({ native: false, base: 'main' })
    expect(layers(stack)).toEqual([[1, 1, false], [2, 2, true], [3, 3, false], [4, 4, false]])
  })

  it('follows a fork upward on every side, its layers sharing a position', async () => {
    const pulls = [pull(1, 'a', 'main'), pull(2, 'b', 'a'), pull(3, 'c', 'a'), pull(4, 'd', 'c')]
    const { client } = fakeClient({ pulls, defaultBranch: 'main' })
    expect(layers(await inferPullRequestStack(client, 'o', 'r', pulls[0]!, 'main'))).toEqual([[1, 1, true], [2, 2, false], [3, 2, false], [4, 3, false]])
  })

  it('does not ask what sits under a pull request based on the default branch', async () => {
    const pulls = [pull(1, 'a', 'main'), pull(9, 'main', 'release')]
    const { client, calls } = fakeClient({ pulls, defaultBranch: 'main' })
    expect(await inferPullRequestStack(client, 'o', 'r', pulls[0]!, 'main')).toBeNull()
    expect(calls.map(call => call.name)).toEqual(['StackAbove'])
  })

  it('never sits a pull request on a fork\'s branch of the same name', async () => {
    const pulls = [pull(5, 'feature', 'main', { isCrossRepository: true }), pull(6, 'next', 'feature')]
    const { client } = fakeClient({ pulls })
    expect(await inferPullRequestStack(client, 'o', 'r', pulls[1]!)).toBeNull()
  })

  it('stops after ten requests each way', async () => {
    const pulls = Array.from({ length: 30 }, (_, index) => pull(index + 1, `b${index + 1}`, index === 0 ? 'main' : `b${index}`))
    const { client, calls } = fakeClient({ pulls, defaultBranch: 'main' })
    const stack = await inferPullRequestStack(client, 'o', 'r', pulls[14]!, 'main')
    expect(calls.filter(call => call.name === 'StackBelow')).toHaveLength(10)
    expect(calls.filter(call => call.name === 'StackAbove')).toHaveLength(10)
    expect(stack?.entries.map(entry => entry.number)).toEqual(Array.from({ length: 21 }, (_, index) => index + 5))
    expect(stack?.entries.find(entry => entry.current)?.position).toBe(11)
    expect(stack?.base).toBe('b4')
  })
})

describe('fetchPullRequestStack', () => {
  const chain = [pull(1, 'a', 'main'), pull(2, 'b', 'a')]

  it('uses GitHub\'s own stack when there is one', async () => {
    const native: RawNativeStack = { number: 3, baseRefName: 'main', entries: { nodes: chain.map((raw, index) => ({ position: index + 1, pullRequest: raw })) } }
    const { client, calls } = fakeClient({ pulls: chain, native, defaultBranch: 'main' })
    const stack = await fetchPullRequestStack(client, 'o', 'r', 2)
    expect(stack).toMatchObject({ native: true, number: 3 })
    expect(calls.map(call => call.name)).toEqual(['PullRequestStack'])
  })

  it('infers from the same answer when GitHub has no stack for it', async () => {
    const { client, calls } = fakeClient({ pulls: chain, native: null, defaultBranch: 'main' })
    expect(layers(await fetchPullRequestStack(client, 'o', 'r', '2'))).toEqual([[1, 1, false], [2, 2, true]])
    expect(calls.map(call => call.name)).toEqual(['PullRequestStack', 'StackBelow', 'StackAbove'])
  })

  it('infers when the host has no stack fields at all', async () => {
    const { client, calls } = fakeClient({ pulls: chain, native: 'unsupported', defaultBranch: 'main' })
    expect(layers(await fetchPullRequestStack(client, 'o', 'r', 1))).toEqual([[1, 1, true], [2, 2, false]])
    expect(calls[1]!.name).toBe('StackPull')
  })

  it('has no stack for a pull request on its own', async () => {
    const { client } = fakeClient({ pulls: [pull(1, 'a', 'main')], native: 'unsupported', defaultBranch: 'main' })
    expect(await fetchPullRequestStack(client, 'o', 'r', 1)).toBeNull()
  })

  it('says when the pull request does not exist', async () => {
    const { client } = fakeClient({ pulls: [], native: null })
    await expect(fetchPullRequestStack(client, 'o', 'r', 9)).rejects.toMatchObject({ status: 404 })
  })
})
