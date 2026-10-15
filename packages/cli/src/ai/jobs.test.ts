import type { AiActivity, AiJobRequest, AiJobSnapshot } from '@pulls.review/core/local-rpc'
import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { cancelAiJob, listAiJobs, runAiJob, startAiJob, waitAiJob } from './jobs'

const FAKE_CLAUDE = `
let input = ''
process.stdin.on('data', chunk => input += chunk)
process.stdin.on('end', () => {
  const prompt = input.trim().split('\\n').map(line => JSON.parse(line)).find(line => line.type === 'user').message.content
  const out = event => process.stdout.write(JSON.stringify(event) + '\\n')
  require('node:fs').writeFileSync(require('node:path').join(process.env.FAKE_PIDS, prompt), String(process.pid))
  out({ type: 'system', subtype: 'init', model: 'fake-model' })
  if (prompt === 'fail') {
    process.stderr.write('Not logged in · Please run /login\\n')
    process.exit(1)
  }
  if (prompt === 'slow')
    return setInterval(() => {}, 1000)
  out({ type: 'stream_event', event: { type: 'message_start', message: {} } })
  out({ type: 'stream_event', event: { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } } })
  out({ type: 'stream_event', event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Hello' } } })
  out({ type: 'stream_event', event: { type: 'content_block_stop', index: 0 } })
  out({ type: 'result', subtype: 'success', is_error: false, result: 'Hello', total_cost_usd: 0.5, duration_ms: 10, usage: { input_tokens: 1, output_tokens: 2 } })
})
`

/** Stands in for `docker`: logs each call, and runs the image's command on the host in the working folder. */
const FAKE_DOCKER = `
const args = process.argv.slice(2)
require('node:fs').appendFileSync(process.env.FAKE_DOCKER_LOG, JSON.stringify(args) + '\\n')
if (args[0] !== 'run')
  process.exit(0)
const image = args.indexOf('pulls-review-agents')
const cwd = args.includes('--workdir') ? args[args.indexOf('--workdir') + 1] : undefined
const child = require('node:child_process').spawn(args[image + 1], args.slice(image + 2), { cwd, stdio: 'inherit' })
child.on('close', code => process.exit(code ?? 1))
`

const ask = (prompt: string, extra: Partial<AiJobRequest> = {}): AiJobRequest => ({ engine: 'claude-code', system: 'S', prompt, ...extra })

async function follow(id: string): Promise<{ last: AiJobSnapshot, log: Map<string, AiActivity> }> {
  const log = new Map<string, AiActivity>()
  let after = 0
  while (true) {
    const snapshot = await waitAiJob(id, after)
    for (const entry of snapshot.activities)
      log.set(entry.id, entry)
    after = snapshot.rev
    if (snapshot.status !== 'running')
      return { last: snapshot, log }
  }
}

async function until(check: () => boolean, ms = 5000): Promise<void> {
  const end = Date.now() + ms
  while (!check()) {
    if (Date.now() > end)
      throw new Error('timed out')
    await new Promise(resolve => setTimeout(resolve, 25))
  }
}

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  }
  catch {
    return false
  }
}

describe('ai jobs', () => {
  let bin: string
  let pids: string
  const path = process.env.PATH

  beforeAll(() => {
    bin = mkdtempSync(join(tmpdir(), 'fake-cli-'))
    pids = mkdtempSync(join(tmpdir(), 'fake-pids-'))
    writeFileSync(join(bin, 'fake-claude.cjs'), FAKE_CLAUDE)
    writeFileSync(join(bin, 'fake-docker.cjs'), FAKE_DOCKER)
    writeFileSync(join(bin, 'docker'), `#!/bin/sh\nexec "${process.execPath}" "$(dirname "$0")/fake-docker.cjs" "$@"\n`)
    chmodSync(join(bin, 'docker'), 0o755)
    process.env.FAKE_DOCKER_LOG = join(pids, 'docker.log')
    // A wrapper that stays the parent of the real process, as mise's shim does.
    writeFileSync(join(bin, 'claude'), `#!/bin/sh\n"${process.execPath}" "$(dirname "$0")/fake-claude.cjs" "$@"\n`)
    chmodSync(join(bin, 'claude'), 0o755)
    process.env.PATH = `${bin}:${path}`
    process.env.FAKE_PIDS = pids
  })

  afterAll(() => {
    process.env.PATH = path
    rmSync(bin, { recursive: true, force: true })
    rmSync(pids, { recursive: true, force: true })
  })

  it('streams the work log to a long-poller and ends with the answer and usage', async () => {
    const { last, log } = await follow(startAiJob(ask('ok', { label: 'Group files' })))
    expect(last).toMatchObject({ status: 'done', result: 'Hello', label: 'Group files', usage: { costUsd: 0.5, inputTokens: 1, outputTokens: 2, durationMs: 10 } })
    expect(log.get('start')).toMatchObject({ title: 'Started Claude (fake-model)', status: 'done' })
    expect(log.get('text:1:0')).toMatchObject({ kind: 'text', title: 'Wrote', text: 'Hello', status: 'done' })
    expect((await waitAiJob(last.id, last.rev)).activities).toEqual([])
    expect(listAiJobs().find(job => job.id === last.id)).toMatchObject({ status: 'done', activities: [] })
  })

  it('says how to sign in when the CLI is not logged in', async () => {
    const { last } = await follow(startAiJob(ask('fail')))
    expect(last).toMatchObject({ status: 'failed', error: 'Not logged in · Please run /login Run `claude` once on the server to sign in.' })
  })

  it('joins a running job with the same key, and cancelling kills the whole process group', async () => {
    const id = startAiJob(ask('slow', { key: 'pr:1' }))
    expect(startAiJob(ask('slow', { key: 'pr:1' }))).toBe(id)
    await until(() => existsSync(join(pids, 'slow')))
    const pid = Number(readFileSync(join(pids, 'slow'), 'utf8'))
    expect(alive(pid)).toBe(true)

    expect(cancelAiJob(id)).toBe(true)
    expect(cancelAiJob(id)).toBe(false)
    expect(await waitAiJob(id, 0)).toMatchObject({ status: 'cancelled', error: 'Cancelled.' })
    await until(() => !alive(pid))
    expect(startAiJob(ask('slow', { key: 'pr:1' }))).not.toBe(id)
    listAiJobs().filter(job => job.status === 'running').forEach(job => cancelAiJob(job.id))
  })

  it('runs in-process with live activity, and an abort cancels it', async () => {
    const seen: string[] = []
    const result = await runAiJob(ask('ok'), { onActivity: entries => seen.push(...entries.map(entry => `${entry.id}:${entry.status}`)) })
    expect(result).toMatchObject({ text: 'Hello', usage: { costUsd: 0.5 } })
    expect(seen).toContain('start:done')
    expect(seen.at(-1)).toBe('text:1:0:done')

    await expect(runAiJob(ask('fail'))).rejects.toThrow('Run `claude` once on the server to sign in.')
    await expect(runAiJob(ask('slow'), { signal: AbortSignal.abort() })).rejects.toThrow('Cancelled.')
  })

  describe('in the agents image', () => {
    const calls = (): string[][] => existsSync(process.env.FAKE_DOCKER_LOG!) ? readFileSync(process.env.FAKE_DOCKER_LOG!, 'utf8').trim().split('\n').map(line => JSON.parse(line)) : []
    const runs = () => calls().filter(call => call[0] === 'run')
    const valueAfter = (call: string[], flag: string) => call[call.indexOf(flag) + 1]!

    beforeAll(() => {
      process.env.PULLS_REVIEW_AGENT_RUNTIME = 'docker'
    })
    afterEach(() => rmSync(process.env.FAKE_DOCKER_LOG!, { force: true }))
    afterAll(() => {
      delete process.env.PULLS_REVIEW_AGENT_RUNTIME
    })

    it('runs the CLI through docker in the job folder, and names the setup script to sign in', async () => {
      const { last } = await follow(startAiJob(ask('ok')))
      expect(last).toMatchObject({ status: 'done', result: 'Hello' })
      const [run] = runs()
      const dir = valueAfter(run!, '--workdir')
      expect(dir).toContain('pulls-review-ai-')
      expect(run).toEqual(expect.arrayContaining(['--rm', '-i', '--init', `type=bind,source=${dir},target=${dir}`, 'type=volume,source=pulls-review-agents-home,target=/home/agent']))
      expect(run!.slice(run!.indexOf('pulls-review-agents'), run!.indexOf('pulls-review-agents') + 3)).toEqual(['pulls-review-agents', 'claude', '-p'])

      expect((await follow(startAiJob(ask('fail')))).last.error).toBe('Not logged in · Please run /login Run `docker/agents/setup.sh login-claude` on the server to sign in.')
    })

    it('gives the tool server the image\'s node, its own code and the checkout, read-only', async () => {
      const headSha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
      const [top, common] = execFileSync('git', ['rev-parse', '--path-format=absolute', '--show-toplevel', '--git-common-dir'], { encoding: 'utf8' }).trim().split('\n')
      await follow(startAiJob(ask('ok', { context: { owner: 'o', repo: 'r', number: 1, headSha, files: [] } })))
      const [run] = runs()
      const server = JSON.parse(valueAfter(run!, '--mcp-config')).mcpServers.pr
      expect(server.command).toBe('node')
      const script = server.args[0] as string
      const dist = script.slice(0, script.lastIndexOf('/'))
      expect(run).toContain(`type=bind,source=${top},target=${top},readonly`)
      if (!common!.startsWith(`${top}/`))
        expect(run).toContain(`type=bind,source=${common},target=${common},readonly`)
      if (!dist.startsWith(`${top}/`))
        expect(run).toContain(`type=bind,source=${dist},target=${dist},readonly`)
    })

    it('stops the container as well as the docker client', async () => {
      rmSync(join(pids, 'slow'), { force: true })
      const id = startAiJob(ask('slow'))
      await until(() => existsSync(join(pids, 'slow')))
      const pid = Number(readFileSync(join(pids, 'slow'), 'utf8'))
      const name = valueAfter(runs()[0]!, '--name')
      expect(name).toMatch(/^pulls-agent-/)

      expect(cancelAiJob(id)).toBe(true)
      await until(() => calls().some(call => call[0] === 'kill' && call.at(-1) === name))
      expect(calls().find(call => call[0] === 'kill')).toEqual(['kill', '--signal', 'SIGTERM', name])
      await until(() => !alive(pid))
    })
  })

  it('says so when it no longer knows a job', async () => {
    await expect(waitAiJob('nope', 0)).rejects.toThrow('That job is gone: the server restarted or dropped it.')
  })
})
