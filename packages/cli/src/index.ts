import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { createDevServer } from 'devframe/adapters/dev'
import { H3 } from 'h3'
import devframe from './devframe'
import { handleNativeRequest } from './native-api'
import { pageFor } from './page'
import { isPreanalyzeEngine, PREANALYZE_ENGINES, startPreanalyze } from './preanalyze'

const HELP = `Usage: pulls.review [target] [options]

Reviews local git changes in the browser, grouped and summarized. Run it inside
a git repository. The target only picks the page that opens; any other can be
picked from the browser.

Target (git revision syntax):
  (none)     the current branch against the default branch, or the ref picker
             when the default branch itself is checked out
  <branch>   what a branch adds since it forked from the default branch
  <rev>      one commit against its parent
  A...B      what B adds since it forked from A
  A..B       the tree diff between two revisions

Options:
  --worktree     open the uncommitted changes against HEAD
  --port <port>  port to listen on
  --host <host>  address to bind (default localhost)
  --allow-origin <origin>
                 accept browser connections from this origin, e.g. behind a
                 tunnel (repeatable)
  --no-auth      skip the terminal code gate; only for a deployment whose
                 reverse proxy already authenticates every request
  --no-open      do not open the browser
  --preanalyze   every 10 minutes, group the open pull requests that request
                 your review with AI, so they open already analyzed; runs on
                 your Claude Code or Codex plan, off by default
  --preanalyze-engine <claude-code|codex>
                 the CLI --preanalyze runs (default claude-code)
  --preanalyze-model <model>
                 the model --preanalyze asks for (default: the engine's own)
  -h, --help

To review a GitHub pull request in CI, use @pulls.review/actions.
`

/**
 * devframe's SPA fallback skips any path that looks like a file, and many pages do: refs
 * (`main...feat`, `v1.2`) and repositories (`/antfu/pulls.review`). Page requests for
 * such paths get the SPA's `index.html` here, unless a built file really lives there.
 */
function createAppWithRefRoutes(): H3 {
  const app = new H3()
  const client = fileURLToPath(new URL('./client/', import.meta.url))
  const index = join(client, 'index.html')
  app.use(async (event, next) => {
    const native = await handleNativeRequest({
      url: event.url,
      method: event.req.method,
      json: () => event.req.json(),
    }, process.cwd())
    if (native)
      return native
    const path = event.url.pathname
    if (event.req.method !== 'GET' || path.startsWith('/__') || !event.req.headers.get('accept')?.includes('text/html'))
      return next()
    const page = /^\/(?:compare|branch|commit)\//.test(path) || (/\.[^/]*$/.test(path) && !existsSync(join(client, path)))
    if (!page)
      return next()
    return new Response(await readFile(index, 'utf8'), { headers: { 'Content-Type': 'text/html; charset=utf-8' } })
  })
  return app
}

async function main() {
  const { values: flags, positionals } = parseArgs({
    options: {
      'worktree': { type: 'boolean' },
      'port': { type: 'string' },
      'host': { type: 'string' },
      'allow-origin': { type: 'string', multiple: true },
      'no-auth': { type: 'boolean' },
      'no-open': { type: 'boolean' },
      'preanalyze': { type: 'boolean' },
      'preanalyze-engine': { type: 'string', default: 'claude-code' },
      'preanalyze-model': { type: 'string' },
      'help': { type: 'boolean', short: 'h' },
    },
    allowPositionals: true,
  })
  if (flags.help) {
    process.stdout.write(HELP)
    return
  }
  const preanalyzeEngine = flags['preanalyze-engine']
  if (flags.preanalyze && !isPreanalyzeEngine(preanalyzeEngine))
    throw new Error(`--preanalyze-engine must be one of: ${PREANALYZE_ENGINES.join(', ')}`)
  const page = encodeURI(await pageFor(process.cwd(), positionals[0], !!flags.worktree))
  await createDevServer(devframe, {
    app: createAppWithRefRoutes(),
    port: flags.port ? Number(flags.port) : undefined,
    host: flags.host,
    allowedOrigins: flags['allow-origin'],
    ...(flags['no-auth'] ? { auth: false } : {}),
    openBrowser: flags['no-open'] ? false : page,
    onReady: ({ origin }) => {
      process.stdout.write(`pulls.review is serving ${origin}${page}\n`)
    },
  })
  if (flags.preanalyze && isPreanalyzeEngine(preanalyzeEngine))
    await startPreanalyze({ cwd: process.cwd(), env: process.env, engine: preanalyzeEngine, model: flags['preanalyze-model'] })
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
})
