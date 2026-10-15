// android/CHANGELOG.md is what the app shows after an update and what the publish step puts in
// latest.json. The versionName in app/build.gradle.kts must have a section there with a summary and
// at least one bullet, and the sections run newest first, so a pull request that bumps the version
// cannot ship without saying what changed. The app parses the same file (core Changelog.kt).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { ANDROID } from './kotlin.mjs'

export const NAME = 'changelog'
export const PATH = join(ANDROID, 'CHANGELOG.md')
const GRADLE = join(ANDROID, 'app', 'build.gradle.kts')

/** The update notification and the changelog headlines show the summary in two short lines. */
const SUMMARY_MAX = 70

const HEADING = /^##\s+(\S+)\s*$/
const BULLET = /^\s*[-*]\s+(\S.*)$/

/** The sections of a changelog in file order: `{ version, summary, items }`. Comments and the `#` title are skipped. */
export function parseChangelog(text) {
  const entries = []
  let entry = null
  for (const line of text.replace(/<!--[\s\S]*?-->/g, '').split('\n')) {
    const heading = HEADING.exec(line)
    if (heading) {
      entry = { version: heading[1], summary: '', items: [] }
      entries.push(entry)
      continue
    }
    if (!entry || !line.trim() || line.startsWith('#'))
      continue
    const bullet = BULLET.exec(line)
    if (bullet)
      entry.items.push(bullet[1].trim())
    else if (entry.items.length > 0 && /^\s/.test(line))
      entry.items[entry.items.length - 1] += ` ${line.trim()}`
    else if (entry.items.length === 0)
      entry.summary = `${entry.summary} ${line.trim()}`.trim()
  }
  return entries
}

/** Compares version names part by part as numbers, as the app does: `0.10.0` is above `0.9.2`. */
export function compareVersions(a, b) {
  const parts = v => v.split('.').map(p => Number.parseInt(p, 10) || 0)
  const [left, right] = [parts(a), parts(b)]
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const diff = (left[i] ?? 0) - (right[i] ?? 0)
    if (diff !== 0)
      return Math.sign(diff)
  }
  return 0
}

/** The `versionName` an app module's build script sets. */
export function versionName(gradle) {
  return /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1]
}

/** Problems with a changelog for an app at `version`. */
export function checkChangelog(text, version) {
  const entries = parseChangelog(text)
  const problems = []
  if (!version)
    return ['app/build.gradle.kts has no versionName']
  if (!entries.some(e => e.version === version)) {
    problems.push(`CHANGELOG.md has no section for ${version}, the versionName in app/build.gradle.kts. Add "## ${version}" at the top with a one-sentence summary and a bullet for each change, in plain words for the person using the app`)
  }
  const seen = new Set()
  entries.forEach((entry, i) => {
    if (seen.has(entry.version))
      problems.push(`CHANGELOG.md lists ${entry.version} twice`)
    seen.add(entry.version)
    if (compareVersions(entry.version, version) > 0)
      problems.push(`CHANGELOG.md has a section for ${entry.version}, above the app's ${version}`)
    if (i > 0 && compareVersions(entries[i - 1].version, entry.version) <= 0)
      problems.push(`CHANGELOG.md: ${entry.version} comes after ${entries[i - 1].version}. Newest first`)
    if (!entry.summary)
      problems.push(`CHANGELOG.md: ${entry.version} needs a summary sentence under its heading, before the bullets`)
    else if (entry.summary.length > SUMMARY_MAX)
      problems.push(`CHANGELOG.md: ${entry.version}'s summary is ${entry.summary.length} characters. Keep it to ${SUMMARY_MAX}: the notification shows two short lines of it, and the bullets carry the rest`)
    if (entry.items.length === 0)
      problems.push(`CHANGELOG.md: ${entry.version} needs at least one bullet saying what changed`)
  })
  return problems
}

export function check() {
  const problems = checkChangelog(readFileSync(PATH, 'utf8'), versionName(readFileSync(GRADLE, 'utf8')))
  return { name: NAME, problems, written: [] }
}
