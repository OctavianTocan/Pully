import type { CacheRepositories } from '@pulls.review/core/cache'
import type { PostedDiffInput } from '@pulls.review/core/paste'
import type { BriefResult, CritiqueResult, DiffsPayload, GroupedResult, TourResult } from '@pulls.review/core/types'
import type { Storage } from 'unstorage'
import { postedPayload } from '@pulls.review/core/paste'
import { DiffGroupSchema, FileNoteSchema, serializeRef, TourBlockSchema } from '@pulls.review/core/types'
import * as v from 'valibot'
import { randomId } from './account'

/**
 * Diffs an agent posted with an account's key (plan 13). The store keeps what the agent sent: the patch, gzipped, and
 * the fields it sent with it, checked. The parsed files and the rule groups are worked out again from the patch on
 * every open, and what the agent sent is written into the server's cache under `posted:<id>`, which the cache's budget
 * never evicts, so the phone's tour, brief, review and analysis read it like any diff's.
 */

/** The most patch one post may carry. */
export const MAX_PATCH_BYTES = 5 * 1024 * 1024
/** The most a whole request may carry: the patch and everything sent with it. */
export const MAX_POST_BYTES = 8 * 1024 * 1024
export const MAX_FILES = 2000
export const MAX_TITLE = 200
export const MAX_DESCRIPTION = 20_000
export const MAX_AUTHOR = 80
/** One stored value's most, under a Durable Object's 2 MB a row; a longer patch is split. */
const CHUNK_CHARS = 1_500_000

const META = 'posted-diff:'
const AGENT = 'posted-agent:'
const PATCH = 'posted-patch:'

/** What the list and the API answer about one posted diff, without its patch. */
export interface PostedMeta {
  id: string
  accountId: string
  /** The key that posted it, by name; kept when the key is revoked. */
  keyName: string
  title: string
  description?: string
  author?: string
  version: number
  createdAt: string
  updatedAt: string
  files: number
  additions: number
  deletions: number
  /** How many stored values the gzipped patch of `version` takes. */
  chunks: number
}

/** The agent's own reading of its change, as checked and kept. */
export interface AgentFields {
  groups?: Omit<GroupedResult, 'source' | 'generatedAt'>
  tour?: Omit<TourResult, 'engine' | 'generatedAt'>
  brief?: { lang: string, locale: string }
  review?: Pick<CritiqueResult, 'summary' | 'findings' | 'dropped'>
}

const AgentGroupsSchema = v.object({
  overallSummary: v.optional(v.string()),
  groups: v.array(DiffGroupSchema),
  files: v.optional(v.array(FileNoteSchema)),
})

const AgentTourSchema = v.object({
  title: v.string(),
  overview: v.optional(v.string(), ''),
  stops: v.array(v.object({
    title: v.string(),
    purpose: v.optional(v.string(), ''),
    files: v.optional(v.array(v.string()), []),
    takeaway: v.optional(v.string(), ''),
    blocks: v.optional(v.array(TourBlockSchema), []),
    sources: v.optional(v.array(v.object({ path: v.string(), line: v.number(), why: v.optional(v.string(), '') })), []),
  })),
  glossary: v.optional(v.array(v.object({ term: v.string(), meaning: v.string() })), []),
  locale: v.optional(v.string(), 'en'),
})

const AgentBriefSchema = v.union([
  v.pipe(v.string(), v.transform(lang => ({ lang, locale: 'en' }))),
  v.object({ lang: v.string(), locale: v.optional(v.string(), 'en') }),
])

const AgentReviewSchema = v.object({
  summary: v.optional(v.string(), ''),
  findings: v.array(v.object({
    id: v.optional(v.string()),
    path: v.string(),
    side: v.optional(v.picklist(['additions', 'deletions']), 'additions'),
    line: v.number(),
    startLine: v.optional(v.number()),
    severity: v.optional(v.picklist(['bug', 'risk', 'nit']), 'nit'),
    title: v.string(),
    body: v.optional(v.string(), ''),
    suggestion: v.optional(v.string()),
  })),
})

/** What one post sends besides the patch. Every field is optional; a `PUT` keeps the ones it leaves out. */
export interface PostFields {
  title?: unknown
  description?: unknown
  author?: unknown
  groups?: unknown
  tour?: unknown
  brief?: unknown
  review?: unknown
  allowSecrets?: unknown
}

/** The token shapes a diff is refused for, on added lines only. */
const SECRET_SHAPES: [string, RegExp][] = [
  ['a GitHub token', /\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_\w{40,})/],
  ['an AWS access key', /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/],
  ['a Stripe key', /\b[rs]k_live_[0-9A-Za-z]{20,}/],
  ['an Anthropic key', /\bsk-ant-[\w-]{20,}/],
  ['an OpenAI key', /\bsk-[\w-]{32,}/],
  ['a Slack token', /\bxox[abprs]-[0-9A-Za-z-]{10,}/],
  ['a Google API key', /\bAIza[\w-]{35}\b/],
  ['a private key', /-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----/],
  ['a Pully key', /\bpully_[0-9A-Za-z]{32}\b/],
]

/** A file whose added lines are secrets by where they live: `.env` and `.env.local`, never `.env.example`. */
const ENV_FILE = /(?:^|\/)\.env(?:\.(?!example|sample|template|dist)[\w-]+)?$/

/** What one added line of [path] looks like, when it looks like a secret. */
function secretOn(path: string, added: string): string | undefined {
  const content = added.trim()
  if (ENV_FILE.test(path) && content && !content.startsWith('#'))
    return 'an environment file'
  return SECRET_SHAPES.find(([, pattern]) => pattern.test(added))?.[0]
}

/** The first added line of a file that looks like a secret, by its line in the new file. */
function secretIn(file: DiffsPayload['files'][number]): { path: string, line: number, what: string } | undefined {
  for (const hunk of file.hunks) {
    let line = hunk.newStart
    for (const row of hunk.patch.split('\n')) {
      const what = row.startsWith('+') && !row.startsWith('+++') ? secretOn(file.path, row.slice(1)) : undefined
      if (what)
        return { path: file.path, line, what }
      if (!row.startsWith('-') && !row.startsWith('\\'))
        line++
    }
  }
  return undefined
}

/** Where a patch seems to add a secret: the file, the new line and what it looks like, never the match itself. */
export function findSecret(diff: DiffsPayload): { path: string, line: number, what: string } | undefined {
  for (const file of diff.files) {
    const found = secretIn(file)
    if (found)
      return found
  }
  return undefined
}

function text(value: unknown, limit: number): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, limit) : undefined
}

type Warn = (message: string) => void

/** "groups was left out: ..." for a part that is not the shape it should be. */
function leftOut(field: string, result: { issues?: v.BaseIssue<unknown>[] }, warn: Warn): undefined {
  const first = result.issues?.[0]
  const at = first?.path?.map(item => String(item.key)).join('.')
  warn(`${field} was left out: ${first?.message ?? 'it is not the expected shape'}${at ? ` (at ${at})` : ''}.`)
  return undefined
}

function checkGroups(value: unknown, paths: Set<string>, warn: Warn): AgentFields['groups'] {
  const parsed = v.safeParse(AgentGroupsSchema, value)
  if (!parsed.success)
    return leftOut('groups', parsed, warn)
  let unknown = 0
  const keep = (list: string[]) => list.filter(path => paths.has(path) || (unknown++, false))
  const groups = parsed.output.groups.map(group => ({
    ...group,
    filePaths: keep(group.filePaths),
    children: group.children?.map(child => ({ ...child, filePaths: keep(child.filePaths) })),
  }))
  if (unknown)
    warn(`groups named ${unknown} ${unknown === 1 ? 'path' : 'paths'} not in the diff; they were left out.`)
  return { overallSummary: parsed.output.overallSummary, groups, files: parsed.output.files?.filter(note => paths.has(note.path)), schemaVersion: 1 }
}

function checkTour(value: unknown, paths: Set<string>, warn: Warn): AgentFields['tour'] {
  const parsed = v.safeParse(AgentTourSchema, value)
  if (!parsed.success)
    return leftOut('tour', parsed, warn)
  const { locale, ...tour } = parsed.output
  const stops = tour.stops.map(stop => ({
    ...stop,
    files: stop.files.filter(path => paths.has(path)),
    sources: stop.sources.filter(source => paths.has(source.path)),
  }))
  return { ...tour, stops, locale }
}

function checkBrief(value: unknown, warn: Warn): AgentFields['brief'] {
  const parsed = v.safeParse(AgentBriefSchema, value)
  return parsed.success ? parsed.output : leftOut('brief', parsed, warn)
}

function checkReview(value: unknown, paths: Set<string>, warn: Warn): AgentFields['review'] {
  const parsed = v.safeParse(AgentReviewSchema, value)
  if (!parsed.success)
    return leftOut('review', parsed, warn)
  const kept = parsed.output.findings.filter(finding => paths.has(finding.path))
  const dropped = parsed.output.findings.length - kept.length
  if (dropped)
    warn(`review named ${dropped} ${dropped === 1 ? 'finding' : 'findings'} outside the diff; they were left out.`)
  return {
    summary: parsed.output.summary,
    findings: kept.map((finding, index) => ({ ...finding, id: finding.id ?? `agent-${index + 1}`, body: finding.body })),
    dropped,
  }
}

/**
 * The agent's fields checked against the shapes Pully already uses, and against the diff: a group, tour stop, source or
 * finding may name only files in it. What fails is left out and said in `warnings`, and keeps what was there; the diff
 * still posts.
 */
export function checkAgentFields(fields: PostFields, diff: DiffsPayload, previous: AgentFields = {}): { agent: AgentFields, warnings: string[] } {
  const warnings: string[] = []
  const warn: Warn = message => warnings.push(message)
  const paths = new Set(diff.files.map(file => file.path))
  const checked: AgentFields = {
    groups: fields.groups === undefined ? undefined : checkGroups(fields.groups, paths, warn),
    tour: fields.tour === undefined ? undefined : checkTour(fields.tour, paths, warn),
    brief: fields.brief === undefined ? undefined : checkBrief(fields.brief, warn),
    review: fields.review === undefined ? undefined : checkReview(fields.review, paths, warn),
  }
  const agent: AgentFields = { ...previous }
  for (const [name, value] of Object.entries(checked) as [keyof AgentFields, never][]) {
    if (value !== undefined)
      agent[name] = value
  }
  return { agent, warnings }
}

async function gzip(textValue: string): Promise<string> {
  const stream = new Blob([textValue]).stream().pipeThrough(new CompressionStream('gzip'))
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer())
  let binary = ''
  for (let at = 0; at < bytes.length; at += 0x8000)
    binary += String.fromCharCode(...bytes.subarray(at, at + 0x8000))
  return btoa(binary)
}

async function gunzip(base64: string): Promise<string> {
  const bytes = Uint8Array.from(atob(base64), char => char.charCodeAt(0))
  return await new Response(new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))).text()
}

/** The posted diff, refused when it has no real change in it, too many files, or (when [secrets] says to look) a secret. */
async function checkedDiff(input: PostedDiffInput, secrets: boolean): Promise<DiffsPayload> {
  const diff = await postedPayload(input)
  if (!diff.files.length || diff.files.some(file => !file.path || (!file.hunks.length && !file.isBinary && file.status === 'modified' && !file.previousPath)))
    throw new PostError(422, 'That patch has no files in it. Send unified diff text, such as `git diff` prints.')
  if (diff.files.length > MAX_FILES)
    throw new PostError(413, `A patch can change at most ${MAX_FILES} files.`)
  const secret = secrets ? findSecret(diff) : undefined
  if (secret)
    throw new PostError(422, `${secret.path} line ${secret.line} looks like ${secret.what}. Take it out, or send allowSecrets: true if it is not a secret.`)
  return diff
}

export class PostError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

export interface PostedStore {
  /** A new posted diff, or a new version of `id` when given. Throws `PostError` for what the caller should be told. */
  save: (input: { accountId: string, keyName: string, id?: string, patch?: string, fields: PostFields }) => Promise<{ meta: PostedMeta, warnings: string[] }>
  meta: (accountId: string, id: string) => Promise<PostedMeta | undefined>
  list: (accountId: string) => Promise<PostedMeta[]>
  /** The patch and fields of the latest version, as posted. */
  read: (accountId: string, id: string) => Promise<{ meta: PostedMeta, patch: string, agent: AgentFields } | undefined>
  /** The payload of `id`, written into the cache with the agent's fields when the cache lacks it. */
  open: (accountId: string, id: string) => Promise<{ key: string, diff: DiffsPayload } | undefined>
  remove: (accountId: string, id: string) => Promise<boolean>
}

function inputOf(meta: PostedMeta, patch: string): PostedDiffInput {
  return { id: meta.id, patch, version: meta.version, title: meta.title, description: meta.description, author: meta.author ?? meta.keyName, createdAt: meta.createdAt, updatedAt: meta.updatedAt }
}

/**
 * The account's posted diffs in the server's store, and their copies in its cache.
 * @param storage - The server's store.
 * @param cache - The server session's cache over the same store.
 */
export function createPostedStore(storage: Storage, cache: CacheRepositories): PostedStore {
  const writes = new Map<string, Promise<unknown>>()
  /** Writes to one posted diff run one at a time, so two `PUT`s never interleave. */
  function exclusive<T>(id: string, task: () => Promise<T>): Promise<T> {
    const run = (writes.get(id) ?? Promise.resolve()).then(task, task)
    writes.set(id, run.catch(() => {}))
    return run
  }

  async function readPatch(meta: PostedMeta): Promise<string> {
    const parts = await Promise.all(Array.from({ length: meta.chunks }, (_, at) => storage.getItem<string>(`${PATCH}${meta.id}:${meta.version}:${at}`)))
    return gunzip(parts.join(''))
  }

  async function ownMeta(accountId: string, id: string): Promise<PostedMeta | undefined> {
    if (!/^[0-9A-Z]{6,32}$/i.test(id))
      return undefined
    const meta = await storage.getItem<PostedMeta>(`${META}${id}`)
    return meta?.accountId === accountId ? meta : undefined
  }

  /** Writes the payload and what the agent sent into the cache, where the phone's review reads them. */
  async function materialize(meta: PostedMeta, patch: string, agent: AgentFields): Promise<{ key: string, diff: DiffsPayload }> {
    const diff = await postedPayload(inputOf(meta, patch))
    const key = serializeRef(diff.ref)
    const headSha = `v${meta.version}`
    const at = meta.updatedAt
    const by = meta.author ?? meta.keyName
    await cache.diffs.putDiff(key, diff, headSha, { viewed: false })
    if (agent.groups)
      await cache.diffs.setAnalyzedResult(key, 'llm', { ...agent.groups, source: 'llm', generatedAt: at, model: by, sharedBy: by })
    if (agent.tour)
      await cache.tours.set(key, { ...agent.tour, engine: 'agent', model: by, generatedAt: at })
    if (agent.brief)
      await cache.briefs.set(key, { ...agent.brief, engine: 'agent', model: by, generatedAt: at } satisfies BriefResult)
    if (agent.review)
      await cache.critiques.set(key, 'agent', { ...agent.review, engine: 'agent', model: by, generatedAt: at })
    return { key, diff }
  }

  /** Stores [patch] as gzip in chunks under the version of [meta]; answers how many. */
  async function storePatch(meta: PostedMeta, patch: string): Promise<number> {
    const packed = await gzip(patch)
    const parts = Array.from({ length: Math.max(1, Math.ceil(packed.length / CHUNK_CHARS)) }, (_, at) => packed.slice(at * CHUNK_CHARS, (at + 1) * CHUNK_CHARS))
    await Promise.all(parts.map((part, at) => storage.setItem(`${PATCH}${meta.id}:${meta.version}:${at}`, part)))
    return parts.length
  }

  return {
    save: ({ accountId, keyName, id, patch, fields }) => exclusive(id ?? '', async () => {
      const previous = id ? await ownMeta(accountId, id) : undefined
      if (id && !previous)
        throw new PostError(404, 'There is no posted diff with that id.')
      if (patch === undefined && !previous)
        throw new PostError(400, 'A post needs a patch: the body as text/x-diff, or `patch` in JSON.')
      if (patch !== undefined && new TextEncoder().encode(patch).length > MAX_PATCH_BYTES)
        throw new PostError(413, `A patch can be at most ${MAX_PATCH_BYTES / 1024 / 1024} MB.`)
      const newPatch = patch !== undefined && (!previous || patch !== await readPatch(previous))
      const text_ = patch !== undefined ? patch : await readPatch(previous!)
      const now = new Date().toISOString()
      const meta: PostedMeta = {
        id: previous?.id ?? randomId(12),
        accountId,
        keyName: previous?.keyName ?? keyName,
        title: text(fields.title, MAX_TITLE) ?? previous?.title ?? 'Posted diff',
        description: text(fields.description, MAX_DESCRIPTION) ?? previous?.description,
        author: text(fields.author, MAX_AUTHOR) ?? previous?.author,
        version: newPatch ? (previous?.version ?? 0) + 1 : previous!.version,
        createdAt: previous?.createdAt ?? now,
        updatedAt: now,
        files: 0,
        additions: 0,
        deletions: 0,
        chunks: previous?.chunks ?? 0,
      }
      const diff = await checkedDiff(inputOf(meta, text_), newPatch && fields.allowSecrets !== true)
      const { agent, warnings } = checkAgentFields(fields, diff, previous ? await storage.getItem<AgentFields>(`${AGENT}${meta.id}`) ?? {} : {})
      meta.files = diff.files.length
      meta.additions = diff.files.reduce((sum, file) => sum + file.additions, 0)
      meta.deletions = diff.files.reduce((sum, file) => sum + file.deletions, 0)
      if (newPatch)
        meta.chunks = await storePatch(meta, text_)
      await storage.setItem(`${AGENT}${meta.id}`, agent)
      await storage.setItem(`${META}${meta.id}`, meta)
      // The older version's patch goes once the new one is stored; its cached copy is replaced below.
      if (newPatch && previous) {
        await Promise.all(Array.from({ length: previous.chunks }, (_, at) => storage.removeItem(`${PATCH}${previous.id}:${previous.version}:${at}`)))
      }
      await materialize(meta, text_, agent)
      return { meta, warnings }
    }),

    meta: ownMeta,

    async list(accountId) {
      const items = await storage.getItems<PostedMeta>(await storage.getKeys(META))
      return items.map(item => item.value).filter(meta => meta?.accountId === accountId).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    },

    async read(accountId, id) {
      const meta = await ownMeta(accountId, id)
      if (!meta)
        return undefined
      return { meta, patch: await readPatch(meta), agent: await storage.getItem<AgentFields>(`${AGENT}${id}`) ?? {} }
    },

    async open(accountId, id) {
      const meta = await ownMeta(accountId, id)
      if (!meta)
        return undefined
      const key = serializeRef({ kind: 'posted', id })
      const cached = await cache.diffs.getMeta(key)
      if (cached?.headSha === `v${meta.version}`) {
        const entry = await cache.diffs.get(key)
        if (entry) {
          await cache.diffs.touch(key)
          return { key, diff: entry.diff }
        }
      }
      const opened = await materialize(meta, await readPatch(meta), await storage.getItem<AgentFields>(`${AGENT}${id}`) ?? {})
      await cache.diffs.touch(key)
      return opened
    },

    remove: (accountId, id) => exclusive(id, async () => {
      const meta = await ownMeta(accountId, id)
      if (!meta)
        return false
      const key = serializeRef({ kind: 'posted', id })
      await Promise.all([
        ...Array.from({ length: meta.chunks }, (_, at) => storage.removeItem(`${PATCH}${id}:${meta.version}:${at}`)),
        storage.removeItem(`${AGENT}${id}`),
        storage.removeItem(`tour:${key}`),
        storage.removeItem(`brief:${key}`),
        storage.removeItem(`critique:${key}`),
        storage.removeItem(`ask-threads:${key}`),
        cache.diffs.remove(key),
      ])
      await storage.removeItem(`${META}${id}`)
      return true
    }),
  }
}
