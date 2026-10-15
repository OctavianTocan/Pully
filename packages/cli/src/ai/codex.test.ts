import type { ActivityPatch, RunSink } from './types'
import { describe, expect, it } from 'vitest'
import { codexArgs, createCodexParser } from './codex'

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

const RUN = [
  { type: 'thread.started', thread_id: 't1' },
  { type: 'turn.started' },
  { type: 'item.completed', item: { id: 'item_0', type: 'reasoning', text: '**Planning tool discovery**' } },
  { type: 'item.started', item: { id: 'item_1', type: 'mcp_tool_call', server: 'pr', tool: 'read_source', arguments: { path: 'src/a.ts' }, result: null, error: null, status: 'in_progress' } },
  { type: 'item.completed', item: { id: 'item_1', type: 'mcp_tool_call', server: 'pr', tool: 'read_source', arguments: { path: 'src/a.ts' }, result: { content: [{ type: 'text', text: 'export const answer = 42' }], structured_content: null }, error: null, status: 'completed' } },
  { type: 'item.started', item: { id: 'item_2', type: 'command_execution', command: '/bin/bash -lc \'rg -n "answer" src\'', aggregated_output: '', exit_code: null, status: 'in_progress' } },
  { type: 'item.completed', item: { id: 'item_2', type: 'command_execution', command: '/bin/bash -lc \'rg -n "answer" src\'', aggregated_output: 'rg: src: No such file\n', exit_code: 2, status: 'failed' } },
  { type: 'item.completed', item: { id: 'item_3', type: 'agent_message', text: '{"value":42}' } },
  { type: 'turn.completed', usage: { input_tokens: 55790, cached_input_tokens: 18176, cache_write_input_tokens: 0, output_tokens: 165, reasoning_output_tokens: 52 } },
].map(event => JSON.stringify(event))

describe('codex stream parser', () => {
  it('turns a run into a readable work log and the usage Codex reported', () => {
    const { sink, log } = collect()
    const parser = createCodexParser(sink, { schema: true, model: 'gpt-6.1-sol' })
    RUN.forEach(parser.feed)

    expect([...log.keys()]).toEqual(['start', 'thinking:item_0', 'tool:item_1', 'tool:item_2', 'text:item_3'])
    expect(log.get('start')).toMatchObject({ title: 'Started Codex (gpt-6.1-sol)', status: 'done' })
    expect(log.get('thinking:item_0')).toMatchObject({ kind: 'thinking', title: 'Thought', text: 'Planning tool discovery', status: 'done' })
    expect(log.get('tool:item_1')).toMatchObject({ kind: 'tool', tool: 'read_source', title: 'Read src/a.ts', text: 'export const answer = 42', status: 'done' })
    expect(log.get('tool:item_2')).toMatchObject({ tool: 'rg', title: 'Searched for `answer` in src', status: 'failed', text: 'rg: src: No such file' })
    expect(log.get('text:item_3')).toMatchObject({ title: 'Wrote the answer', status: 'done' })
    expect(log.get('text:item_3')?.text).toBeUndefined()

    expect(parser.outcome()).toEqual({
      text: '{"value":42}',
      error: undefined,
      usage: { model: 'gpt-6.1-sol', inputTokens: 37614, cacheReadTokens: 18176, cacheWriteTokens: 0, outputTokens: 165, reasoningTokens: 52 },
    })
  })

  it('shows a prose answer as it is written', () => {
    const { sink, log } = collect()
    const parser = createCodexParser(sink)
    parser.feed(JSON.stringify({ type: 'item.completed', item: { id: 'item_9', type: 'agent_message', text: 'Looks good.' } }))
    expect(log.get('text:item_9')).toMatchObject({ title: 'Wrote', text: 'Looks good.' })
  })

  it('keeps the reason a turn failed', () => {
    const { sink } = collect()
    const parser = createCodexParser(sink)
    parser.feed(JSON.stringify({ type: 'error', message: 'stream disconnected before completion' }))
    parser.feed(JSON.stringify({ type: 'turn.failed', error: { message: 'unexpected status 401 Unauthorized' } }))
    expect(parser.outcome().error).toBe('unexpected status 401 Unauthorized')
  })
})

describe('codex invocation', () => {
  it('approves the PR tools up front, since nobody is there to approve them', () => {
    const args = codexArgs({ engine: 'codex', model: 'gpt-6.1-sol', effort: 'high', system: 'S', prompt: 'P' }, '/tmp/answer.txt', '/tmp/schema.json', { name: 'pr', command: '/usr/bin/node', args: ['server.mjs', 'pr.json'], tools: ['read_source'] })
    expect(args).toEqual(expect.arrayContaining([
      '--json',
      'model_reasoning_summary="auto"',
      'model_reasoning_effort="high"',
      'mcp_servers.pr.command="/usr/bin/node"',
      'mcp_servers.pr.args=["server.mjs","pr.json"]',
      'mcp_servers.pr.default_tools_approval_mode="approve"',
    ]))
    expect(args.slice(args.indexOf('--output-schema'), args.indexOf('--output-schema') + 2)).toEqual(['--output-schema', '/tmp/schema.json'])
    expect(args.at(-1)).toBe('-')
  })

  it('keeps its state databases apart from the user\'s own Codex sessions', () => {
    const args = codexArgs({ engine: 'codex', system: 'S', prompt: 'P' }, '/tmp/answer.txt')
    const home = args.findIndex(arg => arg.startsWith('sqlite_home='))
    expect(args[home - 1]).toBe('-c')
    expect(args[home]).toMatch(/^sqlite_home=".+pulls-review.codex"$/)
    expect(args.join(' ')).not.toContain('features.')
  })

  it('in a container, keeps its state in the container\'s home and runs no shell', () => {
    const args = codexArgs({ engine: 'codex', system: 'S', prompt: 'P' }, '/tmp/answer.txt', undefined, undefined, { stateDir: '/home/agent/.cache/pulls-review/codex', shell: false })
    expect(args).toEqual(expect.arrayContaining(['sqlite_home="/home/agent/.cache/pulls-review/codex"', 'features.shell_tool=false', 'features.unified_exec=false']))
    expect(args.at(-1)).toBe('-')
  })
})
