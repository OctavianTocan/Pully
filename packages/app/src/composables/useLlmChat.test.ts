import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { LlmSession } from '@pulls.review/core/cache'
import type { AiTaskSnapshot } from '@pulls.review/core/local-rpc'
import type { DiffsPayload } from '@pulls.review/core/types'
import type { LocalRpc } from '../local/connection'
import { fauxAssistantMessage } from '@earendil-works/pi-ai'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import { t } from '../i18n'
import { useLlmChat } from './useLlmChat'

const mocks = vi.hoisted(() => ({
  resolveAskModel: vi.fn(),
  reply: undefined as AgentMessage | undefined,
  createChatSession: vi.fn(),
  startAiTask: vi.fn(),
  followAiTask: vi.fn(),
  cancelAiTask: vi.fn(),
}))

vi.mock('@pulls.review/core/analyze', async importOriginal => ({
  ...await importOriginal<typeof import('@pulls.review/core/analyze')>(),
  resolveAskModel: mocks.resolveAskModel,
}))

vi.mock('@pulls.review/core/llm', () => ({ createChatSession: mocks.createChatSession }))
vi.mock('../local/ai-tasks', () => ({
  startAiTask: mocks.startAiTask,
  followAiTask: mocks.followAiTask,
  cancelAiTask: mocks.cancelAiTask,
}))

function fakeAgent({ messages }: { messages: AgentMessage[] }) {
  const state = { messages: [...messages], streamingMessage: undefined as AgentMessage | undefined }
  return {
    state,
    subscribe: () => () => {},
    abort: vi.fn(),
    async prompt(text: string) {
      state.messages.push({ role: 'user', content: text, timestamp: 0 }, mocks.reply!)
    },
    async continue() {
      state.messages.push(mocks.reply!)
    },
  }
}

const diff = { ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '1' }, title: 'PR', files: [] } as DiffsPayload
const transcript: AgentMessage[] = [{ role: 'user', content: 'analyze', timestamp: 0 }]

function setup() {
  const sessionRef = shallowRef<LlmSession>({ messages: transcript, chatStartIndex: transcript.length })
  const onSessionChange = vi.fn((next: LlmSession) => {
    sessionRef.value = next
  })
  const onGroupingUpdate = vi.fn()
  const chat = useLlmChat({ diff: ref(diff), session: sessionRef, onSessionChange, onGroupingUpdate })
  return { chat, sessionRef, onSessionChange, onGroupingUpdate }
}

beforeEach(() => {
  mocks.resolveAskModel.mockReset().mockReturnValue({ model: {}, apiKey: 'key' })
  mocks.createChatSession.mockReset().mockImplementation(fakeAgent)
  mocks.reply = fauxAssistantMessage('Because.')
  mocks.startAiTask.mockReset().mockResolvedValue('task')
  mocks.followAiTask.mockReset().mockResolvedValue({ status: 'done' })
  mocks.cancelAiTask.mockReset().mockResolvedValue(undefined)
})

describe('server chat startup', () => {
  function setupServer() {
    const rpc: LocalRpc = { call: vi.fn() } as any
    const reload = vi.fn(async () => {})
    const chat = useLlmChat({
      diff: ref(diff),
      session: shallowRef({ messages: transcript, chatStartIndex: transcript.length }),
      onSessionChange: vi.fn(),
      onGroupingUpdate: vi.fn(),
      server: { target: () => ({ rpc, key: 'github:o/r#1', model: { engine: 'codex' } }), reload },
    })
    return { chat, rpc, reload }
  }

  it('is busy before startup resolves and submits only the first question', async () => {
    const start = Promise.withResolvers<string>()
    const done = Promise.withResolvers<AiTaskSnapshot>()
    mocks.startAiTask.mockReturnValueOnce(start.promise)
    mocks.followAiTask.mockReturnValueOnce(done.promise)
    const { chat, reload } = setupServer()

    const first = chat.send('first question')
    expect(chat.isStreaming.value).toBe(true)
    await chat.send('second question')
    expect(mocks.startAiTask).toHaveBeenCalledOnce()

    start.resolve('task')
    await Promise.resolve()
    expect(chat.isStreaming.value).toBe(true)
    expect(chat.messages.value).toMatchObject([{ role: 'user', content: 'first question' }])
    done.resolve({ status: 'done' } as AiTaskSnapshot)
    await first
    expect(reload).toHaveBeenCalledOnce()
    expect(chat.isStreaming.value).toBe(false)
  })

  it('releases startup failures for another send', async () => {
    mocks.startAiTask.mockRejectedValueOnce(new Error('start failed'))
    const { chat } = setupServer()

    await chat.send('question')
    expect(chat.error.value?.message).toBe('start failed')
    expect(chat.isStreaming.value).toBe(false)
    await chat.send('try again')
    expect(mocks.startAiTask).toHaveBeenCalledTimes(2)
    expect(mocks.followAiTask).toHaveBeenCalledOnce()
    expect(chat.error.value).toBeUndefined()
  })

  it('does not follow or cancel a start that resolves after detaching', async () => {
    const start = Promise.withResolvers<string>()
    mocks.startAiTask.mockReturnValueOnce(start.promise)
    const { chat, reload } = setupServer()

    const sending = chat.send('question')
    chat.detach()
    start.resolve('detached-task')
    await sending

    expect(chat.isStreaming.value).toBe(false)
    expect(mocks.followAiTask).not.toHaveBeenCalled()
    expect(mocks.cancelAiTask).not.toHaveBeenCalled()
    expect(reload).not.toHaveBeenCalled()
  })

  it('cancels a start stopped before the task id arrives without following it', async () => {
    const start = Promise.withResolvers<string>()
    mocks.startAiTask.mockReturnValueOnce(start.promise)
    const { chat, rpc } = setupServer()

    const sending = chat.send('question')
    chat.stop()
    start.resolve('stopped-task')
    await sending

    expect(mocks.cancelAiTask).toHaveBeenCalledWith(rpc, 'stopped-task')
    expect(mocks.followAiTask).not.toHaveBeenCalled()
    expect(chat.isStreaming.value).toBe(false)
  })

  it('ignores an old startup failure while a new start is pending', async () => {
    const oldStart = Promise.withResolvers<string>()
    const newStart = Promise.withResolvers<string>()
    mocks.startAiTask.mockReturnValueOnce(oldStart.promise).mockReturnValueOnce(newStart.promise)
    const { chat } = setupServer()

    const oldSending = chat.send('old question')
    chat.detach()
    const newSending = chat.send('new question')
    oldStart.reject(new Error('detached start failed'))
    await oldSending
    expect(chat.isStreaming.value).toBe(true)
    expect(chat.error.value).toBeUndefined()

    newStart.resolve('new-task')
    await newSending
    expect(mocks.followAiTask).toHaveBeenCalledOnce()
    expect(mocks.followAiTask.mock.calls[0]![1]).toBe('new-task')
  })
})

describe('useLlmChat', () => {
  it('is unavailable without a session', () => {
    const chat = useLlmChat({ diff: ref(diff), session: ref(), onSessionChange: vi.fn(), onGroupingUpdate: vi.fn() })
    expect(chat.available.value).toBe(false)
  })

  it('persists the transcript with the same chatStartIndex after a send', async () => {
    const { chat, onSessionChange } = setup()

    await chat.send('why?')

    expect(onSessionChange).toHaveBeenCalledOnce()
    const next = onSessionChange.mock.calls[0]![0]
    expect(next.chatStartIndex).toBe(transcript.length)
    expect(next.messages).toHaveLength(3)
    expect(chat.messages.value.map(m => m.role)).toEqual(['user', 'assistant'])
    expect(chat.error.value).toBeUndefined()
    expect(chat.isStreaming.value).toBe(false)
  })

  it('ignores whitespace-only sends', async () => {
    const { chat, onSessionChange } = setup()

    await chat.send('   \n')

    expect(mocks.createChatSession).not.toHaveBeenCalled()
    expect(onSessionChange).not.toHaveBeenCalled()
  })

  it('sets error when no model is configured', async () => {
    mocks.resolveAskModel.mockReturnValue(undefined)
    const { chat } = setup()

    await chat.send('why?')

    expect(chat.error.value?.message).toBe(t('analyze.notConfigured'))
    expect(mocks.createChatSession).not.toHaveBeenCalled()
  })

  it('surfaces a provider error reply as error and retries from the user message', async () => {
    mocks.reply = fauxAssistantMessage([], { stopReason: 'error', errorMessage: '401 Unauthorized' })
    const { chat, sessionRef } = setup()

    await chat.send('why?')
    expect(chat.error.value?.message).toBe('401 Unauthorized')

    mocks.reply = fauxAssistantMessage('Because.')
    await chat.retry()

    expect(mocks.createChatSession.mock.calls[1]![0].messages.at(-1)).toMatchObject({ role: 'user', content: 'why?' })
    expect(chat.error.value).toBeUndefined()
    expect(sessionRef.value!.messages.at(-1)).toMatchObject({ role: 'assistant', stopReason: 'stop' })
  })

  it('drops the grouping update and persist of a run whose session was replaced mid-run', async () => {
    const { chat, sessionRef, onSessionChange, onGroupingUpdate } = setup()
    let shownMidRun: AgentMessage[] = []
    const replaced: LlmSession = { messages: [...transcript, { role: 'user', content: 'reanalyzed', timestamp: 1 }], chatStartIndex: 1 }
    mocks.createChatSession.mockImplementation((options: { messages: AgentMessage[], onGroupingUpdate: (result: unknown) => unknown }) => {
      const agent = fakeAgent(options)
      agent.prompt = async () => {
        sessionRef.value = replaced
        shownMidRun = chat.messages.value
        await options.onGroupingUpdate({ source: 'llm', groups: [] })
      }
      return agent
    })

    await chat.send('regroup')

    expect(onGroupingUpdate).not.toHaveBeenCalled()
    expect(onSessionChange).not.toHaveBeenCalled()
    expect(sessionRef.value).toBe(replaced)
    expect(shownMidRun).toEqual(replaced.messages.slice(1))
  })

  it('does not start a run stopped while the chat module is still loading', async () => {
    const { chat, onSessionChange } = setup()

    const sending = chat.send('why?')
    chat.stop()
    await sending

    expect(mocks.createChatSession).not.toHaveBeenCalled()
    expect(onSessionChange).not.toHaveBeenCalled()
    expect(chat.isStreaming.value).toBe(false)
  })

  it('collects the reply\'s work log by id while it runs', async () => {
    const { chat } = setup()
    let shownMidRun: unknown[] = []
    mocks.createChatSession.mockImplementation((options: { messages: AgentMessage[], onActivity: (activities: unknown[]) => void }) => {
      const agent = fakeAgent(options)
      agent.prompt = async () => {
        options.onActivity([{ id: 'a', kind: 'tool', title: 'Reading a.ts', status: 'running', startedAt: 0, rev: 1 }])
        options.onActivity([{ id: 'a', kind: 'tool', title: 'Read a.ts', status: 'done', startedAt: 0, rev: 2 }])
        shownMidRun = chat.activities.value
      }
      return agent
    })

    await chat.send('why?')

    expect(shownMidRun).toMatchObject([{ id: 'a', title: 'Read a.ts', status: 'done' }])
    expect(chat.startedAt.value).toBeTypeOf('number')
  })

  it('clear() truncates the session back to chatStartIndex', async () => {
    const { chat, sessionRef } = setup()
    await chat.send('why?')

    await chat.clear()

    expect(sessionRef.value).toEqual({ messages: transcript, chatStartIndex: transcript.length })
    expect(chat.messages.value).toEqual([])
  })
})
