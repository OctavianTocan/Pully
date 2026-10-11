import type { DiffCategory } from '../../../types/analyze'
import type { FileChange } from '../../../types/diff'
import picomatch from 'picomatch'

export type RuleKey
  = | 'code'
    | 'types'
    | 'data'
    | 'styles'
    | 'tests'
    | 'scripts'
    | 'ci'
    | 'config'
    | 'deps'
    | 'i18n'
    | 'docs'
    | 'assets'
    | 'generated'
    | 'other'

/**
 * What the adapter measured about one group, handed to the caller's `text` so it can
 * word a deterministic label and summary in the current language.
 */
export interface RuleGroupFacts {
  /** Files in the group, including tests placed after their source. */
  files: number
  additions: number
  deletions: number
  /** Files that are new, deleted or renamed in this diff. */
  added: number
  removed: number
  renamed: number
  /** Test files placed in this code group, right after the source file they cover. */
  pairedTests: number
  /**
   * Set only when a large code group was split by area: the area this group covers
   * (`packages/app`, `src/components`, ...), or `''` for files at the repo root.
   */
  area?: string
  /** Set on the split code group that collects the areas too small for their own group. */
  otherAreas?: true
}

/**
 * The words a rule's group shows. Supplied by the caller (so the UI can translate
 * them) and resolved when the adapter runs, so the text follows the current language.
 * `summary` stands in for an LLM summary: deterministic text built from
 * `RuleGroupFacts`, so the UI always has something to show above a group's file tree.
 */
export interface RuleText {
  label: string
  summary: string
}

export interface GroupRule {
  key: RuleKey
  category: DiffCategory
  patterns?: string[]
}

/**
 * Generated/lockfile-style output - shared with `noisy-files.ts`, which collapses
 * these by default in the diff view, and with the llm prompt and critique, so the
 * "this file is noise" notions can't drift apart.
 */
export const GENERATED_PATTERNS = [
  '**/*.generated.*',
  '**/pnpm-lock.yaml',
  '**/yarn.lock',
  '**/package-lock.json',
  '**/Cargo.lock',
  '**/go.sum',
  '**/dist/**',
  '**/*.lock',
]

/** Images, fonts and media, by extension - binary or not (an SVG is text). */
export const ASSET_PATTERNS = [
  '**/*.{png,jpg,jpeg,gif,webp,avif,bmp,ico,icns,svg,tif,tiff}',
  '**/*.{woff,woff2,ttf,otf,eot}',
  '**/*.{mp3,mp4,wav,ogg,webm,mov}',
]

/** Fallback for text files no pattern matches. */
export const codeRule: GroupRule = {
  key: 'code',
  category: 'core',
}

/** Binary files that aren't recognizable assets (archives, databases, ...). */
export const otherRule: GroupRule = {
  key: 'other',
  category: 'other',
}

/** The only path rule a binary file is checked against; any other binary is `other`. */
export const assetsRule: GroupRule = {
  key: 'assets',
  category: 'assets',
  patterns: ASSET_PATTERNS,
}

/**
 * Evaluated in order, first match wins. Patterns match dotfiles too, so `**` also
 * walks into `.github/` and the like. This is precedence, not display order (see
 * `GROUP_ORDER`): the most telling signal comes first.
 *
 * - Generated output and assets are what they are wherever they live.
 * - Docs come before the path rules, so Markdown under `.github/` or `scripts/` is docs.
 * - Tests come before the remaining path rules, so `scripts/foo.test.ts` is a test.
 * - CI comes before config, which would otherwise swallow `.github/workflows/`.
 * - Dependency manifests come before scripts, so `scripts/package.json` is a deps change.
 * - Styles come before config, so `uno.config.ts`/`tailwind.config.js` read as styling.
 * - Config comes before scripts, so `scripts/tsconfig.json` is config.
 *
 * `codeRule` and `otherRule` have no patterns and are only reached as fallbacks.
 */
export const defaultRules: GroupRule[] = [
  {
    key: 'generated',
    category: 'other',
    patterns: [
      ...GENERATED_PATTERNS,
      '**/*.min.{js,mjs,cjs,css}',
      '**/*.map',
    ],
  },
  assetsRule,
  {
    key: 'docs',
    category: 'docs',
    patterns: [
      '**/*.{md,mdx,mdc,rst,adoc}',
      '**/docs/**',
      '**/README*',
      '**/CHANGELOG*',
      '**/LICENSE*',
    ],
  },
  {
    key: 'tests',
    category: 'tests',
    patterns: [
      '**/*.{test,spec,stories,story}.*',
      '**/*_test.*',
      '**/test_*.py',
      '**/__tests__/**',
      '**/__snapshots__/**',
      '**/__mocks__/**',
      '**/__fixtures__/**',
      '**/test/**',
      '**/tests/**',
      '**/e2e/**',
    ],
  },
  {
    key: 'ci',
    category: 'build',
    patterns: [
      '.github/workflows/**',
      '.github/actions/**',
      'action.{yml,yaml}',
      '**/.gitlab-ci.yml',
      '.gitlab/ci/**',
      '.circleci/**',
      '.buildkite/**',
      '.woodpecker/**',
      '**/.travis.yml',
      '**/.drone.yml',
      '**/azure-pipelines*.yml',
      '**/bitbucket-pipelines.yml',
      '**/Jenkinsfile',
    ],
  },
  // Manifests declare deps; their lockfiles are machine-generated output, not
  // something a reviewer edits by hand - see GENERATED_PATTERNS above.
  {
    key: 'deps',
    category: 'deps',
    patterns: [
      '**/package.json',
      '**/pnpm-workspace.yaml',
      '**/Cargo.toml',
      '**/go.mod',
      '**/pyproject.toml',
      '**/requirements*.txt',
      '**/Pipfile',
      '**/Gemfile',
      '**/composer.json',
    ],
  },
  {
    key: 'data',
    category: 'data',
    patterns: [
      '**/migrations/**',
      '**/migration/**',
      '**/alembic/**',
      '**/drizzle/**',
      '**/*.sql',
      '**/*.prisma',
      '**/db/schema.*',
    ],
  },
  {
    key: 'i18n',
    category: 'i18n',
    patterns: [
      '**/{locales,locale,lang,langs,i18n,l10n,translations}/**/*.{json,json5,yml,yaml,toml,po,pot,ftl,properties,xlf,xliff,arb,strings}',
      '**/*.{po,pot,ftl,xlf,xliff,arb}',
    ],
  },
  {
    key: 'styles',
    category: 'ui',
    patterns: [
      '**/*.{css,scss,sass,less,styl,stylus,pcss,postcss}',
      '**/{uno,unocss,tailwind,windi,postcss}.config.*',
    ],
  },
  {
    key: 'types',
    category: 'api',
    patterns: [
      '**/*.d.{ts,mts,cts}',
    ],
  },
  {
    key: 'config',
    category: 'config',
    patterns: [
      '**/*.config.*',
      '**/.*rc',
      '**/.*rc.*',
      '**/.env*',
      '**/.editorconfig',
      '**/.git*',
      '.github/**',
      '.vscode/**',
      '.husky/**',
      '**/tsconfig*.json',
      '**/jsconfig*.json',
      '**/Dockerfile*',
      '**/*.dockerfile',
      '**/docker-compose*.y*ml',
      '**/compose*.y*ml',
      '**/.dockerignore',
      '**/Makefile',
      '**/{vercel,netlify,turbo,nx,lerna,renovate,biome,deno}.json*',
      '**/{netlify,wrangler,fly,mise}.toml',
      '**/.{nvmrc,node-version,tool-versions}',
    ],
  },
  {
    key: 'scripts',
    category: 'scripts',
    patterns: [
      '**/scripts/**',
      'script/**',
      '**/*.{sh,bash,zsh,fish,ps1,bat,cmd}',
    ],
  },
  codeRule,
  otherRule,
]

/**
 * Display order, most review-relevant first: the change itself (code, then the
 * contracts and data shapes it leans on, then its styling), its tests, then the
 * plumbing around it (scripts, CI, config, dependency manifests), then what's mostly
 * read for completeness (translations, docs, assets), and last what nobody reviews
 * line by line (generated output, unknown binaries).
 */
export const GROUP_ORDER: RuleKey[] = [
  'code',
  'types',
  'data',
  'styles',
  'tests',
  'scripts',
  'ci',
  'config',
  'deps',
  'i18n',
  'docs',
  'assets',
  'generated',
  'other',
]

const matchers = defaultRules.map(rule => ({
  rule,
  isMatch: rule.patterns
    ? picomatch(rule.patterns, { dot: true })
    : () => false,
}))
const isAsset = matchers.find(({ rule }) => rule === assetsRule)!.isMatch

/** Which rule a file falls under: path patterns for text files, assets-or-other for binaries. */
export function matchRule(file: Pick<FileChange, 'path' | 'isBinary'>): GroupRule {
  if (file.isBinary)
    return isAsset(file.path) ? assetsRule : otherRule
  for (const { rule, isMatch } of matchers) {
    if (isMatch(file.path))
      return rule
  }
  return codeRule
}
