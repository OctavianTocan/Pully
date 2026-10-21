import assert from 'node:assert/strict'
// The release CI preflight runs before installing workspace dependencies.
// eslint-disable-next-line test/no-import-node-test
import { describe, it } from 'node:test'
import { checkRelease, checkRevisions, shipsInApk } from './check-release.mjs'

const gradle = (code, name = '0.52.0') => `versionCode = ${code}\nversionName = "${name}"\n`
const notes = text => `## 0.52.0\n\n${text}\n\n- Links open in the right place.\n![A link](changelog/0.52.0/link.png)\n`
function fixture(overrides) {
  return {
    beforeGradle: gradle(66),
    afterGradle: gradle(67),
    beforeChangelog: notes('Links open in the right place.'),
    afterChangelog: notes('Links and AI reviews work more clearly.'),
    changedFiles: ['android/app/src/main/kotlin/Screen.kt', 'android/app/build.gradle.kts', 'android/CHANGELOG.md'],
    sizeOf: () => 100,
    ...overrides,
  }
}

describe('Android release prerequisites', () => {
  it('fails closed when the comparison base is missing, invalid or an initial-push zero SHA', () => {
    for (const base of [undefined, '', '0000000000000000000000000000000000000000', 'missing-release-test-ref'])
      assert.throws(() => checkRevisions(base))
  })

  it('rejects runtime changes with the already published version code', () => {
    assert.match(checkRelease(fixture({ afterGradle: gradle(66) })).join('\n'), /versionCode.*higher/)
  })

  it('rejects downgrades and missing or malformed version codes', () => {
    for (const code of ['65', '0', '-1', '2147483648', 'nextVersion'])
      assert.ok(checkRelease(fixture({ afterGradle: gradle(code) })).length > 0)
  })

  it('accepts a higher code with notes for the release, keeping its display version', () => {
    assert.deepEqual(checkRelease(fixture()), [])
  })

  it('rejects a code bump with unchanged current release notes', () => {
    assert.match(checkRelease(fixture({ afterChangelog: notes('Links open in the right place.') })).join('\n'), /release notes.*change/)
  })

  it('requires a matching newest section when versionName changes', () => {
    const afterGradle = gradle(67, '0.53.0')
    assert.match(checkRelease(fixture({ afterGradle })).join('\n'), /no section for 0.53.0/)
    const afterChangelog = `${notes('A new release.').replaceAll('0.52.0', '0.53.0')}\n${notes('Links open in the right place.')}`
    assert.deepEqual(checkRelease(fixture({ afterGradle, afterChangelog })), [])
  })

  it('does not accept changes only to an older version as this release\'s notes', () => {
    const old = '\n## 0.39.0\n\nAn old release.\n\n- An old change.\n'
    const beforeChangelog = notes('Links open in the right place.') + old
    const afterChangelog = beforeChangelog.replace('An old change.', 'An edited old change.')
    assert.match(checkRelease(fixture({ beforeChangelog, afterChangelog })).join('\n'), /current release notes MUST change/)
  })

  it('rejects duplicate version sections and versions out of order', () => {
    const duplicate = notes('A release.') + notes('The same release twice.')
    assert.match(checkRelease(fixture({ afterChangelog: duplicate })).join('\n'), /lists 0.52.0 twice/)
    const outOfOrder = notes('Older first.').replaceAll('0.52.0', '0.51.0') + notes('Newer last.')
    assert.match(checkRelease(fixture({ afterChangelog: outOfOrder })).join('\n'), /Newest first/)
  })

  it('rejects missing or overlong summaries and missing bullets', () => {
    assert.match(checkRelease(fixture({ afterChangelog: notes('') })).join('\n'), /needs a summary/)
    assert.match(checkRelease(fixture({ afterChangelog: notes('x'.repeat(71)) })).join('\n'), /Keep it to 70/)
    assert.match(checkRelease(fixture({ afterChangelog: notes('A release.').replace('- Links open in the right place.\n', '') })).join('\n'), /needs at least one bullet/)
  })

  it('requires actual version-specific pictures with descriptions and bounded sizes', () => {
    assert.match(checkRelease(fixture({ sizeOf: () => undefined })).join('\n'), /not in android/)
    assert.match(checkRelease(fixture({ sizeOf: () => 2 * 1024 * 1024 + 1 })).join('\n'), /Keep a picture under/)
    assert.match(checkRelease(fixture({ afterChangelog: notes('A release.').replace('![A link]', '![]') })).join('\n'), /needs words/)
    assert.match(checkRelease(fixture({ afterChangelog: notes('A release.').replace('changelog/0.52.0/', 'changelog/0.51.0/') })).join('\n'), /must be changelog\/0.52.0/)
  })

  it('rejects missing summaries, bullets, pictures and wrong release headings', () => {
    for (const afterChangelog of ['## 0.52.0\n', '## 0.52.0\n\nA summary.\n', '## 0.51.0\n\nA summary.\n- A change.\n'])
      assert.ok(checkRelease(fixture({ afterChangelog })).length > 0)
  })

  it('covers app, core, resources, build inputs, bundled changelogs and every contract', () => {
    for (const path of [
      'android/app/src/main/AndroidManifest.xml',
      'android/core/src/main/kotlin/Link.kt',
      'android/app/src/debug/Debug.kt',
      'android/core/src/release/Link.kt',
      'android/app/src/main/assets/book/rules.json',
      'android/app/build.gradle.kts',
      'android/gradle/libs.versions.toml',
      'android/settings.gradle.kts',
      'android/gradle.properties',
      'android/CHANGELOG.md',
      'android/changelog/0.52.0/link.png',
      'CONTRACTS',
      'backend/CONTRACTS',
    ])
      assert.equal(shipsInApk(path), true, path)
  })

  it('does not demand an APK for server-only, CI, test or tooling changes', () => {
    const changedFiles = ['backend/src/lib.rs', '.github/workflows/ci.yml', 'android/DESIGN.md', 'android/app/src/test/kotlin/ScreenTest.kt', 'android/scripts/check-release.mjs', 'scripts/update.sh']
    assert.deepEqual(checkRelease(fixture({ changedFiles, afterGradle: gradle(66) })), [])
  })

  it('rejects changing the pinned signing key even with a version bump', () => {
    assert.match(checkRelease(fixture({ changedFiles: ['android/app/debug.keystore'] })).join('\n'), /signing key/)
  })
})
