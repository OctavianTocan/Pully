import type { AiJobRequest } from '@pulls.review/core/local-rpc'
import type { AgentLaunch } from './runtime'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => ({
  mkdtemp: vi.fn(),
  rm: vi.fn(),
  startAgent: vi.fn(),
}))

vi.mock('node:fs/promises', () => ({
  mkdtemp: fake.mkdtemp,
  rm: fake.rm,
  readFile: vi.fn(async () => ''),
  writeFile: vi.fn(async () => {}),
}))
vi.mock('./runtime', () => ({
  AGENT_HOME: '/home/agent',
  agentRuntime: () => ({ kind: 'host' }),
  mcpNode: () => 'node',
  startAgent: fake.startAgent,
}))
vi.mock('./catalog', () => ({ listCliModels: vi.fn() }))

const ask: AiJobRequest = { engine: 'codex', model: 'test', system: 'S', prompt: 'Q' }

function processDouble() {
  let resolve!: (code: number) => void
  const exited = new Promise<number>(done => resolve = done)
  const kill = vi.fn(() => resolve(1))
  return {
    process: { child: { stdin: { end: vi.fn() } }, exited, kill, stderr: () => '' },
    resolve,
    kill,
  }
}

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  fake.mkdtemp.mockReset().mockResolvedValue('/tmp/fake-job')
  fake.rm.mockReset().mockResolvedValue(undefined)
  fake.startAgent.mockReset()
})
afterEach(() => vi.useRealTimers())

describe('job execution boundaries', () => {
  it('disables shell and unified execution for host Codex jobs', async () => {
    const proc = processDouble()
    fake.startAgent.mockReturnValue(proc.process)
    const jobs = await import('./jobs')
    const id = jobs.startAiJob(ask)
    await vi.advanceTimersByTimeAsync(0)
    const launch = fake.startAgent.mock.calls[0]![0] as AgentLaunch
    expect(launch.args).toEqual(expect.arrayContaining(['features.shell_tool=false', 'features.unified_exec=false', 'features.view_image=false']))
    proc.resolve(1)
    await vi.advanceTimersByTimeAsync(0)
    expect((await jobs.waitAiJob(id, 0)).status).toBe('failed')
  })

  it.each(['ENOSPC', 'EACCES'])('settles a %s directory-allocation failure and releases its slot to queued jobs', async (code) => {
    let rejectAllocation!: (reason: Error) => void
    fake.mkdtemp.mockImplementationOnce(() => new Promise((_resolve, reject) => rejectAllocation = reject))
    const processes = [processDouble(), processDouble(), processDouble()]
    fake.startAgent.mockImplementation(() => processes[fake.startAgent.mock.calls.length - 1]!.process)
    const jobs = await import('./jobs')
    const ids = Array.from({ length: 4 }, () => jobs.startAiJob(ask))
    await vi.advanceTimersByTimeAsync(0)
    expect(fake.mkdtemp).toHaveBeenCalledTimes(3)
    rejectAllocation(new Error(`${code}: cannot create job directory`))
    await vi.advanceTimersByTimeAsync(0)
    expect(await jobs.waitAiJob(ids[0]!, 0)).toMatchObject({ status: 'failed', error: `${code}: cannot create job directory` })
    expect(fake.startAgent).toHaveBeenCalledTimes(3)
    expect(fake.mkdtemp).toHaveBeenCalledTimes(4)
    expect(fake.rm).not.toHaveBeenCalled()
    processes.forEach(proc => proc.resolve(1))
    await vi.advanceTimersByTimeAsync(0)
    expect((await jobs.waitAiJob(ids[3]!, 0)).status).toBe('failed')
    expect(fake.rm).toHaveBeenCalledTimes(3)
  })

  it('grants a queued job a full execution budget after waiting more than thirty minutes', async () => {
    const processes = Array.from({ length: 4 }, () => processDouble())
    fake.startAgent.mockImplementation(() => processes[fake.startAgent.mock.calls.length - 1]!.process)
    const jobs = await import('./jobs')
    const ids = Array.from({ length: 4 }, () => jobs.startAiJob(ask))
    await vi.advanceTimersByTimeAsync(0)
    const queuedAt = Date.now()
    expect(fake.startAgent).toHaveBeenCalledTimes(3)
    vi.setSystemTime(queuedAt + 31 * 60_000)
    processes[0]!.resolve(1)
    await vi.advanceTimersByTimeAsync(0)
    expect(fake.startAgent).toHaveBeenCalledTimes(4)
    await vi.advanceTimersByTimeAsync(10_000)
    expect((await jobs.waitAiJob(ids[3]!, 0)).status).toBe('running')
    expect(processes[3]!.kill).not.toHaveBeenCalled()
    // Keep output fresh so this tests the total budget rather than the idle watchdog.
    const options = fake.startAgent.mock.calls[3]![1] as { onLine: (line: string) => void }
    vi.setSystemTime(queuedAt + 60 * 60_000)
    options.onLine('{}')
    await vi.advanceTimersByTimeAsync(10_000)
    expect((await jobs.waitAiJob(ids[3]!, 0)).status).toBe('running')
    vi.setSystemTime(queuedAt + 62 * 60_000)
    options.onLine('{}')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(await jobs.waitAiJob(ids[3]!, 0)).toMatchObject({ status: 'failed', error: 'Codex did not finish within 30m.' })
  })
})
