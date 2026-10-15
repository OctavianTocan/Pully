import type { LlmEngine } from '../../../local-rpc'

/**
 * `claude-code` and `codex` run the server's signed-in CLIs, and `pi` the server's own agent loop against the endpoint
 * it is set up with; the rest call an API with a key from the browser.
 */
export const LLM_PROVIDERS = ['claude-code', 'codex', 'pi', 'gateway', 'anthropic', 'openai-compatible'] as const
export type LlmProvider = typeof LLM_PROVIDERS[number]

export function isLlmProvider(value: string): value is LlmProvider {
  return (LLM_PROVIDERS as readonly string[]).includes(value)
}

/**
 * The `llm` analyze adapter's model access. `provider` picks which credentials
 * and model to use - only the selected provider is ever called, even when
 * several tokens are configured.
 */
export interface LlmSettings {
  provider: LlmProvider
  claudeCodeModel: string
  /** A reasoning effort the model reports it accepts; `''` leaves it to the CLI. */
  claudeCodeEffort: string
  codexModel: string
  codexEffort: string
  /** A model the server's endpoint lists; `''` runs the one the server picks. */
  piModel: string
  piEffort: string
  /** The engine that answers questions about the PR; `''` reuses the analysis model and effort. */
  askProvider: '' | 'claude-code' | 'codex' | 'pi'
  askModel: string
  askEffort: string
  gatewayToken: string
  gatewayModel: string
  anthropicApiKey: string
  anthropicModel: string
  openaiApiKey: string
  openaiBaseUrl: string
  openaiModel: string
}

export const defaultLlmSettings: LlmSettings = {
  provider: 'gateway',
  claudeCodeModel: 'claude-sonnet-5',
  claudeCodeEffort: '',
  codexModel: 'gpt-5.6-sol',
  codexEffort: '',
  piModel: '',
  piEffort: '',
  askProvider: '',
  askModel: '',
  askEffort: '',
  gatewayToken: '',
  gatewayModel: 'anthropic/claude-sonnet-5',
  anthropicApiKey: '',
  anthropicModel: 'claude-sonnet-5',
  openaiApiKey: '',
  openaiBaseUrl: 'https://api.openai.com/v1',
  openaiModel: 'gpt-5.1',
}

/** Picks the provider from whichever credential is set, by priority (gateway > anthropic > openai-compatible). */
export function deriveProvider(llm: Partial<LlmSettings>): LlmProvider {
  if (llm.gatewayToken)
    return 'gateway'
  if (llm.anthropicApiKey)
    return 'anthropic'
  if (llm.openaiApiKey)
    return 'openai-compatible'
  return 'gateway'
}

/** The settings that hold each server engine's model and effort. */
export const ENGINE_SETTINGS = {
  'claude-code': { model: 'claudeCodeModel', effort: 'claudeCodeEffort' },
  'codex': { model: 'codexModel', effort: 'codexEffort' },
  'pi': { model: 'piModel', effort: 'piEffort' },
} as const satisfies Record<Exclude<LlmSettings['askProvider'], ''>, { model: keyof LlmSettings, effort: keyof LlmSettings }>

/** The server engine a provider stands for: itself when it is one, else Claude Code (the local build has no keys). */
export function serverEngine(provider: LlmProvider): LlmEngine {
  return provider === 'codex' || provider === 'pi' ? provider : 'claude-code'
}
