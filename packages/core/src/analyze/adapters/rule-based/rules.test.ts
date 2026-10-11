import { describe, expect, it } from 'vitest'
import { DiffCategorySchema } from '../../../types/analyze'
import { defaultRules, GROUP_ORDER, matchRule } from './rules'

function ruleKey(path: string, isBinary = false): string {
  return matchRule({ path, isBinary }).key
}

describe('matchRule', () => {
  it.each([
    // tests
    ['src/foo.test.ts', 'tests'],
    ['src/__tests__/foo.ts', 'tests'],
    ['test/foo.ts', 'tests'],
    ['src/Button.stories.ts', 'tests'],
    ['pkg/server_test.go', 'tests'],
    ['tests/test_api.py', 'tests'],
    ['src/__snapshots__/foo.test.ts.snap', 'tests'],
    // A test under scripts/ is still a test.
    ['scripts/build.test.ts', 'tests'],
    // docs
    ['README.md', 'docs'],
    ['docs/guide.mdx', 'docs'],
    ['CHANGELOG.md', 'docs'],
    ['LICENSE', 'docs'],
    // Markdown under .github/ is docs, not CI or config.
    ['.github/PULL_REQUEST_TEMPLATE.md', 'docs'],
    // deps
    ['package.json', 'deps'],
    ['packages/app/package.json', 'deps'],
    ['pyproject.toml', 'deps'],
    // A manifest under scripts/ is a dependency change.
    ['scripts/package.json', 'deps'],
    // generated - lockfiles are machine-generated, not a dependency manifest a reviewer edits.
    ['pnpm-lock.yaml', 'generated'],
    ['dist/bundle.js', 'generated'],
    ['schema.generated.ts', 'generated'],
    ['public/vendor.min.js', 'generated'],
    // ci
    ['.github/workflows/ci.yml', 'ci'],
    ['.github/actions/setup/action.yml', 'ci'],
    ['action.yml', 'ci'],
    ['.gitlab-ci.yml', 'ci'],
    ['Jenkinsfile', 'ci'],
    // config
    ['.eslintrc.json', 'config'],
    ['.github/dependabot.yml', 'config'],
    ['tsconfig.json', 'config'],
    ['Dockerfile', 'config'],
    ['vite.config.ts', 'config'],
    ['packages/app/vite.config.ts', 'config'],
    ['packages/app/.env.example', 'config'],
    ['scripts/tsconfig.json', 'config'],
    ['.vscode/settings.json', 'config'],
    // styles
    ['src/styles/main.css', 'styles'],
    ['src/theme.scss', 'styles'],
    ['packages/app/uno.config.ts', 'styles'],
    ['tailwind.config.js', 'styles'],
    // i18n
    ['src/i18n/locales/de.json', 'i18n'],
    ['locales/fr.yml', 'i18n'],
    ['po/messages.po', 'i18n'],
    // data
    ['db/migrations/0001_init.sql', 'data'],
    ['prisma/schema.prisma', 'data'],
    ['src/server/migrations/20260101_add_users.ts', 'data'],
    // types
    ['src/env.d.ts', 'types'],
    ['types/global.d.mts', 'types'],
    // assets (text SVGs included)
    ['public/logo.svg', 'assets'],
    ['src/assets/font.woff2', 'assets'],
    // scripts
    ['scripts/release.ts', 'scripts'],
    ['packages/app/scripts/build-userscript.ts', 'scripts'],
    ['bin/setup.sh', 'scripts'],
    // code
    ['src/index.ts', 'code'],
    ['src/components/Button.vue', 'code'],
    ['src/locale.ts', 'code'],
    ['src/weird-file.xyz', 'code'],
  ])('files %s under the %s rule', (path, expected) => {
    expect(ruleKey(path)).toBe(expected)
  })

  it('files binaries as assets when they look like one, else other - whatever their path', () => {
    expect(ruleKey('docs/logo.png', true)).toBe('assets')
    expect(ruleKey('src/icons/app.icns', true)).toBe('assets')
    expect(ruleKey('docs/archive.zip', true)).toBe('other')
    expect(ruleKey('src/data.sqlite', true)).toBe('other')
  })
})

describe('defaultRules', () => {
  it('has one rule per key, every one of them in GROUP_ORDER exactly once', () => {
    const keys = defaultRules.map(rule => rule.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect([...GROUP_ORDER].sort()).toEqual([...keys].sort())
  })

  it('only uses known categories', () => {
    for (const rule of defaultRules)
      expect(DiffCategorySchema.options).toContain(rule.category)
  })

  it('shows code first and generated/other last', () => {
    expect(GROUP_ORDER[0]).toBe('code')
    expect(GROUP_ORDER.slice(-2)).toEqual(['generated', 'other'])
    expect(GROUP_ORDER.indexOf('tests')).toBeLessThan(GROUP_ORDER.indexOf('config'))
    expect(GROUP_ORDER.indexOf('config')).toBeLessThan(GROUP_ORDER.indexOf('docs'))
  })
})
