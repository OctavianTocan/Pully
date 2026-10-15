import type { AgentMessage } from '@earendil-works/pi-agent-core'
import type { AiUsage } from '@pulls.review/core/local-rpc'
import type { GroupedResult } from '@pulls.review/core/types'
import type { Meta, StoryObj } from '@storybook/vue3-vite'
import type { TrackedActivity } from '../ai/ai-activity'
import { fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from '@earendil-works/pi-ai'
import nested from '../../../test/fixtures/synthetic/nested-groups.json'
import { createMockDiffsStore } from '../../stores/mock-diffs-store'
import AnalyzeStatusModal from './AnalyzeStatusModal.vue'

const meta: Meta<typeof AnalyzeStatusModal> = {
  title: 'Diff/AnalyzeStatusModal',
  component: AnalyzeStatusModal,
  args: { open: true },
}
export default meta

type Story = StoryObj<typeof AnalyzeStatusModal>

const now = Date.now()
const startedAt = now - 74_000

type StepInput = Pick<TrackedActivity, 'kind' | 'title'> & Partial<TrackedActivity>

/** A work-log row that started `at` ms into the run; finished rows took 2s. */
function step(id: string, at: number, input: StepInput): TrackedActivity {
  const status = input.status ?? 'done'
  const endedAt = status === 'running' ? undefined : startedAt + at + 2000
  return { id, startedAt: startedAt + at, endedAt, rev: 1, seenAt: endedAt ?? now - 4000, ...input, status }
}

const cliWork: TrackedActivity[] = [
  step('start', 0, { kind: 'status', title: 'Started' }),
  step('think-1', 2000, { kind: 'thinking', title: 'Thought', text: 'The PR touches the auth flow and the session store.\nI should read the session changes before grouping anything.' }),
  step('read-1', 6000, { kind: 'tool', tool: 'read_file_diff', title: 'Read app/auth/session.ts (lines 1–400)' }),
  step('discussion', 9000, { kind: 'tool', tool: 'read_discussion', title: 'Read the PR description and comments' }),
  step('search-1', 12_000, { kind: 'tool', tool: 'search_diff', title: 'Searched the diff for `localStorage`' }),
  step('rate-limit', 15_000, { kind: 'status', title: 'Rate limited, retrying in 20s (attempt 2)' }),
  step('shell-1', 38_000, { kind: 'tool', tool: 'rg', title: 'Searched for `useSession`' }),
  step('files', 42_000, { kind: 'tool', tool: 'find_files', title: 'Listing the repository\'s files', status: 'failed', text: 'GitHub API rate limit exceeded' }),
  step('read-2', 48_000, { kind: 'tool', tool: 'read_source', title: 'Read app/auth/store.ts (lines 1–120)' }),
]

const thinking = step('think-2', 52_000, {
  kind: 'thinking',
  title: 'Thinking',
  status: 'running',
  text: 'The store now writes tokens on every change.\nGrouping the auth changes with the session handling, tests on their own',
})

const claudeUsage: AiUsage = {
  model: 'claude-opus-5',
  inputTokens: 48_210,
  outputTokens: 3120,
  cacheReadTokens: 210_400,
  cacheWriteTokens: 12_800,
  reasoningTokens: 1850,
  costUsd: 0.4231,
  durationMs: 63_000,
}

const grouped = { ...nested.grouped, source: 'llm', model: 'claude-code/claude-opus-5' } as GroupedResult

export const Running: Story = {
  args: {
    store: createMockDiffsStore({
      isAnalyzing: true,
      llmEngine: 'claude-code',
      llmStartedAt: startedAt,
      llmActivities: [...cliWork, thinking],
    }),
  },
}

export const Starting: Story = {
  args: {
    store: createMockDiffsStore({
      isAnalyzing: true,
      llmEngine: 'codex',
      llmStartedAt: now - 3000,
      llmActivities: [{ ...step('start', 0, { kind: 'status', title: 'Starting Codex…', status: 'running' }), startedAt: now - 3000 }],
    }),
  },
}

export const Resumed: Story = {
  args: {
    store: createMockDiffsStore({
      isAnalyzing: true,
      llmEngine: 'claude-code',
      llmStartedAt: startedAt,
      llmResumed: true,
      llmActivities: cliWork.slice(0, 4),
    }),
  },
}

export const Finished: Story = {
  args: {
    store: createMockDiffsStore({
      grouped,
      llmEngine: 'claude-code',
      llmStartedAt: startedAt,
      llmEndedAt: startedAt + 63_000,
      llmActivities: [
        ...cliWork,
        { ...thinking, title: 'Thought', status: 'done', endedAt: startedAt + 58_000 },
        step('answer', 60_000, { kind: 'tool', tool: 'StructuredOutput', title: 'Wrote the answer' }),
      ],
      llmUsage: claudeUsage,
    }),
  },
}

/** Codex reports tokens but no cost, so none is shown. */
export const FinishedWithCodex: Story = {
  args: {
    store: createMockDiffsStore({
      grouped: { ...grouped, model: 'codex/gpt-5.6-sol' },
      llmEngine: 'codex',
      llmStartedAt: startedAt,
      llmEndedAt: startedAt + 41_000,
      llmActivities: [
        step('start', 0, { kind: 'status', title: 'Started' }),
        step('cat', 3000, { kind: 'tool', tool: 'cat', title: 'Read app/auth/session.ts' }),
        step('rg', 9000, { kind: 'tool', tool: 'rg', title: 'Searched for `persistToken`' }),
        step('git', 15_000, { kind: 'tool', tool: 'git', title: 'Ran `git log --oneline -5`' }),
        step('text', 30_000, { kind: 'text', title: 'Wrote', text: 'Two groups: the session change and its tests.' }),
      ],
      llmUsage: { model: 'gpt-5.6-sol', inputTokens: 31_800, outputTokens: 2240, cacheReadTokens: 12_000, reasoningTokens: 5400 },
    }),
  },
}

export const Failed: Story = {
  args: {
    store: createMockDiffsStore({
      llmEngine: 'claude-code',
      llmStartedAt: startedAt,
      llmEndedAt: startedAt + 50_000,
      llmActivities: cliWork,
      llmError: new Error('Claude Code exited with code 1: API Error: 529 {"type":"overloaded_error","message":"Overloaded"}'),
    }),
  },
}

export const Stopped: Story = {
  args: {
    store: createMockDiffsStore({
      llmEngine: 'claude-code',
      llmStartedAt: startedAt,
      llmEndedAt: startedAt + 30_000,
      llmActivities: cliWork.slice(0, 5),
      llmCancelled: true,
    }),
  },
}

const analysisTranscript: AgentMessage[] = [
  { role: 'system', content: 'You group pull request diffs.', timestamp: 0 },
  { role: 'user', content: 'Manifest: …', timestamp: 0 },
  fauxAssistantMessage([
    fauxThinking('Auth first, then the store.'),
    fauxText('Starting with the auth changes, then the store.'),
    fauxToolCall('read_diffs', { paths: ['app/auth/session.ts', 'app/auth/store.ts'] }, { id: 'read' }),
  ], { stopReason: 'toolUse' }),
  { role: 'toolResult', toolCallId: 'read', toolName: 'read_diffs', content: [{ type: 'text', text: '…' }], isError: false, timestamp: 0 },
  fauxAssistantMessage(fauxToolCall('submit_grouping', { groups: [] }, { id: 'submit' }), { stopReason: 'toolUse' }),
  { role: 'toolResult', toolCallId: 'submit', toolName: 'submit_grouping', content: [{ type: 'text', text: 'Missing paths: app/auth/store.ts\nFix these and call submit_grouping again.' }], isError: true, timestamp: 0 },
]

/** API-key providers have no server work log; it is read off the agent transcript. */
export const RunningWithApiKey: Story = {
  args: {
    store: createMockDiffsStore({
      isAnalyzing: true,
      llmStartedAt: now - 12_000,
      llmProgress: { step: 3, message: 'Organizing groups…' },
      llmTranscript: [...analysisTranscript, fauxAssistantMessage('Fixing the missing path and resub', { stopReason: 'pending' })],
    }),
  },
}

export const JustStarted: Story = {
  args: { store: createMockDiffsStore({ isAnalyzing: true, llmStartedAt: now }) },
}

export const FailedBeforeStart: Story = {
  args: { store: createMockDiffsStore({ llmError: new Error('401 Unauthorized: invalid x-api-key') }) },
}
