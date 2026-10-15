import type { ReviewDraftTarget } from '@pulls.review/core/types'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { Readable } from 'node:stream'
import { createRuleBasedAdapter } from '@pulls.review/core/analyze'
import { createGithubSource } from '@pulls.review/core/github'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { serializeRef, staticCredentials } from '@pulls.review/core/types'
import { resolveGithubToken } from './credentials'
import { highlightLines, pierreTheme } from './highlight'
import { NATIVE_REVIEW } from './native-review'
import { serverFunctions, serverSession } from './server-session'

/** Methods a phone may call. Storage and the GitHub token stay off this list. */
const ALLOWED = new Set<string>([
  LOCAL_RPC.repoInfo,
  LOCAL_RPC.sourceKey,
  LOCAL_RPC.sourceFetch,
  LOCAL_RPC.sourceFingerprint,
  LOCAL_RPC.sourceLoadFile,
  LOCAL_RPC.myPulls,
  LOCAL_RPC.myPullsCached,
  LOCAL_RPC.lensList,
  LOCAL_RPC.lensGet,
  LOCAL_RPC.aiModels,
  LOCAL_RPC.aiTaskStart,
  LOCAL_RPC.aiTaskWait,
  LOCAL_RPC.aiTaskCancel,
  LOCAL_RPC.aiTaskList,
  LOCAL_RPC.aiTaskListAll,
  LOCAL_RPC.askThreads,
  LOCAL_RPC.askThreadClose,
  LOCAL_RPC.inboxChat,
  LOCAL_RPC.inboxChatClear,
])

export interface NativeRequest {
  url: URL
  method: string
  json: () => Promise<unknown>
}

function androidDir(): string {
  return process.env.PULLS_REVIEW_ANDROID_DIR || join(homedir(), '.local/share/pulls-review/android')
}

async function androidFile(name: string, type: string): Promise<Response> {
  const path = join(androidDir(), name)
  const info = await stat(path).catch(() => undefined)
  if (!info?.isFile())
    return Response.json({ error: 'No Android build has been published.' }, { status: 404 })
  const body = Readable.toWeb(createReadStream(path)) as ReadableStream
  return new Response(body, {
    headers: {
      'Content-Type': type,
      'Content-Length': String(info.size),
      'Cache-Control': 'no-store',
    },
  })
}

/**
 * The phone's HTTP API: the same RPC handlers the page uses, plus the updater files.
 * Returns undefined when the request is not one of ours.
 * @param name - The RPC name.
 * @param cwd - The repository the server was started in.
 * @param args - The decoded JSON body.
 */
function nativeCall(name: string, cwd: string, args: unknown): Promise<Response> | undefined {
  if (name === 'github-pr' || name === 'github-compare' || name === 'github-commit')
    return openGithub(cwd, name, args)
  const calls: Record<string, (cwd: string, args: unknown) => Promise<Response>> = {
    'local-diff': openLocal,
    'review-state': reviewState,
    'review-threads': (_cwd, input) => reviewThreads(input),
    'review-reply': (_cwd, input) => reviewReply(input),
    'review-resolve': (_cwd, input) => reviewResolve(input),
    'highlight': (_cwd, input) => highlight(input),
    'repo-viewed': repoViewed,
    'mermaid-svg': (_cwd, input) => mermaidSvg(input),
    'review-comment': (_cwd, input) => reviewComment(input),
  }
  const call = calls[name]
  if (call)
    return call(cwd, args)
  const review = NATIVE_REVIEW[name]
  if (!review)
    return undefined
  return review(cwd, args as Record<string, unknown> | undefined)
    .then(result => Response.json({ result: result ?? null }))
    .catch(error => Response.json({ error: error instanceof Error ? error.message : String(error) }, { status: 400 }))
}

export async function handleNativeRequest(request: NativeRequest, cwd: string): Promise<Response | undefined> {
  const path = request.url.pathname
  if (request.method === 'GET' && path === '/android/latest.json')
    return androidFile('latest.json', 'application/json')
  if (request.method === 'GET' && path === '/android/pulls.apk')
    return androidFile('pulls.apk', 'application/vnd.android.package-archive')
  if (request.method !== 'POST' || path !== '/api/rpc')
    return undefined

  const body = await request.json().catch(() => undefined) as { name?: unknown, args?: unknown } | undefined
  const name = typeof body?.name === 'string' ? body.name : ''
  const native = nativeCall(name, cwd, body?.args)
  if (native)
    return native
  if (!ALLOWED.has(name))
    return Response.json({ error: 'That method is not available to the app.' }, { status: 404 })
  const fn = (await serverFunctions(cwd)).find(candidate => candidate.name === name)
  if (!fn)
    return Response.json({ error: 'That method is not available to the app.' }, { status: 404 })
  try {
    const result = await (fn.handler as (input?: unknown) => Promise<unknown>)(body?.args)
    return Response.json({ result })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

function textOf(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function githubRef(name: string, args: unknown): Parameters<typeof createGithubSource>[0] | string {
  const input = args as Record<string, unknown> | undefined
  const owner = textOf(input?.owner)
  const repo = textOf(input?.repo)
  if (!owner || !repo)
    return 'That needs an owner and a repo.'
  if (name === 'github-compare')
    return textOf(input?.base) && textOf(input?.head) ? { kind: 'github-compare', owner, repo, base: textOf(input?.base), head: textOf(input?.head) } : 'A compare needs a base and a head.'
  if (name === 'github-commit')
    return /^[0-9a-f]{7,64}$/i.test(textOf(input?.sha)) ? { kind: 'github-commit', owner, repo, sha: textOf(input?.sha) } : 'A commit needs a sha.'
  const number = typeof input?.number === 'number' ? String(input.number) : textOf(input?.number)
  return /^\d+$/.test(number) ? { kind: 'github-pr', owner, repo, number } : 'A pull request needs owner, repo and number.'
}

async function openGithub(cwd: string, name: string, args: unknown): Promise<Response> {
  const ref = githubRef(name, args)
  if (typeof ref === 'string')
    return Response.json({ error: ref }, { status: 400 })
  return saveGithub(cwd, ref)
}

async function openLocal(cwd: string, args: unknown): Promise<Response> {
  const target = (args as { target?: unknown } | undefined)?.target
  if (typeof target !== 'string')
    return Response.json({ error: 'A local diff needs a git target.' }, { status: 400 })
  const functions = await serverFunctions(cwd)
  const fetch = functions.find(fn => fn.name === LOCAL_RPC.sourceFetch)
  const keyOf = functions.find(fn => fn.name === LOCAL_RPC.sourceKey)
  try {
    const diff = await (fetch!.handler as (input: unknown) => Promise<import('@pulls.review/core/types').DiffsPayload>)({ target })
    const key = String(await (keyOf!.handler as (input: unknown) => Promise<unknown>)({ target }))
    await (await serverSession(cwd)).cache.diffs.putDiff(key, diff, diff.head?.sha ?? '')
    return Response.json({ result: { key, diff, groups: await ruleGroups(diff) } })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

async function saveGithub(cwd: string, ref: Parameters<typeof createGithubSource>[0]): Promise<Response> {
  const token = await resolveGithubToken(process.env)
  if (!token)
    return Response.json({ error: 'The server has no GitHub token.' }, { status: 400 })
  try {
    const diff = await createGithubSource(ref, staticCredentials(token)).fetch()
    const key = serializeRef(diff.ref)
    await (await serverSession(cwd)).cache.diffs.putDiff(key, diff, diff.head?.sha ?? '')
    return Response.json({ result: { key, diff, groups: await ruleGroups(diff) } })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

const RULE_TEXT: Record<string, [string, string]> = {
  code: ['Code', 'Source changes'],
  types: ['Types', 'Type declarations'],
  data: ['Data', 'Database schema and migrations'],
  styles: ['Styles', 'Stylesheets and styling config'],
  tests: ['Tests', 'Test coverage for the change'],
  scripts: ['Scripts', 'Build, dev and automation scripts'],
  ci: ['CI', 'CI workflows and pipelines'],
  config: ['Config', 'Configuration changes'],
  deps: ['Dependencies', 'Dependency version changes'],
  i18n: ['Translations', 'Locale and translation files'],
  docs: ['Docs', 'Documentation updates'],
  assets: ['Assets', 'Images, fonts and media'],
  generated: ['Generated', 'Generated or build output changes'],
  other: ['Other', 'Other changes that don\'t fit an existing category'],
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * PRs of one repo this server already opened (`loadViewed` in `pull-request-list-store.ts`):
 * their diff size, file and group counts, and whether an AI grouping exists.
 */
async function repoViewed(cwd: string, input: unknown): Promise<Response> {
  const { owner, repo } = (input ?? {}) as { owner?: unknown, repo?: unknown }
  if (typeof owner !== 'string' || typeof repo !== 'string')
    return Response.json({ error: 'That needs an owner and a repo.' }, { status: 400 })
  const entries = await (await serverSession(cwd)).cache.diffs.listMatching(({ ref }) => ref.kind === 'github-pr' && ref.owner === owner && ref.repo === repo)
  const viewed = await Promise.all(entries.map(async (entry) => {
    const ai = entry.analyzedBy.llm ?? entry.analyzedBy['web-llm']
    const ref = entry.diff.ref
    return {
      number: ref.kind === 'github-pr' ? Number(ref.number) : 0,
      additions: entry.diff.files.reduce((sum, file) => sum + file.additions, 0),
      deletions: entry.diff.files.reduce((sum, file) => sum + file.deletions, 0),
      files: entry.diff.files.length,
      groups: ai ? ai.groups.length : (await ruleGroups(entry.diff)).length,
      hasAiResult: !!ai,
    }
  }))
  return Response.json({ result: viewed })
}

/** The web tokens: `bg-base`, `color-base`, the green primary, `color-muted`, `border-base`. */
const MERMAID_THEME = {
  dark: { bg: '#111111', fg: '#E5E5E5', line: '#737373', accent: '#5B9544', muted: '#A3A3A3', surface: '#1A1A1A', border: '#3A3A3A', font: 'sans-serif', transparent: true },
  light: { bg: '#FFFFFF', fg: '#262626', line: '#A3A3A3', accent: '#49833E', muted: '#525252', surface: '#F6F6F6', border: '#D4D4D4', font: 'sans-serif', transparent: true },
} as const

/**
 * A Mermaid diagram as a plain SVG the phone can draw natively (AndroidSVG): rendered by
 * beautiful-mermaid, then every CSS variable and `color-mix()` resolved to a hex colour and the
 * font import dropped, since AndroidSVG supports neither.
 */
async function mermaidSvg(input: unknown): Promise<Response> {
  const { source, dark } = (input ?? {}) as { source?: unknown, dark?: unknown }
  if (typeof source !== 'string' || !source.trim() || source.length > 8_000)
    return Response.json({ error: 'That needs Mermaid source.' }, { status: 400 })
  try {
    const { renderMermaidSVG } = await import('beautiful-mermaid')
    return Response.json({ result: { svg: flattenMermaidSvg(renderMermaidSVG(source, MERMAID_THEME[dark === true ? 'dark' : 'light']), dark === true) } })
  }
  catch (error) {
    return Response.json({ error: `The diagram could not be drawn: ${error instanceof Error ? error.message : String(error)}` }, { status: 400 })
  }
}

function mixHex(a: string, b: string, weight: number): string {
  const ch = (hex: string, at: number) => Number.parseInt(hex.slice(1 + at * 2, 3 + at * 2), 16)
  return `#${[0, 1, 2].map(at => Math.round(ch(a, at) * weight + ch(b, at) * (1 - weight)).toString(16).padStart(2, '0')).join('')}`
}

/** Exported for tests: resolves beautiful-mermaid's theme variables to colours. */
export function flattenMermaidSvg(svg: string, dark: boolean): string {
  const t = MERMAID_THEME[dark ? 'dark' : 'light']
  const vars: Record<string, string> = {
    '--bg': t.bg,
    '--fg': t.fg,
    '--line': t.line,
    '--accent': t.accent,
    '--muted': t.muted,
    '--surface': t.surface,
    '--border': t.border,
    '--_text': t.fg,
    '--_text-sec': t.muted,
    '--_text-muted': t.muted,
    '--_text-faint': mixHex(t.fg, t.bg, 0.25),
    '--_line': t.line,
    '--_arrow': t.accent,
    '--_node-fill': t.surface,
    '--_node-stroke': t.border,
    '--_group-fill': t.bg,
    '--_group-hdr': mixHex(t.fg, t.bg, 0.05),
    '--_inner-stroke': mixHex(t.fg, t.bg, 0.12),
    '--_key-badge': mixHex(t.fg, t.bg, 0.10),
  }
  return svg
    .replace(/@import url\([^)]*\);?/g, '')
    .replace(/svg \{[^}]*\}/, '')
    .replace(/var\((--[\w-]+)(?:,[^()]*(?:\([^()]*\)[^()]*)*)?\)/g, (match, name: string) => vars[name] ?? match)
    .replace(/color-mix\(in srgb, (#[0-9a-f]{6}) (\d+)%, (#[0-9a-f]{6})\)/gi, (_m, a: string, p: string, b: string) => mixHex(a, b, Number(p) / 100))
    .replace(/ style="[^"]*--bg:[^"]*"/, '')
}

/** The same deterministic groups the web app shows before any AI runs, in English. */
async function ruleGroups(diff: import('@pulls.review/core/types').DiffsPayload) {
  const result = await createRuleBasedAdapter((key, facts) => {
    const [base, blurb] = RULE_TEXT[key] ?? [key, key]
    const area = facts.otherAreas ? 'other areas' : facts.area === '' ? 'repo root' : facts.area
    const label = area === undefined ? base : `${base} · ${area}`
    const parts = [
      facts.added > 0 ? plural(facts.added, 'new file', 'new files') : '',
      facts.removed > 0 ? plural(facts.removed, 'deleted', 'deleted') : '',
      facts.renamed > 0 ? plural(facts.renamed, 'renamed', 'renamed') : '',
      facts.pairedTests > 0 ? plural(facts.pairedTests, 'test right after its source', 'tests right after their source') : '',
    ].filter(Boolean)
    return { label, summary: parts.length ? `${blurb}. ${parts.join(', ')}.` : `${blurb}.` }
  }).analyze(diff)
  return result.groups.map(group => ({
    key: group.key,
    label: group.label,
    summary: group.summary,
    category: group.category ?? 'other',
    filePaths: group.filePaths,
  }))
}

function messageText(content: unknown): string {
  if (typeof content === 'string')
    return content
  if (!Array.isArray(content))
    return ''
  return content.map(part => typeof part === 'string' ? part : (part as { text?: string })?.text ?? '').join('')
}

/**
 * The brief as the phone draws it. Only the OpenUI Lang is stored, so a parser upgrade
 * reaches briefs written before it; the phone gets the parsed tree, never the source.
 */
async function briefState(cache: Awaited<ReturnType<typeof serverSession>>['cache'], diff: import('@pulls.review/core/types').DiffsPayload) {
  const saved = await cache.briefs.latest(serializeRef(diff.ref), diff.head?.sha)
  if (!saved)
    return undefined
  const { briefTree } = await import('@pulls.review/core/llm')
  const tree = briefTree(saved.lang)
  return tree
    ? { tree, headSha: saved.headSha, engine: saved.engine, model: saved.model, effort: saved.effort, locale: saved.locale, generatedAt: saved.generatedAt }
    : undefined
}

/** Saved questions, the latest AI review, and the chat, so a reopened pull request shows what already happened. */
async function reviewState(cwd: string, args: unknown): Promise<Response> {
  const key = textOf((args as { key?: unknown } | undefined)?.key)
  if (!key)
    return Response.json({ error: 'A review state needs a key.' }, { status: 400 })
  const session = await serverSession(cwd)
  const entry = await session.cache.diffs.get(key)
  const threadsFn = session.functions.find(fn => fn.name === LOCAL_RPC.askThreads)
  const threads = await (threadsFn!.handler as (input: unknown) => Promise<unknown>)({ key })
  const critique = entry
    ? await session.cache.critiques.latest(serializeRef(entry.diff.ref), entry.diff.head?.sha)
    : undefined
  const stored = entry?.llmSession
  const chat = (stored?.messages ?? []).slice(stored?.chatStartIndex ?? 0).map(message => ({
    role: message.role,
    text: messageText(message.content).slice(0, 4000),
  })).filter(message => message.text)
  const llm = entry?.analyzedBy?.llm
  // Nested groups flatten to the web's two levels: a parent row, then its children.
  const aiGroups = llm?.groups?.map(group => ({
    key: group.key,
    label: group.label,
    summary: group.summary,
    category: group.category ?? 'other',
    filePaths: group.filePaths,
    children: (group.children ?? []).map(child => ({ key: child.key, label: child.label, summary: child.summary, category: child.category ?? group.category ?? 'other', filePaths: child.filePaths })),
  })) ?? []
  const analysis = llm
    ? { overallSummary: llm.overallSummary, files: llm.files ?? [], model: llm.model, generatedAt: llm.generatedAt, sharedBy: llm.sharedBy }
    : undefined
  const tour = entry ? await session.cache.tours.latest(serializeRef(entry.diff.ref), entry.diff.head?.sha) : undefined
  const brief = entry ? await briefState(session.cache, entry.diff) : undefined
  // The questions offered under the last answer; none once the chat ends on a question or a failure.
  const suggestions = chat.at(-1)?.role === 'assistant' ? stored?.suggestions ?? [] : []
  return Response.json({ result: { threads, critique, chat, aiGroups, analysis, sharedComment: entry?.sharedComment, tour, brief, suggestions } })
}

/** GitHub review threads for one pull request, in the same shape the web diff anchors to lines. */
async function reviewThreads(args: unknown): Promise<Response> {
  const input = args as Record<string, unknown> | undefined
  const owner = textOf(input?.owner)
  const repo = textOf(input?.repo)
  const number = typeof input?.number === 'number' ? String(input.number) : textOf(input?.number)
  if (!owner || !repo || !/^\d+$/.test(number))
    return Response.json({ error: 'Review threads need owner, repo and number.' }, { status: 400 })
  const token = await resolveGithubToken(process.env)
  if (!token)
    return Response.json({ error: 'The server has no GitHub token.' }, { status: 400 })
  try {
    const source = createGithubSource({ kind: 'github-pr', owner, repo, number }, staticCredentials(token))
    const data = await source.reviews?.fetch()
    const threads = (data?.threads ?? []).filter(thread => thread.line !== undefined && !thread.outdated).map(thread => ({
      path: thread.path,
      side: thread.side,
      line: thread.line,
      rootId: thread.rootId,
      threadId: thread.threadId,
      resolved: thread.resolved === true,
      comments: thread.comments.map(comment => ({ author: comment.author?.login, body: comment.body })),
    }))
    return Response.json({ result: { threads } })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

async function githubReviews(args: unknown) {
  const input = args as Record<string, unknown> | undefined
  const owner = textOf(input?.owner)
  const repo = textOf(input?.repo)
  const number = typeof input?.number === 'number' ? String(input.number) : textOf(input?.number)
  if (!owner || !repo || !/^\d+$/.test(number))
    return undefined
  const token = await resolveGithubToken(process.env)
  if (!token)
    return undefined
  return createGithubSource({ kind: 'github-pr', owner, repo, number }, staticCredentials(token)).reviews
}

async function reviewReply(args: unknown): Promise<Response> {
  const reviews = await githubReviews(args)
  const rootId = Number((args as { rootId?: unknown } | undefined)?.rootId)
  const body = textOf((args as { body?: unknown } | undefined)?.body).trim()
  if (!reviews || !Number.isFinite(rootId) || !body)
    return Response.json({ error: 'A reply needs the pull request, the thread, and some text.' }, { status: 400 })
  try {
    await reviews.reply(rootId, body)
    return Response.json({ result: true })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

async function reviewResolve(args: unknown): Promise<Response> {
  const reviews = await githubReviews(args)
  const threadId = textOf((args as { threadId?: unknown } | undefined)?.threadId)
  if (!reviews || !threadId)
    return Response.json({ error: 'Resolving needs the pull request and the thread id.' }, { status: 400 })
  try {
    await reviews.resolveThread(threadId)
    return Response.json({ result: true })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

async function highlight(args: unknown): Promise<Response> {
  const input = args as { path?: unknown, lines?: unknown, dark?: unknown } | undefined
  const path = textOf(input?.path)
  const lines = Array.isArray(input?.lines) ? input.lines.filter((line): line is string => typeof line === 'string').slice(0, 400) : []
  if (!path)
    return Response.json({ error: 'Highlighting needs a path.' }, { status: 400 })
  const dark = input?.dark !== false
  const colored = await highlightLines(path, lines, dark)
  return Response.json({ result: { lines: colored, theme: pierreTheme(dark) } })
}

async function reviewComment(args: unknown): Promise<Response> {
  const reviews = await githubReviews(args)
  const input = args as Record<string, unknown> | undefined
  const path = textOf(input?.path)
  const side = textOf(input?.side)
  const line = Number(input?.line)
  const body = textOf(input?.body).trim()
  const headSha = textOf(input?.headSha)
  if (!reviews || !path || (side !== 'additions' && side !== 'deletions') || !Number.isFinite(line) || !body || !headSha)
    return Response.json({ error: 'A line comment needs the pull request, path, side, line, head sha, and text.' }, { status: 400 })
  // `review` adds to the viewer's pending review (or starts one), as the web composer's "Add review comment" does.
  const mode = input?.mode === 'review' ? 'review' : 'single'
  const startLine = Number(input?.startLine)
  const target: ReviewDraftTarget = Number.isFinite(startLine) && startLine < line ? { path, side, line, startLine, startSide: side } : { path, side, line }
  try {
    const pendingReview = mode === 'review' ? (await reviews.fetch()).pendingReview : undefined
    await reviews.addComment({ target, body, mode, headSha, pendingReview })
    return Response.json({ result: true })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}
