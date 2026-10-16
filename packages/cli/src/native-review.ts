import type { LlmSession } from '@pulls.review/core/cache'
import type { GithubClient, MergeMethod, PullRequestStateChange } from '@pulls.review/core/github'
import type { DiffSource, ReviewVerdict } from '@pulls.review/core/types'
import type { ServerHost } from './host'
import type { PatchReader } from './stored-diff'
import process from 'node:process'
import { archiveChat, chatHistory, restoreChat } from '@pulls.review/core/cache'
import {
  cancelAutoMerge,
  changePullRequestState,
  createGithubClient,
  createGithubSource,
  enableAutoMerge,
  fetchGithubTokenMeta,
  fetchOpenPullRequests,
  fetchPullRequestOverview,
  fetchPullRequestStack,
  mergePullRequest,
  updatePullRequestBranch,
  updatePullRequestText,
} from '@pulls.review/core/github'
import { createLocalSource } from '@pulls.review/core/local'
import { staticCredentials } from '@pulls.review/core/types'
import { resolveGithubToken } from './credentials'
import { checkoutOf } from './host'
import { sharedJev } from './jev'
import { JEV_FILES_OFF, sharedFileAttention } from './jev-files'
import { pullOfDiff } from './jev-review'
import { jevOfPull, jevPendingOfPull } from './my-pulls'
import { overviewValue, readStoredOverview, storeOverview } from './pr-store'
import { createRepoPullsCache } from './repo-pulls'
import { serverSession } from './server-session'
import { createPatchReader, MAX_PATCH_PATHS } from './stored-diff'

/**
 * The phone's review routes beyond opening a diff: reviewed marks, full file content,
 * the PR overview and its actions, the stack a PR sits in, pending reviews, posting an AI
 * review, sharing an analysis, a repository's pull requests, and the chat's history (a new
 * chat, the earlier ones, putting one back). Each one runs the same core function the web
 * stores call, with the server's GitHub token. `jev-files` is the phone's own: what Jev
 * said about the diff.
 */
type Args = Record<string, unknown> | undefined
type Handler = (host: ServerHost, args: Args) => Promise<unknown>

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** Edits a pull request's title or description. An empty description is a real edit; only a missing field is left alone. */
async function editPullRequest(github: GithubClient, id: string, args: Args): Promise<void> {
  const title = typeof args?.title === 'string' ? args.title.trim() : undefined
  const body = typeof args?.body === 'string' ? args.body : undefined
  if (title === '')
    throw new Error('A pull request needs a title.')
  if (title === undefined && body === undefined)
    throw new Error('Edit needs a title or a description.')
  await updatePullRequestText(github, id, { title, body })
}

function pullOf(args: Args): { owner: string, repo: string, number: string } {
  const owner = text(args?.owner)
  const repo = text(args?.repo)
  const number = typeof args?.number === 'number' ? String(args.number) : text(args?.number)
  if (!owner || !repo || !/^\d+$/.test(number))
    throw new Error('That needs the pull request owner, repo and number.')
  return { owner, repo, number }
}

async function token(): Promise<string> {
  const value = await resolveGithubToken(process.env)
  if (!value)
    throw new Error('The server has no GitHub token.')
  return value
}

async function client() {
  return createGithubClient(staticCredentials(await token()))
}

/** Each repository's first page of open pull requests, answered from the server and fetched ahead for the Inbox. */
const repoPulls = createRepoPullsCache(async (owner, repo) => fetchOpenPullRequests(await client(), owner, repo))

/**
 * Fetches ahead the first page of the repositories these pull requests are in, so the repository page opens at once.
 *
 * @param pulls The Inbox the phone was just sent.
 */
export function warmRepoPulls(pulls: { owner: string, repo: string }[]): void {
  repoPulls.warm(pulls)
}

async function prReviews(args: Args) {
  const reviews = createGithubSource({ kind: 'github-pr', ...pullOf(args) }, staticCredentials(await token())).reviews
  if (!reviews)
    throw new Error('Reviews are not available for this pull request.')
  return reviews
}

/** The source a cached diff came from, for loading whole files at its base and head. */
async function sourceOfKey(host: ServerHost, key: string): Promise<{ source: DiffSource, entry: NonNullable<Awaited<ReturnType<Awaited<ReturnType<typeof serverSession>>['cache']['diffs']['get']>>> }> {
  const entry = await (await serverSession(host)).cache.diffs.get(key)
  if (!entry)
    throw new Error('Open the diff first.')
  const ref = entry.diff.ref
  if (ref.kind === 'local')
    return { source: createLocalSource({ cwd: checkoutOf(host), target: ref.target }), entry }
  if (ref.kind === 'paste')
    throw new Error('A pasted diff has no full files.')
  return { source: createGithubSource(ref, staticCredentials(await resolveGithubToken(process.env))), entry }
}

const patches = new WeakMap<ServerHost, Promise<PatchReader>>()

/** The patch reader over the server session's cache; it remembers the diffs `github-pr` just served. */
export function sessionPatches(host: ServerHost): Promise<PatchReader> {
  let reader = patches.get(host)
  if (!reader) {
    reader = serverSession(host).then(session => createPatchReader(session.cache.diffs))
    patches.set(host, reader)
  }
  return reader
}

const MERGE_METHODS: MergeMethod[] = ['merge', 'squash', 'rebase']
const STATE_CHANGES: PullRequestStateChange[] = ['close', 'reopen', 'ready', 'draft']
const VERDICTS: ReviewVerdict[] = ['APPROVE', 'REQUEST_CHANGES', 'COMMENT']

/** The merge method [args] names, or an error saying that [what] needs one. */
function mergeMethodOf(args: Args, what: string): MergeMethod {
  const method = text(args?.method) as MergeMethod
  if (!MERGE_METHODS.includes(method))
    throw new Error(`${what} needs a method: merge, squash or rebase.`)
  return method
}

export const NATIVE_REVIEW: Record<string, Handler> = {
  /** Reviewed marks for a diff's files, by blob sha, and the paths that changed since they were marked. */
  'reviewed-get': async function (host, args) {
    const session = await serverSession(host)
    const key = text(args?.key)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    const shas = Array.isArray(args?.shas) ? args.shas.filter((sha): sha is string => typeof sha === 'string') : entry?.diff.files.map(file => file.sha) ?? []
    return {
      reviewed: [...await session.cache.reviewMarks.get(shas)],
      changedSinceReviewed: entry?.changedSinceReviewed ?? [],
    }
  },

  /** Marks or unmarks files; either verdict clears their "changed since reviewed" flag, as the web store does. */
  'reviewed-set': async function (host, args) {
    const session = await serverSession(host)
    const shas = Array.isArray(args?.shas) ? args.shas.filter((sha): sha is string => typeof sha === 'string') : []
    if (shas.length === 0)
      throw new Error('Name at least one file sha.')
    await session.cache.reviewMarks.set(shas, args?.reviewed === true)
    const key = text(args?.key)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    if (entry?.changedSinceReviewed?.length) {
      const touched = new Set(shas)
      const paths = new Set(entry.diff.files.filter(file => touched.has(file.sha)).map(file => file.path))
      const remaining = entry.changedSinceReviewed.filter(path => !paths.has(path))
      if (remaining.length !== entry.changedSinceReviewed.length)
        await session.cache.diffs.setChangedSinceReviewed(key, remaining)
    }
    return true
  },

  /** Both sides of one file in full, cached like the web `fileContent.load`. */
  'file-content': async function (host, args) {
    const key = text(args?.key)
    const path = text(args?.path)
    if (!key || !path)
      throw new Error('Loading a full file needs the diff key and a path.')
    const { source, entry } = await sourceOfKey(host, key)
    const file = entry.diff.files.find(candidate => candidate.path === path)
    const { base, head } = entry.diff
    // A file the change does not touch is read at the head commit, so a link to it opens in the app's own viewer
    // (recording of 16 Oct 12:10, 10:01 to 10:07, N20). Only `new` comes back: it has no old side.
    if (!file && head && source.loadFile && !path.split('/').includes('..')) {
      const cache = (await serverSession(host)).cache.fileContents
      const cached = await cache.get(key, head.sha, path)
      const content = cached !== undefined ? cached : await source.loadFile(path, head.sha)
      if (cached === undefined && content !== undefined)
        await cache.set(key, head.sha, path, content)
      if (content === undefined)
        throw new Error('That file is not in the repository at this commit.')
      return { old: null, new: content }
    }
    if (!file || !base || !head || !source.loadFile)
      throw new Error('This diff has no full file for that path.')
    const cache = (await serverSession(host)).cache.fileContents
    const side = async (sidePath: string, sha: string) => {
      const cached = await cache.get(key, sha, sidePath)
      if (cached !== undefined)
        return cached
      const content = await source.loadFile!(sidePath, sha)
      if (content !== undefined)
        await cache.set(key, sha, sidePath, content)
      return content
    }
    const [old, current] = await Promise.all([
      file.status === 'added' ? undefined : side(file.previousPath ?? file.path, base.sha),
      file.status === 'removed' ? undefined : side(file.path, head.sha),
    ])
    return { old: old ?? null, new: current ?? null }
  },

  /**
   * The PR overview (checks, commits, timeline, viewer abilities) plus whether and how it can
   * merge, stored for next time. `cached: true` answers the stored one at once with when it was
   * fetched (`storedAt`), or `null` when none is stored, without asking GitHub.
   */
  'pr-overview': async function (host, args) {
    const pull = pullOf(args)
    const { storage } = await serverSession(host)
    if (args?.cached === true) {
      const stored = await readStoredOverview(storage, pull)
      return stored ? { ...stored.value, storedAt: stored.at } : null
    }
    const overview = await fetchPullRequestOverview(await client(), pull.owner, pull.repo, pull.number)
    // A failed write only costs the next cached open; the phone still gets the overview.
    return storeOverview(storage, pull, overview).catch(() => overviewValue(overview))
  },

  /** Hunks of a stored diff's files, for the ones a lite `github-pr` answer left out (`patchOmitted`). */
  'diff-patches': async function (host, args) {
    const key = text(args?.key)
    const paths = Array.isArray(args?.paths) ? args.paths.filter((path): path is string => typeof path === 'string') : undefined
    if (!key || !paths)
      throw new Error('Loading patches needs the diff key and the paths.')
    if (paths.length > MAX_PATCH_PATHS)
      throw new Error(`Ask for at most ${MAX_PATCH_PATHS} paths at a time.`)
    return { files: await (await sessionPatches(host)).read(key, paths) }
  },

  /**
   * The stack the PR is a layer of, bottom first: GitHub's own, or inferred from branch names when GitHub has
   * none (or this host has no stack fields). `{ stack: null }` when the PR is in no stack.
   */
  'pr-stack': async function (_host, args) {
    const { owner, repo, number } = pullOf(args)
    return { stack: await fetchPullRequestStack(await client(), owner, repo, number) }
  },

  /** Merge now or when checks pass, close, reopen, ready, draft, edit the title or description, or update the branch. */
  'pr-action': async function (_host, args) {
    const id = text(args?.id)
    const action = text(args?.action)
    if (!id)
      throw new Error('A pull request action needs the pull request node id.')
    const github = await client()
    const expectedHeadOid = text(args?.expectedHeadOid) || undefined
    if (action === 'merge') {
      const method = mergeMethodOf(args, 'Merge')
      await mergePullRequest(github, { pullRequestId: id, method, title: text(args?.title) || undefined, body: typeof args?.body === 'string' ? args.body : undefined, expectedHeadOid })
    }
    else if (action === 'auto-merge') {
      await enableAutoMerge(github, id, { method: mergeMethodOf(args, 'Merging when checks pass'), expectedHeadOid })
    }
    else if (action === 'cancel-auto-merge') {
      await cancelAutoMerge(github, id)
    }
    else if (action === 'update-branch') {
      await updatePullRequestBranch(github, id, { method: args?.method === 'rebase' ? 'rebase' : 'merge', expectedHeadOid })
    }
    else if (action === 'edit') {
      await editPullRequest(github, id, args)
    }
    else if (STATE_CHANGES.includes(action as PullRequestStateChange)) {
      await changePullRequestState(github, id, action as PullRequestStateChange)
    }
    else {
      throw new Error('Unknown pull request action.')
    }
    return true
  },

  /** Every review thread (outdated ones included, flagged), review summaries, and the viewer's pending review. */
  'review-data': async function (_host, args) {
    const data = await (await prReviews(args)).fetch()
    const viewer = await createGithubSource({ kind: 'github-pr', ...pullOf(args) }, staticCredentials(await token())).viewer?.().catch(() => undefined)
    return { ...data, viewer: viewer?.login, canWrite: viewer?.canWrite ?? false }
  },

  'review-submit': async function (_host, args) {
    const verdict = text(args?.verdict) as ReviewVerdict
    if (!VERDICTS.includes(verdict))
      throw new Error('A review needs a verdict.')
    const reviews = await prReviews(args)
    const pending = (await reviews.fetch()).pendingReview
    await reviews.submitReview(verdict, text(args?.body), pending)
    return true
  },

  'review-discard': async function (_host, args) {
    const reviews = await prReviews(args)
    const pending = (await reviews.fetch()).pendingReview
    if (pending)
      await reviews.discardPendingReview(pending)
    return true
  },

  'review-edit': async function (_host, args) {
    const id = Number(args?.commentId)
    const body = text(args?.body).trim()
    if (!Number.isFinite(id) || !body)
      throw new Error('An edit needs the comment and some text.')
    await (await prReviews(args)).editComment(id, body)
    return true
  },

  'review-delete': async function (_host, args) {
    const id = Number(args?.commentId)
    if (!Number.isFinite(id))
      throw new Error('A delete needs the comment.')
    await (await prReviews(args)).deleteComment(id)
    return true
  },

  /** Posts the chosen findings of the saved AI review as one COMMENT review, like `critique.post`. */
  'critique-post': async function (host, args) {
    const key = text(args?.key)
    const ids = new Set(Array.isArray(args?.ids) ? args.ids.filter((id): id is string => typeof id === 'string') : [])
    if (!key || ids.size === 0)
      throw new Error('Posting needs the diff and at least one finding.')
    const session = await serverSession(host)
    const entry = await session.cache.diffs.get(key)
    const { serializeRef } = await import('@pulls.review/core/types')
    const result = entry && await session.cache.critiques.latest(serializeRef(entry.diff.ref), entry.diff.head?.sha)
    if (!entry || !result || entry.diff.ref.kind !== 'github-pr' || !entry.diff.head)
      throw new Error('There is no AI review of this pull request to post.')
    const { citesOnGithub, critiqueReviewBody, findingCommentBody } = await import('@pulls.review/core/llm')
    const chosen = result.findings.filter(finding => ids.has(finding.id))
    const reviews = await prReviews(entry.diff.ref)
    const pending = (await reviews.fetch()).pendingReview
    // The findings link what they mention (rule 96); on GitHub those links go to the file at the head.
    const onGithub = citesOnGithub(entry.diff)
    const body = onGithub(typeof args?.body === 'string' ? args.body : critiqueReviewBody(result))
    await reviews.postReview({
      headSha: entry.diff.head.sha,
      body,
      comments: chosen.map(finding => ({
        target: { path: finding.path, side: finding.side, line: finding.line, ...(finding.startLine !== undefined ? { startLine: finding.startLine, startSide: finding.side } : {}) },
        body: onGithub(findingCommentBody(finding)),
      })),
      pendingReview: pending,
    })
    return { posted: chosen.map(finding => finding.id) }
  },

  /** Posts or updates the viewer's shared-analysis comment with the saved AI analysis. */
  'share-analysis': async function (host, args) {
    const key = text(args?.key)
    const session = await serverSession(host)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    const result = entry?.analyzedBy?.llm
    const ref = entry?.diff.ref
    if (!entry || !result || !ref || ref.kind !== 'github-pr')
      throw new Error('Analyze this pull request before sharing it.')
    const sharing = createGithubSource(ref, staticCredentials(await token())).sharing
    const meta = await fetchGithubTokenMeta(await token())
    if (!sharing)
      throw new Error('Sharing is not available here.')
    const comment = await sharing.upsert(meta.login, { headSha: entry.diff.head?.sha ?? '', result }, entry.sharedComment)
    await session.cache.diffs.setSharedComment(key, comment)
    return comment
  },

  /**
   * Shared analyses posted on the PR (`shared.discover`): who shared, where, whether it is of an
   * older head, and the language. With no AI result saved yet, a `github-actions[bot]` analysis
   * loads at once, as on the web.
   */
  'shared-list': async function (host, args) {
    const key = text(args?.key)
    const session = await serverSession(host)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    const ref = entry?.diff.ref
    if (!entry || !ref || ref.kind !== 'github-pr')
      return { candidates: [] }
    const sharing = createGithubSource(ref, staticCredentials(await token())).sharing
    const found = await sharing?.list().catch(() => []) ?? []
    const viewer = (await fetchGithubTokenMeta(await token()).catch(() => undefined))?.login
    const headSha = entry.diff.head?.sha
    const current = entry.analyzedBy.llm
    const bot = found.find(comment => comment.login === 'github-actions[bot]')
    if (bot && !current) {
      await session.cache.diffs.setAnalyzedResult(key, 'llm', { ...bot.analysis.result, sharedBy: bot.login })
      return { candidates: [], loaded: bot.login }
    }
    // `discover()` without `from` only runs while the diff has no AI result at all.
    if (current)
      return { candidates: [] }
    return {
      candidates: found.map(comment => ({
        login: comment.login,
        url: comment.url,
        own: comment.login === viewer,
        stale: comment.analysis.headSha !== headSha,
        locale: comment.analysis.result.locale,
      })),
    }
  },

  /** Installs one person's shared analysis as this diff's AI result (`shared.load`). */
  'shared-load': async function (host, args) {
    const key = text(args?.key)
    const login = text(args?.login)
    const session = await serverSession(host)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    const ref = entry?.diff.ref
    if (!entry || !ref || ref.kind !== 'github-pr' || !login)
      throw new Error('That needs the diff and who shared it.')
    const found = await createGithubSource(ref, staticCredentials(await token())).sharing?.list() ?? []
    const comment = found.find(item => item.login === login)
    if (!comment)
      throw new Error(`${login} has not shared an analysis on this pull request.`)
    await session.cache.diffs.setAnalyzedResult(key, 'llm', { ...comment.analysis.result, sharedBy: comment.login })
    return { loaded: comment.login }
  },

  /** One page of a repository's open pull requests (`PullsPage`). */
  'repo-pulls': async function (_host, args) {
    const owner = text(args?.owner)
    const repo = text(args?.repo)
    if (!owner || !repo)
      throw new Error('That needs an owner and a repo.')
    const next = text(args?.next)
    // Later pages go to GitHub; the first is the one the page opens on, so the server keeps it (H14).
    if (next)
      return fetchOpenPullRequests(await client(), owner, repo, next)
    return repoPulls.first(owner, repo, args?.fresh === true)
  },

  /**
   * Starts a new chat: the one after the analysis is set aside in the diff's chat history (at most
   * `MAX_ARCHIVED_CHATS`), not thrown away, and the analysis turn stays as it was (L36).
   */
  'chat-clear': async function (host, args) {
    const key = text(args?.key)
    const session = await serverSession(host)
    const entry = key ? await session.cache.diffs.get(key) : undefined
    const stored = entry?.llmSession as LlmSession | undefined
    if (!stored)
      return { cleared: 0 }
    await session.cache.diffs.setLlmSession(key, archiveChat(stored))
    return { cleared: stored.messages.length - stored.chatStartIndex }
  },

  /** The diff's earlier chats for the history sheet, most recent first: `{ chats: [{ id, title, at, turns }] }`. */
  'chat-history': async function (host, args) {
    const key = text(args?.key)
    if (!key)
      throw new Error('That needs the diff key.')
    const entry = await (await serverSession(host)).cache.diffs.get(key)
    return { chats: chatHistory(entry?.llmSession as LlmSession | undefined) }
  },

  /** Puts an earlier chat back as the current one, setting the current chat aside first when it asked anything. */
  'chat-restore': async function (host, args) {
    const key = text(args?.key)
    const id = text(args?.id)
    if (!key || !id)
      throw new Error('That needs the diff key and the chat.')
    const session = await serverSession(host)
    const stored = (await session.cache.diffs.get(key))?.llmSession as LlmSession | undefined
    if (!stored)
      throw new Error('That chat is not in this pull request\'s history.')
    await session.cache.diffs.setLlmSession(key, restoreChat(stored, id))
    return { restored: true }
  },

  /**
   * What Jev said about an opened diff, without asking it anything: each rated file's priority (`p0` needs judgment,
   * `p1` is worth a look, `p2` is low attention), how many are being rated now (`pending`), and the pull request's
   * kind and risk (`pull`). Jev rates and sorts only when a review of the diff starts; the phone asks again while
   * `pending` is above 0, which counts the pull request too while Jev is still sorting it. With Jev off it answers `{ enabled: false, files: {}, pending: 0 }` and never fails.
   */
  'jev-files': async function (host, args) {
    if (!sharedJev().enabled())
      return JEV_FILES_OFF
    const key = text(args?.key)
    if (!key)
      throw new Error('That needs the diff key.')
    const session = await serverSession(host)
    const entry = await session.cache.diffs.get(key)
    if (!entry)
      throw new Error('Open the diff first.')
    const { diff } = entry
    const pull = pullOfDiff(diff)
    const [files, jev] = await Promise.all([
      sharedFileAttention(session.storage).known(diff),
      pull ? jevOfPull(pull).catch(() => undefined) : undefined,
    ])
    if (!files.enabled)
      return files
    // A pull request still being sorted counts as one more answer to wait for, so the phone keeps asking until it lands.
    const sorting = !jev && !!pull && jevPendingOfPull(pull)
    return { ...files, pending: files.pending + (sorting ? 1 : 0), ...(jev ? { pull: jev } : {}) }
  },

  /** Diffs this server opened most recently, for the home page's "recently viewed". */
  'recent-diffs': async function (host) {
    const metas = await (await serverSession(host)).cache.diffs.listRecent(12)
    return metas.map(meta => ({ key: meta.key, ref: meta.ref, title: meta.title, label: meta.label, url: meta.url, state: meta.pullRequestState, additions: meta.additions, deletions: meta.deletions, lastViewedAt: meta.lastViewedAt }))
  },
}
