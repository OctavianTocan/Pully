import type { GithubGraphql } from './my-pulls'
import process from 'node:process'
import { createGithubGraphql } from './my-pulls'

/** How long one answer is reused: the numbers are a year's, so a few minutes old is current enough. */
const STATS_TTL_MS = 10 * 60_000

const STATS_QUERY = `query {
  viewer {
    login
    name
    followers { totalCount }
    repositories(ownerAffiliations: OWNER) { totalCount }
    contributionsCollection {
      totalCommitContributions
      totalPullRequestContributions
      totalPullRequestReviewContributions
      totalIssueContributions
      restrictedContributionsCount
      contributionCalendar {
        totalContributions
        weeks { contributionDays { date contributionCount } }
      }
    }
  }
}`

interface StatsNode {
  viewer: {
    login: string
    name: string | null
    followers: { totalCount: number }
    repositories: { totalCount: number }
    contributionsCollection: {
      totalCommitContributions: number
      totalPullRequestContributions: number
      totalPullRequestReviewContributions: number
      totalIssueContributions: number
      restrictedContributionsCount: number
      contributionCalendar: {
        totalContributions: number
        weeks: { contributionDays: { date: string, contributionCount: number }[] }[]
      }
    }
  }
}

/** The signed-in user's GitHub year, for the phone's profile sheet (M5). Counts only, never what was contributed. */
export interface GithubStats {
  login: string
  name: string | null
  followers: number
  repositories: number
  /** Every contribution in the last year, private ones included as GitHub counts them. */
  total: number
  commits: number
  pullRequests: number
  reviews: number
  issues: number
  /** The first day of `days`, `YYYY-MM-DD`, a Sunday as GitHub's calendar starts its weeks. */
  firstDay: string
  /** Contributions per day, oldest first, through today. */
  days: number[]
  /** Days in a row with a contribution, ending today or yesterday (today may not have one yet). */
  streak: number
  /** The longest run of days in a row with a contribution in the year. */
  longestStreak: number
}

/**
 * @param days Contributions per day, oldest first, the last one today.
 * @returns The current run, which may end yesterday, and the longest run.
 */
export function streaks(days: number[]): { streak: number, longestStreak: number } {
  let longestStreak = 0
  let run = 0
  for (const count of days) {
    run = count > 0 ? run + 1 : 0
    longestStreak = Math.max(longestStreak, run)
  }
  // Today counts once it has a contribution; until then the run that ended yesterday is still going.
  const end = days.length > 0 && days[days.length - 1] === 0 ? days.length - 1 : days.length
  let streak = 0
  for (let index = end - 1; index >= 0 && days[index]! > 0; index--)
    streak++
  return { streak, longestStreak }
}

/** Turns GitHub's answer into `GithubStats`. */
export function toGithubStats(node: StatsNode): GithubStats {
  const { viewer } = node
  const collection = viewer.contributionsCollection
  const calendar = collection.contributionCalendar.weeks.flatMap(week => week.contributionDays)
  const days = calendar.map(day => day.contributionCount)
  return {
    login: viewer.login,
    name: viewer.name || null,
    followers: viewer.followers.totalCount,
    repositories: viewer.repositories.totalCount,
    total: collection.contributionCalendar.totalContributions,
    commits: collection.totalCommitContributions,
    pullRequests: collection.totalPullRequestContributions,
    reviews: collection.totalPullRequestReviewContributions,
    issues: collection.totalIssueContributions,
    firstDay: calendar[0]?.date ?? '',
    days,
    ...streaks(days),
  }
}

/**
 * @param graphql The GitHub client.
 * @param now The clock, replaceable in tests.
 * @returns A function answering the stats, reused for a few minutes; `fresh` asks GitHub again.
 */
export function createGithubStats(graphql: GithubGraphql, now: () => number = Date.now): (fresh?: boolean) => Promise<GithubStats> {
  let last: { at: number, value: Promise<GithubStats> } | undefined
  return (fresh = false) => {
    if (!fresh && last && now() - last.at < STATS_TTL_MS)
      return last.value
    const value = graphql<StatsNode>(STATS_QUERY, {}).then(toGithubStats)
    const entry = { at: now(), value }
    last = entry
    // A failure is not kept, so the next open asks again.
    value.catch(() => {
      if (last === entry)
        last = undefined
    })
    return value
  }
}

let shared: ((fresh?: boolean) => Promise<GithubStats>) | undefined

/** The server's own stats, on its GitHub token. */
export function githubStats(fresh?: boolean): Promise<GithubStats> {
  shared ??= createGithubStats(createGithubGraphql(process.env))
  return shared(fresh)
}
