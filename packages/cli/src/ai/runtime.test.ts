import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { agentRuntime, dockerRunArgs, mcpNode } from './runtime'

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
