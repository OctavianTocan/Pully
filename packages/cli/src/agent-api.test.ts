import type { ServerHost } from './host'
import memoryDriver from 'unstorage/drivers/memory'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSignInFailures } from './account'
import { POSTS_PER_HOUR, resetPostLimits } from './agent-api'
import { handleNativeRequest } from './native-api'
import { MAX_PATCH_BYTES } from './posted'
import { serverSession } from './server-session'

const PATCH = `diff --git a/src/retry.ts b/src/retry.ts
index e69de29..4b825dc 100644
--- a/src/retry.ts
+++ b/src/retry.ts
@@ -0,0 +1,3 @@
+export function retry() {
+  return 3
+}
diff --git a/README.md b/README.md
index 1111111..2222222 100644
--- a/README.md
+++ b/README.md
@@ -1 +1,2 @@
 # App
+Retries GitHub calls.
`

let host: ServerHost

function request(path: string, init: { method?: string, body?: string, headers?: Record<string, string> } = {}) {
  const headers = new Headers(init.headers)
  return handleNativeRequest({
    url: new URL(`http://localhost${path}`),
    method: init.method ?? 'GET',
    json: async () => JSON.parse(init.body ?? 'null'),
    body: init.body === undefined ? null : new Blob([init.body]).stream(),
    headers,
  }, host)
}

async function rpc(name: string, args: unknown, session?: string): Promise<{ status: number, body: any }> {
  const response = await request('/api/rpc', { method: 'POST', body: JSON.stringify({ name, args }), headers: session ? { 'X-Pully-Session': session } : {} })
  return { status: response!.status, body: await response!.json() }
}

async function signIn(): Promise<string> {
  const signed = await rpc('account-sign-in', { email: 'Tavi@Example.com', password: 'correct horse' })
  expect(signed.status).toBe(200)
  return signed.body.result.session
}

async function newKey(session: string, name = 'Claude Code'): Promise<string> {
  return (await rpc('agent-key-create', { name }, session)).body.result.key
}

async function post(key: string, body: unknown, method = 'POST', path = '/api/v1/diffs') {
  const response = await request(path, { method, body: typeof body === 'string' ? body : JSON.stringify(body), headers: { 'Authorization': `Bearer ${key}`, 'Content-Type': typeof body === 'string' ? 'text/x-diff' : 'application/json' } })
  return { status: response!.status, body: response!.status === 204 ? undefined : await response!.json() }
}

beforeEach(() => {
  host = { storage: memoryDriver() }
  vi.stubEnv('PULLY_ACCOUNT_EMAIL', 'tavi@example.com')
  vi.stubEnv('PULLY_ACCOUNT_PASSWORD', 'correct horse')
  vi.stubEnv('PULLY_PUBLIC_URL', 'https://prs.example.com')
  resetSignInFailures()
  resetPostLimits()
})

afterAll(() => {
  vi.unstubAllEnvs()
})

describe('the account', () => {
  it('signs in with the seeded email and password, and stays signed in until sign-out', async () => {
    expect((await rpc('account-sign-in', { email: 'tavi@example.com', password: 'wrong' })).status).toBe(401)
    const session = await signIn()
    expect((await rpc('account-me', {}, session)).body.result).toMatchObject({ email: 'tavi@example.com' })
    expect((await rpc('account-sign-out', {}, session)).body.result).toBe(true)
    expect((await rpc('account-me', {}, session)).status).toBe(401)
  })

  it('waits after five wrong passwords, even for the right one', async () => {
    for (let i = 0; i < 5; i++)
      await rpc('account-sign-in', { email: 'tavi@example.com', password: `wrong ${i}` })
    expect((await rpc('account-sign-in', { email: 'tavi@example.com', password: 'correct horse' })).status).toBe(429)
  })

  it('keeps only hashes of the password, the session and the keys in the store', async () => {
    const session = await signIn()
    const key = await newKey(session)
    const stored = JSON.stringify(await (await serverSession(host)).storage.getItems(await (await serverSession(host)).storage.getKeys()))
    expect(stored).not.toContain('correct horse')
    expect(stored).not.toContain(session)
    expect(stored).not.toContain(key)
  })

  it('refuses every account call without a session', async () => {
    for (const name of ['agent-keys', 'agent-key-create', 'account-settings', 'posted-list', 'posted-diff'])
      expect((await rpc(name, {})).status).toBe(401)
  })

  it('makes, lists and revokes keys; a revoked key is refused', async () => {
    const session = await signIn()
    const created = (await rpc('agent-key-create', { name: 'Codex CI' }, session)).body.result
    expect(created.key).toMatch(/^pully_[0-9A-Za-z]{32}$/)
    expect((await rpc('agent-keys', {}, session)).body.result).toEqual([expect.objectContaining({ id: created.item.id, name: 'Codex CI', hint: created.key.slice(0, 10) })])
    expect((await post(created.key, PATCH)).status).toBe(201)
    expect((await rpc('agent-key-revoke', { id: created.item.id }, session)).body.result).toBe(true)
    expect((await post(created.key, PATCH)).status).toBe(401)
  })

  it('merges settings so the newer value of each one wins', async () => {
    const session = await signIn()
    await rpc('account-settings-merge', { values: { theme: { value: 'dark', at: 10 }, sounds: { value: false, at: 10 } } }, session)
    const merged = await rpc('account-settings-merge', { values: { 'theme': { value: 'light', at: 5 }, 'sounds': { value: true, at: 20 }, 'bad name!': { value: 1, at: 1 } } }, session)
    expect(merged.body.result.values).toEqual({ theme: { value: 'dark', at: 10 }, sounds: { value: true, at: 20 } })
    expect((await rpc('account-settings', {}, session)).body.result.values.sounds.value).toBe(true)
  })
})

describe('the agent API', () => {
  it('refuses a call without a valid key', async () => {
    expect((await post('nope', PATCH)).status).toBe(401)
    expect((await request('/api/v1/diffs'))!.status).toBe(401)
  })

  it('takes a raw patch and answers a link on the public address', async () => {
    const key = await newKey(await signIn())
    const response = await request('/api/v1/diffs', { method: 'POST', body: PATCH, headers: { 'Authorization': `Bearer ${key}`, 'Content-Type': 'text/x-diff', 'X-Pully-Title': encodeURIComponent('Retry GitHub calls') } })
    expect(response!.status).toBe(201)
    const body = await response!.json()
    expect(body).toMatchObject({ version: 1, files: 2, warnings: [] })
    expect(body.url).toBe(`https://prs.example.com/-/${body.id}`)
    expect(body.id).toMatch(/^[0-9A-Z]{12}$/i)
  })

  it('opens a posted diff on the phone with no branches, and with the agent\'s groups, tour, brief and review', async () => {
    const session = await signIn()
    const key = await newKey(session)
    const posted = await post(key, {
      patch: PATCH,
      title: 'Retry GitHub calls',
      author: 'Claude Code',
      groups: { overallSummary: 'Adds a retry.', groups: [{ key: 'retry', label: 'Retry', category: 'core', filePaths: ['src/retry.ts', 'src/gone.ts'] }] },
      tour: { title: 'How retry works', stops: [{ title: 'The helper', files: ['src/retry.ts'] }] },
      brief: 'root = Page([])',
      review: { summary: 'Fine.', findings: [{ path: 'src/retry.ts', line: 2, title: 'Magic number', severity: 'nit' }, { path: 'elsewhere.ts', line: 1, title: 'Off the diff' }] },
    })
    expect(posted.status).toBe(201)
    expect(posted.body.warnings).toHaveLength(2)

    const opened = await rpc('posted-diff', { id: posted.body.id }, session)
    expect(opened.status).toBe(200)
    expect(opened.body.result.key).toBe(`posted:${posted.body.id}`)
    expect(opened.body.result.diff).toMatchObject({ ref: { kind: 'posted' }, title: 'Retry GitHub calls', author: { name: 'Claude Code' } })
    expect(opened.body.result.diff.head).toBeUndefined()
    expect(opened.body.result.groups.length).toBeGreaterThan(0)

    const state = (await rpc('review-state', { key: opened.body.result.key })).body.result
    expect(state.aiGroups).toEqual([expect.objectContaining({ key: 'retry', filePaths: ['src/retry.ts'] })])
    expect(state.analysis).toMatchObject({ overallSummary: 'Adds a retry.', sharedBy: 'Claude Code' })
    expect(state.tour).toMatchObject({ title: 'How retry works', engine: 'agent' })
    expect(state.critique.findings).toEqual([expect.objectContaining({ title: 'Magic number', id: 'agent-1' })])
  })

  it('lists posted diffs for the account, newest first', async () => {
    const session = await signIn()
    const key = await newKey(session, 'Laptop')
    const first = await post(key, { patch: PATCH, title: 'First' })
    const second = await post(key, { patch: PATCH, title: 'Second' })
    const list = (await rpc('posted-list', {}, session)).body.result
    expect(list.map((row: { id: string }) => row.id)).toEqual([second.body.id, first.body.id])
    expect(list[0]).toMatchObject({ title: 'Second', author: 'Laptop', files: 2, additions: 4, deletions: 0 })
    expect((await request('/api/v1/diffs', { headers: { Authorization: `Bearer ${key}` } }))!.status).toBe(200)
  })

  it('takes a new version with PUT, keeping the fields it leaves out', async () => {
    const key = await newKey(await signIn())
    const created = await post(key, { patch: PATCH, title: 'Retry', tour: { title: 'Tour', stops: [] } })
    const changed = PATCH.replace('return 3', 'return 4')
    const updated = await post(key, { patch: changed }, 'PUT', `/api/v1/diffs/${created.body.id}`)
    expect(updated).toMatchObject({ status: 200, body: { id: created.body.id, version: 2 } })
    const read = await request(`/api/v1/diffs/${created.body.id}`, { headers: { Authorization: `Bearer ${key}` } })
    expect(await read!.json()).toMatchObject({ title: 'Retry', version: 2, patch: changed, tour: { title: 'Tour' } })
    // The same patch again is not a new version.
    expect((await post(key, { patch: changed, title: 'Retry, renamed' }, 'PUT', `/api/v1/diffs/${created.body.id}`)).body.version).toBe(2)
  })

  it('refuses a patch with a secret in an added line, unless told it is not one', async () => {
    const key = await newKey(await signIn())
    const leaky = PATCH.replace('return 3', `return 'ghp_${'a'.repeat(36)}'`)
    const refused = await post(key, { patch: leaky })
    expect(refused.status).toBe(422)
    expect(refused.body.error).toContain('src/retry.ts line 2 looks like a GitHub token')
    expect(refused.body.error).not.toContain('ghp_')
    expect((await post(key, { patch: leaky, allowSecrets: true })).status).toBe(201)
  })

  it('refuses text that is not a diff, a patch too large, and a key past its posts for the hour', async () => {
    const key = await newKey(await signIn())
    expect((await post(key, 'hello')).status).toBe(422)
    expect((await post(key, { patch: `${PATCH}${'+x\n'.repeat(MAX_PATCH_BYTES / 3)}` })).status).toBe(413)
    resetPostLimits()
    for (let i = 0; i < POSTS_PER_HOUR; i++)
      await post(key, 'not a diff')
    expect((await post(key, PATCH)).status).toBe(429)
  })

  it('reaches only its own account\'s diffs, and deletes everything of one', async () => {
    const session = await signIn()
    const key = await newKey(session)
    const created = await post(key, PATCH)
    await rpc('posted-diff', { id: created.body.id }, session)
    expect((await post(key, '', 'DELETE', `/api/v1/diffs/${created.body.id}`)).status).toBe(204)
    expect((await rpc('posted-diff', { id: created.body.id }, session)).status).toBe(404)
    expect(await (await serverSession(host)).cache.diffs.get(`posted:${created.body.id}`)).toBeUndefined()
    const left = (await (await serverSession(host)).storage.getKeys()).filter(name => name.includes(created.body.id))
    expect(left).toEqual([])
  })
})
