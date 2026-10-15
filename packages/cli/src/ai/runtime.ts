import type { CliProcess } from './process'
import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import process from 'node:process'
import { startCli } from './process'

/** Where the Claude Code and Codex CLIs run: on the server itself, or in the agents image. */
export type AgentRuntime = { kind: 'host' } | { kind: 'docker', image: string }

export const AGENT_RUNTIMES = ['host', 'docker'] as const

/** How long after a stopped client exits its container is removed by name once more. */
export const REMOVE_AGAIN_MS = 10_000

/** The image `docker/agents/setup.sh build` tags. */
export const DEFAULT_AGENT_IMAGE = 'pulls-review-agents'
/** The volume that keeps the CLIs' logins (and nothing of the host's) between runs. */
export const AGENT_HOME_VOLUME = 'pulls-review-agents-home'
export const AGENT_HOME = '/home/agent'

/**
 * The runtime the environment asks for: `PULLS_REVIEW_AGENT_RUNTIME` (`host` when unset) and, for
 * Docker, `PULLS_REVIEW_AGENT_IMAGE`.
 * @param env - The environment to read.
 * @returns Where the CLIs run.
 * @throws When the runtime is neither `host` nor `docker`.
 */
export function agentRuntime(env: NodeJS.ProcessEnv = process.env): AgentRuntime {
  const kind = env.PULLS_REVIEW_AGENT_RUNTIME?.trim() || 'host'
  if (kind === 'host')
    return { kind: 'host' }
  if (kind === 'docker')
    return { kind: 'docker', image: env.PULLS_REVIEW_AGENT_IMAGE?.trim() || DEFAULT_AGENT_IMAGE }
  throw new Error(`PULLS_REVIEW_AGENT_RUNTIME must be one of ${AGENT_RUNTIMES.join(', ')}, not "${kind}".`)
}

/** A folder of the server's machine that a CLI needs, mounted at the same path so every path stays valid. */
export interface AgentMount {
  path: string
  readOnly?: boolean
}

/** One CLI run, described the same way for both runtimes. */
export interface AgentLaunch {
  bin: string
  args: string[]
  /** Where the CLI runs. In a container it is mounted read-write. */
  cwd?: string
  /** Further folders a container needs; the host runtime ignores them. */
  mounts?: AgentMount[]
}

function inside(path: string, folder: string): boolean {
  return path === folder || path.startsWith(folder.endsWith('/') ? folder : `${folder}/`)
}

/** Docker reads `--mount` as one CSV record, so a path with a comma or a quote is quoted. */
function csv(fields: string[]): string {
  return fields.map(field => /[",\n]/.test(field) ? `"${field.replaceAll('"', '""')}"` : field).join(',')
}

/**
 * The `docker` arguments that run a CLI in the agents image: removed when it exits, stdin kept open,
 * an init process to pass signals on, the home volume, and each folder at its own path.
 * @param image - The image to run.
 * @param name - The container's name, which `docker kill` stops it by.
 * @param launch - The CLI, its arguments, working folder and mounts.
 * @returns The argument list for `docker`.
 */
export function dockerRunArgs(image: string, name: string, launch: AgentLaunch): string[] {
  const wanted: Required<AgentMount>[] = [
    ...(launch.cwd ? [{ path: launch.cwd, readOnly: false }] : []),
    ...(launch.mounts ?? []).map(mount => ({ path: mount.path, readOnly: mount.readOnly ?? false })),
  ]
  // A folder already inside another mount of the same kind needs no mount of its own (Docker refuses a duplicate).
  const mounts = wanted.filter((mount, index) => !wanted.some((other, at) =>
    other.readOnly === mount.readOnly && inside(mount.path, other.path) && (other.path !== mount.path || at < index)))
  const args = [
    'run',
    '--rm',
    '-i',
    '--init',
    '--pull',
    'never',
    '--name',
    name,
    '--mount',
    `type=volume,source=${AGENT_HOME_VOLUME},target=${AGENT_HOME}`,
  ]
  for (const mount of mounts)
    args.push('--mount', csv(['type=bind', `source=${mount.path}`, `target=${mount.path}`, ...(mount.readOnly ? ['readonly'] : [])]))
  if (launch.cwd)
    args.push('--workdir', launch.cwd)
  args.push(image, launch.bin, ...launch.args)
  return args
}

/**
 * The command a CLI starts the pull request tool server with: this very Node on the host, the image's
 * own in a container.
 * @param runtime - Where the CLI runs.
 * @returns The program to put in the MCP config.
 */
export function mcpNode(runtime: AgentRuntime): string {
  return runtime.kind === 'docker' ? 'node' : process.execPath
}

/**
 * Starts a Claude Code or Codex CLI where the runtime says. In a container, stopping it also stops the
 * container: killing the `docker` client alone can leave the CLI running.
 * @param launch - The CLI, its arguments, working folder and mounts.
 * @param options - A listener for each stdout line, and the runtime (from the environment when left out).
 * @param options.onLine - Receives every complete stdout line.
 * @param options.runtime - Where to run it.
 * @returns A handle to the running CLI.
 */
export function startAgent(launch: AgentLaunch, options: { onLine?: (line: string) => void, runtime?: AgentRuntime } = {}): CliProcess {
  const runtime = options.runtime ?? agentRuntime()
  if (runtime.kind === 'host')
    return startCli(launch.bin, launch.args, { cwd: launch.cwd, onLine: options.onLine })

  const name = `pulls-agent-${randomUUID()}`
  const client = startCli('docker', dockerRunArgs(runtime.image, name, launch), { onLine: options.onLine })
  const docker = (...args: string[]) => execFile('docker', args, () => {})
  let stopping = false
  return {
    ...client,
    kill(signal: NodeJS.Signals = 'SIGTERM') {
      // The daemon makes the container after the client asks, so a stop can find none to kill yet and the
      // client can exit before it appears. Once the client is gone it is removed by name, and again later.
      if (!stopping) {
        stopping = true
        const remove = () => docker('rm', '--force', name)
        void client.exited.catch(() => {}).then(() => {
          remove()
          setTimeout(remove, REMOVE_AGAIN_MS).unref()
        })
      }
      docker('kill', '--signal', signal, name)
      client.kill(signal)
    },
  }
}
