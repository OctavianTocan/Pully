import type { LlmSettings } from '@pulls.review/core/analyze'
import type { Locale } from '@pulls.review/core/locales'
import { defaultLlmSettings, deriveProvider, serverEngine } from '@pulls.review/core/analyze'
import { detectLocale, isLocale } from '@pulls.review/core/locales'
import { useLocalStorage } from '@vueuse/core'

export interface Settings {
  githubToken: string
  llm: LlmSettings
  /** UI language and the language the LLM writes summaries in; seeded from the browser's preference. */
  locale: Locale
  sounds: boolean
  soundMaterial: 'default' | 'mech' | 'bubble' | 'press'
  haptics: boolean
  motion: boolean
  appFont: 'geist' | 'mona-sans' | 'system' | 'serif'
  codeFont: 'geist-mono' | 'jetbrains-mono' | 'fira-code' | 'source-code-pro' | 'ibm-plex-mono' | 'roboto-mono' | 'system'
  codeZoom: number
  codeWrap: boolean
  colorTheme: string
}

export const defaultSettings: Settings = {
  githubToken: '',
  // `mergeDefaults` never runs for a browser with nothing stored, so the local build's provider is set here too.
  llm: import.meta.env.PR_LOCAL ? { ...defaultLlmSettings, provider: 'claude-code' } : defaultLlmSettings,
  locale: detectLocale(),
  sounds: true,
  soundMaterial: 'default',
  haptics: true,
  motion: true,
  appFont: 'geist',
  codeFont: 'geist-mono',
  codeZoom: 1,
  codeWrap: false,
  colorTheme: 'pulls',
}

/**
 * Every setting lives under one localStorage key - needs synchronous access before a
 * provider call, so localStorage over unstorage/IndexedDB. A plain module-level
 * singleton (like `state/dark.ts`'s `isDark`) - every caller shares the same value.
 */
export const settings = useLocalStorage<Settings>('diffs:settings', defaultSettings, {
  mergeDefaults: (storage, defaults) => {
    const llm: LlmSettings = { ...defaults.llm, ...storage?.llm }
    // Settings stored before `provider` existed picked a provider by key priority;
    // seed the explicit selection the same way so existing setups keep working.
    if (!storage?.llm?.provider)
      llm.provider = deriveProvider(llm)
    // The local build has no API keys; whatever a browser stored from the site falls back to Claude Code.
    if (import.meta.env.PR_LOCAL)
      llm.provider = serverEngine(llm.provider)
    // A locale this build no longer ships (or none stored yet) falls back to the browser's.
    const locale = isLocale(storage?.locale) ? storage.locale : defaults.locale
    return { ...defaults, ...storage, llm, locale }
  },
})
