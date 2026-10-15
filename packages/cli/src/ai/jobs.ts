import type { CliRunHooks, CliRunResult } from '@pulls.review/core/llm'
import type { AiActivity, AiJobRequest, AiJobSnapshot, AiJobStatus, AiUsage, LlmEngine } from '@pulls.review/core/local-rpc'
import type { PrPayload } from './pr-tools'
import type { AgentMount, AgentRuntime } from './runtime'
import type { ActivityPatch, McpServerSpec, RunOutcome, StreamParser } from './types'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { listCliModels } from './catalog'
import { claudeArgs, claudeInput, createClaudeParser } from './claude'
import { codexArgs, createCodexParser } from './codex'
import { INBOX_TOOL_HINT, inboxTools } from './inbox-tools'
import { prTools } from './pr-tools'
import { AGENT_HOME, agentRuntime, mcpNode, startAgent } from './runtime'
import { dropNulls, strictSchema } from './schema'

const MAX_RUNNING = 3
const WAIT_MS = 25_000
const TEXT_FLUSH_MS = 250
const KEEP_MS = 30 * 60_000
const KEEP_COUNT = 50
const KILL_GRACE_MS = 3_000
const TOTAL_LIMIT_MS = 30 * 60_000
/** Codex reports reasoning only once a summary is complete, so it can go quiet for much longer. */
const IDLE_LIMIT_MS: Record<LlmEngine, number> = { 'claude-code': 5 * 60_000, 'codex': 15 * 60_000 }
const LOGIN_HINT: Record<LlmEngine, string> = { 'claude-code': 'Run `claude` once on the server to sign in.', 'codex': 'Run `codex login` on the server to sign in.' }
const DOCKER_LOGIN_HINT: Record<LlmEngine, string> = { 'claude-code': 'Run `docker/agents/setup.sh login-claude` on the server to sign in.', 'codex': 'Run `docker/agents/setup.sh login-codex` on the server to sign in.' }
/** Codex's own state folder inside the agents image, kept in the home volume. */
const CONTAINER_CODEX_STATE = `${AGENT_HOME}/.cache/pulls-review/codex`
const ENGINE_NAME: Record<LlmEngine, string> = { 'claude-code': 'Claude', 'codex': 'Codex' }
const ENGINE_BIN: Record<LlmEngine, string> = { 'claude-code': 'claude', 'codex': 'codex' }

interface Job {
  id: string
  request: AiJobRequest
  status: AiJobStatus
  rev: number
  entries: Map<string, AiActivity>
  doneTitles: Map<string, string>
  dirty: Set<string>
  flushTimer?: ReturnType<typeof setTimeout>
  waiters: Set<() => void>
  listeners: Set<(activities: AiActivity[]) => void>
  result?: string
  error?: string
  usage?: AiUsage
  startedAt: number
  endedAt?: number
  stop?: (signal: NodeJS.Signals) => void
  settled: Promise<void>
  resolveSettled: () => void
}

const jobs = new Map<string, Job>()
const queue: (() => void)[] = []
let running = 0

function copy(entry: AiActivity): AiActivity {
  return { ...entry }
}

function wake(job: Job): void {
  for (const waiter of [...job.waiters])
    waiter()
}

function flush(job: Job, force = false): void {
  clearTimeout(job.flushTimer)
  job.flushTimer = undefined
  if (!job.dirty.size && !force)
    return
  job.rev += 1
  const changed = [...job.dirty].map(id => job.entries.get(id)!)
  job.dirty.clear()
  for (const entry of changed)
    entry.rev = job.rev
  if (changed.length) {
    for (const listener of job.listeners)
      listener(changed.map(copy))
  }
  wake(job)
}

function apply(job: Job, patch: ActivityPatch): void {
  const current = job.entries.get(patch.id)
  if (patch.doneTitle)
    job.doneTitles.set(patch.id, patch.doneTitle)
  const status = patch.status ?? current?.status ?? 'running'
  let title = patch.title ?? current?.title ?? ''
  if (status !== 'running' && !(patch.title && patch.status))
    title = job.doneTitles.get(patch.id) ?? title
  const now = Date.now()
  const next: AiActivity = {
    id: patch.id,
    kind: patch.kind ?? current?.kind ?? 'status',
    title,
    text: patch.text ?? current?.text,
    tool: patch.tool ?? current?.tool,
    status,
    startedAt: current?.startedAt ?? now,
    endedAt: status === 'running' ? undefined : current?.endedAt ?? now,
    rev: current?.rev ?? 0,
  }
  const structural = !current || current.title !== next.title || current.status !== next.status || current.kind !== next.kind || current.tool !== next.tool
  if (!structural && current.text === next.text)
    return
  job.entries.set(patch.id, next)
  job.dirty.add(patch.id)
  if (structural)
    flush(job)
  else
    job.flushTimer ??= setTimeout(flush, TEXT_FLUSH_MS, job)
}

function put(job: Job, patch: ActivityPatch): void {
  if (job.status === 'running')
    apply(job, patch)
}

function settle(job: Job, status: Exclude<AiJobStatus, 'running'>, fields: { result?: string, error?: string, usage?: AiUsage } = {}): void {
  if (job.status !== 'running')
    return
  for (const entry of job.entries.values()) {
    if (entry.status === 'running')
      apply(job, { id: entry.id, status: status === 'failed' ? 'failed' : 'done' })
  }
  job.status = status
  job.result = fields.result
  job.error = fields.error
  job.usage = fields.usage ?? job.usage
  job.endedAt = Date.now()
  flush(job, true)
  job.resolveSettled()
}

function acquire(): Promise<() => void> {
  return new Promise((resolve) => {
    const grant = () => {
      running += 1
      let released = false
      resolve(() => {
        if (released)
          return
        released = true
        running -= 1
        queue.shift()?.()
      })
    }
    if (running < MAX_RUNNING)
      grant()
    else
      queue.push(grant)
  })
}

function prune(): void {
  const now = Date.now()
  const ended = [...jobs.values()].filter(job => job.status !== 'running').sort((a, b) => (b.endedAt ?? 0) - (a.endedAt ?? 0))
  ended.forEach((job, index) => {
    if (index >= KEEP_COUNT || now - (job.endedAt ?? now) > KEEP_MS)
      jobs.delete(job.id)
  })
}

function hasCommit(sha: string): Promise<boolean> {
  return new Promise((resolve) => {
    execFile('git', ['-C', process.cwd(), 'cat-file', '-e', `${sha}^{commit}`], { timeout: 10_000 }, error => resolve(!error))
  })
}

/**
 * The folders git needs to read the checkout at `dir` from a container: its top level, and the shared
 * git folder when the checkout is a worktree of a repository elsewhere.
 */
function gitFolders(dir: string): Promise<string[]> {
  return new Promise((resolve) => {
    execFile('git', ['-C', dir, 'rev-parse', '--path-format=absolute', '--show-toplevel', '--git-common-dir'], { timeout: 10_000 }, (error, stdout) => {
      const folders = error ? [] : stdout.split('\n').map(line => line.trim()).filter(Boolean)
      resolve(folders.length ? folders : [dir])
    })
  })
}

function serverScript(): string {
  // Built, the server sits next to this chunk in dist/; run from source (tests, dev), it is the built copy.
  const candidates = [new URL('./pr-server.mjs', import.meta.url), new URL('../../dist/pr-server.mjs', import.meta.url)]
  return fileURLToPath(candidates.find(url => existsSync(url)) ?? candidates[0]!)
}

function toolHint(payload: PrPayload): string {
  const search = payload.checkout ? ', search_source to search the whole repository' : ''
  return `\n\nYou can look at this change yourself through the \`pr\` tools: read_file_diff for a file's full diff with line numbers, read_hunks, read_source for any file at the head commit, find_files, search_diff${search}, and read_discussion for the description and review comments. Use them when a diff above is cut short, or when a change only makes sense next to code the diff does not show.`
}

function failure(engine: AiJobRequest['engine'], runtime: AgentRuntime, outcome: RunOutcome, stderr: string, code: number | null): string {
  const lastLine = (text: string) => text.trim().split('\n').filter(line => line.trim()).at(-1)?.trim() ?? ''
  const apiMessage = /"message":\s*"((?:[^"\\]|\\.)*)"/.exec(stderr)?.[1]
  const detail = outcome.error ?? apiMessage ?? (lastLine(stderr) || (code === 0 ? `${ENGINE_NAME[engine]} returned an empty answer.` : `${ENGINE_BIN[engine]} exited ${code}.`))
  const hint = (runtime.kind === 'docker' ? DOCKER_LOGIN_HINT : LOGIN_HINT)[engine]
  return /not logged in|not signed in|not authenticated|unauthorized|\b401\b|invalid api key|please run.*login/i.test(detail) && !detail.includes(hint)
    ? `${detail} ${hint}`
    : detail
}

interface Launch {
  args: string[]
  /** What a container needs besides the job's folder: the tool server's code and the checkout it reads. */
  mounts: AgentMount[]
  input: string
  parser: StreamParser
  answerPath: string
}

async function prepare(job: Job, dir: string, runtime: AgentRuntime): Promise<Launch> {
  const { request } = job
  const docker = runtime.kind === 'docker'
  let system = request.system
  let server: McpServerSpec | undefined
  const mounts: AgentMount[] = []
  if (request.context) {
    const payload: PrPayload = { ...request.context, checkout: await hasCommit(request.context.headSha) ? process.cwd() : undefined }
    const payloadPath = join(dir, 'pr.json')
    await writeFile(payloadPath, JSON.stringify(payload))
    server = { name: 'pr', command: mcpNode(runtime), args: [serverScript(), payloadPath], tools: prTools(payload).map(tool => tool.name) }
    system += toolHint(payload)
    if (docker && payload.checkout)
      mounts.push(...(await gitFolders(payload.checkout)).map(path => ({ path, readOnly: true })))
  }
  else if (request.inbox?.pulls.length) {
    const payloadPath = join(dir, 'inbox.json')
    await writeFile(payloadPath, JSON.stringify(request.inbox))
    server = { name: 'inbox', command: mcpNode(runtime), args: [serverScript(), payloadPath], tools: inboxTools().map(tool => tool.name) }
    system += INBOX_TOOL_HINT
  }
  if (docker && server)
    mounts.push({ path: dirname(server.args[0]!), readOnly: true })
  const sink = { put: (patch: ActivityPatch) => put(job, patch) }
  const answerPath = join(dir, 'answer.txt')
  if (request.engine !== 'codex')
    return { args: claudeArgs({ ...request, system }, server), mounts, input: claudeInput(request.prompt), parser: createClaudeParser(sink), answerPath }

  const schemaPath = request.schema === undefined ? undefined : join(dir, 'schema.json')
  if (schemaPath)
    await writeFile(schemaPath, JSON.stringify(strictSchema(request.schema)))
  const model = request.model ?? await defaultCodexModel()
  return {
    args: codexArgs({ ...request, model }, answerPath, schemaPath, server, docker ? { stateDir: CONTAINER_CODEX_STATE, shell: false } : {}),
    mounts,
    input: `${system}\n\n${request.prompt}`,
    parser: createCodexParser(sink, { schema: request.schema !== undefined, model }),
    answerPath,
  }
}

/** The catalog's first model, which Codex never names in its own output. */
async function defaultCodexModel(): Promise<string | undefined> {
  const catalog = await listCliModels('codex').catch(() => undefined)
  return catalog?.models[0]?.id
}

async function answerOf(job: Job, launch: Launch): Promise<string | undefined> {
  const streamed = launch.parser.outcome().text
  if (job.request.engine !== 'codex')
    return streamed
  const answer = (await readFile(launch.answerPath, 'utf8').catch(() => '')).trim() || streamed
  return answer && job.request.schema !== undefined ? JSON.stringify(dropNulls(JSON.parse(answer))) : answer
}

function overdue(job: Job, lastLine: number): string | undefined {
  const { engine } = job.request
  const idle = Date.now() - lastLine
  if (idle > IDLE_LIMIT_MS[engine])
    return `${ENGINE_NAME[engine]} stopped responding (no activity for ${Math.round(idle / 60_000)}m).`
  if (Date.now() - job.startedAt > TOTAL_LIMIT_MS)
    return `${ENGINE_NAME[engine]} did not finish within ${TOTAL_LIMIT_MS / 60_000}m.`
}

async function execute(job: Job): Promise<void> {
  const { engine } = job.request
  const release = await acquire()
  if (job.status !== 'running')
    return release()
  const dir = await mkdtemp(join(tmpdir(), 'pulls-review-ai-'))
  let watchdog: ReturnType<typeof setInterval> | undefined
  try {
    put(job, { id: 'start', title: `Starting ${ENGINE_NAME[engine]}…` })
    const runtime = agentRuntime()
    const launch = await prepare(job, dir, runtime)
    if (job.status !== 'running')
      return

    let lastLine = Date.now()
    let stopped: string | undefined
    const proc = startAgent({ bin: ENGINE_BIN[engine], args: launch.args, cwd: dir, mounts: launch.mounts }, {
      runtime,
      onLine(line) {
        lastLine = Date.now()
        launch.parser.feed(line)
      },
    })
    job.stop = signal => proc.kill(signal)
    proc.child.stdin.end(launch.input)
    watchdog = setInterval(() => {
      stopped ??= overdue(job, lastLine)
      if (!stopped)
        return
      proc.kill('SIGTERM')
      setTimeout(() => proc.kill('SIGKILL'), KILL_GRACE_MS).unref()
    }, 10_000)

    const code = await proc.exited
    if (job.status !== 'running')
      return
    const outcome = launch.parser.outcome()
    if (stopped)
      return settle(job, 'failed', { error: stopped, usage: outcome.usage })
    const text = await answerOf(job, launch)
    if (text && !outcome.error)
      settle(job, 'done', { result: text, usage: outcome.usage })
    else
      settle(job, 'failed', { error: failure(engine, runtime, outcome, proc.stderr(), code), usage: outcome.usage })
  }
  catch (error) {
    settle(job, 'failed', { error: error instanceof Error ? error.message : String(error) })
  }
  finally {
    clearInterval(watchdog)
    job.stop = undefined
    release()
    await rm(dir, { recursive: true, force: true }).catch(() => {})
  }
}

function snapshot(job: Job, after: number): AiJobSnapshot {
  return {
    id: job.id,
    label: job.request.label,
    key: job.request.key,
    engine: job.request.engine,
    model: job.request.model,
    effort: job.request.effort,
    status: job.status,
    rev: job.rev,
    activities: [...job.entries.values()].filter(entry => entry.rev > after).map(copy),
    result: job.result,
    error: job.error,
    usage: job.usage,
    startedAt: job.startedAt,
    endedAt: job.endedAt,
  }
}

/**
 * Starts a Claude Code or Codex run on the server, or joins the running one with the same `key`.
 * @param request - Engine, model, prompts, and the PR the model may inspect.
 * @returns The job's id, for `waitAiJob` and `cancelAiJob`.
 */
export function startAiJob(request: AiJobRequest): string {
  prune()
  if (request.key) {
    const same = [...jobs.values()].find(job => job.status === 'running' && job.request.key === request.key)
    if (same)
      return same.id
  }
  let resolveSettled!: () => void
  const job: Job = {
    id: randomUUID(),
    request,
    status: 'running',
    rev: 0,
    entries: new Map(),
    doneTitles: new Map(),
    dirty: new Set(),
    waiters: new Set(),
    listeners: new Set(),
    startedAt: Date.now(),
    settled: new Promise(resolve => resolveSettled = resolve),
    resolveSettled: () => resolveSettled(),
  }
  jobs.set(job.id, job)
  apply(job, { id: 'start', kind: 'status', title: running < MAX_RUNNING ? `Starting ${ENGINE_NAME[request.engine]}…` : 'Waiting for another run to finish…' })
  void execute(job)
  return job.id
}

/**
 * A job's state once it has moved past revision `after`, or after a while if it has not.
 * @param id - The job.
 * @param after - The last revision the caller has seen; 0 for everything.
 * @returns The job, with only the work-log entries that changed since `after`.
 * @throws When the server no longer knows the job.
 */
export async function waitAiJob(id: string, after: number): Promise<AiJobSnapshot> {
  const job = jobs.get(id)
  if (!job)
    throw new Error('That job is gone: the server restarted or dropped it.')
  if (job.rev <= after && job.status === 'running') {
    await new Promise<void>((resolve) => {
      let timer: ReturnType<typeof setTimeout> | undefined
      const done = () => {
        clearTimeout(timer)
        job.waiters.delete(done)
        resolve()
      }
      timer = setTimeout(done, WAIT_MS)
      job.waiters.add(done)
    })
  }
  return snapshot(job, after)
}

/**
 * Stops a running job and the CLI behind it.
 * @param id - The job.
 * @returns Whether a running job was stopped.
 */
export function cancelAiJob(id: string): boolean {
  const job = jobs.get(id)
  if (!job || job.status !== 'running')
    return false
  const stop = job.stop
  stop?.('SIGTERM')
  if (stop)
    setTimeout(stop, KILL_GRACE_MS, 'SIGKILL').unref()
  settle(job, 'cancelled', { error: 'Cancelled.' })
  return true
}

/**
 * The jobs running now or finished in the last half hour.
 * @returns Newest first, without their work logs.
 */
export function listAiJobs(): AiJobSnapshot[] {
  prune()
  return [...jobs.values()].sort((a, b) => b.startedAt - a.startedAt).map(job => snapshot(job, Number.POSITIVE_INFINITY))
}

/**
 * Runs a job in-process and waits for its answer, for server-side callers such as background analysis.
 * @param request - Engine, model, prompts, and the PR the model may inspect.
 * @param hooks - Abort signal (cancels the job) and a live work-log listener.
 * @returns The answer and the usage the CLI reported.
 * @throws When the job fails or is cancelled.
 */
export async function runAiJob(request: AiJobRequest, hooks: CliRunHooks = {}): Promise<CliRunResult> {
  const id = startAiJob(request)
  const job = jobs.get(id)!
  const listener = hooks.onActivity
  if (listener) {
    if (job.entries.size)
      listener([...job.entries.values()].map(copy))
    job.listeners.add(listener)
  }
  const abort = () => cancelAiJob(id)
  hooks.signal?.addEventListener('abort', abort, { once: true })
  if (hooks.signal?.aborted)
    abort()
  try {
    await job.settled
  }
  finally {
    if (listener)
      job.listeners.delete(listener)
    hooks.signal?.removeEventListener('abort', abort)
  }
  if (job.status !== 'done')
    throw new Error(job.error ?? 'The run failed.')
  return { text: job.result ?? '', usage: job.usage }
}
