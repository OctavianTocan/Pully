import type { DiffCache } from '@pulls.review/core/cache'
import type { MyPull, MyPullJev } from '@pulls.review/core/local-rpc'
import type { Storage } from 'unstorage'
import type { JevChoiceQuestion, JevClient, JevScoreQuestion } from './jev'
import { MY_PULL_KINDS } from '@pulls.review/core/local-rpc'
import { serializeRef } from '@pulls.review/core/types'

const STORAGE_PREFIX = 'jev-pr:'
/** Changed paths Jev sees at most; enough to tell what a pull request touches. */
const MAX_FILES = 80
/** A pull request Jev could not classify is tried again after this long, not on every list. */
const RETRY_FAILED_MS = 10 * 60_000

const KIND_CRITERIA: Record<typeof MY_PULL_KINDS[number], string> = {
  feature: 'Adds a capability users or callers can see: a new screen, command, option, endpoint, API or behaviour. Tests or refactoring done along the way do not change this.',
  fix: 'Corrects a bug: behaviour that was wrong, crashed, regressed or did not do what it was meant to. Titles often say fix, bug, crash, broken or regression.',
  refactor: 'Restructures, renames, moves or cleans up code without changing what it does for users or callers.',
  performance: 'Makes existing behaviour faster or lighter (speed, memory, bundle size, fewer requests) without changing what it does.',
  docs: 'Only documentation: READMEs, guides, code comments, docstrings, changelogs or examples. Nothing about how the code runs changes.',
  tests: 'Only tests: adds or changes test cases, fixtures, snapshots or test helpers, not the code under test.',
  dependencies: 'Bumps, adds or removes third-party dependencies: package versions and lockfiles, often opened by Renovate or Dependabot.',
  infra: 'Build, CI, tooling, configuration or release work: workflows, scripts, linters, bundler or compiler settings, containers, deployment, version tags.',
}

export const PULL_QUESTIONS = {
  kind: {
    type: 'choice',
    instructions: 'What kind of change is this pull request? Judge from the title, labels, branch name and changed files together. When it does several things, pick its main purpose.',
    criteria: KIND_CRITERIA,
  } satisfies JevChoiceQuestion,
  risk: {
    type: 'score',
    instructions: 'How risky is merging this pull request: how likely is it to break something, and how badly?',
    criteria: [
      'trivial: cannot break anything (typos, comments, docs, formatting)',
      'low: small and contained; a mistake would stay in one place and be easy to spot',
      'medium: touches shared code or behaviour that other parts depend on',
      'high: wide, risky or security-sensitive (auth, permissions, data migrations, money, concurrency, many files)',
    ],
  } satisfies JevScoreQuestion,
}

/** Where classifications persist, and the cached diffs whose paths sharpen them. */
export interface PullClassifierStore {
  storage?: Storage
  diffs?: Pick<DiffCache, 'listMatching'>
}

export interface PullClassifier {
  /** Jev is configured and has not been refused during this run. */
  enabled: () => boolean
  /** Reads what an earlier run saved for these pull requests. Local storage only, never Jev. */
  load: (pulls: MyPull[]) => Promise<void>
  /** Copies of `pulls`, each with `jev` set when a classification of its current head and title exists. */
  apply: (pulls: MyPull[]) => MyPull[]
  /** Classifies the open ones without a current classification. Settles when they are done; the list never waits for it. */
  classify: (pulls: MyPull[]) => Promise<void>
  /**
   * @param pull The pull request at the head and title in question.
   * @returns Its classification there, from memory or the server's storage; `undefined` when there is none. Never asks Jev.
   */
  lookup: (pull: Pick<MyPull, 'owner' | 'repo' | 'number' | 'headSha' | 'title'>) => Promise<MyPullJev | undefined>
}

export interface PullClassifierOptions {
  jev: JevClient
  /** Read on every use: the server session attaches its storage after the service exists. */
  store?: () => PullClassifierStore | undefined
  now?: () => number
}

interface Saved extends MyPullJev {
  headSha: string
  title: string
  /** The Jev release that answered, such as `jev-1.13.0`. */
  model: string
}

function pullKey(pull: Pick<MyPull, 'owner' | 'repo' | 'number'>): string {
  return `${pull.owner}/${pull.repo}#${pull.number}`
}

function isSaved(value: unknown): value is Saved {
  const saved = value as Saved | null | undefined
  return typeof saved?.headSha === 'string' && typeof saved.title === 'string' && typeof saved.kind === 'string'
    && typeof saved.kindConfidence === 'number' && typeof saved.risk === 'number'
}

function isCurrent(saved: Saved | undefined, pull: Pick<MyPull, 'headSha' | 'title'>): saved is Saved {
  return !!saved && saved.headSha === (pull.headSha ?? '') && saved.title === pull.title
}

/** What a classification belongs to: the pull request at one head and title. */
function versionKey(pull: MyPull): string {
  return `${pullKey(pull)}@${pull.headSha ?? ''}\n${pull.title}`
}

/**
 * @param pull The pull request.
 * @param files Its changed paths, when the server has its diff.
 * @returns What Jev is asked about.
 */
export function pullState(pull: MyPull, files: string[] | undefined) {
  return {
    repository: `${pull.owner}/${pull.repo}`,
    title: pull.title,
    labels: pull.labels,
    branch: pull.headRef ?? '',
    author: pull.author,
    ...(files ? { files: files.slice(0, MAX_FILES) } : {}),
  }
}

/**
 * Sorts pull requests by kind and risk with Jev, when asked to: a review of the pull request, or a sort or grouping
 * on the phone that needs every open one. A classification belongs to a head commit
 * and a title, so a new commit or a renamed title asks again; it is kept in memory and in
 * the server's storage (`jev-pr:<owner>/<repo>#<number>`), so a restart does not pay twice.
 *
 * @param options The Jev client and where to keep results.
 * @returns The classifier `createMyPullsService` uses.
 */
export function createPullClassifier(options: PullClassifierOptions): PullClassifier {
  const { jev, store = () => undefined, now = Date.now } = options
  const saved = new Map<string, Saved>()
  /** Pull requests whose storage was already read, found or not. */
  const looked = new Set<string>()
  /** Versions ({@link versionKey}) being classified now. */
  const running = new Set<string>()
  /** Each pull request's newest version handed to Jev, so an answer for an older one is dropped. */
  const latest = new Map<string, string>()
  /** The version that last failed, per pull request: a newer head or title is asked about at once. */
  const failed = new Map<string, { version: string, at: number }>()

  /** Changed paths of the pull requests whose diff the server holds at their current head. */
  async function cachedPaths(pulls: MyPull[]): Promise<Map<string, string[]>> {
    const diffs = store()?.diffs
    const wanted = new Map(pulls.map(pull => [serializeRef({ kind: 'github-pr', owner: pull.owner, repo: pull.repo, number: String(pull.number) }), pull]))
    if (!diffs || wanted.size === 0)
      return new Map()
    const entries = await diffs.listMatching(meta => wanted.get(meta.key)?.headSha === meta.headSha).catch(() => [])
    return new Map(entries.map(entry => [pullKey(wanted.get(entry.key)!), entry.diff.files.slice(0, MAX_FILES).map(file => file.path)]))
  }

  async function classifyOne(pull: MyPull, files: string[] | undefined): Promise<void> {
    const key = pullKey(pull)
    // A newer head or title came in while Jev answered: its own request decides, this one changes nothing.
    const stale = () => latest.get(key) !== versionKey(pull)
    try {
      const answer = await jev.ask(pullState(pull, files), PULL_QUESTIONS)
      if (answer === 'off' || stale())
        return
      const { kind, risk } = answer.answers
      if (!(MY_PULL_KINDS as readonly string[]).includes(kind.choice))
        throw new Error(`Jev chose an unknown kind: ${kind.choice}`)
      const entry: Saved = {
        kind: kind.choice,
        kindConfidence: kind.confidence,
        risk: Math.min(Math.max(risk.score, 0), PULL_QUESTIONS.risk.criteria.length - 1),
        headSha: pull.headSha ?? '',
        title: pull.title,
        model: answer.model,
      }
      saved.set(key, entry)
      failed.delete(key)
      await store()?.storage?.setItem(`${STORAGE_PREFIX}${key}`, entry).catch(() => {})
    }
    catch {
      // The pull request shows without a kind; it is asked again once `RETRY_FAILED_MS` passes.
      if (!stale())
        failed.set(key, { version: versionKey(pull), at: now() })
    }
  }

  function jevOf(entry: Saved): MyPullJev {
    return { kind: entry.kind, kindConfidence: entry.kindConfidence, risk: entry.risk }
  }

  return {
    enabled: jev.enabled,
    async lookup(pull) {
      if (!jev.enabled())
        return undefined
      const key = pullKey(pull)
      if (!saved.has(key) && !looked.has(key)) {
        const value = await store()?.storage?.getItem(`${STORAGE_PREFIX}${key}`).catch(() => undefined)
        looked.add(key)
        if (isSaved(value) && !saved.has(key))
          saved.set(key, value)
      }
      const entry = saved.get(key)
      return isCurrent(entry, pull) ? jevOf(entry) : undefined
    },
    async load(pulls) {
      const storage = store()?.storage
      if (!storage || !jev.enabled())
        return
      const unread = pulls.filter(pull => !looked.has(pullKey(pull)))
      await Promise.all(unread.map(async (pull) => {
        const key = pullKey(pull)
        const value = await storage.getItem(`${STORAGE_PREFIX}${key}`).catch(() => undefined)
        looked.add(key)
        if (isSaved(value) && !saved.has(key))
          saved.set(key, value)
      }))
    },
    apply(pulls) {
      if (!jev.enabled())
        return pulls
      return pulls.map((pull) => {
        const entry = saved.get(pullKey(pull))
        // Only open ones are classified, so a closed one keeps no kind from when it was open.
        return pull.state === 'open' && isCurrent(entry, pull) ? { ...pull, jev: jevOf(entry) } : pull
      })
    },
    async classify(pulls) {
      if (!jev.enabled())
        return
      const todo = pulls.filter((pull) => {
        const key = pullKey(pull)
        const version = versionKey(pull)
        const failure = failed.get(key)
        return pull.state === 'open' && !isCurrent(saved.get(key), pull) && !running.has(version)
          && (failure?.version !== version || now() - failure.at >= RETRY_FAILED_MS)
      })
      if (todo.length === 0)
        return
      const versions = todo.map(versionKey)
      for (const [index, pull] of todo.entries()) {
        running.add(versions[index]!)
        latest.set(pullKey(pull), versions[index]!)
      }
      try {
        const paths = await cachedPaths(todo)
        // The client holds requests to its own concurrency limit, so all of them can be handed over at once.
        await Promise.all(todo.map(pull => classifyOne(pull, paths.get(pullKey(pull)))))
      }
      finally {
        for (const version of versions)
          running.delete(version)
      }
    },
  }
}
