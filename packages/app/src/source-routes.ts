import type { SourceRef } from '@pulls.review/core/types'
import type { RouteComponent, RouteLocation, RouteRecordRaw } from 'vue-router'

/**
 * The one place that maps diff refs to app routes and back. Components link through
 * these helpers instead of spelling out paths. A PR lives at `/{owner}/{repo}/{number}`;
 * github.com's own shapes and the old `/gh/...` paths redirect there, so swapping the
 * host of a github.com link is enough to open it here.
 */

/** Every ref kind with a page of its own in this build. */
export type RoutableRef = Extract<SourceRef, { kind: 'github-pr' | 'github-compare' | 'github-commit' }>

/** One route per routable ref kind, named after the kind so a route reads back into its ref. */
export function routes(component: () => Promise<RouteComponent>): RouteRecordRaw[] {
  // The page receives its ref as the `sourceRef` prop rather than reading the route.
  const props = (route: RouteLocation) => ({ sourceRef: refFromRoute(route) })
  return [
    { name: 'github-pr', path: '/:owner/:repo/:number(\\d+)', component, props },
    // A range is `base...head`; either side may be a branch with slashes.
    { name: 'github-compare', path: '/:owner/:repo/compare/:range(.+\\.\\.\\..+)', component, props },
    { name: 'github-commit', path: '/:owner/:repo/commit/:sha', component, props },
    { path: '/:owner/:repo/pull/:number(\\d+)/:rest(.*)?', redirect: to => `/${to.params.owner}/${to.params.repo}/${to.params.number}` },
    { path: '/:owner/:repo/commits/:sha', redirect: to => `/${to.params.owner}/${to.params.repo}/commit/${to.params.sha}` },
    { path: '/gh/:rest(.*)', redirect: to => ({ path: `/${to.params.rest}`, query: to.query, hash: to.hash }) },
  ]
}

/** A repo's PR list, plus github.com's `/pulls` shape redirecting to it. */
export function repoRoutes(component: () => Promise<RouteComponent>): RouteRecordRaw[] {
  return [
    { path: '/:owner/:repo', component },
    { path: '/:owner/:repo/pulls', redirect: to => `/${to.params.owner}/${to.params.repo}` },
  ]
}

export function routeForRef(ref: RoutableRef): string
export function routeForRef(ref: SourceRef): string | undefined
export function routeForRef(ref: SourceRef): string | undefined {
  switch (ref.kind) {
    case 'github-pr':
      return `/${ref.owner}/${ref.repo}/${ref.number}`
    case 'github-compare':
      return `/${ref.owner}/${ref.repo}/compare/${ref.base}...${ref.head}`
    case 'github-commit':
      return `/${ref.owner}/${ref.repo}/commit/${ref.sha}`
    case 'paste':
      // Deliberately unroutable: a paste has no live source to reopen from a link.
      return undefined
    case 'local':
      // Only the local server can open it (plans/09).
      return undefined
    case 'posted':
      // Only the phone app opens it (plan 13; Tavi, 16 Oct 14:17).
      return undefined
  }
}

/** `base...head` into its two sides; `undefined` without the three dots. */
function splitRange(range: string): { base: string, head: string } | undefined {
  const dots = range.indexOf('...')
  return dots > 0 && dots + 3 < range.length ? { base: range.slice(0, dots), head: range.slice(dots + 3) } : undefined
}

export function refFromRoute(route: Pick<RouteLocation, 'name' | 'params'>): RoutableRef | undefined {
  const param = (name: string) => String(route.params[name])
  const owner = param('owner')
  const repo = param('repo')
  switch (route.name) {
    case 'github-pr':
      return { kind: 'github-pr', owner, repo, number: param('number') }
    case 'github-compare': {
      const range = splitRange(param('range'))
      return range && { kind: 'github-compare', owner, repo, ...range }
    }
    case 'github-commit':
      return { kind: 'github-commit', owner, repo, sha: param('sha') }
  }
}

export function repoRoute(owner: string, repo: string): string {
  return `/${owner}/${repo}`
}

/** Where a diff sits, e.g. its repo's PR list - `undefined` for a ref with no parent view. */
export function parentForRef(ref: SourceRef): { route: string, label: string } | undefined {
  return ref.kind === 'paste' || ref.kind === 'local' || ref.kind === 'posted'
    ? undefined
    : { route: repoRoute(ref.owner, ref.repo), label: `${ref.owner}/${ref.repo}` }
}

/**
 * A pasted github.com URL as an app route: a PR, compare or commit opens its diff,
 * a bare repo (or its `/pulls`) the PR list.
 */
export function routeFromGithubUrl(text: string): string | undefined {
  const match = text.trim().match(/github\.com\/([^/\s]+)\/([^/\s#?]+)(?:\/(pull|commit|compare)\/([^\s#?]+)|\/pulls\/?)?(?:[/?#]|$)/)
  if (!match)
    return undefined
  const [, owner = '', repo = '', kind, rest = ''] = match
  if (kind === 'pull') {
    const number = rest.match(/^\d+/)?.[0]
    return number ? routeForRef({ kind: 'github-pr', owner, repo, number }) : undefined
  }
  if (kind === 'commit')
    return routeForRef({ kind: 'github-commit', owner, repo, sha: rest.replace(/\/.*$/, '') })
  if (kind === 'compare') {
    const range = splitRange(rest)
    return range && routeForRef({ kind: 'github-compare', owner, repo, ...range })
  }
  return repoRoute(owner, repo)
}
