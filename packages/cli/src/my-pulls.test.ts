import type { EnrichNode, GithubGraphql, SearchNode } from './my-pulls'
import { describe, expect, it, vi } from 'vitest'
import { checksFromRollup, createMyPullsService, mergeRoleBatches, searchQueries, toEnrichment, toFoundPull } from './my-pulls'

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
    repository: { nameWithOwner: 'acme/app' },
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

/** A GitHub stand-in answering each search with the given nodes. */
function fakeGraphql(searches: Record<string, SearchNode[]>, enrich: (id: string) => EnrichNode = enrichNode) {
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
    return { nodes: (variables.ids as string[]).map(id => enrich(id)) } as never
  }
  return { graphql, calls }
}

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
  })

  it('reuses enrichment for unchanged pull requests and refetches changed ones', async () => {
    let clock = 0
    const authored = [node('n1'), node('n2')]
    const { graphql, calls } = fakeGraphql({ 'author:@me': authored })
    const service = createMyPullsService({ graphql, now: () => clock })
    const enrichedIds = () => calls.filter(call => call.query.includes('nodes(ids')).flatMap(call => call.variables.ids as string[])

    await service.list('open')
    expect(enrichedIds()).toEqual(['n1', 'n2'])

    authored[1] = node('n2', { updatedAt: '2026-10-04T00:00:00Z' })
    clock = 20_000
    await service.list('open')
    expect(enrichedIds()).toEqual(['n1', 'n2', 'n2'])
  })

  it('still lists pull requests when enrichment fails', async () => {
    const { graphql: base } = fakeGraphql({ 'author:@me': [node('n1')] })
    const graphql: GithubGraphql = (query, variables) => query.includes('nodes(ids') ? Promise.reject(new Error('502')) : base(query, variables)
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
    const snapshot = service.cached('open')
    expect(snapshot).toMatchObject({ fetchedAt: 0, refreshing: true })
    expect(snapshot!.pulls).toHaveLength(1)
    await vi.waitFor(() => expect(service.cached('open')?.fetchedAt).toBe(60_000))
    expect(searches()).toBe(firstRound * 2)
  })
})
