import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { batches, plan, storeRecords } from './import-vps.mjs'

function folders() {
  const root = mkdtempSync(join(tmpdir(), 'pully-import-'))
  const write = (path, text) => {
    mkdirSync(join(root, path, '..'), { recursive: true })
    writeFileSync(join(root, path), text)
  }
  write('server/account', '{"a":1}')
  write('server/diff/github%3Ao%2Fr%231', '{"d":1}')
  write('server/.rust-server.lock', '')
  write('server/diff/.tmp-1', 'partial')
  write('server/diff/deeper/ignored', 'x')
  symlinkSync(join(root, 'server/account'), join(root, 'server/linked'))
  write('public/personal-auth/personal-auth-v1%3Astate', '{"version":1}')
  write(`public/personal-tenants/${'a'.repeat(64)}/settings`, '{"s":1}')
  write('public/personal-tenants/not-an-account/settings', '{}')
  write('analytics/2026-10-27.ndjson', '{"e":1}\n{"e":2}\n')
  write('analytics/notes.txt', 'x')
  return root
}

test('reads a store as the server keeps it, leaving out hidden files, symlinks and deeper folders', () => {
  const root = folders()
  const records = storeRecords(join(root, 'server'))
  assert.deepEqual(records.map(record => record.id), ['account', 'diff/github%3Ao%2Fr%231'])
  assert.equal(Buffer.from(records[0].value, 'base64').toString(), '{"a":1}')
})

test('sends the sign-in store first, then accounts, the server and analytics', () => {
  const root = folders()
  const requests = plan({ serverData: join(root, 'server'), publicData: join(root, 'public'), analytics: join(root, 'analytics') })
  assert.deepEqual(requests.map(request => request.path), ['/import/v1/personal-auth', '/import/v1/tenant', '/import/v1/server', '/import/v1/analytics'])
  assert.equal(requests[1].body.key, 'a'.repeat(64))
  assert.deepEqual(requests[3].body.days, [{ day: '2026-10-27', lines: ['{"e":1}', '{"e":2}'] }])
})

test('splits batches by size and sends a large record alone', () => {
  const big = { id: 'big', value: 'x'.repeat(9 * 1024 * 1024) }
  const small = { id: 'small', value: 'y' }
  assert.deepEqual(batches([small, big, small]).map(batch => batch.map(record => record.id)), [['small'], ['big'], ['small']])
  assert.deepEqual(batches([small, small]).length, 1)
})
