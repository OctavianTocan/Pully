import type { BundledLanguage } from 'shiki'
import { createRequire } from 'node:module'
import { createHighlighter } from 'shiki'

const require = createRequire(import.meta.url)
const pierreDark = require('@pierre/theme/themes/pierre-dark.json')
const pierreLight = require('@pierre/theme/themes/pierre-light.json')

const LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript',
  tsx: 'tsx',
  mts: 'typescript',
  cts: 'typescript',
  js: 'javascript',
  jsx: 'jsx',
  mjs: 'javascript',
  cjs: 'javascript',
  json: 'json',
  css: 'css',
  scss: 'scss',
  html: 'html',
  vue: 'vue',
  md: 'markdown',
  py: 'python',
  kt: 'kotlin',
  kts: 'kotlin',
  go: 'go',
  rs: 'rust',
  sh: 'bash',
  yml: 'yaml',
  yaml: 'yaml',
  java: 'java',
  xml: 'xml',
}

const LANGS = [...new Set(Object.values(LANG_BY_EXT))]

let highlighter: ReturnType<typeof createHighlighter> | undefined

function highlighterOnce() {
  return highlighter ??= createHighlighter({ themes: [pierreDark, pierreLight], langs: LANGS })
}

export interface HighlightTheme {
  background: string
  foreground: string
  added: string
  removed: string
  gutter: string
}

/** Pierre's own theme, the one the web diff uses when themeType is dark or light. */
export function pierreTheme(dark: boolean): HighlightTheme {
  const colors = (dark ? pierreDark : pierreLight).colors as Record<string, string>
  return {
    background: colors['editor.background'] ?? '',
    foreground: colors['editor.foreground'] ?? '',
    added: colors['diffEditor.insertedTextBackground'] ?? '',
    removed: colors['diffEditor.deletedTextBackground'] ?? '',
    gutter: colors['editorLineNumber.foreground'] ?? '',
  }
}

/** Shiki tokens for one file, colored with pierre-dark or pierre-light. */
export async function highlightLines(path: string, lines: string[], dark: boolean): Promise<{ text: string, color: string }[][]> {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  const lang = LANG_BY_EXT[ext] as BundledLanguage | undefined
  if (!lang)
    return lines.map(line => [{ text: line, color: '' }])
  const engine = await highlighterOnce()
  const theme = dark ? 'pierre-dark' : 'pierre-light'
  return lines.map((line) => {
    try {
      const { tokens } = engine.codeToTokens(line || ' ', { lang, theme })
      return (tokens[0] ?? []).map(token => ({ text: token.content, color: token.color ?? '' }))
    }
    catch {
      return [{ text: line, color: '' }]
    }
  })
}
