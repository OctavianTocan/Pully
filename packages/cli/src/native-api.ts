import type { DiffCache } from '@pulls.review/core/cache'
import type { DiffSource, ReviewDraftTarget } from '@pulls.review/core/types'
import type { ServerHost } from './host'
import type { OpenedDiff } from './stored-diff'
import { Buffer } from 'node:buffer'
import process from 'node:process'
import { createRuleBasedAdapter } from '@pulls.review/core/analyze'
import { createGithubSource } from '@pulls.review/core/github'
import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { serializeRef, staticCredentials } from '@pulls.review/core/types'
import { cleanSettings } from './account'
import { accountServices, handleAgentRequest } from './agent-api'
import { ingestAnalytics, track } from './analytics'
import { resolveGithubToken } from './credentials'
import { githubStats } from './github-stats'
import { googleAllowed, googleEmail } from './google-sign-in'
import { highlightLines, pierreTheme } from './highlight'
import { NO_CHECKOUT } from './host'
import { NATIVE_REVIEW, sessionPatches } from './native-review'
import { serverFunctions, serverSession } from './server-session'
import { openStoredDiff, trimPatches } from './stored-diff'

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
  LOCAL_RPC.analyticsSummary,
])

export interface NativeRequest {
  url: URL
  method: string
  json: () => Promise<unknown>
  /** The raw body, so a route can stop reading past a size limit. */
  body?: ReadableStream<Uint8Array> | null
  /** The request's headers: the account's session (`X-Pully-Session`) and an agent's key ride here. */
  headers?: Headers
}

/** The most `POST /api/events` reads: a full batch of the largest events, with room for the envelope. */
export const EVENTS_BODY_LIMIT = 2 * 1024 * 1024

const TOO_LARGE = Symbol('too large')

/** The body as JSON, `undefined` when it is not JSON, or `TOO_LARGE` past `limit` bytes (reading stops there). */
async function readJsonUpTo(request: NativeRequest, limit: number): Promise<unknown> {
  if (!request.body)
    return request.json().catch(() => undefined)
  const reader = request.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done)
      break
    size += value.byteLength
    if (size > limit) {
      await reader.cancel().catch(() => {})
      return TOO_LARGE
    }
    chunks.push(value)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  }
  catch {
    return undefined
  }
}

async function androidFile(host: ServerHost, name: string, type: string, missing?: () => Response): Promise<Response> {
  const file = await host.androidFile?.(name)
  if (!file)
    return missing?.() ?? Response.json({ error: 'No Android build has been published.' }, { status: 404 })
  return new Response(file.body, {
    headers: {
      'Content-Type': type,
      'Content-Length': String(file.size),
      'Cache-Control': 'no-store',
    },
  })
}

/**
 * The phone's HTTP API: the same RPC handlers the page uses, plus the updater files.
 * Returns undefined when the request is not one of ours.
 * @param name - The RPC name.
 * @param host - Where the server runs.
 * @param args - The decoded JSON body.
 */
function nativeCall(name: string, host: ServerHost, args: unknown): Promise<Response> | undefined {
  if (name === 'github-pr' || name === 'github-compare' || name === 'github-commit')
    return openGithub(host, name, args)
  const calls: Record<string, (host: ServerHost, args: unknown) => Promise<Response>> = {
    'local-diff': openLocal,
    'review-state': reviewState,
    'review-threads': (_host, input) => reviewThreads(input),
    'review-reply': (_host, input) => reviewReply(input),
    'review-resolve': (_host, input) => reviewResolve(input),
    'highlight': (_host, input) => highlight(input),
    'repo-viewed': repoViewed,
    'mermaid-svg': (_host, input) => mermaidSvg(input),
    'review-comment': (_host, input) => reviewComment(input),
    'github-stats': (_host, input) => profileStats(input),
  }
  const call = calls[name]
  if (call)
    return call(host, args)
  const review = NATIVE_REVIEW[name]
  if (!review)
    return undefined
  return review(host, args as Record<string, unknown> | undefined)
    .then(result => Response.json({ result: result ?? null }))
    .catch(error => Response.json({ error: error instanceof Error ? error.message : String(error) }, { status: 400 }))
}

const APK = 'application/vnd.android.package-archive'
const PICTURE: Record<string, string> = { png: 'image/png', webp: 'image/webp', gif: 'image/gif', jpg: 'image/jpeg' }

/**
 * The updater's files, and the past versions of the app that android/scripts/build-past-ui.py rebuilt from git for the
 * dev-only version slider. Only these names are served from the Android folder.
 * @param host - Where the server runs; it reads the files.
 * @param path - The request path.
 */
function androidRoute(host: ServerHost, path: string): Promise<Response> | undefined {
  if (path === '/android/latest.json')
    return androidFile(host, 'latest.json', 'application/json')
  if (path === '/android/pulls.apk')
    return androidFile(host, 'pulls.apk', APK)
  // None built yet is an empty list, so the phone says there are none instead of "The server did not answer" (M3).
  if (path === '/android/past/index.json')
    return androidFile(host, 'past/index.json', 'application/json', () => Response.json({ versions: [] }, { headers: { 'Cache-Control': 'no-store' } }))
  const past = /^\/android\/past\/(\d+\.\d+\.\d+)\.apk$/.exec(path)
  if (past)
    return androidFile(host, `past/${past[1]}.apk`, APK)
  // A changelog's pictures, for an update the phone has not installed yet (android/changelog/, copied by publish-apk.sh).
  const picture = /^\/android\/changelog\/(\d+\.\d+\.\d+)\/([a-z0-9-]+)\.(png|webp|gif|jpg)$/.exec(path)
  return picture ? androidFile(host, `changelog/${picture[1]}/${picture[2]}.${picture[3]}`, PICTURE[picture[3]]) : undefined
}

/**
 * `POST /api/events`: a client's batch of analytics events, cleaned and appended to the
 * day files (`analytics.ts`). Answers 204 without waiting for the write.
 */
async function analyticsEvents(request: NativeRequest): Promise<Response> {
  const body = await readJsonUpTo(request, EVENTS_BODY_LIMIT).catch(() => undefined)
  if (body === TOO_LARGE)
    return new Response(null, { status: 413 })
  ingestAnalytics(body)
  return new Response(null, { status: 204 })
}

/** Notes a failed call in analytics as `error.server`, with the message the client got. */
async function noteFailure(name: string, response: Response): Promise<Response> {
  if (response.ok)
    return response
  const message = await response.clone().json().then(body => (body as { error?: unknown }).error).catch(() => undefined)
  track('error.server', { route: 'rpc', method: name.slice(0, 64), status: response.status, message: typeof message === 'string' ? message : undefined })
  return response
}

type AccountServicesOf = Awaited<ReturnType<typeof accountServices>>

interface AccountContext extends AccountServicesOf {
  host: ServerHost
  /** The web client ID Google tokens must be for; undefined while Google sign-in is off. */
  googleClientId: string | undefined
  accountId: string
  session: string
}

/**
 * The phone's calls that belong to the signed-in account (plan 13): `account-sign-in` takes an email and a password,
 * `account-google-sign-in` a Google ID token (making that email's account the first time), and every other one needs
 * the session either answered, in `X-Pully-Session`. `account-status` names the Google client ID when Google sign-in is on. The rest of the phone API stays as it was.
 */
const ACCOUNT_CALLS: Record<string, { open?: true, run: (context: AccountContext, args: Record<string, unknown>) => Promise<Response> }> = {
  'account-sign-in': { open: true, run: signIn },
  'account-google-sign-in': { open: true, run: googleSignIn },
  // `hasAccount` asks the phone to sign in: true when it can, with the test account's password or with Google.
  'account-status': { open: true, run: async ({ hasAccount, googleClientId }) => Response.json({ result: { hasAccount: hasAccount || !!googleClientId, googleClientId } }) },
  'account-sign-out': {
    run: async ({ accounts, session }) => {
      await accounts.signOut(session)
      return Response.json({ result: true })
    },
  },
  'account-me': { run: async ({ accounts, session }) => Response.json({ result: await accounts.bySession(session) }) },
  'agent-keys': { run: async ({ accounts, accountId }) => Response.json({ result: await accounts.keys(accountId) }) },
  'agent-key-create': { run: async ({ accounts, accountId }, args) => Response.json({ result: await accounts.createKey(accountId, textOf(args.name)) }) },
  'agent-key-revoke': { run: async ({ accounts, accountId }, args) => Response.json({ result: await accounts.revokeKey(accountId, textOf(args.id)) }) },
  'account-settings': { run: async ({ accounts, accountId }) => Response.json({ result: { values: await accounts.settings(accountId) } }) },
  'account-settings-merge': {
    run: async ({ accounts, accountId }, args) => {
      const values = cleanSettings(args.values)
      if (!values)
        return Response.json({ error: 'Settings need `values`: { name: { value, at } }.' }, { status: 400 })
      return Response.json({ result: { values: await accounts.mergeSettings(accountId, values) } })
    },
  },
  'posted-list': {
    run: async ({ posted, accountId }) => Response.json({
      result: (await posted.list(accountId)).map(meta => ({ id: meta.id, title: meta.title, author: meta.author ?? meta.keyName, key: meta.keyName, version: meta.version, createdAt: meta.createdAt, updatedAt: meta.updatedAt, files: meta.files, additions: meta.additions, deletions: meta.deletions })),
    }),
  },
  'posted-diff': {
    run: async ({ posted, accountId }, args) => {
      const opened = await posted.open(accountId, textOf(args.id))
      if (!opened)
        return Response.json({ error: 'There is no posted diff with that id.' }, { status: 404 })
      return Response.json({ result: { ...opened, groups: await ruleGroups(opened.diff) } })
    },
  },
  'posted-delete': { run: async ({ posted, accountId }, args) => Response.json({ result: await posted.remove(accountId, textOf(args.id)) }) },
}

async function signIn(context: AccountContext, args: Record<string, unknown>): Promise<Response> {
  if (!context.hasAccount)
    return Response.json({ error: 'Email and password is only for the test account, and this server has none. Sign in with Google.' }, { status: 401 })
  const signed = await context.accounts.signIn(textOf(args.email), textOf(args.password))
  if ('error' in signed) {
    return signed.error === 'wait'
      ? Response.json({ error: 'Too many wrong passwords. Wait 15 minutes, then try again.' }, { status: 429 })
      : Response.json({ error: 'That email and password don\'t match.' }, { status: 401 })
  }
  return Response.json({ result: { session: signed.session, email: signed.account.email } })
}

async function googleSignIn(context: AccountContext, args: Record<string, unknown>): Promise<Response> {
  if (!context.googleClientId)
    return Response.json({ error: 'This server has no Google sign-in. Set PULLY_GOOGLE_CLIENT_ID on it.' }, { status: 401 })
  const email = await googleEmail(textOf(args.idToken), context.googleClientId)
  if (!email)
    return Response.json({ error: 'Google could not confirm that sign-in. Try again.' }, { status: 401 })
  if (!googleAllowed(email))
    return Response.json({ error: `${email} can't sign in to Pully. Pick another Google account.` }, { status: 403 })
  const signed = await context.accounts.signInWithGoogle(email)
  return Response.json({ result: { session: signed.session, email: signed.account.email } })
}

async function accountCall(call: (typeof ACCOUNT_CALLS)[string], host: ServerHost, session: string | undefined, args: unknown): Promise<Response> {
  const services = await accountServices(host, process.env)
  const input = (args && typeof args === 'object' ? args : {}) as Record<string, unknown>
  const account = call.open ? undefined : await services.accounts.bySession(session)
  if (!call.open && !account)
    return Response.json({ error: 'Sign in first.' }, { status: 401 })
  try {
    const googleClientId = process.env.PULLY_GOOGLE_CLIENT_ID?.trim() || undefined
    return await call.run({ ...services, host, googleClientId, accountId: account?.id ?? '', session: session ?? '' }, input)
  }
  catch (error) {
    return Response.json({ error: error instanceof Error ? error.message : String(error) }, { status: 400 })
  }
}

/**
 * Answers a request when it is one of the phone's, else undefined.
 * @param request - The HTTP request.
 * @param host - Where the server runs (`nodeHost` in the CLI).
 */
export async function handleNativeRequest(request: NativeRequest, host: ServerHost): Promise<Response | undefined> {
  const path = request.url.pathname
  const agent = await handleAgentRequest(request, host, process.env)
  if (agent)
    return agent
  const android = request.method === 'GET' ? androidRoute(host, path) : undefined
  if (android)
    return android
  if (request.method === 'POST' && path === '/api/events')
    return analyticsEvents(request)
  if (request.method !== 'POST' || path !== '/api/rpc')
    return undefined

  const body = await request.json().catch(() => undefined) as { name?: unknown, args?: unknown } | undefined
  const name = typeof body?.name === 'string' ? body.name : ''
  const account = ACCOUNT_CALLS[name]
  if (account)
    return noteFailure(name, await accountCall(account, host, request.headers?.get('x-pully-session') ?? undefined, body?.args))
  return noteFailure(name, await rpcCall(name, body?.args, host))
}

async function rpcCall(name: string, args: unknown, host: ServerHost): Promise<Response> {
  const native = nativeCall(name, host, args)
  if (native)
    return native
  if (!ALLOWED.has(name))
    return Response.json({ error: 'That method is not available to the app.' }, { status: 404 })
  const fn = (await serverFunctions(host)).find(candidate => candidate.name === name)
  if (!fn)
    return Response.json({ error: 'That method is not available to the app.' }, { status: 404 })
  try {
    const result = await (fn.handler as (input?: unknown) => Promise<unknown>)(args)
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

/** How a phone asked for a GitHub diff: `lite` trims patches to a budget, `fresh` skips the stored copy. */
interface OpenFlags {
  lite: boolean
  fresh: boolean
}

async function openGithub(host: ServerHost, name: string, args: unknown): Promise<Response> {
  const ref = githubRef(name, args)
  if (typeof ref === 'string')
    return Response.json({ error: ref }, { status: 400 })
  const input = args as Record<string, unknown> | undefined
  return saveGithub(host, ref, { lite: input?.lite === true, fresh: input?.fresh === true })
}

async function openLocal(host: ServerHost, args: unknown): Promise<Response> {
  if (!host.cwd)
    return Response.json({ error: NO_CHECKOUT }, { status: 404 })
  const target = (args as { target?: unknown } | undefined)?.target
  if (typeof target !== 'string')
    return Response.json({ error: 'A local diff needs a git target.' }, { status: 400 })
  const functions = await serverFunctions(host)
  const fetch = functions.find(fn => fn.name === LOCAL_RPC.sourceFetch)
  const keyOf = functions.find(fn => fn.name === LOCAL_RPC.sourceKey)
  try {
    const diff = await (fetch!.handler as (input: unknown) => Promise<import('@pulls.review/core/types').DiffsPayload>)({ target })
    const key = String(await (keyOf!.handler as (input: unknown) => Promise<unknown>)({ target }))
    await (await serverSession(host)).cache.diffs.putDiff(key, diff, diff.head?.sha ?? '')
    return Response.json({ result: { key, diff, groups: await ruleGroups(diff) } })
  }
  catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    return Response.json({ error: message }, { status: 400 })
  }
}

async function openGithubDiff(ref: Parameters<typeof createGithubSource>[0], source: DiffSource, diffs: DiffCache, fresh: boolean): Promise<OpenedDiff & { key: string }> {
  if (ref.kind === 'github-pr') {
    const key = serializeRef(ref)
    return { key, ...await openStoredDiff({ key, source, diffs, fresh }) }
  }
  // GitHub caps a compare or a commit at 300 files, so they keep fetching every time.
  const diff = await source.fetch()
  const key = serializeRef(diff.ref)
  await diffs.putDiff(key, diff, diff.head?.sha ?? '')
  return { key, diff }
}

/**
 * A pull request comes from the server's store when its head has not moved (see
 * `openStoredDiff`); a compare or a commit is fetched every time. Either way the full diff
 * is stored, and `lite` only trims what this answer carries: the files left without hunks are
 * marked `patchOmitted`, and `diff-patches` serves them. Rule groups cover the full diff.
 */
async function saveGithub(host: ServerHost, ref: Parameters<typeof createGithubSource>[0], flags: OpenFlags): Promise<Response> {
  const token = await resolveGithubToken(process.env)
  if (!token)
    return Response.json({ error: 'The server has no GitHub token.' }, { status: 400 })
  try {
    const source = createGithubSource(ref, staticCredentials(token))
    const { key, diff, stale } = await openGithubDiff(ref, source, (await serverSession(host)).cache.diffs, flags.fresh)
    const patches = await sessionPatches(host)
    patches.remember(key, diff.head?.sha ?? '', diff)
    const groups = await ruleGroups(diff)
    const sent = flags.lite ? trimPatches(diff) : { diff, omitted: 0 }
    return Response.json({ result: { key, diff: sent.diff, groups, omitted: sent.omitted, ...(stale ? { stale: true } : {}) } })
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
async function repoViewed(host: ServerHost, input: unknown): Promise<Response> {
  const { owner, repo } = (input ?? {}) as { owner?: unknown, repo?: unknown }
  if (typeof owner !== 'string' || typeof repo !== 'string')
    return Response.json({ error: 'That needs an owner and a repo.' }, { status: 400 })
  // Diffs the server stored ahead of time but nobody opened (`lastViewedAt` 0) are left out.
  const entries = await (await serverSession(host)).cache.diffs.listMatching(({ ref, lastViewedAt }) => lastViewedAt !== 0 && ref.kind === 'github-pr' && ref.owner === owner && ref.repo === repo)
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

/** `github-stats`: the signed-in user's year on GitHub for the profile sheet (M5); `fresh` skips the few minutes' reuse. */
async function profileStats(input: unknown): Promise<Response> {
  const fresh = (input as { fresh?: unknown } | undefined)?.fresh === true
  try {
    return Response.json({ result: await githubStats(fresh) })
  }
  catch (error) {
    return Response.json({ error: error instanceof Error ? error.message : String(error) }, { status: 502 })
  }
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
async function reviewState(host: ServerHost, args: unknown): Promise<Response> {
  const key = textOf((args as { key?: unknown } | undefined)?.key)
  if (!key)
    return Response.json({ error: 'A review state needs a key.' }, { status: 400 })
  const session = await serverSession(host)
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
