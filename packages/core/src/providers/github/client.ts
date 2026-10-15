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
  authenticated?: () => Promise<boolean>
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

/** Without credentials every request is anonymous. The token is read per request, never captured. */
export function createGithubClient(credentials?: Credentials): GithubClient {
  const token = async () => credentials?.githubToken()

  async function request(path: string, options: GithubRequestOptions = {}): Promise<Response> {
    if (credentials?.githubRequest)
      return credentials.githubRequest(path, options)
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
    const res = await fetch(url, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
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
    authenticated: () => credentials?.githubAuthenticated?.() ?? token().then(Boolean),
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
