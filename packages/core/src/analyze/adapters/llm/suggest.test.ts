import type { DiffsPayload } from '../../../types/diff'
import type { CliRunRequest } from './cli-stream'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setCliRunner } from './cli-stream'
import { buildSuggestionsPrompt, resolveSuggestions, suggestQuestions } from './suggest'

const diff: DiffsPayload = {
  ref: { kind: 'github-pr', owner: 'o', repo: 'r', number: '7' },
  title: 'Cache the parser',
  head: { sha: 'h1', ref: 'feat' },
  base: { sha: 'b1', ref: 'main' },
  files: [],
} as never

afterEach(() => setCliRunner(undefined))

describe('suggested questions', () => {
  it('asks in the reader language about the last question and answer', () => {
    const prompt = buildSuggestionsPrompt(diff, 'Why a map?', 'Lookups are constant time.', 'de')
    expect(prompt).toContain('o/r#7')
    expect(prompt).toContain('Why a map?')
    expect(prompt).toContain('Lookups are constant time.')
    expect(prompt).toContain('German')
  })

  it('keeps short questions, drops the one just asked, repeats and statements, and stops at three', () => {
    const questions = resolveSuggestions({
      questions: ['Why a map?', '  Where is the cache cleared? ', 'Where is the cache cleared?', 'Read the tests.', 'Is it thread safe?', 'What evicts entries?', 'And the fourth?'],
    }, 'why a MAP?')
    expect(questions).toEqual(['Where is the cache cleared?', 'Is it thread safe?', 'What evicts entries?'])
    expect(resolveSuggestions({ questions: 'no' }, 'q')).toEqual([])
    expect(resolveSuggestions(undefined, 'q')).toEqual([])
  })

  it('runs on the small model and never fails the answer', async () => {
    const runner = vi.fn(async (_request: CliRunRequest) => ({ text: '{"questions":["What evicts entries?"]}' }))
    setCliRunner(runner)
    expect(await suggestQuestions(diff, 'claude-code', 'Why a map?', 'Speed.', 'en')).toEqual(['What evicts entries?'])
    expect(runner.mock.calls[0]![0]).toMatchObject({ engine: 'claude-code', model: 'haiku', schema: expect.any(Object) })

    setCliRunner(async () => {
      throw new Error('rate limited')
    })
    expect(await suggestQuestions(diff, 'codex', 'Why a map?', 'Speed.', 'en')).toEqual([])
  })
})
