import type { AiJobRequest, AiUsage } from '@pulls.review/core/local-rpc'
import type { McpServerSpec, RunOutcome, RunSink, StreamParser } from './types'
import { bareToolName, describeTool } from './titles'

/** Thinking and answer text kept per entry; older text scrolls off the front. */
const TEXT_LIMIT = 8_000
const PREVIEW_LIMIT = 400

/** What Claude Code's assistant error codes mean to the person waiting. */
const ERRORS: Record<string, string> = {
  // The job adds how to sign in, which depends on where Claude runs.
  authentication_failed: 'Claude is not signed in on the server.',
  oauth_org_not_allowed: 'Claude signed in, but this organization does not allow Claude Code.',
  account_on_hold: 'The Claude account on the server is on hold.',
  billing_error: 'Claude billing or subscription access failed for the account on the server.',
  rate_limit: 'Claude rate limit reached. Wait a moment, then retry.',
  overloaded: 'Claude is temporarily overloaded. Retry in a moment.',
  invalid_request: 'Claude rejected the request as invalid.',
  model_not_found: 'The selected Claude model is not available to this account.',
  server_error: 'Claude returned a server error. Retry in a moment.',
  max_output_tokens: 'Claude reached its maximum output length before finishing.',
}

const BLOCK_TITLES = {
  thinking: { title: 'Thinking', doneTitle: 'Thought' },
  text: { title: 'Writing', doneTitle: 'Wrote' },
}

/**
 * The arguments for one Claude Code run that streams its work as JSON lines.
 * @param request - Model, effort, system prompt and optional answer schema.
 * @param server - The PR tool server Claude may call, if any.
 * @returns The argument list for `claude`.
 */
export function claudeArgs(request: AiJobRequest, server?: McpServerSpec): string[] {
  const args = [
    '-p',
    '--input-format',
    'stream-json',
    '--output-format',
    'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--system-prompt',
    request.system,
    '--tools',
    '',
    '--setting-sources',
    '',
    '--strict-mcp-config',
    '--disable-slash-commands',
    '--no-session-persistence',
  ]
  if (request.model)
    args.push('--model', request.model)
  if (request.effort)
    args.push('--effort', request.effort)
  if (request.schema !== undefined)
    args.push('--json-schema', JSON.stringify(request.schema))
  if (server) {
    args.push('--mcp-config', JSON.stringify({ mcpServers: { [server.name]: { command: server.command, args: server.args } } }))
    args.push('--allowedTools', server.tools.map(tool => `mcp__${server.name}__${tool}`).join(','))
  }
  return args
}

/**
 * What to write to Claude's stdin: a request for readable (summarized) thinking, then the prompt.
 * Without the first line Claude streams its thinking blocks empty.
 * @param prompt - The user prompt.
 * @returns JSON lines, newline-terminated.
 */
export function claudeInput(prompt: string): string {
  const lines = [
    { type: 'control_request', request_id: 'thinking', request: { subtype: 'set_max_thinking_tokens', max_thinking_tokens: null, thinking_display: 'summarized' } },
    { type: 'user', message: { role: 'user', content: prompt } },
  ]
  return `${lines.map(line => JSON.stringify(line)).join('\n')}\n`
}

type Json = Record<string, any>

function parse(line: string): Json | undefined {
  try {
    const value = JSON.parse(line)
    return value && typeof value === 'object' ? value : undefined
  }
  catch {}
}

function tail(text: string, limit = TEXT_LIMIT): string {
  return text.length > limit ? `…${text.slice(-limit)}` : text
}

function preview(content: unknown): string | undefined {
  const text = typeof content === 'string'
    ? content
    : Array.isArray(content) ? content.map(part => typeof part?.text === 'string' ? part.text : '').join('\n') : ''
  const trimmed = text.trim()
  return trimmed ? (trimmed.length > PREVIEW_LIMIT ? `${trimmed.slice(0, PREVIEW_LIMIT)}…` : trimmed) : undefined
}

function sum(values: Json[], key: string): number | undefined {
  const numbers = values.map(value => value[key]).filter((value): value is number => typeof value === 'number')
  return numbers.length ? numbers.reduce((total, value) => total + value, 0) : undefined
}

/**
 * The usage a `result` line reports. Per-model figures are preferred, since they cover every turn.
 * @param result - Claude's final `result` line.
 * @returns Token counts, cost, duration and the model that did most of the work.
 */
export function claudeUsage(result: Json): AiUsage {
  const perModel = Object.entries((result.modelUsage ?? {}) as Record<string, Json>)
  const models = perModel.map(([, value]) => value)
  const usage = (result.usage ?? {}) as Json
  const main = perModel.toSorted(([, a], [, b]) => (b.costUSD ?? 0) - (a.costUSD ?? 0))[0]?.[0]
  return {
    inputTokens: models.length ? sum(models, 'inputTokens') : usage.input_tokens,
    outputTokens: models.length ? sum(models, 'outputTokens') : usage.output_tokens,
    cacheReadTokens: models.length ? sum(models, 'cacheReadInputTokens') : usage.cache_read_input_tokens,
    cacheWriteTokens: models.length ? sum(models, 'cacheCreationInputTokens') : usage.cache_creation_input_tokens,
    reasoningTokens: (models.length ? sum(models, 'thinkingTokens') : undefined) ?? usage.output_tokens_details?.thinking_tokens,
    costUsd: typeof result.total_cost_usd === 'number' ? result.total_cost_usd : undefined,
    durationMs: typeof result.duration_ms === 'number' ? result.duration_ms : undefined,
    model: main,
  }
}

function minutes(resetsAt: unknown): string {
  if (typeof resetsAt !== 'number')
    return ''
  const seconds = Math.max(0, Math.round(resetsAt - Date.now() / 1000))
  return seconds < 120 ? ` Resets in ${seconds}s.` : ` Resets in ${Math.round(seconds / 60)}m.`
}

function retryTitle(event: Json): string {
  const wait = typeof event.retry_delay_ms === 'number' ? ` in ${Math.round(event.retry_delay_ms / 1000)}s` : ''
  const attempt = event.attempt ? ` (attempt ${event.attempt}${event.max_retries ? `/${event.max_retries}` : ''})` : ''
  const why = event.error_status ? `API error ${event.error_status}` : 'The request failed'
  return `${why}, retrying${wait}${attempt}`
}

function rateLimitTitle(info: Json): string | undefined {
  if (info.status === 'rejected')
    return `Rate limit reached.${minutes(info.resetsAt)}`
  if (info.status === 'allowed_warning' && typeof info.utilization === 'number')
    return `Approaching the rate limit (${Math.round(info.utilization * 100)}% used).${minutes(info.resetsAt)}`
}

function resultError(result: Json): string {
  const said = typeof result.result === 'string' ? result.result.trim() : ''
  const errors = Array.isArray(result.errors) ? result.errors.join('\n') : ''
  return said || errors || `Claude stopped: ${String(result.subtype ?? 'error').replace(/_/g, ' ')}.`
}

function blockKind(type: unknown): keyof typeof BLOCK_TITLES | undefined {
  if (type === 'thinking' || type === 'redacted_thinking')
    return 'thinking'
  if (type === 'text')
    return 'text'
}

interface Block {
  id: string
  kind: 'thinking' | 'text' | 'tool'
  text: string
  json: string
  toolId?: string
}

/**
 * Reads `claude -p --output-format stream-json --include-partial-messages` output.
 * @param sink - Receives work-log entries as they start, grow and finish.
 * @returns The parser to feed each stdout line to.
 */
export function createClaudeParser(sink: RunSink): StreamParser {
  let message = 0
  let outcome: RunOutcome = {}
  let assistantError: string | undefined
  const blocks = new Map<number, Block>()
  const calls = new Map<string, { name: string, input: Json }>()

  function callTool(id: string, name: string, input: Json): void {
    calls.set(id, { name, input })
    const title = describeTool(name, input)
    sink.put({ id: `tool:${id}`, kind: 'tool', tool: bareToolName(name), title: title.running, doneTitle: title.done })
  }

  function startBlock(index: number, block: Json): void {
    if (block.type === 'tool_use' && typeof block.id === 'string') {
      blocks.set(index, { id: `tool:${block.id}`, kind: 'tool', text: '', json: '', toolId: block.id })
      callTool(block.id, String(block.name ?? 'tool'), {})
      return
    }
    const kind = blockKind(block.type)
    if (!kind)
      return
    const id = `${kind}:${message}:${index}`
    blocks.set(index, { id, kind, text: '', json: '' })
    sink.put({ id, kind, ...BLOCK_TITLES[kind], status: 'running' })
  }

  function growBlock(index: number, delta: Json): void {
    const block = blocks.get(index)
    if (block && (delta.type === 'thinking_delta' || delta.type === 'text_delta')) {
      block.text += delta.thinking ?? delta.text ?? ''
      sink.put({ id: block.id, text: tail(block.text) })
    }
    else if (block && delta.type === 'input_json_delta') {
      block.json += delta.partial_json ?? ''
    }
  }

  function endBlock(index: number): void {
    const block = blocks.get(index)
    if (!block)
      return
    if (block.kind !== 'tool') {
      sink.put({ id: block.id, text: tail(block.text.trim()), status: 'done' })
      return
    }
    const input = block.json ? parse(block.json) : undefined
    if (input && block.toolId)
      callTool(block.toolId, calls.get(block.toolId)?.name ?? 'tool', input)
  }

  function onStream(event: Json): void {
    if (event.type === 'message_start') {
      message += 1
      blocks.clear()
    }
    else if (event.type === 'content_block_start') {
      startBlock(event.index, event.content_block ?? {})
    }
    else if (event.type === 'content_block_delta') {
      growBlock(event.index, event.delta ?? {})
    }
    else if (event.type === 'content_block_stop') {
      endBlock(event.index)
    }
  }

  function onSystem(event: Json): void {
    if (event.subtype === 'init')
      sink.put({ id: 'start', status: 'done', title: `Started Claude${event.model ? ` (${event.model})` : ''}` })
    else if (event.subtype === 'api_retry')
      sink.put({ id: `retry:${event.attempt ?? Date.now()}`, kind: 'status', title: retryTitle(event), status: 'done' })
  }

  function onAssistant(event: Json): void {
    for (const block of event.message?.content ?? []) {
      if (block?.type === 'tool_use' && typeof block.id === 'string')
        callTool(block.id, String(block.name ?? 'tool'), block.input ?? {})
    }
    if (typeof event.error === 'string')
      assistantError = ERRORS[event.error] ?? 'Claude failed to finish the turn.'
  }

  function onToolResults(event: Json): void {
    for (const block of event.message?.content ?? []) {
      if (block?.type !== 'tool_result' || typeof block.tool_use_id !== 'string')
        continue
      const call = calls.get(block.tool_use_id)
      const title = call ? describeTool(call.name, call.input) : undefined
      const output = call?.name === 'StructuredOutput' ? undefined : preview(block.content)
      sink.put({ id: `tool:${block.tool_use_id}`, status: block.is_error ? 'failed' : 'done', title: title?.done, text: output })
    }
  }

  function onRateLimit(event: Json): void {
    const title = rateLimitTitle(event.rate_limit_info ?? {})
    if (title)
      sink.put({ id: 'rate-limit', kind: 'status', title, status: 'done' })
  }

  function onResult(event: Json): void {
    const usage = claudeUsage(event)
    if (event.is_error || event.subtype !== 'success')
      outcome = { usage, error: assistantError ?? resultError(event) }
    else
      outcome = { usage, text: event.structured_output !== undefined ? JSON.stringify(event.structured_output) : String(event.result ?? '').trim() }
  }

  const handlers = new Map<string, (event: Json) => void>([
    ['system', onSystem],
    ['stream_event', event => onStream(event.event ?? {})],
    ['assistant', onAssistant],
    ['user', onToolResults],
    ['rate_limit_event', onRateLimit],
    ['result', onResult],
  ])

  return {
    feed(line) {
      const event = parse(line)
      if (event)
        handlers.get(event.type)?.(event)
    },
    outcome: () => outcome,
  }
}
