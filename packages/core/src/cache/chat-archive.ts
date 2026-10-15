import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { ArchivedChat, LlmSession } from './schema'

/*
 * A diff's chat history (L36): "new chat" sets the current chat aside instead of dropping it,
 * and an earlier chat can be put back. Only the messages after `chatStartIndex` move; the
 * analysis transcript before it is never touched.
 */

/** How many earlier chats a diff keeps; past it the least recent goes. */
export const MAX_ARCHIVED_CHATS = 30
/** A chat's title is its first question, cut to this many characters. */
export const CHAT_TITLE_CHARS = 80

/** One earlier chat as the phone lists it. */
export interface ChatSummary {
  id: string
  /** The chat's first question, on one line. */
  title: string
  /** ISO time of its last message, or of when it was set aside when no message has one. The list is ordered by when each was set aside. */
  at: string
  /** How many questions it asked. */
  turns: number
}

function textOf(content: unknown): string {
  if (typeof content === 'string')
    return content
  if (!Array.isArray(content))
    return ''
  return content.map(part => (part as { type?: string, text?: string })?.type === 'text' ? (part as { text?: string }).text ?? '' : '').join('\n')
}

function asks(messages: AgentMessage[]): AgentMessage[] {
  return messages.filter(message => message.role === 'user')
}

/** When the chat was last written: its last message's time, else when it was set aside. */
function lastWritten(chat: ArchivedChat): number {
  const stamped = chat.messages.findLast(message => typeof (message as { timestamp?: unknown }).timestamp === 'number')
  return (stamped as { timestamp?: number } | undefined)?.timestamp ?? chat.archivedAt
}

function chatTitle(messages: AgentMessage[]): string {
  const first = asks(messages).map(message => textOf((message as { content?: unknown }).content).split('\n').map(line => line.trim()).find(Boolean) ?? '').find(Boolean) ?? ''
  return first.length > CHAT_TITLE_CHARS ? `${first.slice(0, CHAT_TITLE_CHARS - 1).trimEnd()}…` : first
}

/**
 * Sets the current chat aside: the session keeps its analysis transcript and starts a new chat.
 * A chat with no question in it is dropped rather than kept. The follow-up suggestions go with it.
 * @param session - The diff's stored session.
 * @param now - When it is set aside.
 * @returns The session with nothing after `chatStartIndex`, and the chat first in `archive`.
 */
export function archiveChat(session: LlmSession, now = Date.now()): LlmSession {
  const { suggestions, ...rest } = session
  const chat = session.messages.slice(session.chatStartIndex)
  const kept = { ...rest, messages: session.messages.slice(0, session.chatStartIndex) }
  if (!asks(chat).length)
    return kept
  const archived: ArchivedChat = { id: globalThis.crypto.randomUUID(), archivedAt: now, messages: chat, ...(suggestions?.length ? { suggestions } : {}) }
  // Ordered and capped by when each was set aside, so a chat just reopened and put away again is the most recent one
  // even when its messages are old.
  const archive = [archived, ...(session.archive ?? [])].sort((a, b) => b.archivedAt - a.archivedAt).slice(0, MAX_ARCHIVED_CHATS)
  return { ...kept, archive }
}

/**
 * The diff's earlier chats, the one set aside last first.
 * @param session - The diff's stored session, if any.
 * @returns One summary per archived chat.
 */
export function chatHistory(session: LlmSession | undefined): ChatSummary[] {
  return [...session?.archive ?? []]
    .sort((a, b) => b.archivedAt - a.archivedAt)
    .map(chat => ({ id: chat.id, title: chatTitle(chat.messages), at: new Date(lastWritten(chat)).toISOString(), turns: asks(chat.messages).length }))
}

/**
 * Puts an earlier chat back as the current one, setting the current chat aside first when it asked anything.
 * @param session - The diff's stored session.
 * @param id - The archived chat's id.
 * @param now - When the current chat is set aside.
 * @returns The session with the chosen chat after `chatStartIndex`, and without it in `archive`.
 * @throws When no archived chat has that id.
 */
export function restoreChat(session: LlmSession, id: string, now = Date.now()): LlmSession {
  const chosen = session.archive?.find(chat => chat.id === id)
  if (!chosen)
    throw new Error('That chat is not in this pull request\'s history.')
  const current = archiveChat(session, now)
  return {
    ...current,
    messages: [...current.messages, ...chosen.messages],
    // An empty list is written as such: left out, the cache would keep the stored archive with the chosen chat in it.
    archive: (current.archive ?? []).filter(chat => chat.id !== id),
    ...(chosen.suggestions?.length ? { suggestions: chosen.suggestions } : {}),
  }
}
