import type { ActivityPatch, RunSink } from './types'
import { describe, expect, it } from 'vitest'
import { claudeArgs, claudeInput, createClaudeParser } from './claude'

function collect(): { sink: RunSink, log: Map<string, ActivityPatch> } {
  const log = new Map<string, ActivityPatch>()
  const sink = {
    put(patch: ActivityPatch) {
      const defined = Object.fromEntries(Object.entries(patch).filter(([, value]) => value !== undefined))
      log.set(patch.id, { ...log.get(patch.id), ...defined } as ActivityPatch)
    },
  }
  return { sink, log }
}

const stream = (event: object) => JSON.stringify({ type: 'stream_event', event })

const RUN = [
  JSON.stringify({ type: 'control_response', response: { subtype: 'success', request_id: 'thinking' } }),
  JSON.stringify({ type: 'system', subtype: 'init', model: 'claude-opus-5-5', tools: ['mcp__pr__read_source'] }),
  stream({ type: 'message_start', message: { usage: { input_tokens: 10 } } }),
  stream({ type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } }),
  stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'The queue change ' } }),
  stream({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'needs its caller.' } }),
  stream({ type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'x' } }),
  JSON.stringify({ type: 'assistant', message: { content: [{ type: 'thinking', thinking: 'The queue change needs its caller.' }] } }),
  stream({ type: 'content_block_stop', index: 0 }),
  stream({ type: 'content_block_start', index: 1, content_block: { type: 'tool_use', id: 'toolu_1', name: 'mcp__pr__read_source', input: {} } }),
  stream({ type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '{"path":"src/a.ts",' } }),
  stream({ type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '"startLine":10,"endLine":20}' } }),
  JSON.stringify({ type: 'assistant', message: { content: [{ type: 'tool_use', id: 'toolu_1', name: 'mcp__pr__read_source', input: { path: 'src/a.ts', startLine: 10, endLine: 20 } }] } }),
  stream({ type: 'content_block_stop', index: 1 }),
  stream({ type: 'message_stop' }),
  JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_1', content: [{ type: 'text', text: '   10  export const answer = 42' }] }] } }),
  stream({ type: 'message_start', message: {} }),
  stream({ type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'toolu_2', name: 'StructuredOutput', input: {} } }),
  stream({ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"value":42}' } }),
  stream({ type: 'content_block_stop', index: 0 }),
  JSON.stringify({ type: 'user', message: { content: [{ type: 'tool_result', tool_use_id: 'toolu_2', content: 'Structured output provided successfully' }] } }),
  JSON.stringify({ type: 'rate_limit_event', rate_limit_info: { status: 'allowed' } }),
  JSON.stringify({
    type: 'result',
    subtype: 'success',
    is_error: false,
    result: '',
    structured_output: { value: 42 },
    total_cost_usd: 0.0123,
    duration_ms: 4200,
    usage: { input_tokens: 1, output_tokens: 2 },
    modelUsage: {
      'claude-opus-5-5': { inputTokens: 30, outputTokens: 50, cacheReadInputTokens: 1000, cacheCreationInputTokens: 200, thinkingTokens: 20, costUSD: 0.012 },
      'claude-haiku-4-5': { inputTokens: 5, outputTokens: 5, cacheReadInputTokens: 0, cacheCreationInputTokens: 0, costUSD: 0.0003 },
    },
  }),
]

describe('claude stream parser', () => {
  it('turns a run into a readable work log and the structured answer', () => {
    const { sink, log } = collect()
    const parser = createClaudeParser(sink)
    RUN.forEach(parser.feed)

    expect([...log.keys()]).toEqual(['start', 'thinking:1:0', 'tool:toolu_1', 'tool:toolu_2'])
    expect(log.get('start')).toMatchObject({ title: 'Started', status: 'done' })
    expect(log.get('thinking:1:0')).toMatchObject({ kind: 'thinking', status: 'done', text: 'The queue change needs its caller.', doneTitle: 'Thought' })
    expect(log.get('tool:toolu_1')).toMatchObject({ kind: 'tool', tool: 'read_source', status: 'done', title: 'Read src/a.ts (lines 10–20)', text: '10  export const answer = 42' })
    expect(log.get('tool:toolu_2')).toMatchObject({ title: 'Wrote the answer', status: 'done' })
    expect(log.get('tool:toolu_2')?.text).toBeUndefined()

    expect(parser.outcome()).toEqual({
      text: '{"value":42}',
      usage: { inputTokens: 35, outputTokens: 55, cacheReadTokens: 1000, cacheWriteTokens: 200, reasoningTokens: 20, costUsd: 0.0123, durationMs: 4200, model: 'claude-opus-5-5' },
    })
  })

  it('keeps the model out of the log and in the usage, even when the result names none', () => {
    const { sink, log } = collect()
    const parser = createClaudeParser(sink)
    parser.feed(RUN[1]!)
    parser.feed(JSON.stringify({ type: 'result', subtype: 'success', result: 'ok', usage: { input_tokens: 1, output_tokens: 2 } }))
    expect(log.get('start')?.title).toBe('Started')
    expect(parser.outcome().usage).toMatchObject({ model: 'claude-opus-5-5', inputTokens: 1, outputTokens: 2 })
  })

  it('names a tool by what it will do while its input is still streaming', () => {
    const { sink, log } = collect()
    const parser = createClaudeParser(sink)
    RUN.slice(0, 12).forEach(parser.feed)
    expect(log.get('tool:toolu_1')).toMatchObject({ title: 'Reading a file' })
    parser.feed(RUN[12]!)
    expect(log.get('tool:toolu_1')).toMatchObject({ title: 'Reading src/a.ts (lines 10–20)', doneTitle: 'Read src/a.ts (lines 10–20)' })
  })

  it('explains an assistant error code instead of a bare failure', () => {
    const { sink } = collect()
    const parser = createClaudeParser(sink)
    parser.feed(JSON.stringify({ type: 'assistant', error: 'rate_limit', message: { content: [{ type: 'text', text: 'API Error: 429' }] } }))
    parser.feed(JSON.stringify({ type: 'result', subtype: 'success', is_error: true, result: 'API Error: 429' }))
    expect(parser.outcome().error).toBe('Claude rate limit reached. Wait a moment, then retry.')
  })

  it('reports retries and rate-limit warnings as status lines', () => {
    const { sink, log } = collect()
    const parser = createClaudeParser(sink)
    parser.feed(JSON.stringify({ type: 'system', subtype: 'api_retry', attempt: 2, max_retries: 10, retry_delay_ms: 4000, error_status: 529 }))
    parser.feed(JSON.stringify({ type: 'rate_limit_event', rate_limit_info: { status: 'allowed_warning', utilization: 0.91, resetsAt: Date.now() / 1000 + 1800 } }))
    expect(log.get('retry:2')).toMatchObject({ kind: 'status', title: 'API error 529, retrying in 4s (attempt 2/10)' })
    expect(log.get('rate-limit')?.title).toMatch(/^Approaching the rate limit \(91% used\)\. Resets in (29|30)m\.$/)
  })
})

describe('claude invocation', () => {
  it('hands Claude the PR tools and nothing else', () => {
    const args = claudeArgs({ engine: 'claude-code', model: 'sonnet', effort: 'high', system: 'S', prompt: 'P' }, { name: 'pr', command: '/usr/bin/node', args: ['server.mjs', 'pr.json'], tools: ['read_source', 'find_files'] })
    expect(args).toContain('--include-partial-messages')
    expect(args.slice(args.indexOf('--tools'), args.indexOf('--tools') + 2)).toEqual(['--tools', ''])
    expect(args.slice(args.indexOf('--model'), args.indexOf('--model') + 2)).toEqual(['--model', 'sonnet'])
    expect(args.slice(args.indexOf('--effort'), args.indexOf('--effort') + 2)).toEqual(['--effort', 'high'])
    expect(JSON.parse(args[args.indexOf('--mcp-config') + 1]!)).toEqual({ mcpServers: { pr: { command: '/usr/bin/node', args: ['server.mjs', 'pr.json'] } } })
    expect(args[args.indexOf('--allowedTools') + 1]).toBe('mcp__pr__read_source,mcp__pr__find_files')
  })

  it('asks for readable thinking before sending the prompt', () => {
    const [control, user] = claudeInput('Review this').trim().split('\n').map(line => JSON.parse(line))
    expect(control.request).toEqual({ subtype: 'set_max_thinking_tokens', max_thinking_tokens: null, thinking_display: 'summarized' })
    expect(user).toEqual({ type: 'user', message: { role: 'user', content: 'Review this' } })
  })
})
