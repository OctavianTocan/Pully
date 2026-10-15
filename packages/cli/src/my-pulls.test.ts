import type { EnrichNode, GithubGraphql, NativeStackNode, SearchNode, StackCandidate } from './my-pulls'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createJevClient } from './jev'
import { createPullClassifier } from './jev-pulls'
import { applyStacks, checksFromRollup, createMyPullsService, inferStacks, mergeRoleBatches, searchQueries, toEnrichment, toFoundPull, toNativeStack } from './my-pulls'

function node(id: string, overrides: Partial<SearchNode> = {}): SearchNode {
  return {
    id,
    number: Number(id.replace(/\D/g, '')) || 1,
    title: `PR ${id}`,
    url: `https://github.com/acme/app/pull/${id}`,
    state: 'OPEN',
    isDraft: false,
    updatedAt: '2026-10-01T00:00:00Z',
    mergedAt: null,
    headRefOid: `sha-${id}`,
    baseRefName: 'main',
    headRefName: `branch-${id}`,
    isCrossRepository: false,
    repository: { nameWithOwner: 'acme/app', defaultBranchRef: { name: 'main' } },
    author: { login: 'octo' },
    comments: { totalCount: 2 },
    labels: { nodes: [{ name: 'bug' }] },
    ...overrides,
  }
}

function enrichNode(id: string, overrides: Partial<EnrichNode> = {}): EnrichNode {
  return {
    id,
    reviewDecision: 'APPROVED',
    mergeable: 'MERGEABLE',
    additions: 10,
    deletions: 3,
    changedFiles: 2,
    commits: { nodes: [{ commit: { statusCheckRollup: { state: 'SUCCESS' } } }] },
    reviewRequests: { nodes: [{ requestedReviewer: { login: 'me' } }, { requestedReviewer: { combinedSlug: 'acme/core' } }, { requestedReviewer: null }] },
    ...overrides,
  }
}

/** A GitHub stand-in answering each search with the given nodes, and with no native stacks unless [stack] says. */
function fakeGraphql(searches: Record<string, SearchNode[]>, enrich: (id: string) => EnrichNode = enrichNode, stack: (id: string) => NativeStackNode = id => ({ id, stack: null, stackEntry: null })) {
  const calls: { query: string, variables: Record<string, unknown> }[] = []
  const graphql: GithubGraphql = async (query, variables) => {
    calls.push({ query, variables })
    if (query.includes('organizations('))
      return { viewer: { login: 'me', organizations: { nodes: [{ login: 'acme' }] } } } as never
    if (query.includes('search(')) {
      const q = variables.q as string
      const role = Object.keys(searches).find(key => q.includes(key))
      return { search: { nodes: role ? searches[role] : [] } } as never
    }
    if (query.includes('stackEntry'))
      return { nodes: (variables.ids as string[]).map(id => stack(id)) } as never
    return { nodes: (variables.ids as string[]).map(id => enrich(id)) } as never
  }
  return { graphql, calls }
}

const isEnrichment = (call: { query: string }) => call.query.includes('nodes(ids') && !call.query.includes('stackEntry')

describe('searchQueries', () => {
  it('builds one search per role and ORs the owners', () => {
    expect(searchQueries('open', ['me', 'acme'])).toEqual([
      ['review-requested', 'is:pr is:open sort:updated-desc review-requested:@me'],
      ['authored', 'is:pr is:open sort:updated-desc author:@me'],
      ['involved', 'is:pr is:open sort:updated-desc involves:@me'],
      ['owned', 'is:pr is:open sort:updated-desc user:me user:acme'],
    ])
    expect(searchQueries('closed', []).map(([role]) => role)).toEqual(['review-requested', 'authored', 'involved'])
  })
})

describe('normalization', () => {
  it('maps a search hit to a list entry', () => {
    expect(toFoundPull(node('n7', { author: null, labels: null, mergedAt: '2026-10-02T00:00:00Z' }), 'owned', 'closed')).toEqual({
      id: 'n7',
      crossRepository: false,
      defaultBranch: 'main',
      pull: {
        owner: 'acme',
        repo: 'app',
        number: 7,
        title: 'PR n7',
        author: 'ghost',
        isDraft: false,
        updatedAt: '2026-10-01T00:00:00Z',
        commentsCount: 2,
        labels: [],
        state: 'closed',
        role: 'owned',
        url: 'https://github.com/acme/app/pull/n7',
        merged: true,
        closedAt: '2026-10-02T00:00:00Z',
        headSha: 'sha-n7',
        requestedFromMe: false,
        baseRef: 'main',
        headRef: 'branch-n7',
      },
    })
  })

  it('maps the check rollup to a coarse status', () => {
    expect(checksFromRollup('SUCCESS')).toBe('success')
    expect(checksFromRollup('FAILURE')).toBe('failure')
    expect(checksFromRollup('ERROR')).toBe('failure')
    expect(checksFromRollup('PENDING')).toBe('pending')
    expect(checksFromRollup('EXPECTED')).toBe('pending')
    expect(checksFromRollup(null)).toBe('none')
  })

  it('maps the enrichment fields', () => {
    expect(toEnrichment(enrichNode('a', { reviewDecision: 'CHANGES_REQUESTED', mergeable: 'CONFLICTING', commits: { nodes: [] } }))).toEqual({
      reviewDecision: 'changes-requested',
      checks: 'none',
      mergeable: 'conflicting',
      additions: 10,
      deletions: 3,
      changedFiles: 2,
      reviewRequests: ['me', 'acme/core'],
    })
    expect(toEnrichment(enrichNode('a', { reviewDecision: null })).reviewDecision).toBeUndefined()
  })

  it('keeps each pull request once, under its most specific role, newest first', () => {
    const merged = mergeRoleBatches([
      ['owned', [toFoundPull(node('n1'), 'owned', 'open'), toFoundPull(node('n3', { updatedAt: '2026-10-03T00:00:00Z' }), 'owned', 'open')]],
      ['review-requested', [toFoundPull(node('n1'), 'review-requested', 'open')]],
      ['authored', [toFoundPull(node('n2', { updatedAt: '2026-10-02T00:00:00Z' }), 'authored', 'open')]],
    ])
    expect(merged.map(hit => [hit.id, hit.pull.role])).toEqual([['n3', 'owned'], ['n2', 'authored'], ['n1', 'review-requested']])
  })
})

describe('createMyPullsService', () => {
  it('lists open pull requests with their review, check and merge state', async () => {
    const { graphql } = fakeGraphql({
      'review-requested:@me': [node('n1')],
      'author:@me': [node('n2')],
      'involves:@me': [node('n1'), node('n2')],
      'user:me user:acme': [node('n3')],
    }, id => enrichNode(id, id === 'n3' ? { commits: { nodes: [{ commit: { statusCheckRollup: { state: 'FAILURE' } } }] } } : {}))
    const pulls = await createMyPullsService({ graphql }).list('open')

    expect(pulls.map(pull => [pull.number, pull.role, pull.requestedFromMe, pull.checks])).toEqual([
      [1, 'review-requested', true, 'success'],
      [2, 'authored', false, 'success'],
      [3, 'owned', false, 'failure'],
    ])
    expect(pulls[0]).toMatchObject({ reviewDecision: 'approved', mergeable: 'mergeable', additions: 10, reviewRequests: ['me', 'acme/core'] })
  })

  it('dates a pull request closed without merging by when it closed', () => {
    const { pull } = toFoundPull(node('n8', { state: 'CLOSED', closedAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z' }), 'authored', 'closed')
    expect(pull).toMatchObject({ merged: false, closedAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-09T00:00:00Z' })
    expect(toFoundPull(node('n9', { closedAt: '2026-10-03T00:00:00Z' }), 'authored', 'open').pull.closedAt).toBeUndefined()
  })

  it('skips enrichment for closed pull requests', async () => {
    const { graphql, calls } = fakeGraphql({ 'author:@me': [node('n1', { state: 'MERGED', mergedAt: '2026-10-02T00:00:00Z' })] })
    const [pull] = await createMyPullsService({ graphql }).list('closed')
    expect(pull).toMatchObject({ state: 'closed', merged: true, closedAt: '2026-10-02T00:00:00Z' })
    expect(pull.checks).toBeUndefined()
    expect(calls.some(call => call.query.includes('nodes(ids'))).toBe(false)
    expect(pull.stack).toBeUndefined()
  })

  it('reuses enrichment for unchanged pull requests and refetches changed ones', async () => {
    let clock = 0
    const authored = [node('n1'), node('n2')]
    const { graphql, calls } = fakeGraphql({ 'author:@me': authored })
    const service = createMyPullsService({ graphql, now: () => clock })
    const enrichedIds = () => calls.filter(isEnrichment).flatMap(call => call.variables.ids as string[])

    await service.list('open')
    expect(enrichedIds()).toEqual(['n1', 'n2'])

    authored[1] = node('n2', { updatedAt: '2026-10-04T00:00:00Z' })
    clock = 20_000
    await service.list('open')
    expect(enrichedIds()).toEqual(['n1', 'n2', 'n2'])
  })

  it('still lists pull requests when enrichment fails', async () => {
    const { graphql: base } = fakeGraphql({ 'author:@me': [node('n1')] })
    const graphql: GithubGraphql = (query, variables) => isEnrichment({ query }) ? Promise.reject(new Error('502')) : base(query, variables)
    const [pull] = await createMyPullsService({ graphql }).list('open')
    expect(pull.number).toBe(1)
    expect(pull.checks).toBeUndefined()
  })

  it('retries a failed search once', async () => {
    const { graphql: base } = fakeGraphql({ 'author:@me': [node('n1')] })
    let failures = 0
    const graphql: GithubGraphql = (query, variables) => {
      if (query.includes('search(') && (variables.q as string).includes('author:@me') && failures++ === 0)
        return Promise.reject(new Error('502'))
      return base(query, variables)
    }
    expect(await createMyPullsService({ graphql }).list('open')).toHaveLength(1)
  })

  it('serves the cached list at once and refreshes it in the background once aged', async () => {
    let clock = 0
    const { graphql, calls } = fakeGraphql({ 'author:@me': [node('n1')] })
    const service = createMyPullsService({ graphql, now: () => clock })
    const searches = () => calls.filter(call => call.query.includes('search(')).length

    expect(service.cached('open')).toBeNull()
    await vi.waitFor(() => expect(service.cached('open')).toMatchObject({ fetchedAt: 0, refreshing: false }))
    const firstRound = searches()

    clock = 60_000
    // Asked only to read the copy, as the phone does while Jev sorts, it leaves GitHub alone.
    expect(service.cached('open', false)).toMatchObject({ fetchedAt: 0, refreshing: false })
    expect(searches()).toBe(firstRound)
    const snapshot = service.cached('open')
    expect(snapshot).toMatchObject({ fetchedAt: 0, refreshing: true })
    expect(snapshot!.pulls).toHaveLength(1)
    await vi.waitFor(() => expect(service.cached('open')?.fetchedAt).toBe(60_000))
    expect(searches()).toBe(firstRound * 2)
  })
})

function candidate(number: number, headRef: string, baseRef: string, overrides: Partial<StackCandidate> = {}): StackCandidate {
  return { owner: 'acme', repo: 'app', number, headRef, baseRef, defaultBranch: 'main', ...overrides }
}

describe('inferStacks', () => {
  it('numbers a chain from the bottom and points each layer at the one under it', () => {
    const stacks = inferStacks([candidate(3, 'c', 'b'), candidate(1, 'a', 'main'), candidate(2, 'b', 'a')])
    expect(Object.fromEntries(stacks)).toEqual({
      'acme/app#1': { id: 'inferred:acme/app:a', position: 1, size: 3, native: false, below: undefined },
      'acme/app#2': { id: 'inferred:acme/app:a', position: 2, size: 3, native: false, below: 1 },
      'acme/app#3': { id: 'inferred:acme/app:a', position: 3, size: 3, native: false, below: 2 },
    })
  })

  it('gives two pull requests on one the same position in one stack', () => {
    const stacks = inferStacks([candidate(1, 'a', 'main'), candidate(2, 'b', 'a'), candidate(3, 'c', 'a'), candidate(4, 'd', 'c')])
    expect([...stacks.values()].map(stack => [stack.position, stack.size, stack.below])).toEqual([[1, 4, undefined], [2, 4, 1], [2, 4, 1], [3, 4, 3]])
    expect(new Set([...stacks.values()].map(stack => stack.id))).toEqual(new Set(['inferred:acme/app:a']))
  })

  it('keeps repositories apart even when their branch names match', () => {
    const stacks = inferStacks([candidate(1, 'a', 'main'), candidate(2, 'b', 'a', { repo: 'other' }), candidate(3, 'b', 'a', { owner: 'someone' })])
    expect(stacks.size).toBe(0)
  })

  it('puts nothing under a pull request based on the default branch', () => {
    // A release pull request from `main` would otherwise look like the bottom of every pull request into `main`.
    const stacks = inferStacks([candidate(1, 'main', 'release'), candidate(2, 'feature', 'main'), candidate(3, 'lonely', 'main')])
    expect(stacks.size).toBe(0)
  })

  it('never sits a pull request on a fork\'s branch, and makes no stack of a loop', () => {
    expect(inferStacks([candidate(1, 'patch', 'dev', { crossRepository: true }), candidate(2, 'x', 'patch')]).size).toBe(0)
    expect(inferStacks([candidate(1, 'a', 'b'), candidate(2, 'b', 'a')]).size).toBe(0)
  })
})

describe('applyStacks', () => {
  it('takes GitHub\'s own stack first and infers among the rest', () => {
    const found = [
      toFoundPull(node('n1', { headRefName: 'a' }), 'authored', 'open'),
      toFoundPull(node('n2', { headRefName: 'b', baseRefName: 'a' }), 'authored', 'open'),
      toFoundPull(node('n3', { headRefName: 'x' }), 'authored', 'open'),
      toFoundPull(node('n4', { headRefName: 'y', baseRefName: 'x' }), 'authored', 'open'),
    ]
    const native = { n1: { number: 7, size: 3, position: 1 }, n2: { number: 7, size: 3, position: 2 } } as Record<string, { number: number, size: number, position: number }>
    applyStacks(found, id => native[id] ?? null)
    expect(found.map(hit => hit.pull.stack)).toEqual([
      { id: 'native:acme/app:7', position: 1, size: 3, native: true },
      { id: 'native:acme/app:7', position: 2, size: 3, native: true, below: 1 },
      { id: 'inferred:acme/app:x', position: 1, size: 2, native: false, below: undefined },
      { id: 'inferred:acme/app:x', position: 2, size: 2, native: false, below: 3 },
    ])
  })

  it('sits a pull request on a native layer\'s branch, and leaves the native layers as GitHub has them', () => {
    const found = [
      toFoundPull(node('n1', { headRefName: 'a' }), 'authored', 'open'),
      toFoundPull(node('n2', { headRefName: 'b', baseRefName: 'a' }), 'authored', 'open'),
      toFoundPull(node('n5', { headRefName: 'z', baseRefName: 'b' }), 'authored', 'open'),
    ]
    const native = { n1: { number: 7, size: 2, position: 1 }, n2: { number: 7, size: 2, position: 2 } } as Record<string, { number: number, size: number, position: number }>
    applyStacks(found, id => native[id] ?? null)
    expect(found.map(hit => hit.pull.stack)).toEqual([
      { id: 'native:acme/app:7', position: 1, size: 2, native: true },
      { id: 'native:acme/app:7', position: 2, size: 2, native: true, below: 1 },
      { id: 'inferred:acme/app:a', position: 3, size: 3, native: false, below: 2 },
    ])
  })

  it('reads a native answer only when the pull request is a layer of a real stack', () => {
    expect(toNativeStack({ id: 'n1', stack: { number: 2, size: 3, baseRefName: 'main' }, stackEntry: { position: 2 } })).toEqual({ number: 2, size: 3, position: 2 })
    expect(toNativeStack({ id: 'n1', stack: null, stackEntry: null })).toBeNull()
    expect(toNativeStack({ id: 'n1', stack: { number: 2, size: 1, baseRefName: 'main' }, stackEntry: { position: 1 } })).toBeNull()
  })
})

describe('stacks in the list', () => {
  const stacked = [node('n1', { headRefName: 'a' }), node('n2', { headRefName: 'b', baseRefName: 'a' })]

  it('asks for native stacks in a request of its own and keeps the branches', async () => {
    const { graphql, calls } = fakeGraphql({ 'author:@me': stacked }, enrichNode, id => ({ id, stack: { number: 5, size: 2, baseRefName: 'main' }, stackEntry: { position: id === 'n1' ? 1 : 2 } }))
    const pulls = await createMyPullsService({ graphql }).list('open')
    expect(pulls.map(pull => [pull.number, pull.baseRef, pull.headRef, pull.stack])).toEqual([
      [1, 'main', 'a', { id: 'native:acme/app:5', position: 1, size: 2, native: true }],
      [2, 'a', 'b', { id: 'native:acme/app:5', position: 2, size: 2, native: true, below: 1 }],
    ])
    expect(calls.filter(call => call.query.includes('stackEntry')).every(call => !call.query.includes('reviewDecision'))).toBe(true)
  })

  it('infers instead of keeping a native answer that no longer describes the pull request when asking again fails', async () => {
    let clock = 0
    let failing = false
    const authored = [...stacked]
    const { graphql: base } = fakeGraphql({ 'author:@me': authored }, enrichNode, id => ({ id, stack: { number: 5, size: 2, baseRefName: 'main' }, stackEntry: { position: id === 'n1' ? 1 : 2 } }))
    const graphql: GithubGraphql = (query, variables) => failing && query.includes('stackEntry') ? Promise.reject(new Error('502')) : base(query, variables)
    const service = createMyPullsService({ graphql, now: () => clock })
    expect((await service.list('open')).map(pull => pull.stack?.id)).toEqual(['native:acme/app:5', 'native:acme/app:5'])

    // #2 gets a new head and leaves the stack, and GitHub cannot be asked about it this time.
    failing = true
    authored[1] = node('n2', { headRefName: 'b', baseRefName: 'a', headRefOid: 'sha-new', updatedAt: '2026-10-05T00:00:00Z' })
    clock = 20_000
    const pulls = await service.list('open')
    expect(pulls.find(pull => pull.number === 1)!.stack?.native).toBe(true)
    expect(pulls.find(pull => pull.number === 2)!.stack).toEqual({ id: 'inferred:acme/app:a', position: 2, size: 2, native: false, below: 1 })
  })

  it('infers stacks and keeps every badge when the host has no stack fields, and stops asking', async () => {
    let clock = 0
    let refused = 0
    const authored = [...stacked]
    const { graphql: base } = fakeGraphql({ 'author:@me': authored })
    const graphql: GithubGraphql = (query, variables) => {
      if (!query.includes('stackEntry'))
        return base(query, variables)
      refused++
      return Promise.reject(new Error('GitHub GraphQL request failed (200): Field \'stack\' doesn\'t exist on type \'PullRequest\''))
    }
    const service = createMyPullsService({ graphql, now: () => clock })
    const pulls = await service.list('open')
    expect(pulls.map(pull => [pull.checks, pull.stack?.id, pull.stack?.position])).toEqual([['success', 'inferred:acme/app:a', 1], ['success', 'inferred:acme/app:a', 2]])
    expect(refused).toBe(1)

    // Fresh search results, so nothing is reused: only the unsupported field keeps the request away.
    authored[0] = node('n1', { headRefName: 'a', updatedAt: '2026-10-05T00:00:00Z' })
    clock = 20_000
    expect((await service.list('open'))[1]!.stack?.below).toBe(1)
    expect(refused).toBe(1)
  })
})

describe('classification with Jev', () => {
  const ANSWER = {
    model: 'jev-1.13.0',
    answers: {
      kind: { type: 'choice', choice: 'fix', probabilities: { fix: 0.8 }, confidence: 0.8 },
      risk: { type: 'score', score: 0.6, legend: { 0: 'trivial', 1: 'low', 2: 'medium', 3: 'high' }, probabilities: { 0: 0.4, 1: 0.6 }, confidence: 0.6 },
    },
    usage: { input_tokens: 60, output_tokens: 0 },
  }

  /** A service whose Jev answers only once `answer()` is called. */
  function setup(respond: () => Response = () => Response.json(ANSWER), options: { apiKey?: string } = { apiKey: 'key' }) {
    let clock = 0
    const held: (() => void)[] = []
    const bodies: { state: Record<string, unknown> }[] = []
    const fetch = (async (_url: string, init: RequestInit) => {
      bodies.push(JSON.parse(init.body as string))
      await new Promise<void>(resolve => held.push(resolve))
      return respond()
    }) as typeof globalThis.fetch
    const jev = createJevClient({ apiKey: options.apiKey, fetch, log: () => {}, sleep: async () => {} })
    const storage = createStorage({ driver: memoryDriver() })
    const { graphql } = fakeGraphql({ 'author:@me': [node('n1', { labels: { nodes: [{ name: 'bug' }] } })] })
    const service = createMyPullsService({ graphql, now: () => clock, classifier: createPullClassifier({ jev, store: () => ({ storage }) }) })
    return {
      service,
      bodies,
      answer: () => held.splice(0).forEach(resolve => resolve()),
      tick: (ms: number) => { clock += ms },
    }
  }

  it('answers the list before Jev does, and later lists carry the classification', async () => {
    const { service, bodies, answer } = setup()
    const [pull] = await service.list('open')
    expect(pull.jev).toBeUndefined()

    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].state).toEqual({ repository: 'acme/app', title: 'PR n1', labels: ['bug'], branch: 'branch-n1', author: 'octo' })
    answer()
    await vi.waitFor(async () => expect((await service.list('open'))[0].jev).toEqual({ kind: 'fix', kindConfidence: 0.8, risk: 0.6 }))
    expect(service.cached('open')).toMatchObject({ classifier: 'jev', pulls: [{ jev: { kind: 'fix' } }] })
  })

  it('does not ask again for a pull request it already classified', async () => {
    const { service, bodies, answer, tick } = setup()
    await service.list('open')
    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    answer()
    await vi.waitFor(async () => expect((await service.list('open'))[0].jev).toBeDefined())
    tick(60_000)
    await service.list('open')
    expect(bodies).toHaveLength(1)
  })

  it('keeps listing when TypeSafe refuses the key, and stops offering the grouping', async () => {
    const { service, bodies, answer } = setup(() => Response.json({ error: 'bad key' }, { status: 401 }))
    expect(await service.list('open')).toHaveLength(1)
    expect(service.cached('open')).toMatchObject({ classifier: 'jev' })
    await vi.waitFor(() => expect(bodies).toHaveLength(1))
    answer()
    await vi.waitFor(() => expect(service.cached('open')).not.toHaveProperty('classifier'))
    const [pull] = await service.list('open')
    expect(pull.jev).toBeUndefined()
  })

  it('without a key, offers no grouping and asks nothing', async () => {
    const { service, bodies } = setup(undefined, {})
    await service.list('open')
    expect(service.cached('open')).not.toHaveProperty('classifier')
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(bodies).toHaveLength(0)
  })
})
