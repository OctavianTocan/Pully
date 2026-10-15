import type { ChildProcessWithoutNullStreams } from 'node:child_process'
import { spawn } from 'node:child_process'
import process from 'node:process'
import { createInterface } from 'node:readline'

/** A CLI started in its own process group, so stopping it also stops whatever it spawned. */
export interface CliProcess {
  child: ChildProcessWithoutNullStreams
  /** Signals the CLI and every process it started; a no-op once it exited. */
  kill: (signal?: NodeJS.Signals) => void
  /** Settles with the exit code (null when killed by a signal); rejects when the binary cannot start. */
  exited: Promise<number | null>
  /** The last stderr output, for error messages. */
  stderr: () => string
}

const STDERR_LIMIT = 16_000

/**
 * Starts a CLI with piped stdio.
 * @param bin - The program to run, looked up on PATH.
 * @param args - Its arguments.
 * @param options - Working directory, and a listener for each stdout line.
 * @param options.cwd - Where the CLI runs; the server's working directory when left out.
 * @param options.onLine - Receives every complete stdout line.
 * @returns A handle to the running CLI.
 */
export function startCli(bin: string, args: string[], options: { cwd?: string, onLine?: (line: string) => void } = {}): CliProcess {
  // Wrappers such as mise shims run the real binary as a child; a group kill reaches both.
  const child = spawn(bin, args, { cwd: options.cwd, env: process.env, stdio: ['pipe', 'pipe', 'pipe'], detached: true })
  let stderr = ''
  let done = false
  child.stderr.on('data', (chunk) => {
    stderr = (stderr + chunk).slice(-STDERR_LIMIT)
  })
  child.stdin.on('error', () => {})
  if (options.onLine)
    createInterface({ input: child.stdout }).on('line', options.onLine)
  else
    child.stdout.resume()

  const exited = new Promise<number | null>((resolve, reject) => {
    child.on('error', (error) => {
      done = true
      reject(error.message.includes('ENOENT') ? new Error(`\`${bin}\` was not found on PATH.`) : error)
    })
    child.on('close', (code) => {
      done = true
      resolve(code)
    })
  })
  exited.catch(() => {})

  function kill(signal: NodeJS.Signals = 'SIGTERM'): void {
    if (done || !child.pid)
      return
    try {
      process.kill(-child.pid, signal)
    }
    catch {
      child.kill(signal)
    }
  }

  return { child, kill, exited, stderr: () => stderr }
}

/**
 * The version a CLI reports with `--version`.
 * @param bin - The program to ask.
 * @param start - How to start it; on the server itself by default.
 * @returns The first version number in its output, or undefined when it cannot say.
 */
export async function cliVersion(bin: string, start: (bin: string, args: string[]) => CliProcess = startCli): Promise<string | undefined> {
  let out = ''
  const run = start(bin, ['--version'])
  run.child.stdout.on('data', chunk => out += chunk)
  const timer = setTimeout(() => run.kill('SIGKILL'), 10_000)
  try {
    await run.exited
    return /\d+\.\d+\.\d\S*/.exec(out)?.[0]
  }
  catch {
    return undefined
  }
  finally {
    clearTimeout(timer)
  }
}
