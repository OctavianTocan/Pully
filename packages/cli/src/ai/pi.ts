import type { AgentEvent, AgentMessage, AgentTool, StreamFn } from '@earendil-works/pi-agent-core'
import type { AssistantMessage, Message, Model, ThinkingLevel, Tool } from '@earendil-works/pi-ai'
import type { AiJobRequest, AiUsage, CliModel, CliModelCatalog } from '@pulls.review/core/local-rpc'
import type { ToolSpec } from './pr-tools'
import type { RunOutcome, RunSink } from './types'
import process from 'node:process'
import { runAgentLoop } from '@earendil-works/pi-agent-core'
import { createModels, createProvider } from '@earendil-works/pi-ai'
import { openAICompletionsApi } from '@earendil-works/pi-ai/api/openai-completions.lazy'
import { describeTool } from './titles'

/*
 * The `pi` engine: the server's own agent loop (pi-agent-core) calling any model behind an OpenAI-compatible endpoint,
 * such as CLIProxyAPI. The model gets the `pr` and `inbox` tools the CLIs reach over MCP, and nothing else.
 */

/** Where the `pi` engine sends its requests; read from the server's environment, never from a client. */
export interface PiConfig {
  /** The OpenAI-compatible endpoint up to its `/v1`, e.g. `http://127.0.0.1:8317/v1` for CLIProxyAPI. */
  baseUrl: string
  /** The endpoint's client key (for CLIProxyAPI, one of its `api-keys`); empty when it takes none. */
  apiKey: string
  /** The model a run uses when it names none; the endpoint's first listed model when unset. */
  model?: string
}

export const PI_SETUP_HINT = 'Set PULLS_REVIEW_PI_BASE_URL (for CLIProxyAPI, http://127.0.0.1:8317/v1) and PULLS_REVIEW_PI_API_KEY on the server.'
const KEY_HINT = 'Check PULLS_REVIEW_PI_API_KEY on the server.'

/**
 * The endpoint the environment sets up: `PULLS_REVIEW_PI_BASE_URL`, `PULLS_REVIEW_PI_API_KEY` and `PULLS_REVIEW_PI_MODEL`.
 * @param env - The environment to read.
 * @returns The endpoint, or `undefined` while no base URL is set.
 */
export function piConfig(env: NodeJS.ProcessEnv = process.env): PiConfig | undefined {
  const baseUrl = env.PULLS_REVIEW_PI_BASE_URL?.trim().replace(/\/+$/, '')
  if (!baseUrl)
    return undefined
  return { baseUrl, apiKey: env.PULLS_REVIEW_PI_API_KEY?.trim() ?? '', model: env.PULLS_REVIEW_PI_MODEL?.trim() || undefined }
}

/** What a run fails with while the server has no endpoint. */
export function piNotSetUp(): Error {
  return new Error(`Pi is not set up on this server. ${PI_SETUP_HINT}`)
}

/** Reasoning efforts offered for every model: the endpoint does not say which models reason, and drops what it cannot map. */
export const PI_EFFORTS = ['low', 'medium', 'high'] as const

const MODELS_TIMEOUT_MS = 15_000

/** Words vendors write in their own case. */
const WORD_CASE: Record<string, string> = {
  gpt: 'GPT',
  oss: 'OSS',
  glm: 'GLM',
  deepseek: 'DeepSeek',
  minimax: 'MiniMax',
  highspeed: 'HighSpeed',
  vl: 'VL',
  mimo: 'MiMo',
  longcat: 'LongCat',
  agy: 'AGY',
  xai: 'xAI',
}
/** A reasoning effort a proxy folds into the id, as `-high` or `(high)`; shown in brackets. `max` is left out: Codex Max is a model. */
const EFFORT_WORD = /^(?:minimal|low|medium|high|xhigh)$/
/** Claude's older ids put the version first: `claude-3-7-sonnet` is Claude Sonnet 3.7. */
const CLAUDE_TIER = /^(?:opus|sonnet|haiku)$/

/**
 * Who makes each model family, by the family's word: the group a model goes under when its id names no route. A family
 * whose maker has a route of its own on CLIProxyAPI takes that route's name, so `claude-opus-5-5` and
 * `claude-claude-opus-5-5` are one model under "Claude", and GPT sits under "Codex" with the models its route serves.
 */
const FAMILY_GROUP: Record<string, string> = {
  claude: 'Claude',
  gpt: 'Codex',
  chatgpt: 'Codex',
  codex: 'Codex',
  o: 'Codex',
  gemini: 'Gemini',
  qwen: 'Qwen',
  qwq: 'Qwen',
  grok: 'xAI',
  deepseek: 'DeepSeek',
  kimi: 'Kimi',
  glm: 'GLM',
  minimax: 'MiniMax',
  mimo: 'Xiaomi MiMo',
  longcat: 'Meituan LongCat',
  hy: 'Tencent HY',
  hunyuan: 'Tencent HY',
  llama: 'Meta',
  muse: 'Meta',
  mistral: 'Mistral',
  codestral: 'Mistral',
  devstral: 'Mistral',
  magistral: 'Mistral',
}

/**
 * Route words a proxy puts before an id to say which account serves it (CLIProxyAPI's credential prefixes), and the
 * name each provider writes itself by. `claude`, `codex`, `gemini` and `qwen` are model families too, so they count as a
 * route only before another family's word (`claude-claude-opus-5`, `codex-gpt-6-sol`); the rest count before anything.
 */
const ROUTE_GROUP: Record<string, string> = {
  agy: 'AGY',
  antigravity: 'AGY',
  claude: 'Claude',
  codex: 'Codex',
  gemini: 'Gemini',
  qwen: 'Qwen',
  iflow: 'iFlow',
  kiro: 'Kiro',
  copilot: 'Copilot',
  openrouter: 'OpenRouter',
  vertex: 'Vertex',
  aistudio: 'AI Studio',
}
const FAMILY_ROUTES = new Set(['claude', 'codex', 'gemini', 'qwen'])
/** Makers some ids open with before their own family's word (`xai-grok-4.7`); dropped, and the family names the group. */
const VENDOR_WORDS = new Set(['xai', 'openai', 'anthropic', 'google', 'moonshot', 'moonshotai', 'alibaba', 'zai', 'zhipu', 'meta', 'xiaomi', 'meituan', 'tencent', 'deepseek', 'minimax', 'mistralai'])

/**
 * Words that mark a model which does not answer in text, or is not a chat model: image, video, speech and embedding
 * models. Pulls only reads and writes text, so they are left off the list. A chat model that reads images (`vision`)
 * stays.
 */
const NOT_CHAT = new Set(['image', 'images', 'imagine', 'imagen', 'dall', 'dalle', 'banana', 'sora', 'veo', 'video', 'embed', 'embedding', 'embeddings', 'tts', 'speech', 'audio', 'realtime', 'whisper', 'transcribe', 'moderation', 'rerank', 'reranker'])

/** A snapshot tag inside an id, which says nothing a reader needs: a date (`20250929`, `0309`) or a revision (`001`). */
function isSnapshot(word: string): boolean {
  return /^\d{8}$/.test(word) || /^0\d{2}$/.test(word) || /^(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])$/.test(word)
}

function pretty(word: string): string {
  const lower = word.toLowerCase()
  if (WORD_CASE[lower])
    return WORD_CASE[lower]
  // A size reads in capitals: 120b is 120B, 256k is 256K.
  if (/^\d+(?:\.\d+)?[bkm]$/.test(lower))
    return lower.toUpperCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

/** Joins Claude's two-word versions: `opus-5-5` is Opus 5.5. */
function joinVersions(words: string[]): string[] {
  const out: string[] = []
  for (let index = 0; index < words.length; index++) {
    const word = words[index]!
    const next = words[index + 1]
    if (/^\d{1,2}$/.test(word) && next !== undefined && /^\d{1,2}$/.test(next)) {
      out.push(`${word}.${next}`)
      index += 1
    }
    else {
      out.push(word)
    }
  }
  return out
}

/** The family a word of an id opens: `qwen3.8` is qwen, `o4` is o, `hy3` is hy. */
function familyWord(word: string | undefined): string {
  const lower = word?.toLowerCase() ?? ''
  return /^o\d/.test(lower) ? 'o' : /^[a-z]+/.exec(lower)?.[0] ?? ''
}

/** An id split into the route it names, when it names one, and the model's own id. */
interface RoutedId {
  /** The provider the route words name, as it writes itself. */
  route?: string
  /** The id without its route and vendor words. */
  model: string
}

/**
 * Takes the route and vendor words off the front of an id: `agy-gemini-3.8-flash` is AGY's `gemini-3.8-flash`,
 * `claude:claude-opus-5` Claude's `claude-opus-5`, `xai-grok-4.7` just `grok-4.7`. A path keeps only its last part
 * (`openrouter/deepseek-v3.1`), naming its route when the first part is one.
 * @param id - The id the endpoint lists.
 * @returns The route and the rest.
 */
function splitRoute(id: string): RoutedId {
  let route: string | undefined
  const slash = id.trim().split('/')
  if (slash.length > 1)
    route = ROUTE_GROUP[slash[0]!.toLowerCase()]
  let rest = slash.at(-1)!
  for (let round = 0; round < 3; round++) {
    const match = /^([a-z]+)[-_:.]([^-_:.].*)$/i.exec(rest)
    if (!match)
      break
    const head = match[1]!.toLowerCase()
    const next = familyWord(/^[^-_:.\s(]+/.exec(match[2]!)?.[0])
    const beforeFamily = next !== '' && FAMILY_GROUP[next] !== undefined
    if (ROUTE_GROUP[head] && (beforeFamily || !FAMILY_ROUTES.has(head))) {
      route ??= ROUTE_GROUP[head]
      rest = match[2]!
    }
    else if (VENDOR_WORDS.has(head) && beforeFamily) {
      rest = match[2]!
    }
    else {
      break
    }
  }
  return { route, model: rest }
}

/**
 * A readable name for a model id, the way its vendor writes it: `claude-opus-5-5` reads "Claude Opus 5.5",
 * `claude-3-7-sonnet-20250219` "Claude Sonnet 3.7", `gpt-5.5-codex` "GPT-5.5 Codex", `gpt-oss-120b-medium`
 * "GPT-OSS 120B (Medium)", `gemini-3.8-flash-high` "Gemini 3.8 Flash (High)". A provider or route prefix
 * (`agy-`, `codex-`, `openrouter/`, `xai-`), dates and revision tags are dropped.
 * @param id - The id the endpoint lists.
 * @returns The name to show.
 */
export function piModelName(id: string): string {
  let base = splitRoute(id).model.trim()
  // CLIProxyAPI takes a thinking level after the id: `gpt-5.5(high)`.
  const bracket = /\(([^)]*)\)$/.exec(base)
  base = base.slice(0, bracket?.index).replace(/-\d{4}-\d{2}-\d{2}(?=$|-)/, '')
  const words = joinVersions(base.split(/[-_:\s]+/).filter(word => word && !isSnapshot(word)))
  // A prefix that repeats the family's word ("claude claude opus") says it once.
  while (words.length > 1 && words[0]!.toLowerCase() === words[1]!.toLowerCase())
    words.shift()
  let effort = bracket?.[1]?.trim() || undefined
  if (!effort && words.length > 2 && EFFORT_WORD.test(words.at(-1)!.toLowerCase()))
    effort = words.pop()
  if (words[0]?.toLowerCase() === 'claude' && /^\d/.test(words[1] ?? '') && CLAUDE_TIER.test(words[2]?.toLowerCase() ?? ''))
    words.splice(1, 2, words[2]!, words[1]!)
  // OpenAI writes its o-series in lower case: o4-mini.
  const name = /^o\d+$/i.test(words[0] ?? '')
    ? words.join('-').toLowerCase()
    : words.map(pretty).join(' ').replace(/^GPT (?=\d|OSS\b)/, 'GPT-').replace(/\bNon /g, 'Non-')
  return effort ? `${name} (${pretty(effort)})` : name
}

/**
 * Who serves a model: the route its id names, else a route the endpoint reports as the model's owner (CLIProxyAPI's
 * `owned_by`, when it is one), else the maker of its family, else the family's own word.
 * @param id - The id the endpoint lists.
 * @param ownedBy - The `owned_by` of its `/models` entry, if any.
 * @returns The group's name, as the provider writes it.
 */
export function piModelGroup(id: string, ownedBy?: string): string {
  const { route, model } = splitRoute(id)
  if (route)
    return route
  const owner = ownedBy?.trim().toLowerCase()
  if (owner && ROUTE_GROUP[owner] && !FAMILY_ROUTES.has(owner))
    return ROUTE_GROUP[owner]
  const family = familyWord(model)
  return FAMILY_GROUP[family] ?? (family ? pretty(family) : 'Other')
}

/** True for a model that makes images, video or speech, or embeds, rather than answering in text. */
export function piIsChatModel(id: string): boolean {
  return !id.toLowerCase().split(/[-_/:.\s()]+/).some(word => NOT_CHAT.has(word))
}

/** The name's version as a number to sort by: "Kimi K2.8" is 2.8; a size such as 120B is not a version. */
function version(name: string): number {
  const found = [...name.matchAll(/(\d+)(?:\.(\d+))?([BKM])?\b/g)].find(match => !match[3])
  return found ? Number(found[1]) * 1000 + Number(found[2] ?? 0) : -1
}

/**
 * The chat models of an OpenAI-compatible `/models` answer, one row per model a reader can tell apart: grouped by who
 * serves them (`group`), each group's families newest version first. The default comes first and its group leads;
 * the other groups follow by name. Two ids that read the same in one group are one row: the default's id, else the
 * one without a route, else the shorter.
 * @param body - The parsed answer.
 * @param preferred - The model the server runs by default, when it names one.
 * @returns The catalog's models.
 */
export function piModels(body: unknown, preferred?: string): CliModel[] {
  const data = (body as { data?: unknown })?.data
  const entries = new Map<string, string | undefined>()
  for (const entry of Array.isArray(data) ? data : []) {
    const { id, owned_by: ownedBy } = (entry ?? {}) as { id?: unknown, owned_by?: unknown }
    if (typeof id === 'string' && id.trim() !== '' && !entries.has(id) && piIsChatModel(id))
      entries.set(id, typeof ownedBy === 'string' ? ownedBy : undefined)
  }
  const ids = [...entries.keys()]
  const fallback = preferred && entries.has(preferred) ? preferred : ids[0]
  const rows = new Map<string, { id: string, name: string, group: string, routed: boolean }>()
  // The default's id wins, then an id with no route in it, then the shorter one; ties keep the endpoint's order.
  const rank = (row: { id: string, routed: boolean }) => (row.id === fallback ? 0 : 1) * 1e6 + Number(row.routed) * 1e3 + row.id.length
  for (const id of ids) {
    const row = { id, name: piModelName(id), group: piModelGroup(id, entries.get(id)), routed: splitRoute(id).model !== id }
    const key = `${row.group}\n${row.name.toLowerCase()}`
    const kept = rows.get(key)
    if (!kept || rank(row) < rank(kept))
      rows.set(key, row)
  }
  const named = [...rows.values()]
  const lead = named.find(row => row.id === fallback)?.group
  const family = (name: string) => /^[a-z]+/i.exec(name)?.[0].toLowerCase() ?? ''
  named.sort((a, b) => Number(b.id === fallback) - Number(a.id === fallback)
    || Number(b.group === lead) - Number(a.group === lead)
    || a.group.localeCompare(b.group, 'en', { sensitivity: 'base' })
    || family(a.name).localeCompare(family(b.name))
    || version(b.name) - version(a.name)
    || a.name.localeCompare(b.name))
  return named.map(({ id, name, group }) => ({ id, name, group, efforts: [...PI_EFFORTS], isDefault: id === fallback || undefined }))
}

/**
 * The models the endpoint lists.
 * @param config - The endpoint; `piConfig()` when left out.
 * @param fetcher - The fetch to use; tests pass their own.
 * @returns The catalog, with the endpoint's host as its account line.
 * @throws When the server has no endpoint, or the endpoint fails or lists nothing.
 */
export async function piCatalog(config = piConfig(), fetcher: typeof fetch = fetch): Promise<CliModelCatalog> {
  if (!config)
    throw piNotSetUp()
  const response = await fetcher(`${config.baseUrl}/models`, {
    headers: config.apiKey ? { Authorization: `Bearer ${config.apiKey}` } : {},
    signal: AbortSignal.timeout(MODELS_TIMEOUT_MS),
  }).catch((error: unknown) => {
    throw new Error(`Could not reach ${config.baseUrl}: ${error instanceof Error ? error.message : String(error)}`)
  })
  if (!response.ok)
    throw new Error(`${config.baseUrl} answered ${response.status} for its model list.${response.status === 401 || response.status === 403 ? ` ${KEY_HINT}` : ''}`)
  const models = piModels(await response.json().catch(() => undefined), config.model)
  if (!models.length)
    throw new Error(`${config.baseUrl} lists no models.`)
  return { engine: 'pi', models, account: new URL(config.baseUrl).host }
}

/**
 * The pi-ai model for an id the endpoint serves. Every model is marked as reasoning, so a picked effort goes out as
 * `reasoning_effort`; with none picked nothing is sent and the model reasons as it does by default.
 * @param id - The model id.
 * @param config - The endpoint.
 * @returns The model description pi-ai streams with.
 */
export function piModel(id: string, config: PiConfig): Model<'openai-completions'> {
  return {
    id,
    name: piModelName(id),
    api: 'openai-completions',
    provider: 'pi',
    baseUrl: config.baseUrl,
    reasoning: true,
    input: ['text'],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 200_000,
    // The endpoint does not say each model's output limit, so no cap is sent and every model answers within its own.
    maxTokens: 0,
    // A proxy in front of several vendors: the plain `system` role and no `store` field are what every one of them takes.
    compat: { supportsDeveloperRole: false, supportsStore: false },
  }
}

/**
 * A stream function calling the endpoint with its key.
 * @param config - The endpoint.
 * @param model - The model the run uses; pi-ai streams only models its provider was made with.
 * @returns What the agent loop streams each turn through.
 */
export function piStreamFn(config: PiConfig, model: Model<'openai-completions'>): StreamFn {
  const models = createModels()
  models.setProvider(createProvider({
    id: 'pi',
    // pi-ai wants a key; an endpoint that takes none ignores the one it gets.
    auth: { apiKey: { name: 'API key', resolve: async () => ({ auth: { apiKey: config.apiKey || 'none' } }) } },
    models: [model],
    api: { 'openai-completions': openAICompletionsApi() },
  }))
  return (next, context, options) => models.streamSimple(next, context, options)
}

/** Past this many turns a run is asked to answer with what it has; at `MAX_TURNS` it stops. */
const STEER_TURN = 40
const MAX_TURNS = 50
/** Tool output a run may read before it is asked to answer, so the transcript stays inside the context window. */
const READ_BUDGET_CHARS = 400_000
const TEXT_LIMIT = 8_000
const PREVIEW_LIMIT = 400
/** The tool a run with an answer schema answers through; its arguments are the answer. */
export const SUBMIT_TOOL = 'submit_answer'

function tail(text: string, limit = TEXT_LIMIT): string {
  return text.length > limit ? `…${text.slice(-limit)}` : text
}

function textOf(content: readonly { type: string, text?: string }[]): string {
  return content.map(part => part.type === 'text' ? part.text ?? '' : '').join('\n')
}

function preview(text: string): string | undefined {
  const trimmed = text.trim()
  return trimmed ? (trimmed.length > PREVIEW_LIMIT ? `${trimmed.slice(0, PREVIEW_LIMIT)}…` : trimmed) : undefined
}

/**
 * Tools the model may call, from the same specs and handlers the MCP tool server serves.
 * @param specs - The tools, as `tools/list` describes them.
 * @param call - Runs one by name; its text is what the model reads, and a throw is an error result.
 * @returns The tools for the agent loop.
 */
export function piTools(specs: readonly ToolSpec[], call: (name: string, args: Record<string, unknown>) => Promise<string>): AgentTool[] {
  return specs.map(spec => ({
    name: spec.name,
    label: spec.name,
    description: spec.description,
    parameters: spec.inputSchema as Tool['parameters'],
    execute: async (_id, args) => ({ content: [{ type: 'text', text: await call(spec.name, args as Record<string, unknown>) }], details: undefined }),
  }))
}

/** What a run needs besides the request: its tools, where its work log goes, how to stop it and how to reach the model. */
export interface PiRunOptions {
  tools: AgentTool[]
  sink: RunSink
  signal: AbortSignal
  config: PiConfig
  /** How turns reach the model; `piStreamFn` when left out, tests pass their own. */
  streamFn?: StreamFn
  /** Called on every event of the loop, so a watchdog can tell a quiet run from a stuck one. */
  onEvent?: () => void
}

function sumUsage(messages: readonly AssistantMessage[], model: string, durationMs: number): AiUsage {
  const total = (pick: (message: AssistantMessage) => number | undefined) => messages.reduce((sum, message) => sum + (pick(message) ?? 0), 0)
  const reasoning = messages.some(message => message.usage.reasoning !== undefined) ? total(message => message.usage.reasoning) : undefined
  const cost = total(message => message.usage.cost.total)
  return {
    inputTokens: total(message => message.usage.input),
    outputTokens: total(message => message.usage.output),
    cacheReadTokens: total(message => message.usage.cacheRead),
    cacheWriteTokens: total(message => message.usage.cacheWrite),
    reasoningTokens: reasoning,
    costUsd: cost || undefined,
    durationMs,
    model: messages.findLast(message => message.model)?.model ?? model,
  }
}

/** The loop's events as work-log entries, plus what the run's limits count. */
interface WorkLog {
  feed: (event: AgentEvent) => void
  turn: number
  charsRead: number
  assistants: AssistantMessage[]
}

type StreamUpdate = Extract<AgentEvent, { type: 'message_update' }>['assistantMessageEvent']

const BLOCK_TITLES = {
  thinking: { title: 'Thinking', doneTitle: 'Thought' },
  text: { title: 'Writing', doneTitle: 'Wrote' },
} as const

/**
 * Turns the agent loop's events into the same work-log entries the CLI parsers report.
 * @param sink - Receives the entries.
 * @returns The log to feed every event to.
 */
function createWorkLog(sink: RunSink): WorkLog {
  const blocks = new Map<string, string>()

  function onStream(log: WorkLog, update: StreamUpdate): void {
    const key = 'contentIndex' in update ? `${log.turn}:${update.contentIndex}` : ''
    if (update.type === 'thinking_start' || update.type === 'text_start') {
      const kind = update.type === 'thinking_start' ? 'thinking' : 'text'
      const id = `${kind}:${key}`
      blocks.set(key, id)
      sink.put({ id, kind, status: 'running', ...BLOCK_TITLES[kind] })
      return
    }
    const id = blocks.get(key)
    if (!id || !(update.type === 'thinking_delta' || update.type === 'text_delta' || update.type === 'thinking_end' || update.type === 'text_end'))
      return
    const block = update.partial.content[update.contentIndex]
    const text = block?.type === 'thinking' ? block.thinking : block?.type === 'text' ? block.text : ''
    const done = update.type.endsWith('_end')
    sink.put({ id, text: tail(done ? text.trim() : text), ...(done ? { status: 'done' as const } : {}) })
  }

  function onToolStart(event: Extract<AgentEvent, { type: 'tool_execution_start' }>): void {
    const title = describeTool(event.toolName === SUBMIT_TOOL ? 'StructuredOutput' : event.toolName, (event.args ?? {}) as Record<string, unknown>)
    sink.put({ id: `tool:${event.toolCallId}`, kind: 'tool', tool: event.toolName, title: title.running, doneTitle: title.done, status: 'running' })
  }

  function onToolEnd(log: WorkLog, event: Extract<AgentEvent, { type: 'tool_execution_end' }>): void {
    const submit = event.toolName === SUBMIT_TOOL
    const output = submit ? '' : textOf((event.result as { content?: { type: string, text?: string }[] } | undefined)?.content ?? [])
    log.charsRead += output.length
    sink.put({ id: `tool:${event.toolCallId}`, status: event.isError ? 'failed' : 'done', text: submit ? undefined : preview(output) })
  }

  const log: WorkLog = {
    turn: 0,
    charsRead: 0,
    assistants: [],
    feed(event) {
      if (event.type === 'agent_start')
        sink.put({ id: 'start', status: 'done', title: 'Started' })
      else if (event.type === 'turn_start')
        log.turn += 1
      else if (event.type === 'message_update')
        onStream(log, event.assistantMessageEvent)
      else if (event.type === 'message_end' && event.message.role === 'assistant')
        log.assistants.push(event.message)
      else if (event.type === 'tool_execution_start')
        onToolStart(event)
      else if (event.type === 'tool_execution_end')
        onToolEnd(log, event)
    },
  }
  return log
}

/**
 * Runs one prompt through the agent loop: the model reads through its tools as long as it needs, then answers, in
 * text, or through `submit_answer` when the request has a schema.
 * @param request - System prompt, prompt, model and effort, and the answer's schema if any.
 * @param options - Tools, the work-log sink, the abort signal, the stream function and the endpoint.
 * @returns The answer (JSON text when there is a schema) or the error, and the usage.
 */
export async function runPi(request: AiJobRequest & { model: string }, options: PiRunOptions): Promise<RunOutcome> {
  const { sink } = options
  const startedAt = Date.now()
  const schema = request.schema
  let answer: unknown
  let submitted = false
  let steered = false
  let nudged = false
  const tools = [...options.tools]
  if (schema !== undefined) {
    tools.push({
      name: SUBMIT_TOOL,
      label: 'Submit the answer',
      description: 'Submit your final answer. Its arguments are the whole answer; call it once, when you are done.',
      parameters: schema as Tool['parameters'],
      execute: async (_id, args) => {
        answer = args
        submitted = true
        return { content: [{ type: 'text', text: 'Answer received.' }], details: undefined, terminate: true }
      },
    })
  }
  const system = schema === undefined
    ? request.system
    : `${request.system}\n\nWhen you are done, call ${SUBMIT_TOOL} with your answer. Its arguments are the whole answer: write nothing after it.`

  const log = createWorkLog(sink)
  const emit = (event: AgentEvent) => {
    options.onEvent?.()
    log.feed(event)
  }

  const model = piModel(request.model, options.config)
  const prompt: AgentMessage = { role: 'user', content: request.prompt, timestamp: Date.now() }
  const messages = await runAgentLoop(
    [prompt],
    { messages: [{ role: 'system', content: system, timestamp: Date.now() }], tools },
    {
      model,
      reasoning: (request.effort || undefined) as ThinkingLevel | undefined,
      sessionId: request.key ?? undefined,
      cacheRetention: 'short',
      convertToLlm: messages => messages.filter((message): message is Message => ['system', 'user', 'assistant', 'toolResult'].includes(message.role)),
      getSteeringMessages: async () => {
        if (steered || (log.turn < STEER_TURN && log.charsRead <= READ_BUDGET_CHARS))
          return []
        steered = true
        const ask = schema === undefined ? 'Answer now with what you have.' : `Call ${SUBMIT_TOOL} now with what you have.`
        return [{ role: 'user', content: `You have read as much as this run allows. ${ask}`, timestamp: Date.now() }]
      },
      getFollowUpMessages: async () => {
        if (schema === undefined || submitted || nudged)
          return []
        nudged = true
        return [{ role: 'user', content: `Call ${SUBMIT_TOOL} now with your answer.`, timestamp: Date.now() }]
      },
      finishTurn: ({ message }) => {
        if (message.stopReason === 'error' || message.stopReason === 'aborted')
          return undefined
        if (submitted || log.turn >= MAX_TURNS)
          return { action: 'end' }
        return undefined
      },
    },
    emit,
    options.signal,
    options.streamFn ?? piStreamFn(options.config, model),
  )

  const usage = sumUsage(log.assistants, request.model, Date.now() - startedAt)
  const last = messages.findLast((message): message is AssistantMessage => message.role === 'assistant')
  if (options.signal.aborted || last?.stopReason === 'aborted')
    return { usage, error: 'Stopped.' }
  if (last?.stopReason === 'error')
    return { usage, error: endpointError(last.errorMessage) }
  if (schema !== undefined)
    return submitted ? { usage, text: JSON.stringify(answer) } : { usage, error: `The model did not call ${SUBMIT_TOOL}.` }
  const text = last ? textOf(last.content).trim() : ''
  return text ? { usage, text } : { usage, error: 'The model returned an empty answer.' }
}

/** The endpoint's error, with what to check when it refused the key. */
function endpointError(message: string | undefined): string {
  const said = message?.trim() || 'The model request failed.'
  return /\b40[13]\b|unauthorized|invalid api key|forbidden/i.test(said) ? `${said} ${KEY_HINT}` : said
}

/** The model id a run uses when the request names none. */
export async function defaultPiModel(config: PiConfig, catalog: () => Promise<CliModelCatalog>): Promise<string> {
  if (config.model)
    return config.model
  const models = (await catalog()).models
  return (models.find(model => model.isDefault) ?? models[0])!.id
}
