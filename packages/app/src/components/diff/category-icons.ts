import type { DiffCategory } from '@pulls.review/core/types'

/** @unocss-include */

export const CATEGORY_ICON: Record<DiffCategory, string> = {
  ui: 'i-ph-compass-tool',
  api: 'i-ph:plugs-connected',
  core: 'i-ph-code',
  data: 'i-ph:database',
  cli: 'i-lu-terminal',
  security: 'i-ph:shield-check',
  tests: 'i-ph:flask',
  docs: 'i-ph:book-open',
  examples: 'i-ph:play-circle',
  deps: 'i-ph:package',
  build: 'i-ph:hammer',
  scripts: 'i-ph:wrench',
  config: 'i-ph:sliders-horizontal',
  i18n: 'i-ph:translate',
  assets: 'i-ph:image',
  other: 'i-ph:dots-three-circle',
}

/** One distinct hue per category so groups can be told apart at a glance. */
export const CATEGORY_COLOR_CLASS: Record<DiffCategory, string> = {
  ui: 'color-pink-800 dark:color-pink-300',
  api: 'color-blue-800 dark:color-blue-300',
  core: 'color-violet-800 dark:color-violet-300',
  data: 'color-amber-800 dark:color-amber-300',
  cli: 'color-lime-800 dark:color-lime-300',
  security: 'color-red-800 dark:color-red-300',
  tests: 'color-green-800 dark:color-green-300',
  docs: 'color-sky-800 dark:color-sky-300',
  examples: 'color-cyan-800 dark:color-cyan-300',
  deps: 'color-orange-800 dark:color-orange-300',
  build: 'color-yellow-800 dark:color-yellow-300',
  scripts: 'color-emerald-800 dark:color-emerald-300',
  config: 'color-teal-800 dark:color-teal-300',
  i18n: 'color-indigo-800 dark:color-indigo-300',
  assets: 'color-fuchsia-800 dark:color-fuchsia-300',
  other: 'color-neutral-800 dark:color-neutral-300',
}
