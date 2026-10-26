// android/CHANGELOG.md is what the app shows after an update and what the publish step puts in
// latest.json. The versionName in app/build.gradle.kts must have a section there with a summary and
// at least one bullet, and the sections run newest first, so a pull request that bumps the version
// cannot ship without saying what changed. The app parses the same file (core Changelog.kt).
// Since 0.40.0 every section also shows what changed in pictures, or GIFs for what moves (Tavi,
// 16 Oct 10:49): `![what it shows](changelog/<version>/<name>.png)` lines, unindented after a blank
// line, with the files in android/changelog/<version>/, which the app bundles and the server serves.
// Since 26 Oct 2026 every section also says when it was released (Tavi, 26 Oct 13:28: "add a date and
// time to each changelog entry from now on"): `#### 2026-10-26 14:30 UTC` right under the heading. Builds
// from before then skip any `#` line that is not `## ` or `### `, so the line hides from them.
import { existsSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { ANDROID } from './kotlin.mjs'

export const NAME = 'changelog'
export const PATH = join(ANDROID, 'CHANGELOG.md')
const GRADLE = join(ANDROID, 'app', 'build.gradle.kts')

/** The update notification and the changelog headlines show the summary in two short lines. */
const SUMMARY_MAX = 70

/** The first version whose section must carry a picture. */
export const PICTURES_FROM = '0.40.0'

/** A picture or GIF may weigh this much, since the app bundles every one and the update screen downloads it. */
const PICTURE_MAX_BYTES = 2 * 1024 * 1024

const HEADING = /^##\s+(\S+)\s*$/
const BULLET = /^\s*[-*]\s+(\S.*)$/
const PICTURE = /^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$/
const RELEASED = /^#### (.*)$/
const STAMP = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}) UTC$/

/** A release line's time in milliseconds since 1970, or undefined when it is not a real `YYYY-MM-DD HH:MM UTC`. */
export function releasedAt(stamp) {
  const parts = STAMP.exec(stamp ?? '')
  if (!parts)
    return undefined
  const [year, month, day, hour, minute] = parts.slice(1).map(Number)
  const time = Date.UTC(year, month - 1, day, hour, minute)
  // Date.UTC rolls 31 Feb over into March; a stamp that does not come back the same is not a real time.
  return new Date(time).toISOString().slice(0, 16) === `${parts[1]}-${parts[2]}-${parts[3]}T${parts[4]}:${parts[5]}` ? time : undefined
}

/** Now as a release line wants it, for the message that asks for one. */
const nowStamp = () => `${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC`

/** Takes a section's `#### ` release line into it, and says whether `line` was one. Before any section it is not. */
function readReleased(entry, line) {
  const released = RELEASED.exec(line)
  if (!entry || !released)
    return false
  entry.releasedLines++
  if (entry.releasedLines === 1) {
    entry.released = released[1].trim()
    entry.releasedPlaced = !entry.summary && entry.items.length === 0 && entry.pictures.length === 0
  }
  return true
}

/**
 * The sections of a changelog in file order: `{ version, released, summary, items, pictures }`, each picture `{ alt, path, placed }`.
 * `released` is the text of the section's `#### ` line, `releasedPlaced` whether it came before the summary, and
 * `releasedLines` how many such lines the section has.
 * `placed` is whether the picture line is unindented and after a bullet, the only place a build from before 0.40.0
 * skips it: indented it joins the bullet's text, and above the bullets it joins the summary.
 * Comments and the `#` title are skipped.
 */
export function parseChangelog(text) {
  const entries = []
  let entry = null
  for (const line of text.replace(/<!--[\s\S]*?-->/g, '').split('\n')) {
    const heading = HEADING.exec(line)
    if (heading) {
      entry = { version: heading[1], released: undefined, releasedPlaced: false, releasedLines: 0, summary: '', items: [], pictures: [] }
      entries.push(entry)
      continue
    }
    if (readReleased(entry, line))
      continue
    if (!entry || !line.trim() || line.startsWith('#'))
      continue
    const picture = PICTURE.exec(line)
    if (picture) {
      entry.pictures.push({ alt: picture[1], path: picture[2], placed: entry.items.length > 0 && !/^\s/.test(line) })
      continue
    }
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

/** The size in bytes of a file under android/, or undefined when there is none. */
function pictureSize(path) {
  const file = join(ANDROID, path)
  return existsSync(file) ? statSync(file).size : undefined
}

/** What is wrong with one section's pictures: where each is, its words, and whether it is there and small. */
function pictureProblems(entry, sizeOf) {
  const problems = []
  const where = new RegExp(`^changelog/${entry.version.replace(/\./g, '\\.')}/[a-z0-9-]+\\.(png|webp|gif|jpg)$`)
  for (const picture of entry.pictures) {
    if (!where.test(picture.path)) {
      problems.push(`CHANGELOG.md: ${entry.version}'s picture ${picture.path} must be changelog/${entry.version}/<lower-case-name>.png, .webp, .gif or .jpg`)
      continue
    }
    if (!picture.placed)
      problems.push(`CHANGELOG.md: ${picture.path} must sit on its own unindented line under a bullet, or phones from before 0.40.0 show it as text`)
    if (!picture.alt.trim())
      problems.push(`CHANGELOG.md: ${picture.path} needs words in its [ ] saying what it shows`)
    const size = sizeOf(picture.path)
    if (size === undefined)
      problems.push(`CHANGELOG.md: ${picture.path} is not in android/`)
    else if (size > PICTURE_MAX_BYTES)
      problems.push(`CHANGELOG.md: ${picture.path} is ${Math.round(size / 1024)} KB. Keep a picture under ${PICTURE_MAX_BYTES / 1024 / 1024} MB: crop it or make the GIF shorter`)
  }
  return problems
}

/** What is wrong with one section's release line: there, where it belongs, once, and a real time. */
function releasedProblems(entry) {
  if (entry.released === undefined)
    return [`CHANGELOG.md: ${entry.version} needs the date and time it was released, in UTC, on the line under its heading: "#### ${nowStamp()}" (date -u '+%Y-%m-%d %H:%M UTC')`]
  const problems = []
  if (releasedAt(entry.released) === undefined)
    problems.push(`CHANGELOG.md: ${entry.version}'s release line "#### ${entry.released}" must be a real date and time in UTC, written "#### YYYY-MM-DD HH:MM UTC"`)
  if (!entry.releasedPlaced)
    problems.push(`CHANGELOG.md: ${entry.version}'s release line must come right under its heading, before the summary`)
  if (entry.releasedLines > 1)
    problems.push(`CHANGELOG.md: ${entry.version} has ${entry.releasedLines} release lines. Keep one`)
  return problems
}

/** Problems with a changelog for an app at `version`. `sizeOf` gives a picture's size in bytes, or undefined when it is missing. */
export function checkChangelog(text, version, sizeOf = pictureSize) {
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
    problems.push(...releasedProblems(entry))
    const [newer, older] = [releasedAt(entries[i - 1]?.released), releasedAt(entry.released)]
    if (newer !== undefined && older !== undefined && newer < older)
      problems.push(`CHANGELOG.md: ${entry.version} was released at ${entry.released}, after ${entries[i - 1].version} above it at ${entries[i - 1].released}. Newest first`)
    if (!entry.summary)
      problems.push(`CHANGELOG.md: ${entry.version} needs a summary sentence under its heading, before the bullets`)
    else if (entry.summary.length > SUMMARY_MAX)
      problems.push(`CHANGELOG.md: ${entry.version}'s summary is ${entry.summary.length} characters. Keep it to ${SUMMARY_MAX}: the notification shows two short lines of it, and the bullets carry the rest`)
    if (entry.items.length === 0)
      problems.push(`CHANGELOG.md: ${entry.version} needs at least one bullet saying what changed`)
    if (compareVersions(entry.version, PICTURES_FROM) >= 0 && entry.pictures.length === 0)
      problems.push(`CHANGELOG.md: ${entry.version} needs at least one picture of what changed (a GIF for what moves): add "![what it shows](changelog/${entry.version}/<name>.png)" on its own line under the bullet`)
    problems.push(...pictureProblems(entry, sizeOf))
  })
  return problems
}

export function check() {
  const problems = checkChangelog(readFileSync(PATH, 'utf8'), versionName(readFileSync(GRADLE, 'utf8')))
  return { name: NAME, problems, written: [] }
}
