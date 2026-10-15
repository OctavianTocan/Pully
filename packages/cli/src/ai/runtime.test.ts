import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { agentRuntime, dockerRunArgs, mcpNode, REMOVE_AGAIN_MS, startAgent } from './runtime'

describe('agentRuntime', () => {
  it('runs on the host unless Docker is asked for', () => {
    expect(agentRuntime({})).toEqual({ kind: 'host' })
    expect(agentRuntime({ PULLS_REVIEW_AGENT_RUNTIME: ' host ' })).toEqual({ kind: 'host' })
    expect(agentRuntime({ PULLS_REVIEW_AGENT_RUNTIME: 'docker' })).toEqual({ kind: 'docker', image: 'pulls-review-agents' })
    expect(agentRuntime({ PULLS_REVIEW_AGENT_RUNTIME: 'docker', PULLS_REVIEW_AGENT_IMAGE: 'mine:1' })).toEqual({ kind: 'docker', image: 'mine:1' })
  })

  it('refuses a runtime it does not know', () => {
    expect(() => agentRuntime({ PULLS_REVIEW_AGENT_RUNTIME: 'podman' })).toThrow('PULLS_REVIEW_AGENT_RUNTIME must be one of host, docker, not "podman".')
  })
})

describe('dockerRunArgs', () => {
  it('runs the CLI in the job folder, with the home volume and each folder at its own path', () => {
    expect(dockerRunArgs('pulls-review-agents', 'pulls-agent-1', {
      bin: 'claude',
      args: ['-p', '--mcp-config', '{"mcpServers":{}}'],
      cwd: '/tmp/pulls-review-ai-x',
      mounts: [{ path: '/repo', readOnly: true }, { path: '/opt/pulls/packages/cli/dist', readOnly: true }],
    })).toEqual([
      'run',
      '--rm',
      '-i',
      '--init',
      '--pull',
      'never',
      '--name',
      'pulls-agent-1',
      '--mount',
      'type=volume,source=pulls-review-agents-home,target=/home/agent',
      '--mount',
      'type=bind,source=/tmp/pulls-review-ai-x,target=/tmp/pulls-review-ai-x',
      '--mount',
      'type=bind,source=/repo,target=/repo,readonly',
      '--mount',
      'type=bind,source=/opt/pulls/packages/cli/dist,target=/opt/pulls/packages/cli/dist,readonly',
      '--workdir',
      '/tmp/pulls-review-ai-x',
      'pulls-review-agents',
      'claude',
      '-p',
      '--mcp-config',
      '{"mcpServers":{}}',
    ])
  })

  it('mounts a folder once, even when it sits inside another read-only mount', () => {
    const args = dockerRunArgs('img', 'pulls-agent-2', {
      bin: 'codex',
      args: ['exec'],
      cwd: '/tmp/job',
      mounts: [
        { path: '/repo', readOnly: true },
        { path: '/repo/.git', readOnly: true },
        { path: '/repo/packages/cli/dist', readOnly: true },
        { path: '/repo', readOnly: true },
        { path: '/repository', readOnly: true },
      ],
    })
    expect(args.filter(arg => arg.startsWith('type=bind'))).toEqual([
      'type=bind,source=/tmp/job,target=/tmp/job',
      'type=bind,source=/repo,target=/repo,readonly',
      'type=bind,source=/repository,target=/repository,readonly',
    ])
  })

  it('quotes a path Docker would split, and needs no working folder', () => {
    const args = dockerRunArgs('img', 'pulls-agent-3', { bin: 'codex', args: ['--version'], mounts: [{ path: '/a,b "c"', readOnly: true }] })
    expect(args).toContain('type=bind,"source=/a,b ""c""","target=/a,b ""c""",readonly')
    expect(args).not.toContain('--workdir')
    expect(args.slice(-3)).toEqual(['img', 'codex', '--version'])
  })
})

describe('mcpNode', () => {
  it('starts the tool server with the image\'s node in a container, and this node on the host', () => {
    expect(mcpNode({ kind: 'docker', image: 'img' })).toBe('node')
    expect(mcpNode({ kind: 'host' })).toBe(process.execPath)
  })
})

describe('startAgent in a container', () => {
  const path = process.env.PATH
  let bin: string
  let log: string
  const calls = (): string[][] => existsSync(log) ? readFileSync(log, 'utf8').trim().split('\n').map(line => JSON.parse(line)) : []
  // Polls on setInterval, which the fake timers below leave alone.
  const until = (check: () => boolean) => new Promise<void>((resolve, reject) => {
    const end = Date.now() + 5000
    const timer = setInterval(() => {
      if (check() || Date.now() > end) {
        clearInterval(timer)
        return check() ? resolve() : reject(new Error('timed out'))
      }
    }, 20)
  })

  beforeAll(() => {
    bin = mkdtempSync(join(tmpdir(), 'pulls-review-docker-'))
    log = join(bin, 'docker.log')
    // Logs each call. `run` only waits to be killed: a client stopped before the daemon has made its container.
    writeFileSync(join(bin, 'fake-docker.cjs'), `require('node:fs').appendFileSync(${JSON.stringify(log)}, JSON.stringify(process.argv.slice(2)) + '\\n')\nif (process.argv[2] === 'run') setInterval(() => {}, 1000)\n`)
    writeFileSync(join(bin, 'docker'), `#!/bin/sh\nexec "${process.execPath}" "$(dirname "$0")/fake-docker.cjs" "$@"\n`)
    chmodSync(join(bin, 'docker'), 0o755)
    process.env.PATH = `${bin}:${path}`
  })
  afterEach(() => vi.useRealTimers())
  afterAll(() => {
    process.env.PATH = path
    rmSync(bin, { recursive: true, force: true })
  })

  it('removes the container by name once the stopped client exits, and once more later', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] })
    const agent = startAgent({ bin: 'claude', args: ['-p'] }, { runtime: { kind: 'docker', image: 'img' } })
    await until(() => calls().some(call => call[0] === 'run'))
    const run = calls().find(call => call[0] === 'run')!
    const name = run[run.indexOf('--name') + 1]!

    agent.kill('SIGTERM')
    expect(await agent.exited).toBeNull()
    await until(() => calls().some(call => call[0] === 'rm') && calls().some(call => call[0] === 'kill'))
    expect(calls().filter(call => call[0] !== 'run').sort()).toEqual([['kill', '--signal', 'SIGTERM', name], ['rm', '--force', name]])

    // A kill after the client exited still reaches Docker, and the timer removes whatever the daemon made late.
    agent.kill('SIGKILL')
    vi.advanceTimersByTime(REMOVE_AGAIN_MS)
    await until(() => calls().filter(call => call[0] === 'rm').length === 2 && calls().some(call => call[2] === 'SIGKILL'))
  })
})
