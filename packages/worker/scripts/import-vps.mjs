#!/usr/bin/env node
// Copies the VPS server's data into the hosted Worker, once (packages/worker/src/import.rs).
// Without --send it only counts what it would copy. Run it on the VPS, against a Worker that
// nobody has used yet, with the Worker's PULLY_IMPORT_TOKEN secret set, then delete that
// secret. The order matters: the sign-in store first, as the Worker checks each account store
// against it.
//
//   PULLY_IMPORT_TOKEN=... [CF_ACCESS_CLIENT_ID=... CF_ACCESS_CLIENT_SECRET=...] \
//   node packages/worker/scripts/import-vps.mjs --url https://<host> \
//     --server-data <PULLS_REVIEW_DATA_DIR> --public-data <PULLY_PUBLIC_DATA_DIR> \
//     [--analytics <PULLS_REVIEW_ANALYTICS_DIR>] [--send]
import { lstatSync, readdirSync, readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

/** Largest batch body, in bytes of base64, sent in one request. */
const BATCH_BYTES = 8 * 1024 * 1024

/**
 * Reads the records of one native store directory, as the server's file records keep them:
 * a file per record, at the top or one collection folder down. Hidden files (the server's
 * lock and temporary files) and symlinks are left out.
 * @param {string} root the store directory
 * @returns {{id: string, value: string}[]} each record's id and base64 bytes
 */
export function storeRecords(root) {
  const records = []
  const visit = (directory, prefix, depth) => {
    for (const name of readdirSync(directory).sort()) {
      if (name.startsWith('.'))
        continue
      const path = join(directory, name)
      const stat = lstatSync(path)
      if (stat.isFile())
        records.push({ id: prefix + name, value: readFileSync(path).toString('base64') })
      else if (stat.isDirectory() && depth === 0)
        visit(path, `${name}/`, 1)
    }
  }
  visit(root, '', 0)
  return records
}

/**
 * Splits records into batches of at most BATCH_BYTES; a larger record goes alone.
 * @param {{id: string, value: string}[]} records
 * @returns {{id: string, value: string}[][]} the batches, in order
 */
export function batches(records) {
  const all = []
  let current = []
  let size = 0
  for (const record of records) {
    if (current.length && size + record.value.length > BATCH_BYTES) {
      all.push(current)
      current = []
      size = 0
    }
    current.push(record)
    size += record.value.length
  }
  if (current.length)
    all.push(current)
  return all
}

/**
 * Reads the analytics day files (`YYYY-MM-DD.ndjson`) of the native server.
 * @param {string} root the analytics directory
 * @returns {{day: string, lines: string[]}[]} each day's event lines
 */
export function analyticsDays(root) {
  let names
  try {
    names = readdirSync(root)
  }
  catch {
    return []
  }
  return names
    .filter(name => /^\d{4}-\d{2}-\d{2}\.ndjson$/.test(name) && lstatSync(join(root, name)).isFile())
    .sort()
    .map(name => ({
      day: name.slice(0, 10),
      lines: readFileSync(join(root, name), 'utf8').split('\n').filter(Boolean),
    }))
}

/**
 * Everything to copy, in the order it must be sent.
 * @param {{serverData: string, publicData: string, analytics: string}} folders
 * @returns {{path: string, body: object, count: number}[]} one request each
 */
export function plan({ serverData, publicData, analytics }) {
  const requests = []
  for (const records of batches(storeRecords(join(publicData, 'personal-auth'))))
    requests.push({ path: '/import/v1/personal-auth', body: { records }, count: records.length })
  const tenants = join(publicData, 'personal-tenants')
  let keys = []
  try {
    keys = readdirSync(tenants).filter(name => /^[0-9a-f]{64}$/.test(name) && lstatSync(join(tenants, name)).isDirectory()).sort()
  }
  catch {}
  for (const key of keys) {
    for (const records of batches(storeRecords(join(tenants, key))))
      requests.push({ path: '/import/v1/tenant', body: { key, records }, count: records.length })
  }
  for (const records of batches(storeRecords(serverData)))
    requests.push({ path: '/import/v1/server', body: { records }, count: records.length })
  const days = analyticsDays(analytics)
  for (let i = 0; i < days.length; i += 30)
    requests.push({ path: '/import/v1/analytics', body: { days: days.slice(i, i + 30) }, count: Math.min(30, days.length - i) })
  return requests
}

function option(args, name) {
  const index = args.indexOf(name)
  return index >= 0 ? args[index + 1] : undefined
}

async function main(args) {
  const url = option(args, '--url')
  const serverData = option(args, '--server-data')
  const publicData = option(args, '--public-data')
  const analytics = option(args, '--analytics') ?? join(homedir(), '.local/share/pulls-review/analytics')
  if (!url || !serverData || !publicData)
    throw new Error('Usage: import-vps.mjs --url <https://host> --server-data <dir> --public-data <dir> [--analytics <dir>] [--send]')
  const send = args.includes('--send')
  const requests = plan({ serverData, publicData, analytics })
  const totals = {}
  for (const request of requests)
    totals[request.path] = (totals[request.path] ?? 0) + request.count
  for (const [path, count] of Object.entries(totals))
    console.log(`${path}: ${count} ${path.endsWith('analytics') ? 'days' : 'records'}`)
  if (!send) {
    console.log('Nothing sent. Add --send to copy.')
    return
  }
  const token = process.env.PULLY_IMPORT_TOKEN
  if (!token)
    throw new Error('Set PULLY_IMPORT_TOKEN to the Worker secret of the same name.')
  const headers = { 'content-type': 'application/json', 'x-pully-import': token }
  if (process.env.CF_ACCESS_CLIENT_ID) {
    headers['cf-access-client-id'] = process.env.CF_ACCESS_CLIENT_ID
    headers['cf-access-client-secret'] = process.env.CF_ACCESS_CLIENT_SECRET ?? ''
  }
  for (const request of requests) {
    const response = await fetch(new URL(request.path, url), { method: 'POST', headers, body: JSON.stringify(request.body) })
    if (!response.ok)
      throw new Error(`${request.path} answered ${response.status}; stopping. Batches already sent are kept and are skipped if sent again.`)
    console.log(request.path, request.body.key ?? '', JSON.stringify(await response.json()))
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message)
    process.exit(1)
  })
}
