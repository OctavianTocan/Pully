import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// The release CI preflight runs before installing workspace dependencies.
// eslint-disable-next-line test/no-import-node-test
import { afterEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const publisher = fileURLToPath(new URL('./publish-apk.sh', import.meta.url))
const temporary = []
afterEach(() => {
  for (const path of temporary.splice(0))
    rmSync(path, { recursive: true, force: true })
})

function fixture({ have = 65 } = {}) {
  const home = mkdtempSync(join(tmpdir(), 'pully-publish-test-'))
  temporary.push(home)
  const repo = join(home, 'repo')
  const android = join(repo, 'android')
  const out = join(home, 'published')
  const sdk = join(home, 'sdk')
  for (const path of [join(android, 'scripts'), join(android, 'app/build/outputs/apk/debug'), out, join(sdk, 'build-tools/37.0.0')])
    mkdirSync(path, { recursive: true })
  copyFileSync(publisher, join(android, 'scripts/publish-apk.sh'))
  writeFileSync(join(android, 'scripts/build-past-ui.py'), '')
  writeFileSync(join(android, 'gradlew'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  writeFileSync(join(android, 'CHANGELOG.md'), '## 0.52.0\n\nLinks work.\n\n- A change.\n')
  writeFileSync(join(android, 'app/build.gradle.kts'), 'versionCode = 66\nversionName = "0.52.0"\n')
  writeFileSync(join(android, 'app/build/outputs/apk/debug/app-debug.apk'), 'new APK bytes')
  writeFileSync(join(android, '.gitignore'), 'app/build/\n')
  writeFileSync(join(sdk, 'build-tools/37.0.0/aapt2'), '#!/bin/sh\necho "package: name=\x27com.octaviantocan.pulls\x27 versionCode=\x2766\x27 versionName=\x270.52.0\x27"\n', { mode: 0o755 })
  const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  git('init', '-b', 'main')
  git('config', 'user.email', 'test@example.com')
  git('config', 'user.name', 'Publisher test')
  git('add', '.')
  git('commit', '-m', 'fixture')
  writeFileSync(join(out, 'latest.json'), JSON.stringify({ versionCode: have, versionName: '0.51.0', sha256: 'previous' }))
  writeFileSync(join(out, 'pulls.apk'), 'previous APK bytes')
  const run = () => spawnSync('/bin/bash', [join(android, 'scripts/publish-apk.sh'), 'https://example.com'], {
    encoding: 'utf8',
    env: { ...process.env, HOME: home, JAVA_HOME: home, ANDROID_HOME: sdk, PULLS_REVIEW_ANDROID_DIR: out },
  })
  return { android, out, git, run }
}

describe('APK publication safeguards', () => {
  it('publishes a newer build with its clean source commit and permits identical retries', () => {
    const f = fixture()
    const result = f.run()
    assert.equal(result.status, 0, result.stderr)
    const manifest = JSON.parse(readFileSync(join(f.out, 'latest.json')))
    assert.equal(manifest.versionCode, 66)
    assert.equal(manifest.sourceCommit, f.git('rev-parse', 'HEAD'))
    assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'new APK bytes')
    assert.equal(f.run().status, 0)
  })

  it('refuses older or same-code different-byte builds before replacing either served file', () => {
    for (const have of [66, 67]) {
      const f = fixture({ have })
      const before = readFileSync(join(f.out, 'latest.json'), 'utf8')
      assert.notEqual(f.run().status, 0)
      assert.equal(readFileSync(join(f.out, 'latest.json'), 'utf8'), before)
      assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
    }
  })

  it('rejects a local release bump without deleting it or replacing the APK', () => {
    const f = fixture()
    writeFileSync(join(f.android, 'app/build.gradle.kts'), 'versionCode = 67\n')
    const result = f.run()
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /Refusing to publish tracked local edits/)
    assert.equal(readFileSync(join(f.android, 'app/build.gradle.kts'), 'utf8'), 'versionCode = 67\n')
    assert.equal(readFileSync(join(f.out, 'pulls.apk'), 'utf8'), 'previous APK bytes')
  })
})
