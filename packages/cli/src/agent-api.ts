import type { Accounts } from './account'
import type { ServerHost } from './host'
import type { NativeRequest } from './native-api'
import type { PostedMeta, PostedStore, PostFields } from './posted'
import { createAccounts } from './account'
import { createPostedStore, MAX_POST_BYTES, PostError } from './posted'
import { serverSession } from './server-session'

/**
 * The API agents call with an account's key (plan 13): `/api/v1/*`, the only part of the server that is meant to sit
 * outside Cloudflare Access. It answers nothing without a valid `Authorization: Bearer pully_…` key, and a key reaches
 * only its own account's posted diffs: no phone method, no GitHub token, no AI run.
 */

export interface AccountEnvironment {
  PULLY_ACCOUNT_EMAIL?: string
  PULLY_ACCOUNT_PASSWORD?: string
  /** The web client ID Google signs the phone in for (Google Cloud, Credentials); Google sign-in is off without it. */
  PULLY_GOOGLE_CLIENT_ID?: string
  /** Where links point, such as `https://prs.octaviantocan.com`; the request's own origin when unset. */
  PULLY_PUBLIC_URL?: string
}

export interface AccountServices {
  accounts: Accounts
  posted: PostedStore
  /** Whether the environment names an account; without one nobody can sign in. */
  hasAccount: boolean
}

const services = new WeakMap<ServerHost, Promise<AccountServices>>()

/** The host's accounts and posted diffs, with the environment's account seeded on first use. */
export function accountServices(host: ServerHost, env: AccountEnvironment): Promise<AccountServices> {
  let found = services.get(host)
  if (!found) {
    found = (async () => {
      const session = await serverSession(host)
      const accounts = createAccounts(session.storage)
      const seeded = await accounts.seed(env)
      return { accounts, posted: createPostedStore(session.storage, session.cache), hasAccount: !!seeded }
    })()
    found.catch(() => services.delete(host))
    services.set(host, found)
  }
  return found
}

/** Posts an hour one key may make before it is told to wait. */
export const POSTS_PER_HOUR = 120
const posts = new Map<string, number[]>()

function allowPost(keyId: string, now = Date.now()): number | undefined {
  const recent = (posts.get(keyId) ?? []).filter(at => now - at < 3_600_000)
  if (recent.length >= POSTS_PER_HOUR) {
    posts.set(keyId, recent)
    return Math.ceil((recent[0]! + 3_600_000 - now) / 1000)
  }
  posts.set(keyId, [...recent, now])
  return undefined
}

/** Forgets every key's posts this hour; for tests. */
export function resetPostLimits(): void {
  posts.clear()
}

function fail(status: number, error: string, headers?: Record<string, string>): Response {
  return Response.json({ error }, { status, headers })
}

const TOO_LARGE = Symbol('too large')

async function readText(request: NativeRequest, limit: number): Promise<string | typeof TOO_LARGE> {
  if (!request.body)
    return ''
  const reader = request.body.getReader()
  const decoder = new TextDecoder()
  let size = 0
  let out = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done)
      break
    size += value.byteLength
    if (size > limit) {
      await reader.cancel().catch(() => {})
      return TOO_LARGE
    }
    out += decoder.decode(value, { stream: true })
  }
  return out + decoder.decode()
}

/** The patch and fields of a post: JSON `{ patch, ... }`, or the patch as the body with the title in `X-Pully-Title`. */
async function readPost(request: NativeRequest): Promise<{ patch?: string, fields: PostFields } | Response> {
  const body = await readText(request, MAX_POST_BYTES)
  if (body === TOO_LARGE)
    return fail(413, `A request can be at most ${MAX_POST_BYTES / 1024 / 1024} MB.`)
  const type = request.headers?.get('content-type') ?? ''
  if (type.includes('json')) {
    let parsed: unknown
    try {
      parsed = JSON.parse(body)
    }
    catch {
      return fail(400, 'That body is not JSON.')
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
      return fail(400, 'Send a JSON object: { "patch": "...", "title": "..." }.')
    const { patch, ...fields } = parsed as PostFields & { patch?: unknown }
    if (patch !== undefined && typeof patch !== 'string')
      return fail(400, '`patch` must be the diff as text.')
    return { patch, fields }
  }
  const header = (name: string) => {
    const value = request.headers?.get(name)
    return value ? decodeURIComponent(value) : undefined
  }
  return {
    patch: body.trim() ? body : undefined,
    fields: { title: header('x-pully-title'), author: header('x-pully-author'), allowSecrets: header('x-pully-allow-secrets') === 'true' },
  }
}

function linkOf(origin: string, id: string): string {
  return `${origin.replace(/\/+$/, '')}/-/${id}`
}

function described(meta: PostedMeta, origin: string) {
  const { accountId: _accountId, chunks: _chunks, ...shown } = meta
  return { ...shown, url: linkOf(origin, meta.id) }
}

/** Whose key a call carries: the account and the key. */
type Caller = NonNullable<Awaited<ReturnType<Accounts['byKey']>>>

const NO_SUCH_DIFF = 'There is no posted diff with that id.'

async function postDiff(request: NativeRequest, caller: Caller, posted: PostedStore, origin: string, id?: string): Promise<Response> {
  const wait = allowPost(caller.key.id)
  if (wait !== undefined)
    return fail(429, `This key made ${POSTS_PER_HOUR} posts in the last hour. Try again in ${Math.ceil(wait / 60)} minutes.`, { 'Retry-After': String(wait) })
  const input = await readPost(request)
  if (input instanceof Response)
    return input
  const { meta, warnings } = await posted.save({ accountId: caller.account.id, keyName: caller.key.name, id, patch: input.patch, fields: input.fields })
  return Response.json({ id: meta.id, url: linkOf(origin, meta.id), version: meta.version, files: meta.files, warnings }, { status: id ? 200 : 201 })
}

async function readDiff(caller: Caller, posted: PostedStore, origin: string, id: string): Promise<Response> {
  const found = await posted.read(caller.account.id, id)
  if (!found)
    return fail(404, NO_SUCH_DIFF)
  return Response.json({ ...described(found.meta, origin), patch: found.patch, ...found.agent })
}

async function deleteDiff(caller: Caller, posted: PostedStore, id: string): Promise<Response> {
  return await posted.remove(caller.account.id, id) ? new Response(null, { status: 204 }) : fail(404, NO_SUCH_DIFF)
}

/** The call a route and method name, or undefined for one the API does not have. */
function agentRoute(request: NativeRequest, route: string, caller: Caller, posted: PostedStore, origin: string): Promise<Response> | undefined {
  const id = route.match(/^\/diffs\/([^/]+)$/)?.[1]
  const method = request.method
  if (route === '/me' && method === 'GET')
    return Promise.resolve(Response.json({ account: caller.account.email, key: caller.key.name }))
  if (route === '/diffs' && method === 'GET')
    return posted.list(caller.account.id).then(list => Response.json({ diffs: list.map(meta => described(meta, origin)) }))
  if (route === '/diffs' && method === 'POST')
    return postDiff(request, caller, posted, origin)
  if (!id)
    return undefined
  if (method === 'PUT')
    return postDiff(request, caller, posted, origin, id)
  if (method === 'GET')
    return readDiff(caller, posted, origin, id)
  if (method === 'DELETE')
    return deleteDiff(caller, posted, id)
  return undefined
}

/**
 * Answers `/api/v1/*`, or `undefined` for any other path.
 * @param request - The HTTP request.
 * @param host - Where the server runs.
 * @param env - The account and the public address.
 */
export async function handleAgentRequest(request: NativeRequest, host: ServerHost, env: AccountEnvironment): Promise<Response | undefined> {
  // Any spelling of `/api/v1` (`/API/v1`, `//api//v1`) is the API's, so it never falls through to the app behind Access.
  const path = request.url.pathname.replace(/\/{2,}/g, '/')
  if (!/^\/api\/v1(?:\/|$)/i.test(path))
    return undefined
  const { accounts, posted } = await accountServices(host, env)
  const bearer = request.headers?.get('authorization')?.match(/^Bearer\s+(\S+)$/i)?.[1]
  const caller = await accounts.byKey(bearer)
  if (!caller)
    return fail(401, 'This needs an API key: send `Authorization: Bearer pully_...`. Make one in Pully, Settings, Agent keys.', { 'WWW-Authenticate': 'Bearer' })
  const origin = env.PULLY_PUBLIC_URL?.trim() || request.url.origin
  const route = path.slice('/api/v1'.length).replace(/\/+$/, '')
  try {
    const answer = agentRoute(request, route, caller, posted, origin)
    if (answer)
      return await answer
  }
  catch (error) {
    if (error instanceof PostError)
      return fail(error.status, error.message)
    return fail(500, `The server could not do that: ${error instanceof Error ? error.message : String(error)}`)
  }
  return fail(404, 'There is no such call. The API has GET /api/v1/me, GET and POST /api/v1/diffs, and GET, PUT and DELETE /api/v1/diffs/<id>.')
}
