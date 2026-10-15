import { describe, expect, it } from 'vitest'
import { effortLabel, formatClockDuration, formatCost, formatTokens, keepRepoLinks, lastLine, verbKey } from './ai-format'

describe('formatClockDuration', () => {
  it.each([
    [0, '0s'],
    [-500, '0s'],
    [999, '0s'],
    [45_000, '45s'],
    [60_000, '1m 0s'],
    [192_000, '3m 12s'],
    [3_900_000, '1h 5m'],
    [(2 * 24 + 3) * 3_600_000 + 59_000, '2d 3h'],
  ])('%d ms reads as %s', (ms, expected) => {
    expect(formatClockDuration(ms)).toBe(expected)
  })
})

describe('formatTokens', () => {
  it.each([
    [0, '0'],
    [950, '950'],
    [1000, '1k'],
    [1500, '1.5k'],
    [12_345, '12.3k'],
    [99_960, '100k'],
    [200_000, '200k'],
    [123_456, '123k'],
    [999_499, '999k'],
    [999_500, '1m'],
    [1_234_567, '1.2m'],
    [250_000_000, '250m'],
  ])('%d tokens read as %s', (count, expected) => {
    expect(formatTokens(count)).toBe(expected)
  })
})

describe('formatCost', () => {
  it.each([
    [0.00004, '$0.000040'],
    [0.0004, '$0.00040'],
    [0.0042, '$0.0042'],
    [0.042, '$0.042'],
    [0.1, '$0.10'],
    [1.2749, '$1.27'],
    [12, '$12.00'],
  ])('$%d reads as %s', (usd, expected) => {
    expect(formatCost(usd)).toBe(expected)
  })
})

describe('effortLabel', () => {
  const translate = (key: string) => `<${key}>`

  it('names the efforts the UI knows through i18n', () => {
    expect(effortLabel('xhigh', translate)).toBe('<ai.effort.xhigh>')
    expect(effortLabel('low', translate)).toBe('<ai.effort.low>')
  })

  it('capitalises efforts it does not know', () => {
    expect(effortLabel('turbo', translate)).toBe('Turbo')
  })
})

describe('lastLine', () => {
  it('keeps only the newest non-empty line', () => {
    expect(lastLine('First idea\n\nSecond idea\n  \n')).toBe('Second idea')
  })

  it('drops Markdown heading, emphasis and code marks', () => {
    expect(lastLine('## **Checking** the `parser`')).toBe('Checking the parser')
    expect(lastLine('__Planning__')).toBe('Planning')
  })

  it('ignores HTML comments, even across lines', () => {
    expect(lastLine('Reading the diff\n<!-- hidden\nnote -->')).toBe('Reading the diff')
  })

  it('is empty when there is nothing to show', () => {
    expect(lastLine('')).toBe('')
    expect(lastLine('\n**\n')).toBe('')
  })
})

describe('verbKey', () => {
  it('is present tense only while running', () => {
    expect(verbKey('thinking', 'running')).toBe('ai.verb.thinking.now')
    expect(verbKey('thinking', 'done')).toBe('ai.verb.thinking.past')
    expect(verbKey('thinking', 'failed')).toBe('ai.verb.thinking.past')
  })
})

describe('keepRepoLinks', () => {
  function click(href: string | null) {
    const host = document.createElement('div')
    const words = document.createElement('span')
    if (href === null) {
      host.append(words)
    }
    else {
      const link = document.createElement('a')
      link.setAttribute('href', href)
      link.append(words)
      host.append(link)
    }
    words.addEventListener('click', keepRepoLinks)
    const event = new MouseEvent('click', { cancelable: true })
    words.dispatchEvent(event)
    return event.defaultPrevented
  }

  it('stops a link to a path in the repository and lets the rest go', () => {
    expect(click('src/cache/key.ts#L42')).toBe(true)
    expect(click('key.ts:7')).toBe(true)
    expect(click('https://github.com/o/r/blob/abc/src/a.ts#L1')).toBe(false)
    expect(click('#overview')).toBe(false)
    expect(click(null)).toBe(false)
  })
})
