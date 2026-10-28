// Prints the Worker config for the stable server: wrangler.jsonc as its own Worker
// (`pully-stable`, so its Durable Objects and data are apart from staging's), with its own
// Android bucket and the stable host as its custom domain and public URL. The host comes from
// the PULLY_STABLE_HOST repository variable, which the operator sets only with Tavi's go-ahead
// to move that host to Cloudflare, after its Access application exists (CONTRACTS,
// hosted-server-only-behind-access).
//
//   node scripts/stable-config.mjs <host> > wrangler.stable.json
import { readFileSync } from 'node:fs'
import process from 'node:process'

const host = process.argv[2] ?? ''
if (!/^[a-z0-9.-]+\.[a-z]+$/.test(host))
  throw new Error('Pass the stable host name, for example prs.octaviantocan.com')
// wrangler.jsonc has only whole-line comments.
const source = readFileSync(new URL('../wrangler.jsonc', import.meta.url), 'utf8')
const config = JSON.parse(source.split('\n').filter(line => !/^\s*\/\//.test(line)).join('\n'))
config.name = 'pully-stable'
config.r2_buckets = config.r2_buckets.map(bucket =>
  bucket.binding === 'PULLY_ANDROID' ? { ...bucket, bucket_name: 'pully-android-stable' } : bucket)
config.routes = [{ pattern: host, custom_domain: true }]
config.vars = { ...config.vars, PULLY_PUBLIC_URL: `https://${host}` }
process.stdout.write(`${JSON.stringify(config, null, 2)}\n`)
