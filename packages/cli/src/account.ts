import type { Storage } from 'unstorage'

/**
 * The server's accounts (plan 13). There is one, seeded from the environment
 * (`PULLY_ACCOUNT_EMAIL`, `PULLY_ACCOUNT_PASSWORD`), and no way to sign up. It signs in with
 * an email and a password, makes the API keys agents post diffs with, and keeps the phone's
 * settings. Everything here uses Web Crypto and the host's store only, so it runs on a
 * computer and on a hosted server alike.
 */

export interface AccountEnv {
  PULLY_ACCOUNT_EMAIL?: string
  PULLY_ACCOUNT_PASSWORD?: string
}

export interface Account {
  id: string
  email: string
}

interface PasswordHash {
  salt: string
  iterations: number
  hash: string
}

interface StoredAccount extends Account {
  password: PasswordHash
  createdAt: string
}

interface StoredSession {
  accountId: string
  createdAt: string
}

/** An agent key as the account sees it: never the key itself, which is shown once when it is made. */
export interface AgentKey {
  id: string
  name: string
  /** The key's first characters, so a list can tell keys apart. */
  hint: string
  createdAt: string
  lastUsedAt?: string
}

interface StoredKey extends AgentKey {
  accountId: string
}

/** One synced setting: its value and when it was changed, so the newer of two devices wins. */
export interface SyncedSetting {
  value: unknown
  at: number
}

const ACCOUNT = 'account:'
const ACCOUNT_EMAIL = 'account-email:'
const SESSION = 'account-session:'
const KEY = 'agent-key:'
const SETTINGS = 'account-settings:'

/** Web Crypto in Workers allows at most 100,000 PBKDF2 iterations, so the same hash checks on both hosts. */
const PBKDF2_ITERATIONS = 100_000
export const KEY_PREFIX = 'pully_'
export const MAX_KEYS = 50
export const MAX_KEY_NAME = 60
export const MAX_SETTINGS = 200
export const MAX_SETTING_BYTES = 8 * 1024

/** Wrong passwords allowed for one email before sign-in waits. */
export const SIGN_IN_TRIES = 5
export const SIGN_IN_WINDOW_MS = 15 * 60 * 1000

const BASE62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'

/** `length` characters of base62 from the system's random source, unbiased (bytes past 247 are thrown away). */
export function randomId(length: number): string {
  let out = ''
  while (out.length < length) {
    for (const byte of crypto.getRandomValues(new Uint8Array(length * 2))) {
      if (byte < 248 && out.length < length)
        out += BASE62[byte % 62]
    }
  }
  return out
}

function toBase64(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes)
    binary += String.fromCharCode(byte)
  return btoa(binary)
}

function fromBase64(text: string): Uint8Array {
  return Uint8Array.from(atob(text), char => char.charCodeAt(0))
}

/** SHA-256 of `text` in hex: how sessions and keys are stored, so the store never holds one that works. */
export async function sha256(text: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
  return [...digest].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const material = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits'])
  return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt as BufferSource, iterations }, material, 256))
}

export async function hashPassword(password: string): Promise<PasswordHash> {
  const salt = crypto.getRandomValues(new Uint8Array(16))
  return { salt: toBase64(salt), iterations: PBKDF2_ITERATIONS, hash: toBase64(await pbkdf2(password, salt, PBKDF2_ITERATIONS)) }
}

/** Compares in time that does not depend on where the two differ. */
function sameText(a: string, b: string): boolean {
  let diff = a.length ^ b.length
  for (let i = 0; i < Math.max(a.length, b.length); i++)
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0)
  return diff === 0
}

export async function checkPassword(password: string, stored: PasswordHash): Promise<boolean> {
  return sameText(toBase64(await pbkdf2(password, fromBase64(stored.salt), stored.iterations)), stored.hash)
}

function normalEmail(email: string): string {
  return email.trim().toLowerCase()
}

/** Every failed sign-in by email, for the wait after too many (process memory: a restart forgets them). */
const failures = new Map<string, number[]>()

export type SignIn = { session: string, account: Account } | { error: 'wrong' | 'wait' }

export interface Accounts {
  /** Creates the environment's account if it is missing, and takes a changed password. A no-op without both values. */
  seed: (env: AccountEnv) => Promise<Account | undefined>
  signIn: (email: string, password: string, now?: number) => Promise<SignIn>
  /** The account a session token belongs to; `undefined` for a missing, signed-out or unknown one. */
  bySession: (token: string | undefined) => Promise<Account | undefined>
  signOut: (token: string) => Promise<void>
  keys: (accountId: string) => Promise<AgentKey[]>
  /** A new key: the key itself (shown once) and how the list shows it. */
  createKey: (accountId: string, name: string) => Promise<{ key: string, item: AgentKey }>
  revokeKey: (accountId: string, id: string) => Promise<boolean>
  /** The account and key a `pully_…` key belongs to, noting when it was last used. */
  byKey: (key: string | undefined) => Promise<{ account: Account, key: AgentKey } | undefined>
  settings: (accountId: string) => Promise<Record<string, SyncedSetting>>
  /** Merges `values` in, each setting keeping the newer of the stored and the sent; answers the merged set. */
  mergeSettings: (accountId: string, values: Record<string, SyncedSetting>) => Promise<Record<string, SyncedSetting>>
}

function publicAccount(stored: StoredAccount): Account {
  return { id: stored.id, email: stored.email }
}

function publicKey(stored: StoredKey): AgentKey {
  const { accountId: _accountId, ...key } = stored
  return key
}

/**
 * The accounts kept in a host's store.
 * @param storage - The server's store.
 */
export function createAccounts(storage: Storage): Accounts {
  async function byEmail(email: string): Promise<StoredAccount | undefined> {
    const id = await storage.getItem<string>(`${ACCOUNT_EMAIL}${normalEmail(email)}`)
    return id ? await storage.getItem<StoredAccount>(`${ACCOUNT}${id}`) ?? undefined : undefined
  }

  async function storedKeys(accountId: string): Promise<{ hash: string, key: StoredKey }[]> {
    const names = await storage.getKeys(KEY)
    const items = await storage.getItems<StoredKey>(names)
    return items
      .filter(item => item.value?.accountId === accountId)
      .map(item => ({ hash: item.key.slice(item.key.lastIndexOf(':') + 1), key: item.value! }))
  }

  return {
    async seed(env) {
      const email = env.PULLY_ACCOUNT_EMAIL?.trim()
      const password = env.PULLY_ACCOUNT_PASSWORD
      if (!email || !password)
        return undefined
      const existing = await byEmail(email)
      if (existing) {
        if (!await checkPassword(password, existing.password))
          await storage.setItem(`${ACCOUNT}${existing.id}`, { ...existing, password: await hashPassword(password) })
        return publicAccount(existing)
      }
      const account: StoredAccount = { id: randomId(16), email, password: await hashPassword(password), createdAt: new Date().toISOString() }
      await storage.setItem(`${ACCOUNT}${account.id}`, account)
      await storage.setItem(`${ACCOUNT_EMAIL}${normalEmail(email)}`, account.id)
      return publicAccount(account)
    },

    async signIn(email, password, now = Date.now()) {
      const who = normalEmail(email)
      const recent = (failures.get(who) ?? []).filter(at => now - at < SIGN_IN_WINDOW_MS)
      failures.set(who, recent)
      if (recent.length >= SIGN_IN_TRIES)
        return { error: 'wait' }
      const account = await byEmail(who)
      // An unknown email is checked against a throwaway hash too, so it takes as long as a wrong password.
      const right = account ? await checkPassword(password, account.password) : (await hashPassword(password), false)
      if (!account || !right) {
        failures.set(who, [...recent, now])
        return { error: 'wrong' }
      }
      failures.delete(who)
      const session = randomId(43)
      await storage.setItem(`${SESSION}${await sha256(session)}`, { accountId: account.id, createdAt: new Date(now).toISOString() } satisfies StoredSession)
      return { session, account: publicAccount(account) }
    },

    async bySession(token) {
      if (!token)
        return undefined
      const session = await storage.getItem<StoredSession>(`${SESSION}${await sha256(token)}`)
      const account = session ? await storage.getItem<StoredAccount>(`${ACCOUNT}${session.accountId}`) : undefined
      return account ? publicAccount(account) : undefined
    },

    async signOut(token) {
      await storage.removeItem(`${SESSION}${await sha256(token)}`)
    },

    async keys(accountId) {
      return (await storedKeys(accountId)).map(({ key }) => publicKey(key)).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    },

    async createKey(accountId, name) {
      const label = name.trim().slice(0, MAX_KEY_NAME)
      if (!label)
        throw new Error('A key needs a name.')
      if ((await storedKeys(accountId)).length >= MAX_KEYS)
        throw new Error(`An account can hold ${MAX_KEYS} keys. Revoke one first.`)
      const key = `${KEY_PREFIX}${randomId(32)}`
      const stored: StoredKey = { id: randomId(12), accountId, name: label, hint: key.slice(0, KEY_PREFIX.length + 4), createdAt: new Date().toISOString() }
      await storage.setItem(`${KEY}${await sha256(key)}`, stored)
      return { key, item: publicKey(stored) }
    },

    async revokeKey(accountId, id) {
      const found = (await storedKeys(accountId)).find(({ key }) => key.id === id)
      if (!found)
        return false
      await storage.removeItem(`${KEY}${found.hash}`)
      return true
    },

    async byKey(key) {
      if (!key?.startsWith(KEY_PREFIX))
        return undefined
      const name = `${KEY}${await sha256(key)}`
      const stored = await storage.getItem<StoredKey>(name)
      const account = stored ? await storage.getItem<StoredAccount>(`${ACCOUNT}${stored.accountId}`) : undefined
      if (!stored || !account)
        return undefined
      // Noted at most once a minute, so a busy agent does not rewrite its key on every call.
      const now = new Date()
      if (!stored.lastUsedAt || now.getTime() - Date.parse(stored.lastUsedAt) > 60_000)
        await storage.setItem(name, { ...stored, lastUsedAt: now.toISOString() })
      return { account: publicAccount(account), key: publicKey(stored) }
    },

    async settings(accountId) {
      return (await storage.getItem<{ values: Record<string, SyncedSetting> }>(`${SETTINGS}${accountId}`))?.values ?? {}
    },

    async mergeSettings(accountId, values) {
      const stored = (await storage.getItem<{ values: Record<string, SyncedSetting> }>(`${SETTINGS}${accountId}`))?.values ?? {}
      const merged = { ...stored }
      for (const [name, setting] of Object.entries(values)) {
        if (!stored[name] || stored[name].at < setting.at)
          merged[name] = setting
      }
      if (Object.keys(merged).length > MAX_SETTINGS)
        throw new Error(`An account keeps at most ${MAX_SETTINGS} settings.`)
      await storage.setItem(`${SETTINGS}${accountId}`, { values: merged })
      return merged
    },
  }
}

/**
 * Settings as a client sent them, keeping only well-formed ones: a name of letters, digits, `.`, `-` and `_`, a time,
 * and a value that is JSON of at most `MAX_SETTING_BYTES`.
 */
export function cleanSettings(input: unknown): Record<string, SyncedSetting> | undefined {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    return undefined
  const out: Record<string, SyncedSetting> = {}
  for (const [name, raw] of Object.entries(input as Record<string, unknown>)) {
    const setting = raw as Partial<SyncedSetting> | undefined
    if (!/^[\w.-]{1,64}$/.test(name) || typeof setting?.at !== 'number' || !Number.isFinite(setting.at) || setting.value === undefined)
      continue
    if (JSON.stringify(setting.value).length > MAX_SETTING_BYTES)
      continue
    out[name] = { value: setting.value, at: setting.at }
  }
  return out
}

/** Forgets every failed sign-in; for tests. */
export function resetSignInFailures(): void {
  failures.clear()
}
