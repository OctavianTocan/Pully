/** How many commits and timeline items one overview fetch carries; older ones are counted, not loaded. */
const OVERVIEW_PAGE_LIMIT = 100

export const PULL_REQUEST_OVERVIEW_QUERY = `
query PullRequestOverview($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    viewerPermission
    mergeCommitAllowed
    squashMergeAllowed
    rebaseMergeAllowed
    viewerDefaultMergeMethod
    squashMergeCommitTitle
    squashMergeCommitMessage
    mergeCommitTitle
    mergeCommitMessage
    pullRequest(number: $number) {
      id
      number
      title
      url
      body
      bodyHTML
      createdAt
      updatedAt
      author { __typename login avatarUrl }
      state
      isDraft
      merged
      mergedAt
      closedAt
      mergeable
      mergeStateStatus
      reviewDecision
      viewerCanUpdate
      viewerDidAuthor
      viewerCanUpdateBranch
      viewerCanClose
      viewerCanReopen
      viewerCanMergeAsAdmin
      viewerCanEnableAutoMerge
      viewerCanDisableAutoMerge
      autoMergeRequest { mergeMethod }
      headRefOid
      headRefName
      baseRefName
      baseRefOid
      headRepositoryOwner { login }
      reviewRequests(first: 20) {
        nodes { requestedReviewer { __typename ... on User { login } ... on Team { name } ... on Bot { login } ... on Mannequin { login } } }
      }
      latestOpinionatedReviews(first: 20) {
        nodes { state author { __typename login avatarUrl } }
      }
      headCommit: commits(last: 1) {
        nodes {
          commit {
            oid
            statusCheckRollup {
              state
              contexts(first: 100) {
                totalCount
                nodes {
                  __typename
                  ... on CheckRun {
                    id
                    name
                    status
                    conclusion
                    detailsUrl
                    startedAt
                    completedAt
                    title
                    isRequired(pullRequestNumber: $number)
                    checkSuite { app { name } workflowRun { workflow { name } } }
                  }
                  ... on StatusContext {
                    id
                    context
                    state
                    description
                    targetUrl
                    createdAt
                    isRequired(pullRequestNumber: $number)
                  }
                }
              }
            }
          }
        }
      }
      commits(last: ${OVERVIEW_PAGE_LIMIT}) {
        totalCount
        nodes {
          commit {
            oid
            abbreviatedOid
            messageHeadline
            messageBody
            committedDate
            url
            author { name avatarUrl user { login } }
            parents(first: 1) { nodes { oid } }
            statusCheckRollup { state }
          }
        }
      }
      timelineItems(last: ${OVERVIEW_PAGE_LIMIT}, itemTypes: [ISSUE_COMMENT, PULL_REQUEST_REVIEW, PULL_REQUEST_COMMIT, LABELED_EVENT, UNLABELED_EVENT, ASSIGNED_EVENT, UNASSIGNED_EVENT, READY_FOR_REVIEW_EVENT, CONVERT_TO_DRAFT_EVENT, CLOSED_EVENT, REOPENED_EVENT, MERGED_EVENT, HEAD_REF_FORCE_PUSHED_EVENT, REVIEW_REQUESTED_EVENT, REVIEW_DISMISSED_EVENT, RENAMED_TITLE_EVENT]) {
        totalCount
        nodes {
          __typename
          ... on IssueComment { id url createdAt bodyHTML isMinimized minimizedReason author { __typename login avatarUrl } }
          ... on PullRequestReview {
            id url state submittedAt createdAt bodyHTML author { __typename login avatarUrl }
            comments(first: 20) { totalCount nodes { id url path line originalLine outdated bodyHTML author { __typename login avatarUrl } } }
          }
          ... on PullRequestCommit { id url commit { oid abbreviatedOid messageHeadline committedDate author { name avatarUrl user { login } } } }
          ... on LabeledEvent { id createdAt actor { __typename login avatarUrl } label { name color } }
          ... on UnlabeledEvent { id createdAt actor { __typename login avatarUrl } label { name color } }
          ... on AssignedEvent { id createdAt actor { __typename login avatarUrl } assignee { ... on Actor { login } } }
          ... on UnassignedEvent { id createdAt actor { __typename login avatarUrl } assignee { ... on Actor { login } } }
          ... on ReadyForReviewEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ConvertToDraftEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ClosedEvent { id createdAt actor { __typename login avatarUrl } }
          ... on ReopenedEvent { id createdAt actor { __typename login avatarUrl } }
          ... on MergedEvent { id createdAt actor { __typename login avatarUrl } commit { oid abbreviatedOid } mergeRefName }
          ... on HeadRefForcePushedEvent { id createdAt actor { __typename login avatarUrl } beforeCommit { abbreviatedOid } afterCommit { abbreviatedOid } }
          ... on ReviewRequestedEvent { id createdAt actor { __typename login avatarUrl } requestedReviewer { __typename ... on User { login } ... on Team { name } ... on Bot { login } ... on Mannequin { login } } }
          ... on ReviewDismissedEvent { id createdAt actor { __typename login avatarUrl } dismissalMessage review { author { login } } }
          ... on RenamedTitleEvent { id createdAt actor { __typename login avatarUrl } previousTitle currentTitle }
        }
      }
    }
  }
}`

export interface RawActor {
  /** GraphQL's type for the account: `User`, `Bot` (a GitHub App), `Mannequin`, `Organization`. Absent from answers stored before it was asked for. */
  __typename?: string
  login: string
  avatarUrl?: string
}

export interface RawGitActor {
  name?: string | null
  avatarUrl?: string | null
  user?: { login: string } | null
}

export interface RawCheckRun {
  __typename: 'CheckRun'
  id: string
  name: string
  status: string
  conclusion: string | null
  detailsUrl: string | null
  startedAt: string | null
  completedAt: string | null
  title: string | null
  isRequired: boolean
  checkSuite: { app: { name: string } | null, workflowRun: { workflow: { name: string } } | null } | null
}

export interface RawStatusContext {
  __typename: 'StatusContext'
  id: string
  context: string
  state: string
  description: string | null
  targetUrl: string | null
  createdAt: string
  isRequired: boolean
}

export type RawCheckContext = RawCheckRun | RawStatusContext

export interface RawRequestedReviewer {
  __typename: string
  login?: string
  name?: string
}

interface RawEventBase {
  id: string
  createdAt: string
  actor: RawActor | null
}

export type RawTimelineNode
  = | { __typename: 'IssueComment', id: string, url: string, createdAt: string, bodyHTML: string, isMinimized: boolean, minimizedReason: string | null, author: RawActor | null }
    | {
      __typename: 'PullRequestReview'
      id: string
      url: string
      state: string
      submittedAt: string | null
      createdAt: string
      bodyHTML: string
      author: RawActor | null
      comments: {
        totalCount: number
        nodes: { id: string, url: string, path: string, line: number | null, originalLine: number | null, outdated: boolean, bodyHTML: string, author: RawActor | null }[]
      }
    }
    | { __typename: 'PullRequestCommit', id: string, url: string, commit: { oid: string, abbreviatedOid: string, messageHeadline: string, committedDate: string, author: RawGitActor | null } }
    | (RawEventBase & { __typename: 'LabeledEvent' | 'UnlabeledEvent', label: { name: string, color: string } })
    | (RawEventBase & { __typename: 'AssignedEvent' | 'UnassignedEvent', assignee: { login?: string } | null })
    | (RawEventBase & { __typename: 'ReadyForReviewEvent' | 'ConvertToDraftEvent' | 'ClosedEvent' | 'ReopenedEvent' })
    | (RawEventBase & { __typename: 'MergedEvent', commit: { oid: string, abbreviatedOid: string } | null, mergeRefName: string })
    | (RawEventBase & { __typename: 'HeadRefForcePushedEvent', beforeCommit: { abbreviatedOid: string } | null, afterCommit: { abbreviatedOid: string } | null })
    | (RawEventBase & { __typename: 'ReviewRequestedEvent', requestedReviewer: RawRequestedReviewer | null })
    | (RawEventBase & { __typename: 'ReviewDismissedEvent', dismissalMessage: string | null, review: { author: { login: string } | null } | null })
    | (RawEventBase & { __typename: 'RenamedTitleEvent', previousTitle: string, currentTitle: string })

export interface RawPullRequest {
  id: string
  number: number
  title: string
  url: string
  body: string
  bodyHTML: string
  createdAt: string
  /** Absent only in fixtures from before the field was queried. */
  updatedAt?: string
  author: RawActor | null
  state: 'OPEN' | 'CLOSED' | 'MERGED'
  isDraft: boolean
  merged: boolean
  mergedAt: string | null
  closedAt: string | null
  mergeable: 'MERGEABLE' | 'CONFLICTING' | 'UNKNOWN'
  mergeStateStatus: string
  reviewDecision: 'APPROVED' | 'CHANGES_REQUESTED' | 'REVIEW_REQUIRED' | null
  viewerCanUpdate: boolean
  /** Absent from answers cached before it was asked for. */
  viewerDidAuthor?: boolean
  viewerCanUpdateBranch: boolean
  viewerCanClose: boolean
  viewerCanReopen: boolean
  viewerCanMergeAsAdmin: boolean
  viewerCanEnableAutoMerge?: boolean
  viewerCanDisableAutoMerge?: boolean
  /** Set while the pull request waits to merge itself once its checks pass. */
  autoMergeRequest?: { mergeMethod: string } | null
  headRefOid: string
  headRefName: string
  baseRefName: string
  baseRefOid: string
  headRepositoryOwner: { login: string } | null
  reviewRequests: { nodes: { requestedReviewer: RawRequestedReviewer | null }[] }
  latestOpinionatedReviews: { nodes: { state: string, author: RawActor | null }[] }
  headCommit: { nodes: { commit: { oid: string, statusCheckRollup: { state: string, contexts: { totalCount: number, nodes: RawCheckContext[] } } | null } }[] }
  commits: {
    totalCount: number
    nodes: {
      commit: {
        oid: string
        abbreviatedOid: string
        messageHeadline: string
        messageBody: string
        committedDate: string
        url: string
        author: RawGitActor | null
        parents: { nodes: { oid: string }[] }
        statusCheckRollup: { state: string } | null
      }
    }[]
  }
  timelineItems: { totalCount: number, nodes: RawTimelineNode[] }
}

export interface RawRepositoryOverview {
  viewerPermission: string | null
  mergeCommitAllowed: boolean
  squashMergeAllowed: boolean
  rebaseMergeAllowed: boolean
  viewerDefaultMergeMethod: 'MERGE' | 'SQUASH' | 'REBASE'
  squashMergeCommitTitle: 'PR_TITLE' | 'COMMIT_OR_PR_TITLE'
  squashMergeCommitMessage: 'PR_BODY' | 'COMMIT_MESSAGES' | 'BLANK'
  mergeCommitTitle: 'PR_TITLE' | 'MERGE_MESSAGE'
  mergeCommitMessage: 'PR_BODY' | 'PR_TITLE' | 'BLANK'
  pullRequest: RawPullRequest | null
}

export interface RawOverviewData {
  repository: RawRepositoryOverview | null
}

/**
 * A pull request's title and description alone: a small query that answers well before the overview's, so a page
 * showing a kept overview can put an edited description in place at once.
 */
export const PULL_REQUEST_TEXT_QUERY = `
query PullRequestText($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      title
      body
      bodyHTML
      updatedAt
    }
  }
}`

export interface RawTextData {
  repository: { pullRequest: { title: string, body: string, bodyHTML: string, updatedAt: string } | null } | null
}
