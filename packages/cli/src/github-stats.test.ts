import { describe, expect, it, vi } from 'vitest'
import { createGithubStats, streaks, toGithubStats } from './github-stats'

function answer(days: number[]) {
  const weeks = []
  for (let index = 0; index < days.length; index += 7)
    weeks.push({ contributionDays: days.slice(index, index + 7).map((contributionCount, day) => ({ date: `2026-01-${String(index + day + 1).padStart(2, '0')}`, contributionCount })) })
  return {
    viewer: {
      login: 'tavi',
      name: 'Tavi',
      followers: { totalCount: 12 },
      repositories: { totalCount: 30 },
      contributionsCollection: {
        totalCommitContributions: 400,
        totalPullRequestContributions: 50,
        totalPullRequestReviewContributions: 20,
        totalIssueContributions: 5,
        restrictedContributionsCount: 3,
        contributionCalendar: { totalContributions: 478, weeks },
      },
    },
  }
}

describe('streaks', () => {
  it('counts the run ending today, or yesterday while today has nothing yet, and the longest run', () => {
    expect(streaks([1, 1, 1, 0, 2, 3])).toEqual({ streak: 2, longestStreak: 3 })
    expect(streaks([1, 1, 1, 0, 2, 3, 0])).toEqual({ streak: 2, longestStreak: 3 })
    expect(streaks([1, 0, 0])).toEqual({ streak: 0, longestStreak: 1 })
    expect(streaks([])).toEqual({ streak: 0, longestStreak: 0 })
  })
})

describe('github stats', () => {
  it('flattens the calendar into days, oldest first, with the year\'s totals', () => {
    const stats = toGithubStats(answer([0, 1, 2, 3, 4, 5, 6, 7, 0]))
    expect(stats).toMatchObject({ login: 'tavi', name: 'Tavi', followers: 12, repositories: 30, total: 478, commits: 400, pullRequests: 50, reviews: 20, issues: 5, firstDay: '2026-01-01', streak: 7, longestStreak: 7 })
    expect(stats.days).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 0])
  })

  it('reuses an answer for a few minutes, asks again when fresh, and does not keep a failure', async () => {
    let now = 0
    const graphql = vi.fn().mockResolvedValue(answer([1]))
    const stats = createGithubStats(graphql as never, () => now)
    await stats()
    now = 60_000
    await stats()
    expect(graphql).toHaveBeenCalledTimes(1)
    await stats(true)
    expect(graphql).toHaveBeenCalledTimes(2)

    graphql.mockRejectedValueOnce(new Error('down'))
    await expect(stats(true)).rejects.toThrow('down')
    await stats()
    expect(graphql).toHaveBeenCalledTimes(4)
  })
})
