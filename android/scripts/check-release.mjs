import { execFileSync } from 'node:child_process'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { checkChangelog, compareVersions, parseChangelog, versionName } from './design/changelog.mjs'

const GRADLE = 'android/app/build.gradle.kts'
const CHANGELOG = 'android/CHANGELOG.md'

/** Inputs bundled in the APK, including the contracts collected from every package. */
export function shipsInApk(path) {
  return /(?:^|\/)CONTRACTS$/.test(path)
    || /^android\/(?:app|core)\/src\/(?!(?:test|androidTest|testFixtures)\/)/.test(path)
    || /^android\/(?:app\/|core\/)?(?:build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradle\.lockfile)$/.test(path)
    || /^android\/(?:gradle\/|gradlew(?:\.bat)?$|CHANGELOG\.md$|changelog\/|app\/(?:debug\.keystore$|libs\/|[^/]+\.pro$))/.test(path)
}

function versionCode(gradle) {
  const code = /^\s*versionCode\s*=\s*(\d+)\s*$/m.exec(gradle)?.[1]
  return code ? Number(code) : undefined
}

/** Release changes must be installable and describe the current release, rather than editing old notes. */
export function checkRelease({ beforeGradle, afterGradle, beforeChangelog, afterChangelog, changedFiles, sizeOf }) {
  const problems = []
  if (changedFiles.includes('android/app/debug.keystore'))
    problems.push('The pinned Android signing key MUST NOT change: installed apps would reject updates.')
  if (!changedFiles.some(shipsInApk))
    return problems

  const [before, after] = [versionCode(beforeGradle), versionCode(afterGradle)]
  if (!Number.isSafeInteger(before) || !Number.isSafeInteger(after) || after <= 0 || after > 2100000000 || after <= before)
    problems.push(`Android changes need a versionCode higher than the base (${before ?? 'missing'}); found ${after ?? 'missing'} in ${GRADLE}.`)
  const name = versionName(afterGradle)
  const oldName = versionName(beforeGradle)
  if (name && oldName && compareVersions(name, oldName) < 0)
    problems.push(`versionName MUST NOT go backwards from ${oldName} to ${name}.`)
  problems.push(...checkChangelog(afterChangelog, name, sizeOf))
  const current = parseChangelog(afterChangelog)[0]
  if (current?.version !== name)
    problems.push(`The newest changelog section MUST be ${name}.`)
  const previous = parseChangelog(beforeChangelog).find(entry => entry.version === name)
  if (JSON.stringify(current) === JSON.stringify(previous))
    problems.push('The current release notes MUST change with the Android build. Describe what changed under the newest heading.')
  return problems
}

const git = args => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

export function checkRevisions(base, head = 'HEAD') {
  if (!base || /^0+$/.test(base))
    throw new Error('Pass --base <commit>: refusing to skip the Android release comparison without a valid base.')
  const before = git(['rev-parse', '--verify', `${base}^{commit}`]).trim()
  const after = git(['rev-parse', '--verify', `${head}^{commit}`]).trim()
  const changedFiles = git(['diff', '--no-renames', '--name-only', '-z', before, after]).split('\0').filter(Boolean)
  const read = (ref, path) => git(['show', `${ref}:${path}`])
  return checkRelease({
    beforeGradle: read(before, GRADLE),
    afterGradle: read(after, GRADLE),
    beforeChangelog: read(before, CHANGELOG),
    afterChangelog: read(after, CHANGELOG),
    changedFiles,
    sizeOf: (path) => {
      try {
        return Number(git(['cat-file', '-s', `${after}:android/${path}`]).trim())
      }
      catch {
        return undefined
      }
    },
  })
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2)
    const base = args[args.indexOf('--base') + 1]
    const head = args.includes('--head') ? args[args.indexOf('--head') + 1] : 'HEAD'
    const problems = checkRevisions(args.includes('--base') ? base : undefined, head)
    for (const problem of problems)
      console.error(problem)
    if (problems.length)
      process.exitCode = 1
    else
      console.log('Android release prerequisites ok')
  }
  catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
