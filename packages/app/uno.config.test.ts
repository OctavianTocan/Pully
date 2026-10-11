import { createGenerator } from 'unocss'
import { describe, expect, it } from 'vitest'
import { createUnoConfig } from './uno.config'

const HOVER_MEDIA = '@media (hover: hover) and (pointer: fine)'

async function css(tokens: string) {
  const uno = await createGenerator(createUnoConfig())
  return (await uno.generate(tokens, { preflights: false })).css
}

/** Every rule whose selector has `:hover`, paired with whether it sits inside HOVER_MEDIA. */
function hoverRules(css: string) {
  const output = css.replace(/\/\*[^*]*\*\//g, '')
  const rules: { selector: string, gated: boolean }[] = []
  const stack: string[] = []
  let start = 0
  for (let i = 0; i < output.length; i++) {
    if (output[i] === '{') {
      stack.push(output.slice(start, i).trim())
      start = i + 1
    }
    else if (output[i] === '}') {
      stack.pop()
      start = i + 1
    }
    else if (output[i] === ';') {
      const selector = stack.at(-1)
      if (selector?.includes(':hover') && !rules.some(rule => rule.selector === selector))
        rules.push({ selector, gated: stack.includes(HOVER_MEDIA) })
    }
  }
  return rules
}

describe('uno config', () => {
  it('only applies :hover on hover-capable fine pointers', async () => {
    const output = await css([
      'hover:op-80',
      'group-hover:op-100',
      'sm:hover:bg-red-500',
      'coarse:hover:op-50',
      '[&_a:hover]:underline',
      'btn',
      'icon-btn',
      'btn-icon',
      'btn-action',
      'btn-primary',
      'btn-text',
      'chat-markdown',
    ].join(' '))

    const rules = hoverRules(output)
    expect(rules.length).toBeGreaterThanOrEqual(12)
    expect(rules.filter(rule => !rule.gated)).toEqual([])
  })

  it('leaves non-hover rules ungated', async () => {
    const output = await css('op-80 active:op-50 focus-visible:ring-2')
    expect(output).not.toContain(HOVER_MEDIA)
  })
})
