import type { Api, Model } from '@earendil-works/pi-ai'
import type { LlmSettings } from './settings'
import { ENGINE_SETTINGS } from './settings'

export interface ResolvedModel {
  model: Model<Api>
  apiKey: string
  /** Reasoning effort for the CLI engines; unset leaves it to the CLI. */
  effort?: string
}

function customModel(id: string, api: Api, provider: string, baseUrl: string): Model<Api> {
  return {
    id,
    name: id,
    api,
    provider,
    baseUrl,
    reasoning: false,
    input: ['text'],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 16_000,
  }
}

/**
 * Resolves the model to call: only the explicitly selected provider is used,
 * even when several tokens are configured. `undefined` means the selected
 * API provider has no token; the CLI providers need none.
 */
export function resolveModel(llm: LlmSettings): ResolvedModel | undefined {
  switch (llm.provider) {
    case 'claude-code':
      return { model: customModel(llm.claudeCodeModel, 'anthropic-messages', 'claude-code', ''), apiKey: '', effort: llm.claudeCodeEffort || undefined }

    case 'codex':
      return { model: customModel(llm.codexModel, 'openai-completions', 'codex', ''), apiKey: '', effort: llm.codexEffort || undefined }

    // The server holds the endpoint and its key; the page only names the model.
    case 'pi':
      return { model: customModel(llm.piModel, 'openai-completions', 'pi', ''), apiKey: '', effort: llm.piEffort || undefined }

    case 'gateway':
      return llm.gatewayToken
        ? { model: customModel(llm.gatewayModel, 'anthropic-messages', 'vercel-ai-gateway', 'https://ai-gateway.vercel.sh'), apiKey: llm.gatewayToken }
        : undefined

    case 'anthropic':
      return llm.anthropicApiKey
        ? { model: customModel(llm.anthropicModel, 'anthropic-messages', 'anthropic', 'https://api.anthropic.com'), apiKey: llm.anthropicApiKey }
        : undefined

    case 'openai-compatible':
      return llm.openaiApiKey
        ? { model: customModel(llm.openaiModel, 'openai-completions', 'openai-compatible', llm.openaiBaseUrl), apiKey: llm.openaiApiKey }
        : undefined
  }
}

/** The model that answers questions about the PR: the ask engine when one is set, else the analysis model. */
export function resolveAskModel(llm: LlmSettings): ResolvedModel | undefined {
  if (!llm.askProvider)
    return resolveModel(llm)
  const model = llm.askModel || llm[ENGINE_SETTINGS[llm.askProvider].model]
  const api = llm.askProvider === 'claude-code' ? 'anthropic-messages' : 'openai-completions'
  return { model: customModel(model, api, llm.askProvider, ''), apiKey: '', effort: llm.askEffort || undefined }
}
