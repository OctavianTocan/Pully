import type { PrOverview } from './overview'
import type { RawCheckContext, RawOverviewData, RawPullRequest, RawTimelineNode } from './overview-query'
import { Diagnostic } from 'nostics'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { staticCredentials } from '../../types/source'
import { createGithubClient, GithubApiError } from './client'
import { assessMergeability, defaultMergeCommit, fetchPullRequestOverview, parseChecks, parsePullRequestOverview, parseTimeline } from './overview'
import { changePullRequestState, mergePullRequest, updatePullRequestBranch, updatePullRequestText } from './overview-actions'

function rawPullRequest(overrides: Partial<RawPullRequest> = {}): RawPullRequest {
  return {
    id: 'PR_1',
    number: 7,
    title: 'Add widgets',
    url: 'https://github.com/o/r/pull/7',
    body: 'Adds widgets.',
    bodyHTML: '<p>Adds widgets.</p>',
    createdAt: '2026-01-01T00:00:00Z',
    author: { login: 'alice', avatarUrl: 'https://avatars/alice' },
    state: 'OPEN',
    isDraft: false,
    merged: false,
    mergedAt: null,
    closedAt: null,
    mergeable: 'MERGEABLE',
    mergeStateStatus: 'CLEAN',
    reviewDecision: 'APPROVED',
    viewerCanUpdate: true,
    viewerCanUpdateBranch: false,
    viewerCanClose: true,
    viewerCanReopen: false,
    viewerCanMergeAsAdmin: false,
    headRefOid: 'head000',
    headRefName: 'feat/widgets',
    baseRefName: 'main',
    baseRefOid: 'base000',
    headRepositoryOwner: { login: 'alice' },
    reviewRequests: { nodes: [{ requestedReviewer: { __typename: 'User', login: 'bob' } }, { requestedReviewer: { __typename: 'Team', name: 'core' } }] },
    latestOpinionatedReviews: { nodes: [{ state: 'APPROVED', author: { login: 'carol' } }] },
    headCommit: { nodes: [{ commit: { oid: 'head000', statusCheckRollup: null } }] },
    commits: {
      totalCount: 2,
      nodes: [
        { commit: { oid: 'c1', abbreviatedOid: 'c1', messageHeadline: 'first', messageBody: '', committedDate: '2026-01-01T00:00:00Z', url: 'u1', author: { name: 'Alice', user: { login: 'alice' } }, parents: { nodes: [{ oid: 'base000' }] }, statusCheckRollup: { state: 'SUCCESS' } } },
        { commit: { oid: 'c2', abbreviatedOid: 'c2', messageHeadline: 'second', messageBody: 'details', committedDate: '2026-01-02T00:00:00Z', url: 'u2', author: { name: 'Alice', user: null }, parents: { nodes: [{ oid: 'c1' }] }, statusCheckRollup: null } },
      ],
    },
    timelineItems: { totalCount: 0, nodes: [] },
    ...overrides,
  }
}

function rawData(pr: RawPullRequest | null = rawPullRequest()): RawOverviewData {
  return {
    repository: {
      viewerPermission: 'WRITE',
      mergeCommitAllowed: true,
      squashMergeAllowed: true,
      rebaseMergeAllowed: false,
      viewerDefaultMergeMethod: 'SQUASH',
      squashMergeCommitTitle: 'COMMIT_OR_PR_TITLE',
      squashMergeCommitMessage: 'COMMIT_MESSAGES',
      mergeCommitTitle: 'MERGE_MESSAGE',
      mergeCommitMessage: 'PR_TITLE',
      pullRequest: pr,
    },
  }
}

function overview(prOverrides: Partial<RawPullRequest> = {}): PrOverview {
  return parsePullRequestOverview(rawData(rawPullRequest(prOverrides)))!
}

function checkRun(name: string, status: string, conclusion: string | null, extra: Partial<RawCheckContext> = {}): RawCheckContext {
  return { __typename: 'CheckRun', id: name, name, status, conclusion, detailsUrl: `https://ci/${name}`, startedAt: null, completedAt: null, title: null, isRequired: false, checkSuite: { app: { name: 'GitHub Actions' }, workflowRun: { workflow: { name: 'CI' } } }, ...extra } as RawCheckContext
}

function graphqlResponse(data: unknown, errors?: { type?: string, message: string }[]) {
  return new Response(JSON.stringify({ data, errors }), { status: 200 })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parsePullRequestOverview', () => {
  it('normalizes state, reviewers, commits and merge settings', () => {
    const result = overview()
    expect(result).toMatchObject({
      id: 'PR_1',
      state: 'open',
      mergeable: 'mergeable',
      mergeStateStatus: 'clean',
      reviewDecision: 'approved',
      head: { oid: 'head000', ref: 'feat/widgets', owner: 'alice' },
      requestedReviewers: ['bob', 'core'],
      latestReviews: [{ author: { login: 'carol' }, state: 'approved' }],
      commitsHidden: 0,
      viewer: { permission: 'WRITE', canPush: true, canClose: true },
      merge: { methods: ['merge', 'squash'], defaultMethod: 'squash' },
    })
    expect(result.commits.map(c => [c.oid, c.parentOid, c.checks, c.author.login])).toEqual([
      ['c1', 'base000', 'success', 'alice'],
      ['c2', 'c1', undefined, undefined],
    ])
  })

  it('derives draft and merged states', () => {
    expect(overview({ isDraft: true }).state).toBe('draft')
    expect(overview({ state: 'MERGED', merged: true }).state).toBe('merged')
    expect(overview({ state: 'CLOSED' }).state).toBe('closed')
  })

  it('counts commits and timeline items beyond the loaded page', () => {
    const result = overview({ commits: { ...rawPullRequest().commits, totalCount: 130 }, timelineItems: { totalCount: 150, nodes: [] } })
    expect(result.commitsHidden).toBe(128)
    expect(result.timelineHidden).toBe(150)
  })

  it('returns undefined without a pull request', () => {
    expect(parsePullRequestOverview(rawData(null))).toBeUndefined()
    expect(parsePullRequestOverview({ repository: null })).toBeUndefined()
  })

  it('falls back to the first allowed method when the preferred one is disabled', () => {
    const data = rawData()
    data.repository!.viewerDefaultMergeMethod = 'REBASE'
    expect(parsePullRequestOverview(data)!.merge.defaultMethod).toBe('merge')
  })
})

describe('parseChecks', () => {
  it('sorts failing first, then required, then by name, and counts each bucket', () => {
    const { checks, summary } = parseChecks({
      state: 'FAILURE',
      contexts: {
        totalCount: 7,
        nodes: [
          checkRun('lint', 'COMPLETED', 'SUCCESS'),
          checkRun('test', 'COMPLETED', 'FAILURE'),
          checkRun('build', 'IN_PROGRESS', null),
          checkRun('docs', 'COMPLETED', 'SKIPPED'),
          checkRun('types', 'COMPLETED', 'SUCCESS', { isRequired: true }),
          { __typename: 'StatusContext', id: 's1', context: 'netlify', state: 'ERROR', description: 'Deploy failed', targetUrl: 'https://netlify', createdAt: '2026-01-01T00:00:00Z', isRequired: false },
        ],
      },
    })
    expect(checks.map(c => [c.name, c.bucket])).toEqual([
      ['netlify', 'failing'],
      ['test', 'failing'],
      ['build', 'pending'],
      ['types', 'passing'],
      ['lint', 'passing'],
      ['docs', 'skipped'],
    ])
    expect(checks.find(c => c.name === 'build')).toMatchObject({ conclusion: 'in_progress', group: 'CI' })
    expect(checks.find(c => c.name === 'netlify')).toMatchObject({ summary: 'Deploy failed', url: 'https://netlify' })
    expect(summary).toEqual({ failing: 2, pending: 1, passing: 2, skipped: 1, total: 7, hidden: 1, state: 'failure' })
  })

  it('treats a missing rollup as no checks', () => {
    expect(parseChecks(null)).toEqual({ checks: [], summary: { failing: 0, pending: 0, passing: 0, skipped: 0, total: 0, hidden: 0, state: undefined } })
  })
})

describe('parseTimeline', () => {
  const commitNode = (oid: string, login = 'alice'): RawTimelineNode => ({
    __typename: 'PullRequestCommit',
    id: `PRC_${oid}`,
    url: `https://github.com/o/r/pull/7/commits/${oid}`,
    commit: { oid, abbreviatedOid: oid, messageHeadline: `commit ${oid}`, committedDate: '2026-01-01T00:00:00Z', author: { name: login, user: { login } } },
  })
  const review = (state: string, bodyHTML: string, totalCount = 0): RawTimelineNode => ({
    __typename: 'PullRequestReview',
    id: `R_${state}_${totalCount}`,
    url: 'https://review',
    state,
    submittedAt: '2026-01-03T00:00:00Z',
    createdAt: '2026-01-02T00:00:00Z',
    bodyHTML,
    author: { login: 'carol' },
    comments: { totalCount, nodes: totalCount ? [{ id: 'RC', url: 'https://rc', path: 'a.ts', line: null, originalLine: 4, outdated: true, bodyHTML: '<p>nit</p>', author: { login: 'carol' } }] : [] },
  })

  it('collapses consecutive commits and keeps the rest in order', () => {
    const items = parseTimeline([
      commitNode('a'),
      commitNode('b'),
      { __typename: 'IssueComment', id: 'IC', url: 'https://c', createdAt: '2026-01-02T00:00:00Z', bodyHTML: '<p>hi</p>', isMinimized: true, minimizedReason: 'OUTDATED', author: { login: 'bob' } },
      commitNode('c'),
    ])
    expect(items.map(item => item.kind)).toEqual(['commits', 'comment', 'commits'])
    expect(items[0]).toMatchObject({ actor: { login: 'alice' }, commits: [{ oid: 'a' }, { oid: 'b' }] })
    expect(items[1]).toMatchObject({ minimizedReason: 'outdated', actor: { login: 'bob' } })
  })

  it('drops pending and empty comment-only reviews, keeps inline comment counts', () => {
    const items = parseTimeline([
      review('PENDING', '<p>draft</p>'),
      review('COMMENTED', ''),
      review('COMMENTED', '', 3),
      review('APPROVED', ''),
    ])
    expect(items).toHaveLength(2)
    expect(items[0]).toMatchObject({ kind: 'review', state: 'commented', commentCount: 3, createdAt: '2026-01-03T00:00:00Z', comments: [{ path: 'a.ts', line: 4, outdated: true }] })
    expect(items[1]).toMatchObject({ kind: 'review', state: 'approved' })
  })

  it('maps events with their subjects', () => {
    const base = { createdAt: '2026-01-01T00:00:00Z', actor: { login: 'alice' } }
    const items = parseTimeline([
      { ...base, __typename: 'LabeledEvent', id: 'e1', label: { name: 'bug', color: 'ff0000' } },
      { ...base, __typename: 'ReviewRequestedEvent', id: 'e2', requestedReviewer: { __typename: 'Team', name: 'core' } },
      { ...base, __typename: 'HeadRefForcePushedEvent', id: 'e3', beforeCommit: { abbreviatedOid: 'aaa' }, afterCommit: { abbreviatedOid: 'bbb' } },
      { ...base, __typename: 'RenamedTitleEvent', id: 'e4', previousTitle: 'old', currentTitle: 'new' },
      { ...base, __typename: 'MergedEvent', id: 'e5', commit: { oid: 'ccc111', abbreviatedOid: 'ccc' }, mergeRefName: 'main' },
      JSON.parse('{"__typename":"SomethingNew"}'),
    ])
    expect(items).toEqual([
      { kind: 'event', id: 'e1', createdAt: base.createdAt, actor: { login: 'alice', avatarUrl: undefined }, event: 'labeled', label: { name: 'bug', color: 'ff0000' } },
      expect.objectContaining({ event: 'review_requested', subject: 'core' }),
      expect.objectContaining({ event: 'force_pushed', from: 'aaa', to: 'bbb' }),
      expect.objectContaining({ event: 'renamed', from: 'old', to: 'new' }),
      expect.objectContaining({ event: 'merged', subject: 'ccc', to: 'main' }),
    ])
  })
})

describe('assessMergeability', () => {
  it('hides merging from viewers without push access and on closed PRs', () => {
    const readOnly = overview()
    readOnly.viewer.canPush = false
    expect(assessMergeability(readOnly)).toEqual({ allowed: false, ready: false })
    expect(assessMergeability(overview({ state: 'CLOSED' }))).toEqual({ allowed: false, ready: false })
  })

  it('reports why a merge is blocked', () => {
    expect(assessMergeability(overview())).toEqual({ allowed: true, ready: true })
    expect(assessMergeability(overview({ isDraft: true, mergeStateStatus: 'DRAFT' }))).toMatchObject({ ready: false, blocker: 'draft' })
    expect(assessMergeability(overview({ mergeable: 'CONFLICTING', mergeStateStatus: 'DIRTY' }))).toMatchObject({ ready: false, blocker: 'conflicts' })
    expect(assessMergeability(overview({ mergeStateStatus: 'BEHIND' }))).toMatchObject({ ready: false, blocker: 'behind' })
    expect(assessMergeability(overview({ mergeable: 'UNKNOWN', mergeStateStatus: 'UNKNOWN' }))).toMatchObject({ ready: false, blocker: 'checking' })
    expect(assessMergeability(overview({ mergeStateStatus: 'UNSTABLE' }))).toEqual({ allowed: true, ready: true, warning: 'unstable' })
  })

  it('lets admins merge past branch protection with a warning', () => {
    expect(assessMergeability(overview({ mergeStateStatus: 'BLOCKED', viewerCanMergeAsAdmin: true }))).toEqual({ allowed: true, ready: true, blocker: 'blocked', warning: 'bypass' })
    expect(assessMergeability(overview({ mergeStateStatus: 'BLOCKED' }))).toEqual({ allowed: true, ready: false, blocker: 'blocked' })
  })
})

describe('defaultMergeCommit', () => {
  it('builds squash defaults from commit messages', () => {
    expect(defaultMergeCommit(overview(), 'squash')).toEqual({ title: 'Add widgets (#7)', body: '* first\n\n* second\n\ndetails' })
  })

  it('uses the single commit for COMMIT_OR_PR_TITLE', () => {
    const single = overview({ commits: { totalCount: 1, nodes: [rawPullRequest().commits.nodes[1]!] } })
    expect(defaultMergeCommit(single, 'squash')).toEqual({ title: 'second (#7)', body: 'details' })
  })

  it('builds merge-commit defaults from the repository settings', () => {
    const result = overview()
    expect(defaultMergeCommit(result, 'merge')).toEqual({ title: 'Merge pull request #7 from alice/feat/widgets', body: 'Add widgets' })
    result.merge.mergeTitle = 'PR_TITLE'
    result.merge.mergeMessage = 'PR_BODY'
    expect(defaultMergeCommit(result, 'merge')).toEqual({ title: 'Add widgets (#7)', body: 'Adds widgets.' })
    expect(defaultMergeCommit(result, 'rebase')).toEqual({ title: '', body: '' })
  })
})

describe('fetchPullRequestOverview', () => {
  it('queries by numeric PR number and parses the result', async () => {
    const fetchMock = vi.fn().mockResolvedValue(graphqlResponse(rawData()))
    vi.stubGlobal('fetch', fetchMock)
    const result = await fetchPullRequestOverview(createGithubClient(staticCredentials('token')), 'o', 'r', '7')
    expect(result.number).toBe(7)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body).variables).toEqual({ owner: 'o', repo: 'r', number: 7 })
  })

  it('throws a 404 when the PR is missing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(graphqlResponse(rawData(null))))
    const error = await fetchPullRequestOverview(createGithubClient(staticCredentials('token')), 'o', 'r', 7).catch(err => err)
    expect(error).toBeInstanceOf(GithubApiError)
    expect(error.status).toBe(404)
  })
})

describe('pull request state mutations', () => {
  it('merges with the method uppercased and unset fields left to GitHub', async () => {
    const fetchMock = vi.fn().mockResolvedValue(graphqlResponse({ mergePullRequest: { pullRequest: { id: 'PR_1', state: 'MERGED' } } }))
    vi.stubGlobal('fetch', fetchMock)
    await mergePullRequest(createGithubClient(staticCredentials('token')), { pullRequestId: 'PR_1', method: 'squash', expectedHeadOid: 'head000' })
    const body = JSON.parse(fetchMock.mock.calls[0]![1].body)
    expect(body.query).toContain('mergePullRequest')
    expect(body.variables).toEqual({ id: 'PR_1', method: 'SQUASH', headline: null, body: null, expectedHeadOid: 'head000' })
  })

  it('sends the mutation for each state change', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => graphqlResponse({}))
    vi.stubGlobal('fetch', fetchMock)
    const client = createGithubClient(staticCredentials('token'))
    await changePullRequestState(client, 'PR_1', 'close')
    await changePullRequestState(client, 'PR_1', 'reopen')
    await changePullRequestState(client, 'PR_1', 'ready')
    await changePullRequestState(client, 'PR_1', 'draft')
    await updatePullRequestBranch(client, 'PR_1', { method: 'rebase' })
    const queries = fetchMock.mock.calls.map(call => JSON.parse(call[1].body))
    expect(queries.map(q => q.query.match(/\{ (\w+)\(input/)?.[1] ?? q.query.match(/(updatePullRequestBranch)/)?.[1])).toEqual([
      'closePullRequest',
      'reopenPullRequest',
      'markPullRequestReadyForReview',
      'convertPullRequestToDraft',
      'updatePullRequestBranch',
    ])
    expect(queries[4].variables).toEqual({ id: 'PR_1', method: 'REBASE', expectedHeadOid: null })
  })

  it('edits the description and leaves the title to GitHub when only the body is sent', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => graphqlResponse({ updatePullRequest: { pullRequest: { id: 'PR_1', title: 'T', body: 'New' } } }))
    vi.stubGlobal('fetch', fetchMock)
    const client = createGithubClient(staticCredentials('token'))
    await updatePullRequestText(client, 'PR_1', { body: 'New' })
    await updatePullRequestText(client, 'PR_1', { body: '' })
    const [first, second] = fetchMock.mock.calls.map(call => JSON.parse(call[1].body))
    expect(first.query).toContain('updatePullRequest(')
    expect(first.variables).toEqual({ id: 'PR_1', title: null, body: 'New' })
    // An emptied description is a real edit, not an omitted field.
    expect(second.variables).toEqual({ id: 'PR_1', title: null, body: '' })
  })

  it('turns a forbidden write into the writeForbidden diagnostic', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(graphqlResponse(null, [{ type: 'FORBIDDEN', message: 'Resource not accessible by integration' }])))
    const error = await changePullRequestState(createGithubClient(staticCredentials('token')), 'PR_1', 'close').catch(err => err)
    expect(error).toBeInstanceOf(Diagnostic)
    expect(error.name).toBe('writeForbidden')
  })

  it('surfaces GitHub refusal messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(graphqlResponse(null, [{ message: 'Pull Request is not mergeable' }])))
    await expect(mergePullRequest(createGithubClient(staticCredentials('token')), { pullRequestId: 'PR_1', method: 'merge' })).rejects.toThrow('Pull Request is not mergeable')
  })
})
