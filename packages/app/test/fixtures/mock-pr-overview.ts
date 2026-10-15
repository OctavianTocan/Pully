import type { PrOverview } from '@pulls.review/core/github'
import type { PrOverviewStore } from '../../src/stores/pr-overview-store'
import { assessMergeability } from '@pulls.review/core/github'
import { computed, reactive, ref } from 'vue'

const actor = { login: 'aaronjablonski' }
const at = '2026-10-14T09:00:00Z'

/** A pull request's overview as the PR page's stories show it. */
export function mockPrOverview(patch: Partial<PrOverview> = {}): PrOverview {
  return {
    id: 'PR_1',
    number: 695,
    title: 'fix(dashboard): drive the Ojin widget through its embedder API',
    url: 'https://github.com/acme/dashboard/pull/695',
    body: '',
    bodyHTML: '<p>Session pages stop mounting the Ojin widget themselves and hand its controls to the embedder, so the label comes back after a reload.</p>',
    createdAt: at,
    author: actor,
    state: 'open',
    mergeable: 'mergeable',
    mergeStateStatus: 'clean',
    reviewDecision: 'review_required' as PrOverview['reviewDecision'],
    head: { oid: 'head', ref: 'chore/ojin-agent' },
    base: { oid: 'base', ref: 'main' },
    requestedReviewers: [],
    latestReviews: [],
    checks: [
      { id: 'k1', name: 'lint', bucket: 'passing', conclusion: 'success', required: false },
      { id: 'k2', name: 'test', group: 'CI', bucket: 'failing', conclusion: 'failure', summary: '2 failed', url: 'https://github.com/acme/dashboard/actions/runs/1', required: true },
    ],
    checksSummary: { failing: 1, pending: 0, passing: 1, skipped: 0, total: 2, hidden: 0, state: 'failure' },
    commits: [{ oid: 'aaaaaaa1111111111', abbreviatedOid: 'aaaaaaa', headline: 'fix(dashboard): drive the widget through the embedder', body: '', committedDate: at, url: '', author: actor }],
    commitsHidden: 0,
    timeline: [
      { createdAt: at, actor, id: 'c1', kind: 'comment', url: 'https://github.com/acme/dashboard/pull/695#c1', bodyHTML: '<p>The gate waits for the embedder before it mounts.</p>' },
    ],
    timelineHidden: 0,
    viewer: { canPush: true, canMergeAsAdmin: false, canClose: true, canReopen: true, canUpdate: true, canUpdateBranch: false, canEnableAutoMerge: false, canDisableAutoMerge: false },
    merge: { methods: ['merge', 'squash'], defaultMethod: 'squash', squashTitle: 'PR_TITLE', squashMessage: 'COMMIT_MESSAGES', mergeTitle: 'MERGE_MESSAGE', mergeMessage: 'PR_TITLE' },
    ...patch,
  }
}

/** An in-memory overview store: actions resolve without a network. */
export function createMockPrOverviewStore(value: PrOverview = mockPrOverview()): PrOverviewStore {
  const current = ref(value)
  const actionError = ref<string>()
  return reactive({
    owner: 'acme',
    repo: 'dashboard',
    number: value.number,
    overview: current,
    available: true,
    isLoading: false,
    error: undefined,
    mergeability: computed(() => assessMergeability(current.value)),
    busy: undefined,
    actionError,
    load: async () => {},
    refresh: async () => {},
    merge: async () => true,
    changeState: async () => true,
    updateBranch: async () => true,
    clearActionError: () => {
      actionError.value = undefined
    },
  })
}
