// Prints the Worker config for the staging server: wrangler.jsonc with the staging host as its
// custom domain and public URL. The host comes from the PULLY_STAGING_HOST repository variable,
// set only once its Cloudflare Access application exists (CONTRACTS, hosted-server-only-behind-access).
//
//   node scripts/staging-config.mjs <host> > wrangler.staging.json
import { readFileSync } from 'node:fs'
import process from 'node:process'

const host = process.argv[2] ?? ''
if (!/^[a-z0-9.-]+\.[a-z]+$/.test(host))
  throw new Error('Pass the staging host name, for example pully-staging.octaviantocan.com')
// wrangler.jsonc has only whole-line comments.
const source = readFileSync(new URL('../wrangler.jsonc', import.meta.url), 'utf8')
const config = JSON.parse(source.split('\n').filter(line => !/^\s*\/\//.test(line)).join('\n'))
config.routes = [{ pattern: host, custom_domain: true }]
config.vars = { ...config.vars, PULLY_PUBLIC_URL: `https://${host}` }
process.stdout.write(`${JSON.stringify(config, null, 2)}\n`)
