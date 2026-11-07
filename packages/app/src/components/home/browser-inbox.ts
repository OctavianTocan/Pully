import type { GithubClient } from '@pulls.review/core/github'
import type { MyPull, MyPullState } from '@pulls.review/core/local-rpc'

interface Issue {
  number: number
  title: string
  html_url: string
  repository_url: string
  user: { login: string } | null
  draft?: boolean
  updated_at: string
  comments: number
  labels: { name: string }[]
}

/** The static client uses the viewer's GitHub authority through the existing client. */
export async function browserInbox(client: GithubClient, viewer: string, state: MyPullState): Promise<MyPull[]> {
  async function search(qualifier: string): Promise<Issue[]> {
    const items: Issue[] = []
    for (let page = 1; page <= 10; page++) {
      const params = new URLSearchParams({ q: `is:pr is:${state} ${qualifier}:${viewer}`, sort: 'updated', order: 'desc', per_page: '100', page: String(page) })
      const result = await (await client.request(`/search/issues?${params}`)).json() as {
        items: Issue[]
        total_count: number
      }
      items.push(...result.items)
      if (result.items.length < 100 || items.length >= result.total_count)
        break
    }
    return items
  }
  const [involved, requested] = await Promise.all([search('involves'), search('review-requested')])
  const requests = new Set(requested.map(issue => issue.html_url))
  return [...new Map([...involved, ...requested].map(issue => [issue.html_url, issue])).values()].map((issue): MyPull => {
    const [owner = '', repo = ''] = new URL(issue.repository_url).pathname.split('/').slice(-2)
    return { owner, repo, number: issue.number, title: issue.title, author: issue.user?.login ?? '', isDraft: issue.draft ?? false, updatedAt: issue.updated_at, commentsCount: issue.comments, labels: issue.labels.map(label => label.name), state, role: issue.user?.login === viewer ? 'authored' : requests.has(issue.html_url) ? 'review-requested' : 'involved', requestedFromMe: requests.has(issue.html_url), url: issue.html_url }
  }).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
}
