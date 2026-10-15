import type { CliModel, CliModelCatalog, LlmEngine } from '@pulls.review/core/local-rpc'
import type { CliProcess } from './process'
import { cliVersion } from './process'
import { startAgent } from './runtime'

const CACHE_MS = 10 * 60_000
const CLAUDE_INIT_TIMEOUT_MS = 30_000
const CODEX_TIMEOUT_MS = 30_000

type Json = Record<string, any>

const cache = new Map<LlmEngine, { at: number, value: Promise<CliModelCatalog> }>()

/** The CLIs are asked where jobs run them, so the catalog names what a job will get. */
function agentProcess(bin: string, args: string[]): CliProcess {
  return startAgent({ bin, args })
}

function claudeInitialize(): Promise<Json> {
  return new Promise((resolve, reject) => {
    const run = startAgent({ bin: 'claude', args: ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--setting-sources', '', '--strict-mcp-config', '--no-session-persistence'] }, {
      onLine(line) {
        let event: Json
        try {
          event = JSON.parse(line)
        }
        catch {
          return
        }
        if (event?.type !== 'control_response' || event.response?.request_id !== 'init')
          return
        finish()
        if (event.response.subtype === 'error')
          reject(new Error(String(event.response.error ?? 'Claude could not list its models.')))
        else
          resolve(event.response.response ?? {})
      },
    })
    const timer = setTimeout(() => {
      finish()
      reject(new Error(`Claude did not list its models within ${CLAUDE_INIT_TIMEOUT_MS / 1000}s.`))
    }, CLAUDE_INIT_TIMEOUT_MS)
    function finish(): void {
      clearTimeout(timer)
      run.child.stdin.end()
      run.kill()
    }
    run.exited.then(
      code => reject(new Error(run.stderr().trim().split('\n').at(-1) || `claude exited ${code} before listing its models.`)),
      (error) => {
        clearTimeout(timer)
        reject(error)
      },
    )
    run.child.stdin.write(`${JSON.stringify({ type: 'control_request', request_id: 'init', request: { subtype: 'initialize' } })}\n`)
  })
}

async function claudeCatalog(): Promise<CliModelCatalog> {
  const [init, version] = await Promise.all([claudeInitialize(), cliVersion('claude', agentProcess)])
  const models: CliModel[] = (Array.isArray(init.models) ? init.models : [])
    .filter((model: Json) => typeof model?.value === 'string')
    .map((model: Json) => ({
      id: model.value,
      name: typeof model.displayName === 'string' ? model.displayName : model.value,
      description: typeof model.description === 'string' ? model.description : undefined,
      efforts: Array.isArray(model.supportedEffortLevels) ? model.supportedEffortLevels : [],
      isDefault: model.value === 'default' || undefined,
    }))
  return { engine: 'claude-code', models, account: init.account?.subscriptionType, version }
}

async function codexCatalog(): Promise<CliModelCatalog> {
  let out = ''
  const run = startAgent({ bin: 'codex', args: ['debug', 'models'] })
  run.child.stdout.on('data', chunk => out += chunk)
  run.child.stdin.end()
  const timer = setTimeout(() => run.kill('SIGKILL'), CODEX_TIMEOUT_MS)
  const [code, version] = await Promise.all([run.exited.finally(() => clearTimeout(timer)), cliVersion('codex', agentProcess)])
  let parsed: Json
  try {
    parsed = JSON.parse(out)
  }
  catch {
    throw new Error(run.stderr().trim().split('\n').at(-1) || `codex debug models exited ${code} without a model list.`)
  }
  const models: CliModel[] = (Array.isArray(parsed.models) ? parsed.models : [])
    .filter((model: Json) => typeof model?.slug === 'string' && model.visibility !== 'hide')
    .toSorted((a: Json, b: Json) => (a.priority ?? Infinity) - (b.priority ?? Infinity))
    .map((model: Json, index: number) => ({
      id: model.slug,
      name: typeof model.display_name === 'string' ? model.display_name : model.slug,
      description: typeof model.description === 'string' ? model.description : undefined,
      efforts: (Array.isArray(model.supported_reasoning_levels) ? model.supported_reasoning_levels : [])
        .map((level: Json) => level?.effort)
        .filter((effort: unknown): effort is string => typeof effort === 'string'),
      defaultEffort: typeof model.default_reasoning_level === 'string' ? model.default_reasoning_level : undefined,
      // Jobs run this model when none is picked, so the default shown is the one that runs.
      isDefault: index === 0 || undefined,
    }))
  return { engine: 'codex', models, version }
}

/**
 * The models (and their reasoning efforts) the server's CLI offers to the account it is signed in with.
 * @param engine - Which CLI to ask.
 * @param refresh - Skip the ten-minute cache.
 * @returns The catalog, in the CLI's own order.
 * @throws When the CLI is missing, signed out or answers with something unreadable.
 */
export function listCliModels(engine: LlmEngine, refresh = false): Promise<CliModelCatalog> {
  const hit = cache.get(engine)
  if (hit && !refresh && Date.now() - hit.at < CACHE_MS)
    return hit.value
  const entry = { at: Date.now(), value: engine === 'codex' ? codexCatalog() : claudeCatalog() }
  cache.set(engine, entry)
  entry.value.catch(() => {
    if (cache.get(engine) === entry)
      cache.delete(engine)
  })
  return entry.value
}
