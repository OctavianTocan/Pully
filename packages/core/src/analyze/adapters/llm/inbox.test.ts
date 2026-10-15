import type { MyPull } from '../../../local-rpc'
import type { CliRunRequest } from './cli-stream'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setCliRunner } from './cli-stream'
import { ageOf, askAboutInbox, buildInboxPrompt, inboxLine, renderInbox } from './inbox'
import { resolveModel } from './model'
import { defaultLlmSettings } from './settings'
import { buildInboxSuggestionsPrompt } from './suggest'

const NOW = Date.parse('2026-10-15T12:00:00Z')

function pull(fields: Partial<MyPull>): MyPull {
  return { owner: 'o', repo: 'r', number: 1, title: 'A change', author: 'ana', isDraft: false, updatedAt: '2026-10-15T09:00:00Z', commentsCount: 0, labels: [], state: 'open', role: 'involved', ...fields }
}

afterEach(() => setCliRunner(undefined))

describe('ageOf', () => {
  it('says minutes, hours and days the way a person does', () => {
    expect(ageOf('2026-10-15T11:59:50Z', NOW)).toBe('just now')
    expect(ageOf('2026-10-15T11:55:00Z', NOW)).toBe('5m ago')
    expect(ageOf('2026-10-15T09:00:00Z', NOW)).toBe('3h ago')
    expect(ageOf('2026-10-12T12:00:00Z', NOW)).toBe('3d ago')
    expect(ageOf('not a date', NOW)).toBeUndefined()
  })
})

describe('inboxLine', () => {
  it('names the pull request as owner/repo#number with what the inbox knows about it', () => {
    const line = inboxLine(pull({
      number: 241,
      title: 'Fix   the cache',
      requestedFromMe: true,
      checks: 'failure',
      reviewDecision: 'changes-requested',
      mergeable: 'conflicting',
      additions: 12,
      deletions: 3,
      changedFiles: 1,
      commentsCount: 2,
      labels: ['bug'],
    }), NOW)
    expect(line).toBe('- o/r#241 "Fix the cache" · by ana · your review is requested · checks failing · changes requested · has conflicts · +12 −3 in 1 file · 2 comments · labels bug · updated 3h ago')
  })

  it('says a closed pull request was merged or closed, and when', () => {
    expect(inboxLine(pull({ state: 'closed', merged: true, role: 'authored' }), NOW)).toBe('- o/r#1 "A change" · by ana · merged 3h ago · you wrote it')
    expect(inboxLine(pull({ state: 'closed', isDraft: true }), NOW)).toBe('- o/r#1 "A change" · by ana · closed 3h ago · you are involved · draft')
  })
})

describe('renderInbox', () => {
  it('groups open pull requests by repository, newest first, then lists the closed ones', () => {
    const text = renderInbox({
      open: [
        pull({ repo: 'web', number: 2, updatedAt: '2026-10-14T09:00:00Z' }),
        pull({ repo: 'api', number: 7 }),
        pull({ repo: 'web', number: 3, updatedAt: '2026-10-15T10:00:00Z' }),
      ],
      closed: [pull({ repo: 'web', number: 1, state: 'closed', merged: true })],
    }, NOW)
    expect(text.split('\n').filter(line => line.startsWith('#') || line.startsWith('- ')).map(line => line.split(' "')[0])).toEqual([
      '## Open (3)',
      '### o/api',
      '- o/api#7',
      '### o/web',
      '- o/web#3',
      '- o/web#2',
      '## Closed lately',
      '- o/web#1',
    ])
  })

  it('says so when nothing is open', () => {
    expect(renderInbox({ open: [], closed: [] }, NOW)).toBe('## Open (0)\n\nNone.')
  })
})

describe('buildInboxPrompt', () => {
  it('dates the list, keeps the last turns and ends with the question and its language', () => {
    const history = Array.from({ length: 6 }, (_, index) => ({ question: `Q${index}`, answer: `A${index}` }))
    const prompt = buildInboxPrompt({ open: [pull({})], closed: [] }, { question: ' What is failing? ', history }, NOW, 'de')
    expect(prompt.startsWith('# The reader\'s pull requests, as of 2026-10-15 12:00 UTC')).toBe(true)
    expect(prompt).not.toContain('Earlier question: Q1')
    expect(prompt).toContain('Earlier question: Q2\nYour answer: A2')
    expect(prompt).toContain('## Question\n\nWhat is failing?')
    expect(prompt.trim().endsWith('(in German (Deutsch) when you cannot tell).')).toBe(true)
  })
})

describe('askAboutInbox', () => {
  it('answers through the CLI with only the listed pull requests open to its tools', async () => {
    const runner = vi.fn(async (_request: CliRunRequest) => ({ text: ' o/r#5 is failing. \n' }))
    setCliRunner(runner)
    const resolved = resolveModel({ ...defaultLlmSettings, provider: 'claude-code', claudeCodeModel: 'sonnet' })!
    const result = await askAboutInbox({ open: [pull({ number: 5 })], closed: [pull({ number: 4, state: 'closed' })] }, resolved, 'en', { question: 'What is failing?', history: [] }, { now: NOW })
    expect(result.text).toBe('o/r#5 is failing.')
    expect(runner).toHaveBeenCalledWith(expect.objectContaining({
      engine: 'claude-code',
      label: 'Ask about your pull requests',
      inbox: { pulls: [{ owner: 'o', repo: 'r', number: 5 }, { owner: 'o', repo: 'r', number: 4 }] },
    }), expect.anything())
    expect(runner.mock.calls[0]![0]).not.toHaveProperty('context')
  })

  it('needs a CLI engine', async () => {
    const resolved = resolveModel({ ...defaultLlmSettings, provider: 'gateway', gatewayToken: 't' })!
    await expect(askAboutInbox({ open: [], closed: [] }, resolved, 'en', { question: 'Hi?', history: [] })).rejects.toThrow('Claude Code or Codex')
  })
})

describe('inbox suggestions', () => {
  it('ask about the last question and answer in the reader language', () => {
    const prompt = buildInboxSuggestionsPrompt('What waits on me?', 'o/r#5.', 'fr')
    expect(prompt).toContain('## The question\n\nWhat waits on me?')
    expect(prompt).toContain('## The answer\n\no/r#5.')
    expect(prompt).toContain('in French')
  })
})
