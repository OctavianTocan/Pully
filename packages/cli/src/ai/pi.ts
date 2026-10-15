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

/**
 * A readable name for a model id: `claude-opus-5-5` reads "Claude Opus 5.5", `gpt-5.5-codex` "GPT-5.5 Codex",
 * `gemini-3-pro-preview` "Gemini 3 Pro Preview". A dated snapshot suffix and a provider prefix are dropped.
 * @param id - The id the endpoint lists.
 * @returns The name to show.
 */
export function piModelName(id: string): string {
  const base = id.slice(id.lastIndexOf('/') + 1).replace(/-\d{8}$|-\d{4}-\d{2}-\d{2}$/, '')
  const words = base.split(/[-_\s]+/).filter(Boolean)
  const out: string[] = []
  for (let index = 0; index < words.length; index++) {
    const word = words[index]!
    const next = words[index + 1]
    // Claude writes its version as two words: `opus-5-5` is Opus 5.5.
    if (/^\d{1,2}$/.test(word) && next && /^\d{1,2}$/.test(next)) {
      out.push(`${word}.${next}`)
      index += 1
      continue
    }
    out.push(word.toLowerCase() === 'gpt' ? 'GPT' : word.charAt(0).toUpperCase() + word.slice(1))
  }
  // "GPT" keeps the hyphen it is written with before its number.
  return out.join(' ').replace(/^GPT (?=\d)/, 'GPT-')
}

function version(name: string): number {
  const found = /\b(\d+)(?:\.(\d+))?\b/.exec(name)
  return found ? Number(found[1]) * 1000 + Number(found[2] ?? 0) : -1
}

/**
 * The models of an OpenAI-compatible `/models` answer: grouped by family (the name's first word), the newest version of
 * each first, the default on top.
 * @param body - The parsed answer.
 * @param preferred - The model the server runs by default, when it names one.
 * @returns The catalog's models.
 */
export function piModels(body: unknown, preferred?: string): CliModel[] {
  const data = (body as { data?: unknown })?.data
  const ids = [...new Set((Array.isArray(data) ? data : [])
    .map(entry => (entry as { id?: unknown })?.id)
    .filter((id): id is string => typeof id === 'string' && id.trim() !== ''))]
  const fallback = preferred && ids.includes(preferred) ? preferred : ids[0]
  const named = ids.map(id => ({ id, name: piModelName(id) }))
  const family = (name: string) => /^[a-z]+/i.exec(name)?.[0].toLowerCase() ?? ''
  named.sort((a, b) => Number(b.id === fallback) - Number(a.id === fallback)
    || family(a.name).localeCompare(family(b.name))
    || version(b.name) - version(a.name)
    || a.name.localeCompare(b.name))
  return named.map(({ id, name }) => ({ id, name, efforts: [...PI_EFFORTS], isDefault: id === fallback || undefined }))
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
    maxTokens: 32_000,
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
