import type { DataBucket } from './data'
import { describe, expect, it } from 'vitest'
import { answerDataMirror, dataPath } from './data'

function bucket(): DataBucket & { objects: Map<string, string> } {
  const objects = new Map<string, string>()
  return {
    objects,
    async get(key) {
      const value = objects.get(key)
      return value === undefined ? null : { body: new Response(value).body! }
    },
    async put(key, value) {
      objects.set(key, new TextDecoder().decode(value))
    },
    async delete(key) {
      objects.delete(key)
    },
    async list(options) {
      const keys = [...objects.keys()].filter(key => key.startsWith(options.prefix)).sort()
      const start = Number(options.cursor ?? 0)
      const truncated = start + 2 < keys.length
      return {
        objects: keys.slice(start, start + 2).map(key => ({ key, size: objects.get(key)!.length })),
        truncated,
        cursor: truncated ? String(start + 2) : undefined,
      }
    },
  }
}

const call = (b: DataBucket, path: string, init?: RequestInit) => answerDataMirror(new Request(`http://data.pully${path}`, init), b)

describe('answerDataMirror', () => {
  it('stores, reads, lists by page and deletes files under the data prefix', async () => {
    const b = bucket()
    b.objects.set('android/pully.apk', 'not data')
    expect((await call(b, '/file/store/diff/a%20b%3A1', { method: 'PUT', body: 'one' })).status).toBe(204)
    await call(b, '/file/store/settings', { method: 'PUT', body: '{}' })
    await call(b, '/file/analytics/2026-10-24.ndjson', { method: 'PUT', body: 'x' })
    expect(b.objects.get('data/store/diff/a b:1')).toBe('one')
    expect(await (await call(b, '/file/store/diff/a%20b%3A1')).text()).toBe('one')
    expect((await call(b, '/file/store/missing')).status).toBe(404)

    const first = await (await call(b, '/list')).json() as { files: { path: string }[], cursor: string | null }
    const second = await (await call(b, `/list?cursor=${first.cursor}`)).json() as typeof first
    expect([...first.files, ...second.files].map(f => f.path)).toEqual(['analytics/2026-10-24.ndjson', 'store/diff/a b:1', 'store/settings'])
    expect(second.cursor).toBeNull()

    expect((await call(b, '/file/store/settings', { method: 'DELETE' })).status).toBe(204)
    expect(b.objects.has('data/store/settings')).toBe(false)
    expect(b.objects.get('android/pully.apk')).toBe('not data')
  })

  it('refuses paths that could leave the data prefix and unknown methods', async () => {
    const b = bucket()
    for (const path of ['/file/a%2Fb', '/file/a%5Cb', '/file/', '/file/a//b', '/file/%E0%A4%A'])
      expect((await call(b, path, { method: 'PUT', body: 'x' })).status, path).toBe(400)
    // The URL parser folds dot segments before the handler sees them; nothing lands outside data/.
    for (const path of ['/file/../android/pully.apk', '/file/%2E%2E/%2E%2E/android/pully.apk'])
      await call(b, path, { method: 'PUT', body: 'x' })
    expect([...b.objects.keys()].every(key => key.startsWith('data/'))).toBe(true)
    b.objects.clear()
    expect((await call(b, '/file/a', { method: 'POST', body: 'x' })).status).toBe(405)
    expect((await call(b, '/list', { method: 'POST' })).status).toBe(405)
    expect((await call(b, '/elsewhere')).status).toBe(404)
    expect(b.objects.size).toBe(0)
    expect(dataPath('store/diff/a%20b')).toBe('store/diff/a b')
  })
})
