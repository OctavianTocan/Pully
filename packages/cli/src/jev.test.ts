import type { JevClientOptions } from './jev'
import { describe, expect, it } from 'vitest'
import { createJevClient, JEV_ENDPOINT, JevError, jevKeyFromEnv } from './jev'

const QUESTIONS = {
  kind: { type: 'choice', instructions: 'What kind?', criteria: { fix: 'A bug fix.', feature: 'Something new.' } },
  risk: { type: 'score', instructions: 'How risky?', criteria: ['low', 'high'] },
} as const

const ANSWER = {
  model: 'jev-1.13.0',
  answers: {
    kind: { type: 'choice', choice: 'fix', probabilities: { fix: 0.9, feature: 0.1 }, confidence: 0.9 },
    risk: { type: 'score', score: 0.3, legend: { 0: 'low', 1: 'high' }, probabilities: { 0: 0.7, 1: 0.3 }, confidence: 0.7 },
  },
  usage: { input_tokens: 42, output_tokens: 0 },
}

/** A TypeSafe stand-in answering each request with the next of `responses` (the last one repeats). */
function fakeTypesafe(responses: (() => Response)[], options: Partial<JevClientOptions> = {}) {
  const requests: { url: string, init: RequestInit }[] = []
  const sleeps: number[] = []
  const logs: string[] = []
  let clock = 0
  const fetch = (async (url: string, init: RequestInit) => {
    requests.push({ url, init })
    return responses[Math.min(requests.length - 1, responses.length - 1)]()
  }) as typeof globalThis.fetch
  const client = createJevClient({
    apiKey: 'sk-test-secret',
    fetch,
    log: line => logs.push(line),
    now: () => clock,
    sleep: async (ms) => {
      sleeps.push(ms)
      clock += ms
    },
    ...options,
  })
  return { client, requests, sleeps, logs }
}

const ok = () => Response.json(ANSWER)
const status = (code: number, headers: Record<string, string> = {}, body: unknown = { error: 'nope' }) => () => Response.json(body, { status: code, headers })

describe('jevKeyFromEnv', () => {
  it('prefers the pulls.review key and ignores blank values', () => {
    expect(jevKeyFromEnv({ PULLS_REVIEW_TYPESAFE_API_KEY: 'a', TYPESAFE_API_KEY: 'b' })).toBe('a')
    expect(jevKeyFromEnv({ PULLS_REVIEW_TYPESAFE_API_KEY: ' ', TYPESAFE_API_KEY: 'b' })).toBe('b')
    expect(jevKeyFromEnv({})).toBeUndefined()
  })
})

describe('createJevClient', () => {
  it('posts the state and questions to TypeSafe with the key', async () => {
    const { client, requests } = fakeTypesafe([ok])
    const answer = await client.ask({ title: 'Fix the crash' }, QUESTIONS)

    expect(answer).toEqual(ANSWER)
    expect(requests).toHaveLength(1)
    const [{ url, init }] = requests
    expect(url).toBe(JEV_ENDPOINT)
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Authorization': 'Bearer sk-test-secret', 'Content-Type': 'application/json' })
    expect(init.signal).toBeInstanceOf(AbortSignal)
    expect(JSON.parse(init.body as string)).toEqual({ state: { title: 'Fix the crash' }, model: 'jev-latest', questions: QUESTIONS })
  })

  it('is off without a key and asks nothing', async () => {
    const { client, requests } = fakeTypesafe([ok], { apiKey: undefined })
    expect(client.enabled()).toBe(false)
    expect(await client.ask('anything', QUESTIONS)).toBe('off')
    expect(requests).toHaveLength(0)
  })

  it('retries 429 and 529 with backoff, honouring retry-after', async () => {
    const { client, requests, sleeps } = fakeTypesafe([status(429, { 'retry-after': '3' }), status(529), ok])
    expect(await client.ask('state', QUESTIONS)).toEqual(ANSWER)
    expect(requests).toHaveLength(3)
    expect(sleeps).toEqual([3000, 2000])
  })

  it('gives up after three attempts', async () => {
    const { client, requests, sleeps } = fakeTypesafe([status(529)])
    await expect(client.ask('state', QUESTIONS)).rejects.toMatchObject({ name: 'JevError', status: 529 })
    expect(requests).toHaveLength(3)
    expect(sleeps).toEqual([1000, 2000])
  })

  it('turns off for the rest of the run when the key is refused, without logging it', async () => {
    const { client, requests, logs } = fakeTypesafe([status(401)])
    expect(await client.ask('state', QUESTIONS)).toBe('off')
    expect(client.enabled()).toBe(false)
    expect(await client.ask('state', QUESTIONS)).toBe('off')
    expect(requests).toHaveLength(1)
    expect(logs).toHaveLength(1)
    expect(logs.join('\n')).not.toContain('sk-test-secret')
  })

  it('does not retry a refused request and says which field was wrong', async () => {
    const { client, requests } = fakeTypesafe([status(422, {}, { error: 'questions.kind.criteria: too many options' })])
    const error = await client.ask('state', QUESTIONS).catch(error => error)
    expect(error).toBeInstanceOf(JevError)
    expect(error).toMatchObject({ status: 422, message: expect.stringContaining('questions.kind.criteria') })
    expect(requests).toHaveLength(1)
  })

  it('rejects an answer that misses a question', async () => {
    const { client } = fakeTypesafe([() => Response.json({ ...ANSWER, answers: { kind: ANSWER.answers.kind } })])
    await expect(client.ask('state', QUESTIONS)).rejects.toThrow('"risk"')
  })

  it('keeps at most eight requests in flight', async () => {
    let active = 0
    let peak = 0
    const release: (() => void)[] = []
    const fetch = (async (_url: string, _init: RequestInit) => {
      active++
      peak = Math.max(peak, active)
      await new Promise<void>(resolve => release.push(resolve))
      active--
      return Response.json(ANSWER)
    }) as typeof globalThis.fetch
    const { client } = fakeTypesafe([], { fetch })
    const all = Promise.all(Array.from({ length: 20 }, () => client.ask('state', QUESTIONS)))
    for (let round = 0; round < 40; round++) {
      await new Promise(resolve => setTimeout(resolve, 0))
      release.splice(0).forEach(resolve => resolve())
    }
    expect(await all).toHaveLength(20)
    expect(peak).toBe(8)
  })

  it('cuts off a request that takes too long', async () => {
    const fetch = ((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal!.addEventListener('abort', () => reject(init.signal!.reason))
    })) as typeof globalThis.fetch
    const { client } = fakeTypesafe([], { fetch, timeoutMs: 10 })
    await expect(client.ask('state', QUESTIONS)).rejects.toThrow('no answer within')
  })
})
