/**
 * Deterministic review order for the rule-based adapter: how paths sort inside a
 * group, which test covers which source file, and which area of the repo a source
 * file belongs to. Everything here depends on paths alone - never on the order the
 * diff listed them in - so the same set of files always comes out the same way.
 */

/** File stems read first in their directory: the entry point explains the rest. */
const ENTRY_STEMS = new Set(['index', 'main', 'mod', 'lib', '__init__', 'entry'])

/** Directories that hold tests for the sibling source tree (`pkg/__tests__/x` covers `pkg/src/x`). */
const TEST_DIRS = new Set(['__tests__', 'test', 'tests', 'spec', 'e2e'])

/** Where a test directory's subjects usually live, relative to the test directory's parent. */
const SOURCE_DIRS = ['', 'src', 'lib']

/** Top-level folders whose children are separate packages: `packages/<name>/` is one area. */
const MONOREPO_ROOTS = new Set(['packages', 'apps', 'libs', 'services', 'crates', 'modules', 'plugins', 'extensions'])

/** Source roots too generic to name an area on their own: `src/components/` is the area, not `src/`. */
const SOURCE_ROOTS = new Set(['src', 'lib', 'app', 'source', 'internal', 'pkg'])

const CHUNK_RE = /\d+|\D+/g
const DIGITS_RE = /^\d/

function splitPath(path: string): { dirs: string[], name: string } {
  const segments = path.split('/')
  return { dirs: segments.slice(0, -1), name: segments.at(-1)! }
}

function stemOf(name: string): string {
  const dot = name.indexOf('.', 1)
  return dot === -1 ? name : name.slice(0, dot)
}

/** `name` without its last extension (`user.service.ts` -> `user.service`). */
function withoutExtension(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

/**
 * Natural, case-insensitive name order (`2-x` before `10-x`), with a plain code-unit
 * tie-break so names differing only in case still order the same everywhere -
 * no `localeCompare`, whose result depends on the runtime's collation data.
 */
export function compareNames(a: string, b: string): number {
  const ac = a.toLowerCase().match(CHUNK_RE) ?? []
  const bc = b.toLowerCase().match(CHUNK_RE) ?? []
  for (let i = 0; i < Math.min(ac.length, bc.length); i++) {
    const x = ac[i]!
    const y = bc[i]!
    if (x === y)
      continue
    if (DIGITS_RE.test(x) && DIGITS_RE.test(y)) {
      const diff = Number(x) - Number(y)
      if (diff !== 0)
        return diff
    }
    return x < y ? -1 : 1
  }
  if (ac.length !== bc.length)
    return ac.length - bc.length
  return a === b ? 0 : a < b ? -1 : 1
}

/** `index.ts`, `main.go`, `__init__.py` - but not `index.test.ts` or `lib.snap.js`. */
function entryRank(name: string): number {
  const stem = stemOf(name)
  return ENTRY_STEMS.has(stem.toLowerCase()) && withoutExtension(name) === stem ? 0 : 1
}

/**
 * Directory-aware path order: directories compare segment by segment; within one
 * directory its own files come before its subdirectories, entry files (`index.*`,
 * `main.*`, ...) come first, and the rest go by name.
 */
export function comparePaths(a: string, b: string): number {
  const pa = splitPath(a)
  const pb = splitPath(b)
  const shared = Math.min(pa.dirs.length, pb.dirs.length)
  for (let i = 0; i < shared; i++) {
    if (pa.dirs[i] !== pb.dirs[i])
      return compareNames(pa.dirs[i]!, pb.dirs[i]!)
  }
  if (pa.dirs.length !== pb.dirs.length)
    return pa.dirs.length - pb.dirs.length
  return entryRank(pa.name) - entryRank(pb.name) || compareNames(pa.name, pb.name)
}

/** The lookup key a source file is found by when pairing tests: `dir/name-without-extension`. */
export function sourceKey(path: string): string {
  const { dirs, name } = splitPath(path)
  return [...dirs, withoutExtension(name)].join('/')
}

const TEST_NAME_PATTERNS = [
  /^(.+)\.(?:test|spec|stories|story)\.[^.]+$/,
  /^(.+)_test\.[^.]+$/,
  /^test_(.+)\.py$/,
]

/** The names a test's subject may have, most specific first (`a.b.test.ts` -> `a.b`, `a`). */
function subjectStems(name: string): string[] | undefined {
  for (const pattern of TEST_NAME_PATTERNS) {
    const stem = pattern.exec(name)?.[1]
    if (stem) {
      const parts = stem.split('.')
      return parts.map((_, i) => parts.slice(0, parts.length - i).join('.'))
    }
  }
  return undefined
}

/**
 * Where the source file a test covers would be, as `sourceKey`s in order of
 * preference: next to the test (`foo.test.ts` -> `foo.*`), or - for a test under a
 * test directory - in the matching place of the sibling source tree
 * (`pkg/__tests__/foo.spec.ts` -> `pkg/foo.*`, `pkg/src/foo.*`, `pkg/lib/foo.*`).
 * Empty for files that don't look like a test of one source file (snapshots, fixtures).
 */
export function testSubjectKeys(path: string): string[] {
  const { dirs, name } = splitPath(path)
  const testDirIndex = dirs.findLastIndex(dir => TEST_DIRS.has(dir))
  const stems = subjectStems(name) ?? (testDirIndex === -1 ? undefined : [withoutExtension(name)])
  if (!stems)
    return []

  const candidateDirs = [dirs]
  if (testDirIndex !== -1) {
    const parent = dirs.slice(0, testDirIndex)
    const rest = dirs.slice(testDirIndex + 1)
    for (const sourceDir of SOURCE_DIRS)
      candidateDirs.push([...parent, ...(sourceDir ? [sourceDir] : []), ...rest])
  }

  const keys = candidateDirs.flatMap(candidate => stems.map(stem => [...candidate, stem].join('/')))
  return [...new Set(keys)]
}

/**
 * Pairs tests with the source files they cover. Every test is matched against
 * `sources` by `testSubjectKeys` order; when several sources share a key
 * (`foo.ts` and `foo.vue`) the first in `comparePaths` order wins. Returns
 * source path -> its tests, sorted; unmatched tests are simply absent.
 */
export function pairTests(sources: string[], tests: string[]): Map<string, string[]> {
  const byKey = new Map<string, string>()
  for (const source of [...sources].sort(comparePaths)) {
    const key = sourceKey(source)
    if (!byKey.has(key))
      byKey.set(key, source)
  }

  const pairs = new Map<string, string[]>()
  for (const test of [...tests].sort(comparePaths)) {
    const subject = testSubjectKeys(test).map(key => byKey.get(key)).find(Boolean)
    if (subject)
      pairs.set(subject, [...(pairs.get(subject) ?? []), test])
  }
  return pairs
}

/**
 * The top-level area of the repo a path belongs to, for splitting a large code
 * group: a monorepo package (`packages/<name>`, `apps/<name>`, ...), a directory under
 * a generic source root (`src/<dir>`, `lib/<dir>`, ...), else the first directory;
 * `''` for a file at the repo root.
 */
export function areaOf(path: string): string {
  const { dirs } = splitPath(path)
  const first = dirs[0]
  if (first === undefined)
    return ''
  if (dirs.length >= 2 && (MONOREPO_ROOTS.has(first) || SOURCE_ROOTS.has(first)))
    return `${first}/${dirs[1]}`
  return first
}
