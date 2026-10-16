import type { ServerHost } from './host'
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from 'jose'
import memoryDriver from 'unstorage/drivers/memory'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { googleAllowed, useGoogleKeys } from './google-sign-in'
import { handleNativeRequest } from './native-api'
import { serverSession } from './server-session'

const CLIENT_ID = 'web-client.apps.googleusercontent.com'

let host: ServerHost
let google: CryptoKey
let stranger: CryptoKey

async function rpc(name: string, args: unknown, session?: string): Promise<{ status: number, body: any }> {
  const body = JSON.stringify({ name, args })
  const response = await handleNativeRequest({
    url: new URL('http://localhost/api/rpc'),
    method: 'POST',
    json: async () => JSON.parse(body),
    body: new Blob([body]).stream(),
    headers: new Headers(session ? { 'X-Pully-Session': session } : {}),
  }, host)
  return { status: response!.status, body: await response!.json() }
}

/** An ID token as Google would sign it, with `claims` laid over a valid one. */
function idToken(claims: Record<string, unknown> = {}, key = google, kid = 'google-1') {
  return new SignJWT({ email: 'tocanoctavian@gmail.com', email_verified: true, ...claims })
    .setProtectedHeader({ alg: 'RS256', kid })
    .setIssuer(typeof claims.iss === 'string' ? claims.iss : 'https://accounts.google.com')
    .setAudience(typeof claims.aud === 'string' ? claims.aud : CLIENT_ID)
    .setIssuedAt()
    .setExpirationTime(typeof claims.exp === 'number' ? claims.exp : '1h')
    .sign(key)
}

beforeAll(async () => {
  const pair = await generateKeyPair('RS256')
  google = pair.privateKey
  stranger = (await generateKeyPair('RS256')).privateKey
  useGoogleKeys(createLocalJWKSet({ keys: [{ ...await exportJWK(pair.publicKey), kid: 'google-1', alg: 'RS256' }] }))
})

beforeEach(() => {
  host = { storage: memoryDriver() }
  vi.stubEnv('PULLY_ACCOUNT_EMAIL', 'tavi@example.com')
  vi.stubEnv('PULLY_ACCOUNT_PASSWORD', 'correct horse')
  vi.stubEnv('PULLY_GOOGLE_CLIENT_ID', CLIENT_ID)
})

afterAll(() => {
  useGoogleKeys()
  vi.unstubAllEnvs()
})

describe('google sign-in', () => {
  it('names the client ID in the account status, and asks for sign-in even with no test account', async () => {
    expect((await rpc('account-status', {})).body.result).toEqual({ hasAccount: true, googleClientId: CLIENT_ID })
    vi.stubEnv('PULLY_ACCOUNT_EMAIL', '')
    expect((await rpc('account-status', {})).body.result).toEqual({ hasAccount: true, googleClientId: CLIENT_ID })
    host = { storage: memoryDriver() }
    vi.stubEnv('PULLY_GOOGLE_CLIENT_ID', '')
    expect((await rpc('account-status', {})).body.result).toEqual({ hasAccount: false })
  })

  it('makes the account on the first Google sign-in, and signs in to the same one after', async () => {
    const first = await rpc('account-google-sign-in', { idToken: await idToken({ email: 'TocanOctavian@gmail.com' }) })
    expect(first.status).toBe(200)
    expect(first.body.result.email).toBe('tocanoctavian@gmail.com')
    const session = first.body.result.session
    const me = (await rpc('account-me', {}, session)).body.result
    expect(me).toMatchObject({ email: 'tocanoctavian@gmail.com' })
    const again = await rpc('account-google-sign-in', { idToken: await idToken() })
    expect((await rpc('account-me', {}, again.body.result.session)).body.result.id).toBe(me.id)
    expect((await rpc('account-sign-out', {}, session)).body.result).toBe(true)
    expect((await rpc('account-me', {}, session)).status).toBe(401)
  })

  it('lets anyone at octaviantocan.com in, each with an account of their own', async () => {
    const a = await rpc('account-google-sign-in', { idToken: await idToken({ email: 'ana@octaviantocan.com' }) })
    const b = await rpc('account-google-sign-in', { idToken: await idToken({ email: 'tocanoctavian@gmail.com' }) })
    expect(a.status).toBe(200)
    const meA = (await rpc('account-me', {}, a.body.result.session)).body.result
    const meB = (await rpc('account-me', {}, b.body.result.session)).body.result
    expect(meA.id).not.toBe(meB.id)
  })

  it('makes no account for an email it does not allow', async () => {
    const answer = await rpc('account-google-sign-in', { idToken: await idToken({ email: 'someone@gmail.com' }) })
    expect(answer.status).toBe(403)
    expect(answer.body.result).toBeUndefined()
    expect((await (await serverSession(host)).storage.getKeys()).filter(key => key.startsWith('account-email:'))).toEqual(['account-email:tavi@example.com'])
  })

  it('never takes a password for an account Google made, and the test account keeps its password', async () => {
    await rpc('account-google-sign-in', { idToken: await idToken() })
    expect((await rpc('account-sign-in', { email: 'tocanoctavian@gmail.com', password: '' })).status).toBe(401)
    expect((await rpc('account-sign-in', { email: 'tocanoctavian@gmail.com', password: 'correct horse' })).status).toBe(401)
    expect((await rpc('account-sign-in', { email: 'tavi@example.com', password: 'correct horse' })).status).toBe(200)
  })

  it('refuses an unverified email, and a token for another app, issuer or key, or one expired', async () => {
    const refused = [
      await idToken({ email_verified: false }),
      await idToken({ aud: 'another-app.apps.googleusercontent.com' }),
      await idToken({ iss: 'https://evil.example.com' }),
      await idToken({ exp: Math.floor(Date.now() / 1000) - 60 }),
      await idToken({}, stranger),
      'not a token',
      '',
    ]
    for (const token of refused) {
      const answer = await rpc('account-google-sign-in', { idToken: token })
      expect(answer.status).toBe(401)
      expect(answer.body.result).toBeUndefined()
    }
  })

  it('is off without a client ID on the server', async () => {
    vi.stubEnv('PULLY_GOOGLE_CLIENT_ID', '')
    const answer = await rpc('account-google-sign-in', { idToken: await idToken() })
    expect(answer.status).toBe(401)
    expect(answer.body.error).toContain('PULLY_GOOGLE_CLIENT_ID')
  })

  it('allows Tavi\'s address and his domain, by the whole address or domain only', () => {
    expect(googleAllowed('tocanoctavian@gmail.com')).toBe(true)
    expect(googleAllowed(' Ana@OctavianTocan.com ')).toBe(true)
    expect(googleAllowed('@octaviantocan.com')).toBe(false)
    expect(googleAllowed('ana@evil-octaviantocan.com')).toBe(false)
    expect(googleAllowed('ana@octaviantocan.com.evil.com')).toBe(false)
    expect(googleAllowed('tocanoctavian+x@gmail.com')).toBe(false)
  })
})
