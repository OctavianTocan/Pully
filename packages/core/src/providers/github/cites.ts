import type { GroupedResult } from '../../types/analyze'

/** The repository an AI text's mentions point into, and the commit they were written against. */
export interface CiteTarget {
  owner: string
  repo: string
  sha: string
}

const SCHEME = /^[a-z][a-z0-9+.-]*:/i
/** `key.ts:12` starts like a scheme but is a place. */
const NAME_AND_LINE = /^[^/]+:\d/
const PLACE = /^([^#?\s:]+)(?::(\d+)|#L(\d+)(?:-L?(\d+))?)?$/
const LINK_TARGET = /\]\(\s*([^()\s]+)((?:\s+"[^"]*")?\s*)\)/g
const FENCE = /^ {0,3}(`{3,}|~{3,})/

/**
 * A link's target as a path from the repository root. A leading `/` or `./` goes and `..` steps up a folder, never
 * above the root, so `../../.agents/x.md` copied out of `docs/runbooks/` still finds `.agents/x.md`. A folder whose
 * name starts with a dot (`.agents`, `.github`) keeps its dot: it is a name, not a `./`.
 * @param link - The path part of a link's target, such as `./src/a.ts` or `.github/ci.yml`.
 * @returns The path from the root, or `undefined` for a folder.
 */
export function repoPath(link: string): string | undefined {
  let decoded = link
  try {
    decoded = decodeURIComponent(link)
  }
  catch {}
  const segments = decoded.split('/')
  if (['', '.', '..'].includes(segments.at(-1)!))
    return undefined
  const path: string[] = []
  for (const segment of segments) {
    if (segment === '..')
      path.pop()
    else if (segment !== '' && segment !== '.')
      path.push(segment)
  }
  return path.length ? path.join('/') : undefined
}

/**
 * Where a repository-relative link target points on GitHub.
 * @param target - A Markdown link's target, such as `src/a.ts#L12`, `src/a.ts:12`, `./a.ts` or `.github/ci.yml`.
 * @param at - The repository and the commit to read the file at.
 * @returns The file's `github.com` blob URL with its line anchor, or `undefined` for a web link, an anchor or a folder.
 */
export function githubCiteUrl(target: string, at: CiteTarget): string | undefined {
  const trimmed = target.trim()
  if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('//') || (SCHEME.test(trimmed) && !NAME_AND_LINE.test(trimmed)))
    return undefined
  const match = PLACE.exec(trimmed)
  const path = match?.[1] === undefined ? undefined : repoPath(match[1])
  if (!match || !path)
    return undefined
  const first = match[2] ?? match[3]
  const anchor = first ? `#L${first}${match[4] ? `-L${match[4]}` : ''}` : ''
  return `https://github.com/${at.owner}/${at.repo}/blob/${at.sha}/${path.split('/').map(encodeURIComponent).join('/')}${anchor}`
}

/**
 * Rewrites the repository links an AI text names places with (`[cacheKey](src/cache/key.ts#L42)`, rule 96) into
 * links to the file on GitHub at `at.sha`, so the text still points somewhere once it is posted there, where a
 * relative path resolves against the pull request's page. Web links, anchors and anything inside code stay as they are.
 * @param markdown - The text as the model wrote it.
 * @param at - The repository and the commit the text was written against.
 * @returns The text with its repository links made absolute.
 */
export function githubCites(markdown: string, at: CiteTarget): string {
  let fence: string | undefined
  return markdown.split('\n').map((line) => {
    const marker = FENCE.exec(line)?.[1]
    if (fence) {
      if (marker && marker[0] === fence[0] && marker.length >= fence.length && !line.slice(line.indexOf(marker) + marker.length).trim())
        fence = undefined
      return line
    }
    if (marker) {
      fence = marker
      return line
    }
    // Odd pieces are code spans: a path in code is code.
    return line.split(/(`+[^`]*`+)/).map((piece, index) => index % 2
      ? piece
      : piece.replace(LINK_TARGET, (whole, target: string, title: string) => {
          const url = githubCiteUrl(target, at)
          return url ? `](${url}${title})` : whole
        })).join('')
  }).join('\n')
}

/**
 * An analysis as it is posted on GitHub: every summary and note with its repository links made absolute.
 * @param result - The analysis.
 * @param at - The repository and the head commit it was written against.
 * @returns A copy whose text links resolve on GitHub; the groups, paths and the rest are unchanged.
 */
export function githubCitesInResult(result: GroupedResult, at: CiteTarget): GroupedResult {
  const cite = (text: string | undefined) => text === undefined ? undefined : githubCites(text, at)
  return {
    ...result,
    ...(result.overallSummary !== undefined ? { overallSummary: cite(result.overallSummary) } : {}),
    groups: result.groups.map(group => ({
      ...group,
      ...(group.summary !== undefined ? { summary: cite(group.summary) } : {}),
      ...(group.children ? { children: group.children.map(child => child.summary !== undefined ? { ...child, summary: cite(child.summary) } : child) } : {}),
    })),
    ...(result.files
      ? { files: result.files.map(file => ({ ...file, summary: githubCites(file.summary, at), ...(file.hunks ? { hunks: file.hunks.map(hunk => ({ ...hunk, note: githubCites(hunk.note, at) })) } : {}) })) }
      : {}),
  }
}
