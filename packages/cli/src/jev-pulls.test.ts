import type { MyPull } from '@pulls.review/core/local-rpc'
import type { PullClassifierStore } from './jev-pulls'
import { createCacheRepositories } from '@pulls.review/core/cache'
import { serializeRef } from '@pulls.review/core/types'
import { createStorage } from 'unstorage'
import memoryDriver from 'unstorage/drivers/memory'
import { describe, expect, it, vi } from 'vitest'
import { createJevClient } from './jev'
import { createPullClassifier, PULL_QUESTIONS, pullState } from './jev-pulls'

function pull(number: number, overrides: Partial<MyPull> = {}): MyPull {
  return {
    owner: 'acme',
    repo: 'app',
    number,
    title: `Fix the crash ${number}`,
    author: 'octo',
    isDraft: false,
    updatedAt: '2026-10-01T00:00:00Z',
    commentsCount: 0,
    labels: ['bug'],
    state: 'open',
    role: 'authored',
    headSha: `sha-${number}`,
    ...overrides,
  }
}

function answer(kind = 'fix', confidence = 0.9, risk = 1.4) {
  return {
    model: 'jev-1.13.0',
    answers: {
      kind: { type: 'choice', choice: kind, probabilities: { [kind]: confidence }, confidence },
      risk: { type: 'score', score: risk, legend: { 0: 'trivial', 1: 'low', 2: 'medium', 3: 'high' }, probabilities: { 1: 0.6, 2: 0.4 }, confidence: 0.6 },
    },
    usage: { input_tokens: 80, output_tokens: 0 },
  }
}

/** A classifier over a TypeSafe stand-in that records each state it is asked about. */
function setup(options: { store?: PullClassifierStore, respond?: () => Response, now?: () => number } = {}) {
  const states: unknown[] = []
  const fetch = (async (_url: string, init: RequestInit) => {
    states.push(JSON.parse(init.body as string).state)
    return options.respond?.() ?? Response.json(answer())
  }) as typeof globalThis.fetch
  const jev = createJevClient({ apiKey: 'key', fetch, log: () => {}, sleep: async () => {} })
  const store = options.store ?? { storage: createStorage({ driver: memoryDriver() }) }
  return { classifier: createPullClassifier({ jev, store: () => store, now: options.now }), states, store }
}

describe('pullState', () => {
  it('describes the pull request, with at most 80 changed paths', () => {
    const paths = Array.from({ length: 90 }, (_, i) => `src/${i}.ts`)
    expect(pullState(pull(1, { headRef: 'fix/crash' }), paths)).toEqual({
      repository: 'acme/app',
      title: 'Fix the crash 1',
      labels: ['bug'],
      branch: 'fix/crash',
      author: 'octo',
      files: paths.slice(0, 80),
    })
    expect(pullState(pull(1), undefined)).not.toHaveProperty('files')
  })

  it('asks for a kind out of eight and a risk on four levels', () => {
    expect(Object.keys(PULL_QUESTIONS.kind.criteria)).toEqual(['feature', 'fix', 'refactor', 'performance', 'docs', 'tests', 'dependencies', 'infra'])
    expect(PULL_QUESTIONS.risk.criteria).toHaveLength(4)
  })
})

describe('createPullClassifier', () => {
  it('classifies each pull request once per head and title', async () => {
    const { classifier, states } = setup()
    await classifier.classify([pull(1, { headRef: 'fix/crash' }), pull(2)])
    expect(states).toHaveLength(2)
    expect(classifier.apply([pull(1)])[0].jev).toEqual({ kind: 'fix', kindConfidence: 0.9, risk: 1.4 })

    await classifier.classify([pull(1), pull(2)])
    expect(states).toHaveLength(2)

    // A new commit or a new title is a different pull request to classify.
    expect(classifier.apply([pull(1, { headSha: 'sha-new' })])[0].jev).toBeUndefined()
    await classifier.classify([pull(1, { headSha: 'sha-new' }), pull(2, { title: 'Add dark mode' })])
    expect(states).toHaveLength(4)
  })

  it('leaves closed pull requests alone, and gives a closed one no kind from when it was open', async () => {
    const { classifier, states } = setup()
    await classifier.classify([pull(1, { state: 'closed' })])
    expect(states).toHaveLength(0)

    await classifier.classify([pull(2)])
    expect(classifier.apply([pull(2)])[0].jev).toBeDefined()
    expect(classifier.apply([pull(2, { state: 'closed' })])[0].jev).toBeUndefined()
  })

  it('asks about a new head while Jev still has the old one, and never lets the older answer win', async () => {
    const held: ((response: Response) => void)[] = []
    const fetch = (async () => new Promise<Response>(resolve => held.push(resolve))) as typeof globalThis.fetch
    const jev = createJevClient({ apiKey: 'key', fetch, log: () => {}, sleep: async () => {} })
    const classifier = createPullClassifier({ jev, store: () => ({ storage: createStorage({ driver: memoryDriver() }) }) })
    const moved = pull(1, { headSha: 'sha-new' })

    const old = classifier.classify([pull(1)])
    await vi.waitFor(() => expect(held).toHaveLength(1))
    const current = classifier.classify([moved])
    await vi.waitFor(() => expect(held).toHaveLength(2))

    held[1]!(Response.json(answer('feature')))
    await current
    held[0]!(Response.json(answer('fix')))
    await old
    expect(classifier.apply([moved])[0].jev?.kind).toBe('feature')
  })

  it('reads what an earlier run saved instead of asking again', async () => {
    const first = setup()
    await first.classifier.classify([pull(1)])
    expect(first.states).toHaveLength(1)

    const restarted = setup({ store: first.store })
    expect(restarted.classifier.apply([pull(1)])[0].jev).toBeUndefined()
    await restarted.classifier.load([pull(1)])
    expect(restarted.classifier.apply([pull(1)])[0].jev).toEqual({ kind: 'fix', kindConfidence: 0.9, risk: 1.4 })
    await restarted.classifier.classify([pull(1)])
    expect(restarted.states).toHaveLength(0)
  })

  it('sends the changed paths of a diff cached at the same head', async () => {
    const storage = createStorage({ driver: memoryDriver() })
    const { diffs } = createCacheRepositories(storage)
    const cache = (number: number, headSha: string) => {
      const ref = { kind: 'github-pr' as const, owner: 'acme', repo: 'app', number: String(number) }
      const files = [`src/${number}.ts`, 'README.md'].map(path => ({ path, status: 'modified' as const, additions: 1, deletions: 0, isBinary: false, sha: path, hunks: [] }))
      return diffs.putDiff(serializeRef(ref), { ref, title: `Fix the crash ${number}`, files }, headSha)
    }
    await cache(1, 'sha-1')
    await cache(2, 'sha-old')
    const { classifier, states } = setup({ store: { storage, diffs } })
    await classifier.classify([pull(1), pull(2)])
    expect(states).toContainEqual(expect.objectContaining({ title: 'Fix the crash 1', files: ['src/1.ts', 'README.md'] }))
    expect(states).toContainEqual(expect.not.objectContaining({ files: expect.anything() }))
    expect(states).toHaveLength(2)
  })

  it('tries a failed pull request again only after a while', async () => {
    let clock = 0
    let fail = true
    const { classifier, states } = setup({ now: () => clock, respond: () => fail ? Response.json({ error: 'bad state' }, { status: 400 }) : Response.json(answer()) })
    await classifier.classify([pull(1)])
    await classifier.classify([pull(1)])
    expect(states).toHaveLength(1)
    expect(classifier.apply([pull(1)])[0].jev).toBeUndefined()

    fail = false
    clock = 11 * 60_000
    await classifier.classify([pull(1)])
    expect(states).toHaveLength(2)
    expect(classifier.apply([pull(1)])[0].jev?.kind).toBe('fix')
  })

  it('asks about a new head at once, even while the one before it waits out a failure', async () => {
    let fail = true
    const { classifier, states } = setup({ now: () => 0, respond: () => fail ? Response.json({ error: 'bad state' }, { status: 400 }) : Response.json(answer()) })
    await classifier.classify([pull(1)])
    fail = false
    const pushed = pull(1, { headSha: 'sha-1b' })
    await classifier.classify([pushed])
    expect(states).toHaveLength(2)
    expect(classifier.apply([pushed])[0].jev?.kind).toBe('fix')
  })

  it('keeps the risk on its four levels', async () => {
    const { classifier } = setup({ respond: () => Response.json(answer('feature', 0.4, 3.2)) })
    await classifier.classify([pull(1)])
    expect(classifier.apply([pull(1)])[0].jev).toEqual({ kind: 'feature', kindConfidence: 0.4, risk: 3 })
  })
})
