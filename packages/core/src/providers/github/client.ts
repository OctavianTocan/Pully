import type { Credentials } from '../../types/source'

const GITHUB_API_BASE = 'https://api.github.com'
const PAGE_SIZE = 100
const PAGE_CONCURRENCY = 6

/** A non-ok GitHub response, keeping the status so callers can react to auth/permission failures (401/403). */
export class GithubApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'GithubApiError'
  }
}

export interface GithubRequestOptions {
  method?: string
  body?: object
  /** Media type, e.g. `application/vnd.github.raw` for a file body or `.diff` for diff text. */
  accept?: string
}

/** Every GitHub REST and GraphQL call goes through one of these, bound to the viewer's token. */
export interface GithubClient {
  /** The token requests are sent with; `undefined` means anonymous. */
  token: () => Promise<string | undefined>
  /** `path` is relative to the API root (`/repos/...`). Throws `GithubApiError` on a non-ok response. */
  request: (path: string, options?: GithubRequestOptions) => Promise<Response>
  /** Every item across the `per_page=100` pages, in order. */
  paginate: <T>(path: string) => Promise<T[]>
  graphql: <T>(query: string, variables: Record<string, string | number | null>) => Promise<T>
}

/** The `page` of the `rel="last"` link in a `Link` header. */
function lastPage(link: string | null): number | undefined {
  const last = link?.split(',').find(part => part.includes('rel="last"'))
  const target = last?.slice(last.indexOf('<') + 1, last.indexOf('>'))
  const page = target ? new URL(target, 'https://api.github.com').searchParams.get('page') : null
  return page ? Number(page) : undefined
}

/** `map` with at most `limit` calls in flight, results in input order. */
async function mapLimited<T, R>(inputs: T[], limit: number, fn: (input: T) => Promise<R>): Promise<R[]> {
  const results: R[] = Array.from({ length: inputs.length })
  let next = 0
  async function worker() {
    while (next < inputs.length) {
      const index = next++
      results[index] = await fn(inputs[index]!)
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, inputs.length) }, worker))
  return results
}

/** One finished GitHub request as the server's analytics sees it: no owner, repo, token or body. */
export interface GithubCallInfo {
  /** The API path with the owner, repo, numbers and SHAs left out, such as `pulls/:n/files` or `graphql`. */
  route: string
  method: string
  /** The HTTP status, or 0 when the request never got an answer. */
  status: number
  ms: number
  /** `x-ratelimit-remaining`, when GitHub sent it. */
  rateLeft?: number
}

let observer: ((call: GithubCallInfo) => void) | undefined

/** Has every GitHub request reported to `fn` (the server's analytics); `undefined` stops it. */
export function observeGithubCalls(fn: ((call: GithubCallInfo) => void) | undefined): void {
  observer = fn
}

const GITHUB_ROUTE_WORDS = new Set(['actions', 'blobs', 'branches', 'check-runs', 'check-suites', 'commits', 'comments', 'compare', 'contents', 'events', 'files', 'git', 'graphql', 'issues', 'jobs', 'labels', 'merge', 'pulls', 'rate_limit', 'refs', 'requested_reviewers', 'reviews', 'runs', 'search', 'status', 'statuses', 'timeline', 'trees', 'user', 'users'])

/**
 * The shape of an API path without what names a repository or a pull request:
 * `/repos/acme/app/pulls/7/files?page=2` is `pulls/:n/files`.
 */
export function githubRoute(path: string): string {
  const parts = path.split('?')[0]!.split('/').filter(Boolean)
  const rest = parts[0] === 'repos' ? parts.slice(3) : parts
  // Only GitHub's own words stay; a branch, a file path, a user or a SHA becomes `:n`.
  const route = rest.slice(0, 4).map(part => GITHUB_ROUTE_WORDS.has(part) ? part : ':n').join('/')
  return route || (parts[0] === 'repos' ? 'repo' : 'root')
}

function report(path: string, method: string, started: number, res: Response | undefined): void {
  if (!observer)
    return
  const left = Number(res?.headers.get('x-ratelimit-remaining') ?? Number.NaN)
  try {
    observer({ route: githubRoute(path), method, status: res?.status ?? 0, ms: Date.now() - started, ...(Number.isFinite(left) ? { rateLeft: left } : {}) })
  }
  catch {}
}

/** Without credentials every request is anonymous. The token is read per request, never captured. */
export function createGithubClient(credentials?: Credentials): GithubClient {
  const token = async () => credentials?.githubToken()

  async function request(path: string, options: GithubRequestOptions = {}): Promise<Response> {
    const auth = await token()
    const headers: Record<string, string> = {
      'Accept': options.accept ?? 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    }
    if (auth)
      headers.Authorization = `Bearer ${auth}`
    if (options.body !== undefined)
      headers['Content-Type'] = 'application/json'
    const url = `${GITHUB_API_BASE}${path}`
    const method = options.method ?? 'GET'
    const started = Date.now()
    let res: Response
    try {
      res = await fetch(url, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      })
    }
    catch (error) {
      report(path, method, started, undefined)
      throw error
    }
    report(path, method, started, res)
    if (!res.ok) {
      // GitHub's own `message` (e.g. "Resource not accessible by personal access token")
      // makes a permission failure actionable instead of a bare status.
      let message: string | undefined
      try {
        message = (await res.json() as { message?: string }).message
      }
      catch {}
      throw new GithubApiError(res.status, message ?? `GitHub API request failed (${res.status}): ${url}`)
    }
    return res
  }

  return {
    token,
    request,
    async paginate<T>(path: string) {
      const separator = path.includes('?') ? '&' : '?'
      const pageUrl = (page: number) => `${path}${separator}per_page=${PAGE_SIZE}&page=${page}`
      const first = await request(pageUrl(1))
      const items: T[] = await first.json()
      if (items.length < PAGE_SIZE)
        return items
      const last = lastPage(first.headers.get('Link'))
      if (last !== undefined) {
        const rest = await mapLimited(Array.from({ length: last - 1 }, (_, i) => i + 2), PAGE_CONCURRENCY, async page => await (await request(pageUrl(page))).json() as T[])
        return items.concat(...rest)
      }
      for (let page = 2; ; page++) {
        const pageItems: T[] = await (await request(pageUrl(page))).json()
        items.push(...pageItems)
        if (pageItems.length < PAGE_SIZE)
          return items
      }
    },
    async graphql<T>(query: string, variables: Record<string, string | number | null>) {
      const payload: { data?: T, errors?: { type?: string, message: string }[] } = await (await request('/graphql', { method: 'POST', body: { query, variables } })).json()
      if (payload.errors?.length) {
        // GraphQL reports permission problems as FORBIDDEN errors on a 200 - map them
        // to the same error type/status the REST layer throws so gating logic is shared.
        const forbidden = payload.errors.some(error => error.type === 'FORBIDDEN')
        throw new GithubApiError(forbidden ? 403 : 500, payload.errors.map(error => error.message).join('; '))
      }
      if (!payload.data)
        throw new GithubApiError(500, 'GitHub GraphQL response contained no data')
      return payload.data
    },
  }
}
