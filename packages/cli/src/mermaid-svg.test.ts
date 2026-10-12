import { describe, expect, it } from 'vitest'
import { flattenMermaidSvg } from './native-api'

describe('flattenMermaidSvg', () => {
  it('resolves theme variables and color-mix to plain colours', async () => {
    const { renderMermaidSVG } = await import('beautiful-mermaid')
    const svg = flattenMermaidSvg(renderMermaidSVG('flowchart TD\n  A[Save] --> B{Cached?}\n  B -->|no| C[Parse]', { bg: '#111111', fg: '#E5E5E5', line: '#737373', accent: '#5B9544', muted: '#A3A3A3', surface: '#1A1A1A', border: '#3A3A3A', font: 'sans-serif', transparent: true }), true)
    expect(svg).not.toMatch(/var\(--/)
    expect(svg).not.toContain('color-mix')
    expect(svg).not.toContain('@import')
    expect(svg).not.toContain('display=')
    expect(svg).toContain('#5B9544')
    expect(svg).toContain('Cached?')
  })
})
