import type { RouteLocationNormalized } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { diffFacts } from '../analytics'
import { screenOf } from './analytics'

function route(path: string, name?: string): Pick<RouteLocationNormalized, 'matched' | 'name'> {
  return { name, matched: [{ path }] as RouteLocationNormalized['matched'] }
}

describe('screenOf', () => {
  it('names a page by the route it matched, never by its owner, repo or number', () => {
    expect(screenOf(route('/'))).toBe('home')
    expect(screenOf(route('/stats'))).toBe('stats')
    expect(screenOf(route('/:owner/:repo'))).toBe('repo')
    expect(screenOf(route('/:owner/:repo/:number(\\d+)', 'github-pr'))).toBe('review')
    expect(screenOf(route('/branch/:branch(.+)'))).toBe('branch')
  })
})

describe('diffFacts', () => {
  it('keeps the repo, number and sizes of a pull request, and no paths', () => {
    const facts = diffFacts({
      ref: { kind: 'github-pr', owner: 'acme', repo: 'app', number: '7' },
      files: [{ path: 'src/secret.ts', status: 'modified', additions: 3, deletions: 1, isBinary: false, hunks: [] }],
    } as never)
    expect(facts).toEqual({ kind: 'github-pr', repo: 'acme/app', number: 7, files: 1, additions: 3, deletions: 1 })
  })
})
