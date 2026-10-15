import type { CliModel, CliModelCatalog, LlmEngine } from '@pulls.review/core/local-rpc'
import { cliVersion, startCli } from './process'

const CACHE_MS = 10 * 60_000
const CLAUDE_INIT_TIMEOUT_MS = 30_000
const CODEX_TIMEOUT_MS = 30_000

type Json = Record<string, any>

const cache = new Map<LlmEngine, { at: number, value: Promise<CliModelCatalog> }>()

function claudeInitialize(): Promise<Json> {
  return new Promise((resolve, reject) => {
    const run = startCli('claude', ['-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--setting-sources', '', '--strict-mcp-config', '--no-session-persistence'], {
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

/**
 * The effort Claude Code runs a model at when no `--effort` is passed: the `default_effort` of the model table bundled
 * in Claude Code (read from the 2.1.300 binary with `strings`; its `--help` and `initialize` answer do not say).
 * A model the table gives none runs at `CLAUDE_FALLBACK_EFFORT`. An account or organization setting can still
 * change it on the server, which no answer of the CLI reveals.
 */
export const CLAUDE_DEFAULT_EFFORTS: Readonly<Record<string, string>> = {
  'claude-opus-5-5': 'medium',
  'claude-sonnet-5-5': 'medium',
  'claude-haiku-5-5': 'medium',
  'claude-fable-5-1': 'high',
  'claude-fable-5': 'high',
  'claude-opus-5': 'high',
  'claude-sonnet-5': 'high',
  'claude-opus-4-8': 'high',
  'claude-opus-4-7': 'xhigh',
}

/** What Claude Code falls back to for a model its table gives no default effort. */
export const CLAUDE_FALLBACK_EFFORT = 'high'

/**
 * The effort Claude Code uses for a model when none is passed.
 * @param model - The model the CLI resolves an alias to (`claude-opus-5-5`), or a full id with a date or `[1m]`.
 * @param efforts - The efforts the model accepts.
 * @returns That effort, or `undefined` when the model takes none of these levels.
 */
export function claudeDefaultEffort(model: string, efforts: readonly string[]): string | undefined {
  const base = model.replace(/\[[^\]]*\]$/, '').replace(/-\d{8}$/, '')
  const effort = CLAUDE_DEFAULT_EFFORTS[base] ?? CLAUDE_FALLBACK_EFFORT
  return efforts.includes(effort) ? effort : undefined
}

/**
 * The name a Claude model is shown by. Claude Code names its aliases by family alone ("Opus") and puts the version
 * at the head of the description ("Opus 5.5 · Best for everyday, complex tasks"); the version moves into the name, so
 * every model reads like "Opus 5.5" and the app can tell the newest from the older ones (rule 89).
 * @param name - The CLI's display name.
 * @param description - The CLI's description.
 * @returns The name and the description that is left.
 */
export function claudeModelName(name: string, description: string | undefined): { name: string, description?: string } {
  const at = description?.indexOf(' · ') ?? -1
  const head = at > 0 ? description!.slice(0, at).trim() : ''
  if (!/^[a-z]+$/i.test(name) || !/^\S+ \d+(?:\.\d+)?$/.test(head) || head.split(' ')[0]!.toLowerCase() !== name.toLowerCase())
    return { name, description }
  return { name: head, description: description!.slice(at + 3).trim() || undefined }
}

/**
 * The models of Claude Code's `initialize` answer.
 * @param models - Its `models` field.
 * @returns Them in the CLI's order, each with its versioned name and the effort it runs at by default.
 */
export function claudeModels(models: unknown): CliModel[] {
  return (Array.isArray(models) ? models : [])
    .filter((model: Json) => typeof model?.value === 'string')
    .map((model: Json) => {
      const efforts: string[] = Array.isArray(model.supportedEffortLevels) ? model.supportedEffortLevels.filter((level: unknown) => typeof level === 'string') : []
      const named = model.value === 'default'
        ? { name: typeof model.displayName === 'string' ? model.displayName : model.value, description: typeof model.description === 'string' ? model.description : undefined }
        : claudeModelName(typeof model.displayName === 'string' ? model.displayName : model.value, typeof model.description === 'string' ? model.description : undefined)
      const resolved = typeof model.resolvedModel === 'string' ? model.resolvedModel : model.value
      return {
        id: model.value,
        ...named,
        efforts,
        defaultEffort: efforts.length ? claudeDefaultEffort(resolved, efforts) : undefined,
        isDefault: model.value === 'default' || undefined,
      }
    })
}

async function claudeCatalog(): Promise<CliModelCatalog> {
  const [init, version] = await Promise.all([claudeInitialize(), cliVersion('claude')])
  return { engine: 'claude-code', models: claudeModels(init.models), account: init.account?.subscriptionType, version }
}

async function codexCatalog(): Promise<CliModelCatalog> {
  let out = ''
  const run = startCli('codex', ['debug', 'models'])
  run.child.stdout.on('data', chunk => out += chunk)
  run.child.stdin.end()
  const timer = setTimeout(() => run.kill('SIGKILL'), CODEX_TIMEOUT_MS)
  const [code, version] = await Promise.all([run.exited.finally(() => clearTimeout(timer)), cliVersion('codex')])
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
