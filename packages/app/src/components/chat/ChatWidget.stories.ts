import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { Meta, StoryObj } from '@storybook/vue3-vite'
import type { TrackedActivity } from '../ai/ai-activity'
import { fauxAssistantMessage, fauxText, fauxToolCall } from '@earendil-works/pi-ai'
import { createMockDiffsStore } from '../../stores/mock-diffs-store'
import ChatWidget from './ChatWidget.vue'

const meta: Meta<typeof ChatWidget> = {
  title: 'Chat/ChatWidget',
  component: ChatWidget,
  args: { open: true },
}
export default meta

type Story = StoryObj<typeof ChatWidget>

const question: AgentMessage = { role: 'user', content: 'Why does the session store change?', timestamp: 0 }

const conversation: AgentMessage[] = [
  question,
  fauxAssistantMessage([
    fauxText('Let me look at the store first.'),
    fauxToolCall('read_diffs', { paths: ['app/auth/session.ts', 'app/auth/store.ts'] }),
  ], { stopReason: 'toolUse' }),
  { role: 'toolResult', toolCallId: 'read', toolName: 'read_diffs', content: [{ type: 'text', text: '…' }], isError: false, timestamp: 0 },
  fauxAssistantMessage([
    fauxText('The session store now **persists tokens** across reloads:\n\n- `session.ts` reads from `localStorage` on init\n- `store.ts` writes on every change\n\n```ts\nconst token = localStorage.getItem(\'token\')\n```'),
  ]),
  { role: 'user', content: 'Put the auth files in their own group.', timestamp: 0 },
  fauxAssistantMessage(fauxToolCall('update_grouping', { groups: [] }), { stopReason: 'toolUse' }),
  { role: 'toolResult', toolCallId: 'group', toolName: 'update_grouping', content: [{ type: 'text', text: 'Grouping updated: 3 groups.' }], isError: false, timestamp: 0 },
  fauxAssistantMessage('Done: auth files now have their own group.'),
]

export const Collapsed: Story = {
  args: { open: false, store: createMockDiffsStore({ chatMessages: conversation }) },
}

export const Conversation: Story = {
  args: { store: createMockDiffsStore({ chatMessages: conversation }) },
}

export const Streaming: Story = {
  args: {
    store: createMockDiffsStore({
      chatStreaming: true,
      chatMessages: [question, fauxAssistantMessage('The session store now persists tok', { stopReason: 'pending' })],
    }),
  },
}

const now = Date.now()

/** A reply's work log as Claude Code reports it, `at` ms after the question. */
function step(id: string, at: number, input: Pick<TrackedActivity, 'kind' | 'title'> & Partial<TrackedActivity>): TrackedActivity {
  const status = input.status ?? 'done'
  const endedAt = status === 'running' ? undefined : now - 20_000 + at + 1500
  return { id, startedAt: now - 20_000 + at, endedAt, rev: 1, seenAt: endedAt ?? now - 2000, ...input, status }
}

const askWork: TrackedActivity[] = [
  step('start', 0, { kind: 'status', title: 'Started' }),
  step('read', 3000, { kind: 'tool', tool: 'read_file_diff', title: 'Read app/auth/store.ts (lines 1–120)' }),
  step('search', 7000, { kind: 'tool', tool: 'search_diff', title: 'Searched the diff for `localStorage`' }),
]

export const StreamingFromCli: Story = {
  args: {
    store: createMockDiffsStore({
      chatStreaming: true,
      chatStartedAt: now - 20_000,
      chatActivities: [...askWork, step('think', 11_000, { kind: 'thinking', title: 'Thinking', status: 'running', text: 'The store writes on every change.\nChecking whether the reads are guarded' })],
      chatMessages: [question, fauxAssistantMessage([], { stopReason: 'pending' })],
    }),
  },
}

export const AnsweredFromCli: Story = {
  args: {
    store: createMockDiffsStore({
      chatStartedAt: now - 20_000,
      chatEndedAt: now - 4000,
      chatActivities: [...askWork, step('answer', 12_000, { kind: 'text', title: 'Wrote', text: 'The session store now persists tokens across reloads.' })],
      chatMessages: [question, fauxAssistantMessage('The session store now **persists tokens** across reloads: `store.ts` writes on every change.')],
    }),
  },
}

export const Error: Story = {
  args: {
    store: createMockDiffsStore({
      chatError: new globalThis.Error('401 Unauthorized: invalid x-api-key'),
      chatMessages: [question, fauxAssistantMessage([], { stopReason: 'error', errorMessage: '401 Unauthorized: invalid x-api-key' })],
    }),
  },
}

export const Unavailable: Story = {
  args: { store: createMockDiffsStore({ chatAvailable: false }) },
}
