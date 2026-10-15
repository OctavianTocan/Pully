import type { LlmSession } from './schema'
import { describe, expect, it } from 'vitest'
import { archiveChat, CHAT_TITLE_CHARS, chatHistory, MAX_ARCHIVED_CHATS, restoreChat } from './chat-archive'

const analysis = [
  { role: 'user', content: 'Group this pull request', timestamp: 1 },
  { role: 'assistant', content: [{ type: 'text', text: 'Grouped.' }], timestamp: 2 },
] as LlmSession['messages']

function chat(question: string, at: number): LlmSession['messages'] {
  return [
    { role: 'system', content: '', timestamp: at },
    { role: 'user', content: [{ type: 'text', text: question }], timestamp: at },
    { role: 'assistant', content: [{ type: 'text', text: 'Because.' }], timestamp: at + 1 },
  ] as LlmSession['messages']
}

function session(...chats: LlmSession['messages'][]): LlmSession {
  return { messages: [...analysis, ...chats.flat()], chatStartIndex: analysis.length }
}

describe('archiveChat', () => {
  it('sets the chat after the analysis aside, keeping the analysis transcript as it was', () => {
    const next = archiveChat({ ...session(chat('Why the cache?', 10)), suggestions: ['And the TTL?'] }, 50)
    expect(next.messages).toEqual(analysis)
    expect(next.chatStartIndex).toBe(analysis.length)
    expect(next.suggestions).toBeUndefined()
    expect(next.archive).toEqual([{ id: expect.any(String), archivedAt: 50, messages: chat('Why the cache?', 10), suggestions: ['And the TTL?'] }])
  })

  it('drops a chat that asked nothing, and keeps the earlier ones', () => {
    const earlier = archiveChat(session(chat('First', 10)), 20)
    const next = archiveChat({ ...earlier, messages: [...earlier.messages, { role: 'system', content: '', timestamp: 30 } as never] }, 40)
    expect(next.messages).toEqual(analysis)
    expect(next.archive).toEqual(earlier.archive)
  })

  it(`keeps at most ${MAX_ARCHIVED_CHATS} chats, the least recent going first`, () => {
    let current = session()
    for (let at = 0; at < MAX_ARCHIVED_CHATS + 2; at++)
      current = archiveChat({ ...current, messages: [...current.messages, ...chat(`Question ${at}`, 100 + at * 10)] }, 1000 + at)
    expect(current.archive).toHaveLength(MAX_ARCHIVED_CHATS)
    expect(chatHistory(current).map(entry => entry.title).at(0)).toBe(`Question ${MAX_ARCHIVED_CHATS + 1}`)
    expect(chatHistory(current).map(entry => entry.title).at(-1)).toBe('Question 2')
  })
})

describe('chatHistory', () => {
  it('lists each chat by its first question on one line, with its last message time and its question count, most recent first', () => {
    const long = `${'word '.repeat(30)}\nsecond line`
    let current = archiveChat(session(chat(long, Date.UTC(2026, 9, 15, 12))), Date.UTC(2026, 9, 15, 13))
    current = archiveChat({ ...current, messages: [...current.messages, ...chat('  Why?  \nMore detail', Date.UTC(2026, 9, 15, 14)), ...chat('And then?', Date.UTC(2026, 9, 15, 15))] }, Date.UTC(2026, 9, 15, 16))
    const [latest, older] = chatHistory(current)
    expect(latest).toEqual({ id: expect.any(String), title: 'Why?', at: new Date(Date.UTC(2026, 9, 15, 15) + 1).toISOString(), turns: 2 })
    expect(older!.title.length).toBeLessThanOrEqual(CHAT_TITLE_CHARS)
    expect(older!.title).toMatch(/^word word .*…$/)
    expect(older!.turns).toBe(1)
  })

  it('is empty without a session or an archive', () => {
    expect(chatHistory(undefined)).toEqual([])
    expect(chatHistory(session(chat('Now', 1)))).toEqual([])
  })
})

describe('restoreChat', () => {
  it('puts the chosen chat back after the analysis and sets the current one aside', () => {
    const archived = archiveChat({ ...session(chat('Old question', 10)), suggestions: ['Old follow-up'] }, 20)
    const id = archived.archive![0]!.id
    const restored = restoreChat({ ...archived, messages: [...archived.messages, ...chat('New question', 30)] }, id, 40)
    expect(restored.messages).toEqual([...analysis, ...chat('Old question', 10)])
    expect(restored.chatStartIndex).toBe(analysis.length)
    expect(restored.suggestions).toEqual(['Old follow-up'])
    expect(chatHistory(restored).map(entry => entry.title)).toEqual(['New question'])
  })

  it('writes an empty archive when the restored chat was the only one, so the cache does not keep the stored list', () => {
    const archived = archiveChat(session(chat('Only', 10)), 20)
    const restored = restoreChat(archived, archived.archive![0]!.id)
    expect(restored.archive).toEqual([])
    expect(restored.messages).toEqual([...analysis, ...chat('Only', 10)])
  })

  it('throws on an id that is not in the history', () => {
    expect(() => restoreChat(session(chat('Now', 1)), 'missing')).toThrow('not in this pull request\'s history')
  })
})
