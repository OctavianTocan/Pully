import type { Directive } from 'vue'

/** Phone-keyboard hints for one text field. */
export interface FieldHints {
  /** The label on the keyboard's return key. */
  enterkeyhint?: 'enter' | 'done' | 'go' | 'next' | 'previous' | 'search' | 'send'
  inputmode?: 'none' | 'text' | 'decimal' | 'numeric' | 'tel' | 'search' | 'email' | 'url'
  autocapitalize?: 'none' | 'off' | 'sentences' | 'words' | 'characters'
  autocorrect?: 'on' | 'off'
  spellcheck?: boolean
  autocomplete?: string
}

/** Presets for the fields the app has. */
export const FIELD = {
  /** A prompt or chat message: the return key reads "send". */
  message: { enterkeyhint: 'send' },
  /** A filter or search box. */
  search: { enterkeyhint: 'search', autocapitalize: 'none', autocorrect: 'off', spellcheck: false },
  /** A URL, branch, ref or model id: no capitals, no autocorrect. */
  identifier: { enterkeyhint: 'go', autocapitalize: 'none', autocorrect: 'off', spellcheck: false },
  /** An API key or token. */
  secret: { enterkeyhint: 'done', autocapitalize: 'none', autocorrect: 'off', spellcheck: false, autocomplete: 'off' },
} as const satisfies Record<string, FieldHints>

function fieldOf(el: HTMLElement): HTMLElement | null {
  return el.matches('input, textarea') ? el : el.querySelector('input, textarea')
}

function apply(el: HTMLElement, hints: FieldHints) {
  const field = fieldOf(el)
  if (!field)
    return
  for (const [name, value] of Object.entries(hints)) {
    if (value !== undefined)
      field.setAttribute(name, String(value))
  }
}

/**
 * `v-field="FIELD.search"` sets keyboard hints on a text field. `@antfu/design`'s
 * `FormTextInput` passes attributes to its `<label>`, not the `<input>` inside, so a plain
 * `enterkeyhint` attribute on it would never reach the field; this finds the field.
 */
export const vField: Directive<HTMLElement, FieldHints> = {
  mounted: (el, { value }) => apply(el, value),
  updated: (el, { value }) => apply(el, value),
}
