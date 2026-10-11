import { describe, expect, it } from 'vitest'
import { areaOf, compareNames, comparePaths, pairTests, testSubjectKeys } from './order'

describe('compareNames', () => {
  it('orders numbers naturally and ignores case, with a stable case tie-break', () => {
    expect(['10-b.ts', '2-a.ts', '1-c.ts'].sort(compareNames)).toEqual(['1-c.ts', '2-a.ts', '10-b.ts'])
    expect(['b.ts', 'A.ts', 'a.ts'].sort(compareNames)).toEqual(['A.ts', 'a.ts', 'b.ts'])
  })
})

describe('comparePaths', () => {
  it('puts a directory\'s own files before its subdirectories, and entry files first', () => {
    const paths = [
      'src/utils/z.ts',
      'src/b.ts',
      'src/index.ts',
      'src/a.ts',
      'README.md',
      'src/utils/index.ts',
      'src/main.ts',
    ]
    expect(paths.sort(comparePaths)).toEqual([
      'README.md',
      'src/index.ts',
      'src/main.ts',
      'src/a.ts',
      'src/b.ts',
      'src/utils/index.ts',
      'src/utils/z.ts',
    ])
  })

  it('only treats a plain entry file as one, not a test or snapshot named after it', () => {
    expect(['t/default.snap.js', 't/lib.snap.js', 't/index.test.ts'].sort(comparePaths)).toEqual(['t/default.snap.js', 't/index.test.ts', 't/lib.snap.js'])
    expect(['pkg/b.py', 'pkg/__init__.py'].sort(comparePaths)).toEqual(['pkg/__init__.py', 'pkg/b.py'])
  })

  it('is a total order: any input order sorts the same way', () => {
    const paths = ['b/x.ts', 'a/index.ts', 'a/B.ts', 'a/b.ts', 'a/c/d.ts', 'z.ts', 'index.ts']
    const sorted = [...paths].sort(comparePaths)
    expect([...paths].reverse().sort(comparePaths)).toEqual(sorted)
    expect(sorted).toEqual(['index.ts', 'z.ts', 'a/index.ts', 'a/B.ts', 'a/b.ts', 'a/c/d.ts', 'b/x.ts'])
  })
})

describe('testSubjectKeys', () => {
  it('looks next to a co-located test, most specific name first', () => {
    expect(testSubjectKeys('src/foo.test.ts')).toEqual(['src/foo'])
    expect(testSubjectKeys('src/user.service.spec.ts')).toEqual(['src/user.service', 'src/user'])
    expect(testSubjectKeys('pkg/server_test.go')).toEqual(['pkg/server'])
  })

  it('maps a test directory onto the sibling source tree', () => {
    expect(testSubjectKeys('packages/reactivity/__tests__/computed.spec.ts')).toEqual([
      'packages/reactivity/__tests__/computed',
      'packages/reactivity/computed',
      'packages/reactivity/src/computed',
      'packages/reactivity/lib/computed',
    ])
    expect(testSubjectKeys('tests/test_api.py')).toContain('src/api')
  })

  it('has no subject for files that aren\'t a test of one source file', () => {
    expect(testSubjectKeys('src/fixtures.json')).toEqual([])
  })
})

describe('pairTests', () => {
  it('pairs co-located and test-directory tests with their source, leaving the rest unpaired', () => {
    const pairs = pairTests(
      ['src/foo.ts', 'src/bar.ts', 'packages/reactivity/src/computed.ts'],
      ['src/foo.test.ts', 'src/foo.spec.ts', 'packages/reactivity/__tests__/computed.spec.ts', 'src/gc.test.ts'],
    )
    expect(Object.fromEntries(pairs)).toEqual({
      'src/foo.ts': ['src/foo.spec.ts', 'src/foo.test.ts'],
      'packages/reactivity/src/computed.ts': ['packages/reactivity/__tests__/computed.spec.ts'],
    })
  })

  it('picks the same source for a test whatever order the inputs come in', () => {
    const a = pairTests(['src/foo.vue', 'src/foo.ts'], ['src/foo.test.ts'])
    const b = pairTests(['src/foo.ts', 'src/foo.vue'], ['src/foo.test.ts'])
    expect(Object.fromEntries(a)).toEqual({ 'src/foo.ts': ['src/foo.test.ts'] })
    expect(Object.fromEntries(b)).toEqual(Object.fromEntries(a))
  })
})

describe('areaOf', () => {
  it.each([
    ['packages/app/src/main.ts', 'packages/app'],
    ['apps/web/index.ts', 'apps/web'],
    ['src/components/Button.vue', 'src/components'],
    ['src/index.ts', 'src'],
    ['server/routes.ts', 'server'],
    ['index.ts', ''],
  ])('puts %s in area %s', (path, area) => {
    expect(areaOf(path)).toBe(area)
  })
})
