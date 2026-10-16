import { LOCAL_RPC } from '@pulls.review/core/local-rpc'
import { describe, expect, it, vi } from 'vitest'
import { createRpcCredentials, createRpcDriver } from './rpc-backends'

it('delegates GitHub requests without retrieving server tokens', async () => {
  const call = vi.fn(async (method: string) => method === LOCAL_RPC.githubAuthenticated
    ? true
    : {
        status: 200,
        body: '{"login":"tavi"}',
        headers: { 'x-oauth-scopes': 'repo' },
      })
  const credentials = createRpcCredentials({ call } as any)
  expect(await credentials.githubToken()).toBeUndefined()
  expect(await credentials.githubAuthenticated!()).toBe(true)
  expect(await (await credentials.githubRequest!('/user')).json()).toEqual({ login: 'tavi' })
  expect(call.mock.calls.map(([method]) => method)).toEqual([LOCAL_RPC.githubAuthenticated, LOCAL_RPC.githubRequest])
})

describe('optimistic cache writes', () => {
  it('sends the exact last observed value and updates it only after success', async () => {
    const call = vi.fn().mockResolvedValueOnce('old').mockRejectedValueOnce(new Error('changed')).mockResolvedValueOnce(undefined)
    const driver = createRpcDriver({ call } as any)
    await driver.getItem('key', {})
    await expect(driver.setItem!('key', 'new', {})).rejects.toThrow('changed')
    await driver.setItem!('key', 'retry', {})
    expect(call.mock.calls[1]).toEqual([LOCAL_RPC.storageSetItem, { key: 'key', value: 'new', expected: 'old' }])
    expect(call.mock.calls[2]).toEqual([LOCAL_RPC.storageSetItem, { key: 'key', value: 'retry', expected: 'old' }])
  })
})
