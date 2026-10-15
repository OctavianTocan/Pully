import type { ChecksStatus, ReviewDecision } from '../../types/pull-request-list'
import type { GithubClient } from './client'
import type { RawActor, RawCheckContext, RawGitActor, RawOverviewData, RawPullRequest, RawRepositoryOverview, RawRequestedReviewer, RawTimelineNode } from './overview-query'
import { GithubApiError } from './client'
import { PULL_REQUEST_OVERVIEW_QUERY } from './overview-query'

export type PrOverviewState = 'open' | 'draft' | 'closed' | 'merged'
export type MergeMethod = 'merge' | 'squash' | 'rebase'
export type MergeStateStatus = 'behind' | 'blocked' | 'clean' | 'dirty' | 'draft' | 'has_hooks' | 'unknown' | 'unstable'
export type PrReviewState = 'approved' | 'changes_requested' | 'commented' | 'dismissed' | 'pending'
/** How a check reads at a glance; failing sorts first. */
export type CheckBucket = 'failing' | 'pending' | 'passing' | 'skipped'

export interface PrActor {
  login: string
  avatarUrl?: string
}

export interface PrCheck {
  id: string
  name: string
  /** The workflow or app that ran it, when GitHub reports one. */
  group?: string
  bucket: CheckBucket
  /** GitHub's own conclusion (or status while running), lowercased: `success`, `failure`, `in_progress`... */
  conclusion: string
  summary?: string
  url?: string
  startedAt?: string
  completedAt?: string
  required: boolean
}

export interface PrChecksSummary {
  failing: number
  pending: number
  passing: number
  skipped: number
  total: number
  /** Checks GitHub has but the overview did not load. */
  hidden: number
  state?: ChecksStatus
}

export interface PrCommit {
  oid: string
  abbreviatedOid: string
  headline: string
  body: string
  committedDate: string
  url: string
  author: { name?: string, login?: string, avatarUrl?: string }
  /** First parent; the base a single-commit diff compares against. */
  parentOid?: string
  checks?: ChecksStatus
}

interface PrTimelineBase {
  id: string
  createdAt: string
  actor?: PrActor
}

export interface PrTimelineComment extends PrTimelineBase {
  kind: 'comment'
  url: string
  bodyHTML: string
  /** Set when the comment is hidden on GitHub, e.g. `outdated` or `spam`. */
  minimizedReason?: string
}

export interface PrTimelineReviewComment {
  id: string
  url: string
  path: string
  line?: number
  outdated: boolean
  bodyHTML: string
  author?: PrActor
}

export interface PrTimelineReview extends PrTimelineBase {
  kind: 'review'
  url: string
  state: PrReviewState
  bodyHTML: string
  comments: PrTimelineReviewComment[]
  commentCount: number
}

export interface PrTimelineCommit {
  oid: string
  abbreviatedOid: string
  headline: string
  url: string
}

/** Consecutive pushed commits, collapsed into one entry. */
export interface PrTimelineCommits extends PrTimelineBase {
  kind: 'commits'
  commits: PrTimelineCommit[]
}

export type PrEventKind
  = | 'labeled' | 'unlabeled' | 'assigned' | 'unassigned' | 'ready_for_review' | 'convert_to_draft'
    | 'closed' | 'reopened' | 'merged' | 'force_pushed' | 'review_requested' | 'review_dismissed' | 'renamed'

export interface PrTimelineEvent extends PrTimelineBase {
  kind: 'event'
  event: PrEventKind
  label?: { name: string, color: string }
  /** Who or what the event targets: the assignee, requested reviewer, dismissed reviewer or merge commit. */
  subject?: string
  /** Before/after values: force-push shas, renamed titles, or the merge target branch in `to`. */
  from?: string
  to?: string
  message?: string
}

export type PrTimelineItem = PrTimelineComment | PrTimelineReview | PrTimelineCommits | PrTimelineEvent

export interface PrViewerAbilities {
  /** The viewer's repository role (`ADMIN`, `WRITE`, `READ`...); absent for anonymous viewers. */
  permission?: string
  /** Push access, which merging needs. */
  canPush: boolean
  canMergeAsAdmin: boolean
  canClose: boolean
  canReopen: boolean
  /** Edit the PR itself, e.g. toggle draft. */
  canUpdate: boolean
  canUpdateBranch: boolean
  /** Turn on merging once the checks pass; GitHub says no when the repository does not allow auto-merge. */
  canEnableAutoMerge: boolean
  canDisableAutoMerge: boolean
}

export interface PrMergeSettings {
  methods: MergeMethod[]
  defaultMethod: MergeMethod
  squashTitle: RawRepositoryOverview['squashMergeCommitTitle']
  squashMessage: RawRepositoryOverview['squashMergeCommitMessage']
  mergeTitle: RawRepositoryOverview['mergeCommitTitle']
  mergeMessage: RawRepositoryOverview['mergeCommitMessage']
}

/** Everything the PR page shows besides the diff, as one GitHub GraphQL round trip returns it. */
export interface PrOverview {
  /** GraphQL node id, the input every state mutation takes. */
  id: string
  number: number
  title: string
  url: string
  body: string
  bodyHTML: string
  createdAt: string
  /** The pull request's `updatedAt`, the same field the `my-pulls` list carries, to tell whether a stored overview is current. */
  updatedAt?: string
  author?: PrActor
  state: PrOverviewState
  mergedAt?: string
  closedAt?: string
  mergeable: 'mergeable' | 'conflicting' | 'unknown'
  mergeStateStatus: MergeStateStatus
  reviewDecision?: ReviewDecision
  head: { oid: string, ref: string, owner?: string }
  base: { oid: string, ref: string }
  requestedReviewers: string[]
  latestReviews: { author: PrActor, state: PrReviewState }[]
  checks: PrCheck[]
  checksSummary: PrChecksSummary
  /** Oldest first. */
  commits: PrCommit[]
  /** Older commits GitHub has but the overview did not load. */
  commitsHidden: number
  /** Oldest first. */
  timeline: PrTimelineItem[]
  /** Older timeline items GitHub has but the overview did not load. */
  timelineHidden: number
  viewer: PrViewerAbilities
  merge: PrMergeSettings
  /** Present while the pull request is set to merge itself, with this method, once its checks pass. */
  autoMerge?: { method: MergeMethod }
}

const BUCKET_ORDER: CheckBucket[] = ['failing', 'pending', 'passing', 'skipped']
const PUSH_PERMISSIONS = new Set(['ADMIN', 'MAINTAIN', 'WRITE'])

function actor(raw: RawActor | null | undefined): PrActor | undefined {
  return raw?.login ? { login: raw.login, avatarUrl: raw.avatarUrl ?? undefined } : undefined
}

function gitActor(raw: RawGitActor | null | undefined): PrCommit['author'] {
  return { name: raw?.name ?? undefined, login: raw?.user?.login, avatarUrl: raw?.avatarUrl ?? undefined }
}

function reviewerName(raw: RawRequestedReviewer | null | undefined): string | undefined {
  return raw?.login ?? raw?.name
}

/**
 * Where one check run or status lands in the overview's grouping.
 * @param context A check run or commit status from the head commit's rollup.
 * @returns `failing`, `pending`, `passing` or `skipped`.
 */
export function checkBucket(context: RawCheckContext): CheckBucket {
  if (context.__typename === 'StatusContext') {
    if (context.state === 'SUCCESS')
      return 'passing'
    return context.state === 'PENDING' || context.state === 'EXPECTED' ? 'pending' : 'failing'
  }
  if (context.status !== 'COMPLETED')
    return 'pending'
  switch (context.conclusion) {
    case 'SUCCESS':
      return 'passing'
    case 'NEUTRAL':
    case 'SKIPPED':
    case 'STALE':
      return 'skipped'
    default:
      return 'failing'
  }
}

/**
 * GitHub's commit-status rollup as the app's three-state check status.
 * @param state `statusCheckRollup.state` (`SUCCESS`, `FAILURE`, `ERROR`, `PENDING`, `EXPECTED`).
 * @returns The status, or `undefined` when there is no rollup.
 */
export function rollupStatus(state: string | null | undefined): ChecksStatus | undefined {
  switch (state) {
    case 'SUCCESS':
      return 'success'
    case 'FAILURE':
    case 'ERROR':
      return 'failure'
    case 'PENDING':
    case 'EXPECTED':
      return 'pending'
    default:
      return undefined
  }
}

function toCheck(context: RawCheckContext): PrCheck {
  if (context.__typename === 'StatusContext') {
    return {
      id: context.id,
      name: context.context,
      bucket: checkBucket(context),
      conclusion: context.state.toLowerCase(),
      summary: context.description ?? undefined,
      url: context.targetUrl ?? undefined,
      startedAt: context.createdAt,
      required: context.isRequired,
    }
  }
  return {
    id: context.id,
    name: context.name,
    group: context.checkSuite?.workflowRun?.workflow.name ?? context.checkSuite?.app?.name,
    bucket: checkBucket(context),
    conclusion: (context.status === 'COMPLETED' ? context.conclusion ?? 'completed' : context.status).toLowerCase(),
    summary: context.title ?? undefined,
    url: context.detailsUrl ?? undefined,
    startedAt: context.startedAt ?? undefined,
    completedAt: context.completedAt ?? undefined,
    required: context.isRequired,
  }
}

/**
 * The head commit's checks, failing first, with per-bucket counts.
 * @param rollup The head commit's `statusCheckRollup`, `null` when nothing reported.
 * @returns The sorted checks and their summary.
 */
export function parseChecks(rollup: { state: string, contexts: { totalCount: number, nodes: RawCheckContext[] } } | null | undefined): { checks: PrCheck[], summary: PrChecksSummary } {
  const checks = (rollup?.contexts.nodes ?? [])
    .filter(node => node.__typename === 'CheckRun' || node.__typename === 'StatusContext')
    .map(toCheck)
    .sort((a, b) => BUCKET_ORDER.indexOf(a.bucket) - BUCKET_ORDER.indexOf(b.bucket)
      || Number(b.required) - Number(a.required)
      || a.name.localeCompare(b.name))
  const count = (bucket: CheckBucket) => checks.filter(check => check.bucket === bucket).length
  return {
    checks,
    summary: {
      failing: count('failing'),
      pending: count('pending'),
      passing: count('passing'),
      skipped: count('skipped'),
      total: rollup?.contexts.totalCount ?? 0,
      hidden: Math.max(0, (rollup?.contexts.totalCount ?? 0) - checks.length),
      state: rollupStatus(rollup?.state),
    },
  }
}

function reviewState(state: string): PrReviewState {
  return state.toLowerCase() as PrReviewState
}

function toEvent(node: Exclude<RawTimelineNode, { __typename: 'IssueComment' | 'PullRequestReview' | 'PullRequestCommit' }>): PrTimelineEvent | undefined {
  const base = { kind: 'event' as const, id: node.id, createdAt: node.createdAt, actor: actor(node.actor) }
  switch (node.__typename) {
    case 'LabeledEvent':
    case 'UnlabeledEvent':
      return { ...base, event: node.__typename === 'LabeledEvent' ? 'labeled' : 'unlabeled', label: node.label }
    case 'AssignedEvent':
    case 'UnassignedEvent':
      return { ...base, event: node.__typename === 'AssignedEvent' ? 'assigned' : 'unassigned', subject: node.assignee?.login }
    case 'ReadyForReviewEvent':
      return { ...base, event: 'ready_for_review' }
    case 'ConvertToDraftEvent':
      return { ...base, event: 'convert_to_draft' }
    case 'ClosedEvent':
      return { ...base, event: 'closed' }
    case 'ReopenedEvent':
      return { ...base, event: 'reopened' }
    case 'MergedEvent':
      return { ...base, event: 'merged', subject: node.commit?.abbreviatedOid, to: node.mergeRefName }
    case 'HeadRefForcePushedEvent':
      return { ...base, event: 'force_pushed', from: node.beforeCommit?.abbreviatedOid, to: node.afterCommit?.abbreviatedOid }
    case 'ReviewRequestedEvent':
      return { ...base, event: 'review_requested', subject: reviewerName(node.requestedReviewer) }
    case 'ReviewDismissedEvent':
      return { ...base, event: 'review_dismissed', subject: node.review?.author?.login, message: node.dismissalMessage ?? undefined }
    case 'RenamedTitleEvent':
      return { ...base, event: 'renamed', from: node.previousTitle, to: node.currentTitle }
    default:
      return undefined
  }
}

type RawNode<T extends RawTimelineNode['__typename']> = Extract<RawTimelineNode, { __typename: T }>

function toReview(node: RawNode<'PullRequestReview'>): PrTimelineReview | undefined {
  const state = reviewState(node.state)
  if (state === 'pending' || (state === 'commented' && !node.bodyHTML.trim() && node.comments.totalCount === 0))
    return undefined
  return {
    kind: 'review',
    id: node.id,
    url: node.url,
    createdAt: node.submittedAt ?? node.createdAt,
    actor: actor(node.author),
    state,
    bodyHTML: node.bodyHTML,
    commentCount: node.comments.totalCount,
    comments: node.comments.nodes.map(comment => ({
      id: comment.id,
      url: comment.url,
      path: comment.path,
      line: comment.line ?? comment.originalLine ?? undefined,
      outdated: comment.outdated,
      bodyHTML: comment.bodyHTML,
      author: actor(comment.author),
    })),
  }
}

function appendCommit(items: PrTimelineItem[], node: RawNode<'PullRequestCommit'>): void {
  const commit = { oid: node.commit.oid, abbreviatedOid: node.commit.abbreviatedOid, headline: node.commit.messageHeadline, url: node.url }
  const last = items.at(-1)
  if (last?.kind === 'commits') {
    last.commits.push(commit)
    return
  }
  const author = node.commit.author
  const login = author?.user?.login ?? author?.name ?? undefined
  items.push({
    kind: 'commits',
    id: node.id,
    createdAt: node.commit.committedDate,
    actor: login ? { login, avatarUrl: author?.avatarUrl ?? undefined } : undefined,
    commits: [commit],
  })
}

function toItem(node: Exclude<RawTimelineNode, { __typename: 'PullRequestCommit' }>): PrTimelineItem | undefined {
  if (node.__typename === 'PullRequestReview')
    return toReview(node)
  if (node.__typename !== 'IssueComment')
    return toEvent(node)
  return {
    kind: 'comment',
    id: node.id,
    url: node.url,
    createdAt: node.createdAt,
    actor: actor(node.author),
    bodyHTML: node.bodyHTML,
    minimizedReason: node.isMinimized ? (node.minimizedReason ?? 'hidden').toLowerCase() : undefined,
  }
}

/**
 * The PR conversation in order, with runs of pushed commits collapsed and empty or pending reviews dropped.
 * @param nodes `timelineItems.nodes`, oldest first.
 * @returns Timeline entries, oldest first.
 */
export function parseTimeline(nodes: RawTimelineNode[]): PrTimelineItem[] {
  const items: PrTimelineItem[] = []
  for (const node of nodes) {
    if (node.__typename === 'PullRequestCommit') {
      appendCommit(items, node)
      continue
    }
    const item = toItem(node)
    if (item)
      items.push(item)
  }
  return items
}

function prState(pr: RawPullRequest): PrOverviewState {
  if (pr.merged || pr.state === 'MERGED')
    return 'merged'
  if (pr.state === 'CLOSED')
    return 'closed'
  return pr.isDraft ? 'draft' : 'open'
}

function mergeSettings(repo: RawRepositoryOverview): PrMergeSettings {
  const methods: MergeMethod[] = []
  if (repo.mergeCommitAllowed)
    methods.push('merge')
  if (repo.squashMergeAllowed)
    methods.push('squash')
  if (repo.rebaseMergeAllowed)
    methods.push('rebase')
  const preferred = repo.viewerDefaultMergeMethod?.toLowerCase() as MergeMethod | undefined
  return {
    methods,
    defaultMethod: preferred && methods.includes(preferred) ? preferred : methods[0] ?? 'merge',
    squashTitle: repo.squashMergeCommitTitle,
    squashMessage: repo.squashMergeCommitMessage,
    mergeTitle: repo.mergeCommitTitle,
    mergeMessage: repo.mergeCommitMessage,
  }
}

/**
 * Normalizes the overview query's response.
 * @param data The raw `PullRequestOverview` GraphQL data.
 * @returns The overview, or `undefined` when the repository or PR is missing.
 */
export function parsePullRequestOverview(data: RawOverviewData): PrOverview | undefined {
  const repo = data.repository
  const pr = repo?.pullRequest
  if (!repo || !pr)
    return undefined
  const { checks, summary } = parseChecks(pr.headCommit.nodes[0]?.commit.statusCheckRollup)
  const commits = pr.commits.nodes.map(({ commit }): PrCommit => ({
    oid: commit.oid,
    abbreviatedOid: commit.abbreviatedOid,
    headline: commit.messageHeadline,
    body: commit.messageBody,
    committedDate: commit.committedDate,
    url: commit.url,
    author: gitActor(commit.author),
    parentOid: commit.parents.nodes[0]?.oid,
    checks: rollupStatus(commit.statusCheckRollup?.state),
  }))
  return {
    id: pr.id,
    number: pr.number,
    title: pr.title,
    url: pr.url,
    body: pr.body,
    bodyHTML: pr.bodyHTML,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
    author: actor(pr.author),
    state: prState(pr),
    mergedAt: pr.mergedAt ?? undefined,
    closedAt: pr.closedAt ?? undefined,
    mergeable: pr.mergeable.toLowerCase() as PrOverview['mergeable'],
    mergeStateStatus: pr.mergeStateStatus.toLowerCase() as MergeStateStatus,
    reviewDecision: pr.reviewDecision?.toLowerCase() as ReviewDecision | undefined,
    head: { oid: pr.headRefOid, ref: pr.headRefName, owner: pr.headRepositoryOwner?.login },
    base: { oid: pr.baseRefOid, ref: pr.baseRefName },
    requestedReviewers: pr.reviewRequests.nodes.map(node => reviewerName(node.requestedReviewer)).filter((name): name is string => !!name),
    latestReviews: pr.latestOpinionatedReviews.nodes.flatMap(node => node.author ? [{ author: actor(node.author)!, state: reviewState(node.state) }] : []),
    checks,
    checksSummary: summary,
    commits,
    commitsHidden: Math.max(0, pr.commits.totalCount - commits.length),
    timeline: parseTimeline(pr.timelineItems.nodes),
    timelineHidden: Math.max(0, pr.timelineItems.totalCount - pr.timelineItems.nodes.length),
    viewer: {
      permission: repo.viewerPermission ?? undefined,
      canPush: PUSH_PERMISSIONS.has(repo.viewerPermission ?? ''),
      canMergeAsAdmin: pr.viewerCanMergeAsAdmin,
      canClose: pr.viewerCanClose,
      canReopen: pr.viewerCanReopen,
      canUpdate: pr.viewerCanUpdate,
      canUpdateBranch: pr.viewerCanUpdateBranch,
      canEnableAutoMerge: pr.viewerCanEnableAutoMerge ?? false,
      canDisableAutoMerge: pr.viewerCanDisableAutoMerge ?? false,
    },
    autoMerge: pr.autoMergeRequest ? { method: pr.autoMergeRequest.mergeMethod.toLowerCase() as MergeMethod } : undefined,
    merge: mergeSettings(repo),
  }
}

/**
 * Loads a PR's description, conversation, checks, commits and merge state in one GraphQL call.
 * @param client The GitHub client to query with; needs a token (GraphQL rejects anonymous calls).
 * @param owner Repository owner.
 * @param repo Repository name.
 * @param number PR number.
 * @returns The normalized overview.
 * @throws GithubApiError 404 when the PR does not exist.
 */
export async function fetchPullRequestOverview(client: GithubClient, owner: string, repo: string, number: string | number): Promise<PrOverview> {
  const data = await client.graphql<RawOverviewData>(PULL_REQUEST_OVERVIEW_QUERY, { owner, repo, number: Number(number) })
  const overview = parsePullRequestOverview(data)
  if (!overview)
    throw new GithubApiError(404, `Pull request ${owner}/${repo}#${number} not found`)
  return overview
}

export type MergeBlocker = 'draft' | 'conflicts' | 'blocked' | 'behind' | 'checking'

export interface PrMergeability {
  /** Whether the viewer may merge this PR at all; `false` hides the merge action. */
  allowed: boolean
  /** Whether a merge can be attempted right now. */
  ready: boolean
  /** Why GitHub would refuse a regular merge. */
  blocker?: MergeBlocker
  /** `bypass`: only an admin override gets past the blocker. `unstable`: non-required checks are failing. */
  warning?: 'bypass' | 'unstable'
}

/**
 * Whether and how the viewer can merge, from GitHub's merge state.
 * @param overview The PR overview.
 * @returns The viewer's merge ability and, when blocked, why.
 */
export function assessMergeability(overview: Pick<PrOverview, 'state' | 'mergeable' | 'mergeStateStatus' | 'viewer' | 'merge'>): PrMergeability {
  const open = overview.state === 'open' || overview.state === 'draft'
  if (!open || !overview.viewer.canPush || overview.merge.methods.length === 0)
    return { allowed: false, ready: false }
  if (overview.state === 'draft' || overview.mergeStateStatus === 'draft')
    return { allowed: true, ready: false, blocker: 'draft' }
  if (overview.mergeable === 'conflicting' || overview.mergeStateStatus === 'dirty')
    return { allowed: true, ready: false, blocker: 'conflicts' }
  if (overview.mergeStateStatus === 'blocked' || overview.mergeStateStatus === 'behind') {
    const blocker = overview.mergeStateStatus
    return overview.viewer.canMergeAsAdmin
      ? { allowed: true, ready: true, blocker, warning: 'bypass' }
      : { allowed: true, ready: false, blocker }
  }
  if (overview.mergeable === 'unknown' || overview.mergeStateStatus === 'unknown')
    return { allowed: true, ready: false, blocker: 'checking' }
  if (overview.mergeStateStatus === 'unstable')
    return { allowed: true, ready: true, warning: 'unstable' }
  return { allowed: true, ready: true }
}

/**
 * The commit title and message GitHub would prefill for this merge, following the repository's settings.
 * @param overview The PR overview.
 * @param method The merge method; `rebase` has no merge commit and yields empty strings.
 * @returns The default title and body.
 */
export function defaultMergeCommit(overview: MergeCommitSource, method: MergeMethod): { title: string, body: string } {
  if (method === 'squash')
    return defaultSquashCommit(overview)
  if (method === 'rebase')
    return { title: '', body: '' }
  const { merge, number, head } = overview
  const title = merge.mergeTitle === 'PR_TITLE'
    ? `${overview.title} (#${number})`
    : `Merge pull request #${number} from ${head.owner ? `${head.owner}/` : ''}${head.ref}`
  const body = merge.mergeMessage === 'PR_BODY' ? overview.body : merge.mergeMessage === 'PR_TITLE' ? overview.title : ''
  return { title, body }
}

type MergeCommitSource = Pick<PrOverview, 'number' | 'title' | 'body' | 'head' | 'commits' | 'commitsHidden' | 'merge'>

function defaultSquashCommit(overview: MergeCommitSource): { title: string, body: string } {
  const single = overview.commits.length === 1 && overview.commitsHidden === 0 ? overview.commits[0] : undefined
  const title = `${overview.merge.squashTitle === 'COMMIT_OR_PR_TITLE' && single ? single.headline : overview.title} (#${overview.number})`
  if (overview.merge.squashMessage === 'PR_BODY')
    return { title, body: overview.body }
  if (overview.merge.squashMessage === 'BLANK')
    return { title, body: '' }
  const body = single ? single.body : overview.commits.map(c => c.body ? `* ${c.headline}\n\n${c.body}` : `* ${c.headline}`).join('\n\n')
  return { title, body }
}
