import type { AiActivity } from '@pulls.review/core/local-rpc'

/** Looks up a UI message, the shape of vue-i18n's `t` that these helpers need. */
export type Translate = (key: string, named?: Record<string, unknown>, plural?: number) => string

/** Effort ids the UI has a proper name for; anything else shows its capitalised id. */
export const NAMED_EFFORTS = ['minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'] as const

/**
 * A compact wall-clock duration: `45s`, `3m 12s`, `1h 5m`, `2d 3h`.
 * @param ms - The duration in milliseconds; negative values read as zero.
 * @returns The two most significant units (one below a minute).
 */
export function formatClockDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(total / 86_400)
  const hours = Math.floor(total % 86_400 / 3600)
  const minutes = Math.floor(total % 3600 / 60)
  const seconds = total % 60
  if (days)
    return `${days}d ${hours}h`
  if (hours)
    return `${hours}h ${minutes}m`
  if (minutes)
    return `${minutes}m ${seconds}s`
  return `${seconds}s`
}

function trimZero(value: string): string {
  return value.endsWith('.0') ? value.slice(0, -2) : value
}

/**
 * A token count at a glance: `950`, `1.5k`, `200k`, `1.2m`.
 * @param count - The number of tokens.
 * @returns At most one decimal, none from three significant digits up.
 */
export function formatTokens(count: number): string {
  if (count < 1000)
    return String(Math.round(count))
  if (count < 999_500) {
    const thousands = count / 1000
    return `${trimZero(thousands.toFixed(thousands < 100 ? 1 : 0))}k`
  }
  const millions = count / 1_000_000
  return `${trimZero(millions.toFixed(millions < 100 ? 1 : 0))}m`
}

/**
 * A dollar amount with enough decimals to show sub-cent runs: `$0.0042`, `$1.27`.
 * @param usd - The cost in US dollars.
 * @returns The amount with 2 to 6 decimals, more the smaller it is.
 */
export function formatCost(usd: number): string {
  const digits = usd < 0.0001 ? 6 : usd < 0.001 ? 5 : usd < 0.01 ? 4 : usd < 0.1 ? 3 : 2
  return `$${usd.toFixed(digits)}`
}

/**
 * The display name of a reasoning effort.
 * @param id - The effort as the CLI reports it, e.g. `xhigh`.
 * @param translate - Looks up `ai.effort.<id>` for the named efforts.
 * @returns e.g. `Extra High`, or the capitalised id for efforts the UI doesn't know.
 */
export function effortLabel(id: string, translate: Translate): string {
  if ((NAMED_EFFORTS as readonly string[]).includes(id))
    return translate(`ai.effort.${id}`)
  return id.charAt(0).toUpperCase() + id.slice(1)
}

/**
 * The newest line of streamed reasoning or prose, fit for a one-line preview.
 * @param text - Everything streamed so far.
 * @returns Its last non-empty line without Markdown heading, emphasis or code marks; `''` when there is none.
 */
export function lastLine(text: string): string {
  const lines = text.replace(/<!--[\s\S]*?-->/g, '').split('\n')
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]!.replace(/^\s*#+\s*/, '').replace(/\*\*|__|`/g, '').trim()
    if (line)
      return line
  }
  return ''
}

/**
 * The i18n key of a verb phrase in the tense that fits a step's state.
 * @param verb - The phrase under `ai.verb`, e.g. `thinking`.
 * @param status - The step's state; only `running` is present tense.
 * @returns `ai.verb.<verb>.now` while running, else `ai.verb.<verb>.past`.
 */
export function verbKey(verb: string, status: AiActivity['status']): string {
  return `ai.verb.${verb}.${status === 'running' ? 'now' : 'past'}`
}

/**
 * A click handler for Markdown the AI wrote: it links what it mentions to a path in the repository (rule 96), and
 * such a link is not a page of this app, so it must not navigate away.
 * @param event - The click on the rendered Markdown.
 */
export function keepRepoLinks(event: MouseEvent): void {
  const href = (event.target as HTMLElement | null)?.closest('a')?.getAttribute('href')
  // `key.ts:7` starts like a scheme but is a place.
  if (href && !/^(?:[a-z][a-z\d+.-]*:(?!\d)|#|\/\/)/i.test(href))
    event.preventDefault()
}
