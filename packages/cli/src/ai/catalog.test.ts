import { describe, expect, it } from 'vitest'
import { CLAUDE_FALLBACK_EFFORT, claudeDefaultEffort, claudeModelName, claudeModels } from './catalog'

const LEVELS = ['low', 'medium', 'high', 'xhigh', 'max']

/** The `models` of Claude Code 2.1.300's `initialize` answer: aliases named by family, the version in the description. */
const ALIASES = [
  { value: 'default', resolvedModel: 'claude-sonnet-5-5', displayName: 'Default (recommended)', description: 'Sonnet 5.5 · Efficient for routine tasks', supportedEffortLevels: LEVELS },
  { value: 'sonnet', resolvedModel: 'claude-sonnet-5-5', displayName: 'Sonnet', description: 'Sonnet 5.5 · Efficient for routine tasks', supportedEffortLevels: LEVELS },
  { value: 'fable', resolvedModel: 'claude-fable-5-1', displayName: 'Fable', description: 'Fable 5.1 · Most capable for your hardest and longest-running tasks', supportedEffortLevels: LEVELS },
  { value: 'opus', resolvedModel: 'claude-opus-5-5', displayName: 'Opus', description: 'Opus 5.5 · Best for everyday, complex tasks', supportedEffortLevels: LEVELS },
  { value: 'haiku', resolvedModel: 'claude-haiku-5-5', displayName: 'Haiku', description: 'Haiku 5.5 · Fastest for quick answers', supportedEffortLevels: LEVELS },
  { value: 'claude-opus-4-1-20250805', displayName: 'Opus 4.1', description: 'Legacy model', supportedEffortLevels: ['low', 'medium', 'high'] },
  { value: 'claude-haiku-3-5', displayName: 'Haiku 3.5', description: 'Oldest' },
]

describe('claudeModels', () => {
  it('names each alias with its version and marks the effort Claude Code runs it at', () => {
    expect(claudeModels(ALIASES).map(({ id, name, description, defaultEffort }) => ({ id, name, description, defaultEffort }))).toEqual([
      { id: 'default', name: 'Default (recommended)', description: 'Sonnet 5.5 · Efficient for routine tasks', defaultEffort: 'medium' },
      { id: 'sonnet', name: 'Sonnet 5.5', description: 'Efficient for routine tasks', defaultEffort: 'medium' },
      { id: 'fable', name: 'Fable 5.1', description: 'Most capable for your hardest and longest-running tasks', defaultEffort: 'high' },
      { id: 'opus', name: 'Opus 5.5', description: 'Best for everyday, complex tasks', defaultEffort: 'medium' },
      { id: 'haiku', name: 'Haiku 5.5', description: 'Fastest for quick answers', defaultEffort: 'medium' },
      { id: 'claude-opus-4-1-20250805', name: 'Opus 4.1', description: 'Legacy model', defaultEffort: CLAUDE_FALLBACK_EFFORT },
      { id: 'claude-haiku-3-5', name: 'Haiku 3.5', description: 'Oldest', defaultEffort: undefined },
    ])
    expect(claudeModels(ALIASES)[0]).toMatchObject({ isDefault: true, efforts: LEVELS })
  })

  it('ignores what is not a model list', () => {
    expect(claudeModels(undefined)).toEqual([])
    expect(claudeModels([{ displayName: 'No value' }])).toEqual([])
  })
})

describe('claudeDefaultEffort', () => {
  it('reads the bundled table through dates and context sizes, and falls back to high', () => {
    expect(claudeDefaultEffort('claude-opus-5-5', LEVELS)).toBe('medium')
    expect(claudeDefaultEffort('claude-opus-5-5[1m]', LEVELS)).toBe('medium')
    expect(claudeDefaultEffort('claude-opus-4-7-20260101', LEVELS)).toBe('xhigh')
    expect(claudeDefaultEffort('claude-sonnet-4-6', LEVELS)).toBe('high')
  })

  it('names no default the model cannot take', () => {
    expect(claudeDefaultEffort('claude-opus-4-7', ['low', 'medium', 'high'])).toBeUndefined()
  })
})

describe('claudeModelName', () => {
  it('leaves a name that has its version, or a description that names another model', () => {
    expect(claudeModelName('Opus 5.5', 'For complex work')).toEqual({ name: 'Opus 5.5', description: 'For complex work' })
    expect(claudeModelName('Opus (1M context)', 'Opus 5.5 · For long sessions')).toEqual({ name: 'Opus (1M context)', description: 'Opus 5.5 · For long sessions' })
    expect(claudeModelName('Sonnet', 'Opus 5.5 · Best')).toEqual({ name: 'Sonnet', description: 'Opus 5.5 · Best' })
    expect(claudeModelName('Opus', undefined)).toEqual({ name: 'Opus', description: undefined })
  })
})
