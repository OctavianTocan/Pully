import type { AiInboxContext } from '@pulls.review/core/local-rpc'
import type { ToolSpec } from './pr-tools'
import { execFile } from 'node:child_process'

/*
 * The tools an inbox question is served with: read one of the listed pull requests, or its diff, through `gh`.
 * Only the pull requests the inbox listed can be read, so a question never reaches the rest of GitHub.
 */

/** Everything the inbox tools serve, handed to the tool server as one JSON file. */
export type InboxPayload = AiInboxContext

/** Runs a CLI and resolves to its stdout, or `null` when it failed. */
export type Exec = (bin: string, args: string[]) => Promise<string | null>

const VIEW_CHAR_BUDGET = 40_000
const DIFF_CHAR_BUDGET = 80_000
const BODY_CHAR_LIMIT = 6_000
const COMMENT_CHAR_LIMIT = 1_500
const EXEC_TIMEOUT_MS = 60_000
const VIEW_FIELDS = 'title,body,url,author,state,isDraft,baseRefName,headRefName,additions,deletions,changedFiles,labels,reviewDecision,mergeable,statusCheckRollup,reviews,comments,files,createdAt,updatedAt,mergedAt,closedAt'

const PULL = { type: 'string', description: 'The pull request as owner/repo#number, exactly as the list names it.' }

const INBOX_TOOLS: ToolSpec[] = [
  {
    name: 'read_pull_request',
    description: 'One pull request from the list: its description, branches, size, labels, checks, reviews and comments, and the files it changes. Use it to find out what a pull request is for or what is holding it up.',
    inputSchema: { type: 'object', properties: { pull: PULL }, required: ['pull'], additionalProperties: false },
  },
  {
    name: 'read_pull_request_diff',
    description: 'The diff of one pull request from the list, or only of some of its files. Use it when the question is about the code.',
    inputSchema: {
      type: 'object',
      properties: { pull: PULL, paths: { type: 'array', items: { type: 'string' }, description: 'Paths to show, as read_pull_request lists them. Omit for the whole diff.' } },
      required: ['pull'],
      additionalProperties: false,
    },
  },
]

/** What the model is told about these tools, after the system prompt. */
export const INBOX_TOOL_HINT = '\n\nYou can read any pull request in the list yourself through the `inbox` tools: read_pull_request for its description, checks, reviews, comments and files, and read_pull_request_diff for its code. Use them when the question is about what a pull request does or why it is stuck, not just about the list.'

/**
 * The tools an inbox question can be served with.
 * @returns Every tool the model may call, in the order it is shown them.
 */
export function inboxTools(): ToolSpec[] {
  return INBOX_TOOLS
}

function defaultExec(bin: string, args: string[]): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(bin, args, { maxBuffer: 64 * 1024 * 1024, timeout: EXEC_TIMEOUT_MS }, (error, stdout) => resolve(error ? null : stdout))
  })
}

/**
 * The listed pull request `owner/repo#number` names.
 * @param payload - The pull requests the inbox listed.
 * @param value - What the model passed.
 * @returns The pull request.
 * @throws When it is not `owner/repo#number`, or names one the list does not have.
 */
export function listedPull(payload: InboxPayload, value: unknown): { owner: string, repo: string, number: number } {
  const match = /^([\w.-]+)\/([\w.-]+)#(\d+)$/.exec(String(value ?? '').trim())
  if (!match)
    throw new Error('Name the pull request as owner/repo#number.')
  const [, owner, repo, number] = match
  const found = payload.pulls.find(pull => pull.owner.toLowerCase() === owner!.toLowerCase() && pull.repo.toLowerCase() === repo!.toLowerCase() && pull.number === Number(number))
  if (!found)
    throw new Error(`${owner}/${repo}#${number} is not in the list.`)
  return found
}

type Json = Record<string, any>

function cut(text: string, limit: number): string {
  const trimmed = text.trim()
  return trimmed.length <= limit ? trimmed : `${trimmed.slice(0, limit)}…`
}

function checksLine(rollup: Json[] | undefined): string {
  const runs = (rollup ?? []).map(run => ({
    name: String(run.name ?? run.context ?? 'a check'),
    state: String(run.conclusion || run.state || run.status || '').toUpperCase(),
  }))
  if (!runs.length)
    return 'Checks: none'
  const failing = runs.filter(run => ['FAILURE', 'ERROR', 'TIMED_OUT', 'CANCELLED', 'ACTION_REQUIRED', 'STARTUP_FAILURE'].includes(run.state))
  const passing = runs.filter(run => ['SUCCESS', 'NEUTRAL', 'SKIPPED'].includes(run.state))
  const running = runs.length - failing.length - passing.length
  const parts = [`${failing.length} failing${failing.length ? ` (${failing.map(run => run.name).join(', ')})` : ''}`, `${running} running`, `${passing.length} passing`]
  return `Checks: ${parts.join(', ')}`
}

/**
 * One pull request as `gh pr view --json` describes it, written for the model.
 * @param ref - Which pull request, for the heading.
 * @param pr - The parsed `gh pr view` JSON.
 * @returns The description, state, checks, reviews, comments and files as text.
 */
export function describePull(ref: string, pr: Json): string {
  const state = pr.mergedAt ? 'merged' : String(pr.state ?? '').toLowerCase()
  const head = [state, pr.isDraft ? 'draft' : '', `by ${pr.author?.login ?? 'someone'}`, `${pr.headRefName ?? '?'} into ${pr.baseRefName ?? '?'}`, `+${pr.additions ?? 0} −${pr.deletions ?? 0} in ${pr.changedFiles ?? 0} files`]
  const parts = [
    `# ${ref}: ${pr.title ?? ''}`,
    head.filter(Boolean).join(' · '),
    `Opened ${String(pr.createdAt ?? '').slice(0, 10)}, updated ${String(pr.updatedAt ?? '').slice(0, 10)}${pr.mergeable === 'CONFLICTING' ? ' · has conflicts' : ''}`,
    checksLine(pr.statusCheckRollup),
  ]
  if (pr.labels?.length)
    parts.push(`Labels: ${pr.labels.map((label: Json) => label.name).join(', ')}`)
  if (pr.reviewDecision)
    parts.push(`Review decision: ${String(pr.reviewDecision).toLowerCase().replace(/_/g, ' ')}`)
  parts.push(`## Description\n\n${cut(String(pr.body ?? ''), BODY_CHAR_LIMIT) || '(the author wrote none)'}`)
  const said = [
    ...(pr.reviews ?? []).filter((review: Json) => review.body?.trim() || (review.state && review.state !== 'COMMENTED')).map((review: Json) => ({
      at: String(review.submittedAt ?? ''),
      line: `${review.author?.login ?? 'someone'} (${String(review.state ?? 'reviewed').toLowerCase().replace(/_/g, ' ')}, ${String(review.submittedAt ?? '').slice(0, 10)}): ${cut(String(review.body ?? ''), COMMENT_CHAR_LIMIT)}`,
    })),
    ...(pr.comments ?? []).map((comment: Json) => ({
      at: String(comment.createdAt ?? ''),
      line: `${comment.author?.login ?? 'someone'} (${String(comment.createdAt ?? '').slice(0, 10)}): ${cut(String(comment.body ?? ''), COMMENT_CHAR_LIMIT)}`,
    })),
  ].sort((a, b) => a.at.localeCompare(b.at))
  parts.push(said.length ? `## Reviews and comments\n\n${said.map(entry => entry.line).join('\n\n')}` : '## Reviews and comments\n\nNone yet.')
  if (pr.files?.length)
    parts.push(`## Files\n\n${pr.files.map((file: Json) => `${file.path} (+${file.additions ?? 0} −${file.deletions ?? 0})`).join('\n')}`)
  const text = parts.join('\n\n')
  return text.length <= VIEW_CHAR_BUDGET ? text : `${text.slice(0, VIEW_CHAR_BUDGET)}\n… truncated.`
}

/** A path as Git writes it in a header when it has quotes, backslashes or control characters: `"a/say \"hi\".md"`. */
function quoted(path: string): string {
  return `"${path.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\t/g, '\\t').replace(/\n/g, '\\n')}"`
}

/** Whether a `diff --git` header is about `path`, before or after a rename, with spaces in it or quoted. */
function headerTouches(header: string, path: string): boolean {
  const [before, after] = [`a/${path}`, `b/${path}`]
  return header.startsWith(`diff --git ${before} `) || header.endsWith(` ${after}`)
    || header.startsWith(`diff --git ${quoted(before)} `) || header.endsWith(` ${quoted(after)}`)
}

/**
 * The files of a unified diff whose path is one of `paths`; the whole diff when `paths` is empty.
 * @param diff - `gh pr diff` output.
 * @param paths - Paths to keep.
 * @returns The kept part of the diff.
 */
export function pickFiles(diff: string, paths: string[]): string {
  if (!paths.length)
    return diff
  return diff.split(/(?=^diff --git )/m).filter((part) => {
    const header = part.split('\n', 1)[0]!
    return header.startsWith('diff --git ') && paths.some(path => headerTouches(header, path))
  }).join('')
}

function paths(value: unknown): string[] {
  if (value === undefined)
    return []
  if (Array.isArray(value) && value.every(item => typeof item === 'string'))
    return value
  throw new Error('paths must be an array of strings')
}

/**
 * Runs one inbox tool.
 * @param payload - The pull requests the inbox listed.
 * @param name - The tool's name, as `inboxTools` lists it.
 * @param args - The arguments the model passed.
 * @param exec - Runs `gh`; replaced in tests.
 * @returns The answer as text, ready to hand back as tool output.
 * @throws When the tool is unknown, its arguments make no sense, or it names a pull request the list does not have.
 */
export async function callInboxTool(payload: InboxPayload, name: string, args: Record<string, unknown>, exec: Exec = defaultExec): Promise<string> {
  if (!INBOX_TOOLS.some(tool => tool.name === name))
    throw new Error(`unknown tool ${name}`)
  const pull = listedPull(payload, args.pull)
  const repo = `${pull.owner}/${pull.repo}`
  const ref = `${repo}#${pull.number}`
  switch (name) {
    case 'read_pull_request': {
      const view = await exec('gh', ['pr', 'view', String(pull.number), '--repo', repo, '--json', VIEW_FIELDS])
      if (view === null)
        return `${ref} could not be read.`
      return describePull(ref, JSON.parse(view) as Json)
    }
    case 'read_pull_request_diff': {
      const wanted = paths(args.paths)
      const diff = await exec('gh', ['pr', 'diff', String(pull.number), '--repo', repo])
      if (diff === null)
        return `The diff of ${ref} could not be read.`
      const picked = pickFiles(diff, wanted)
      if (!picked.trim())
        return wanted.length ? `${ref} changes none of ${wanted.join(', ')}.` : `${ref} has no diff.`
      return picked.length <= DIFF_CHAR_BUDGET ? picked : `${picked.slice(0, DIFF_CHAR_BUDGET)}\n… truncated; ask for fewer paths at a time.`
    }
    default:
      throw new Error(`unknown tool ${name}`)
  }
}
