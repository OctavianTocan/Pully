import type { StreamFn } from '@earendil-works/pi-agent-core'
import type { ActivityPatch } from './types'
import { createModels, fauxAssistantMessage, fauxProvider, fauxThinking, fauxToolCall } from '@earendil-works/pi-ai'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { piCatalog, piConfig, piModelName, piModels, piTools, runPi, SUBMIT_TOOL } from './pi'

const faux = fauxProvider()
const models = createModels()
models.setProvider(faux.provider)
// The run asks for the endpoint's model; the faux provider answers for it.
const streamFn: StreamFn = (_model, context, options) => models.streamSimple(faux.getModel(), context, options)
const config = { baseUrl: 'http://127.0.0.1:8317/v1', apiKey: 'k' }

function toolUse(name: string, args: Parameters<typeof fauxToolCall>[1]) {
  return fauxAssistantMessage(fauxToolCall(name, args), { stopReason: 'toolUse' })
}

function sink() {
  const log = new Map<string, ActivityPatch>()
  return { log, put: (patch: ActivityPatch) => log.set(patch.id, { ...log.get(patch.id), ...patch }) }
}

const READ_HUNKS = { name: 'read_hunks', description: 'Hunks by id.', inputSchema: { type: 'object', properties: { hunkIds: { type: 'array', items: { type: 'string' } } }, required: ['hunkIds'] } }

beforeEach(() => {
  faux.setResponses([])
  faux.state.callCount = 0
})

describe('piConfig', () => {
  it('is off without a base URL and trims the trailing slash', () => {
    expect(piConfig({})).toBeUndefined()
    expect(piConfig({ PULLS_REVIEW_PI_BASE_URL: 'http://127.0.0.1:8317/v1/', PULLS_REVIEW_PI_MODEL: ' gpt-6 ' }))
      .toEqual({ baseUrl: 'http://127.0.0.1:8317/v1', apiKey: '', model: 'gpt-6' })
  })
})

describe('piModelName', () => {
  // Ids from CLIProxyAPI's own model list, and the names their vendors give them.
  it.each([
    ['claude-opus-5-5', 'Claude Opus 5.5'],
    ['claude-sonnet-4-5-20250929', 'Claude Sonnet 4.5'],
    ['claude-opus-4-20250514', 'Claude Opus 4'],
    ['claude-3-7-sonnet-20250219', 'Claude Sonnet 3.7'],
    ['claude-opus-4-6-thinking', 'Claude Opus 4.6 Thinking'],
    ['claude-opus-5-5-high', 'Claude Opus 5.5 (High)'],
    ['gpt-5.5', 'GPT-5.5'],
    ['gpt-6-luna', 'GPT-6 Luna'],
    ['gpt-5.5-codex', 'GPT-5.5 Codex'],
    ['gpt-5.1-codex-max', 'GPT-5.1 Codex Max'],
    ['gpt-5.5(high)', 'GPT-5.5 (High)'],
    ['gpt-oss-120b-medium', 'GPT-OSS 120B (Medium)'],
    ['o4-mini', 'o4-mini'],
    ['o3-mini-2025-01-31', 'o3-mini'],
    ['o3-mini-20250131-high', 'o3-mini (High)'],
    ['gemini-3-pro-preview', 'Gemini 3 Pro Preview'],
    ['gemini-3.8-flash-high', 'Gemini 3.8 Flash (High)'],
    ['models/gemini-2.5-flash-lite', 'Gemini 2.5 Flash Lite'],
    ['imagen-4.0-generate-001', 'Imagen 4.0 Generate'],
    ['grok-4.20-0309-reasoning', 'Grok 4.20 Reasoning'],
    ['grok-4.20-0309-non-reasoning', 'Grok 4.20 Non-Reasoning'],
    ['kimi-k3-256k', 'Kimi K3 256K'],
    ['kimi-k2.7-code-highspeed', 'Kimi K2.7 Code HighSpeed'],
    ['openrouter/deepseek-v3.1', 'DeepSeek V3.1'],
    ['glm-4.6', 'GLM 4.6'],
    ['gpt-4-2024-08-06', 'GPT-4'],
  ])('reads %s as %s', (id, name) => {
    expect(piModelName(id)).toBe(name)
  })
})

describe('piModels', () => {
  it('puts the default first, then each family newest first, offering the same efforts for all', () => {
    const body = { data: [{ id: 'gpt-5.5' }, { id: 'claude-opus-4-8' }, { id: 'gpt-6' }, { id: 'claude-opus-5-5' }, { id: 'gpt-6' }, { id: 'gemini-3-pro' }] }
    const listed = piModels(body, 'gemini-3-pro')
    expect(listed.map(model => model.id)).toEqual(['gemini-3-pro', 'claude-opus-5-5', 'claude-opus-4-8', 'gpt-6', 'gpt-5.5'])
    expect(listed[0]).toMatchObject({ isDefault: true, efforts: ['low', 'medium', 'high'] })
    expect(listed.filter(model => model.isDefault)).toHaveLength(1)
  })

  it('sorts by version, not by a size in the name', () => {
    const body = { data: [{ id: 'kimi-k2' }, { id: 'kimi-k3' }, { id: 'kimi-k2.8' }, { id: 'gpt-5.5' }, { id: 'gpt-oss-120b' }, { id: 'o3-mini-20250131' }, { id: 'o4-mini' }] }
    expect(piModels(body, 'gpt-5.5').map(model => model.id)).toEqual(['gpt-5.5', 'gpt-oss-120b', 'kimi-k3', 'kimi-k2.8', 'kimi-k2', 'o4-mini', 'o3-mini-20250131'])
  })

  it('defaults to the first listed model when the server names none it has', () => {
    expect(piModels({ data: [{ id: 'b' }, { id: 'a' }] }, 'missing')[0]).toMatchObject({ id: 'b', isDefault: true })
  })
})

describe('piCatalog', () => {
  it('says how to set Pi up when the server has no endpoint', async () => {
    await expect(piCatalog(undefined)).rejects.toThrow('PULLS_REVIEW_PI_BASE_URL')
  })

  it('lists the endpoint\'s models with its key', async () => {
    const fetcher = vi.fn<typeof fetch>(async () => Response.json({ object: 'list', data: [{ id: 'claude-opus-5-5', object: 'model' }] }))
    const catalog = await piCatalog(config, fetcher)
    expect(fetcher).toHaveBeenCalledWith('http://127.0.0.1:8317/v1/models', expect.objectContaining({ headers: { Authorization: 'Bearer k' } }))
    expect(catalog).toMatchObject({ engine: 'pi', account: '127.0.0.1:8317', models: [{ id: 'claude-opus-5-5', name: 'Claude Opus 5.5' }] })
  })

  it('points at the key when the endpoint refuses it', async () => {
    const fetcher: typeof fetch = async () => new Response('no', { status: 401 })
    await expect(piCatalog(config, fetcher)).rejects.toThrow('PULLS_REVIEW_PI_API_KEY')
  })
})

describe('runPi', () => {
  it('reads through its tools, then answers in text, logging each step', async () => {
    const call = vi.fn(async () => '@@ a.ts#0 @@\n+x')
    const out = sink()
    faux.setResponses([toolUse('read_hunks', { hunkIds: ['a.ts#0'] }), fauxAssistantMessage([fauxThinking('Looks fine.'), { type: 'text', text: 'It adds x.' }])])

    const outcome = await runPi({ engine: 'pi', model: 'm', system: 'S', prompt: 'What changed?' }, { tools: piTools([READ_HUNKS], call), sink: out, signal: new AbortController().signal, config, streamFn })

    expect(call).toHaveBeenCalledWith('read_hunks', { hunkIds: ['a.ts#0'] })
    expect(outcome).toMatchObject({ text: 'It adds x.', usage: { durationMs: expect.any(Number) } })
    const entries = [...out.log.values()]
    expect(entries).toContainEqual(expect.objectContaining({ kind: 'tool', tool: 'read_hunks', status: 'done', text: '@@ a.ts#0 @@\n+x' }))
    expect(entries).toContainEqual(expect.objectContaining({ kind: 'thinking', status: 'done', text: 'Looks fine.' }))
    expect(entries).toContainEqual(expect.objectContaining({ kind: 'text', status: 'done', text: 'It adds x.' }))
  })

  it('answers a schema through the submit tool, as JSON', async () => {
    const schema = { type: 'object', properties: { questions: { type: 'array', items: { type: 'string' } } }, required: ['questions'] }
    faux.setResponses([toolUse(SUBMIT_TOOL, { questions: ['Why?'] })])

    const outcome = await runPi({ engine: 'pi', model: 'm', system: 'S', prompt: 'P', schema }, { tools: [], sink: sink(), signal: new AbortController().signal, config, streamFn })

    expect(outcome.text).toBe('{"questions":["Why?"]}')
    expect(faux.state.callCount).toBe(1)
  })

  it('nudges once, then fails, when the model never submits', async () => {
    const schema = { type: 'object', properties: {} }
    faux.setResponses([fauxAssistantMessage('Here you go.'), fauxAssistantMessage('Still text.')])

    const outcome = await runPi({ engine: 'pi', model: 'm', system: 'S', prompt: 'P', schema }, { tools: [], sink: sink(), signal: new AbortController().signal, config, streamFn })

    expect(outcome.error).toContain(SUBMIT_TOOL)
    expect(faux.state.callCount).toBe(2)
  })

  it('reports a stop as stopped', async () => {
    const controller = new AbortController()
    controller.abort()
    faux.setResponses([fauxAssistantMessage('late')])

    const outcome = await runPi({ engine: 'pi', model: 'm', system: 'S', prompt: 'P' }, { tools: [], sink: sink(), signal: controller.signal, config, streamFn })

    expect(outcome.error).toBe('Stopped.')
  })
})
