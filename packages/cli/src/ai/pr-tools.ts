import type { AiPrContext } from '@pulls.review/core/local-rpc'
import { execFile } from 'node:child_process'

/**
 * What Jev, TypeSafe's classifier, said about the change before the review started: what kind of pull request it is
 * and how risky, and how much attention each file needs (`p0` needs judgment, `p1` is worth a look, `p2` is low).
 */
export interface PrTriage {
  pull?: { kind: string, kindConfidence: number, risk: number }
  /** By path; a file Jev did not rate is missing. */
  files: Record<string, { priority: 'p0' | 'p1' | 'p2', confidence: number }>
}

/** Everything the PR tools serve, handed to the tool server as one JSON file. */
export interface PrPayload extends AiPrContext {
  /** A git repository that holds `headSha`, when the server has one. */
  checkout?: string
  /** Jev's read of the change, when it gave one. */
  triage?: PrTriage
}

/** A tool as MCP's `tools/list` describes it. */
export interface ToolSpec {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

/** Past this a single answer stops being context and starts being the whole diff again. */
const REPLY_CHAR_BUDGET = 120_000
const SEARCH_HIT_LIMIT = 200
const FIND_HIT_LIMIT = 300
const SOURCE_LINES = 400
const EXEC_TIMEOUT_MS = 60_000

const LOCKFILE = /(?:^|\/)(?:package-lock\.json|pnpm-lock\.yaml|yarn\.lock|bun\.lockb?|Cargo\.lock|Gemfile\.lock|poetry\.lock|composer\.lock|go\.sum|uv\.lock)$|\.min\.(?:js|css)$|\.map$/

function strings(description: string): Record<string, unknown> {
  return { type: 'array', items: { type: 'string' }, description }
}

const PR_TOOLS: ToolSpec[] = [
  {
    name: 'read_file_diff',
    description: 'The full diff of one or more changed files, hunk by hunk. Each hunk is labelled with its id, and every line carries its line number (new-side for added and context lines, old-side for removed ones). This is the main way to read the change.',
    inputSchema: { type: 'object', properties: { paths: strings('Paths exactly as the change lists them.') }, required: ['paths'], additionalProperties: false },
  },
  {
    name: 'read_hunks',
    description: 'The body of specific hunks, by id. Use it to re-read a few hunks without pulling whole files again.',
    inputSchema: { type: 'object', properties: { hunkIds: strings('Hunk ids, e.g. src/queue.ts#0.') }, required: ['hunkIds'], additionalProperties: false },
  },
  {
    name: 'find_files',
    description: 'Paths in the repository at the head commit, changed or not. Use it to locate a caller, a test or a config before reading it with read_source.',
    inputSchema: {
      type: 'object',
      properties: { pattern: { type: 'string', description: 'JavaScript regular expression matched case-insensitively against the whole path. Omit to list everything.' } },
      additionalProperties: false,
    },
  },
  {
    name: 'read_source',
    description: 'A file as it stands at the head commit, with line numbers, including the parts the diff does not touch. Use it when a hunk only makes sense next to the code around it.',
    inputSchema: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Path in the head revision.' },
        startLine: { type: 'number', description: 'First line to return. Defaults to 1.' },
        endLine: { type: 'number', description: `Last line to return. Defaults to ${SOURCE_LINES} lines after startLine.` },
      },
      required: ['path'],
      additionalProperties: false,
    },
  },
  {
    name: 'search_diff',
    description: 'Every changed line matching a regular expression, with its file, line number and hunk. Use it to find where a symbol moved, or which hunks touch the same thing.',
    inputSchema: {
      type: 'object',
      properties: {
        pattern: { type: 'string', description: 'JavaScript regular expression, matched case-insensitively.' },
        added: { type: 'boolean', description: 'Search added lines. Defaults to true.' },
        removed: { type: 'boolean', description: 'Search removed lines. Defaults to true.' },
      },
      required: ['pattern'],
      additionalProperties: false,
    },
  },
  {
    name: 'read_discussion',
    description: 'What the author wrote in the description and what reviewers have said, inline notes included. Use it to find out what the pull request is trying to do.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
]

const SEARCH_SOURCE: ToolSpec = {
  name: 'search_source',
  description: 'Every line in the repository at the head commit matching a regular expression, changed or not. Use it to find callers, definitions and other uses of a symbol the diff touches.',
  inputSchema: {
    type: 'object',
    properties: {
      pattern: { type: 'string', description: 'POSIX extended regular expression, matched case-insensitively.' },
      path: { type: 'string', description: 'Restrict to paths matching this glob, e.g. src/**/*.ts. Omit to search everything.' },
    },
    required: ['pattern'],
    additionalProperties: false,
  },
}

const TRIAGE_FILES: ToolSpec = {
  name: 'triage_files',
  description: 'A fast classifier\'s first read of the change: what kind of pull request it is, how risky, and which changed files need human judgment (p0), are worth a look (p1) or need little attention (p2). Call it first to decide where to spend your reading. It is a hint, not a verdict: read the code before agreeing with it.',
  inputSchema: { type: 'object', properties: {}, additionalProperties: false },
}

function hasTriage(payload: PrPayload): boolean {
  return !!payload.triage && (!!payload.triage.pull || Object.keys(payload.triage.files).length > 0)
}

/**
 * The tools a change can be served with.
 * @param payload - The change, the checkout that holds it, and Jev's read of it, if any.
 * @returns Every tool the model may call, in the order it is shown them.
 */
export function prTools(payload: PrPayload): ToolSpec[] {
  return [...(hasTriage(payload) ? [TRIAGE_FILES] : []), ...PR_TOOLS, ...(payload.checkout ? [SEARCH_SOURCE] : [])]
}

const RISK_WORDS = ['trivial', 'low', 'medium', 'high'] as const
const PRIORITY_HEADINGS = {
  p0: 'p0, needs human judgment (logic, security, data handling, API contracts, concurrency or money)',
  p1: 'p1, worth a look (ordinary changes that follow what the code already does)',
  p2: 'p2, low attention (generated files, lockfiles, formatting, renames, snapshots or trivial docs)',
} as const

function percent(value: number): string {
  return `${Math.round(value * 100)}%`
}

/**
 * @param payload - The change and Jev's read of it.
 * @returns Jev's read as the model sees it: the pull request's kind and risk, then the files by priority.
 */
export function triageText(payload: PrPayload): string {
  const triage = payload.triage
  if (!triage || !hasTriage(payload))
    return 'No triage is available for this change; read it file by file.'
  const parts: string[] = []
  if (triage.pull) {
    const risk = RISK_WORDS[Math.min(Math.max(Math.round(triage.pull.risk), 0), RISK_WORDS.length - 1)]
    parts.push(`Pull request: ${triage.pull.kind} (${percent(triage.pull.kindConfidence)} sure), ${risk} risk (${triage.pull.risk.toFixed(1)} on a scale of 0 trivial to 3 high).`)
  }
  for (const priority of ['p0', 'p1', 'p2'] as const) {
    const files = payload.files.filter(file => triage.files[file.path]?.priority === priority)
    if (files.length)
      parts.push(`${PRIORITY_HEADINGS[priority]}:\n${files.map(file => `- ${file.path} (${percent(triage.files[file.path]!.confidence)} sure)`).join('\n')}`)
  }
  const unrated = payload.files.filter(file => !triage.files[file.path]).length
  if (unrated)
    parts.push(`Not rated: ${unrated} ${unrated === 1 ? 'file' : 'files'} (binary, past the first 300, or still being rated). Judge those yourself.`)
  return parts.join('\n\n')
}

/** One hunk of a file's patch; `lines` keep their `+`/`-`/space prefix. */
export interface Hunk {
  id: string
  header: string
  oldStart: number
  newStart: number
  lines: string[]
}

/**
 * Splits a file's patch (hunks each led by its `@@ -a,b +c,d @@` header) into hunks.
 * @param path - The file's path, which prefixes every hunk id.
 * @param patch - The patch text.
 * @returns The hunks, ids `path#0`, `path#1`, …
 */
export function parseHunks(path: string, patch: string | undefined): Hunk[] {
  const hunks: Hunk[] = []
  for (const line of (patch ?? '').split('\n')) {
    const header = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line)
    if (header)
      hunks.push({ id: `${path}#${hunks.length}`, header: line, oldStart: Number(header[1]), newStart: Number(header[2]), lines: [] })
    else if (hunks.length && line !== '')
      hunks.at(-1)!.lines.push(line)
  }
  return hunks
}

/** Each line of a hunk with its line number: new-side for added and context lines, old-side for removed ones. */
function numbered(hunk: Hunk): { text: string, sign: string, line: number }[] {
  let old = hunk.oldStart
  let next = hunk.newStart
  return hunk.lines.map((text) => {
    const sign = text[0] ?? ' '
    if (sign === '-')
      return { text, sign, line: old++ }
    if (sign === '+')
      return { text, sign, line: next++ }
    if (sign === '\\')
      return { text, sign, line: next - 1 }
    old++
    return { text, sign, line: next++ }
  })
}

function body(hunk: Hunk): string {
  const lines = numbered(hunk).map(({ text, sign, line }) => sign === '\\' ? `       │ ${text}` : `${sign}${String(line).padStart(5)} │ ${text.slice(1)}`)
  return [`[${hunk.id}] ${hunk.header}`, '```', ...lines, '```'].join('\n')
}

function skipNote(path: string): string | undefined {
  return LOCKFILE.test(path) ? 'Lockfile or generated output; its diff is left out. Use read_source if you really need it.' : undefined
}

function readFileDiff(payload: PrPayload, paths: string[]): string {
  return paths.map((path) => {
    const file = payload.files.find(item => item.path === path)
    if (!file)
      return `${path}: not changed here.`
    const heading = `### ${file.path}${file.previousPath ? ` (renamed from ${file.previousPath})` : ''}${file.status ? ` [${file.status}]` : ''}`
    const skipped = skipNote(file.path)
    if (skipped)
      return `${heading}\n${skipped}`
    const hunks = parseHunks(file.path, file.patch)
    if (!hunks.length)
      return `${heading}\nNo textual diff (binary, too large, or a rename or mode change only).`
    return [heading, ...hunks.map(body)].join('\n\n')
  }).join('\n\n')
}

function readHunks(payload: PrPayload, ids: string[]): string {
  const index = new Map(payload.files.flatMap(file => parseHunks(file.path, file.patch).map(hunk => [hunk.id, { hunk, path: file.path }] as const)))
  return ids.map((id) => {
    const found = index.get(id)
    if (!found)
      return `[${id}] no such hunk.`
    const skipped = skipNote(found.path)
    return skipped ? `[${id}] ${skipped}` : body(found.hunk)
  }).join('\n\n')
}

function searchDiff(payload: PrPayload, pattern: string, added: boolean, removed: boolean): string {
  const re = regex(pattern)
  const wanted = (sign: string) => sign === '+' ? added : sign === '-' && removed
  const hits = payload.files
    .filter(file => !skipNote(file.path))
    .flatMap(file => parseHunks(file.path, file.patch).flatMap(hunk => numbered(hunk)
      .filter(({ text, sign }) => wanted(sign) && re.test(text.slice(1)))
      .map(({ text, line }) => `${file.path}:L${line} [${hunk.id}] ${text}`)))
  if (!hits.length)
    return `No changed line matches ${pattern}.`
  const more = hits.length > SEARCH_HIT_LIMIT ? `\n… stopped at ${SEARCH_HIT_LIMIT} matches; narrow the pattern.` : ''
  return `${hits.slice(0, SEARCH_HIT_LIMIT).join('\n')}${more}`
}

function run(bin: string, args: string[], cwd?: string): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(bin, args, { cwd, maxBuffer: 64 * 1024 * 1024, timeout: EXEC_TIMEOUT_MS }, (error, stdout) => resolve(error ? null : stdout))
  })
}

function git(checkout: string, args: string[]): Promise<string | null> {
  return run('git', ['-C', checkout, ...args])
}

const treeCache = new Map<string, Promise<string[] | null>>()

async function listTree(payload: PrPayload): Promise<string[] | null> {
  const lines = (text: string | null) => text === null ? null : text.split('\n').filter(line => line !== '')
  if (payload.checkout)
    return lines(await git(payload.checkout, ['ls-tree', '-r', '--name-only', payload.headSha]))
  return lines(await run('gh', ['api', '--paginate', `repos/${payload.owner}/${payload.repo}/git/trees/${payload.headSha}?recursive=1`, '--jq', '.tree[] | select(.type == "blob") | .path']))
}

async function findFiles(payload: PrPayload, pattern?: string): Promise<string> {
  const key = `${payload.checkout ?? `${payload.owner}/${payload.repo}`}@${payload.headSha}`
  if (!treeCache.has(key))
    treeCache.set(key, listTree(payload))
  const paths = await treeCache.get(key)!
  const where = `${payload.owner}/${payload.repo} at ${payload.headSha.slice(0, 7)}`
  if (!paths)
    return `The file list of ${where} could not be read.`
  const re = pattern ? regex(pattern) : undefined
  const hits = re ? paths.filter(path => re.test(path)) : paths
  if (!hits.length)
    return `No path in ${where} matches ${pattern}.`
  const shown = hits.slice(0, FIND_HIT_LIMIT)
  const more = hits.length > shown.length ? `\n… ${hits.length - shown.length} more; narrow the pattern.` : ''
  return `${hits.length} files in ${where}:\n${shown.join('\n')}${more}`
}

function sourceOf(payload: PrPayload, path: string): Promise<string | null> {
  if (payload.checkout)
    return git(payload.checkout, ['show', `${payload.headSha}:${path}`])
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  return run('gh', ['api', '-H', 'Accept: application/vnd.github.raw', `repos/${payload.owner}/${payload.repo}/contents/${encoded}?ref=${payload.headSha}`])
}

async function readSource(payload: PrPayload, path: string, startLine?: number, endLine?: number): Promise<string> {
  if (!path)
    throw new Error('read_source needs a path')
  const text = await sourceOf(payload, path)
  if (text === null)
    return `${path}: could not be read at ${payload.headSha.slice(0, 7)}.`
  const lines = text.replace(/\n$/, '').split('\n')
  const from = Math.max(startLine ?? 1, 1)
  const to = Math.min(endLine ?? from + SOURCE_LINES - 1, lines.length)
  if (from > lines.length)
    return `${path} has ${lines.length} lines; ${from} is past the end.`
  const shown = lines.slice(from - 1, to).map((line, i) => `${String(from + i).padStart(5)}  ${line}`)
  const more = to < lines.length ? `\n… ${lines.length - to} more lines` : ''
  return `${path} at ${payload.headSha.slice(0, 7)}, lines ${from}–${to} of ${lines.length}:\n${shown.join('\n')}${more}`
}

async function searchSource(payload: PrPayload, pattern: string, path?: string): Promise<string> {
  if (!pattern)
    throw new Error('search_source needs a pattern')
  if (!payload.checkout)
    return 'There is no checkout to search. Use search_diff for the changed lines, or read_source for one file.'
  const out = await git(payload.checkout, ['grep', '-n', '-I', '-i', '-E', '-e', pattern, payload.headSha, ...(path ? ['--', path] : [])])
  const prefix = `${payload.headSha}:`
  const hits = (out ?? '').split('\n').filter(line => line !== '').map(line => line.startsWith(prefix) ? line.slice(prefix.length) : line)
  if (!hits.length)
    return `No file in the repository matches ${pattern}.`
  const shown = hits.slice(0, SEARCH_HIT_LIMIT)
  const more = hits.length > shown.length ? `\n… stopped at ${SEARCH_HIT_LIMIT} matches; narrow the pattern.` : ''
  return `${shown.join('\n')}${more}`
}

type Json = Record<string, any>

function jsonLines(text: string | null): Json[] {
  return (text ?? '').split('\n').flatMap((line) => {
    try {
      return line.trim() ? [JSON.parse(line)] : []
    }
    catch {
      return []
    }
  })
}

async function readDiscussion(payload: PrPayload): Promise<string> {
  if (payload.number === undefined)
    return 'This change is not a pull request, so it has no description or review discussion.'
  const repo = `${payload.owner}/${payload.repo}`
  const [view, inline] = await Promise.all([
    run('gh', ['pr', 'view', String(payload.number), '--repo', repo, '--json', 'title,body,author,comments,reviews']),
    run('gh', ['api', '--paginate', `repos/${repo}/pulls/${payload.number}/comments`, '--jq', '.[] | {author: .user.login, path, line: (.line // .original_line), body, createdAt: .created_at}']),
  ])
  if (view === null)
    return `The discussion on ${repo}#${payload.number} could not be read.`
  const pr = JSON.parse(view) as Json
  const entries: { author: string, createdAt: string, body: string, note: string }[] = [
    ...(pr.comments ?? []).map((comment: Json) => ({ author: comment.author?.login ?? 'someone', createdAt: comment.createdAt ?? '', body: comment.body ?? '', note: '' })),
    ...(pr.reviews ?? []).filter((review: Json) => review.body?.trim() || (review.state && review.state !== 'COMMENTED')).map((review: Json) => ({
      author: review.author?.login ?? 'someone',
      createdAt: review.submittedAt ?? '',
      body: review.body ?? '',
      note: review.state && review.state !== 'COMMENTED' ? ` (${String(review.state).toLowerCase().replace('_', ' ')})` : '',
    })),
    ...jsonLines(inline).map(comment => ({ author: comment.author ?? 'someone', createdAt: comment.createdAt ?? '', body: comment.body ?? '', note: ` on ${comment.path}${comment.line ? `:${comment.line}` : ''}` })),
  ].sort((a, b) => a.createdAt.localeCompare(b.createdAt))

  const parts = [`# ${pr.title ?? payload.title ?? `${repo}#${payload.number}`}`, `## Description (by ${pr.author?.login ?? 'the author'})\n\n${String(pr.body ?? '').trim() || '(the author wrote none)'}`]
  parts.push(entries.length ? '## Reviews and comments' : '## Reviews and comments\n\nNone yet.')
  for (const entry of entries)
    parts.push(`--- ${entry.author}${entry.note}, ${entry.createdAt.slice(0, 10)} ---\n${entry.body.trim()}`)
  return parts.join('\n\n')
}

function regex(pattern: string): RegExp {
  try {
    return new RegExp(pattern, 'i')
  }
  catch {
    throw new Error(`${pattern} is not a valid regular expression`)
  }
}

function list(value: unknown, name: string): string[] {
  if (typeof value === 'string')
    return [value]
  if (Array.isArray(value) && value.every(item => typeof item === 'string'))
    return value
  throw new Error(`${name} must be an array of strings`)
}

function num(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function cap(text: string): string {
  return text.length <= REPLY_CHAR_BUDGET ? text : `${text.slice(0, REPLY_CHAR_BUDGET)}\n… truncated; ask for fewer files at a time.`
}

/**
 * Runs one PR tool.
 * @param payload - The change being reviewed.
 * @param name - The tool's name, as `prTools` lists it.
 * @param args - The arguments the model passed.
 * @returns The answer as text, ready to hand back as tool output.
 * @throws When the tool is unknown or its arguments make no sense.
 */
export async function callPrTool(payload: PrPayload, name: string, args: Record<string, unknown>): Promise<string> {
  switch (name) {
    case 'triage_files':
      return cap(triageText(payload))
    case 'read_file_diff':
      return cap(readFileDiff(payload, list(args.paths, 'paths')))
    case 'read_hunks':
      return cap(readHunks(payload, list(args.hunkIds, 'hunkIds')))
    case 'find_files':
      return cap(await findFiles(payload, args.pattern === undefined ? undefined : String(args.pattern)))
    case 'read_source':
      return cap(await readSource(payload, String(args.path ?? ''), num(args.startLine), num(args.endLine)))
    case 'search_diff':
      return cap(searchDiff(payload, String(args.pattern ?? ''), args.added !== false, args.removed !== false))
    case 'search_source':
      return cap(await searchSource(payload, String(args.pattern ?? ''), args.path === undefined ? undefined : String(args.path)))
    case 'read_discussion':
      return cap(await readDiscussion(payload))
    default:
      throw new Error(`unknown tool ${name}`)
  }
}
