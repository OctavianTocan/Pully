import type { MyPull } from '@pulls.review/core/local-rpc'

const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString()

function pull(owner: string, repo: string, number: number, title: string, extra: Partial<MyPull> = {}): MyPull {
  return {
    owner,
    repo,
    number,
    title,
    author: 'aaronjablonski',
    isDraft: false,
    updatedAt: hoursAgo(1),
    commentsCount: 0,
    labels: [],
    state: 'open',
    role: 'review-requested',
    requestedFromMe: true,
    reviewDecision: 'review-required',
    checks: 'success',
    mergeable: 'mergeable',
    additions: 120,
    deletions: 7,
    changedFiles: 4,
    ...extra,
  }
}

/** The inbox the phone's design map shows, for the home page's stories. */
export const MOCK_MY_PULLS: MyPull[] = [
  pull('acme', 'dashboard', 2200, 'fix(dashboard): drive the Ojin widget through its embedder API and show its AI-generated label again (0.1.97)'),
  pull('acme', 'dashboard', 2201, 'luca\'s labs dev branch', { author: 'jorge-journee', additions: 240, deletions: 14, updatedAt: hoursAgo(2) }),
  pull('acme', 'dashboard', 2202, 'docs: add root AGENTS.md with Cursor Cloud setup notes', { author: 'sqrt-negative', additions: 360, deletions: 21, updatedAt: hoursAgo(3), checks: 'failure' }),
  pull('acme', 'dashboard', 2203, 'docs: specify how session recording works, and correct two false claims', { additions: 480, deletions: 28, updatedAt: hoursAgo(4), reviewDecision: 'approved', role: 'authored', requestedFromMe: false }),
  pull('acme', 'ojin', 2204, 'feat: Low / Medium / High reasoning level for Ojin-managed agents', { updatedAt: hoursAgo(1), checks: 'pending' }),
  pull('acme', 'ojin', 2205, 'fix(human-agents-bot): speak the agent\'s own line while a knowledge search runs', { isDraft: true, role: 'authored', requestedFromMe: false, mergeable: 'conflicting', updatedAt: hoursAgo(26) }),
]
