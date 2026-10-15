import type { PrOverview, PrTimelineItem } from '@pulls.review/core/github'
import type { PrOverviewStore } from '../../stores/pr-overview-store'
import type { CommitSelection } from './commit-selection'
import { assessMergeability } from '@pulls.review/core/github'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { computed, nextTick, reactive, ref } from 'vue'
import { i18n } from '../../i18n'
import { selectCommit } from './commit-selection'
import { createPrOverviewContext, prOverviewKey } from './context'
import PrOverviewPanel from './PrOverviewPanel.vue'

const actor = { login: 'octo' }
const base = { createdAt: '2026-01-02T00:00:00Z', actor }

const timeline: PrTimelineItem[] = [
  { ...base, id: 'c1', kind: 'comment', url: 'https://github.com/o/r/pull/7#c1', bodyHTML: '<p>Looks <strong>good</strong></p>' },
  { ...base, id: 'k1', kind: 'commits', commits: [{ oid: 'bbbbbbb2222222222', abbreviatedOid: 'bbbbbbb', headline: 'Second commit', url: '' }] },
  { ...base, id: 'r1', kind: 'review', url: '', state: 'approved', bodyHTML: '', comments: [], commentCount: 0 },
  { ...base, id: 'e1', kind: 'event', event: 'labeled', label: { name: 'bug', color: 'd73a4a' } },
  { ...base, id: 'e2', kind: 'event', event: 'merged', subject: 'abc1234', to: 'main' },
  { ...base, id: 'e3', kind: 'event', event: 'renamed', from: 'Old', to: 'New' },
  { ...base, id: 'e4', kind: 'event', event: 'review_requested', subject: 'hubot' },
]

function overview(): PrOverview {
  return {
    id: 'PR_1',
    number: 7,
    title: 'Add things',
    url: 'https://github.com/o/r/pull/7',
    body: 'Body',
    bodyHTML: '<p>The <em>description</em></p>',
    createdAt: '2026-01-01T00:00:00Z',
    author: actor,
    state: 'open',
    mergeable: 'mergeable',
    mergeStateStatus: 'clean',
    reviewDecision: 'approved',
    head: { oid: 'head', ref: 'feature' },
    base: { oid: 'base', ref: 'main' },
    requestedReviewers: [],
    latestReviews: [{ author: actor, state: 'approved' }],
    checks: [
      { id: 'k1', name: 'lint', bucket: 'passing', conclusion: 'success', required: false },
      { id: 'k2', name: 'test', group: 'CI', bucket: 'failing', conclusion: 'failure', summary: '2 failed', url: 'https://github.com/o/r/actions/runs/1', required: true },
    ],
    checksSummary: { failing: 1, pending: 0, passing: 1, skipped: 0, total: 2, hidden: 0, state: 'failure' },
    commits: ['aaaaaaa1111111111', 'bbbbbbb2222222222'].map((oid, i) => ({ oid, abbreviatedOid: oid.slice(0, 7), headline: `Commit ${i + 1}`, body: '', committedDate: '2026-01-01T00:00:00Z', url: '', author: { login: 'octo' } })),
    commitsHidden: 0,
    timeline,
    timelineHidden: 0,
    viewer: { canPush: true, canMergeAsAdmin: false, canClose: true, canReopen: true, canUpdate: true, isAuthor: true, canUpdateBranch: false, canEnableAutoMerge: false, canDisableAutoMerge: false },
    merge: { methods: ['merge', 'squash'], defaultMethod: 'squash', squashTitle: 'PR_TITLE', squashMessage: 'COMMIT_MESSAGES', mergeTitle: 'MERGE_MESSAGE', mergeMessage: 'PR_TITLE' },
  }
}

function fakeStore(value: PrOverview): PrOverviewStore {
  const current = ref(value)
  const actionError = ref<string>()
  return reactive({
    owner: 'o',
    repo: 'r',
    number: 7,
    overview: current,
    available: true,
    isLoading: false,
    error: undefined,
    mergeability: computed(() => assessMergeability(current.value)),
    busy: undefined,
    actionError,
    load: vi.fn(),
    refresh: vi.fn(),
    merge: vi.fn(async () => true),
    changeState: vi.fn(async () => {
      actionError.value = 'Resource not accessible by integration'
      return false
    }),
    updateBranch: vi.fn(async () => true),
    clearActionError: vi.fn(() => {
      actionError.value = undefined
    }),
  })
}

let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => {
  wrapper?.unmount()
  localStorage.clear()
})

function mountPanel() {
  const store = fakeStore(overview())
  const select = vi.fn<(selection: CommitSelection | undefined) => void>()
  const ctx = createPrOverviewContext(store, () => undefined, select)
  ctx.setOpen(true)
  ctx.setTab('conversation')
  wrapper = mount(PrOverviewPanel, {
    global: { plugins: [i18n], provide: { [prOverviewKey]: ctx } },
    attachTo: document.body,
  })
  return { store, select, ctx }
}

function button(label: string) {
  const match = [...document.body.querySelectorAll('button')].find(el => el.textContent?.trim() === label || el.getAttribute('aria-label') === label)
  if (!match)
    throw new Error(`no button "${label}"`)
  return match
}

describe('prOverviewPanel', () => {
  it('renders the description and every kind of timeline entry', () => {
    mountPanel()
    const text = document.body.textContent!.replace(/\s+/g, ' ')

    expect(document.body.querySelector('em')?.textContent).toBe('description')
    expect(text).toContain('Looks good')
    expect(text).toContain('added 1 commit')
    expect(text).toContain('approved these changes')
    expect(text).toContain('added bug')
    expect(text).toContain('merged commit abc1234 into main')
    expect(text).toContain('changed the title from Old to New')
    expect(text).toContain('requested a review from hubot')
    expect(text).toContain('Ready to merge')
  })

  it('narrows the diff to a commit picked from the timeline', async () => {
    const { select } = mountPanel()

    button('Second commit').click()
    await nextTick()

    expect(select).toHaveBeenCalledWith(selectCommit('bbbbbbb2222222222'))
  })

  it('lists failing checks first and folds the passing ones away', async () => {
    const { ctx } = mountPanel()
    ctx.setTab('checks')
    await nextTick()

    const rows = [...document.body.querySelectorAll('li')].map(li => li.textContent!.replace(/\s+/g, ' ')).filter(text => /test|lint/.test(text))
    expect(rows).toHaveLength(1)
    expect(rows[0]).toContain('CI / test')
    expect(rows[0]).toContain('Required')
    expect(document.body.textContent).toContain('1 successful')

    button('1 successful').click()
    await nextTick()
    expect(document.body.textContent).toContain('lint')
  })

  it('merges with the chosen method after confirming', async () => {
    const { store } = mountPanel()

    button('Merge').click()
    await nextTick()
    button('Confirm merge').click()
    await nextTick()

    expect(store.merge).toHaveBeenCalledWith({ method: 'squash', title: undefined, body: undefined })
  })

  it('shows why GitHub refused an action', async () => {
    const { store } = mountPanel()

    button('Close pull request').click()
    await vi.waitFor(() => expect(document.body.querySelector('[role="alert"]')?.textContent).toContain('Resource not accessible by integration'))
    expect(store.changeState).toHaveBeenCalledWith('close')
  })
})
