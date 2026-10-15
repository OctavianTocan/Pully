import type { AiActivityStatus, AiJobRequest, AiUsage } from '@pulls.review/core/local-rpc'
import type { ActivityPatch, McpServerSpec, RunOutcome, RunSink, StreamParser } from './types'
import { homedir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { describeCommand, describeTool } from './titles'

const TEXT_LIMIT = 8_000
const OUTPUT_LIMIT = 400
// Codex prunes its SQLite logs before it reports anything, which took up to 30s on a busy shared ~/.codex.
// A home of our own stays small; only its very first run waits, once, to index past sessions.
const STATE_DIR = join(process.env.XDG_CACHE_HOME || join(homedir(), '.cache'), 'pulls-review', 'codex')

/**
 * The arguments for one `codex exec` run that streams its work as JSON lines. The prompt goes on stdin.
 * @param request - Model, effort and whether an answer schema applies.
 * @param answerPath - Where Codex writes its final message.
 * @param schemaPath - The strict answer schema on disk, when the answer is structured.
 * @param server - The PR tool server Codex may call, if any.
 * @returns The argument list for `codex`.
 */
export function codexArgs(request: AiJobRequest, answerPath: string, schemaPath?: string, server?: McpServerSpec): string[] {
  const args = [
    'exec',
    '--json',
    '--ephemeral',
    '--ignore-user-config',
    '--ignore-rules',
    '--skip-git-repo-check',
    '--sandbox',
    'read-only',
    '--color',
    'never',
    '--output-last-message',
    answerPath,
    '-c',
    'model_reasoning_summary="auto"',
    '-c',
    `sqlite_home=${JSON.stringify(STATE_DIR)}`,
  ]
  if (request.model)
    args.push('--model', request.model)
  if (request.effort)
    args.push('-c', `model_reasoning_effort="${request.effort}"`)
  if (schemaPath)
    args.push('--output-schema', schemaPath)
  if (server) {
    const key = `mcp_servers.${server.name}`
    // `codex exec` cannot ask anyone to approve a tool call, so without this it cancels every one.
    args.push(
      '-c',
      `${key}.command=${JSON.stringify(server.command)}`,
      '-c',
      `${key}.args=${JSON.stringify(server.args)}`,
      '-c',
      `${key}.default_tools_approval_mode="approve"`,
    )
  }
  args.push('-')
  return args
}

type Json = Record<string, any>

function parse(line: string): Json | undefined {
  try {
    const value = JSON.parse(line)
    return value && typeof value === 'object' ? value : undefined
  }
  catch {}
}

function clip(text: string, limit: number, fromEnd = false): string | undefined {
  const trimmed = text.trim()
  if (!trimmed)
    return undefined
  if (trimmed.length <= limit)
    return trimmed
  return fromEnd ? `…${trimmed.slice(-limit)}` : `${trimmed.slice(0, limit)}…`
}

function message(error: unknown): string | undefined {
  if (typeof error === 'string')
    return error
  if (error && typeof error === 'object' && typeof (error as Json).message === 'string')
    return (error as Json).message
}

function toolOutput(result: Json | undefined): string | undefined {
  const parts = Array.isArray(result?.content) ? result.content : []
  return clip(parts.map((part: Json) => typeof part?.text === 'string' ? part.text : '').join('\n'), OUTPUT_LIMIT)
}

function isJson(text: string): boolean {
  return parse(text) !== undefined
}

function progress(done: boolean): AiActivityStatus {
  return done ? 'done' : 'running'
}

function toolStatus(item: Json, done: boolean, failed: boolean): AiActivityStatus {
  if (item.status === 'in_progress' && !done)
    return 'running'
  return failed ? 'failed' : 'done'
}

function reasoningEntry(item: Json, done: boolean): ActivityPatch {
  const thought = String(item.text ?? '').replace(/\*\*/g, '')
  return { id: `thinking:${item.id}`, kind: 'thinking', title: done ? 'Thought' : 'Thinking', status: progress(done), text: clip(thought, TEXT_LIMIT, true) }
}

function messageEntry(item: Json, done: boolean, schema: boolean): ActivityPatch {
  const answer = String(item.text ?? '')
  if (schema && isJson(answer))
    return { id: `text:${item.id}`, kind: 'text', title: done ? 'Wrote the answer' : 'Writing the answer', status: progress(done) }
  return { id: `text:${item.id}`, kind: 'text', title: done ? 'Wrote' : 'Writing', status: progress(done), text: clip(answer, TEXT_LIMIT, true) }
}

function mcpEntry(item: Json, done: boolean): ActivityPatch {
  const title = describeTool(String(item.tool ?? 'tool'), item.arguments ?? {})
  const failed = item.status === 'failed' || !!item.error
  const status = toolStatus(item, done, failed)
  return {
    id: `tool:${item.id}`,
    kind: 'tool',
    tool: String(item.tool ?? 'tool'),
    title: status === 'running' ? title.running : title.done,
    doneTitle: title.done,
    status,
    text: failed ? message(item.error) : toolOutput(item.result),
  }
}

function commandEntry(item: Json, done: boolean): ActivityPatch {
  const command = describeCommand(String(item.command ?? ''))
  const failed = item.status === 'failed' || (typeof item.exit_code === 'number' && item.exit_code !== 0)
  const status = toolStatus(item, done, failed)
  return {
    id: `tool:${item.id}`,
    kind: 'tool',
    tool: command.program,
    title: status === 'running' ? command.running : command.done,
    doneTitle: command.done,
    status,
    text: clip(String(item.aggregated_output ?? ''), OUTPUT_LIMIT, true),
  }
}

function searchEntry(item: Json, done: boolean): ActivityPatch {
  const title = describeTool('web_search', { query: item.query })
  return { id: `tool:${item.id}`, kind: 'tool', tool: 'web_search', title: done ? title.done : title.running, doneTitle: title.done, status: progress(done) }
}

function errorEntry(item: Json): ActivityPatch {
  return { id: `status:${item.id}`, kind: 'status', title: message(item) ?? 'Codex reported an error', status: 'done' }
}

const ITEM_ENTRIES = new Map<string, (item: Json, done: boolean, schema: boolean) => ActivityPatch>([
  ['reasoning', reasoningEntry],
  ['agent_message', messageEntry],
  ['mcp_tool_call', mcpEntry],
  ['command_execution', commandEntry],
  ['web_search', searchEntry],
  ['error', errorEntry],
])

function addTurn(usage: AiUsage, reported: Json): void {
  const cached = reported.cached_input_tokens ?? 0
  // Codex counts cache hits inside input_tokens; split them so input means fresh tokens, as it does for Claude.
  usage.inputTokens = (usage.inputTokens ?? 0) + Math.max(0, (reported.input_tokens ?? 0) - cached)
  usage.cacheReadTokens = (usage.cacheReadTokens ?? 0) + cached
  usage.cacheWriteTokens = (usage.cacheWriteTokens ?? 0) + (reported.cache_write_input_tokens ?? 0)
  usage.outputTokens = (usage.outputTokens ?? 0) + (reported.output_tokens ?? 0)
  usage.reasoningTokens = (usage.reasoningTokens ?? 0) + (reported.reasoning_output_tokens ?? 0)
}

/**
 * Reads `codex exec --json` output.
 * @param sink - Receives work-log entries as they start, grow and finish.
 * @param options - Whether the final message is a structured answer, and the model asked for.
 * @param options.schema - True when the run has an answer schema.
 * @param options.model - The model the run was started with, for the usage report.
 * @returns The parser to feed each stdout line to.
 */
export function createCodexParser(sink: RunSink, options: { schema?: boolean, model?: string } = {}): StreamParser {
  const usage: AiUsage = { model: options.model }
  let error: string | undefined
  let text: string | undefined
  let turns = 0

  function onItem(item: Json, done: boolean): void {
    if (done && item.type === 'agent_message')
      text = String(item.text ?? '')
    const entry = ITEM_ENTRIES.get(item.type)
    if (entry)
      sink.put(entry({ ...item, id: String(item.id ?? '') }, done, !!options.schema))
  }

  function onError(event: Json): void {
    const detail = message(event) ?? 'Codex reported an error'
    sink.put({ id: `status:error:${turns}:${detail.length}`, kind: 'status', title: clip(detail, 300) ?? detail, status: 'done' })
    error = detail
  }

  const handlers = new Map<string, (event: Json) => void>([
    // No model id in the log (F58): it is the usage's model.
    ['thread.started', () => sink.put({ id: 'start', status: 'done', title: 'Started' })],
    ['item.started', event => onItem(event.item ?? {}, false)],
    ['item.updated', event => onItem(event.item ?? {}, false)],
    ['item.completed', event => onItem(event.item ?? {}, true)],
    ['turn.completed', (event) => {
      turns += 1
      addTurn(usage, event.usage ?? {})
    }],
    ['turn.failed', event => error = message(event.error) ?? 'Codex failed to finish the turn.'],
    ['error', onError],
  ])

  return {
    feed(line) {
      const event = parse(line)
      if (event)
        handlers.get(event.type)?.(event)
    },
    outcome: (): RunOutcome => ({ text, error: text ? undefined : error, usage: turns ? usage : undefined }),
  }
}
