import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { checkoutOf, NO_CHECKOUT } from './host'

describe('host', () => {
  it('imports nothing from Node, so a hosted server can load it', () => {
    const source = readFileSync(new URL('./host.ts', import.meta.url), 'utf8')
    expect(source).not.toMatch(/from '(?:node:|fs|path|os|child_process)/)
  })

  it('names the checkout, or says there is none', () => {
    expect(checkoutOf({ cwd: '/repo' })).toBe('/repo')
    expect(() => checkoutOf({})).toThrow(NO_CHECKOUT)
  })
})
